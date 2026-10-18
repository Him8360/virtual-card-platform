package com.example.cards.exception;

import org.springframework.http.HttpStatus;

public class InvalidRequestException extends BusinessException {
    public InvalidRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }
}
