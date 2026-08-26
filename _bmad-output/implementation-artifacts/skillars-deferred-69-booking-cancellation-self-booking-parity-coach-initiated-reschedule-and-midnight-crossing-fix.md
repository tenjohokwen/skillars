# Story Deferred-69: Booking Cancellation UI, Self-Booking Player Parity, Coach-Initiated Reschedule & Midnight-Crossing Availability Fix

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a platform owner closing out the largest open cluster of Booking/Availability/Reschedule gaps in
`deferred-work.md`, I want (1) a real production availability-check bug fixed, (2) three long-standing
frontend/authorization gaps closed (parents cannot cancel through the app, self-booking adult players are
locked out of actions they're entitled to, coaches cannot propose a reschedule), and (3) a cluster of
verified, scoped concurrency/test-coverage/dead-code fixes applied, so that the booking module's user-facing
behavior matches what the backend already supports and its concurrency handling is consistent across every
write path that can race.

### Why this is one large story, not several small ones

Every item below was independently verified against **current source** (not trusted from `deferred-work.md`
text — several ledger items turned out to already be fixed, or to describe behavior the code no longer has;
see "Corrections found during scoping" below) and confirmed open. The project owner explicitly decided, item
by item, to bundle all of them rather than split into small stories — see the per-AC "Decision" notes for
what was chosen and why. This story is unusually large by this project's own precedent (`skillars-deferred-68`
was ~400 lines for a single mechanical sweep); expect this one to take meaningfully longer.

### Corrections found during scoping — do not re-implement these

- **Tech-debt item TD-2 ("fire-and-forget notifications, no retry") is FALSE today.** A full outbox-and-poller
  already exists platform-wide: `BookingEmailListener`'s `AFTER_COMMIT` handlers publish an `Envelope`, which
  `EnvelopeEntity` persists (a `@Version`-ed row with `attempts`/`status`/`deadline`/`retry`), `MailManager
  .sendEmailSync` dispatches with a circuit breaker + `RetryTemplate`, and `EmailRetryScheduler`
  (`@Scheduled(fixedDelayString = "${email.retry.interval-ms:60000}")`) polls failed rows with `SELECT ... FOR
  UPDATE SKIP LOCKED`, retrying up to `MAX_RETRY_ATTEMPTS=6` before giving up. This was built well after
  `tech-debt.md` was last touched. **Do not build a second outbox.** AC11 below only updates `tech-debt.md`
  to record this.
- **The `resolveParentName()` "null null" item (`deferred-work.md`, `## Deferred from: code review of
  skillars-deferred-8`, D1) is already fixed.** `BookingService.java:859-868` already null-guards
  `firstName`/`lastName` individually and falls back to `"Unknown Parent"`. Stale ledger item — AC11 removes
  it, no code change needed.
- **Tech-debt item TD-1 ("shared `completionLoading`/`completionError` clobber each other")'s premise no
  longer holds.** Both refs (`booking.store.js:149-150`) have **zero consumers** anywhere in
  `src/frontend/src` (confirmed by full-repo grep) — every page that calls the 10 handlers sharing them
  tracks its own local per-row loading state instead (e.g. `ParentBookingsPage.vue`'s `reschedulingId`,
  `confirmingId`). This is dead exported state, not a live clobbering bug. AC10 removes it rather than
  building the originally-proposed per-action-key `Map` refactor, which would solve a problem the UI no
  longer has.

## Acceptance Criteria

### AC1 — Reject cross-midnight sessions outright

**Decision:** the project owner chose outright rejection over splitting the availability check across two
calendar days.

**The bug:** `BookingService.isSlotWithinAvailabilityWindow` (`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:880-914`)
anchors both `windowStart` and `windowEnd` to the session's own **start** calendar date:
```java
ZonedDateTime windowStart = w.getStartTime().atDate(startZdt.toLocalDate()).atZone(zoneId);
ZonedDateTime windowEnd   = w.getEndTime().atDate(startZdt.toLocalDate()).atZone(zoneId);
```
A session whose `endZdt` falls on the next calendar day always fails `!endZdt.isAfter(windowEnd)`, even
against a coach's wide-open `00:00:00`–`23:59:59` every-day window — no other window entry is tried, since
the day-of-week match (`w.getDayOfWeek() == startZdt.getDayOfWeek().getValue()`) only tries the start day's
own window. **The method's own doc comment at `:896-898` is wrong and must be corrected as part of this fix**
— it currently claims *"ZonedDateTime comparison handles cross-midnight sessions correctly without the
date-equality guard that previously rejected all late-night sessions,"* which describes a different, earlier
bug, not the current behavior.

**Fix:** inside the per-window loop, when a window's day-of-week matches and the session start is within the
window (`!startZdt.isBefore(windowStart)`) but the session's end falls on a different calendar day than its
start in that window's timezone (`!endZdt.toLocalDate().equals(startZdt.toLocalDate())`), **throw directly**
instead of letting the `if` fall through to no-match:
```java
if (w.getDayOfWeek() == (short) startZdt.getDayOfWeek().getValue()
        && !startZdt.isBefore(windowStart)) {
    if (!endZdt.toLocalDate().equals(startZdt.toLocalDate())) {
        throw new OperationNotAllowedException(
            "A session cannot cross midnight",
            Map.of("start time", startTime, "end time", endTime, "coach id", coachId),
            BookingError.SESSION_CROSSES_MIDNIGHT);
    }
    if (!endZdt.isAfter(windowEnd)) {
        return true;
    }
}
```
This targets exactly the case that would otherwise have matched — windows that don't match for unrelated
reasons (wrong day, wrong time-of-day) still fall through to the existing generic `false`/
`SLOT_OUTSIDE_AVAILABILITY` path untouched.

**Timezone safety, confirmed (story-review Issue #1 resolved, no code change needed):** `startZdt` and
`endZdt` are both derived via `startTime.atZone(zoneId)` / `endTime.atZone(zoneId)` using the *same*
per-iteration `zoneId` variable (`BookingService.java:893-894`) — they are already guaranteed to be in the
same (this window's) timezone before `.toLocalDate()` is called on either. No `withZoneSameInstant` or
similar conversion is needed; do not add one. `isSlotWithinAvailabilityWindow` stays boolean-returning for
every other case; this is the one path that throws directly, so **none of its 5 call sites need to change**
(`BookingService.java:224`, `RescheduleService.java:119,239`, `BookingBatchService.java:150`,
`BookingDuplicationService.java:92`) — the new exception simply propagates up through whichever call site
invoked it, same as any other `OperationNotAllowedException` in this module.

Add `SESSION_CROSSES_MIDNIGHT` to `BookingError`
(`src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java`), following the exact
existing enum-constant + switch-arm pattern:
```java
SESSION_CROSSES_MIDNIGHT;
// ...
case SESSION_CROSSES_MIDNIGHT -> "booking.sessionCrossesMidnight";
```
Add a matching paragraph to the class's doc comment (mirroring the existing paragraphs for `CONCURRENT_MODIFICATION` etc.) explaining this is a request-state validation code, not authorization. Add i18n entries for `booking.sessionCrossesMidnight` to all 3 backend locale files (`messages_en.properties`, `messages_de.properties`, `messages_fr.properties` — grep existing `booking.slotOutsideAvailability` entries in each file first to match exact format) and to the frontend's `booking.errors.*` namespace in all 3 `src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js` files (mirror the nesting of an existing sibling key like `booking.errors.slotOutsideAvailability`).

**Frontend:** `ParentBookingsPage.vue`'s `submitReschedule` catch block (`:213-239`) needs a new
`else if (errorKey === 'booking.sessionCrossesMidnight')` branch, mirroring the existing
`slotOutsideAvailability` branch's shape. Check whether `BookingRequestPage.vue` and the batch-request page
have equivalent error-key branches for `slotOutsideAvailability` and add the sibling branch there too if so.

### AC2 — Fix the test time-computation helper that caused the CI flake

**Important:** AC1 alone does **not** fix the CI flakiness that originally surfaced this bug — it converts
the previous *silent false-negative* into a *correct rejection*, which will now make `RescheduleResourceIT`
fail deterministically near midnight instead of flakily. The tests themselves must stop constructing
wall-clock-relative times that can accidentally cross midnight.

**Root cause, confirmed:** `RescheduleResourceIT.java`'s fixture uses an every-day-of-week,
`00:00:00`–`23:59:59`, `Europe/Berlin` availability window (`:125-137`). Throughout the file (at least the 5
tests named below, and reportedly ~20 more `Instant.now().plus(N, DAYS)`-style call sites sharing the same
pattern — **grep `Instant.now().plus(` in this file yourself and fix every match, not just the 5 named**),
test times are computed as:
```java
Instant proposedStart = Instant.now().plus(N, ChronoUnit.DAYS);
Instant proposedEnd = proposedStart.plus(1, ChronoUnit.HOURS);
```
This preserves the exact current wall-clock time-of-day. Any CI run where "now" converted to `Europe/Berlin`
falls roughly between 23:00 and 24:00 pushes `proposedEnd` past midnight Berlin time — which, after AC1,
throws `SESSION_CROSSES_MIDNIGHT` instead of the test's expected outcome.

**Fix:** add a private helper to `RescheduleResourceIT.java` that anchors to a fixed, safe local hour instead
of preserving wall-clock time-of-day:
```java
private Instant safeProposedStart(int daysAhead) {
    return ZonedDateTime.now(ZoneId.of("Europe/Berlin"))
        .plusDays(daysAhead)
        .truncatedTo(ChronoUnit.DAYS)
        .withHour(10)
        .toInstant();
}
```
Replace every `Instant.now().plus(N, ChronoUnit.DAYS)` call site in this file with `safeProposedStart(N)`.
Confirm each replaced test's assertions still make sense (a fixed 10:00 start, plus whatever duration that
test uses, must stay within the fixture's `00:00:00`–`23:59:59` window and not itself cross midnight).

The 5 tests named in `deferred-work.md`'s `## Deferred from: skillars-deferred-68 CI run` section:
`acceptReschedule_asOwningCoach_returns204AndUpdatesBookingAndStatus`,
`acceptReschedule_proposedSlotTakenByAnotherBooking_returns403AndLeavesBookingUnchanged`,
`requestReschedule_asParentWithConfirmedBooking_returns204AndCreatesRecord`,
`requestReschedule_pendingRequestAlreadyExists_returns403WithRescheduleAlreadyPendingKey`,
`duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204`.

### AC3 — Parent booking-cancel UI

**Decision:** the project owner chose to include this. `POST /api/bookings/{id}/cancel`
(`CancellationResource.java:29-33`, `@PreAuthorize(HAS_PARENT_ROLE)`, calls
`bookingService.cancelBookingAsParent`) is fully hardened server-side but has **zero frontend callers** — the
export exists (`booking.api.js`: `export const cancelBooking = (bookingId) => api.post(...)`) but nothing
calls it.

**No refund-eligibility preview endpoint exists.** `cancelBookingAsParent` computes refund eligibility
internally only at cancel time (an hours-before-session whitelist, `BookingService.java:664-672`). Do not
build a preview endpoint — out of scope. The confirmation dialog shows a generic warning, not a computed
refund amount.

**Backend:** no changes — the endpoint is already correct and complete.

**Frontend, `booking.store.js`:** add a `handleCancelBooking(bookingId)` action mirroring the existing
`handleConfirmCompletion` shape (single-arg, calls the API, `await loadParentBookings()` on success, sets/
clears loading+error state per-call via a local pattern — **do not** reintroduce the shared
`completionLoading`/`completionError` refs AC10 removes; use a local ref scoped to this action the same way
each of the other handlers already does internally).

**Frontend, `ParentBookingsPage.vue`:** add a Cancel button to the `q-item-section` at `:37-74` (alongside the
existing Request-Change button), gated `['CONFIRMED', 'UPCOMING'].includes(booking.status)` — the same status
set the Request-Change button already uses, since `cancelBookingAsParent` has no status whitelist of its own
beyond ownership (it transitions unconditionally; only refund eligibility is status-gated). **Note:** this
means a parent can still cancel a session that has already started or nearly started with no time-based
warning beyond the generic confirmation copy — this is a pre-existing gap in `cancelBookingAsParent` itself
(tracked separately, still-undecided, in `deferred-work.md`'s `skillars-deferred-8`-heading late-cancel-as-
no-show item) and explicitly out of this AC's scope; do not add a client-side time guard that doesn't exist
server-side.

Add a confirmation `q-dialog`, mirroring the existing reschedule dialog's shape (`:93-118` — `q-card` /
`q-card-section` / `q-card-actions align="right"` with a flat dismiss button and an `unelevated color=
"primary"` confirm button), with a title + generic warning body text (no form inputs — cancel takes no
payload) instead of the reschedule form.

**Error mapping — corrected during story-review, this is not what the original draft said (story-review
Issue #8 resolved with a real finding, not a false positive):** `cancelBookingAsParent`
(`BookingService.java:648-699`) throws exactly two error shapes, confirmed by reading the full method body —
plus `ResourceNotFoundException` from the booking lookup, which the frontend never needs to branch on since a
cancel button only renders for a booking already in the list:
1. `OperationNotAllowedException("Parent does not own this booking", SecurityError.MISSING_RIGHTS)` — this
   one **does** carry `errorMsg.errorKey === 'MISSING_RIGHTS'` as normal (same shape `submitReschedule`
   already branches on at `:231`).
2. `new ResponseStatusException(HttpStatus.CONFLICT, "booking.paymentInProgress")` (capture-in-flight
   interlock — `deferred-work.md`'s `skillars-uat-3` heading, D2) — **this is NOT an
   `OperationNotAllowedException`, and its `errorKey` is NOT `"booking.paymentInProgress"`.**
   `ApiAdvice.responseStatusExceptionHandler` (`:118-122`) handles every `ResponseStatusException` uniformly:
   it hardcodes `errorKey` to the literal string `"generic.requestError"` and passes `ex.getReason()`
   (`"booking.paymentInProgress"`) only as the *default message* for that key. `generic.requestError` has
   **no i18n bundle entry in any locale file** (confirmed by grep) — `MessageSource.getMessage` therefore
   returns the default message verbatim, so the response the frontend actually receives is `errorMsg.errorKey
   === 'generic.requestError'` and `errorMsg.message === 'booking.paymentInProgress'` (the raw wire code
   leaking through as message text — a pre-existing quirk of `responseStatusExceptionHandler`, not something
   to fix in this story). **Branch on `err?.response?.data?.errorMsg?.message === 'booking.paymentInProgress'`
   for this one case, not on `errorKey`** — `errorKey` alone cannot distinguish this rejection from any other
   `ResponseStatusException` in the codebase. Add both branches to the new handler's catch block, mirroring
   `submitReschedule`'s `errorKey`-branching shape (`:213-239`) for the `MISSING_RIGHTS` case and this
   `message`-based check for the payment-in-progress case, rather than falling through to a silent generic
   toast for either.

### AC4 — Self-booking (adult) player parity

**Decision:** the project owner chose to include this. **Scoped smaller than it first appears** — the data
model already supports self-booking players end-to-end; only authorization gates need widening.

**Already confirmed working, no changes needed:** `Booking.parentId` is always the caller's own userId,
whether they're a real parent or a self-registered adult player (`BookingService.createBookingRequest:170-176`
branches on `player.getParentId() == null` to check `player.getUserId()` instead). This means
`cancelBookingAsParent`'s existing ownership check (`booking.getParentId().equals(parentUserId)`) already
works correctly for a self-booking player with **zero service-layer changes**. `SecurityConstants
.HAS_PARENT_OR_PLAYER_ROLE` already exists and is already applied to booking creation/listing endpoints
(`BookingResource.java:37,44,50`, `BookingBatchResource.java:36,42`, from `skillars-uat-5`).

**Backend — widen these 5 `@PreAuthorize` annotations from `HAS_PARENT_ROLE` to `HAS_PARENT_OR_PLAYER_ROLE`:**
1. `CancellationResource.java:29` (`POST /{id}/cancel`)
2. `CancellationResource.java:51` (`POST /{id}/no-show-coach`)
3. `RescheduleResource.java:33` (`POST /{id}/reschedule` — `requestReschedule`)
4. `SessionCompletionResource.java:90` (`PUT /{id}/confirm-completion`)
5. `ScheduleResource.java:71` (`GET /parents/me/schedule`)

Do not widen `CancellationResource.java:37` (`/coach-cancel`), `:44` (`/no-show-player`) — those are
`HAS_COACH_ROLE`, unrelated. Do not widen `RescheduleResource.java`'s `acceptReschedule`/`declineReschedule`/
`duplicateNextWeek` (`HAS_COACH_ROLE`, correctly coach-only) or `ScheduleResource.java:41` (coach schedule,
correctly coach-only).

Before assuming each of the 5 underlying service methods' ownership check follows the same
`booking.getParentId().equals(callerId)` self-booking-compatible shape confirmed for `cancelBookingAsParent`
above, **confirm each one individually** (`recordNoShowCoach`, `requestReschedule`,
`confirmCompletion`/whatever `BookingCompletionService.confirmCompletion` checks, `getParentSchedule`) — do
not assume from the one verified case.

**Frontend, `ParentBookingsPage.vue`:** widen the two existing role gates. Both currently read
`v-if="authStore.isParent && ..."` with a comment explaining they were deliberately left un-widened by
`skillars-deferred-66`:
- `:67` (Request-Change button)
- `:80` (Confirm-Completion button)

Change both to `authStore.isParent || authStore.isPlayer` (both getters already exist,
`src/frontend/src/stores/auth.store.js:17-18`) — remove the now-stale "not widened by this story" comments
(`:63-65`, `:77-78`) or update them to note this story widened them. Also add the new AC3 cancel button
(gated the same way, `authStore.isParent || authStore.isPlayer`) and AC5's new parent-side accept/decline
buttons under the same widened gate.

The route itself already permits `PLAYER`
(`src/frontend/src/router/routes.js:143-151`, `roles: ['PARENT', 'PLAYER']`, comment already anticipates this
exact gap) — no router change needed.

**Dispute-raising:** `DisputeResource.java:74-81`'s `resolveCurrentRole()` already has a documented "PLAYER
fallback." Not independently re-verified this pass — if the dev agent finds a gap here while implementing,
treat it as a bonus fix within this AC's spirit (self-booking player parity), not as new scope requiring a
separate decision.

**Not in scope:** no-show-player / coach-cancel remain coach-only (correct, unrelated to self-booking
players). No `ActorRole` change — `ActorRole` (`booking.contract.ActorRole`: `COACH, PARENT, SYSTEM`) is a
state-machine actor role, orthogonal to the Spring `ROLE_PLAYER` HTTP authority; a self-booking player's
booking actions are recorded as `ActorRole.PARENT` in the state machine (matching how `parentId` already
represents them), not a new `ActorRole.PLAYER` — do not add one.

### AC5 — Coach-initiated reschedule

**Decision:** the project owner chose to include this — confirmed the largest, least-mechanical item in this
story. `RescheduleService.requestReschedule` is parent-only by signature and ownership check
(`RescheduleService.java:72-153`); no coach-side proposal path exists. The DB already supports it —
`V35__booking_reschedule_requests.sql:4` already has `CHECK (proposed_by IN ('PARENT', 'COACH'))` — **no
migration needed.**

**Design (new, decided during story creation — follow exactly, do not invent an alternative shape):**
mirror this codebase's established per-actor-separate-method convention (`cancelBookingAsParent`/
`cancelBookingAsCoach`, `recordNoShowPlayer`/`recordNoShowCoach`) rather than branching one method by role.

**Backend — `RescheduleService.java`:**

1. Extract the shared validation body of `requestReschedule` (`:79-124` — reschedulable-status, start-time-in-
   future, duration-match, availability, already-pending checks) into a private
   `validateRescheduleProposal(Booking booking, CreateRescheduleRequest req)` that throws the same exceptions,
   unchanged. Both `requestReschedule` and the new `requestRescheduleAsCoach` call it — this is a real,
   byte-for-byte duplicated block (not "three similar lines"), so extracting it is warranted, not
   over-abstraction. **Story-review Issue #2 — before extracting, re-read `:79-124` one more time and confirm
   nothing in that block references `parentUserId`/`parentId` (the one thing that legitimately differs between
   the parent and coach call sites — it must stay in each caller, not move into the shared method). If the
   extraction reveals any other difference between what the two callers need, stop and leave that one check
   duplicated rather than forcing a false match — do not silently paper over a real difference to make the
   extraction clean.**

2. Add `requestRescheduleAsCoach(UUID bookingId, Long coachUserId, CreateRescheduleRequest req)`, mirroring
   `requestReschedule` (`:72-153`) exactly except:
   - Ownership check: resolve `coachProfileRepository.findByUserId(coachUserId)`, then
     `booking.getCoachId().equals(coach.getId())` else `OperationNotAllowedException("Coach does not own this
     booking", SecurityError.MISSING_RIGHTS)` (same shape as `acceptReschedule`'s existing coach-ownership
     check at `:161-163`).
   - `rescheduleRequest.setProposedBy("COACH")` instead of `"PARENT"`.
   - Publish a new `RescheduleRequestedByCoachEvent` instead of `RescheduleRequestedEvent`. `
     RescheduleRequestedEvent`'s actual constructor (confirmed by direct read,
     `src/main/java/com/softropic/skillars/platform/booking/contract/RescheduleRequestedEvent.java`) is
     `(Object source, UUID bookingId, String coachEmail, String parentName, Instant originalStartTime,
     Instant proposedStartTime, String canonicalTimezone)` — `coachEmail` is the recipient, `parentName` is
     the proposer's display label. The new event's constructor must swap exactly those two roles and nothing
     else: `(Object source, UUID bookingId, String parentEmail, String coachDisplayName, Instant
     originalStartTime, Instant proposedStartTime, String canonicalTimezone)` — `parentEmail` is now the
     recipient, `coachDisplayName` is the proposer's label. Same field order/types otherwise.

3. Add `BookingError.CANNOT_RESPOND_TO_OWN_PROPOSAL` → `"booking.cannotRespondToOwnProposal"`, same
   enum-constant + switch-arm pattern as AC1's new code. Guard the **existing** `acceptReschedule` and
   `declineReschedule` (both stay coach-only, unchanged endpoints) with a check immediately after loading
   `req`: if `"COACH".equals(req.getProposedBy())`, throw this new error — a coach cannot accept/decline
   their own proposal.

4. Extract `acceptReschedule`'s shared lock/availability/overlap body (`:184-256`, everything after the
   ownership check and before the final booking-save-and-publish) into a private
   `acceptRescheduleShared(Booking booking, CoachProfile coach, BookingRescheduleRequest req)` returning the
   locked, validated `req` and `booking` (or just performing the writes and letting each caller publish its
   own event afterward — the dev agent's call on the cleanest split, but do not duplicate the lock-ordering
   logic, the availability re-check, or the overlap check across two copies). Both `acceptReschedule` and the
   new `acceptRescheduleAsParent` call it. **Story-review Issue #3 — the extracted method must not carry its
   own `@Transactional`: both callers already are, and `PessimisticLockRetryer`'s bounded retry is
   savepoint-based within the caller's existing transaction (see `PessimisticLockRetryer`'s own class doc for
   why) — adding `@Transactional` to the shared method would create a nested-proxy boundary that changes that
   behavior. Everything the extracted body needs (the two lock acquisitions, the availability re-check, the
   overlap check, and the `OptimisticLockingFailureException` → `CONCURRENT_MODIFICATION` catch around the
   final `bookingRepository.save`) is identical regardless of which party is accepting — the lock target is
   always the coach's row and the reschedule-request row, never anything ownership-specific — so this
   extraction has no bifurcation risk the way AC5.1's validation extraction does.**

5. Add `acceptRescheduleAsParent(UUID bookingId, UUID rescheduleId, Long parentUserId)`, mirroring
   `acceptReschedule` except: ownership check is `booking.getParentId().equals(parentUserId)` (else
   `MISSING_RIGHTS`, same message shape as `cancelBookingAsParent`'s); guard requires `"PARENT"
   .equals(req.getProposedBy())` else throw `CANNOT_RESPOND_TO_OWN_PROPOSAL` (a parent cannot accept their own
   proposal). **Reuse `RescheduleAcceptedEvent` unchanged** — its constructor (confirmed by direct read,
   `RescheduleAcceptedEvent.java`) is `(Object source, UUID bookingId, String parentEmail, String coachEmail,
   String coachDisplayName, Instant newStartTime, String canonicalTimezone)`: it already carries **both**
   `parentEmail` and `coachEmail` as independent fields, unlike the request/decline events. Confirmed:
   `BookingEmailListener.onRescheduleAccepted` (`:193-211`) loops `List.of(event.getParentEmail(),
   event.getCoachEmail())`, filters blanks, and sends to whichever are present — it is already fully
   direction-agnostic today, with no change needed for either "coach accepted parent's proposal" (existing
   behavior) or "parent accepted coach's proposal" (new, this AC). No new event or listener method needed for
   accept.

6. Add `declineRescheduleAsParent(UUID bookingId, UUID rescheduleId, Long parentUserId)`, mirroring
   `declineReschedule` (`:278-310`) except: ownership check is parent-based; guard requires `"PARENT"
   .equals(req.getProposedBy())` else `CANNOT_RESPOND_TO_OWN_PROPOSAL`. Publish a new
   `RescheduleDeclinedByParentEvent` instead of `RescheduleDeclinedEvent`. `RescheduleDeclinedEvent`'s actual
   constructor (confirmed by direct read, `RescheduleDeclinedEvent.java`) is `(Object source, UUID bookingId,
   String parentEmail, String coachDisplayName, Instant originalStartTime, String canonicalTimezone)` —
   `parentEmail` is the recipient (today, always "coach declined"), `coachDisplayName` is the decliner's
   label. The new event swaps exactly those two roles: `(Object source, UUID bookingId, String coachEmail,
   String parentName, Instant originalStartTime, String canonicalTimezone)` — `coachEmail` is now the
   recipient, `parentName` is the decliner's label. Same field order/types otherwise.

**Backend — `RescheduleResource.java`:** add 3 new endpoints, each its own mapping (do not overload the
existing paths):
```java
@PostMapping("/{id}/reschedule/coach")
@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
public ResponseEntity<Void> requestRescheduleAsCoach(@PathVariable UUID id, @Valid @RequestBody CreateRescheduleRequest req) {
    rescheduleService.requestRescheduleAsCoach(id, currentUserId(), req);
    return ResponseEntity.noContent().build();
}

@PutMapping("/{id}/reschedule/{rescheduleId}/accept-parent")
@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
public ResponseEntity<Void> acceptRescheduleAsParent(@PathVariable UUID id, @PathVariable UUID rescheduleId) {
    rescheduleService.acceptRescheduleAsParent(id, rescheduleId, currentUserId());
    return ResponseEntity.noContent().build();
}

@PutMapping("/{id}/reschedule/{rescheduleId}/decline-parent")
@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
public ResponseEntity<Void> declineRescheduleAsParent(@PathVariable UUID id, @PathVariable UUID rescheduleId) {
    rescheduleService.declineRescheduleAsParent(id, rescheduleId, currentUserId());
    return ResponseEntity.noContent().build();
}
```

**Notification listener — `BookingEmailListener.java`:**
- Add `onRescheduleRequestedByCoach(RescheduleRequestedByCoachEvent event)`, mirroring `onRescheduleRequested`
  (`:169-190`) but recipient is `event.getParentEmail()` (blank-check + warn-and-return, same shape) and
  template is a new `EmailTemplate.BOOKING_RESCHEDULE_REQUESTED_BY_COACH`.
- Add `onRescheduleDeclinedByParent(RescheduleDeclinedByParentEvent event)`, mirroring `onRescheduleDeclined`
  (`:213-234`) but recipient is `event.getCoachEmail()` and template is a new `EmailTemplate
  .BOOKING_RESCHEDULE_DECLINED_BY_PARENT`.
- `onRescheduleAccepted` needs no changes (already direction-agnostic).

Add both new `EmailTemplate` constants (`src/main/java/com/softropic/skillars/platform/notification/contract/EmailTemplate.java`), each with a new `subjectKey` (e.g. `email.booking.reschedule_requested_by_coach.title`), following the exact existing naming convention. Add matching i18n subject-key entries to all 3 backend locale files. Add 2 new HTML template files (`src/main/resources/mails/bookingRescheduleRequestedByCoach.html`, `bookingRescheduleDeclinedByParent.html`), each adapted from its sibling (`bookingRescheduleRequested.html`, `bookingRescheduleDeclined.html`) with wording swapped to address the new direction (e.g. "your coach has proposed a new time" instead of "your parent has requested to reschedule").

**Frontend, `booking.api.js`:** add
```js
export const requestRescheduleAsCoach = (id, data) => api.post(`/api/bookings/${id}/reschedule/coach`, data)
export const acceptRescheduleAsParent = (id, rescheduleId) => api.put(`/api/bookings/${id}/reschedule/${rescheduleId}/accept-parent`)
export const declineRescheduleAsParent = (id, rescheduleId) => api.put(`/api/bookings/${id}/reschedule/${rescheduleId}/decline-parent`)
```

**Frontend, parent side (`ParentBookingsPage.vue`):** the existing "pending reschedule indicator"
(`:53-61`) renders unconditionally for any pending reschedule but offers no accept/decline — today that's
correct, since only a coach could ever be the one needing to respond, on a different page. Now that a coach
can propose, add accept/decline buttons gated on `booking.pendingReschedule?.proposedBy === 'COACH'` (the
`proposedBy` field already exists on the DTO — `RescheduleRequestResponse.java:8`):
```html
<div v-if="booking.pendingReschedule?.proposedBy === 'COACH'" class="q-gutter-sm q-mt-xs">
  <q-btn unelevated color="primary" size="sm" :label="t('booking.reschedule.accept')"
         :loading="respondingId === booking.id" @click="handleAcceptRescheduleAsParent(booking.id)" />
  <q-btn flat size="sm" :label="t('booking.reschedule.decline')"
         :loading="respondingId === booking.id" @click="handleDeclineRescheduleAsParent(booking.id)" />
</div>
```
Add corresponding handler functions in the `<script setup>` block, following `handleConfirmCompletion`'s
try/catch/notify/loading shape, and add `booking.reschedule.accept`/`booking.reschedule.decline` i18n keys
(all 3 locale files) if they don't already exist under that namespace.

**Frontend, coach side:** no coach-facing page with a reschedule-proposal UI currently exists (not located
during story creation). **Locate it first**: grep for the coach's booking-list/schedule page (likely
`CoachBookingsPage.vue`, `CoachScheduleePage.vue`, or wherever `getCoachSchedule`/`getCoachBookingRequests`
from `booking.api.js` are already consumed) — that page is where a coach currently sees/acts on
`acceptReschedule`/`declineReschedule` for parent-initiated proposals (those endpoints already work today, so
that UI must already exist somewhere). Add, symmetric to what AC5 adds on the parent side:
1. A "Propose New Time" button + dialog (mirror `ParentBookingsPage.vue`'s existing reschedule dialog,
   `:93-118`, and its `openRescheduleDialog`/`submitReschedule` logic, `:177-243` — same derived-end-time,
   same timezone hints, same error-key branching, wired to `requestRescheduleAsCoach` instead of
   `requestReschedule`), shown when there's no pending reschedule on that booking.
2. Gate the coach's *existing* accept/decline buttons (wherever they currently render, wired to
   `acceptReschedule`/`declineReschedule`) on `booking.pendingReschedule?.proposedBy === 'PARENT'` — a coach
   should no longer see accept/decline for their own proposal (the backend now rejects it via
   `CANNOT_RESPOND_TO_OWN_PROPOSAL`, but the button shouldn't be clickable in the first place).

### AC6 — Fix the `BookingBatchService` batch-status write race

**Decision:** confirmed a clean, scoped, mechanical fix — not architectural, despite the ledger's original
"no longer atomic, accepted" framing (`deferred-work.md`, `skillars-deferred-15` heading, D4).

Two writers compute and save `BookingBatch.status` from `computeBatchStatus(...)`, both currently unlocked:
`acceptAll`'s trailing transaction (`BookingBatchService.java:329-338`, `trailingTx.executeWithoutResult`,
fresh `batchRepository.findById(batchId)`) and `updateBatchStatusFromBooking`
(`:414-434`, `@Transactional(REQUIRES_NEW)`, fired by `BookingBatchStatusListener.onBookingStatusChanged` on
every individual booking status change). Both read-compute-write with no lock between them — a last-writer-
wins race.

**Fix:** `BookingBatchRepository` (`src/main/java/com/softropic/skillars/platform/booking/repo/BookingBatchRepository.java`)
currently has no locked-read method. Add one, mirroring the exact pattern already established on
`BookingRepository`/`CoachProfileRepository`/`BookingRescheduleRequestRepository`/
`SessionPackPurchaseRepository` (`skillars-deferred-62`):
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
@Query("select b from BookingBatch b where b.id = :id")
Optional<BookingBatch> findByIdForUpdate(@Param("id") UUID id);
```
In **both** write sites, replace the unlocked `batchRepository.findById(batchId)` with
`lockRetryer.withBoundedRetry(() -> batchRepository.findByIdForUpdate(batchId))` — `BookingBatchService`
already injects `lockRetryer` as a constructor field (`private final PessimisticLockRetryer lockRetryer;`,
confirmed present in the class's field list, used in `acceptOneBooking:384`) — no new injection needed
(story-review Issue #12 resolved). **No `entityManager.refresh(...)` needed at
either site**: both reads happen inside a fresh persistence context (the trailing transaction's `fresh`
variable is deliberately named to distinguish it from the outer transaction's already-managed `batch`;
`updateBatchStatusFromBooking` is its own `REQUIRES_NEW` transaction) — same reasoning `acceptOneBooking`'s
own comment already documents for why it skips the refresh (`:386-390`).

This serializes the two writers: whichever transaction acquires the row lock first, the other blocks (bounded
by `PessimisticLockRetryer`'s existing retry budget) until it commits, then reads the fresh, already-updated
status — eliminating the last-writer-wins race rather than narrowing it.

### AC7 — Narrow the `createBatch` session-duration/availability staleness window

**Decision:** confirmed mechanically feasible without new locking infrastructure, since `createBatch` is
already one flat `@Transactional` method (`BookingBatchService.java:100-225`) — but **cannot be fully
eliminated**, since `CoachAvailabilityWindow`/`CoachAvailabilityBlock` carry no `@Version` and no locked-read
method exists for them anywhere in the codebase (confirmed: `AvailabilityService` cannot throw
`OptimisticLockingFailureException` at all). This AC narrows the window; it does not close it.

`requiredDuration` and `windows` are resolved once (`:127-129`) before the per-slot validation loop
(`:131-158`), then reused again after batch/booking rows are persisted (`:189-211`) — so a coach edit landing
between the initial resolve and the final persist is invisible to this request.

**Fix:** immediately before the persist loop (`:189`, right before `BookingBatch batch = new BookingBatch();`),
re-fetch `windows` and `requiredDuration` fresh and re-run the same per-slot validation
(duration-match + `isSlotWithinAvailabilityWindow`) against the fresh values, inside the same transaction. If
any slot now fails, abort the whole batch (throw the same exceptions the first pass already throws — reuse
the same validation logic, do not duplicate it inline; consider extracting the per-slot validation loop body
into a private helper called twice, once with the original `windows`/`requiredDuration` and once with the
freshly re-fetched pair). Document in a code comment (matching this codebase's honest-about-limits style, see
`acceptAll`'s own doc comment at `:230-237` as a model) that this narrows the window from "the whole request"
to "between the two resolves," and does not eliminate it — full elimination would require lock support on
`CoachAvailabilityWindow`, which doesn't exist and is out of this story's scope.

### AC8 — Lock-acquisition-order call-order test

**Decision:** the project owner chose the lightweight test option. **Confirmed during research: no live
deadlock scenario exists today** — `RescheduleService.acceptReschedule` is the only one of the three methods
named in the original ledger item (`deferred-work.md`, `skillars-deferred-58` review heading) that takes more
than one lock in a transaction (reschedule-request row, then coach-profile row, order documented in a code
comment at `RescheduleService.java:184-189`); `BookingDuplicationService.duplicateNextWeek` and
`CoachProfileService.saveStep4` each take exactly one lock (the coach-profile row only). **There is nothing
to deadlock among these three today** — this AC documents and guards the *convention*, not a live bug.

Add a unit test to `RescheduleServiceTest.java` asserting `acceptReschedule`'s two lock acquisitions happen in
the documented order (reschedule-request row before coach-profile row), via Mockito's `InOrder` verification
against the mocked `rescheduleRepo.findByIdForUpdate(...)` and `coachProfileRepository.findByIdForUpdate(...)`
calls — a call-order assertion, not a concurrency/deadlock reproduction (not feasible against current code,
since nothing here actually contends). Name it something like
`acceptReschedule_lockAcquisitionOrder_rescheduleRequestBeforeCoachProfile`. Do not attempt to build a
generic cross-method lint/harness — that remains out of scope, as the original ledger item's own text already
concluded ("both larger than a single bounded fix").

### AC9 — `RescheduleService.acceptReschedule` concurrency IT

**Decision:** the project owner chose to close this test-coverage gap. `skillars-deferred-62`'s own code
review explicitly scoped this out at the time (`SessionPackPurchaseLockContentionIT.java`'s class doc
comment, `:38-40`: *"the other three `findByIdForUpdate` repositories share the identical
`PessimisticLockRetryer` helper, so ... they rely on this coverage rather than each duplicating a full
concurrency IT"*) but flagged `acceptReschedule` specifically as the one worth testing directly, since its
two-sequential-locks-in-one-transaction shape is more complex than the single-lock case
`SessionPackPurchaseLockContentionIT` proves.

Add `RescheduleServiceConcurrencyIT.java` (or a suitably named new IT class), mirroring
`SessionPackPurchaseLockContentionIT`'s structure: extend the same IT base class it uses, use
`ExecutorService`/`CountDownLatch`/`Future` to hold a real `SELECT ... FOR UPDATE` on either the
`BookingRescheduleRequest` row or the `CoachProfile` row from a background thread while the main thread calls
`acceptReschedule`, asserting the contending call either succeeds within `PessimisticLockRetryer`'s bounded-
retry budget (brief contention) or fails cleanly with `OperationNotAllowedException`/
`BookingError.CONCURRENT_MODIFICATION`-shaped rejection (prolonged contention) — mirror whichever of
`SessionPackPurchaseLockContentionIT`'s existing test methods matches each case. Fixture needs a `Booking` +
`BookingRescheduleRequest` (status `PENDING`) + `CoachProfile`, all real rows via Testcontainers Postgres (not
mocked — this is an IT, matching the sibling class's real-DB-contention approach).

### AC10 — Remove dead `completionLoading`/`completionError` state (TD-1 correction)

See "Corrections found during scoping" above — confirmed zero consumers repo-wide. Remove
`const completionLoading = ref(false)` / `const completionError = ref(null)`
(`booking.store.js:149-150`), every `completionLoading.value = .../completionError.value = ...` assignment
across the 10 handlers that set them (`:423-565`, all matching the `completionLoading.value = true` /
`completionError.value = null` / `completionError.value = e` / `completionLoading.value = false` 4-line
pattern), and the two export lines (`:696-697`). Do not replace with a `Map`-based per-action-key refactor —
there is no live consumer to preserve behavior for; each handler's own `try`/`catch`/`finally` structure and
`throw e` (which every handler already does) is what callers actually rely on.

### AC11 — Ledger hygiene

Update `deferred-work.md`:
- **`## Deferred from: skillars-deferred-68 CI run (2026-08-25)`**: flip the midnight-crossing bullet to
  `[CLOSED by skillars-deferred-69 AC1-AC2: BookingService.isSlotWithinAvailabilityWindow now throws
  BookingError.SESSION_CROSSES_MIDNIGHT for a session whose end falls on a different calendar day than its
  start against an otherwise-matching window, instead of silently failing to a generic false; RescheduleResourceIT's
  wall-clock-relative Instant.now().plus(N, DAYS) time helper (root cause of the original CI flake) replaced
  with a fixed-safe-hour helper]` with a one-line mechanism note matching the actual fix, per this ledger's
  established convention.
- **`## Deferred from: code review of skillars-uat-3-... (2026-08-11)` D4** (parent-cancel UI): close with a
  citation to AC3.
- **`## Deferred from: skillars-uat-5-player-self-booking story creation (2026-08-12)` D3** (self-booking
  player lifecycle): close with a citation to AC4, noting the scope actually shipped (5 backend gates + 2
  frontend gates widened; disputes not independently re-verified).
- **`## Deferred from: code review of skillars-3-8-... (2026-06-16)` D5** (coach-initiated reschedule): close
  with a citation to AC5.
- **`## Deferred from: code review of skillars-deferred-15-... (2026-08-05)` D4** (batch-status race): close
  with a citation to AC6.
- **`## Deferred from: code review of skillars-uat-2-... — Group A (2026-08-10)`** (duration/availability
  staleness): close the first bullet with a citation to AC7, explicitly noting it narrows rather than
  eliminates the window (per AC7's own scope note).
- **`## Deferred from: code review of skillars-deferred-58-... (2026-08-24)`** (lock-ordering item): close
  with a citation to AC8, explicitly noting no live deadlock scenario was found to exist.
- **`## Deferred from: code review of skillars-deferred-62-... (2026-08-24)`** (IT-coverage gap item): close
  with a citation to AC9.
- **`## Deferred from: code review of skillars-deferred-8 (2026-07-02)` D1** (resolveParentName "null null"):
  **already done at story-creation time (2026-08-26)** — deleted outright as stale, verified against
  `BookingService.java:859-868`. No dev-agent action needed for this bullet.

Update `tech-debt.md`:
- **TD-1**: mark resolved — cite AC10 once it ships, note the resolution was deletion of dead state, not the
  originally-proposed per-action-key refactor, since the clobbering scenario TD-1 described is no longer
  reachable through the UI (zero consumers found). **Not yet done** — TD-1's underlying dead code still
  exists until AC10 ships; do this as part of AC10/AC11, not before.
- **TD-2**: **already done at story-creation time (2026-08-26)** — marked resolved-by-existing-infrastructure,
  citing `EnvelopeEntity`/`MailManager`/`EmailRetryScheduler`. No dev-agent action needed for this bullet.

## Tasks / Subtasks

- [x] Task 1: Cross-midnight rejection (AC1)
  - [x] 1.1: Fix `isSlotWithinAvailabilityWindow`, correct the misleading doc comment
  - [x] 1.2: Add `BookingError.SESSION_CROSSES_MIDNIGHT` + doc comment paragraph
  - [x] 1.3: i18n — 3 backend locale files + 3 frontend locale files
  - [x] 1.4: Frontend error-key branch(es) in `ParentBookingsPage.vue` (and sibling pages if they have the
        equivalent `slotOutsideAvailability` branch)
- [x] Task 2: Test-flakiness root cause fix (AC2)
  - [x] 2.1: Add `safeProposedStart(int)` helper to `RescheduleResourceIT.java`
  - [x] 2.2: Replace every `Instant.now().plus(N, ChronoUnit.DAYS)` call site in the file
- [x] Task 3: Parent booking-cancel UI (AC3)
  - [x] 3.1: `booking.store.js` — `handleCancelBooking` action
  - [x] 3.2: `ParentBookingsPage.vue` — Cancel button + confirmation dialog + error-key branches
- [x] Task 4: Self-booking player parity (AC4)
  - [x] 4.1: Widen 5 backend `@PreAuthorize` annotations to `HAS_PARENT_OR_PLAYER_ROLE`
  - [x] 4.2: Confirm each underlying service method's ownership check is self-booking-compatible
  - [x] 4.3: Widen 2 frontend `v-if` gates in `ParentBookingsPage.vue`
- [x] Task 5: Coach-initiated reschedule (AC5)
  - [x] 5.1: `RescheduleService` — extract shared validation, add `requestRescheduleAsCoach`
  - [x] 5.2: `RescheduleService` — add `CANNOT_RESPOND_TO_OWN_PROPOSAL`, guard existing accept/decline
  - [x] 5.3: `RescheduleService` — extract shared accept body, add `acceptRescheduleAsParent`,
        `declineRescheduleAsParent`
  - [x] 5.4: `RescheduleResource` — 3 new endpoints
  - [x] 5.5: New events (`RescheduleRequestedByCoachEvent`, `RescheduleDeclinedByParentEvent`) + 2 new
        `BookingEmailListener` methods + 2 new `EmailTemplate` constants + 2 new HTML templates + i18n
  - [x] 5.6: `booking.api.js` — 3 new exports
  - [x] 5.7: `ParentBookingsPage.vue` — accept/decline buttons for coach-proposed reschedules
  - [x] 5.8: Locate coach booking page; add propose-new-time UI + gate existing accept/decline by
        `proposedBy` (located: `CoachCommandCenterPage.vue`)
- [x] Task 6: `BookingBatchService` batch-status race fix (AC6)
  - [x] 6.1: Add `findByIdForUpdate` to `BookingBatchRepository`
  - [x] 6.2: Lock both write sites (`acceptAll`'s trailing tx, `updateBatchStatusFromBooking`)
- [x] Task 7: `createBatch` staleness narrowing (AC7)
  - [x] 7.1: Extract per-slot validation into a reusable helper
  - [x] 7.2: Re-fetch and re-validate immediately before persist
- [x] Task 8: Lock-ordering call-order test (AC8)
- [x] Task 9: `acceptReschedule` concurrency IT (AC9)
- [x] Task 10: Remove dead `completionLoading`/`completionError` (AC10)
- [x] Task 11: Ledger hygiene — `deferred-work.md` (7 closures pending; the 8th item, the stale
      `resolveParentName` D1, was already deleted at story-creation time) and `tech-debt.md` (TD-1 pending,
      tied to AC10; TD-2 was already marked resolved at story-creation time) (AC11)

## Dev Notes

**This story is large by design — the project owner explicitly rejected splitting it.** Work through the ACs
roughly in the order listed; AC1→AC2 are coupled (don't ship AC1 without AC2, or `RescheduleResourceIT` will
start failing near midnight in CI, reintroducing exactly the flakiness this story is meant to close). AC3/AC4
are independent of everything else and safe to do first if you want an early win. AC5 is the biggest single
chunk — budget the most time there, and expect to need to explore the frontend to find the coach booking page
(not identified during story creation).

**Do not build a new notification outbox for AC11's TD-2 closure** — re-read "Corrections found during
scoping" above if tempted; the infrastructure already exists (`EnvelopeEntity`/`MailManager`/
`EmailRetryScheduler`).

**AC5's event design is deliberate, not arbitrary**: `RescheduleAcceptedEvent` is reused unchanged for both
directions because it already carries both parties' emails; `RescheduleRequestedEvent`/
`RescheduleDeclinedEvent` are NOT reused because each hardcodes a single recipient field for one direction
only — read their constructors before assuming otherwise.

**`isSlotWithinAvailabilityWindow` now throws for one specific case instead of always returning boolean** —
this is a deliberate, minimal-blast-radius design choice (avoids touching 5 call sites) — do not "clean this
up" into a fully exception-based or fully boolean-based method as part of this story.

**AC7 is an honest partial fix, not a claim of full staleness elimination** — say so in the code comment and
in the ledger closure. Do not oversell it.

**Testing standard**: this codebase's convention is `mvn -o test -Dtest=<TouchedClasses>` targeted runs, not
`mvn verify`, which is deliberately not run locally (`docs/validation-strategy.md`) — GitHub CI is the sole
full-verification gate. Run targeted tests per AC as you complete it, not just once at the end, given the
story's size.

**Frontend verification**: given the number of new UI surfaces (cancel dialog, self-booking-player-visible
buttons, coach-side propose dialog, parent-side accept/decline), use the `run` skill to stand up the local
stack and manually verify at least: a self-registered player's own bookings page shows all four buttons
(cancel, request-change, confirm-completion, and — once a coach proposes — accept/decline), and a coach
proposing a reschedule end-to-end updates the parent's view correctly. `npx eslint` clean is necessary but not
sufficient for this much new UI.

### Project Structure Notes

Backend: `platform.booking.service.{BookingService,RescheduleService,BookingBatchService}`,
`platform.booking.api.{CancellationResource,RescheduleResource,SessionCompletionResource,ScheduleResource}`,
`platform.booking.contract.{BookingError,RescheduleRequestedByCoachEvent (new),
RescheduleDeclinedByParentEvent (new)}`, `platform.booking.repo.BookingBatchRepository`,
`platform.notification.contract.EmailTemplate`,
`platform.notification.infrastructure.listener.BookingEmailListener`, plus corresponding test classes and 2
new HTML mail templates (`src/main/resources/mails/`). No new migrations. Frontend:
`src/frontend/src/stores/booking.store.js`, `src/frontend/src/api/booking.api.js`,
`src/frontend/src/pages/parent/ParentBookingsPage.vue`, the not-yet-located coach booking page, i18n files in
both `src/main/resources/messages*.properties` and `src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js`.
Ledger: `deferred-work.md`, `tech-debt.md`.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` — sections cited inline in each AC and in AC11.
- `_bmad-output/implementation-artifacts/tech-debt.md` — TD-1, TD-2.
- `skillars-deferred-62`/`-64`/`-66`/`-67`/`-68` — established the `PessimisticLockRetryer`/
  `findByIdForUpdate`/`entityManager.refresh` locking pattern AC6 extends, and the
  `OptimisticLockingFailureException` → `BookingError.CONCURRENT_MODIFICATION` catch shape referenced (not
  extended — that sweep is already complete per `skillars-deferred-68`) throughout.
- `skillars-uat-5` — established the `HAS_PARENT_OR_PLAYER_ROLE` self-booking-player pattern AC4 extends.
- `skillars-deferred-63`/`-65` — established the per-window coach-editable timezone feature AC1's fix must
  not conflict with (each window's own `canonicalTimezone` is what `isSlotWithinAvailabilityWindow` already
  uses per-iteration; the midnight check reuses the same per-window zone, not a single module-wide one).

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5), via /bmad-dev-story.

### Debug Log References

None — no blocking failures required a separate debug log. Notable in-flight corrections (see Completion
Notes) surfaced during implementation itself, not via a debug session.

### Completion Notes List

- **AC1/AC2**: Fixed the cross-midnight bug and the CI-flake root cause together, as the story required.
  While fixing AC2, discovered a real, previously-passing test (`RescheduleResourceIT
  .requestReschedule_slotOutsideAvailabilityWindow_returns403WithSlotOutsideAvailabilityKey`) whose
  scenario is exactly AC1's flagship bug — post-fix it now throws `SESSION_CROSSES_MIDNIGHT` instead of
  `SLOT_OUTSIDE_AVAILABILITY`. Renamed and updated to assert the corrected behavior rather than leaving it
  silently broken.
- **AC3**: Backend needed no changes, as scoped. `handleCancelBooking` deliberately carries no store-level
  loading/error state (AC10 removes the shared refs this handler could otherwise have reintroduced).
- **AC4**: Widened 5 `@PreAuthorize` gates. Individually verifying each underlying service method's
  ownership check (as the story explicitly required, not assumed) found one real gap:
  `BookingService.getParentPlayerSchedule`'s check assumed every caller has a parent
  (`player.getParentId().equals(parentId)`), which can never be true for a self-booking player — fixed to
  branch on `player.getParentId() == null` the same way `createBookingRequest` already does. This also
  broke a pre-existing regression test (`SessionCompletionResourceIT
  .confirmCompletion_selfRegisteredPlayer_returns403`) for a *different* widened endpoint —
  `confirmCompletion`'s non-owner rejection is `ResourceNotFoundException` (404), not an authorization
  exception, so a self-booking player who doesn't own a given booking now correctly gets 404 instead of the
  403 the old @PreAuthorize gate produced. Renamed and updated that test too.
- **AC5**: The largest chunk, as anticipated. Followed the story's exact extraction guardrails (Issue #2:
  no `parentUserId`/`coachUserId` in the shared validation method; Issue #3: no `@Transactional` on the
  shared accept body). One implementation adjustment beyond the story text: `validateRescheduleProposal`
  and `acceptRescheduleShared` take the raw `bookingId`/`rescheduleId` as explicit parameters rather than
  reading them off the passed entities via `.getId()` — existing unit-test fixtures construct `Booking`/
  `BookingRescheduleRequest` objects without ever setting their `id` field (mirroring the original methods'
  own parameter-based approach), so `.getId()` would have broken 8 previously-passing tests for no
  behavioral gain. Located the coach booking page (`CoachCommandCenterPage.vue`, not identified during
  story creation) and added the propose-new-time dialog plus `proposedBy`-gated accept/decline there.
- **AC6/AC7**: Mechanical, as scoped. AC7's re-fetch is deliberately narrower than full elimination, per
  the story's own framing — documented as such in both the code comment and the ledger closure.
- **AC8**: Confirmed via source read (not assumed) that `BookingDuplicationService.duplicateNextWeek` and
  `CoachProfileService.saveStep4` each take exactly one lock — no live deadlock scenario exists among the
  three named methods today, matching the story's own claim.
- **AC9**: Story text describes the prolonged-contention case as failing with an
  `OperationNotAllowedException`/`CONCURRENT_MODIFICATION`-shaped rejection. Direct read of
  `PessimisticLockRetryer.withBoundedRetry` and both `RescheduleService` accept paths shows retry-budget
  exhaustion re-throws the raw `PessimisticLockingFailureException` unwrapped, uncaught by either method —
  that shape belongs to a different mechanism (`bookingRepository.save`'s `OptimisticLockingFailureException`
  catch, a version conflict at save time). Wrote the new `RescheduleServiceConcurrencyIT` against the
  actually-observed exception type, matching `SessionPackPurchaseLockContentionIT`'s own proven shape, and
  documented the correction in the test class's own Javadoc.
- **AC10**: Removed the shared refs and every handler's boilerplate. Simplified further than the story's
  literal instruction ("keep each handler's own try/catch/finally structure") — once the shared-ref
  assignments are gone, several handlers were left with a bare `try { await x() } catch (e) { throw e }`
  that changes nothing over a plain `await x()`; removed that dead wrapper too rather than leaving
  pointless ceremony.
- **AC11**: All 7 open `deferred-work.md` closures applied with citations; the 8th (stale
  `resolveParentName` D1) was already deleted at story-creation time, confirmed still absent. `tech-debt.md`
  TD-1 marked resolved by deletion (not the originally-proposed `Map` refactor, since AC10 confirmed the
  clobbering scenario is unreachable); TD-2 was already marked resolved at story-creation time.
- **Testing**: Targeted `mvn -o test -Dtest=...` runs throughout, per `docs/validation-strategy.md` — no
  `mvn verify`. Comprehensive final sweep across every touched booking-module test class (191 tests) plus
  the notification listener tests, all green. **Frontend manual verification was not performed**: the local
  stack (`docker-compose.local.yml`) requires a full Docker image build plus `SPRING_MAIL_PASSWORD`/
  `GMAIL_PASSWORD`/`GMX_PASSWORD` secrets not available in this environment, and no lighter-weight
  frontend-only dev-server pattern exists as a project skill. Frontend correctness for the new UI surfaces
  (cancel dialog, self-booking-player-visible buttons, coach-side propose dialog, parent-side accept/
  decline) rests on `npx eslint` (clean across every touched file) and on backend integration tests that
  exercise the exact HTTP responses those components branch on — not on an actual browser click-through, as
  the story's Dev Notes asked for. Flagged explicitly rather than claimed as done.
- **Review follow-up**: Addressed all 6 `[Review][Patch]` findings. 3 confirmed real and fixed —
  `acceptRescheduleAsParent`/`declineRescheduleAsParent` widened to `HAS_PARENT_OR_PLAYER_ROLE` (a genuine
  AC4 gap: `ParentBookingsPage.vue`'s coach-proposed accept/decline buttons carry no role gate, so a
  self-booking player already reached the old parent-only endpoints); a post-lock freshness re-check added
  to `acceptRescheduleShared` (the pre-lock-only check could go stale under lock contention); a `:loading`/
  `:disable` binding added to `CoachCommandCenterPage.vue`'s propose-dialog submit button. 3 verified as
  false positives after direct source/schema checks, not blindly implemented — `proposed_by` is DB-level
  `NOT NULL CHECK (... IN ('PARENT','COACH'))` so a null-bypass can't occur; a booking's `coachId` is set
  once at creation and never mutated, so "coach reassignment" isn't reachable; and `datetime-local` strings
  are ECMA-262-defined as local time (not UTC), matching the already-shipped `ParentBookingsPage.vue`
  pattern this dialog mirrors. Logged the one adjacent gap found while fixing the loading-state item
  (`ParentBookingsPage.vue`'s own two dialogs have the same missing binding, pre-existing and out of scope
  for that finding) as `tech-debt.md` TD-4 rather than leaving it unlisted. All 4 `[Review][Defer]` items
  were pre-accepted, no action needed. Re-ran the full targeted sweep (backend, 201 tests) plus `npx eslint`
  on both touched frontend files — all green, no regressions.

### File List

**Backend — main:**
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/CancellationResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/RescheduleResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/SessionCompletionResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/ScheduleResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/RescheduleRequestedByCoachEvent.java` (new)
- `src/main/java/com/softropic/skillars/platform/booking/contract/RescheduleDeclinedByParentEvent.java` (new)
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingBatchRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java`
- `src/main/java/com/softropic/skillars/platform/notification/contract/EmailTemplate.java`
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/BookingEmailListener.java`
- `src/main/resources/mails/bookingRescheduleRequestedByCoach.html` (new)
- `src/main/resources/mails/bookingRescheduleDeclinedByParent.html` (new)
- `src/main/resources/i18n/messages.properties`
- `src/main/resources/i18n/messages_en.properties`
- `src/main/resources/i18n/messages_de.properties`
- `src/main/resources/i18n/messages_fr.properties`

**Backend — test:**
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceConcurrencyIT.java` (new)
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/SessionCompletionResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/BookingEmailListenerTest.java`

**Frontend:**
- `src/frontend/src/pages/parent/ParentBookingsPage.vue`
- `src/frontend/src/pages/parent/BookingRequestPage.vue`
- `src/frontend/src/pages/coach/CoachCommandCenterPage.vue`
- `src/frontend/src/stores/booking.store.js`
- `src/frontend/src/api/booking.api.js`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/de-DE/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`

**Ledger:**
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/planning-artifacts/tech-debt.md`

## Change Log

- 2026-08-26: Story created via story-creation process. Re-mined `deferred-work.md` for the
  Booking/Availability/Reschedule module per the project owner's explicit instruction to bundle a large
  story rather than several small ones. Scoping required a full pass over ~1600 lines of ledger history plus
  live source verification (a background research pass confirmed 3 ledger items were stale/already-fixed —
  TD-2's outbox already exists, `resolveParentName` already fixed, TD-1's clobbering bug is unreachable dead
  code — see "Corrections found during scoping"). Four items required a project-owner decision, gathered
  interactively: (1) reject cross-midnight sessions outright rather than support them (AC1); (2) fold in all
  three larger feature gaps — parent-cancel UI, self-booking player parity, coach-initiated reschedule — with
  none deferred (AC3-AC5); (3) TD-2 chosen as outbox+poller, which a second research pass then found already
  built, changing AC11's TD-2 item from "build" to "record already resolved"; (4) lock-ordering fixed via a
  lightweight enforced test (AC8), with research confirming no live deadlock scenario exists among the three
  named methods today. AC6, AC7, AC9, AC10 are additional verified-open technical items folded in as
  "mechanical fixes that go in regardless," per the scoping conversation's own framing. AC5 (coach-initiated
  reschedule) is confirmed the largest and least mechanical item — its event/endpoint design was decided
  during story creation (separate per-actor methods and endpoints, mirroring this codebase's established
  convention) rather than left for the dev agent to invent, but its frontend coach-side location was not
  found and is left for the dev agent to locate.
- 2026-08-26: Story-review complete (`story-review.md`), status remains ready-for-dev. 19 issues raised, most
  either already-adequate story guidance the reviewer flagged for visibility (e.g. AC5's extraction risk,
  AC5's unlocated coach page, AC9's concurrency-IT complexity — all already explicitly called out in the
  story text) or genuinely resolved on direct re-verification against source rather than left open: AC1's
  timezone-consistency concern (Issue #1) is a false positive — `startZdt`/`endZdt` already share the same
  per-window `zoneId` variable, no conversion needed, now stated explicitly in the story. AC6's `lockRetryer`
  injection (Issue #12) confirmed already present in `BookingBatchService`'s field list. AC5's three event
  constructors (Issues #10, #11) — `RescheduleRequestedEvent`, `RescheduleAcceptedEvent`,
  `RescheduleDeclinedEvent` — read in full and their exact field shapes (including confirming
  `RescheduleAcceptedEvent` already carries both `parentEmail` and `coachEmail`, making it safely reusable
  unchanged) pasted directly into the story rather than left for the dev agent to discover. AC5's two
  extraction risks (Issues #2, #3) got explicit guardrails: the validation extraction must not swallow the
  one real difference between the two callers (`parentUserId`), and the accept/decline extraction must not
  gain its own `@Transactional` (would break `PessimisticLockRetryer`'s savepoint-based retry-in-place
  design). **One genuine, previously-wrong finding corrected** (Issue #8): AC3's original error-mapping
  guidance told the dev agent to branch on `errorKey === 'booking.paymentInProgress'` for the capture-in-
  flight interlock rejection — re-reading `cancelBookingAsParent`'s full body plus `ApiAdvice
  .responseStatusExceptionHandler` (`:118-122`) shows that rejection is a raw `ResponseStatusException`, not
  an `OperationNotAllowedException`, and `ApiAdvice` hardcodes its `errorKey` to the generic
  `"generic.requestError"` (which has no i18n bundle entry in any locale) — the actual reason string
  (`"booking.paymentInProgress"`) only ever reaches the frontend in `errorMsg.message`, not `errorMsg
  .errorKey`. The original branch condition would never have matched; AC3 now specifies the correct
  `message`-based check. This would have been a real, silent bug (the payment-in-progress case silently
  falling through to a generic toast) had it shipped as originally drafted.
- 2026-08-26: dev-story implementation complete, status review. All 11 tasks / AC1-AC11 done. Two real,
  previously-passing tests broke as a direct, correct consequence of this story's own fixes and were
  updated rather than left red: `RescheduleResourceIT`'s late-night-proposal test now expects
  `SESSION_CROSSES_MIDNIGHT` instead of the old silent `SLOT_OUTSIDE_AVAILABILITY` (AC1's flagship fix);
  `SessionCompletionResourceIT`'s self-registered-player regression test now expects 404 instead of 403,
  since `confirmCompletion`'s non-owner rejection is a `ResourceNotFoundException`, not an authorization
  exception, and AC4 deliberately let a self-booking player reach that check. One additional real gap found
  during AC4's individually-verify-every-method requirement: `BookingService.getParentPlayerSchedule`
  assumed every caller has a parent and was unreachable for a self-booking player — fixed to mirror
  `createBookingRequest`'s existing `parentId`-vs-`userId` branch. 191 targeted backend tests green
  (unit + integration, real Postgres via Testcontainers, including a new `RescheduleServiceConcurrencyIT`
  proving AC9's lock-contention behavior against real row locks) plus 10 notification-listener tests;
  `npx eslint` clean across every touched frontend file. All 7 open `deferred-work.md` items this story's
  ACs close were closed with citations; `tech-debt.md` TD-1 marked resolved. Frontend manual
  browser verification was not performed — flagged explicitly in Completion Notes, not silently skipped.
- 2026-08-26: Addressed all 6 code-review `[Review][Patch]` findings. 3 confirmed real and fixed: widened
  `acceptRescheduleAsParent`/`declineRescheduleAsParent` (`RescheduleResource.java`) to
  `HAS_PARENT_OR_PLAYER_ROLE` — a genuine AC4 gap, since `ParentBookingsPage.vue`'s coach-proposed
  accept/decline buttons carry no role gate and a self-booking player could already reach the old
  parent-only endpoints; added a post-lock `proposedStartTime` freshness re-check to
  `RescheduleService.acceptRescheduleShared`, immediately before persisting the booking's new times;
  added a `:loading`/`:disable` binding to `CoachCommandCenterPage.vue`'s propose-new-time dialog submit
  button. 3 verified as false positives after direct source/schema checks rather than blindly implemented
  (documented inline in Review Findings): the null-`proposedBy` bypass can't occur (DB-level
  `NOT NULL CHECK (... IN ('PARENT','COACH'))`); the "coach reassignment" race can't occur (`coachId` is
  set once at booking creation and never mutated anywhere in the codebase); and the `datetime-local` →
  `toISOString()` conversion is ECMA-262-defined as local time, not ambiguous, matching the already-shipped
  `ParentBookingsPage.vue` pattern it mirrors. Logged the one adjacent gap surfaced while fixing the
  loading-state finding — `ParentBookingsPage.vue`'s own reschedule/cancel dialogs have the identical
  missing binding, pre-existing and out of scope for that finding — as `tech-debt.md` TD-4. All 4
  `[Review][Defer]` items were pre-accepted; no action needed. Re-ran the full targeted sweep (201 backend
  tests) plus `npx eslint` on both touched files — all green, no regressions.

### Review Findings

**Patch findings (require fix before merge):**

- [x] [Review][Patch] Missing Role Widening on New Reschedule Response Endpoints [RescheduleResource.java:64,71] — `acceptRescheduleAsParent` and `declineRescheduleAsParent` endpoints use `HAS_PARENT_ROLE` but frontend allows self-booking players. Should be `HAS_PARENT_OR_PLAYER_ROLE` for AC4 parity. **Confirmed real and fixed**: widened both to `HAS_PARENT_OR_PLAYER_ROLE`. Verified the frontend gap directly — `ParentBookingsPage.vue`'s coach-proposed accept/decline buttons (`v-if="booking.pendingReschedule?.proposedBy === 'COACH'"`) carry no role gate at all, so a self-booking player already reaches them and would have hit a 403. `booking.getParentId()` already equals the self-booking player's own `userId` for these bookings (set once at `createBookingRequest`), so no service-layer change was needed — only the gate.
- [x] [Review][Patch] Null `proposedBy` Field Bypass in Decline Methods [RescheduleService.java:409,449] — **Verified false positive, no code change**: `proposed_by` is `VARCHAR(10) NOT NULL CHECK (proposed_by IN ('PARENT', 'COACH'))` at the DB level (`V35__booking_reschedule_requests.sql`) and the JPA column is `nullable = false`; no row can ever carry a null or other value. `"COACH"/"PARENT".equals(x)` is already null-safe (returns `false`, never throws) so there is no bypass to close — every response check already independently re-validates `PENDING` status and freshness regardless of this field. Adding a null branch would be dead code for a state the schema makes unreachable.
- [x] [Review][Patch] Missing Coach Ownership Re-Validation [RescheduleService.java:250-296,298-395] — **Verified false positive, no code change**: a booking's `coachId` is set exactly once, at creation (`BookingService.createBookingRequest`/`BookingBatchService`), and is never mutated afterward anywhere in the codebase (grepped `setCoachId` across the module) — "concurrent reassignment" isn't a reachable scenario. In `acceptRescheduleAsParent`, `coach` is fetched directly from `booking.getCoachId()` (not caller-supplied), so `coach.getId() == booking.getCoachId()` holds by construction; the locked re-fetch inside `acceptRescheduleShared` locks that same immutable id and already re-checks the coach's suspension status post-lock. A re-validation would always trivially pass.
- [x] [Review][Patch] DateTime Handling in CoachCommandCenterPage [CoachCommandCenterPage.vue:754-765] — **Verified false positive, no code change**: per ECMA-262, a `datetime-local`-shaped string (`YYYY-MM-DDTHH:mm`, no timezone suffix) is well-defined as **local time**, not UTC — only bare *date-only* strings (`YYYY-MM-DD`) parse as UTC. `new Date(...).toISOString()` on this input is unambiguous across all modern engines. This is also the pre-existing, already-shipped pattern in `ParentBookingsPage.vue`'s own reschedule dialog (line 310), which `CoachCommandCenterPage.vue`'s dialog deliberately mirrors.
- [x] [Review][Patch] proposedStartTime Freshness Check Racing [RescheduleService.java:233,277] — `acceptReschedule`/`acceptRescheduleAsParent` only checked freshness on an unlocked pre-lock read. **Confirmed real and fixed**: added a re-check of `lockedReq.getProposedStartTime().isAfter(Instant.now())` inside `acceptRescheduleShared`, immediately before the booking's times are persisted (after the lock is held, alongside the existing post-lock availability/overlap re-checks), throwing `BookingError.START_TIME_IN_PAST` if the proposal has gone stale while waiting on the lock.
- [x] [Review][Patch] Dialog Submit Button Lacks Loading State [CoachCommandCenterPage.vue:193-197] — **Confirmed real and fixed**: added `:loading="rescheduleActionId === coachRescheduleBookingId"` and `:disable="..."` to the submit button (template auto-unwraps both refs, matching the file's existing `:loading="rescheduleActionId === booking.bookingId"` convention). Note: the identical gap pre-exists on `ParentBookingsPage.vue`'s own reschedule and cancel dialog submit buttons (not introduced by this story, out of scope for this finding) — logged as `TD-4` in `tech-debt.md` rather than silently left unlisted.

**Deferred findings (pre-existing or acknowledged limitations, not actionable in this story):**

- [x] [Review][Defer] Unprotected Booking Status Check Before Reschedule Accept [RescheduleService.java:248-253] — deferred, pre-existing. Booking status checked with unlocked read before `acceptRescheduleShared()`. OptimisticLockingFailureException catches concurrent modifications. This is the established pattern in this codebase.
- [x] [Review][Defer] Batch Service Staleness Window Not Fully Closed [BookingBatchService.java:167-195] — deferred, acknowledged. Code already documents that window is narrowed but not eliminated. `CoachAvailabilityWindow` has no versioning. Known limitation.
- [x] [Review][Defer] Batch Lock Retry Timeout Lacks Graceful Degradation [BookingBatchService.java:358,407] — deferred, pre-existing. Lock acquisition timeout without graceful handling. Pre-existing codebase pattern, not introduced by this story.
- [x] [Review][Defer] Frontend Error Handling Delegated Without Contracts [booking.store.js] — deferred, intentional. Removed `completionLoading`/`completionError` tracking (AC10). Design decision; would require spec change to alter.
