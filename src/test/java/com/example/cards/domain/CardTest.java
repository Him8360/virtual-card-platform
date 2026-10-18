package com.example.cards.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.cards.exception.InvalidCardStateException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Pure unit tests: no Spring, no database. */
class CardTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private Card card(String balance) {
        return Card.issue("Alice", new BigDecimal(balance), NOW, null);
    }

    @Test
    void issue_createsActiveCard_withScaleTwoBalance() {
        Card card = card("10");

        assertThat(card.getStatus()).isEqualTo(CardStatus.ACTIVE);
        assertThat(card.getBalance()).isEqualTo(new BigDecimal("10.00"));
    }

    @Test
    void debit_reducesBalance() {
        Card card = card("50.00");
        card.debit(new BigDecimal("20.25"));
        assertThat(card.getBalance()).isEqualTo(new BigDecimal("29.75"));
    }

    @Test
    void debit_canDrainBalanceToExactlyZero() {
        Card card = card("10.00");
        card.debit(new BigDecimal("10.00"));
        assertThat(card.getBalance()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void debit_neverAllowsNegativeBalance() {
        Card card = card("10.00");

        assertThatThrownBy(() -> card.debit(new BigDecimal("10.01"))).isInstanceOf(IllegalStateException.class);
        assertThat(card.getBalance()).isEqualTo(new BigDecimal("10.00"));
    }

    @Test
    void hasSufficientFunds_isInclusiveOfExactBalance() {
        Card card = card("10.00");
        assertThat(card.hasSufficientFunds(new BigDecimal("10.00"))).isTrue();
        assertThat(card.hasSufficientFunds(new BigDecimal("10.01"))).isFalse();
    }

    @Test
    void credit_increasesBalance() {
        Card card = card("10.00");
        card.credit(new BigDecimal("5.50"));
        assertThat(card.getBalance()).isEqualTo(new BigDecimal("15.50"));
    }

    @Test
    void blockAndUnblock_toggleStatus_andAreIdempotent() {
        Card card = card("1.00");

        card.block();
        card.block();
        assertThat(card.getStatus()).isEqualTo(CardStatus.BLOCKED);
        assertThat(card.isUsable(NOW)).isFalse();

        card.unblock();
        card.unblock();
        assertThat(card.getStatus()).isEqualTo(CardStatus.ACTIVE);
        assertThat(card.isUsable(NOW)).isTrue();
    }

    @Test
    void closed_isTerminal() {
        Card card = card("1.00");
        card.close();

        assertThat(card.isUsable(NOW)).isFalse();
        assertThatThrownBy(card::block).isInstanceOf(InvalidCardStateException.class);
        assertThatThrownBy(card::unblock).isInstanceOf(InvalidCardStateException.class);
    }

    @Test
    void expiredCard_isNotUsable_andReportsClosed() {
        Card card = Card.issue("Alice", new BigDecimal("5.00"), NOW, NOW.plusSeconds(60));

        assertThat(card.isUsable(NOW.plusSeconds(59))).isTrue();
        assertThat(card.isUsable(NOW.plusSeconds(60))).isFalse();   // boundary: expiry instant itself is expired
        assertThat(card.effectiveStatus(NOW.plusSeconds(61))).isEqualTo(CardStatus.CLOSED);
        assertThat(card.getStatus()).isEqualTo(CardStatus.ACTIVE);  // stored state untouched until the sweep
    }

    @Test
    void cardWithoutExpiry_neverExpires() {
        assertThat(card("1.00").isUsable(NOW.plusSeconds(10L * 365 * 24 * 3600))).isTrue();
    }
}
