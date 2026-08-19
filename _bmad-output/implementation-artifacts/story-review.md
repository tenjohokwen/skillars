# Senior-Dev Review: `skillars-deferred-34-batch-accept-per-booking-outcome-reporting`

**Reviewed:** `_bmad-output/implementation-artifacts/skillars-deferred-34-batch-accept-per-booking-outcome-reporting.md`
**Method:** every AC's "verified current state" claim was cross-checked against the actual file content on disk (not
just trusted), including exact line numbers, test names/fixtures, i18n key locations, and the three ledger-hygiene
"stale" claims. Findings below are all grounded in code actually read during this review — file:line citations are
given so each can be independently re-verified in under a minute.

---

## Finding 1 (High) — AC5's own test-update instructions leave 2 of the 3 IT tests it touches with an assertion that will fail once AC2 ships

AC2 changes the endpoint's status from `204 No Content` to `200 OK` for **every** successful call, partial or full.
AC5 lists three `BookingBatchResourceIT` tests to update, but only one of the three is told to update its status
assertion:

- `acceptAll_asOwningCoach_returns204AndUpdatesBookingsAndBatch` — AC5 explicitly says "update the status
  assertion to `HttpStatus.OK`" (and rename the test). ✅ covered.
- `acceptAll_oneSlotCollides_acceptsOtherAndEndsPartiallyAccepted` (`BookingBatchResourceIT.java:290-342`) — AC5
  says only "change the response type... and add an assertion [on the body]". It does **not** mention the
  status-code assertion. But line 314 of the current test currently reads:
  `assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);` — this will fail once AC2 ships, since
  the endpoint now returns 200.
- `acceptAll_withASiblingDeclinedBeforehand_endsPartiallyAccepted` (`BookingBatchResourceIT.java:422-448`) — same
  gap. AC5 says "same response-type change; assert the body reflects only the REQUESTED booking's outcome" — no
  mention of the status assertion. Line 437 currently reads the same
  `assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);` and will fail identically.

Both lines were verified directly in `BookingBatchResourceIT.java` during this review. A developer following AC5's
bullet list literally (rather than independently noticing the inconsistency) ships two failing tests, which Task
5's own "full `mvn -o verify` green" checkbox would then force them to fix anyway — but the story's stated
per-test instructions are incomplete as written, and should say explicitly that **all three** modified tests need
their status assertion changed to `HttpStatus.OK`, not just the one that's already named `...returns204...`.

**Suggested fix:** add "update the status assertion to `HttpStatus.OK`" to the other two bullets, or add one
umbrella sentence before the three-bullet list noting that every test hitting this endpoint's success path now
needs its status assertion updated regardless of which specific bullet mentions it.

**[CLOSED — verified against the shipped diff by the skillars-deferred-34 code review, 2026-08-19]** The dev
did not follow AC5's incomplete literal instructions; all three `BookingBatchResourceIT` tests assert
`HttpStatus.OK`, not just the renamed one — `acceptAll_asOwningCoach_returns200AndUpdatesBookingsAndBatch`,
`acceptAll_oneSlotCollides_acceptsOtherAndEndsPartiallyAccepted`, and
`acceptAll_withASiblingDeclinedBeforehand_endsPartiallyAccepted` all updated in place. Confirmed by the
Acceptance Auditor layer of that review, independently re-reading the current test file.

---

## Finding 2 (High) — `batchAcceptResults` is a single page-wide ref; AC4 is what first makes its cross-batch race user-visible

AC3 adds `batchAcceptResults` as one shared store-level `ref`, explicitly "mirroring the existing `batchAcceptError`
pattern two lines above it." That mirrored pattern is, as of today, **write-only and dead**: a repo-wide grep
confirms `batchAcceptError`/`batchAcceptLoading` are set in `booking.store.js` but read by no component anywhere in
`src/frontend/src` — so the race condition inherent in a shared, non-keyed ref has never actually been exercised in
production. AC4 changes that: it's the first code to actually *read* `bookingStore.batchAcceptResults`, both for
the partial/full toast math and for `failureReasonFor`'s per-row caption.

The page's own concurrency model allows two different batch groups' "Accept All" actions to be in flight at once:
`acceptingAll` (`CoachBookingRequestsPage.vue:145`) is keyed per `batchId`
(`:loading="acceptingAll[group.batchId]"`, `CoachBookingRequestsPage.vue:81`), so clicking "Accept All" on batch A
does not disable batch B's own "Accept All" button. A coach with two pending batch groups on screen (a completely
ordinary state — nothing dedupes or serializes this) can click both in quick succession.

Trace what happens: `handleAcceptAllBatch(batchId)` (`booking.store.js:568-581`) unconditionally does
`batchAcceptResults.value = null` at the top of every call, then `batchAcceptResults.value = response.data` on
success. If batch A's request is in flight and the coach clicks batch B's button, B's call resets the ref to
`null` and later overwrites it with B's results — regardless of what A's own in-flight promise later resolves to.
Whichever response lands last wins the single shared ref. The page then reads
`bookingStore.batchAcceptResults ?? []` right after each individual `await bookingStore.handleAcceptAllBatch(...)`
call, so:

- Batch A's own toast (`results.filter((r) => !r.accepted).length`) can be computed from **batch B's** results if
  B's response resolved in between A's await returning and A's own read of the ref (the two happen back-to-back
  synchronously in each handler, but nothing prevents B's promise from resolving and overwriting the ref between
  A's `await` settling and A's very next line reading it, since Vue/JS microtask ordering is not something either
  handler coordinates on).
- More reliably reproducible: `failureReasonFor(bookingId)` (used in the template, evaluated on every re-render)
  reads the *current* value of `bookingStore.batchAcceptResults` at render time, not a value captured at the time
  of A's own accept-all call. Once B's click has run, every row's caption on the page — including batch A's
  still-failed rows — is looked up against **B's** result list. Since A's booking ids won't be found in B's list,
  `failureReasonFor` returns `null` for all of A's rows and A's captions silently disappear, replaced by nothing
  (not by an error) — even though A's slots are still unaccepted and still need attention.

This is exactly the failure mode the CONTRACT comment already sitting a few dozen lines above in the same file
(`booking.store.js:302-320`, above `loadCoachBookingRequests`) documents and deliberately designs around: it notes
concurrent coach actions can race on a shared module-scoped ref, and that's precisely why
`loadCoachBookingRequests`/`approveBooking`/`rejectBooking` return their own per-invocation outcome instead of
requiring callers to re-read a ref. AC3/AC4 reintroduce the same hazard class for `batchAcceptResults` without the
same mitigation — the difference this time is the ref carries the very data the story exists to make trustworthy
(which bookings did or didn't succeed), so the failure mode is a coach being shown a wrong or missing per-row
explanation for a real partial failure, not just a generic staleness warning.

**Suggested fix:** don't rely on the shared ref for anything read synchronously after the call. Either (a) have
`acceptAllBatch`'s response threaded through `handleAcceptAllBatch`'s own return value instead of/alongside the
refresh outcome, and have the page capture it locally per call, or (b) key `batchAcceptResults` by `batchId` (e.g.
a `Map`/object) so concurrent batches don't clobber each other.

**[CLOSED — verified against the shipped diff by the skillars-deferred-34 code review, 2026-08-19]** Option
(b) was taken: `booking.store.js` ships `batchAcceptResultsByBatch = ref({})`, keyed by `batchId`, with
`handleAcceptAllBatch` reading/writing only its own `[batchId]` entry via a spread-merge. Two concurrent
"Accept All" calls on different batches can no longer clobber each other's results. Confirmed by the
Acceptance Auditor layer of that review.

---

## Finding 3 (Medium) — after any partial accept, the batch's "Accept All" button is unconditionally left visible, enabled, and permanently broken; clicking it also wipes the new per-row captions

Confirmed directly from code, not inferred: `BookingBatchService.acceptAll`'s trailing transaction
(`BookingBatchService.java:319-328`) computes and writes `batch.status` via `computeBatchStatus(allBookingsInBatch)`
immediately after the per-booking loop — and `computeBatchStatus` (`:411-425`) is a pure ratio of
POST_ACCEPTANCE_STATUSES vs. total bookings; it does **not** check whether any bookings are still `REQUESTED`. This
is asymmetric with the *other* writer of batch status, `updateBatchStatusFromBooking` (`:378-398`), which
deliberately early-returns while `requestedCount > 0` (`:387-389`). So a batch that partially succeeds is marked
`PARTIALLY_ACCEPTED` — not `PENDING` — the instant `acceptAll` returns, even while some of its bookings are still
sitting `REQUESTED`, undecided. This exact behavior is independently pinned by the existing (unmodified-by-this-story)
test `acceptAll_oneSlotCollides_acceptsOtherAndEndsPartiallyAccepted`
(`BookingBatchResourceIT.java:330-332` asserts `batchStatus == "PARTIALLY_ACCEPTED"` while `:325-328` asserts one
booking is still `status = 'REQUESTED'`), so this isn't a hypothetical — it's the batch's real, tested,
soon-to-be-unchanged behavior.

Consequence: `acceptAll`'s very first check (`BookingBatchService.java:245-247`,
`if (!"PENDING".equals(batch.getStatus())) throw ...BATCH_ALREADY_PROCESSED`) means **a second click on "Accept
All" for the same batch will now always fail** with `booking.batchAlreadyProcessed`, regardless of whether the
remaining slots might have since become acceptable. Nothing in this story (or the code today) hides, disables, or
relabels the "Accept All" button once a batch stops being fully-pending — it stays rendered
unconditionally inside the group's `q-card-actions` (`CoachBookingRequestsPage.vue:75-84`), still captioned with
the **original** `group.totalCount` (e.g. "Accept all 5 sessions" even though only 2 of the 5 remain), because
`BatchGroupedBookingResponse` (`totalCount` is the batch's original `requestedCount`, unaffected by partial
accepts) carries no signal the frontend could use to hide it, and this story doesn't add one.

This compounds directly with AC4's new UX: a coach who just read "3 of 5 sessions accepted. See below for the
rest." plus two per-row "could not be accepted" captions has every reason to click the still-present "Accept All 5"
button to resolve the remaining two — and doing so (a) always fails with a "these requests have already been
handled" toast that doesn't match what's visibly still on the page, and (b) per Finding 2's mechanics, unconditionally
resets `batchAcceptResults.value = null` at the very top of `handleAcceptAllBatch` (`booking.store.js:570`)
*before* the doomed API call even fires — silently erasing the per-row captions this story just added, for every
batch group on the page, not only the one just clicked. The coach is left with two still-pending rows, no
explanation of why, and a green "Accept All" button that will fail again if tried a third time.

The story's "Explicitly NOT in this story" section rules out building a *new* retry mechanism, which is a
reasonable scope cut — but this isn't about adding retry; it's that the pre-existing "Accept All" affordance is
left in a state that actively contradicts and undoes the very reporting this story adds, and the story doesn't
mention this interaction at all.

**Suggested fix (any one closes the gap):** disable/hide the group's "Accept All" button once any row in that
group has a caption (i.e., once `batchAcceptResults` shows a failure for that batch), or have the button's handler
short-circuit locally when a prior partial result is already known for that batch, or at minimum have AC4 note this
as a known, deliberately-accepted rough edge so it isn't mistaken for an oversight during code review.

**[CLOSED — verified against the shipped diff, then further hardened by the skillars-deferred-34 code
review, 2026-08-19]** AC4 shipped `groupHasKnownFailure(batchId)` gating the group's `q-card-actions` with
`v-if`, closing the "button stays clickable" half. The code review's own Edge Case Hunter layer then found
that signal was itself session-ephemeral (a reload/new tab/second session forgot it, reproducing this exact
finding one layer down) and, separately, that the button's stale `group.totalCount` label
("Accept all 5 sessions" after only 2 remain) was still live. Both were patched in the same review pass:
`BatchGroupedBookingResponse` gained a server-sourced `status` field (from `BookingBatch.status`, refreshed
on every `loadCoachBookingRequests` call) that the button visibility now keys off instead of the client-only
ref, and `totalCount` now reflects the batch's current still-`REQUESTED` count instead of its original
size — both reload-safe. See `skillars-deferred-34-....md`'s Review Findings, Decisions 1–2.

---

## Finding 4 (Low / informational) — `resolveFailureCode`'s `ResponseStatusException` branch puts a raw diagnostic sentence into the wire-facing `errorKey` field

`resolveFailureCode` (as specified) returns `rse.getReason()` for the `ResponseStatusException` case. The one
live throw site it's citing, `BookingService.readStatusOrThrow` (`BookingService.java:592-599`), constructs that
exception as:

```java
throw new ResponseStatusException(HttpStatus.CONFLICT,
    "Booking " + booking.getId() + " has unrecognised status '" + booking.getStatus() + "'");
```

So for a corrupted-status booking, `BatchAcceptResult.errorKey()` would literally be a free-text string like
`"Booking 3fa85f64-5717-4562-b3fc-2c963f66afa6 has unrecognised status 'FOOBAR'"` — not a stable dot-separated
code like every other value this field carries (`booking.slotUnavailable`, `booking.coachUnavailable`,
`booking.invalidTransition`, `generic.unknown`). This is functionally harmless today: `failureReasonFor`
(`CoachBookingRequestsPage.vue`, AC4) only pattern-matches the two known slot/coach codes and falls back to a
generic caption for anything else, so this string is never rendered verbatim. But it *is* still shipped verbatim in
the JSON response body of a `200 OK`, inspectable via browser devtools, and it embeds an internal booking UUID and
raw DB status value in a field whose name and every other value implies a stable, i18n-lookup-safe code. This is
also a direct, if minor, inconsistency with this same story's own stated Dev Notes convention: *"the frontend
renders its own copy from the wire `errorKey`, never from a backend message string... `errorKey` values... already
resolved client-side from the wire code, not from `errorMsg.message`."* This one value is a message, not a code.

**Suggested fix:** map the corrupted-status case to a stable code too (e.g. `"generic.unknown"`, same as the
fallback) rather than passing `getReason()` through — one line, no new i18n key needed since the frontend already
falls back to a generic caption for it either way. Low severity: doesn't change observed behavior, just avoids the
minor internal-detail leak and keeps the field's contract consistent.

**[CLOSED — verified against the shipped diff by the skillars-deferred-34 code review, 2026-08-19]** The
suggested fix was taken and generalized: `resolveFailureCode` has no `ResponseStatusException`-specific
branch at all — that case (and anything else that isn't `ApplicationException`/`BookingStateTransitionException`)
falls through to the stable literal `"generic.unknown"`. No raw `getReason()` text ever reaches the wire.
Confirmed by the Acceptance Auditor layer of that review; a Blind Hunter finding from the same pass noted
this invariant has no dedicated regression test, tracked separately as a patch item on the story.

---

## What was verified and found accurate (no issue)

To keep the above list free of false positives, these specific claims in the story were independently checked
against current code and confirmed correct:

- `BookingBatchService.acceptAll`'s current signature, loop, and swallow-on-catch behavior exactly as described
  (`BookingBatchService.java:233-331`), including the `acceptedIds.isEmpty()` branch and its two documented
  reachable paths.
- `BookingBatchResource.acceptAll`'s current `ResponseEntity<Void>`/`204` shape (`BookingBatchResource.java:46-51`).
- `booking.store.js`'s current `handleAcceptAllBatch` (`:568-581`) and the CONTRACT note above
  `loadCoachBookingRequests` (`:302-320`) that AC3 is deliberately written to respect.
- `CoachBookingRequestsPage.vue`'s current `handleAcceptAll` (`:201-234`) including the exact four reachable
  catch-path outcomes and the unconditional positive toast on the try path.
- `BookingError.COACH_UNAVAILABLE`/`SLOT_UNAVAILABLE` wire codes and `BookingStateTransitionException`'s fixed
  `"booking.invalidTransition"` code match what `resolveFailureCode` and `failureReasonFor` assume.
- `ApplicationException`/`ResourceNotFoundException`/`OperationNotAllowedException`/`AuthorizationException`'s
  constructors all guarantee a non-null `ErrorCode` on every throw site `acceptOneBooking` can actually reach, so
  `resolveFailureCode`'s `ae.getErrorCode() != null` guard never spuriously falls through to `generic.unknown` for
  a live path.
- The exact existing test names, fixture shapes, and line ranges cited for
  `acceptAll_batchAlreadyContainsADeclinedBooking_endsPartiallyAccepted`
  (`BookingBatchServiceTest.java:475-508`) and all four cited `BookingBatchResourceIT` tests
  (`:254-278`, `:290-342`, `:422-448`, `:460-485`).
- i18n key locations for `batch.acceptedAll`/`acceptError` in all three bundles (`en-US:916-917`,
  `de-DE:459-460`, `fr-FR:1198-1199`), and that `booking.errors.slotUnavailable`/`coachUnavailable` (referenced by
  the new `failureReasonFor`) already exist as a **separate** top-level `errors:` block, not nested under `batch:`.
- All three ledger-hygiene "STALE" claims (`skillars-deferred-3` D2/D3, `skillars-2-3` review) — each cited test
  (`VideoRepositoryIT.java:30`, `SessionTemplateResourceIT.java:507-515`,
  `CoachProfileResourceIT.java:119-123`) exists exactly as described and does close the behavior claimed.
- The primary ledger item's location (`deferred-work.md:1521-1523`, under
  `## Deferred from: skillars-deferred-31 implementation (2026-08-18)`) and the `sprint-status.yaml` entry
  (already present, `sprint-status.yaml:1233`).

None of the above needs changes.
