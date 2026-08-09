-- One Postgres role per service, granted only on its own schema (security T-11).
--
-- This is not only a security control: it enforces the Phase 5 module boundaries
-- structurally. dispatch_svc physically cannot write orders.orders, so a boundary violation
-- fails at the first attempt in development rather than in review.
--
-- Local development credentials, committed deliberately so `docker compose up` works from a
-- cold clone (NFR-007). The hosted overlay generates its own and shares nothing with these.

CREATE ROLE order_svc    LOGIN PASSWORD 'order_svc';
CREATE ROLE dispatch_svc LOGIN PASSWORD 'dispatch_svc';
CREATE ROLE tracking_svc LOGIN PASSWORD 'tracking_svc';

-- Flyway (run by each service at startup) needs to create its own schema and objects.
GRANT CREATE ON DATABASE wassal TO order_svc, dispatch_svc, tracking_svc;
