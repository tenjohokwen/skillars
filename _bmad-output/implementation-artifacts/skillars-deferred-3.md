# Story Deferred-3: DB Schema Hardening

Status: backlog

## Story

As a platform engineer,
I want critical indexes and constraints to be in place,
so that queries are performant under production data volumes and data-integrity invariants are enforced at the database layer rather than relying solely on application logic.

## Acceptance Criteria

1. **Given** the `session.homework_assignments` table is queried by player and coach
   **When** `HomeworkAssignmentService.getLockerRoomDrills()` filters by `coach_id` for a player's assignments
   **Then** a composite index `(player_id, coach_id)` on `homework_assignments` exists, so the query uses an index scan rather than a sequential scan

2. **Given** `SessionPackExpiryScheduler` queries packs by coach and expiry window
   **When** the `session_pack_purchases` table grows to thousands of rows
   **Then** indexes on `(parent_id)` and `(coach_id, expires_at)` exist on `session_pack_purchases` so expiry and parent-scoped lookups avoid full-table scans

3. **Given** `SessionTemplate.source_template_id` is used for analytics queries on deployed sessions
   **When** the sessions table grows
   **Then** an index on `sessions.source_template_id` exists (even if not immediately needed by current queries, the FK-like column warrants an index per project convention)

4. **Given** `VideoRepository.findByProviderAssetId()` loads a video by its Bunny.net asset ID
   **When** two videos are accidentally stored with the same `provider_asset_id`
   **Then** a unique index on `video.videos(provider_asset_id)` prevents the duplicate and ensures `findByProviderAssetId()` never throws `IncorrectResultSizeDataAccessException`

5. **Given** a coach clones a platform drill into their private library
   **When** the coach clones the same drill a second time
   **Then** a unique constraint on `session.drills(owner_coach_id, name)` (partial: `WHERE library_type = 'COACH'`) prevents silent duplicates in the coach's private library
   **And** the service throws `DataIntegrityViolationException` → `409 Conflict` (translated by `ApiAdvice`) rather than silently creating a second row

6. **Given** `parent_credit_ledger` is an append-only audit table
   **When** any application code or DBA issues an `UPDATE` or `DELETE` against a ledger row
   **Then** a PostgreSQL `RULE` (or trigger) prevents the mutation and raises an exception — enforcing the append-only invariant at the DB layer, not just by convention

7. **Given** `SessionPlanService.deployTemplate()` is called concurrently for the same booking
   **When** two concurrent calls both pass the `existsByBookingId()` check before either commits
   **Then** a unique constraint on `session.sessions(booking_id)` prevents a duplicate session plan row from being committed — the second commit throws `DataIntegrityViolationException` which the service translates to a `409`

## Tasks / Subtasks

- [ ] **Task 1 — Flyway V76: Add missing indexes** (AC: 1, 2, 3)
  - [ ] Confirm the exact table and schema names by reading the relevant V-series migrations:
    - `session.homework_assignments` — from V45 migration
    - `booking.session_pack_purchases` — from V30/V62 migrations
    - `session.sessions` — from V38/V44 migrations
  - [ ] Create `src/main/resources/db/migration/V76__missing_indexes.sql`:
    ```sql
    -- AC 1: homework assignments composite index
    CREATE INDEX IF NOT EXISTS idx_homework_assignments_player_coach
        ON session.homework_assignments(player_id, coach_id);

    -- AC 2: session pack purchases
    CREATE INDEX IF NOT EXISTS idx_session_pack_purchases_parent_id
        ON booking.session_pack_purchases(parent_id);

    CREATE INDEX IF NOT EXISTS idx_session_pack_purchases_coach_expires
        ON booking.session_pack_purchases(coach_id, expires_at)
        WHERE status NOT IN ('EXHAUSTED', 'EXPIRED');

    -- AC 3: session template traceability
    CREATE INDEX IF NOT EXISTS idx_sessions_source_template_id
        ON session.sessions(source_template_id)
        WHERE source_template_id IS NOT NULL;
    ```
  - [ ] Verify column names match the actual migration files before writing — do not guess; read the source migrations

- [ ] **Task 2 — Flyway V77: Video provider asset ID unique index** (AC: 4)
  - [ ] Create `src/main/resources/db/migration/V77__video_provider_asset_unique.sql`:
    ```sql
    -- Prevent duplicate Bunny.net asset IDs; also makes findByProviderAssetId safe
    CREATE UNIQUE INDEX IF NOT EXISTS idx_videos_provider_asset_id_unique
        ON video.videos(provider_asset_id)
        WHERE provider_asset_id IS NOT NULL;
    ```
  - [ ] Partial index (WHERE NOT NULL) avoids constraining draft/failed videos that may not yet have a provider ID assigned
  - [ ] Verify the schema name (`video`) and column name (`provider_asset_id`) from V53 or the video module init migration

- [ ] **Task 3 — Flyway V78: Drill dedup and session booking constraint** (AC: 5, 7)
  - [ ] Create `src/main/resources/db/migration/V78__drill_dedup_session_booking_unique.sql`:
    ```sql
    -- AC 5: prevent duplicate private drill names per coach
    -- Partial: only applies to COACH-owned drills (library_type = 'COACH')
    CREATE UNIQUE INDEX IF NOT EXISTS idx_drills_coach_name_unique
        ON session.drills(owner_coach_id, name)
        WHERE library_type = 'COACH';

    -- AC 7: prevent duplicate session plans for the same booking
    CREATE UNIQUE INDEX IF NOT EXISTS idx_sessions_booking_id_unique
        ON session.sessions(booking_id)
        WHERE booking_id IS NOT NULL;
    ```
  - [ ] Read `V38__session_module_init.sql` to confirm: schema name, table name, column names (`owner_coach_id`, `name`, `library_type`, `booking_id`)

- [ ] **Task 4 — Flyway V79: Append-only rule on `parent_credit_ledger`** (AC: 6)
  - [ ] Create `src/main/resources/db/migration/V79__credit_ledger_append_only.sql`:
    ```sql
    -- Enforce append-only at DB layer; application never updates or deletes ledger rows
    CREATE OR REPLACE RULE no_update_credit_ledger AS
        ON UPDATE TO booking.parent_credit_ledger
        DO INSTEAD NOTHING;

    CREATE OR REPLACE RULE no_delete_credit_ledger AS
        ON DELETE TO booking.parent_credit_ledger
        DO INSTEAD NOTHING;
    ```
  - [ ] **Alternative (stronger)**: use a trigger that raises an exception rather than silently ignoring the mutation:
    ```sql
    CREATE OR REPLACE FUNCTION enforce_ledger_append_only()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
    BEGIN
        RAISE EXCEPTION 'parent_credit_ledger is append-only; UPDATE/DELETE is not permitted';
    END;
    $$;

    CREATE TRIGGER trg_ledger_no_update
        BEFORE UPDATE ON booking.parent_credit_ledger
        FOR EACH ROW EXECUTE FUNCTION enforce_ledger_append_only();

    CREATE TRIGGER trg_ledger_no_delete
        BEFORE DELETE ON booking.parent_credit_ledger
        FOR EACH ROW EXECUTE FUNCTION enforce_ledger_append_only();
    ```
  - [ ] **Use the trigger approach** — a `RULE DO INSTEAD NOTHING` silently discards the mutation; the trigger raises a visible exception that catches accidental DBA mistakes
  - [ ] Confirm the schema and table name from V62 (`booking.parent_credit_ledger` or wherever it was created)
  - [ ] Note: GDPR erasure does NOT delete ledger rows (per Story 10.4 AC 5 — financial records are retained). No conflict with the trigger.

- [ ] **Task 5 — Handle `DataIntegrityViolationException` → 409 for drill clone duplicates** (AC: 5)
  - [ ] In `DrillLibraryService.java` (or wherever drill cloning happens) — wrap the `save()` call:
    ```java
    try {
        drillRepository.save(clonedDrill);
    } catch (DataIntegrityViolationException e) {
        if (e.getMessage() != null && e.getMessage().contains("idx_drills_coach_name_unique")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A drill with this name already exists in your library");
        }
        throw e;
    }
    ```
  - [ ] Same pattern for `SessionPlanService.deployTemplate()` for the booking_id unique constraint

- [ ] **Task 6 — Integration tests** (AC: 4, 5, 7)
  - [ ] TSID range `9310_xxx`
  - [ ] Test 1: `duplicateProviderAssetId_throwsDataIntegrity()` — insert two video rows with same `provider_asset_id`; verify `DataIntegrityViolationException`
  - [ ] Test 2: `cloneSameDrillTwice_returns409()` — clone a platform drill twice for the same coach; second clone returns 409
  - [ ] Test 3: `deployTemplateTwiceForSameBooking_secondFails()` — concurrent deploy; second returns 409 or equivalent error

## Dev Notes

### Flyway version sequencing

V75 was the last migration (Story 10.4 GDPR). V76–V79 are all new. If any other migration was added between Story 10.4 and this story, renumber accordingly — check `src/main/resources/db/migration/` for the highest V-number before creating these files.

### `CREATE INDEX IF NOT EXISTS` is safe to re-run

All index creation uses `IF NOT EXISTS` so the migrations are safe to re-apply (e.g., if the DB was restored). This is consistent with other project migrations.

### `WHERE status NOT IN ('EXHAUSTED', 'EXPIRED')` on session_pack_purchases

This partial index focuses the expiry scheduler query. Verify the exact status strings from `SessionPackStatus.java` (or however status is stored) before writing the migration. If status is stored as an enum column, use the DB-stored string values.

### `parent_credit_ledger` trigger vs rule

The trigger approach is preferred (raises a visible exception) over `DO INSTEAD NOTHING` (silent discard). If the DBA accidentally runs `DELETE FROM booking.parent_credit_ledger WHERE parent_id = X` during a GDPR erasure, the trigger fires and surfaces the mistake. The GDPR erasure story (10.4) intentionally retains ledger rows — the trigger is consistent with this design.

### Drill unique constraint and the existing `chk_drill_owner` check constraint

The existing `chk_drill_owner` prevents PLATFORM drills from having a non-null `owner_coach_id`. The new partial unique index only applies to `library_type = 'COACH'` rows. These two constraints are complementary and do not conflict.

### References — Files to Read Before Implementing

- `V38__session_module_init.sql` — confirm `session.drills` column names and existing constraints
- `V30__booking_session_packs.sql` and `V62__session_payment_credit_wallet.sql` — confirm `session_pack_purchases` and `parent_credit_ledger` schema
- `V45__homework_assignments.sql` — confirm `homework_assignments` column names
- `V44__session_templates.sql` — confirm `session.sessions(source_template_id)` and `(booking_id)` columns
- `V53__video_quota_system.sql` (or video init migration) — confirm `video.videos(provider_asset_id)` column
- `DrillLibraryService.java` — cloning code path
- `SessionPlanService.java` — `deployTemplate()` code path

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

**New Files:**
- `src/main/resources/db/migration/V76__missing_indexes.sql`
- `src/main/resources/db/migration/V77__video_provider_asset_unique.sql`
- `src/main/resources/db/migration/V78__drill_dedup_session_booking_unique.sql`
- `src/main/resources/db/migration/V79__credit_ledger_append_only.sql`

**Modified Files:**
- `src/main/java/com/softropic/skillars/platform/session/service/DrillLibraryService.java`
- `src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java`
