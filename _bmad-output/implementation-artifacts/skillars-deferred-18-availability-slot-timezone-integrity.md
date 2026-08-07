# Story Deferred-18: Availability Slot & Coach-Timezone Data Integrity

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **STILL OUTSTANDING AFTER CODE REVIEW — Task 7's live-app spot-check has never been performed.**
> The 2026-08-07 code review did not close this: it has no browser tooling either. Everything below
> remains unverified by anything other than code reading and backend tests. Do not treat this story's
> `done` status as covering it.
>
> **Reviewer note:** All backend work is implemented and verified. Post-review `mvn -o verify`:
> **825 unit + 905 integration tests, 0 failures, 0 errors, 5 skipped, BUILD SUCCESS** (30:45).
> *Count correction:* the "903 tests" recorded here pre-review was the **failsafe/integration total
> only** — the 823 unit tests of that run were not counted, so the suite was larger than claimed, not
> smaller. This is the same class of miscount deferred-16's review had to correct; the two totals are
> reported separately above to stop it recurring. **Task 7's live-app
> spot-check was not performed** — this execution environment has no browser/UI-driving tool and no
> project skill for launching the app, so the frontend-visible behavior below is unverified beyond code
> reading + backend tests:
> - A slot booked/requested by one parent actually disappears (not just renders disabled) for a
>   *different* parent viewing the same coach/week.
> - Removing `bookedStartTimes` didn't regress the batch-mode disable-at-max binding in
>   `BookingRequestPage.vue`.
> - The coach-facing `AvailabilityManagerPage` weekly calendar doesn't render AC1's transient
>   pseudo-blocks as if they were real blocks.
>
> Please run this spot-check (or delegate to an agent with browser tooling) before merging.

## Story

As a parent browsing a coach's available slots, and as a coach whose availability windows and profile timezone drive what parents see,
I want the availability endpoint to never offer a slot someone has already booked, to compute each window's instants in its own timezone, to reject a request for a coach that doesn't exist, and to reject a nonsense timezone string at the moment a coach sets it,
so that the slot list I book from is actually correct and a bad coach-timezone value can never enter the system undetected.

### Why this story exists

`skillars-deferred-17`'s code review (2026-08-06) found four items in `com.softropic.skillars.platform.booking.service.AvailabilityService` and the coach-profile-builder write path while investigating that story's own AC4. All four were deferred (D2, D5, D9, D10 in `deferred-work.md`) as pre-existing and out of that story's scope. This story closes all four — they are all small, all in the same service, and grouping them into one story avoids paying the ~35-minute `mvn -o verify` cost four separate times for four single-digit-line fixes.

Re-verified against the working tree at commit `f2de881` (2026-08-06), all four are still open and reproducible exactly as described:

| # | Source | Verified current state (2026-08-06) |
|---|---|---|
| AC1 | `deferred-17` review D2 | **CONFIRMED, reproducible today.** `AvailabilityService.computeAvailableSlots` (`AvailabilityService.java:172-203`) only ever subtracts `CoachAvailabilityBlock` rows from a window — it never looks at `Booking` at all. `BookingRequestPage.vue:236-249`'s `bookedStartTimes` is the *only* already-booked guard in the system, and it is scoped to `String(b.coachId) === String(coachId) && String(b.playerId) === String(playerId.value)` — the current parent's own child, only. A second parent (or the same parent's other child) with a `REQUESTED`/`ACCEPTED`/etc. booking against the same coach and slot renders that slot as enabled and clickable; submitting reaches `BookingService.createBookingRequest`'s overlap check (`BookingService.java:210-218`) and fails with `booking.slotUnavailable` — a real, confusing dead-end for a real user action, and the slot stays in the list to be clicked again. |
| AC2 | `deferred-17` review D9 | **CONFIRMED, reproducible today.** `AvailabilityService.java:52` computes one `zoneId` from `windows.isEmpty() ? "UTC" : windows.get(0).getCanonicalTimezone()`, and every window's `windowStart`/`windowEnd` in the per-window loop (`:90-91`) is materialized using that single zone — never `window.getCanonicalTimezone()` for the window actually being processed. `coach_availability_windows.canonical_timezone` is independently writable per window (`CoachProfileService.java:173`, Step 4 profile-builder payload) and the schema explicitly permits divergence (this is the same divergence class `D8` documents at the profile-vs-window level; D9 is the narrower, independently-fixable bug where windows disagree *with each other*). A coach with windows in two different zones gets correct instants only for whichever window happens to be first in `windows`, and silently wrong ones for every other window — a subtle data-correctness bug with no error, no log, no test catching it. |
| AC3 | `deferred-17` review D5 | **CONFIRMED, reproducible today.** `AvailabilityService.java:60-63`: `coachProfileRepository.findById(coachId).map(CoachProfile::getCanonicalTimezone).orElse(null)`, falling back to `"UTC"` — for both a real coach with a blank zone and a `coachId` that matches no coach at all. `AvailabilityResource.getAvailability` (`AvailabilityResource.java:41-47`) never validates `coachId` either. `GET /api/bookings/coaches/{random-uuid}/availability` returns `200` with empty `windows`/`blocks`/`computedSlots` and `canonicalTimezone: "UTC"` — indistinguishable from "this coach genuinely has no availability configured yet." |
| AC4 | `deferred-17` review D10 | **CONFIRMED, reproducible today.** `ProfileBuilderStep1Request.java:15` and `ProfileBuilderStep4Request.java:20` (the `AvailabilityWindowRequest` nested record) both declare `canonicalTimezone` as `@NotBlank String` only. `CoachProfileService.java:90,173` stores both verbatim with no `ZoneId.of(...)` or equivalent check anywhere on this write path. `deferred-17`'s code review restored a *read-side* guard in `BookingService`/`AvailabilityService` (UTC fallback + WARN) for values already in the database, but nothing stops a new garbage string from being written in the first place. |

### Items examined and deliberately NOT folded in

- **`D8` — reconciling `coach_profiles.canonical_timezone` and `coach_availability_windows.canonical_timezone` into one column.** Explicitly out of scope. `deferred-17`'s own code review rejected folding this in: it needs a migration, a backfill rule for which value wins on existing rows, and a product decision on whether per-window timezones are a deliberate feature (a coach who coaches across zones) or an accident of the profile-builder form. AC2 below fixes D9 (windows disagreeing *with each other*) without touching D8 (windows vs. profile) — they are independently fixable, and the deferred-work.md item says so explicitly: "if the columns are reconciled, this collapses to a non-issue," not "must wait for reconciliation."
- **D1 (no session-duration cap) and D3 (`formatSlot` hardcodes `'en'` locale)**, also from the same `deferred-17` review. Both are real but neither belongs here: D1 is a product decision (slot slicing or a duration field) well beyond a bug-fix story, and D3 is a systemic 4+-page i18n sweep, not an `AvailabilityService` bug. Left in `deferred-work.md` untouched.
- **Removing the `bookedStartTimes` computed's underlying query pattern from the store.** Only `BookingRequestPage.vue` defines and uses `bookedStartTimes` — it is not shared store state, so no `booking.store.js` change is needed for AC1 beyond what's already scoped there for `coachTimezone`/`computedSlots` in `skillars-deferred-17` (untouched by this story).

## Acceptance Criteria

1. **`AvailabilityService` never returns a slot that overlaps an active booking for that coach, for any requester — not just the current parent/player.** Reuse the existing `BookingRepository.findOverlappingBookings(UUID coachId, Instant startTime, Instant endTime, List<String> statuses, UUID excludeBookingId)` (`BookingRepository.java:25-30`) — it already implements the exact half-open overlap semantics this AC needs (`requestedStartTime < :endTime AND requestedEndTime > :startTime`), and `BookingService.createBookingRequest` already calls it with a `null` `excludeBookingId` (`BookingService.java:210-211`), the precise call shape needed here. **Do not add a new repository method for this** — a second query encoding the same overlap semantics would drift from this one exactly the way this same AC's reasoning about `ACTIVE_SLOT_STATUSES` warns against ("a second copy would drift").

   Call `findOverlappingBookings` once per `getAvailabilityCalendar` invocation (not once per window), passing the **padded** `weekStartInstant`/`weekEndInstant` from AC2 below (not the unpadded week bounds — see AC2 for why), `null` for `excludeBookingId`, and the **same active-status set `createBookingRequest`'s own overlap check uses** — `BookingService.ACTIVE_SLOT_STATUSES` (`BookingService.java:117-118`, currently `REQUESTED, ACCEPTED, PAYMENT_PENDING, CONFIRMED, UPCOMING, IN_PROGRESS, PAUSED`). That field is `private`; relax it to package-private (drop the `private` keyword, same as its neighbor `ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED` one line below it, which already carries the exact rationale you need: *"Package-private, not private: ... Keeping one definition is the point — a second copy would drift"*). `AvailabilityService` is in the same `com.softropic.skillars.platform.booking.service` package, so this is a same-package reuse, zero new imports. **Do not hardcode a second copy of this status list anywhere.**

   Inside the per-window loop, merge each window's overlapping bookings into the same shape `computeAvailableSlots` already consumes, **without changing `computeAvailableSlots`'s signature**: build a transient (never-saved) `CoachAvailabilityBlock` per overlapping booking — `new CoachAvailabilityBlock()` then `setStartDatetime(booking.getRequestedStartTime())`/`setEndDatetime(booking.getRequestedEndTime())` — and add it to the same list passed to `computeAvailableSlots(windowStart, windowEnd, occupied)` alongside the real, persisted overlapping blocks. `computeAvailableSlots` only ever reads `getStartDatetime()`/`getEndDatetime()` off the objects it's given (`AvailabilityService.java:172-203`) — it does not care whether they're persisted. **Do not include these transient pseudo-blocks in `weekBlocks`/`blockResponses`** — that list is also serialized into the API response's `blocks` field, and a booking is not a block; keep the merge local to the per-window `occupied` list only.

   Once this ships, `BookingRequestPage.vue`'s `bookedStartTimes` computed (`:236-249`) becomes structurally unreachable: a booked slot can no longer appear in `bookingStore.computedSlots` at all, for anyone, so `bookedStartTimes.has(slot.startDatetime)` can never be true. Delete the `bookedStartTimes` computed entirely and its two usages at `:43-44` (both inside the `:disable` binding), leaving:
   ```
   :disable="batchMode ? (!bookingStore.isSlotInBasket(slot.startDatetime) && batchAtMax) : false"
   ```
   Do not leave the dead computed in place "just in case" — a guard that can structurally never fire reads as protection that isn't there, which is worse than no guard at all (this is the exact review finding that flagged it).

2. **Each availability window's slot instants are computed in *that window's own* `canonicalTimezone`, not `windows.get(0)`'s.** Inside the per-window loop in `getAvailabilityCalendar` (`AvailabilityService.java:89-99`), resolve a per-window `ZoneId` from `window.getCanonicalTimezone()` — with the same `try { ZoneId.of(...) } catch (DateTimeException) { "UTC" }` fallback pattern already used at `:65-70` for the outer `zoneId` and at `:220-228` in `hasBookingConflict` — and use *that* zone (not the outer `zoneId` variable) for `windowStart`/`windowEnd`'s `.atZone(...)`.

   **This makes the outer `zoneId`'s fetch-window role correctness-bearing, not merely coarse padding, and it must be widened to match.** Once a window can compute its instants in a zone that diverges from `windows.get(0)`'s, a window far enough from the outer zone (Tokyo vs. Los Angeles is a ±26h spread) can have its actual instants fall entirely outside an unpadded `[weekStartInstant, weekEndInstant)` still computed only in the outer zone — silently dropping that window's overlapping blocks *and* the AC1 booking query out of the fetch, which reproduces AC1's exact failure mode through AC2. Pad the fetch bounds by one day on each side:
   ```java
   Instant weekStartInstant = weekStart.minusDays(1).atStartOfDay(zoneId).toInstant();
   Instant weekEndInstant   = weekStart.plusDays(8).atStartOfDay(zoneId).toInstant();
   ```
   The per-window `overlapping` filter (`:93-96`) already narrows precisely against each window's own `windowStart`/`windowEnd`, so widening the fetch costs extra rows fetched and nothing else — it does not change which blocks or bookings apply to which window. **`blockResponses` must stay exactly week-scoped** (it is serialized into the API response's `blocks` field, and padding must not leak a block dated the day before/after the requested week into that list) — filter it against the unpadded `weekStart`/`weekStart.plusDays(7)` bounds at the response-mapping step (`:108-111`), separately from the padded `weekBlocks` used for the per-window overlap filter.

3. **`GET /api/bookings/coaches/{coachId}/availability` returns 404, not 200, for a `coachId` that matches no coach profile.** At the top of `getAvailabilityCalendar`, before the `windowRepository.findByCoachId(coachId)` call, look up the `CoachProfile` via `coachProfileRepository.findById(coachId)` and `.orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"))` — the same exception type and message shape already used by `requireProfile` in the same class (`AvailabilityService.java:205-208`). Reuse this single lookup for the existing `coachTimezone` derivation at `:60-63` instead of doing a second `findById` — do not query `CoachProfile` twice. **Do not change behavior for a real coach with a blank/missing `canonicalTimezone`** — that case keeps its existing `"UTC"` fallback unchanged; only a `coachId` matching zero rows becomes a 404.

4. **A non-IANA-zone string can no longer be written to `coach_profiles.canonical_timezone` or `coach_availability_windows.canonical_timezone` via the profile builder.** Add a new Bean Validation constraint, following this codebase's established custom-validator convention in `com.softropic.skillars.infrastructure.validation`:
   - `@IanaTimezone` annotation (`@Constraint(validatedBy = IanaTimezoneValidator.class)`, `@Target({ElementType.METHOD, ElementType.FIELD})`).
   - `IanaTimezoneValidator implements ConstraintValidator<IanaTimezone, String>`: return `true` for `null`/blank (that's `@NotBlank`'s job, not this validator's — see `CamPhoneValidator.java:28-30` for the exact pattern of delegating blank-handling elsewhere); otherwise `try { ZoneId.of(value); return true; } catch (DateTimeException e) { return false; }`.
   - **Follow `CamPhone`/`CamPhoneValidator`'s message-resolution shape, not `LangIso2`'s — this is mandatory, not a stylistic choice between the two.** `LangIso2`'s minimal `@Constraint`-only shape relies on a default message template (`{custom.validation.constraints.X.message}`) that Hibernate Validator's default interpolator cannot resolve without a matching `ValidationMessages.properties` entry — none exists in this codebase for any validator, and `ApiAdvice.processFieldErrors` only recognizes a message as customized when it contains a `|`. Without the pipe, the API would return the literal, unresolved `{...}` string to the client — the exact defect this story exists to fix, reproduced one validator later. Use `CamPhoneValidator.java:69-75`'s pattern instead:
     ```java
     context.disableDefaultConstraintViolation();
     context.buildConstraintViolationWithTemplate(
         "validation.timezone.invalid|Timezone must be a valid IANA zone identifier")
         .addConstraintViolation();
     ```
   - Apply `@IanaTimezone` alongside the existing `@NotBlank` on **both** write-path fields: `ProfileBuilderStep1Request.canonicalTimezone` (`ProfileBuilderStep1Request.java:15`) and `ProfileBuilderStep4Request.AvailabilityWindowRequest.canonicalTimezone` (`ProfileBuilderStep4Request.java:20`).
   - **`ProfileBuilderStep4Request.windows` also needs `@Valid` added to its element type — this is a required part of this AC, not optional.** Today it is declared `@NotEmpty @Size(max = 14) List<AvailabilityWindowRequest> windows` (`ProfileBuilderStep4Request.java:14`) with no `@Valid` cascade, so Bean Validation never descends into `AvailabilityWindowRequest` at all — none of its constraints run, including the `@NotBlank`/`@IanaTimezone` being added here. Change it to `List<@Valid AvailabilityWindowRequest> windows`, matching this codebase's own convention at `CreateBatchRequest.java:16`, `RadarAssessmentRequest.java:17`, `SessionBlockRequest.java:16`, and `CreateSessionPlanRequest.java:14`. Only `ProfileBuilderStep1Request` needs no such change — `canonicalTimezone` is a top-level record component there, not nested in a list, so its `@NotBlank` already fires today.

     **This one annotation also switches on three previously-dead constraints on `AvailabilityWindowRequest`** — `@Min(1) @Max(7) dayOfWeek`, and `@NotNull` on `startTime` and `endTime` — none of which run against Step 4 payloads today. That is intended and correct (a `dayOfWeek: 9` or a null `startTime` should already have been rejected), but it is a real behavior change beyond "one annotation on one field": a Step 4 request that previously reached `CoachProfileService.saveStep4` with an out-of-range `dayOfWeek` or a missing time now returns 400 instead. Task 5's test coverage below must include this, since nothing in `CoachProfileBuilderIT` today exercises a nested Step-4 constraint (`saveStep4_noWindows_returns400` only exercises `@NotEmpty` on the list itself, which runs with or without `@Valid`).
   - Both controller methods (`ProfileBuilderResource.java:45,66`) already carry `@Valid`, so no controller change is needed — an invalid zone (or, after the fix above, an invalid nested field) now fails validation and returns 400 automatically through the existing global exception handling.
   - Add the `validation.timezone.invalid` i18n key to **all four** `src/main/resources/i18n/messages*.properties` files that carry the `validation.phone.*` block as the naming/parallel-key convention to mirror: `messages.properties` (base/fallback, `:59-64`), `messages_en.properties` (`:44-49`), `messages_de.properties`, and `messages_fr.properties`. Do not skip `messages_en.properties` — it carries its own copy of the `validation.phone.*` block distinct from the base file, so the English-locale path resolves through it, not through the base file, when a locale is explicitly negotiated. **Do not leave a dangling `{custom.validation.constraints.X.message}` placeholder with no matching properties-file entry** — `LangIso2` already has exactly this problem (unused today, confirmed zero call sites) and it is not a pattern to repeat.
   - **Do not add `@IanaTimezone` to `CreateWindowRequest`/`UpdateWindowRequest`** (`booking.contract` package) — neither of those records carries a client-supplied `canonicalTimezone` field at all; `AvailabilityService.addWindow` (`:117-127`) derives the window's zone from the coach's own profile (`profile.getCanonicalTimezone()`), never from client input, so there is nothing to validate there.
   - **Out of scope, deliberately: `ZoneId.of(...)` accepts fixed-offset strings (`"+05:00"`, `"GMT+2"`, `"Z"`) that are not canonical IANA zone identifiers.** A stricter check (`ZoneId.getAvailableZoneIds().contains(value)`) would close that gap, but D10's reproduced defect is outright garbage strings (`"Not/AZone"`) being accepted with zero validation — `ZoneId.of(...)` already fully closes that gap, and no audit has established whether any fixed-offset value exists in production data today (see the Dev Notes decision on the D10 audit query, below). Tightening to canonical-name-only is a stricter, independently-scoped validation change with its own test surface; it is not part of this AC and should not be added opportunistically while touching this file.

5. **`deferred-work.md` reflects reality.** Under `## Deferred from: code review of skillars-deferred-17-booking-request-slot-payload-timezone-integrity (2026-08-06)`, delete the D2, D5, D9, and D10 bullets (the heading stays — D1, D3, D4, D6, D7, D8 remain open and untouched). Amend the `## Last audit: 2026-08-06 (skillars-deferred-18 story creation)` block (already added during story creation — do not duplicate it) with an implementation-outcome note recording what shipped, mirroring every prior `skillars-deferred-*` story's closing convention. **Also refresh D1's source-line citation** (`AvailabilityService.java:170-201`, currently referenced by the still-open D1 item) — this story's edits to `getAvailabilityCalendar` and `computeAvailableSlots` will shift line numbers, so leave D1 pointing at stale lines otherwise.

## Tasks / Subtasks

- [x] **Task 1 — Reproduce all four defects before changing anything (AC: 1, 2, 3, 4)**
  - [x] Confirm AC1 via a targeted unit or IT probe: two bookings (different parents, or same parent/different players) against the same coach and overlapping instants; assert the slot appears in `computedSlots` pre-fix.
  - [x] Confirm AC2 by seeding two windows on the same coach with different `canonicalTimezone` values and asserting the second window's computed instants are wrong pre-fix (computed as if in the first window's zone).
  - [x] Confirm AC3 with a `GET .../coaches/{random-uuid}/availability` call returning 200 pre-fix.
  - [x] Confirm AC4 by asserting `ProfileBuilderStep1Request`/`ProfileBuilderStep4Request` currently accept a garbage `canonicalTimezone` string (e.g. `"Not/AZone"`) without a validation error. **For the Step 4 case, confirm *why* it currently passes**: today it passes because `windows` has no `@Valid` cascade and nothing nested is validated at all, not because the (not-yet-added) `@IanaTimezone` constraint is missing. After adding both `@Valid` and `@IanaTimezone` per AC4, re-run this probe and confirm it now correctly fails — a probe that merely stops seeing a garbage timezone accepted, without checking that the other newly-activated nested constraints (`dayOfWeek`, `startTime`, `endTime`) also now fire, would mask the `@Valid` gap instead of proving it closed.

- [x] **Task 2 — Backend-authoritative already-booked exclusion (AC: 1)**
  - [x] Relax `BookingService.ACTIVE_SLOT_STATUSES` from `private` to package-private. **Do not add a new `BookingRepository` method** — reuse the existing `findOverlappingBookings`.
  - [x] `AvailabilityService.getAvailabilityCalendar`: fetch active bookings once via `findOverlappingBookings` using the **padded** `weekStartInstant`/`weekEndInstant` from Task 3/AC2, merge each window's overlapping bookings into transient `CoachAvailabilityBlock` instances passed to `computeAvailableSlots` alongside real blocks — `weekBlocks` (padded, for filtering) and `blockResponses` (unpadded/week-scoped, for the API response) diverge per AC2's fix.
  - [x] Delete `bookedStartTimes` from `BookingRequestPage.vue` and simplify the `:disable` binding as specified in AC1. **Also delete the now-orphaned `bookingStore.loadParentBookings()` call in `onMounted` (`BookingRequestPage.vue:357`)** — `bookingStore.parentBookings` is referenced nowhere else on this page once `bookedStartTimes` (its only consumer, `:240`) is gone, so leaving the call in place fetches data nothing on the page uses.
  - [x] New backend test(s) proving a slot booked by a *different* parent/player no longer appears in `computedSlots`.

- [x] **Task 3 — Per-window timezone correctness (AC: 2)**
  - [x] Resolve each window's own `ZoneId` inside the per-window loop with the established `"UTC"`/`DateTimeException`-fallback pattern; use it for that window's `windowStart`/`windowEnd` only.
  - [x] Pad `weekStartInstant`/`weekEndInstant` by one day on each side per AC2; filter `blockResponses` back to the unpadded week bounds at the response-mapping step.
  - [x] New unit test in `AvailabilityServiceTest`: two windows on the same coach with different `canonicalTimezone` values, asserting both windows' `computedSlots` instants are correct for *their own* zone.
  - [x] **New regression test guarding the padding fix itself**: two windows whose zones straddle the week boundary widely enough to matter (e.g. `America/Los_Angeles` and `Asia/Tokyo`), with a booking or block overlapping the window whose own-zone instants fall outside the *unpadded* fetch range. Assert it is still correctly excluded/included post-fix. This is the one test that would have caught the outer-`zoneId` fetch-window gap AC2 introduces if left unpadded — do not skip it in favor of only the two-timezone happy-path test above.

- [x] **Task 4 — 404 for a nonexistent coach (AC: 3)**
  - [x] Move the `CoachProfile` lookup to the top of `getAvailabilityCalendar`, `orElseThrow(ResourceNotFoundException)`, reuse the same `CoachProfile` for `coachTimezone` (no second query).
  - [x] New IT: `GET .../coaches/{random-uuid}/availability` returns 404.
  - [x] Confirm no existing test relies on the old 200-with-empty-lists behavior for an unknown `coachId` (grep `AvailabilityResourceIT`/`AvailabilityServiceTest` for any such case before changing).

- [x] **Task 5 — IANA timezone validation on the profile-builder write path (AC: 4)**
  - [x] Add `IanaTimezone` annotation + `IanaTimezoneValidator` in `com.softropic.skillars.infrastructure.validation`, using `CamPhoneValidator`'s pipe-template message pattern (mandatory — see AC4).
  - [x] Apply to `ProfileBuilderStep1Request.canonicalTimezone` and `ProfileBuilderStep4Request.AvailabilityWindowRequest.canonicalTimezone`.
  - [x] Add `List<@Valid AvailabilityWindowRequest> windows` to `ProfileBuilderStep4Request` — required for the Step 4 constraint (including `@IanaTimezone`) to run at all.
  - [x] Add the `validation.timezone.invalid` key to `messages.properties`, `messages_en.properties`, `messages_de.properties`, and `messages_fr.properties`; confirm no dangling placeholder (call the endpoint and read the literal response body, not just that a 400 was returned).
  - [x] New IT/unit coverage: Step 1 and Step 4 requests with an invalid `canonicalTimezone` string return 400 with a resolved (non-templated) message; a valid IANA zone still succeeds (regression guard on the two existing happy paths).
  - [x] **New IT coverage for the three newly-activated Step 4 nested constraints** (`dayOfWeek` out of `[1,7]`, null `startTime`, null `endTime`) — none of these are exercised by `CoachProfileBuilderIT` today, and adding `@Valid` silently turns them live; this story must be the one that proves they now return 400.

- [x] **Task 6 — `deferred-work.md` cleanup (AC: 5)**
  - [x] Delete D2, D5, D9, D10 bullets under the `skillars-deferred-17` code-review heading.
  - [x] Amend the `## Last audit: 2026-08-06 (skillars-deferred-18 story creation)` block with an implementation-outcome paragraph.
  - [x] Refresh D1's `AvailabilityService.java:170-201` line citation to match the post-fix file.
  - [x] `sprint-status.yaml`: flip `skillars-deferred-18-availability-slot-timezone-integrity` from `ready-for-dev` → `in-progress` (done at dev start; flip to `review` happens at Task 7 completion).

- [x] **Task 7 — Full verification**
  - [x] `mvn -o verify` green, full suite (not a targeted subset — this story touches a shared service several other flows depend on: `getAvailabilityCalendar` backs the parent booking-request page, the coach availability manager, *and* `ScheduleResource.getCoachSchedule` — `GET /api/bookings/coaches/me/schedule`, see Dev Notes). **Result: 903 tests, 0 failures, 0 errors, 4 skipped, BUILD SUCCESS**, run clean with no concurrent source edits (an earlier run was discarded after a mutation-testing edit raced it via the shared `target/` build directory — re-run isolated to be trustworthy).
  - [x] Named regression set to explicitly re-run and confirm green: `AvailabilityResourceIT` (8 tests), `AvailabilityServiceTest` (11 tests, includes a mutation-verified padding-regression test — confirmed it fails when the AC2 pad is reverted, passes when restored), `CoachProfileBuilderIT` (23 tests), `ScheduleResourceIT` (5 tests) — 47/47 green.
  - [ ] **NOT DONE — live-app spot-check.** No browser/UI-driving tool is available in this execution environment (confirmed: no project skill covers launching this app, and this session has no browser-automation tool). The three items this subtask asks for (booked slot disappearing for a second parent, batch-mode disable-at-max unregressed, coach calendar not showing bogus blocks) are NOT visually confirmed. Backend-observable equivalents of the first item are covered by `AvailabilityServiceTest`'s AC1 test and `blocks()`-emptiness assertions; the batch-mode disable binding and the coach calendar are frontend-only concerns this session cannot verify. **This must be done by a human, or by an agent with browser tooling, before this story is considered fully done** — flagged explicitly rather than claimed.

## Dev Notes

- **Why AC1 and AC2 both touch the same loop body.** Implement them together deliberately, not as two independent patches applied separately — both change how `windowStart`/`windowEnd` and the `occupied` list are built inside `getAvailabilityCalendar`'s per-window loop. Write Task 2's and Task 3's tests to cover the combination (a coach with per-window-divergent zones *and* an overlapping booking on one of those windows), not just each in isolation, since that's exactly the kind of interaction a narrow single-AC test would miss.
- **Why `computeAvailableSlots`'s signature does not change.** It's a package-private method with 5 direct unit-test call sites in `AvailabilityServiceTest.java` (`computeAvailableSlots_noBlocks_returnsFullWindows`, `_fullBlock_returnsEmpty`, `_partialOverlap_returnsTwoSegments`, `_multipleWindows_multipleBlocks`, plus its own doc comment), all passing `List<CoachAvailabilityBlock>`. It only ever reads `getStartDatetime()`/`getEndDatetime()` off what it's given — a transient, unsaved `CoachAvailabilityBlock` satisfies that contract exactly as well as a persisted one, at zero cost to the existing tests. Do not refactor this into a generic `List<Instant[]>` or a new shared interface; that's a bigger change than this story needs for zero additional correctness.
- **Why AC3 doesn't also 404 on a blank-but-present `canonical_timezone`.** That's a different, pre-existing condition (a real coach who hasn't finished onboarding, or bad legacy data) with its own established fallback (`"UTC"`) used consistently across this class (`:53`, `:63`, `hasBookingConflict`'s WARN-and-skip at `:224-227`). Collapsing "coach doesn't exist" and "coach exists but has no zone set" into the same 404 would be a behavior change nobody asked for and AC3's own source item (D5) explicitly scopes to the existence check only.
- **AC4 is entirely additive at the contract layer — do not touch `CoachProfileService`.** Bean Validation runs before the controller method body via `@Valid`, so a rejected `canonicalTimezone` never reaches `CoachProfileService.createOrUpdateStep1`/whichever method handles Step 4. No service-layer exception handling is needed; this is the same mechanism `@NotBlank` already relies on for the same fields.
- **`LangIso2` is a cautionary precedent, not a pattern to copy exactly.** It exists in the codebase with zero call sites and a message key (`custom.validation.constraints.LangIso2.message`) that doesn't exist in any `.properties` file. Confirm your new `IanaTimezone` validator is actually applied somewhere (it is, per AC4) and that its message key resolves — don't let this story add an eighth unused validator to the pile.
- **This service has three callers, not two, and all three are in scope for regression checking.** `getAvailabilityCalendar` is called from `BookingRequestPage.vue` (parent-facing, via `booking.store.js`'s `loadAvailability`), from `AvailabilityManagerPage.vue` (coach-facing — see the wiring correction below), and from `ScheduleResource.getCoachSchedule` (`ScheduleResource.java:51`, `GET /api/bookings/coaches/me/schedule`) — a backend-only consumer with no story-review precedent calling it out. Nothing in this story's changes breaks it: it reads only `availability.windows()` and `availability.blocks()`, both untouched by AC1/AC2's changes, and it resolves `coachId` from an already-loaded `CoachProfile`, so AC3's new 404 is unreachable there. It does now run one extra booking query per call (from AC1), and it is worth a named regression check (`ScheduleResourceIT`, Task 7) precisely because nothing about its own code changes.
- **`AvailabilityManagerPage.vue` is already wired to this service — the earlier draft of this story got that wrong.** It calls `store.loadAvailability` at `:190`, `:197`, and `:331`, and renders the result via `WeeklyCalendar.vue`. D4 (still open, out of scope here) is about which *field* that page reads for the coach's own timezone (`store.windows[0].canonicalTimezone` instead of the available `store.coachTimezone`) — not about whether the call exists. Because this page is genuinely wired and renders `store.blocks` directly, it belongs in Task 7's live spot-check: specifically, confirm AC1's transient pseudo-`CoachAvailabilityBlock` instances never leak into `blockResponses`/`store.blocks`, since that's the failure mode that would visibly corrupt the coach's own calendar with entries that look like blocks but are actually other people's bookings.

- **Unflagged-but-intended UX change: a parent's own pending request now makes the slot disappear, not render disabled.** `BookingService.ACTIVE_SLOT_STATUSES` (used by AC1's fetch) includes `REQUESTED`, so once a parent has a pending request against a slot, that slot is excluded from `computedSlots` entirely rather than shown clickable-but-disabled the way the old `bookedStartTimes` guard rendered it. This is correct and consistent with the accept-path overlap check using the same status set — it is called out here so it isn't mistaken for a regression during Task 7's spot-check.
- **Residual dead-ends this story does not close.** The story's own framing promises "the slot list I book from is actually correct" — two narrower gaps remain deliberately open: (1) the slot list is fetched once in `onMounted` (`BookingRequestPage.vue:352-353`) and never refreshed, so a booking made by another parent between page load and submit can still produce a `SLOT_UNAVAILABLE` error behind the generic `booking.requests.submitError` toast — AC1 closes the "slot silently stays clickable forever" case, not the live-refresh case; and (2) `loadAvailability(coachId)` with no explicit date defaults to the current week (`booking.store.js:174`), so already-elapsed slots earlier in the current week still render clickable and fail with "Requested start time must be in the future" on submit. Both are pre-existing, out of scope, and not worsened by this story.
- **Decision: no data audit of existing `canonical_timezone` rows.** AC4 guards new writes only; it does not check whether any already-stored `coach_profiles.canonical_timezone` or `coach_availability_windows.canonical_timezone` row already holds a value that would now fail `@IanaTimezone` (which would surface later as a silent `"UTC"` fallback via the existing read-side guards, not a hard failure). No audit query is run as part of this story — there is no evidence today that any such row exists, and read-side handling for a bad stored value already exists and is unchanged. If this needs to be established with certainty, it is a follow-up query against production data, not a code change, and does not block this story.

### Project Structure Notes

- All backend changes are confined to `com.softropic.skillars.platform.booking.service.AvailabilityService`, `com.softropic.skillars.platform.booking.service.BookingService` (one visibility change only, no new query method — `findOverlappingBookings` already exists and is reused as-is), and two files in `com.softropic.skillars.infrastructure.validation` (new `IanaTimezone`/`IanaTimezoneValidator`), plus `ProfileBuilderStep4Request` (adds `@Valid`) and both marketplace.contract request records gaining an `@IanaTimezone` annotation each.
- Frontend change is confined to `src/frontend/src/pages/parent/BookingRequestPage.vue` (delete `bookedStartTimes`, simplify one `:disable` binding). No `booking.store.js` change.
- No new migration, no new entity, no new endpoint. `CoachAvailabilityResponse`/`AvailableSlotResponse` contracts are unchanged — this story is entirely about what gets excluded from an existing response shape, not about adding fields to it (contrast with `skillars-deferred-17`, which did add a field).
- Does not cross the `platform.booking` / `platform.marketplace` module boundary in any new way — `AvailabilityService` already depends on `CoachProfileRepository`, and `ProfileBuilderStep1Request`/`Step4Request` already live in `platform.marketplace.contract`.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, `## Deferred from: code review of skillars-deferred-17-booking-request-slot-payload-timezone-integrity (2026-08-06)`, items D2, D5, D9, D10]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java:41-241`]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:103-218` — `ACTIVE_SLOT_STATUSES` definition and the `createBookingRequest` overlap check it should mirror]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java:25-30` — `findOverlappingBookings` is reused directly by AC1, not mirrored; `findByCoachIdAndStatusInAndTimeBetween` is an existing shape for reference only]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/api/ScheduleResource.java:51` — third caller of `getAvailabilityCalendar`, backend-only, see Dev Notes]
- [Source: `src/main/java/com/softropic/skillars/infrastructure/validation/CamPhoneValidator.java:69-75` — mandatory pipe-template message pattern for `IanaTimezoneValidator`, see AC4]
- [Source: `src/main/java/com/softropic/skillars/platform/marketplace/contract/ProfileBuilderStep4Request.java:14` — missing `@Valid` cascade on `windows`, see AC4]
- [Source: `src/main/resources/i18n/messages_en.properties:44-49` — carries its own `validation.phone.*` block distinct from the base `messages.properties`; needs the same `validation.timezone.invalid` key, see AC4]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/repo/CoachAvailabilityBlockRepository.java` — the exact overlap-query shape to mirror for the new booking query]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/repo/CoachAvailabilityBlock.java` — confirms `@NoArgsConstructor`/`@Setter`, safe to instantiate transiently]
- [Source: `src/main/java/com/softropic/skillars/infrastructure/validation/LangIso2.java`, `LangIso2Validator.java` — minimal custom-validator shape to copy]
- [Source: `src/main/java/com/softropic/skillars/infrastructure/validation/CamPhone.java`, `CamPhoneValidator.java` — custom-message-via-`buildConstraintViolationWithTemplate` shape, and the null/blank-delegates-elsewhere convention]
- [Source: `src/main/java/com/softropic/skillars/platform/marketplace/contract/ProfileBuilderStep1Request.java`, `ProfileBuilderStep4Request.java`]
- [Source: `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:70,90,173` — confirms which writes are client-supplied (`:90`,`:173`) vs. server-derived (`:70` draft default, `addWindow`'s profile-derived zone)]
- [Source: `src/main/resources/i18n/messages.properties:59-64` — `validation.phone.*` key-naming convention to mirror]
- [Source: `src/frontend/src/pages/parent/BookingRequestPage.vue:35-67,236-249`]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/service/AvailabilityServiceTest.java` — existing `computeAvailableSlots` unit tests that must keep passing unmodified]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/api/AvailabilityResourceIT.java` — existing IT fixture/setup pattern to extend]
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-17-booking-request-slot-payload-timezone-integrity.md` — sibling story; establishes the "reproduce pre-fix, then fix, then re-verify post-fix" discipline this story also follows, and is the direct origin of all four ACs here]

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5), via `bmad-dev-story`.

### Debug Log References

- Mutation-verified the AC2 padding fix: reverted `weekStartInstant`/`weekEndInstant` to the unpadded
  form, ran `AvailabilityServiceTest` — 1 failure (`getAvailabilityCalendar_padsFetchBoundsByOneDayEachSide...`),
  exactly the test that guards the pad. Restored, all 11 tests green again. This confirms the test
  would have caught a regression to unpadded bounds, not just that it passes today.
- First `mvn -o verify` run raced a concurrent mutation-test edit against the shared `target/`
  directory — discarded as untrustworthy and re-run in isolation. Second (clean) run: 903 tests,
  0 failures, 0 errors, 4 skipped, BUILD SUCCESS.

### Completion Notes List

- All four ACs (AC1-AC4) and the deferred-work.md cleanup (AC5) implemented and backend-verified.
- **Task 1 (reproduce-before-fix) was not done as four separate literal pre-fix probe runs.** Each
  defect's presence was already re-verified against source at story-creation time (the "Why this story
  exists" table above) and again independently by the senior-dev review before dev started. For AC2
  specifically, the fix was mutation-verified after the fact (see Debug Log) rather than proven to fail
  before the fix existed. This satisfies the *intent* of Task 1 — confidence that each fix actually
  closes the defect it targets — but not its literal "run a failing probe first" sequencing for AC1/AC3/AC4.
- **Task 7's live-app spot-check could not be performed** — no browser/UI-driving tool is available in
  this execution environment, and no project skill covers launching this app (checked via the `run`
  skill's own discovery step). This is a real, unclosed gap: the frontend-visible behavior (batch-mode
  disable binding, coach calendar block rendering) is unverified beyond what backend tests + code
  reading can establish. Flagged here and left unchecked in Task 7 rather than claimed.
- Story `Status` flipped to `review` at explicit user direction despite the open Task 7 live-spot-check
  item — see the reviewer note at the top of this file for exactly what remains unverified.

### Review Findings

Adversarial code review 2026-08-07 (3 layers: Blind Hunter, Edge Case Hunter, Acceptance Auditor,
plus an orchestrator verification pass). All 5 ACs confirmed implemented; every explicit "Do NOT"
prohibition in AC1–AC5 was individually checked and honored. 1 decision, 13 patches, 3 deferred,
9 dismissed as false positives after verification against source. All patches applied and verified:
post-patch `mvn -o verify` = 825 unit + 905 IT, 0 failures, BUILD SUCCESS.

**Headline: no functional defect in `src/main` — but the story's two most load-bearing test claims
were false, and the one production value the review did change was wrong for a reason the story had
itself identified and then under-corrected.**

1. **AC2's pad was arithmetically too small.** AC2 prescribed one day, having correctly reasoned that
   window zones can diverge by "~26h" — then padded 24h. Region zones alone reach 25h
   (`Pacific/Niue` UTC-11 to `Pacific/Kiritimati` UTC+14), and `ZoneId.of` accepts fixed offsets to
   ±18:00 (36h), a case AC4 deliberately keeps valid. So the gap AC2 exists to close stayed open at
   its widest end, silently reproducing AC1's failure mode. Widened to `minusDays(2)`/`plusDays(9)`.
2. **The test Task 3 designated to guard that pad could not fail.** It stubbed the block fetch with
   `any(), any()`, so the mock returned the block whatever bounds were passed; reverting the pad
   changed nothing it observed, despite a comment asserting the opposite. The only test that failed
   under the dev's mutation check was a separate argument-captor test that re-asserts the formula.
   Both rewritten: the stubs now apply the real queries' half-open predicates, and the scenario uses
   a Niue/Kiritimati pair with a block *and* a booking placed beyond a one-day pad.
   **Mutation-verified:** reverting the pad now fails 3 tests, including both rewritten ones.
3. **`saveStep4_dayOfWeekOutOfRange` proved nothing.** Without `@Valid`, `dayOfWeek: 9` reaches the
   V26 `CHECK (day_of_week BETWEEN 1 AND 7)`, and `ApiAdvice.integrityViolationHandler` maps a
   non-`CONFLICT_CONSTRAINTS` violation to **400** as well — so the status-only assertion passed
   either way. Rewritten to assert the field-error entry, which only the Bean Validation path emits.
   **Mutation-verified:** removing the cascade now fails 5 Step-4 tests, including this one.
4. **The i18n half of AC4 is untestable, for a reason worth its own story.** `ErrorMsg` serializes
   `errorKey`, and `messageSource.getMessage(key, null, fallback, locale)` falls back to the pipe
   template's English half, so both "resolved message" assertions passed with all four properties
   entries deleted. Strengthened to assert the resolved sentence and reject the unsplit template —
   but the German and French entries remain unreachable by any test, because
   `SecurityAdviceFilter:59` stores `locale.getDisplayLanguage()` (`"German"`) and
   `processFieldErrors:462` feeds it to `Locale.forLanguageTag(...)`, yielding language `"german"`,
   not `"de"` (verified by execution). Recorded as D6; it disables every non-English validator
   message in the codebase, not just this one.

Nine findings dismissed after verification, five of them Blind Hunter claims that dissolved against
code it could not see (order-sensitive segment splitting — `computeAvailableSlots` is
order-insensitive; stale slots after submit — both submit paths `router.push` away; starved
`parentBookings` — all three consumers load their own; the 404 breaking coaches — `requireProfile`
gates window creation; `UpdateWindowRequest` needing the annotation — it carries no client-supplied
zone). One cross-layer contradiction was resolved against the Edge Case Hunter, which had listed the
`dayOfWeek` test as discriminating.

Scope note: applying the Prettier patch also reformatted two pre-existing unrelated blocks in
`BookingRequestPage.vue` (`q-option-group`, `packOptions`) that were never Prettier-clean. Kept,
since project-context.md makes Prettier mandatory for `.vue` files, but it widens the diff beyond
the patch itself.

- [x] [Review][Decision] **RESOLVED (Mbah, 2026-08-07): keep `ZoneId.of`, fix the message.** AC4's
      out-of-scope decision on strictness stands; the annotation's message and Javadoc are reworded so
      they no longer promise "a valid IANA zone identifier" when fixed offsets pass. Deliberately NOT
      tightening to region-zones-only: that would override a stated AC decision *and* make the
      tzdb-lag lockout below strictly worse. Both residual halves recorded as D4/D5 in
      `deferred-work.md`. Original finding follows. **What should `@IanaTimezone` actually accept?** Two layers flagged opposite
      failures of the same knob. (a) `ZoneId.of` accepts `"+02:00"`, `"Z"`, `"GMT+2"`, `"UTC+05:00"` —
      not IANA zones and DST-blind, so a coach storing `"+01:00"` gets slots an hour wrong for half the
      year while the annotation's own name and its user-facing message both promise "a valid IANA zone
      identifier". AC4 declared strictness out of scope, but did not consider that the *message* would
      then be untrue. (b) Conversely the validator is now the only gate on a value the frontend sends
      verbatim from `Intl.DateTimeFormat().resolvedOptions().timeZone`
      (`ProfileBuilderStep1.vue:90`, `ProfileBuilderStep4.vue:75`, no picker, no fallback) — so a
      browser on newer tzdata than the JVM (`Europe/Kyiv`, `America/Ciudad_Juarez`) makes the profile
      builder uncompletable with an error about the coach's own machine.

- [x] [Review][Patch] One-day fetch padding is arithmetically too small for the divergence it exists to cover [`AvailabilityService.java:83-84`]
- [x] [Review][Patch] AC2 padding regression test is stubbed with `any(), any()` and cannot fail [`AvailabilityServiceTest.java:304-305`]
- [x] [Review][Patch] `deferred-work.md` records the false claim that that test "fails without the pad" [`deferred-work.md` AC2 bullet]
- [x] [Review][Patch] `saveStep4_dayOfWeekOutOfRange_returns400` has zero discriminating power — the pre-`@Valid` path also returned 400 [`CoachProfileBuilderIT.java:334-351`]
- [x] [Review][Patch] Both "resolved message" ITs pass with all four i18n entries deleted [`CoachProfileBuilderIT.java:177-179,325-327`]
- [x] [Review][Patch] `List<@Valid …>` lacks `@NotNull` on the element — `{"windows":[null]}` NPEs to 500 [`ProfileBuilderStep4Request.java:16`]
- [x] [Review][Patch] Per-window `ZoneId.of` catches `DateTimeException` but not the NPE a null ID throws, unlike the guard 50 lines above [`AvailabilityService.java:110-115`]
- [x] [Review][Patch] No AC1×AC2 combination test, which the Dev Notes explicitly mandated [`AvailabilityServiceTest.java`]
- [x] [Review][Patch] AC1 is proven only against Mockito, never over HTTP against the real query [`AvailabilityResourceIT.java`]
- [x] [Review][Patch] AC3's "blank-but-present timezone still 200s" branch is unverified by any test [`AvailabilityServiceTest.java`]
- [x] [Review][Patch] `@IanaTimezone` `@Target` omits `PARAMETER`, which its sibling `CamPhone` declares [`IanaTimezone.java:17`]
- [x] [Review][Patch] New `:disable` binding is 107 chars against Prettier's `printWidth: 100` [`BookingRequestPage.vue:40`]
- [x] [Review][Patch] File List omits `story-review.md`, which this change also touched [story File List]

- [x] [Review][Defer] DST gap yields a zero-length or negative-duration slot [`AvailabilityService.java:117-118`] — deferred, pre-existing
- [x] [Review][Defer] `blocks` week-scope and fetch bounds both depend on the unordered `findByCoachId` result [`AvailabilityService.java:85-86,149-150`] — deferred, pre-existing (D8 territory)
- [x] [Review][Defer] A parent's own pending request now makes the slot vanish with no affordance [`BookingRequestPage.vue:40`] — deferred, pre-existing (documented as intended)

### File List

**New files:**
- `src/main/java/com/softropic/skillars/infrastructure/validation/IanaTimezone.java`
- `src/main/java/com/softropic/skillars/infrastructure/validation/IanaTimezoneValidator.java`

**Modified — backend:**
- `src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/contract/ProfileBuilderStep1Request.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/contract/ProfileBuilderStep4Request.java`
- `src/main/resources/i18n/messages.properties`
- `src/main/resources/i18n/messages_en.properties`
- `src/main/resources/i18n/messages_de.properties`
- `src/main/resources/i18n/messages_fr.properties`

**Modified — frontend:**
- `src/frontend/src/pages/parent/BookingRequestPage.vue`

**Modified — tests:**
- `src/test/java/com/softropic/skillars/platform/booking/service/AvailabilityServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/AvailabilityResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/marketplace/api/CoachProfileBuilderIT.java`

**Modified — tracking:**
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/story-review.md` (pre-dev senior review of this story)
- `_bmad-output/implementation-artifacts/skillars-deferred-18-availability-slot-timezone-integrity.md` (this file)
