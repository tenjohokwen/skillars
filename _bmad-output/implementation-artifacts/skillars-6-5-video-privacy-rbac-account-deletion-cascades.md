# Story 6.5: Video Privacy, RBAC & Account Deletion Cascades

Status: done

## Story

As a platform operator,
I want all videos private by default, deletion restricted to authorised parties only, and account deletion to cascade a full purge of all owned video assets,
so that no video is ever accessible without explicit authorisation and GDPR erasure obligations are met automatically.

## Acceptance Criteria

1. **Given** any video is uploaded and reaches `PUBLISHED` status **When** access is evaluated **Then** the video is private by default — no public video feeds, no unauthenticated playback URL; `GET /api/video/{id}/play` enforces `@PreAuthorize` via `@videoAccessGuard`; the requester must be the owner, a coach with an active paid relationship to the owner (within the configurable window from ConfigService), or a platform admin.

2. **Given** a parent is the verified parent of a minor player who owns a video in `HIDDEN` state **When** `GET /api/video/{id}/play` is called by the parent **Then** the parent is granted access (parentOf check bypasses the HIDDEN → 403 block); this is the parental approval path deferred from Story 6.4 AC 5.

3. **Given** a user attempts to delete a video **When** `DELETE /api/video/{id}` is received **Then** the deletion is permitted only if the requester is: the video owner, a verified parent of the owner (player videos), or a platform admin (FR-VID-015); any other requester receives `403 Forbidden` with `ErrorDto` code `video.deletionNotAuthorised`.

4. **Given** a permitted `DELETE /api/video/{id}` request **When** processed **Then** on success: the `videos` record is marked `operationalState = PURGED` and `storageBytes = 0L` (quota decremented); a `VideoPhysicalDeletionEvent` is published AFTER_COMMIT; a `video_deletion_log` row is appended with `triggeredBy = 'USER_DELETION'`; response is `204 No Content`; physical Bunny.net deletion is asynchronous via `video_deletion_outbox` — never inline.

5. **Given** a physical deletion job processes a `video_deletion_outbox` row **When** `BunnyVideoProviderAdapter.deleteAsset()` is called **Then** it is called outside `@Transactional`; on success: `videos.bunnyVideoId` (providerAssetId) is nulled, a `video_deletion_log` row appended; on failure: `attempts++`, `lastError` set; after `platform.video.deletion.max_attempts` failures the row transitions to `DEAD` and logs `[DEAD_LETTER videoId=<id>]`.

6. **Given** a platform drill video is cloned by a coach (Story 4.1) **When** the coach's cloned video is deleted **Then** `drill_video_refs.refCount` is atomically decremented by the mechanism in `platform.session` (not redefined here); physical deletion only if `refCount = 0`; this story adds no new ref-count entities — it defers to Story 4.1's mechanism.

7. **Given** a parent account is deleted **When** `AccountDeletionRequestedEvent` is published from `platform.security` **Then** a `@TransactionalEventListener(AFTER_COMMIT)` in `platform.video` immediately queues all videos owned by the parent and by all their linked player profiles for deletion via `video_deletion_outbox`; `storageBytes` and `bandwidthUsedBytes` in `video_quotas` are reset to 0 for all affected users; physical Bunny.net deletion completes within 30 days (FR-VID-013, FR-VID-016).

8. **Given** a coach account is deleted **When** `AccountDeletionRequestedEvent` fires **Then** the same cascade applies to all videos owned by the coach; drill videos shared with `refCount > 1` are not physically deleted — only the coach's reference is decremented.

9. **Given** the `VideoLifecycleService.VALID_TRANSITIONS` map **When** all PROCESSING→READY events have ceased (confirmed by ops) **Then** the backward-compat bypass is removed (TODO left by Story 6.3 dev notes); this story cleans up the `PROCESSING→READY` path and removes the associated counter metric per the TODO comment at `VideoLifecycleService.java` line 36.

## Tasks / Subtasks

- [x] **Task 0 — Identity Bridge investigation (MANDATORY GATE — complete before writing any RBAC code)** (AC: 1, 2, 3, 7, 8)
  - [ ] Read `VideoResource.java` lines 60–61: confirm the exact value written to `videos.owner_id` (coach path: `coachId.toString()` where `coachId` is a `UUID` from `coachProfileService.getCoachIdByUserId(Long)` — so coach `ownerId` = UUID string)
  - [ ] Read `VideoPlayResource.java` line 44: run the existing `VideoPlayResourceIT` with a debug log to print `video.getOwnerId()` and `securityUtil.getCurrentUserName()` for the same coach session — confirm they match (UUID string vs email vs Long string)
  - [ ] Document the confirmed format in a code comment at the top of `VideoAccessGuard.java`: `// ownerId format: <confirmed format from investigation> — see Story 6.5 Task 0`
  - [ ] Confirm the Long↔String bridge: how `ParentPlayerLink.playerId` (Long) maps to `videos.owner_id` (String) for player-owned videos — document the exact conversion in the same comment block
  - [ ] **DO NOT proceed to Task 3, 4, or 6 until the ownerId format is confirmed and documented** — this is the single highest bug-risk in the story

- [x] **Task 1 — Flyway V59: deletion infrastructure** (AC: 4, 5, 7)
  - [ ] Create `main.video_deletion_log` table: `(id UUID PK DEFAULT gen_random_uuid(), video_id UUID REFERENCES main.videos(id) ON DELETE SET NULL, deleted_at TIMESTAMPTZ NOT NULL DEFAULT now(), triggered_by VARCHAR(32) NOT NULL CHECK (triggered_by IN ('USER_DELETION', 'ACCOUNT_DELETION', 'SYSTEM')), bunny_video_id VARCHAR(255))` — append-only audit; `video_id` is nullable so the audit row survives if the `videos` row is ever hard-deleted (do NOT use `ON DELETE CASCADE` on an audit table); add index `CREATE INDEX idx_vdlog_video_id ON main.video_deletion_log(video_id)`; **Note on epics divergence**: the epics specify `triggeredBy ENUM USER/ACCOUNT_DELETION/LIFECYCLE` — this story uses `USER_DELETION` and `SYSTEM` instead of `USER` and `LIFECYCLE`; all `LifecycleTrigger` constants must match these CHECK values exactly; do NOT use the epics strings (`USER`, `LIFECYCLE`) anywhere in code — use only the values in this CHECK constraint
  - [ ] Create `main.video_deletion_outbox` table: `(id UUID PK DEFAULT gen_random_uuid(), video_id UUID NOT NULL, bunny_video_id VARCHAR(255), status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','COMPLETED','FAILED','DEAD')), attempts INT NOT NULL DEFAULT 0, next_retry_at TIMESTAMPTZ NOT NULL DEFAULT now(), last_error TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), triggered_by VARCHAR(32) NOT NULL DEFAULT 'USER_DELETION')`; add index `CREATE INDEX idx_vdoutbox_status_retry ON main.video_deletion_outbox(status, next_retry_at) WHERE status = 'PENDING'`; add `CREATE UNIQUE INDEX idx_vdoutbox_unique_pending ON main.video_deletion_outbox(video_id) WHERE status = 'PENDING'` to prevent duplicate PENDING rows when the cascade listener is manually re-fired by ops; add `CREATE INDEX idx_vdoutbox_video_id ON main.video_deletion_outbox(video_id)` for outbox lookup by video (used by the concurrent deletion guard and future reconciliation job)
  - [ ] Seed ConfigService keys: `platform.video.deletion.max_attempts = 5`, `platform.video.deletion.outbox_poll_delay_ms = 60000`, `platform.video.access.coach_window_days = 90` (note: access namespace, not deletion — this governs playback authorisation, not deletion); use `ON CONFLICT (key) DO NOTHING` pattern from V57 migration
  - [ ] Add `DELETION_NOT_AUTHORISED` to `platform.video.contract.VideoErrorCode` enum

- [x] **Task 2 — `AccountDeletionRequestedEvent`** (AC: 7, 8)
  - [ ] Create `platform.security.contract.AccountRole` enum: `COACH, PARENT, PLAYER` — placed in `platform.security.contract` package; used by `AccountDeletionRequestedEvent.userRole`; do NOT use `String` for role comparisons (Java `==` on `String` is reference equality and will silently fail for non-interned values at runtime)
  - [ ] Create `platform.security.contract.event.AccountDeletionRequestedEvent` record: `(String userId, AccountRole userRole, List<Long> linkedPlayerIds)` — `userId` is the deleted user's identifier (same String format stored in `video.ownerId`); `userRole` is `AccountRole` enum identifying COACH vs PARENT vs PLAYER for cascade routing; `linkedPlayerIds` is the list of Long player IDs managed by a parent (empty for coaches and standalone players)
  - [ ] **CRITICAL — Story 10.4 API mismatch**: The epics (Story 10.4 line 3310) describe `AccountDeletionRequestedEvent(userId)` — a single-field event. This story defines the event as `(String userId, AccountRole userRole, List<Long> linkedPlayerIds)`. These are incompatible. Story 10.4 is the publisher; if it publishes a single-field event, the cascade listener here receives null/empty `userRole` and `linkedPlayerIds`, silently skipping all linked-player cascades for parents. **Resolution required before implementation**: either (a) confirm with the Story 10.4 author that it will publish the full three-field event (preferred — update Story 10.4 dev notes), or (b) redesign the cascade listener to resolve linked players itself from `userId` via a ParentPlayerLinkRepository lookup, removing `userRole` and `linkedPlayerIds` from the event. Do not implement either story until this contract is settled.
  - [ ] **CRITICAL — userId format**: `AccountDeletionRequestedEvent.userId` must use the SAME String format stored in `videos.owner_id` for the cascade listener to match videos by owner. Verify the exact format (see Task 0 and "Identity Bridge" dev note below) before writing the event record — do not guess.
  - [ ] Place in `platform.security.contract.event` package alongside existing events (`AccountSuspensionRequestedEvent`, `UserRegisteredEvent`)
  - [ ] Event is a Spring application event — no JMS/Kafka; published by `platform.security` account deletion flow (Story 10.4 will publish it; Story 6.5 builds the consumer)

- [x] **Task 3 — `VideoAccessGuard`** (AC: 1, 2, 3)
  - [ ] Create `platform.video.service.VideoAccessGuard` — `@Component("videoAccessGuard")`, `@Slf4j`, `@RequiredArgsConstructor`; the SpEL bean name is used by `@PreAuthorize("@videoAccessGuard.canPlay(authentication, #id)")` on `VideoPlayResource` and `@PreAuthorize("@videoAccessGuard.canDelete(authentication, #id)")` on `VideoResource`; the epics dev notes reference the old name `check()` — ignore that; `canPlay` and `canDelete` are the canonical method names used throughout this story
  - [ ] Inject: `VideoService` (for `findById`), `SecurityUtil`, `ParentPlayerLinkRepository` (from `platform.security`), `BookingRepository` (from `platform.booking`), `ConfigService`
  - [ ] **`canPlay(Authentication auth, UUID videoId)`** method — returns `boolean`; use this in `@PreAuthorize` SpEL as `@videoAccessGuard.canPlay(authentication, #id)`. Checks in order:
    0. State gate: if `video.getOperationalState() == OperationalState.PURGED` → throw `VideoNotFoundException` for ALL callers including admin (**deliberate design decision**: returning 404 prevents existence leakage via status code; admin is NOT exempt — see Task 11; trade-off: admins cannot distinguish "never existed" from "was purged" via the play API — mitigate by directing admins to query `video_deletion_log` directly for audit; do NOT change to 403 or 410 on this endpoint as either leaks existence; track an admin-only `GET /api/admin/video/{id}` audit endpoint as a backlog item if admin audit use cases require it)
    1. Admin: `securityUtil.isAdmin()` → allow (admin can play HIDDEN or any non-PURGED state)
    2. Owner: `currentUser.equals(video.getOwnerId())` → allow (matches existing VideoPlayResource pattern)
    3. Parent of owner (for HIDDEN approval flow AC 2): resolve `video.ownerId` to player Long ID → `parentPlayerLinkRepository.findByPlayerId(playerId)` returns `List<ParentPlayerLink>` (a player may have multiple verified parents — use `.stream().anyMatch(link -> link.getParentId().equals(currentUserLongId))`) → if match found, write `videoAccessCache.setParentDecision(videoId, true)` (request-scoped cache — see "VideoAccessCache" in Dev Notes) and allow; parent access bypasses the HIDDEN check in `PlaybackService` (pass `skipHiddenCheck = true` — see Task 8)
    4. Coach with active relationship: resolve coach's UUID from `securityUtil.getCurrentCoachUserId()` → `coachProfileService.getCoachIdByUserId()` → query `bookingRepository` for recent completed bookings between coach and the video owner within `platform.video.access.coach_window_days` window → allow; **coaches are NOT exempt from the HIDDEN block** — the guard returning `true` here does NOT bypass `PlaybackService.authorizePlayback()`'s HIDDEN state check; a coach with an active booking will still receive `PlaybackDeniedException` for a HIDDEN video (correct — minor safety gate must hold until parent approves)
    5. Deny — throw `PlaybackDeniedException` (reuses existing exception) so `VideoApiAdvice` maps to 403
  - [ ] **`isParentOf(String currentUserId, String videoOwnerId)`** method — returns `boolean`; used by `VideoPlayResource.play()` to determine `skipHiddenCheck`; **read from `VideoAccessCache` first** (`videoAccessCache.getParentDecision(videoId).orElse(null)`) — `canPlay()` step 3 already populated the cache during `@PreAuthorize` evaluation in the same request, so no second DB round-trip is needed for the parent path; only fall back to a DB call if the cache returns empty (e.g. `isParentOf()` called outside a `@PreAuthorize` context — use the same `List<ParentPlayerLink>.anyMatch()` pattern); returns `false` if `videoOwnerId` cannot be resolved to a player (e.g. coach-owned video); inject `VideoAccessCache` into `VideoAccessGuard` (add to injection list above)
  - [ ] **`canDelete(Authentication auth, UUID videoId)`** method — returns `boolean`; use in `@PreAuthorize` on `DELETE` endpoint:
    1. Admin → allow
    2. Owner → allow
    3. Parent of owner → allow
    4. Deny — throw `VideoDeletionNotAuthorisedException` (new exception) → 403 `video.deletionNotAuthorised`
  - [ ] Create `platform.video.contract.exception.VideoDeletionNotAuthorisedException` — extends `ApplicationException`; `VideoApiAdvice` maps it to 403 with code `video.deletionNotAuthorised`
  - [ ] Add `@ExceptionHandler(VideoDeletionNotAuthorisedException.class)` to `VideoApiAdvice` → `@ResponseStatus(HttpStatus.FORBIDDEN)` with `ErrorDto` code `"video.deletionNotAuthorised"`; also add metric: `videoMetrics.recordError(...)` with `VideoErrorCode.DELETION_NOT_AUTHORISED.getErrorCode()`
  - [ ] **Do NOT inject `CoachProfileService` directly into `VideoAccessGuard` if it creates a circular dependency** — use `@Lazy` or resolve the coach UUID via a separate `CoachVideoAccessPort` interface in `platform.video.contract` (same pattern as `PlayerSubscriptionQueryPort` from Story 6.4); the booking module implements it

- [x] **Task 4 — `VideoDeletionService`** (AC: 4, 7, 8)
  - [ ] Create `platform.video.service.VideoDeletionService` — `@Service`, `@Slf4j`, `@RequiredArgsConstructor`
  - [ ] Inject: `VideoRepository`, `VideoLifecycleLogRepository`, `QuotaService`, `ConfigService`, `ApplicationEventPublisher`, `TransactionTemplate`; also JPA repositories for `VideoDeletionOutbox` and `VideoDeletionLog` (see Task 1)
  - [ ] **`deleteVideo(UUID videoId, String triggeredBy)`** — the central deletion method; atomic steps inside `@Transactional`:
    1. Load video via `videoRepository.findById(videoId).orElseThrow(VideoNotFoundException::new)`
    2. Skip if already `PURGED` — idempotent (returns without error; logs WARN)
    3. Capture `storageBytes = video.getStorageBytes() != null ? video.getStorageBytes() : 0L`; capture `String bunnyVideoId = video.getProviderAssetId()` (may be null if video never reached encoding) — **both must be captured here, before step 4 nulls these fields**; implementing in any other order will produce a null `bunnyVideoId` in the outbox row
    4. Set `video.setOperationalState(OperationalState.PURGED)`, `video.setStorageBytes(0L)`, and **`video.setProviderAssetId(null)`** — nulling providerAssetId prevents the outbox processor from re-attempting a Bunny API call after the first successful run; **DO NOT** call `transitionOperationalState()` or `markPurged()` — `markPurged()` asserts `operationalState == READY` and throws for any other state; set the fields directly to handle account-deletion cascade where the video may be in any operational state; for user-initiated deletion, validate that the video is not already PURGED first (step 2)
    5. `videoRepository.save(video)`
    6. `quotaService.decrementStorageBytes(video.getOwnerId(), storageBytes)` — added in Story 6.4
    7. Append `video_deletion_log` row with `triggeredBy`
    8. Insert `video_deletion_outbox` row using the `bunnyVideoId` captured in step 3, with `status = PENDING`, `triggeredBy` — physical deletion is async; outbox processor will short-circuit on `row.getBunnyVideoId() == null` (see Task 7)
    9. Publish `VideoPhysicalDeletionEvent(videoId)` AFTER_COMMIT via `publisher.publishEvent()` — listeners can react
  - [ ] **`deleteByUser(UUID videoId, String currentUser)`** — user-initiated deletion:
    - Load video, verify `!video.getOperationalState().equals(OperationalState.PURGED)` (skip if already gone — return silently for idempotent 204; do NOT re-insert outbox row)
    - **Defense-in-depth ownership check**: assert `video.getOwnerId().equals(currentUser)` — this is belt-and-suspenders behind `@PreAuthorize`; throw `VideoDeletionNotAuthorisedException` if it fails and log WARN `[DELETION_AUTH_BYPASS_ATTEMPT videoId={} caller={}]`; this guards against future callers bypassing the `@PreAuthorize` guard (internal service calls, tests calling the method directly)
    - **Cancel pending approval request for this video** (if any): call `videoApprovalRequestRepository.cancelAllPendingForVideo(videoId)` — `@Modifying @Query` that sets `status = 'CANCELLED'` for any PENDING `video_approval_requests` row for this specific `videoId`; required here (not only in the account cascade) because an owner or parent can delete a HIDDEN video at any time, leaving an orphaned PENDING approval record that Story 6.6's approve/reject flow will attempt to resolve against a PURGED video; gate behind the same `platform.video.approvalCancellation.enabled` ConfigService flag as Task 6; the query method is defined in Task 4a
    - Call `deleteVideo(videoId, LifecycleTrigger.USER_DELETION, false)` — reuse central method; `skipQuotaDecrement = false` so quota is decremented for individual deletions
    - **Concurrent deletion guard**: **verify `@Version Long version` does NOT already exist on the `Video` entity before adding it** (check `Video.java` — Stories 6.1–6.4 added multiple fields); adding `@Version` is a cross-cutting change that alters JPA UPDATE behaviour for ALL `videoRepository.save()` call sites; audit every existing save() caller across the codebase for stale-entity loads before adding the field; once added, `ObjectOptimisticLockingFailureException` from concurrent DELETEs must be caught in `VideoDeletionService` and treated as idempotent (return without error; the first request already purged the video)
  - [ ] **`deleteVideo(UUID videoId, String triggeredBy, boolean skipQuotaDecrement)`** — overload of the central deletion method; when `skipQuotaDecrement = true`, skip the `quotaService.decrementStorageBytes()` call; use `deleteVideo(videoId, triggeredBy, false)` as the default for all user-initiated paths; only the account-deletion cascade passes `true` to avoid N redundant decrements before the quota reset
  - [ ] **`cascadeDeleteForAccount(String ownerId)`** — account deletion cascade for a single ownerId:
    - Load non-PURGED videos in batches: use `videoRepository.findByOwnerIdAndOperationalStateNot(ownerId, OperationalState.PURGED, Pageable.ofSize(100))` in a loop until the returned page is empty (new query — see Task 4a); do NOT use a `findAll...` variant returning `List<Video>` — a prolific coach with thousands of videos will materialise all entities in heap simultaneously, risking OOM during account deletion
    - For each video in each batch: call `deleteVideo(videoId, LifecycleTrigger.ACCOUNT_DELETION, true)` per video inside its own transaction; `skipQuotaDecrement = true` avoids N intermediate decrements that would be immediately overwritten by the reset
    - Reset `video_quotas` for the owner AFTER the loop: `videoQuotaRepository.resetBytesForOwner(ownerId)` (new @Modifying query) — zeroes both `storageUsedBytes` and `bandwidthUsedBytes` in a single atomic UPDATE; this is the authoritative quota reset, not the per-video decrements
  - [ ] **PURGED state guard in `VideoLifecycleService`** — add at entry of `transitionOperationalState()`: `if (video.getOperationalState() == OperationalState.PURGED) { log.warn("[LIFECYCLE_TRANSITION_BLOCKED_PURGED videoId={}]", videoId); return; }` — silently discards in-flight lifecycle events (e.g., Bunny encoding-complete webhook) for already-purged videos; without this guard, a race where a user deletes a PROCESSING video and Bunny's encoding webhook fires afterward will either throw `TerminalStateViolationException` (crashing the webhook handler) or silently reactivate the video; add to `VideoLifecycleService.java` and add it to the modified files list in File Structure
  - [ ] Add `USER_DELETION` constant to `platform.video.contract.LifecycleTrigger` (alongside existing `SYSTEM`, `SUBSCRIPTION_EXPIRY`, `YEARLY_EXPIRY_CLOCK_RESET`, `ACCOUNT_DELETION` — `ACCOUNT_DELETION` already exists; do NOT re-add it or the file will fail to compile)
  - [ ] Create `platform.video.contract.event.VideoPhysicalDeletionEvent` record: `(UUID videoId)` — Spring application event

- [x] **Task 4a — New repository queries for deletion** (AC: 4, 7)
  - [ ] Add to `VideoRepository`: `Page<Video> findByOwnerIdAndOperationalStateNot(@Param("ownerId") String ownerId, @Param("state") OperationalState state, Pageable pageable)` — paginated JPA derived query; `cascadeDeleteForAccount()` calls this in a loop with `Pageable.ofSize(100)` until empty; do NOT add the `findAllBy...` unbounded variant (OOM risk for high-volume accounts); no SKIP LOCKED needed (account deletion is a deliberate, non-concurrent operation)
  - [ ] Add to `VideoApprovalRequestRepository` (from `platform.video` repo layer, populated by Story 6.6 schema): `@Modifying @Query("UPDATE VideoApprovalRequest var SET var.status = 'CANCELLED' WHERE var.videoId = :videoId AND var.status = 'PENDING'") void cancelAllPendingForVideo(@Param("videoId") UUID videoId)` — called by `VideoDeletionService.deleteByUser()` for single-video user deletions; complement to `cancelAllPendingForOwners()` used in the account cascade
  - [ ] Add to `VideoQuotaRepository`: `@Modifying @Query("UPDATE VideoQuota vq SET vq.storageUsedBytes = 0, vq.bandwidthUsedBytes = 0 WHERE vq.ownerId = :ownerId") void resetBytesForOwner(@Param("ownerId") String ownerId)` — called by account deletion cascade only; quota is 0 because all videos are purged

- [x] **Task 5 — `DELETE /api/video/{id}` endpoint** (AC: 3, 4)
  - [ ] Add to `VideoResource.java` (existing file at `platform.video.api`):
    ```java
    @DeleteMapping("/{id}")
    @PreAuthorize("@videoAccessGuard.canDelete(authentication, #id)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Observed(name = "video.delete")
    public void deleteVideo(@PathVariable UUID id) {
        String currentUser = securityUtil.getCurrentUserName();
        videoDeletionService.deleteByUser(id, currentUser);
    }
    ```
  - [ ] Inject `VideoDeletionService` into `VideoResource`
  - [ ] The `@PreAuthorize` with `canDelete` fires BEFORE the method body — `VideoAccessGuard.canDelete()` throws `VideoDeletionNotAuthorisedException` internally, which `VideoApiAdvice` maps to 403; no explicit ownership check needed inside the method body
  - [ ] **Do NOT add a `@Observed` wrapper class-level if it conflicts** — `VideoResource` already has `@Observed(name = "video.upload")` at class level; add `@Observed(name = "video.delete")` at method level only; **post-implementation smoke test required**: verify the `video.delete` metric is recorded with that name in the metrics backend and is not overridden by the class-level `video.upload` observation — method-level `@Observed` override over class-level annotations depends on the Micrometer AOP proxy configuration and may silently not override

- [x] **Task 6 — `AccountDeletionCascadeListener`** (AC: 7, 8)
  - [ ] Create `platform.video.service.AccountDeletionCascadeListener` — `@Component`, `@Slf4j`, `@RequiredArgsConstructor`
  - [ ] Inject: `VideoDeletionService`, `ParentPlayerLinkRepository` (from `platform.security`), `VideoQuotaRepository`
  - [ ] **`@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`** on `AccountDeletionRequestedEvent` — **do NOT annotate this handler method with `@Transactional`**: AFTER_COMMIT means no surrounding transaction is active when the listener runs; adding `@Transactional` creates an unintended new transaction for the listener body; `deleteVideo()` already creates its own per-video transactions; a `@Transactional` annotation on the listener would also cause individual `deleteVideo()` failures to propagate upward rather than being caught by the continue-on-error loop:
    - Log INFO: `[VIDEO_ACCOUNT_DELETION] Cascading video purge for userId={}, role={}`
    - Call `videoDeletionService.cascadeDeleteForAccount(event.userId())` — deletes all videos for the primary account
    - If `event.userRole() == AccountRole.PARENT` (enum comparison, NOT String `==`) and `event.linkedPlayerIds()` is non-empty: for each `Long playerId` in `event.linkedPlayerIds()`, resolve the player's `ownerId` String → call `videoDeletionService.cascadeDeleteForAccount(playerOwnerId)`
    - **Edge case — parent with 0 linked players**: `event.linkedPlayerIds()` may be an empty list even for `AccountRole.PARENT`; this is valid (parent has no registered minor players); do NOT throw or NPE — the cascade runs only for the parent's own videos
    - **Edge case — player account deletion**: `event.userRole() == AccountRole.PLAYER` with empty `linkedPlayerIds`; cascade runs only for the player's own videos; test this case explicitly (see Task 11); add a defensive guard at the start of the linked-player cascade block: `if (event.userRole() == AccountRole.PLAYER && !event.linkedPlayerIds().isEmpty()) { log.error("[ACCOUNT_DELETION_INVARIANT_VIOLATION userId={} linkedPlayerIds={}] PLAYER role must have empty linkedPlayerIds — aborting linked cascade to prevent cross-account video purge", event.userId(), event.linkedPlayerIds()); return; }` — a future publisher bug sending a non-empty list for a PLAYER would otherwise cascade to and purge other users' videos
    - **Player ownerId resolution** (new sub-task — add to Task 6): add `String findOwnerIdByPlayerId(Long playerId)` to `UserRepository` (or equivalent identity repository in `platform.security`) bridging the Long player ID in `linkedPlayerIds` to the String `ownerId` stored in `videos.owner_id`; inject this repository into `AccountDeletionCascadeListener`; if no row found, skip with WARN log `[ACCOUNT_DELETION_NO_OWNER playerId={}]`
    - **Cancel pending video approval requests**: after cascading deletions, call `videoApprovalRequestRepository.cancelAllPendingForOwners(affectedOwnerIds)` — a `@Modifying @Query` setting `status = 'CANCELLED'` for all PENDING records where `playerId` is in the cascade set; orphaned PENDING approval records left by Story 6.6 will block the publish flow if not cleaned up; **deployment contract**: Story 6.6 MUST deploy before or simultaneously with Story 6.5 — do NOT use `@Autowired(required = false)` as a permanent crutch; if they cannot deploy together, use a feature flag controlled by `ConfigService` key `platform.video.approvalCancellation.enabled` (default **`true`** — Story 6.6 MUST deploy before 6.5, making `true` safe; a default of `false` is a silent GDPR compliance risk leaving orphaned approval records indefinitely); if 6.6 is delayed and 6.5 must deploy first, temporarily seed `false` in the V59 migration and document flipping it as a required post-6.6 deployment runbook action; **add `platform.video.approvalCancellation.enabled = true` to the V59 ConfigService seeds in Task 1**; the null-guard approach is fragile because a running pod will not pick up the new bean without restart, and a later "cleanup" removal of the null-guard would break rollback
  - [ ] **Failure handling**: if any single video deletion fails (exception), log ERROR with `[ACCOUNT_DELETION_VIDEO_FAILURE videoId=<id> userId=<userId>]` and CONTINUE to next video — do not abort the cascade on partial failure; at-least-once pattern means the outbox processor will retry any partially-inserted outbox rows

- [x] **Task 7 — `VideoDeletionOutboxProcessor`** (AC: 5)
  - [ ] Create `platform.video.service.VideoDeletionOutboxProcessor` — `@Component`, `@Slf4j`, `@RequiredArgsConstructor`
  - [ ] Inject: `VideoDeletionOutboxRepository`, `VideoRepository`, `VideoProviderAdapter`, `ConfigService`, `TransactionTemplate`
  - [ ] `@Scheduled(fixedDelayString = "${platform.video.deletion.outbox_poll_delay_ms:60000}")` — poll period from ConfigService default 60s
  - [ ] For each PENDING outbox row (ordered by `next_retry_at ASC`, SKIP LOCKED):
    - **Drill refCount check (CRITICAL — prevents shared drill asset deletion)**: before any Bunny API call, query `platform.session` for a drill_video_ref entry: if a `DrillVideoRefRepository.findByBunnyVideoId(row.getBunnyVideoId())` returns a record with `refCount > 1`, do NOT call `deleteAsset()`; instead: call `DrillVideoRefRepository.decrementRefCount(bunnyVideoId)` atomically; mark outbox row `COMPLETED`; append `video_deletion_log` with `triggeredBy`; skip to next row; only call `deleteAsset()` if there is no drill_video_ref entry OR `refCount <= 1`; inject `DrillVideoRefRepository` from `platform.session` into `VideoDeletionOutboxProcessor` (direct Spring injection — same monolith pattern); if `DrillVideoRefRepository` is unavailable (future module split), gate behind `@ConditionalOnBean` with a WARN log
    - **Null Bunny ID short-circuit**: if `row.getBunnyVideoId() == null` (video never reached encoding, no Bunny asset was ever created), skip the API call immediately; append `video_deletion_log`; mark `COMPLETED` — do this check on the outbox row itself BEFORE reloading the video entity
    - Reload the video — if `video.getProviderAssetId() == null` (nulled in step 4 of `deleteVideo()` after first processor run), treat as already deleted from Bunny (skip the API call; append `video_deletion_log` if not already logged; mark `COMPLETED`)
    - Call `videoProviderAdapter.deleteAsset(row.getBunnyVideoId())` — OUTSIDE `@Transactional`; treat Bunny 404 as success (already deleted — idempotent pattern from Story 6.4)
    - On success: `transactionTemplate.execute(_ -> { Video v = videoRepository.findById(row.getVideoId()).orElse(null); if (v != null) { v.setProviderAssetId(null); videoRepository.save(v); } videoDeletionLogRepository.save(...with triggeredBy=row.getTriggeredBy()...); row.setStatus("COMPLETED"); outboxRepository.save(row); })`
    - On failure: `row.setAttempts(row.getAttempts() + 1); row.setLastError(e.getMessage()); int maxAttempts = configService.getInt("platform.video.deletion.max_attempts", 5); if (row.getAttempts() >= maxAttempts) { row.setStatus("DEAD"); log.error("[DEAD_LETTER videoId={} triggeredBy={}]", row.getVideoId(), row.getTriggeredBy()); } else { long backoffMinutes = Math.min(60L, (long) Math.pow(2, row.getAttempts())); row.setNextRetryAt(Instant.now().plus(backoffMinutes, ChronoUnit.MINUTES)); }`; `outboxRepository.save(row)` inside `TransactionTemplate`; backoff schedule: attempt 1 → 2 min, 2 → 4 min, 3 → 8 min, 4 → 16 min, 5+ → 60 min cap
  - [ ] **Batch query**: `outboxRepository.findPendingBatch(Instant.now(), batchSize)` — new native query: `SELECT * FROM main.video_deletion_outbox WHERE status = 'PENDING' AND next_retry_at <= now() ORDER BY next_retry_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED`
  - [ ] Create `platform.video.repo.VideoDeletionOutbox` entity and `VideoDeletionOutboxRepository`
  - [ ] Create `platform.video.repo.VideoDeletionLog` entity and `VideoDeletionLogRepository`

- [x] **Task 8 — Extend `VideoPlayResource` with `@videoAccessGuard`** (AC: 1, 2)
  - [ ] Replace owner-only check in `VideoPlayResource.java` (lines 43–46) with `@PreAuthorize("@videoAccessGuard.canPlay(authentication, #id)")` annotation on the `play()` method
  - [ ] Remove the manual ownership check block (lines 43–46): `Video video = videoService.findById(id); if (!currentUser.equals(video.getOwnerId())) { throw new OperationNotAllowedException(...); }` — the guard handles this
  - [ ] Keep `String currentUser = securityUtil.getCurrentUserName()` for the `authorizePlayback(id, currentUser, extractClientIp(request))` call
  - [ ] The `SecurityConstants.IS_AUTHENTICATED` annotation at class/method level is superseded by `@videoAccessGuard.canPlay()` — remove `@PreAuthorize(SecurityConstants.IS_AUTHENTICATED)` from the method and add the guard annotation; the guard itself verifies authentication implicitly (all checks require the user to be known)
  - [ ] **HIDDEN parent access (AC 2)**: use Option A from dev notes — add `boolean skipHiddenCheck` parameter overload to `PlaybackService.authorizePlayback()` (backward-compatible: existing callers use the current signature which defaults to `false`); in `VideoPlayResource.play()`, after the `@PreAuthorize` guard passes, call `boolean parentAccess = videoAccessGuard.isParentOf(currentUser, video.getOwnerId())` and pass it as `skipHiddenCheck` to `playbackService.authorizePlayback(id, currentUser, ip, parentAccess)`; see `isParentOf()` spec in Task 3; this adds one lightweight parent-link DB read in the method body — acceptable
  - [ ] **HIDDEN state and coaches**: coaches are NOT granted a HIDDEN bypass — `VideoAccessGuard.canPlay()` step 4 returns `true` for a coach with an active booking, but `PlaybackService.authorizePlayback()` still throws `PlaybackDeniedException` for HIDDEN state; do NOT pass `skipHiddenCheck = true` for the coach path; this is correct behaviour (minor safety gate must hold until parent approval) and must be verified end-to-end in `VideoPlayResourceIT` (not `VideoDeleteResourceIT` — see Task 11)

- [ ] **Task 9 — VALID_TRANSITIONS cleanup** (AC: 9) — **SEPARATE PR, DO NOT bundle with this story's RBAC/deletion changes**
  - [ ] **This task must be submitted as a standalone PR** independent of the RBAC and deletion changes in Tasks 1–8; bundling a production safety net removal with feature work means delivery pressure can force a merge before ops sign-off is obtained; if the safety net removal is rejected or delayed, the entire story should not be blocked
  - [ ] **Ops sign-off is a hard blocker for this PR**: the PR description must include a Slack thread link or ticket reference showing ops confirmed `video.moderation.bypass` counter has been at 0 for 7+ consecutive days before the PR may be merged; a reviewer checklist item must verify this link exists
  - [ ] In `VideoLifecycleService.java` (line 36), remove `OperationalState.READY` from `PROCESSING → Set.of(SCANNING, READY, FAILED)` → change to `Set.of(OperationalState.SCANNING, OperationalState.FAILED)`
  - [ ] Remove the backward-compat bypass block (lines 63–66): the `if (current == PROCESSING && newState == READY)` warn + counter block
  - [ ] Remove the TODO comment at line 36 (the cleanup note itself)
  - [ ] Update `VideoLifecycleServiceTest` (if one exists) to remove the PROCESSING→READY test case; add a test that PROCESSING→READY now throws `TerminalStateViolationException`

- [x] **Task 10 — i18n keys** (AC: 3, 5)
  - [ ] Add to `src/frontend/src/i18n/en-US/index.js`:
    - `"video.deletionNotAuthorised"`: `"You are not authorised to delete this video"`
    - `"video.deletionFailed"`: `"Video deletion failed. Please try again."`
  - [ ] Follow existing pattern from `video.status.SUBSCRIPTION_LOCKED` added in Story 6.4
  - [ ] **Frontend Delete button — deferred to Story 6.6**: FR-POR-013 (self-service video deletion UI) is satisfied server-side by this story's `DELETE /api/video/{id}` endpoint; the Delete button on the `VideoStatusCard` and confirmation modal are part of Story 6.6 (Player Video Management Portal); Story 6.6 must explicitly reference this endpoint and the `video.deletionNotAuthorised` / `video.deletionFailed` i18n keys added here

- [x] **Task 11 — Tests** (AC: all)
  - [ ] `VideoAccessGuardTest` (unit, Mockito): all role/ownership combinations:
    - Owner plays own PUBLISHED video → allow
    - Admin plays any PUBLISHED video → allow
    - Any caller (including admin) plays a PURGED video → VideoNotFoundException (not 403 and not 200 — do not leak existence; admin is NOT exempt from the PURGED 404 gate)
    - Coach with active booking plays PUBLISHED player's video → guard returns true
    - Coach with active booking plays HIDDEN player's video → guard returns true BUT `PlaybackService.authorizePlayback()` throws PlaybackDeniedException → 403; **test this end-to-end in `VideoPlayResourceIT`, not `VideoDeleteResourceIT`** — this is a GET play flow; a test in the delete IT leaves the play endpoint's HIDDEN-block path uncovered
    - Coach with no booking plays player's video → deny (PlaybackDeniedException from guard step 5)
    - Parent plays linked player's HIDDEN video → guard returns true; `isParentOf()` also returns true; `skipHiddenCheck = true` passed to PlaybackService
    - Parent plays unlinked player's video → deny
    - Parent with 0 linked players plays any other user's video → deny
    - Unauthenticated user → deny
  - [ ] `VideoDeleteResourceIT` (`@SpringBootTest @Testcontainers`):
    - Owner deletes own video → 204; verify `operational_state = PURGED`, `storage_bytes = 0`, `provider_asset_id = null` in `videos`, `video_deletion_outbox` row inserted with correct `bunny_video_id`, `video_deletion_log` row with `triggered_by = 'USER_DELETION'`
    - Admin deletes any video → 204
    - Parent deletes linked player's video → 204
    - **Coach with active booking tries to DELETE a player's video → 403 + `video.deletionNotAuthorised`** (coach can play but cannot delete — guard must explicitly deny coach path)
    - Non-owner unrelated user tries to delete → 403 + `video.deletionNotAuthorised`
    - DELETE on non-existent video ID → 404 (VideoNotFoundException from `deleteByUser()` step 1 maps to 404 in `VideoApiAdvice`)
    - Unauthenticated request (no auth token) → 401 (verifies endpoint is not accidentally open in Spring Security config)
    - Owner deletes a video in `HIDDEN` state (awaiting parental approval) → 204; verify `operational_state = PURGED` in `videos`, any PENDING `video_approval_requests` row for that `videoId` is `CANCELLED`; confirms approval-request orphan cleanup path in `deleteByUser()`
    - Deleting already-PURGED video → idempotent 204; verify exactly ONE `video_deletion_outbox` row exists (no duplicate inserted by the second call)
    - Two concurrent DELETE requests on the same video by the same owner → exactly one succeeds with 204, the other either 204 (idempotent) or 409 (optimistic lock); verify only ONE `video_deletion_outbox` row and ONE `video_deletion_log` row exists after both complete
  - [ ] `AccountDeletionCascadeIT` (`@SpringBootTest @Testcontainers`):
    - Create 3 videos for a coach; fire `AccountDeletionRequestedEvent` (role=`COACH`) → assert all 3 videos `PURGED` in DB, `storage_bytes = 0`, `provider_asset_id = null`, 3 rows in `video_deletion_outbox`, `video_quotas.storage_used_bytes = 0`, `video_quotas.bandwidth_used_bytes = 0`
    - Create videos for a parent + 2 linked players; fire event (role=`PARENT`, linkedPlayerIds=[p1,p2]) → assert all cascaded videos PURGED, quota zeroed for all 3 owners
    - Player account deleted (role=`PLAYER`, linkedPlayerIds=[]) → assert player's own videos PURGED; no cascade to other users; no NPE on empty list
    - Parent with 0 linked players (role=`PARENT`, linkedPlayerIds=[]) → assert only parent's own videos PURGED; no NPE or empty-list error
    - Video in HIDDEN state owned by deleted user → assert it is also PURGED (cascade handles all non-PURGED operational states)
    - Video in PROCESSING state (no Bunny asset yet, `bunnyVideoId = null`) owned by deleted user → assert PURGED, outbox row inserted with `bunny_video_id = null`; processor treats as no-op and marks COMPLETED
    - Shared drill video (`refCount > 1`) owned by deleted coach → assert outbox row is marked `COMPLETED` without Bunny API call; `drill_video_refs.ref_count` decremented by 1; video is NOT physically deleted on Bunny
  - [ ] `VideoDeletionOutboxProcessorIT` (`@SpringBootTest @Testcontainers`):
    - Insert PENDING outbox row; run processor; mock `deleteAsset()` to succeed → assert row `COMPLETED`, `providerAssetId` nulled in `videos`, `video_deletion_log` row appended
    - Mock `deleteAsset()` to fail 5 times → assert row transitions to `DEAD` after `max_attempts`; assert `[DEAD_LETTER]` log marker
    - Mock `deleteAsset()` to throw on first call, succeed on second → assert `attempts = 1` after first drain, `COMPLETED` after second
  - [ ] `VideoLifecycleCleanupIT` (or update `VideoLifecycleServiceTest`): add test that `PROCESSING → READY` now throws `TerminalStateViolationException` (confirms Task 9 cleanup is in effect) — **belongs in the Task 9 separate PR, not here**
  - [ ] **Update existing `VideoPlayResourceIT`** — Task 8 removes the owner-only check from `VideoPlayResource` and replaces it with `@videoAccessGuard.canPlay()`; the existing test file likely has cases that mock/verify the old owner-only path; these must be updated: (a) remove any tests that directly verify the old ownership check block (lines 43–46 being called), (b) add/update tests that verify the `@PreAuthorize("@videoAccessGuard.canPlay(authentication, #id)")` annotation is the effective security gate (e.g. unauthenticated request → 401/403 before the method body is entered), (c) explicitly assert that a coach with an active booking receives 200 for a PUBLISHED video but 403 for a HIDDEN video even when `canPlay()` returns `true` — verifying the guard + PlaybackService HIDDEN interaction end-to-end

### Review Findings

- [ ] [Review][Patch] Drill ref lookup uses `findByVideoId` — fix to spec-required `findByBunnyVideoId(row.getBunnyVideoId())`; using wrong key silently misses shared drill assets and may cause physical deletion of Bunny content referenced by multiple drills [`VideoDeletionOutboxProcessor.java`, `DrillVideoRefRepository.java`]
- [ ] [Review][Patch] `approvalCancellation.enabled` seeded as `'false'` — flip to `'true'`; Story 6.6 deploys alongside this story; `false` is a GDPR compliance risk leaving orphaned PENDING approval records [`V59__deletion_infrastructure.sql`]
- [ ] [Review][Patch] `isParentOf()` never reads `VideoAccessCache` — `videoId` is set to `null` and the cache lookup is unreachable; every parent-play request incurs a second DB round-trip against the spec contract. [`VideoAccessGuard.java`]
- [ ] [Review][Patch] `cascadeDeleteForAccount()` infinite loop — `PageRequest.of(0, BATCH_SIZE)` always fetches page 0; a single video that consistently fails to purge (caught exception) re-appears on every iteration, creating an unbounded loop. [`VideoDeletionService.java`]
- [ ] [Review][Patch] `@Modifying` without `@Transactional` on `resetBytesForOwner()` — `cascadeDeleteForAccount()` is not `@Transactional`; calling `@Modifying` outside a transaction throws `TransactionRequiredException` at runtime. [`VideoQuotaRepository.java`]
- [ ] [Review][Patch] `DELETED` state accessible via parent HIDDEN bypass — `skipHiddenCheck=true` in `PlaybackService.authorizePlayback()` removes the HIDDEN guard but does not re-add a block for `OperationalState.DELETED`; a parent can receive a playback token for a logically-deleted video. [`PlaybackService.java`]
- [ ] [Review][Patch] Double `@Observed` on `PlaybackService` overloads — both the 3-arg delegate and the new 4-arg method carry `@Observed(name="video.playback.authorize")`; every authorisation generates two observations, double-counting all playback metrics. [`PlaybackService.java`]
- [ ] [Review][Patch] `Long.parseLong()` throws uncaught `NumberFormatException` — `isPlayerOwnedVideo()` calls `Long.parseLong(ownerId)` with no try-catch; a corrupt or unexpected ownerId format produces HTTP 500 instead of 403/404. [`VideoAccessGuard.java`]
- [ ] [Review][Patch] `decrementRefCount` and `completeRow` in separate transactions — if `completeRow` fails after `decrementRefCount` commits, the outbox row remains PENDING; the next processor run sees `refCount=1` (already decremented) and calls `deleteAsset()`, physically deleting a still-referenced Bunny asset. [`VideoDeletionOutboxProcessor.java`]
- [ ] [Review][Patch] PLAYER invariant guard returns before approval cancellation — the early-return at the invariant violation guard exits before `cancelAllPendingForOwners` is reached; the player's own approval requests are left unclean after invariant fires. [`AccountDeletionCascadeListener.java`]
- [ ] [Review][Patch] `ObjectOptimisticLockingFailureException` not caught — `@Version` field was added to `Video` but concurrent DELETE collisions throwing this exception are not caught anywhere in `VideoDeletionService`; they surface as HTTP 500. [`VideoDeletionService.java`]
- [ ] [Review][Patch] `deleteByUser()` belt-and-suspenders uses `canDelete(null, videoId)` instead of direct `ownerId.equals(currentUser)` — spec requires direct equality check specifically to catch email-vs-UUID format mismatches that `canDelete()` would silently pass; the broad `catch(Exception)` also swallows `VideoNotFoundException` from a re-invoked guard. [`VideoDeletionService.java`]
- [ ] [Review][Patch] Double video load in guard then controller — `VideoPlayResource.play()` calls `videoService.findById(id)` again immediately after `@PreAuthorize` guard already loaded the video; creates a race window and redundant DB read. [`VideoPlayResource.java`]
- [ ] [Review][Patch] `isOwner()` silently swallows infrastructure exceptions — any DB or service failure in the coach UUID resolution path is caught by a broad `catch(Exception)` that returns `false` without logging at WARN; transient failure produces a silent 403 indistinguishable from a real denial. [`VideoAccessGuard.java`]
- [ ] [Review][Patch] `configService.getLong()` int cast overflow — `windowDays` is cast from `long` to `int` without range check; a misconfigured value > `Integer.MAX_VALUE` produces a negative window, granting no coach access. [`VideoAccessGuard.java`]
- [ ] [Review][Patch] `video_deletion_outbox` missing `CHECK` constraint on `triggered_by` — unlike `video_deletion_log`, the outbox table has no `CHECK (triggered_by IN (...))`, allowing arbitrary string values to persist silently. [`V59__deletion_infrastructure.sql`]
- [ ] [Review][Patch] `VideoDeletionOutbox.triggeredBy` Java field default `"USER_DELETION"` — entity has a hardcoded field-level default; any code path that constructs the entity without explicitly calling `setTriggeredBy()` silently records the wrong trigger type. [`VideoDeletionOutbox.java`]
- [ ] [Review][Patch] Missing `VideoDeleteResourceIT` test: coach-with-booking deletes player's video → 403 — spec Task 11 explicitly requires this case in the IT (not just unit test). [`VideoDeleteResourceIT.java`]
- [ ] [Review][Patch] Missing `VideoPlayResourceIT` end-to-end: coach-plays-HIDDEN → 403 — spec Task 11 requires end-to-end guard + `PlaybackService` HIDDEN interaction verified in `VideoPlayResourceIT`. [`VideoPlayResourceIT.java`]
- [ ] [Review][Patch] Missing `VideoDeletionOutboxProcessorIT` retry-then-succeed scenario — spec requires assertion of `attempts=1` after first drain and `COMPLETED` after second call. [`VideoDeletionOutboxProcessorIT.java`]
- [ ] [Review][Patch] Missing `AccountDeletionCascadeIT` scenarios — 4 required cases absent: parent+2-players cascade, HIDDEN video purge, PROCESSING/null-bunnyId video purge, shared-drill refCount decrement. [`AccountDeletionCascadeIT.java`]
- [ ] [Review][Patch] `decrementRefCount` returns `void` — caller cannot detect a no-op decrement (refCount already 0); processor silently skips delete when it should have decremented, leaving the asset orphaned. [`DrillVideoRefRepository.java`]
- [x] [Review][Defer] Request-scoped `VideoAccessCache` in singleton `VideoAccessGuard` — standard Spring proxy pattern; correct in web context; only fails in bare unit tests without web context (mocked there) [`VideoAccessGuard.java`] — deferred, pre-existing
- [x] [Review][Defer] `@TransactionalEventListener` partial cascade failure — spec-acknowledged known gap; no automatic retry if listener dies mid-loop; ops can refire manually [`AccountDeletionCascadeListener.java`] — deferred, pre-existing
- [x] [Review][Defer] Native SQL queries bypass `@Version` optimistic lock — `@Modifying` native queries do not increment the `version` column; pre-existing codebase-wide pattern across all repositories [`VideoRepository.java`, others] — deferred, pre-existing
- [x] [Review][Defer] ownerId format ambiguity for mixed-type strings — Task 0 Identity Bridge investigation was a mandatory gate before RBAC implementation; if Task 0 was completed, format is confirmed [`VideoAccessGuard.java`] — deferred, pre-existing
- [x] [Review][Defer] `PROCESSING→READY` backward-compat bypass not removed — spec Task 9 explicitly requires a separate PR with ops sign-off; intentionally excluded from this story [`VideoLifecycleService.java`] — deferred, by design
- [x] [Review][Defer] `VideoPhysicalDeletionEvent` name is misleading (recommendation to rename) — spec dev notes call this a recommendation, not a requirement [`VideoPhysicalDeletionEvent.java`] — deferred, recommendation only

<!-- ─── Round 2 review findings (2026-06-23) ─── -->
- [x] [Review][Decision] `VideoPhysicalDeletionEvent` class collision — `VideoDeletionService` publishes `video.contract.event.VideoPhysicalDeletionEvent(UUID videoId)` but the existing `VideoPhysicalDeletionListener` listens for `session.contract.VideoPhysicalDeletionEvent(UUID videoId, UUID drillId)` — different class, Spring never dispatches to the listener; `adminVideoService.deleteVideo()` drill cleanup is silently dead for all Story 6.5 deletions. Two options: (a) update `VideoPhysicalDeletionListener` to consume the new event class and resolve `drillId` from `videoId` via repository lookup, or (b) remove the listener and accept that the outbox processor's drill-ref check (once F-01/F-02 are fixed) is the sole drill-cleanup path. [`VideoPhysicalDeletionListener.java`, `session.contract.VideoPhysicalDeletionEvent.java`, `video.contract.event.VideoPhysicalDeletionEvent.java`] — resolved: Option (a); added `onVideoPurged()` handler (log-only; drill-ref cleanup owned by outbox processor to avoid double-decrement)
- [x] [Review][Patch] Physical Bunny deletion never occurs — `deleteVideo()` nulls `video.providerAssetId` immediately at line 69 before writing the outbox row; the outbox processor's reload check (`videoOpt.get().getProviderAssetId() == null`, line 80) always fires on the first run and marks COMPLETED without calling `deleteAsset()`; GDPR 30-day physical erasure is never met. Fix: remove `video.setProviderAssetId(null)` from `deleteVideo()` — only null it in `completeRowWithNullAsset()` after a confirmed successful Bunny deletion. `VideoDeletionOutboxProcessorIT` tests pass because they seed `providerAssetId` directly (bypassing `deleteVideo()`), masking this production failure. [`VideoDeletionService.java:69`, `VideoDeletionOutboxProcessor.java:79-83`] — applied 2026-06-23
- [x] [Review][Patch] `findByBunnyVideoId` drill-ref check dead code — native JOIN uses `WHERE v.provider_asset_id = :bunnyVideoId` but `providerAssetId` is already null (see F above); the drill guard is permanently bypassed; shared Bunny assets are neither protected from deletion nor have their `refCount` decremented. Fix: change JOIN to `d.video_id = :videoId` using the outbox row's `videoId` field, which remains stable after purge. [`DrillVideoRefRepository.java:99-100`, `VideoDeletionOutboxProcessor.java:58`] — applied 2026-06-23
- [x] [Review][Patch] `FOR UPDATE SKIP LOCKED` in `findPendingBatch` is ineffective — `process()` has no `@Transactional`; the batch query runs in its own auto-committed transaction and releases all row locks before any row is processed; two pods fetch and process the same batch concurrently. Fix: atomically transition fetched rows to a `CLAIMED` status inside a short `@Transactional` fetch-and-claim step, then process only CLAIMED rows. [`VideoDeletionOutboxProcessor.java:39-44`, `VideoDeletionOutboxRepository.java`] — applied 2026-06-23 (two-phase claimPendingBatch/findClaimedBatch/resetStaleClaimed)
- [x] [Review][Patch] `cancelAllPendingForOwners` `@Modifying` without `@Transactional` throws at runtime — `AccountDeletionCascadeListener.onAccountDeleted()` runs AFTER_COMMIT (no ambient transaction); `cancelAllPendingForOwners` has no `@Transactional` of its own; calling a `@Modifying` query outside a transaction throws `TransactionRequiredException` on every account deletion. Fix: add `@Transactional` to `cancelAllPendingForOwners` (mirror `DrillVideoRefRepository.decrementRefCount` which already annotates `@Transactional`). [`VideoApprovalRequestRepository.java:21-27`, `AccountDeletionCascadeListener.java:63-65`] — applied 2026-06-23
- [x] [Review][Patch] `approvalCancellation.enabled` seeded as `value_type='STRING'` not `'BOOLEAN'` — `configService.getBoolean()` may only parse `BOOLEAN`-typed entries; if so, the lookup always returns the `false` default, silently disabling approval-request cancellation despite the migration seeding `'true'`. Fix: change `'STRING'` to `'BOOLEAN'` in the INSERT at V59 line 67. [`V59__deletion_infrastructure.sql:67`] — **voided 2026-06-23**: `chk_platform_config_type` constraint only allows `STRING`/`LONG`; `configService.getBoolean()` uses `"true".equalsIgnoreCase(value)` on STRING values — original `STRING` type is correct, no change needed
- [x] [Review][Patch] `approvalCancellation.enabled` Java-level default is `false` — both `deleteByUser()` and `onAccountDeleted()` call `configService.getBoolean(key, false)`; if ConfigService lookup fails at runtime (cache miss, startup race), approval cancellation is silently disabled and orphaned approval records accumulate indefinitely. Fix: change both defaults to `true` (config seeds `true`; missing key should not silently disable GDPR-critical behaviour). [`VideoDeletionService.java:128`, `AccountDeletionCascadeListener.java:63`] — applied 2026-06-23
- [x] [Review][Patch] Concurrent double-DELETE returns 404 instead of idempotent 204 — `deleteByUser()` checks PURGED at line 107 (returns 204 if already purged) but then calls `videoAccessGuard.canDelete(null, videoId)` at line 117, which does a second `findById()`; if a concurrent request purged the video between lines 107 and 117, `canDelete()` throws `VideoNotFoundException` which is re-thrown as 404 instead of the required idempotent 204. Fix: catch `VideoNotFoundException` from the internal guard call at line 118 and return silently (the video is already gone — treat as idempotent success). [`VideoDeletionService.java:117-119`] — applied 2026-06-23
- [x] [Review][Defer] `VideoPhysicalDeletionEvent` published inside `@Transactional` before commit — synchronous `@EventListener` consumers would process a deletion that may roll back; no current consumer exists; risk materialises when Story 10.4 adds a GDPR-acknowledgement consumer — ensure Story 10.4 uses `@TransactionalEventListener(AFTER_COMMIT)` only [`VideoDeletionService.java:90`] — deferred, no current consumer
- [x] [Review][Defer] `cascadeDeleteForAccount` quota reset non-atomic — JVM crash after the last per-video `deleteVideo()` commit but before `resetBytesForOwner()` leaves a deleted account's quota row permanently non-zero; no automatic retry path corrects this [`VideoDeletionService.java:169`] — deferred, spec-acknowledged known gap (dev notes "Known gap — quota reset vulnerability")
- [x] [Review][Defer] `canDelete(null, videoId)` belt-and-suspenders is a no-op in non-HTTP callers — null auth causes broad `catch(Exception)` to swallow the re-check; `@PreAuthorize` is the primary guard and the only enforceable gate in HTTP context [`VideoDeletionService.java:117`] — deferred, spec-acknowledged design
- [x] [Review][Defer] Parent-play/PURGE race — null `providerAssetId` may be passed to `generatePlaybackUrl()` if a video is concurrently purged between the `@PreAuthorize` canPlay check and `PlaybackService.authorizePlayback()`; provider should throw cleanly rather than silently returning a broken URL [`PlaybackService.java`] — deferred, low-probability race, provider exception maps to error response

## Dev Notes

### Identity Bridge — CRITICAL Investigation Required

`Video.ownerId` is a `String`. Its format determines how cross-module RBAC lookups work. Before implementing `VideoAccessGuard` and `AccountDeletionCascadeListener`, **verify the exact value stored in `videos.owner_id`** by:
1. Reading `VideoResource.java` `initiateUpload()` at line 60–61: `ownerId = coachId.toString()` where `coachId` is a `UUID` from `coachProfileService.getCoachIdByUserId(Long)`. So for coaches, `video.ownerId` is a **coach profile UUID string**.
2. Reading `VideoPlayResource.java` line 44: `currentUser.equals(video.getOwnerId())` where `currentUser = securityUtil.getCurrentUserName()`. This comparison passes in `VideoPlayResourceIT` — **verify what `getCurrentUserName()` returns in an authenticated coach context**: does it return the coach profile UUID string, or the login email?
3. The `ParentPlayerLink.playerId` and `Booking.playerId` are `Long` (platform user IDs). The bridge from `video.ownerId` (String) to `Long` player IDs must be established before the guard and cascade listener can work. Options:
   - If `ownerId` = username (email): look up the user by username → get their Long ID → cross-reference
   - If `ownerId` = coachProfileUUID string: convert via `UUID.fromString(ownerId)` → look up coach profile → get userId → cross-reference
   - If `ownerId` = player's Long ID as string: `Long.parseLong(ownerId)` directly

This identity mapping is the single most likely source of bugs in this story. Write a smoke test or add a log statement to the IT that prints `video.ownerId` vs `currentUserName` for the same user before relying on any assumption.

### What Already Exists — DO NOT Reinvent

- **`VideoLifecycleService`** — `transitionOperationalState()`, `setAccessState()`, `blockForSubscriptionExpiry()`, `archiveForLifecycle()`, `markPurged()`, `resetLifecycleClock()`. **DO NOT** call `markPurged()` for user or account-deletion deletions — it asserts `operationalState == READY` and will throw for videos in other states. The `VideoDeletionService.deleteVideo()` in this story sets fields directly for all-state deletion.
- **`QuotaService.decrementStorageBytes(String ownerId, long bytes)`** — added in Story 6.4. Already exists; call it from `VideoDeletionService`.
- **`VideoLifecycleLog` entity and repository** — already exist from Story 6.4; do NOT create duplicates. The `video_deletion_log` in this story is a **new, separate table** for user/account/outbox deletions (different schema from `video_lifecycle_log`).
- **`BunnyVideoProviderAdapter.deleteAsset()`** — already implemented and idempotent (404 = success). **DO NOT** reimplement.
- **`PlayerSubscriptionQueryPort` pattern** — template for cross-module port interfaces. If `VideoAccessGuard` needs to check coach relationships via `BookingRepository`, it may inject `BookingRepository` directly (same monolith, acceptable in DDD if contained) OR follow the port pattern. Prefer direct injection since this is the same bounded context (no external system boundary crossed).
- **`OperationNotAllowedException`** — exists in `platform.security.contract.exception`. If `VideoDeletionNotAuthorisedException` would be a thin wrapper, just throw `OperationNotAllowedException` and catch/map it in `VideoApiAdvice` — but the epics specify `video.deletionNotAuthorised` as the code, so a dedicated exception is cleaner.
- **`LifecycleTrigger` constants** — `platform.video.contract.LifecycleTrigger` already has `SYSTEM`, `SUBSCRIPTION_EXPIRY`, `YEARLY_EXPIRY_CLOCK_RESET`, `ACCOUNT_DELETION`. Add `USER_DELETION` here. All `triggered_by` values in `video_deletion_log` MUST use this class — no raw strings.
- **`SecurityUtil.isAdmin()`** — exists; use for admin bypass in `VideoAccessGuard`.
- **`ParentPlayerLinkRepository`** — `findByParentId(Long parentId)`, `findByPlayerId(Long playerId)` both exist. Use these for parent-child video access checks.

### VALID_TRANSITIONS Cleanup (Task 9)

The TODO at `VideoLifecycleService.java:36` reads:
```
// PROCESSING→READY kept for backward compat: encoding.success webhook fires before Story 6.3 is deployed.
// TODO Story 6.5: remove PROCESSING→READY once deployment confirms all PROCESSING→READY events have ceased.
```
This story owns that cleanup. Also remove the bypass block at lines 63–66:
```java
if (current == OperationalState.PROCESSING && newState == OperationalState.READY) {
    log.warn("PROCESSING→READY bypass taken for videoId={} ...", videoId);
    meterRegistry.counter("video.moderation.bypass", ...).increment();
}
```
**Pre-condition**: confirm with ops that the `video.moderation.bypass` counter has been at 0 for a sustained period (recommend 7+ days) before removing it. If not confirmed, KEEP the code and remove only the TODO comment. Gate the removal: the PR merging Task 9 must include a Slack/ticket reference from ops sign-off as a checklist item — do not rely solely on the pre-condition note in the story.

### `VideoAccessGuard` — Coach Access Window

From ConfigService key `platform.video.access.coach_window_days` (default 90) — note: this key is in the `access` namespace, not `deletion`; it controls playback authorisation. Coaches can access player videos within N days of their last completed booking together. Query:
```sql
SELECT COUNT(*) FROM booking.bookings
WHERE coach_id = :coachId
  AND player_id = :playerId
  AND status = 'COMPLETED'
  AND updated_at >= :windowStart
```
Add this as a new query on `BookingRepository`. The `coachId` is `UUID`, `playerId` is `Long` — you need to resolve both from the current user context and from `video.ownerId` (see Identity Bridge above).

### Transaction Boundaries for `VideoDeletionService`

- `deleteVideo()` MUST be `@Transactional` — it atomically sets DELETED state, decrements quota, and inserts outbox + log rows.
- `cascadeDeleteForAccount()` is NOT `@Transactional` at the method level — it loops and calls `deleteVideo()` per video, each in its own transaction. This avoids one massive transaction for accounts with many videos.
- `VideoDeletionOutboxProcessor`: `deleteAsset()` call is OUTSIDE `@Transactional`; the success/failure update is inside `TransactionTemplate` — same pattern as `VideoLifecycleScheduler` from Story 6.4.

### `VideoPhysicalDeletionEvent` — Extension Hook, Not a Deletion Trigger

**Divergence from epics (intentional improvement):** The epics describe `VideoPhysicalDeletionEvent` as the trigger for Bunny.net deletion ("Given a physical deletion job processes a VideoPhysicalDeletionEvent..."). This implementation replaces that with an outbox pattern — the event is NOT the deletion trigger. This is architecturally correct (the outbox provides at-least-once retry that an in-process event cannot), but the name `VideoPhysicalDeletionEvent` is now misleading — it fires when a video is *logically* deleted (set to PURGED), not when the *physical* Bunny deletion completes.

**Action for Story 10.4 and future stories:** Do not build any consumer that expects this event to mean "Bunny deletion is done." If a future story needs to react to physical deletion completion, listen to an event published by `VideoDeletionOutboxProcessor` when an outbox row transitions to `COMPLETED`.

**Before Story 10.4 is implemented**: update Story 10.4's dev notes with this warning — the 10.4 author must NOT treat `VideoPhysicalDeletionEvent` as a "Bunny deletion complete" signal when wiring GDPR erasure acknowledgement; the erasure is logically complete when videos transition to PURGED (this event fires), not when Bunny physically removes the file (outbox COMPLETED). Consider renaming the event to `VideoPurgedEvent` or `VideoLogicallyDeletedEvent` now while blast radius is a single Find & Replace — the cost of a naming fix at Story 10.4 integration is a potential production incident from misinterpretation.

`VideoPhysicalDeletionEvent` is published AFTER_COMMIT by `VideoDeletionService.deleteVideo()`. It is an **in-process extension hook** for cross-module listeners that need to react to a video being logically deleted (e.g., a future analytics or notification module). It does **NOT** drive physical Bunny.net deletion — that is exclusively owned by the `video_deletion_outbox` + `VideoDeletionOutboxProcessor` poll loop.

Do NOT add an `@EventListener` on `VideoPhysicalDeletionEvent` that makes Bunny API calls or inserts additional outbox rows — both would cause double-deletion attempts.

### AccountDeletionCascadeListener — At-Least-Once Guarantee

The `@TransactionalEventListener(AFTER_COMMIT)` fires after the account deletion transaction commits. If it throws (pod restart mid-cascade, DB blip), Spring does NOT retry it. The outbox pattern inside `VideoDeletionService.deleteVideo()` provides at-least-once for the physical Bunny deletion — but if the cascade listener itself dies mid-loop, some videos may not be queued.

Mitigation: log each video queued at DEBUG, log a summary at INFO after the loop (`[VIDEO_ACCOUNT_DELETION_COMPLETE userId={} videosQueued={}]`). If only a subset of videos are found to be queued, ops can refire the event manually.

**Known gap — no automatic reconciliation**: if the listener dies mid-loop, videos processed before the crash are queued (outbox retries handle them), but videos not yet reached are silently missed. A future backlog item should add a scheduled reconciliation query: `SELECT v.id FROM main.videos v JOIN users u ON v.owner_id = u.owner_id WHERE u.deleted_at IS NOT NULL AND v.operational_state != 'PURGED'` and re-queue matches. Track this as a known gap in the project backlog. This is the same acknowledged gap as Story 6.4's outbox pattern.

**Known gap — quota reset vulnerability**: `cascadeDeleteForAccount()` resets quota AFTER the per-video loop via `resetBytesForOwner()`. If the pod crashes after the loop completes but before the reset (or if the reset itself throws), the quota for that owner is left at its pre-cascade value even though all videos are PURGED. The reconciliation job spec above should include: `UPDATE video_quotas SET storage_used_bytes = 0, bandwidth_used_bytes = 0 WHERE owner_id IN (SELECT owner_id FROM users WHERE deleted_at IS NOT NULL)` — so quota re-zeroing is covered by the same reconciliation sweep.

### `VideoAccessCache` — Request-Scoped Parent Decision Cache

To avoid a double parent-link DB query (once in `canPlay()` step 3 during `@PreAuthorize` evaluation, once in `isParentOf()` called from the `VideoPlayResource.play()` method body), introduce a request-scoped cache:

```java
@Component
@RequestScope
public class VideoAccessCache {
    private final Map<UUID, Boolean> parentDecisions = new HashMap<>();
    public void setParentDecision(UUID videoId, boolean isParent) { parentDecisions.put(videoId, isParent); }
    public Optional<Boolean> getParentDecision(UUID videoId) { return Optional.ofNullable(parentDecisions.get(videoId)); }
}
```

- `canPlay()` step 3: after the `anyMatch()` result is known, call `videoAccessCache.setParentDecision(videoId, matched)`
- `isParentOf()`: call `videoAccessCache.getParentDecision(videoId).orElseGet(() -> <DB fallback>)` — eliminates the second round-trip for parent callers; DB fallback only fires if `isParentOf()` is somehow called outside a `@PreAuthorize` context
- Add `VideoAccessCache` to the `VideoAccessGuard` injection list (Task 3); place in `platform.video.service` package; add to the New files list in File Structure

### SCANNING / PROCESSING State Playback

`VideoAccessGuard.canPlay()` step 2 (Owner → allow) lets owners play their own video in any non-PURGED state, including `SCANNING` and `PROCESSING`. A video in these states has no playback URL yet. Before completing Task 8, **confirm that `PlaybackService.authorizePlayback()` throws an appropriate exception (e.g. `VideoNotReadyException` → 409 Conflict or 423 Locked) for these states** rather than returning a null URL that causes a 500 or a broken stream on the client. If no such guard exists in `PlaybackService`, add it as part of Task 8 and document it in the modified files list.

### `VideoPlayResource` — HIDDEN Parent Access Path

`PlaybackService.authorizePlayback()` currently throws `PlaybackDeniedException` for `HIDDEN` videos (added in Story 6.4, Task 8). The parent approval use case (AC 2) requires parents to play `HIDDEN` videos. Options:

**Option A (Recommended)**: Add a boolean `skipHiddenCheck` parameter to `PlaybackService.authorizePlayback()` — when `true`, the HIDDEN check is bypassed. `VideoPlayResource.play()` calls `videoAccessGuard.isParentOf(currentUser, video.getOwnerId())` and passes the result as `skipHiddenCheck`.

**Option B**: Override the HIDDEN check inside `VideoAccessGuard.canPlay()` using a thread-local or request attribute — more complex, avoid.

Choose Option A. The `authorizePlayback()` signature change must be backward-compatible (overloaded or default false).

### File Structure

**New files:**
- `src/main/resources/db/migration/V59__deletion_infrastructure.sql`
- `src/main/java/com/softropic/skillars/platform/security/contract/AccountRole.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/event/AccountDeletionRequestedEvent.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoAccessGuard.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionService.java`
- `src/main/java/com/softropic/skillars/platform/video/service/AccountDeletionCascadeListener.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java`
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutbox.java`
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutboxRepository.java`
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionLog.java`
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionLogRepository.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/exception/VideoDeletionNotAuthorisedException.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/event/VideoPhysicalDeletionEvent.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoAccessCache.java` (request-scoped parent decision cache — see "VideoAccessCache" in Dev Notes)

**Modified files:**
- `src/main/java/com/softropic/skillars/platform/video/api/VideoResource.java` — add `DELETE /api/video/{id}` endpoint; inject `VideoDeletionService`
- `src/main/java/com/softropic/skillars/platform/video/api/VideoPlayResource.java` — replace owner-only check with `@videoAccessGuard.canPlay()`; add `skipHiddenCheck` path for parent
- `src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java` — add `VideoDeletionNotAuthorisedException` handler
- `src/main/java/com/softropic/skillars/platform/video/contract/VideoErrorCode.java` — add `DELETION_NOT_AUTHORISED`
- `src/main/java/com/softropic/skillars/platform/video/contract/LifecycleTrigger.java` — add `USER_DELETION` constant
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java` — add `findByOwnerIdAndOperationalStateNot(ownerId, state, Pageable)` (paginated — replaces unbounded variant)
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoQuotaRepository.java` — add `resetBytesForOwner()`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java` — add PURGED state guard at entry of `transitionOperationalState()` (Task 4); remove PROCESSING→READY backward-compat path (Task 9 — separate PR)
- `src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java` — add `skipHiddenCheck` parameter overload to `authorizePlayback()`
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java` — add `existsRecentCompletedBooking(UUID coachId, Long playerId, Instant windowStart)` query
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoApprovalRequestRepository.java` — add `cancelAllPendingForVideo(UUID videoId)` query (Task 4a); referenced by `deleteByUser()` and the account cascade
- `src/frontend/src/i18n/en-US/index.js` — add `video.deletionNotAuthorised`, `video.deletionFailed` i18n keys

### References

- `VideoLifecycleService.java` lines 32–44 — `VALID_TRANSITIONS` map; lines 63–66 — PROCESSING→READY bypass to remove [Source: `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java`]
- `VideoResource.java` lines 52–62 — existing upload flow with `coachId.toString()` as ownerId [Source: `src/main/java/com/softropic/skillars/platform/video/api/VideoResource.java`]
- `VideoPlayResource.java` lines 39–68 — existing owner-only check to be replaced by guard; IP extraction helper to retain [Source: `src/main/java/com/softropic/skillars/platform/video/api/VideoPlayResource.java`]
- `VideoApiAdvice.java` — exception handler pattern; `PlaybackDeniedException` → 403 as template for `VideoDeletionNotAuthorisedException` [Source: `src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java`]
- `ParentPlayerLinkRepository.java` — `findByParentId(Long)`, `findByPlayerId(Long)` exist; `ParentPlayerLink.parentId` and `playerId` are both `Long` [Source: `src/main/java/com/softropic/skillars/platform/security/repo/ParentPlayerLinkRepository.java`]
- `Booking.java` — `coachId: UUID`, `playerId: Long`, status strings [Source: `src/main/java/com/softropic/skillars/platform/booking/repo/Booking.java`]
- `BookingRepository.java` — `countInFlightBookings(Long playerId, UUID coachId)` as template for coach access query; `COMPLETED` status string [Source: `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java`]
- `V57__moderation_config.sql` — ConfigService seed pattern with `ON CONFLICT (key) DO NOTHING` [Source: `src/main/resources/db/migration/V57__moderation_config.sql`]
- `V58__lifecycle_schema.sql` — table creation pattern; index naming convention [Source: `src/main/resources/db/migration/V58__lifecycle_schema.sql`]
- `LifecycleTrigger.java` — existing triggered_by constants; add `USER_DELETION` here [Source: `src/main/java/com/softropic/skillars/platform/video/contract/LifecycleTrigger.java`]
- `QuotaService.java` — `decrementStorageBytes(String ownerId, long bytes)` exists (added Story 6.4) [Source: `src/main/java/com/softropic/skillars/platform/video/service/QuotaService.java`]
- Story 6.4 handoff notes — `HIDDEN → 403` in `PlaybackService` (Task 8 note: parent path is Story 6.5); `VideoPlayResource` owner-only check: "Story 6.5 will open this to coaches + admins via `@videoAccessGuard`" (line 42 comment) [Source: `_bmad-output/implementation-artifacts/skillars-6-4-streaming-security-video-lifecycle.md`]
- Project context — DDD module structure; `platform.{module}.{layer}` hierarchy; no cross-module `platform.*` imports in `infrastructure`; direct Spring injection is OK between platform modules (modular monolith) [Source: `_bmad-output/project-context.md`]

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

### File List
