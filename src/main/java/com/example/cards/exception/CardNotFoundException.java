package com.example.cards.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class CardNotFoundException extends BusinessException {
    public CardNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", "Card " + id + " does not exist");
    }
}
