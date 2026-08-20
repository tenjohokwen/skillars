# Story Deferred-47: Booking Active-Slot-Status Config Endpoint & Frontend Wiring

Status: ready-for-dev

## Story

As an engineer operating this platform,
I want `BookingRequestPage.vue`'s `OWN_BLOCKING_STATUSES` to be sourced from the backend's
`BookingService.ACTIVE_SLOT_STATUSES` at runtime instead of hand-duplicated as a hardcoded frontend
constant,
so that a future change to the backend's active-slot-status set can no longer silently desync the
frontend's own-booking carve-out.

### Why this story exists

This story was explicitly greenlit as a single, more substantial item for this cycle rather than a small
bundle — a departure from every prior `skillars-deferred-*` pass's "bundle 2+ small items" convention,
per this cycle's own explicit instruction.

`_bmad-output/implementation-artifacts/deferred-work.md` (line 1276, filed by an earlier review, never
picked up) reads:

> **`BookingRequestPage.vue`'s `OWN_BLOCKING_STATUSES` duplicates the backend's `ACTIVE_SLOT_STATUSES`
> with no shared source of truth.** Verified byte-for-byte identical to `BookingService.ACTIVE_SLOT_STATUSES`
> today, but nothing keeps the two lists in step — a future change to the backend set silently desyncs the
> frontend's own-booking carve-out (a slot either wrongly shows as available or stays wrongly greyed out).
> Not fixable within a frontend-only diff; would need the status set exposed via an API contract or
> generated from a shared source.

Re-verified today by direct read: both lists remain byte-for-byte identical
(`["REQUESTED","ACCEPTED","PAYMENT_PENDING","CONFIRMED","UPCOMING","IN_PROGRESS","PAUSED"]`) — the gap
described is still real and still open (`BookingService.java:131-132`, `BookingRequestPage.vue:355-363`).

**Design decision (made for this cycle, not left to the dev agent):** the item's own text names two
candidate fixes — "an API contract or generated from a shared source." This story picks the API contract:
a new lightweight, runtime-fetched GET endpoint, mirroring the config-endpoint pattern this exact page
already consumes (`getBatchConfig()` / `GET /api/bookings/batches/config`). No build-time codegen, no
shared-file generation step — the backend stays the single source of truth and the frontend fetches it
once per mount, exactly like `maxBatchSize` already does.

**Why a new endpoint on `BookingResource`, not `ConfigResource` or `BookingBatchResource`:** `ConfigResource`
(`/api/config`) is `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` admin-only generic key/value CRUD —
wrong audience (parents/players, not admins) and wrong shape (single string value, not a status list).
`BookingBatchResource.getConfig()` (`/api/bookings/batches/config`) is parent/player-facing and already the
exact pattern to mirror, but it is scoped to batch-specific settings (`maxBatchSize`) — bolting an unrelated,
non-batch concept onto it would blur that resource's boundary. `ACTIVE_SLOT_STATUSES` is a `BookingService`
concept, and `BookingResource` (`/api/bookings/requests`) is the parent-facing controller that already serves
this exact page (`getParentBookings()`) — so the new endpoint belongs there, as a sibling `/config` route,
matching `BookingBatchResource`'s own naming convention.

## Acceptance Criteria

1. **AC1 — `BookingService.ACTIVE_SLOT_STATUSES` is exposed via a new `GET /api/bookings/requests/config`
   endpoint on `BookingResource`, parent/player-authorized, returning a record DTO.**
   - New file `src/main/java/com/softropic/skillars/platform/booking/contract/BookingRequestConfigResponse.java`:
     ```java
     package com.softropic.skillars.platform.booking.contract;

     import java.util.List;

     public record BookingRequestConfigResponse(List<String> activeSlotStatuses) {}
     ```
     Matches this module's existing `BatchConfigResponse(int maxSize)` one-field-record shape exactly.
   - In `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`: add one new
     public method, placed near the `ACTIVE_SLOT_STATUSES` field declaration (`:131-132`) or alongside the
     class's other simple public accessors:
     ```java
     public List<String> getActiveSlotStatuses() {
         return ACTIVE_SLOT_STATUSES;
     }
     ```
     `ACTIVE_SLOT_STATUSES` is built via `List.of(...)`, already immutable — no defensive copy needed. Do
     **not** change the field's existing package-private visibility or its "keeping one definition is the
     point" comment (`:128-132`) — `AvailabilityService`/`RescheduleService`/`BookingBatchService` all still
     read the static field directly within the package; this getter is purely an additional read path for
     the REST layer, not a replacement.
   - In `src/main/java/com/softropic/skillars/platform/booking/api/BookingResource.java`: add one new
     endpoint, declared after `getParentBookings()` and before `getCoachBookingRequests()` (grouping the two
     parent-facing GETs together; matches the file's own existing precedent of ordering literal-path routes
     before any `/{id}/...` routes to avoid Spring path-matching ambiguity, noted in the file's `/coach`
     comment):
     ```java
     @GetMapping("/config")
     @PreAuthorize(SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE)
     public ResponseEntity<BookingRequestConfigResponse> getConfig() {
         return ResponseEntity.ok(new BookingRequestConfigResponse(bookingService.getActiveSlotStatuses()));
     }
     ```
     Add the `import com.softropic.skillars.platform.booking.contract.BookingRequestConfigResponse;` line
     alongside the class's existing `booking.contract` imports. Resulting path: `GET /api/bookings/requests/config`.
     `@PreAuthorize(SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE)` matches every other endpoint in this class
     and `BookingBatchResource.getConfig()`'s identical annotation. The class's existing class-level
     `@Observed(name = "booking.requests")` already covers this new endpoint — no per-method `@Observed` is
     needed, matching every other method in this file (none carry one individually).
   - New IT coverage in `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java`
     (the existing IT class for this resource — `BOOKINGS_BASE = "/api/bookings/requests"` constant already
     defined at the top of the file, reuse it): one test asserting a `GET {BOOKINGS_BASE}/config` from an
     authenticated parent returns 200 with `activeSlotStatuses` equal to
     `List.of("REQUESTED","ACCEPTED","PAYMENT_PENDING","CONFIRMED","UPCOMING","IN_PROGRESS","PAUSED")`, and
     (if the file's existing tests already establish a role-rejection convention for this resource — check
     before adding a new one) one negative-auth case mirroring whatever pattern the file already uses for
     its other endpoints.

2. **AC2 — `BookingRequestPage.vue` fetches its own-booking blocking-status list from the new endpoint on
   mount instead of hardcoding it, with the identical hardcoded list kept only as a fetch-failure fallback.**
   In `src/frontend/src/pages/parent/BookingRequestPage.vue`:
   - In `src/frontend/src/api/booking.api.js`: add one new export, placed immediately after
     `getParentBookings` (`:32`) — same file, same `/requests` route family:
     ```js
     export const getBookingRequestConfig = () => api.get('/api/bookings/requests/config')
     ```
   - Add `getBookingRequestConfig` to the page's existing `import { getBatchConfig } from 'src/api/booking.api'`
     line (`:228`), making it `import { getBatchConfig, getBookingRequestConfig } from 'src/api/booking.api'`.
   - Replace the hardcoded `const OWN_BLOCKING_STATUSES = [...]` (`:355-363`) with a `ref`, keeping the
     identical seven values as the pre-fetch/fetch-failure default — mirroring `maxBatchSize`'s own
     `ref(5) // populated from backend on mount` shape exactly:
     ```js
     // ACTIVE_SLOT_STATUSES on the backend, fetched via GET /api/bookings/requests/config — populated
     // from backend on mount. Hardcoded here only as a fetch-failure fallback identical to the values
     // this replaced, so a slow/failed config load still blocks the parent's own occupied slots
     // correctly instead of showing them all as available.
     const ownBlockingStatuses = ref([
       'REQUESTED',
       'ACCEPTED',
       'PAYMENT_PENDING',
       'CONFIRMED',
       'UPCOMING',
       'IN_PROGRESS',
       'PAUSED',
     ])
     ```
   - Update the one usage site (`:434`): `if (!OWN_BLOCKING_STATUSES.includes(b.status)) return false` →
     `if (!ownBlockingStatuses.value.includes(b.status)) return false`.
   - In `onMounted` (`:596-626`), add a fetch immediately after the existing `getBatchConfig()` try/catch
     block (`:620-625`), as its own independent try/catch — mirroring that block's exact shape (silent
     `console.warn` fallback, no user-facing toast, matching this page's established "config fetch failure
     degrades quietly to the pre-fetch default" convention):
     ```js
     try {
       const res = await getBookingRequestConfig()
       ownBlockingStatuses.value = res.activeSlotStatuses
     } catch {
       console.warn('Could not load booking request config, using default active-slot statuses')
     }
     ```
   - **No other call site changes.** Grep-confirmed: `OWN_BLOCKING_STATUSES` is referenced only at its
     declaration and the one `:434` usage site, both inside this file — no other `.vue`/`.js` file imports
     or references it.
   - **Manually exercise** `BookingRequestPage.vue`'s own-booking-row rendering after this change: confirm a
     parent's own pending/booked slots still render as disabled/greyed-out rows exactly as before (same
     values, same behavior), and confirm the page still degrades correctly (falls back to the hardcoded
     default, no crash, no blank slot grid) if the new endpoint is temporarily unreachable — the same manual
     regression-check convention `skillars-deferred-45`/`-46` established for their own sequencing-guard
     changes, since this repo has no frontend test suite (see Dev Notes).

3. **AC3 — Ledger hygiene.** In `deferred-work.md`, tag the line-1276 item with
   `` `[PICKED UP by skillars-deferred-47 AC1, AC2]` ``.

## Tasks / Subtasks

- [ ] Task 1: Backend config endpoint (AC: #1)
  - [ ] 1.1 Add `BookingRequestConfigResponse` record to `booking.contract`.
  - [ ] 1.2 Add `BookingService.getActiveSlotStatuses()` public getter.
  - [ ] 1.3 Add `BookingResource.getConfig()` `GET /config` endpoint with `@PreAuthorize(SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE)` and `@GetMapping` ordering consistent with the class's existing path-matching precedent.
  - [ ] 1.4 Add IT coverage to `BookingRequestResourceIT` for the new endpoint (happy path + auth-rejection, matching the file's existing conventions).
  - [ ] 1.5 Run targeted `mvn test` for the touched module/IT and confirm green.
- [ ] Task 2: Frontend wiring (AC: #2)
  - [ ] 2.1 Add `getBookingRequestConfig` to `booking.api.js`.
  - [ ] 2.2 Convert `OWN_BLOCKING_STATUSES` to `ownBlockingStatuses` ref with the identical default values.
  - [ ] 2.3 Update the one usage site to `.value`.
  - [ ] 2.4 Add the `onMounted` fetch with try/catch fallback, mirroring `getBatchConfig`'s existing shape.
  - [ ] 2.5 Manually exercise the own-booking-row rendering, both happy path and simulated fetch-failure fallback.
  - [ ] 2.6 Run `npx eslint` on both touched frontend files and confirm clean.
- [ ] Task 3: Ledger hygiene (AC: #3) — apply the `[PICKED UP]` tag specified above.

## Dev Notes

- **This is a single substantial item this cycle, not a small bundle** — an explicit, deliberate departure
  from the standard `skillars-deferred-*` bundling convention for this pass only.
- **The design decision (new runtime API endpoint, no codegen) is already made — do not re-litigate it.**
  The dev agent's job is implementation, not re-evaluating "API endpoint vs. shared-file generation."
- **AC2's fallback default must stay byte-for-byte identical to the values it replaces.** This is a
  behavior-preserving change on the happy path (fetch succeeds, same values either way) and on the failure
  path (fetch fails, same hardcoded values as before this story existed) — there is no code path where this
  diff should change which bookings render as blocked.
- **Neither AC needs new frontend automated test coverage beyond the manual check in Task 2.5.** Standing
  repo-wide gap — no frontend test harness exists in this codebase (the same accepted gap
  `skillars-deferred-35`/`36`/`37`/`38`/`45`/`46` have all recorded for other stores/pages). The **backend**
  endpoint does get IT coverage (Task 1.4) since backend IT infrastructure already exists and this module's
  own `BookingRequestResourceIT` is the natural, already-established place for it.
- Per `docs/validation-strategy.md`, run targeted verification only: `mvn test` scoped to the touched backend
  module/IT, and `npx eslint` on the two touched frontend files — do not run a full `mvn verify` or full
  frontend build unless targeted verification proves insufficient.

### Project Structure Notes

- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingRequestConfigResponse.java` — new file (AC1).
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` — one new public getter method; `ACTIVE_SLOT_STATUSES` field itself unchanged (AC1).
- `src/main/java/com/softropic/skillars/platform/booking/api/BookingResource.java` — one new `GET /config` endpoint + one new import (AC1).
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java` — new test(s) for the endpoint (AC1).
- `src/frontend/src/api/booking.api.js` — one new export (AC2).
- `src/frontend/src/pages/parent/BookingRequestPage.vue` — `OWN_BLOCKING_STATUSES` const → `ownBlockingStatuses` ref, one usage-site update, one new `onMounted` fetch block (AC2).
- `_bmad-output/implementation-artifacts/deferred-work.md` — one `[PICKED UP]` tag (AC3).
- No changes to `BookingBatchResource.java`, `ConfigResource.java`, `booking.store.js`, or any other page — all confirmed to need no change (AC1/AC2's "no other call site" verification).

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 1276 — this story's sole source item]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:128-138` — `ACTIVE_SLOT_STATUSES` definition and its "keeping one definition is the point" rationale]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/api/BookingBatchResource.java:35-39` and `src/main/java/com/softropic/skillars/platform/booking/contract/BatchConfigResponse.java` — the exact sibling pattern this story mirrors]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/api/BookingResource.java` — full file read, AC1's target]
- [Source: `src/main/java/com/softropic/skillars/platform/config/api/ConfigResource.java` — checked and ruled out as the extension target (admin-only, wrong shape)]
- [Source: `src/frontend/src/pages/parent/BookingRequestPage.vue:220-266,345-363,596-631` — `maxBatchSize`/`getBatchConfig()` pattern this story's AC2 mirrors exactly, and `OWN_BLOCKING_STATUSES`'s current declaration/usage]
- [Source: `src/frontend/src/api/booking.api.js:32,68` — `getParentBookings`/`getBatchConfig` export conventions]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java:1-45` — existing IT conventions (`AbstractIntegrationTest`, `HttpTestClient`, AssertJ, `BOOKINGS_BASE` constant) this story's AC1 test extends]

## Change Log

| Date | Change |
|---|---|
| 2026-08-20 | Story created via story-creation process, as a single substantial item (not a bundle) per this cycle's explicit override of the standard bundling convention. Source: `deferred-work.md` line 1276 (`OWN_BLOCKING_STATUSES`/`ACTIVE_SLOT_STATUSES` duplication), re-verified byte-for-byte identical against live code. Design decision (new lightweight runtime API endpoint, no codegen) made explicitly for this story rather than left to the dev agent: checked for an existing config/metadata endpoint first (`ConfigResource` — ruled out, admin-only wrong shape; `BookingBatchResource.getConfig()` — right pattern, wrong resource boundary) and landed on a new sibling `GET /config` endpoint on `BookingResource`, the parent-facing controller that already serves this exact page. |
