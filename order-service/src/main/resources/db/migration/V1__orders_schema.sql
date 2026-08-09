-- Sprint 1 (S1-03): orders, idempotency keys, outbox.
--
-- Offers, assignments and the three invariant constraints deliberately land in Sprint 2
-- (S2-13) — the invariant constraints belong in the sprint that proves them.
--
-- Full schema and the reasoning behind every index: docs/05-data-model.md

-- Extensions are created by the superuser in infra/db/init/00-extensions.sql, not here:
-- service roles are not superusers by design (security T-11).

CREATE SCHEMA IF NOT EXISTS orders;

CREATE TYPE orders.order_status AS ENUM (
    'PENDING', 'OFFERING', 'ASSIGNED', 'PICKED_UP', 'DELIVERED', 'CANCELLED', 'UNASSIGNABLE');

CREATE TABLE orders.orders (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id         uuid NOT NULL,
    status              orders.order_status NOT NULL DEFAULT 'PENDING',
    pickup              geography(Point, 4326) NOT NULL,
    dropoff             geography(Point, 4326) NOT NULL,
    pickup_address      text,
    dropoff_address     text,
    assigned_courier_id uuid,
    offer_attempts      int NOT NULL DEFAULT 0 CHECK (offer_attempts >= 0),
    version             bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    terminal_at         timestamptz,
    sla_deadline        timestamptz NOT NULL,

    -- Makes `terminal_at IS NOT NULL` exactly equivalent to being in a terminal state.
    -- The INV-4 stuck-order gauge depends on that equivalence; drift between the two would
    -- make the invariant silently unmeasurable, which is the worst failure class here.
    CONSTRAINT chk_terminal_consistency CHECK (
        (status IN ('DELIVERED', 'CANCELLED', 'UNASSIGNABLE')) = (terminal_at IS NOT NULL))
);

-- Partial: only non-terminal orders can be stuck, and most rows end terminal. Keeps the
-- index proportional to work in flight rather than work ever done (docs/05-data-model.md).
CREATE INDEX idx_orders_stuck   ON orders.orders (sla_deadline) WHERE terminal_at IS NULL;
CREATE INDEX idx_orders_pending ON orders.orders (status)       WHERE status = 'PENDING';

CREATE TABLE orders.idempotency_keys (
    merchant_id  uuid NOT NULL,
    key          text NOT NULL,
    request_hash text NOT NULL,
    -- DEFERRABLE so the idempotency key can be claimed BEFORE the order row is inserted.
    -- Claiming first is what makes a lost race cost nothing: the loser returns the winner's
    -- id having written nothing. Claiming second means the loser has already inserted an
    -- order it must then undo, which is how the first implementation created 24 orders for
    -- 24 concurrent requests sharing one key (see docs/bug-log.md).
    order_id     uuid NOT NULL REFERENCES orders.orders (id) ON DELETE RESTRICT
                 DEFERRABLE INITIALLY DEFERRED,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, key)
);

-- Column shape matches Debezium's outbox-event-router SMT so the CDC migration path in
-- ADR-0006 needs no schema change — only Connect config and deleting the publisher.
CREATE TABLE orders.order_outbox (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type text NOT NULL,
    aggregate_id   uuid NOT NULL,
    event_type     text NOT NULL,
    payload        jsonb NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    sent_at        timestamptz,
    attempts       int NOT NULL DEFAULT 0
);

-- Partial again: the index shrinks back to near-empty as the publisher drains, which is what
-- keeps a 10/s poll cheap over a long run.
CREATE INDEX idx_order_outbox_pending
    ON orders.order_outbox (aggregate_id, created_at) WHERE sent_at IS NULL;

CREATE TABLE orders.processed_messages (
    message_id     uuid NOT NULL,
    consumer_group text NOT NULL,
    processed_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, consumer_group)
);
CREATE INDEX idx_processed_prune ON orders.processed_messages (processed_at);
