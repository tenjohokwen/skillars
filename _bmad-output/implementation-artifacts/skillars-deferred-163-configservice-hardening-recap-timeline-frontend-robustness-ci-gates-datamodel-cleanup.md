# Story skillars-deferred-163: ConfigService hardening, recap/timeline lifecycle fixes, frontend request robustness, and CI gate hardening

Status: done

<!-- Created 2026-10-26 (/bmad-create-story). User-requested bundle mined from deferred-work.md. Verified
     against HEAD 0f089e5b (skillars-deferred-162 merged) before drafting; see "Verified state at drafting."
     Ledger line numbers below are current working-tree lines as of drafting time — locate bullets by lead
     text if they have drifted by the time dev starts.

     Revised 2026-10-26 after story-review.md (four-layer verification: citations, ledger/precedent,
     mechanism, corner cases, each re-checked). All HIGH/MEDIUM findings were independently re-verified
     against the real source by the reviser (not just trusted from the review) before being applied — see
     each AC's "Corrected" notes.

     Revised again 2026-10-26: owner dropped the three review-flagged decision points entirely —
     verification_status CHECK constraint (ex-AC6), orphaned voice-note upload cleanup (ex-AC8), and
     isBookingPlannable's UPCOMING-acceptance change (was AC3.3) — rather than proceeding with any of them.
     See "Dropped by owner" below. ACs renumbered 1-7 accordingly; the ledger mapping table and "out of
     scope" list were updated to match. -->

## Story

As a maintainer of the Skillars platform,

I want ConfigService's concurrent-refresh paths hardened, the recap/plan-note/timeline lifecycle bugs around
session completion fixed, three frontend request-robustness gaps closed, two CI gate scripts hardened, and
the dead `voice_note_text` column's last code reader removed,

so that a DB outage does not wedge config readers indefinitely, a coach never loses typed wrap-up text or
sees a mis-dated or unsanitised recap, prep and plan-note flows never show a carried-over note from the
wrong session, the coach-player workspace degrades gracefully instead of hanging forever, a CI failure is
never masked by a swallowed test-load error or a mis-scoped log parse, and the path to finally dropping
`voice_note_text` is unblocked.

## Owner decisions (2026-10-26)

- **D163-A, scope: one bundled story, not split.** The remaining groups below ship together, matching the
  established "bundle small unrelated items" pattern from prior deferred-* stories.

### Dropped by owner (2026-10-26) — full details on each were in story-review.md and the prior revision of
this file; kept brief here per this project's established "DROPPED" convention (cf. deferred-161's index/
phone-operators drops)

- **`verification_status` CHECK constraint — DROPPED.** Would have reversed `V149`'s explicit, recorded
  decision not to add this CHECK (made because `spring.jpa.generate-ddl: true` could diverge an
  already-booted environment's schema from a fresh Flyway-only build) and broken a passing IT
  (`UserSchemaWidthIT`) that locks that decision in. Technically safe to reverse now that `generate-ddl` is
  permanently removed, but the owner chose not to spend this story reopening that call. Ledger bullet 893
  is left untouched, not picked up.
- **Orphaned voice-note upload cleanup — DROPPED.** Re-verification found `audio/webm` was never in
  `platform.filestorage`'s MIME allowlist, at any point — every upload attempt would have failed validation
  before a row could ever be created, so these rows almost certainly never existed. Ledger bullet 3206 is
  left untouched, not picked up; a future pass can close it with a quick empirical count if desired.
- **`isBookingPlannable` accepting `UPCOMING` — DROPPED.** Both copies' own code comments say rejecting
  `UPCOMING` is intentional ("no session-plan creation window remains once a booking is UPCOMING"); flipping
  it is a product-behavior change, not a bug fix, and the owner chose not to make that call in this story.
  Ledger bullet 3192 is left untouched, not picked up. (The *other* three plan-note/timeline items — the
  timeline timestamp fix, the booking-time-bounded prep lookup, and `setPlayerRequest`'s clock fix — are
  unrelated to this item and remain in scope under AC3.)

### Explicitly out of scope (do not re-pick)

- **`ProjectedRevenueService` ignoring variable session pricing** (ledger line ~845) — the ledger itself
  says "revisit in a pricing-model story"; needs its own story, not a bundle item.
- **No index for `main.user`'s cleanup-sweep predicate** (ledger line ~2186) — blocked twice already
  (deferred-125, considered and dropped again at deferred-161) pending production row-count/`EXPLAIN`
  evidence. Still no production deployment exists. Do not guess an index shape.
- **"Frontend unit-test job isn't merge-gating"** (ledger lines ~1749-1768, ~2637-2642) — an
  owner-reaffirmed **accepted design** (opt-in auto-detection via `detect-frontend-changes.sh`, decided at
  deferred-129). Not an open defect; do not relitigate.
- **`verification_status` CHECK constraint** (ledger line ~893) — dropped by owner 2026-10-26, see above.
- **Orphaned voice-note audio uploads** (ledger line ~3206) — dropped by owner 2026-10-26, see above.
- **`SessionPlanService`/`SessionTemplateService` `isBookingPlannable` accepting `UPCOMING`** (ledger line
  ~3192) — dropped by owner 2026-10-26, see above.

## Ledger → story mapping (so future passes do not re-pick these)

All bullets live in `_bmad-output/implementation-artifacts/deferred-work.md`. On completion the dev tags each
with `[PICKED UP by skillars-deferred-163 ACn]` and then follows AC7's hygiene step.

| AC | Ledger bullet (lead text) | Section | Line |
|----|---------------------------|---------|------|
| 1 | "Must-be-fresh waiters have no wait bound…" | code review of skillars-deferred-161 (2026-10-25) | 3250 |
| 1 | "`scheduledRefresh()` can park the shared `@Scheduled` thread." | code review of skillars-deferred-161 (2026-10-25) | 3251 |
| 1 | "`invalidate()` is per-JVM and not deferred to after-commit." | code review of skillars-deferred-161 (2026-10-25) | 3252 |
| 1 | "`load()` `putAll`+`retainAll` is not an atomic swap." | code review of skillars-deferred-161 (2026-10-25) | 3253 |
| 1 | "`refreshCache()` failure after `invalidate()` causes all subsequent config gets to throw 409…" | code review of skillars-1-1-feature-gate-configuration-layer (2026-06-11) | 909 |
| 2 | "Sweeper-vs-resume race loses typed wrap-up text." | code review of skillars-12-7-recap-lifecycle (2026-10-22) | 3214 |
| 2 | "Recap text is stored and reported raw (unsanitised)." | dev of skillars-12-7-recap-lifecycle (2026-10-22) | 3209 |
| 2 | "QUICK-mode drill snapshot reflects plan at parent confirmation." | code review of skillars-12-8-timeline-enrichment (2026-10-23) | 3218 |
| 3 | "`findLatestWhatsNextSuggestion` is not time-bounded…" | code review of skillars-12-6-session-prep-completeness (2026-10-22) | 3198 |
| 3 | "Recovered recaps are timestamped at write time (Open Question 3)." | dev of skillars-12-7-recap-lifecycle (2026-10-22) | 3207 |
| 3 | "`setPlayerRequest` still uses bare `Instant.now()`…" | dev of skillars-12-6-session-prep-completeness (2026-10-22) | 3193 |
| 4 | "Radar-preference flush on `pagehide` may be cancelled." | code review of skillars-12-9-coach-player-workspace-parity (2026-10-23) | 3222 |
| 4 | "Radar-preference PUTs are unordered." | code review of skillars-12-9-coach-player-workspace-parity (2026-10-23) | 3223 |
| 4 | "`fetchCoachTier` has no timeout." | code review of skillars-12-9-coach-player-workspace-parity (2026-10-23) | 3224 |
| 5 | "Convention-test class loading swallows failures." | code review of skillars-deferred-160 (2026-10-24) | 3236 |
| 5 | "Gate evaluates the last statistics line of the whole build log." | code review of skillars-deferred-160 (2026-10-24) | 3237 |
| 6 | "Drop `booking.session_completion_data.voice_note_text` (contract migration)." | dev of skillars-12-7-recap-lifecycle (2026-10-22) | 3205 |

The table above has **17 rows, all 17 picked up by this story.** Dropped items (893, 3206, 3192) are listed
under "Explicitly out of scope" above, not here — they are deliberately left unpicked. The
recap-attendance-on-recovery Open Question at line 3208 is a related but distinct feature (coach confirms
attendance on a recovered recap) and is **not** part of this story, left for its own story.

## Verified state at drafting (HEAD 0f089e5b)

Paths are relative to `src/main/java/com/softropic/skillars/` unless prefixed `src/frontend/` or
`.github/`. Re-verify every citation against HEAD at dev time — ledger line numbers have historically
drifted by the time a story is picked up.

**AC1, ConfigService hardening** (`platform/config/service/ConfigService.java`)
- Fields: `cache`:47, `lastRefreshed`:48, `invalidationEpoch`:54, `loadedEpoch`:55, `refreshLock` (plain
  `ReentrantLock`):56.
- `scheduledRefresh()`:73-76 — just calls `refreshCache()`, no special-casing for a busy lock.
- `invalidate()`:380-382 — only increments an `AtomicLong`; no DB/transactional interaction today.
- `refreshCache()`:416-442 — single-flight already works (post-deferred-161); the must-be-fresh waiter path
  is the plain, uninterruptible `refreshLock.lock()` at **line 439**.
- `load()`:449-460 — `cache.putAll(newData)` (455) then `cache.keySet().retainAll(newData.keySet())` (456); a
  reader can observe a cross-key mix mid-load.
- **The old line-909 "409" claim is stale against current code — do not re-assert a 409 in the AC.**
  `ApiAdvice.java:643-648` maps `IllegalStateException` → **500** (deliberately, since skillars-deferred-91
  AC17 — see its javadoc at `:627-642`), and the generic `@ExceptionHandler(Throwable.class)` fallback
  (`ApiAdvice.java:127-132`) also returns 500. A must-be-fresh load failure during a DB outage surfaces as
  500 today, not 409. AC1's fix is "bounded wait instead of an indefinite hang, still surfacing as 500 on
  genuine failure," and it supersedes/closes the old line-909 note.
- Tests: `ConfigServiceTest.java` already has an extensive concurrency suite.
  `mustBeFreshReaderParkedBehindAPreInvalidateLoad_reloadsAfterIt` (~660-702) asserts the waiter thread
  reaches `Thread.State.WAITING` on the exact uninterruptible `lock()` call AC1 must change — **this test
  needs rewriting to match bounded-wait semantics, not just extending.** `mustBeFreshRefreshFailure_stillPropagates`
  (718-729) and `ttlOnlyRefreshFailure_servesTheCachedSnapshot_andRetriesNextRead` (704-716) pin
  failure-propagation behavior AC1 must preserve. No IT covers this path (`ConfigResourceIT.java` is
  REST-level only).
- **Corrected 2026-10-26 (story review): the only true after-commit-deferral precedent in this codebase is
  `EmailRetryScheduler.java:147-150`** (`platform/notification/infrastructure/EmailRetryScheduler.java`, not
  `infrastructure/**` as originally miscited), guarded by `isActualTransactionActive()`.
  `OutboxService.java:78-86` is a **different** mechanism — `registerSynchronization(...).afterCompletion()`
  used only to unbind a transaction-scoped dedup resource, guarded by `isSynchronizationActive()`; its real
  "run after commit" path is a separate `@TransactionalEventListener(phase = AFTER_COMMIT)` method
  (`OutboxService.java:~109`), not a manual `registerSynchronization(...).afterCommit()` call. Follow
  `EmailRetryScheduler.java`'s `dispatchAfterCommit` method (and its javadoc, ~line 140) as the one real
  precedent.
- **AC1's `invalidate()`-after-commit item is defensive/future-proofing, not a live bug today:** `invalidate()`'s
  only production caller, `updateConfig()` (`ConfigService.java:243,248`), is not `@Transactional`, and its
  only caller `ConfigResource.updateValue()` is not `@Transactional` either.
- **Corrected 2026-10-26 (story review, directly re-verified in `refreshCache()`): a bounded `tryLock` alone
  does NOT remove the ledger's N-waiters-serialize-on-failure problem.** When the leader's load fails on the
  must-be-fresh path, it rethrows (`:432-434`, `if (!ttlOnly) throw e;`) — this propagates only to the
  leader's own caller. A waiter that was blocked on `refreshLock.lock(); refreshLock.unlock();` (the
  "wait for the in-flight load" line, `:440-441`) never sees that exception; it simply unblocks once the
  leader's `finally` releases the lock, loops back through `while (isStale())`, and — since `loadedEpoch`
  is still stale — wins `tryLock()` itself and runs its **own** `findAll()`. With N waiters this is still N
  sequential DB attempts, each potentially taking the full Hikari `connection-timeout` (`application.yaml:187`,
  30000ms). **AC1.1 must add a shared, volatile `(failure, failedAt)` pair** that the leader populates before
  rethrowing a must-be-fresh failure, which a waiter — after its own bounded `tryLock` times out — checks: if
  a failure was recorded within a short backoff window, rethrow it (wrapped) instead of attempting its own
  `findAll()`; otherwise the waiter may make one bounded attempt of its own. There is no existing exception
  object to "just rethrow" without this — it must be built.
- **Corrected 2026-10-26 (story review): the new timeout/failure exception must not be, or extend,
  `org.springframework.dao.PessimisticLockingFailureException`.** `ApiAdvice.java:670-676` maps that type to
  **409 Conflict** ("this resource is busy — please retry"), which is semantically wrong for a DB-outage/
  config-load failure and would silently misclassify it. Use a plain `RuntimeException`/`IllegalStateException`-
  family type instead, which still lands on the existing `:643-648` 500 handler (or add a narrow new handler
  if a distinct message is wanted — not required by this AC).
- **New, found during re-verification: `DatabaseResetTestExecutionListener.evictInProcessCaches`
  (`:712-719`) calls `configService.scheduledRefresh()` directly** to evict stale rows after a test-DB
  truncation. Because `refreshCache()` only loads when `isStale()` is true, and the cache is rarely stale
  immediately after a normal test run (default 300s TTL, no invalidation), this call already does nothing in
  the common case — a pre-existing gap, not something AC1.2 newly breaks, but directly adjacent to this
  AC's area. Fold in a small fix: change the listener to call `configService.invalidate()` before
  `scheduledRefresh()` (or equivalent), so the post-truncation refresh is guaranteed to actually run
  regardless of TTL state.

**AC2, recap lifecycle** (`platform/booking/service/BookingCompletionService.java`, `platform/development`,
`src/frontend/src/components/booking/{WrapUpSequence,SessionRecapDialog}.vue`)
- `submitWrapUp` (:154-230); the MISSING_RIGHTS throw for a swept booking is at **:168-175** (ledger cited
  :157-161 — drifted ~11 lines; reverify at dev time). `updateRecap` (the still-available `PUT /recap` path)
  is at `:373-394`, gated by `recapEditability` (`:339-361`) with a 30-day-default edit window — genuinely
  still usable after a sweep today.
- **Corrected 2026-10-26 (story review, re-verified directly): the placeholder signature `submitWrapUp`'s
  guard checks is not sweeper-exclusive.** `BookingCompletionService`'s admin force-complete path
  (`:305-311`) builds the **same** empty-recap-fields `SessionCompletionData` row (no `whatWeDid`/
  `whatsNext`/`whatWentWell` set, `completionMode = "QUICK"`) as the stuck-completion sweeper. The new
  error message/key must not claim the sweeper exclusively "auto-completed" it — word it to cover either
  cause (sweeper or admin force-complete).
- `WrapUpSequence.vue:296-308`'s `handleSubmitWrapUp` catch only branches on
  `errorKey === 'booking.concurrentModification'` (`:301`); everything else — including the sweeper/
  force-complete case — falls to the generic `step5SubmitError` toast (`:304`), even though the component
  already holds the typed recap text in local refs (`recapWhatWeDid`/`recapWhatsNext`/`recapWhatWentWell`).
- **Corrected 2026-10-26 (story review, re-verified directly in both files): `SessionRecapDialog.vue` has
  no prefill mechanism, and is mounted only from `CoachCommandCenterPage.vue`** (confirmed by repo-wide
  grep — `WrapUpSequence.vue` does not mount it today). Its only props are `modelValue`, `bookingId`,
  `playerName` (`:122-126`); its `load()` (`:172-177`) unconditionally overwrites `whatWeDid`/`whatsNext`/
  `whatWentWell` from the `GET /recap` response. For a fresh placeholder row these GET fields are empty
  anyway (the force-complete/sweeper row never set them), so a prefill **can** safely apply after `load()`
  resolves without clobbering real data — but only if it is applied **after**, not passed in and ignored.
  "Redirect to the recap dialog" therefore means: navigate the coach to `CoachCommandCenterPage` for this
  booking (the dialog's only current home) carrying the typed text, and merge it into the dialog's fields
  once `load()` completes and they are still empty.
- **Corrected 2026-10-26: this fix recovers the recap TEXT only, not the whole wrap-up.** `updateRecap`
  persists just `whatWeDid`/`whatsNext`/`whatWentWell` (`BookingCompletionService.java:373-394`) — it has
  no parameters for `playerAttended`, the three ratings, or `homeworkDrillIds`. Those remain lost for a
  swept/force-completed booking; do not claim otherwise in the AC or the UI copy.
- `submitWrapUp` (:179-190) stores `req.recapWhatWeDid()`/`recapWhatsNext()`/`blankToNull(req.recapWhatWentWell())`
  raw — no sanitizer call. **`updateRecap` (:373-394) is equally unsanitised** (`req.whatWeDid().trim()`
  etc.) — since AC2.2 is about to route coaches into exactly this method, fixing only `submitWrapUp` would
  leave the sweeper-recovery path writing unsanitised text, defeating the point. **`submitWrapUp`'s own
  LIVE branch (:213-218) also has an internal inconsistency**: it publishes `BookingCompletedEvent` with
  the **raw** `req.recapWhatWeDid()`/`req.recapWhatsNext()` for the first two recap fields, but the
  already-processed `scd.getWhatWentWell()` for the third — all three must come from the saved/sanitised
  `scd`, matching what `confirmCompletion`'s QUICK path already does correctly (`:446-449`,
  `scd.getWhatWeDid()`/`scd.getWhatsNext()`/`scd.getWhatWentWell()`). `ReportGenerationService.java:475-482,
  647-654` renders these fields straight into the PDF, unsanitised, from whichever path wrote them.
  Contrast: `SessionPrepService.java:116-120` already sanitizes **at read** for the prep-card "carried
  over" display only — the raw/sanitized split is already live and inconsistent. Established call
  pattern: `BookingService.sanitizeAndTruncate(ContactDetailSanitizer, String, int)` static helper
  (`BookingService.java:1124-1128`, package-private, same package as `BookingCompletionService`), used by
  `setPlayerRequest` (`:771`) and `setCoachPlanNote` (`:811`). `ContactDetailSanitizer` is not currently
  injected into `BookingCompletionService` — add it. Columns are `varchar(500)` each
  (`SessionCompletionData.java:52-59`) — pass 500 as the truncation length.
- **Corrected 2026-10-26 (story review): the QUICK drill snapshot needs a new, nullable column — there is
  none today.** `SessionCompletionData` (`:20-67`) has no field for plan-drill ids; `homeworkDrillIds`
  (`:60-61`) is a distinct concept (homework assignments, not the session plan's own drill blocks). This
  means a new migration — the only new migration this story needs (the `verification_status` CHECK that
  would have needed a second one was dropped, see Owner Decisions).
  `TimelineDrillSnapshotResolver.forBooking` must read the new snapshot column for a QUICK-completed
  booking instead of `sessionRepository.findByBookingId`'s live plan; `SluCalculationService`'s own
  `onBookingCompleted` listener also reads the live plan today and will disagree with the timeline unless
  it is pointed at the same snapshot. `QuickCompleteTimeoutService` (the delayed/swept QUICK completion
  path) is a **third** route to the same `BookingCompletedEvent` and must go through the same snapshot, not
  just the direct `submitWrapUp` QUICK branch.
- `SessionPlanService.updateSession` (now `:116-164`, drifted from the originally-cited `:115-158`) only
  blocks edits when `session.getStatus()` is COMPLETED/CANCELLED (`:129-132`, unchanged) or the booking is
  terminal (null-status check `:138-142`, `isTerminal` check `:143-147`, drifted from `:138-144`) — a
  QUICK-mode booking's plan stays editable through `BookingCompletedEvent`. `submitWrapUp`'s LIVE branch
  publishes `BookingCompletedEvent` immediately (`:207-218`); QUICK instead goes through `confirmCompletion`
  (`:429-451`), which already reads `scd.getHomeworkDrillIds()` at that later point. `submitWrapUp` already
  serializes `homeworkDrillIds` at `:192-198`, giving a direct precedent for the new column's shape, rather
  than coupling `SessionPlanService` locking into `BookingCompletionService`.
- Tests covering this area today (will need new cases, not rewrites): `BookingCompletionServiceTest.java`,
  `AdminBookingCompletionServiceTest.java`, `WrapUpSequenceSpec.js` (Vitest). **`SessionPauseResumeServiceTest`
  also constructs `BookingCompletionService` directly** — adding the `ContactDetailSanitizer` dependency (a
  constructor change) will break its instantiation too; update it alongside the others.

**AC3, plan-note/timeline correctness** (couples with AC2's recap-timestamp item — same root cause, same
files). **Note: the original draft's third item, `isBookingPlannable` accepting `UPCOMING`, was dropped by
owner 2026-10-26 — see "Dropped by owner." This AC now covers only the timestamp/bound/clock items below.**
- **Corrected 2026-10-26 (story review, directly re-verified — this was the single most consequential
  citation error in the original draft): `TimelineEventListener.saveTimelineEvent:281` is NOT the
  SESSION_COMPLETED writer.** `writeSessionCompleted` (`:121-...`) calls
  `timelineRepository.insertSessionCompletedIfAbsent(playerId, bookingId, Instant.now(), json, coachId)`
  **directly at `:131`**, bypassing `saveTimelineEvent` entirely. `saveTimelineEvent` (private, `:273-282`,
  `evt.setOccurredAt(Instant.now())` at `:281`) is used only by `writeHomeworkAssigned` (HOMEWORK_ASSIGNED)
  and the generic `writeTimelineEvent` (RADAR_ASSESSMENT via `onRadarEntrySubmitted`, PERFORMANCE_REPORT via
  `ReportGenerationService:267`) — events with **no booking association**, for which "session-end time"
  has no meaning and `Instant.now()` remains correct. **Scope the fix to `writeSessionCompleted`'s
  `insertSessionCompletedIfAbsent` call at `:131` only**, replacing `Instant.now()` with the booking's
  `requestedEndTime` (the method/its caller `onBookingCompleted` needs to load/pass it — `BookingCompletedEvent`
  itself carries no booking times). There is no separate "captured session-end instant" anywhere for a
  QUICK/swept completion — `requestedEndTime` is the only available anchor.
- **New, found during re-verification: this timestamp change shifts `CoachPlayerLastSessionResolver
  .isRecapAttributable`'s (`:58-69`) dominant code path.** Its own comment documents that
  "`calculated_at` [SLU's `calculated_at`] `> occurred_at` is the NORMAL ordering" for the *same* booking
  today, because both are independent async listeners stamping wall-clock time off the same commit — the
  fast-path `!occurredAt.isBefore(sluDate)` check is therefore the **minority** case today; the dominant
  path already falls through to the `findLastSessionBookingId` correlation query. Once `occurredAt` becomes
  session-end time (typically well before `calculated_at`/now), the fast path will fire **even less**
  often, making the correlation query the near-universal path instead of just the dominant one. Still
  functionally correct (the comment's own reasoning holds either way), but it is a measurable behavior/cost
  shift that needs its own test, not just an assumption that "it was already mostly this path." Existing
  rows are not backfilled, so timeline ordering briefly mixes processing-time and session-time stamps.
- `PlayerTimelineRepository.findLatestWhatsNextSuggestion` (~`:100-108`, called from
  `SessionPrepService.getPrep:111-112`) is a native query: filters `player_id`, coach metadata match,
  `event_type = 'SESSION_COMPLETED'`, `reference_id <> :bookingId`, non-blank `whatsNext`, ordered
  `occurred_at DESC, id DESC LIMIT 1`. **It has zero time bound relative to the prepped booking.**
  `Booking.requestedStartTime`/`requestedEndTime` (`Booking.java:42,45`) are available as the anchor. The fix
  must bound by the **other** booking's `requestedStartTime` (booking-time-based, via a join/subquery against
  the referenced booking — a join precedent against `booking.bookings` already exists at `:45`), not by
  `occurred_at` — `occurred_at` is itself unreliable for recovered/swept sessions, which is exactly why this
  item and the timestamp fix above are coupled: fixing the timestamp alone does not add the missing bound,
  and adding the bound without fixing the timestamp still leaves a mislabelled sweep-date on recovered
  recaps.
- **New, found during re-verification: the Session Prep card's OTHER two fields (`lastWhatWeDid`/
  `lastWhatsNext`) come from `CoachPlayerLastSessionResolver`, which is also unbounded relative to the
  prepped booking** — fixing only `findLatestWhatsNextSuggestion` leaves the card able to pair a
  correctly-bounded suggestion with a "last session" summary from a session **after** the prepped booking.
  If in scope, the bounded query should also order by the referenced booking's `requested_start_time DESC`
  and make an explicit decision on legacy `reference_id IS NULL` rows (currently included, `:103`).
- `BookingService.rejectIfNotEditablePlayerRequest` (`:838-847`) uses bare `Instant.now()` against
  `booking.getRequestedEndTime()`. **Corrected 2026-10-26: `setCoachPlanNote` (`:793-818`) does NOT
  "delegate to `isPrepNoteEditable` for both codes"** as the original draft said — it already does the
  **exact two-step split** this AC wants: an explicit status check first (`:801-805`, throws
  `BOOKING_NOT_EDITABLE`), **then** `!booking.isPrepNoteEditable(now)` (`:806-807`, throws
  `SESSION_ALREADY_ENDED`) — by the time `isPrepNoteEditable` is called the status half of its combined
  boolean is already guaranteed true, so in practice only its time-bound half is being exercised at that
  call site. `rejectIfNotEditablePlayerRequest` already has the identical two-step shape
  (`PLAYER_REQUEST_EDITABLE_STATUSES` status check, then a separate time check) — the only thing wrong is
  the clock source. **Copy `setCoachPlanNote`'s pattern verbatim**: keep the existing status check, replace
  only the inline `booking.getRequestedEndTime() != null && !Instant.now().isBefore(...)` line with
  `!booking.isPrepNoteEditable(Instant.now(ClockProvider.getClock()))`. Note
  `rejectIfNotEditablePlayerRequest`'s javadoc (`:816-832`) records a deliberate reason this guard is
  separate from `rejectIfPostCompletion` — preserve that reasoning/comment, only change the clock source and
  the time-check expression.
- `BookingServiceTest.java:1646-1763` already covers `setPlayerRequest` editability; confirmed no test
  depends on the wall clock specifically.
- **Dropped, left in `src/frontend/src/pages/coach/SessionBuilderPage.vue:390-393` for a future pass to
  find on its own:** during research for the now-dropped `isBookingPlannable` item, a genuinely separate,
  pre-existing bug was found there — its error branches compare `e.response.data.helpCode` to literal
  codes, but `ApiAdvice.operationDeniedHandler` (`:333-338`) actually puts the domain error code in the
  response's `errorMsg.errorKey`, not `helpCode` (which is a support/log id). Those branches are dead code
  today. Not touched by this story; not added to its ledger-hygiene additions either, since it was never
  part of this story's picked-up scope — left for whoever next touches that file or the ledger.

**AC4, frontend request robustness** (`src/frontend/src/components/development/CoachRadarCard.vue`,
`src/frontend/src/stores/{development,auth}.store.js`)
- `CoachRadarCard.vue`: local `useDebounce` helper (`:63-85`) with a `.flush()` method; `pagehide` listener
  at **:129** (`window.addEventListener('pagehide', flushPendingSave)`); `flushPendingSave` (`:104-106`)
  calls `debouncedSave.flush()`, which runs the normal debounced async fn (`:96-102`) —
  `await store.saveRadarPreferences(...)` → `putRadarPreferences`
  (`src/frontend/src/api/development.api.js:28-29`) → `api.put(...)` through the shared axios instance
  (`src/frontend/src/boot/axios.js`). Confirmed: no `keepalive`/`sendBeacon` anywhere in this flow.
- **Corrected 2026-10-26 (story review, directly re-verified): `navigator.sendBeacon` cannot be used
  here.** The endpoint is `@PutMapping("/api/development/players/{playerId}/radar/preferences")`
  (`RadarDisplayResource.java:52`), and `sendBeacon` only ever sends `POST`. Auth is cookie-based
  (`boot/axios.js:96`, `withCredentials: true`); CSRF is disabled for this app. Use
  `fetch(url, { method: 'PUT', keepalive: true, credentials: 'include', headers: {...}, body:
  JSON.stringify(...) })` instead — add this as a new function in `development.api.js` (keeping the
  centralized-API-call convention) rather than calling `fetch` inline in the component; note it bypasses
  the shared axios instance's interceptors.
- **Corrected 2026-10-26 (story review, directly re-verified): a client-side `_radarSaveSeq` response
  guard does not fix ledger 3223's actual defect.** `saveRadarPreferences`
  (`stores/development.store.js:273-280`) deliberately writes **no** local state on success — the ledger
  bug is that the **server** can end up holding an older selection because two PUTs sent out of order are
  *applied* out of order; a client-side guard that only ignores a stale *response* cannot change what order
  the server received or applied the writes in. Confirmed overlap is real and not hypothetical: the
  debounced fn (`:69-74`) is invoked from a bare `setTimeout` callback without awaiting any prior in-flight
  call, so two debounce firings more than 300ms apart can have overlapping in-flight PUTs on the wire. The
  real fix is one of: (a) **serialise sends** — chain each new save on the promise of the prior in-flight
  save so PUTs are always issued to the network in order (no backend change needed), or (b) have the client
  send a monotonic sequence/timestamp that the **server** uses to reject/ignore an out-of-order write
  (needs a backend change to `RadarDisplayResource`/its service). Prefer (a) unless the dev finds (a) still
  allows reordering somewhere not yet identified.
- `fetchCoachTier` (`stores/auth.store.js:34-41`): plain `api.get('/api/marketplace/coaches/me/tier')`, no
  timeout. `coachTier.value = null` in the catch handles a *rejected* request gracefully, but a request that
  never settles leaves the awaiting caller stuck forever. Consumers: `pages/coach/CoachPlayerDetailPage.vue`
  — `tierPromise = authStore.fetchCoachTier()` (`:431`), `await tierPromise` then `tierSettled.value = true`
  (`:386-389`); and **`pages/ProfilePage.vue:597`**, a second, previously-unmentioned caller
  (`skillars-deferred-151 AC3` comment confirms it's deliberately the only other one). **A visible
  indication for the "settled but unknown" case already exists** —
  `tierUnknown = computed(() => tierSettled.value && authStore.coachTier == null)`
  (`CoachPlayerDetailPage.vue:329`), rendered at `:128`. The actual gap is narrower than originally scoped:
  only that `tierSettled` never becomes true at all if the fetch hangs forever — once a timeout makes it
  settle (even to a failure), the existing `tierUnknown` UI already covers the rest.
- No app-wide axios timeout exists (`boot/axios.js`; confirmed by existing in-code comments at
  `composables/useSession.js:91` and `layouts/MainLayout.vue:458` that call this out explicitly). Precedent
  for a **per-call** timeout: `api/booking.api.js`'s `getCoachBookingRequests` (now `:38-39`, drifted) and
  `acceptBooking`/`declineBooking` (now `:23-30`, one shared deferred-40 comment rather than one each), each
  `{ timeout: 20000 }`. `fetchCoachTier` currently bypasses this project's centralized-API-call convention
  (it calls `api.get` directly inside the store instead of through an `api/*.api.js` module). **Corrected
  2026-10-26: the endpoint is `/api/marketplace/coaches/me/tier`, so the new API module function arguably
  belongs in `marketplace.api.js`, not `auth.api.js`** (dev's call either way — note it explicitly in
  Completion Notes) — with the same `{ timeout: 20000 }` shape.
- No dedicated Vitest spec for `CoachRadarCard.vue` or `fetchCoachTier` exists, but **this is not zero
  coverage to build around blind**: `developmentStoreSpec.js:114,288,296` already test
  `saveRadarPreferences`, and `CoachPlayerDetailPageSpec.js` already mounts `CoachRadarCard` — extend these,
  don't assume a clean slate.

**AC5, CI script hardening** (`src/test/java/com/softropic/skillars/config/IntegrationTestConventionTest.java`,
`.github/scripts/assert-context-count.sh`)
- `IntegrationTestConventionTest.allConcreteItClasses()` (the method spans `:147-175`; the `Class.forName`
  try/catch itself is `:163-171`, drifted-but-still-correct for that part — called by
  `integrationTestClasses()` `:138-144`, which filters out slices and asserts `hasSizeGreaterThan(100)` —
  "the floor") does `Class.forName(cn, false, ...)` wrapped in `catch (Throwable ignored) { ... }` at
  `:169-171`. With `initialize=false`, only linkage errors are possible there — a class that fails to load
  for a genuine reason silently never enters the model, invisible to every check built on it, including
  `distinctContextKeyCountIsPinned` (`:252`) and the size-100 floor itself. (A strict "no load failures"
  assertion can trip locally on stale `target/` classes — note this as a known false-positive source in
  Dev Notes, not a reason to skip the fix.)
- **Corrected 2026-10-26 (story review, verified against a real captured CI log, run 40338236660): the
  script's real output format is the Maven 3.9 short plugin prefix, not the full `maven-<plugin>-plugin`
  name the original draft assumed.** The actual banner lines are
  `[INFO] --- surefire:3.5.6:test (default-test) @ skillars ---` and
  `[INFO] --- failsafe:3.5.6:integration-test (default) @ skillars ---` (confirmed at the saved log's lines
  709 and 10516; the deferred-146 story file independently records the same marker). A regex anchored on
  `maven-failsafe-plugin` would match **nothing**, silently defeating the whole fix (the script would then
  report "no missCount found" and exit 1 on every build). Use a pattern that matches the real banner, e.g.
  `/--- (maven-)?failsafe[^ ]*:integration-test/`, and decide explicitly what the script does if no banner
  is found at all (fail loudly, matching the script's own existing "a gate that cannot find its input is not
  a gate" philosophy at `:197-203`).
- `.github/scripts/assert-context-count.sh` is 263 lines total; the actual stats grep is one line at
  `:194` (`grep -aoE 'size = [0-9]+, maxSize = [0-9]+, parentContextCount = [0-9]+, hitCount = [0-9]+,
  missCount = [0-9]+' "$LOG" | tail -1`), and the size≥maxSize and missCount-vs-size checks (`:217-239`)
  all run against that **same** parsed line — scoping the grep's input to the post-failsafe-banner section
  of the log therefore fixes all three checks at once, not just the missCount ceiling check.
  `pr-build.yml:71` runs one `mvn -B verify 2>&1 | tee build.log` (surefire's `test` phase then failsafe's
  `integration-test` phase, same combined log); the script is invoked at `pr-build.yml:88` as
  `assert-context-count.sh build.log 17` (matching the script's own current default `CEILING`).
- **Found during re-verification: in the real captured log, scoping is not yet fixing a live failure** —
  the first `cache statistics` line in that log is at 10559, *after* the failsafe banner at 10516, and
  there are none in the surefire section, so today's `tail -1` happens to already select the right line.
  The fix is still correct and worth doing (defensive against a future failsafe crash before its own stats
  line prints), but verify it with a **crafted fixture log** reproducing a pre-stats failsafe crash, not
  just the real log, since the real log doesn't currently exercise the bug.

**AC6, drop `voice_note_text` — contract migration, step 1 of a THREE-release sequence**
- Entity field: `SessionCompletionData.java:49-50` (`@Column(name="voice_note_text", length=2000)`).
  `isSweeperPlaceholder`'s null-check: `BookingCompletionService.java:470-475` (includes
  `existing.getVoiceNoteText() == null` in its placeholder test, alongside other field null-checks). Both
  GDPR scrubs are in `SessionCompletionDataRepository.java`: `:53` (`anonymizeByPlayerId`) and `:60`
  (`anonymizeByCoachId`), both nulling `voiceNoteText`. `GdprErasureService.java:413` references the column
  in a comment.
- Per `docs/deployment/migration-conventions.md` rule 2 (`:58-85`), a column is dropped **only in a release
  after** the one that removed every code reference to it. **Corrected 2026-10-26 (story review): the
  original AC6.3 (then numbered AC7.3) violated this rule by compressing two required steps into one future
  release.** After *this* story, the entity field is still JPA-mapped (Hibernate still selects/inserts
  `voice_note_text`), so this story is **not yet** "the release that removed the last non-scrub code
  reference" — only the `isSweeperPlaceholder` reader is removed. A future release that both removes the
  entity field/scrubs **and** drops the column in the same change would leave old pods (still running the
  previous release's JPA mapping) erroring against a column their own release's migration just dropped,
  during the overlap window of a rolling deploy. The correct sequence is **three** releases total: (1, this
  story) remove `isSweeperPlaceholder`'s reliance — done, no other change; (2, a future story) remove the
  entity field and both GDPR scrub references, pure Java, no migration of its own; (3, a further future
  story) `DROP COLUMN IF EXISTS` with `-- migration-lint: drop-prepared-in: V<n>` **naming a migration
  version number** (per `migration-conventions.md:63-64`, the marker format takes a Flyway version, never a
  PR/merge reference) — that marker must cite a version already on disk by the time release (3) ships, and
  the lint's own live-reference scan of `src/main` is the actual gate that must pass by then, not the marker
  alone.

## Acceptance Criteria

1. **ConfigService's concurrent-refresh paths have bounded waits with a shared failure signal, an atomic
   cache swap, an after-commit invalidation hook, and a test-reset fix; this supersedes the old line-909
   ledger note.**
   1. A must-be-fresh reader that loses `refreshLock.tryLock()` no longer parks in an uninterruptible
      `lock()` (`ConfigService.java:440-441`). It uses `tryLock(timeout, unit)` with a short, documented
      timeout (justified against Hikari's `connection-timeout`, `application.yaml:187`, 30000ms — e.g. a
      fraction of it, not equal to or longer). **A bounded `tryLock` alone does not fix the underlying
      problem**: today, when the leader's must-be-fresh load fails, it rethrows only to its own caller: a
      waiter that was simply blocked on the lock never sees that exception, unblocks once the leader's
      `finally` releases the lock, and — since the cache is still stale — becomes the new leader and runs
      its **own** `findAll()`, so N waiters still mean up to N sequential DB attempts (the ledger's
      N × connection-timeout serialization). The fix needs a **shared, volatile `(failure, failedAt)`
      pair**: the leader records it before rethrowing a must-be-fresh failure; a waiter whose own
      `tryLock` times out checks it, and — if a failure was recorded within a short backoff window —
      rethrows it (wrapped) instead of attempting its own `findAll()`. The thrown/rethrown type must
      **not** be, or extend, `org.springframework.dao.PessimisticLockingFailureException`
      (`ApiAdvice.java:670-676` maps that to **409**, which is wrong here); it must still land on the
      existing `IllegalStateException`/generic-`Throwable` 500 handlers (`ApiAdvice.java:643-648`,
      `:127-132`). Handle `InterruptedException` from the bounded `tryLock` sanely (restore the interrupt
      flag, treat as a timeout). Never silently falls back to stale data for a caller that explicitly
      asked for fresh.
   2. `scheduledRefresh()` (`:73-76`) uses `tryLock()` only (non-blocking) and skips the tick (logging at
      DEBUG/INFO) if the lock is busy, so it never parks the shared `@Scheduled` thread behind a concurrent
      must-be-fresh load. (In practice this scheduler pool has 8 threads, `application.yaml:117-119`, and
      most ticks already no-op because nothing is stale — this is a smaller-than-ledger-implied risk, but
      the fix is still correct and cheap.)
   3. `invalidate()` (`:380-382`) defers its effect to after the caller's transaction commits, following
      `EmailRetryScheduler.java:147-150`'s `dispatchAfterCommit` pattern (`TransactionSynchronizationManager
      .registerSynchronization(...).afterCommit()`, guarded by `isActualTransactionActive()` — file is at
      `platform/notification/infrastructure/EmailRetryScheduler.java`). **`OutboxService.java:78-86` is
      not the same pattern** — it registers an `afterCompletion()` synchronization only to unbind a
      transaction-scoped dedup marker, guarded by `isSynchronizationActive()`; do not copy it. If no
      transaction is active, `invalidate()` still invalidates immediately (current behavior) — this fix is
      defensive/future-proofing since no current caller wraps `updateConfig()` in an outer transaction; say
      so in Completion Notes.
   4. `load()`'s `putAll`+`retainAll` (`:455-456`) is replaced with a single atomic swap of one immutable map
      via a `volatile` reference, so no reader can ever observe a cross-key mix of old and new values mid-load.
      (No API today reads more than one key per call, so this mix is not currently observable — do it as
      hygiene, not as a fix for a reachable bug.)
   5. `DatabaseResetTestExecutionListener.evictInProcessCaches` (`:712-719`) calls
      `configService.scheduledRefresh()` directly to evict stale rows after a test-DB truncation, which
      already does nothing in the common case because the cache usually isn't stale yet. Change it to call
      `configService.invalidate()` first (or equivalent), so the post-truncation refresh is guaranteed to
      actually run.
   6. Unit tests: `mustBeFreshReaderParkedBehindAPreInvalidateLoad_reloadsAfterIt` (`ConfigServiceTest.java`
      ~660-702) is rewritten to assert the new bounded-wait behavior (still reloads after the in-flight load
      completes, now observed via `Thread.State.TIMED_WAITING`, not `WAITING`).
      `mustBeFreshRefreshFailure_stillPropagates` (718-729) and
      `ttlOnlyRefreshFailure_servesTheCachedSnapshot_andRetriesNextRead` (704-716) still pass unmodified in
      their outcome (failure still propagates / stale TTL read still served), only their mechanism assertions
      may need adjusting. New tests: a waiter rethrows the leader's recorded failure within the backoff
      window instead of attempting its own `findAll()`; a timed-out `tryLock` with no recent recorded
      failure makes one bounded attempt; `scheduledRefresh` skips (not blocks) when the lock is held;
      `load()` under concurrent readers never observes a partial map. A size-based `load()` assertion needs
      either a test-only accessor/seam (the `cache` field is private) or should be dropped in favor of a
      behavioral assertion — don't add reflection just for this. Allow enough timing margin versus CI
      variance (the existing 5-reader concurrency test already allows up to 10s).

2. **A coach never silently loses typed recap text to a sweeper/force-complete race, recap text is
   sanitised on every write path, and the QUICK-mode timeline drill snapshot is accurate.**
   1. `submitWrapUp` (`BookingCompletionService.java:154-230`) throws a **new**, dedicated error (distinct
      from the generic MISSING_RIGHTS path) when the booking was already force-completed — by the
      stuck-completion sweeper **or** by admin force-complete, which produces the identical empty-recap
      placeholder signature (`:305-311`) — instead of the existing "can no longer be edited" message, which
      is inaccurate either way since `PUT /recap` (`updateRecap`, `:373-394`) can still save it. Suggested
      key: `booking.recapStillEditableAfterForceComplete` (wording must not claim the sweeper exclusively).
   2. `WrapUpSequence.vue`'s `handleSubmitWrapUp` catch (`:296-308`) adds a branch on this new `errorKey`
      that navigates the coach to `CoachCommandCenterPage` for this booking (today the **only** place
      `SessionRecapDialog.vue` is mounted) carrying the typed `recapWhatWeDid`/`recapWhatsNext`/
      `recapWhatWentWell` text. `SessionRecapDialog.vue` gains an optional prefill mechanism that applies
      **after** its own `load()` resolves (`:172-177`) and only into fields the load left empty — safe
      because a fresh placeholder's GET response has nothing in these fields to begin with. **Scope note:**
      this recovers the recap **text** only; `playerAttended`, the three ratings, and `homeworkDrillIds`
      are not part of `updateRecap` and remain lost — do not claim otherwise in UI copy or Completion Notes.
   3. Sanitisation is added to **both** write paths, not just one: `submitWrapUp` (`:179-190`) and
      `updateRecap` (`:373-394`, the exact path 2.2 now routes coaches into) both run
      `BookingService.sanitizeAndTruncate(ContactDetailSanitizer, String, int)`
      (`BookingService.java:1124-1128`) on all three recap text fields before saving, with length 500
      (`SessionCompletionData.java:52-59`'s column lengths). `ContactDetailSanitizer` must be injected into
      `BookingCompletionService` (not currently a dependency). **`submitWrapUp`'s LIVE branch (`:213-218`)
      is also fixed to publish `BookingCompletedEvent` from the saved `scd.getWhatWeDid()`/
      `scd.getWhatsNext()`/`scd.getWhatWentWell()`**, not the raw `req.recapWhatWeDid()`/
      `req.recapWhatsNext()` it currently mixes with the already-correct `scd.getWhatWentWell()` — matching
      `confirmCompletion`'s QUICK path (`:446-449`), which already does this correctly.
   4. A new nullable column on `session_completion_data` (the only new migration this story needs) stores a
      snapshot of the QUICK-mode session plan's drill ids, serialized the same way `homeworkDrillIds`
      already is (`:192-198`), written at `submitWrapUp`'s QUICK branch **and** at
      `QuickCompleteTimeoutService`'s delayed-completion path (a second route to the same
      `BookingCompletedEvent` that must not bypass the snapshot). `TimelineDrillSnapshotResolver.forBooking`
      reads this snapshot for a QUICK-completed booking instead of the live plan; `SluCalculationService`'s
      own `onBookingCompleted` listener is pointed at the same snapshot so QUICK-session SLU and the
      timeline's "Drills" entry cannot disagree.
   5. Tests: `BookingCompletionServiceTest` gains cases for the new error path (sweeper- and
      admin-force-complete-then-resume), the sanitization call on both `submitWrapUp` and `updateRecap`
      (assert `ContactDetailSanitizer` is invoked, a contact-detail-bearing recap is redacted before save),
      and the LIVE-branch event using saved `scd` values. **`SessionPauseResumeServiceTest`'s direct
      `BookingCompletionService` construction also needs updating** for the new `ContactDetailSanitizer`
      dependency. `WrapUpSequenceSpec.js` gains a case for the new `errorKey` branch (navigation + prefill
      hand-off). A new or extended IT confirms the QUICK drill snapshot reflects the plan **as of wrap-up**
      (or as of the delayed-timeout completion), not as of a later edit, across all three QUICK-completion
      routes.

3. **Timeline SESSION_COMPLETED events carry the real session-end time, plan-note/prep lookups are
   booking-time-bounded, and `setPlayerRequest` uses the correct clock.**
   1. `TimelineEventListener.writeSessionCompleted`'s `insertSessionCompletedIfAbsent(...)` call (`:131`)
      stops passing wall-clock `Instant.now()` and instead passes the booking's `requestedEndTime` (load it
      via the booking if not already available to the caller). **Do not touch `saveTimelineEvent:281`** —
      it serves HOMEWORK_ASSIGNED and the generic RADAR_ASSESSMENT/PERFORMANCE_REPORT events, which have no
      booking association and for which wall-clock `Instant.now()` remains correct. A recovered/swept
      SESSION_COMPLETED event is now labelled and ordered by when the session actually happened, not by
      when the sweeper got to it. Add a test proving `CoachPlayerLastSessionResolver.isRecapAttributable`
      still returns the correct verdict now that its fast path fires less often (it will fall through to
      `findLastSessionBookingId` correlation in more cases than before — still correct per that method's own
      documented reasoning, but now the dominant path rather than a secondary one, so it needs its own
      explicit coverage). Existing rows are not backfilled; note in Completion Notes that timeline ordering
      briefly mixes processing-time and session-time stamps until they age out.
   2. `PlayerTimelineRepository.findLatestWhatsNextSuggestion` (~`:100-108`) gains a bound against the
      **prepped booking's own `requestedStartTime`**: only consider `whatsNext` suggestions from events whose
      referenced booking's `requestedStartTime` is before the prepped booking's `requestedStartTime` (a
      join/subquery against the bookings table via `reference_id`, not a naive `occurred_at <
      requestedStartTime` filter, which would wrongly drop back-to-back sessions). This item and 3.1 are one
      coordinated fix: 3.1 makes `occurredAt` trustworthy for display/ordering ties, 3.2 adds the booking-time
      bound that was always missing. If time allows, apply the same booking-time bound to
      `CoachPlayerLastSessionResolver`'s `lastWhatWeDid`/`lastWhatsNext` source (also currently unbounded
      against the prepped booking) so the prep card's two text sources cannot disagree about which session
      they describe; if deferred, say so explicitly and record it as a new ledger bullet rather than
      silently leaving it.
   3. `BookingService.rejectIfNotEditablePlayerRequest` (`:838-847`) keeps its existing two-step shape
      (status check → `BOOKING_NOT_EDITABLE`, then a time check → `SESSION_ALREADY_ENDED`) and its existing
      javadoc — only the time check's clock source changes, to
      `!booking.isPrepNoteEditable(Instant.now(ClockProvider.getClock()))`, matching `setCoachPlanNote`'s
      identical two-step pattern (`BookingService.java:801-807`) verbatim.
   4. Tests: `PlayerTimelineRepositoryTest`/IT (**new test class** — none exists today for
      `findLatestWhatsNextSuggestion`) proving a later-dated-but-earlier-booking-time suggestion is excluded
      (and vice versa); `BookingServiceTest` (building on `:1646-1763`) gains a case proving the two distinct
      error codes still fire correctly after the clock-source change; a
      `TimelineEventListenerTest`/`CoachPlayerLastSessionResolverTest` case for 3.1's fast-path-frequency
      shift.

4. **The coach-player workspace's radar save is never cancelled on unload, is applied in order on the
   server, and the coach-tier fetch degrades gracefully instead of hanging.**
   1. `CoachRadarCard.vue`'s `pagehide` flush (`flushPendingSave`, `:104-106`) sends its final save via
      `fetch(url, { method: 'PUT', keepalive: true, credentials: 'include', ... })` (a new function in
      `development.api.js`), not the normal axios PUT the browser may cancel. **`navigator.sendBeacon` is
      not viable** — the endpoint (`RadarDisplayResource.java:52`) is `@PutMapping`, and `sendBeacon` only
      sends `POST`.
   2. Radar-preference saves are **serialised on the client** so PUTs always reach the server in the order
      they were issued: each new save chains on the promise of the previous in-flight save (e.g. the
      `useDebounce`'s `fn` tracks and awaits its own prior call before issuing a new one) instead of firing
      independently from `setTimeout`. **A response-side `_radarSaveSeq` guard alone does not fix this** —
      `saveRadarPreferences` writes no local state on success, so the actual defect (the server applying an
      older PUT after a newer one) lives entirely in send-order, not response-handling order. If, after
      implementing client-side serialisation, a genuine reordering risk remains (e.g. network-level
      reordering of two still-independently-sent requests), fall back to a server-honoured sequence/version
      instead — state in Completion Notes which was needed.
   3. `fetchCoachTier` moves into the project's centralized `api/*.api.js` convention (it currently calls
      `api.get` directly inside `stores/auth.store.js:34-41`, bypassing that rule — dev's call whether the
      new function belongs in `auth.api.js` or `marketplace.api.js`, since the endpoint is
      `/api/marketplace/coaches/me/tier`) and gets a scoped `{ timeout: 20000 }`, following the shape of
      `api/booking.api.js`'s per-call timeouts. On timeout, `CoachPlayerDetailPage.vue`'s `tierSettled` gate
      (`:386-389`) still resolves instead of hanging forever — the existing `tierUnknown` indicator
      (`:329`, rendered at `:128`) already covers the "settled but unavailable" UI state, so no new UI is
      needed, only making `tierSettled` reachable. `ProfilePage.vue:597`'s independent call to
      `fetchCoachTier` benefits from the same fix with no extra work.
   4. New or extended Vitest specs: `CoachRadarCard.vue` (pagehide flush uses `fetch`/`keepalive`; two saves
      issued close together are sent to the network in order) — extend the existing
      `developmentStoreSpec.js`/`CoachPlayerDetailPageSpec.js` coverage rather than assuming a clean slate —
      and for `fetchCoachTier` (times out, resolves `tierSettled` regardless).

5. **CI's Spring-context-count gate cannot be fooled by a swallowed class-load failure or a mis-scoped log
   parse.**
   1. `IntegrationTestConventionTest.allConcreteItClasses()` (`:147-175`, the `Class.forName` try/catch at
      `:163-171`) collects `(className, Throwable)` pairs for any `Class.forName` failure instead of
      silently swallowing them, and a new assertion (in `integrationTestClasses()` or a sibling test method)
      fails the build if that collection is non-empty. Be aware a stale `target/` classes directory can
      produce a spurious local failure here — document that in the test's own failure message so it isn't
      mistaken for a real regression.
   2. `.github/scripts/assert-context-count.sh` scopes its single stats-line grep (`:194`) to the section of
      the log **after** the failsafe `integration-test` execution banner, using a pattern that matches the
      **real** Maven 3.9 short-prefix banner format —
      `[INFO] --- failsafe:3.5.6:integration-test (default) @ skillars ---` — not the full
      `maven-failsafe-plugin:...` name, which does not appear in actual CI output and would match nothing.
      Something like `sed -n '/--- (maven-)\?failsafe[^ ]*:integration-test/,$p' "$LOG" | grep ... | tail -1`
      (or an equivalent `awk`). Decide and document what happens if the banner pattern matches nothing at
      all (fail loudly, consistent with the script's existing philosophy at `:197-203`).
   3. Both changes are verified against a **crafted fixture log** reproducing a failsafe crash before its own
      stats line prints (the real current CI log does not exercise this bug today — its stats line already
      happens to come after the failsafe banner — so a real log alone would not prove the fix works), in
      addition to a read-through against a real `build.log` for the regex format itself.

6. **`isSweeperPlaceholder` no longer reads `voice_note_text` — step 1 of a THREE-release contract-migration
   sequence. No migration SQL ships in this story for this item.**
   1. `BookingCompletionService.isSweeperPlaceholder`'s (`:470-475`) placeholder test no longer includes
      `existing.getVoiceNoteText() == null` in its condition — placeholder detection relies solely on its
      other existing null-checks.
   2. The `SessionCompletionData` entity field, both GDPR scrub references
      (`SessionCompletionDataRepository.java:53,60`), and the `GdprErasureService.java:413` comment are all
      **left untouched** — they remain the documented safety net for legacy rows holding real coach-typed
      text until the column is actually dropped, two releases from now.
   3. A new ledger bullet records the **remaining two steps**, explicitly as separate releases (not one):
      **(2)** a future release removes the `SessionCompletionData` entity field and both GDPR scrub
      references — pure Java, no migration; **(3)** a further future release adds the actual
      `DROP COLUMN IF EXISTS voice_note_text` with `-- migration-lint: drop-prepared-in: V<n>` naming a
      migration version already on disk by then (the marker format is a Flyway version per
      `migration-conventions.md:63-64`, never a PR/merge reference). Compressing (2) and (3) into one release
      would drop the column while a prior release's pods still have it JPA-mapped, breaking them mid-rolling-
      deploy — do not compress these.

7. **Ledger hygiene and sprint status.**
   1. Delete all **17** bullets closed by code in this story — every row in the mapping table above. Leave
      the three dropped bullets (893, 3206, 3192) and the recap-attendance item (3208) untouched — none of
      these four were picked up. If a section ends up with no bullets, remove its heading.
   2. Add the new bullets this story generates: AC3.2's optional `CoachPlayerLastSessionResolver` follow-up
      (if deferred), AC6.3 (the two remaining contract-migration release steps), AC1.5's test-reset-listener
      note if not folded directly into AC1, plus any other narrower out-of-scope findings surfaced during
      implementation, all under a new `## Deferred from: skillars-deferred-163 (<date>)` heading.
   3. Set the `sprint-status.yaml` entry for this story to `review` when dev is complete.

## Tasks / Subtasks

- [x] Task 1: ConfigService hardening (AC: 1)
  - [x] Bounded `tryLock` + shared `(failure, failedAt)` state for must-be-fresh waiters; ensure the thrown
        type is never a `PessimisticLockingFailureException`; rewrite the affected `ConfigServiceTest` case
  - [x] `scheduledRefresh` → `tryLock()`-only, skip-if-busy
  - [x] `invalidate()` after-commit deferral, following `EmailRetryScheduler.dispatchAfterCommit`'s pattern
        (not `OutboxService`)
  - [x] Atomic `volatile` map swap in `load()`
  - [x] Fix `DatabaseResetTestExecutionListener.evictInProcessCaches` to `invalidate()` before
        `scheduledRefresh()`
  - [x] New concurrency tests; confirm old line-909 ledger note is superseded
- [x] Task 2: Recap sweeper/force-complete race, sanitization on both write paths, QUICK drill snapshot
      (AC: 2) — needs the story's one new migration
  - [x] New dedicated error key covering sweeper AND admin force-complete; `WrapUpSequence.vue` navigates to
        `CoachCommandCenterPage` with the typed text; `SessionRecapDialog.vue` prefill-after-load
  - [x] `ContactDetailSanitizer` injected into `BookingCompletionService`; sanitize in both `submitWrapUp`
        AND `updateRecap`; fix the LIVE branch's event-publish to use saved `scd` values
  - [x] New nullable migration column for the plan-drill snapshot; write it in `submitWrapUp`'s QUICK branch
        AND `QuickCompleteTimeoutService`'s delayed path; point `TimelineDrillSnapshotResolver` and
        `SluCalculationService.onBookingCompleted` at the snapshot
  - [x] Update `SessionPauseResumeServiceTest`'s direct `BookingCompletionService` construction
  - [x] Unit/IT/Vitest coverage
- [x] Task 3: Timeline timestamp (SESSION_COMPLETED writer only) + plan-note/prep bounding (AC: 3) —
      coordinate with Task 2's recap work; same files
  - [x] Fix only `writeSessionCompleted`'s `insertSessionCompletedIfAbsent` call to use session-end time
        (`requestedEndTime`) — do NOT touch `saveTimelineEvent`'s HOMEWORK_ASSIGNED/RADAR_ASSESSMENT/
        PERFORMANCE_REPORT stamps
  - [x] Booking-time-bounded `findLatestWhatsNextSuggestion`; new test class (none exists today)
  - [x] `setPlayerRequest`'s clock source fixed to `ClockProvider`, keeping its existing two-step
        status/time-check shape (copy `setCoachPlanNote`'s pattern, do not collapse into one
        `isPrepNoteEditable` boolean)
  - [x] Test for `CoachPlayerLastSessionResolver.isRecapAttributable`'s shifted fast-path frequency
  - [x] Tests for all of the above
- [x] Task 4: Frontend request robustness (AC: 4)
  - [x] `pagehide` flush via `fetch(..., { keepalive: true, credentials: 'include' })` — not `sendBeacon`
        (endpoint is PUT-only)
  - [x] Serialise radar-preference saves client-side (chain on prior in-flight save), not a response-side seq guard
  - [x] `fetchCoachTier` into `api/*.api.js` convention (dev's call on `auth.api.js` vs `marketplace.api.js`)
        with scoped timeout
  - [x] New/extended Vitest specs (`developmentStoreSpec.js`, `CoachPlayerDetailPageSpec.js`)
- [x] Task 5: CI script hardening (AC: 5)
  - [x] Convention test collects and asserts no class-load failures
  - [x] `assert-context-count.sh` regex matches the REAL short-prefix Maven banner
        (`failsafe:3.5.6:integration-test`), not `maven-failsafe-plugin:...`
  - [x] Verify against a crafted fixture log reproducing a pre-stats failsafe crash (the real current log
        does not exercise this bug)
- [x] Task 6: `voice_note_text` step 1 of 3 (AC: 6)
  - [x] Remove the field from `isSweeperPlaceholder`'s condition only
  - [x] Confirm scrubs and entity field untouched; record the follow-up ledger bullet naming BOTH remaining
        release steps separately (field/scrub removal, then the DROP)
- [x] Task 7: Ledger hygiene and sprint status (AC: 7) — 17 bullets; the three dropped items (893, 3206,
      3192) and the recap-attendance item (3208) stay untouched

### Review Findings

<!-- /bmad-code-review 2026-10-27: Blind Hunter + Edge Case Hunter + Acceptance Auditor, every finding re-verified by the orchestrator against the working tree. 2 decision-needed (both resolved by the owner → option (a), applied as patches), 9 patch, 2 defer, 17 dismissed. All 11 patches applied 2026-10-27. -->

- [x] [Review][Patch] (Decision resolved 2026-10-27 → option (a): snapshot the full `blocks` JSON at QUICK wrap-up and compute both SLU and the timeline drills from it) QUICK SLU vs timeline can still disagree (AC2.4 "cannot disagree") — `SluCalculationService` filters the LIVE plan's blocks by the snapshot id set (`SluCalculationService.java:111,119,148`); the timeline shows the snapshot ids themselves. A drill swapped out after wrap-up is listed on the timeline but earns zero SLU (the new IT's exact scenario), and `allocatedPerDrill` (`:146`) still divides by the live, unfiltered block size. Options: (a) snapshot the full `blocks` JSON (ids + order + per-block duration) and compute SLU from it — exact agreement; (b) accept the filter-only approach and amend AC2.4's wording; (c) defer. Blind+Edge+Auditor.
- [x] [Review][Patch] (Decision resolved 2026-10-27 → option (a): correlate the latest SLU booking by the referenced booking's `requested_end_time`, plus an A-confirmed-after-B test) AC3.1's session-end `occurred_at` breaks `CoachPlayerLastSessionResolver.isRecapAttributable` across bookings — older QUICK booking A confirmed after newer booking B completes: B's row now tops the timeline (by session end), but `sluDate`/`findLastSessionBookingId` (`SluRepository`, ordered by `calculated_at`) point at A, so B's own recap is nulled in prep/roster (`CoachPlayerLastSessionResolver.java:63-68`). Contradicts the story's "still functionally correct" claim; the new resolver test only covers the same-booking case. Options: (a) correlate by booking time (order the SLU booking lookup by the referenced booking's `requested_end_time`); (b) fold into the existing deferred resolver-bound ledger bullet; (c) accept. Edge.
- [x] [Review][Patch] Must-be-fresh waiter that ACQUIRES the lock ignores a just-recorded leader failure and runs its own `findAll()` (sequential Hikari timeouts AC1.1 forbids); add the AC1.6 "timed-out tryLock, no recent failure → one bounded attempt" test [ConfigService.java:492]
- [x] [Review][Patch] SESSION_COMPLETED `occurred_at` can be in the future for a session ended/quick-completed before `requestedEndTime` — clamp to `min(requestedEndTime, now)` [TimelineEventListener.java:116]
- [x] [Review][Patch] QUICK wrap-up with no plan / zero drills stores `null` (= "use live plan"), so drills added after wrap-up leak into timeline+SLU; store `[]` for any QUICK wrap-up so `null` means only LIVE/pre-V170 rows; also filter null `drillId`s at capture (one null poisons the whole parse) [BookingCompletionService.java:658]
- [x] [Review][Patch] `RECAP_STILL_EDITABLE_AFTER_FORCE_COMPLETE` is thrown without checking `recapEditability` — disputed/erased/window-closed bookings get "you can still save it" then a read-only dialog prefilled with unsaved text; check the verdict server-side and skip prefill when `!editable` [BookingCompletionService.java:185]
- [x] [Review][Patch] "completed automatically" wording is false for admin force-complete (AC2.1: must cover either cause) — server message + en/de/fr strings [BookingCompletionService.java:187]
- [x] [Review][Patch] Redirect into the recap dialog never refreshes schedule/missing-recaps if the coach closes it unsaved — the booking keeps offering the dead-end "resume wrap-up" [CoachCommandCenterPage.vue:898]
- [x] [Review][Patch] AC2.5 requires a sanitization test on `updateRecap`; only `submitWrapUp` is covered [BookingCompletionServiceTest.java:136]
- [x] [Review][Patch] AC1.6 behavioral "no cross-key mixed snapshot" test for the atomic map swap was not added [ConfigServiceTest.java]
- [x] [Review][Patch] AC3.1 requires a Completion Note that timeline ordering briefly mixes processing-time and session-time stamps until old rows age out (not backfilled) — missing [this story file, Completion Notes]
- [x] [Review][Defer] pagehide keepalive PUT bypasses the save chain — an older PUT already on the wire (or, after bfcache restore, still queued in `pendingSave`) can land after it [CoachRadarCard.vue:135] — deferred, inherent pagehide limit; no clean fix without server-side versioning
- [x] [Review][Defer] Recovered recap prefill is lost if the recap GET fails — the dialog has no retry and the wizard is already closed [SessionRecapDialog.vue:189] — deferred, pre-existing no-retry load error

## Dev Notes

- **Do not run `mvn verify` locally** (project rule); GitHub CI is the gate. Targeted unit tests and single
  ITs are fine.
- **Spring test-context gate:** do not add `@MockitoBean`/`@MockitoSpyBean`/`@TestPropertySource` to any IT
  for this story if it can be avoided — none of the 7 ACs above should need a new Spring context (ConfigService
  and migration-lint tests are plain unit tests). If a new context genuinely can't be avoided, read
  `.github/scripts/assert-context-count.sh`'s own comment history before touching the ceiling.
- **One new migration in this story**: AC2.4's plan-drill-snapshot column. The latest migration on disk at
  drafting time was `V169`.
- **IT writes:** bare `jdbcTemplate` writes in an IT method body never commit (Hikari `auto-commit=false`);
  wrap them in `transactionTemplate` if any new fixture needs direct inserts.
- **Booking fixture CHECK** `chk_bkg_end_after_start`: derive any new booking fixture's start and end from one
  `Instant.now()` call, not two separate calls (a known CI-only flake source).
- **Prettier gate** on frontend files (`.github/actions/`, not `workflows/`): run `npx prettier --check` on
  every touched `.vue`/`.js`/`.json` before pushing.
- **i18n:** any new user-facing error key (AC2.1/2.2) needs `en-US`, `de-DE`, `fr-FR` entries; **de-DE uses
  the formal register (`Sie`)**.
- **Re-verify every cited line against HEAD at dev time.** All citations above come from a research pass
  against HEAD 0f089e5b, independently re-verified again on 2026-10-26 after story review (see the "Corrected"
  notes throughout) — but code moves between drafting and dev start regardless.
- AC1–AC3 all touch adjacent areas of `booking`/`development` — read the current state of
  `TimelineEventListener`, `SessionPlanService`, and `BookingCompletionService` fully before starting, since
  AC2's QUICK snapshot, AC2's sanitization, and AC3's timestamp/clock fixes all touch overlapping code paths
  and, in AC2's case, the story's one new migration.
- **Three items were dropped by owner 2026-10-26, not deferred as a caveat** — `verification_status` CHECK,
  orphaned-upload cleanup, and `isBookingPlannable`'s UPCOMING acceptance. None of the three is in scope;
  do not reintroduce any of them under a different AC number.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` (bullets in the mapping table)
- `docs/deployment/migration-conventions.md` (rule 2: drop-last-and-guarded, three-release sequencing;
  `:63-64` drop-prepared-in marker format)
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java`
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/EmailRetryScheduler.java`
  (the real after-commit precedent — not `OutboxService.java`, which uses a different mechanism)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java`, `BookingService.java`
- `src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/TimelineEventListener.java`, `repo/PlayerTimelineRepository.java`
- `src/frontend/src/components/booking/{WrapUpSequence,SessionRecapDialog}.vue`,
  `components/development/CoachRadarCard.vue`
- `src/frontend/src/stores/{development,auth}.store.js`, `api/{development,booking,auth}.api.js`
- `src/test/java/com/softropic/skillars/config/DatabaseResetTestExecutionListener.java` (AC1.5's test-reset fix)
- `src/test/java/com/softropic/skillars/config/IntegrationTestConventionTest.java`
- `.github/scripts/assert-context-count.sh`, `.github/workflows/pr-build.yml`

## Dev Agent Record

### Agent Model Used

claude-sonnet-5 (Claude Code)

### Debug Log References

- Running `mvn verify` (or any `mvn` invocation touching `test`/`test-compile`) in two overlapping
  shells against the same `target/` directory corrupts class files mid-build and produces spurious
  cascading failures (`NoClassDefFoundError`, `ApplicationContext failure threshold exceeded` across
  unrelated `*WebMvcTest` slices). Hit this once mid-session; confirmed self-inflicted via a clean
  sequential `mvn clean compile test-compile`, which passed. No code defect — a process hygiene
  note for future sessions, not a finding about this story's changes.
- `mvn verify -DskipTests=true` skips BOTH surefire AND failsafe (they share the `skipTests` user
  property) — not useful for "unit tests off, ITs on". Used
  `mvn test-compile failsafe:integration-test failsafe:verify -Dit.test=<Class>` instead to run a
  single IT without the full unit suite.

### Completion Notes List

- **AC1 (ConfigService):** Implemented as specified. `MUST_BE_FRESH_LOCK_TIMEOUT` = 4000ms,
  `MUST_BE_FRESH_FAILURE_BACKOFF` = 15000ms (deliberately wider than the lock timeout, not equal —
  see `ConfigService.java`'s own field comments for why; these were dev's call, no specific values
  were mandated by the AC). New `ConfigRefreshException extends IllegalStateException` (package
  `platform.config.service`, not a `contract` exception — it is an internal load-failure signal,
  never an API-facing domain error). `mustBeFreshReaderParkedBehindAPreInvalidateLoad_reloadsAfterIt`
  rewritten per the task's own instruction (now asserts `TIMED_WAITING`). Added 2 new concurrency
  tests (failure-rethrow-without-redundant-findAll, scheduledRefresh non-blocking skip). Deliberately
  did NOT add a dedicated `load()` atomic-swap test needing reflection on the private `cache`
  field — Dev Notes explicitly sanctioned dropping it in favour of the existing behavioral tests,
  which already exercise the swap under concurrency.
- **AC2 (recap lifecycle):** New `BookingError.RECAP_STILL_EDITABLE_AFTER_FORCE_COMPLETE` /
  `booking.recapStillEditableAfterForceComplete` (en/de/fr added, de formal register). Sanitization:
  `submitWrapUp`'s `whatWeDid`/`whatsNext` are NOT pre-trimmed before `sanitizeAndTruncate` (matches
  this method's own pre-existing no-trim behavior, which no test depended on); `updateRecap`'s ARE
  pre-trimmed (`.trim()` before `sanitizeAndTruncate`) to preserve its own pre-existing trimming
  behavior that an existing test pins. QUICK drill snapshot: `BookingCompletionService` gained a new
  `SessionRepository` dependency and a `capturePlanDrillIdsSnapshot` helper that duplicates
  `TimelineDrillSnapshotResolver.forBooking`'s id-gathering logic (block order, dedup, CANCELLED
  exclusion) rather than sharing it — the resolver is package-private in a different package
  (`development.service`) and returns a name-snapshot shape this call site doesn't need yet (it
  only needs ids at capture time; names are resolved later, at read time, by whichever listener
  consumes the column). `SluCalculationService` is "pointed at the same snapshot" by RESTRICTING
  its drill-id/block iteration to the snapshot's id set for a QUICK completion (not by recomputing
  SLU from the snapshot directly — the snapshot only stores a flat id list, not per-block duration
  data, so exact SLU numbers still come from the live `session.getBlocks()`; only WHICH drills earn
  SLU is now pinned to the snapshot). This is a dev judgment call the story's own text leaves open
  ("SluCalculationService's own onBookingCompleted listener is pointed at the same snapshot" without
  specifying the mechanism) — flagging for story review in case a stricter reading was intended.
  `forceCompleteWithPlaceholderData` (the third of the three QUICK-completion routes to
  `BookingCompletedEvent`) is structurally unaffected by the snapshot: it sets `playerAttended =
  false`, and `TimelineEventListener.onBookingCompleted` only resolves drills when
  `event.isPlayerAttended()` — so this route can never reach `resolveDrills` either way. Verified
  via a new real-DB IT (`SessionCompletionResourceIT`, two new tests) that an edit to the live plan
  between wrap-up and the eventual `BookingCompletedEvent` does not change what the timeline shows,
  for both the `confirmCompletion` and `QuickCompleteTimeoutService` routes.
- **AC3 (timeline/prep):** `TimelineEventListener` gained a `BookingRepository` dependency to load
  `requestedEndTime`; falls back to `Instant.now()` only if the booking row cannot be found (should
  not happen in practice, defensive only). `findLatestWhatsNextSuggestion` uses a LEFT (not INNER)
  join against `booking.bookings`, with `b.requested_start_time IS NULL OR ...` in the bound — an
  existing test (`SessionPrepResourceIT.getPrep_suggestionIncludesUnattendedRecap_...`) inserts a
  SESSION_COMPLETED row with no `reference_id` at all (a test-only shortcut simulating an unattended
  recap) and expects it to still surface; an INNER join would have silently broken that test by
  excluding it. New booking-time-bound test added to `SessionPrepResourceIT` (reusing its existing
  Spring context) rather than a new `PlayerTimelineRepositoryTest`/IT class, per Dev Notes' explicit
  "none of the 7 ACs above should need a new Spring context" constraint — a native-SQL JOIN query
  cannot be meaningfully unit-tested with Mockito, and a new IT class would cost a new context.
- **AC3.2 (deferred, as the AC's own text permits):** did NOT also bound
  `CoachPlayerLastSessionResolver`'s `lastWhatWeDid`/`lastWhatsNext` source — recorded as a new
  ledger bullet in `deferred-work.md` per the AC's own "if deferred, say so explicitly" instruction.
- **AC4 (frontend robustness):** `putRadarPreferencesKeepAlive` added to `development.api.js`
  (bypasses the shared axios instance, as instructed). Save serialization is a simple promise chain
  (`pendingSave = pendingSave.catch(()=>{}).then(() => store.saveRadarPreferences(...))`), not a
  server-side sequence — matches the AC's "prefer (a) unless (a) still allows reordering somewhere
  not yet identified" and no such gap was found. The pagehide flush is diverted to the keepalive
  sender via a new `debounced.flushWith(altFn)`, deliberately NOT chained onto the serialization
  promise — awaiting a possibly-still-pending prior axios call during `pagehide` risked the keepalive
  never firing before the browser tears the page down; it is fire-and-forget by design. `fetchCoachTier`
  moved to `marketplace.api.js` (the endpoint's own module — `GET /api/marketplace/coaches/me/tier` —
  not `auth.api.js`), per the story's own explicit "dev's call" note.
- **AC5 (CI scripts):** `assert-context-count.sh`'s banner regex needed a `grep -- ` separator (the
  pattern's leading `---` is otherwise parsed as option flags, reproducible on BSD grep/macOS,
  possibly not on GNU grep/CI — fixed defensively either way). Verified manually against 4 crafted
  fixture logs (clean pass, pre-stats failsafe crash — confirmed the OLD unscoped grep would have
  silently read the surefire phase's stale `missCount` and reported "OK" through a real failsafe
  crash — no-banner-at-all, and a real captured CI-shaped log for the regex itself) since this repo
  has no shell-test harness for `.github/scripts/*.sh`; introducing one (e.g. bats) for a single
  script felt disproportionate. No automated test committed for this script as a result — flagging
  for story review in case a committed fixture-based check is wanted despite the missing harness.
- **AC6 (voice_note_text step 1 of 3):** as specified; steps 2/3 recorded in `deferred-work.md`.
- **AC7 (ledger hygiene):** all 17 bullets deleted (verified by exact-text grep returning zero
  matches post-edit); 4 section headings removed because their last bullet was the one picked up
  (code review of skillars-12-7, skillars-12-8, skillars-12-9, skillars-deferred-160); 2 new bullets
  added under `## Deferred from: skillars-deferred-163 (2026-10-26)` (the voice_note_text steps 2/3,
  and the deferred `CoachPlayerLastSessionResolver` bound). `AC1.5`'s test-reset-listener item needed
  no new bullet — it was fixed directly as part of AC1, not deferred.
- **Validation:** per `docs/testing/frontend-unit-tests.md` / this project's validation-strategy,
  `mvn verify` was never run. All targeted Java unit tests pass (258 across the 9 touched/added
  classes, run together in one pass); `MigrationConventionLintTest` (30) and `SchemaAssumptionsTest`
  (1) pass with the new V170 migration in place. Three real-DB ITs were run via
  `failsafe:integration-test failsafe:verify` (bypassing the full suite): `SessionPrepResourceIT`
  (19/19), `SluCalculationServiceIT` (17/17), `SessionCompletionResourceIT` (36/36, 2 new). Frontend:
  146 Vitest tests across 8 touched/added spec files pass; Prettier clean on every touched/added
  frontend file. GitHub CI remains the authoritative full-suite gate.

- **Code review 2026-10-27 (/bmad-code-review), all 11 patches applied:**
  - **AC2.4 superseded (Decision 1 → (a)):** the id-only snapshot + SLU filter flagged above is replaced. The
    column is now `plan_blocks_snapshot` (V170 edited in place, never committed) holding the full
    `List<SessionBlockData>` at QUICK wrap-up; `QuickPlanSnapshot` is the one reader, and BOTH
    `SluCalculationService` (durations included) and `TimelineEventListener` (via the new
    `TimelineDrillSnapshotResolver.forBlocks`) compute from it, so they cannot disagree. A QUICK wrap-up with
    no / CANCELLED plan snapshots `"[]"` (authoritative: nothing shown, nothing earned); `null` now means only
    LIVE or a pre-V170 row; an unreadable snapshot falls back to the live plan on BOTH readers; null drill ids
    are dropped at capture. Known residual: SLU still gates on the LIVE session's SAVED/COMPLETED status (the
    timeline does not) — pre-existing and shared with LIVE mode, documented on `forBooking`.
  - **AC3.1 regression fixed (Decision 2 → (a)):** the story's claim that `isRecapAttributable` stays
    "functionally correct" was wrong across bookings (older QUICK booking confirmed after a newer one nulled
    the newer one's recap). Attribution now compares session time on both sides (`sluEnd`, and
    `findLastSessionBookingId` ordered by booking end); the same-session fast path is again the common case.
  - `occurred_at` is capped at write time (`min(requestedEndTime, now)`); a session ended early is never
    future-stamped.
  - **Timeline ordering note (AC3.1's required Completion Note):** existing SESSION_COMPLETED rows are not
    backfilled, so timeline ordering briefly mixes processing-time (old rows) and session-time (new rows)
    stamps until the old rows age out.
  - ConfigService: a must-be-fresh waiter that ACQUIRES the lock right after the leader's load failed rethrows
    that failure instead of running its own `findAll()`; tests added for that, for the AC1.6 "timed-out wait,
    no recorded failure → another bounded attempt", and a behavioral no-gap reload test.
  - `RECAP_STILL_EDITABLE_AFTER_FORCE_COMPLETE` only when `recapEditability` says PUT /recap would accept;
    otherwise the verdict's own reason. The dialog skips the prefill when not editable. Wording no longer says
    "automatically" (server + en/de/fr). The page refreshes schedule + missing recaps on the redirect.
  - Verified locally: the 6 changed unit-test classes (234 tests) and the 4+3 affected Vitest files pass;
    the ConfigService waiter and resolver fixes were mutation-checked (each fix reverted → its new tests fail).
    ITs (incl. the new SLU/timeline-agreement IT in `SessionCompletionResourceIT`) are left to CI per the
    no-local-`mvn verify` rule.

### File List

**New:**
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigRefreshException.java`
- `src/main/resources/db/migration/V170__booking_session_completion_data_plan_blocks_snapshot.sql` (code review: renamed from `..._plan_drill_ids_snapshot.sql` before ever being committed — the column now holds the full blocks)
- `src/main/java/com/softropic/skillars/platform/development/service/QuickPlanSnapshot.java` (code review)
- `src/frontend/src/components/development/__tests__/CoachRadarCardSpec.js`

**Modified (main):**
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/SessionCompletionData.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerTimelineRepository.java`
- `src/main/java/com/softropic/skillars/platform/development/service/SessionPrepService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/SluCalculationService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/TimelineEventListener.java`
- `src/main/java/com/softropic/skillars/platform/development/service/TimelineDrillSnapshotResolver.java` (code review)
- `src/main/java/com/softropic/skillars/platform/development/service/CoachPlayerLastSessionResolver.java` (code review)
- `src/main/java/com/softropic/skillars/platform/development/repo/SluRepository.java` (code review)

**Modified (tests):**
- `src/test/java/com/softropic/skillars/config/DatabaseResetTestExecutionListener.java`
- `src/test/java/com/softropic/skillars/config/IntegrationTestConventionTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/SessionCompletionResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingCompletionServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/SessionPauseResumeServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/config/service/ConfigServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/development/api/SessionPrepResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/development/service/CoachPlayerLastSessionResolverTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/TimelineEventListenerTest.java`
- `src/test/java/com/softropic/skillars/platform/development/api/RosterQueryCountIT.java` (post-review CI fix: pinned budget)

**Modified (CI script):**
- `.github/scripts/assert-context-count.sh`

**Modified (frontend):**
- `src/frontend/src/api/development.api.js`
- `src/frontend/src/api/marketplace.api.js`
- `src/frontend/src/components/booking/SessionRecapDialog.vue`
- `src/frontend/src/components/booking/WrapUpSequence.vue`
- `src/frontend/src/components/booking/__tests__/SessionRecapDialogSpec.js`
- `src/frontend/src/components/booking/__tests__/WrapUpSequenceSpec.js`
- `src/frontend/src/components/development/CoachRadarCard.vue`
- `src/frontend/src/i18n/de-DE/index.js`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`
- `src/frontend/src/pages/coach/CoachCommandCenterPage.vue`
- `src/frontend/src/pages/coach/__tests__/CoachPlayerDetailPageSpec.js`
- `src/frontend/src/stores/auth.store.js`

**Modified (ledger/sprint tracking):**
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-10-26: Story implemented end-to-end (AC1-AC7). Status moved ready-for-dev → in-progress →
  review. See Completion Notes for dev judgment calls flagged for story review (SLU-snapshot
  mechanism, CI-script fixture verification without a committed test harness).
- 2026-10-27: Code review (/bmad-code-review): 2 decisions resolved (full-blocks QUICK snapshot; session-time
  recap attribution), 11 patches applied, 2 items deferred to deferred-work.md. Status review → done.
- 2026-10-27: GitHub CI caught a stale pinned statement-count budget in `RosterQueryCountIT` (expected 7, got 6
  for an attended player) that the session-time recap-attribution fix made correct but didn't update: with
  occurredAt now compared against sluEnd instead of calculated_at, the resolver's recap booking-id lookup no
  longer fires for that IT's seeded shape. Updated the pinned budget (7 → 6) and the comments explaining why.
  Also fixed a CoachRadarCardSpec ESLint no-unused-vars failure (`quasar build` ran ESLint on it) caught by the
  same CI run. No production code changed by either fix.
