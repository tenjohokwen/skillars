-- C4 (chore/auth-legacy-removal-and-platform-safety): single-instance safety net.
--
-- Skillars must run as ONE application instance until server-sent-event fan-out exists: the SSE
-- emitter registries (messaging, notifications) and a few per-node caches are in-memory, so a second
-- instance silently drops real-time events for the users connected to the other node. See
-- docs/dev-docs/decisions ("Single instance until SSE fan-out exists").
--
-- Each running instance upserts one row here on a fixed delay (InstanceHeartbeatMonitor) and logs a
-- WARN when another instance's row is still fresh, so an accidental scale-out is visible in the logs
-- within a minute instead of surfacing as "my messages arrive late" reports. Timestamps are written
-- with the database's now(), never an instance clock, so clock skew between hosts cannot hide or fake
-- a peer. A graceful shutdown deletes its own row; stale rows (crash, kill -9) are pruned after a day.
--
-- Migration conventions (docs/deployment/migration-conventions.md): a new, empty, tiny table — no
-- backfill, no constraint on an existing table. Lives in main, next to main.shedlock, because it is
-- infrastructure state with no owning business module.
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS main.app_instance_heartbeat (
    instance_id  varchar(64)  NOT NULL,
    hostname     varchar(255) NOT NULL,
    started_at   timestamptz  NOT NULL,
    last_seen_at timestamptz  NOT NULL,
    CONSTRAINT pk_app_instance_heartbeat PRIMARY KEY (instance_id)
);
