# Story Deferred-34: Batch-Accept Per-Booking Outcome Reporting

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a coach batch-accepting several session requests at once,
I want to know exactly which bookings in the batch were actually accepted and which were not (and why),
so that a partial failure is never reported to me as an unqualified success and I know which slots still need attention.

### Why this story exists

Drawn from `_bmad-output/implementation-artifacts/deferred-work.md`, under `## Deferred from: skillars-deferred-31
implementation (2026-08-18)`:

> `BookingBatchService.acceptAll` still cannot tell the coach *which* bookings in a batch failed, or why.
> `skillars-deferred-31` AC2 closed only the false-success half: a batch in which nothing was accepted is now a
> 403 `booking.batchNoneAccepted` instead of an HTTP 2xx behind an "All sessions accepted" toast. Everything short
> of that is still opaque. A **partial** success (3 of 5 accepted) reports as an unqualified success... Closing
> this needs `acceptAll` to return a per-booking result DTO — which booking ids settled, which did not, and the
> `ErrorCode` for each — which is a REST contract change (currently `void` → 204), a store change, and new
> rendering on `CoachBookingRequestsPage` for a partial outcome.

Unlike the last several `skillars-deferred-*` stories (11 through 33), which bundled several small, unrelated,
mechanical fixes, `deferred-work.md` has been mined thin of that kind of material — a fresh pass across the full
~1550-line ledger (this story's own creation, 2026-08-19, plus an independent fork covering the previously
never-audited `skillars-1` through `skillars-10`/`deploy-*` sections) found nothing else both small and
decision-free. Everything else open is either already owned by a shipped story, needs a product/design decision
(e.g. coach no-show refund semantics, the `jakarta.persistence.lock.timeout` fix approach), or — like this item —
is real, scoped, and mechanical, but sized for its own story rather than a bundle slot. Mbah selected this item
specifically over the alternatives on 2026-08-19.

**Re-verified against current code on master @ `8e3cc02` (post `skillars-deferred-33` merge) during this story's
creation** — the defect is exactly as the ledger describes, unchanged since it was filed:
`BookingBatchService.java:263-277`'s per-booking loop still `catch (Exception e) { log.warn(...) }`s with no
rethrow and no result captured; `acceptAll` is still `void`
(`BookingBatchService.java:233`); `BookingBatchResource.acceptAll` still returns `ResponseEntity<Void>`
(`BookingBatchResource.java:46-51`); `CoachBookingRequestsPage.vue`'s `handleAcceptAll` still shows one
unconditional positive toast (`booking.batch.acceptedAll`) on any non-error response
(`CoachBookingRequestsPage.vue:206`).

## Deferred Item Closed

| Source | Item | Current location (re-verified 2026-08-19) | AC | Planned outcome |
|---|---|---|---|---|
| `skillars-deferred-31` implementation (2026-08-18) | `BookingBatchService.acceptAll` cannot report which bookings in a batch succeeded vs. failed on a partial outcome — always an unqualified positive toast if `acceptedIds` is non-empty | `BookingBatchService.java:233-331`, `BookingBatchResource.java:46-51`, `CoachBookingRequestsPage.vue:201-234` | 1-4 | `acceptAll` returns a per-booking result list; frontend renders a distinct partial-outcome message |

**Explicitly NOT in this story** (considered during story creation and rejected):

- **Changing the total-failure path.** `skillars-deferred-31` AC2's `booking.batchNoneAccepted` 403 when
  `acceptedIds` is empty stays exactly as shipped — this story adds visibility to the *partial*-success case, it
  does not touch the zero-accepted case's status code or error key.
- **A full retry/resubmit UI for failed slots.** The ledger item asks for *reporting* (which ids, which reason),
  not a mechanism to re-attempt a failed slot from the same dialog. A coach who sees a slot failed can decline the
  batch's parent-side leftover or wait for the parent to resubmit; building an in-place retry is new scope beyond
  what was deferred.
- **Backend-driven i18n for the per-slot failure reason.** Per this codebase's established convention (see
  `project-context.md` and every prior `booking.*` errorKey), the frontend renders its own copy from the wire
  `errorKey`; the backend DTO carries the raw code only.

## Acceptance Criteria

1. **`BookingBatchService.acceptAll` returns a per-booking result instead of `void`.**

   Verified current state (`BookingBatchService.java:233-331`): the method is `@Transactional public void
   acceptAll(...)`. Its loop (`:263-277`) iterates `requestedBookings`, calls `acceptOneBooking` inside a
   `REQUIRES_NEW` transaction, and on success adds the id to `acceptedIds` (`:274`); on any `Exception`, it logs a
   warning and **discards** the exception and the booking id entirely (`:275-277`). Nothing downstream of the loop
   knows which bookings failed or why — only that `acceptedIds` grew or did not.

   **Required:**
   - New record `com.softropic.skillars.platform.booking.contract.BatchAcceptResult`:
     ```java
     package com.softropic.skillars.platform.booking.contract;

     import java.util.UUID;

     public record BatchAcceptResult(UUID bookingId, boolean accepted, String errorKey) {}
     ```
     `errorKey` is `null` when `accepted` is `true`. This mirrors `BatchBookingCreatedResponse`'s existing
     lean-record style (`booking.contract.BatchBookingCreatedResponse`) — no wrapper object, no
     `acceptedCount`/`failedCount` fields (both are trivially derivable by the caller from the list; do not add
     redundant fields the codebase has no precedent for).
   - Change `acceptAll`'s signature to `public List<BatchAcceptResult> acceptAll(UUID batchId, Long
     coachUserId)`.
   - Build the result list alongside the existing `acceptedIds` list (do not remove `acceptedIds` — it is still
     needed for `BatchBookingAcceptedEvent` and the `acceptedIds.isEmpty()` check):
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
     ```
   - New private helper, placed near the other private helpers at the bottom of the class:
     ```java
     /**
      * Every exception acceptOneBooking can throw today is one of these two shapes, both mapped to a stable
      * dot-separated wire code. Everything else — including ResponseStatusException, whose one live throw site
      * (readStatusOrThrow) builds its message from the raw booking id and the corrupted DB status value, not a
      * stable code — falls into the generic bucket below. Do NOT special-case ResponseStatusException.getReason()
      * back in: it is free-text diagnostic detail, not something safe to expose on the wire as errorKey, and
      * every consumer of this field (see failureReasonFor in CoachBookingRequestsPage.vue) is written assuming
      * errorKey is always either a known code or "generic.unknown". The fallback exists so a future throw site
      * added without updating this method still reports something identifiable rather than silently losing the
      * failure reason — do not treat it as dead code.
      */
     private String resolveFailureCode(Exception e) {
         if (e instanceof ApplicationException ae && ae.getErrorCode() != null) {
             return ae.getErrorCode().getErrorCode();
         }
         if (e instanceof BookingStateTransitionException bste) {
             return bste.getErrorCode();
         }
         return "generic.unknown";
     }
     ```
     New imports needed in `BookingBatchService.java`: `com.softropic.skillars.infrastructure.exception
     .ApplicationException`, `com.softropic.skillars.platform.booking.contract.BatchAcceptResult`,
     `com.softropic.skillars.platform.booking.contract.BookingStateTransitionException`. No import of
     `ResponseStatusException` is needed — it is deliberately not type-checked and falls through to the generic
     bucket like any other unrecognised exception (see the Javadoc above).
     Verified exhaustively which exceptions `acceptOneBooking` (`:347-376`) can actually throw, so this covers
     every live path: `ResourceNotFoundException` and `OperationNotAllowedException` both extend
     `ApplicationException` (covers the coach-not-found, suspended-coach, and slot-collision throws at
     `:349,356-357,364-368`); `bookingService.acceptAndInitiatePayment` → `transitionInternal` can throw
     `BookingStateTransitionException` (invalid state transition — `BookingStateMachine.java:87-98`) or, since
     `skillars-deferred-33` AC2, `ResponseStatusException` (a corrupted `status` column —
     `BookingService.java:594-601`) — the latter now resolves to `"generic.unknown"` rather than its raw message,
     per the code-review finding above. No other throw site exists in this call chain; the `generic.unknown`
     fallback is defensive for future throw sites, not merely for a case this story leaves untested — it IS
     reachable today via the `ResponseStatusException` path, just intentionally coarse.
   - The `acceptedIds.isEmpty()` branch (`:280-294`) is **unchanged** — it still throws
     `OperationNotAllowedException(..., BookingError.BATCH_NONE_ACCEPTED)` before any `results` list would ever
     be returned. Do not return `results` from that branch; the exception path stays exception-only, exactly as
     `skillars-deferred-31` AC2 shipped it.
   - At the end of the method (after the trailing-transaction block, replacing the current bare fall-through of a
     `void` method), add `return results;`.

2. **`BookingBatchResource.acceptAll` returns the per-booking results with a `200 OK`, not a blind `204`.**

   Verified current state (`BookingBatchResource.java:46-51`):
   ```java
   @PostMapping("/{batchId}/accept-all")
   @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
   public ResponseEntity<Void> acceptAll(@PathVariable UUID batchId) {
       batchService.acceptAll(batchId, currentUserId());
       return ResponseEntity.noContent().build();
   }
   ```
   Per `project-context.md`'s own REST convention ("return `204 No Content` for body-less success"), a response
   that now carries a body must not stay `204` — `200 OK` is correct.

   **Required:**
   ```java
   @PostMapping("/{batchId}/accept-all")
   @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
   public ResponseEntity<List<BatchAcceptResult>> acceptAll(@PathVariable UUID batchId) {
       List<BatchAcceptResult> results = batchService.acceptAll(batchId, currentUserId());
       return ResponseEntity.ok(results);
   }
   ```
   New imports: `java.util.List`, `com.softropic.skillars.platform.booking.contract.BatchAcceptResult`.
   No `@PreAuthorize`/`@Observed` change — this AC only changes the return shape and status of an already-secured
   endpoint.

3. **`booking.store.js`'s `handleAcceptAllBatch` must expose the per-booking results, keyed by batch, without
   breaking its existing refresh-outcome return contract.**

   Verified current state (`booking.store.js:565-581`):
   ```js
   const batchAcceptLoading = ref(false)
   const batchAcceptError = ref(null)

   async function handleAcceptAllBatch(batchId) {
     batchAcceptLoading.value = true
     batchAcceptError.value = null
     try {
       await acceptAllBatch(batchId)
       // Returns its own refresh outcome — see the CONTRACT note above loadCoachBookingRequests.
       return await loadCoachBookingRequests()
     } catch (e) {
       batchAcceptError.value = e
       throw e
     } finally {
       batchAcceptLoading.value = false
     }
   }
   ```
   `acceptAllBatch` (`src/frontend/src/api/booking.api.js:62`) is a thin axios wrapper — `api.post(...)` — that
   resolves to the full axios response object; its `.data` is currently discarded (`await acceptAllBatch(batchId)`
   with no capture). `handleAcceptAllBatch`'s **return value** is already load-bearing: `CoachBookingRequestsPage
   .vue:205`'s `notifyIfRequestsStale(await bookingStore.handleAcceptAllBatch(batchId))` depends on it being the
   boolean-ish refresh outcome from `loadCoachBookingRequests()`. **Do not change what this function returns.**

   **Do not add the per-booking results as a single shared ref.** `CoachBookingRequestsPage.vue` can have more
   than one batch group on screen at once, and nothing serializes their "Accept All" actions — `acceptingAll`
   (the page's own in-flight flag) is keyed per `batchId`, so a coach can click "Accept All" on batch A, then
   immediately click it on batch B while A's request is still in flight. A single shared ref (the naive
   mirror of `batchAcceptError`/`batchAcceptLoading` two lines above) would let whichever response resolves last
   silently overwrite the other batch's results — batch A's toast or per-row captions could then read from
   batch B's outcome, or vice versa. This is the same hazard the CONTRACT note above `loadCoachBookingRequests`
   (`booking.store.js:302-320`) already documents for other module-scoped refs, and the same reason that note's
   functions return their own per-invocation outcome instead of relying on a ref a caller re-reads. Key the new
   state by `batchId` instead — each batch's own entry can never be clobbered by another batch's call.

   **Required:**
   ```js
   const batchAcceptLoading = ref(false)
   const batchAcceptError = ref(null)
   // Keyed by batchId, not a single shared value — see the note above. batchAcceptError/batchAcceptLoading stay
   // single refs because nothing in this codebase reads them today (confirmed: no component references either
   // name); the moment AC4 makes this new state something the UI actually reads, per-batch keying stops being
   // optional.
   const batchAcceptResultsByBatch = ref({})

   async function handleAcceptAllBatch(batchId) {
     batchAcceptLoading.value = true
     batchAcceptError.value = null
     batchAcceptResultsByBatch.value = { ...batchAcceptResultsByBatch.value, [batchId]: null }
     try {
       const response = await acceptAllBatch(batchId)
       batchAcceptResultsByBatch.value = {
         ...batchAcceptResultsByBatch.value,
         [batchId]: response.data,
       }
       // Returns its own refresh outcome — see the CONTRACT note above loadCoachBookingRequests.
       return await loadCoachBookingRequests()
     } catch (e) {
       batchAcceptError.value = e
       throw e
     } finally {
       batchAcceptLoading.value = false
     }
   }
   ```
   Add `batchAcceptResultsByBatch` to the store's `return { ... }` block (`booking.store.js:583` onward, alongside
   the existing `batchAcceptError`/`batchAcceptLoading` entries — grep those two names in the return block to
   find the exact insertion point).

4. **`CoachBookingRequestsPage.vue`'s `handleAcceptAll` must distinguish a full accept from a partial accept.**

   Verified current state (`CoachBookingRequestsPage.vue:201-234`): on the `try` path (no thrown exception —
   which is every outcome where `acceptedIds` was non-empty, i.e. full **or** partial success), the handler
   unconditionally shows `$q.notify({ message: t('booking.batch.acceptedAll'), type: 'positive' })` (`:206`). A
   batch where 3 of 5 slots succeeded and 2 collided with newly-taken slots takes this exact path and tells the
   coach "All sessions accepted."

   **Required:** after the existing `notifyIfRequestsStale(await bookingStore.handleAcceptAllBatch(batchId))`
   call, read `bookingStore.batchAcceptResultsByBatch[batchId]` (this specific batch's own entry, not a shared
   value — see AC3) and branch on whether every result succeeded:
   ```js
   async function handleAcceptAll(batchId) {
     acceptingAll.value[batchId] = true
     try {
       // handleAcceptAllBatch runs loadCoachBookingRequests() itself and returns that refresh's outcome.
       notifyIfRequestsStale(await bookingStore.handleAcceptAllBatch(batchId))
       const results = bookingStore.batchAcceptResultsByBatch[batchId] ?? []
       const failedCount = results.filter((r) => !r.accepted).length
       if (failedCount > 0) {
         $q.notify({
           type: 'warning',
           message: t('booking.batch.partiallyAccepted', {
             accepted: results.length - failedCount,
             total: results.length,
           }),
         })
       } else {
         $q.notify({ message: t('booking.batch.acceptedAll'), type: 'positive' })
       }
     } catch (err) {
       // ... unchanged from :207-233 ...
     } finally {
       acceptingAll.value[batchId] = false
     }
   }
   ```
   The `catch` block (the total-failure path — `booking.coachUnavailable`/`booking.batchAlreadyProcessed`/
   `booking.batchNoneAccepted`/`MISSING_RIGHTS`/generic) is **unchanged**; this AC only touches the `try` path's
   success branch.

   **Per-slot reason on the still-pending rows.** After a partial accept, `loadCoachBookingRequests()` has
   already refreshed `bookingStore.coachBatchGroups`, so `group.bookings` for this batch now contains exactly the
   bookings that are still `REQUESTED` — the failed subset (accepted ones transitioned away and no longer match
   the group's own REQUESTED-only query). Add an inline caption on each such row showing why it wasn't accepted,
   using a small local lookup keyed on the known `errorKey`s `resolveFailureCode` can produce
   (`booking.slotUnavailable`, `booking.coachUnavailable`), with a generic fallback caption for anything else
   (`booking.invalidTransition`, `generic.unknown`, or the folded-in corrupted-status case — see AC1's
   `resolveFailureCode`, which now returns `"generic.unknown"` for that case rather than a raw message):
   ```js
   function failureReasonFor(batchId, bookingId) {
     const results = bookingStore.batchAcceptResultsByBatch[batchId] ?? []
     const result = results.find((r) => r.bookingId === bookingId)
     if (!result || result.accepted) return null
     if (result.errorKey === 'booking.slotUnavailable') return t('booking.errors.slotUnavailable')
     if (result.errorKey === 'booking.coachUnavailable') return t('booking.errors.coachUnavailable')
     return t('booking.batch.itemNotAccepted')
   }

   // Once we know (from this session's own accept-all call) that a batch has a failed slot, the
   // group's "Accept All" button must stop being offered — see the note below on why leaving it up
   // is actively broken, not just redundant.
   function groupHasKnownFailure(batchId) {
     return (bookingStore.batchAcceptResultsByBatch[batchId] ?? []).some((r) => !r.accepted)
   }
   ```
   In the template, inside the batch group's `q-item` (`CoachBookingRequestsPage.vue:42-73`), add one
   `q-item-label caption` bound to `failureReasonFor(group.batchId, booking.id)`, rendered only when non-null,
   e.g. immediately after the existing `formatDateTime` caption at `:49-51`:
   ```html
   <q-item-label v-if="failureReasonFor(group.batchId, booking.id)" caption class="text-negative">
     {{ failureReasonFor(group.batchId, booking.id) }}
   </q-item-label>
   ```
   This is deliberately read-only, session-scoped context (cleared implicitly on next page load, since
   `batchAcceptResultsByBatch` starts empty on every store initialisation) — it is not persisted, and does not
   need to be: it explains the *most recent* accept-all attempt's outcome for that specific batch, not a durable
   audit trail. Being keyed by `batchId` (AC3), it survives a *different* batch's accept-all call untouched —
   only a second accept-all attempt on the *same* batch resets that batch's own entry, which is the scenario the
   next paragraph covers.

   **Once a batch has a known partial failure, its "Accept All" button must stop being offered.**
   `BookingBatchService.acceptAll`'s trailing transaction sets `batch.status` to `PARTIALLY_ACCEPTED` as soon as
   the loop finishes — regardless of whether any bookings are still `REQUESTED` (unlike
   `updateBatchStatusFromBooking`'s sibling path, which waits until none are). This is existing, tested behaviour
   (`BookingBatchResourceIT.acceptAll_oneSlotCollides_acceptsOtherAndEndsPartiallyAccepted` asserts
   `PARTIALLY_ACCEPTED` while one booking is still `REQUESTED`) and is **not** being changed by this story. The
   consequence: `acceptAll`'s own top-of-method guard
   (`if (!"PENDING".equals(batch.getStatus())) throw ...BATCH_ALREADY_PROCESSED`) means a *second* click on
   "Accept All" for the same batch will always fail — there is no scenario where clicking it again succeeds. Left
   alone, the button stays visible, enabled, and labelled with the batch's original (now-inflated)
   `group.totalCount`, sitting directly below the new per-row failure captions this AC adds — inviting the coach
   to click something that is guaranteed to fail with a "these requests have already been handled" toast, and
   whose own `handleAcceptAllBatch` call resets that batch's `batchAcceptResultsByBatch` entry to `null` *before*
   the doomed request even fires, wiping the captions that were just explained.

   Hide the button once this batch's own results show a failure:
   ```html
   <q-card-actions v-if="!groupHasKnownFailure(group.batchId)">
     <q-btn
       unelevated
       color="positive"
       class="full-width"
       :label="t('booking.batch.acceptAll', { n: group.totalCount })"
       :loading="acceptingAll[group.batchId]"
       @click="handleAcceptAll(group.batchId)"
     />
   </q-card-actions>
   ```
   (Replaces the unconditional `q-card-actions` currently at `CoachBookingRequestsPage.vue:75-84` — everything
   inside it is otherwise unchanged.) This guard is necessarily session-scoped: it only fires once *this page
   session* has itself observed a failed slot for that batch via `batchAcceptResultsByBatch`. A coach who reloads
   the page after a partial accept from an earlier session will still see the button (the backend response this
   story adds does not carry batch status, only per-booking outcomes, and adding that is out of this story's
   scope) — that pre-existing gap is unchanged by this story. What this AC does fix is the regression this
   story's own new UI would otherwise cause: showing an explanation for a failure right next to a button that
   erases that explanation and then fails anyway.

   **New i18n keys**, in all three frontend bundles (`en-US`, `de-DE`, `fr-FR`), inside the existing `batch: {
   ... }` block next to `acceptedAll`/`acceptError` (`en-US/index.js:916-917`, `de-DE/index.js:459-460`,
   `fr-FR/index.js:1198-1199`):
   - `partiallyAccepted`: interpolated with `{accepted}`/`{total}`, e.g. en-US:
     `'{accepted} of {total} sessions accepted. See below for the rest.'`
   - `itemNotAccepted`: generic per-row fallback caption, e.g. en-US: `'Could not be accepted.'`
   Use idiomatic de-DE/fr-FR translations matching this file's existing tone (informal "du" is NOT used anywhere
   in this codebase's German bundle — check neighboring `batch.*` keys for the formal register already in use).

5. **Tests prove the new response body and the frontend's partial-outcome branch, mutation-verified where
   applicable.**

   - **`BookingBatchServiceTest`** (`src/test/java/.../booking/service/BookingBatchServiceTest.java`): every
     existing test that calls `service.acceptAll(...)` and does not currently capture its return value must
     still compile and pass once the signature becomes non-`void` (Java permits ignoring a return value, so no
     assertion needs to change unless the test is specifically about the return) — confirm this by running the
     full class, not just reading it.
     - Add a new case, `acceptAll_oneSlotCollides_returnsOneAcceptedOneFailedResult`, adapting the existing
       `acceptAll_batchAlreadyContainsADeclinedBooking_endsPartiallyAccepted` fixture shape (`:475-508`): seed two
       REQUESTED bookings, make `acceptOneBooking`'s downstream collision check fail for one of them (mock
       `bookingRepository.findOverlappingBookings` to return a non-empty list for booking B's coordinates, empty
       for booking A's), call `service.acceptAll(...)`, and assert the returned `List<BatchAcceptResult>` has
       exactly one `accepted=true` entry for booking A's id with `errorKey=null`, and one `accepted=false` entry
       for booking B's id with `errorKey="booking.slotUnavailable"`.
     - Mutation-verify: temporarily make `resolveFailureCode` return a hardcoded string regardless of input,
       confirm the new test's `errorKey` assertion fails, restore byte-identical.
   - **`BookingBatchResourceIT`** (`src/test/java/.../booking/api/BookingBatchResourceIT.java`): extend the two
     existing partial-accept tests to assert on the response body instead of only the DB state they already
     check. **This class's `httpTestClient.makeHttpRequest` has no precedent anywhere in this test suite for a
     typed array/record response class** — every existing body-bearing assertion in this codebase's ITs (e.g.
     `FamilyDataIsolationIT.java:164-177`, `BookingBatchResourceIT.java:176-183`'s own `createBatch` test) uses
     `ResponseEntity<List>` or `ResponseEntity<Map>` and casts each element to `Map<?, ?>` with `.get("field")`
     reads. Follow that exact pattern — do not introduce a new response-typing style for this one endpoint:
     ```java
     ResponseEntity<List> response = httpTestClient.makeHttpRequest(
         baseUrl() + "/api/bookings/batches/" + batchId + "/accept-all",
         HttpMethod.POST, null, authenticatedHeaders(coachCookies), List.class
     );
     assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
     List<?> results = response.getBody();
     ```
     Then cast each element to `Map<?, ?>` and read `.get("bookingId")`/`.get("accepted")`/`.get("errorKey")`.

     **Every one of the three tests below currently asserts
     `assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT)` — all three must update that
     assertion to `HttpStatus.OK`, not just the one explicitly renamed.** AC2 changes the endpoint's status from
     `204` to `200` for every successful call, partial or full, so this is not optional for any of them; missing
     it on the first two below leaves a compiling test that fails at runtime.
     - `acceptAll_oneSlotCollides_acceptsOtherAndEndsPartiallyAccepted` (`:290-342`): change the response type
       from `ResponseEntity<Void>` to `ResponseEntity<List>`, **update the status assertion from
       `HttpStatus.NO_CONTENT` to `HttpStatus.OK`** (line 314 today), and add an assertion that the body has one
       element with `accepted() == true` and one with `accepted() == false` and `errorKey ==
       "booking.slotUnavailable"`.
     - `acceptAll_withASiblingDeclinedBeforehand_endsPartiallyAccepted` (`:422+`): same response-type change,
       **same status-assertion update from `HttpStatus.NO_CONTENT` to `HttpStatus.OK`** (line 437 today); assert
       the body reflects only the `REQUESTED` booking's outcome (the already-declined sibling was never in
       `requestedBookings` to begin with, so it must not appear in the results list at all — confirm the list
       size equals 1, not 2).
     - `acceptAll_asOwningCoach_returns204AndUpdatesBookingsAndBatch` (`:254-278`): rename to
       `acceptAll_asOwningCoach_returns200AndUpdatesBookingsAndBatch` (status changed from `204` to `200` per
       AC2), update the status assertion to `HttpStatus.OK`, and add a body assertion that both results have
       `accepted == true`.
     - `acceptAll_everySiblingDeclinedBeforehand_returns403WithBatchNoneAcceptedKey` (`:460+`): **unchanged** —
       this test hits the `acceptedIds.isEmpty()` exception path, which AC1 explicitly leaves untouched, and
       never returned `204`/`200` in the first place (it's a thrown 403).
   - **Frontend**: no automated test infrastructure exists in this repo (`package.json`'s `test` script is a
     no-op placeholder; confirmed zero `*.spec.js`/`*.test.js` files outside `node_modules`) — this is a
     standing, project-wide gap noted by every prior story with frontend changes, not something to fix here.
     Manual verification of the new toast wording and the per-row failure caption is required before merge but
     cannot be automated; record in Completion Notes that this was done (or that it could not be, per the
     project's standing no-browser-tooling constraint — match the exact phrasing prior stories used for this).

6. **Ledger hygiene.** In `deferred-work.md`:
   - Annotate the primary item (the "Deferred Item Closed" table row above) `[CLOSED by skillars-deferred-34
     AC1-4]` at its existing location under `## Deferred from: skillars-deferred-31 implementation
     (2026-08-18)`, describing what shipped, per the format every prior `skillars-deferred-*` story used.
   - Mark these 3 items, independently verified during this story's creation as already fixed in code but never
     annotated, `[STALE — verified against current code by skillars-deferred-34 story creation, 2026-08-19:
     already fixed, ledger not updated]` at their existing locations:
     - `skillars-deferred-3` D2 ("no test verifies NULL provider_asset_id videos coexist") — closed by
       `VideoRepositoryIT.java:30`'s `nullProviderAssetId_multipleVideosCoexist()`.
     - `skillars-deferred-3` D3 ("concurrency test masks `barrier.await()` failures with `catch(Exception
       ignored)`") — closed by `SessionTemplateResourceIT.java:507-515`'s `awaitBarrier`, which already rethrows
       as `AssertionError` on both `InterruptedException` and `BrokenBarrierException|TimeoutException`.
     - `skillars-2-3` review ("test doesn't assert 404 status before the cast") — closed by
       `CoachProfileResourceIT.java:119-123`'s `getCoachProfile_unknownId_returns404`, which already has a
       status-code assertion via `.satisfies(e -> ...isEqualTo(HttpStatus.NOT_FOUND))`.
   - Do **not** re-verify or touch anything else in the file — the rest of the ledger was read during this
     story's creation and everything else open is either already owned by a shipped story or needs a decision
     this story does not make.
   - `sprint-status.yaml`: add the `skillars-deferred-34-batch-accept-per-booking-outcome-reporting` entry
     (already added at story-creation time by this workflow) and its `last_updated` note.

## Tasks / Subtasks

- [x] **Task 1 — AC1: `BookingBatchService.acceptAll` returns per-booking results**
  - [x] New `BatchAcceptResult` record in `booking.contract`
  - [x] Change `acceptAll` signature to return `List<BatchAcceptResult>`
  - [x] Build `results` alongside `acceptedIds` in the loop; add `resolveFailureCode(Exception)` helper
  - [x] `acceptedIds.isEmpty()` branch unchanged (still throws, still no `results` return from that branch)
  - [x] `return results;` at method end
- [x] **Task 2 — AC2: `BookingBatchResource.acceptAll` wired to the new response**
  - [x] Return type `ResponseEntity<List<BatchAcceptResult>>`, status `200 OK`
- [x] **Task 3 — AC3: `booking.store.js` exposes per-booking results, keyed by batch**
  - [x] New `batchAcceptResultsByBatch` ref (object keyed by `batchId`, NOT a single shared value — two
        concurrent "Accept All" calls on different batches must not be able to clobber each other's results),
        set from `response.data` inside `handleAcceptAllBatch`
  - [x] `handleAcceptAllBatch`'s return value (the refresh outcome) is unchanged
  - [x] `batchAcceptResultsByBatch` added to the store's returned object
- [x] **Task 4 — AC4: `CoachBookingRequestsPage.vue` partial-outcome UX**
  - [x] `handleAcceptAll`'s success branch distinguishes full vs. partial accept, reading
        `bookingStore.batchAcceptResultsByBatch[batchId]`
  - [x] New `partiallyAccepted`/`itemNotAccepted` i18n keys in `en-US`/`de-DE`/`fr-FR`
  - [x] `failureReasonFor(batchId, bookingId)` helper + inline caption on still-pending batch rows
  - [x] `groupHasKnownFailure(batchId)` helper; group's "Accept All" `q-card-actions` hidden when true (a
        second click on an already-partially-accepted batch always fails with `batchAlreadyProcessed` and would
        also wipe that batch's own just-shown failure captions — see AC4)
- [x] **Task 5 — AC5: tests**
  - [x] `BookingBatchServiceTest`: new `acceptAll_oneSlotCollides_returnsOneAcceptedOneFailedResult`,
        mutation-verified
  - [x] `BookingBatchResourceIT`: extend the two existing partial-accept tests + the fully-accepted test with
        response-body assertions; rename the fully-accepted test to `...returns200...`; **all three** (not just
        the renamed one) need their status assertion changed from `HttpStatus.NO_CONTENT` to `HttpStatus.OK`
  - [x] Full `mvn -o verify` green; record surefire/failsafe counts against `skillars-deferred-33`'s baseline
        (surefire 893, failsafe 934)
  - [x] Manual frontend verification of the new toast/caption, including that the "Accept All" button disappears
        for a batch with a known partial failure and that a second batch's accept-all does not disturb the
        first batch's captions (or explicit note if no browser tooling available)
- [x] **Task 6 — AC6: ledger hygiene**
  - [x] `[CLOSED by skillars-deferred-34 AC1-4]` on the primary item
  - [x] `[STALE ...]` on the 3 verified-stale items
  - [x] `sprint-status.yaml` entry

### Review Findings

Code review (2026-08-19): Blind Hunter + Edge Case Hunter + Acceptance Auditor. Acceptance Auditor found 0 AC
violations — all four concerns raised by the pre-implementation `story-review.md` draft (AC5 status-assertion
gaps, `batchAcceptResults` shared-ref race, dead "Accept All" button, raw-message `errorKey` leak) are confirmed
fixed in the shipped code.

- [x] [Review][Decision→Patch] `groupHasKnownFailure`'s partial-accept signal was session-ephemeral, not
  persisted server-side — **Resolved:** `BatchGroupedBookingResponse` gained a `status` field
  (`BookingService.getCoachBookingRequests`, sourced fresh from `BookingBatch.status` on every load), and
  `CoachBookingRequestsPage.vue`'s button visibility now reads `group.status === 'PENDING'`
  (`batchIsActionable`) instead of the client-only `batchAcceptResultsByBatch` ref. Survives reload/new
  tab/second session since it comes from the DB, not this session's own accept-all response.
  [`src/main/java/.../booking/contract/BatchGroupedBookingResponse.java`,
  `src/main/java/.../booking/service/BookingService.java:492-513`,
  `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue`]
- [x] [Review][Decision→Patch] Partial-accept toast total disagreed with the batch card's own label —
  **Resolved:** `BatchGroupedBookingResponse.totalCount` (and the per-booking `BookingResponse.batchSize`
  it feeds) now reflects the batch's current still-`REQUESTED` booking count
  (`batchBookings.size()`, the query already filters to `status='REQUESTED'`), not
  `BookingBatch.requestedCount` (the original batch size at creation, never decremented by individual
  declines). New `BookingBatchResourceIT` assertions on `acceptAll_oneSlotCollides_...` pin
  `GET /api/bookings/requests/coach` returning `status: "PARTIALLY_ACCEPTED"` and `totalCount: 1` for a
  2-booking batch with 1 still-`REQUESTED` sibling. [`src/main/java/.../booking/service/BookingService.java:492-513`,
  `src/test/java/.../booking/api/BookingBatchResourceIT.java`]
- [x] [Review][Patch] `story-review.md` ships 4 "open" findings already fixed in this same diff — **Applied:**
  appended a `[CLOSED — verified against the shipped diff ...]` note to each of the 4 findings, citing the
  exact code that resolves it. [`_bmad-output/implementation-artifacts/story-review.md`]
- [x] [Review][Patch] `BookingBatchResourceIT` deserializes the response body as raw `List.class` + `Map` casts
  instead of a typed `ParameterizedTypeReference<List<BatchAcceptResult>>` — **Applied:** added a
  `ParameterizedTypeReference` overload to `HttpTestClient.makeHttpRequest` (purely additive, existing
  `Class<T>` overloads untouched) and converted all 3 accept-all call sites (now 4, including the Decision-2
  regression test) to `ResponseEntity<List<BatchAcceptResult>>` with typed accessors instead of `Map` casts.
  [`src/test/java/com/softropic/skillars/e2e/HttpTestClient.java`,
  `src/test/java/com/softropic/skillars/platform/booking/api/BookingBatchResourceIT.java`]
- [x] [Review][Patch] fr-FR's new `partiallyAccepted` string uses a manual "(s)" pluralization hack
  inconsistent with sibling strings' unmarked-plural convention — **Applied:** reworded to
  `'{accepted} séances sur {total} acceptées. Voir ci-dessous pour le reste.'`, matching the sibling
  `acceptAll: 'Accepter les {n} séances'` convention. [`src/frontend/src/i18n/fr-FR/index.js`]
- [x] [Review][Patch] `resolveFailureCode`'s documented invariant (never leak `ResponseStatusException.getReason()`
  into `errorKey`) has no executable test enforcing it — **Applied:** new
  `BookingBatchServiceTest.acceptAll_oneBookingHasCorruptedStatus_returnsGenericUnknownNotRawMessage`, mutation-verified
  (reintroduced the `ResponseStatusException` special-case, confirmed the new assertion failed, restored
  byte-identical). [`src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java`]

Full `mvn -o clean verify` after all decisions + patches applied: BUILD SUCCESS, surefire 895 (+1 vs this
story's own 894, exactly the new `resolveFailureCode` regression test), failsafe 934 (unchanged — only
existing tests extended), 0 failures/errors. ESLint clean on all touched frontend files.
- [x] [Review][Defer] `acceptAll` discards computed per-booking results on the total-failure path (`acceptedIds.isEmpty()` still throws `BATCH_NONE_ACCEPTED` with no result detail) [`src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:265-300`] — deferred, pre-existing/explicitly out of this story's scope (total-failure path stated unchanged)
- [x] [Review][Defer] `failureReasonFor` does a linear `.find()` scan over the batch's result array on every template re-render, once per row [`src/frontend/src/pages/coach/CoachBookingRequestsPage.vue`] — deferred, pre-existing pattern, low impact at current batch sizes

## Dev Notes

### Established conventions this story must follow

- **Lean DTOs, no redundant derived fields.** `BatchAcceptResult` carries only `bookingId`/`accepted`/`errorKey`
  — `acceptedCount`/`failedCount` are one `.filter()` away in the caller and this codebase's existing batch DTOs
  (`BatchBookingCreatedResponse`) do not carry derived counts either.
- **The frontend renders its own copy from `errorKey`, never from a backend message string.** Every `booking.*`
  error-handling call site in this codebase (see `BookingRequestPage.vue`, `ParentBookingsPage.vue`, this same
  page's existing `catch` block) follows this; AC4's `failureReasonFor` continues it.
- **`REQUIRES_NEW` per-booking transactions stay exactly as they are.** This story adds a result value to what
  the loop already does; it does not change transaction boundaries, locking, or the trailing-transaction shape
  documented at `BookingBatchService.java:221-231,296-328`. Do not "simplify" any of that while touching this
  method — it is deliberate, load-bearing behavior from `skillars-deferred-14`/`-15`, not incidental.
- **`store.js` return-value contracts are load-bearing.** `notifyIfRequestsStale`'s call sites across this file
  depend on specific functions returning specific things (see the CONTRACT comments already in the file above
  `loadCoachBookingRequests` and throughout `CoachBookingRequestsPage.vue`). AC3 is written the way it is
  specifically to avoid becoming the next one of these that a future story has to explain away.
- **Shared, module-scoped refs that more than one concurrent action can write are a known hazard in this file** —
  see the CONTRACT note above `loadCoachBookingRequests` (`booking.store.js:302-320`), which exists because two
  independent reviews already flagged a version of this problem for `coachRequestsError`/`coachScheduleError`.
  `batchAcceptResultsByBatch` (AC3) is keyed by `batchId` specifically because it is the first ref of this kind
  that the UI actually reads (AC4) — `batchAcceptError`/`batchAcceptLoading`, the pattern it would otherwise have
  mirrored, are currently write-only and unread by any component, so a single shared value never actually caused
  an observable bug there. Do not "simplify" the keyed ref back into a single shared value.
- **`errorKey` is always a stable, dot-separated wire code — never a free-text message.** Every `booking.*`
  error-handling call site in this codebase (see `BookingRequestPage.vue`, `ParentBookingsPage.vue`, this same
  page's existing `catch` block) follows this, and `resolveFailureCode` (AC1) must too: its
  `ResponseStatusException` case deliberately does **not** pass through `rse.getReason()` (a raw sentence
  embedding the booking's UUID and its corrupted DB status value for that one throw site) — it folds into the
  same `"generic.unknown"` bucket as any other unrecognised exception instead. `failureReasonFor` (AC4) is
  written assuming every `errorKey` it can receive is one of the known codes or `"generic.unknown"`; do not
  reintroduce a free-text value there.

### Files being modified — current state and what must be preserved

- **`BookingBatchService.acceptAll`** (`:233-331`) — AC1 changes the method's return type and adds result
  tracking inside the existing loop. The lock/transaction shape, the `acceptedIds.isEmpty()` exception, the
  trailing-transaction block, and the final `log.info` are all unchanged.
- **`BookingBatchResource.acceptAll`** (`:46-51`) — AC2 changes only the return type and status; the
  `@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)` guard and `currentUserId()` call are unchanged.
- **`booking.store.js`** — AC3 adds one new ref (`batchAcceptResultsByBatch`, keyed by `batchId` — not a plain
  `ref(null)`) and a few lines inside `handleAcceptAllBatch` (`:565-581`); every other store function is
  untouched.
- **`CoachBookingRequestsPage.vue`** — AC4 changes `handleAcceptAll`'s `try` branch (`:201-234`, the `catch`
  branch is untouched), adds one template caption inside the existing batch-group `q-item` (`:42-73`), and wraps
  the group's existing `q-card-actions` "Accept All" button (`:75-84`) in a `v-if="!groupHasKnownFailure(...)"` —
  everything inside that block is otherwise unchanged.

### Project Structure Notes

- No new REST endpoint, no DTO beyond the one new record, no migration — this story is a response-shape and
  frontend-rendering change only.
- `BatchAcceptResult` belongs beside `BatchBookingCreatedResponse` in
  `src/main/java/com/softropic/skillars/platform/booking/contract/` (existing package convention for this
  module's wire DTOs).

### References

- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:221-331,347-376`
- `src/main/java/com/softropic/skillars/platform/booking/api/BookingBatchResource.java:46-51`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BatchBookingCreatedResponse.java` (reference
  lean-record DTO style)
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java` (existing wire-code
  conventions and doc comment on why `OperationNotAllowedException` always maps to 403 regardless of code)
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingStateTransitionException.java`
- `src/main/java/com/softropic/skillars/infrastructure/exception/ApplicationException.java:49` (`getErrorCode()`)
- `src/frontend/src/api/booking.api.js:62`
- `src/frontend/src/stores/booking.store.js:565-581,583+` (return block)
- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue:42-73,155-165,201-234`
- `src/frontend/src/i18n/en-US/index.js:901-960` (`batch` namespace), `de-DE/index.js:443-460`,
  `fr-FR/index.js:1182-1199`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java:466-620`
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingBatchResourceIT.java:254-500`
- `_bmad-output/implementation-artifacts/deferred-work.md` (`## Deferred from: skillars-deferred-31
  implementation (2026-08-18)`, and the `skillars-deferred-3`/`skillars-2-3` sections for the ledger-hygiene AC)
- `_bmad-output/project-context.md`

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (dev-story workflow)

### Debug Log References

None — no blocking failures encountered. Full `mvn -o clean verify` run log retained at
`/tmp/full_verify_output.log` for this session.

### Completion Notes List

- AC1: added `BatchAcceptResult(UUID bookingId, boolean accepted, String errorKey)` record in
  `booking.contract`; `BookingBatchService.acceptAll` now returns `List<BatchAcceptResult>` instead of `void`.
  Added `resolveFailureCode(Exception)`, exactly as specified — `ApplicationException`-derived exceptions map
  via `getErrorCode().getErrorCode()`, `BookingStateTransitionException` via `getErrorCode()`, everything else
  (including the `ResponseStatusException` corrupted-status path from `skillars-deferred-33` AC2) falls to
  `"generic.unknown"`. The `acceptedIds.isEmpty()` exception branch is unchanged. Confirmed both
  `ResourceNotFoundException` and `OperationNotAllowedException` extend `ApplicationException` (the latter via
  `AuthorizationException`) before relying on the `instanceof` check.
- AC2: `BookingBatchResource.acceptAll` now returns `ResponseEntity<List<BatchAcceptResult>>` with `200 OK`
  instead of `ResponseEntity<Void>`/`204`. `@PreAuthorize` and `currentUserId()` unchanged.
- AC3: `booking.store.js`'s `handleAcceptAllBatch` now captures `response.data` into a new
  `batchAcceptResultsByBatch` ref keyed by `batchId` (not a single shared ref, per the story's explicit
  concurrent-batch hazard warning). The function's return value (the refresh outcome) is unchanged.
  `batchAcceptResultsByBatch` added to the store's returned object.
- AC4: `CoachBookingRequestsPage.vue`'s `handleAcceptAll` success branch now distinguishes full vs. partial
  accept via the new per-batch results, showing a `booking.batch.partiallyAccepted` warning toast with counts
  on partial success. Added `failureReasonFor(batchId, bookingId)` (per-row caption, mapped from
  `slotUnavailable`/`coachUnavailable`/generic fallback) and `groupHasKnownFailure(batchId)` (hides the
  group's "Accept All" button once a failure is known, since a second click always fails with
  `batchAlreadyProcessed` and would wipe the just-shown captions). New i18n keys `batch.partiallyAccepted` and
  `batch.itemNotAccepted` added to `en-US`/`de-DE`/`fr-FR`, matching each bundle's existing tone (formal
  register in `de-DE`, consistent with neighboring `batch.*` keys).
- AC5: new `BookingBatchServiceTest.acceptAll_oneSlotCollides_returnsOneAcceptedOneFailedResult`, adapted from
  the existing `acceptAll_batchAlreadyContainsADeclinedBooking_endsPartiallyAccepted` fixture shape.
  Mutation-verified: temporarily hardcoded `resolveFailureCode` to return a fixed string, confirmed the new
  test's `errorKey` assertion failed (`expected: "booking.slotUnavailable" but was: "MUTATION_TEST_HARDCODED"`),
  restored byte-identical, re-ran green. `BookingBatchResourceIT`'s three affected tests extended with
  response-body assertions and their status assertion changed from `HttpStatus.NO_CONTENT` to `HttpStatus.OK`:
  `acceptAll_asOwningCoach_returns204AndUpdatesBookingsAndBatch` renamed to
  `...returns200AndUpdatesBookingsAndBatch` (asserts both results `accepted == true`);
  `acceptAll_oneSlotCollides_acceptsOtherAndEndsPartiallyAccepted` (asserts one accepted, one failed with
  `errorKey == "booking.slotUnavailable"`); `acceptAll_withASiblingDeclinedBeforehand_endsPartiallyAccepted`
  (asserts the results list reflects only the 2 REQUESTED bookings from this fixture's 3-booking batch — the
  already-declined sibling never entered `requestedBookings` and does not appear). The fourth test,
  `acceptAll_everySiblingDeclinedBeforehand_returns403WithBatchNoneAcceptedKey`, is unchanged per AC1/AC5 —
  confirmed still passing (still hits the exception path, never returned `204`/`200`).
  Full `mvn -o clean verify`: **BUILD SUCCESS** — surefire 894 unit tests (+1 vs. `skillars-deferred-33`'s
  baseline of 893, exactly the one new unit test), failsafe 934 IT (unchanged vs. baseline — the three affected
  ITs were extended in place, not added to), 0 failures, 0 errors, 1 unit + 4 IT skipped (pre-existing,
  unrelated to this story).
  Manual frontend verification of the new toast wording, per-row failure caption, and "Accept All" button
  hiding was **not performed** — no browser tooling available in this environment, a standing project-wide gap
  every prior story with frontend changes has recorded the same way. ESLint clean on all touched frontend
  files (`booking.store.js`, `CoachBookingRequestsPage.vue`, all three i18n bundles). Prettier flags all five
  touched frontend files, but this was independently confirmed pre-existing (via `git stash` + `prettier
  --check` against the unmodified files at HEAD) and not introduced by this story's changes.
- AC6: primary ledger item converted from `[PICKED UP by skillars-deferred-34 story creation, 2026-08-19]` to
  `[CLOSED by skillars-deferred-34 AC1-4]` with a description of what shipped. The 3 stale items
  (`skillars-deferred-3` D2/D3, `skillars-2-3` review) were found already annotated `[STALE — verified against
  current code by skillars-deferred-34 story creation, 2026-08-19: ...]` at story-creation time — no further
  action needed for those three. `sprint-status.yaml` entry was likewise already present at story-creation
  time (`ready-for-dev`); this workflow updated it to `in-progress` and now to `review`.

### File List

**New:**
- `src/main/java/com/softropic/skillars/platform/booking/contract/BatchAcceptResult.java`

**Modified:**
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/BookingBatchResource.java`
- `src/frontend/src/stores/booking.store.js`
- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/de-DE/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingBatchResourceIT.java`
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

### Change Log

| Date | Change | Author |
|---|---|---|
| 2026-08-19 | AC1: new `BatchAcceptResult` record; `BookingBatchService.acceptAll` returns `List<BatchAcceptResult>` instead of `void`, with `resolveFailureCode(Exception)` mapping every live throw site to a stable wire code. | dev-story (Claude Sonnet 5) |
| 2026-08-19 | AC2: `BookingBatchResource.acceptAll` returns `ResponseEntity<List<BatchAcceptResult>>` with `200 OK` instead of `204`. | dev-story (Claude Sonnet 5) |
| 2026-08-19 | AC3: `booking.store.js`'s `handleAcceptAllBatch` exposes per-booking results via new `batchAcceptResultsByBatch`, keyed by `batchId`. | dev-story (Claude Sonnet 5) |
| 2026-08-19 | AC4: `CoachBookingRequestsPage.vue` renders a distinct partial-outcome toast, per-row failure captions, and hides "Accept All" once a batch has a known failure; new `batch.partiallyAccepted`/`batch.itemNotAccepted` i18n keys in all 3 bundles. | dev-story (Claude Sonnet 5) |
| 2026-08-19 | AC5: new `BookingBatchServiceTest.acceptAll_oneSlotCollides_returnsOneAcceptedOneFailedResult` (mutation-verified); 3 `BookingBatchResourceIT` tests extended with response-body assertions and updated from `204` to `200`. Full `mvn -o clean verify` green: surefire 894 (+1), failsafe 934 (unchanged), 0 failures. | dev-story (Claude Sonnet 5) |
| 2026-08-19 | AC6: ledger hygiene — primary item `[CLOSED by skillars-deferred-34 AC1-4]` in `deferred-work.md`. | dev-story (Claude Sonnet 5) |
| 2026-08-19 | Story status: ready-for-dev → in-progress → review. | dev-story (Claude Sonnet 5) |
