# Senior-Dev Review: `skillars-deferred-35-batch-failure-diagnostics-lookup-perf-and-refund-docs`

**Reviewed:** `_bmad-output/implementation-artifacts/skillars-deferred-35-batch-failure-diagnostics-lookup-perf-and-refund-docs.md`
**Method:** every AC's "verified current state" claim, code excerpt, and file:line citation was cross-checked
against the actual file content on disk (not just trusted) — `BookingBatchService.java`, `ApplicationException.java`,
`OperationNotAllowedException.java`/`AuthorizationException.java`, `ApiAdvice.java`, `ErrorDto.java`,
`CoachBookingRequestsPage.vue`, `booking.store.js`, `BookingService.java`, `CancellationRefundService.java`,
`BookingBatchServiceTest.java`, both target `docs/` HTML files, and the `deferred-work.md` line ranges AC5 touches.
Findings below are all grounded in code actually read during this review — file:line citations are given so each
can be independently re-verified in under a minute.

---

## Finding 1 (Medium) — AC3's own "zero hits" grep check can never pass: `PARTIALLY_ACCEPTED` on `dev-docs/booking/index.html:80` matches the same pattern, and that line is untouched by this story

Task 3 and AC4's doc-verification bullet both specify the same done-criteria:

```
grep -n "applyRefundLogic\|refundEligibility\|refundAmount\|PARTIAL" docs/dev-docs/booking/index.html docs/business-docs/money/index.html
```

should return **zero hits** after AC3's four dev-docs edits (lines 78, 346, 359-362, 412-422) and one business-docs
edit (lines 149-160) are applied. Running that exact grep against the current file confirms every one of those five
locations is indeed where `applyRefundLogic`/`refundEligibility`/`refundAmount`/`PARTIAL` appears **except one**:

```
docs/dev-docs/booking/index.html:80:      <li><strong>Batch bookings:</strong> ... the batch's aggregate status
(<code>PENDING</code> / <code>PARTIALLY_ACCEPTED</code> / <code>FULLY_ACCEPTED</code> / <code>DECLINED</code>)
rolls up automatically as individual bookings change status.</li>
```

Line 80 is the very next bullet after the one AC3 replaces (line 78), describing the **batch acceptance status
enum** (`PENDING`/`PARTIALLY_ACCEPTED`/`FULLY_ACCEPTED`/`DECLINED`) — a live, current, unrelated feature. It
matches the grep pattern only because `PARTIALLY_ACCEPTED` contains the substring `PARTIAL`. This bullet is not
in AC3's required-edit list, is not stale, and describes correct, current behavior (`BookingBatch.status`, the
same enum `BookingBatchService.computeBatchStatus` writes) — it must not be touched.

Consequence: even after every required AC3 edit is applied correctly, the grep the story itself prescribes as the
pass/fail check will still report one hit, on a line that is fine as-is. A developer following the story literally
will either (a) be confused into thinking an edit was missed or incomplete, or (b) "fix" the false positive by
rewording or removing the unrelated, accurate batch-status bullet — which would be a real regression the story
does not intend and explicitly scopes out ("does not otherwise restructure either file").

**Suggested fix:** narrow the verification command so it doesn't false-positive on `PARTIALLY_ACCEPTED`, e.g.
`grep -n "applyRefundLogic\|refundEligibility\|refundAmount\|[^Y_]PARTIAL\b"` or, more simply, just drop bare
`PARTIAL` from the pattern and instead grep for the two literal tier tokens that actually indicate stale refund
content: `FULL.*PARTIAL.*NONE\|refundEligibility\|refundAmount\|applyRefundLogic`. Simplest fix: state explicitly
in Task 3 / AC4 that the grep's expected hit count is **1** (the untouched `PARTIALLY_ACCEPTED` batch-status
bullet on line 80), not 0, so the dev isn't left second-guessing a correct diff.

---

## What was verified and found accurate (no issue)

To keep the above list free of false positives, these specific claims in the story were independently checked
against current code and confirmed correct:

- **AC1 / `BookingBatchService.acceptAll`** (`:236-339`): the loop, `acceptedIds`/`results` tracking, the exact
  two-argument `acceptedIds.isEmpty()` throw being replaced, and the sibling `COACH_UNAVAILABLE` three-argument-
  constructor precedent three lines above (`:258-260`) all match the story's quoted excerpts verbatim, including
  line numbers.
- **`OperationNotAllowedException`/`AuthorizationException`/`ApplicationException` constructor chain**: the
  three-argument `OperationNotAllowedException(String, Map<String,Object>, ErrorCode)` constructor exists and
  forwards through `AuthorizationException` to `ApplicationException(String, Throwable, Map, ErrorCode)`, whose
  constructor `putAll`s the given map into its own internal `HashMap` — confirming `Map.of(...)`'s immutability is
  a non-issue (it's copied, not stored by reference) and that reverting to the two-argument constructor really
  does default `logContext` to an empty map (the mutation-verification AC4 describes will behave as claimed).
- **`ApiAdvice.logError`** (`:636-651`) unconditionally reads `getLogContext()` for any `ApplicationException` and
  logs it via `entries(ctx)` (`StructuredArguments.entries`, imported at `:83`) — exactly as claimed, and requires
  no new plumbing.
- **`ApiAdvice.operationDeniedHandler`** (`:267-277`) and `ErrorDto`'s fixed `helpCode`/`errorMsg`/`fieldErrors`
  shape (`infrastructure.message.ErrorDto.java`) confirm the story's "this does not put `results` on the wire"
  claim: the client-facing DTO has no field for it and the handler never reads `getLogContext()`.
- **`resolveFailureCode`/`acceptOneBooking`** (`:352-398`): the test fixture path AC4 describes (a single
  `REQUESTED` booking colliding via `findOverlappingBookings`) genuinely throws `OperationNotAllowedException`
  with `BookingError.SLOT_UNAVAILABLE`, whose `getErrorCode()` is the literal string `"booking.slotUnavailable"`
  (`BookingError.java:47`) — the exact `errorKey` value AC4's new assertion expects.
- **`BookingBatchServiceTest`**'s two cited existing tests (`:690-726`, `:733-753`) match the story's description
  of each fixture and both already-passing assertions; `BatchAcceptResult` and `List` are already imported in the
  test file, so AC4's new assertions need no new imports.
- **AC2 / `CoachBookingRequestsPage.vue`**: current `failureReasonFor` (`:208-215`), the template's two calls per
  row (`:52-58`), the `import { ref, onMounted } from 'vue'` line (`:141`), and `handleAcceptAll`'s untouched
  direct array read (`:230`) all match verbatim. Traced the proposed `computed`-based rewrite against every input
  shape the store can actually produce — including the total-failure case, where `handleAcceptAllBatch` never
  overwrites `batchAcceptResultsByBatch[batchId]` away from the `null` it's pre-set to (`booking.store.js:576`)
  because the store's `catch` block re-throws before reaching the success assignment (`:579-582`) — and confirmed
  the new `Map`-based lookup returns byte-identical results to the old `Array.find()` for every reachable case
  (absent/null/empty/populated array × accepted/failed/absent bookingId), including that Pinia's ref-unwrapping on
  `bookingStore.batchAcceptResultsByBatch` is already relied on reactively elsewhere in this same file, so wrapping
  it in a `computed` introduces no new reactivity risk.
- **No frontend test infrastructure exists** to contradict AC4's "verify by inspection" fallback: `package.json`'s
  `test` script is a no-op (`"echo \"No test specified\" && exit 0"`), and no `*.spec.js`/`*.test.js` files exist
  under `src/frontend/src`.
- **AC3's real-behavior citations**: `BookingService.cancelBookingAsParent`'s binary `refundEligible` computation
  (`:657-660`) and `CancellationRefundService.onBookingCancelledByParent`'s full-or-nothing read of it (`:36-53`)
  match exactly. The replacement bullet's retained claims about coach-initiated cancellation, coach no-show, and
  player no-show were independently checked against `onBookingCancelledByCoach`/`onCoachNoShow`/`onPlayerNoShow`
  (`:56-134`) and are still accurate: both coach-side events always issue a full refund/pack restore, and player
  no-show forfeits fully with no refund action — so AC3's replacement text doesn't introduce a new inaccuracy while
  fixing the parent-cancellation tiering.
- **Business-docs table** (`money/index.html:139-148`) independently confirmed still accurate against the same
  binary rule, and the only other `PARTIAL`-pattern hits in that file (`:151,153,156`) all fall inside the one
  callout AC3 replaces — no leftover stale text there.
- **`deferred-work.md` line citations**: `:1543-1545` (skillars-deferred-33 docs item) and `:1552-1555` (the two
  skillars-deferred-34 code-review findings) match exactly, including that both are already annotated
  `[PICKED UP by skillars-deferred-35 story creation, 2026-08-19]` from this story's own creation pass, consistent
  with AC5's plan to convert them to `[CLOSED by skillars-deferred-35 ACn]`.

None of the above needs changes.
