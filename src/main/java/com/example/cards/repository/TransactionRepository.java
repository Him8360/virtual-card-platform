package com.example.cards.repository;

import com.example.cards.domain.Transaction;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    Page<Transaction> findByCardId(UUID cardId, Pageable pageable);
}
