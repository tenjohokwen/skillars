# Story Deferred-13: Admin Moderation Action Integrity & Dead Refund-Path Removal

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a platform operator,
I want admin review-blocking to be idempotent and race-safe the same way approving already is, the coach-self-flag guard to fail closed instead of silently skipping, the unwired unauthenticated admin-refund stub deleted, and the admin enforcement list to stop firing one query per coach,
so that a double-click on "Block" cannot forge a second moderation-audit row or double-fire a rating recompute, no future caller can wire a refund path that skips auth and amount validation, and the admin console's enforcement list scales past a handful of coaches.

### Why this story exists

Four items in `deferred-work.md`, all in the admin/moderation surface, all backend-only, all with **zero unshipped dependencies**. Every one was re-verified against the current source on 2026-08-04 during story creation — file/line references below come from direct reads, not from trusting the file.

| # | Source item | Verified current state (2026-08-04) |
|---|---|---|
| AC1 | `skillars-deferred-12` review D3 (2026-08-04) | `AdminReviewService.blockReview()` (`AdminReviewService.java:110-137`) still uses a plain unlocked `findById` and has **no already-`BLOCKED` guard** — the exact two defects `deferred-12` AC1 closed for `approveReview()` (`:72-107`). A double-block writes two `review_moderation_log` rows and publishes two `ReviewModerationResolvedEvent`s. Explicitly out of scope for `deferred-12` per its own Dev Notes; carried forward. |
| AC2 | `skillars-9-3` review D3 (2026-06-30) | `ReviewFlagService.flag()` (`ReviewFlagService.java:46-52`) guards coach-self-flagging with `coachProfileRepository.findById(...).ifPresent(...)`. If the profile row is absent the lambda never runs and the guard is **silently bypassed** — a fail-open security check. |
| AC3 | `skillars-7-3` review D2 (2026-06-25), audited 2026-08-04 | `CancellationRefundService.processAdminRefund()` (`CancellationRefundService.java:138-143`) writes a `BOOKING_REFUND` ledger entry with **no auth check, no amount validation, no idempotency guard, no audit log**. Audit note says "admin endpoint wired in Story 10.x". Epic 10 shipped and wired admin refunds through `DisputeService.resolveDispute()` instead. `processAdminRefund` has **zero call sites in `src/main` and `src/test`** — verified by grep. |
| AC4 | `skillars-10-2` review D3 (2026-06-30) | `AdminCoachEnforcementService.getCoachesUnderEnforcement()` (`:247-268`) fires `strikeRepository.countByCoachIdAndCreatedAtAfter(...)` once per coach per page (`:264`). The batch alternative **already exists** — `CoachReliabilityStrikeRepository.countByCoachIdInAndCreatedAtAfter(...)` (`:13-19`) — and is already used by `CoachSearchService.loadReliabilityStrikes()` (`:161-168`). No new query, no migration. |

### Items examined and deliberately NOT included

Recording these so the next audit does not re-litigate them:

- **`skillars-9-3` D1 and D2 are already closed** by `deferred-12` (AC1 and AC2). `deferred-work.md:77-78` still annotates both as `STILL OPEN` — the file is **stale**, not the code. `ReviewApiAdvice.java:26-28` now carries `assignableTypes = AdminReviewResource.class` alongside `basePackages`, and `approveReview()` has both the pessimistic read and the `ALREADY_APPROVED` guard. Verified by direct read. Do not re-implement.
- **The D1 under `## Deferred from: code review of skillars-10-1-admin-moderation-queue-message-content-actions (2026-06-30)`** ("`buildSummary()` returns null summary when `message.content` is null") is **obsolete**: `V65__messaging_module_init.sql:22` declares `content TEXT NOT NULL`, so the state is unreachable. `AdminQueueService.buildSummary()` also already yields `"[message not found]"` rather than `null` in that branch, because `Optional.map` collapses a null mapper result to empty. Delete the item, do not code against it. **Do not confuse this with the separate `## Deferred from: code review of skillars-10-1 patches (2026-06-30)` heading**, which has its own unrelated D1 (`findBeforePivot`/`findAfterPivot` return empty context when pivot `createdAt` is null) and D2 — both still open, both untouched by this story.
- **`skillars-3-11` D2** ("no DB-level exclusion constraint") was already closed by `V87__booking_overlap_exclusion_constraint.sql`; `deferred-12` recorded this. Still stale in `deferred-work.md`.
- **`skillars-10-2` D1** (`AFTER_COMMIT` listener failure silently drops refunds) is explicitly a platform-wide event-reliability concern shared by every `@TransactionalEventListener` in the codebase. Too large for this story; leave in the file.

## Acceptance Criteria

1. **`blockReview()` is race-safe and idempotent.** `AdminReviewService.blockReview()` reads the review with `reviewRepository.findByIdForUpdate(reviewId)` instead of `findById(reviewId)`, and throws `OperationNotAllowedException(ReviewErrorCode.ALREADY_BLOCKED)` when the review is already `BLOCKED`. `POST /api/admin/reviews/{id}/block` answers `409 CONFLICT` with `"errorKey":"reviews.alreadyBlocked"` on the second call. Exactly one `review_moderation_log` row with `action = 'BLOCKED'` exists after a repeated call, sequential **and** concurrent. `ResourceNotFoundException` on a missing review is unchanged (still `404`, `errorKey = RESOURCE_NOT_FOUND`).

2. **The `ALREADY_BLOCKED` code maps to 409.** `ReviewErrorCode` gains `ALREADY_BLOCKED("reviews.alreadyBlocked")`, and `ReviewApiAdvice.handleOperationNotAllowed` adds it to the `HttpStatus.CONFLICT` branch (`ReviewApiAdvice.java:38-42`) so it does not fall through to the `else` → `403` default.

3. **The existing block-path behaviour is preserved.** A first block of an `UNDER_REVIEW` or `APPROVED` review still sets `BLOCKED`, clears `heldReason`, resolves all open flags, writes exactly one log row carrying `reason`, and publishes exactly one `ReviewModerationResolvedEvent` with the correct `previousStatus`. `coachRatingService.recompute()` still fires **only** when `previousStatus == APPROVED` — a blocked-then-blocked sequence must not recompute twice.

4. **The coach-self-flag guard fails closed.** `ReviewFlagService.flag()` resolves the reviewed coach's profile with `orElseThrow` semantics: when no `coach_profiles` row exists for `review.getCoachId()`, the call throws `OperationNotAllowedException(ReviewErrorCode.COACH_PROFILE_MISSING)` instead of skipping the guard. ~~`POST /api/reviews/{id}/flag` answers `409 CONFLICT`~~ **AMENDED by code review 2026-08-05: answers `500 INTERNAL_SERVER_ERROR`** with `"errorKey":"reviews.coachProfileMissing"`. `ReviewErrorCode` gains `COACH_PROFILE_MISSING("reviews.coachProfileMissing")`, added to ~~`ReviewApiAdvice`'s `CONFLICT` branch~~ **its own explicit `INTERNAL_SERVER_ERROR` branch in `ReviewApiAdvice`** (explicit because the advice's `else` fallback is 403, so an unlisted code silently becomes `FORBIDDEN`). Rationale for the override: an orphaned `coach_profiles` row is a data-integrity failure the caller neither caused nor can retry away — unlike `ALREADY_BLOCKED`, which is a genuine conflict and stays 409. As a 4xx it would sit in the "user error" bucket where 5xx-keyed alerting never sees it. An ERROR-level log accompanies the throw. All existing flag outcomes (`201` success, `403 cannotFlagOwnReview`, `403 cannotFlagOwnCoachedReview`, `409 alreadyFlagged`, auto-hold at threshold) are unchanged.

5. **The dead admin-refund stub is deleted.** `CancellationRefundService.processAdminRefund(UUID, BigDecimal, Long)` is removed entirely. `BigDecimal` and any other imports left unused by the removal are dropped. The project compiles and the full test suite passes with no reference to it anywhere.

6. **The enforcement list issues one strike query per page.** `AdminCoachEnforcementService.getCoachesUnderEnforcement()` collects the page's coach ids and resolves strike counts with a single `strikeRepository.countByCoachIdInAndCreatedAtAfter(ids, since)` call, mirroring `CoachSearchService.loadReliabilityStrikes()`. A coach absent from the grouped result reports `0`, not an error. An **empty page** must not issue the `IN` query at all. Response payload, ordering (`statusChangedAt` ascending), page size (20), and the 30-day strike window are byte-for-byte unchanged. The two single-coach call sites (`AdminCoachEnforcementService.java:74` and `:212`) keep using `countByCoachIdAndCreatedAtAfter` and are **not** touched.

7. **`deferred-work.md` reflects reality.** The items this story closes are deleted from `_bmad-output/implementation-artifacts/deferred-work.md`, along with the four stale entries identified above that are already closed or obsolete. Specifically removed: `skillars-deferred-12` D3; `skillars-9-3` D1, D2 and D3; `skillars-7-3` D2; `skillars-10-2` D3; **the D1 under the `## Deferred from: code review of skillars-10-1-admin-moderation-queue-message-content-actions (2026-06-30)` heading** (the `buildSummary()` null-summary item); `skillars-3-11` D2. Nothing else is deleted. **`deferred-work.md` has a second, differently-headed section — `## Deferred from: code review of skillars-10-1 patches (2026-06-30)` — with its own, unrelated D1 (`findBeforePivot`/`findAfterPivot` return empty context when pivot `createdAt` is null) and D2. Both entries under that separate `skillars-10-1 patches` heading are still-open and explicitly NOT deleted by this story — do not match on the bare string `skillars-10-1 D1`, which is ambiguous between the two headings.** The `## Last audit` block is updated with a 2026-08-04 entry naming this story and stating what was and was not re-verified.

## Tasks / Subtasks

- [x] **Task 1 — Error codes and advice mapping (AC: 2, 4)**
  - [x] Add `ALREADY_BLOCKED("reviews.alreadyBlocked")` and `COACH_PROFILE_MISSING("reviews.coachProfileMissing")` to `ReviewErrorCode`.
  - [x] Add both to the `CONFLICT` branch condition in `ReviewApiAdvice.handleOperationNotAllowed` (`ReviewApiAdvice.java:38-42`), alongside the existing `ALREADY_SUBMITTED` / `RESPONSE_ALREADY_SUBMITTED` / `ALREADY_FLAGGED` / `ALREADY_APPROVED`.
  - [x] No i18n work: grep confirms no `reviews.*` error keys exist in any `src/frontend/src/i18n/*` locale and there is no admin-review frontend page. Do not add locale entries.

- [x] **Task 2 — `blockReview()` lock + guard (AC: 1, 3)**
  - [x] In `AdminReviewService.blockReview()` (`:110-137`) swap `reviewRepository.findById(reviewId)` for `reviewRepository.findByIdForUpdate(reviewId)`. Keep the `ResourceNotFoundException("Review not found", "coach_review")` `orElseThrow`.
  - [x] After `previousStatus` is read (`:113`), add: `if (previousStatus == ReviewModerationStatus.BLOCKED) throw new OperationNotAllowedException("Review already blocked", ReviewErrorCode.ALREADY_BLOCKED);`
  - [x] Leave the `if (previousStatus == APPROVED) coachRatingService.recompute(...)` conditional (`:122-124`) exactly as-is.
  - [x] Mirror the explanatory comment style already on `approveReview()` (`:74-80`) — state that this is the **first** read of the row in the method, so the locked query returns fresh state.

- [x] **Task 3 — `blockReview()` tests (AC: 1, 3)**
  - [x] In `AdminReviewQueueIT`, add `blockReview_calledTwice_secondReturns409AndWritesNoDuplicateLog()`, modelled on `approveReview_calledTwice_secondReturns409AndWritesNoDuplicateLog()` (`:302-325`). Assert `409`, `"errorKey":"reviews.alreadyBlocked"`, and `SELECT COUNT(*) FROM reviews.review_moderation_log WHERE review_id = ? AND action = 'BLOCKED'` equals 1.
  - [x] Add `blockReview_concurrentDoubleClick_onlyOneSucceedsAndOneLogRowIsWritten()`, modelled on the approve equivalent (`:334-376`): `CyclicBarrier(2)`, two-thread `ExecutorService`, assert exactly one `200` and one `409`, and exactly one `'BLOCKED'` log row. **This is the test that actually proves the pessimistic read** — the sequential test above passes against a plain `findById`.
  - [x] Verify `blockReview_setsBlockedAndResolvesFlags()` (`:261`) and `blockReview_blankReason_returns400WithReviewShapedValidationError()` (`:385`) still pass unchanged.

- [x] **Task 4 — `ReviewFlagService` fail-closed guard (AC: 4)**
  - [x] Replace the `.ifPresent(profile -> {...})` block at `ReviewFlagService.java:46-52` with a resolve-then-check: fetch the profile, throw `OperationNotAllowedException("Coach profile not found for review", ReviewErrorCode.COACH_PROFILE_MISSING)` if absent, then apply the existing `flaggedBy.equals(profile.getUserId())` self-flag check unchanged.
  - [x] Keep the guard in its current position — **after** the author-self-flag check (`:40-44`) and **before** the duplicate-flag check (`:54-58`). Reordering changes which error a caller sees.

- [x] **Task 5 — `ReviewFlagService` test (AC: 4)**
  - [x] In `ReviewFlagIT`, add `flagReviewWithMissingCoachProfile_returns409WithCoachProfileMissing()`. Seed a `reviews.coach_reviews` row whose `coach_id` is a random UUID with no `coach_profiles` row — `V67__reviews_module_init.sql:3-16` has **no FK on `coach_id`**, so this is directly insertable via `jdbcTemplate`. Assert `409` and `"errorKey":"reviews.coachProfileMissing"`. Clean the row up in `tearDown` alongside the existing fixtures.
  - [x] Note in the test's javadoc that this state is **not reachable in production today** — `GdprErasureService` (`:96-102`) *anonymises* `coach_profiles` and never deletes the row, and no other code path deletes one. The change converts a fail-open security guard into a fail-closed one; it is defence-in-depth, not a live bug fix. Do not claim otherwise in the Completion Notes.

- [x] **Task 6 — Delete `processAdminRefund` (AC: 5)**
  - [x] Re-confirm zero call sites before deleting: `grep -rn "processAdminRefund" src/`. If any appear, **stop and report** rather than deleting.
  - [x] Delete the method at `CancellationRefundService.java:137-143`. The `java.math.BigDecimal` import at `:18` is used **only** by this method (verified: two occurrences in the file, the import and the signature) — it must be removed too or the build warns.
  - [x] Do not touch `DisputeService.resolveDispute()`, which is the real admin refund path and already has `@PreAuthorize(HAS_ADMIN_ROLE)` on `AdminDisputeResource`, amount bounds validation (`INVALID_CREDIT_AMOUNT` against `sessionPrice`), an already-resolved `409` guard, and an `AdminActionLog` entry.

- [x] **Task 7 — Batch the enforcement strike counts (AC: 6)**
  - [x] In `getCoachesUnderEnforcement()` (`:247-268`), after `Page<CoachProfile> coaches = ...`, build `List<UUID> ids = coaches.getContent().stream().map(CoachProfile::getId).toList()`.
  - [x] Guard the empty case: if `ids.isEmpty()`, use an empty map and skip the query — an `IN ()` with an empty collection is a Hibernate hazard.
  - [x] Otherwise build `Map<UUID, Long>` from `strikeRepository.countByCoachIdInAndCreatedAtAfter(ids, since)`, collecting `row -> (UUID) row[0]` / `row -> (Long) row[1]`. Copy the cast shape from `CoachSearchService.loadReliabilityStrikes()` (`:161-168`) — note that method narrows to `Integer`; here the DTO field is `long`, so **keep it as `Long`** and do not narrow.
  - [x] In the `coaches.map(...)` lambda, replace the per-coach count with `strikeCounts.getOrDefault(coach.getId(), 0L)`. Coaches with no strikes are absent from a `GROUP BY` result — the default is load-bearing.
  - [x] Leave `since = OffsetDateTime.now().minusDays(30)` where it is; it must be computed once, before the query.

- [x] **Task 8 — Enforcement list test (AC: 6)**
  - [x] Extend `CoachEnforcementListIT.listEnforcementCoaches_returnsOrderedByStatusChangedAt()` (`:129`) or add a sibling test asserting that a coach with **zero** strikes reports `activeStrikes = 0` and a coach with N in-window strikes reports N, in the same page. This pins the `getOrDefault` default and the batch-mapping correctness; the existing test alone would pass against a map that silently drops every row.
  - [x] Confirm `getEnforcementProfile_returnsAllFields()` (`:162`) still passes — it exercises the untouched single-coach path at `:74`.

- [x] **Task 9 — Full verification (AC: all)**
  - [x] `mvn -o verify` (unit + IT). Full green required — this story touches shared moderation and refund code, so a partial run is not sufficient evidence.
  - [x] No frontend build needed: all changes are backend, and grep confirms there is no admin-review or enforcement-list frontend consuming these codes.

- [x] **Task 10 — Update `deferred-work.md` (AC: 7)**
  - [x] Delete the eight entries named in AC7. Leave every other entry, including the ones this story explicitly declined (`skillars-10-2` D1).
  - [x] **Disambiguation gotcha:** `deferred-work.md` has two separate section headings that both start with `skillars-10-1`, both dated 2026-06-30 — `## Deferred from: code review of skillars-10-1 patches` and `## Deferred from: code review of skillars-10-1-admin-moderation-queue-message-content-actions`, each with its own D1 (and the former also has a D2). Only the D1 under the **second** heading (the `buildSummary()` null-summary item) is deleted here. The D1 and D2 under `skillars-10-1 patches` are unrelated, still-open items and must be left untouched. Confirm by matching the item text, not just the section prefix, before deleting.
  - [x] Add a `## Last audit: 2026-08-04 (deferred-13)` note under the existing audit block recording: which items were closed by code here, which four were deleted as already-closed-or-obsolete with the evidence (`ReviewApiAdvice` `assignableTypes`, `approveReview` guard, `V87`, `content TEXT NOT NULL`), and that the deployment (`deploy-*`) sections were again **not** re-checked.

### Review Findings

_Code review 2026-08-05 — three adversarial layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor), all completed. 20 unified findings after merging; 7 dismissed as false positives. No functional defect was found in `src/main` — all seven ACs are correctly implemented in production code. Every finding below is a test-strength gap, a documentation-deliverable defect, or a pre-existing adjacent issue._

- [x] [Review][Patch] **(Decision resolved 2026-08-05 → option 2: strengthen the test.)** Add a test that asserts something only the row lock can produce — the test holds a `PESSIMISTIC_WRITE` lock on the review row in its own transaction, fires `POST /block` on another thread, asserts the request **does not complete** while the lock is held (a `TimeoutException` from `future.get(short)`), then releases and asserts it completes. Against a plain `findById` the request sails past and completes immediately, so this fails closed. No `Thread.sleep`. The existing barrier-based test is kept as a cheap regression net, with its javadoc corrected to stop claiming it proves the lock.

  **Applied, and the first attempt was itself wrong — worth carrying forward.** The obvious design (hold `SELECT … FOR UPDATE`, assert the request times out) *passed against the mutation*, because a plain `findById` still blocks later at the `UPDATE` — the request waits either way, so waiting is not the discriminator. What separates them is **what the request observes once unblocked**. The shipped test therefore has the concurrent transaction *block the review and commit* while the request is in flight: a locked read waits and then sees fresh `BLOCKED` → 409; a plain `findById` reads the pre-commit snapshot, sees stale `UNDER_REVIEW`, passes the guard, and writes a duplicate audit row → 200. Verified by mutation in both directions: `blockReview_whenAConcurrentBlockCommitsFirst_readsFreshStateAndRefuses` fails on `findById` and passes on `findByIdForUpdate`. **Lesson for future lock ACs: "the request blocked" proves nothing about *where* it blocked — assert on observed state, and always run the mutation.** Original finding: `blockReview_concurrentDoubleClick_onlyOneSucceedsAndOneLogRowIsWritten` synchronises the two threads with a `CyclicBarrier` *before the HTTP call*; everything after it (TCP connect, auth filter, dispatch, tx begin) is unsynchronised and easily wider than the read-to-commit window under test. If thread A commits before thread B issues its `SELECT`, a plain `findById` also observes `BLOCKED` and returns 409 — the test passes green against the exact mutation it exists to catch. Flagged independently by the Blind Hunter and the Edge Case Hunter. The story's Task 3 asserts "this is the test that actually proves the pessimistic read"; that claim is stronger than what the code establishes — the barrier makes the overlap *likely*, not guaranteed. Note this weakness is inherited from `approveReview_concurrentDoubleClick_...` (shipped and reviewed under `deferred-12`), so accepting it is pattern-consistent. Options: (a) accept as-is and soften the Task 3 / javadoc claim, (b) assert something only the lock can produce, (c) accept and log as a codebase-wide test-strength item covering both tests. [`src/test/java/com/softropic/skillars/platform/admin/api/AdminReviewQueueIT.java:471-514`]
- [x] [Review][Patch] **(Decision resolved 2026-08-05 → option 2: map to 500 so it pages.)** This is a deliberate, user-approved deviation from AC4, which specifies 409 + `reviews.coachProfileMissing`. `COACH_PROFILE_MISSING` moves out of the `CONFLICT` branch into an explicit `INTERNAL_SERVER_ERROR` branch (it must be explicit — the advice's `else` fallback is 403, so an unlisted code silently becomes `FORBIDDEN`). `ReviewFlagIT`'s expectation changes from 409 to 500, and AC4's text is amended to record the override. `ALREADY_BLOCKED` stays 409 — it is a genuine conflict. Original finding: AC4 specifies 409 + `reviews.coachProfileMissing` and the code implements it exactly, so this is a challenge to the spec, not to the implementation. `ALREADY_BLOCKED` is a true conflict the caller can resolve by not retrying; `COACH_PROFILE_MISSING` is an orphaned row — the user did nothing wrong and no retry will ever succeed. The story's own test javadoc states this state is unreachable in production today, which means if it ever fires it is a data-integrity incident. As a 4xx it lands in the "user error" bucket and alerting keyed on 5xx rates will never surface it. The two codes share an `if` branch because both are new, not because they are the same kind of failure. Options: (a) keep 409 per AC4, (b) map to 500 so it pages, (c) keep 409 but add an ERROR-level log at the throw site. [`src/main/java/com/softropic/skillars/platform/reviews/api/ReviewApiAdvice.java:38-44`]
- [x] [Review][Patch] Enforcement-list test pins two output values, not the batching, the 30-day window, or any count > 1 — the fixture inserts exactly one in-window strike for coach 1 and none for coach 2, so dropping the `AND s.createdAt > :since` predicate, changing the window, or collapsing every non-empty group to `1L` all survive; reverting to the per-coach N+1 also passes unchanged. Only `getOrDefault(id, 0L)` and the `(UUID) row[0]` / `(Long) row[1]` cast order are genuinely pinned. Raised by Blind Hunter and Edge Case Hunter independently. [`src/test/java/com/softropic/skillars/platform/admin/api/CoachEnforcementListIT.java:159-162`, fixture `:105-107`]
- [x] [Review][Patch] Change Log and `sprint-status.yaml` both say "all 6 ACs" for a story that defines seven — all seven were implemented, but the count is wrong in the two artifacts a future audit reads to decide whether AC7 (the `deferred-work.md` hygiene AC) was done. [`skillars-deferred-13-admin-moderation-action-integrity.md:214`, `sprint-status.yaml:307`]
- [x] [Review][Patch] The new `deferred-work.md` audit block opens "Ran alongside code review closing `skillars-deferred-13`" — no code review had run when that line was written; the block was authored by the dev agent. The file's own reading rules state that audit annotations "are the only re-verified claims in this file", so the block inherits a trust level it had not earned. [`_bmad-output/implementation-artifacts/deferred-work.md:51`]
- [x] [Review][Patch] Dangling cross-reference left behind by the AC7 cleanup — the "Known tracking defect" note still reads "`ReviewApiAdvice` still does not cover `platform.admin.api`. The item is retained below under the `skillars-9-3` section." Both halves are now false: `ReviewApiAdvice.java:26-28` does carry `assignableTypes`, and the `skillars-9-3` section was deleted by this story. The added "Resolved 2026-08-04" paragraph explains the removal but the original false sentence was left standing above it — the same class of staleness this story exists to clean up. [`_bmad-output/implementation-artifacts/deferred-work.md:41-43`]
- [x] [Review][Patch] `deferred-work.md` now has two `## Last audit: 2026-08-04` H2 headings (`:20` and `:49`) — same level, same date, same prefix. This recreates precisely the ambiguity trap AC7 and Task 10 each spend a paragraph warning about for the two `skillars-10-1` headings; a future `grep '## Last audit: 2026-08-04'` matches both. AC7 said "the `## Last audit` block **is updated**"; Task 10 said add a note "under the existing audit block" — the task won. [`_bmad-output/implementation-artifacts/deferred-work.md:20,49`]
- [x] [Review][Patch] `executor.shutdown()` is not in a `finally` — if either `get(30, TimeUnit.SECONDS)` throws (`ExecutionException` from a non-409 error, or `TimeoutException`), shutdown is skipped, the non-daemon pool leaks, and a still-in-flight request can `INSERT` into `review_moderation_log` *after* `@AfterEach` has already deleted the fixture rows, leaving an orphan row keyed to a vanished review. Assertion failures are safe (shutdown precedes them); only the `get()` failure paths leak. [`src/test/java/com/softropic/skillars/platform/admin/api/AdminReviewQueueIT.java:498-503`]
- [x] [Review][Patch] AC1's closing clause — "`ResourceNotFoundException` on a missing review is unchanged (still `404`, `errorKey = RESOURCE_NOT_FOUND`)" — has no test on the block path; the only 404-shape test hits `/approve`. The behaviour was verified correct by reading (`AdminReviewService.java:113-114` keeps the `orElseThrow`, and `ReviewApiAdvice` declares no `ResourceNotFoundException` handler so it falls through to the global advice identically for both endpoints), but the AC outran its tasks — Task 3 never asked for the test. [`src/test/java/com/softropic/skillars/platform/admin/api/AdminReviewQueueIT.java` (near `:284`)]
- [x] [Review][Patch] AC3's "writes exactly one log row **carrying `reason`**" is unasserted — both new tests count rows by `action = 'BLOCKED'` but never read the `reason` column. Related, and **not** patchable: AC3 also requires "publishes exactly one `ReviewModerationResolvedEvent` with the correct `previousStatus`", but `grep -rn "ReviewModerationResolvedEvent" src/` finds only the publisher and the record itself — **zero listeners in `src/main`, zero references in `src/test`**. The duplicate-event half of the original `deferred-12` D3 defect was therefore inert, which is worth knowing but does not change that AC1's log-row and recompute guarantees were real. [`src/test/java/com/softropic/skillars/platform/admin/api/AdminReviewQueueIT.java:437-461,471-514`]
- [x] [Review][Defer] The Gemini moderation listener can silently revert a committed admin BLOCK [`src/main/java/com/softropic/skillars/platform/reviews/service/ReviewModerationService.java:93-110`] — deferred, pre-existing. Unlocked `findById` + unconditional `setModerationStatus` in a `REQUIRES_NEW` tx after an out-of-transaction Gemini call; a `SAFE` verdict landing after an admin block overwrites `BLOCKED` → `APPROVED` and recomputes the rating, with the exception swallowed. Not touched by this diff, but it means the AC1 lock is only half the guarantee. Recorded in `deferred-work.md` as D1.
- [x] [Review][Defer] Booking exclusion-constraint violations have no 409 mapping [`V87__booking_overlap_exclusion_constraint.sql`, `BookingBatchService.createBatch`, `RescheduleService.acceptReschedule`] — deferred, pre-existing. Recorded in `deferred-work.md` as D2. Note: the Blind Hunter's premise that AC7's deletion of `skillars-3-11` D2 "erased the only written record" of the two bypass paths is **wrong** — the V87 header comment names both paths verbatim. Only the ledger entry moved.
- [x] [Review][Defer] `ReviewFlagIT` orphan fixture cleaned in an in-test `try/finally` rather than `@AfterEach` as Task 5 specifies [`src/test/java/com/softropic/skillars/platform/reviews/api/ReviewFlagIT.java:343-349`] — deferred, pre-existing convention deviation, bounded impact. Recorded in `deferred-work.md` as D3.

#### Dismissed as false positives (7)

Recorded so a future review does not re-raise them:

1. **"`countByCoachIdInAndCreatedAtAfter` is not in the diff, so this does not compile"** (Blind) — the method pre-exists in `CoachReliabilityStrikeRepository:13-19` as an explicit JPQL `GROUP BY` returning `List<Object[]>`. Blind-layer artifact: the Blind Hunter has no repo access by design, and the story correctly stated the method already existed.
2. **"`Collectors.toMap` throws on duplicate keys or null values"** (Blind) — `GROUP BY s.coachId` guarantees unique keys, `coachId` is `nullable = false`, and `COUNT` is never null. Verified in source by the Edge Case Hunter.
3. **"First-level-cache staleness defeats the `findByIdForUpdate` guard"** (Blind) — `blockReview()`'s only caller is `AdminReviewResource:63` (a controller), and `spring.jpa.open-in-view: false` (`application.yaml:48`) means no request-scoped persistence context can have cached the entity earlier. The comment's claim is accurate today and truthfully scoped.
4. **"Fail-closed reordering changes the error for an already-flagged orphan review, and callers are unaudited"** (Blind) — the guard position is AC4-mandated and the story documents the trade-off explicitly; `flag()`'s only caller is `ReviewResource:113`; and the state is unreachable in production (`GdprErasureService:97-102` anonymises `coach_profiles`, never deletes).
5. **"`processAdminRefund` deleted on an unverifiable zero-call-sites claim"** (Blind) — independently verified by two layers with repo access across `src/main`, `src/test`, `src/main/resources` (migrations, config, SpEL strings). Only prose hits in `_bmad-output/*.md` remain.
6. **"Deleting `skillars-10-1` D1 rests on a schema invariant a future migration could drop"** (Blind) — the deletion was AC7-mandated and `V65__messaging_module_init.sql:22` (`content TEXT NOT NULL`) holds. The Blind Hunter is right that the second justification clause (`Optional.map` collapse) answers the message-absent case rather than the content-null case, but the primary evidence is sound.
7. **"`sprint-status.yaml` uses the bare ambiguous string `10-1 D1`"** (Auditor) — the parenthetical (`unreachable — messages.content is TEXT NOT NULL`) disambiguates by item text, which is exactly what Task 10 asked for.

## Dev Notes

### Architecture and conventions this story must follow

- **Module layout** — `platform.{module}.{api|service|repo|contract|config}`. `AdminReviewService` and `AdminCoachEnforcementService` live in `platform.admin.service`; `ReviewFlagService` and `ReviewErrorCode`/`ReviewApiAdvice` in `platform.reviews.{service,contract,api}`; `CancellationRefundService` in `platform.payment.service`. Nothing moves. [Source: `_bmad-output/project-context.md` — Module Internal Structure]
- **Exception handling** — errors flow through `@RestControllerAdvice`; never catch generic `Exception`. Both new codes go through `ReviewApiAdvice`, which already covers `AdminReviewResource` via `assignableTypes` (added by `deferred-12`). [Source: `_bmad-output/project-context.md` — Code Quality Rules]
- **Testing** — `@SpringBootTest` + `@Testcontainers` for ITs, never a mocked DB; AssertJ `assertThat` for assertions. The admin/reviews ITs seed fixtures with raw `jdbcTemplate` rather than Instancio, because FK-constrained integration seeding needs fixed ids — follow the surrounding file's existing pattern, not the project-wide Instancio default. [Source: `_bmad-output/project-context.md` — Testing Rules; `AdminReviewQueueIT`, `ReviewFlagIT` as written]
- **Backend-only** — no Flyway migration, no new repository method, no DTO change, no frontend change. If you find yourself writing a migration, you have gone outside this story.

### Files being modified — current state and what must survive

**`src/main/java/com/softropic/skillars/platform/admin/service/AdminReviewService.java`**
- `approveReview()` (`:72-107`) is the **reference implementation** for AC1: locked read via `findByIdForUpdate`, `previousStatus` capture, already-in-target-state guard throwing `OperationNotAllowedException`, then mutate → `resolveAllOpenFlags` → `recompute` → log row → event. Make `blockReview()` structurally parallel; do not refactor them into a shared private method — the two differ in flag-resolution ordering, recompute conditionality, and the `reason` field, and a premature merge would obscure that.
- `blockReview()`'s recompute is conditional on `previousStatus == APPROVED` (`:122-124`) because blocking an `UNDER_REVIEW` review changes no published rating. `approveReview()`'s is unconditional. This asymmetry is intentional — preserve it.
- `getUnderReviewQueue()` (`:42-71`) is untouched.

**`src/main/java/com/softropic/skillars/platform/reviews/repo/CoachReviewRepository.java`**
- `findByIdForUpdate` already exists (`:23-25`) as `@Lock(PESSIMISTIC_WRITE)` + `@Query("SELECT r FROM CoachReview r WHERE r.reviewId = :reviewId")`. **Do not add a second locking method.**

**`src/main/java/com/softropic/skillars/platform/reviews/api/ReviewApiAdvice.java`**
- The `else` fallback is `403 FORBIDDEN` (`:47-49`). A new code that is not explicitly listed silently becomes a 403 — this is exactly why AC2 and AC4 both require the `CONFLICT` branch edit. Verify with the test, not by reading.
- The class javadoc comment (`:20-26`) explains that `assignableTypes` routes **every** exception from `AdminReviewResource` here, including bean validation. Keep that comment accurate if you touch the annotation — you should not need to.

**`src/main/java/com/softropic/skillars/platform/reviews/service/ReviewFlagService.java`**
- Guard order in `flag()` is contractual: review-exists → author-self → coach-self → duplicate-flag → insert (with a `DataIntegrityViolationException` catch mapping the unique-index race to `ALREADY_FLAGGED`) → threshold auto-hold. `ReviewFlagIT` pins each outcome. Changing the order changes which code a caller sees for an input that trips two guards.
- The class is `@Transactional` at type level; the new throw rolls back cleanly with no partial write, since it precedes the insert.

**`src/main/java/com/softropic/skillars/platform/payment/service/CancellationRefundService.java`**
- The class's other members are `@TransactionalEventListener(AFTER_COMMIT)` + `REQUIRES_NEW` handlers (`onBookingCancelledByAdmin`, `onCoachNoShow`, `onPlayerNoShow`, …) plus a private `saveCancellationHistory`. `processAdminRefund` is the only plain `@Transactional` public method and the only one with no caller. Deleting it does not affect any listener.

**`src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java`**
- Three call sites of `countByCoachIdAndCreatedAtAfter`: `:74` (single-coach profile detail), `:212` (post-strike-deletion recount), `:264` (the N+1 in the page loop). **Only `:264` changes.**
- `since` at `:262` is `OffsetDateTime.now().minusDays(30)` — the batch query takes the same `OffsetDateTime`, so no type conversion is needed.
- The DTO is `CoachEnforcementListItemDto(UUID coachId, String coachName, String status, long activeStrikes, Instant statusChangedAt)` — the strike field is a primitive `long`. `getOrDefault(id, 0L)` unboxes cleanly; `getOrDefault(id, 0)` would not compile against a `Map<UUID, Long>`. Note the DTO's `statusChangedAt` is an `Instant` while the strike window uses `OffsetDateTime` — that mismatch is pre-existing and out of scope.

**`src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachReliabilityStrikeRepository.java`**
- `countByCoachIdInAndCreatedAtAfter` (`:13-19`) returns `List<Object[]>` from an explicit JPQL `GROUP BY`, **not** a projection interface. Rows are `[UUID coachId, Long count]`. Coaches with zero in-window strikes produce **no row**. Read-only — do not modify this file.

### Prior-story intelligence (`skillars-deferred-12`, shipped 2026-08-04, commit `0fca473`)

Directly load-bearing here — `deferred-12` did AC1 of this exact shape on `approveReview()` and its review found three things worth carrying:

1. **A mocked-repository unit test would have hidden the bug.** `deferred-12`'s AC3 fix was originally a no-op because Hibernate returns the already-managed (stale) entity from `findByIdForUpdate` when an earlier `findById` in the same transaction made it managed; the mocked-repo test could not see it. `blockReview()` is safe from that specific trap — the locked read is the method's **first** touch of the row — but the lesson stands: **prove AC1 with the concurrent IT, not a unit test.**
2. **The sequential double-call test is not sufficient evidence of the lock.** `deferred-12`'s code review added `approveReview_concurrentDoubleClick_...` precisely because the sequential test passes unchanged against a plain `findById`. Task 3's second test is not optional.
3. **`deferred-work.md` promises are unreliable; the code is the source of truth.** `deferred-12` found 12 of 13 audited forward-references never actually fixed, and found `deferred-work.md` stale in the opposite direction too (`3-11` D2 already fixed). This story's own audit found two more stale-in-the-opposite-direction entries (`9-3` D1/D2). Re-grep before implementing any AC.

### Git intelligence

Recent commits are all story-scoped squashes (`Story Deferred-12: …`, `Story Deferred-11: …`, `Story 11.3: …`). Established patterns visible in `0fca473`:
- New error-code constants land in the module's `contract` enum plus the module's `@RestControllerAdvice` status mapping in the same change.
- Concurrency guarantees are pinned by an IT using `CyclicBarrier` + a two-thread `ExecutorService` with `AtomicInteger` outcome counters, asserting a DB row count afterwards — copy that shape verbatim.
- Story completion is gated on a full `mvn -o verify`, and the sprint-status note records exact test counts.

### Testing standards summary

- ITs: `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@Testcontainers`, real Postgres, `httpTestClient.makeHttpRequest(...)`, `assertThatThrownBy(...).isInstanceOf(HttpClientErrorException.class).satisfies(...)` for error responses — assert on **both** status and `errorKey` in the body.
- Every new fixture row must be removed in `@AfterEach`; the reviews and admin ITs share `SecurityIT` seed data, so leaking rows breaks unrelated tests.
- Async: none in this story. Do not introduce `Thread.sleep`.

### Project Structure Notes

No new files in `src/main`. Two new test methods in `AdminReviewQueueIT`, one in `ReviewFlagIT`, one added or extended in `CoachEnforcementListIT`. All modified paths sit inside their existing modules; no cross-module dependency is added (`platform.admin` already depends on `platform.reviews.contract`, and `platform.reviews.api` already references `platform.admin.api.AdminReviewResource` fully-qualified in `ReviewApiAdvice` for exactly this reason).

No variances from the unified structure detected.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — "Deferred from: code review of skillars-deferred-12-booking-payment-review-integrity (2026-08-04)", item D3]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — "Deferred from: code review of skillars-9-3-review-visibility-flagging-admin-resolution (2026-06-30)", item D3 (D1/D2 confirmed already closed — see "Items examined and deliberately NOT included")]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — "Deferred from: code review of skillars-7-3-cancellation-refund-reliability-strikes (2026-06-25)", item D2]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — "Deferred from: code review of skillars-10-2-coach-enforcement-strike-management (2026-06-30)", item D3]
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-12-booking-payment-review-integrity.md` — AC1/AC2 and Dev Notes, for the approve-path reference implementation and the mocked-repo/sequential-test pitfalls]
- [Source: `_bmad-output/project-context.md` — Technology Stack, Framework Rules, Testing Rules, Module Internal Structure]
- Current-code verification performed directly against the repository on 2026-08-04 for every item above. Every file/line reference in this story came from a direct read on that date — do not trust `deferred-work.md`'s own line numbers, which are older.

## Dev Agent Record

### Agent Model Used

claude-sonnet-5

### Debug Log References

None — all ACs implemented and verified green on the first pass; no failing test required a debugging detour.

### Completion Notes List

- AC1/AC3: `blockReview()` made structurally parallel to `approveReview()` — locked read via `findByIdForUpdate` (this is the method's first touch of the row, so no stale-managed-entity risk existed here), `ALREADY_BLOCKED` guard, existing mutate/resolve-flags/conditional-recompute/log/event sequence otherwise untouched. Per prior-story intelligence, the sequential double-call test alone would pass against a plain `findById`; the concurrent `CyclicBarrier` IT is what actually proves the lock, and both were added.
- AC2/AC4: `ALREADY_BLOCKED` and `COACH_PROFILE_MISSING` added to `ReviewErrorCode` and to `ReviewApiAdvice`'s `CONFLICT` branch. No i18n keys added — confirmed no `reviews.*` locale entries exist and there is no admin-review frontend page.
- AC4: `ReviewFlagService.flag()`'s coach-profile lookup changed from `.ifPresent(...)` (silently skips the self-flag guard when the profile is absent) to `.orElseThrow(...)`, converting a fail-open check to fail-closed. Confirmed via direct read and the story's own note that this state is not reachable in production today (`GdprErasureService` anonymises `coach_profiles` rows, never deletes them) — this is defence-in-depth, not a live bug fix, and the test javadoc says so.
- AC5: `processAdminRefund` deleted from `CancellationRefundService` after re-confirming zero call sites via `grep -rn "processAdminRefund" src/` (only the definition itself matched). The now-unused `java.math.BigDecimal` import was removed in the same change.
- AC6: `getCoachesUnderEnforcement()` now collects the page's coach ids and issues one `countByCoachIdInAndCreatedAtAfter` call instead of one query per coach, mirroring `CoachSearchService.loadReliabilityStrikes()`'s cast shape (kept as `Long`, not narrowed to `Integer`, since the DTO field is a primitive `long`). Empty pages skip the query entirely. The two single-coach call sites (`:74`, `:212`) were left untouched.
- AC7: Deleted the eight `deferred-work.md` entries named in the story — `skillars-deferred-12` D3, `skillars-9-3` D1/D2/D3, `skillars-7-3` D2, `skillars-10-2` D3, the `buildSummary()` null-summary D1 under the `skillars-10-1-admin-moderation-queue-message-content-actions` heading specifically (not the unrelated D1/D2 under the separately-headed `skillars-10-1 patches` section, which are untouched), and `skillars-3-11` D2. Added a `## Last audit: 2026-08-04 (deferred-13)` note recording what was closed by code vs. deleted as already-closed/obsolete, and updated the stale "Known tracking defect" callout at the top of the file (which referenced the now-deleted `skillars-9-3` D2) to point at its resolution.
- Full `mvn -o verify`: 807 unit tests + 842 IT tests, 0 failures, 0 errors (5 pre-existing skips, unrelated to this story). No frontend build was needed — confirmed no admin-review or enforcement-list frontend consumes these codes.

**Code review addendum (2026-08-05).** Three adversarial layers found **no functional defect in `src/main`** — all seven ACs were correctly implemented. What the review changed:

- **AC4 was overridden, not just implemented.** `COACH_PROFILE_MISSING` now answers `500` with an ERROR log, not the `409` the AC specified. An orphaned `coach_profiles` row is a data-integrity failure the caller neither caused nor can retry away; as a 4xx it would never reach 5xx-keyed alerting. `ALREADY_BLOCKED` stays `409`. The branch is explicit in `ReviewApiAdvice` because the `else` fallback is 403 — an unlisted code becomes `FORBIDDEN`, never a 5xx.
- **AC1's evidence was replaced.** Both barrier-based concurrency tests pass unchanged against a plain `findById`, so the Completion Note above claiming "the concurrent `CyclicBarrier` IT is what actually proves the lock" was **wrong**. A mutation-verified test now carries that burden; the barrier tests are kept as regression nets with corrected javadocs. See the Review Findings entry for why the obvious lock-test design also failed to catch the mutation.
- **Test-strength gaps closed:** the enforcement-list test now pins aggregation (count > 1) and the 30-day window, which the single-strike fixture left open; `blockReview`'s 404 path and AC3's "log row carrying `reason`" are now asserted rather than assumed.
- **Four documentation defects fixed**, three of them introduced by AC7's own cleanup: a dangling cross-reference into the deleted `skillars-9-3` section, a duplicate `## Last audit: 2026-08-04` heading recreating the exact ambiguity trap AC7 warns about, an audit block claiming a code review that had not yet run, and an "all 6 ACs" count on a 7-AC story.
- **Correction to the AC3 framing:** `ReviewModerationResolvedEvent` has **zero listeners in `src/main`**, so the "double-fire an event" half of the original `deferred-12` D3 defect was inert. The duplicate-audit-row and double-recompute halves were real; the event half was not.
- **Deferred (see `deferred-work.md`):** the Gemini moderation listener can silently revert a committed admin BLOCK via an unlocked `findById` + unconditional status write, with the exception swallowed. Pre-existing and untouched here, but it means AC1's lock only guards admin-vs-admin races, not admin-vs-moderation-pipeline.

### File List

- `src/main/java/com/softropic/skillars/platform/reviews/contract/ReviewErrorCode.java`
- `src/main/java/com/softropic/skillars/platform/reviews/api/ReviewApiAdvice.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminReviewService.java`
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewFlagService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/CancellationRefundService.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java`
- `src/test/java/com/softropic/skillars/platform/admin/api/AdminReviewQueueIT.java`
- `src/test/java/com/softropic/skillars/platform/reviews/api/ReviewFlagIT.java`
- `src/test/java/com/softropic/skillars/platform/admin/api/CoachEnforcementListIT.java`
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

| Date | Version | Description | Author |
|------|---------|-------------|--------|
| 2026-08-04 | 0.1 | Story created from `deferred-work.md` — four verified-still-open admin/moderation items grouped, four stale entries identified for deletion | Mbah |
| 2026-08-05 | 1.0 | Implemented all 7 ACs: `blockReview()` race-safety/idempotency guard, fail-closed coach-profile guard in `ReviewFlagService`, dead `processAdminRefund` stub removed, enforcement list N+1 batched. Full `mvn -o verify` green (807 unit + 842 IT, 0 failures). `deferred-work.md` updated per AC7. Status → review | Mbah (dev agent) |
| 2026-08-05 | 1.1 | Code review (3 adversarial layers): no functional defect found in `src/main`; all 7 ACs correctly implemented. 2 decisions resolved + 10 patches applied, 3 items deferred, 7 dismissed as false positives. Headline changes: a deterministic lock-proof IT replaces the barrier test as AC1's evidence (both barrier tests passed unchanged against a plain `findById`), and **AC4 was overridden — `COACH_PROFILE_MISSING` now returns `500`, not `409`**. Also corrected the AC count in this log (was "6 ACs" for a 7-AC story) and three `deferred-work.md` hygiene defects the AC7 cleanup introduced. Full `mvn -o verify` green after patches: 845 IT (842 + 3 new), 0 failures, 0 errors. Status → done | Mbah (code review) |
