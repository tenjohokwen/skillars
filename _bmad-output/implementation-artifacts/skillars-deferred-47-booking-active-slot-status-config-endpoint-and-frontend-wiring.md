# Story Deferred-47: Booking Active-Slot-Status Config Endpoint & Frontend Wiring

Status: done

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
     parent-facing GETs together for readability; this is purely stylistic — the file's `/coach` comment's
     "avoid Spring path-matching ambiguity" rationale doesn't apply here, since a `GET` mapping can never
     collide with the class's `PUT /{id}/accept` and `PUT /{id}/decline` mappings regardless of declaration
     order, and there is no plain `GET /{id}` mapping to collide with either):
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

- [x] Task 1: Backend config endpoint (AC: #1)
  - [x] 1.1 Add `BookingRequestConfigResponse` record to `booking.contract`.
  - [x] 1.2 Add `BookingService.getActiveSlotStatuses()` public getter.
  - [x] 1.3 Add `BookingResource.getConfig()` `GET /config` endpoint with `@PreAuthorize(SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE)` and `@GetMapping` ordering consistent with the class's existing path-matching precedent.
  - [x] 1.4 Add IT coverage to `BookingRequestResourceIT` for the new endpoint (happy path + auth-rejection, matching the file's existing conventions).
  - [x] 1.5 Run targeted `mvn test` for the touched module/IT and confirm green.
- [x] Task 2: Frontend wiring (AC: #2)
  - [x] 2.1 Add `getBookingRequestConfig` to `booking.api.js`.
  - [x] 2.2 Convert `OWN_BLOCKING_STATUSES` to `ownBlockingStatuses` ref with the identical default values.
  - [x] 2.3 Update the one usage site to `.value`.
  - [x] 2.4 Add the `onMounted` fetch with try/catch fallback, mirroring `getBatchConfig`'s existing shape.
  - [x] 2.5 Manually exercise the own-booking-row rendering, both happy path and simulated fetch-failure fallback.
  - [x] 2.6 Run `npx eslint` on both touched frontend files and confirm clean.
- [x] Task 3: Ledger hygiene (AC: #3) — apply the `[PICKED UP]` tag specified above.

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

## Dev Agent Record

### Debug Log

No blockers or deviations from plan. `mvn -o test -Dit.test=...` initially run against the wrong Maven goal (`test`, which only binds surefire — this module's `*IT` classes run under `maven-failsafe-plugin`, bound to `integration-test`/`verify`); re-run as `mvn -o integration-test -Dit.test=BookingRequestResourceIT` produced the actual result (17/17 green, 0 failures).

No interactive browser session was available in this environment to manually exercise AC2's own-booking-row rendering (Task 2.5) end-to-end. Per the fallback precedent this repo has already established for the same situation (`skillars-deferred-45`'s Dev Agent Record), verified instead by direct code inspection: the new `onMounted` fetch block is a byte-for-byte structural mirror of the already-proven `getBatchConfig()`/`maxBatchSize` block immediately above it (same try/catch shape, same silent `console.warn` fallback, same "populate ref on success, leave default on failure" behavior), `ownBlockingBookings`'s only usage site was updated from `OWN_BLOCKING_STATUSES.includes(...)` to `ownBlockingStatuses.value.includes(...)` with no other logic change, and the fallback default array is byte-for-byte identical to the array it replaced — so both the happy path and the fetch-failure path are behavior-preserving by construction, not just by inspection of this one diff.

### Completion Notes List

- AC1: `BookingService.getActiveSlotStatuses()` added as a plain public getter returning the existing package-private `ACTIVE_SLOT_STATUSES` field (unchanged, no defensive copy needed — already `List.of(...)`-immutable). `BookingRequestConfigResponse` record added to `booking.contract`, mirroring `BatchConfigResponse`'s one-field shape. `BookingResource.getConfig()` added as `GET /config` (resolves to `GET /api/bookings/requests/config`), `@PreAuthorize(SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE)`, declared between `getParentBookings()` and `getCoachBookingRequests()` per the story's grouping guidance. One new IT (`getConfig_authenticatedParent_returns200WithActiveSlotStatuses`) added to `BookingRequestResourceIT`, asserting the full 7-status list; no negative-auth-rejection test added, per AC1's own hedge — re-confirmed no existing GET-endpoint role-rejection convention exists anywhere in this IT class to match. Verified via `mvn -o integration-test -Dit.test=BookingRequestResourceIT`: 17/17 tests green (16 pre-existing + 1 new), 0 failures, 0 errors.
- AC2: `booking.api.js` gained `getBookingRequestConfig()`, placed immediately after `getParentBookings`. `BookingRequestPage.vue`'s hardcoded `OWN_BLOCKING_STATUSES` const became `ownBlockingStatuses` ref (identical 7 default values), its one usage site updated to `.value`, and a new independent try/catch block added to `onMounted` immediately after the existing `getBatchConfig()` block, mirroring its exact shape (silent `console.warn` fallback, no user-facing toast). `npx eslint` on both touched frontend files: clean, no warnings or errors. Grep-reconfirmed at implementation time: `OWN_BLOCKING_STATUSES`/`ownBlockingStatuses` has no other reference anywhere in `src/frontend/`.
- AC3: Ledger tag was already applied to `deferred-work.md` line 1276 in the story-creation commit (`834a3f0`) — reconfirmed present, no further action needed this pass.
- Story review (`story-review.md`) findings applied before dev started: AC1's endpoint-placement rationale no longer cites the inapplicable "Spring path-matching ambiguity" precedent (a `GET` mapping can never collide with the class's `PUT /{id}/...` mappings regardless of declaration order); the grouping is now correctly framed as stylistic only.

### File List

- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingRequestConfigResponse.java` (new)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` (modified — new `getActiveSlotStatuses()` getter)
- `src/main/java/com/softropic/skillars/platform/booking/api/BookingResource.java` (modified — new `GET /config` endpoint + import)
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java` (modified — new test)
- `src/frontend/src/api/booking.api.js` (modified — new `getBookingRequestConfig` export)
- `src/frontend/src/pages/parent/BookingRequestPage.vue` (modified — const→ref, usage-site update, new `onMounted` fetch)
- `_bmad-output/implementation-artifacts/deferred-work.md` (ledger tag — already applied at story-creation time, reconfirmed only)

## Change Log

| Date | Change |
|---|---|
| 2026-08-20 | Story created via story-creation process, as a single substantial item (not a bundle) per this cycle's explicit override of the standard bundling convention. Source: `deferred-work.md` line 1276 (`OWN_BLOCKING_STATUSES`/`ACTIVE_SLOT_STATUSES` duplication), re-verified byte-for-byte identical against live code. Design decision (new lightweight runtime API endpoint, no codegen) made explicitly for this story rather than left to the dev agent: checked for an existing config/metadata endpoint first (`ConfigResource` — ruled out, admin-only wrong shape; `BookingBatchResource.getConfig()` — right pattern, wrong resource boundary) and landed on a new sibling `GET /config` endpoint on `BookingResource`, the parent-facing controller that already serves this exact page. |
| 2026-08-20 | Story review (`story-review.md`) applied: AC1's placement rationale corrected to drop the inapplicable path-matching-ambiguity justification, framed as stylistic grouping instead. |
| 2026-08-20 | Implementation complete: backend `GET /api/bookings/requests/config` endpoint + IT coverage (AC1), frontend runtime wiring with byte-for-byte-identical fetch-failure fallback (AC2), ledger tag reconfirmed (AC3). All tasks complete, targeted backend tests green (17/17), frontend lint clean. Status → review. |

### Review Findings

Blind Hunter + Edge Case Hunter + Acceptance Auditor, 0 AC violations (Acceptance Auditor independently re-verified every AC1/AC2/AC3 claim, including the axios-unwrap pattern, the `/coach` path-matching-ambiguity non-applicability, the ledger tag, and "no other call site," against the live repo — no deviations found). 14 raw findings (12 Blind Hunter, 2 Edge Case Hunter merged into 1 as they name the same unguarded assignment), 11 dismissed as false positives or matches to explicitly-accepted/pre-existing convention, 2 deferred. 0 decision-needed, 0 patch.

**Defer (2):**
- [x] [Review][Defer] New `GET /config` endpoint has no negative-auth-path (role-rejection) IT coverage — no coach/unauthenticated/PLAYER-role case, only the happy-path parent test [BookingRequestResourceIT.java, BookingResource.java:49-53] — deferred, matches this IT file's own pre-existing convention (verified: no `GET`-endpoint role-rejection test exists anywhere in `BookingRequestResourceIT.java` or `BookingBatchResourceIT.java`, including the sibling `/coach` and `/batches/config` endpoints); AC1's own hedge already anticipated and correctly resolved this as "no convention exists, add none."
- [x] [Review][Defer] `ownBlockingStatuses.value = res.activeSlotStatuses` assigns the fetched value with no shape validation; a malformed/missing-field 200 response (version skew, future contract drift) would make the next `.includes()` call at `:436` throw or silently mismatch [BookingRequestPage.vue:629-630,436] — deferred, this exact unvalidated-trust pattern is symmetric with the already-shipped `maxBatchSize.value = res.maxSize` fetch one block above (same file, same risk class, pre-existing); fixing only the new call site would be an inconsistent, isolated patch — better addressed for both fetches together in a future hardening pass.

**Dismissed as noise (11):** sequential (non-`Promise.all`) awaits in `onMounted` — matches this function's existing sequential-await style throughout; fallback array "re-introducing drift" — the explicitly-documented, accepted fetch-failure-fallback design, not a new gap; axios `.data`-unwrap unconfirmed — verified true via `boot/axios.js:124`; new-route path-matching-ambiguity risk — this class has no `GET /{id}` mapping for `/config` to ever collide with, verified empty; missing defensive copy on `getActiveSlotStatuses()` — field is `List.of(...)`, already immutable, verified; instance method wrapping a static field — idiomatic for this class's DI-based access convention; no frontend automated test — the standing, explicitly-accepted repo-wide gap this and five prior `skillars-deferred-*` stories have all recorded; silent `console.warn`-only error handling — matches the adjacent `getBatchConfig()` block's explicit, spec-mandated convention verbatim; `HAS_PARENT_OR_PLAYER_ROLE` scope "blocking coaches" — correctly matches every other endpoint in this resource and mirrors `BookingBatchResource.getConfig()`'s identical annotation, coaches don't use this page's feature; endpoint placement "domain mismatch" — re-litigates this story's own already-made, explicitly-documented design decision (Dev Notes: do not re-litigate); IT test using `Map.class` instead of the real response record — matches every other test's convention in this same file.
