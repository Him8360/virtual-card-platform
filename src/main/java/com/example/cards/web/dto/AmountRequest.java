package com.example.cards.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Used for both spend and top-up. At most 2 decimals, between 0.01 and 1,000,000.00 per operation. */
public record AmountRequest(
        @NotNull @DecimalMin("0.01") @DecimalMax("1000000.00") @Digits(integer = 7, fraction = 2)
        BigDecimal amount) {
}
