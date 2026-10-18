package com.example.cards.web;

import com.example.cards.exception.InvalidRequestException;
import java.util.regex.Pattern;

final class IdempotencyKeys {
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private IdempotencyKeys() {}

    static String validate(String key) {
        if (key == null || !VALID.matcher(key).matches()) {
            throw new InvalidRequestException(
                    "Idempotency-Key must be 1-128 characters from [A-Za-z0-9._:-]");
        }
        return key;
    }
}
