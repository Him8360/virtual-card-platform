package com.example.cards.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.cards.audit.AuditEvent;
import com.example.cards.domain.CardStatus;
import com.example.cards.domain.TransactionStatus;
import com.example.cards.domain.TransactionType;
import com.example.cards.exception.CardNotFoundException;
import com.example.cards.exception.CardNotUsableException;
import com.example.cards.exception.IdempotencyKeyReuseException;
import com.example.cards.exception.InvalidCardStateException;
import com.example.cards.repository.CardRepository;
import com.example.cards.repository.TransactionRepository;
import com.example.cards.web.dto.CardResponse;
import com.example.cards.web.dto.TransactionResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@SpringBootTest
@ActiveProfiles("test")
@RecordApplicationEvents
class CardServiceIntegrationTest {

    @Autowired CardService service;
    @Autowired CardRepository cards;
    @Autowired TransactionRepository transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEvents applicationEvents;

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static BigDecimal money(String v) {
        return new BigDecimal(v);
    }

    private UUID newCard(String balance) {
        return service.createCard(key(), "Alice", money(balance)).body().id();
    }

    // ------------------------------------------------------------ create

    @Test
    void createCard_returnsActiveCard_andRecordsIssueTransaction() {
        Outcome<CardResponse> outcome = service.createCard(key(), "  Alice  ", money("100.00"));

        assertThat(outcome.status()).isEqualTo(201);
        assertThat(outcome.replayed()).isFalse();
        CardResponse card = outcome.body();
        assertThat(card.cardholderName()).isEqualTo("Alice");
        assertThat(card.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(card.balance()).isEqualByComparingTo("100.00");
        assertThat(card.expiresAt()).isNotNull();

        var history = service.history(card.id(), 0, 10);
        assertThat(history.items()).hasSize(1);
        assertThat(history.items().get(0).type()).isEqualTo(TransactionType.ISSUE);
        assertThat(history.items().get(0).status()).isEqualTo(TransactionStatus.SUCCESSFUL);
    }

    @Test
    void createCard_sameKeySamePayload_replaysSameCard() {
        String key = key();
        long before = cards.count();

        Outcome<CardResponse> first = service.createCard(key, "Alice", money("10.00"));
        Outcome<CardResponse> second = service.createCard(key, "Alice", money("10.00"));

        assertThat(second.replayed()).isTrue();
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body().id()).isEqualTo(first.body().id());
        assertThat(cards.count()).isEqualTo(before + 1);
    }

    @Test
    void createCard_sameKeyDifferentPayload_isRejected() {
        String key = key();
        service.createCard(key, "Alice", money("10.00"));

        assertThatThrownBy(() -> service.createCard(key, "Alice", money("11.00")))
                .isInstanceOf(IdempotencyKeyReuseException.class);
    }

    // ------------------------------------------------------------ spend / top-up

    @Test
    void spend_reducesBalance_andTopUpIncreasesIt() {
        UUID id = newCard("100.00");

        Outcome<TransactionResponse> spend = service.spend(id, key(), money("30.00"));
        assertThat(spend.status()).isEqualTo(200);
        assertThat(spend.body().status()).isEqualTo(TransactionStatus.SUCCESSFUL);
        assertThat(spend.body().balanceAfter()).isEqualByComparingTo("70.00");

        Outcome<TransactionResponse> topUp = service.topUp(id, key(), money("5.50"));
        assertThat(topUp.body().balanceAfter()).isEqualByComparingTo("75.50");
        assertThat(service.getCard(id).balance()).isEqualByComparingTo("75.50");
    }

    @Test
    void spend_exactlyTheWholeBalance_isAllowed() {
        UUID id = newCard("25.00");

        Outcome<TransactionResponse> outcome = service.spend(id, key(), money("25.00"));

        assertThat(outcome.status()).isEqualTo(200);
        assertThat(service.getCard(id).balance()).isEqualByComparingTo("0.00");
    }

    @Test
    void spend_withInsufficientFunds_isDeclined_recorded_andBalanceUnchanged() {
        UUID id = newCard("10.00");

        Outcome<TransactionResponse> outcome = service.spend(id, key(), money("10.01"));

        assertThat(outcome.status()).isEqualTo(422);
        assertThat(outcome.body().status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(outcome.body().reason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(service.getCard(id).balance()).isEqualByComparingTo("10.00");

        var history = service.history(id, 0, 10);
        assertThat(history.items()).extracting(TransactionResponse::status)
                .containsExactlyInAnyOrder(TransactionStatus.DECLINED, TransactionStatus.SUCCESSFUL);
    }

    @Test
    void spend_sameKeySamePayload_isAppliedOnce_andReplayed() {
        UUID id = newCard("100.00");
        String key = key();

        Outcome<TransactionResponse> first = service.spend(id, key, money("40.00"));
        Outcome<TransactionResponse> second = service.spend(id, key, money("40.00"));

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.body().id()).isEqualTo(first.body().id());
        assertThat(service.getCard(id).balance()).isEqualByComparingTo("60.00");
        assertThat(service.history(id, 0, 10).totalElements()).isEqualTo(2);   // ISSUE + one SPEND
    }

    @Test
    void topUp_sameKey_isAppliedOnce() {
        UUID id = newCard("0.00");
        String key = key();

        service.topUp(id, key, money("10.00"));
        service.topUp(id, key, money("10.00"));

        assertThat(service.getCard(id).balance()).isEqualByComparingTo("10.00");
    }

    @Test
    void declinedSpend_isAlsoReplayedForSameKey_evenAfterTopUp() {
        UUID id = newCard("5.00");
        String key = key();
        assertThat(service.spend(id, key, money("20.00")).status()).isEqualTo(422);

        service.topUp(id, key(), money("100.00"));
        Outcome<TransactionResponse> retry = service.spend(id, key, money("20.00"));

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.status()).isEqualTo(422);          // same key => same answer; use a new key to try again
        assertThat(service.getCard(id).balance()).isEqualByComparingTo("105.00");
    }

    @Test
    void sameKeyDifferentAmount_isRejected() {
        UUID id = newCard("100.00");
        String key = key();
        service.spend(id, key, money("10.00"));

        assertThatThrownBy(() -> service.spend(id, key, money("11.00")))
                .isInstanceOf(IdempotencyKeyReuseException.class);
    }

    @Test
    void sameKeyOnDifferentCards_areIndependent() {
        UUID a = newCard("50.00");
        UUID b = newCard("50.00");
        String key = key();

        service.spend(a, key, money("10.00"));
        Outcome<TransactionResponse> onB = service.spend(b, key, money("10.00"));

        assertThat(onB.replayed()).isFalse();
        assertThat(service.getCard(b).balance()).isEqualByComparingTo("40.00");
    }

    @Test
    void unknownCard_isNotFound() {
        assertThatThrownBy(() -> service.spend(UUID.randomUUID(), key(), money("1.00")))
                .isInstanceOf(CardNotFoundException.class);
        assertThatThrownBy(() -> service.getCard(UUID.randomUUID())).isInstanceOf(CardNotFoundException.class);
        assertThatThrownBy(() -> service.history(UUID.randomUUID(), 0, 10)).isInstanceOf(CardNotFoundException.class);
    }

    // ------------------------------------------------------------ card state rules

    @Test
    void blockedCard_rejectsSpendAndTopUp_untilUnblocked() {
        UUID id = newCard("100.00");
        service.block(id);

        assertThatThrownBy(() -> service.spend(id, key(), money("1.00"))).isInstanceOf(CardNotUsableException.class);
        assertThatThrownBy(() -> service.topUp(id, key(), money("1.00"))).isInstanceOf(CardNotUsableException.class);
        assertThat(service.getCard(id).balance()).isEqualByComparingTo("100.00");

        service.unblock(id);
        assertThat(service.spend(id, key(), money("1.00")).status()).isEqualTo(200);
    }

    @Test
    void closedCard_rejectsMoneyMovement_andCannotBeReopened() {
        UUID id = newCard("100.00");
        service.close(id);

        assertThatThrownBy(() -> service.spend(id, key(), money("1.00"))).isInstanceOf(CardNotUsableException.class);
        assertThatThrownBy(() -> service.topUp(id, key(), money("1.00"))).isInstanceOf(CardNotUsableException.class);
        assertThatThrownBy(() -> service.unblock(id)).isInstanceOf(InvalidCardStateException.class);
    }

    @Test
    void replayOfEarlierSuccess_stillWorks_afterCardIsBlocked() {
        UUID id = newCard("100.00");
        String key = key();
        service.spend(id, key, money("10.00"));
        service.block(id);

        Outcome<TransactionResponse> replay = service.spend(id, key, money("10.00"));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo(200);
    }

    @Test
    void expiredCard_rejectsSpend_evenBeforeTheSweepRuns() {
        UUID id = newCard("100.00");
        expireNow(id);

        assertThatThrownBy(() -> service.spend(id, key(), money("1.00"))).isInstanceOf(CardNotUsableException.class);
        assertThatThrownBy(() -> service.topUp(id, key(), money("1.00"))).isInstanceOf(CardNotUsableException.class);
        assertThat(service.getCard(id).status()).isEqualTo(CardStatus.CLOSED);   // effective status
    }

    @Test
    void expireDueCards_closesOnlyExpiredActiveCards() {
        UUID expired = newCard("10.00");
        UUID fresh = newCard("10.00");
        expireNow(expired);

        int closed = service.expireDueCards();

        assertThat(closed).isGreaterThanOrEqualTo(1);
        assertThat(cards.findById(expired).orElseThrow().getStatus()).isEqualTo(CardStatus.CLOSED);
        assertThat(cards.findById(fresh).orElseThrow().getStatus()).isEqualTo(CardStatus.ACTIVE);
    }

    // ------------------------------------------------------------ history & events

    @Test
    void history_isPaged_newestFirst() {
        UUID id = newCard("0.00");
        for (int i = 1; i <= 4; i++) {
            service.topUp(id, key(), money(i + ".00"));
        }

        var page0 = service.history(id, 0, 2);
        var page2 = service.history(id, 2, 2);

        assertThat(page0.totalElements()).isEqualTo(5);
        assertThat(page0.totalPages()).isEqualTo(3);
        assertThat(page0.items()).hasSize(2);
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.items().get(0).type()).isEqualTo(TransactionType.ISSUE);   // oldest is last
    }

    @Test
    void everyTransaction_publishesAnAuditEvent() {
        long before = applicationEvents.stream(AuditEvent.class).count();
        UUID id = newCard("10.00");
        service.spend(id, key(), money("1.00"));
        service.spend(id, key(), money("1000.00"));     // declined, still audited

        assertThat(applicationEvents.stream(AuditEvent.class).count() - before).isEqualTo(3);
    }

    // ------------------------------------------------------------ concurrency

    @Test
    void concurrentSpends_neverDriveBalanceBelowZero() throws Exception {
        UUID id = newCard("100.00");

        List<Outcome<TransactionResponse>> results =
                Concurrently.run(40, i -> service.spend(id, key(), money("10.00")));

        assertThat(results.stream().filter(o -> o.status() == 200).count()).isEqualTo(10);
        assertThat(results.stream().filter(o -> o.status() == 422).count()).isEqualTo(30);
        assertThat(service.getCard(id).balance()).isEqualByComparingTo("0.00");
    }

    @Test
    void concurrentRequestsWithSameKey_applyTheSpendExactlyOnce() throws Exception {
        UUID id = newCard("100.00");
        String key = key();

        List<Outcome<TransactionResponse>> results =
                Concurrently.run(20, i -> service.spend(id, key, money("10.00")));

        assertThat(results).allMatch(o -> o.status() == 200);
        assertThat(results.stream().filter(o -> !o.replayed()).count()).isEqualTo(1);
        assertThat(results.stream().map(o -> o.body().id()).distinct().count()).isEqualTo(1);
        assertThat(service.getCard(id).balance()).isEqualByComparingTo("90.00");
    }

    @Test
    void concurrentCreatesWithSameKey_issueExactlyOneCard() throws Exception {
        String key = key();
        long before = cards.count();

        List<Outcome<CardResponse>> results =
                Concurrently.run(20, i -> service.createCard(key, "Bob", money("25.00")));

        assertThat(results.stream().map(o -> o.body().id()).distinct().count()).isEqualTo(1);
        assertThat(results.stream().filter(o -> !o.replayed()).count()).isEqualTo(1);
        assertThat(cards.count()).isEqualTo(before + 1);
    }

    @Test
    void concurrentTopUpsAndSpends_leaveBalanceEqualToLedger() throws Exception {
        UUID id = newCard("1000.00");

        Concurrently.run(40, i -> i % 2 == 0
                ? service.topUp(id, key(), money("5.00"))
                : service.spend(id, key(), money("5.00")));

        BigDecimal ledger = BigDecimal.ZERO;
        for (var tx : transactions.findByCardId(id, Pageable.unpaged())) {
            if (tx.getStatus() != TransactionStatus.SUCCESSFUL) {
                continue;
            }
            ledger = switch (tx.getType()) {
                case SPEND -> ledger.subtract(tx.getAmount());
                default -> ledger.add(tx.getAmount());
            };
        }
        assertThat(service.getCard(id).balance()).isEqualByComparingTo(ledger);
        assertThat(service.getCard(id).balance()).isEqualByComparingTo("1000.00");
    }

    // ------------------------------------------------------------ helpers

    private void expireNow(UUID id) {
        Instant past = Instant.now().minusSeconds(5);
        jdbc.update("update cards set expires_at = ? where id = ?",
                OffsetDateTime.ofInstant(past, ZoneOffset.UTC), id);
    }
}
