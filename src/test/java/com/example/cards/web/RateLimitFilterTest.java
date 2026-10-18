package com.example.cards.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Own Spring context (different properties) so the tiny bucket does not affect other test classes. */
@SpringBootTest(properties = {"app.rate-limit.capacity=3", "app.rate-limit.refill-per-second=0.001"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitFilterTest {

    @Autowired MockMvc mvc;

    @Test
    void clientThatExceedsItsBudget_gets429_withRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/cards/" + UUID.randomUUID())).andExpect(status().isNotFound());
        }

        mvc.perform(get("/api/v1/cards/" + UUID.randomUUID()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
