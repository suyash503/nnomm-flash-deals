-- Deals: stock_available is the single counter every claim and every expiry moves.
-- The CHECKs are a last line of defence: even buggy code can't push stock below 0 or above stock_total.
CREATE TABLE deals (
    id              uuid        PRIMARY KEY,
    restaurant_id   uuid        NOT NULL,
    title           text        NOT NULL CHECK (char_length(title) BETWEEN 1 AND 100),
    price_paise     bigint      NOT NULL CHECK (price_paise BETWEEN 1 AND 1000000),
    stock_total     integer     NOT NULL CHECK (stock_total BETWEEN 1 AND 10000),
    stock_available integer     NOT NULL,
    starts_at       timestamptz NOT NULL,
    ends_at         timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT deals_stock_in_range CHECK (stock_available BETWEEN 0 AND stock_total),
    CONSTRAINT deals_window_valid CHECK (starts_at < ends_at)
);

CREATE TABLE claims (
    id           uuid        PRIMARY KEY,
    deal_id      uuid        NOT NULL REFERENCES deals (id),
    customer_id  uuid        NOT NULL,
    quantity     integer     NOT NULL CHECK (quantity BETWEEN 1 AND 3),
    amount_paise bigint      NOT NULL CHECK (amount_paise > 0),
    status       text        NOT NULL CHECK (status IN ('RESERVED', 'PAID', 'EXPIRED', 'NEEDS_REVIEW', 'REFUND_PENDING')),
    expires_at   timestamptz NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at   timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- Rule 2: one active claim per customer per deal. Only active statuses are in the index,
-- so an EXPIRED or REFUND_PENDING claim frees the slot and the customer may claim again.
CREATE UNIQUE INDEX claims_one_active_per_customer
    ON claims (deal_id, customer_id)
    WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');

-- The expiry sweeper only ever looks for RESERVED claims that are due; keep that lookup small.
CREATE INDEX claims_reserved_by_expiry ON claims (expires_at) WHERE status = 'RESERVED';

-- Idempotency records for POST /v1/deals/{dealId}/claims, scoped to the customer.
-- The row is inserted in the same transaction as the claim, so it commits or vanishes with it.
CREATE TABLE idempotency_keys (
    customer_id   uuid        NOT NULL,
    key           text        NOT NULL,
    deal_id       uuid        NOT NULL,
    quantity      integer     NOT NULL,
    response_code integer,
    response_body text,
    created_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (customer_id, key)
);

-- Every webhook event we've accepted. The primary key on event_id is what makes duplicates no-ops.
-- No foreign key on claim_id: events for unknown claims are recorded too (see DESIGN.md).
CREATE TABLE payment_events (
    event_id     text        PRIMARY KEY,
    type         text        NOT NULL,
    claim_id     uuid        NOT NULL,
    amount_paise bigint      NOT NULL,
    occurred_at  timestamptz NOT NULL,
    outcome      text        NOT NULL DEFAULT 'RECEIVED',
    received_at  timestamptz NOT NULL DEFAULT clock_timestamp()
);
