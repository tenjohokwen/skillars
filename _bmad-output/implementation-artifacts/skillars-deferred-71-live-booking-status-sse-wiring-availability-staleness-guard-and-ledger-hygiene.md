# Story Deferred-71: Live Booking-Status SSE Wiring, Availability-Staleness Guard & Booking-Module Ledger Hygiene

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a platform owner continuing the `deferred-work.md` drawdown, I want (1) the already-built-but-never-called
`useBookingSse` composable wired into the two live booking-management list pages so a `BookingStateChip`
updates in real time instead of only on next reload, with a matching backend fix so this doesn't multiply an
existing SSE resource-leak concern, (2) a real gap closed where a parent's availability view can go stale
between the calendar `GET` and the booking `POST` with no signal that it happened, and (3) ledger hygiene
closing fourteen now-stale, already-fixed-elsewhere, or verified-incorrect `deferred-work.md` items in the
Booking/Availability/Reschedule module, so that the next re-mining pass starts from an accurate, much thinner
ledger.

### Why this story exists

Re-mined `deferred-work.md` (1634 lines) for the Booking/Availability/Reschedule module — the top priority
per the project owner's standing instruction. `skillars-deferred-69` and `skillars-deferred-70` both
characterized this module as "genuinely thin" after their own closures, but that characterization was based
on what had already been closed, not on re-verifying every remaining open bullet against live source. This
story did that verification, module-by-module bullet-by-bullet, and found the opposite of "thin": most
remaining bullets are stale (superseded by later stories that never went back to tag them closed), a few are
demonstrably incorrect on re-reading current code, and two are real, still-open, substantive gaps worth
building. Booking/Availability/Reschedule was **not** exhausted — no need to bundle forward into Marketplace
this time.

**AC1 and AC2 both required a product decision, gathered from the project owner directly during this story's
creation.** For AC1 (`useBookingSse` — fully built, connects to `GET /api/bookings/{id}/events`, handles
reconnection with backoff, but never called anywhere in the frontend, clearly meant for a booking detail page
that was never built): three options were presented — wire into the existing booking list rows, delete it as
dead code, or leave it alone until a real product driver appears. **Decided: wire into booking list rows.**
For AC2 (an availability calendar `GET` response has no way to tell the frontend its view is now stale before
a booking `POST` predictably gets rejected — the write path always re-validates freshly, so this is a UX gap,
not a correctness bug): two options were presented — leave as-is (matching this project's anti-speculative-
engineering convention) or build a staleness-detection mechanism. **Decided: build it.**

Investigating AC1 more deeply (grep for every call site of `bookingSseService.subscribe`, and reading
`BookingSseService.onStatusChanged`) surfaced that wiring SSE broadly across list rows would make an
**existing, already-filed ledger item worse, not better**: `BookingSseService` never proactively completes an
emitter once a booking reaches a terminal status — it just sits open until its 5-minute timeout. Multiplying
open connections across every visible row in `ParentBookingsPage.vue`/`CoachCommandCenterPage.vue` (which
both list bookings across all statuses, including old completed/cancelled ones) would turn a latent,
low-traffic leak into a live one. AC1 below fixes both the wiring and this pre-existing leak in the same pass,
since shipping one without the other would be irresponsible.

A `CoachCommandCenterPage.vue:267` hardcoded-English `getDayIndex` candidate, a `DisputeService` two-sided-
contest gap, and several architectural/speculative concurrency items (connection-pool sizing, lock-timeout
edge cases) were all found already `[CLOSED]`/`[DECIDED]`/`[SUPERSEDED]` by prior stories on direct
re-verification and are not re-litigated here — see AC3 for the ones whose stale tags needed formal closing
or correcting.

## Acceptance Criteria

### AC1 — Wire `useBookingSse` into booking list rows, and stop terminal-status bookings from holding a live SSE subscription open

**Current behavior, verified against live source:**

`src/frontend/src/stores/booking.store.js:42-99` exports `useBookingSse(bookingId)` — a fully working
composable (connects via `EventSource` to `GET /api/bookings/{id}/events`, exponential-backoff reconnect,
falls back to 2s polling after 3 failed retries, `onUnmounted` cleanup). `grep -rln "useBookingSse"
src/frontend/src` returns only `booking.store.js` itself — **it is never called anywhere.** No booking-detail
route/page exists (`grep -rn "bookings/:id\|BookingDetail" src/frontend/src/router` and `find
src/frontend/src/pages -iname "*BookingDetail*"` both return nothing) — it was clearly built for a detail
page that was never shipped.

`src/frontend/src/components/booking/BookingStateChip.vue` is used inline, per-row, inside a plain `v-for` in
exactly two live booking-management pages (not wrapped in its own row sub-component — each `<BookingStateChip
:status="..." />` usage inside a `v-for` already gets its own component instance, which is what makes wiring
`useBookingSse` directly inside `BookingStateChip.vue` itself lifecycle-safe per row, with no new wrapper
component needed):

- `src/frontend/src/pages/parent/ParentBookingsPage.vue:101` — `<q-item v-for="booking in
  bookingStore.parentBookings" :key="booking.id" ...><BookingStateChip :status="booking.status" /></q-item>`
- `src/frontend/src/pages/coach/CoachCommandCenterPage.vue:76` — `<div v-for="booking in
  (bookingsByDay[dayIndex - 1] ?? [])" :key="booking.bookingId" ...><BookingStateChip :status="booking.status"
  /></div>` — note this page's booking objects key off `booking.bookingId`, not `booking.id`.

Two other pages also render `BookingStateChip` (`ParentPlayerPortalPage.vue`'s summary widget,
`BookingRequestPage.vue`'s "already requested" rows) — **out of scope for this AC**, deliberately: they are
summary/secondary contexts, not the primary active booking-management views the project owner's decision was
scoped to. Revisit in a follow-up if live status becomes a priority there too.

**Resource-leak interaction, verified against live source
(`src/main/java/com/softropic/skillars/platform/booking/service/BookingSseService.java`):**

```java
public SseEmitter subscribe(UUID bookingId, String currentStatus) {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    emitters.computeIfAbsent(bookingId, id -> new CopyOnWriteArrayList<>()).add(emitter);
    ...
}

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onStatusChanged(BookingStatusChangedEvent event) {
    List<SseEmitter> list = emitters.get(event.bookingId());
    if (list == null || list.isEmpty()) return;
    for (SseEmitter emitter : list) {
        try {
            emitter.send(SseEmitter.event().name("status").data(event.newStatus()));
        } catch (IOException e) { ... removeEmitter(...) ... }
    }
}
```

`onStatusChanged` sends a booking's final terminal status but never calls `emitter.complete()` afterward — the
emitter just sits registered in the `emitters` map until `SSE_TIMEOUT_MS` (5 minutes) naturally fires. This is
exactly the still-open ledger item `- SSE endpoint accepts subscriptions for terminal-state bookings —
emitters accumulate for COMPLETED/CANCELLED/REFUNDED bookings; implement lifecycle-based subscription guard
in a resource-management pass [BookingEventResource.java:37]` (`## Deferred from: code review of
skillars-3-4-booking-state-machine-sse (2026-06-15)`). Wiring SSE across every row of two list pages that both
show terminal bookings (old completed/cancelled sessions, not just upcoming ones) directly multiplies this
gap's exposure — fix both in this AC, not just the wiring.

**Design constraint (important — read before implementing):** a browser `EventSource` has no concept of "the
server closed this on purpose" vs. "network failure" — closing the stream from the server side (even via a
clean `emitter.complete()`) makes the client's `onerror` fire and **auto-reconnect** by default, unless the
client itself calls `.close()`. So the fix must be client-driven on the "reached terminal" transition (the
frontend closes itself, which does *not* trigger reconnect), with a backend-side guard as defense-in-depth
for callers that haven't picked up this frontend fix (a stray direct API call, a stale cached bundle) — not
the backend trying to force-close an already-open stream.

**Fix — four files, all four must ship together:**

1. **`src/main/java/com/softropic/skillars/platform/booking/service/BookingStateMachine.java`** — add a
   public helper (this class already owns the `TRANSITIONS` map; a status absent as a key has zero outgoing
   transitions, i.e. is terminal):
   ```java
   public boolean isTerminal(BookingStatus status) {
       return !TRANSITIONS.containsKey(status);
   }
   ```
   Verify against the current `TRANSITIONS` map: `DECLINED`, `CANCELLED`, `CANCELLED_PARENT`,
   `CANCELLED_COACH`, `NO_SHOW_PLAYER`, `NO_SHOW_COACH`, `REFUNDED` are absent as keys (terminal).
   `COMPLETED` **is** a key (`DISPUTE` still transitions it) — it is *not* terminal by this definition,
   deliberately: a completed booking can still be disputed, so it must stay eligible for a live subscription.

2. **`src/main/java/com/softropic/skillars/platform/booking/service/BookingSseService.java`** — add a second,
   short-lived subscribe path for a booking that is *already* terminal at subscribe time, which never
   registers into the long-lived `emitters` map at all (there is nothing left to ever push to it):
   ```java
   private static final long TERMINAL_SUBSCRIBE_TIMEOUT_MS = 5 * 1000L;

   public SseEmitter subscribeTerminal(String currentStatus) {
       SseEmitter emitter = new SseEmitter(TERMINAL_SUBSCRIBE_TIMEOUT_MS);
       try {
           emitter.send(SseEmitter.event().name("status").data(currentStatus));
       } catch (IOException e) {
           log.warn("Failed to send status to SSE subscriber for an already-terminal booking", e);
       }
       emitter.complete();
       return emitter;
   }
   ```

3. **`src/main/java/com/softropic/skillars/platform/booking/api/BookingEventResource.java`** — inject
   `BookingStateMachine` (constructor injection via existing `@RequiredArgsConstructor` — just add the
   field) and branch in `subscribeToEvents`:
   ```java
   @GetMapping("/{id}/events")
   @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
   public ResponseEntity<SseEmitter> subscribeToEvents(@PathVariable UUID id) {
       Long actorUserId = currentUserId();
       Booking booking = bookingService.getBookingOrThrow(id);
       verifyIsParty(booking, actorUserId);

       BookingStatus currentStatus = BookingStatus.valueOf(booking.getStatus());
       SseEmitter emitter = bookingStateMachine.isTerminal(currentStatus)
           ? bookingSseService.subscribeTerminal(booking.getStatus())
           : bookingSseService.subscribe(id, booking.getStatus());
       return ResponseEntity.ok()
           .contentType(MediaType.TEXT_EVENT_STREAM)
           .body(emitter);
   }
   ```
   Add the `BookingStatus` import (`com.softropic.skillars.platform.booking.contract.BookingStatus`) — the
   `BookingStatus.valueOf(booking.getStatus())` conversion is this codebase's established idiom (see
   `BookingService.java:634`).

4. **`src/frontend/src/stores/booking.store.js`** — export a shared terminal-status set (avoid a second,
   independently-drifting copy in `BookingStateChip.vue` — matches this codebase's own
   `ACTIVE_SLOT_STATUSES`-sharing precedent) and self-close on reaching one, from both the SSE path and the
   polling fallback:
   ```js
   // IMPORTANT: keep in sync with BookingStateMachine.TRANSITIONS on the backend — a status absent as a
   // key there has no outgoing transitions, i.e. is terminal. See
   // src/main/java/com/softropic/skillars/platform/booking/service/BookingStateMachine.java. If a future
   // status is added to that enum with no transitions out of it, add it here too, or this composable will
   // keep subscribing to it forever.
   export const TERMINAL_BOOKING_STATUSES = new Set([
     'DECLINED', 'CANCELLED', 'CANCELLED_PARENT', 'CANCELLED_COACH',
     'NO_SHOW_PLAYER', 'NO_SHOW_COACH', 'REFUNDED',
   ])

   export function useBookingSse(bookingId) {
     const status = ref(null)
     const connectionState = ref('disconnected')
     let es = null
     let retryCount = 0
     let pollingInterval = null
     const delays = [1000, 2000, 4000, 8000, 16000, 30000]

     function connect() {
       es = new EventSource(`/api/bookings/${bookingId}/events`, { withCredentials: true })
       connectionState.value = 'reconnecting'
       es.onopen = () => {
         connectionState.value = 'connected'
       }
       es.addEventListener('status', (e) => {
         status.value = e.data
         retryCount = 0
         if (pollingInterval) {
           clearInterval(pollingInterval)
           pollingInterval = null
           connectionState.value = 'connected'
         }
         if (TERMINAL_BOOKING_STATUSES.has(e.data)) {
           es.close()
           connectionState.value = 'disconnected'
         }
       })
       es.onerror = () => {
         es.close()
         retryCount++
         if (retryCount >= 3 && !pollingInterval) {
           connectionState.value = 'polling'
           pollingInterval = setInterval(async () => {
             const r = await getBookingById(bookingId)
             status.value = r.status
             if (TERMINAL_BOOKING_STATUSES.has(r.status)) {
               // es is already closed/errored by this point (polling only starts after es.onerror), but
               // close it explicitly rather than relying on that — defensive, not load-bearing.
               es?.close()
               clearInterval(pollingInterval)
               pollingInterval = null
               connectionState.value = 'disconnected'
             }
           }, 2000)
         } else if (!pollingInterval) {
           connectionState.value = 'reconnecting'
           const delay = delays[Math.min(retryCount - 1, delays.length - 1)]
           setTimeout(connect, delay)
         }
       }
       es.addEventListener('heartbeat', () => {
         es.close()
         retryCount = 0
         clearInterval(pollingInterval)
         pollingInterval = null
         connect()
       })
     }

     function cleanup() {
       es?.close()
       clearInterval(pollingInterval)
       pollingInterval = null
       connectionState.value = 'disconnected'
     }

     connect()
     onUnmounted(cleanup)
     return { status, connectionState, cleanup }
   }
   ```
   Only the `status` listener and the polling-interval callback gained the terminal-close branch; nothing
   else in this function changes.

5. **`src/frontend/src/components/booking/BookingStateChip.vue`** — accept an optional `bookingId` prop;
   when provided and the *initial* status isn't already terminal, subscribe and prefer the live value:
   ```vue
   <script setup>
   import { computed } from 'vue'
   import { useI18n } from 'vue-i18n'
   import { useBookingSse, TERMINAL_BOOKING_STATUSES } from 'src/stores/booking.store'

   const props = defineProps({
     status: { type: String, required: true },
     bookingId: { type: String, default: null },
   })
   const { t } = useI18n()

   const sse = props.bookingId && !TERMINAL_BOOKING_STATUSES.has(props.status)
     ? useBookingSse(props.bookingId)
     : null

   const liveStatus = computed(() => sse ? (sse.status.value ?? props.status) : props.status)

   const statusMap = {
     REQUESTED: { key: 'booking.requests.statusRequested', cls: 'chip--warning' },
     ACCEPTED: { key: 'booking.requests.statusAccepted', cls: 'chip--primary' },
     PAYMENT_PENDING: { key: 'booking.requests.statusPaymentPending', cls: 'chip--warning' },
     CONFIRMED: { key: 'booking.requests.statusConfirmed', cls: 'chip--primary' },
     UPCOMING: { key: 'booking.requests.statusUpcoming', cls: 'chip--primary' },
     IN_PROGRESS: { key: 'booking.requests.statusInProgress', cls: 'chip--primary' },
     PAUSED: { key: 'booking.requests.statusPaused', cls: 'chip--warning' },
     COMPLETED_PENDING_CONFIRMATION: { key: 'booking.requests.statusCompletingPending', cls: 'chip--warning' },
     DECLINED: { key: 'booking.requests.statusDeclined', cls: 'chip--error' },
     COMPLETED: { key: 'booking.requests.statusCompleted', cls: 'chip--neutral' },
     CANCELLED: { key: 'booking.requests.statusCancelled', cls: 'chip--neutral' },
     CANCELLED_PARENT: { key: 'booking.requests.statusCancelledParent', cls: 'chip--neutral' },
     CANCELLED_COACH: { key: 'booking.requests.statusCancelledCoach', cls: 'chip--neutral' },
     NO_SHOW_PLAYER: { key: 'booking.requests.statusNoShowPlayer', cls: 'chip--error' },
     NO_SHOW_COACH: { key: 'booking.requests.statusNoShowCoach', cls: 'chip--error' },
     DISPUTED: { key: 'booking.requests.statusDisputed', cls: 'chip--neutral' },
     REFUND_PENDING: { key: 'booking.requests.statusRefundPending', cls: 'chip--warning' },
     REFUNDED: { key: 'booking.requests.statusRefunded', cls: 'chip--neutral' },
   }

   const label = computed(() => {
     const entry = statusMap[liveStatus.value]
     return entry ? t(entry.key) : liveStatus.value
   })

   const chipClass = computed(() => statusMap[liveStatus.value]?.cls ?? 'chip--neutral')
   </script>
   ```
   Template and `<style>` are unchanged. `statusMap` itself is unchanged (verified: `CANCELLED` is a live,
   reachable status — the `CANCEL_DUE_TO_PAUSE` event transitions `REQUESTED`/`ACCEPTED`/`CONFIRMED`/
   `UPCOMING` all to plain `BookingStatus.CANCELLED`, not `CANCELLED_PARENT`/`CANCELLED_COACH` — do **not**
   remove this entry; see AC3 item 12 for the stale ledger bullet that incorrectly called it dead).

6. **Caller updates** — pass the new prop at both wired call sites:
   - `ParentBookingsPage.vue:101`: `<BookingStateChip :status="booking.status" :booking-id="booking.id" />`
   - `CoachCommandCenterPage.vue:76`: `<BookingStateChip :status="booking.status" :booking-id="booking.bookingId" />`

7. **Ledger closure** — append to the exact bullet quoted above (`## Deferred from: code review of
   skillars-3-4-booking-state-machine-sse (2026-06-15)`, the `BookingEventResource.java:37` one):
   `` `[CLOSED by skillars-deferred-71 AC1: subscribeToEvents now branches on BookingStateMachine.isTerminal — an already-terminal booking gets a short-lived (5s) emitter via the new BookingSseService.subscribeTerminal, never registered in the long-lived emitters map. The frontend's useBookingSse also self-closes its EventSource on receiving a terminal status (via the client's own .close(), which does not trigger EventSource's auto-reconnect), so an emitter that transitions to terminal while genuinely subscribed is no longer left open for the full 5-minute timeout in practice either.]` ``

**Testing:**
- Backend: add unit tests to `BookingStateMachineTest.java` (or the nearest existing test file covering this
  class — confirm the name via a repo search, do not assume) for `isTerminal`: true for each of the seven
  terminal statuses, false for `COMPLETED` and at least one clearly-non-terminal status (e.g. `UPCOMING`).
  Add tests to whichever test file covers `BookingEventResource`/`BookingSseService` (check for an existing
  `BookingEventResourceIT` or similar; if none exists, add focused unit tests for `BookingSseService
  .subscribeTerminal` mirroring this class's existing test conventions) confirming a terminal-status
  subscribe does not add an entry to the live `emitters` map and completes the returned emitter.
- Frontend: no automated test infrastructure exists in this repo (standing gap — do not introduce one).
  Verify by direct code reading post-fix and `npx eslint` clean on every touched `.vue`/`.js` file.

---

### AC2 — Availability-staleness guard: tell the parent their view is out of date instead of a generic rejection

**Current behavior, verified against live source:**

`AvailabilityService.getAvailabilityCalendar` (`src/main/java/com/softropic/skillars/platform/booking/service/
AvailabilityService.java:53-222`) resolves `windows` (`CoachAvailabilityWindow` list) and `slotLength`
(`Duration`, via `sessionDurationResolver.resolve(coachId)`) to compute `computedSlots`, and returns them in
`CoachAvailabilityResponse` — but nothing ties that response to a later `POST`. `BookingService
.createBookingRequest` (`:213-224`) independently re-resolves both the same way and re-validates fully at
write time (duration-exact-match check, then `isSlotWithinAvailabilityWindow`) — **this already prevents any
incorrect booking from ever being created**; the gap is purely informational: if a coach edits their session
duration or a window between the parent's `GET` and their `POST`, the parent's screen keeps showing the
now-stale slot until they submit and get a generic `booking.invalidSessionDuration`/
`booking.slotOutsideAvailability` rejection, with no signal that their own view — not their click — was the
problem.

This is the still-open half of ledger item `- **Session duration / availability windows are resolved once and
not re-validated before persistence.** ... [CLOSED (partially) by skillars-deferred-69 AC7: ... The separate
GET-availability-calendar-vs-POST-booking staleness (AvailabilityService.getAvailabilityCalendar) is unrelated
and remains fully open.]` (`## Deferred from: code review of skillars-uat-2-session-duration-and-booking-
slot-integrity — Group A (2026-08-10)`).

**Fix — a signature string, not `@Version` columns.** The originally-filed suggestion (`@Version` on
`CoachAvailabilityWindow`/`CoachAvailabilityBlock`, echoed on `POST`) needs a migration on two entities across
two schemas and still would not by itself detect an *added* or *removed* window/block (a `@Version` bump only
fires on an `UPDATE` of an existing row). A deterministic content signature computed at request time from
exactly what `createBookingRequest` actually re-validates needs **no schema change at all** and correctly
captures adds/removes/edits alike. Scoped to `windows` + `slotLength` only — **not** `blocks**: verified
`createBookingRequest` never fetches/checks `CoachAvailabilityBlock` at write time at all (only the window
schedule and existing-booking overlap), so including blocks in the signature would flag "staleness" for a
change the write path doesn't even care about.

1. **`src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java`** — add
   `java.util.Comparator` to imports, add a package-private static method (same package as `BookingService`,
   so it's callable with no new dependency injection), and call it from `getAvailabilityCalendar`:
   ```java
   static String computeAvailabilitySignature(List<CoachAvailabilityWindow> windows, Duration slotLength) {
       StringBuilder sb = new StringBuilder();
       windows.stream()
           .sorted(Comparator.comparing(CoachAvailabilityWindow::getId))
           .forEach(w -> sb.append(w.getId()).append(':').append(w.getDayOfWeek()).append(':')
               .append(w.getStartTime()).append('-').append(w.getEndTime()).append(':')
               .append(w.getCanonicalTimezone()).append(';'));
       sb.append("duration=").append(slotLength.toMinutes());
       return sb.toString();
   }
   ```
   Change the existing return statement:
   ```java
   return new CoachAvailabilityResponse(windowResponses, blockResponses, computedSlots, coachTimezone,
       computeAvailabilitySignature(windows, slotLength));
   ```

2. **`src/main/java/com/softropic/skillars/platform/booking/contract/CoachAvailabilityResponse.java`** — add
   the field (it's a record — this is the only construction site, confirmed via
   `grep -rn "new CoachAvailabilityResponse("`):
   ```java
   public record CoachAvailabilityResponse(
       List<AvailabilityWindowResponse> windows,
       List<AvailabilityBlockResponse> blocks,
       List<AvailableSlotResponse> computedSlots,
       String canonicalTimezone,
       String availabilitySignature
   ) {}
   ```

3. **`src/main/java/com/softropic/skillars/platform/booking/contract/CreateBookingRequest.java`** — add an
   optional (nullable, no `@NotNull`) field so older/unrelated callers (batch, reschedule, direct API,
   already-in-flight requests from a not-yet-refreshed frontend) are unaffected:
   ```java
   public record CreateBookingRequest(
       @NotNull UUID coachId,
       @NotNull Long playerId,
       @NotNull @Future Instant requestedStartTime,
       @NotNull Instant requestedEndTime,
       @Size(max = 500) String notes,
       UUID sessionPackPurchaseId,
       String availabilitySignature
   ) { ... unchanged isEndAfterStart() body ... }
   ```

4. **`src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java`** — add one new code,
   following this enum's exact existing per-value pattern (both the value list and the `getErrorCode()`
   switch):
   ```java
   AVAILABILITY_CHANGED;
   ...
   case AVAILABILITY_CHANGED -> "booking.availabilityChanged";
   ```

5. **`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`** — in
   `createBookingRequest`, immediately after the existing `windows` fetch (`:222`) and before the existing
   `isSlotWithinAvailabilityWindow` call, insert the staleness check — placed here (after duration check,
   before window-fit check) so a genuinely stale view is reported specifically, not masked by whichever
   generic check happens to fire first:
   ```java
   List<CoachAvailabilityWindow> windows = coachAvailabilityWindowRepository.findByCoachId(req.coachId());
   if (req.availabilitySignature() != null) {
       String currentSignature = AvailabilityService.computeAvailabilitySignature(windows, requiredDuration);
       if (!currentSignature.equals(req.availabilitySignature())) {
           throw new OperationNotAllowedException(
               "Coach availability changed since this view was loaded — please refresh",
               Map.of("coach id", req.coachId()), BookingError.AVAILABILITY_CHANGED);
       }
   }
   if (!isSlotWithinAvailabilityWindow(req.requestedStartTime(), req.requestedEndTime(), windows, req.coachId())) {
   ```
   **Scope note:** `RescheduleService` and `BookingBatchService` also independently re-resolve
   windows/duration (`skillars-deferred-69` AC7 already added their own fresh re-validation at write time,
   per the ledger item this AC partially closes) — deliberately **not** wired into the signature check in
   this AC. Neither reschedule nor batch currently exposes a `GET`-then-`POST` calendar-view seam the way
   single-booking creation does (reschedule proposes against an already-known slot; batch builds from the
   same `getAvailabilityCalendar` response but across a whole basket, where "stale" would need a
   basket-level, not single-slot, story of its own). Left as a natural follow-up, not built speculatively.

6. **`src/frontend/src/stores/booking.store.js`** — capture and expose the signature in `loadAvailability`,
   and thread it into `submitBookingRequest`'s caller contract is unchanged (the caller already builds and
   passes the whole request object — see next item):
   ```js
   const availabilitySignature = ref(null)
   ```
   (declare alongside the other `useBookingStore` state refs, e.g. next to `coachTimezone`) and in
   `loadAvailability`:
   ```js
   async function loadAvailability(coachId, date) {
     loading.value = true
     error.value = null
     windows.value = []
     blocks.value = []
     computedSlots.value = []
     coachTimezone.value = null
     availabilitySignature.value = null
     try {
       const ws = date ?? currentMonday()
       weekStart.value = ws
       const res = await getCoachAvailability(coachId, ws)
       windows.value = res.windows ?? []
       blocks.value = res.blocks ?? []
       computedSlots.value = res.computedSlots ?? []
       coachTimezone.value = res.canonicalTimezone ?? null
       availabilitySignature.value = res.availabilitySignature ?? null
     } catch (e) {
       error.value = e
     } finally {
       loading.value = false
     }
   }
   ```
   Add `availabilitySignature` to this store's returned/exposed object (mirror how `coachTimezone` is already
   exposed — find that line in the `return { ... }` at the bottom of `useBookingStore` and add it alongside).

7. **`src/frontend/src/pages/parent/BookingRequestPage.vue`** — include the signature in the submit payload
   (`submit()`, `:481-488`):
   ```js
   await bookingStore.submitBookingRequest({
     coachId,
     playerId: playerId.value,
     requestedStartTime: selectedSlot.value.startDatetime,
     requestedEndTime: selectedSlot.value.endDatetime,
     notes: notes.value || null,
     sessionPackPurchaseId: selectedPackId.value,
     availabilitySignature: bookingStore.availabilitySignature,
   })
   ```
   and add a new error branch in the same `catch` block, following the exact existing per-code branch
   pattern (place it next to the `booking.slotOutsideAvailability`/`booking.invalidSessionDuration` branches,
   `:499-518`), re-fetching availability so the parent's screen is current after the toast — mirroring
   `submitBatchRequest`'s existing `booking.batchSizeExceeded` refetch-then-toast shape (`:552-583`):
   ```js
   } else if (errorKey === 'booking.availabilityChanged') {
     $q.notify({ type: 'negative', message: t('booking.errors.availabilityChanged') })
     await bookingStore.loadAvailability(coachId, bookingStore.weekStart)
   }
   ```

8. **i18n** — add the new key to all three locale files, alongside the existing `sessionCrossesMidnight` key
   in the same `booking.errors` block (`src/frontend/src/i18n/en-US/index.js:940`,
   `src/frontend/src/i18n/de-DE/index.js:483`, `src/frontend/src/i18n/fr-FR/index.js:1222` — confirm exact
   line via search, these shift):
   - en-US: `availabilityChanged: 'This coach's availability changed since you loaded this page. Please pick a new time.',`
   - de-DE: `availabilityChanged: 'Die Verfügbarkeit dieses Coaches hat sich seit dem Laden dieser Seite geändert. Bitte wählen Sie eine neue Zeit.',`
   - fr-FR: `availabilityChanged: "La disponibilité de ce coach a changé depuis le chargement de cette page. Veuillez choisir un nouveau créneau.",`

9. **Ledger closure** — append to the exact bullet quoted above (`## Deferred from: code review of
   skillars-uat-2-session-duration-and-booking-slot-integrity — Group A (2026-08-10)`, the one already
   carrying the `[CLOSED (partially) by skillars-deferred-69 AC7: ...]` tag):
   `` `[CLOSED (further) by skillars-deferred-71 AC2, for single-booking creation only: CoachAvailabilityResponse now carries an availabilitySignature (a deterministic string built from the current windows + resolved session duration, no schema change), which CreateBookingRequest can optionally echo back; BookingService.createBookingRequest compares it against a freshly-recomputed signature before the existing window/duration checks and throws a dedicated BookingError.AVAILABILITY_CHANGED (distinct from the generic rejection) on mismatch, and the frontend re-fetches availability on that specific error. RescheduleService and BookingBatchService remain unwired — see this AC's own scope note for why — so the GET-vs-POST staleness gap for those two paths is not part of this closure.]` ``

**Testing:**
- Backend: add unit tests to whichever test file covers `AvailabilityService` (check for an existing
  `AvailabilityServiceTest.java` — confirm before assuming) for `computeAvailabilitySignature`: same
  windows+duration produce identical signatures across calls; a changed window (different start/end time),
  an added window, a removed window, and a changed duration must each produce a different signature from the
  baseline.
- Add tests to `BookingServiceTest.java` for `createBookingRequest`: a request with a `null`
  `availabilitySignature` still succeeds unchanged (no regression for callers not yet sending it); a request
  with a signature matching current state succeeds; a request with a stale (mismatched) signature throws
  `OperationNotAllowedException` carrying `BookingError.AVAILABILITY_CHANGED`, and does so *before* reaching
  the window-fit check (verify via a case where the slot would otherwise have been valid).
- Consider one `BookingRequestResourceIT`/similar integration test exercising the full stale-signature →
  409/403 → error-code path if a suitable existing IT class covers `POST /api/bookings/requests`'s error
  paths already (check first; do not build new IT infrastructure solely for this).
- Frontend: `npx eslint` clean on every touched file; no automated regression test (standing repo-wide gap).

---

### AC3 — Ledger hygiene: close fourteen stale/already-fixed/incorrect Booking-module items

Every item below was individually verified against live current source during this story's creation (not
assumed from the ledger's own text — several of the ledger's own "revisit when X is built" conditions are now
met, and one item's premise turned out to be flatly wrong on re-reading current code). Apply these
`deferred-work.md` edits (locate each by its quoted text — line numbers shift, do not trust them without
re-grepping first):

1. **`verifyIsParty` admin bypass — already fixed.** Bullet (`## Deferred from: code review of
   skillars-3-4-booking-state-machine-sse (2026-06-15)`): `` `verifyIsParty` has no admin bypass path — no
   admin role exists yet; revisit when admin management stories are implemented ``. Verified:
   `BookingEventResource.verifyIsParty` (`:63-66`) already opens with `if (securityUtil.isAdmin()) { return; }`.
   Append: `` `[CLOSED by skillars-deferred-71 (verified already fixed): BookingEventResource.verifyIsParty already opens with an isAdmin() bypass. Admin-role infrastructure has existed for some time; this bullet's "no admin role exists yet" premise is long stale.]` ``

2. **`PAYMENT_FAILED` refund-eligibility gap — method no longer exists.** Bullet (`## Deferred from: code
   review of skillars-3-4-booking-state-machine-sse (2026-06-15)`): `` `PAYMENT_FAILED` sets no
   `refundEligibility` — `null` is intentional; Epic 7 handles payment-failure refund logic independently
   [BookingService.java:applyRefundLogic] ``. Verified: `grep -n "applyRefundLogic"
   src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` returns nothing — the
   method was removed. `refund_eligibility`/`refund_amount` columns on `booking.bookings` were dropped
   entirely (`V97__drop_booking_refund_eligibility_and_amount.sql`). Append: `` `[CLOSED by
   skillars-deferred-71 (verified stale): applyRefundLogic no longer exists — refund_eligibility/refund_amount
   were dropped from booking.bookings by V97. This concept was fully removed from the domain by a later story
   rather than fixed.]` ``

3. **Authority id 9502 test leak — already audited, needs formal close.** Bullet (`## Deferred from: code
   review of skillars-3-3-booking-request-approval-workflow Group E (2026-06-15)`), already carrying `` `[AUDIT
   2026-08-13: not reproducible — main.authority.name is UNIQUE and id=9500 already holds name='ROLE_PARENT'
   ... nothing leaks. Verified during skillars-deferred-21 story creation.]` ``. Append (this file's own "How
   to read this file" rule distinguishes `[AUDIT ...]` re-verified-but-still-listed items from
   `[CLOSED ...]`/deleted ones — this one has been re-verified twice now with the same finding and should
   graduate to closed): `` `[CLOSED by skillars-deferred-71: the 2026-08-13 AUDIT already established this is
   not reproducible; formally closing rather than leaving it as a perpetually-reopened AUDIT note.]` ``

4. **Booking-overlap partial index — already closed, un-pruned.** Bullet (`## Deferred from: code review of
   skillars-3-3-booking-request-approval-workflow Group B (2026-06-15)`), already carrying `` `[CLOSED —
   decided, not open, found during skillars-deferred-65 story creation (2026-08-25): ...]` ``. This bullet
   already meets this file's own stated convention for outright deletion (see the file's `## Last audit:
   2026-08-24` pruning-pass section) — it was filed after that one-time pruning script ran, so it was never
   swept. **Delete this bullet entirely** (not just re-tag), matching the established convention.

5. **Old-form midnight-crossing bullet — superseded by the exact fix.** Bullet (same section as #4):
   `` Midnight-crossing sessions fail/pass incorrectly in availability window check because
   endZdt.toLocalTime() wraps past midnight; add explicit day-boundary guard when requestedEnd < requestedStart
   (in LocalTime) [BookingService.java:228-232] ``. This describes the identical defect
   `skillars-deferred-69` AC1-AC2 already fixed (see the separately-filed, already-`[CLOSED by
   skillars-deferred-69 AC1-AC2: ...]`-tagged bullet under `## Deferred from: code review of
   skillars-deferred-68-...`). Append: `` `[CLOSED by cross-reference (skillars-deferred-71): this is the same
   midnight-crossing defect independently fixed by skillars-deferred-69 AC1-AC2 — see that story's own
   BookingService.isSlotWithinAvailabilityWindow bullet elsewhere in this file for the fix detail. Duplicate
   filing, not a separate remaining gap.]` ``

6. **DST 1h shift concern — verified stale.** Bullet (same section as #4/#5): `` DST transition can shift
   booking time by 1h relative to window boundary; acceptable for current scope; revisit when timezone
   management (Story 3.5) is implemented [BookingService.java:isSlotWithinAvailabilityWindow] ``. Story 3.5 is
   long since implemented; re-read the current method (`:889-905` and following): every window boundary is
   computed via `date.atTime(window.getStartTime()).atZone(windowZoneId).toInstant()` — a fresh, correct
   per-date `ZonedDateTime` conversion using the JDK's own IANA tz-database DST rules, not a cached or
   hardcoded offset. `AvailabilityService.getAvailabilityCalendar` even explicitly logs when a DST transition
   shortens or inverts a window (`:170-186`) — DST is actively detected and handled today, not silently wrong.
   Append: `` `[CLOSED by skillars-deferred-71 (verified stale): current isSlotWithinAvailabilityWindow/
   getAvailabilityCalendar derive every window boundary via a fresh per-date ZonedDateTime conversion using
   real IANA tz rules, correctly handling DST by construction — this predates and is unrelated to Story 3.5;
   the "revisit when Story 3.5 ships" condition is long since met and the concern does not reproduce.]` ``

7. **Day-of-week ISO-format "verify" task — verified correct.** Bullet (`## Deferred from: code review of
   skillars-3-3-booking-request-approval-workflow Group B (2026-06-15)`, same section as #4/#5/#6):
   `` `w.getDayOfWeek()` vs JS 0-based day format — verify that the availability-windows frontend sends ISO
   1-7 (not JS 0-6); pre-existing from Story 3.1 [BookingService.java:230, CreateWindowRequest.java] ``.
   Verified:
   `AvailabilityManagerPage.vue`'s day-selection helper (`onAddWindowForDay`, `:250-253`) sets
   `form.value.dayOfWeek = day.isoDay` — an already-ISO-1-7 value from the page's own week-grid day objects,
   not a raw JS `Date.getDay()` (0-6) result. Append: `` `[CLOSED by skillars-deferred-71 (verified correct):
   AvailabilityManagerPage.vue's day-selection form already uses an ISO-1-7 isoDay value, not raw JS
   Date.getDay(). This was a "verify" task, not a known bug — verification confirms the frontend has always
   sent the correct format.]` ``

8. **`requestedEndTime` minimum duration — already enforced.** Bullet (`## Deferred from: code review of
   skillars-3-3-booking-request-approval-workflow Group A (2026-06-15)`): `` `requestedEndTime` minimum
   duration not validated — 1-second bookings accepted; minimum session length not in scope for Story 3.3; add
   a `@PositiveDuration(min=15m)` or service-level check in a future session-constraints story
   [CreateBookingRequest.java:16] ``. Verified: `BookingService.createBookingRequest` (`:213-219`) requires
   `requestedDuration.equals(requiredDuration)` — an exact match against the coach's resolved session
   duration (15-240 minutes per `chk_coach_pricing_session_duration`), added by the `skillars-uat-2` story.
   Far stricter than the originally-suggested 15-minute floor. Append: `` `[CLOSED by skillars-deferred-71
   (verified already fixed): createBookingRequest requires requestedDuration to exactly equal the coach's
   resolved session duration (skillars-uat-2's SessionDurationResolver-based check), which is strictly
   stronger than the originally-suggested minimum-duration floor. A 1-second booking has been impossible for
   some time.]` ``

9. **`OWN_BLOCKING_STATUSES` duplication — already fixed, formalize close.** Bullet (`## Deferred from: code
   review of skillars-uat-2-session-duration-and-booking-slot-integrity — Group C (2026-08-11)`), already
   tagged `` `[PICKED UP by skillars-deferred-47 AC1, AC2]` ``. Verified: `BookingRequestPage.vue:356`'s
   comment confirms the frontend now fetches `ACTIVE_SLOT_STATUSES` from `GET /api/bookings/requests/config`
   rather than hardcoding a duplicate list. Append: `` `[CLOSED by skillars-deferred-71 (verified complete):
   BookingRequestPage.vue now fetches the active-slot-status set from the backend config endpoint rather than
   duplicating it — skillars-deferred-47's pickup is confirmed shipped. Graduating from PICKED UP to CLOSED.]` ``

10. **Superseded lock-timeout stub #1 — target already closed.** Bullet (`## Deferred from: code review of
    skillars-uat-3-payment-capture-integrity-and-backup-retention (2026-08-11)`, first occurrence):
    `` `BookingService.cancelBookingAsParent`'s locked read races a settle-side write with no lock-timeout
    hint. ... `[SUPERSEDED by the more precise diagnosis at ## Deferred from:
    skillars-deferred-23-flaky-perf-test-dead-code-and-ops-hygiene-fixes (2026-08-14): ... Not a separate
    item. — noted by skillars-deferred-33 story creation, 2026-08-18]` ``. Verified: the target section (`##
    Deferred from: code review of skillars-deferred-23-...`) no longer contains any lock-timeout content — the
    underlying `OptimisticLockingFailureException`/lock-conflict handling gap this pointed to was fully closed
    by `skillars-deferred-66`/`-67`/`-68` (dedicated `BookingError.CONCURRENT_MODIFICATION` code, applied
    uniformly across all affected `BookingService`/`BookingCompletionService`/`RescheduleService`/
    `BookingBatchService` sites — see those stories' own already-`[CLOSED by skillars-deferred-67/68 ...]`-
    tagged bullets elsewhere in this file). This stub now points at a target whose own root cause is resolved.
    **Delete this bullet entirely** — it is a pointer with nothing left to point at.

11. **Superseded lock-timeout stub #2 — same as #10.** Bullet (`## Deferred from: code review of
    skillars-uat-3-payment-capture-integrity-and-backup-retention (2026-08-11)`, second occurrence, "D12"):
    the near-identical restatement of #10. Same verification, same resolution. **Delete this bullet entirely.**

12. **Dead `CANCELLED` chip entry — premise is incorrect.** Bullet (`## Deferred from: code review of
    skillars-3-4-booking-state-machine-sse (2026-06-15)`): `` Dead `CANCELLED` entry in
    `BookingStateChip.statusMap` — harmless graceful-degradation fallback; clean up after data migration is
    confirmed complete [BookingStateChip.vue] ``. Verified against `BookingStateMachine.java`: plain
    `BookingStatus.CANCELLED` (not `CANCELLED_PARENT`/`CANCELLED_COACH`) is the real, live target of every
    `CANCEL_DUE_TO_PAUSE` transition (from `REQUESTED`, `ACCEPTED`, `CONFIRMED`, and `UPCOMING` — the
    session-pack-pause cancellation path). This entry is not dead code at all. Append: `` `[CLOSED by
    skillars-deferred-71 (verified incorrect premise): BookingStatus.CANCELLED is the live, reachable target
    of every CANCEL_DUE_TO_PAUSE transition (pack-pause cancellations) — it is not a dead legacy entry. Do not
    remove this statusMap entry.]` ``

13. AC1's own ledger closure — see AC1 step 7 above (the SSE resource-leak bullet).

14. AC2's own ledger closure — see AC2 step 9 above (the GET-vs-POST staleness bullet).

**Testing:** none — this AC is markdown-only ledger editing, no code changes, no test impact. Items 13/14 are
listed here for completeness of the "fourteen items" count but their actual edit instructions live in AC1/AC2
above (they're closed *by* those ACs shipping, not as a separate editing pass) — do not duplicate the edit,
just confirm both landed once AC1 and AC2 are done.

## Tasks / Subtasks

- [x] AC1: Add `BookingStateMachine.isTerminal`. Add `BookingSseService.subscribeTerminal` (short 5s timeout,
      never registered in the `emitters` map). Branch `BookingEventResource.subscribeToEvents` on
      `isTerminal`. Export `TERMINAL_BOOKING_STATUSES` from `booking.store.js`; make `useBookingSse` self-close
      (both the SSE `status` listener and the polling fallback) on reaching one. Add optional `bookingId` prop
      to `BookingStateChip.vue`, wiring `useBookingSse` when provided and the initial status is non-terminal.
      Pass `:booking-id` from `ParentBookingsPage.vue` (`booking.id`) and `CoachCommandCenterPage.vue`
      (`booking.bookingId`). Append the AC1 ledger-closure tag to `deferred-work.md`. Add
      `BookingStateMachine`/`BookingSseService` tests per AC1's Testing section; `npx eslint` clean on every
      touched frontend file.
- [x] AC2: Add `AvailabilityService.computeAvailabilitySignature` (windows + slotLength, no `blocks`, no
      schema change). Add `availabilitySignature` to `CoachAvailabilityResponse` and `CreateBookingRequest`.
      Add `BookingError.AVAILABILITY_CHANGED`. Insert the staleness check into
      `BookingService.createBookingRequest` immediately after the `windows` fetch, before the window-fit
      check. Thread `availabilitySignature` through `booking.store.js` (`loadAvailability` capture + expose)
      and `BookingRequestPage.vue` (submit payload + new error branch with refetch). Add the new i18n key to
      all three locale files. Append the AC2 ledger-closure tag to `deferred-work.md`. Add tests per AC2's
      Testing section; `npx eslint` clean on every touched frontend file.
- [x] AC3: Apply all fourteen `deferred-work.md` edits specified above (twelve standalone edits in this AC's
      own list, plus confirming AC1/AC2's own two closures landed).
- [x] Run the full targeted test sweep for every touched test class (backend) plus `npx eslint` on every
      touched frontend file; confirm no regressions. Do not run `mvn verify` locally — GitHub CI is the sole
      full-verification gate (`docs/validation-strategy.md`).

### Review Findings

**Parallel adversarial review completed 2026-08-26** — 3 layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor); 28 raw findings → 19 unique after deduplication.

**Decision-Needed:** 0

**Patches** (fixable code issues):
- [x] [Review][Patch] Missing `es?.close()` in polling terminal detection [`src/frontend/src/stores/booking.store.js:504-512`] — Verified against live source rather than blindly re-applied: the cited line range (504-512) does not correspond to `useBookingSse` in the current file — those lines are `handleAcceptRescheduleAsParent`/`handleDeclineRescheduleAsParent`, unrelated reschedule handlers. The actual polling terminal-detection branch (`booking.store.js:84-95`) already calls `es?.close()` at line 90, exactly as AC1's own spec (and its accompanying comment explaining why) mandated — this was implemented correctly from the start, not missed. Stale/misattributed line citation from the review tooling; no code change needed.
- [x] [Review][Patch] TERMINAL_BOOKING_STATUSES frontend/backend sync verification [`src/frontend/src/stores/booking.store.js:482-485`] — Implemented as recommended: added `BookingStateMachineTest.isTerminal_fullTerminalSet_matchesFrontendTerminalBookingStatuses`, which computes the full terminal-status set from `BookingStateMachine.isTerminal` across every `BookingStatus` value and asserts it equals a hardcoded mirror of the frontend's `TERMINAL_BOOKING_STATUSES` Set. A future status added to `BookingStateMachine.TRANSITIONS` without updating both sides now fails this test loudly instead of drifting silently. `mvn -o test -Dtest=BookingStateMachineTest` 64/64 green post-patch.

**Deferred** (pre-existing issues, not caused by this change):
- [x] [Review][Defer] Inconsistent property naming between endpoints (`id` vs `bookingId`) — ParentBookingsPage.vue uses `booking.id` from parent endpoint; CoachCommandCenterPage.vue uses `booking.bookingId` from coach endpoint. Different response objects. Story correctly uses right property at each site. Pre-existing API design, not introduced by this change.
- [x] [Review][Defer] EventSource heartbeat + polling interaction — Heartbeat listener already exists in useBookingSse (lines 102-108). This story does not modify heartbeat interaction. Pre-existing pattern.
- [x] [Review][Defer] Signature ignores per-slot adjustment logic — Current code correctly signatures windows + duration. Per-slot session-duration overrides do not exist yet. If added, signature model evolves then. Not blocking.

**Dismissed as noise or acceptable by design:** 16 findings
- SSE subscription cleanup (Vue framework handles correctly)
- Signature versioning (deliberate design choice, no schema changes)
- Race between signature and slot check (expected layered validation, both checks pass in tests)
- availabilitySignature null/missing (backward compatible, optional field, tested)
- subscribeTerminal reconnection risk (acceptable defense-in-depth, rare edge case)
- UUID.compareTo() stability (stable Java contract)
- Signature scope exclusion from batch/reschedule (deliberate per AC2 step 5)
- Exception vs i18n message (not user-facing, internal logging only)
- availabilitySignature format validation (server-side equality check sufficient)
- Polling interval unmount (Vue framework no-op on unmounted component)
- i18n consistency (caught at build time by Vite)
- Record positional constructor (acceptable Java record pattern, all call sites updated)
- Signature timezone ordering (mitigated by UUID-based sort)
- Signature collision risk (no collision possible with current types)
- Concurrent booking slot contention (signature checks config staleness, not capacity)

## Dev Notes

- This story stays entirely within Booking/Availability/Reschedule — re-verification against live source
  found it was *not* exhausted (contrary to `skillars-deferred-69`/`-70`'s own characterization, which was
  based on what had already been closed, not a fresh re-check of every remaining bullet). No need to bundle
  into Marketplace this time.
- AC1 and AC2 are the two substantive feature additions in this story, both product-decided by the project
  owner during creation (see "Why this story exists"). AC3 is ledger hygiene, unusually large (14 items) but
  every single one was individually re-verified against live source in this story's own creation, not
  assumed from old ledger text.
- AC1's backend and frontend halves are not independently useful — ship both together. The `EventSource`
  auto-reconnect behavior (any stream close, even a clean one, triggers client-side retry unless the client
  itself calls `.close()`) is the reason the terminal-status guard must be frontend-driven, with the backend
  guard only as defense-in-depth. Do not "simplify" this by having the backend force-close active
  subscriptions on transition to terminal — re-read AC1's design-constraint paragraph before deviating.
- AC2 deliberately does not touch `RescheduleService`/`BookingBatchService` — see AC2 step 5's scope note.
  Do not expand scope to "make it consistent everywhere" without re-confirming with the project owner first;
  batch's own staleness story is a basket-level problem, not a single-slot one.
- Frontend: this repo has no automated frontend test infrastructure (standing, repeatedly-documented gap) —
  do not introduce one as part of this story; `npx eslint` plus direct code reading is the established bar.
- Backend: follow `docs/validation-strategy.md` — targeted `mvn -o test -Dtest=X` runs only; never run
  `mvn verify` locally; GitHub CI (triggered on PR) is the sole full-verification gate.
- No new database migrations in this story (AC2's signature is computed, not persisted — deliberately, see
  AC2's "Fix" preamble for why `@Version` columns were rejected in favor of this).

### Project Structure Notes

- `src/main/java/com/softropic/skillars/platform/booking/service/BookingStateMachine.java` — AC1.
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingSseService.java` — AC1.
- `src/main/java/com/softropic/skillars/platform/booking/api/BookingEventResource.java` — AC1.
- `src/frontend/src/stores/booking.store.js` — AC1 (`useBookingSse`/`TERMINAL_BOOKING_STATUSES`), AC2
  (`availabilitySignature` state + `loadAvailability`).
- `src/frontend/src/components/booking/BookingStateChip.vue` — AC1.
- `src/frontend/src/pages/parent/ParentBookingsPage.vue` — AC1 (caller update only).
- `src/frontend/src/pages/coach/CoachCommandCenterPage.vue` — AC1 (caller update only).
- `src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java` — AC2.
- `src/main/java/com/softropic/skillars/platform/booking/contract/CoachAvailabilityResponse.java` — AC2.
- `src/main/java/com/softropic/skillars/platform/booking/contract/CreateBookingRequest.java` — AC2.
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java` — AC2.
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` — AC2.
- `src/frontend/src/pages/parent/BookingRequestPage.vue` — AC2 (submit payload + error branch only).
- `src/frontend/src/i18n/en-US/index.js`, `de-DE/index.js`, `fr-FR/index.js` — AC2.
- `_bmad-output/implementation-artifacts/deferred-work.md` — AC1 (1 closure), AC2 (1 closure), AC3 (12
  closures/deletions).
- Test files: `BookingStateMachineTest.java` (or nearest equivalent — confirm name), whichever file covers
  `BookingEventResource`/`BookingSseService` (AC1); `AvailabilityServiceTest.java` (confirm it exists),
  `BookingServiceTest.java` (existing) (AC2).

### References

- `src/main/java/com/softropic/skillars/platform/booking/service/HomeworkAssignmentService.java` — not
  directly relevant to this story, no listener-idiom mirroring needed here (AC1/AC2 are request/response and
  SSE-lifecycle work, not new domain-event listeners).
- `_bmad-output/implementation-artifacts/skillars-deferred-69-...md`,
  `skillars-deferred-70-...md` — immediately preceding stories in this module; both characterized
  Booking/Availability/Reschedule as thin, a characterization this story's own deeper re-verification
  corrected (see "Why this story exists").
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingStateMachine.java` — source of
  truth for terminal-status determination (AC1); do not hardcode a separate terminal-status list in Java
  anywhere — `isTerminal` is the one place.

## Dev Agent Record

### Agent Model Used

claude-sonnet-5 (Claude Code)

### Debug Log References

None — no failures encountered requiring debug-log capture. All targeted test runs passed on first
execution after each AC's implementation.

### Completion Notes List

- AC1 implemented exactly per spec: `BookingStateMachine.isTerminal` (absent-as-key = terminal),
  `BookingSseService.subscribeTerminal` (5s timeout, never registered in `emitters`),
  `BookingEventResource.subscribeToEvents` branching on `isTerminal`, `TERMINAL_BOOKING_STATUSES`
  exported from `booking.store.js` with `useBookingSse` self-closing on both the SSE `status` listener
  and the polling fallback, `BookingStateChip.vue`'s new optional `bookingId` prop wiring
  `useBookingSse` when provided and non-terminal, and both caller sites
  (`ParentBookingsPage.vue`/`CoachCommandCenterPage.vue`) passing their respective id fields. New
  `BookingStateMachineTest` (`isTerminal`) and `BookingSseServiceTest` (new file — no prior test
  covered `BookingSseService`/`BookingEventResource`) cases added per the Testing section, using
  `ReflectionTestUtils` to inspect the private `emitters` map and `ResponseBodyEmitter`'s private
  `complete` field since `SseEmitter` exposes no public "is this closed" accessor. Ledger closure tag
  appended to the exact bullet cited.
- AC2 implemented exactly per spec: `AvailabilityService.computeAvailabilitySignature` (windows +
  slotLength only, no `blocks`), `CoachAvailabilityResponse`/`CreateBookingRequest` both gained the new
  field, `BookingError.AVAILABILITY_CHANGED` added, the staleness check inserted into
  `BookingService.createBookingRequest` immediately after the `windows` fetch and before the
  window-fit check. `booking.store.js`'s `loadAvailability` now captures and exposes
  `availabilitySignature`; `BookingRequestPage.vue`'s `submit()` sends it and gained the new
  `booking.availabilityChanged` error branch (re-fetches availability, mirroring
  `submitBatchRequest`'s refetch-then-toast shape). i18n key added to all three locale files
  (en-US/de-DE/fr-FR), matching each file's existing apostrophe-quoting convention. New
  `computeAvailabilitySignature` tests added to `AvailabilityServiceTest.java` (same-inputs-identical,
  changed-window-time/added-window/removed-window/changed-duration-all-different) and three new
  `createBookingRequest` tests added to `BookingServiceTest.java` (null signature unchanged, matching
  signature succeeds, stale signature throws `AVAILABILITY_CHANGED` before the window-fit/lock
  machinery — proven with a window that *does* cover the requested slot, so the failure is attributable
  to the staleness check alone). Ledger closure tag appended to the exact bullet cited.
- `CreateBookingRequest` gaining a 7th record component required a mechanical (behavior-preserving)
  fix to every existing positional-constructor call site across five test files
  (`ExpiredPackBookingValidationTest`, `PaymentPendingSweeperIT`, `BookingServiceConcurrencyIT`,
  `BookingServiceTest` — six call sites plus one helper) — each now passes a trailing `null` for the
  new `availabilitySignature` parameter, preserving prior behavior exactly (a `null` signature skips
  the staleness check entirely, per AC2's own backward-compatibility design). Not spec'd explicitly in
  the story text but required for the module to compile; confirmed via `mvn test-compile` before
  proceeding, then re-ran every touched test class to confirm no behavioral regression.
- AC3: applied all twelve standalone `deferred-work.md` edits plus confirmed AC1/AC2's own two
  closures landed (fourteen total, matching the story's own count). Three bullets were **deleted
  entirely** per their own instructions rather than tagged (the booking-overlap partial-index item,
  already closed and un-pruned; both superseded lock-timeout stubs, verified their target section
  — the `skillars-deferred-23` review — no longer contains any lock-timeout content). The other nine
  standalone items were appended with `[CLOSED ...]` tags carrying a verification note each, all
  copied verbatim from the story's own pre-verified closure text. No source-code changes in this AC —
  markdown-only, as the story's own Testing note states.
- `docs/validation-strategy.md` followed throughout: only targeted `mvn -o test -Dtest=X` runs plus
  `npx eslint` on touched frontend files — `mvn verify` was not run locally at any point. 165 targeted
  backend tests green across all seven touched/added test classes (including the two Testcontainers
  ITs, `BookingServiceConcurrencyIT` and `PaymentPendingSweeperIT`, both requiring and finding Docker
  available); `npx eslint` clean on all eight touched frontend files.
- ✅ Resolved review finding [Patch]: the "TERMINAL_BOOKING_STATUSES frontend/backend sync
  verification" recommendation — added `BookingStateMachineTest.isTerminal_fullTerminalSet_
  matchesFrontendTerminalBookingStatuses`, computing the full terminal set from `isTerminal` and
  asserting it equals a hardcoded mirror of the frontend's `TERMINAL_BOOKING_STATUSES`.
- ✅ Verified review finding [Patch]: the "missing `es?.close()` in polling terminal detection" finding
  cited line numbers (`booking.store.js:504-512`) that do not correspond to `useBookingSse` in the
  current file — the actual polling terminal branch (`:84-95`) already calls `es?.close()` at line 90,
  matching AC1's own spec. No code change was needed; the cited defect does not exist in this diff.

### File List

**Backend — production code:**
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingStateMachine.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingSseService.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/BookingEventResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/CoachAvailabilityResponse.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/CreateBookingRequest.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`

**Backend — tests:**
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingStateMachineTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingSseServiceTest.java` (new)
- `src/test/java/com/softropic/skillars/platform/booking/service/AvailabilityServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java`
  (mechanical: trailing `null` arg for the new `CreateBookingRequest` component)
- `src/test/java/com/softropic/skillars/platform/payment/service/ExpiredPackBookingValidationTest.java`
  (mechanical, same reason)
- `src/test/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeperIT.java`
  (mechanical, same reason)

**Frontend:**
- `src/frontend/src/stores/booking.store.js`
- `src/frontend/src/components/booking/BookingStateChip.vue`
- `src/frontend/src/pages/parent/ParentBookingsPage.vue`
- `src/frontend/src/pages/coach/CoachCommandCenterPage.vue`
- `src/frontend/src/pages/parent/BookingRequestPage.vue`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/de-DE/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`

**Docs / ledger:**
- `_bmad-output/implementation-artifacts/deferred-work.md`

## Change Log

- 2026-08-26: Story created via story-creation process. Re-mined `deferred-work.md` for
  Booking/Availability/Reschedule (top module priority per project owner's standing instruction), verifying
  every remaining open bullet against live current source rather than trusting the ledger's own text —
  found the module was *not* exhausted despite `skillars-deferred-69`/`-70` both characterizing it as thin.
  Two items required product decisions, gathered interactively from the project owner during this story's
  creation: (1) `useBookingSse` — a fully-built but never-called SSE composable — decided to wire into
  booking list rows rather than delete or leave alone, scoped to AC1; investigating that wiring surfaced an
  existing ledger item (SSE emitters never proactively completing on reaching a terminal status) that wiring
  broadly would make worse, so AC1 fixes both together. (2) Availability GET-vs-POST staleness (a parent's
  calendar view can go stale with no signal before a predictable rejection) — decided to build a
  staleness-detection guard rather than leave as-is, scoped to AC2, designed as a computed signature string
  rather than the originally-filed `@Version`-column suggestion (no schema change needed, correctly captures
  window adds/removes, not just edits). AC3 closes fourteen ledger items found stale, already-fixed-elsewhere,
  or (in one case) factually incorrect on re-verification against live source — twelve standalone plus AC1's
  and AC2's own closures.
- 2026-08-26: story-review complete (`story-review.md`), status remains ready-for-dev — high-confidence
  audit, no logic errors, false assumptions, or missed flows found across AC1/AC2/AC3; all design trade-offs
  (signature string vs. `@Version` columns, client-driven vs. server-forced SSE close, AC2's batch/reschedule
  exclusion) confirmed justified. Two non-blocking hygiene recommendations incorporated directly into AC1's
  spec: (1) the polling fallback's terminal-status branch now also calls `es?.close()` defensively (the
  `EventSource` is already in an errored state by the time polling starts, since polling only begins after
  `es.onerror`, but explicit is better than relying on that); (2) `TERMINAL_BOOKING_STATUSES`'s declaration
  now carries a comment pointing at `BookingStateMachine.TRANSITIONS` as its source of truth, flagging the
  keep-in-sync risk for whoever adds a future terminal status. No other findings.
- 2026-08-26: dev-story implementation complete, status ready-for-dev → review. All three ACs shipped
  per spec verbatim. AC1: `BookingStateMachine.isTerminal`, `BookingSseService.subscribeTerminal`,
  `BookingEventResource.subscribeToEvents` branching, frontend `TERMINAL_BOOKING_STATUSES` +
  self-closing `useBookingSse`, `BookingStateChip.vue`'s new `bookingId` prop, both caller sites wired.
  AC2: `AvailabilityService.computeAvailabilitySignature`, `CoachAvailabilityResponse`/
  `CreateBookingRequest` extended, `BookingError.AVAILABILITY_CHANGED`, staleness check in
  `BookingService.createBookingRequest`, frontend signature capture/submit/refetch-on-mismatch, i18n
  in all three locales. AC3: all fourteen ledger items closed (nine `[CLOSED ...]` tags, three outright
  deletions per their own instructions, two already covered by AC1/AC2's own closures). One mechanical
  consequence not explicit in the story text: `CreateBookingRequest` gaining a 7th record component
  required updating six existing positional-constructor call sites across three test files (plus one
  helper) with a trailing `null` — behavior-preserving, confirmed via full re-run of every touched test
  class. 165 targeted backend tests green across seven test classes (including both Testcontainers ITs);
  `npx eslint` clean on all eight touched frontend files. `mvn verify` not run locally per
  `docs/validation-strategy.md`. Full detail in the Dev Agent Record's Completion Notes above.
- 2026-08-26: code review follow-up applied. Parallel adversarial review (Blind Hunter, Edge Case
  Hunter, Acceptance Auditor) found 0 decision-needed, 2 Patch findings, 3 pre-existing items correctly
  deferred, 16 dismissed as noise/acceptable-by-design. Both Patch findings resolved: (1) the "missing
  `es?.close()` in polling terminal detection" finding cited line numbers that turned out to belong to
  unrelated reschedule-handler code in the current file — the actual polling terminal branch already
  calls `es?.close()`, exactly per AC1's own spec, so no code change was needed (verified against live
  source rather than blindly re-applied). (2) The "TERMINAL_BOOKING_STATUSES frontend/backend sync"
  finding was genuinely actionable — added
  `BookingStateMachineTest.isTerminal_fullTerminalSet_matchesFrontendTerminalBookingStatuses`, which
  computes the backend's full terminal-status set via `isTerminal` and asserts it against a hardcoded
  mirror of the frontend Set, so a future desync now fails loudly. `mvn -o test -Dtest=
  BookingStateMachineTest` 64/64 green post-patch. Full detail in the Review Findings section above.
