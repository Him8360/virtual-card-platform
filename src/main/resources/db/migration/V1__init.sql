CREATE TABLE cards (
    id              UUID                     NOT NULL PRIMARY KEY,
    cardholder_name VARCHAR(100)             NOT NULL,
    balance         NUMERIC(19, 2)           NOT NULL,
    status          VARCHAR(16)              NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE,
    -- Last line of defence: even a buggy code path cannot overdraw a card.
    CONSTRAINT chk_cards_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT chk_cards_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED'))
);

CREATE INDEX idx_cards_status_expires_at ON cards (status, expires_at);

CREATE TABLE transactions (
    id            UUID                     NOT NULL PRIMARY KEY,
    card_id       UUID                     NOT NULL,
    tx_type       VARCHAR(16)              NOT NULL,
    amount        NUMERIC(19, 2)           NOT NULL,
    tx_status     VARCHAR(16)              NOT NULL,
    balance_after NUMERIC(19, 2)           NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_transactions_card FOREIGN KEY (card_id) REFERENCES cards (id),
    CONSTRAINT chk_transactions_amount CHECK (amount >= 0),
    CONSTRAINT chk_transactions_type CHECK (tx_type IN ('ISSUE', 'TOP_UP', 'SPEND')),
    CONSTRAINT chk_transactions_status CHECK (tx_status IN ('SUCCESSFUL', 'DECLINED', 'PENDING'))
);

CREATE INDEX idx_transactions_card_created ON transactions (card_id, created_at, id);

-- One row per (operation, card, Idempotency-Key). The primary key is what makes
-- "exactly once" hold even if two requests with the same key race each other.
CREATE TABLE idempotency_records (
    id              VARCHAR(300)             NOT NULL PRIMARY KEY,
    request_hash    VARCHAR(64)              NOT NULL,
    response_status INT                      NOT NULL,
    response_body   VARCHAR(4000)            NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
