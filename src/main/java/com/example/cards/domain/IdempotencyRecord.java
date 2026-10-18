package com.example.cards.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * Stores the response of a completed financial operation. The id has an assigned value, so we
 * implement Persistable to make Spring Data issue a plain INSERT (not SELECT + merge). A duplicate
 * key then surfaces immediately as a constraint violation, which is the race-safety mechanism.
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord implements Persistable<String> {

    @Id
    @Column(length = 300)
    private String id;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", nullable = false, length = 4000)
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean fresh = true;

    protected IdempotencyRecord() {
        // for JPA
    }

    public IdempotencyRecord(String id, String requestHash, int responseStatus, String responseBody, Instant createdAt) {
        this.id = id;
        this.requestHash = requestHash;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.createdAt = createdAt;
    }

    @Override
    public String getId() { return id; }

    @Override
    public boolean isNew() { return fresh; }

    @PostPersist
    @PostLoad
    void markNotNew() { this.fresh = false; }

    public String getRequestHash() { return requestHash; }

    public int getResponseStatus() { return responseStatus; }

    public String getResponseBody() { return responseBody; }

    public Instant getCreatedAt() { return createdAt; }
}
