package com.example.cards.exception;

import com.example.cards.domain.CardStatus;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Raised when money movement is attempted on a card that is blocked, closed or expired. */
public class CardNotUsableException extends BusinessException {
    public CardNotUsableException(UUID id, CardStatus effectiveStatus) {
        super(HttpStatus.CONFLICT, "CARD_NOT_ACTIVE",
                "Card " + id + " is " + effectiveStatus + "; only ACTIVE, non-expired cards accept spends and top-ups");
    }
}
