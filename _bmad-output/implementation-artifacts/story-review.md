# Story Review: skillars-deferred-83

**Reviewed:** 2026-08-29  
**Reviewer:** Senior Dev Audit  
**Status:** Ready for dev with noted timing assumptions

---

## Summary

The story is well-structured with clear acceptance criteria. Combines two unrelated pieces of work (video-cascade test hardening + reviews module frontend) to meet the "no small stories" bar — a documented pattern. No blockers found. **Key risks:** AC2's thread-timing test fragility, AC1's cascade ordering determinism, and the emphasized "HTTP 403, not 404" detail in AC4 (noted by spec as most-likely developer mistake).

---

## Per-AC Audit

### AC1: VideoDeletionService Transaction Isolation Test

**Proposed:** Spy on `VideoDeletionOutboxRepository`, stub `doThrow` for video B's save only, assert A commits independently while B rolls back atomically.

**Strengths:**
- Reuses established `@MockitoSpyBean` pattern (precedent: `CaptureReservationIT`, `MessageModerationSweeperIT`)
- Correctly places failure at transaction boundary (outbox-save is END of `deleteVideo`, so rollback is clean)
- Test-only, no production code change

**Corner cases to verify during dev:**

1. **Cascade iteration order must be deterministic.** The spec seeds videos A and B but doesn't specify if `cascadeDeleteForAccount` iterates by ID order, insertion order, or non-deterministically. If non-deterministic:
   - Sometimes B fails on iteration 2 (A already committed on iteration 1) → test passes
   - Sometimes A fails on iteration 1 (B hasn't processed yet, nothing to rollback) → test fails or passes vacuously
   - **Action:** Inspect `cascadeDeleteForAccount` implementation. If iteration is non-deterministic, seed/query videos with guaranteed order (e.g., by ID). Alternatively, use video IDs in assertions rather than A/B labels.

2. **Outbox-save failure point verification.** The spec assumes outbox-save is the ONLY failure point in `deleteVideo`. If other operations exist after operational-state update (DB trigger side-effects?), the test might pass by accident if an earlier exception already rolled back.
   - **Action:** Code-inspect `deleteVideo` method body. Confirm outbox-save is the last statement in the transaction, or identify the actual transaction boundary.

3. **Seeded video state not specified.** The spec says "seed two videos, A and B" but not their initial `operationalState`. Are they `UPLOADED`? `ARCHIVED`? The cascade presumably updates to `PURGED`, so the state-change rollback is what gets tested.
   - **Action:** Seed both in a known, identical state. Assert B's state explicitly pre- and post-cascade (e.g., `video_B.operationalState == UPLOADED` before, `video_B.operationalState == UPLOADED` after failure).

4. **Outbox-row count verification.** The spec asserts "no outbox row exists for B" which is correct. But ensure the query is definitive (`findByVideoId(videoB.getId()).isEmpty()` not `count() == 0`), and that retry scenarios can't leave stale rows.

---

### AC2: Cross-Drill Lock Causality Test

**Proposed:** Instrument `findByIdForUpdate` with entry/exit timestamps, inject ~300ms sleep on first successful acquisition, assert second thread's timestamp is ~250ms+ later.

**Strengths:**
- Measures lock causality (serialization), not just end-state correctness
- Reuses `@MockitoSpyBean` pattern
- One-shot `compareAndSet` guard prevents double-instrumentation

**Timing-dependent risks:**

1. **The 250ms threshold is fragile under CI load.** Injects 300ms, asserts ≥250ms separation (83% threshold). On a CI agent running other jobs, this could flake. JVM GC pauses, CPU contention, and thread scheduler latency can easily exceed 50ms.
   - **Severity:** Medium. Conservative threshold (83%) should mostly absorb normal noise, but heavy load could cause failures.
   - **Mitigation:** If flakiness occurs, increase injected sleep to 500ms (adjust threshold to ~400ms) or add `@RepeatedTest(5)` to detect intermittent failures. Consider a comment in the test explaining this is timing-based and may need adjustment on slow CI.

2. **Edge case: thread interleaving and one-shot delay guard.** The `compareAndSet(false, true)` guard assumes the first thread to enter the `doAnswer` successfully is the one that should inject delay. But thread B's call might *return* from `doAnswer` (completing successfully) BEFORE thread A's injected sleep finishes, causing timestamps to nearly overlap despite the lock working correctly.
   - **Specific scenario:** Thread A calls → enters doAnswer → sets flag → sleeps 300ms (now holding lock). Thread B calls → blocks on pessimistic lock → retries → eventually succeeds (call completes) while A's sleep is still running. If B's successful-call exit-timestamp is recorded at T+280ms and A's at T+300ms, the separation is only 20ms despite proper serialization.
   - **Root cause:** The test measures when calls EXIT the spy, not how long each thread HELD the lock. B's call might successfully acquire the lock (breaking through retries) and exit before A's sleep finishes, even though B was truly blocked for 280ms by the lock.
   - **Action:** Clarify test intent in a comment. The goal is to prove B was blocked/retried (not that B's exit is 250ms+ later). Consider measuring retry-count instead of timestamps: assert B experienced ≥1 retry (caught `PessimisticLockingFailureException`) before the successful call. Alternatively, measure the timing of the lock-acquisition event itself (when `findByIdForUpdate` ENTERS the spy), not the exit.

3. **Verify shared repository instance.** The test spawns two threads via `ExecutorService`. Both must reference the same `VideoRepository` singleton for the mock spy to see both calls. The existing end-result test (`deleteVideo_concurrentCallsOnTwoClonesSharingOneVideoId_doesNotDoublePublishDeletionEvent`) presumably passes, so infrastructure is likely correct, but confirm.

---

### AC3: Reviews List UI — Read Side

**Proposed:** Create `reviews.api.js`, add "Reviews" section to `CoachPublicProfilePage.vue`, list reviews with role, rating, body, date, coach-response (if present), "Load more" pagination.

**Strengths:**
- Follows `marketplace.api.js` pattern
- Renders for guests (no auth gate)
- Empty-state reuses existing key (avoids duplication)

**Ambiguities:**

1. **Date-formatting utility not named.** The spec says "reuse whatever date-formatting utility other pages already use" but doesn't identify it. Common options: `dayjs`, `vue-dayjs`, custom `formatDate()` helper.
   - **Action:** Search `src/frontend` for existing date formatting in templates (look for `createdAt`, `formatDate`, `dayjs` usage). Reuse the established pattern.

2. **Coach-response visibility is conditional but underspecified.** "If present (indented/boxed)" — but if `coachResponseBody` is empty string or whitespace?
   - **Action:** Render section only if `coachResponseBody?.trim()` is truthy.

3. **Pagination state management.** The "Load more" button must maintain a `currentPage` counter across multiple clicks. If the component rebuilds the entire list on each fetch, pagination breaks.
   - **Action:** Maintain `currentPage` ref, increment on each button click before fetching `listCoachReviews(coachId, ++currentPage)`.

4. **Empty state placement.** If the list is below other page content, the empty-state message might not be immediately visible. Acceptable UX but suboptimal.
   - **Action:** Place empty state immediately after section title, before list. Show ONLY the message when `totalElements === 0`.

---

### AC4: Reviews Write/Edit UI — Write Side

**Proposed:** Role-gated dialog (`isParent || isPlayer`), call `getMyReviewForCoach` on mount, branch on `errorKey === 'reviews.reviewNotFound'`, handle moderation states, refresh both "your review" and AC3 list on success, use `useErrorHandler` for errors.

**Strengths:**
- Correctly uses `errorKey === 'reviews.reviewNotFound'` (not `status === 404`) **[spec emphasizes as most-likely mistake]**
- Role-gate pre-applied (user doesn't see buttons if not eligible role)
- Refresh both lists on success (keeps UI consistent)
- Delegate error mapping to `useErrorHandler` (no custom logic)

**Critical corner cases:**

1. **HTTP 403 "not found" is the most-likely mistake.** The spec emphasizes repeatedly: endpoint returns 403 (not 404) with `errorKey === 'reviews.reviewNotFound'`. Naive `status === 404` check will fail.
   - **Current safeguard:** Spec uses correct branch on `errorKey`. The `useErrorHandler` utility must map it correctly.
   - **Action:** Explicit test scenario in dev-server pass: login as new player, visit coach profile, confirm "Write a review" button shows (not error page or "not found" message).

2. **Pre-filling edit dialog requires safe field access.** If fetched review has `body === null` or `moderationStatus === undefined`:
   - `v-model="rating"` should populate from `review.rating`
   - `v-model="body"` should populate from `review.body || ''` (empty string for empty textarea)
   - No moderation note shows (only if `PENDING`, `UNDER_REVIEW`, or `BLOCKED`)
   - **Action:** Safely access: `review.body || ''`, `review.rating || 0`.

3. **Dialog state cleanup between new/edit flows.** A user opens "Write a review" (blank form) → cancels → review is posted → visits again → sees "Edit" → opens dialog (should pre-fill). Must reset state on "Write a review" click and pre-fill on "Edit" click.
   - **Action:** Reset dialog in `@show` event handler: `rating = null; body = ''` for new, pre-fill for edit.

4. **Moderation status display is conditional.** The spec says:
   - `PENDING` or `UNDER_REVIEW` → show pending note
   - `BLOCKED` → show blocked note
   - `APPROVED` (or missing) → show nothing
   - **Action:** Use `v-if="review?.moderationStatus === 'PENDING' || review?.moderationStatus === 'UNDER_REVIEW'"` for pending, `v-if="review?.moderationStatus === 'BLOCKED'"` for blocked.

5. **Eligibility window is not pre-flightable.** No API to check if user qualifies before submitting (no completed session with coach in last 14 days). User writes review, submits, then learns they're not eligible via `reviews.noRecentSession` error.
   - **Status:** Spec acknowledges this. Backend enforces it, UI displays error via `useErrorHandler`.

6. **Edit has a one-per-year rate limit.** Error key `reviews.updateTooSoon` is backend-enforced.

7. **Submit success re-fetches in sequence.** Spec says "refresh the 'your review' state ... and re-fetch page 0 of the approved-reviews list." Sequential order is fine (if review is pending, "your review" shows pending, list doesn't include it yet). This is correct behavior.

8. **Three i18n locale packs (en-US, de-DE, fr-FR) must be updated together.** The spec correctly requires all three. Missing any one is a failure.
   - **Action:** Verify all 3 files have the new `reviews:` namespace with all strings and 8 error keys after implementation.

9. **The 8 enumerated error keys are the only reachable ones.** Spec explicitly excludes flag/coach-response codes (because this UI never calls those endpoints). This is correct. If backend adds new error codes later, the UI falls back to raw-message behavior (safe but not localized).

---

## Task 3: Ledger-Hygiene Audit

The story closes/dismisses 6 items without picking them up. These seem reasonable but warrant quick verification:

1. **AvailabilityService.deleteBlock test coverage: already fixed by deferred-82 AC1.** → Verify that deferred-82 AC1 actually added this test. No further work needed if confirmed.

2. **AvailabilityService SELECT FOR UPDATE deadlock risk: false premise — single-row-per-transaction locking.** → Quick scan: confirm each `AvailabilityService` lock-taking method locks exactly one coach-profile row (no nested locks, no circular waits). Claims solid but worth 30s verification.

3. **VideoDeletionService self-injection concerns: unreachable Spring-startup-time issues.** Precedent: `RadarCompositeCalculationService` uses same pattern. → Verify `RadarCompositeCalculationService` exists and uses `@Lazy @Autowired self`. No change needed if precedent holds.

4. **PlayerProfile XOR-ownership-both-null: false premise — V84__player_self_registration.sql CHECK constraint.** → Verify `chk_pp_owner` constraint exists: `(playerAccountId IS NOT NULL) OR (parentAccountId IS NOT NULL)`. State is truly unreachable if it does.

5. **BookingService hardcoded-fallback strings: leave-as-is.** Same unreachable-orphaned-profile shape already decided for messaging. → Acceptable precedent.

6. **SessionPackPaymentService.purchasePack missing @Transactional: no real bug.** Compensating-action pattern already correct for flows spanning external Stripe call. → Acceptable technical judgment.

---

## Summary of Risks by Severity

### High
- **AC4: HTTP 403 "not found" detail.** Most-likely dev mistake per spec itself. Mitigation: explicit test scenario in dev-server (new player with no review shows "Write a review", not error).

### Medium
- **AC2: Thread-timing test fragility.** 250ms threshold might flake under CI load. Mitigation: conservative threshold (83% of injected 300ms); add `@RepeatedTest(5)` if flakiness observed.
- **AC1: Cascade iteration determinism.** If iteration is non-deterministic, test can flake. Mitigation: verify cascade order, seed/query to guarantee sequence.

### Low
- **AC3: Date-formatting utility not named.** Requires codebase search. Mitigation: standard for this codebase.
- **AC4: Dialog state cleanup.** Requires careful component lifecycle. Mitigation: reset state in `@show` handler, pre-fill from fetched review.

---

## No Blockers

The story is **ready for dev**. All ACs are implementable as specified. No architectural issues or false assumptions found. Exercise extra care on AC2's timing (could be flaky) and AC4's HTTP 403 detail (most-likely mistake).
