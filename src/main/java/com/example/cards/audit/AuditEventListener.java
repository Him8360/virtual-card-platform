package com.example.cards.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Non-critical workflow handled off the request path. AFTER_COMMIT means we never audit something that
 * was rolled back. Today it writes to a dedicated "AUDIT" logger; swapping in Kafka or an outbox table
 * only touches this class.
 */
@Component
public class AuditEventListener {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    @Async("auditExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(AuditEvent event) {
        AUDIT.info("action={} outcome={} cardId={} txId={} amount={} at={}",
                event.action(), event.outcome(), event.cardId(), event.transactionId(),
                event.amount(), event.occurredAt());
    }
}
