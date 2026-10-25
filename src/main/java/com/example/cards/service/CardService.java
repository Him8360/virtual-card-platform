package com.example.cards.service;

import com.example.cards.audit.AuditEvent;
import com.example.cards.config.AppProperties;
import com.example.cards.domain.Card;
import com.example.cards.domain.Money;
import com.example.cards.domain.Transaction;
import com.example.cards.domain.TransactionStatus;
import com.example.cards.domain.TransactionType;
import com.example.cards.exception.BusinessException;
import com.example.cards.exception.CardNotFoundException;
import com.example.cards.exception.CardNotUsableException;
import com.example.cards.repository.CardRepository;
import com.example.cards.repository.TransactionRepository;
import com.example.cards.web.dto.CardResponse;
import com.example.cards.web.dto.PageResponse;
import com.example.cards.web.dto.TransactionResponse;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Application service: orchestrates transactions, locking, idempotency, events and metrics.
 * Business rules about a single card live in the Card entity, not here.
 *
 * Concurrency model: every mutation of an existing card first takes SELECT ... FOR UPDATE on that card
 * row, so operations on the same card are serialised (across any number of app instances) while
 * different cards proceed fully in parallel.
 */
@Service
public class CardService {

    private static final Logger log = LoggerFactory.getLogger(CardService.class);
    private static final int CREATE_ATTEMPTS = 3;

    private final CardRepository cards;
    private final TransactionRepository transactions;
    private final IdempotencyStore idempotency;
    private final TransactionTemplate txTemplate;
    private final ApplicationEventPublisher events;
    private final CardMetrics metrics;
    private final Clock clock;
    private final AppProperties props;

    public CardService(CardRepository cards, TransactionRepository transactions, IdempotencyStore idempotency,
                       PlatformTransactionManager txManager, ApplicationEventPublisher events,
                       CardMetrics metrics, Clock clock, AppProperties props) {
        this.cards = cards;
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.txTemplate = new TransactionTemplate(txManager);
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
        this.props = props;
    }

    // ------------------------------------------------------------------ create

    /**
     * Not @Transactional on purpose: if two requests race on the same Idempotency-Key, the loser's
     * transaction fails with a constraint violation (and on PostgreSQL the transaction is then unusable).
     * So each attempt runs in its own fresh transaction, and the retry finds the winner's record and replays it.
     */
    public Outcome<CardResponse> createCard(String idempotencyKey, String cardholderName, BigDecimal initialBalance) {
        String name = cardholderName.trim();
        BigDecimal initial = Money.normalize(initialBalance);
        String hash = Hashing.sha256Hex("CREATE|" + name + "|" + initial.toPlainString());

        return instrumented("create", () -> {
            for (int attempt = 1; ; attempt++) {
                try {
                    return txTemplate.execute(status -> idempotency.execute(
                            "CREATE:" + idempotencyKey, hash, CardResponse.class, () -> issue(name, initial)));
                } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
                    if (attempt >= CREATE_ATTEMPTS) {
                        throw e;
                    }
                    log.debug("Idempotent create raced with another request (attempt {}), retrying", attempt);
                }
            }
        });
    }

    private Outcome<CardResponse> issue(String name, BigDecimal initial) {
        Instant now = now();
        Instant expiresAt = props.card().expirationDays() > 0
                ? now.plus(Duration.ofDays(props.card().expirationDays())) : null;

        Card card = cards.save(Card.issue(name, initial, now, expiresAt));
        Transaction tx = transactions.save(Transaction.of(card.getId(), TransactionType.ISSUE, initial,
                TransactionStatus.SUCCESSFUL, card.getBalance(), now));
        publish(tx, "CARD_ISSUED");
        log.info("Issued card id={} initialBalance={}", card.getId(), initial);
        return new Outcome<>(201, CardResponse.from(card, now), false);
    }

    // ------------------------------------------------------------------ spend / top-up

    @Transactional
    public Outcome<TransactionResponse> spend(UUID cardId, String idempotencyKey, BigDecimal amount) {
        return instrumented("spend", () -> apply(cardId, idempotencyKey, amount, TransactionType.SPEND));
    }

    @Transactional
    public Outcome<TransactionResponse> topUp(UUID cardId, String idempotencyKey, BigDecimal amount) {
        return instrumented("top_up", () -> apply(cardId, idempotencyKey, amount, TransactionType.TOP_UP));
    }

    private Outcome<TransactionResponse> apply(UUID cardId, String key, BigDecimal rawAmount, TransactionType type) {
        BigDecimal amount = Money.normalize(rawAmount);
        Card card = lockCard(cardId);                       // 404 if missing; serialises this card from here on
        String scope = type + ":" + cardId + ":" + key;
        String hash = Hashing.sha256Hex(type + "|" + cardId + "|" + amount.toPlainString());

        return idempotency.execute(scope, hash, TransactionResponse.class, () -> {
            Instant now = now();
            requireUsable(card, now);

            if (type == TransactionType.SPEND && !card.hasSufficientFunds(amount)) {
                Transaction declined = transactions.save(Transaction.of(cardId, type, amount,
                        TransactionStatus.DECLINED, card.getBalance(), now));
                publish(declined, "SPEND");
                log.warn("Declined spend cardId={} amount={} balance={}", cardId, amount, card.getBalance());
                return new Outcome<>(422, TransactionResponse.from(declined, "INSUFFICIENT_FUNDS"), false);
            }

            if (type == TransactionType.SPEND) {
                card.debit(amount);
            } else {
                card.credit(amount);
            }
            Transaction tx = transactions.save(Transaction.of(cardId, type, amount,
                    TransactionStatus.SUCCESSFUL, card.getBalance(), now));
            publish(tx, type.name());
            log.info("Applied {} cardId={} amount={} newBalance={}", type, cardId, amount, card.getBalance());
            return new Outcome<>(200, TransactionResponse.from(tx), false);
        });
    }

    // ------------------------------------------------------------------ lifecycle

    @Transactional
    public CardResponse block(UUID cardId) {
        return transition(cardId, "BLOCK", Card::block);
    }

    @Transactional
    public CardResponse unblock(UUID cardId) {
        return transition(cardId, "UNBLOCK", Card::unblock);
    }

    @Transactional
    public CardResponse close(UUID cardId) {
        return transition(cardId, "CLOSE", Card::close);
    }

    private CardResponse transition(UUID cardId, String action, Consumer<Card> change) {
        Card card = lockCard(cardId);
        var before = card.getStatus();
        change.accept(card);
        Instant now = now();
        if (before != card.getStatus()) {
            events.publishEvent(new AuditEvent(cardId, null, action, card.getStatus().name(), null, now));
            log.info("Card {} {} -> {}", cardId, before, card.getStatus());
        }
        return CardResponse.from(card, now);
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public CardResponse getCard(UUID cardId) {
        Card card = cards.findById(cardId).orElseThrow(() -> new CardNotFoundException(cardId));
        return CardResponse.from(card, now());
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> history(UUID cardId, int page, int size) {
        if (!cards.existsById(cardId)) {
            throw new CardNotFoundException(cardId);
        }
        // id as tiebreaker keeps page boundaries stable when two entries share a timestamp
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<Transaction> result = transactions.findByCardId(cardId, pageRequest);
        return PageResponse.from(result.map(TransactionResponse::from));
    }

    // ------------------------------------------------------------------ expiry

    /** Called by the scheduled job. Safe to run on several instances at once: it is one idempotent UPDATE. */
    @Transactional
    public int expireDueCards() {
        int closed = cards.closeExpired(now());
        if (closed > 0) {
            metrics.cardsExpired(closed);
            log.info("Closed {} expired card(s)", closed);
        }
        return closed;
    }

    // ------------------------------------------------------------------ helpers

    private Card lockCard(UUID cardId) {
        return cards.findByIdForUpdate(cardId).orElseThrow(() -> new CardNotFoundException(cardId));
    }

    private void requireUsable(Card card, Instant now) {
        if (!card.isUsable(now)) {
            throw new CardNotUsableException(card.getId(), card.effectiveStatus(now));
        }
    }

    private Instant now() {
        // Databases store microseconds; truncate so what we return equals what we persist.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private void publish(Transaction tx, String action) {
        events.publishEvent(new AuditEvent(tx.getCardId(), tx.getId(), action, tx.getStatus().name(),
                tx.getAmount(), tx.getCreatedAt()));
    }

    private <T> Outcome<T> instrumented(String operation, Supplier<Outcome<T>> body) {
        Timer.Sample sample = metrics.start();
        try {
            Outcome<T> outcome = body.get();
            String label = outcome.replayed() ? "replayed" : outcome.status() == 422 ? "declined" : "success";
            metrics.record(operation, label, sample);
            return outcome;
        } catch (BusinessException e) {
            metrics.record(operation, "rejected", sample);
            throw e;
        } catch (RuntimeException e) {
            metrics.record(operation, "error", sample);
            throw e;
        }
    }
}
