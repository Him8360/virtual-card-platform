package com.example.cards.domain;

import com.example.cards.exception.InvalidCardStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate root. All business rules about a single card live here so they can be unit tested
 * without Spring or a database. Callers must hold the row lock (see CardRepository#findByIdForUpdate)
 * before mutating a card.
 */
@Entity
@Table(name = "cards")
public class Card {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cardholder_name", nullable = false, length = 100)
    private String cardholderName;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CardStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected Card() {
        // for JPA
    }

    public static Card issue(String cardholderName, BigDecimal initialBalance, Instant now, Instant expiresAt) {
        Card card = new Card();
        card.cardholderName = cardholderName;
        card.balance = Money.normalize(initialBalance);
        card.status = CardStatus.ACTIVE;
        card.createdAt = now;
        card.expiresAt = expiresAt;
        return card;
    }

    /** True when the card may move money: status ACTIVE and not past its expiry instant. */
    public boolean isUsable(Instant now) {
        return status == CardStatus.ACTIVE && !isExpired(now);
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    /** Status as clients should see it: an ACTIVE card past expiry is effectively CLOSED. */
    public CardStatus effectiveStatus(Instant now) {
        return status == CardStatus.ACTIVE && isExpired(now) ? CardStatus.CLOSED : status;
    }

    public boolean hasSufficientFunds(BigDecimal amount) {
        return balance.compareTo(Money.normalize(amount)) >= 0;
    }

    public void debit(BigDecimal amount) {
        BigDecimal value = Money.normalize(amount);
        if (balance.compareTo(value) < 0) {
            throw new IllegalStateException("Debit would make balance negative");
        }
        balance = balance.subtract(value);
    }

    public void credit(BigDecimal amount) {
        balance = balance.add(Money.normalize(amount));
    }

    /** ACTIVE -> BLOCKED. Blocking a blocked card is a no-op; CLOSED is terminal. */
    public void block() {
        if (status == CardStatus.BLOCKED) {
            return;
        }
        requireNotClosed("block");
        status = CardStatus.BLOCKED;
    }

    /** BLOCKED -> ACTIVE. Unblocking an active card is a no-op; CLOSED is terminal. */
    public void unblock() {
        if (status == CardStatus.ACTIVE) {
            return;
        }
        requireNotClosed("unblock");
        status = CardStatus.ACTIVE;
    }

    /** Any state -> CLOSED (terminal). */
    public void close() {
        status = CardStatus.CLOSED;
    }

    private void requireNotClosed(String action) {
        if (status == CardStatus.CLOSED) {
            throw new InvalidCardStateException("Cannot " + action + " card " + id + ": it is CLOSED");
        }
    }

    public UUID getId() { return id; }

    public String getCardholderName() { return cardholderName; }

    public BigDecimal getBalance() { return balance; }

    public CardStatus getStatus() { return status; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
}
