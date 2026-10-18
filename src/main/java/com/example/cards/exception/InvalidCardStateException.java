package com.example.cards.exception;

import org.springframework.http.HttpStatus;

public class InvalidCardStateException extends BusinessException {
    public InvalidCardStateException(String message) {
        super(HttpStatus.CONFLICT, "INVALID_CARD_STATE", message);
    }
}
