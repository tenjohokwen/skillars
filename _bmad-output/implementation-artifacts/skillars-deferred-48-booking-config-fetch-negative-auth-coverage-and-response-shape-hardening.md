# Story Deferred-48: Booking Config-Fetch Negative-Auth Coverage & Response-Shape Hardening

Status: ready-for-dev

## Story

As an engineer operating this platform,
I want the new `GET /api/bookings/requests/config` endpoint to have negative-auth IT coverage and
`BookingRequestPage.vue`'s two config-fetch response bodies to be shape-validated before use,
so that a wrong-role caller is provably rejected and a malformed/contract-drifted config response degrades
to the known-good fallback instead of throwing inside the slot-rendering computed or silently comparing
against the wrong shape.

### Why this story exists

Both items are 2026-08-20 deferrals from `skillars-deferred-47`'s own code review — the story that added the
`GET /api/bookings/requests/config` endpoint and its frontend consumer. Neither was fixable within that
story's own diff (one was explicitly conditional on a convention that didn't yet exist to check against; the
other was explicitly framed as "a single hardening pass covering both fetches together," i.e. bundled scope
by design), so both were filed rather than patched ad hoc. This story is exactly that bundle: two small,
mechanical, non-decision-needing items from the same immediately-preceding story's review, picked up in the
very next pass — no other item in `deferred-work.md` qualified this cycle (every other untagged item found on
a full re-mine was either already `[PICKED UP]`/`[CLOSED]` by an intervening story, explicitly decision-needed
pending a design/product call, an accepted standing tradeoff — most commonly this repo's own recorded
no-frontend-test-infrastructure gap — or an ops/infra item needing live-environment verification this
environment cannot perform).

`_bmad-output/implementation-artifacts/deferred-work.md`, under
`## Deferred from: code review of skillars-deferred-47-booking-active-slot-status-config-endpoint-and-frontend-wiring (2026-08-20)`,
reads:

> **New `GET /api/bookings/requests/config` endpoint has no negative-auth-path (role-rejection) IT
> coverage.** Only the happy-path authenticated-parent case is tested
> (`BookingRequestResourceIT.getConfig_authenticatedParent_returns200WithActiveSlotStatuses`); no test
> asserts a coach, an unauthenticated caller, or the `PLAYER` role's actual behavior against
> `@PreAuthorize(SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE)`. Not a regression specific to this diff —
> verified that no `GET`-endpoint role-rejection test exists anywhere in `BookingRequestResourceIT.java` or
> `BookingBatchResourceIT.java` today, including the sibling `/coach` and `/batches/config` endpoints this
> story mirrors; this story's own AC1 explicitly hedged the negative test as conditional on an existing
> convention, and correctly found none. Standing candidate for whenever this resource's IT suite gets a
> general role-rejection pass for its `GET` endpoints.
> [`BookingResource.java:49-53`, `BookingRequestResourceIT.java`]

> **`BookingRequestPage.vue`'s new `getBookingRequestConfig()` fetch assigns the response body to
> `ownBlockingStatuses.value` with no shape validation.** `res.activeSlotStatuses` is trusted directly
> (`:629-630`); a malformed or missing-field 200 response (e.g. future contract drift, a version-skewed
> rolling deploy) would make the very next `.includes()` call at `:436` either throw (breaking
> `ownBlockingBookings`, and by extension `slotRows`, for the whole page) or silently do a wrong-shape
> comparison. Not a new anti-pattern introduced by this diff — the identical unvalidated-trust pattern
> already exists one block above for `maxBatchSize.value = res.maxSize` (`:622`), so patching only the new
> call site here would be an inconsistent, isolated fix. Better addressed as a single hardening pass
> covering both fetches together (e.g. `Array.isArray` guard before assignment, falling back to the current
> default on a malformed shape) whenever one of them is next touched.
> [`BookingRequestPage.vue:622-633,436`]

**Design decisions made for this cycle (not left to the dev agent):**

- **AC1's scope is the new `/config` endpoint's own coach-role rejection only** — not a general role-rejection
  pass across every `GET` endpoint in either IT file. The source item names three untested dimensions
  (coach, unauthenticated, `PLAYER` role); this story closes the one with a directly analogous existing
  pattern to mirror (`acceptBooking_wrongCoach_returns403` and siblings already assert a wrong-role/wrong-party
  caller gets `403` the same way `HttpClientErrorException`/`HttpStatus.FORBIDDEN` is asserted throughout this
  file). Extending coverage to unauthenticated callers or establishing a `PLAYER`-role fixture is a larger,
  file-wide testing-convention decision outside a bundled small-fix story's bar — leave it filed, do not
  expand scope unilaterally.
- **AC2's guard shape is `Array.isArray`/`Number.isInteger` inline checks with a `console.warn` fallback to
  the existing pre-fetch default** — matching the source item's own suggested shape verbatim, and matching
  this page's established "config fetch failure degrades quietly to the pre-fetch default, no user-facing
  toast" convention (identical to both `try`/`catch` blocks already in `onMounted`). Do not introduce a
  runtime schema-validation library (e.g. Zod) for two fields — that is disproportionate to the gap.

## Acceptance Criteria

1. **AC1 — `GET /api/bookings/requests/config` rejects a coach-role caller with 403, proven by a new IT.**
   - In `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java`, add one
     new test immediately after the existing `getConfig_authenticatedParent_returns200WithActiveSlotStatuses`
     test, mirroring this file's own established wrong-role negative-test shape
     (`acceptBooking_wrongCoach_returns403` / `declineBooking_wrongCoach_returns403`'s
     `assertThatThrownBy(...).isInstanceOf(HttpClientErrorException.class).satisfies(...isEqualTo(HttpStatus.FORBIDDEN))`
     pattern) exactly:
     ```java
     @Test
     void getConfig_coachRole_returns403() {
         String cookies = loginAndGetCookies(COACH_EMAIL);

         assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
             baseUrl() + BOOKINGS_BASE + "/config",
             HttpMethod.GET,
             null,
             authenticatedHeaders(cookies),
             Map.class
         ))
             .isInstanceOf(HttpClientErrorException.class)
             .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
     }
     ```
     `COACH_EMAIL` and `loginAndGetCookies` are both already defined in this file (used throughout for the
     existing coach-side tests) — no new fixture, no new constant, no new import.
   - **No other file changes for AC1.** `BookingResource.getConfig()` itself is already correctly annotated
     (`@PreAuthorize(SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE)`, shipped by `skillars-deferred-47`) — this
     AC adds proof, not a fix to production code.
   - **Explicitly out of scope, do not add:** an unauthenticated-caller test, a `PLAYER`-role fixture/test, or
     any change to `BookingBatchResourceIT.java`'s identical gap on `/batches/config`. All three are named in
     the source item as standing, not as this story's job.

2. **AC2 — Both `onMounted` config fetches in `BookingRequestPage.vue` validate response shape before
   assignment, falling back to the existing pre-fetch default on a malformed shape.**
   In `src/frontend/src/pages/parent/BookingRequestPage.vue`'s `onMounted` (the two `try`/`catch` blocks
   added by `skillars-deferred-47`, immediately after `bookingStore.loadPlayerPacks`):
   - Replace the unconditional `maxBatchSize.value = res.maxSize` with a shape-checked assignment, warning and
     keeping the current value otherwise:
     ```js
     try {
       const res = await getBatchConfig()
       if (Number.isInteger(res.maxSize) && res.maxSize > 0) {
         maxBatchSize.value = res.maxSize
       } else {
         console.warn('Batch config response had an unexpected shape, using default max size')
       }
     } catch {
       console.warn('Could not load batch config, using default max size')
     }
     ```
   - Replace the unconditional `ownBlockingStatuses.value = res.activeSlotStatuses` the same way:
     ```js
     try {
       const res = await getBookingRequestConfig()
       if (Array.isArray(res.activeSlotStatuses)) {
         ownBlockingStatuses.value = res.activeSlotStatuses
       } else {
         console.warn('Booking request config response had an unexpected shape, using default active-slot statuses')
       }
     } catch {
       console.warn('Could not load booking request config, using default active-slot statuses')
     }
     ```
   - **No change to the `catch` blocks' existing messages or to any other line in `onMounted`.** Both guards
     are pure additions ahead of the existing assignment; the `try`/`catch` boundaries, the two exported API
     calls, and every other line in the block are unchanged.
   - **Manually exercise** (this repo has no frontend test suite — see Dev Notes): confirm the happy path is
     unaffected (both values still populate from a normal 200 response, identical to `skillars-deferred-47`'s
     own behavior), and confirm a simulated malformed response (e.g. temporarily stub one fetch to resolve
     `{}` in a local dev session) leaves the corresponding ref at its pre-fetch default with a console warning
     instead of throwing inside `ownBlockingBookings`/`slotRows`.

3. **AC3 — Ledger hygiene.** In `deferred-work.md`, tag both source items:
   - The `New GET /api/bookings/requests/config endpoint has no negative-auth-path...` item with
     `` `[PICKED UP by skillars-deferred-48 AC1]` ``.
   - The `` `BookingRequestPage.vue`'s new `getBookingRequestConfig()` fetch assigns... `` item with
     `` `[PICKED UP by skillars-deferred-48 AC2]` ``.

## Tasks / Subtasks

- [ ] Task 1: Backend negative-auth IT (AC: #1)
  - [ ] 1.1 Add `getConfig_coachRole_returns403` to `BookingRequestResourceIT.java`, immediately after
    `getConfig_authenticatedParent_returns200WithActiveSlotStatuses`.
  - [ ] 1.2 Run targeted verification for the touched IT and confirm green (see Dev Notes — this project's
    `*IT` classes run under `maven-failsafe-plugin`, not `mvn test`).
- [ ] Task 2: Frontend response-shape hardening (AC: #2)
  - [ ] 2.1 Add the `Number.isInteger`/`> 0` guard around the `maxBatchSize.value` assignment.
  - [ ] 2.2 Add the `Array.isArray` guard around the `ownBlockingStatuses.value` assignment.
  - [ ] 2.3 Manually exercise both the happy path and a simulated malformed-response fallback.
  - [ ] 2.4 Run `npx eslint` on the touched file and confirm clean.
- [ ] Task 3: Ledger hygiene (AC: #3) — apply both `[PICKED UP]` tags specified above.

## Dev Notes

- **This is a 2-item bundle from the same immediately-preceding story's own code review** — both items are
  small, mechanical, and explicitly not decision-needing. Do not expand either AC's scope (see the "Design
  decisions made for this cycle" section above) — the ledger will still hold the broader versions of both
  gaps (general `GET`-endpoint role-rejection coverage; a runtime-validation-library discussion) as future
  candidates if a later pass wants to pick them up.
- **IT-execution gotcha, discovered during `skillars-deferred-47`'s own dev pass:** this project's `*IT`
  classes (including `BookingRequestResourceIT`) run under `maven-failsafe-plugin`, bound to the
  `integration-test`/`verify` phases, **not** `maven-surefire-plugin`/`mvn test`. Running
  `mvn -o test -Dtest=BookingRequestResourceIT` silently executes nothing (exit 0, no report generated) and
  will look like a false pass. Use `mvn -o integration-test -Dit.test=BookingRequestResourceIT` (or
  `-Dit.test=BookingRequestResourceIT#getConfig_coachRole_returns403` to scope to just the new test) and
  confirm a `target/failsafe-reports/...BookingRequestResourceIT.txt` report was actually written with the
  expected test count.
- **AC2 is not behavior-changing on the happy path.** Both guards are additive checks ahead of an assignment
  that already succeeds unconditionally today; a normal 200 response with the expected shape produces
  identical `maxBatchSize.value`/`ownBlockingStatuses.value` results before and after this diff.
- **No new frontend automated test coverage** — standing repo-wide gap, the same one recorded by every prior
  `skillars-deferred-*` frontend-only change (most recently `-45`/`-46`/`-47`). Manual exercise per AC2's own
  text is this project's established verification path here.
- Per `docs/validation-strategy.md`, run targeted verification only: the one new/touched backend IT via
  `maven-failsafe-plugin` as above, and `npx eslint` on the one touched frontend file — do not run a full
  `mvn verify` or full frontend build unless targeted verification proves insufficient.

### Project Structure Notes

- `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java` — one new test
  method (AC1).
- `src/frontend/src/pages/parent/BookingRequestPage.vue` — two `onMounted` assignments gain a shape guard;
  no other line changes (AC2).
- `_bmad-output/implementation-artifacts/deferred-work.md` — two `[PICKED UP]` tags (AC3).
- No changes to `BookingResource.java`, `BookingService.java`, `booking.api.js`, `BookingBatchResourceIT.java`,
  or any other file — all confirmed unnecessary by both source items' own analysis and this story's scoping
  decisions above.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, section
  `## Deferred from: code review of skillars-deferred-47-booking-active-slot-status-config-endpoint-and-frontend-wiring (2026-08-20)`
  — this story's two source items, both quoted in full above]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/api/BookingResource.java:49-53` —
  `getConfig()`, shipped by `skillars-deferred-47`, AC1's target]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java` —
  existing IT conventions this story's AC1 test extends verbatim: `COACH_EMAIL`/`loginAndGetCookies` fixture,
  and the `acceptBooking_wrongCoach_returns403`/`declineBooking_wrongCoach_returns403` negative-test shape]
- [Source: `src/frontend/src/pages/parent/BookingRequestPage.vue`'s `onMounted` — the two
  `skillars-deferred-47`-added `try`/`catch` blocks AC2 hardens, and `ownBlockingBookings`'s `:436`
  `.includes()` call the malformed-shape risk was originally filed against]
- [Source: `docs/validation-strategy.md` — targeted-test-only validation policy, and this story's own
  discovery of the `mvn test` vs. `mvn integration-test` `*IT`-execution gotcha, recorded in Dev Notes above
  for the next dev agent]

## Change Log

| Date | Change |
|---|---|
| 2026-08-20 | Story created via story-creation process, bundling two small, non-decision-needing items both filed by `skillars-deferred-47`'s own code review — the immediately preceding story. Full re-mine of `deferred-work.md` found no other untagged item qualifying this pass (all either already picked up/closed by an intervening story, explicitly decision-needed, a standing accepted tradeoff, or an ops/infra item needing live-environment verification). |
