# Virtual Card Issuance & Spending API

Spring Boot 3 / Java 17 backend for issuing virtual cards, topping them up, spending from them and
tracking transactions, designed to stay correct under heavy concurrent use.

Creating a card, spending, and topping up all require an `Idempotency-Key` header. Repeating the same key and body returns the original result and does not move money again.

## Run it

Requirements: JDK 17+, Maven 3.9+. Docker is optional (only for the PostgreSQL integration tests and the Postgres profile).

```bash
mvn test                      # unit + integration tests (H2). Postgres tests run only if Docker is available
mvn spring-boot:run           # starts on http://localhost:8080 with in-memory H2
```

On Docker Desktop/Engine 29 or newer, run the tests with Docker API 1.44 so Testcontainers does not skip the PostgreSQL tests:

```bash
mvn test -DargLine="-Dapi.version=1.44"
```

Run against PostgreSQL instead:

```bash
docker compose up -d
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

Observability endpoints: `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`.

## API

Every money-moving call **requires** an `Idempotency-Key` header (1-128 chars, `[A-Za-z0-9._:-]`).
Errors are RFC 7807 problem documents with a stable `code` field.

| Method & path | Purpose | Success | Notable errors |
|---|---|---|---|
| `POST /api/v1/cards` | Create card `{cardholderName, initialBalance}` | 201 | 400 validation, 422 key reused with different body |
| `GET /api/v1/cards/{id}` | Card details incl. balance | 200 | 404 |
| `POST /api/v1/cards/{id}/spend` | Deduct `{amount}` | 200 | **422 `DECLINED` / `INSUFFICIENT_FUNDS`**, 409 card not active, 404 |
| `POST /api/v1/cards/{id}/top-up` | Add `{amount}` | 200 | 409 card not active, 404 |
| `GET /api/v1/cards/{id}/transactions?page=&size=` | History, newest first (size <= 100) | 200 | 404 |
| `POST /api/v1/cards/{id}/block` / `unblock` / `close` | Lifecycle (CLOSED is terminal) | 200 | 409 invalid transition |

Amounts: `0.01 .. 1,000,000.00`, at most 2 decimals. Initial balance: `0.00 .. 1,000,000.00`.

```bash
curl -s -X POST localhost:8080/api/v1/cards -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: create-001' -d '{"cardholderName":"Alice","initialBalance":100.00}'

curl -s -X POST localhost:8080/api/v1/cards/<id>/spend -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: spend-001' -d '{"amount":25.50}'
# repeating the exact same request returns the same response with header Idempotent-Replayed: true
```

## Requirement coverage

| Requirement | How it is met |
|---|---|
| Card / Transaction entities | `Card` (id, cardholderName, balance, status, createdAt, + expiresAt), `Transaction` (id, cardId, type, amount, status, createdAt, + balanceAfter) |
| Balance never below zero | Three layers: `Card.debit` guard, row lock + check in the service, DB `CHECK (balance >= 0)` |
| Idempotent financial operations | `Idempotency-Key` + request hash, stored response replayed (see below) |
| Only active cards can spend/top up | `Card.isUsable(now)`: status ACTIVE **and** not past `expiresAt`, checked under the row lock |
| Correct under concurrency | `SELECT ... FOR UPDATE` per card; tests with 20-40 parallel callers on H2 and PostgreSQL |
| Scales to thousands of users | Stateless app, lock scope is one card row, indexed queries, paging; see "Scaling" |
| Logging and metrics | SLF4J with request-id MDC; Micrometer timers/counters; Prometheus endpoint |
| Bonus: async processing | `AuditEvent` published in the transaction, handled `@Async` + `AFTER_COMMIT` |
| Bonus: scheduled expiry | `CardExpiryJob` closes expired cards; correctness does not depend on it |
| Bonus: DoS protection | Per-client token bucket filter on **all** endpoints (incl. actuator), 429 + `Retry-After` |
| Bonus: Testcontainers | `PostgresConcurrencyTest` (real PostgreSQL + Flyway migration) |

## How a spend flows

```
HTTP POST /cards/{id}/spend
 -> RequestIdFilter -> RateLimitFilter            (correlation id, per-client limit)
 -> CardController                                (validate body + Idempotency-Key, no business logic)
 -> CardService.spend  [one DB transaction]
      1. SELECT card FOR UPDATE                   (serialises this card; 404 if missing)
      2. IdempotencyStore: record for this key?   yes -> compare request hash -> replay stored response
      3. Card.isUsable(now)?                      no  -> 409
      4. enough funds?                            no  -> save DECLINED tx, respond 422
      5. Card.debit, save SUCCESSFUL tx, publish AuditEvent
      6. save idempotency record (same transaction)
 -> COMMIT -> AuditEventListener (async)
```

Layers: `web` (HTTP) -> `service` (transactions, locking, idempotency, metrics) -> `domain` (rules, entities) -> `repository`.

## Key design decisions

**Pessimistic row lock per card.** A spend is read-check-write; the simplest correct way to serialise it is
`SELECT ... FOR UPDATE`. Different cards never contend, so throughput scales with the number of cards, and the lock
lives in the database so it works across any number of app instances. Optimistic locking would work at low contention
but wastes work and needs retry loops on a hot card.

**Idempotency without extra locks.** Spend/top-up take the card row lock first, which already serialises same-key
requests, so the idempotency table is a plain lookup plus insert in the same transaction (the business change and its
record commit or roll back together). Card creation has no row to lock yet, so the table's primary key decides the
winner; the loser gets a constraint violation, its (fresh) transaction is rolled back and the operation is retried,
which then replays the winner's response. `createCard` is deliberately not `@Transactional` for this reason:
on PostgreSQL a failed statement poisons the whole transaction, so each attempt needs its own.

**Idempotency semantics (explicit choices).**
- The key is scoped per operation and card; the same key with a *different* payload is rejected (422), not silently accepted.
- A declined spend is stored and replayed too. To retry after topping up, use a new key.
- Business rejections (blocked card, unknown card) are *not* stored, so the client can retry the same key later.
- Replays of an earlier success still succeed even if the card was blocked afterwards.

**Money.** `BigDecimal`, scale 2, `NUMERIC(19,2)`. Input is validated to 2 decimals and a per-operation maximum,
so there is no rounding and no overflow path.

**Expiry is enforced at use time.** A card past `expiresAt` is rejected immediately and reported as CLOSED; the
scheduled job only tidies stored state. A single bulk `UPDATE` makes the job safe to run on several instances.

**Schema by Flyway**, `ddl-auto=none`: the schema (including the CHECK constraints) is reviewed, versioned SQL.

**JPA, not jOOQ.** The model is one card aggregate and a few queries; `SELECT ... FOR UPDATE` and the expiry `UPDATE` are already explicit in Spring Data, and the rules live on `Card`. jOOQ would earn its place if the SQL outgrew the domain.

**Domain rules inside the entity** (`Card`), so they are unit-testable without Spring or a database.

## Observability

- Logs carry `requestId` (from `X-Request-Id` or generated, echoed in the response). No cardholder names are logged.
- `cards.operation.duration` (timer) and `cards.operations` (counter), tagged `operation` (create/spend/top_up) and
  `outcome` (success/declined/replayed/rejected/error); `cards.expired`; `ratelimit.rejected`; plus JVM/HTTP/DB-pool metrics.
- Audit trail on a dedicated `AUDIT` logger.

## Scaling

- App tier is stateless: add instances behind a load balancer.
- Contention is per card, so the database is the scaling unit. Next steps in order: connection-pool tuning, read replicas
  for `GET` endpoints (balances read from a replica can be slightly stale; spends always hit the primary), then partitioning
  `transactions` by card/time and sharding by card id.
- Idempotency table: add a retention job (e.g. delete records older than 24-72h) so it does not grow without bound.
- Rate limiting is in-memory per instance; with N instances the effective limit is N x configured. For a strict global
  limit move it to the gateway or Redis.

## Evolving towards microservices / event-driven

1. The `AuditEvent` is already an in-process domain event. Replace the listener with a transactional **outbox** table
   written in the same transaction, relayed to Kafka by a poller/CDC: no dual-write problem.
2. Split along the existing seams: *Cards/Ledger* (this service, owns balances), *Notifications/Audit/Fraud* (consume events),
   *Expiry* (scheduled worker), with an API gateway handling auth and rate limits.
3. For external systems (card processor, KYC): add an anti-corruption adapter behind a port interface and use the reserved
   `PENDING` status for authorize-then-capture flows.
4. Never split the balance across services: the ledger stays the single writer; others hold read models built from events.

## Trade-offs and pragmatic choices

- **Single database, single writer per card** rather than event sourcing: simpler and strongly consistent; harder to scale
  past one primary.
- **In-JVM audit via Spring events**: not durable if the process dies between commit and handling. Fine for a log line,
  not for compliance; hence the outbox suggestion above.
- **No authentication/authorisation**: out of scope for the exercise. Production needs per-client auth, and idempotency keys
  for card creation should be scoped to the caller.
- **Rate limiting keyed by remote address**: crude (shared NAT, proxies). Fixed token bucket, not per-endpoint.
- **Services return the API response records** (`web.dto`) directly to avoid a mapping layer. With more time I would add
  separate application-level result types.
- **Response bodies are stored as JSON strings** in the idempotency table, which couples replay to the DTO shape; acceptable for
  short retention.
- **Transaction history uses offset paging**; keyset paging would be better for very long histories.

## With more time

- Load/soak test (e.g. Gatling) and tuning of pool size and lock timeouts under real contention.
- Keyset pagination, idempotency-record retention job, OpenAPI documentation (springdoc), authentication.
- Outbox + Kafka for events; shedlock-style coordination if jobs become non-idempotent.
- Mutation testing and contract tests for the HTTP API.

## Testing

| Suite | What it proves |
|---|---|
| `CardTest` | Pure domain rules: no overdraft, exact-balance spend, state transitions, expiry boundary |
| `CardServiceIntegrationTest` | Idempotency (replay, key reuse, declined replay), blocked/closed/expired rules, paging, audit events, **concurrent** spends / same-key spends / same-key creates / mixed top-ups vs ledger |
| `CardApiTest` | HTTP contract: status codes, validation, error bodies, headers, metrics endpoint |
| `RateLimiterTest`, `RateLimitFilterTest` | Token bucket maths, eviction, 429 + `Retry-After` |
| `PostgresConcurrencyTest` | The concurrency guarantees on real PostgreSQL via Testcontainers (skipped without Docker) |

## Learning strategy

New or less familiar pieces were Testcontainers with `@ServiceConnection`, Flyway, and Micrometer. For each I started
from the official reference documentation, built the smallest working example, and then wrote a test that would fail if
my understanding was wrong (for example, the Postgres test exists specifically because H2 does not reproduce the
aborted-transaction behaviour that drives the retry in `createCard`).
