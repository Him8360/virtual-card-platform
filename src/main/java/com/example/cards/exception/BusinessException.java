package com.example.cards.exception;

import org.springframework.http.HttpStatus;

/** Base type for expected, client-visible failures. Mapped to RFC 7807 problem responses. */
public abstract class BusinessException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    protected BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }

    public String getCode() { return code; }
}
