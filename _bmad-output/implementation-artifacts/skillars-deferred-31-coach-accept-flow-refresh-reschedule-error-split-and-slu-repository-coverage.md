# Story Deferred-31: Coach Accept-Flow Refresh Normalisation, Zero-Accept Batch Integrity, Reschedule Error-Code Split, i18n Namespace Convention, Console PII & SLU Repository Coverage

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Skillars maintainer,
I want six small, independently-verified deferred items closed — three coach-side accept flows that
each behave differently after a failure and whose silent refresh failures are never surfaced, a batch
accept that reports "All sessions accepted" over zero accepted bookings, a reschedule path that
collapses nine distinct rejections into one authorization code, a payment-domain toast string stranded
in the booking i18n namespace, three `console.warn` calls that serialize a minor's id and free-text
notes into the browser console, and a repository predicate with zero real-database coverage — so that
each of six unrelated, previously-deferred defects, spanning the booking and development modules plus
the frontend i18n bundles, gets fixed without bundling any of them into a larger story that would need
its own design pass.

### Why this story exists

Drawn directly from `_bmad-output/implementation-artifacts/deferred-work.md`, per Mbah's direction to
group small, unrelated, already-deferred items into one story to reduce dev overhead — the same spirit
as `skillars-deferred-11/20/21/22/23/24/25/26/27/28/29/30`. All items below were independently
re-verified against **current** code during this story's creation (2026-08-18), by reading the throw
sites, catch blocks and store loaders directly rather than trusting the ledger's own citations.

Five of the six items were filed by `skillars-deferred-30` itself (at story creation, at its review, and
at its two code-review passes) with an explicit "file as a new ledger item, do not fix here" — this
story is where they land.

**Senior-dev review correction (2026-08-18):** a full audit of the draft (`story-review.md`) found that
the draft's own throw-site enumeration for AC3 — the thing this story's stated methodology exists to get
right — was itself incomplete. The draft claimed "nine sites, two genuine" and printed a ten-row table;
the file actually has **twelve** `MISSING_RIGHTS` throws across **three** public methods (3 genuine authz
+ 9 to re-code). Two real sites in `declineReschedule` (`:224` ownership, `:237` not-`PENDING`) were
absent from the table entirely, and the row labelled "(decline path) request is not `PENDING`" cited
`:165-166`, which is not the decline path at all — it is `acceptReschedule`'s *second*, locked-re-read
`PENDING` check. Re-verified against source before folding in: `requestReschedule` spans `:54-120`,
`acceptReschedule` `:123-217`, `declineReschedule` `:218-250`. The draft's error came from an enumeration
truncated before the end of the file; AC3's table below is now complete and carries a method column so
the same truncation cannot repeat. The review also found that `RescheduleResourceIT` asserts nothing on
these codes (grep-verified, zero hits), so Task 3 now requires adding that coverage rather than merely
"updating" assertions that do not exist, and that AC1's success-path stale-check had a concrete insertion
point for three of five handlers but none for `handleAccept`/`handleDecline` — Task 1 now names it.

**Three further corrections of substance came out of the story-creation re-verification, each re-checked
before being folded in below:**

- **One ledger item is factually wrong and is withdrawn rather than implemented.** The last item in the
  file — *"`CoachBookingRequestsPage.vue`'s `handleAccept` calls its post-failure refresh unguarded — a
  rejecting refresh throws an unhandled promise rejection out of the catch block"*
  (`CoachBookingRequestsPage.vue:164`) — rests on the premise that `loadCoachBookingRequests()` can
  reject. It cannot. `booking.store.js:302-314` wraps the whole call in
  `try { … } catch (e) { coachRequestsError.value = e }` with **no rethrow**, so the `await` at `:164`
  can never reject and no unhandled rejection is possible. The prescribed `.catch(() => {})` would be
  dead code. This is the **same false premise** that got a sibling item withdrawn by the
  `skillars-deferred-30` code review on the same day (see the `[WITHDRAWN …]` entry under
  *"Deferred from: skillars-deferred-30 story creation and review"*), reached independently by a
  different review pass a few hours later — the store's swallow-and-never-rethrow contract is evidently
  not obvious from the call sites. AC7 withdraws the item **and** adds the one-line comment at the store
  loaders that would have prevented both filings. The identical unguarded pattern in `handleDecline`
  (`:176`) is harmless for the same reason and is likewise not "fixed".

- **The real residual behind both withdrawn items is live and is what AC1 actually closes.** A failed
  post-mutation refresh is *silent*: `coachRequestsError` and `coachScheduleError` are set by the
  loaders, exported by the store (`booking.store.js:577,604`), and **rendered by no component at all**
  — verified by grepping every `.vue` and `.js` file under `src/frontend/src`: the only error ref with a
  consumer is `bookingsError` (`ParentBookingsPage.vue:10,19`,
  `ParentDashboardPlaceholderPage.vue:53`). So a coach can see "Session accepted", have the refresh fail,
  and read a stale list indefinitely with no signal.

- **AC3's scope is wider than its ledger item.** The item names only
  `ParentBookingsPage.submitReschedule()` (the parent request path). Re-enumerating **every**
  `SecurityError.MISSING_RIGHTS` throw in `RescheduleService` — all twelve, across all three public
  methods — found the coach `acceptReschedule` path (`:139`, `:143`, `:147`, `:166`) and the coach
  `declineReschedule` path (`:237`) carry the same defect, surfaced by
  `CoachCommandCenterPage.handleAcceptReschedule` / `handleDeclineReschedule` — a file AC1 already opens.
  Splitting only the parent half would earn this story the exact criticism `skillars-deferred-30` earned
  for leaving `submitBatchRequest()`'s `MISSING_RIGHTS` unmapped thirty lines below the chain it fixed.
  AC3 covers all three methods.

A full read of `deferred-work.md` (all 1519 lines, at story-creation time) was performed before selecting
these six, focused on the most recent, least-mined sections — the code reviews of
`skillars-deferred-26` through `skillars-deferred-30` (2026-08-15 → 2026-08-18) — since every older
section has been swept by multiple prior `skillars-deferred-N` stories. The following open items in
those same sections were deliberately excluded as too large, blocked, or needing a decision this story's
bundled-fix bar does not cover — not omitted by oversight:

- **`DrillMetadata.repDensity` is a primitive `int` and cannot represent "unset"** (deferred-26 story
  creation, D1) — needs a backend contract change (nullable `Integer` through the JSONB mapping and every
  arithmetic read site) **plus a product decision** on whether "no density data" is a real state.
- **`SessionErrorCode.DRILL_UPLOAD_NOT_ALLOWED` is thrown by three unrelated conditions** (deferred-30
  story creation) — a backend contract change, and, as that item itself records, the ownership-check path
  is **not reachable from the drill panel today**, so there is no user-visible defect to close. Splitting
  the code is correct but is its own contract story.
- **`booking.errors.batchSizeExceeded`'s wrong `{max}` figure** — the item's own text states the fix is a
  backend contract change (`BatchRuleViolationException` must carry the real limit as a message
  argument). Already excluded by `skillars-deferred-29` and `-30` for the same reason.
- **`'QUOTA_EXCEEDED'` cannot distinguish a transient rate limit from a hard storage quota** — needs a
  backend code split, and is already partly mitigated by `skillars-deferred-29`'s toast rewording.
- **Two independent refund-eligibility computations can disagree on the same booking**, and **the
  product question about a post-start parent cancellation settling as a coach no-show** (deferred-28
  story creation) — both are explicitly product decisions, not mechanical fixes.
- **Every ledger item marked "needs sign-off", "product decision", or targeting a currently
  unreachable / already-mitigated code path** — none are small, independently-safe, mechanical fixes.

## Deferred Items Closed

| Source | Item | Current location (re-verified 2026-08-18) | AC | Outcome |
|---|---|---|---|---|
| code review of `skillars-deferred-30-...` (2026-08-18) | The three coach-side accept flows have three different post-failure refresh behaviours, and one toasts before the state it describes | `CoachBookingRequestsPage.vue:151-205`, `CoachCommandCenterPage.vue:372-390` | 1 | `[CLOSED by skillars-deferred-31 AC1]` refresh-then-toast applied to all **five** coach handlers (both decline handlers included) |
| `skillars-deferred-30` story creation/review (2026-08-18), `[WITHDRAWN]` residual | A failed post-mutation refresh is silent — `coachRequestsError` / `coachScheduleError` are set and rendered by nothing | `booking.store.js:120,138,302-328,577,604` | 1 | `[CLOSED by skillars-deferred-31 AC1]` new `booking.errors.listMayBeStale` warning on both success and failure paths of all five handlers |
| `skillars-deferred-30` story creation (2026-08-18) | `BookingBatchService.acceptAll` returns HTTP 200 with a positive "All sessions accepted" toast when zero bookings were accepted | `BookingBatchService.java:281-284`, `CoachBookingRequestsPage.vue:186` | 2 | `[CLOSED by skillars-deferred-31 AC2]` 403 `booking.batchNoneAccepted`; per-booking detail re-filed |
| code review of `skillars-deferred-29-...` (2026-08-17) | `ParentBookingsPage.submitReschedule()`'s `else` branch swallows `MISSING_RIGHTS`, which covers four distinct non-authorization reschedule rejections | `RescheduleService.java:58-97`, `ParentBookingsPage.vue:210-217` | 3 | `[CLOSED by skillars-deferred-31 AC3]` 9 of 12 throws re-coded, 3 authz kept; 3 frontend chains mapped; 2 mutation-verified ITs added |
| code review of `skillars-deferred-30-...` (2026-08-18) | Four `payment.*` wire codes resolve into three different i18n namespaces, leaving no rule for the next author | `BookingRequestPage.vue:489-498`, `i18n/*/index.js` | 4 | `[CLOSED by skillars-deferred-31 AC4]` convention adopted + written down; `packExpired` moved to `payment.sessionPack.*` in all 3 bundles |
| `skillars-deferred-30` story creation (2026-08-18) | `SluWeeklySnapshotRepository.findByPlayerIdFromWeek`'s compound `(year, week)` range predicate has zero repository-level test coverage at any date | `SluWeeklySnapshotRepository.java:30-38` | 5 | `[CLOSED by skillars-deferred-31 AC5]` new `SluWeeklySnapshotRepositoryIT`, 5 tests, mutation-verified 4 ways |
| code review of `skillars-deferred-29-...` (2026-08-17) | Three `console.warn(..., err)` calls serialize a minor's `playerId` and free-text `notes` into the browser console | `BookingRequestPage.vue:509,555`, `ParentBookingsPage.vue:214` | 6 | `[CLOSED by skillars-deferred-31 AC6]` `err` dropped at all three sites; warnings kept |

**Explicitly NOT in this story** (considered during story creation and rejected — do not implement):

- **`CoachBookingRequestsPage.vue:164`'s "unguarded refresh → unhandled promise rejection"** — the
  premise is false (see "Why this story exists"). AC7 withdraws it with evidence. **Do not add
  `.catch(() => {})` anywhere**; it would be dead code and a future reader would have to re-derive why.
- **Per-booking outcome reporting from `acceptAll`** (which bookings failed and why) — AC2 deliberately
  closes only the false-success defect. Returning a per-booking result DTO changes the REST contract and
  the store/page rendering; AC7 files it as a new ledger item.
- **Rendering `coachRequestsError` / `coachScheduleError` as full error banners** — AC1 adds a
  *stale-data* notification on the post-mutation refresh path only. A general error-banner treatment for
  every load path on both coach pages is a UX task, not a bundled fix.
- **Splitting `MISSING_RIGHTS` anywhere outside `RescheduleService`** — AC3 is scoped to that one class.
  `BookingService` and `BookingBatchService` were already split by `skillars-deferred-30`.
- **All other open ledger items** — every one inspected during this story's creation either needed a
  product/design decision, targeted an unreachable/already-mitigated code path, or duplicated a fix a
  prior story already made.

## Acceptance Criteria

1. **The three coach-side accept flows behave three different ways after a failure, one of them toasts
   before the state it describes, and a failed post-mutation refresh is invisible to the coach. Pick one
   behaviour and apply it to all three.**

   Current, verified state:
   - `CoachBookingRequestsPage.handleAccept` (`:151-168`) — notifies, **then** `await`s
     `loadCoachBookingRequests()` (`:164`). On a `booking.slotUnavailable` rejection the coach reads
     "This time slot is no longer available" while the row is still rendered as pending; a second click
     on the still-visible row fires another doomed request.
   - `CoachBookingRequestsPage.handleAcceptAll` (`:182-205`) — no refresh after a failure at all; the
     list stays stale indefinitely.
   - `CoachCommandCenterPage.handleAcceptReschedule` (`:372-390`) — no refresh after a failure at all.

   **Required behaviour, identical in all three:** inside the `catch`, `await` the refresh **first**, then
   show the error toast, so the message describes state the coach can already see. `handleDecline`
   (`:170-180`) and `handleDeclineReschedule` (`:392+`) get the same ordering — they are the same flow
   from the coach's point of view and leaving them inconsistent recreates the defect one story later.

   **Plus the silent-refresh gap:** `loadCoachBookingRequests` and `loadCoachSchedule`
   (`booking.store.js:302-328`) swallow their own errors into `coachRequestsError` / `coachScheduleError`,
   which **no component renders** (verified by grep across `src/frontend/src`; only `bookingsError` has
   consumers). After every post-mutation refresh — success path and failure path — check the
   corresponding error ref and, if it is non-null, show one additional `type: 'warning'` notification
   using a new i18n key `booking.errors.listMayBeStale` ("This list may be out of date. Reload the page
   to see the latest."). This is the residual both withdrawn items were circling.

   **Do NOT** wrap the refresh calls in `.catch(() => {})` — see "Explicitly NOT in this story".

2. **`BookingBatchService.acceptAll` reports success over zero accepted bookings.**
   `BookingBatchService.java:281-284` reads:

   ```java
   if (acceptedIds.isEmpty()) {
       log.warn("No bookings were accepted in batch {}", batchId);
       return;
   }
   ```

   A bare `return` from a `void` method is an HTTP 2xx, so `handleAcceptAllBatch` resolves and
   `CoachBookingRequestsPage.vue:186` fires `t('booking.batch.acceptedAll')` — "All sessions accepted" —
   having accepted nothing. The batch row stays `PENDING` and every booking stays `REQUESTED`. Two
   distinct paths reach this branch: (a) every per-booking `acceptOneBooking` threw and was swallowed by
   the loop's `catch` (`:274-277`), and (b) `findByBatchIdAndStatus(batchId, "REQUESTED")` returned empty
   on a still-`PENDING` batch.

   **Required:** replace the silent `return` with
   `throw new OperationNotAllowedException("No bookings in batch were accepted", BookingError.BATCH_NONE_ACCEPTED)`
   using a new enum constant `BATCH_NONE_ACCEPTED` → wire code `booking.batchNoneAccepted`, and map it in
   `handleAcceptAll`'s `errorKey` chain to a new `booking.errors.batchNoneAccepted` key. Keep the existing
   `log.warn` — it is the only per-booking diagnostic that survives the loop's swallow. Add a code comment
   naming both reaching paths so the next reader does not assume it means only "all failed".

   `OperationNotAllowedException` maps to **403 regardless of error code** (established by
   `skillars-deferred-30`'s split), so the blast radius is the toast text only — no status-code change.
   Per-booking failure detail is explicitly out of scope (AC7 files it).

3. **`RescheduleService` throws `SecurityError.MISSING_RIGHTS` at twelve sites, only three of which are
   genuine authorization failures — and both frontend chains collapse the rest into one message.**

   Verified throw sites and required re-coding. **All twelve are listed; the method column matters —
   `acceptReschedule` checks `PENDING` twice (an unlocked early-out and a locked re-read) and
   `declineReschedule` is a separate method further down the file, easy to miss when scanning:**

   | Line | Method | Condition | Required code |
   |---|---|---|---|
   | `:58` | `requestReschedule` | Parent does not own this booking | **keep** `MISSING_RIGHTS` (genuine authz) |
   | `:62` | `requestReschedule` | Booking is not `CONFIRMED`/`UPCOMING` | new `BOOKING_NOT_RESCHEDULABLE` |
   | `:66` | `requestReschedule` | Proposed start time not in the future | existing `START_TIME_IN_PAST` |
   | `:70` | `requestReschedule` | Proposed end time not after start | existing `INVALID_TIME_RANGE` |
   | `:97` | `requestReschedule` | A pending reschedule request already exists | new `RESCHEDULE_ALREADY_PENDING` |
   | `:129` | `acceptReschedule` | Coach does not own this booking | **keep** `MISSING_RIGHTS` (genuine authz) |
   | `:139` | `acceptReschedule` | Request not `PENDING` — **unlocked early-out** | new `RESCHEDULE_NOT_PENDING` |
   | `:143` | `acceptReschedule` | Booking no longer in a reschedulable state | new `BOOKING_NOT_RESCHEDULABLE` |
   | `:147` | `acceptReschedule` | Proposed start time no longer in the future | existing `START_TIME_IN_PAST` |
   | `:166` | `acceptReschedule` | Request not `PENDING` — **locked re-read** (`findByIdForUpdate` + `entityManager.refresh`, the Deferred-14 race guard) | new `RESCHEDULE_NOT_PENDING` |
   | `:224` | `declineReschedule` | Coach does not own this booking | **keep** `MISSING_RIGHTS` (genuine authz — third site) |
   | `:237` | `declineReschedule` | Request is not `PENDING` | new `RESCHEDULE_NOT_PENDING` |

   That is **3 keep + 9 re-code**. `:166` and `:237` both map to `RESCHEDULE_NOT_PENDING` but are in
   different methods and both must be changed — recoding only `:166` leaves the decline path, which the
   frontend half of this AC explicitly maps, still throwing `MISSING_RIGHTS`.

   `:88-92` already throws `BookingError.INVALID_SESSION_DURATION` and `:186-198` already throw
   `COACH_UNAVAILABLE` / `SLOT_UNAVAILABLE` — leave all three alone.

   **Frontend mapping:**
   - `ParentBookingsPage.submitReschedule()` (`:210-217`) currently maps only
     `booking.invalidSessionDuration`. Add branches for `booking.notReschedulable`,
     `booking.startTimeInPast`, `booking.invalidTimeRange`, `booking.rescheduleAlreadyPending`, and
     `MISSING_RIGHTS` (→ existing `booking.errors.requestNotAllowed`, authorization-worded, no retry
     advice — same treatment `skillars-deferred-30` applied to `submit()`).
   - `CoachCommandCenterPage.handleAcceptReschedule` (`:378-386`) currently maps
     `booking.coachUnavailable` and `booking.slotUnavailable`. Add `booking.rescheduleNotPending`,
     `booking.notReschedulable`, `booking.startTimeInPast`, and `MISSING_RIGHTS`.
   - `handleDeclineReschedule` uses a bare `catch {}` with no `errorKey` read at all. Give it the
     `errorKey` destructure and a `booking.rescheduleNotPending` branch; everything else keeps the
     existing generic decline message.

   This is a **wire contract change** — same class and same small blast radius as
   `skillars-deferred-30`'s split, for the same reason (403 either way).

4. **Four `payment.*` wire codes resolve into three different i18n namespaces, leaving no rule.**
   `BookingRequestPage.vue:489-498` maps `payment.coachStripeNotConfigured` → `payment.error.*`,
   `payment.packExpired` → **`booking.errors.*`**, and `payment.packCoachMismatch` /
   `payment.packExhausted` → `payment.sessionPack.*`.

   **Required convention (apply it, then write it down):** *a toast key lives in the namespace of the
   wire code's domain prefix, not the page that renders it.* Under that rule the only misplaced key is
   `booking.errors.packExpired` (`i18n/en-US/index.js:924` and its `de-DE` / `fr-FR` siblings). Move it to
   `payment.sessionPack.packExpired`, beside `packCoachMismatch` / `packExhausted` (`:1068-1069`), in all
   three bundles, and update the single call site. `payment.error.coachStripeNotConfigured` (`:1046`)
   already satisfies the rule and stays.

   Before deleting the old key, grep all three bundles **and** all of `src/frontend/src` for
   `packExpired` and confirm `BookingRequestPage.vue:493` is the only consumer — a leftover reference to
   a removed key renders the raw key string to a parent, which is worse than the namespace inconsistency
   being fixed. Record the convention as a comment above the `errorKey` chain in `BookingRequestPage.vue`,
   where the next author adding a branch will actually read it.

   **Behaviour must not change** — same English/German/French strings, same toast, different key path.

5. **`SluWeeklySnapshotRepository.findByPlayerIdFromWeek`'s compound `(year, week)` range predicate has
   zero repository-level coverage at any date.** The JPQL (`SluWeeklySnapshotRepository.java:30-38`)
   encodes an ISO week-based-year range as two three-term boolean clauses:

   ```
   (isoYear > :fromYear OR (isoYear = :fromYear AND isoWeek >= :fromWeek))
   AND (isoYear < :toYear OR (isoYear = :toYear AND isoWeek <= :toWeek))
   ORDER BY isoYear ASC, isoWeek ASC
   ```

   Its only two production callers are `SluDashboardService.java:49` and `SluNarrativeService.java:45`;
   its only test appearances are **mocked stubs** in `SluDashboardServiceTest` (`:81,123,141`), which
   assert the arguments passed but never execute the query. Nothing has ever run it against Postgres.

   **Required:** add `src/test/java/com/softropic/skillars/platform/development/repo/SluWeeklySnapshotRepositoryIT.java`
   extending `AbstractIntegrationTest`, seeding rows that make each half of the predicate load-bearing:
   - a row **before** the window in the same year (excluded by `isoWeek >= :fromWeek`)
   - a row **at** `fromWeek` (included — lower boundary is inclusive)
   - a row **after** the window in the same year (excluded by `isoWeek <= :toWeek`)
   - a row **at** `toWeek` (included — upper boundary is inclusive)
   - a **year-rollover window** (e.g. `from = (2025, 52)`, `to = (2026, 2)`) with rows at `(2025, 51)`
     excluded, `(2025, 52)`, `(2026, 1)`, `(2026, 2)` included and `(2026, 3)` excluded — this is the
     case the two `isoYear >`/`isoYear <` disjuncts exist for and the one a naive
     `week BETWEEN from AND to` would get wrong
   - a row for a **different `playerId`** inside the window, excluded — the `s.id.playerId = :playerId`
     predicate is as unproven as the rest
   - assert the returned order is ascending by `(isoYear, isoWeek)`

   **Mutation-verify before marking done:** delete one disjunct from the JPQL (e.g.
   `s.id.isoYear > :fromYear OR`), confirm the new test **fails**, then revert. A test that passes against
   a broken predicate is worth nothing, and this exact class of self-satisfying test is what
   `skillars-deferred-30` AC5 was written to remove.

   Fixture ids: claim block **`9630000001`–`9630000003`** (`9630` is listed as free in
   `docs/testing/test-data-isolation.md`) and **add the row to that file's registry table and its
   claimed-prefixes list, and remove `9630` from the free-blocks list** — all three edits, or the file
   contradicts itself exactly the way `skillars-deferred-30` AC6 just fixed.

   `player_slu_weekly_snapshot.player_id` has **no foreign key** (`V48__development_exposure_dashboard.sql:5-12`),
   so no `main."user"` seed is needed. `skill_code` **does** FK to `development.skill_definitions(code)` —
   use codes seeded by V46 (`PAC`, `SHO`).

6. **Three `console.warn` calls serialize a minor's `playerId` and free-text `notes` into the browser
   console.** `BookingRequestPage.vue:509`, `:555` and `ParentBookingsPage.vue:214` all read
   `console.warn('[booking] unmapped errorKey:', errorKey, err)`. `boot/axios.js:176` rejects with the
   **original** axios error, so `err` carries `err.config.data` — the serialized request body, which on
   these paths contains `playerId`, `notes` and the full slot list. These fire on every unmapped
   rejection, including ordinary 403s.

   **Required:** log the diagnostic value only — `console.warn('[booking] unmapped errorKey:', errorKey)`
   — at all three sites. `errorKey` is a wire constant and carries nothing personal. The `helpCode`
   (`err?.response?.data?.helpCode`, the SQIDS-encoded support id) may be added alongside it if useful for
   support correlation; the raw `err` object may not. Do not remove the warnings — they are the
   `skillars-deferred-28` fix that makes an unmapped code visible at all.

7. **Ledger hygiene.** Annotate each of the six items in the **Deferred Items Closed** table above with
   `[CLOSED by skillars-deferred-31 ACn]` plus a one-line description of what actually shipped, in
   `deferred-work.md`. Additionally:
   - **Withdraw** the `CoachBookingRequestsPage.vue:164` unguarded-refresh item with
     `[WITHDRAWN by skillars-deferred-31 — the described defect cannot occur]` and the evidence
     (`booking.store.js:302-314` catches without rethrow; the same false premise withdrawn by the
     `skillars-deferred-30` review). State that the identical pattern at `handleDecline` (`:176`) is
     harmless for the same reason.
   - Add a one-line comment above `loadCoachBookingRequests` / `loadCoachSchedule` in `booking.store.js`
     recording that these loaders **never rethrow** and that callers therefore need no guard — two
     independent reviewers have now filed the same false defect against this contract.
   - **File as new items:** (a) `acceptAll` still cannot tell the coach *which* bookings failed or why —
     needs a per-booking result DTO and a REST contract change; (b) any further gap this story's
     implementation surfaces. Do not re-file anything already present.

## Tasks / Subtasks

- [x] **Task 1 — AC1: normalise coach accept/decline refresh + surface stale lists**
  - [x] Add `booking.errors.listMayBeStale` to `i18n/en-US`, `de-DE`, `fr-FR`
  - [x] `CoachBookingRequestsPage.handleAccept` — move `await loadCoachBookingRequests()` above the
        toast; after it, if `bookingStore.coachRequestsError` is non-null, add the stale warning
  - [x] `CoachBookingRequestsPage.handleDecline` — same ordering + stale check
  - [x] `CoachBookingRequestsPage.handleAcceptAll` — add the refresh (it has none) + stale check
  - [x] `CoachCommandCenterPage.handleAcceptReschedule` / `handleDeclineReschedule` — add the
        post-failure `loadCoachSchedule(selectedWeek.value)` + `coachScheduleError` stale check; keep the
        existing success-path refresh
  - [x] Add the stale check on the **success** paths too — a silent refresh failure after a *successful*
        accept is the more likely case. Insertion points differ per handler and are **not** symmetric:
    - [x] `handleAccept` / `handleDecline` have **no success-path code at all** today — nothing sits
          between the successful `await bookingStore.approveBooking(id)` / `rejectBooking(id)` and the
          `finally`. Their success refresh happens one level down, inside the store
          (`booking.store.js:348-356`, `approveBooking` = `acceptBooking(id)` then
          `loadCoachBookingRequests()`), so the refresh has already run by the time the `await` resolves.
          Add the `if (bookingStore.coachRequestsError) { … }` check as a new statement directly after
          that `await`, inside the `try`
    - [x] `handleAcceptAll`, `handleAcceptReschedule` and `handleDeclineReschedule` already have an
          explicit success-path refresh and/or notify block (e.g. `CoachCommandCenterPage.vue:375-377`)
          — attach the check there, after the refresh and before the positive toast
  - [x] `npx eslint` clean on every changed file
- [x] **Task 2 — AC2: `acceptAll` zero-accept**
  - [x] `BookingError`: add `BATCH_NONE_ACCEPTED` + `booking.batchNoneAccepted` in `getErrorCode()`
  - [x] `booking.batchNoneAccepted` in all four `messages*.properties`
  - [x] `BookingBatchService.java:281-284` — throw instead of `return`, keep the `log.warn`, comment both
        reaching paths
  - [x] `booking.errors.batchNoneAccepted` in all three frontend bundles + branch in `handleAcceptAll`
  - [x] Test: a `PENDING` batch whose every booking fails to accept returns 403 `booking.batchNoneAccepted`
        and leaves the batch `PENDING` — **mutation-verify** by reverting the throw and confirming failure
- [x] **Task 3 — AC3: `RescheduleService` `MISSING_RIGHTS` split**
  - [x] `BookingError`: add `BOOKING_NOT_RESCHEDULABLE`, `RESCHEDULE_ALREADY_PENDING`,
        `RESCHEDULE_NOT_PENDING` + wire codes
  - [x] All three keys in all four `messages*.properties`
  - [x] Re-code the **nine** non-authz throw sites per AC3's table; **leave `:58`, `:129` and `:224` as
        `MISSING_RIGHTS`**
  - [x] Confirm by grep that `RescheduleService` has exactly **three** remaining `MISSING_RIGHTS`
        occurrences when done — the file has twelve today and the count is the cheapest check that no
        site was missed, particularly `declineReschedule` (`:218-250`), which sits far below the two
        methods most of this AC's line citations point at
  - [x] Frontend keys in all three bundles; branches in `ParentBookingsPage.submitReschedule`,
        `CoachCommandCenterPage.handleAcceptReschedule`, `handleDeclineReschedule`
  - [x] **`RescheduleResourceIT` asserts nothing on `errorKey` or `MISSING_RIGHTS` today** (grep-verified
        — zero hits), so this AC ships with no regression net unless one is added. Add at least two
        assertions: one parent-path rejection (e.g. non-`CONFIRMED` booking → `booking.notReschedulable`)
        and one decline-path rejection (already-declined request → `booking.rescheduleNotPending`,
        covering the `:237` site the line citations are most likely to miss). Mutation-verify both by
        reverting the corresponding throw to `MISSING_RIGHTS` and confirming failure
- [x] **Task 4 — AC4: i18n namespace convention**
  - [x] Grep `packExpired` across all three bundles and all of `src/frontend/src`; confirm the single
        consumer before touching anything
  - [x] Move `booking.errors.packExpired` → `payment.sessionPack.packExpired` in all three bundles,
        same strings
  - [x] Update `BookingRequestPage.vue:493`; add the convention comment above the `errorKey` chain
  - [x] Re-grep for the old key path — zero hits
- [x] **Task 5 — AC5: `SluWeeklySnapshotRepositoryIT`**
  - [x] New IT extending `AbstractIntegrationTest`, ids `9630000001`–`9630000003`, `skill_code` from V46
  - [x] Boundary rows, year-rollover window, other-player exclusion, ascending-order assertion
  - [x] **Mutation-verify:** delete one JPQL disjunct → test fails → revert
  - [x] `docs/testing/test-data-isolation.md`: registry row **+** claimed-prefixes list **+** remove
        `9630` from free blocks
- [x] **Task 6 — AC6: console PII**
  - [x] Drop `err` from the three `console.warn` calls (`BookingRequestPage.vue:509,555`,
        `ParentBookingsPage.vue:214`); keep the warnings themselves
- [x] **Task 7 — AC7: ledger hygiene**
  - [x] Six `[CLOSED by skillars-deferred-31 ACn]` annotations with what shipped
  - [x] `[WITHDRAWN …]` annotation with the `booking.store.js:302-314` evidence
  - [x] Never-rethrow comment on the two store loaders
  - [x] File the `acceptAll` per-booking-detail item; do not re-file existing items
- [x] **Task 8 — verification**
  - [x] Full `mvn -o verify` green (unit + IT, 0 failures, 0 errors) — surefire 890 (0F/0E/1 skipped),
        failsafe 923 (0F/0E/4 skipped), `BUILD SUCCESS`
  - [x] `npx eslint` exit 0 on every changed frontend file; `npx quasar build` succeeds (ran as part of
        `mvn -o verify`'s `frontend:2.0.2:npx (npx quasar build)` execution)
  - [x] Every new i18n key resolves in **all three** bundles — verified by importing each bundle and
        resolving all 9 affected key paths (`booking.errors.listMayBeStale`, `batchNoneAccepted`,
        `notReschedulable`, `rescheduleAlreadyPending`, `rescheduleNotPending`, `requestNotAllowed`,
        `startTimeInPast`, `invalidTimeRange`, `payment.sessionPack.packExpired`); also asserted
        `booking.errors.packExpired` now resolves to `undefined` in all three
  - [ ] Manual toast verification is **not** possible in this environment (no browser tooling — standing
        project-wide gap). Leave those subtasks unchecked and flag for human spot-check rather than
        marking them done. **NOT DONE — flagged for human spot-check** (see Completion Notes)

- [x] **Task 9 — [AI-Review] review follow-ups (3 Patch findings; 2 Defer findings acknowledged, no action)**
  - [x] [AI-Review][Patch] Cross-call race in the shared `coachRequestsError`/`coachScheduleError`
        refs read by the AC1 stale-check helpers — `loadCoachBookingRequests`/`loadCoachSchedule` now
        **return their own outcome** (`true` refreshed / `false` failed), propagated through
        `approveBooking`, `rejectBooking` and `handleAcceptAllBatch`; both page helpers take that
        value as a parameter instead of re-reading the module-scoped ref. All 10 call sites converted.
        The refs themselves are unchanged and still exported — they carry the error object a future
        error-banner treatment will want.
  - [x] [AI-Review][Patch] Dead `UNUSED_PLAYER_ID` fixture constant removed from
        `SluWeeklySnapshotRepositoryIT`; the claimed block in `docs/testing/test-data-isolation.md`
        narrowed from `9630000001`–`9630000003` to `9630000001`–`9630000002` to match what is
        actually seeded
  - [x] [AI-Review][Patch] AC3 closure note in `deferred-work.md` reworded — "(5 new + `MISSING_RIGHTS`)"
        read as 6 branches when the diff adds 5 in total; counts for all three chains now stated
        unambiguously (5 / 4 / 1), each verified against the diff
  - [x] Re-verified: `npx eslint` exit 0 on the three patched frontend files; `SluWeeklySnapshotRepositoryIT`
        5/5 green after the constant removal; full `mvn -o verify` BUILD SUCCESS with unchanged totals
        (890 unit, 923 IT, 0 failures, 0 errors)

## Dev Notes

### Established conventions this story must follow

- **Error codes:** `BookingError` (`platform/booking/contract/BookingError.java`) is a plain enum
  implementing `ErrorCode`; `getErrorCode()` is an exhaustive `switch` returning the dotted wire string.
  Adding a constant **requires** adding its `case` — the switch has no `default`, so a missing arm is a
  compile error, which is the desired behaviour. Do not add a `default`.
- **`OperationNotAllowedException` → HTTP 403** regardless of the `ErrorCode` it carries. This is why
  `skillars-deferred-30`'s split had a small blast radius and why AC2's and AC3's do too. Do not change
  status codes.
- **Backend messages:** every wire code needs a line in **all four** of `messages.properties`,
  `messages_en.properties`, `messages_de.properties`, `messages_fr.properties`. `messages.properties` is
  the default bundle and duplicates the English text (verified: `booking.startTimeInPast` appears in both
  at `:79` and `:123` respectively).
- **Frontend i18n:** three bundles, `en-US` / `de-DE` / `fr-FR`. All user-facing text externalized
  (project-context rule). A key present in one bundle and missing from another renders the raw key path.
- **Frontend style:** `<script setup>`, `async//await` (never `.then()`), Prettier mandatory, API calls
  only via `src/api/*.api.js`, shared state via Pinia.
- **Tests:** `@SpringBootTest` + Testcontainers via `AbstractIntegrationTest`; AssertJ `assertThat`;
  Instancio for generated data. Do not mock the database in an IT.
- **No frontend test infrastructure exists** — `package.json`'s `test` script is
  `echo "No test specified" && exit 0`. AC1/AC3/AC4/AC6 are therefore verified by build + eslint + code
  reading, and their behavioural claims flagged for human spot-check. This is a standing project gap, not
  a shortcut taken here.

### Files being modified — current state and what must be preserved

- **`booking.store.js`** (`:289-328`) — `loadParentBookings`, `loadCoachBookingRequests`,
  `loadCoachSchedule` each set `*Loading`, null the `*Error` ref, `try` the fetch, `catch (e) { *Error.value = e }`,
  and `finally` clear loading. **They never rethrow.** AC1 reads the error refs; it must not change this
  contract — `BookingRequestPage.vue:418,578` both have comments relying on a failed fetch being swallowed
  rather than blanking the slot list.
- **`CoachBookingRequestsPage.vue`** — `handleAcceptAll`'s `errorKey` chain carries a long comment
  explaining that only the **pre-flight** checks in `acceptAll` reach the client because the per-booking
  throws are swallowed by the loop's `catch`. AC2 adds a fourth reachable outcome to that flow; **update
  that comment** or it becomes wrong the moment `BATCH_NONE_ACCEPTED` ships. A stale comment here is
  precisely what the `skillars-deferred-30` second review had to patch.
- **`BookingBatchService.acceptAll`** — dense with deliberate transactional design (per-booking
  `REQUIRES_NEW` via `perBookingTx`, an unlocked suspension pre-flight, a trailing batch+event
  transaction), each with a comment naming the story that put it there. AC2 touches **only** the
  `acceptedIds.isEmpty()` branch. Changing where the throw sits relative to the trailing transaction, or
  swallowing it, re-opens defects `deferred-14` and `deferred-15` closed.
- **`RescheduleService`** — three public methods: `requestReschedule` (`:54-120`), `acceptReschedule`
  (`:123-217`), `declineReschedule` (`:218-250`). AC3 touches all three; the draft of this story missed
  the third. `:88-92` (`INVALID_SESSION_DURATION`) and `:186-198` (`COACH_UNAVAILABLE`,
  `SLOT_UNAVAILABLE`) are already correctly coded — AC3 must not touch them. The `:166` and `:237`
  `PENDING` checks both sit **after** a `findByIdForUpdate` lock (the `deferred-14`/`deferred-15` race
  guards, each with a comment saying so): change only the `ErrorCode` argument, never the lock, the
  `entityManager.refresh`, or the check's position relative to it.
- **`BookingRequestPage.vue:480-515`** — the `errorKey` chain `skillars-deferred-30` AC2 built, including
  a comment explaining that post-split `MISSING_RIGHTS` carries exactly one meaning. AC4 changes one
  `t()` argument in it; AC6 changes one `console.warn`. Nothing else.
- **`docs/testing/test-data-isolation.md`** — has a registry table, a claimed-prefixes paragraph, and a
  free-blocks paragraph that must agree. `skillars-deferred-30` AC6 just reconciled them after they
  contradicted each other. AC5 adds a block and must update **all three**.

### Why the withdrawn item matters more than it looks

Two independent review passes on the same day filed the same false defect against the same store
contract. The fix is not code — it is the one-line comment AC7 adds at the loaders. Skipping it means a
third reviewer files it again.

### Project Structure Notes

- Backend packages follow `com.softropic.skillars.platform.{module}.{layer}`; the new IT belongs in
  `platform/development/repo/` (mirroring `platform/payment/repo/SessionPackPurchaseRepositoryIT.java`,
  the closest analogue — read it for the `AbstractIntegrationTest` + `transactionTemplate`/`jdbcTemplate`
  seeding pattern before writing).
- No Flyway migration is needed by any AC. No new REST endpoint is added, so no `@PreAuthorize` question
  arises.
- No entity, DTO or mapper changes. `BookingError` is a contract-layer enum; adding constants there is the
  only contract-package change.

### References

- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue:151-205`
- `src/frontend/src/pages/coach/CoachCommandCenterPage.vue:372-400`
- `src/frontend/src/stores/booking.store.js:115-138,289-328,573-604`
- `src/frontend/src/pages/parent/ParentBookingsPage.vue:195-220`
- `src/frontend/src/pages/parent/BookingRequestPage.vue:480-515,555`
- `src/frontend/src/boot/axios.js:165-177`
- `src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js` (`en-US` anchors: `:924`, `:1046`, `:1068-1069`)
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java:23-43`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:235-284`
- `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java:58-198`
- `src/main/java/com/softropic/skillars/platform/development/repo/SluWeeklySnapshotRepository.java:30-38`
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerSluWeeklySnapshot.java`
- `src/main/resources/db/migration/V48__development_exposure_dashboard.sql:5-14`
- `src/main/resources/i18n/messages{,_en,_de,_fr}.properties`
- `src/test/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepositoryIT.java` (pattern)
- `src/test/java/com/softropic/skillars/config/AbstractIntegrationTest.java`
- `docs/testing/test-data-isolation.md:195-230`
- `_bmad-output/implementation-artifacts/deferred-work.md` (sections dated 2026-08-15 → 2026-08-18)
- `_bmad-output/project-context.md`

## Dev Agent Record

### Agent Model Used

Claude Opus 5 (`claude-opus-5`) via Claude Code, `bmad-dev-story` workflow.

### Debug Log References

Mutation verifications performed (each reverted immediately afterwards; the production files are
byte-identical to their intended final state):

| AC | Mutation applied | Result |
|---|---|---|
| 2 | `BookingBatchService` zero-accept branch reverted to a bare `return` | 2/24 `BookingBatchServiceTest` fail |
| 3 | `:62` `BOOKING_NOT_RESCHEDULABLE` → `MISSING_RIGHTS`, and `declineReschedule`'s `:237` `RESCHEDULE_NOT_PENDING` → `MISSING_RIGHTS` | 2/16 `RescheduleResourceIT` fail |
| 5 | dropped `s.id.isoYear > :fromYear` disjunct | 3/5 `SluWeeklySnapshotRepositoryIT` fail |
| 5 | dropped `s.id.isoYear < :toYear` disjunct | 3/5 fail |
| 5 | both week boundaries made exclusive (`>=`→`>`, `<=`→`<`) | 4/5 fail |
| 5 | `s.id.playerId = :playerId` neutralised to `(… OR 1=1)` | 1/5 fail |

Post-change grep check required by Task 3: `RescheduleService` has exactly **3** `SecurityError.MISSING_RIGHTS`
throw sites remaining (was 12), one ownership check per public method.

Final `mvn -o verify`: surefire `Tests run: 890, Failures: 0, Errors: 0, Skipped: 1`; failsafe
`Tests run: 923, Failures: 0, Errors: 0, Skipped: 4`; `BUILD SUCCESS` in 8:19. The `[ERROR]`-prefixed
Vite chunking notices in the log are pre-existing build warnings routed to stderr by
`frontend-maven-plugin`, present on `master` before this story.

### Completion Notes List

**AC1 — coach accept/decline refresh normalisation + stale-list surfacing.** One behaviour applied to
**five** handlers, not the three the AC names: inside every `catch`, `await` the refresh first, then
toast. `CoachBookingRequestsPage.handleAccept`/`handleDecline` had their existing post-failure refresh
hoisted above the toast; `handleAcceptAll` gained a post-failure refresh it never had;
`CoachCommandCenterPage.handleAcceptReschedule`/`handleDeclineReschedule` gained one each. The two
decline handlers were included deliberately — same flow from the coach's point of view. The stale check
is a small local helper per page (`notifyIfRequestsStale` / `notifyIfScheduleStale`) so the five call
sites cannot drift; it fires on **both** the success and the failure path of every handler. On
`handleAccept`/`handleDecline` the success-path check sits directly after the `await`, because the
refresh they depend on runs one level down inside the store (`approveBooking` = `acceptBooking(id)` then
`loadCoachBookingRequests()`), exactly as the task specified. New key `booking.errors.listMayBeStale` in
all three bundles. No `.catch(() => {})` was added anywhere.

**AC2 — `acceptAll` zero-accept.** New `BookingError.BATCH_NONE_ACCEPTED` → `booking.batchNoneAccepted`,
in all four backend `messages*.properties` and all three frontend bundles. The silent `return` is now a
`throw new OperationNotAllowedException(...)`; the `log.warn` is kept, and a code comment names both
paths that reach the branch. `handleAcceptAll`'s throw-site comment was rewritten from "three
pre-flight outcomes" to four, distinguishing the one post-loop outcome. Status is 403, not a new code —
`OperationNotAllowedException` maps to FORBIDDEN independent of the `ErrorCode`. Two mutation-verified
unit tests cover both reaching paths (every per-booking accept threw; a still-`PENDING` batch with no
`REQUESTED` bookings).

**AC3 — `RescheduleService` `MISSING_RIGHTS` split.** The corrected table in the story matched source
exactly: 12 throws, 3 genuine authz (one ownership check per public method), 9 re-coded. Three new
`BookingError` constants (`BOOKING_NOT_RESCHEDULABLE`, `RESCHEDULE_ALREADY_PENDING`,
`RESCHEDULE_NOT_PENDING`) plus the existing `START_TIME_IN_PAST` / `INVALID_TIME_RANGE`. Both `PENDING`
checks in `acceptReschedule` (unlocked early-out and locked re-read) and the one in `declineReschedule`
were changed; only the `ErrorCode` argument moved — locks, `entityManager.refresh` and check positions
are untouched, as are `:88-92` (`INVALID_SESSION_DURATION`) and `:186-198`
(`COACH_UNAVAILABLE`/`SLOT_UNAVAILABLE`). Post-change grep confirms exactly 3 remaining. Frontend
branches added to all three chains, including `handleDeclineReschedule`, which had a bare `catch {}`
with no `errorKey` read. A class-level javadoc on `RescheduleService` records why the three survivors
are survivors.

**AC4 — i18n namespace convention.** Convention adopted: *a toast key lives in the namespace of the
wire code's domain prefix, not the page that renders it*, written down as a comment above the `errorKey`
chain in `BookingRequestPage.vue`. `booking.errors.packExpired` → `payment.sessionPack.packExpired` in
all three bundles, same strings, single call site updated. Grep confirmed one consumer before the move
and zero hits on the old key path after; the runtime resolution check additionally asserts
`booking.errors.packExpired` is now `undefined` in all three bundles.

**AC5 — `SluWeeklySnapshotRepositoryIT`.** New IT, 5 tests, fixture block `9630000001`–`9630000003`.
Beyond the cases the AC listed I added a **two-rollover** window (`(2024,50) → (2026,3)`) — it is the
only shape in which a whole intervening year must come back regardless of its week numbers, and it kills
a mutation the single-rollover case survives. The ordering test seeds deliberately backwards and uses
two `skill_code` values per week so ties on `(year, week)` cannot mask a broken year sort.
`SluWeeklySnapshotRepository.java` is byte-identical to HEAD — no production code changed by this AC.
`docs/testing/test-data-isolation.md` updated in all three places (registry row, claimed-prefixes list,
`9630` removed from free blocks, which now start at `9640`).

**AC6 — console PII.** `err` dropped from all three `console.warn` calls; the warnings themselves are
kept, and each carries a comment recording why the second argument is gone. `helpCode` was **not** added
— the AC permits it but nothing currently needs it, and adding an unused field would be speculative.

**AC7 — ledger hygiene.** Six `[CLOSED by skillars-deferred-31 ACn]` annotations describing what actually
shipped (two of them explicitly partial: AC2 closed only the false-success half, AC6 only the PII half).
The `CoachBookingRequestsPage.vue:164` item is annotated `[WITHDRAWN …]` with the
`booking.store.js:302-314` evidence, a note that `handleDecline` (`:176`) is harmless for the same
reason, and a pointer to the identical withdrawal made hours earlier by the `skillars-deferred-30`
review. A `CONTRACT` comment above `loadCoachBookingRequests`/`loadCoachSchedule` now states that neither
loader ever rethrows, so a third reviewer does not file it again.

**Two new ledger items filed** (not one): (a) `acceptAll` still cannot report *which* bookings failed or
why — needs a per-booking result DTO and a REST contract change, as the AC anticipated; (b) surfaced by
this implementation — `booking.errors.requestNotAllowed` is worded for the parent booking-request path
("You do not have access to the player or session pack in this request") but AC3 and `skillars-deferred-30`
now reuse it on three paths where `MISSING_RIGHTS` means "does not own this booking/batch". Every use is
a genuine authorization failure and no user is misled into a wrong action, so it is a copy/i18n decision
rather than a defect — filed rather than fixed, since choosing between one generic string and one per
object type is a UX call.

**Review follow-ups (2026-08-18, post code review).** Three `[Patch]` findings resolved; both `[Defer]`
findings were re-checked and agreed with as acknowledged scope limits, not defects, so no code changed
for them.

- ✅ Resolved review finding [Patch]: **cross-call race in the shared error refs.** The finding is
  correct and is in fact stronger than it states — beyond two rows being accepted concurrently (which
  the per-row `accepting[id]`/`declining[id]` flags deliberately permit), `CoachCommandCenterPage`
  fires `loadCoachSchedule` from four places that never `await` it (`:241`, `:246`, `:251` and
  `onWrapUpComplete`'s 3-second `setTimeout` at `:368`), any of which can reset-then-write
  `coachScheduleError` between a handler's `await` and its check. Fixed as the reviewer proposed: both
  loaders now return `true`/`false` for **their own** invocation, `approveBooking`, `rejectBooking` and
  `handleAcceptAllBatch` propagate it, and `notifyIfRequestsStale`/`notifyIfScheduleStale` take it as a
  parameter. This is a deliberate, documented deviation from AC1's literal wording ("check the
  corresponding error ref"): the AC's intent is that a failed post-mutation refresh be surfaced, and a
  per-invocation return value delivers that without the ref's cross-call ambiguity. The refs are left
  in place and still exported — they carry the error object itself, which the error-banner treatment
  AC1 explicitly deferred will want. The never-rethrow CONTRACT comment in the store now also documents
  the return value and tells future callers not to re-read the refs for this purpose.
- ✅ Resolved review finding [Patch]: **dead `UNUSED_PLAYER_ID`.** Removed, and the registry claim
  narrowed to `9630000001`–`9630000002`. I chose deletion over the reviewer's alternative of inventing a
  test for it: the only candidate case (a player whose rows all fall outside the window) is already
  covered by the same-year test's before/after exclusions, so a third id would have been padding. Note
  this narrows the block AC5 specified (`…001`–`…003`) by one id — a strictly safer change that makes
  the registry match what is actually seeded.
- ✅ Resolved review finding [Patch]: **ambiguous branch count.** "(5 new + `MISSING_RIGHTS`)" read as 6;
  the diff adds 5 to `submitReschedule` with `MISSING_RIGHTS` among them. Reworded, and while fixing it
  I verified all three chains against the diff and stated each count explicitly: `submitReschedule` 5,
  `handleAcceptReschedule` 4, `handleDeclineReschedule` 1.
- ⏭️ Deferred finding [Defer]: **7 of 9 recoded reschedule sites lack IT-level wire assertions.** Agreed
  — Task 3 scoped itself to "at least two assertions", and the two chosen are the two the story argued
  were most at risk (the parent path, and the `declineReschedule` site a truncated enumeration misses).
  Not re-filed: this is the same coverage-depth question the ledger already tracks.
- ⏭️ Deferred finding [Defer]: **`BATCH_NONE_ACCEPTED` verified only at unit level.** Agreed — Task 2
  required a mutation-verified test, which it has, and both reaching paths are covered. The uneven bar
  next to AC3's ITs is real but is a scope observation, not a defect.

**Not done — flagged for human spot-check.** No frontend test infrastructure exists in this repo
(`package.json`'s `test` script is `echo "No test specified" && exit 0`) and no browser tooling is
available here, so the **behaviour** of every toast added or reordered by AC1/AC3/AC4/AC6 is verified by
build, eslint, runtime i18n key resolution and code reading only — never by seeing a toast fire. The
specific claims a human should spot-check in a browser: (1) the refresh-then-toast ordering actually
removes the still-pending row before the coach reads the error; (2) `booking.errors.listMayBeStale`
appears when a post-mutation refresh fails and not otherwise; (3) the reschedule toasts render the new
strings rather than a raw key path in `de-DE` and `fr-FR`. This is a standing project gap, not a shortcut
taken here.

### File List

**Added**
- `src/test/java/com/softropic/skillars/platform/development/repo/SluWeeklySnapshotRepositoryIT.java`

**Modified — backend**
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java`
- `src/main/resources/i18n/messages.properties`
- `src/main/resources/i18n/messages_en.properties`
- `src/main/resources/i18n/messages_de.properties`
- `src/main/resources/i18n/messages_fr.properties`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java`

**Modified — frontend**
- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue`
- `src/frontend/src/pages/coach/CoachCommandCenterPage.vue`
- `src/frontend/src/pages/parent/ParentBookingsPage.vue`
- `src/frontend/src/pages/parent/BookingRequestPage.vue`
- `src/frontend/src/stores/booking.store.js`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/de-DE/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`

**Modified — docs / process**
- `docs/testing/test-data-isolation.md`
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/skillars-deferred-31-coach-accept-flow-refresh-reschedule-error-split-and-slu-repository-coverage.md` (this file)

**Not modified** (verified byte-identical to HEAD after mutation testing)
- `src/main/java/com/softropic/skillars/platform/development/repo/SluWeeklySnapshotRepository.java`

### Change Log

| Date | Change |
|---|---|
| 2026-08-18 | Story implemented end to end (AC1–AC7). Backend: new `BookingError` constants `BATCH_NONE_ACCEPTED`, `BOOKING_NOT_RESCHEDULABLE`, `RESCHEDULE_ALREADY_PENDING`, `RESCHEDULE_NOT_PENDING` with wire codes in all four `messages*.properties`; `BookingBatchService.acceptAll`'s zero-accept branch now throws 403 instead of returning 2xx; 9 of `RescheduleService`'s 12 `MISSING_RIGHTS` throws re-coded (3 genuine authz kept). Frontend: refresh-then-toast normalised across five coach handlers with a new `booking.errors.listMayBeStale` warning on success and failure paths; error-mapping branches added to `handleAcceptAll`, `submitReschedule`, `handleAcceptReschedule`, `handleDeclineReschedule`; `packExpired` moved to the `payment.sessionPack` namespace under a newly-documented convention; `err` dropped from three `console.warn` calls carrying request-body PII. Tests: new `SluWeeklySnapshotRepositoryIT` (5 tests, mutation-verified 4 ways), 2 new `BookingBatchServiceTest` cases, 2 new `RescheduleResourceIT` cases — all mutation-verified. Docs: `test-data-isolation.md` block `9630` claimed in all three places; `deferred-work.md` six items closed, one withdrawn, two new items filed. |

| 2026-08-18 | Addressed code review findings — 3 items resolved (2 further findings reviewed and agreed as deferred, no code change). Store loaders `loadCoachBookingRequests`/`loadCoachSchedule` now return a per-invocation refresh outcome, propagated through `approveBooking`/`rejectBooking`/`handleAcceptAllBatch`, so the AC1 stale-list checks no longer read a shared module-scoped error ref that a concurrent load can clear or overwrite; dead `UNUSED_PLAYER_ID` fixture constant removed and the claimed id block narrowed to `9630000001`–`9630000002`; ambiguous branch-count phrasing corrected in the `deferred-work.md` AC3 closure note. Re-verified: eslint clean, `SluWeeklySnapshotRepositoryIT` 5/5, full `mvn -o verify` BUILD SUCCESS with unchanged totals (890 unit / 923 IT, 0 failures, 0 errors). |

### Review Findings

_Three adversarial layers ran (Blind Hunter, Edge Case Hunter, Acceptance Auditor). Acceptance Auditor found zero AC violations — all seven ACs' specific claims (throw-site counts, handler coverage, key moves, IT coverage, ledger annotations) verified against source. 13 unique findings survived dedup across Blind Hunter and Edge Case Hunter; 8 were dismissed as false premises, spec-mandated behaviour, or already-filed/pre-existing scope. 3 patch, 2 defer, 0 decision-needed remain below._

- [x] [Review][Patch] Cross-call race in shared `coachRequestsError`/`coachScheduleError` refs used by the new stale-check helpers [`src/frontend/src/stores/booking.store.js:311-337`, `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue:155-159`, `src/frontend/src/pages/coach/CoachCommandCenterPage.vue:376-380`] — `loadCoachBookingRequests`/`loadCoachSchedule` reset then write a single module-scoped error ref on every call, with no per-invocation isolation. Nothing prevents two coach actions on different rows/reschedules firing concurrently (per-row `accepting.value[id]`/`declining.value[id]` loading state explicitly allows it), so a later-resolving concurrent call's ref write can be read by an earlier call's own post-await `notifyIfRequestsStale()`/`notifyIfScheduleStale()` check — producing a false "list may be out of date" warning attributed to the wrong action, or silently overwriting/suppressing a real one. Introduced by this diff (AC1), not spec-mandated as racy — AC1 only requires checking "the corresponding error ref," not that the check be race-free. Fix: have the loaders return their own outcome (success/error) and have each caller branch on that return value instead of re-reading the shared ref.
- [x] [Review][Patch] Unused `UNUSED_PLAYER_ID` fixture constant in the new IT [`src/test/java/com/softropic/skillars/platform/development/repo/SluWeeklySnapshotRepositoryIT.java:37,50`] — declared and claimed in the `9630000001`–`9630000003` fixture-id registry block, but never seeded into any row; only appears in the `clearFixtures()` cleanup `DELETE ... WHERE player_id IN (...)`. `OTHER_PLAYER_ID` already covers AC5's "different playerId excluded" case, so this third id is dead weight that a future reader could mistake for exercised coverage. Fix: remove the constant (and narrow the claimed fixture block by one id in `docs/testing/test-data-isolation.md`), or give it an actual test case.
- [x] [Review][Patch] Ambiguous branch-count phrasing in the AC3 closure note [`_bmad-output/implementation-artifacts/deferred-work.md:1480`] — "Frontend branches added to `ParentBookingsPage.submitReschedule` (5 new + `MISSING_RIGHTS`)" reads as 6 branches; the diff adds 5 total and `MISSING_RIGHTS` is one of the 5 (previously falling to the generic `else`). Fix: reword to "(5 new, including a `MISSING_RIGHTS` branch that previously fell through to the generic `else`)" or equivalent.
- [x] [Review][Defer] Thin IT coverage for 7 of the 9 recoded `RescheduleService` `MISSING_RIGHTS` sites [`src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java`] — only `booking.notReschedulable` (parent path) and `booking.rescheduleNotPending` (decline path) get end-to-end wire-errorKey assertions; `START_TIME_IN_PAST`, `INVALID_TIME_RANGE`, `RESCHEDULE_ALREADY_PENDING`, and the three distinct codes reachable from `acceptReschedule` have no IT confirming their enum-to-wire-code mapping round-trips through `ApiAdvice`/`BookingApiAdvice`. AC3 explicitly scoped Task 3 to "at least two assertions," so this is an acknowledged residual, not a diff defect — deferred, pre-existing scope limitation.
- [x] [Review][Defer] `BookingBatchService.BATCH_NONE_ACCEPTED` verified only at unit (Mockito) level, no HTTP/integration-level assertion of the wire `errorKey` [`src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java`] — inconsistent verification bar next to the IT-level coverage added for AC3's codes in the same diff, but AC2's Task 2 only required a mutation-verified test, not IT-level coverage — deferred, pre-existing scope limitation.
