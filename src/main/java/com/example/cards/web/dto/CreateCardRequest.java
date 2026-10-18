package com.example.cards.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateCardRequest(
        @NotBlank @Size(max = 100) String cardholderName,
        @NotNull @DecimalMin("0.00") @DecimalMax("1000000.00") @Digits(integer = 7, fraction = 2)
        BigDecimal initialBalance) {
}
