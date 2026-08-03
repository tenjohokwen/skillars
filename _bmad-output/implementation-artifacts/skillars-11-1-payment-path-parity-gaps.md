# Story 11.1: Session Pack Payment-Path Parity Gaps

Status: done

## Story

As a platform engineer,
I want `payment.session_pack_purchases` to support per-player scoping, pause/resume, a parent-facing list query, and idempotent expiry notification/forfeiture,
so that it has full feature parity with the legacy `booking.session_packs_purchased` system before any caller is migrated to depend on it exclusively (Story 11.2/11.3).

**This story is purely additive.** It must not modify `BookingService`, `SessionPackService` (legacy), `SessionPackExpiryScheduler` (legacy), or any frontend file. The legacy pack-purchase system keeps running unmodified and is still what the live frontend uses after this story ships — Story 11.2 does the cutover.

**No live system exists yet (development/UAT stage) — there is no production data to protect and no rollback safety net required.** This epic's mandatory end-state (Story 11.3) is complete deletion of the legacy system; every use case it currently supports must keep working, just running on the new schema. The 3-story split is purely about implementation sequencing (build parity → cut over → delete), not about hedging production risk that doesn't exist at this stage.

## Acceptance Criteria

1. **Given** Flyway runs on startup **When** migration `V88__session_pack_purchases_parity.sql` applies **Then** `payment.session_pack_purchases` gains three nullable-safe columns: `player_id BIGINT NOT NULL` (table is empty pre-launch — no backfill needed, safe to add as `NOT NULL` directly), `paused_until TIMESTAMPTZ NULL`, `expired_notified_at TIMESTAMPTZ NULL`. An index `idx_session_pack_purchases_parent_id` on `parent_id` and a composite index `idx_session_pack_purchases_coach_player` on `(coach_id, player_id)` are created to support the list query (AC 3) and future per-player lookups.

2. **Given** a parent purchases a pack **When** `POST /api/payment/session-packs/purchase` is called **Then** the request body now includes `playerId` (in addition to existing `packTierId`, `paymentMethodId`); `SessionPackPaymentService.purchasePack()` validates the player belongs to the authenticated parent using `playerProfileRepository.findByIdAndParentId(playerId, parentId)` — **this is the repository's own documented convention** ("Always use this instead of findById — parentId enforces family isolation," `PlayerProfileRepository.java:14-15`), used at 7 other live call sites (`ShadowAccountService`, `PlayerOwnershipGuard`, `MessagingService`, `MessagingResource`, `HomeworkAssignmentService`). Throw `ResourceNotFoundException` on empty result. **Do not** copy `BookingService.createBookingRequest()`'s manual `findById` + `Objects.equals(parentId)` check (lines 151-155) — that predates `findByIdAndParentId` and is the codebase's one outlier, not its convention. Persist `playerId` on the created `SessionPackPurchase` row.

3. **Given** an authenticated parent **When** `GET /api/payment/session-packs?coachId={optional}` is called **Then** returns all of that parent's packs (optionally filtered to one coach) as a list of `SessionPackPurchaseResponse` records including `playerId`, `pausedUntil`, and a computed `status` string added as a new field on `SessionPackPurchaseResponse` itself (not a separate list-only DTO) — computed in the mapping layer on every response (including the purchase endpoint's response, where a fresh pack simply computes to `"ACTIVE"`), never persisted, using this precedence: `remainingSessions == 0` → `"EXHAUSTED"`; else `pausedUntil != null && pausedUntil.isAfter(now)` → `"PAUSED"`; else `expiresAt.isBefore(now)` → `"EXPIRED"`; else `"ACTIVE"`. No new repository query needed beyond `findByParentIdOrderByCreatedAtDesc(Long parentId)` — filter by `coachId` in the service layer (mirrors the in-memory filtering style already used by legacy `SessionPackService.getPacksForPlayer()`).

4. **Given** a parent wants to pause an active, unpaused pack **When** `POST /api/payment/session-packs/{purchaseId}/pause` is called with `{pauseStartDate, pauseDurationDays, confirmedCancellationIds}` (reuse `PausePackRequest` from `booking.contract` verbatim — do not create a duplicate DTO, and do not add a `playerId` field to it) **Then** `PackSessionService.pausePack(Long parentId, UUID purchaseId, PausePackRequest req)`:
   - Loads the purchase via `findByIdForUpdate` (pessimistic write — already exists), verifies `purchase.getParentId().equals(parentId)`, else `OperationNotAllowedException`. **`playerId` is not a caller-supplied parameter** — the endpoint has no path/body slot for it (unlike legacy's `/players/{playerId}/packs/{packId}/pause` route) and it isn't needed as one: once the purchase is loaded, `purchase.getPlayerId()` (added in Task 2) is the value to pass into `findConflictingBookingsForPause` below. Ownership is enforced purely via `parentId`, consistent with how `extendPack`/`purchasePack` already scope by parent/coach without a separate player check.
   - Rejects if `remainingSessions <= 0` or `expiresAt.isBefore(now)` (mirrors legacy's active-status check, since this path has no persisted status column)
   - Rejects with a `BatchRuleViolationException("booking.packAlreadyPaused")` if `pausedUntil != null` — **one pause per pack lifetime**, exactly like legacy (the non-null check on `pausedUntil` doubles as the "already used" flag; no separate boolean column)
   - Validates `pauseDurationDays` against `configService.getLong("pack.pause.maxDays")`, and `pauseStartDate` is not in the past and precedes `expiresAt` (same validation as legacy `SessionPackService.pausePack()` lines 205-216)
   - Finds conflicting bookings via **the existing** `bookingRepository.findConflictingBookingsForPause(playerId, coachId, pauseStart, pauseEnd, conflictStatuses)` (do not write a new query — this method already exists on `BookingRepository` and is shared)
   - If conflicts exist and `confirmedCancellationIds` is empty: returns HTTP 200 with `PauseConflictResponse(false, conflictItems, null)` without applying the pause (reuse `PauseConflictResponse`/`ConflictingBookingItem` from `booking.contract`) — matches legacy's behavior of always returning `200 OK` regardless of `pauseApplied` (the response body's `pauseApplied` flag is what signals outcome, not the HTTP status)
   - Otherwise cancels each confirmed conflicting booking via `bookingService.cancelDueToPause(bookingId, coachId, parentId)` (existing `BookingService` method), sets `pausedUntil = pauseEnd`, extends `expiresAt` by `pauseDurationDays`, saves, and publishes the existing `PackPausedEvent` (from `booking.contract` — reused, not duplicated; its constructor needs `coachDisplayName`, `parentEmail`, `canonicalTimezone`, so `PackSessionService` needs `CoachProfileRepository` and `UserRepository` injected too — see Task 3)
   - Returns HTTP 200 with `PauseConflictResponse(true, [], newExpiresAt)`

5. **Given** the platform runs on multiple instances **When** a new `@Scheduled` job (`SessionPackForfeitureScheduler`, `platform.payment.service` package) fires on a fixed delay (mirror legacy `SessionPackExpiryScheduler`'s `@Scheduled(fixedDelay = 60, timeUnit = TimeUnit.MINUTES)`) **Then** it is `@SchedulerLock`-protected (`name = "SessionPackForfeitureScheduler_expire"`, `lockAtMostFor = "PT15M"`, `lockAtLeastFor = "PT2M"` — same values as legacy) and finds packs where `expiresAt < now AND expiredNotifiedAt IS NULL AND remainingSessions > 0` via a new repository query `findExpiredNotYetNotified(Instant now)`, publishes the existing `SessionPackExpiredEvent` (from `booking.contract` — reused) exactly once per pack, then sets `expiredNotifiedAt = now` so the next run does not re-notify. This job does **not** touch legacy `SessionPackPurchasedRepository` — it is entirely scoped to the new `payment.session_pack_purchases` table.

6. **Given** the pre-existing `SessionPackExpiryNotifier` (`platform.payment.service`, 14-day warning emails) currently has **no** `@SchedulerLock` annotation **When** this story ships **Then** it gains one (`name = "SessionPackExpiryNotifier_warn"`, `lockAtMostFor = "PT15M"`, `lockAtLeastFor = "PT2M"`) — closing a multi-instance duplicate-email gap identified during research, consistent with the ShedLock pattern already applied to every other scheduler in Story `deferred-4`.

7. **Given** all pre-existing `SessionPackPaymentService`/`PackSessionService` behavior (purchase, extend, deduct, restore, tier CRUD) **When** this story's changes are applied **Then** none of that *behavior* changes — but `purchasePack(Long, UUID, String)`'s **signature** does change (new `playerId` parameter), which is a mechanical, not behavioral, break. Update its 5 existing call sites accordingly rather than expecting them to compile unmodified: `PackPriceLockedOnPurchaseTest.java:74,105` and `SessionPackPurchaseIT.java:44,61,85`. New tests are added for: purchase-with-playerId (including the ownership-mismatch rejection), the list endpoint (including the computed-status precedence in AC 3), pause (happy path, already-paused rejection, conflict-without-confirmation, conflict-with-confirmation cascading cancel), and the forfeiture scheduler (marks once, does not re-notify on a second run, does not touch already-notified packs).

## Tasks / Subtasks

- [x] **Task 1 — Flyway migration** (AC: 1)
  - [x] Create `src/main/resources/db/migration/V88__session_pack_purchases_parity.sql`:
    ```sql
    ALTER TABLE payment.session_pack_purchases
        ADD COLUMN player_id BIGINT NOT NULL,
        ADD COLUMN paused_until TIMESTAMPTZ,
        ADD COLUMN expired_notified_at TIMESTAMPTZ;

    CREATE INDEX idx_session_pack_purchases_parent_id ON payment.session_pack_purchases (parent_id);
    CREATE INDEX idx_session_pack_purchases_coach_player ON payment.session_pack_purchases (coach_id, player_id);
    ```
  - [x] Confirm the table is actually empty in the target environment before relying on `NOT NULL` with no default — check via `SELECT count(*) FROM payment.session_pack_purchases` in a local/UAT DB session if any doubt remains; if not empty, add a default or backfill step instead of assuming.

- [x] **Task 2 — Entity, repository, and purchase-flow changes** (AC: 1, 2, 3)
  - [x] `SessionPackPurchase.java`: add `@Column(name = "player_id", nullable = false) private Long playerId;`, `@Column(name = "paused_until") private Instant pausedUntil;`, `@Column(name = "expired_notified_at") private Instant expiredNotifiedAt;`
  - [x] `SessionPackPurchaseRepository.java`: add
    ```java
    List<SessionPackPurchase> findByParentIdOrderByCreatedAtDesc(Long parentId);

    @Query("SELECT p FROM SessionPackPurchase p WHERE p.expiresAt < :now AND p.expiredNotifiedAt IS NULL AND p.remainingSessions > 0")
    List<SessionPackPurchase> findExpiredNotYetNotified(@Param("now") Instant now);
    ```
  - [x] `SessionPackPaymentService.purchasePack(...)`: add a `Long playerId` parameter; verify ownership via `playerProfileRepository.findByIdAndParentId(playerId, parentId)` → `ResourceNotFoundException` if empty (inject `PlayerProfileRepository` into `SessionPackPaymentService`). Set `purchase.setPlayerId(playerId)` in `createPurchase(...)`. Update the 5 existing call sites in `PackPriceLockedOnPurchaseTest.java` and `SessionPackPurchaseIT.java` for the new signature.
  - [x] Update `SessionPackPaymentResource`'s purchase endpoint request DTO to include `playerId`, and the shared `SessionPackPurchaseResponse` record to add `playerId`, `pausedUntil`, and `status` fields — `status` is computed in the mapping method (`toResponse(...)`) per the AC-3 precedence and included on every response, purchase and list alike (a fresh purchase simply computes to `"ACTIVE"`).
  - [x] Add `GET /api/payment/session-packs` (optional `coachId` query param) to `SessionPackPaymentResource`, backed by a new `SessionPackPaymentService.getPacksForParent(Long parentId, UUID coachId)` method: calls `findByParentIdOrderByCreatedAtDesc`, filters by `coachId` in-memory if provided, maps each to the shared response shape.

- [x] **Task 3 — Pause capability** (AC: 4)
  - [x] Add `pausePack(Long parentId, UUID purchaseId, PausePackRequest req)` to `PackSessionService` per the exact behavior in AC 4. Inject `BookingRepository`, `BookingService`, `ConfigService`, `ApplicationEventPublisher`, `CoachProfileRepository`, `UserRepository` (the latter two are required to build `PackPausedEvent`'s `coachDisplayName`/`parentEmail`/`canonicalTimezone` — see legacy `SessionPackService.pausePack()` lines 254-257 for the exact resolution pattern). No `@Lazy` needed: `BookingService` does not inject `PackSessionService` anywhere today (only legacy `SessionPackService` and `SessionPackPurchaseRepository`), and `PaymentLifecycleService` talks to booking only via `@TransactionalEventListener`, not field injection — there is no circular bean graph to break here, unlike legacy's `SessionPackService ↔ BookingService` cycle.
  - [x] Add `POST /api/payment/session-packs/{purchaseId}/pause` to `SessionPackPaymentResource`, `@PreAuthorize` parent role, body = `PausePackRequest`, returns `PauseConflictResponse` (HTTP 200 in both the applied and not-applied cases).
  - [x] Read `pack.pause.maxDays` from `ConfigService` — this key already exists (seeded in `V37__session_pack_expiry_pause.sql`, read today by legacy `SessionPackService.pausePack()`) — reuse it, do not introduce a second config key.

- [x] **Task 4 — Forfeiture scheduler + missing SchedulerLock** (AC: 5, 6)
  - [x] Create `SessionPackForfeitureScheduler.java` in `platform.payment.service`, structured like legacy `SessionPackExpiryScheduler` (inject `SessionPackPurchaseRepository`, `CoachProfileRepository`, `UserRepository`, `ApplicationEventPublisher`, `TransactionTemplate` for per-row transactional isolation with try/catch-per-row so one failing pack doesn't block the batch — copy this resilience pattern from legacy lines 43-67).
  - [x] Add `@SchedulerLock(name = "SessionPackExpiryNotifier_warn", lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")` to `SessionPackExpiryNotifier.notifyExpiringPacks()`.

- [x] **Task 5 — Tests** (AC: 7)
  - [x] Extend/create service tests for: `purchasePack` with `playerId` (including the new ownership-mismatch rejection), `getPacksForParent` (status computation for all four states, `coachId` filter), `pausePack` (happy path, double-pause rejection, unconfirmed-conflict returns without applying, confirmed-conflict cancels bookings and applies pause), `SessionPackForfeitureScheduler` (marks-once idempotency — run twice, assert only one event published and `expiredNotifiedAt` prevents a second).
  - [x] Do not modify any existing legacy (`booking.service.SessionPackService`/`SessionPackExpiryScheduler`) tests — they must remain green, untouched, proving this story didn't touch the legacy path.

### Review Findings

- [x] [Review][Decision] Pack shows `"PAUSED"` immediately upon confirmation even when `pauseStartDate` is in the future — `computeStatus()`'s AC3 precedence (`pausedUntil != null && pausedUntil.isAfter(now)` → `PAUSED`) flips as soon as the pause is confirmed. **Resolved 2026-08-03: accepted as intended behavior, no fix needed** — immediate `PAUSED` display upon confirmation is the desired UX. [`SessionPackPaymentService.java` `computeStatus`, `PackSessionService.java` `pausePack`]

- [x] [Review][Patch] Forfeiture scheduler publishes `SessionPackExpiredEvent` before persisting `expiredNotifiedAt` [`SessionPackForfeitureScheduler.java:37-53`] — if `save()` fails or the process crashes between publish and save, the pack is re-notified on the next hourly run with no de-dup. This also deviates from the legacy `SessionPackExpiryScheduler.expireActivePacks()` pattern (save-then-publish) that Task 4 explicitly said to mirror. **Fixed**: reordered to `save()` before `publishEvent(...)`.
- [x] [Review][Patch] N+1 query in `getPacksForParent` [`SessionPackPaymentService.java` `getPacksForParent`] — calls `sessionPackTierRepository.findById(...)` once per pack inside the mapping stream. **Fixed**: batch-fetch tiers via `findAllById` once into a map, then look up by id in the mapping step.
- [x] [Review][Patch] Duplicate ids in `confirmedCancellationIds` are not deduplicated [`PackSessionService.java` `pausePack`, `validatedIds` loop] — a duplicate id causes `bookingService.cancelDueToPause` to be called twice for the same booking; the second call is expected to throw on an already-cancelled booking, aborting the whole pause. **Fixed**: added `.distinct()` before filtering to the live conflict set.
- [x] [Review][Patch] No index supports the new hourly `findExpiredNotYetNotified` query [`V88__session_pack_purchases_parity.sql`] — it filters on `expires_at`, `expired_notified_at IS NULL`, and `remaining_sessions > 0`, but only `parent_id` and `(coach_id, player_id)` indexes were added in this migration. **Fixed**: added a partial index `idx_session_pack_purchases_expiry_notify` on `expires_at WHERE expired_notified_at IS NULL`.

- [x] [Review][Defer] Partial/mismatched `confirmedCancellationIds` lets `pausePack` apply the pause even when not all currently-conflicting bookings are confirmed for cancellation (or the confirmed ids don't match any real conflict) [`PackSessionService.java` `pausePack`] — deferred, pre-existing (verified byte-for-byte identical to legacy `SessionPackService.pausePack()`; AC4 explicitly requires mirroring legacy here)
- [x] [Review][Defer] Silent `.orElse(null)`/`.orElse("")` defaulting for missing coach/parent records in `pausePack` and `SessionPackForfeitureScheduler` (blank email, `"Coach"` placeholder) — deferred, pre-existing (identical to legacy's own resolution pattern)
- [x] [Review][Defer] `pauseStartDate` "in the past" check truncates to UTC day boundary, ignoring coach/parent timezone [`PackSessionService.java` `pausePack`] — deferred, pre-existing (byte-for-byte identical to legacy `SessionPackService.pausePack()` lines 205-216)
- [x] [Review][Defer] `configService.getLong("pack.pause.maxDays")` has no defensive default if the config key is missing/non-numeric — deferred, pre-existing (identical usage to legacy, same risk profile)
- [x] [Review][Defer] `pausePack` holds a pessimistic row lock across booking cancellations and event publishing within one `@Transactional` method — deferred, pre-existing (same single-transaction shape as the legacy method this story mirrors)
- [x] [Review][Defer] Inconsistent concurrency control: `pausePack` takes a pessimistic lock but `deductSession`/`restoreSession`/`extendPack` do not — deferred, pre-existing (those methods predate this story and are unmodified except for call-site signature changes)
- [x] [Review][Defer] `SessionPackForfeitureScheduler` doesn't re-verify `expiresAt` immediately before forfeiting inside the per-row transaction, leaving a window where a concurrent extension could still get forfeited — deferred, pre-existing (inherent to the legacy-mirrored select-then-per-row-transaction scheduler shape)
- [x] [Review][Defer] TOCTOU between the conflicting-bookings query and the per-booking `cancelDueToPause` calls in `pausePack` — deferred, pre-existing (same risk shape as the legacy method being mirrored)
- [x] [Review][Defer] Stringly-typed computed `status` field and hardcoded `CONFLICT_STATUSES` list rather than shared enums — deferred, pre-existing (consistent with existing codebase convention; legacy also uses string status constants)

## Dev Notes

- **Do not touch `BookingService.java`, `SessionPackService.java` (booking module), or `SessionPackExpiryScheduler.java` (booking module) in this story.** They are Story 11.2/11.3's responsibility. This story only adds capability to the `payment` module so those later stories have something safe to cut over to.
- **Anti-duplication — reuse these existing `booking.contract` types verbatim, do not create new ones:** `PausePackRequest`, `PauseConflictResponse`, `ConflictingBookingItem`, `PackPausedEvent`, `SessionPackExpiredEvent`, `SessionPackExhaustedEvent` (already reused correctly by `PackSessionService.deductSession()` — follow that precedent). These types living in `booking.contract` rather than `payment.contract` is pre-existing (cross-module reuse already established by Story 7.2), not something to "fix" here.
- **Why no persisted `status` column:** the new path has never had one (unlike legacy's `status` column driving `STATUS_ACTIVE`/`STATUS_EXHAUSTED`). Computing status on read (AC 3) avoids a sync-drift class of bugs (persisted status disagreeing with the fields that actually determine it) and keeps this story's migration minimal. `expiredNotifiedAt` is the only new "state" column, and it exists purely for scheduler idempotency, not to represent pack status.
- **No circular-dependency workaround needed here** (unlike legacy): legacy `SessionPackService` needs `@Lazy` on its `BookingService` field because `BookingService` itself injects `SessionPackService` (`BookingService.java:103`), creating a real cycle. `BookingService` does **not** inject `PackSessionService` anywhere — plain constructor injection (`@RequiredArgsConstructor`) is sufficient in the new `pausePack()`.
- **`PlayerProfileRepository` ownership check** — use `findByIdAndParentId(playerId, parentId)`, the repository's own documented convention (`PlayerProfileRepository.java:14-15`: "Always use this instead of findById — parentId enforces family isolation"), already used by `ShadowAccountService`, `PlayerOwnershipGuard`, `MessagingService`, `MessagingResource`, and `HomeworkAssignmentService`. `BookingService.createBookingRequest()`'s manual `findById` + `Objects.equals(parentId)` (lines 151-155) predates that helper and is the one place in the codebase that never adopted it — do not copy it into new code:
  ```java
  PlayerProfile player = playerProfileRepository.findByIdAndParentId(playerId, parentId)
      .orElseThrow(() -> new ResourceNotFoundException("Player not found", "player_profile"));
  ```
- **Config key reuse:** `pack.pause.maxDays` is already read by legacy `SessionPackService.pausePack()` (`configService.getLong("pack.pause.maxDays")`) — reuse the same key, don't add a `payment.pack.pause.maxDays` duplicate.
- **`findConflictingBookingsForPause` already exists** on `BookingRepository` (used by legacy pause today) — it is not booking-module-private, it's a public repository method, safe to call from `PackSessionService` in the `payment` module.

### Project Structure Notes

- New scheduler goes in `com.softropic.skillars.platform.payment.service` (alongside `PackSessionService`, `SessionPackPaymentService`, `SessionPackExpiryNotifier`) — not the `booking` package, to keep the new path's code fully contained in `payment` per the module boundary Story 7.2 established.
- Migration file: `src/main/resources/db/migration/V88__session_pack_purchases_parity.sql` — confirm `V88` is still the next free version number immediately before writing (latest at story-creation time was `V87__booking_overlap_exclusion_constraint.sql`); if another migration has landed in the meantime, use the next free number instead.
- No frontend changes in this story — `SessionPackPurchasePage.vue`/`SessionPackTracker.vue`/`payment.api.js` are Story 11.2's scope.

### References

- [Source: src/main/java/.../platform/booking/service/SessionPackService.java] — legacy `pausePack()` (lines 189-264) is the direct behavioral template for AC 4/Task 3.
- [Source: src/main/java/.../platform/booking/service/SessionPackExpiryScheduler.java] — direct template for the forfeiture scheduler's resilience pattern (per-row `TransactionTemplate` + try/catch).
- [Source: src/main/java/.../platform/payment/service/PackSessionService.java] — existing `deductSession()`/`restoreSession()` show the correct event-reuse pattern (`SessionPackExhaustedEvent` reused from `booking.contract`, comment: "do not create a duplicate").
- [Source: src/main/java/.../platform/payment/service/SessionPackPaymentService.java] — existing `purchasePack()`/`extendPack()` show the established transaction boundaries (Stripe calls outside `@Transactional`) to preserve when adding `playerId`.
- [Source: src/main/java/.../platform/payment/repo/SessionPackPurchase.java, SessionPackPurchaseRepository.java] — current schema/queries this story extends.
- [Source: _bmad-output/implementation-artifacts/skillars-7-3-cancellation-refund-reliability-strikes.md#Story 7.2 Deprecation Cleanup] — the deferred decision this epic finally resolves.
- [Source: _bmad-output/planning-artifacts/architecture.md#Decision: Session Credit Tracking] — original architectural intent (single `SessionPack` entity with both `coach_id` and `player_id`) that this story restores alignment with.
- [Source: git commit 25f1b05 "skips locking for new payment.session_pack_purchases path"] — the most recent commit on this exact codepath (2026-08-03), resolved deferred item D10 (`BookingService.java` lines 233-238 skip the legacy lock when `sessionPackPurchaseId != null`). Confirms `BookingService.java` is intentionally untouched by this story — Story 11.2 will remove that `if` branch entirely once the legacy path is no longer needed.
- [Source: _bmad-output/implementation-artifacts/skillars-deferred-4.md] — established the `@SchedulerLock` convention (lock names, `lockAtMostFor`/`lockAtLeastFor` values) this story follows for the new forfeiture scheduler and the notifier fix.

## Dev Agent Record

### Agent Model Used

claude-sonnet-5

### Debug Log References

- Full `mvn verify` (unit + Testcontainers IT suite, 783 unit tests + all IT classes) initially surfaced 13 IT failures across 5 pre-existing test classes (`BookingRequestResourceIT`, `BatchPaymentIT`, `PackCancellationRefundIT`, `PackExtensionIT`, `PaymentWebhookIdempotencyIT`) — all `DataIntegrityViolationException: null value in column "player_id"`, caused by those tests raw-JDBC-inserting into `payment.session_pack_purchases` without the new NOT NULL `player_id` column added in this story's migration. Fixed by adding a `player_id` value to each raw INSERT (no FK on that column, so any Long satisfies the constraint — used existing `PLAYER_ID` constants where present, added one where absent). Re-ran all 5 classes plus the full unit suite after the fix: 100% green, 0 failures/errors.

### Completion Notes List

- Ultimate context engine analysis completed — comprehensive developer guide created. Story scope and phasing (3-story split, no data migration needed, add playerId) confirmed with product owner 2026-08-03. Independent fact-check pass run against the live codebase; corrected a broken reference path, resolved an AC7/Task-2 contradiction over `purchasePack()`'s signature change, replaced an outlier ownership-check pattern with the repository's documented convention, closed a design gap on how `pausePack()` obtains `playerId`, completed `PackSessionService`'s pause-time dependency list, and removed an unfounded `@Lazy`/circular-dependency requirement.
- Implemented all 5 tasks: Flyway migration V88 (player_id/paused_until/expired_notified_at + 2 indexes); entity/repo/purchase-flow changes (playerId param + ownership check on purchasePack, getPacksForParent list endpoint, computed status field on SessionPackPurchaseResponse); PackSessionService.pausePack() mirroring legacy behavior exactly (ownership via parentId only, one-pause-per-lifetime, conflict detection/confirmation/cascade-cancel via existing BookingRepository/BookingService methods, PackPausedEvent reuse); SessionPackForfeitureScheduler (new, ShedLock-protected, per-row TransactionTemplate resilience) plus the missing @SchedulerLock on SessionPackExpiryNotifier.
- Legacy `BookingService`, `booking.service.SessionPackService`, `booking.service.SessionPackExpiryScheduler`, and all frontend files were left untouched, as required — confirmed via `SessionPackExpirySchedulerTest`/`SessionPackServiceTest`/`BookingServiceTest` all remaining green and unmodified.
- All 5 `purchasePack(...)` call sites updated for the new `playerId` parameter (`PackPriceLockedOnPurchaseTest.java` x2, `SessionPackPurchaseIT.java` x3); `BasePaymentIT` gained `insertTestParent`/`insertTestPlayer` helpers since `player_profiles.parent_id` has an FK to `main.user` and the new ownership check requires a real, owned player row in IT tests.
- New unit tests added: `SessionPackPaymentServiceTest` (ownership-mismatch rejection, 4-state status computation, coachId filter), `PackSessionServicePauseTest` (happy path, already-paused, wrong-parent, unconfirmed conflict, confirmed conflict cascade-cancel), `SessionPackForfeitureSchedulerTest` (marks-once idempotency across two runs, per-row failure isolation, no-op on empty result).
- Full regression suite (`mvn verify`, unit + Testcontainers IT, 783 unit tests + full IT suite) passes with 0 failures/errors after the fixture fix described in Debug Log References.

### File List

**New files:**
- `src/main/resources/db/migration/V88__session_pack_purchases_parity.sql`
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackForfeitureScheduler.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/PackSessionServicePauseTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackForfeitureSchedulerTest.java`

**Modified files:**
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchase.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/SessionPackPurchaseResponse.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/PurchaseSessionPackRequest.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackExpiryNotifier.java`
- `src/main/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResource.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/PackPriceLockedOnPurchaseTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPurchaseIT.java`
- `src/test/java/com/softropic/skillars/platform/payment/BasePaymentIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java` (fixture fix only — added `player_id` to raw INSERT)
- `src/test/java/com/softropic/skillars/platform/payment/service/PaymentWebhookIdempotencyIT.java` (fixture fix only)
- `src/test/java/com/softropic/skillars/platform/payment/service/PackCancellationRefundIT.java` (fixture fix only)
- `src/test/java/com/softropic/skillars/platform/payment/service/PackExtensionIT.java` (fixture fix only)
- `src/test/java/com/softropic/skillars/platform/payment/service/BatchPaymentIT.java` (fixture fix only)

## Change Log

- 2026-08-03: Implemented Story 11.1 — added per-player scoping, pause/resume, parent-facing list query, and idempotent expiry forfeiture to `payment.session_pack_purchases`, fully additive alongside the untouched legacy `booking` pack system. Fixed 5 pre-existing IT tests whose raw-SQL fixtures needed the new `player_id` NOT NULL column. Status → review.
