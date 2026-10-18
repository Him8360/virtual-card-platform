package com.example.cards.web.dto;

import com.example.cards.domain.Card;
import com.example.cards.domain.CardStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CardResponse(UUID id, String cardholderName, BigDecimal balance, CardStatus status,
                           Instant createdAt, Instant expiresAt) {

    /** Reports the effective status, so an expired-but-not-yet-swept card already shows as CLOSED. */
    public static CardResponse from(Card card, Instant now) {
        return new CardResponse(card.getId(), card.getCardholderName(), card.getBalance(),
                card.effectiveStatus(now), card.getCreatedAt(), card.getExpiresAt());
    }
}
