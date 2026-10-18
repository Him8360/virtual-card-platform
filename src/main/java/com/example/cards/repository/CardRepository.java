package com.example.cards.repository;

import com.example.cards.domain.Card;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardRepository extends JpaRepository<Card, UUID> {

    /** SELECT ... FOR UPDATE. Serialises every mutation of one card across all app instances. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Card c where c.id = :id")
    Optional<Card> findByIdForUpdate(@Param("id") UUID id);

    /** Single atomic statement; row locks make it queue behind any in-flight spend on the same card. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Card c set c.status = com.example.cards.domain.CardStatus.CLOSED "
            + "where c.status = com.example.cards.domain.CardStatus.ACTIVE "
            + "and c.expiresAt is not null and c.expiresAt <= :now")
    int closeExpired(@Param("now") Instant now);
}
