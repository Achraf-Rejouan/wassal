-- Extensions are created ONCE by the superuser at database init, never by a service migration.
--
-- Service roles are deliberately not superusers (security T-11) — that is what makes
-- dispatch_svc physically unable to write orders.orders. CREATE EXTENSION requires superuser,
-- so a migration that creates its own extensions forces every service to be one, which would
-- discard the privilege separation entirely.
--
-- This ordering (00- before 01-) matters: roles are granted below, extensions exist above.

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
