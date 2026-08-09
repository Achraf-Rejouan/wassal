-- Sprint 2 (S2-13): the offer lifecycle and the three invariant constraints.
--
-- These constraints arrive in the sprint that proves them. INV-1 and INV-2 shipped early with
-- the skeleton because they were cheap and their absence would have allowed exactly the state
-- the project forbids; INV-3 needs the offers table, so it lands here.

CREATE TYPE dispatch.offer_status AS ENUM
    ('OFFERED', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'CANCELLED');

CREATE TABLE dispatch.offers (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id     uuid NOT NULL,                       -- no FK: cross-schema, by design
    courier_id   uuid NOT NULL REFERENCES dispatch.couriers (id) ON DELETE RESTRICT,
    status       dispatch.offer_status NOT NULL DEFAULT 'OFFERED',

    -- The single most important column in the schema. It makes the deadline durable across
    -- process death (INV-4) AND available as a SQL predicate in the accept path, which is what
    -- lets accept-vs-expire resolve without coordination (FR-012). A deadline held in a timer,
    -- a Redis key or a scheduler would lose the second property while keeping the first.
    expires_at   timestamptz NOT NULL,

    offered_at   timestamptz NOT NULL DEFAULT now(),
    responded_at timestamptz,
    sequence     int NOT NULL CHECK (sequence >= 1),
    created_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT chk_deadline_after_offer CHECK (expires_at > offered_at)
);

-- Partial: only outstanding offers can expire, and most rows end terminal. Without the
-- predicate this index would grow for the life of a run and the sweeper would slowly degrade —
-- the property that matters for a system designed to run under sustained synthetic load.
CREATE INDEX idx_offers_expiring ON dispatch.offers (expires_at) WHERE status = 'OFFERED';
CREATE INDEX idx_offers_by_order ON dispatch.offers (order_id, sequence);

-- INV-3, structurally. Unconditional rather than partial, deliberately: one offer produces one
-- assignment EVER, including across cancellation. That is stronger than INV-3 strictly needs and
-- it matches the intended domain semantics — a cancelled assignment must lead to a NEW offer
-- rather than a revived one, which closes a re-acceptance path that would otherwise need
-- application logic to forbid.
ALTER TABLE dispatch.assignments
    ADD COLUMN offer_id uuid REFERENCES dispatch.offers (id) ON DELETE RESTRICT;
ALTER TABLE dispatch.assignments
    ADD CONSTRAINT uq_assignment_per_offer UNIQUE (offer_id);

-- Candidate exclusion (FR-006): a courier already offered this order is not offered it again.
CREATE INDEX idx_offers_order_courier ON dispatch.offers (order_id, courier_id);
