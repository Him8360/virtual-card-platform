package com.example.cards.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Black-box tests of the HTTP contract: status codes, validation, error shape, headers. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CardApiTest {

    @Autowired MockMvc mvc;

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private ResultActions postJson(String url, String key, String json) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON).content(json);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private String createCard(String balance) throws Exception {
        String body = postJson("/api/v1/cards", key(),
                "{\"cardholderName\":\"Alice\",\"initialBalance\":" + balance + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    // ---------------------------------------------------------------- create / read

    @Test
    void createCard_returns201_withLocation_andCanBeRetrieved() throws Exception {
        String id = createCard("100.00");

        mvc.perform(get("/api/v1/cards/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.cardholderName").value("Alice"))
                .andExpect(jsonPath("$.balance").value(100.0))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void createCard_withSameKey_returnsSameCard_andReplayHeader() throws Exception {
        String key = key();
        String json = "{\"cardholderName\":\"Alice\",\"initialBalance\":10.00}";

        String first = postJson("/api/v1/cards", key, json).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        postJson("/api/v1/cards", key, json)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value((String) JsonPath.read(first, "$.id")));
    }

    @Test
    void createCard_withoutIdempotencyKey_isBadRequest() throws Exception {
        postJson("/api/v1/cards", null, "{\"cardholderName\":\"Alice\",\"initialBalance\":10.00}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void createCard_withMalformedIdempotencyKey_isBadRequest() throws Exception {
        postJson("/api/v1/cards", "has spaces!", "{\"cardholderName\":\"Alice\",\"initialBalance\":10.00}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void createCard_validation() throws Exception {
        postJson("/api/v1/cards", key(), "{\"cardholderName\":\"\",\"initialBalance\":10.00}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.cardholderName").exists());

        postJson("/api/v1/cards", key(), "{\"cardholderName\":\"A\",\"initialBalance\":-1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.initialBalance").exists());

        postJson("/api/v1/cards", key(), "{\"cardholderName\":\"A\",\"initialBalance\":1.001}")
                .andExpect(status().isBadRequest());

        postJson("/api/v1/cards", key(), "{\"cardholderName\":\"A\"}")
                .andExpect(status().isBadRequest());

        postJson("/api/v1/cards", key(), "{not json")
                .andExpect(status().isBadRequest());
    }

    @Test
    void getCard_unknown_is404_withProblemBody() throws Exception {
        mvc.perform(get("/api/v1/cards/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CARD_NOT_FOUND"));
    }

    @Test
    void getCard_withMalformedId_is400() throws Exception {
        mvc.perform(get("/api/v1/cards/not-a-uuid")).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- spend / top-up

    @Test
    void spend_andTopUp_updateBalance() throws Exception {
        String id = createCard("100.00");

        postJson("/api/v1/cards/" + id + "/spend", key(), "{\"amount\":30.00}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESSFUL"))
                .andExpect(jsonPath("$.type").value("SPEND"))
                .andExpect(jsonPath("$.balanceAfter").value(70.0));

        postJson("/api/v1/cards/" + id + "/top-up", key(), "{\"amount\":5.50}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("TOP_UP"))
                .andExpect(jsonPath("$.balanceAfter").value(75.5));

        mvc.perform(get("/api/v1/cards/" + id)).andExpect(jsonPath("$.balance").value(75.5));
    }

    @Test
    void spend_withInsufficientFunds_returns422_declined_andBalanceUnchanged() throws Exception {
        String id = createCard("10.00");

        postJson("/api/v1/cards/" + id + "/spend", key(), "{\"amount\":10.01}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_FUNDS"));

        mvc.perform(get("/api/v1/cards/" + id)).andExpect(jsonPath("$.balance").value(10.0));
    }

    @Test
    void spend_isIdempotent_andMarksReplay() throws Exception {
        String id = createCard("100.00");
        String key = key();

        postJson("/api/v1/cards/" + id + "/spend", key, "{\"amount\":40.00}")
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        postJson("/api/v1/cards/" + id + "/spend", key, "{\"amount\":40.00}")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        mvc.perform(get("/api/v1/cards/" + id)).andExpect(jsonPath("$.balance").value(60.0));
    }

    @Test
    void spend_sameKeyDifferentBody_is422() throws Exception {
        String id = createCard("100.00");
        String key = key();
        postJson("/api/v1/cards/" + id + "/spend", key, "{\"amount\":10.00}").andExpect(status().isOk());

        postJson("/api/v1/cards/" + id + "/spend", key, "{\"amount\":11.00}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void spend_withoutKey_is400() throws Exception {
        String id = createCard("10.00");
        postJson("/api/v1/cards/" + id + "/spend", null, "{\"amount\":1.00}").andExpect(status().isBadRequest());
    }

    @Test
    void amountValidation() throws Exception {
        String id = createCard("10.00");
        for (String bad : new String[] {"0", "-5", "0.001", "1000000.01", "99999999999999999999", "null"}) {
            postJson("/api/v1/cards/" + id + "/spend", key(), "{\"amount\":" + bad + "}")
                    .andExpect(status().isBadRequest());
            postJson("/api/v1/cards/" + id + "/top-up", key(), "{\"amount\":" + bad + "}")
                    .andExpect(status().isBadRequest());
        }
        postJson("/api/v1/cards/" + id + "/spend", key(), "{}").andExpect(status().isBadRequest());
    }

    @Test
    void spend_onUnknownCard_is404() throws Exception {
        postJson("/api/v1/cards/" + UUID.randomUUID() + "/spend", key(), "{\"amount\":1.00}")
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    void blockedCard_rejectsMoneyMovement_with409_untilUnblocked() throws Exception {
        String id = createCard("50.00");

        mvc.perform(post("/api/v1/cards/" + id + "/block"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));

        postJson("/api/v1/cards/" + id + "/spend", key(), "{\"amount\":1.00}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CARD_NOT_ACTIVE"));
        postJson("/api/v1/cards/" + id + "/top-up", key(), "{\"amount\":1.00}")
                .andExpect(status().isConflict());

        mvc.perform(post("/api/v1/cards/" + id + "/unblock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        postJson("/api/v1/cards/" + id + "/spend", key(), "{\"amount\":1.00}").andExpect(status().isOk());
    }

    @Test
    void closedCard_isTerminal() throws Exception {
        String id = createCard("50.00");

        mvc.perform(post("/api/v1/cards/" + id + "/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        postJson("/api/v1/cards/" + id + "/spend", key(), "{\"amount\":1.00}").andExpect(status().isConflict());
        mvc.perform(post("/api/v1/cards/" + id + "/unblock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CARD_STATE"));
    }

    // ---------------------------------------------------------------- history

    @Test
    void history_isPaged() throws Exception {
        String id = createCard("0.00");
        for (int i = 0; i < 4; i++) {
            postJson("/api/v1/cards/" + id + "/top-up", key(), "{\"amount\":1.00}").andExpect(status().isOk());
        }

        mvc.perform(get("/api/v1/cards/" + id + "/transactions?page=0&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items[0].cardId").value(id));
    }

    @Test
    void history_rejectsInvalidPaging() throws Exception {
        String id = createCard("1.00");
        mvc.perform(get("/api/v1/cards/" + id + "/transactions?size=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/cards/" + id + "/transactions?size=101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/cards/" + id + "/transactions?page=-1")).andExpect(status().isBadRequest());
    }

    @Test
    void history_unknownCard_is404() throws Exception {
        mvc.perform(get("/api/v1/cards/" + UUID.randomUUID() + "/transactions")).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- observability

    @Test
    void everyResponse_carriesARequestId() throws Exception {
        mvc.perform(get("/api/v1/cards/" + UUID.randomUUID()))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void metricsAreExposed() throws Exception {
        String id = createCard("10.00");
        postJson("/api/v1/cards/" + id + "/spend", key(), "{\"amount\":1.00}").andExpect(status().isOk());

        mvc.perform(get("/actuator/metrics/cards.operations")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
