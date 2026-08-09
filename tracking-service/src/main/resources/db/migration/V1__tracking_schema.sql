-- Sprint 4 (S4-06): the cold path.
--
-- Extensions are created by the superuser in infra/db/init, not here — service roles are
-- deliberately not superusers (security T-11).

CREATE SCHEMA IF NOT EXISTS tracking;

CREATE TABLE tracking.location_history (
    courier_id  uuid NOT NULL,
    recorded_at timestamptz NOT NULL,
    position    geography(Point, 4326) NOT NULL,
    speed_kmh   real,
    ingested_at timestamptz NOT NULL DEFAULT now(),

    -- (courier_id, recorded_at) gives duplicate suppression for free: a retried position report
    -- violates the key and is discarded. A surrogate key would have permitted exactly the
    -- duplicates this table exists to reject.
    PRIMARY KEY (courier_id, recorded_at)
) PARTITION BY RANGE (recorded_at);

-- Partitioned so retention is a DROP rather than a DELETE. At ~8.6M rows/day a DELETE would
-- generate enormous WAL and leave the table bloated; dropping a partition is instant and
-- produces no dead tuples (A-05, 7-day retention).
--
-- NO index beyond the primary key, deliberately. This is the highest-volume write table in the
-- system and no query has a latency budget against it — a GiST index on position would cost
-- write throughput on exactly the path NFR-003 constrains.

CREATE OR REPLACE FUNCTION tracking.ensure_partition(day date) RETURNS void AS $$
DECLARE
    name text := 'location_history_' || to_char(day, 'YYYYMMDD');
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = name) THEN
        EXECUTE format(
            'CREATE TABLE tracking.%I PARTITION OF tracking.location_history
             FOR VALUES FROM (%L) TO (%L)',
            name, day, day + 1);
    END IF;
END;
$$ LANGUAGE plpgsql;

SELECT tracking.ensure_partition(current_date - 1);
SELECT tracking.ensure_partition(current_date);
SELECT tracking.ensure_partition(current_date + 1);
