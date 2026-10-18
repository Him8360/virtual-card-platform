package com.example.cards.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** All money is BigDecimal with scale 2. Input is validated to at most 2 decimals at the API edge. */
public final class Money {
    private Money() {}

    public static BigDecimal normalize(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
}
