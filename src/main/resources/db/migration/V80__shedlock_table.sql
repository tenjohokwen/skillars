-- Story deferred-4: ShedLock table for distributed @Scheduled method locking across instances.
-- Table name/columns are dictated by ShedLock's JdbcTemplateLockProvider contract; schema is
-- qualified as main.shedlock (see ShedLockConfig withTableName("main.shedlock")) to follow the
-- platform convention of a single "main" schema for cross-cutting infrastructure tables.
CREATE TABLE IF NOT EXISTS main.shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);
