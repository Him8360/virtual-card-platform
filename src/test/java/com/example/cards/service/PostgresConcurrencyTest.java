package com.example.cards.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cards.repository.CardRepository;
import com.example.cards.web.dto.CardResponse;
import com.example.cards.web.dto.TransactionResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Same guarantees, but against a real PostgreSQL (row locks, constraint-violation behaviour and the
 * Flyway migration all differ subtly from H2). Skipped automatically when Docker is not available;
 * the surefire report then shows these tests as SKIPPED, not passed.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PostgresConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired CardService service;
    @Autowired CardRepository cards;

    private static String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    void concurrentSpends_neverDriveBalanceBelowZero() throws Exception {
        UUID id = service.createCard(key(), "Alice", new BigDecimal("100.00")).body().id();

        List<Outcome<TransactionResponse>> results =
                Concurrently.run(40, i -> service.spend(id, key(), new BigDecimal("10.00")));

        assertThat(results.stream().filter(o -> o.status() == 200).count()).isEqualTo(10);
        assertThat(results.stream().filter(o -> o.status() == 422).count()).isEqualTo(30);
        assertThat(service.getCard(id).balance()).isEqualByComparingTo("0.00");
    }

    @Test
    void concurrentSameKeySpend_isAppliedOnce() throws Exception {
        UUID id = service.createCard(key(), "Alice", new BigDecimal("100.00")).body().id();
        String key = key();

        List<Outcome<TransactionResponse>> results =
                Concurrently.run(20, i -> service.spend(id, key, new BigDecimal("10.00")));

        assertThat(results.stream().filter(o -> !o.replayed()).count()).isEqualTo(1);
        assertThat(service.getCard(id).balance()).isEqualByComparingTo("90.00");
    }

    /** The race that needs the retry logic: on PostgreSQL the losing transaction is aborted by the constraint error. */
    @Test
    void concurrentSameKeyCreate_issuesExactlyOneCard() throws Exception {
        String key = key();
        long before = cards.count();

        List<Outcome<CardResponse>> results =
                Concurrently.run(20, i -> service.createCard(key, "Bob", new BigDecimal("25.00")));

        assertThat(results.stream().map(o -> o.body().id()).distinct().count()).isEqualTo(1);
        assertThat(results.stream().filter(o -> !o.replayed()).count()).isEqualTo(1);
        assertThat(cards.count()).isEqualTo(before + 1);
    }
}
