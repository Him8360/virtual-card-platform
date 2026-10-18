package com.example.cards.web;

import com.example.cards.exception.InvalidRequestException;
import com.example.cards.service.CardService;
import com.example.cards.service.Outcome;
import com.example.cards.web.dto.AmountRequest;
import com.example.cards.web.dto.CardResponse;
import com.example.cards.web.dto.CreateCardRequest;
import com.example.cards.web.dto.PageResponse;
import com.example.cards.web.dto.TransactionResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter only: parse, validate, delegate, map the result. No business logic here. */
@RestController
@RequestMapping("/api/v1/cards")
public class CardController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final int MAX_PAGE_SIZE = 100;

    private final CardService service;

    public CardController(CardService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CardResponse> create(@RequestHeader(IDEMPOTENCY_KEY) String key,
                                               @Valid @RequestBody CreateCardRequest request) {
        Outcome<CardResponse> outcome = service.createCard(IdempotencyKeys.validate(key),
                request.cardholderName(), request.initialBalance());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(outcome.status())
                .location(URI.create("/api/v1/cards/" + outcome.body().id()));
        return replayHeader(response, outcome).body(outcome.body());
    }

    @GetMapping("/{cardId}")
    public CardResponse get(@PathVariable UUID cardId) {
        return service.getCard(cardId);
    }

    @PostMapping("/{cardId}/spend")
    public ResponseEntity<TransactionResponse> spend(@PathVariable UUID cardId,
                                                     @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                     @Valid @RequestBody AmountRequest request) {
        Outcome<TransactionResponse> outcome = service.spend(cardId, IdempotencyKeys.validate(key), request.amount());
        return replayHeader(ResponseEntity.status(outcome.status()), outcome).body(outcome.body());
    }

    @PostMapping("/{cardId}/top-up")
    public ResponseEntity<TransactionResponse> topUp(@PathVariable UUID cardId,
                                                     @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                     @Valid @RequestBody AmountRequest request) {
        Outcome<TransactionResponse> outcome = service.topUp(cardId, IdempotencyKeys.validate(key), request.amount());
        return replayHeader(ResponseEntity.status(outcome.status()), outcome).body(outcome.body());
    }

    @GetMapping("/{cardId}/transactions")
    public PageResponse<TransactionResponse> history(@PathVariable UUID cardId,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page must be >= 0 and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return service.history(cardId, page, size);
    }

    @PostMapping("/{cardId}/block")
    public CardResponse block(@PathVariable UUID cardId) {
        return service.block(cardId);
    }

    @PostMapping("/{cardId}/unblock")
    public CardResponse unblock(@PathVariable UUID cardId) {
        return service.unblock(cardId);
    }

    @PostMapping("/{cardId}/close")
    public CardResponse close(@PathVariable UUID cardId) {
        return service.close(cardId);
    }

    private static ResponseEntity.BodyBuilder replayHeader(ResponseEntity.BodyBuilder builder, Outcome<?> outcome) {
        return outcome.replayed() ? builder.header(REPLAYED_HEADER, "true") : builder;
    }
}
