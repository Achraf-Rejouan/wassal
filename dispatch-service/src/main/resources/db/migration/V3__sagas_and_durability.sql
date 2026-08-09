-- Sprint 3 (S3-05, S3-06): persisted saga state and the columns INV-4/INV-6 need.

CREATE TYPE dispatch.saga_status AS ENUM ('STARTED', 'COMPLETED', 'FAILED_NEEDS_ATTENTION');

CREATE TABLE dispatch.sagas (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    saga_type        text NOT NULL,
    aggregate_id     uuid NOT NULL,
    trigger_event_id uuid NOT NULL,

    -- The resumption point. On startup the orchestrator resumes from here rather than
    -- replaying from zero: re-running completed steps would lean on idempotency for
    -- correctness instead of using it as the safety net it is meant to be (ADR-0008).
    current_step     int NOT NULL DEFAULT 0 CHECK (current_step >= 0),

    status           dispatch.saga_status NOT NULL DEFAULT 'STARTED',
    attempts         int NOT NULL DEFAULT 0,
    last_error       text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),

    -- FR-009's idempotency, expressed as a constraint rather than as code: a duplicated
    -- trigger event cannot start a second saga for the same aggregate.
    CONSTRAINT uq_saga_trigger UNIQUE (aggregate_id, saga_type, trigger_event_id)
);

CREATE INDEX idx_sagas_inflight ON dispatch.sagas (status) WHERE status = 'STARTED';

-- Records that a courier was released, so INV-6 ("exactly once") is checkable rather than
-- merely intended. Without this a double release is invisible after the fact.
ALTER TABLE dispatch.assignments ADD COLUMN released_at timestamptz;
ALTER TABLE dispatch.assignments ADD COLUMN cancel_reason text;
ALTER TABLE dispatch.assignments ADD COLUMN picked_up_at timestamptz;

-- dispatch-service does not own orders.orders and must not read it (module boundaries, and
-- the per-service Postgres roles make it impossible anyway). The pickup coordinates ride along
-- on the OrderCreated event and are cached here so a re-offer can run a candidate search
-- without reaching across the boundary.
CREATE TABLE dispatch.order_pickups (
    order_id   uuid PRIMARY KEY,
    pickup_lat double precision NOT NULL,
    pickup_lon double precision NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
