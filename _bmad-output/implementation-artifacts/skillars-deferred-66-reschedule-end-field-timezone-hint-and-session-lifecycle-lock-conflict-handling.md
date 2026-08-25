# Story Deferred-66: Reschedule End-Field Timezone Hint & Session-Lifecycle Lock-Conflict Handling

Status: review

## Story

As an engineer operating this platform,
I want two items shipped together — a timezone-clarifying hint on the reschedule dialog's "New session
end" field, and consistent `OptimisticLockingFailureException` handling across every
`BookingCompletionService` method that transitions a booking —
so that the Booking/Availability/Reschedule module's decision-light backlog keeps draining in the same
disciplined, one-bundled-story-at-a-time way the `skillars-deferred-*` series has followed since
`skillars-deferred-59`.

### Why this story exists

This story was scoped by re-mining `deferred-work.md` for items touching the Booking/Availability/
Reschedule module, immediately after `skillars-deferred-65` shipped. Three items looked open from the
ledger text; every one was live-verified against the current tree rather than trusted, per this file's own
repeatedly-stated convention.

**Two of the three turned out to already be fixed, just never annotated:**

- `skillars-deferred-62` review's item that `BookingService.cancelBookingAsParent`'s locked read has no
  `entityManager.refresh(...)` — **already fixed.** `BookingService.java:650` carries
  `entityManager.refresh(booking, LockModeType.PESSIMISTIC_WRITE)` with a comment citing "Deferred-64 AC2".
- `skillars-deferred-58` review's item that `CoachProfileService.saveStep4` never re-checks
  `CoachProfileStatus.SUSPENDED` after its locked refresh — **already fixed.** `CoachProfileService.java:258`
  carries the exact check, with a comment citing "Deferred-64 AC1: mirrors RescheduleService.acceptReschedule's
  and BookingDuplicationService.duplicateNextWeek's identical check".

Both are annotated `[CLOSED by skillars-deferred-64 ...]` in `deferred-work.md` as part of this story's
creation, per this file's own established audit convention (verify against code before trusting an
unannotated forward-reference; annotate the correction whether or not this story does the fixing).

**The third ledger item is genuinely still open and is this story's AC1** — decision-light, a plain UX gap
open since `skillars-3-8` D7 (2026-06-16), most recently deferred by `skillars-deferred-65`'s own code
review (2026-08-25): the reschedule dialog's "New session end" field has no timezone-clarifying hint,
unlike its sibling "New session start" field (which `skillars-deferred-65` AC4 just added one to).

**With the module left thin by those two closures, a broader search — first deeper into
Booking/Availability/Reschedule, then into the neighboring Marketplace/Coach-profile module per this
series' own escalation order — found one fresh, undiscovered defect through direct code reading rather
than from any prior ledger entry.** It is this story's AC2, filed and picked up in the same pass per the
`skillars-deferred-63` precedent:

- **`BookingCompletionService` handles `OptimisticLockingFailureException` inconsistently across its own
  sibling methods.** `Booking` carries `@Version` (optimistic locking, confirmed at `Booking.java:54`).
  `endSession`, `pauseSession`, `resumeSession`, and `confirmCompletion` each wrap their
  `bookingService.transition(...)` call in a `try/catch (OptimisticLockingFailureException e)` that converts
  a concurrent-modification conflict into a clean, retry-able 403. `startSession`, `initiateQuickComplete`,
  and `submitWrapUp`'s `LIVE`-mode branch do not — a concurrent double-click on "Start Session" or "Quick
  Complete" surfaces as a raw, unhandled 500 instead of the same clean 403 every sibling gets.

Everything else this re-mine touched was either already decided (the five `[DECIDED 2026-08-25: ...]`
items `skillars-deferred-64`'s own creation pass settled — dispute contest, per-window timezone drift,
`IN_PROGRESS` no-show, overnight availability windows, `duplicateNextWeek`'s DST shift — none re-opened
here) or explicitly ruled out during this story's own creation-time discussion with the project owner: the
missing "cancel booking" frontend UI (`skillars-uat-3` D4) stays deferred (its own design work is bigger
than a bundled-fix story), the lock-ordering-safety documentation gap (`skillars-deferred-58` review) stays
deferred (the ledger's own text already says a real fix is "larger than a single bounded fix"), and the DRY
duplication of availability-window validation logic across three booking call sites (`skillars-deferred-49`
review) stays deferred (matches this project's established anti-abstraction convention for blocks this
small).

## Acceptance Criteria

1. **The reschedule dialog on `ParentBookingsPage.vue` tells the parent which timezone their read-only
   "New session end" time is displayed in, mirroring the hint `skillars-deferred-65` AC4 already added to
   the "New session start" field right above it.**
   Today (`[src/frontend/src/pages/parent/ParentBookingsPage.vue:99-109]`) the proposed-start `q-input`
   carries `:hint="t('booking.reschedule.startTimezoneHint', { browser: browserTimezone, session:
   rescheduleBookingTimezone })"` (`:100-103`); the read-only proposed-end `q-input` right below it
   (`:107-109`) carries only `:hint="t('booking.reschedule.endDerivedHint')"`, which explains the value is
   auto-derived from start + duration but says nothing about which timezone it is rendered in. Both
   `browserTimezone` and `rescheduleBookingTimezone` are already refs in this component's `<script setup>`
   block (the latter set from `booking.canonicalTimezone` inside `openRescheduleDialog`, per
   `skillars-deferred-65` AC4) — no new state is needed.
   **Fix:** add a second `:hint` line to the proposed-end `q-input`, combining the existing derivation
   explanation with the same timezone statement the start field already carries. **Do not replace
   `endDerivedHint` — concatenate or interpolate both messages into one hint string** (Quasar's `q-input`
   renders only a single `:hint` slot; losing the "this is auto-derived" explanation would reintroduce a
   different point of confusion — why can't I edit this field? — that this field's hint exists to answer).
   Add a new i18n key alongside the existing `reschedule` block
   (`[src/frontend/src/i18n/en-US/index.js:880-894]`, insert near `endDerivedHint` at `:887`) — e.g.
   `endDerivedHintWithTimezone` interpolating `{browser}` and `{session}` and folding in the same
   "auto-derived, keeps original length" wording `endDerivedHint` already carries, so the single combined
   hint says both things. Mirror the same key into `de-DE` and `fr-FR`'s equivalent `reschedule` blocks
   (`[src/frontend/src/i18n/de-DE/index.js:421-434]`, `[src/frontend/src/i18n/fr-FR/index.js:1161-1174]`),
   matching each locale's existing `startTimezoneHint`/`endDerivedHint` wording style rather than inventing
   a new phrasing convention. Update the template to use the new key
   (`:hint="t('booking.reschedule.endDerivedHintWithTimezone', { browser: browserTimezone, session:
   rescheduleBookingTimezone })"`), and remove the now-unused `endDerivedHint` key from all three locale
   bundles only if nothing else references it (grep first — `endDerivedLengthUnavailable` is a distinct key
   and must stay).
   **Tests:** this repository has no frontend test framework (`deferred-17` D6 already records this gap: no
   `*.spec.js` outside `node_modules`, no `src/frontend/test`) — no automated test to add. Verify manually
   via the `run` skill: open a booking's reschedule dialog and confirm both fields now show a timezone-aware
   hint, and that the combined end-field hint still explains the value is auto-derived (not just the
   timezone). **Also check the combined hint doesn't visually overlap or clip** — `skillars-deferred-65`'s
   own manual verification found that Quasar's `.q-field__bottom` hint slot is absolutely positioned and
   does not reserve document-flow space for wrapped text, so a longer two-line hint overlapped the field
   below it until a `q-mb-lg` class was added to the field above; the combined `en-US` hint here is longer
   still, and `de-DE` typically runs ~20% longer than `en-US`, so check `de-DE` specifically (not just
   `en-US`) for wrapping/overlap and adjust spacing the same way if it recurs.

2. **`BookingCompletionService.startSession`, `initiateQuickComplete`, and `submitWrapUp`'s `LIVE`-mode
   branch each catch `OptimisticLockingFailureException` around their `bookingService.transition(...)` call
   and convert it into the same clean, retry-able `OperationNotAllowedException` their sibling methods
   already throw.**
   Today (`[src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java]`)
   `endSession` (`:57-73`), `pauseSession` (`:75-86`), `resumeSession` (`:88-99`), and `confirmCompletion`
   (`:167-189`) each wrap their `bookingService.transition(...)` call in
   `try { ... } catch (OptimisticLockingFailureException e) { throw new
   OperationNotAllowedException(<message>, SecurityError.MISSING_RIGHTS); }`. `startSession` (`:48-55`),
   `initiateQuickComplete` (`:101-112`), and `submitWrapUp`'s `LIVE`-mode transition call (`:147-148`, inside
   `submitWrapUp:114-165`) call `bookingService.transition(...)` with no such guard, so a concurrent
   modification of the same `Booking` row (its `@Version` bumped elsewhere between this method's own
   unlocked `getBookingOrThrow` read and its `transition()` write) surfaces as a raw, unhandled
   `OptimisticLockingFailureException` — a 500, not the clean 403 every other sibling method converts it to.
   **Fix:** wrap each of the three unguarded `bookingService.transition(...)` calls in the identical
   `try/catch (OptimisticLockingFailureException e)` shape the four existing methods use, throwing
   `new OperationNotAllowedException("Booking status changed concurrently — retry",
   SecurityError.MISSING_RIGHTS)` — the exact message `endSession`/`pauseSession`/`resumeSession` already
   use (not `confirmCompletion`'s more specific "Session already confirmed", which is accurate only to that
   one method's own semantics). No new imports are needed: `OptimisticLockingFailureException`,
   `OperationNotAllowedException`, and `SecurityError` are all already imported in this file for the
   existing four catches.
   **Do not add any special-casing around `submitWrapUp`'s prior `completionDataRepository.save(scd)`
   call** (`:139-145`, itself already guarded by its own `DataIntegrityViolationException` idempotency
   catch for a *different* concern — a duplicate `submitWrapUp` request). The whole `submitWrapUp` method is
   `@Transactional`; when the new catch re-throws `OperationNotAllowedException`, Spring rolls back the
   entire transaction — including the just-saved `SessionCompletionData` row — before the exception reaches
   the caller, so a retried request re-runs `submitWrapUp` from scratch rather than tripping the duplicate
   guard on a half-committed state. **Verified, not assumed** (story-review flagged this as the one point
   worth confirming rather than taking on faith): `OperationNotAllowedException extends
   AuthorizationException extends ApplicationException extends RuntimeException`
   (`[src/main/java/com/softropic/skillars/infrastructure/exception/ApplicationException.java]`), and none
   of `submitWrapUp`'s or the four existing methods' bare `@Transactional` annotations carry a
   `rollbackFor`/`noRollbackFor` override — so Spring's default rollback-on-any-unchecked-exception applies
   uniformly, exactly as it already does for the four existing catch-and-rethrow sites. This is the same
   reasoning that already makes those four sites safe; it does not change under this AC.
   **Tests:** extend `BookingCompletionServiceTest.java`
   (`[src/test/java/com/softropic/skillars/platform/booking/service/BookingCompletionServiceTest.java]`) with
   three new cases, one per newly-guarded call site, each mocking `bookingService.transition(...)` to throw
   `OptimisticLockingFailureException` and asserting the call throws `OperationNotAllowedException` instead
   (mirror `startSession_bookingNotUpcoming_throwsException`'s `assertThatThrownBy` shape at `:164-171`):
   `startSession_concurrentModification_throwsRetryableException`,
   `initiateQuickComplete_concurrentModification_throwsRetryableException`, and
   `submitWrapUp_liveMode_concurrentModification_throwsRetryableException` (the last needs
   `completionDataRepository.save(any())` stubbed to return its argument first, same as
   `submitWrapUp_liveMode_completesBookingAndDeductsCredit` at `:88-102`, so the method reaches the
   transition call before the mocked exception fires). **Do not** add equivalent tests for the four
   already-guarded methods (`endSession`/`pauseSession`/`resumeSession`/`confirmCompletion`) — none exist
   today either, and backfilling coverage for code this story does not touch is a separate test-hygiene pass,
   not this AC's scope.

## Tasks / Subtasks

- [x] Task 1: Reschedule end-field timezone hint (AC1)
  - [x] 1.1: Add `endDerivedHintWithTimezone` i18n key to `en-US`, `de-DE`, `fr-FR`, combining the existing
        derivation explanation with the timezone statement
  - [x] 1.2: Update the proposed-end `q-input`'s `:hint` in `ParentBookingsPage.vue` to use the new key,
        interpolating `browserTimezone`/`rescheduleBookingTimezone`
  - [x] 1.3: Remove the now-orphaned `endDerivedHint` key from all three locale bundles, after confirming
        (grep) nothing else references it
  - [x] 1.4: Manual verification via the `run` skill (no frontend test framework exists)
- [x] Task 2: `BookingCompletionService` lock-conflict handling consistency (AC2)
  - [x] 2.1: Wrap `startSession`'s `transition()` call in the established `try/catch
        (OptimisticLockingFailureException)` shape
  - [x] 2.2: Wrap `initiateQuickComplete`'s `transition()` call the same way
  - [x] 2.3: Wrap `submitWrapUp`'s `LIVE`-mode `transition()` call the same way
  - [x] 2.4: Add three new `BookingCompletionServiceTest` cases, one per newly-guarded call site
- [x] Task 3: Ledger hygiene — already applied during this story's creation (2026-08-25), no dev-story work
      needed for this task. `deferred-work.md`'s `skillars-deferred-62` review and `skillars-deferred-58`
      review sections were annotated `[CLOSED by skillars-deferred-64 ...]` for the two items verified
      already fixed; the `skillars-deferred-65` review section's reschedule-end-hint item and this story's
      own freshly-filed `BookingCompletionService` item are both tagged `[PICKED UP by skillars-deferred-66
      ACn]`. **Once AC1/AC2 actually ship, flip both `[PICKED UP]` tags to `[CLOSED by skillars-deferred-66
      ACn: ...]`** citing the exact fix, per this ledger's own established PICKED-UP-at-creation /
      CLOSED-at-shipment convention — do not flip them before the code lands. Both tags flipped to
      `[CLOSED by skillars-deferred-66 ACn: ...]` in this dev pass.

## Dev Notes

**AC1 is a one-field, one-i18n-key change — resist scope creep.** `browserTimezone` and
`rescheduleBookingTimezone` already exist and are already correct (proven by AC4 of `skillars-deferred-65`,
which uses them on the sibling field). This AC only needs a second hint string and a template edit; no new
computed state, no backend change.

**AC2 is a three-call-site, copy-the-existing-pattern change — do not redesign the concurrency handling.**
The four existing catches are the spec. Do not introduce a different message, a different exception type,
or a retry loop; match what `endSession`/`pauseSession`/`resumeSession` already do exactly. The transaction-
rollback reasoning documented in AC2 above (why `submitWrapUp`'s prior `save()` is safe to let roll back) is
there so the dev agent does not invent unnecessary idempotency handling around it.

**Both ACs are independent — no shared files, no ordering dependency.** AC1 touches only
`ParentBookingsPage.vue` and the three locale bundles; AC2 touches only `BookingCompletionService.java` and
its test file. They may be implemented and verified in either order.

**Existing test file to extend (do not create a new one):** `BookingCompletionServiceTest.java` (AC2).

### Project Structure Notes

Backend: `platform.booking.service` (`BookingCompletionService` — AC2). Frontend:
`src/frontend/src/pages/parent/ParentBookingsPage.vue` and the three
`src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js` locale bundles (AC1). No new migrations — both ACs are
code-only (i18n/template change, exception-handling change), no schema change.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` — source of AC1; AC2 was filed directly into this
  ledger during this story's own creation (see the "Why this story exists" section above for the exact
  investigation and the two stale-item closures).
- `skillars-deferred-65` AC4 — added the sibling `startTimezoneHint` this story's AC1 mirrors, and the
  `browserTimezone`/`rescheduleBookingTimezone` refs AC1 reuses.
- `skillars-deferred-64` AC1/AC2 — the two fixes this story's creation found already closed the
  `skillars-deferred-58` and `skillars-deferred-62` review items, unannotated until this story's ledger
  hygiene pass.

## Dev Agent Record

### Implementation Plan

- **AC1:** Added `endDerivedHintWithTimezone` to `en-US`/`de-DE`/`fr-FR`'s `reschedule` i18n block,
  combining the existing "auto-derived, keeps original length" wording with the same
  browser/session-timezone statement `startTimezoneHint` already uses. Updated the proposed-end `q-input` in
  `ParentBookingsPage.vue` to use the new key, interpolating the same `browserTimezone`/
  `rescheduleBookingTimezone` refs the sibling field already uses. Confirmed via grep that `endDerivedHint`
  had no other references, then removed it from all three locale bundles. Added `q-mb-lg` alongside the
  existing `q-mt-sm` on the proposed-end field to reserve bottom space for the now-longer, potentially
  two-line hint — mirroring the exact fix `skillars-deferred-65` already applied to the field above for the
  identical Quasar `.q-field__bottom` absolute-positioning overlap issue.
- **AC2:** Wrapped the three previously-unguarded `bookingService.transition(...)` calls
  (`startSession`, `initiateQuickComplete`, `submitWrapUp`'s `LIVE`-mode branch) in
  `BookingCompletionService` with the identical `try/catch (OptimisticLockingFailureException e)` shape the
  four existing methods use, throwing `OperationNotAllowedException("Booking status changed concurrently —
  retry", SecurityError.MISSING_RIGHTS)`. No new imports needed — all three types were already imported.
  Added three new test cases to `BookingCompletionServiceTest`, one per newly-guarded call site, each mocking
  `bookingService.transition(...)` to throw `OptimisticLockingFailureException` and asserting
  `OperationNotAllowedException` is thrown instead.
- Flipped both `[PICKED UP by skillars-deferred-66 ACn]` tags in `deferred-work.md` to
  `[CLOSED by skillars-deferred-66 ACn: ...]`, citing the exact fix, per the ledger's own convention.

### Completion Notes

- Targeted backend tests: `mvn test -Dtest=BookingCompletionServiceTest` (skipping the frontend build via
  `-DskipFrontend=true`, per this repo's Maven/frontend-plugin wiring) — **10/10 passed** (7 pre-existing + 3
  new), 0 failures, 0 errors.
- Frontend validation: `npx eslint` on the four changed files (`ParentBookingsPage.vue`,
  `en-US/index.js`, `de-DE/index.js`, `fr-FR/index.js`) — clean, no errors. `npx quasar build` — **build
  succeeded**, confirming the template and all three i18n bundles compile with no syntax/reference errors.
- Per `docs/validation-strategy.md`, `mvn verify`/the full suite was not run locally — GitHub CI is the
  authoritative full-verification gate. This story's changes are narrowly scoped (one service class, one
  template, three locale bundles) and targeted tests directly cover both ACs.
- **AC1 manual UI verification (honest note on scope):** this repo has no browser-automation tooling
  installed (no `chromium-cli`, no Playwright browsers) and no seed/fixture data or documented local-dev
  bootstrap for reaching a real parent-login → open-reschedule-dialog flow, so a live-browser click-through
  (as the story's Tests subsection asks for) was not performed. In its place: (1) `npx quasar build`
  succeeding confirms the template/i18n wiring is syntactically and referentially correct; (2) the combined
  hint string content was reviewed directly and does explain both the auto-derivation and the timezone in a
  single sentence, for all three locales; (3) the overlap/clipping risk the story specifically calls out was
  addressed by applying the exact same `q-mb-lg` spacing fix `skillars-deferred-65` already proved works for
  this identical Quasar `.q-field__bottom` mechanism on the sibling field, rather than by re-discovering it
  via a fresh manual pass. A human should do a quick visual pass in a running environment before merge to be
  fully certain, particularly for `de-DE`'s longer text.

## File List

- `src/frontend/src/pages/parent/ParentBookingsPage.vue` — modified (AC1: proposed-end `q-input`'s `:hint`
  now uses `endDerivedHintWithTimezone`; added `q-mb-lg` spacing)
- `src/frontend/src/i18n/en-US/index.js` — modified (AC1: `endDerivedHint` → `endDerivedHintWithTimezone`)
- `src/frontend/src/i18n/de-DE/index.js` — modified (AC1: `endDerivedHint` → `endDerivedHintWithTimezone`)
- `src/frontend/src/i18n/fr-FR/index.js` — modified (AC1: `endDerivedHint` → `endDerivedHintWithTimezone`)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java` — modified
  (AC2: `startSession`, `initiateQuickComplete`, `submitWrapUp`'s `LIVE`-mode branch now catch
  `OptimisticLockingFailureException`; review follow-up: cause now chained via the new
  `OperationNotAllowedException` constructor, plus a documenting comment on the `MISSING_RIGHTS` choice)
- `src/main/java/com/softropic/skillars/platform/security/contract/exception/OperationNotAllowedException.java`
  — modified (review follow-up: added `(String message, Throwable cause, ErrorCode errorCode)` constructor,
  purely additive)
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingCompletionServiceTest.java` —
  modified (AC2: 3 new test cases; review follow-up: strengthened all 3 with
  `.hasCauseInstanceOf(OptimisticLockingFailureException.class)`)
- `_bmad-output/implementation-artifacts/deferred-work.md` — modified (ledger hygiene: both
  `[PICKED UP by skillars-deferred-66 ACn]` tags flipped to `[CLOSED by skillars-deferred-66 ACn: ...]`;
  review follow-up: 2 new deferred items filed under the existing "code review of skillars-deferred-66"
  section for the `MISSING_RIGHTS` semantics and the remaining 4 unchained catch blocks)

## Change Log

| Date | Description |
|------|-------------|
| 2026-08-25 | Story created via story-creation process. Re-mine of `deferred-work.md` scoped to the Booking/Availability/Reschedule module found 3 items; live-verification against current source found 2 of the 3 already fixed by `skillars-deferred-64`, unannotated (both now tagged `[CLOSED by skillars-deferred-64 ...]` in the ledger). The module was left thin by those two closures; a broader search (first deeper into Booking/Availability/Reschedule, then into Marketplace/Coach-profile per this series' escalation order) found one fresh defect through direct code reading — an `OptimisticLockingFailureException`-handling inconsistency across `BookingCompletionService`'s own sibling methods — filed and picked up in the same pass. Final scope: 2 ACs (1 from the ledger, 1 freshly found), both decision-light. Three additional candidate items (the missing cancel-booking frontend UI, the lock-ordering-safety documentation gap, and the DRY-duplicated availability-window validation logic) were explicitly discussed with and declined by the project owner during this story's creation — see "Why this story exists" for detail. |
| 2026-08-25 | Story-review adjustments applied (`story-review.md`), status remains ready-for-dev. 1 High finding fixed, 1 finding independently disproven, 2 findings incorporated as guidance, rest dismissed as false or out of scope. **AC2 (High, verified not assumed):** the review correctly flagged that AC2's transaction-rollback safety claim (why `submitWrapUp`'s prior `completionDataRepository.save(scd)` is safe to let roll back) rested on an unverified assumption. Checked directly: `OperationNotAllowedException extends AuthorizationException extends ApplicationException extends RuntimeException`, and neither `submitWrapUp` nor the four existing catch-and-rethrow methods override rollback behavior on their bare `@Transactional` — Spring's default rollback-on-unchecked-exception applies uniformly. AC2's text now cites this directly instead of asserting it. **AC1 (Medium, disproven):** the review's "timezone null-safety" concern (`booking.canonicalTimezone` might be null, producing an empty hint) is the identical false positive `skillars-deferred-65`'s own code review already raised and dismissed — `V31__booking_requests.sql:14` declares `canonical_timezone VARCHAR(50) NOT NULL`. Not incorporated. **AC1 (Medium, real, incorporated):** the review's hint-truncation/wrapping concern has real precedent — `skillars-deferred-65`'s own manual verification found and fixed exactly this failure mode (a longer hint overlapping the field below it, since Quasar's `.q-field__bottom` doesn't reserve flow space). AC1's Tests section now explicitly calls this out, including checking `de-DE` specifically (typically ~20% longer than `en-US`). **AC1 (Medium, dismissed):** the review's "hint display inconsistency" finding — that combining both messages on the end field while the start field has only one "violates the mirroring principle" — misreads the design: each field's hint carries exactly what that field needs (the end field is read-only and needs its own derivation explained, which the start field does not), not a symmetric copy of the other field's hint. AC1 already explains this reasoning inline; not changed. **AC2 (Low, dismissed):** `SecurityError.MISSING_RIGHTS`'s semantic fit for a concurrency conflict — the review itself notes this matches the four existing methods' own established choice; consistency with existing code is the point, already stated in AC2. **Dismissed as out of scope, matching this story's own stated boundaries:** client retry/double-click-prevention UX (a pre-existing property of all four already-guarded sibling methods, not something this story's backend-only fix changes), backfilling tests for the four already-guarded methods (AC2 already explicitly excludes this as a separate test-hygiene pass), and the two "missed flow" scenarios describing normal, already-correct 403 behavior rather than new gaps. |
| 2026-08-25 | Dev implementation complete. AC1: added `endDerivedHintWithTimezone` i18n key (en-US/de-DE/fr-FR) combining the existing auto-derived explanation with the timezone statement; `ParentBookingsPage.vue`'s proposed-end field now uses it, plus `q-mb-lg` spacing mirroring `skillars-deferred-65`'s proven overlap fix. AC2: `BookingCompletionService.startSession`, `initiateQuickComplete`, and `submitWrapUp`'s `LIVE`-mode branch now each catch `OptimisticLockingFailureException` and convert it to `OperationNotAllowedException`, matching the four already-guarded sibling methods; 3 new `BookingCompletionServiceTest` cases added (10/10 tests passing). Ledger hygiene: both `[PICKED UP]` tags in `deferred-work.md` flipped to `[CLOSED by skillars-deferred-66 ACn: ...]`. AC1's live-browser manual verification was not fully performed (no browser-automation tooling or seed data available in this environment for a real login → reschedule-dialog flow); frontend build success, i18n content review, and reuse of the proven spacing fix stand in its place — flagged in Dev Agent Record for a human visual pass before merge. Status set to review. |
| 2026-08-25 | Code review complete. Acceptance Auditor: ✅ AC1 PASS, ✅ AC2 PASS. Blind Hunter + Edge Case Hunter found 5 actionable issues (see Review Findings below). |
| 2026-08-25 | Review follow-ups resolved. 2 of 5 "Patch" findings disproven with evidence: the "test mock mismatch" claim doesn't match `transition()`'s actual signature (`UUID bookingId`, not a `Booking` instance — mocks were already correct); the "unverified i18n parameter" claim is the same false-positive class the prior `skillars-deferred-65` review already raised and dismissed (`rescheduleBookingTimezone` is set before the dialog opens, from a `NOT NULL` DB column; `browserTimezone` is a synchronous always-populated const). 3 of 5 addressed: added an `OperationNotAllowedException(String, Throwable, ErrorCode)` constructor (purely additive) and chained the original `OptimisticLockingFailureException` as cause in the 3 new catch sites this story introduced (fixes "Missing Exception Chaining" and its duplicate "Lost Stack Traces" for the code this story owns); added a one-line comment to those same 3 sites documenting why `MISSING_RIGHTS` is used for a concurrency conflict (documentation-only fix for "Incorrect Error Classification", since changing the error code itself would break AC2's explicit "match the existing pattern exactly" constraint). Did not touch the 4 pre-existing catch blocks (`endSession`/`pauseSession`/`resumeSession`/`confirmCompletion`) for either the chaining or the comment — out of this story's bounded scope, same reasoning already applied to not backfilling their tests. Both residual items (uncommented/unchained pre-existing sites; no dedicated `ErrorCode` for lock conflicts) filed to `deferred-work.md`'s existing "code review of skillars-deferred-66" section for a future bounded story. Strengthened all 3 new tests with `.hasCauseInstanceOf(...)` assertions to lock in the chaining fix. Re-ran `mvn test -Dtest=BookingCompletionServiceTest` (10/10 pass) and `mvn compile` (clean) after the changes. |

## Review Findings

### Patch (Actionable Issues)

- [x] [Review][Patch] **Test Mock Setup - Booking Instance Mismatch** [BookingCompletionServiceTest.java:193-221] — **Disproven.** `bookingService.transition(...)` takes `UUID bookingId` as its first parameter, not a `Booking` object — there is no `Booking`-instance argument to mismatch. The three new tests mock via `eq(BOOKING_ID)` (the same `UUID` constant `getBookingOrThrow(BOOKING_ID)` is stubbed against in `setUp()`), which is exactly how every pre-existing test in this file already mocks `transition(...)`. Re-ran `mvn test -Dtest=BookingCompletionServiceTest`: 10/10 pass, including these two. No code change; finding does not describe the actual method signature.

- [x] [Review][Patch] **Incorrect Error Classification** [BookingCompletionService.java:56-58, 117-119, 159-161] — Real observation, but changing the `SecurityError` code would mean the three new sites no longer "match what endSession/pauseSession/resumeSession already do exactly" (this story's own Dev Notes constraint) while the four pre-existing sites still use `MISSING_RIGHTS` — fixing only 3 of 7 identical blocks creates the exact inconsistency AC2 was written to eliminate. Took the review's documentation-only alternative instead: added a one-line comment above each of the three new catches explaining `MISSING_RIGHTS` is the established code for this conflict shape, matching the four existing sites. Filed to `deferred-work.md` for a future bounded story that would touch all seven sites uniformly if a dedicated `ErrorCode` for optimistic-lock conflicts is ever introduced.

- [x] [Review][Patch] **Missing Exception Chaining** [BookingCompletionService.java, all 7 catch blocks] — **Fixed for the three sites this story owns.** Added an `OperationNotAllowedException(String message, Throwable cause, ErrorCode errorCode)` constructor (purely additive — no existing call site or constructor changed) and used it in `startSession`, `initiateQuickComplete`, and `submitWrapUp`'s `LIVE`-mode catch to chain the original `OptimisticLockingFailureException` as cause. This changes only the exception's cause, not its message/type/retry-semantics, so it does not violate AC2's "match exactly" constraint. Left the four pre-existing catches (`endSession`/`pauseSession`/`resumeSession`/`confirmCompletion`) unchained — bringing those in line is out of this story's bounded scope (same "separate test-hygiene pass" reasoning already applied to not backfilling their tests); filed to `deferred-work.md` alongside the error-classification item since a future story would sensibly fix both together. All three new tests strengthened with `.hasCauseInstanceOf(OptimisticLockingFailureException.class)` to lock in the fix; 10/10 pass.

- [x] [Review][Patch] **Unverified I18n Parameter Initialization** [ParentBookingsPage.vue:109] — **Disproven.** `rescheduleBookingTimezone.value = booking.canonicalTimezone` (`:193`) is set *before* `rescheduleDialogOpen.value = true` (`:196`) inside the same synchronous `openRescheduleDialog` function — the dialog, and therefore the hint, cannot render before the ref is populated. `canonicalTimezone` is `NOT NULL` in the DB (`V31__booking_requests.sql:14`, same citation the prior story-review pass already used to dismiss the identical concern about the sibling `startTimezoneHint` field). `browserTimezone` (`:137`) is a plain `const` set synchronously at component setup from `Intl.DateTimeFormat().resolvedOptions().timeZone`, which the ECMAScript spec guarantees always resolves to a string. No code change — same false-positive class already documented in this story's Change Log for `skillars-deferred-65`'s prior review.

- [x] [Review][Patch] **Lost Stack Traces** [BookingCompletionService.java, all OptimisticLockingFailureException catch blocks] — Duplicate of the "Missing Exception Chaining" finding above (same three sites, same fix, same cause-chaining constructor). Resolved together; see that entry.

### Defer (Pre-existing Issues)

- [x] [Review][Defer] **Misleading Exception Message in confirmCompletion()** [BookingCompletionService.java:189] — Pre-existing method (not modified by this story) uses message "Session already confirmed" for `OptimisticLockingFailureException`, which signals concurrent modification, not a confirmed session. Deferred: This story correctly uses "Booking status changed concurrently — retry" in new methods; pre-existing issue is out of scope.

- [x] [Review][Defer] **Confusing "Retry" Message in Exception** [BookingCompletionService.java, exception messages] — Pre-existing message pattern already used in endSession/pauseSession/resumeSession. Message uses imperative "retry" language that might confuse users. Deferred: This story mirrors the existing pattern per AC2 spec; message design is a pre-existing choice, out of scope.
