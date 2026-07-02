# Story Deferred-6: Video Module Hardening

Status: done

## Story

As a platform engineer,
I want the video module's state machine, deletion events, and cascade flows to be correct,
so that videos are never stuck in wrong states, deletion consumers are safe to add, and account deletion properly cleans up approval rows.

## Acceptance Criteria

1. **Given** `VideoPhysicalDeletionEvent` is published from `VideoDeletionService.deleteVideo()`
   **When** the event is published inside a `@Transactional` method before the TX commits
   **Then** any `@EventListener` (synchronous) consumer would process a deletion that could still roll back — the event MUST be published via `@TransactionalEventListener(AFTER_COMMIT)` instead, OR the event must be published after the transaction commits
   **And** the event is renamed from `VideoPhysicalDeletionEvent` to `VideoPurgedEvent` to accurately describe what happened (Story 6.5 W6 — "physical deletion" is misleading; the video is logically deleted; Bunny.net purge is async)
   **And** all existing consumers of `VideoPhysicalDeletionEvent` (including `VideoPhysicalDeletionListener` in `DrillUploadService`) are updated to `VideoPurgedEvent`

2. **Given** `VideoService.confirmUpload()` is called to mark an upload as confirmed
   **When** it transitions the video state to `PROCESSING`
   **Then** the transition goes through `VideoLifecycleService.transition(videoId, PROCESSING)` rather than a direct `videoRepository.save()` call — so `VALID_TRANSITIONS` is enforced and future state refactors are safe

3. **Given** `VideoService.retryUpload()` is called to retry a failed upload
   **When** the method runs
   **Then** `videos.operational_state` is transitioned back to `UPLOADING` before the new upload session is initiated — so the video is not left in `FAILED` while the retry is in progress (which causes the ReconciliationWorker to re-FAIL it)

4. **Given** an account deletion cascade runs for a user who owns videos
   **When** the account-deletion cascade completes (`AccountDeletionCascadeListener.onAccountDeleted()`, which calls `VideoDeletionService.cascadeDeleteForAccount()` for the primary account and any linked player accounts)
   **Then** all pending `VideoApprovalRequest` rows for the deleted user's videos are cancelled/deleted — parents no longer see stale approval cards for purged videos
   **And** `VideoApprovalRequestRepository.cancelAllPendingForOwners(affectedOwnerIds)` is called once, after the cascade(s) complete, scoped to the affected owner IDs — a bulk equivalent of calling `cancelAllPendingForVideo(videoId)` per video that avoids N+1 queries
   **Note (code review, 2026-07-02):** this guarantee lives in `AccountDeletionCascadeListener`, not inside `cascadeDeleteForAccount()` itself. It is correct today because the listener is the sole production caller of `cascadeDeleteForAccount()`. Any future caller that bypasses the listener would skip approval cancellation — flagged as a latent risk, not fixed in code, since no such caller currently exists.

## Tasks / Subtasks

- [x] **Task 1 — Rename `VideoPhysicalDeletionEvent` → `VideoPurgedEvent`** (AC: 1)
  - [x] Rename the class: `src/main/java/com/softropic/skillars/platform/video/contract/event/VideoPhysicalDeletionEvent.java` → `VideoPurgedEvent.java`
  - [x] Update the class declaration — record kept its single `videoId` field
  - [x] Find and update all references (see Completion Notes for the two-events discovery)
  - [x] Known consumers: `VideoPhysicalDeletionListener.java` (`platform.session.service`) — renamed the `onVideoPurged` parameter type; the unrelated `onVideoPhysicalDeletion` (drillId) method was left untouched — see Completion Notes

- [x] **Task 2 — Move `VideoPurgedEvent` publication to AFTER_COMMIT** (AC: 1)
  - [x] Read `VideoDeletionService.java` — `deleteVideo()` confirmed as the publishing method
  - [x] Already implemented via **Option B** prior to this story: both `VideoPhysicalDeletionListener.onVideoPurged` and `VideoSseService.onVideoPurged` (renamed from `onPhysicalDeletion`) were already annotated `@TransactionalEventListener(phase = AFTER_COMMIT)` — no behavioral change needed, only the rename
  - [x] Verified via `VideoPurgedEventIT` that a rolled-back `deleteVideo()` never reaches the listener, and a committed one does
  - [x] Added the CRITICAL Javadoc requirement on `VideoPurgedEvent` stating future consumers MUST use `@TransactionalEventListener(phase = AFTER_COMMIT)`

- [x] **Task 3 — Fix `confirmUpload()` to go through lifecycle enforcement** (AC: 2)
  - [x] Replaced the direct `video.setOperationalState(PROCESSING); videoRepository.save(video);` with `videoLifecycleService.transitionOperationalState(videoId, OperationalState.PROCESSING)` (actual method name — the story draft's `transition()` does not exist)
  - [x] `UPLOADING → PROCESSING` was already present in `VALID_TRANSITIONS` — no state-machine change needed

- [x] **Task 4 — Fix `retryUpload()` to transition back to UPLOADING** (AC: 3)
  - [x] Added `videoLifecycleService.transitionOperationalState(request.videoId(), OperationalState.UPLOADING)` as the first statement in the retry `try` block, before quota reservation and session creation
  - [x] `FAILED → UPLOADING` was already present in `VALID_TRANSITIONS` — no state-machine change needed
  - [x] Corrected two pre-existing `VideoRetryUploadIT` tests that had pinned the pre-fix bug (asserted the video stayed `FAILED` during retry)

- [x] **Task 5 — Cancel pending approvals on account deletion cascade** (AC: 4)
  - [x] `VideoApprovalRequestRepository.cancelAllPendingForVideo(videoId)` already existed (UPDATE-based soft cancel, not hard delete — `status` is a plain `VARCHAR` with a CHECK constraint, not an enum) and is already used by `VideoDeletionService.deleteByUser()` for the single-video user-deletion path
  - [x] `cascadeDeleteForAccount()` itself does **not** call it per-video — see Completion Notes for why no code change was made here
  - [x] `VideoApprovalRequestRepository` was already injected into `VideoDeletionService`

- [x] **Task 6 — Integration tests** (AC: 1, 2, 3, 4)
  - [x] TSID range `9340_xxx` used for the new approval-cancellation seed data
  - [x] `confirmUpload_transitionsViaLifecycleService()` — `VideoServiceTest` (Mockito unit test): verifies `transitionOperationalState()` was called and `videoRepository.save()` was never called directly
  - [x] `retryUpload_setsUploadingBeforeNewSession()` — `VideoServiceTest`: verifies (via `InOrder`) the transition to `UPLOADING` happens before quota reservation and session save
  - [x] `cascadeDeleteAccount_cancelsPendingApprovals()` — added to `AccountDeletionCascadeIT`: seeds 2 videos + 1 pending approval each, runs the cascade, asserts both approvals are `CANCELLED`
  - [x] `videoPurgedEvent_publishedAfterCommit()` — new `VideoPurgedEventIT`: asserts the listener fires after a committed `deleteVideo()`, and asserts (via `Mockito.never()` + `Awaitility`) it never fires after a rolled-back one

## Dev Notes

### `VideoPurgedEvent` vs "physical deletion"

The original name `VideoPhysicalDeletionEvent` was misleading: the event fires when a video is logically deleted in the DB. The actual Bunny.net CDN/storage purge is asynchronous and happens through `VideoPhysicalDeletionListener` calling the provider. `VideoPurgedEvent` is accurate: the video has been purged from the platform's perspective (DB state = PURGED), regardless of whether the provider has physically removed it yet.

### VALID_TRANSITIONS and the state machine

Read `VideoLifecycleService.java` fully before making changes. The valid transitions map determines what state changes are legal. Adding `FAILED → UPLOADING` and verifying `UPLOADING → PROCESSING` are present is necessary before calling `transition()` from `retryUpload()` and `confirmUpload()`. If `transition()` throws on an invalid transition, the current code (direct save) would have bypassed the error — the fix may surface a latent bug if the transition is truly invalid by the intended state machine design.

### `VideoApprovalRequest` status lifecycle

Read `VideoApprovalRequest.java` to confirm:
- Whether `status` is a String or enum
- The valid terminal states (`APPROVED`, `REJECTED`, `CANCELLED`, or similar)
- Whether a hard-delete or soft-cancel is correct for the cascade

If the approval flow uses hard-delete, `@Modifying @Query("DELETE FROM VideoApprovalRequest ...")` is correct. If it uses soft-cancel (`status = 'CANCELLED'`), use an UPDATE query instead.

### `VideoPhysicalDeletionListener` location

This listener was added in Story 4.3 (`DrillUploadService` territory) to handle `VideoPhysicalDeletionEvent` for drill video orphan cleanup. Its exact package location must be found before renaming:
`find src -name "VideoPhysicalDeletionListener.java"`
Update the import, the `@EventListener` parameter type, and the log message to reference `VideoPurgedEvent`.

### References — Files to Read Before Implementing

- `VideoDeletionService.java` — `deleteVideo()` and `cascadeDeleteForAccount()` methods
- `VideoService.java` — `confirmUpload()` and `retryUpload()` methods
- `VideoLifecycleService.java` — `VALID_TRANSITIONS` map and `transition()` signature
- `VideoPhysicalDeletionEvent.java` — current payload fields (to replicate in `VideoPurgedEvent`)
- `VideoPhysicalDeletionListener.java` — current consumer to update
- `VideoApprovalRequest.java` — status field and lifecycle
- `VideoApprovalRequestRepository.java` — existing query methods
- `AccountDeletionCascadeListener.java` — `@TransactionalEventListener(AFTER_COMMIT)` pattern to follow

## Dev Agent Record

### Agent Model Used

claude-sonnet-5

### Debug Log References

None — no test failures required debugging beyond the two pre-existing `VideoRetryUploadIT` assertions noted below (expected consequence of the AC3 fix, not a defect).

### Completion Notes List

- **Task 1 — two distinct `VideoPhysicalDeletionEvent` classes existed.** The codebase had
  `platform.session.contract.VideoPhysicalDeletionEvent(videoId, drillId)` — published by
  `DrillUploadService` for drill-orphan video cleanup (Story 4.3) — and a *separate*
  `platform.video.contract.event.VideoPhysicalDeletionEvent(videoId)` — published by
  `VideoDeletionService.deleteVideo()` for the logical-purge notification AC1 describes. Only the
  latter was renamed to `VideoPurgedEvent`; the drill-orphan event and its
  `onVideoPhysicalDeletion` listener method were left untouched as they are an unrelated concept
  outside AC1's scope. `VideoPhysicalDeletionListener` (in `platform.session.service`, not
  `platform.session.listener` as the Dev Notes assumed) keeps both listener methods, with only the
  `onVideoPurged` parameter type updated.
- **Task 2 — AFTER_COMMIT was already correct.** Both consumers
  (`VideoPhysicalDeletionListener.onVideoPurged`, `VideoSseService.onVideoPurged`) already used
  `@TransactionalEventListener(phase = AFTER_COMMIT)` (Option B) before this story. No publication
  logic changed; only the rename and a new Javadoc requirement on `VideoPurgedEvent`.
- **Tasks 3/4 — method name and VALID_TRANSITIONS.** `VideoLifecycleService`'s real method is
  `transitionOperationalState(UUID, OperationalState)`, not `transition()`. `VALID_TRANSITIONS`
  already contained `UPLOADING→PROCESSING` and `FAILED→UPLOADING`, so no state-machine changes
  were required — only `VideoService.confirmUpload()`/`retryUpload()` needed to route through the
  lifecycle service instead of a direct `videoRepository.save()`.
- **Task 4 — fixed two pre-existing tests that pinned the bug.** `VideoRetryUploadIT` had
  `retryUpload_onFailedVideo_createsNewSessionVideoStaysFailed` (asserting the video incorrectly
  stayed `FAILED` during retry) and an assertion in `retryUpload_quotaReleaseFails_sessionNotCreated`
  asserting the same. Both were updated to assert `UPLOADING`, matching the AC3 fix; the first test
  was renamed to `retryUpload_onFailedVideo_createsNewSessionVideoTransitionsToUploading`.
- **Task 5 — AC4 was already functionally satisfied, no production code changed.**
  `AccountDeletionCascadeListener.onAccountDeleted()` already calls
  `VideoApprovalRequestRepository.cancelAllPendingForOwners(affectedOwnerIds)` once, after
  `cascadeDeleteForAccount()` completes for the primary account and any linked player accounts —
  this covers every video owned by those owners, achieving the same outcome as calling
  `cancelAllPendingForVideo(videoId)` per video inside the cascade loop, without the N+1 query cost.
  Adding the literal per-video call as the story draft specified would have been redundant (the
  bulk query already cancels the same rows) so it was intentionally not added. A regression test
  (`cascadeDeleteAccount_cancelsPendingApprovals`) was added to lock in this behavior since no test
  previously covered it.
- **Task 6 — full test run.** Ran the complete `video` and `session` package test suites
  (169 test classes) plus the new/changed tests explicitly; all pass with 0 failures/errors.

### File List

**Renamed Files:**
- `src/main/java/com/softropic/skillars/platform/video/contract/event/VideoPhysicalDeletionEvent.java` → `VideoPurgedEvent.java`

**Modified Files:**
- `src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionService.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoSseService.java`
- `src/main/java/com/softropic/skillars/platform/session/service/VideoPhysicalDeletionListener.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoService.java`
- `src/test/java/com/softropic/skillars/platform/video/service/VideoRetryUploadIT.java` (corrected two assertions pinning the pre-fix bug)
- `src/test/java/com/softropic/skillars/platform/video/service/AccountDeletionCascadeIT.java` (added AC4 regression test)

**Added Files:**
- `src/test/java/com/softropic/skillars/platform/video/service/VideoServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/video/service/VideoPurgedEventIT.java`

### Review Findings

1. - [x] [Review][Patch] `retryUpload()` left videos stuck in `UPLOADING` (up to 60min or indefinitely) when the retry itself failed after the state transition committed, contradicting AC3 — fixed by reverting to `FAILED` in the `finally` block [VideoService.java:141-224]
2. - [x] [Review][Patch] AC4 wording said `cascadeDeleteForAccount()` calls `cancelAllPendingForVideo(videoId)` per video, but cancellation actually happens in `AccountDeletionCascadeListener` via a bulk `cancelAllPendingForOwners()` call — spec wording updated to match the actual (functionally equivalent, N+1-avoiding) implementation; no code change

### Change Log

- 2026-07-02 — Implemented Story deferred-6 (Video Module Hardening): renamed `VideoPhysicalDeletionEvent`→`VideoPurgedEvent`, routed `confirmUpload()`/`retryUpload()` through `VideoLifecycleService`, verified AC1/AC4 were already correctly implemented and added regression tests to lock in that behavior, fixed two pre-existing tests that pinned the AC3 bug. 6/6 tasks complete, status → review.
- 2026-07-02 — Code review (bmad-code-review, 3-layer adversarial + spec audit): found and fixed a regression where `retryUpload()` could permanently strand a video in `UPLOADING` on failure — added catch-and-revert-to-`FAILED` logic plus a new regression test (`retryUpload_reserveFailsBeforeSessionCreated_revertsToFailed`) and updated the existing pinned test to assert the corrected behavior. Also corrected AC4's wording to describe the actual bulk-cancellation implementation. Both patches applied; status → done.
