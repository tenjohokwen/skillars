# Story 6.4: Streaming Security & Video Lifecycle

Status: done

## Audit Blockers (must resolve before dev starts)

**BLOCKER-1 — `platform.payment` module does not exist.** The story references `platform.payment.contract.event.SubscriptionExpiredEvent`, `platform.payment.adapter.PlayerSubscriptionQueryAdapter`, and a `player_subscriptions` table. None of these exist in the codebase. The payment-adjacent module is `platform.booking`; the analogous event is `SessionPackExpiredEvent` (in `platform.booking.contract`), which carries `Long playerId` (not UUID) and has no subscription-tier concept. Tasks 4, 6, and the yearly-exemption path in Task 5 will not compile until this is resolved. Resolution options: (a) block on Epic 7 (Payment) and keep this story `blocked`, or (b) redesign ACs 6 and 9 against `SessionPackExpiredEvent` from `platform.booking` and confirm whether the YEARLY/MONTHLY tier concept applies to session packs at all.

**BLOCKER-2 — `QuotaService.decrementStorage()` does not exist.** Task 5 calls `quotaService.decrementStorage(v.getOwnerId(), released)` in the ARCHIVED→DELETED phase, but `QuotaService`'s public API has no such method (only `check`, `reserve`, `commit`, `release`). Additionally, `QuotaService` is not listed in `VideoLifecycleScheduler`'s injected dependencies. Add a `decrementStorageBytes(String ownerId, long bytes)` method to `QuotaService` as an explicit subtask before Task 5 is started.

**BLOCKER-3 — `Video.ownerId` is `String`, not `UUID`.** Task 2 states "parameter type is `UUID` (not `String`) to match the `videos.owner_id` column type." The `Video` entity declares `private String ownerId`. Native queries using `UUID` parameters on a `VARCHAR` column is JDBC-driver-dependent and is incorrect here. All `findBy*Owner` query parameters must use `String`. Additionally, `SessionPackExpiredEvent.playerId` is `Long` — the story never bridges `Long playerId` (from the booking event) to `String ownerId` (on the Video entity). This cross-module ID mapping must be defined before the outbox processor can be written.

## Story

As a platform operator,
I want all video playback secured with short-lived signed URLs and videos automatically progressing through their retention lifecycle,
so that unauthorised access is structurally impossible and storage is reclaimed on schedule.

## Acceptance Criteria

1. **Given** an authorised owner requests playback of a `READY` + `ACTIVE` video **When** `GET /api/video/{id}/play` is called **Then** the server generates a Bunny CDN signed HLS URL with TTL = `ConfigService["platform.video.playback.signed_url_ttl_minutes"]` (default 120 minutes); the raw CDN URL is never returned directly to clients.

2. **Given** `ConfigService["platform.video.playback.ip_binding_enabled"]` is `true` **When** a signed URL is generated **Then** the client IP extracted from the HTTP request is bound into the HMAC signature; the CDN URL includes `&clientIp=<ip>` so Bunny Edge rejects requests from mismatched IPs.

3. **Given** a `COACH_REVIEW` video **When** a signed URL is generated **Then** only `signedHlsUrl` is returned — no `signedDownloadUrl` is included in the response; additionally, `Content-Disposition: inline` must be enforced at the CDN level to prevent direct HLS segment download by clients who capture the URL — not generating `signedDownloadUrl` alone is insufficient because a motivated user can download `.m3u8` + segments directly (FR-VID-009). If Bunny signed URLs support a `Content-Disposition` override query parameter, set it; otherwise configure a Bunny Edge Rule on the Stream library to force `Content-Disposition: inline` for all HLS requests, and document this as a required deployment step in the runbook — it is not optional.

4. **Given** a `HOMEWORK` or `DRILL_DEMO` video **And** the requester is the video owner **When** a signed URL is generated **Then** the response includes both `signedHlsUrl` and `signedDownloadUrl`; the `signedDownloadUrl` signs the `/original` path with the same TTL and IP-binding policy.

   **Note — DRILL_DEMO ownership**: A `DRILL_DEMO` video is created by a coach. Confirm with `platform.session` whether a player can hold `owner_id` on a `DRILL_DEMO` record (e.g. via a cloned drill in Story 4.1). If no player can own a DRILL_DEMO, the `DRILL_DEMO` branch here is dead code for the player portal. Add a comment in `PlaybackService` documenting the expected ownership model for DRILL_DEMO regardless of the finding.

   **Epics conflict — DRILL_DEMO download contradicts FR-VID-009**: The epics state "coach drill videos not downloadable" (FR-VID-009). Including `DRILL_DEMO` in the download URL path may conflict with this. Confirm with product whether a player-owned clone of a drill demo (if such ownership is possible) is permitted to be downloaded, or whether FR-VID-009 categorically excludes all DRILL_DEMO video types from download regardless of owner. If FR-VID-009 is categorical, remove DRILL_DEMO from this AC and generate `signedDownloadUrl` for HOMEWORK only.

5. **Given** a user attempts to play a video that is `OperationalState.LOCKED` (moderation), `OperationalState.HIDDEN` (minor safety gate — awaiting parent approval), `AccessState.BLOCKED`, `AccessState.ARCHIVED`, or `OperationalState.DELETED` **When** `GET /api/video/{id}/play` is called **Then** the response is `403 Forbidden` with `ErrorDto` code `video.notAccessible`; no URL is generated.

   **Note — HIDDEN and parental review (Story 6.5/6.6)**: Blocking `HIDDEN` videos for the owner (a minor player) is correct for this story. However, the parental approval flow in Story 6.6 requires a parent to *watch* a `HIDDEN` video before approving it. Story 6.5 must explicitly add a `parentOf(owner)` permission path that bypasses the `HIDDEN → 403` block for the approval use case. Do not attempt to add parent access here — `/play` in Story 6.4 is owner-only; this note is a handoff to Story 6.5's `@videoAccessGuard`.

6. **Given** a video's associated subscription expires **When** the platform publishes `SubscriptionExpiredEvent` from `platform.payment` **And** the subscriber has no other active subscription at event-processing time **Then** `platform.video` sets the video's `AccessState` → `BLOCKED` and records `lifecycle_locked_at = now()` for all videos owned by the subscriber where `operationalState = READY` — this is day 0 of the lifecycle window; if `hasAnyActiveSubscription(subscriberId)` returns true at processing time, the BLOCKED transition is skipped entirely and videos remain `ACTIVE`.

   **BLOCKER — `platform.payment` does not exist.** `SubscriptionExpiredEvent` from `platform.payment` does not exist in the codebase. The real event is `SessionPackExpiredEvent` in `platform.booking.contract`, which carries `Long playerId` and has no subscription-tier field. This AC cannot be implemented until either (a) Epic 7 creates `platform.payment` and `SubscriptionExpiredEvent`, or (b) this AC is redesigned to listen to `SessionPackExpiredEvent` from `platform.booking`. If redesigning for session packs, the YEARLY/MONTHLY tier distinction in AC 9 also collapses — session packs have no tier. See BLOCKER-1 and BLOCKER-3.

   **Simultaneous MONTHLY+YEARLY expiry race — unhandled gap:** If a player holds concurrent MONTHLY and YEARLY subscriptions that expire on the same billing day, the outbox processor is event-order-dependent. When the MONTHLY event is processed first, `hasAnyActiveSubscription()` returns `true` (yearly still active at that moment) → Path B skips BLOCKED → videos remain ACTIVE. When the YEARLY event is then processed, Path A looks for already-BLOCKED videos to reset the clock → finds none → is a no-op. End state: videos permanently ACTIVE with no active subscription, never caught by any subsequent run. Mitigation: add a periodic reconciliation task that checks for ACTIVE videos whose owner has no active subscription and logs/alerts — this reconciliation is out of scope for the initial implementation but must be created before the lifecycle mechanism is trusted in production.

   **Scope gap — session pack expiry:** The epics AC for Story 6.4 explicitly includes "subscription **or session pack** expires" as the day-0 trigger (FR-VID-010). Session pack expiry is **not implemented here** and currently has no story home. This is a known FR-VID-010 gap. **Before closing Story 6.4**, either: (a) add a session pack lifecycle AC to Story 6.5, or (b) create a dedicated backlog story for session pack lifecycle. The `VideoSubscriptionLifecycleListener` must not be described or documented as handling session packs — its scope is subscription expiry only.

   **Scope gap — account deletion:** The epics AC for Story 6.4 explicitly states "at day 90 **or immediately on account deletion**, `videos.status` transitions to `PURGED`." Immediate purge on account deletion is **not implemented here** and is deferred to Story 6.5 (`AccountDeletionCascadeListener` AC). This gap is not flagged elsewhere in this story. **Before closing Story 6.4**, confirm Story 6.5 explicitly covers the immediate-purge path and that no video retention gap exists between the two stories.

7. **Given** the `VideoLifecycleScheduler` daily job runs **When** it finds a video with `accessState = BLOCKED AND lifecycle_locked_at < now() - 30 days` **Then** the video's `AccessState` transitions → `ARCHIVED`; `BunnyVideoProviderAdapter.archiveAsset()` is called outside `@Transactional`; on success the DB commit sets `accessState = ARCHIVED`; a `video_lifecycle_log` row is appended with `(videoId, BLOCKED, ARCHIVED, now())`.

8. **Given** a video with `accessState = ARCHIVED AND archived_at < now() - 90 days` **When** the lifecycle job evaluates it **Then** `BunnyVideoProviderAdapter.deleteAsset()` is called outside `@Transactional`; on success `operationalState` transitions → `DELETED`, `videos.storageBytes` is set to 0, and `video_quotas.storageUsedBytes` is decremented by the video's `storageBytes` value (restoring the owner's quota allocation); a `video_lifecycle_log` row is appended with `(videoId, ARCHIVED, DELETED, now())`.

9. **Given** a player's videos are in `BLOCKED` state (from a prior subscription expiry) **And** the player subsequently renewed or upgraded to an active yearly subscription **When** the lifecycle job evaluates their videos **Then** videos remain `BLOCKED` without transitioning to `ARCHIVED` — the archive clock is paused for as long as a yearly subscription is active (FR-PAY-008); **when the yearly subscription later expires without renewal**, `lifecycle_locked_at` is **reset to the yearly expiry date** (the new day 0) — the epics explicitly state "the standard 30-day LOCKED window begins from the expiry date" (Story 6.4 AC); the `VideoSubscriptionLifecycleListener` performs this reset when processing a `SubscriptionExpiredEvent` for a YEARLY-tier subscription: for all videos owned by the subscriber that are already in `BLOCKED` state, it updates `lifecycle_locked_at = now()` without changing `accessState`; the 30-day BLOCKED→ARCHIVED clock then runs from that reset date. **`SubscriptionExpiredEvent` must carry the expired subscription's `tier` field** — if it does not currently, extend the event record in `platform.payment.contract.event` as part of Task 4.

   **BLOCKER — depends on `platform.payment` and tier concept.** The YEARLY/MONTHLY tier distinction required by this AC does not exist in `platform.booking.SessionPackExpiredEvent`. Until Epic 7 defines subscription tiers, Path A (YEARLY clock reset) and Path B (non-YEARLY BLOCKED) cannot be implemented. Resolve BLOCKER-1 first.

   **Scheduler timing race — yearly expiry before outbox drains:** There is a race window where the yearly subscription expires and the outbox has not yet run Path A (clock reset). If the scheduler runs in this window, it sees `hasActiveYearlySubscription() = false` and evaluates `lifecycle_locked_at` against the original monthly expiry threshold (T0), potentially advancing the video to ARCHIVED before the clock is reset to T1. This is partially mitigated by the `hasActiveYearlySubscription()` check in the scheduler — once the yearly expires, that check returns false and the video can advance. The missing test case (see Task 13) must cover this scenario explicitly to confirm the behavior is acceptable or identify a required ordering guarantee.

10. **Given** the lifecycle `@Scheduled` job runs **When** it processes videos **Then** all DB queries use `FOR UPDATE SKIP LOCKED`; each video transitions at most one lifecycle state per job run; external Bunny calls are always outside `@Transactional`.

11. **Given** `GET /api/video/{id}/status` is called **When** a video has `operationalState=READY` but `accessState=BLOCKED` **Then** the response includes a `displayState` field equal to `"SUBSCRIPTION_LOCKED"` so the frontend can render the correct locked-subscription visual; `displayState` is `"ARCHIVED"` when `accessState=ARCHIVED`; otherwise `displayState` equals `operationalState.name()`. The complete priority order is: `accessState=BLOCKED → "SUBSCRIPTION_LOCKED"`, `accessState=ARCHIVED → "ARCHIVED"`, else `operationalState.name()`. Note: when `operationalState=HIDDEN`, the else-branch produces `displayState="HIDDEN"` — this is correct and expected; the `HIDDEN` statusConfig in `VideoStatusCard` was added in Story 6.3 and must not be removed by this story's changes.

12. **Given** the `VideoStatusCard` receives `displayState = "SUBSCRIPTION_LOCKED"` **When** it renders **Then** it shows a padlock icon with warning color — distinct from moderation `LOCKED` (negative/red) — and the status label reads the `video.status.SUBSCRIPTION_LOCKED` i18n key.

13. **Given** the `VideoStatusCard` receives `displayState = "DELETED"` **When** the parent component receives the `status-changed` event **Then** the card is removed from the list immediately; the card itself does not render a deleted state — removal is the correct UX. (`"PURGED"` is not a valid displayState — `OperationalState.DELETED` is the terminal purge state; the string `"PURGED"` must not appear in any guard, config, or test assertion.)

14. **Given** all lifecycle window values **When** read by the scheduler **Then** they come exclusively from `ConfigService` — never hardcoded — using keys: `platform.video.lifecycle.blocked_to_archived_days` (default 30), `platform.video.lifecycle.archived_to_deleted_days` (default 90), `platform.video.lifecycle.batch_size` (default 100).

## Tasks / Subtasks

- [x] **Task 1 — Flyway V58: lifecycle schema** (AC: 6, 7, 8, 14)
  - [x] Add column `lifecycle_locked_at TIMESTAMPTZ NULL` to `main.videos` — set when `accessState` first transitions `ACTIVE → BLOCKED`; never reset on subsequent transitions
  - [x] Add column `archived_at TIMESTAMPTZ NULL` to `main.videos` — set when `accessState` transitions `BLOCKED → ARCHIVED`; used as the reference clock for the ARCHIVED→DELETED 90-day threshold (prevents batch-skipping two states in one scheduler run when `lifecycle_locked_at` is already > 90 days old)
  - [x] Create `main.video_lifecycle_log` table: `(id UUID PK DEFAULT gen_random_uuid(), video_id UUID NOT NULL REFERENCES main.videos(id), from_state VARCHAR(64) NOT NULL, to_state VARCHAR(64) NOT NULL, triggered_by VARCHAR(64) NOT NULL DEFAULT 'SYSTEM', transitioned_at TIMESTAMPTZ NOT NULL DEFAULT now())`; add indexes: `CREATE INDEX idx_vllog_video_id ON main.video_lifecycle_log(video_id)` and `CREATE INDEX idx_vllog_transitioned_at ON main.video_lifecycle_log(transitioned_at DESC)` — the second index enables efficient ops queries for "transitions in the last N hours" without a full-table scan
  - [x] Add check constraint to prevent the silent-orphan case: `ALTER TABLE main.videos ADD CONSTRAINT chk_lifecycle_locked_at_when_blocked CHECK (access_state != 'BLOCKED' OR lifecycle_locked_at IS NOT NULL)` — a BLOCKED video with NULL `lifecycle_locked_at` is never selected by the scheduler query (`NULL < threshold` is NULL in SQL) and would be silently skipped forever
  - [x] Create `main.subscription_lifecycle_outbox` table for at-least-once subscription expiry processing: `(id UUID PK DEFAULT gen_random_uuid(), subscriber_id UUID NOT NULL, subscription_tier VARCHAR(32) NOT NULL, expired_at TIMESTAMPTZ NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0, last_error TEXT, processed_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now())`; add index on `(status, created_at)`
  - [x] Seed ConfigService keys: `platform.video.lifecycle.blocked_to_archived_days = 30`, `platform.video.lifecycle.archived_to_deleted_days = 90`, `platform.video.lifecycle.batch_size = 100`, `platform.video.playback.signed_url_ttl_minutes = 120`, `platform.video.playback.ip_binding_enabled = false`, `platform.video.lifecycle.outbox_max_attempts = 5`
  - [x] Create `platform.video.contract.LifecycleTrigger` constants class (or enum) defining `triggered_by` values used in `video_lifecycle_log`: `SYSTEM` (scheduler-initiated), `SUBSCRIPTION_EXPIRY` (outbox processor Path B), `YEARLY_EXPIRY_CLOCK_RESET` (outbox processor Path A), `ACCOUNT_DELETION` (Story 6.5). All `VideoLifecycleLog` writes must use one of these constants — no raw string literals. Tests assert `triggered_by` against these constants.

- [x] **Task 2 — Extend `VideoRepository` with lifecycle queries** (AC: 7, 8, 9, 10)
  - [x] Add `findBlockedExceedingThreshold(@Param Instant threshold, @Param int batchSize)` — native query: `SELECT * FROM main.videos WHERE access_state = 'BLOCKED' AND lifecycle_locked_at < :threshold ORDER BY lifecycle_locked_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED`
  - [x] Add `findArchivedExceedingThreshold(@Param Instant threshold, @Param int batchSize)` — uses `archived_at` (not `lifecycle_locked_at`) as the threshold column: `SELECT * FROM main.videos WHERE access_state = 'ARCHIVED' AND archived_at < :threshold ORDER BY archived_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED`; this prevents a video that was just archived in Phase 1 of the same scheduler run from immediately advancing to DELETED in Phase 2
  - [x] Add `findActiveReadyByOwner(@Param String ownerId, @Param int batchSize)` — for initial ACTIVE→BLOCKED transition on subscription expiry: `SELECT * FROM main.videos WHERE owner_id = :ownerId AND operational_state = 'READY' AND access_state = 'ACTIVE' ORDER BY created_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED`; **parameter type is `String`** — `Video.ownerId` is declared `private String ownerId` in the entity (maps to a `VARCHAR` column); passing `UUID` to this native query is incorrect and JDBC-driver-dependent. **Cross-module ID bridge required:** the outbox row stores `subscriber_id` derived from `SubscriptionExpiredEvent.subscriberId` (UUID); the video's `owner_id` is a String. Before calling this query, confirm that `subscriberId.toString()` matches the format stored in `videos.owner_id` — if `ownerId` stores a plain username rather than a UUID string, the lookup will return zero results silently. Verify this bridge as a pre-condition for Task 4.
  - [x] Add `findBlockedReadyByOwner(@Param String ownerId, @Param int batchSize)` — for yearly-subscription-expiry `lifecycle_locked_at` clock reset (AC 9): `SELECT * FROM main.videos WHERE owner_id = :ownerId AND operational_state = 'READY' AND access_state = 'BLOCKED' ORDER BY lifecycle_locked_at ASC NULLS LAST LIMIT :batchSize FOR UPDATE SKIP LOCKED`; same `String` parameter type rule applies — see `findActiveReadyByOwner` note above
  - [x] Add `VideoLifecycleLogRepository` (JpaRepository for `VideoLifecycleLog` entity) — append-only audit

- [x] **Task 3 — `VideoLifecycleLog` entity** (AC: 7, 8)
  - [x] Create `platform.video.repo.VideoLifecycleLog` entity — fields: `id UUID`, `videoId UUID`, `fromState String`, `toState String`, `triggeredBy String`, `transitionedAt Instant`
  - [x] No setter for `id` or `transitionedAt` — use `@PrePersist` to set `transitionedAt = Instant.now()` and UUID generation

- [ ] **Task 4 — `SubscriptionExpiredEventListener` (day 0 trigger)** (AC: 6, 9) ⚠️ **[MOVED → Story 7.4]** — blocked on `platform.payment.SubscriptionExpiredEvent` (BLOCKER-1) and `Video.ownerId ↔ subscriber ID bridge` (BLOCKER-3); Story 7.4 must implement this task after creating the payment module
  - [ ] Create `platform.video.service.VideoSubscriptionLifecycleListener` — `@Component`, `@Slf4j`, `@RequiredArgsConstructor`
  - [ ] Inject: `VideoRepository`, `VideoLifecycleLogRepository`, `VideoLifecycleService`, `PlayerSubscriptionQueryPort`, `SubscriptionLifecycleOutboxRepository` (JpaRepository for `SubscriptionLifecycleOutbox` entity — new file in `platform/video/repo/`), `ConfigService`, `TransactionTemplate`
  - [ ] **Event handler** — `@TransactionalEventListener(AFTER_COMMIT)` on `SubscriptionExpiredEvent`: write a single outbox row (`subscriberId`, `subscriptionTier` from event, `expiredAt = now()`, `status = PENDING`) to `subscription_lifecycle_outbox` inside a new `TransactionTemplate`; this is the only thing the event handler does — the durable write is the at-least-once guarantee; if the insert fails, log at ERROR with `MANUAL_REMEDIATION_REQUIRED` marker and re-throw
  - [ ] **`SubscriptionExpiredEvent` contract**: verify the event carries a `tier` field (String/enum) identifying YEARLY vs non-YEARLY; if absent, **add it to the event record in `platform.payment.contract.event`** as part of this task — do not infer tier from a secondary query at processing time (race condition: tier may have changed between event fire and processing); **null-safety**: if an event arrives with `tier = null` (legacy event in flight during a rolling deployment), treat as Path B (non-YEARLY) and log WARN — do not throw NPE
  - [ ] **Outbox processor** — `@Scheduled(fixedDelay = 60_000)`, drain rows with `status = 'PENDING'` ordered by `created_at ASC`; for each row:
    - Read `batchSize` from `configService.getInt("platform.video.lifecycle.batch_size", 100)`
    - Resolve `ownerId` as `String` — derive from `subscriberId` using the confirmed ID bridge (see Task 2 note and BLOCKER-3); do NOT assume `subscriberId.toString()` equals `video.ownerId` without verification
    - **Path A — YEARLY tier expired** (`row.subscriptionTier == "YEARLY"`): **paginate in a loop** — call `videoRepository.findBlockedReadyByOwner(ownerId, batchSize)` repeatedly until the result is empty; for each batch, call `videoLifecycleService.resetLifecycleClock(video.getId(), Instant.now())` per video (see dev note for method spec); log total count at INFO. **Do NOT call once with a batchSize limit and mark PROCESSED** — subscribers with >batchSize videos would have their remaining videos silently skipped forever
    - **Path B — non-YEARLY tier expired**: call `playerSubscriptionQueryPort.hasAnyActiveSubscription(subscriberId)` — if `true`, log at INFO and mark row `PROCESSED` (another sub still active; correct no-op); if `false`: **paginate in a loop** — call `videoRepository.findActiveReadyByOwner(ownerId, batchSize)` repeatedly until the result is empty; for each batch, call `videoLifecycleService.blockForSubscriptionExpiry(video.getId(), Instant.now())` per video (atomic: sets `accessState = BLOCKED` + `lifecycleLockedAt` in one transaction — see dev note); append `VideoLifecycleLog` row per video using `LifecycleTrigger.SUBSCRIPTION_EXPIRY`; log total count at INFO. **Do NOT call `setAccessState()` + set `lifecycleLockedAt` in two separate operations** — the check constraint (`access_state != 'BLOCKED' OR lifecycle_locked_at IS NOT NULL`) fires if `lifecycleLockedAt` is not written atomically with `accessState`
    - All mutations in `TransactionTemplate`; on success: `row.status = PROCESSED`, `processedAt = now()`; on exception: `row.attempts++`, `row.lastError = e.getMessage()`; if `attempts >= configService.getInt("platform.video.lifecycle.outbox_max_attempts", 5)`: `row.status = DEAD`, log at ERROR with `[DEAD_LETTER subscriber=<id>]` so ops can catch it — videos remain ACTIVE and require manual remediation

- [x] **Task 5 — `VideoLifecycleScheduler` (daily job)** (AC: 7, 8, 9, 10, 14) — **Requires Task 7 before testing Phase 1** (`archiveAsset()` has no Bunny implementation until Task 7; do not test BLOCKED→ARCHIVED until Task 7 is complete — the interface default stub is a silent no-op)
  - [x] Create `platform.video.service.VideoLifecycleScheduler` — `@Component`, `@Slf4j`, `@RequiredArgsConstructor`
  - [x] Inject: `VideoRepository`, `VideoLifecycleLogRepository`, `VideoLifecycleService`, `VideoProviderAdapter`, `ConfigService`, `TransactionTemplate`, `QuotaService`, and a `PlayerSubscriptionQueryPort` (interface, `platform.video.contract` package) to check yearly subscription status
  - [x] **Add `decrementStorageBytes(String ownerId, long bytes)` to `QuotaService` as a prerequisite subtask** — this method does not currently exist; it must atomically decrement `video_quotas.storage_used_bytes` for the given owner by `bytes`. Add it to `QuotaService` before implementing the ARCHIVED→DELETED phase below.
  - [x] `@Scheduled(cron = "${app.video.lifecycle.cron:0 0 3 * * *}")` — 03:00 UTC daily
  - [x] **BLOCKED→ARCHIVED phase**: 
    - Read `blockedToArchivedDays` from ConfigService; compute `threshold = now - days`; read `batchSize` from ConfigService
    - `videoRepository.findBlockedExceedingThreshold(threshold, batchSize)` (SKIP LOCKED guaranteed by query)
    - For each video: check `playerSubscriptionQueryPort.hasActiveYearlySubscription(video.getOwnerId())` → if true, skip (yearly exemption — player renewed to yearly after prior monthly expiry)
    - Otherwise: call `videoProviderAdapter.archiveAsset(video.getProviderAssetId())` — **outside** any `@Transactional`; `archiveAsset()` must treat a Bunny 404 as success (already archived — a prior run may have succeeded but the DB commit failed); add defensive assertion: `Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(), "archiveAsset must not be called inside @Transactional")` inside the Bunny adapter method to fail fast if a future refactor violates the boundary
    - On success: `transactionTemplate.execute(_ -> { videoLifecycleService.archiveForLifecycle(video.getId()); videoLifecycleLogRepository.save(buildLog(video.getId(), BLOCKED, ARCHIVED, LifecycleTrigger.SYSTEM)); })` — `archiveForLifecycle()` atomically sets `accessState = ARCHIVED` and `archivedAt = Instant.now()` in one `@Transactional` call; `archivedAt` is the Phase 2 reference clock; **do NOT** set `accessState` and `archivedAt` directly on the entity — they must be written atomically through the service method
    - On `VideoProviderException`: log error, skip this video (retry next run)
  - [x] **ARCHIVED→DELETED phase**:
    - Read `archivedToDeletedDays` from ConfigService key `platform.video.lifecycle.archived_to_deleted_days`; compute threshold; `videoRepository.findArchivedExceedingThreshold(threshold, batchSize)` (uses `archived_at` column — videos just archived in Phase 1 of this same run will not appear here because their `archived_at` was just set to `now()`)
    - For each video: call `videoProviderAdapter.deleteAsset()` outside `@Transactional`; same defensive assertion applies; `deleteAsset()` must treat a Bunny 404 as success (already deleted — a prior run may have succeeded but the DB commit failed)
    - On success: `transactionTemplate.execute(_ -> { Video v = videoRepository.findById(video.getId()).orElseThrow(); long released = videoLifecycleService.markPurged(v.getId()); quotaService.decrementStorageBytes(v.getOwnerId(), released); videoLifecycleLogRepository.save(buildLog(v.getId(), ARCHIVED, DELETED, LifecycleTrigger.SYSTEM)); })` — `markPurged()` atomically sets `operationalState = DELETED` and `storageBytes = 0`, fires `VideoStatusChangedEvent(DELETED)`, and returns the prior `storageBytes` (re-read inside the transaction to avoid stale-read race); **do NOT** call `transitionOperationalState(DELETED)` — it throws `TerminalStateViolationException` (`READY → Set.of()` is terminal); **do NOT** set fields directly on the entity; see dev note for `markPurged()` spec
    - On `VideoProviderException`: log and skip
  - [x] Log totals (archived count, deleted count) at INFO level after each phase

- [x] **Task 6 — `PlayerSubscriptionQueryPort` interface** (AC: 6, 9) — interface + stub implemented; real adapter deferred to Epic 7
  - [x] Create `platform.video.contract.PlayerSubscriptionQueryPort` — two methods:
    - `boolean hasActiveYearlySubscription(UUID playerId)` — true if player has `tier = 'YEARLY'` and `status = 'ACTIVE'`; used by the scheduler's yearly exemption
    - `boolean hasAnyActiveSubscription(UUID playerId)` — true if player has ANY subscription with `status = 'ACTIVE'`; used by `VideoSubscriptionLifecycleListener` to skip BLOCKED transition when another sub is still live
  - [x] **Type note**: the method signatures use `UUID playerId` (correct for the payment system's user identifier); `video.getOwnerId()` is `String` — the scheduler must call `UUID.fromString(video.getOwnerId())` to convert, or the port must be changed to accept `String`; this depends on the confirmed ID bridge from Task 2 / BLOCKER-3
  - [x] Create `platform.booking.adapter.PlayerSubscriptionQueryAdapter` in `platform.booking` — stub returning `false` for both methods; real implementation deferred to Epic 7 ⚠️ STUB — needs real adapter when `platform.payment` exists
  - [x] Register as Spring `@Component`; `platform.video` depends on the interface only — no cross-module package import

- [x] **Task 7 — Extend `BunnyVideoProviderAdapter.generatePlaybackUrl()` with IP binding** (AC: 1, 2)
  - [x] **PRE-CONDITION — verify existing HMAC format before adding IP binding.** The current implementation signs `signaturePath + expires` using HMAC-SHA256 with an `HS256-` prefix and Base64url encoding. The Bunny signed URL HMAC format per their docs is `SHA256(securityKey + videoId + expiry [+ ip])` — a different concatenation order with no `HS256-` prefix. If the existing format is wrong and the CDN is accepting tokens for the wrong reason (or token validation is disabled in the library), adding IP binding on the wrong base will generate tokens that either always pass or never pass for IP-bound requests. **Verify the current token is actually being validated by Bunny Edge before proceeding** — make a request with a deliberately wrong token and confirm a 403 is returned. If Bunny Edge is not validating, fix the base HMAC format first, then add IP binding. Do not proceed with IP binding if the base format is unverified.
  - [x] Add `clientIp String` to `PlaybackTokenClaims` record (nullable — `@Nullable` annotation preferred; null means no IP binding)
  - [x] In `generatePlaybackUrl()`: if `claims.clientIp() != null`, append clientIp to the HMAC message: `message = signaturePath + expires + clientIp`; append `&clientIp=<ip>` to the returned URL
  - [x] Verify Bunny.net signed URL format for IP-bound tokens before finalising the HMAC message concatenation order — Bunny docs specify: `SHA256(token + "/" + videoId + "/" + filename + expiry + ip)` — cross-check the existing HMAC approach matches what Bunny Edge validates; add a prominent `// CRITICAL pre-deploy: verify IP-binding HMAC order matches Bunny documentation` comment
  - [x] **IPv6 guard**: if the extracted client IP is an IPv6 address (contains `:`), confirm Bunny's HMAC concatenation handles IPv6 without ambiguity — some CDN providers require bracket notation (`[2001:db8::1]`). Add a `SignedPlaybackUrlTest` unit test case for an IPv6 `clientIp` and document the verified format in the runbook. If the format is unspecified, normalise IPv6 to bracket notation before concatenation.
  - [x] **`archiveAsset()` idempotency**: implement `archiveAsset()` so a Bunny 404 response is treated as success (asset already archived — prior run may have succeeded but DB commit failed). Do NOT wrap 404 in `VideoProviderException`. Add comment: `// 404 = asset already gone; treat as success for idempotency`. (Note: `deleteAsset()` has already been fixed for the same reason — see `BunnyVideoProviderAdapter`.)
  - [x] Add `generateDownloadUrl(String providerAssetId, PlaybackTokenClaims claims)` to `VideoProviderAdapter` interface (default method returning `Optional.empty()` as a stub for non-Bunny adapters)
  - [x] Implement in `BunnyVideoProviderAdapter`: signs `/original` path with same HMAC logic; includes `Content-Disposition=attachment` as URL param if Bunny supports it, otherwise omit and document as operational runbook item

- [x] **Task 8 — Extend `PlaybackService.authorizePlayback()` for IP binding + ConfigService TTL** (AC: 1, 2, 3, 4, 5)
  - [x] Change signature: `authorizePlayback(UUID videoId, String viewerId, @Nullable String clientIp)`
  - [x] Extend the ineligibility check to include `OperationalState.HIDDEN` — throw `PlaybackDeniedException` if `video.getOperationalState() == HIDDEN` (in addition to the existing LOCKED and access-state checks)
  - [x] Replace `properties.getPlayback().getTokenTtlMinutes()` with `configService.getLong("platform.video.playback.signed_url_ttl_minutes", 120L)` for TTL; retain `tokenMaxTtlMinutes` check from VideoProperties as a safety ceiling (max 2h = 120 min)
  - [x] Read `ipBindingEnabled = configService.getBoolean("platform.video.playback.ip_binding_enabled", false)`; pass `ipBindingEnabled ? clientIp : null` into `PlaybackTokenClaims`
  - [x] Build download URL: if `video.getVideoType() != VideoType.COACH_REVIEW && viewerId.equals(video.getOwnerId())` → call `videoProviderAdapter.generateDownloadUrl()` and include in response
  - [x] `PlaybackAuthorizationResponse` — update record to add `@Nullable Optional<String> downloadUrl` field (or replace with a new `PlaybackUrlResponse` record in `contract` — keep the existing record if only adding a field)
  - [x] **MUST** verify `VideoExceptionHandler` (or global handler) maps `PlaybackDeniedException` → `403 Forbidden` with `ErrorDto` code `video.notAccessible`; updated `VideoApiAdvice` handler to use `"video.notAccessible"` as msgKey
  - [x] **Confirm `GET /api/video/{id}/status` is authenticated**: Task 10 exposes `displayState = "SUBSCRIPTION_LOCKED"` — if this endpoint is reachable without authentication, unauthenticated callers can probe payment status by video ID; verify `VideoEventResource.getStatus()` already carries `@PreAuthorize(SecurityConstants.IS_AUTHENTICATED)` consistent with the SSE endpoint from Story 6.3; if absent, add it
  - [x] **Reminder — HIDDEN parent review conflict is a Story 6.5 handoff**: see AC 5 note; do not address here
  - [x] **JWT/PlaybackToken — decision**: retained JWT write — revocation window check still works, and tokens serve as an audit trail; the JWT is never returned to clients from `VideoPlayResource`

- [x] **Task 9 — `VideoPlayResource` (new endpoint)** (AC: 1, 2, 3, 4, 5)
  - [x] Create `platform.video.api.VideoPlayResource` — `@RestController`, `@RequestMapping("/api/video")`, `@Observed(name = "video.playback")`, `@RequiredArgsConstructor`, `@Slf4j`
  - [x] `GET /api/video/{id}/play` — `@PreAuthorize(SecurityConstants.IS_AUTHENTICATED)`
  - [x] Verify ownership: `securityUtil.getCurrentUserName()` — if not owner → throw `OperationNotAllowedException` with `SecurityError.MISSING_RIGHTS` (Story 6.5 will open this to coaches + admins via `@videoAccessGuard`); implemented in resource via `videoService.findById()` ownership check
  - [x] Extract client IP: read `X-Forwarded-For` header — if present and non-blank, take the **last** value from the comma-separated list (the last entry is appended by the innermost trusted reverse proxy and reflects the actual client IP as seen by that proxy); fall back to `request.getRemoteAddr()` only if the header is absent or blank. **Multi-hop assumption**: this holds for a single trusted proxy tier; a second proxy layer (e.g. WAF behind the load balancer) would make the last entry the WAF's IP, not the client's — document the expected proxy topology in the deployment runbook. See "IP extraction from reverse-proxy" dev note.
  - [x] Call `playbackService.authorizePlayback(id, currentUser, clientIp)`
  - [x] Return `ResponseEntity<PlaybackResponse>` with record fields: `signedHlsUrl`, `expiresAt`, `downloadUrl` (nullable)
  - [x] Do NOT return the raw `PlaybackAuthorizationResponse` entity directly — map to `PlaybackResponse` record in the `contract` package

- [x] **Task 10 — Extend `VideoEventResource.VideoStatusResponse` with `displayState`** (AC: 11)
  - [x] Change `VideoStatusResponse(UUID videoId, String operationalState)` → add `String displayState`
  - [x] In `getStatus()`: compute `displayState` — if `accessState == BLOCKED` → `"SUBSCRIPTION_LOCKED"`; if `accessState == ARCHIVED` → `"ARCHIVED"`; else → `operationalState.name()`
  - [x] Preserve backward compat: `operationalState` field remains

- [x] **Task 11 — Frontend: `VideoStatusCard.vue` + `video.api.js`** (AC: 12, 13)
  - [x] Add `statusConfigs` entries: `SUBSCRIPTION_LOCKED: { icon: 'lock_clock', color: 'warning', error: true }` and `ARCHIVED: { icon: 'archive', color: 'grey' }`
  - [x] `TERMINAL_STATES` in `VideoStatusCard.vue`: add `"ARCHIVED"` and `"DELETED"`. `TERMINAL_SSE_STATES` in `video.store.js`: add `"ARCHIVED"` **and `"DELETED"`** — `markPurged()` fires `VideoStatusChangedEvent(DELETED)` which reaches the SSE service; if a subscriber is active when a video is purged, the SSE channel will push `"DELETED"`; without `"DELETED"` in `TERMINAL_SSE_STATES` the SSE connection stays open indefinitely after purge. Note: `"ARCHIVED"` is defensive — SSE is inactive after READY so this state will not arrive via SSE in practice; document the reason for the defensive addition. Do **NOT** add `"SUBSCRIPTION_LOCKED"` to either — subscription lock is reversible (player may renew within the 30-day window) and the UI must continue polling to reflect a return to ACTIVE.
  - [x] In `VideoStatusCard.vue`: the `status-changed` emit now emits `displayState`, not `operationalState` — update polling in `useVideoStatusSse` to read `data.displayState ?? data.operationalState` from the status API response
  - [x] DELETED handling: update the `status-changed` listener pattern in the parent — when `state === 'DELETED'`, the parent removes the video from its list; `VideoStatusCard` itself does NOT render for DELETED — remove `DELETED` from `statusConfigs` entirely. **Card rendering race:** there is a narrow window between the poll returning `displayState="DELETED"` and the parent removing the card from the DOM. During this window `statusConfigs[displayState]` is `undefined`. Guard against this in the card template: `v-if="statusConfig"` (or equivalent null-check) so the card renders nothing rather than throwing a TypeError when `displayState="DELETED"` arrives. Do NOT add `"PURGED"` — that string is never emitted by the backend.
  - [x] Add `videoApi.getPlayUrl(videoId)` — `GET /api/video/{videoId}/play` → returns `{ signedHlsUrl, expiresAt, downloadUrl }`

- [x] **Task 12 — i18n keys**
  - [x] Add to `en-US.json` (and any other locale files): `video.status.SUBSCRIPTION_LOCKED`, `video.status.ARCHIVED`
  - [x] Follow existing pattern from `video.status.LOCKED`, `video.status.HIDDEN`

- [x] **Task 13 — Tests** (AC: all)
  - [x] `SignedPlaybackUrlTest` (unit): TTL from ConfigService; IP-binding on/off; HMAC message composition; **when IP binding is enabled, assert `clientIp` is embedded in the returned URL (`&clientIp=<ip>`)** — unit test only; the IT below verifies end-to-end
  - [x] `VideoLifecycleSchedulerTest` (unit):
    - BLOCKED→ARCHIVED transition; sets `archivedAt`
    - ARCHIVED→DELETED transition; decrements `video_quotas.storageUsedBytes`; assert `storageBytesToRelease` is read from the DB inside the transaction (not pre-captured)
    - Yearly subscriber exemption: video BLOCKED + yearly sub active → no ARCHIVED transition
    - **Batch-skip guard**: video with `lifecycle_locked_at = 91 days ago` transitions to ARCHIVED in Phase 1; assert it does NOT appear in Phase 2 within the same run (its `archived_at` is `now()`, which is < 90-day threshold)
    - SKIP LOCKED enforced; Bunny call outside @Transactional (verify no active transaction via `TransactionSynchronizationManager.isActualTransactionActive()`)
    - **archiveAsset double-call guard**: stub `videoProviderAdapter.archiveAsset()` to throw `VideoProviderException` on first call — assert video remains `BLOCKED`, log is written, and next scheduler run retries the call; documents expected idempotency behavior
  - [ ] `VideoSubscriptionLifecycleListenerIT` (integration `@SpringBootTest @Testcontainers`) ⚠️ **[MOVED → Story 7.4]** — depends on BLOCKER-1 (`platform.payment.SubscriptionExpiredEvent`):
    - Fire `SubscriptionExpiredEvent` (MONTHLY tier) with no other active subscription → assert outbox row inserted; drain outbox → assert videos transition to BLOCKED + `lifecycle_locked_at` set
    - Fire `SubscriptionExpiredEvent` (MONTHLY tier) while `hasAnyActiveSubscription` returns true → assert outbox row inserted; drain outbox → assert videos remain ACTIVE (concurrent-subscription guard; row marked PROCESSED)
    - **Fire `SubscriptionExpiredEvent` (YEARLY tier)** for a subscriber whose videos are already BLOCKED (from a prior monthly expiry) → drain outbox → assert `lifecycle_locked_at` is reset to approximately `now()`, NOT the original monthly expiry date (clock-reset AC 9)
    - **Outbox at-least-once**: insert a PENDING outbox row, make `videoRepository.findActiveReadyByOwner` throw on the first attempt → assert `attempts = 1`, `status = PENDING`, `last_error` set; on second drain cycle → assert processing succeeds and `status = PROCESSED`
    - **Dead-letter**: configure `outbox_max_attempts = 1`; make processing always fail → assert `status = DEAD` after one attempt; assert no video state changed
  - [x] `VideoPlayResourceIT`:
    - Owner plays READY+ACTIVE video → 200 + signed URL
    - Play LOCKED/HIDDEN/BLOCKED/ARCHIVED/DELETED video → 403 + `video.notAccessible`
    - Non-owner → 403
    - COACH_REVIEW → no `downloadUrl` in response
    - HOMEWORK owner → response includes `downloadUrl`
    - **IP binding enabled**: set `platform.video.playback.ip_binding_enabled = true` in test config; call with `X-Forwarded-For: 1.2.3.4, 5.6.7.8`; assert returned `signedHlsUrl` contains `clientIp=5.6.7.8` (last entry, not first — guards against spoof)
  - [x] `VideoLifecycleLogIT`: assert log rows appended for each transition (ACTIVE→BLOCKED, BLOCKED→ARCHIVED, ARCHIVED→DELETED); assert `triggered_by` is set correctly per transition
  - [x] `LifecycleOrphanGuardTest` (unit/integration): create a video with `accessState = BLOCKED` and `lifecycle_locked_at = NULL`; run scheduler; assert the video is NOT transitioned to ARCHIVED (SQL NULL comparison guard); assert no `VideoLifecycleLog` row is written for it
  - [ ] `YearlyExemptionRenewalIT` (integration) ⚠️ **[MOVED → Story 7.4]** — depends on BLOCKER-1:
    - Player has monthly sub expire (videos BLOCKED, `lifecycle_locked_at = T0`) → scheduler run does NOT advance to ARCHIVED (yearly sub active)
    - Yearly sub then expires → outbox fires Path A → assert `lifecycle_locked_at` is reset to `T1` (yearly expiry date), NOT `T0` (original monthly expiry date)
    - Scheduler run after reset → videos are NOT yet archived (T1 + 30 days not yet elapsed)
    - Advance clock 31 days past T1 → scheduler run DOES advance to ARCHIVED
    - **Scheduler-before-outbox race**: yearly sub expires → scheduler runs BEFORE outbox drains Path A → at this point `hasActiveYearlySubscription() = false` and `lifecycle_locked_at = T0`; assert expected behavior — does the scheduler advance to ARCHIVED using T0 threshold or does it wait? Document the chosen behavior as a spec decision; if the intention is that the clock must always be reset before archiving, the scheduler must re-check whether the yearly just expired (within the last N minutes) and skip; if it's acceptable for archiving to proceed using T0, document this explicitly.
  - [ ] `SimultaneousExpiryIT` (integration — covers AC 6 race gap) ⚠️ **[MOVED → Story 7.4]**:
    - Player holds MONTHLY and YEARLY subscriptions concurrently; both expire on the same day
    - Process MONTHLY `SubscriptionExpiredEvent` first with `hasAnyActiveSubscription()` returning `true` (yearly still "active" in test DB) → assert videos remain ACTIVE
    - Process YEARLY `SubscriptionExpiredEvent` next via Path A → assert Path A finds NO BLOCKED videos (because MONTHLY guard skipped BLOCKED transition) → assert videos remain ACTIVE
    - Assert no active subscription remains in DB; assert videos are ACTIVE — this is the documented gap; assert an alert/log is produced so ops are aware of the inconsistency

## Dev Notes

### State terminology mapping (epics → implementation)

The epics describe a single `videos.status` enum. The implementation uses a two-axis model (`OperationalState` + `AccessState`). This mapping must be understood before reading any AC:

| Epics term | Implementation term | Notes |
|---|---|---|
| `PUBLISHED` | `OperationalState.READY` | Renamed during Story 6.2 implementation |
| `LOCKED` (lifecycle day 0–30) | `AccessState.BLOCKED` | Different from moderation `LOCKED`; separated to avoid conflating two distinct locks |
| `LOCKED` (moderation) | `OperationalState.LOCKED` | Content moderation lock — terminal via state machine |
| `ARCHIVED` | `AccessState.ARCHIVED` | Same name; operationalState stays READY |
| `PURGED` | `OperationalState.DELETED` | Terminal state after physical Bunny deletion |
| `HIDDEN` | `OperationalState.HIDDEN` | Minor safety gate; unchanged from epics term |

The `displayState` field on the status endpoint bridges this: frontend always reads `displayState`, never `operationalState` or `accessState` directly.

### What already exists — DO NOT reinvent

- **`VideoLifecycleService`** (`platform.video.service`) — already has `transitionOperationalState()` (enforces `VALID_TRANSITIONS`) and `setAccessState()` (unrestricted AccessState transitions). **Do NOT bypass `VideoLifecycleService` and write directly to the entity.** This story adds three new methods to `VideoLifecycleService` (see "New VideoLifecycleService methods" dev note below); all lifecycle state changes must go through one of these methods. Do NOT call `transitionOperationalState(DELETED)` for lifecycle purge — it throws `TerminalStateViolationException` because `READY → Set.of()` is terminal in `VALID_TRANSITIONS`; use `markPurged()` instead.
- **`AccessState.java`** — already has `ACTIVE`, `BLOCKED`, `ARCHIVED`. No new enum values needed.
- **`OperationalState.DELETED`** — already exists and is terminal (`VALID_TRANSITIONS` has `DELETED → Set.of()`). This is the "PURGED" state in story language. No new `PURGED` OperationalState needed.
- **`BunnyVideoProviderAdapter.generatePlaybackUrl()`** — HMAC-SHA256 signing is already implemented. Task 7 extends it; do not rewrite from scratch.
- **`PlaybackService.authorizePlayback()`** — already does READY+ACTIVE check, revocation window, JWT, and signed URL generation. Task 8 extends it; do not rewrite from scratch.
- **`VideoLifecycleService.isPlaybackEligible()`** — can be called from `VideoPlayResource` before calling `authorizePlayback()`, or just let `PlaybackDeniedException` propagate from `authorizePlayback()`.
- **`VideoProviderAdapter.archiveAsset()`** — already declared in the interface (default method stub). Implement in `BunnyVideoProviderAdapter` to call the Bunny cold storage API.
- **`VideoEventResource`** at `/api/video/{id}/events` and `/{id}/status` — Task 10 extends the status endpoint; do not touch the SSE endpoint.
- **`VideoStateReconciliationScheduler`** — handles UPLOADING/PROCESSING reconciliation. `VideoLifecycleScheduler` (Task 5) is a NEW, separate scheduler for post-READY lifecycle. Do not merge them.

### VALID_TRANSITIONS — do NOT change

`VideoLifecycleService.VALID_TRANSITIONS` must not be modified in this story:
- `LOCKED → Set.of()` stays terminal — moderation LOCKED (CSAM, explicit content) must remain irrecoverable via the state machine. Story 10 handles admin review.
- `HIDDEN → Set.of()` stays terminal — Story 6.6 must add exits; do not anticipate it here.
- `READY → Set.of()` stays terminal — subscription expiry is an **AccessState** transition (`ACTIVE → BLOCKED`), NOT an OperationalState transition. `READY` operationalState never changes for a subscription-expired video; only `accessState` changes.

### Subscription expiry lifecycle — AccessState, not OperationalState

When a subscription expires (and no other active subscription exists):
- `operationalState` stays `READY`
- `accessState` transitions: `ACTIVE → BLOCKED` (day 0); `lifecycle_locked_at = now()`
- `accessState` transitions: `BLOCKED → ARCHIVED` (day 30+); `archived_at = now()`
- `operationalState` transitions: `READY → DELETED` (90 days after `archived_at`, after physical Bunny deletion)

**Two reference clocks — do not mix them:**
- `lifecycle_locked_at` — set at the `ACTIVE → BLOCKED` transition; may be **reset** when a yearly subscription expires via `videoLifecycleService.resetLifecycleClock()` (AC 9, Path A). Used as the 30-day threshold for Phase 1 (BLOCKED→ARCHIVED). The "set once" framing only applies to the initial BLOCKED entry — the yearly expiry clock reset is intentional and documented.
- `archived_at` — set at the `BLOCKED → ARCHIVED` transition. Used as the 90-day threshold for Phase 2 (ARCHIVED→DELETED).

Using two separate clocks prevents the batch-skip bug: a video BLOCKED 91 days ago advances to ARCHIVED in Phase 1 (91 > 30), but Phase 2's query uses `archived_at` which was just set to `now()` — so the video will not satisfy `archived_at < now() - 90 days` and will not advance to DELETED in the same run.

### Transaction boundaries — CRITICAL

All calls to `VideoProviderAdapter.archiveAsset()` and `VideoProviderAdapter.deleteAsset()` must be made outside any active `@Transactional` context. In `VideoLifecycleScheduler`, use `TransactionTemplate` to commit the DB state update AFTER a successful Bunny call:

```java
// Pattern: external call first, DB commit after (BLOCKED→ARCHIVED)
videoProviderAdapter.archiveAsset(video.getProviderAssetId()); // outside @Transactional; 404 = already archived, treat as success
transactionTemplate.execute(status -> {
    videoLifecycleService.archiveForLifecycle(video.getId()); // atomic: accessState=ARCHIVED + archivedAt=now()
    videoLifecycleLogRepository.save(buildLog(video.getId(), BLOCKED, ARCHIVED, "SYSTEM"));
    return null;
});
// DO NOT: video.setAccessState(ARCHIVED) + video.setArchivedAt() directly — not atomic, violates service boundary

// Pattern for ARCHIVED→DELETED
videoProviderAdapter.deleteAsset(video.getProviderAssetId()); // outside @Transactional; 404 = already deleted, treat as success
transactionTemplate.execute(status -> {
    Video v = videoRepository.findById(video.getId()).orElseThrow();
    long released = videoLifecycleService.markPurged(v.getId()); // atomic: operationalState=DELETED + storageBytes=0; fires VideoStatusChangedEvent; returns prior storageBytes
    quotaService.decrementStorage(v.getOwnerId(), released);
    videoLifecycleLogRepository.save(buildLog(v.getId(), ARCHIVED, DELETED, "SYSTEM"));
    return null;
});
// DO NOT: call transitionOperationalState(DELETED) — throws TerminalStateViolationException (READY → Set.of())
// DO NOT: set operationalState/storageBytes directly on entity — must go through markPurged()
```

The `@Scheduled` method itself must NOT be annotated `@Transactional`.

### At-least-once guarantee for subscription expiry events

`@TransactionalEventListener(AFTER_COMMIT)` fires synchronously after the subscription commit but is **not retried** by Spring if it throws. Without additional infrastructure, a transient failure (DB blip, pod restart) silently drops the event — the player's videos stay ACTIVE indefinitely.

This story uses the outbox pattern to address this:
1. The event handler's only job is to write a `subscription_lifecycle_outbox` row — a durable insert inside its own `TransactionTemplate`.
2. A `@Scheduled(fixedDelay = 60_000)` processor drains PENDING rows, retrying up to `outbox_max_attempts` times.
3. After `outbox_max_attempts` failures, the row transitions to `DEAD` and logs `[DEAD_LETTER subscriber=<id>]` — ops must resolve manually.

**Failure modes still acknowledged:**
- If the outbox insert itself fails (step 1), the event is still lost. This is the irreducible minimum without a full transactional outbox at the payment module boundary. Log the failure with `MANUAL_REMEDIATION_REQUIRED` and monitor.
- DEAD-letter rows must be monitored via an alert — define an alert on `subscription_lifecycle_outbox WHERE status = 'DEAD'` before production.

### `LifecycleTrigger` constants — use everywhere in lifecycle log writes

All `triggered_by` values in `video_lifecycle_log` must come from `platform.video.contract.LifecycleTrigger` constants (created in Task 1). Do NOT use raw string literals. Defined values: `SYSTEM` (scheduler-initiated transitions), `SUBSCRIPTION_EXPIRY` (outbox processor Path B — ACTIVE→BLOCKED), `YEARLY_EXPIRY_CLOCK_RESET` (outbox processor Path A — `resetLifecycleClock()`), `ACCOUNT_DELETION` (Story 6.5 cascade). Tests that assert `triggered_by` must import and reference these constants.

### `VideoLifecycleService.resetLifecycleClock()` — new method

Add `resetLifecycleClock(UUID videoId, Instant newClock)` to `VideoLifecycleService` — loads the video, sets `lifecycleLockedAt = newClock`, saves, and appends a `VideoLifecycleLog` row with `triggeredBy = "YEARLY_EXPIRY_CLOCK_RESET"`. Called by the outbox processor's Path A (yearly expiry clock reset). Do NOT set this inside `setAccessState()` — it is a separate, explicit operation.

### New `VideoLifecycleService` methods (add in Tasks 4 and 5)

Three additional methods must be added to `VideoLifecycleService`. All are `@Transactional` and follow the existing `findById` / mutate / `save` pattern. They exist because the two lifecycle fields added by this story (`lifecycleLockedAt`, `archivedAt`) must be written atomically with their associated access/operational state changes — calling `setAccessState()` and then setting the timestamp field separately risks the constraint violation or a stale-entity overwrite.

**`blockForSubscriptionExpiry(UUID videoId, Instant lockedAt)`** — atomically sets `accessState = BLOCKED` and `lifecycleLockedAt = lockedAt` in a single `@Transactional` call. Called by the outbox processor Path B. Without this, `setAccessState()` saves first and the check constraint (`access_state != 'BLOCKED' OR lifecycle_locked_at IS NOT NULL`) fires on the next save if `lifecycleLockedAt` was not written in the same transaction.

**`archiveForLifecycle(UUID videoId)`** — atomically sets `accessState = ARCHIVED` and `archivedAt = Instant.now()` in a single `@Transactional` call. Called inside `TransactionTemplate` in `VideoLifecycleScheduler` Phase 1. `archivedAt` is the Phase 2 reference clock — it must be committed in the same transaction as the access state change to prevent Phase 2 selecting a newly-archived video within the same scheduler run.

**`markPurged(UUID videoId)`** — the designated lifecycle escape hatch for `READY → DELETED`. Does **not** go through `VALID_TRANSITIONS` (which blocks `READY → *`). Loads the video, asserts `operationalState == READY` (throws `IllegalStateException` if not — prevents misuse on wrong states), captures current `storageBytes`, sets `operationalState = DELETED` and `storageBytes = 0L`, saves, fires `VideoStatusChangedEvent(videoId, DELETED)`, and returns the captured `storageBytes`. Called inside `TransactionTemplate` in `VideoLifecycleScheduler` Phase 2 so the DB commit is sequenced after a successful Bunny `deleteAsset()` call.

### `PlaybackDeniedException` → 403 mapping

`VideoExceptionHandler` (or global exception handler) **must** map `PlaybackDeniedException` to `403 Forbidden` with `ErrorDto` code `video.notAccessible`. This is a hard requirement — `HIDDEN` videos (AC #5) now return 403 via this same path, so the mapping must exist before any of the play-blocking behaviour is testable. If the mapping is absent, add it to the handler; do not change `PlaybackService` to throw a different exception type.

### IP extraction from reverse-proxy

When extracting client IP in `VideoPlayResource`, the platform runs behind a reverse proxy. The proxy **appends** to `X-Forwarded-For` (RFC 7239-compliant default), so the last entry in the comma-separated list is the IP the trusted proxy observed. Take the **last** entry; fall back to `request.getRemoteAddr()` only if the header is absent or blank.

**Do not take the first entry.** The first entry is client-supplied and trivially spoofable — a malicious caller injects `X-Forwarded-For: 1.2.3.4` to bind the token to an arbitrary IP, then plays from their real IP, bypassing the IP-binding lock entirely.

**Operational requirement:** Confirm the reverse proxy is configured to append (not replace) `X-Forwarded-For`. If the proxy is configured to replace the header, the last-entry approach still works (there will be only one entry). Document the verified proxy behaviour in the deployment runbook before enabling `ip_binding_enabled = true` in production.

**Multi-hop proxy topology**: "last entry = client IP" holds for a single trusted proxy tier. If a second proxy layer is added (e.g. a WAF sitting behind the load balancer), the last entry will be the inner proxy's IP, not the client's. The deployment runbook must document the exact proxy topology and the expected `X-Forwarded-For` entry position. Do not silently assume single-hop.

**IPv6 addresses**: IPv6 client IPs contain colons — confirm Bunny's HMAC concatenation format for `clientIp` handles IPv6 without delimiter ambiguity (some CDN providers require bracket notation `[2001:db8::1]`). Add an IPv6 unit test case to `SignedPlaybackUrlTest` and document the verified format. If the format is unspecified, normalise IPv6 to bracket notation before concatenation.

### Operational runbook items (FR-VID-008, FR-VID-009, FR-VID-019)

These controls are managed at the Bunny.net dashboard level, not in application code. They must be configured before the platform goes to production. Include each in the deployment runbook:

- **FR-VID-008 — Domain whitelist (Bunny Edge Rules):** Configure Bunny.net Edge Rules to reject signed-URL requests from domains not on the platform whitelist. No application code is needed; this is a CDN-level firewall. Verify the rules are active in the production library before enabling public traffic.
- **FR-VID-009 — COACH_REVIEW Content-Disposition enforcement (Bunny Edge Rules):** AC 3 requires `Content-Disposition: inline` to be enforced for all HLS requests on coach review videos to prevent direct `.m3u8` + segment download. If Bunny signed URLs do not support a `Content-Disposition` override query parameter, configure a Bunny Edge Rule on the Stream library to set `Content-Disposition: inline` for all HLS segment requests. **This is a deployment blocker for the coach review privacy guarantee** — not generating a `signedDownloadUrl` alone is insufficient (a motivated user can download `.m3u8` + segments directly). Verify the Edge Rule is active and tested before any coach review content is uploaded to production.
- **FR-VID-019 — HLS 2-second segments:** Configure the Bunny.net Stream library's encoding profile to produce 2-second HLS segments (default varies by library). Shorter segments enable precise frame-seeking on coach review playback. Verify segment size via the Bunny Stream API or dashboard before release.

### Bunny archiveAsset implementation

`BunnyVideoProviderAdapter.archiveAsset()` needs to call the Bunny.net cold storage API. Research the correct Bunny Stream API endpoint for moving a video to cold storage before implementing. Add a `// CRITICAL pre-deploy: verify cold storage archive API endpoint` comment as a reminder. If no dedicated archive endpoint exists in Bunny Stream, document this as an operational runbook item (dashboard-level cold storage configuration) and make the method a no-op with a warning log.

**No-op semantic clarification**: if `archiveAsset()` is a no-op (no Bunny cold storage endpoint exists), the BLOCKED→ARCHIVED DB transition still commits correctly — the video is marked `ARCHIVED` in the database but the file stays on Bunny hot storage until the ARCHIVED→DELETED phase physically deletes it. This is functionally correct but "ARCHIVED" is semantically inaccurate (the file is not in cold storage). Acknowledge this in the runbook and confirm whether Bunny billing differs between hot and cold storage — if so, estimate the cost impact before launch.

**Idempotency requirement**: `archiveAsset()` must treat a Bunny 404 response as success (asset already archived/gone — a prior run's DB commit may have failed after a successful Bunny call). Do NOT wrap 404 in `VideoProviderException`. Add comment: `// 404 = asset already gone; treat as success for idempotency`. (`deleteAsset()` has already been fixed for the same reason in `BunnyVideoProviderAdapter`.)

### Yearly subscriber exemption — cross-module data access

**`platform.payment` does not exist — see BLOCKER-1.** Until Epic 7 creates it, `PlayerSubscriptionQueryAdapter` must live in `platform.booking` and query session pack / booking subscription data. The interface (`PlayerSubscriptionQueryPort`) still lives in `platform.video.contract`. Once Epic 7 lands, move the adapter to `platform.payment` — the interface and all callers are unchanged.

`PlayerSubscriptionQueryPort` (interface in `platform.video.contract`) is implemented in `platform.booking` (temporarily) or `platform.payment` (post-Epic 7). The interface must be in `platform.video.contract` (no cross-module package import in `platform.video`). The implementation is registered as a Spring `@Component`. Spring autowiring wires the two at runtime without compile-time coupling between modules.

**The two methods serve distinct use cases:**

- `hasAnyActiveSubscription(playerId)` — called by `VideoSubscriptionLifecycleListener` *before* setting videos to BLOCKED. Guards the case where a player holds two concurrent subscriptions (e.g. monthly + yearly); the monthly expires and fires `SubscriptionExpiredEvent`, but the yearly is still active. Without this guard, the listener would block the player's videos even though they have uninterrupted access.

- `hasActiveYearlySubscription(playerId)` — called by `VideoLifecycleScheduler` in the BLOCKED→ARCHIVED phase. Guards the renewal path: player's monthly sub expired (videos → BLOCKED), then the player bought a yearly subscription. Their videos are legitimately BLOCKED but should not advance to ARCHIVED while the yearly sub is active (FR-PAY-008). When the yearly sub eventually expires, `hasActiveYearlySubscription()` returns false — but by that point the outbox processor's Path A has already **reset `lifecycle_locked_at` to the yearly expiry date** (AC 9), so the 30-day window runs from the yearly expiry, not the original monthly expiry. The scheduler does not need to know about the clock reset — it simply reads the current `lifecycle_locked_at` value and applies the standard threshold.

**Do not conflate the two:** the listener check prevents videos from entering BLOCKED unnecessarily; the scheduler check prevents BLOCKED videos from advancing to ARCHIVED while a yearly sub is active. Both checks are required.

### Frontend: `displayState` field

The `GET /api/video/{id}/status` response now includes `displayState`. Update `useVideoStatusSse` polling in `video.store.js` to read `data.displayState` (not `data.operationalState`) for rendering in `VideoStatusCard`. The SSE event stream still emits only `operationalState` (moderation states, READY, FAILED, etc.) — SSE is not used after a video reaches READY state so SUBSCRIPTION_LOCKED will never arrive via SSE; it is always read from the polling endpoint on page load.

`TERMINAL_STATES` in `video.store.js` controls when SSE/polling stops. Add `"ARCHIVED"` to both `TERMINAL_SSE_STATES` (store) and `TERMINAL_STATES` (VideoStatusCard). **Do NOT add `"SUBSCRIPTION_LOCKED"`** — a subscription-locked video can return to `ACTIVE` if the player renews within the 30-day window; polling must continue so the UI reflects the renewal. Adding `"SUBSCRIPTION_LOCKED"` to TERMINAL_STATES would freeze the card in the locked state after renewal, requiring a full page reload to recover.

### ConfigService usage

`ConfigService` must be called per use — never cached in a field. All ConfigService reads in `PlaybackService` and `VideoLifecycleScheduler` must call `configService.getLong(...)` / `configService.getBoolean(...)` at method invocation time.

### Project Structure Notes

- New files:
  - `platform/video/api/VideoPlayResource.java`
  - `platform/video/service/VideoLifecycleScheduler.java`
  - `platform/video/service/VideoSubscriptionLifecycleListener.java`
  - `platform/video/contract/PlayerSubscriptionQueryPort.java`
  - `platform/video/contract/LifecycleTrigger.java` (constants class — `triggered_by` values for `video_lifecycle_log`)
  - `platform/video/contract/PlaybackResponse.java` (record: `signedHlsUrl`, `expiresAt`, `downloadUrl`)
  - `platform/video/repo/VideoLifecycleLog.java` (entity)
  - `platform/video/repo/VideoLifecycleLogRepository.java` (JpaRepository)
  - `platform/video/repo/SubscriptionLifecycleOutbox.java` (entity — mirrors `subscription_lifecycle_outbox` table)
  - `platform/video/repo/SubscriptionLifecycleOutboxRepository.java` (JpaRepository)
  - `platform/booking/adapter/PlayerSubscriptionQueryAdapter.java` (temporary home until Epic 7 creates `platform.payment`)
  - `platform/payment/contract/event/SubscriptionExpiredEvent.java` — add `tier` field if absent (**Epic 7 deliverable — does not exist yet**)
  - `src/main/resources/db/migration/V58__lifecycle_schema.sql`
- Modified files:
  - `platform/video/api/VideoEventResource.java` — add `displayState` to `VideoStatusResponse`
  - `platform/video/service/PlaybackService.java` — extend `authorizePlayback()` signature and TTL source
  - `platform/video/repo/VideoRepository.java` — add 3 new queries
  - `platform/video/contract/PlaybackAuthorizationResponse.java` — add `downloadUrl` field (nullable)
  - `infrastructure/video/BunnyVideoProviderAdapter.java` — extend `generatePlaybackUrl()`, add `generateDownloadUrl()`
  - `infrastructure/video/PlaybackTokenClaims.java` — add `clientIp` (nullable)
  - `infrastructure/video/VideoProviderAdapter.java` — add `generateDownloadUrl()` default method
  - `src/frontend/src/stores/video.store.js` — add ARCHIVED (only) to TERMINAL_SSE_STATES; use `displayState`
  - `src/frontend/src/components/video/VideoStatusCard.vue` — add SUBSCRIPTION_LOCKED/ARCHIVED statusConfigs
  - `src/frontend/src/api/video.api.js` — add `getPlayUrl()`
  - `src/frontend/src/i18n/en-US.json` — new i18n keys

### References

- `PlaybackService.java` — current `authorizePlayback()` signature, TTL source (VideoProperties), JJWT pattern [Source: `src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java`]
- `BunnyVideoProviderAdapter.java` lines 137–158 — existing HMAC-SHA256 signed URL generation [Source: `src/main/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapter.java`]
- `VideoLifecycleService.java` lines 32–44 — `VALID_TRANSITIONS` map — must not be modified [Source: `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java`]
- `AccessState.java` — `ACTIVE, BLOCKED, ARCHIVED` [Source: `src/main/java/com/softropic/skillars/platform/video/contract/AccessState.java`]
- `VideoEventResource.java` — ownership check pattern via `securityUtil.getCurrentUserName()` [Source: `src/main/java/com/softropic/skillars/platform/video/api/VideoEventResource.java`]
- `VideoRepository.java` — SKIP LOCKED query pattern [Source: `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java`]
- `VideoStatusCard.vue` — `statusConfigs` map structure, `TERMINAL_STATES`, SSE lifecycle [Source: `src/frontend/src/components/video/VideoStatusCard.vue`]
- `video.store.js` — `TERMINAL_SSE_STATES`, polling pattern, `displayState` read location [Source: `src/frontend/src/stores/video.store.js`]
- `V57__moderation_config.sql` — ConfigService seed pattern with `ON CONFLICT (key) DO NOTHING` [Source: `src/main/resources/db/migration/V57__moderation_config.sql`]
- Previous story 6.3 handoffs — `LOCKED → Set.of()` terminal (admin action in Story 10); `HIDDEN → Set.of()` terminal until Story 6.6; `VideoSseService.TERMINAL_STATES` includes LOCKED [Source: `_bmad-output/implementation-artifacts/skillars-6-3-content-moderation-pipeline.md`]
- Architecture — transaction boundary rule: external calls always outside `@Transactional`; `TransactionTemplate` for manual boundaries [Source: `_bmad-output/planning-artifacts/architecture.md`]
- Epic 6.5 dependency note — `GET /api/video/{id}/play` in Story 6.4 enforces owner-only access; Story 6.5 extends via `@videoAccessGuard` for coach + admin roles. Do NOT implement coach/admin access here.

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- Unnecessary Mockito stubs in VideoLifecycleSchedulerTest: removed `findArchivedExceedingThreshold` stubs (Mockito returns empty list by default for collection return types). Fixed ownerId strings to valid UUIDs so `UUID.fromString(ownerId)` in the scheduler succeeds and `hasActiveYearlySubscription` stub is actually called.
- PlaybackRevocationWindowUnitTest compile error: added `@Mock ConfigService configService`, stubbed `getLong`/`getBoolean`, and updated `PlaybackService` constructor call after ConfigService was added as dependency.
- Ownership check placement: initially placed in `PlaybackService.authorizePlayback()`, but this broke `PlaybackRevocationIT` which calls authorizePlayback with viewerId != ownerId for test setup. Moved the check to `VideoPlayResource` using `videoService.findById()` — keeps service flexible for internal/administrative use cases and matches AC wording ("GET /api/video/{id}/play verifies...").
- VideoApiAdvice `PlaybackDeniedException` handler: changed msgKey from `VideoErrorCode.PLAYBACK_DENIED.getErrorCode()` to `"video.notAccessible"` to match AC 5 requirement; metrics label retains `PLAYBACK_DENIED` via `videoMetrics.recordError`.

### Completion Notes List

- Tasks 1–3, 5–13 implemented. Task 4 (VideoSubscriptionLifecycleListener) and Task 13 tests (VideoSubscriptionLifecycleListenerIT, YearlyExemptionRenewalIT, SimultaneousExpiryIT) were deferred on BLOCKER-1 (platform.payment module does not exist) and have been **moved to Story 7.4** (Coach & Player Subscription Tiers). Story 7.4 must implement Task 4 in full and run all three integration tests once `platform.payment.SubscriptionExpiredEvent` is available.
- PlayerSubscriptionQueryAdapter stub in platform.booking returns false for both methods. Real adapter needed post-Epic 7.
- ConfigService default overloads added (getLong, getInt, getBoolean) to support lifecycle scheduler and playback service without requiring all keys to be pre-seeded.
- Two-clock design: lifecycle_locked_at for BLOCKED→ARCHIVED 30-day threshold; archived_at for ARCHIVED→DELETED 90-day threshold. This prevents the batch-skip bug.
- Transaction boundaries enforced: archiveAsset/deleteAsset outside @Transactional; Assert.state added to fail fast if violated.
- displayState computed in VideoEventResource: BLOCKED→"SUBSCRIPTION_LOCKED", ARCHIVED→"ARCHIVED", else operationalState.name().
- IP extraction: XFF last-entry (not first) for spoof prevention. IPv6 normalised to bracket notation before HMAC concatenation.
- JWT/PlaybackToken write retained (audit trail + revocation window).
- 19 unit/web-slice tests green: SignedPlaybackUrlTest (6), VideoLifecycleSchedulerTest (5), LifecycleOrphanGuardTest (1), PlaybackRevocationWindowUnitTest (1), VideoPlayResourceIT (6).

### File List

**New files:**
- `src/main/resources/db/migration/V58__lifecycle_schema.sql`
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoLifecycleLog.java`
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoLifecycleLogRepository.java`
- `src/main/java/com/softropic/skillars/platform/video/repo/SubscriptionLifecycleOutbox.java`
- `src/main/java/com/softropic/skillars/platform/video/repo/SubscriptionLifecycleOutboxRepository.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/LifecycleTrigger.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/PlayerSubscriptionQueryPort.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/PlaybackResponse.java`
- `src/main/java/com/softropic/skillars/platform/booking/adapter/PlayerSubscriptionQueryAdapter.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleScheduler.java`
- `src/main/java/com/softropic/skillars/platform/video/api/VideoPlayResource.java`
- `src/test/java/com/softropic/skillars/infrastructure/video/SignedPlaybackUrlTest.java`
- `src/test/java/com/softropic/skillars/platform/video/service/VideoLifecycleSchedulerTest.java`
- `src/test/java/com/softropic/skillars/platform/video/service/LifecycleOrphanGuardTest.java`
- `src/test/java/com/softropic/skillars/platform/video/service/VideoLifecycleLogIT.java`
- `src/test/java/com/softropic/skillars/platform/video/api/VideoPlayResourceIT.java`

**Modified files:**
- `src/main/java/com/softropic/skillars/platform/video/repo/Video.java` — added lifecycleLockedAt, archivedAt fields
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java` — added 4 lifecycle queries (SKIP LOCKED)
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java` — added getLong/getInt/getBoolean overloads
- `src/main/java/com/softropic/skillars/platform/video/service/QuotaService.java` — added decrementStorageBytes
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java` — added blockForSubscriptionExpiry, archiveForLifecycle, markPurged, resetLifecycleClock
- `src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java` — extended authorizePlayback with clientIp + ConfigService TTL + download URL
- `src/main/java/com/softropic/skillars/platform/video/contract/PlaybackAuthorizationResponse.java` — added downloadUrl field
- `src/main/java/com/softropic/skillars/platform/video/api/VideoEventResource.java` — added displayState to VideoStatusResponse
- `src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java` — PlaybackDeniedException → msgKey "video.notAccessible"
- `src/main/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapter.java` — IP binding, archiveAsset, generateDownloadUrl, normalizeClientIp
- `src/main/java/com/softropic/skillars/infrastructure/video/PlaybackTokenClaims.java` — added clientIp field
- `src/main/java/com/softropic/skillars/infrastructure/video/VideoProviderAdapter.java` — added generateDownloadUrl default method
- `src/frontend/src/components/video/VideoStatusCard.vue` — SUBSCRIPTION_LOCKED/ARCHIVED statusConfigs, v-if guard, TERMINAL_STATES
- `src/frontend/src/stores/video.store.js` — TERMINAL_SSE_STATES + DELETED, displayState polling
- `src/frontend/src/api/video.api.js` — added getPlayUrl
- `src/frontend/src/i18n/en-US/index.js` — SUBSCRIPTION_LOCKED, ARCHIVED i18n keys
- `src/test/java/com/softropic/skillars/platform/video/service/PlaybackRevocationWindowUnitTest.java` — added ConfigService mock + ownerId fix
