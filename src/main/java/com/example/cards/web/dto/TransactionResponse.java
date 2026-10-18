package com.example.cards.web.dto;

import com.example.cards.domain.Transaction;
import com.example.cards.domain.TransactionStatus;
import com.example.cards.domain.TransactionType;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionResponse(UUID id, UUID cardId, TransactionType type, BigDecimal amount,
                                  TransactionStatus status, BigDecimal balanceAfter, Instant createdAt,
                                  String reason) {

    public static TransactionResponse from(Transaction tx) {
        return from(tx, null);
    }

    /** reason is only populated on the response to the failed request itself (e.g. INSUFFICIENT_FUNDS). */
    public static TransactionResponse from(Transaction tx, String reason) {
        return new TransactionResponse(tx.getId(), tx.getCardId(), tx.getType(), tx.getAmount(),
                tx.getStatus(), tx.getBalanceAfter(), tx.getCreatedAt(), reason);
    }
}
