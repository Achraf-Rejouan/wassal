-- Sprint 1 walking skeleton: couriers, a minimal assignments table, inbox and outbox.
--
-- The offer lifecycle, the sweeper tables and the accept-vs-expire machinery land in Sprint 2
-- and 3. The three invariant constraints (INV-1, INV-2, INV-3) arrive with S2-13, in the
-- sprint that proves them — but INV-1 and INV-2 are cheap here and omitting them would let a
-- skeleton bug create exactly the state the project exists to forbid, so they are created now.

CREATE SCHEMA IF NOT EXISTS dispatch;

CREATE TYPE dispatch.courier_status AS ENUM ('OFFLINE', 'AVAILABLE', 'BUSY');
CREATE TYPE dispatch.assignment_status AS ENUM ('ACTIVE', 'COMPLETED', 'CANCELLED');

CREATE TABLE dispatch.couriers (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name     text NOT NULL,
    status           dispatch.courier_status NOT NULL DEFAULT 'OFFLINE',
    last_position    geography(Point, 4326),
    last_position_at timestamptz,
    version          bigint NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),

    -- An unlocatable courier in the availability set would be offered work and fail every
    -- claim, so availability and a known position are inseparable (FR-005).
    CONSTRAINT chk_available_needs_position CHECK (
        status <> 'AVAILABLE' OR last_position IS NOT NULL)
);
CREATE INDEX idx_couriers_available ON dispatch.couriers (status) WHERE status = 'AVAILABLE';

CREATE TABLE dispatch.assignments (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id    uuid NOT NULL,                       -- no FK: cross-schema, by design
    courier_id  uuid NOT NULL REFERENCES dispatch.couriers (id) ON DELETE RESTRICT,
    status      dispatch.assignment_status NOT NULL DEFAULT 'ACTIVE',
    assigned_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    cancelled_at timestamptz
);

-- INV-1 and INV-2, enforced by the database rather than by application code. A bug in the
-- service cannot violate these: the write simply fails.
CREATE UNIQUE INDEX uq_active_assignment_per_courier
    ON dispatch.assignments (courier_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_active_assignment_per_order
    ON dispatch.assignments (order_id)   WHERE status = 'ACTIVE';

-- Consumer-side dedup. The row is written in the SAME transaction as the effect, which makes
-- "dedup record exists but effect did not commit" an unreachable state rather than a case to
-- handle (FR-014, INV-3, INV-5).
CREATE TABLE dispatch.processed_messages (
    message_id     uuid NOT NULL,
    consumer_group text NOT NULL,
    processed_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, consumer_group)
);
CREATE INDEX idx_processed_prune ON dispatch.processed_messages (processed_at);

CREATE TABLE dispatch.dispatch_outbox (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type text NOT NULL,
    aggregate_id   uuid NOT NULL,
    event_type     text NOT NULL,
    payload        jsonb NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    sent_at        timestamptz,
    attempts       int NOT NULL DEFAULT 0
);
CREATE INDEX idx_dispatch_outbox_pending
    ON dispatch.dispatch_outbox (aggregate_id, created_at) WHERE sent_at IS NULL;

-- Seed couriers for the walking skeleton. The simulator replaces this in Sprint 4.
INSERT INTO dispatch.couriers (display_name, status, last_position, last_position_at)
SELECT 'courier-' || i,
       'AVAILABLE',
       ST_SetSRID(ST_MakePoint(10.18 + (i * 0.001), 36.80 + (i * 0.001)), 4326)::geography,
       now()
  FROM generate_series(1, 5) AS i;
