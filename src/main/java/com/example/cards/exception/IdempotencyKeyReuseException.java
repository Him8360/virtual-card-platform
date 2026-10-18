package com.example.cards.exception;

import org.springframework.http.HttpStatus;

/** The same Idempotency-Key was sent with a different request payload. */
public class IdempotencyKeyReuseException extends BusinessException {
    public IdempotencyKeyReuseException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                "This Idempotency-Key was already used with a different request");
    }
}
