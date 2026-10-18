package com.example.cards.domain;

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

/** Immutable ledger entry. Declined attempts are recorded too, so history shows what was tried. */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "card_id", nullable = false, updatable = false)
    private UUID cardId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tx_type", nullable = false, updatable = false, length = 16)
    private TransactionType type;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "tx_status", nullable = false, updatable = false, length = 16)
    private TransactionStatus status;

    @Column(name = "balance_after", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Transaction() {
        // for JPA
    }

    public static Transaction of(UUID cardId, TransactionType type, BigDecimal amount,
                                 TransactionStatus status, BigDecimal balanceAfter, Instant now) {
        Transaction tx = new Transaction();
        tx.cardId = cardId;
        tx.type = type;
        tx.amount = Money.normalize(amount);
        tx.status = status;
        tx.balanceAfter = Money.normalize(balanceAfter);
        tx.createdAt = now;
        return tx;
    }

    public UUID getId() { return id; }

    public UUID getCardId() { return cardId; }

    public TransactionType getType() { return type; }

    public BigDecimal getAmount() { return amount; }

    public TransactionStatus getStatus() { return status; }

    public BigDecimal getBalanceAfter() { return balanceAfter; }

    public Instant getCreatedAt() { return createdAt; }
}
