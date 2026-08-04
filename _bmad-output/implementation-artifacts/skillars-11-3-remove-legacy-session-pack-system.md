# Story 11.3: Remove Legacy Session-Pack System and Scheduled Tasks

Status: done

## Story

As a platform engineer,
I want the legacy `booking.session_packs_purchased` system fully deleted,
so that there is exactly one session-pack code path left in the codebase.

**This is the completion story of Epic 11.** Story 11.1 built parity on `payment.session_pack_purchases`; Story 11.2 cut every live caller over to it and left the legacy classes in place but unreferenced by design. This story deletes what 11.2 deliberately left behind. **No live/production system exists yet (development/UAT stage)** — the legacy table is confirmed empty, so this is a pure code/schema deletion with no data-migration concern.

## Acceptance Criteria

1. **Given** Story 11.2 has cut every caller over to the new path **When** this story ships **Then** these 6 classes and their dedicated tests are deleted entirely, with no compensating shims or deprecated-but-retained stubs:
   - `src/main/java/com/softropic/skillars/platform/booking/service/SessionPackService.java`
   - `src/main/java/com/softropic/skillars/platform/booking/api/SessionPackResource.java`
   - `src/main/java/com/softropic/skillars/platform/booking/contract/SessionPackMapper.java`
   - `src/main/java/com/softropic/skillars/platform/booking/repo/SessionPackPurchasedRepository.java`
   - `src/main/java/com/softropic/skillars/platform/booking/repo/SessionPackPurchased.java`
   - `src/main/java/com/softropic/skillars/platform/booking/service/SessionPackExpiryScheduler.java`

   Plus the two booking-module DTOs that exist only to serve those classes: `SessionPackPurchasedResponse.java` and `PurchaseSessionPackRequest.java` (both in `booking/contract`). **Do not delete** `booking/contract`'s `SessionPackExpiredEvent`, `SessionPackExhaustedEvent`, `SessionPackExpiryWarningEvent`, `PackPausedEvent`, `PauseConflictResponse`, `PausePackRequest` — these are live, reused verbatim by the `payment` module (see Dev Notes).

2. **Given** the legacy table has no real user data (development-stage, confirmed empty) **When** the removal migration runs **Then** a new Flyway migration `V89__drop_legacy_session_packs.sql` runs `DROP TABLE booking.session_packs_purchased;` outright — no data-migration script needed. No other table has an FK to this table (verified during story creation), so no other schema change is required.

3. **Given** the application starts up after this story **When** the Spring context loads **Then** no `@Scheduled` registration exists for the deleted `SessionPackExpiryScheduler`, and a full-repo grep (`grep -rn "SessionPackService\|SessionPackResource\|SessionPackMapper\|SessionPackPurchasedRepository\|SessionPackPurchased\b\|SessionPackExpiryScheduler" src/main src/test`) confirms zero remaining references outside deleted files. **A second, independent sweep is also required**: `grep -rln "session_packs_purchased" src/main src/test` (the raw table name, not the class names above) must return zero hits — this catches the 7 unrelated IT fixture files fixed in Task 6 that reference the legacy table in SQL strings the class-name grep cannot see.

4. **Given** the full regression suite **When** run with the legacy system entirely absent **Then** `mvn -o verify` (unit + Testcontainers IT) is BUILD SUCCESS with no new failures — including the 7 non-pack-feature IT files whose fixture setup still touches the dropped legacy table (Task 6) — and frontend `npx eslint`/`npx quasar build` pass clean.

## Tasks / Subtasks

- [x] **Task 1 — Delete the 6 legacy classes + 2 dead DTOs** (AC: 1)
  - [x] Delete `SessionPackService.java`, `SessionPackResource.java`, `SessionPackMapper.java`, `SessionPackPurchasedRepository.java`, `SessionPackPurchased.java`, `SessionPackExpiryScheduler.java` (all under `platform.booking`, see Dev Notes for exact per-layer paths).
  - [x] Delete `booking/contract/SessionPackPurchasedResponse.java` and `booking/contract/PurchaseSessionPackRequest.java` — dead DTOs used only by the classes above. **Do not touch** the other `SessionPack*`/`Pack*` classes remaining in `booking/contract` (event classes + `PausePackRequest`/`PauseConflictResponse`) — those are live, consumed by `payment.PackSessionService`, `payment.SessionPackExpiryNotifier`, `payment.SessionPackForfeitureScheduler`, and `notification.infrastructure.listener.SessionPackEmailListener`. Deleting them would break the new (live) path — this is the one mistake that would turn this cleanup story into a regression.

- [x] **Task 2 — Delete the 4 legacy test files** (AC: 1)
  - [x] Delete `src/test/java/com/softropic/skillars/platform/booking/service/SessionPackServiceTest.java` (243 lines)
  - [x] Delete `src/test/java/com/softropic/skillars/platform/booking/service/SessionPackExpirySchedulerTest.java` (205 lines)
  - [x] Delete `src/test/java/com/softropic/skillars/platform/booking/api/SessionPackResourceIT.java` (322 lines)
  - [x] Delete `src/test/java/com/softropic/skillars/platform/booking/api/SessionPackPauseResourceIT.java` (417 lines)

- [x] **Task 3 — Fix the stale mocks that would otherwise fail to compile** (AC: 1, 3)
  - [x] `src/test/java/com/softropic/skillars/platform/payment/service/ExpiredPackBookingValidationTest.java` currently declares `@Mock SessionPackService sessionPackService` and `@Mock SessionPackPurchasedRepository sessionPackPurchasedRepository` (lines ~54, ~61) plus their imports — these are stale leftovers from before Story 11.2 removed the corresponding fields from `BookingService`'s constructor (Mockito silently tolerates unused `@Mock` fields today, but the class won't compile once the mocked types are deleted). Remove both `@Mock` fields and their now-unused imports. Do not remove anything else in this file — the rest of its mocks/assertions are current and unrelated to this story.
  - [x] After Task 1/2, run a repo-wide grep for each of the 6 class names plus `SessionPackPurchasedResponse`/`PurchaseSessionPackRequest` (booking package) to confirm zero remaining references before proceeding (see AC3's exact grep command).

- [x] **Task 4 — Drop the legacy table via Flyway migration** (AC: 2)
  - [x] Create `src/main/resources/db/migration/V89__drop_legacy_session_packs.sql` (next version after existing `V88__session_pack_purchases_parity.sql`) containing `DROP TABLE booking.session_packs_purchased;`. Postgres drops the table's own indexes (`idx_spp_player_coach_status`, `idx_spp_parent_player`) and check constraints automatically — no separate `DROP INDEX`/`DROP CONSTRAINT` statements needed. No other table has a foreign key into this table (confirmed during story creation — `session.homework_assignments.pack_id` is a plain `UUID` column with no `REFERENCES` clause, see `V45__homework_assignments.sql`), so this is a single-statement migration.

- [x] **Task 5 — Remove dead frontend legacy functions** (AC: 1)
  - [x] Delete `getPlayerPacks`, `purchaseSessionPack`, and `pauseSessionPack` from `src/frontend/src/api/booking.api.js` (confirmed zero importers repo-wide as of Story 11.2 — `booking.store.js` already imports its pack functions exclusively from `payment.api.js`). **Do not touch** the same-named functions in `src/frontend/src/api/payment.api.js` — those are the live new-path implementations.

- [x] **Task 6 — Strip dead legacy-table fixture SQL from 7 unrelated IT tests** (AC: 4)
  - [x] **This is the trap that will silently break `mvn -o verify` if skipped.** Seven IT test files outside the booking/pack feature area still contain raw `jdbcTemplate` SQL (`INSERT`/`UPDATE`/`DELETE`) against `booking.session_packs_purchased`, left over from before Story 11.2's cutover — this table name does **not** match any of AC3's class-name grep terms, so the AC3 verification grep will report clean even while these are broken. Once Task 4's migration drops the table, every one of these files fails at `@BeforeEach`/`@AfterEach` time with "relation \"booking.session_packs_purchased\" does not exist" the moment its Testcontainers IT runs — not a compile failure, so `mvn -o test` (unit-only) won't catch it, only `mvn -o verify` (Testcontainers IT phase) will. Fix each file:
    - `src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java` — remove the legacy `INSERT INTO booking.session_packs_purchased` (setup) and matching `DELETE FROM booking.session_packs_purchased` (teardown); the file already separately seeds `payment.session_pack_tiers`/`payment.session_pack_purchases` for its actual pack-based scenario, so the legacy rows are pure dead weight.
    - `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java` — remove the legacy `INSERT`/two `DELETE`s/`UPDATE` against `booking.session_packs_purchased` (lines ~149, ~185, ~231, ~260); leave its separate `payment.session_pack_tiers`/`payment.session_pack_purchases` setup and `sessionPackPurchaseId` request field untouched — that part is the live new-path pack-booking scenario and is unaffected by this story.
    - `src/test/java/com/softropic/skillars/platform/booking/api/BookingBatchResourceIT.java` — remove the legacy `INSERT`/`DELETE` pair against `booking.session_packs_purchased`; this file has no corresponding `payment.session_pack_purchases` setup at all, confirming the legacy row was never functionally required by its scenarios.
    - `src/test/java/com/softropic/skillars/platform/booking/api/ScheduleResourceIT.java` — remove the legacy `INSERT`/`DELETE` pair against `booking.session_packs_purchased` (same dead-weight pattern as BookingBatchResourceIT).
    - `src/test/java/com/softropic/skillars/platform/booking/api/SessionCompletionResourceIT.java` — remove the legacy `INSERT`/`DELETE` pair against `booking.session_packs_purchased` (same dead-weight pattern).
    - `src/test/java/com/softropic/skillars/platform/booking/api/BookingSseIT.java` — remove the legacy `INSERT`/`DELETE` pair against `booking.session_packs_purchased` (same dead-weight pattern).
    - `src/test/java/com/softropic/skillars/platform/session/api/HomeworkResourceIT.java` — remove the legacy `INSERT INTO booking.session_packs_purchased` (setup, ~line 106), the `DELETE FROM booking.session_packs_purchased` (teardown, ~line 156), and the `UPDATE booking.session_packs_purchased SET status = 'EXHAUSTED' ...` (~line 213, in `getLockerRoomDrills_packExhausted_returns200EmptyList`). Its own code comment (~line 114) already confirms `HomeworkAssignmentService`'s active-pack gating reads exclusively from `payment.session_pack_purchases` since Story 11.2 — the legacy row was already inert, only its `id` was reused as a filler value for `session.homework_assignments.pack_id` (a plain `UUID` column, no FK, so any valid UUID works). Replace the removed `packId` variable's source with a freestanding `UUID.randomUUID()` (or reuse `paymentPurchaseId`) so `homework_assignments.pack_id` still gets a non-null value; keep that test's existing `UPDATE payment.session_pack_purchases SET remaining_sessions = 0 ...` line as-is, since that's what actually drives the pack-exhausted assertion post-11.2.
  - [x] After edits, re-run the full-repo raw-SQL sweep — `grep -rln "session_packs_purchased" src/main src/test` — and confirm the only remaining hit is `src/main/java/.../booking/repo/SessionPackPurchased.java` itself being deleted in Task 1 (i.e. zero hits once Tasks 1–6 are all applied).

- [x] **Task 7 — Regression verification** (AC: 4)
  - [x] Run `mvn -o test` (unit) — confirm no failures and no new skips beyond the pre-existing baseline (792 passing, 1 pre-existing skip per Story 11.2's last run).
  - [x] Run `mvn -o verify` (full Testcontainers IT suite, requires Docker) — confirm BUILD SUCCESS with a test count consistent with removing the 4 deleted test files (Story 11.2 baseline: 833 tests). If Docker is unavailable in the execution environment, state that explicitly rather than claiming it passed (Story 11.2 hit this exact limitation — be honest about it, per this project's established practice). **Do not rely on `mvn -o test` alone to declare this story done** — Task 6's fixture breakage only surfaces in the IT (`verify`) phase, so a Docker-less environment cannot actually confirm AC4 for this story; say so explicitly rather than reporting success.
  - [x] Run `npx eslint` and `npx quasar build` on the frontend — confirm clean, per project convention (no `npm test` — it's a no-op stub in this repo, confirmed unchanged since Story 11.1/11.2).
  - [x] Confirm the AC3 grep is clean (zero hits for any of the 6 deleted class names + the 2 deleted DTOs, outside version-control history).

### Review Findings

- [x] [Review][Patch] Stale comment in `BookingRequestResourceIT` still says "consuming the single legacy credit" after the legacy fixture insert it referred to was deleted — the test no longer touches any legacy credit table. [src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java:243]
- [x] [Review][Defer] `V89__drop_legacy_session_packs.sql`'s `DROP TABLE` has no `IF EXISTS` guard — fine given Flyway won't re-run an applied migration and the table is confirmed empty at this dev/UAT stage, but worth adopting as convention for future destructive migrations. [src/main/resources/db/migration/V89__drop_legacy_session_packs.sql:1] — deferred, pre-existing convention gap (no prior DROP TABLE in this codebase to follow either way)
- [x] [Review][Defer] Code deletion and the destructive `DROP TABLE` migration ship in one combined change with no staged rollout — normal practice for a live system would be to remove all references first, verify, then drop the table in a later release. Not applicable now since no live/production system exists yet (confirmed by this story's own framing), but relevant once this app has real deployed traffic. [src/main/resources/db/migration/V89__drop_legacy_session_packs.sql:1] — deferred, no live system yet
- [x] [Review][Defer] `session.homework_assignments.pack_id` has no FK and points at nothing meaningful now that the legacy table is dropped — pre-existing design (the column never had a `REFERENCES` clause, per V45), not introduced by this diff. [src/test/java/com/softropic/skillars/platform/session/api/HomeworkResourceIT.java:103] — deferred, pre-existing

## Dev Notes

- **This is a subtractive story — resist the urge to add anything.** No new production behavior, no new endpoints, no refactor of surviving code beyond the mechanical fixes in Task 3/5/6. If `mvn -o verify` reveals an unexpected compile break beyond `ExpiredPackBookingValidationTest`, that means something still depends on a deleted class that this story's research missed — fix the reference (usually a stale import or mock), do not restore the deleted class. If `mvn -o verify` instead fails at IT *runtime* (not compile) with a "relation does not exist" error against `booking.session_packs_purchased`, that means Task 6 missed one of its 7 target files — re-run the sweep in Task 6's last bullet rather than adding a compensating schema shim.
- **The critical trap in this story is over-deleting `booking/contract`.** That package holds two disjoint sets of `SessionPack*`-named classes: (a) the 2 dead DTOs this story removes (`SessionPackPurchasedResponse`, `PurchaseSessionPackRequest`) which existed only to serve the now-deleted `SessionPackMapper`/`SessionPackResource`, and (b) live domain events/pause DTOs (`SessionPackExpiredEvent`, `SessionPackExhaustedEvent`, `SessionPackExpiryWarningEvent`, `PackPausedEvent`, `PauseConflictResponse`, `PausePackRequest`) that the `payment` module publishes/consumes today and Story 11.1 explicitly chose to reuse verbatim rather than duplicate. Deleting set (b) breaks live functionality — verify each file's actual importers with grep before deleting anything in `booking/contract`, don't delete by name-pattern alone.
- **Name-collision trap:** `com.softropic.skillars.platform.payment.contract` has its own, unrelated, live `PurchaseSessionPackRequest` and `SessionPackTierResponse` classes (same simple name pattern, different package, different purpose — the new path's request/response DTOs). Likewise `platform.marketplace.repo.SessionPack` and `platform.payment.repo.SessionPackTier`/`SessionPackPurchase` are distinct live entities. When deleting, act only on the exact fully-qualified `booking.contract`/`booking.repo`/`booking.service`/`booking.api` paths listed in AC1 — do not pattern-match on simple class name alone across the whole codebase.
- **Package/layer map for the 6 classes being deleted** (all `platform.booking`): `booking.service` → `SessionPackService`, `SessionPackExpiryScheduler`; `booking.api` → `SessionPackResource`; `booking.contract` → `SessionPackMapper`; `booking.repo` → `SessionPackPurchasedRepository`, `SessionPackPurchased`.
- **Confirmed dead subtree, not a live caller graph.** `SessionPackResource` → `SessionPackService` → (`SessionPackMapper`, `SessionPackPurchasedRepository`, `SessionPackPurchased`) is fully self-contained with zero external callers; `SessionPackExpiryScheduler` is a second, independent dead entry point (no other class calls it — it's a `@Scheduled` bean). Story 11.2 already verified and documented this; this story acts on that verification rather than re-deriving it.
- **Legacy REST routes being removed** (all under `SessionPackResource`, base path implied by `booking.api`): `GET /api/bookings/players/{playerId}/packs`, `POST /api/bookings/players/{playerId}/packs/purchase`, `POST /api/bookings/players/{playerId}/packs/{packId}/pause`. No frontend caller remains (Story 11.2 migrated the UI to `payment` module routes) — deleting `SessionPackResource` removes these endpoints from the API surface entirely, which is intended, not a regression.
- **`Booking` entity has no legacy field to clean up.** `Booking.java` holds only `sessionPackPurchaseId` (a plain `UUID` column, no JPA FK), which already points at the new `payment.SessionPackPurchase` table — there is no separate legacy `packId`/`sessionPackId` column on `Booking` left over from the old system.
- **Anti-duplication:** don't write a new test file to "cover" the deletion — Task 7's regression run against the existing (now-smaller) suite is the verification; there's no new behavior to unit test. Task 6's edits are pure fixture cleanup (removing dead `INSERT`/`UPDATE`/`DELETE` calls) in existing files, not new test coverage.
- **Testing standards:** this repo uses JUnit 5 + AssertJ + Mockito for unit tests, `@SpringBootTest` + `@Testcontainers` for ITs (never mock the database in integration tests) — inherited convention, not new to this story, relevant only in that Task 3's fix must keep using the same Mockito style already present in `ExpiredPackBookingValidationTest`.
- **Why Task 6 exists / how it was found:** the 6 classes in AC1 and the raw table name `booking.session_packs_purchased` are two independent surfaces — deleting all callers of the *classes* (Task 1–3) does not remove all *SQL references to the table*, because 7 IT tests outside the pack feature area (reschedule, booking-request, batch, schedule, session-completion, SSE, homework) use raw `jdbcTemplate` calls against the table as leftover fixture setup from before Story 11.2's cutover — of these, only `RescheduleResourceIT`/`BookingRequestResourceIT` also seed the live `payment.session_pack_purchases` table for their actual pack-based scenarios; the other 5 seed *only* the legacy table for scenarios that don't depend on pack state at all. None of this is visible to AC3's class-name grep, which is why it needed its own dedicated task and its own verification sweep rather than being folded into Task 2/3.

### Project Structure Notes

- All backend deletions are in `platform.booking` (service/api/contract/repo layers) plus one file in `platform.payment`'s test tree needing a mechanical mock cleanup (Task 3), plus fixture SQL cleanup in 7 IT files across `platform.booking.api` and `platform.session.api` (Task 6) — no new files except the migration.
- One new file: `src/main/resources/db/migration/V89__drop_legacy_session_packs.sql` — follows the existing `V{n}__description.sql` naming convention (last existing migration is `V88__session_pack_purchases_parity.sql`).
- Frontend deletion is confined to 3 dead function exports in `src/frontend/src/api/booking.api.js` — no component/store changes needed since Story 11.2 already confirmed zero importers.

### References

- [Source: _bmad-output/planning-artifacts/skillars-epics.md#Epic 11, Story 11.3] — epic-level acceptance criteria (this story file's ACs are the same requirements, made precise against live code found during story creation).
- [Source: _bmad-output/implementation-artifacts/skillars-11-2-cutover-booking-and-frontend.md, Dev Notes] — explicit statement that Story 11.2 deliberately left the legacy classes in place, unreferenced, for this story to delete; also documents that `SessionPackServiceTest`/`SessionPackExpirySchedulerTest` were kept green and untouched through 11.2 specifically to prove the legacy path still compiled with zero live callers.
- [Source: src/main/java/com/softropic/skillars/platform/booking/service/SessionPackService.java] — referenced only by `SessionPackResource.java` (confirmed via repo-wide grep during story creation); to be deleted.
- [Source: src/main/java/com/softropic/skillars/platform/booking/api/SessionPackResource.java] — zero references anywhere outside its own file; routes listed in Dev Notes; to be deleted.
- [Source: src/test/java/com/softropic/skillars/platform/payment/service/ExpiredPackBookingValidationTest.java] — contains the stale `@Mock SessionPackService`/`@Mock SessionPackPurchasedRepository` fields that must be removed in Task 3, or the payment module's test tree fails to compile once Task 1 deletes those classes.
- [Source: src/main/resources/db/migration/V30__booking_session_packs.sql, V37__session_pack_expiry_pause.sql, V76__missing_indexes.sql, V83__fix_session_packs_expiry_index_predicate.sql] — all prior migrations touching `booking.session_packs_purchased`; confirms table schema (UUID PK, 2 indexes, no external FK) referenced in Task 4's Dev Note about a single-statement `DROP TABLE` being sufficient.
- [Source: src/main/resources/db/migration/V88__session_pack_purchases_parity.sql] — most recent existing migration; this story's new migration is `V89`.
- [Source: src/frontend/src/api/booking.api.js] — `getPlayerPacks`, `purchaseSessionPack`, `pauseSessionPack` confirmed to have zero importers repo-wide; to be deleted (Task 5). Distinct from same-named live functions in `src/frontend/src/api/payment.api.js`, which must not be touched.
- [Source: src/main/resources/db/migration/V45__homework_assignments.sql] — confirms `session.homework_assignments.pack_id` is a plain `UUID` column with no `REFERENCES` clause, supporting AC2's "no FK into this table" claim and Task 6's note that any valid UUID can replace the removed legacy `packId` value in `HomeworkResourceIT`.
- [Source: src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java, BookingRequestResourceIT.java, BookingBatchResourceIT.java, ScheduleResourceIT.java, SessionCompletionResourceIT.java, BookingSseIT.java; src/test/java/com/softropic/skillars/platform/session/api/HomeworkResourceIT.java] — 7 files confirmed via repo-wide grep for the raw string `session_packs_purchased` (not caught by AC3's class-name grep) to contain leftover raw-SQL fixture calls against the legacy table; each fails at IT runtime once Task 4's migration drops the table. Fixed in Task 6.

## Dev Agent Record

### Agent Model Used

claude-sonnet-5

### Debug Log References

None — no blockers hit. `mvn -o compile`/`test-compile` were run after each deletion pass to catch stale references early; all resolved on the first pass (only `ExpiredPackBookingValidationTest`'s stale mocks needed fixing, exactly as Dev Notes predicted).

### Completion Notes List

- Task 1: Deleted the 6 legacy classes (`SessionPackService`, `SessionPackResource`, `SessionPackMapper`, `SessionPackPurchasedRepository`, `SessionPackPurchased`, `SessionPackExpiryScheduler`) and the 2 dead DTOs (`SessionPackPurchasedResponse`, `PurchaseSessionPackRequest`) via `git rm`. Verified via `find` beforehand that these were the only `SessionPack*` files in `booking/{api,service,repo}` plus the 2 target DTOs in `booking/contract` — the 3 live event classes in `booking/contract` were left untouched.
- Task 2: Deleted the 4 legacy test files via `git rm`.
- Task 3: Removed the stale `@Mock SessionPackService` / `@Mock SessionPackPurchasedRepository` fields and their imports from `ExpiredPackBookingValidationTest`. AC3's class-name grep confirmed zero remaining compiled references — the only hits left are historical migration-file comments and two unrelated test files (`BookingCompletionServiceTest`, `QuickCompleteTimeoutServiceTest`) whose assertions check that a log message *does not contain* the string `"SessionPackService"` (string literal in an assertion, not a class reference).
- Task 4: Created `V89__drop_legacy_session_packs.sql` with a single `DROP TABLE booking.session_packs_purchased;` statement.
- Task 5: Removed `getPlayerPacks`, `purchaseSessionPack`, `pauseSessionPack` from `booking.api.js`. Confirmed via grep that `booking.store.js` imports the same-named live functions exclusively from `payment.api.js`, and `getPlayerPacks` has zero importers anywhere in the frontend.
- Task 6: Found and fixed exactly the 7 IT files the story predicted via `grep -rln "session_packs_purchased" src/main src/test`, stripping the dead legacy-table `INSERT`/`UPDATE`/`DELETE` fixture calls from each while preserving each file's live `payment.session_pack_purchases`/`payment.session_pack_tiers` setup where present. `HomeworkResourceIT`'s `packId` variable now sources from a freestanding `UUID.randomUUID()` per Dev Notes guidance (the column has no FK). Post-edit sweep confirmed zero remaining `session_packs_purchased` hits in `src/main`/`src/test` outside migration files (both the old migrations that created/altered the table and the new V89 that drops it).
- Task 7: `mvn -o compile` and `mvn -o test-compile` both clean. `mvn -o clean test` (unit): 777 tests, 0 failures, 0 errors, 1 pre-existing skip (down from the 792/1 baseline by exactly the 15 tests in the 2 deleted unit test files, as expected). `mvn -o verify` (full Testcontainers IT suite, Docker available in this environment): BUILD SUCCESS, exit code 0, combined unit+IT total 1597 tests / 0 failures / 0 errors / 5 skipped — confirms Task 6's fixture fixes hold at IT runtime, which was the story's central risk. `npx eslint` clean. `npx quasar build` succeeded (SPA build, no errors). Final AC3 sweeps (both the class-name grep and the raw-table-name grep) came back clean per the story's exact commands.
- No production behavior changed — this was a pure subtractive story as scoped. No new tests were written per the story's explicit anti-duplication guidance; Task 7's regression run against the existing suite is the verification.

### File List

**Deleted:**
- `src/main/java/com/softropic/skillars/platform/booking/api/SessionPackResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/SessionPackMapper.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/SessionPackPurchasedResponse.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/PurchaseSessionPackRequest.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/SessionPackPurchased.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/SessionPackPurchasedRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/SessionPackService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/SessionPackExpiryScheduler.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/SessionPackServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/SessionPackExpirySchedulerTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/SessionPackResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/SessionPackPauseResourceIT.java`

**Added:**
- `src/main/resources/db/migration/V89__drop_legacy_session_packs.sql`

**Modified:**
- `src/test/java/com/softropic/skillars/platform/payment/service/ExpiredPackBookingValidationTest.java` — removed stale `@Mock` fields/imports for deleted types
- `src/frontend/src/api/booking.api.js` — removed `getPlayerPacks`, `purchaseSessionPack`, `pauseSessionPack`
- `src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java` — stripped legacy fixture SQL
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java` — stripped legacy fixture SQL
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingBatchResourceIT.java` — stripped legacy fixture SQL
- `src/test/java/com/softropic/skillars/platform/booking/api/ScheduleResourceIT.java` — stripped legacy fixture SQL
- `src/test/java/com/softropic/skillars/platform/booking/api/SessionCompletionResourceIT.java` — stripped legacy fixture SQL
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingSseIT.java` — stripped legacy fixture SQL
- `src/test/java/com/softropic/skillars/platform/session/api/HomeworkResourceIT.java` — stripped legacy fixture SQL, `packId` now sourced from `UUID.randomUUID()`
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — status tracking

## Change Log

| Date | Change |
| :--- | :--- |
| 2026-08-04 | Story implemented: deleted the 6 legacy `booking.session_packs_purchased` classes + 2 dead DTOs, deleted their 4 dedicated tests, fixed stale mocks in `ExpiredPackBookingValidationTest`, added `V89` migration dropping the legacy table, removed 3 dead frontend functions, stripped legacy-table fixture SQL from 7 unrelated IT files, and ran full regression (`mvn -o verify` + frontend lint/build) — all green. Status → review. |
