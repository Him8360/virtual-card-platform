package com.example.cards.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Housekeeping only: correctness does NOT depend on this job, because spends and top-ups already reject
 * cards past their expiry instant. The job just moves them to CLOSED so stored state matches reality.
 */
@Component
@ConditionalOnProperty(name = "app.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class CardExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(CardExpiryJob.class);

    private final CardService service;

    public CardExpiryJob(CardService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.expiry.fixed-delay-ms:60000}")
    public void run() {
        try {
            service.expireDueCards();
        } catch (RuntimeException e) {
            log.error("Card expiry sweep failed; will retry on next run", e);
        }
    }
}
