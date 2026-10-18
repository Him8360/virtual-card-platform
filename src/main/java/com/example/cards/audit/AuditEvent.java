package com.example.cards.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Domain event published inside the business transaction, consumed after it commits. */
public record AuditEvent(UUID cardId, UUID transactionId, String action, String outcome,
                         BigDecimal amount, Instant occurredAt) {
}
