# Story Deferred-35: Batch-Accept Failure Diagnostics, O(1) Row Lookup & Stale Refund Docs

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an engineer operating this platform,
I want a total-failure batch-accept to keep its already-computed per-booking failure detail somewhere
retrievable, the coach requests page to look up a booking's failure reason in constant time instead of
re-scanning the whole batch result on every row on every render, and the two generated onboarding docs that
still describe the deleted three-tier refund mechanism to say what the code actually does,
so that a support engineer debugging a "no bookings accepted" 403 isn't limited to a free-text log line, the
requests page doesn't do needless repeated work as batch sizes grow, and a reader of the dev-docs/business-docs
doesn't learn a refund policy that was deleted from the code three stories ago.

### Why this story exists

Drawn from `_bmad-output/implementation-artifacts/deferred-work.md`, three items filed since
`skillars-deferred-34`'s own full-ledger audit (2026-08-19) closed the file out as "mined thin" of small,
decision-free material. `skillars-deferred-34`'s creation read all ~1550 lines of the ledger, plus an
independent fork covering the previously-unaudited `skillars-1`–`skillars-10`/`deploy-*` sections, and found
nothing else both small and decision-free at that time. This story re-read the entire file end to end again
(all ~1555 lines, including the historical audit trail `skillars-deferred-34` already trusted as mined) and
confirms that conclusion still holds for everything before the two sections below — every open item in the
first ~1543 lines is either already `[CLOSED ...]`/`~~struck through~~`/`[WITHDRAWN ...]`/`[OWNED BY <a
shipped story>]`, or is explicitly a product/design decision, a large scoped change, or a "pre-existing,
low-probability, accepted tradeoff" item its own section already argues should stay open. Three new/re-checked
items qualify:

1. **`## Deferred from: code review of skillars-deferred-34-batch-accept-per-booking-outcome-reporting
   (2026-08-19)`** (deferred-work.md lines 1552-1555) — filed by `skillars-deferred-34`'s own code review as
   two `[Review][Defer]` findings, both explicitly deferred rather than fixed in that story:
   - `BookingBatchService.acceptAll` computes a full `List<BatchAcceptResult>` in its per-booking loop but
     discards it entirely on the total-failure path (the `acceptedIds.isEmpty()` branch), throwing a bare
     `OperationNotAllowedException` with no diagnostic payload.
   - `CoachBookingRequestsPage.vue`'s `failureReasonFor` does a linear `Array.find()` scan over the batch's
     result array on every call — and the template calls it twice per pending row (once in `v-if`, once in
     the caption body) on every re-render.

   **Re-verified against current code on master @ `8e3cc02` (post `skillars-deferred-34` merge, PR #64) during
   this story's creation** — both are exactly as the ledger describes, unchanged since filed:
   `BookingBatchService.java:263-300`'s loop still builds `results` (`:265,279,282`) and the
   `acceptedIds.isEmpty()` throw at `:298-299` still calls the two-argument `OperationNotAllowedException`
   constructor, carrying no reference to `results` at all. `CoachBookingRequestsPage.vue:208-215`'s
   `failureReasonFor` still does `results.find((r) => r.bookingId === bookingId)` on every call, and the
   template (`:52-58`) still calls it twice per pending row.

2. **`## Deferred from: skillars-deferred-33 implementation (2026-08-18)`** (deferred-work.md line 1545) —
   filed during `skillars-deferred-33` (which deleted `applyRefundLogic`/`Booking.refundEligibility`/
   `refundAmount` in its AC7) and explicitly left for "a future docs-focused pass": `docs/dev-docs/booking/
   index.html` and `docs/business-docs/money/index.html` still describe the deleted three-tier
   `FULL`/`PARTIAL`/`NONE` refund mechanism as if it were live.

   **Re-verified against current code and the current doc content during this story's creation** — still
   present, and worse than the ledger item describes: `docs/dev-docs/booking/index.html`'s own "Gotchas"
   callout (`:412-422`) was written when the dead column still existed in the schema and warned that it
   "disagrees with actual behaviour"; now that `V97__drop_booking_refund_eligibility_and_amount.sql` and the
   Java field deletions have shipped, the callout describes a column and method that no longer exist at all,
   not merely an unread one. `docs/business-docs/money/index.html`'s parallel callout (`:149-160`) similarly
   still tells a reader to "flag it to engineering" over a defect that was fixed by deletion three stories ago.
   The current, accurate refund rule (a binary flag, not a three-tier one) is `BookingService
   .cancelBookingAsParent`'s `refundEligible = paymentWasCaptured && booking.getRequestedStartTime()
   .isAfter(Instant.now().plus(24, ChronoUnit.HOURS))` (`BookingService.java:657-660`), read by
   `payment.CancellationRefundService.onBookingCancelledByParent`
   (`CancellationRefundService.java:36-53`). The business-docs table itself (`money/index.html:139-148`) is
   already accurate — only its accompanying warning callout is stale.

Both `BookingBatchService.acceptAll` and `CoachBookingRequestsPage.vue`'s `failureReasonFor` are unrelated in
subsystem (backend structured logging vs. frontend render-path perf) to the docs item, and all three are
small, mechanical, and require no product/design decision — exactly the shape prior bundling stories
(`skillars-deferred-31`/`32`/`33`) grouped together. No other newly-open or previously-missed item of
comparable size surfaced during this re-read; the ledger remains mined thin for anything beyond these three.

## Deferred Item(s) Closed

| Source | Item | Current location (re-verified 2026-08-19) | AC | Planned outcome |
|---|---|---|---|---|
| `skillars-deferred-34` code review (2026-08-19), Finding "[Review][Defer]" #1 | `acceptAll` discards its computed per-booking results on the total-failure path | `BookingBatchService.java:263-300` | 1, 4 | Total-failure exception carries the already-computed results in its structured log context |
| `skillars-deferred-34` code review (2026-08-19), Finding "[Review][Defer]" #2 | `failureReasonFor` does an O(n) linear scan per call, twice per row per render | `CoachBookingRequestsPage.vue:208-215`, template `:52-58` | 2 | O(1) per-row lookup via a memoized `Map`, behavior-preserving |
| `skillars-deferred-33` implementation (2026-08-18) | Two generated docs still describe the deleted three-tier refund mechanism | `docs/dev-docs/booking/index.html:78,346,359-362,412-422`, `docs/business-docs/money/index.html:149-160` | 3 | Docs corrected to the real binary `refundEligible` rule; stale "dead column" warnings replaced with accurate history |

**Explicitly NOT in this story** (considered during story creation and rejected):

- **Exposing per-booking results on the total-failure HTTP response body.** The ledger item itself frames this
  as "a natural follow-up once a use case for per-booking detail on total failure emerges" — a genuine wire
  contract change. `ErrorDto` (`infrastructure.message.ErrorDto`) has a fixed shape (`helpCode`, `errorMsg`,
  `fieldErrors`) with no field for arbitrary per-item detail, and adding one is a change that touches every
  other error response in the app. AC1 below closes the "computed then silently discarded" defect by
  preserving the data server-side (in the exception's structured log context, which `ApiAdvice.logError`
  already reads for every `ApplicationException` with zero new plumbing) — it deliberately does not put the
  data on the wire. That remains future work, same as `skillars-deferred-34`'s own scoping decision.
- **Any change to the total-failure path's HTTP status or error key.** `booking.batchNoneAccepted` (403) is
  untouched — this story only changes what accompanies the exception in the server log, not what the client
  receives or how the client is expected to react.
- **Rewriting `failureReasonFor`'s branching logic, its i18n key choices, or the "Accept All" button's
  visibility rule (`batchIsActionable`).** AC2 is a pure lookup-mechanism change; for every input the function
  can receive, its return value is unchanged.
- **A broader docs regeneration or a sweep of the rest of `docs/dev-docs`/`docs/business-docs` for other
  drift.** AC3 corrects exactly the refund-mechanism passages the ledger item names; it does not attempt to
  re-verify every other claim in either generated doc.

## Acceptance Criteria

1. **`BookingBatchService.acceptAll`'s total-failure exception preserves the per-booking results it already
   computed, instead of discarding them.**

   Verified current state (`BookingBatchService.java:263-300`):
   ```java
   List<Booking> requestedBookings = bookingRepository.findByBatchIdAndStatus(batchId, "REQUESTED");
   List<UUID> acceptedIds = new ArrayList<>();
   List<BatchAcceptResult> results = new ArrayList<>();

   for (Booking b : requestedBookings) {
       try {
           perBookingTx.executeWithoutResult(tx -> acceptOneBooking(b, coach.getId(), coachUserId));
           acceptedIds.add(b.getId());
           results.add(new BatchAcceptResult(b.getId(), true, null));
       } catch (Exception e) {
           log.warn("Failed to accept booking {} in batch {}: {}", b.getId(), batchId, e.getMessage());
           results.add(new BatchAcceptResult(b.getId(), false, resolveFailureCode(e)));
       }
   }

   if (acceptedIds.isEmpty()) {
       // ...
       log.warn("No bookings were accepted in batch {}", batchId);
       throw new OperationNotAllowedException("No bookings in batch were accepted",
           BookingError.BATCH_NONE_ACCEPTED);
   }
   ```
   `results` is fully populated by this point for the reachable case that actually has per-booking detail to
   lose (every per-booking accept threw — path (a) in the existing code comment at `:287-291`); for the other
   reachable cause (path (b), no `REQUESTED` bookings existed to iterate) `results` is simply empty, so there
   is nothing to lose there. The two-argument `OperationNotAllowedException(String, ErrorCode)` constructor
   used at `:298-299` carries neither. Three lines above (`:258-260`), the sibling `COACH_UNAVAILABLE` throw in
   this same method already demonstrates this codebase's established pattern for attaching diagnostic detail
   to an authorization exception:
   ```java
   throw new OperationNotAllowedException("Coach is suspended",
       Map.of("submitted coach id", coach.getId()), BookingError.COACH_UNAVAILABLE);
   ```
   using the three-argument `OperationNotAllowedException(String, Map<String, Object>, ErrorCode)` constructor
   (`security.contract.exception.OperationNotAllowedException`), which forwards to `ApplicationException`'s
   `logContext` field. `ApiAdvice.logError` (`ApiAdvice.java:636-651`) already reads
   `((ApplicationException) throwable).getLogContext()` unconditionally for every `ApplicationException` and
   logs it as structured fields via `net.logstash.logback.argument.StructuredArguments.entries(ctx)`
   (`ApiAdvice.java:83,649`) — so attaching a `Map` here requires no new plumbing anywhere else in the
   exception-handling pipeline.

   **Required:** change the `acceptedIds.isEmpty()` throw to the three-argument constructor, carrying the
   batch id and the already-computed `results`:
   ```java
   if (acceptedIds.isEmpty()) {
       // ... existing comment above (a)/(b) unchanged ...
       log.warn("No bookings were accepted in batch {}", batchId);
       throw new OperationNotAllowedException("No bookings in batch were accepted",
           Map.of("batch id", batchId, "per-booking results", results),
           BookingError.BATCH_NONE_ACCEPTED);
   }
   ```
   `java.util.Map` is already imported in this file (used by the `COACH_UNAVAILABLE` throw above). No other
   import changes. The existing `log.warn` two lines above is unchanged — it stays as the free-text signal
   that fires regardless of whether structured detail exists; the new `Map` entry is additive, not a
   replacement. This does **not** put `results` on the HTTP response body — `ApiAdvice`'s handler for
   `OperationNotAllowedException` (`operationDeniedHandler`, `ApiAdvice.java:267-276`) builds its `ErrorDto`
   from `exception.getErrorCode()` only and never reads `getLogContext()` for the client-facing body; the data
   is now reachable in the server's structured logs (e.g. for a support engineer correlating a
   `booking.batchNoneAccepted` complaint against `log.error` output keyed by `batch id`), not on the wire —
   see the "Explicitly NOT in this story" note above for why exposing it to the client is out of scope here.

2. **`CoachBookingRequestsPage.vue`'s `failureReasonFor` does an O(1) lookup instead of an O(n) linear scan,
   with no change to any input's output.**

   Verified current state (`CoachBookingRequestsPage.vue:208-215`):
   ```js
   function failureReasonFor(batchId, bookingId) {
     const results = bookingStore.batchAcceptResultsByBatch[batchId] ?? []
     const result = results.find((r) => r.bookingId === bookingId)
     if (!result || result.accepted) return null
     if (result.errorKey === 'booking.slotUnavailable') return t('booking.errors.slotUnavailable')
     if (result.errorKey === 'booking.coachUnavailable') return t('booking.errors.coachUnavailable')
     return t('booking.batch.itemNotAccepted')
   }
   ```
   The template calls this function twice per pending row, once per re-render (`:52-58`):
   ```html
   <q-item-label
     v-if="failureReasonFor(group.batchId, booking.id)"
     caption
     class="text-negative"
   >
     {{ failureReasonFor(group.batchId, booking.id) }}
   </q-item-label>
   ```
   `bookingStore.batchAcceptResultsByBatch` (`booking.store.js:571`) is a plain object keyed by `batchId`,
   each value either `null` (no accept-all run yet this session) or a `BatchAcceptResult[]` (`bookingId`,
   `accepted`, `errorKey`). Each `Array.find()` call is O(n) in the batch's result-array size; for a page with
   several batch groups on screen, each with several rows, this is a linear scan repeated on every re-render
   for every row — needlessly quadratic for any batch of meaningful size, per the ledger item's own framing.

   **Required:** add a memoized `computed` that builds one `Map<bookingId, BatchAcceptResult>` per batch,
   keyed by `batchId`, recomputed only when `bookingStore.batchAcceptResultsByBatch` itself changes (Vue's
   `computed` caching, not on every unrelated re-render), and have `failureReasonFor` do two O(1) lookups
   against it instead of one O(n) scan:
   ```js
   import { ref, computed, onMounted } from 'vue'
   // ...

   // O(1) per-row lookup instead of a linear Array.find() scan per call — the template calls
   // failureReasonFor twice per pending row, once per re-render (skillars-deferred-34 code review
   // Decision→Defer, closed here). Vue's computed cache means this only rebuilds when
   // bookingStore.batchAcceptResultsByBatch itself changes, not on every unrelated re-render. Only
   // failed entries are stored — an accepted result and a missing result both correctly resolve to
   // "not found" below, matching the prior implementation's `!result || result.accepted` check.
   const failedResultByBatch = computed(() => {
     const byBatch = {}
     for (const [batchId, results] of Object.entries(bookingStore.batchAcceptResultsByBatch)) {
       if (!results) continue
       const byBookingId = new Map()
       for (const r of results) {
         if (!r.accepted) byBookingId.set(r.bookingId, r)
       }
       byBatch[batchId] = byBookingId
     }
     return byBatch
   })

   function failureReasonFor(batchId, bookingId) {
     const result = failedResultByBatch.value[batchId]?.get(bookingId)
     if (!result) return null
     if (result.errorKey === 'booking.slotUnavailable') return t('booking.errors.slotUnavailable')
     if (result.errorKey === 'booking.coachUnavailable') return t('booking.errors.coachUnavailable')
     return t('booking.batch.itemNotAccepted')
   }
   ```
   `computed` is a new import from `'vue'` (currently `import { ref, onMounted } from 'vue'` at `:141`);
   `ref`/`onMounted` are unchanged. `handleAcceptAll`'s own direct read of
   `bookingStore.batchAcceptResultsByBatch[batchId] ?? []` (`:230`, once per accept-all click, not a
   per-render hot path) is untouched — this AC only changes `failureReasonFor`'s internal mechanism.
   Behavior-preserving by construction: for every combination of inputs (`batchAcceptResultsByBatch[batchId]`
   absent/`null`/`[]`/populated; `bookingId` present-and-accepted/present-and-failed/absent), the new
   implementation returns the identical value the old `Array.find()` version did — verify this by inspection
   against the four cases before marking this AC done, since no automated frontend test exists to pin it (see
   AC4's Dev Notes on the standing gap).

3. **`docs/dev-docs/booking/index.html` and `docs/business-docs/money/index.html` describe the real,
   binary refund rule — not the deleted three-tier `applyRefundLogic` mechanism.**

   Verified current state: `BookingService.cancelBookingAsParent` (`BookingService.java:657-660`) computes
   ```java
   boolean paymentWasCaptured = statusBeforeCancel == BookingStatus.CONFIRMED
       || statusBeforeCancel == BookingStatus.UPCOMING;
   boolean refundEligible = paymentWasCaptured
       && booking.getRequestedStartTime().isAfter(Instant.now().plus(24, ChronoUnit.HOURS));
   ```
   a single boolean, carried on `BookingCancelledByParentEvent` and read by
   `payment.CancellationRefundService.onBookingCancelledByParent` (`CancellationRefundService.java:36-53`) to
   decide full-refund-or-nothing — there is no partial tier. `Booking.refundEligibility`/`refundAmount` and
   `applyRefundLogic` were deleted outright by `skillars-deferred-33` AC7
   (`V97__drop_booking_refund_eligibility_and_amount.sql`); `grep -rn "refundEligibility|refundAmount|applyRefundLogic" src/main/java` returns zero hits.

   **Required, `docs/dev-docs/booking/index.html`:**
   - Line 78 (the "Key business rules" bullet) — replace:
     ```html
     <li><strong>Cancellation refund windows (parent-initiated):</strong> <code>applyRefundLogic</code> stamps <code>Booking.refundEligibility</code> with <code>FULL</code> (&gt;24h), <code>PARTIAL</code> (6–24h) or <code>NONE</code> (&lt;6h). A coach-initiated cancellation is always <code>FULL</code>, a coach no-show <code>FULL</code>, a player no-show <code>NONE</code>. <strong>But see the gotcha below — that column is dead, and <code>PARTIAL</code> pays out nothing.</strong></li>
     ```
     with:
     ```html
     <li><strong>Cancellation refund windows (parent-initiated):</strong> <code>cancelBookingAsParent</code> computes a binary <code>refundEligible</code> flag — <code>true</code> only if payment was already captured (booking was <code>CONFIRMED</code>/<code>UPCOMING</code>) <em>and</em> the session start is more than 24h away; otherwise the payment is forfeited. There is no partial-refund tier. A coach-initiated cancellation is always a full refund, a coach no-show is always a full refund, a player no-show forfeits fully.</li>
     ```
   - Line 346 (the cancellation/reschedule sequence diagram) — replace:
     ```
     BookingService->>BookingService: compute hoursBeforeSession -> refundEligibility (FULL/PARTIAL/NONE)
     ```
     with:
     ```
     BookingService->>BookingService: compute hoursBeforeSession -> refundEligible (boolean: captured && >24h)
     ```
   - Lines 359-362 (the paragraph immediately after that diagram) — replace:
     ```html
     Cancellation and reschedule are separate, competing paths on a live booking. Cancellation always computes
     a refund-eligibility tier based on hours-until-session before transitioning the booking to
     <code>CANCELLED_PARENT</code> or <code>CANCELLED_COACH</code>; the actual refund is executed downstream
     by <code>payment.CancellationRefundService</code> off the cancellation event, not by this module.
     ```
     with:
     ```html
     Cancellation and reschedule are separate, competing paths on a live booking. Cancellation always computes
     a binary refund-eligible flag (payment captured &amp; more than 24h until the session) before
     transitioning the booking to <code>CANCELLED_PARENT</code> or <code>CANCELLED_COACH</code>; the actual
     refund is executed downstream by <code>payment.CancellationRefundService</code> off the cancellation
     event, not by this module.
     ```
   - Lines 412-422 (the "Conventions & Gotchas" `callout warn` about the dead column) — this callout was
     accurate when the dead-but-present column existed; the column and the method that wrote it no longer
     exist at all, so replace the whole block with a plain (non-`warn`) historical note, matching the style of
     the existing "Booking no longer owns session packs"/"Completion no longer moves money or credits"
     `callout` blocks earlier in this same file (`:63-72`, `:401-409`):
     ```html
     <div class="callout">
       <p><strong>The old three-tier refund column is gone.</strong> An earlier version of this page warned
       that <code>Booking.refundEligibility</code> (written by <code>applyRefundLogic</code> as
       <code>FULL</code>/<code>PARTIAL</code>/<code>NONE</code>) was dead and disagreed with the real
       behaviour above. <code>skillars-deferred-33</code> AC7 deleted <code>applyRefundLogic</code> and both
       <code>Booking.refundEligibility</code>/<code>refundAmount</code> outright
       (migration <code>V97__drop_booking_refund_eligibility_and_amount.sql</code>) rather than reconciling
       them — there is now exactly one refund rule, the boolean <code>cancelBookingAsParent</code> computes
       and <code>payment.CancellationRefundService</code> reads (see above).</p>
     </div>
     ```

   **Required, `docs/business-docs/money/index.html`:**
   - Lines 149-160 (the `callout warn` following the cancellations/refunds table) — the table above it
     (`:139-148`) is already accurate; only this callout, which frames the tiered `refundEligibility` label as
     a live, unread defect ("flag it to engineering, because the label and the behaviour disagreeing is a
     defect waiting to become a chargeback"), is stale. Replace it with a plain `callout` recording that it
     was fixed, not merely deferred:
     ```html
     <div class="callout">
       <p><strong>Fixed as of <code>skillars-deferred-33</code>.</strong> This page used to warn that a
       cancelled booking's <code>refundEligibility</code> label (<code>FULL</code>/<code>PARTIAL</code>/
       <code>NONE</code>) was never read and disagreed with the table above — a defect risking a chargeback.
       That dead column and the code that wrote it were deleted outright rather than reconciled; the table
       above is the single, accurate source of truth.</p>
     </div>
     ```

   Both files are hand-edited HTML in this diff (not regenerated) — the ledger item itself frames a full
   regeneration as outside a small-fix story's scope; this AC corrects only the passages named above and does
   not otherwise restructure either file.

4. **Tests prove AC1's structured log context, mutation-verified. AC2 and AC3 have no automated test
   infrastructure to extend — verify by inspection and record that explicitly.**

   - **`BookingBatchServiceTest`** (`src/test/java/.../booking/service/BookingBatchServiceTest.java`): extend
     the two existing tests that already exercise the `acceptedIds.isEmpty()` branch, rather than adding new
     fixtures — both already assert on the thrown exception's `getErrorCode()`; add an assertion on
     `getLogContext()` to each:
     - `acceptAll_everyBookingFailsToAccept_throwsBatchNoneAcceptedAndLeavesBatchPending` (`:690-726`, path
       (a) — the one `REQUESTED` booking's `acceptOneBooking` call fails on the slot-collision guard): assert
       the thrown `OperationNotAllowedException`'s `getLogContext()` contains a `"per-booking results"` entry
       equal to a single-element `List<BatchAcceptResult>` — `accepted() == false`, `bookingId() ==
       requested.getId()`, `errorKey() == "booking.slotUnavailable"` — and a `"batch id"` entry equal to
       `BATCH_ID`.
     - `acceptAll_pendingBatchWithNoRequestedBookings_throwsBatchNoneAccepted` (`:733-753`, path (b) — no
       `REQUESTED` bookings at all): assert the thrown exception's `getLogContext()` contains a
       `"per-booking results"` entry equal to an **empty** list — this path never entered the loop, so there
       is nothing to carry, and the test should pin that explicitly rather than leave it unasserted.
     - Mutation-verify both: temporarily revert `acceptAll`'s throw to the two-argument constructor (dropping
       the `Map`), confirm both new assertions fail (`getLogContext()` reverts to the empty map the
       `ApplicationException(String, ErrorCode)` constructor default-initializes), restore byte-identical.
   - **`BookingBatchResourceIT`**: no change. AC1 does not touch the HTTP response body or status — the
     existing `acceptAll_everySiblingDeclinedBeforehand_returns403WithBatchNoneAcceptedKey` IT (asserting 403
     + `booking.batchNoneAccepted` over real HTTP) remains valid unmodified and is not re-verified by this
     story beyond confirming it still compiles against the unchanged method signature.
   - **AC2 (frontend)**: no automated test infrastructure exists in this repo (`package.json`'s `test` script
     is a no-op placeholder; confirmed zero `*.spec.js`/`*.test.js` files outside `node_modules` — the same
     standing, project-wide gap every prior story with frontend changes has recorded). This AC is a pure
     behavior-preserving refactor of an internal lookup mechanism with no new UI, so there is nothing for a
     manual browser check to observe beyond "the page renders identically to before" — record in Completion
     Notes that the four-case-by-inspection verification described in AC2 was performed (or could not be, per
     the project's standing no-browser-tooling constraint), matching the exact phrasing prior stories used for
     this.
   - **AC3 (docs)**: no test infrastructure covers generated HTML doc content in this repo. Verify by reading
     the rendered diff and running `grep -n "applyRefundLogic\|refundEligibility\|refundAmount\|PARTIAL\b" docs/dev-docs/booking/index.html docs/business-docs/money/index.html`.
     **This does NOT return zero hits, and that is correct** — AC3's own "Required" replacement text (the
     historical-note callouts) deliberately quotes the deleted terms in past tense to explain what used to be
     there (e.g. "An earlier version of this page warned that `Booking.refundEligibility` ... was dead"), so a
     handful of hits *inside those two callouts* are expected. The actual pass criterion: every surviving hit
     must fall either inside `docs/dev-docs/booking/index.html`'s replaced Gotchas callout (originally
     `:412-422`) / `docs/business-docs/money/index.html`'s replaced callout (originally `:149-160`), or be the
     one untouched, unrelated `PARTIALLY_ACCEPTED` value at `docs/dev-docs/booking/index.html:80` (excluded from
     matching bare `PARTIAL` by the pattern's `\b` word boundary — `PARTIAL` immediately followed by `LY` has no
     boundary there). Any hit **outside** those locations means a stale reference was missed.

5. **Ledger hygiene.** In `deferred-work.md`:
   - Annotate the two `[Review][Defer]` findings under `## Deferred from: code review of
     skillars-deferred-34-batch-accept-per-booking-outcome-reporting (2026-08-19)` (deferred-work.md lines
     1554-1555) `[CLOSED by skillars-deferred-35 AC1]` and `[CLOSED by skillars-deferred-35 AC2]`
     respectively, describing what shipped, per the format every prior `skillars-deferred-*` story used.
   - Annotate the docs item under `## Deferred from: skillars-deferred-33 implementation (2026-08-18)`
     (deferred-work.md line 1545) `[CLOSED by skillars-deferred-35 AC3]`, describing what shipped.
   - Do **not** re-verify or touch anything else in the file — the rest of the ledger was re-read during this
     story's creation and everything else open is either already owned by a shipped story, needs a decision
     this story does not make, or is an accepted low-priority tradeoff its own section already argues for.
   - `sprint-status.yaml`: add the
     `skillars-deferred-35-batch-failure-diagnostics-lookup-perf-and-refund-docs` entry (already added at
     story-creation time by this workflow) and its `last_updated` note.

## Tasks / Subtasks

- [x] **Task 1 — AC1: `BookingBatchService.acceptAll` preserves per-booking results in the total-failure
      exception's structured log context**
  - [x] Change the `acceptedIds.isEmpty()` throw to the three-argument `OperationNotAllowedException`
        constructor, carrying `Map.of("batch id", batchId, "per-booking results", results)`
  - [x] No change to `log.warn`, the exception message, the error code, or any other branch of the method
- [x] **Task 2 — AC2: `CoachBookingRequestsPage.vue`'s `failureReasonFor` becomes O(1)**
  - [x] Add `computed` import from `'vue'`
  - [x] New `failedResultByBatch` computed: `{ [batchId]: Map<bookingId, BatchAcceptResult> }`, built only
        from failed (`accepted === false`) entries
  - [x] `failureReasonFor` rewritten to do two `Map` lookups instead of `Array.find()`; branching logic and
        return values unchanged for every input
  - [x] `handleAcceptAll`'s own direct array read (`:230`) left untouched
- [x] **Task 3 — AC3: correct the stale refund-mechanism docs**
  - [x] `docs/dev-docs/booking/index.html`: line 78 bullet, line 346 sequence-diagram note, lines 359-362
        paragraph, lines 412-422 "Gotchas" callout (replaced with a plain historical `callout`)
  - [x] `docs/business-docs/money/index.html`: lines 149-160 `callout warn` (replaced with a plain historical
        `callout`); the cancellations/refunds table above it (`:139-148`) is untouched — it was already
        accurate
  - [x] `grep -n "applyRefundLogic\|refundEligibility\|refundAmount\|PARTIAL\b"` on both files — see Dev Agent
        Record for a noted discrepancy: the AC3 "Required" historical-callout text (followed verbatim) itself
        contains these terms in past-tense references, so this grep cannot return zero hits after implementing
        AC3 exactly as specified; confirmed the unrelated, live `PARTIALLY_ACCEPTED` batch-status value at
        `docs/dev-docs/booking/index.html:80` remains untouched
- [x] **Task 4 — AC4: tests**
  - [x] `BookingBatchServiceTest`: extended
        `acceptAll_everyBookingFailsToAccept_throwsBatchNoneAcceptedAndLeavesBatchPending` and
        `acceptAll_pendingBatchWithNoRequestedBookings_throwsBatchNoneAccepted` with `getLogContext()`
        assertions, mutation-verified
  - [x] Targeted `BookingBatchServiceTest` suite green (26/26) per `docs/validation-strategy.md`'s
        smallest-relevant-scope policy — full `mvn -o verify` deliberately not run (not requested, changes are
        not cross-cutting); CI runs the full suite on push
  - [x] By-inspection verification of AC2's behavior-preservation (all input-shape combinations) and AC3's doc
        rendering — recorded in Completion Notes; no browser tooling available (standing project constraint)
- [x] **Task 5 — AC5: ledger hygiene**
  - [x] `[CLOSED by skillars-deferred-35 AC1]` / `[CLOSED by skillars-deferred-35 AC2]` on the two
        `skillars-deferred-34` code-review findings
  - [x] `[CLOSED by skillars-deferred-35 AC3]` on the `skillars-deferred-33` docs item
  - [x] `sprint-status.yaml` entry

### Review Findings

Code review (2026-08-19): Blind Hunter (diff-only, no repo access) + Edge Case Hunter (diff + repo read access) +
Acceptance Auditor (diff vs. this spec). Acceptance Auditor found 0 AC violations — all five ACs' "Verified
current state"/"Required" claims were independently re-checked against the live diff and confirmed shipped
exactly as specified, including a live `mvn -o -Dtest=BookingBatchServiceTest test` run (26/26 green). 13 Blind
Hunter findings were raised; 9 were verified false positives (results/batchId can never be null at the AC1 throw
site; `ApplicationException` copies `Map.of`'s entries into its own mutable `HashMap` so no `UnsupportedOperationException`
risk; the raw-string log-context keys match the existing `COACH_UNAVAILABLE` `Map.of("submitted coach id", ...)`
precedent three lines above, not a new pattern; `results` is bounded by the existing `booking.batch.maxSize`
config and contains only booking UUIDs/error keys, not PII; `batchId` is always a backend-generated UUID and can
never literally be `"__proto__"`; `BatchAcceptResult` is a Java record with automatic structural `equals`/`hashCode`;
the ledger self-attestation and `review`-status-vs-`[CLOSED by ACn]` "inconsistency" both match this project's
established, intentional convention used identically by every prior `skillars-deferred-*` story; the dev-docs
sequence-diagram's `hoursBeforeSession` reference is accurate — `BookingService.java:635` genuinely computes that
value before `refundEligible` at `:659-660`). 1 finding merged with an independent Acceptance Auditor observation
into a single patch. 1 Edge Case Hunter finding (mislocated by the agent but verified at its real location)
became a second patch. 3 findings deferred to `deferred-work.md`.

- [x] [Review][Patch] AC4's docs-verification grep (`applyRefundLogic\|refundEligibility\|refundAmount\|PARTIAL\b`)
  can never return zero hits even when AC3 is implemented exactly as specified — its own "Required" replacement
  HTML (the historical-note callouts) deliberately quotes the deleted terms in past tense (e.g. "An earlier
  version of this page warned that `Booking.refundEligibility` ... was dead"), which the shipped diff correctly
  followed verbatim. The story's Dev Agent Record already disclosed this as "an internal inconsistency in the
  story's own text, not a scope deviation" rather than silently reporting a false "zero hits" pass — **Applied:**
  reworded AC4's docs-verification bullet and Task 3's checklist item (below) to state the true, checked outcome:
  the two intentional historical references inside the replacement callouts are expected and correct, and the
  actual pass criterion is that the *only* remaining matches fall inside those two callouts (`docs/dev-docs/booking/index.html`'s
  and `docs/business-docs/money/index.html`'s replacement text) plus the untouched, unrelated `PARTIALLY_ACCEPTED`
  line — re-ran the corrected check against the live files and confirmed exactly that shape, nothing else.
  [`_bmad-output/implementation-artifacts/skillars-deferred-35-batch-failure-diagnostics-lookup-perf-and-refund-docs.md`]
- [x] [Review][Patch] `failedResultByBatch`'s `Map.set(r.bookingId, r)` has no duplicate-`bookingId` guard — for
  a results array containing two entries with the same `bookingId` (never produced by the current backend, which
  builds the list from one row per `REQUESTED` booking, but not an invariant this file enforces or asserts), the
  **last** matching entry silently wins instead of the **first**, diverging from the prior `Array.find()`'s
  first-match semantics that AC2 claims to preserve byte-identically. **Applied:** added the one-line guard
  `if (!r.accepted && !byBookingId.has(r.bookingId))` so first-match wins, matching old behavior exactly for
  every input shape including the hypothetical duplicate case.
  [`src/frontend/src/pages/coach/CoachBookingRequestsPage.vue:219-221`]
- [x] [Review][Defer] `failedResultByBatch` only stores failed entries and permanently discards which
  `bookingId`s were accepted — correct and behavior-preserving for `failureReasonFor`'s current single caller
  (a missing entry and an accepted entry both resolve to "no caption" either way), but narrows the data
  structure's capability for any future caller of this computed that might need to distinguish "accepted" from
  "never attempted." Additionally, the computed rebuilds a fresh `Map` for *every* batch in
  `bookingStore.batchAcceptResultsByBatch` whenever *any* batch's results change, and that store object is never
  pruned within a page session — low impact at current batch sizes and typical session lengths, worth revisiting
  if either grows. [`src/frontend/src/pages/coach/CoachBookingRequestsPage.vue:214-225`] — deferred to
  `deferred-work.md`, pre-existing low-priority design tradeoff, not a defect in this diff.
- [x] [Review][Defer] `BookingBatchService.acceptAll`'s total-failure exception's `getLogContext()` payload
  (this story's own AC1) is verified only at the `BookingBatchServiceTest` unit level — nothing in this diff
  exercises `ApiAdvice.logError`'s unconditional read of it (`:636-651`) via a real HTTP request, so a future
  regression in `ApiAdvice` that stops reading `getLogContext()` would not be caught by any test added here.
  Acknowledged scope gap, consistent with several other IT-level coverage gaps this ledger has already accepted
  for similar diagnostic-only (non-wire) fields. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:298-300`,
  `src/main/java/com/softropic/skillars/infrastructure/exception/ApiAdvice.java:636-651`] — deferred to
  `deferred-work.md`, not actionable within this story's bar.

Full targeted `mvn -o -Dtest=BookingBatchServiceTest test` after all patches applied: BUILD SUCCESS, 0 failures
(consistent with the 26/26 recorded pre-review). ESLint not re-run for the one-line frontend guard change (no
new lint surface — same file, same patterns already clean per Completion Notes).

## Dev Notes

### Established conventions this story must follow

- **Structured diagnostic data belongs in an `ApplicationException`'s `logContext`, not invented ad hoc.**
  `OperationNotAllowedException`'s three-argument constructor and `ApiAdvice.logError`'s unconditional read of
  `getLogContext()` already exist and are already used in this exact method three lines above AC1's edit
  (`COACH_UNAVAILABLE`'s `Map.of("submitted coach id", coach.getId())`) — AC1 follows that precedent verbatim
  rather than introducing a new mechanism.
- **The frontend never re-derives a wire error code's meaning from anything other than `errorKey`, and this
  story does not change that.** AC2 is a pure lookup-mechanism change; `failureReasonFor`'s `errorKey`
  branching (`booking.slotUnavailable`/`booking.coachUnavailable`/generic fallback) is untouched.
- **Vue `computed` for memoized derived state is the established pattern for expensive per-render
  computation in this codebase's Composition API components** (see `vue-best-practices` skill guidance and
  this file's own existing use of Composition API `<script setup>`); AC2 introduces the codebase's first
  `computed` in this specific file but the pattern itself is standard Vue 3, not a new house convention.
- **Generated dev-docs/business-docs are hand-corrected for factual drift, not regenerated, when the fix is
  narrowly scoped.** Prior "Fixed as of `skillars-deferred-N`" style historical notes already exist in both
  files (e.g. `dev-docs/booking/index.html:63-72,401-409`) — AC3 matches that established callout style
  exactly rather than inventing new phrasing or deleting history outright.
- **Lean, decision-free bundling.** Per `skillars-deferred-31`/`32`/`33`'s precedent and `skillars-deferred-
  34`'s own explicit finding that the ledger is "mined thin," this story bundles three small, unrelated,
  mechanical items rather than forcing in anything requiring a product/design decision or a larger scoped
  change (see "Explicitly NOT in this story" above).

### Files being modified — current state and what must be preserved

- **`BookingBatchService.acceptAll`** (`:263-300`) — AC1 changes only the `acceptedIds.isEmpty()` throw's
  constructor call. The loop, `acceptedIds` tracking, `results` tracking, the `log.warn` calls, the trailing
  transaction block, and every other branch of the method are unchanged.
- **`CoachBookingRequestsPage.vue`** (`:141` import line, `:208-215` `failureReasonFor`) — AC2 adds one import
  and one `computed`, and rewrites `failureReasonFor`'s body. The template (`:42-90`), `handleAcceptAll`
  (`:225-` onward), `batchIsActionable`, and every other function in the file are unchanged.
- **`docs/dev-docs/booking/index.html`** (`:78,346,359-362,412-422`) — AC3 touches exactly these four
  passages. Every other section of the file (package layout, entities/tables, other sequence diagrams, other
  callouts) is unchanged.
- **`docs/business-docs/money/index.html`** (`:149-160`) — AC3 touches exactly this one callout. The
  cancellations/refunds table immediately above it (`:139-148`), which is already accurate, and every other
  section of the file are unchanged.

### Project Structure Notes

- No new REST endpoint, no DTO, no migration, no new i18n keys — this story is a diagnostics-preservation
  change, a frontend lookup-mechanism refactor, and a documentation correction only.
- No new files. All four edits are to files already tracked by prior stories in this same module family.

### References

- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:233-339`
- `src/main/java/com/softropic/skillars/infrastructure/exception/ApplicationException.java:14,34-46,58`
  (`logContext` field, constructors, `getLogContext()`)
- `src/main/java/com/softropic/skillars/platform/security/contract/exception/OperationNotAllowedException.java`
  (three-argument `Map<String, Object>` constructor)
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java:267-276,614-651` (`operationDeniedHandler`, `logError`, `logErrorAndReturnDTO`)
- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue:42-90,141,208-215,225-`
- `src/frontend/src/stores/booking.store.js:571` (`batchAcceptResultsByBatch` shape)
- `docs/dev-docs/booking/index.html:63-72,78,346,359-366,401-422`
- `docs/business-docs/money/index.html:139-160`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:643-673`
  (`cancelBookingAsParent`'s real, binary `refundEligible` computation)
- `src/main/java/com/softropic/skillars/platform/payment/service/CancellationRefundService.java:36-53`
  (`onBookingCancelledByParent`)
- `src/main/resources/db/migration/V97__drop_booking_refund_eligibility_and_amount.sql`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java:690-753`
- `_bmad-output/implementation-artifacts/deferred-work.md` (`## Deferred from: code review of
  skillars-deferred-34-batch-accept-per-booking-outcome-reporting (2026-08-19)`, `## Deferred from:
  skillars-deferred-33 implementation (2026-08-18)`)
- `_bmad-output/implementation-artifacts/skillars-deferred-34-batch-accept-per-booking-outcome-reporting.md`
  (methodology and format this story replicates)
- `_bmad-output/project-context.md`

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (dev-story workflow)

### Debug Log References

None — no blocking failures encountered. Mutation-test evidence for AC4 (mutated/restored
`BookingBatchService.java`, confirmed both new assertions fail on the two-argument constructor and pass again
after restore) is in this session's transcript, not a retained log file.

### Completion Notes List

- AC1: `BookingBatchService.acceptAll`'s `acceptedIds.isEmpty()` throw changed to the three-argument
  `OperationNotAllowedException(String, Map<String, Object>, ErrorCode)` constructor, carrying
  `Map.of("batch id", batchId, "per-booking results", results)`. Verified current-state code matched the
  story's cited line numbers exactly before editing. No other line in the method changed — `log.warn`, the
  exception message, `BookingError.BATCH_NONE_ACCEPTED`, and every other branch untouched. `java.util.Map` was
  already imported (used by the `COACH_UNAVAILABLE` throw three lines above).
- AC2: `CoachBookingRequestsPage.vue`'s `import { ref, onMounted } from 'vue'` extended to include `computed`.
  Added `failedResultByBatch`, a memoized `computed` building `{ [batchId]: Map<bookingId, BatchAcceptResult> }`
  from only the failed (`accepted === false`) entries of each batch's result array. `failureReasonFor` rewritten
  to a single `Map.get()` lookup followed by the same unchanged `errorKey` branching. Verified behavior
  preservation by inspection across every reachable input-shape combination: `batchAcceptResultsByBatch[batchId]`
  absent (both old and new resolve to `null` — the new `computed` simply never populates a missing key),
  `null` (old's `?? []` and new's `if (!results) continue` both skip to "not found"), `[]` (old's `find` on an
  empty array and new's empty inner `Map` both resolve to "not found" — note `[]` is truthy in JS so the new
  code still builds an empty `Map` for that batchId rather than skipping it, but the lookup result is identical
  either way), and populated with the target `bookingId` accepted/failed/absent (old's `find` + `.accepted`
  check and new's failed-only `Map` construction produce the same three outcomes). `handleAcceptAll`'s own
  direct array read (`bookingStore.batchAcceptResultsByBatch[batchId] ?? []`) was left untouched — confirmed by
  inspection it is unreachable from `failedResultByBatch` and unaffected by this change. No automated frontend
  test infrastructure exists in this repo (`package.json`'s `test` script is a no-op placeholder, zero
  `*.spec.js`/`*.test.js` files outside `node_modules`) — this is the same standing, project-wide gap every
  prior story with frontend changes has recorded; no browser tooling was available to manually render the page,
  also consistent with prior stories' recorded constraint.
- AC3: `docs/dev-docs/booking/index.html` — line 78 "Cancellation refund windows" bullet, line 346
  sequence-diagram step, the paragraph at 359-362, and the 412-422 "Gotchas" `callout warn` (replaced with a
  plain historical `callout`, matching the file's existing "Booking no longer owns session packs"/"Completion
  no longer moves money or credits" callout style) all replaced with the story's exact "Required" text.
  `docs/business-docs/money/index.html`'s 149-160 `callout warn` replaced with a plain "Fixed as of
  `skillars-deferred-33`" historical `callout`; the cancellations/refunds table immediately above it (already
  accurate) is untouched. **Discrepancy noted, not a defect in this story's edits:** AC4's docs-verification step
  literally asks for `grep -n "applyRefundLogic\|refundEligibility\|refundAmount\|PARTIAL\b" docs/dev-docs/booking/index.html
  docs/business-docs/money/index.html` to return zero hits, but AC3's own "Required" replacement HTML — which
  this story followed verbatim, since it is given as an exact code block and is the established historical-note
  style already used elsewhere in both files — itself contains these terms in past-tense, historical references
  (e.g. "An earlier version of this page warned that `Booking.refundEligibility` ... was dead"). Implementing
  AC3 exactly as specified therefore makes AC4's literal zero-hits grep unsatisfiable; this is an internal
  inconsistency in the story's own text, not a scope deviation. Confirmed via the same grep that the one
  remaining non-historical, unrelated match (`PARTIALLY_ACCEPTED` batch-status value at
  `docs/dev-docs/booking/index.html:80`) is correctly excluded by the `\b` word-boundary pattern and was not
  touched.
- AC4: `BookingBatchServiceTest`'s two `acceptedIds.isEmpty()`-branch tests extended with `getLogContext()`
  assertions — `acceptAll_everyBookingFailsToAccept_...` asserts `"batch id"` equals `BATCH_ID` and
  `"per-booking results"` equals a single-element list matching the one failed booking
  (`accepted=false`, `errorKey="booking.slotUnavailable"`); `acceptAll_pendingBatchWithNoRequestedBookings_...`
  asserts the same `"batch id"` entry and an **empty** `"per-booking results"` list (path (b) never enters the
  loop). Mutation-verified: temporarily reverted the throw to the two-argument constructor, confirmed both new
  assertions failed (`Tests run: 2, Failures: 2`), restored byte-identical (`diff` against a pre-mutation backup
  confirmed exact restoration), re-ran green. Targeted suite: `mvn -o -Dtest=BookingBatchServiceTest test` —
  **26/26 passing**, 0 failures, 0 errors. Per `docs/validation-strategy.md`'s persistent-fact policy, full
  `mvn -o verify` was deliberately not run — this story's changes are not cross-cutting (no dependency upgrade,
  no framework/build config change, no security-sensitive change) and this is not a pre-PR final validation
  pass; CI runs the full suite on push. AC2/AC3 by-inspection verification is recorded above in their own notes.
- AC5: the two `skillars-deferred-34` code-review findings (deferred-work.md, `## Deferred from: code review of
  skillars-deferred-34-batch-accept-per-booking-outcome-reporting (2026-08-19)`) converted from
  `[PICKED UP by skillars-deferred-35 story creation, 2026-08-19]` to `[CLOSED by skillars-deferred-35 AC1]` and
  `[CLOSED by skillars-deferred-35 AC2]` respectively, each with a description of what shipped. The
  `skillars-deferred-33` docs item (`## Deferred from: skillars-deferred-33 implementation (2026-08-18)`)
  converted the same way to `[CLOSED by skillars-deferred-35 AC3]`. No other part of the ledger touched, per the
  story's explicit instruction. `sprint-status.yaml`'s `last_updated` header note updated to record this
  story's completion; the per-story status line was already updated `ready-for-dev` → `in-progress` at Step 4
  of this workflow and is now updated to `review` in Step 9.

### File List

**Modified:**
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java`
- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java`
- `docs/dev-docs/booking/index.html`
- `docs/business-docs/money/index.html`
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

### Change Log

| Date | Change | Author |
|---|---|---|
| 2026-08-19 | AC1: `BookingBatchService.acceptAll`'s total-failure throw now carries `batch id`/`per-booking results` in its `OperationNotAllowedException` logContext instead of discarding the already-computed results. | dev-story (Claude Sonnet 5) |
| 2026-08-19 | AC2: `CoachBookingRequestsPage.vue`'s `failureReasonFor` rewritten around a new memoized `failedResultByBatch` computed Map — O(1) lookup instead of a linear `Array.find()` scan, behavior-preserving. | dev-story (Claude Sonnet 5) |
| 2026-08-19 | AC3: `docs/dev-docs/booking/index.html` and `docs/business-docs/money/index.html` corrected to describe the real binary `refundEligible` rule; stale three-tier-refund "dead column" warning callouts replaced with plain historical notes. | dev-story (Claude Sonnet 5) |
| 2026-08-19 | AC4: `BookingBatchServiceTest`'s two `acceptedIds.isEmpty()`-branch tests extended with mutation-verified `getLogContext()` assertions. Targeted suite green (26/26); full `mvn -o verify` deliberately not run per `docs/validation-strategy.md`. | dev-story (Claude Sonnet 5) |
| 2026-08-19 | AC5: ledger hygiene — 2 `skillars-deferred-34` code-review findings and 1 `skillars-deferred-33` docs item marked `[CLOSED by skillars-deferred-35 ACx]` in `deferred-work.md`. | dev-story (Claude Sonnet 5) |
| 2026-08-19 | Story status: ready-for-dev → in-progress → review. | dev-story (Claude Sonnet 5) |
