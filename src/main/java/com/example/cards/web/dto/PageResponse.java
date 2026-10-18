package com.example.cards.web.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/** Stable, explicit paging contract (instead of leaking Spring Data's Page JSON). */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
