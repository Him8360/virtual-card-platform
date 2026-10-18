package com.example.cards.service;

import com.example.cards.domain.IdempotencyRecord;
import com.example.cards.exception.IdempotencyKeyReuseException;
import com.example.cards.repository.IdempotencyRecordRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Runs an action at most once per idempotency scope and replays the stored response afterwards.
 * Must be called inside the caller's transaction so the business change and the record commit together.
 *
 * Race safety comes from two places, depending on the operation:
 *  - spend / top-up: the caller already holds the card row lock, so same-key requests are serialised.
 *  - create card: there is no row to lock yet, so the primary key of idempotency_records decides the
 *    winner; the loser fails with a constraint violation and the caller retries (and then replays).
 */
@Component
public class IdempotencyStore {

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyStore(IdempotencyRecordRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public <T> Outcome<T> execute(String scope, String requestHash, Class<T> bodyType, Supplier<Outcome<T>> action) {
        Optional<IdempotencyRecord> existing = repository.findById(scope);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (!record.getRequestHash().equals(requestHash)) {
                throw new IdempotencyKeyReuseException();
            }
            return new Outcome<>(record.getResponseStatus(), read(record.getResponseBody(), bodyType), true);
        }

        Outcome<T> outcome = action.get();
        // saveAndFlush so a duplicate-key race surfaces here, inside the transaction, not at commit.
        repository.saveAndFlush(new IdempotencyRecord(scope, requestHash, outcome.status(),
                write(outcome.body()), clock.instant().truncatedTo(ChronoUnit.MICROS)));
        return outcome;
    }

    private String write(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise idempotent response", e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialise stored idempotent response", e);
        }
    }
}
