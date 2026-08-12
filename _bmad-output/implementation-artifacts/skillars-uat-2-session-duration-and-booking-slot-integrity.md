# Story UAT.2: Session Duration & Booking Slot Integrity

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **Read the "Regression surface" section in Dev Notes before writing any code.** This story
> introduces the first duration constraint the booking package has ever had. Eleven existing test
> files construct bookings with arbitrary durations, and every one of them that goes through
> `createBookingRequest`, `createBatch` or `requestReschedule` will start failing. That is the
> correct outcome, not a bug in your change — but if you discover it one test class at a time you
> will burn the story's budget on it. Plan for it up front.

## Story

As a parent booking a session for my child during UAT,
I want a slot in the coach's calendar to be one session long rather than the coach's entire working day,
so that one click books one lesson, consumes one credit, and leaves the rest of the coach's day bookable.

### Why this story exists

Source: `_bmad-output/implementation-artifacts/uat-readiness-priorities.md` (2026-08-09 ranking against
commit `a170e69`), which measures the open `deferred-work.md` backlog against one goal — deploy to a
VPS, create a player/parent/coach/admin account, log in, search a coach, pay, book.

`skillars-uat-1` (done, merged as `8a76652`) took every item on that list needing **no product
decision**. It explicitly named P0-5 as story #2: *"The single highest-impact functional defect on the
journey, and the one the first UAT booking will hit."* This is that story, plus the booking-UX item in
the same code path and three ops fixes small enough that opening the files separately would cost more
than the fixes themselves.

**The product decision P0-5 was blocked on is resolved (Mbah, 2026-08-10):** *the default session
length is one hour, system-wide, and a coach can override it.* AC1 implements exactly that shape — a
platform-config default plus a nullable per-coach column — and the nullability is load-bearing; see
AC1 for why `DEFAULT 60` would be wrong.

Every claim below was verified by direct read of the working tree at `8a76652` on 2026-08-10.

| AC | Source item | Verified current state (2026-08-10, `8a76652`) |
|---|---|---|
| AC1–AC4 | **P0-5** — `deferred-17` D1 | **CONFIRMED, and broader than D1 records.** No session-duration concept exists anywhere in the booking package. Grepping `durationMinutes` / `session_duration` / `sessionDuration` across `src/main/java` returns hits only in `platform.session` (drill blocks — `SessionBlockRequest.durationMinutes`, unrelated) and `platform.development` (SLU formula). `AvailabilityService.computeAvailableSlots:275-306` seeds its segment list with `{windowStart, windowEnd}` and returns whole window-minus-block segments; `BookingRequestPage.vue:35-63` renders **one clickable row per segment** and `submit():296-314` posts that segment's `startDatetime`/`endDatetime` verbatim. Backend validation is only future-start (`BookingService.java:174-177`), end-after-start (`:178-182`), and inside-a-window (`:184-189`). So a coach with a 09:00–17:00 window and no blocks presents exactly one row, and one click books an eight-hour session — one pack credit, and the coach's whole day locked out via `findOverlappingBookings`. **Three write paths, not one:** `BookingBatchService.createBatch:107-114` checks only future/end-after-start and **never calls `isSlotWithinAvailabilityWindow` at all** (`@Size(max = 10)` on `CreateBatchRequest:16` multiplies the damage by ten), and `RescheduleService:63-67` checks only future/end-after-start too. D1 named the first two; the reschedule path is a third, previously unrecorded. |
| AC5 | **P1 #1** — `deferred-18` D3 | **CONFIRMED.** `BookingService.ACTIVE_SLOT_STATUSES:120-121` includes `REQUESTED`, `PAYMENT_PENDING` and `PAUSED`, and `AvailabilityService:190-198` merges every matching booking into `occupied` as a transient pseudo-block. So after a parent submits, their own pending request is carved out of `computedSlots` and the slot is **simply absent** on the next visit — where before `deferred-18` it rendered as a disabled row. `BookingRequestPage.vue` loads only availability (`:334`), so it has no booking data to render a "you already requested this" state from. `bookingStore.loadParentBookings()` and `getParentBookings()` already exist (`booking.store.js:289-300`) and `BookingResponse` already carries `coachId`, `requestedStartTime`, `requestedEndTime` and `status` — the data is one call away. D3 called restoring this "a product decision about the booking-request page"; **AC2 is what makes it tractable**, because a fixed-length booking now occupies exactly one slot instead of splitting a segment into two odd fragments. |
| AC6 | **P2 #2, #3, #5** | **ALL THREE CONFIRMED.** (a) `provision.sh:160` mounts the Hetzner Volume at `${DEPLOY_ROOT}/data` — and **only** there. `docker-compose.yml:113` binds `/opt/skillars/traefik/acme.json`, which is on the **root disk**, so a server rebuild loses every TLS cert and Let's Encrypt rate limits make reissuance slow. (b) `docker-compose.yml:92` gives redis a Docker **named volume** (`redis-data`, declared `:293-294`) while postgres, prometheus, loki, tempo and grafana all bind under `/opt/skillars/data/…` — redis is the sole stateful service not on the persistent volume. (c) `ci.yml:75` pushes `ghcr.io/…:sha-${short}` and nothing else, so every UAT redeploy needs the exact SHA typed in. `.github/actions/docker-build/action.yml:19` documents `tags` as newline-separated — adding a second tag is two lines. |
| AC7 | Ledger hygiene | `deferred-17` D1, `deferred-18` D3 and the three P2 rows are closed by this story and must be recorded as such, following the `deferred-13`/`-14`/`-16`/`uat-1` convention of a dated one-line note rather than a silent deletion. |

### Items examined and NOT folded in

Recorded so the next pass does not re-litigate them:

- **P0-2 — a player account cannot book anything.** Unchanged from `uat-1`'s assessment: `BookingResource:36` and `BookingBatchResource:40` are both `@PreAuthorize(HAS_PARENT_ROLE)`. Still a product decision ("register + browse" vs. player self-booking), still untouched. Note this story makes self-booking *cheaper* if you choose it later — the three enforcement points AC3/AC4 add are role-agnostic.
- **P0-4 — a coach cannot subscribe through the UI.** Still blocked on the `payment.stripe_customers` re-key. `deferred-11` D3 folds into it. Untouched.
- **P1 #2 / #3 — payment-integrity races** (`deferred-12` D2, `deferred-15` story-creation D1). Deliberately after the first round of UAT payment testing, per the priorities doc's own sequencing. Untouched.
- **P1 #7 / #8 — the i18n pair** (`deferred-17` D3 `formatSlot` hardcodes `'en'`; `deferred-18` D6 `ApiAdvice` can never resolve a non-English bundle). AC5 edits `BookingRequestPage.vue`, which contains one of #7's call sites (`:265`, `:275`) — **do not opportunistically fix it there.** #7 is a 4+-page sweep and #8 needs all three `ApiAdvice.chosenLang` sites plus `VideoApiAdvice:155`; a half-sweep leaves the codebase in a worse state than either doing it properly or leaving it alone. `uat-1` recorded the same decision. One i18n story or none.
- **P2 #4 — no backup retention policy.** Cost, not correctness. Not on the journey.
- **`RescheduleService` does not check the availability window.** Found while scoping AC3. `RescheduleService:63-67` validates only future-start and end-after-start, so a reschedule can move a session outside the coach's availability entirely — a distinct pre-existing defect from the duration one. **AC3 adds duration enforcement there but deliberately not the window check**, because adding it changes reschedule semantics (a coach who narrows their availability after accepting would retroactively block legitimate reschedules) and needs its own regression pass across `RescheduleServiceTest` and `RescheduleResourceIT`. Record it as a new deferred item under AC7.
- **`BookingDuplicationService.duplicateNextWeek`** (`:50-51`, `:64-65`) copies the original's start/end +7 days, so it inherits whatever duration that booking had. **Deliberately exempt from AC3's check.** It duplicates a booking that was already valid; if a coach changes their session length afterwards, rejecting the duplicate would retroactively invalidate history rather than prevent a bad booking. Do not add the check here. **Same reasoning governs the reschedule path** — see AC3, where the check is same-as-original rather than same-as-coach's-current-length for exactly this reason. The two must not diverge: a rule that lets you duplicate a legacy booking but not move it would be incoherent.
- **Everything in P3.** Unchanged.

## Acceptance Criteria

### AC1 — Session length exists: a live platform default with a per-coach override

**New Flyway migration `src/main/resources/db/migration/V93__session_duration.sql`.** `V92__seed_admin_authorities.sql` is the current highest — verify with `ls src/main/resources/db/migration/ | sort -V | tail -1` before writing, since another branch may have landed one.

Two changes in the one migration:

```sql
-- Per-coach override. NULL is meaningful: it means "inherit the platform default".
ALTER TABLE marketplace.coach_pricing
    ADD COLUMN session_duration_minutes INT NULL,
    ADD CONSTRAINT chk_coach_pricing_session_duration
        CHECK (session_duration_minutes IS NULL
               OR (session_duration_minutes BETWEEN 15 AND 240));

-- Platform-wide default. 603 is the next free id: 601 is V90's
-- booking.payment_pending_sweep_grace_minutes and 602 is V91's
-- platform.messaging.moderation_orphan_grace_minutes.
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
VALUES (603, 'booking.session.defaultDurationMinutes', '60', 'LONG',
        'Default coaching session length in minutes; a coach may override it on their pricing row',
        NOW())
ON CONFLICT (key) DO NOTHING;
```

**The id must be 603, and `ON CONFLICT (key)` does not protect you if it is not.** `platform_config.id`
is `PRIMARY KEY` with no sequence (`V20:8`) — ids are hand-assigned — and the conflict target here is
`key`, a *different* unique constraint. An id collision therefore raises a PK violation that the
`ON CONFLICT (key)` clause never sees, and Flyway fails the migration on every database that has run
`V91` — which is every UAT box. Confirm with `SELECT max(id) FROM main.platform_config;` before
committing (currently 602) and take the next free value.

**The column must be `NULL`-able, not `NOT NULL DEFAULT 60`.** This is the whole point of the decision
being "a system-wide default that a coach can override" rather than "a per-coach value pre-filled with
60". With `DEFAULT 60`, every existing row is stamped with `60` at migration time, the platform key
becomes decorative, and an admin who later changes `booking.session.defaultDurationMinutes` to 45
changes nothing for any coach that already exists — the default would be frozen at write time instead
of being read live. `NULL` keeps the platform value authoritative for every coach who has not made a
deliberate choice.

`ON CONFLICT (key) DO NOTHING` mirrors what `V92` had to learn the hard way: `platform_config.key` is
`UNIQUE` (`V20:9`), and a UAT database may already carry a hand-inserted row.

**Entity + resolver:**

- `CoachPricing` (`marketplace/repo/CoachPricing.java`) gains `@Column(name = "session_duration_minutes") private Integer sessionDurationMinutes;` — boxed `Integer`, not `int`, so `null` round-trips.
- **New `SessionDurationResolver` in `com.softropic.skillars.platform.booking.service`.** One class, one public method:

  ```java
  public Duration resolve(UUID coachId)
  ```

  Reads `coachPricingRepository.findByCoachId(coachId)`, maps a non-null `sessionDurationMinutes` to
  `Duration.ofMinutes(...)`, and otherwise falls back to
  `configService.getBoundedLong("booking.session.defaultDurationMinutes", 60, 15, 240)`.

  **Both fallbacks matter.** A coach with no `coach_pricing` row at all is reachable today —
  `createBookingRequest` accepts `PENDING_REVIEW` profiles (`BookingService.java:164-168`) and Step 3
  is where pricing is first written (`CoachProfileService.saveStep3:195-203`), so a coach can be
  bookable before pricing exists. Use `getBoundedLong`, not `getLong`: it already exists precisely for
  keys where "a syntactically valid but absurd number would silently corrupt business-rule behaviour"
  (`ConfigService.java:88-100`), and a `0` or negative duration here would make `computeAvailableSlots`
  loop forever in AC2.

  **It lives in `booking`, not `marketplace`.** It is a booking rule that happens to read a marketplace
  table, and `BookingService` already injects `CoachPricingRepository` (`:114`) for exactly that reason.
  Do not create a marketplace-side service for it, and do not duplicate the fallback logic into each of
  the four callers — one definition, same rationale the `ACTIVE_SLOT_STATUSES` comment
  (`BookingService.java:117-121`) already records for its own shared constant.

- **Callers — three, not four:** `AvailabilityService` (AC2), `BookingService` (AC3) and
  `BookingBatchService` (AC4). `AvailabilityService` currently injects neither `ConfigService` nor
  `CoachPricingRepository`; inject the resolver, not those. **`RescheduleService` must NOT inject it** —
  its check compares against the booking's own existing duration (already in scope as `booking` at
  `RescheduleService.java:54`), never against the coach's configured length. See AC3 for why.

**Coach-facing control (Profile Builder Step 3):**

- `ProfileBuilderStep3Request` gains `@Min(15) @Max(240) Integer sessionDurationMinutes` — **nullable, no `@NotNull`**. A coach who does not touch the field inherits the platform default, which is the behaviour the whole model rests on.
- `CoachProfileService.saveStep3:200` sets `pricing.setSessionDurationMinutes(req.sessionDurationMinutes())` alongside the existing `setPerSessionPrice`. Passing `null` must clear an override back to "inherit", not be ignored.
- `ProfileBuilderStep3.vue` gains a `q-select` directly under the per-session-price input, options `30 / 45 / 60 / 90 / 120` minutes plus an explicit "Use platform default (60 min)" entry mapping to `null`. Follow the `outlined dense` / `emit-value map-options` conventions already used by the `q-select` in `ProfileBuilderStep4.vue:14-25`. Do **not** free-type minutes — the CHECK constraint would 400 behind the generic step-error toast.
- New i18n keys in **all four** bundles (`src/frontend/src/i18n/{en,en-US,de,fr-FR}/index.js`) under the existing `auth.coach.*` namespace, beside `step3PerSessionPrice`. All four, even though `MainLayout.vue:236-239` offers only `en-US` and `fr-FR` — a missing key fires a runtime warning in whichever bundle lacks it.
- `CoachProfileDto` / `CoachCardDto`: **do not add the field.** Search and the public profile page do not need it for this story, and widening the search DTOs pulls in `CoachSearchSpecification`'s price subqueries for no UAT benefit. If a later story wants "60-min sessions" on the coach card, that is its scope.

### AC2 — Availability slots are sliced to one session length

`AvailabilityService.computeAvailableSlots` currently returns whole free segments. It must return
consecutive fixed-length slots carved out of each segment.

- Change the signature to `computeAvailableSlots(Instant windowStart, Instant windowEnd, List<CoachAvailabilityBlock> blocks, Duration slotLength)`. Keep it package-private — `AvailabilityServiceTest:497-570` drives it directly and that is the right level for this logic.
- Leave the existing segment computation (`:278-301`) **exactly as it is**. Slice at the end, replacing the terminal `segments.stream().map(...)` (`:303-305`): for each segment, walk from its start emitting `[t, t+slotLength)` while `t+slotLength <= segmentEnd`; drop the trailing remainder.
- `getAvailabilityCalendar` resolves the length **once per request**, before the day loop at `:110` — `Duration slotLength = sessionDurationResolver.resolve(coachId);` — and passes it into the call at `:200`. Not once per window, and definitely not once per day: it is a per-coach value and the method already holds `coachId`.

**The grid is anchored per segment, not per window, and that is intended.** A 09:00–17:00 window with a
12:00–12:45 block yields slots on the hour from 09:00 to 12:00, then 12:45, 13:45 … — the post-block
run is anchored to the block's end because that is genuinely when the coach next becomes free.
Anchoring the whole window to 09:00 instead would silently discard the 12:45–13:00 quarter-hour and
every equivalent fragment. Do not "tidy" this into a window-anchored grid.

**Worked cases to pin in `AvailabilityServiceTest`** (60-minute length unless stated):

| Window | Blocks | Expected |
|---|---|---|
| 09:00–17:00 | none | 8 slots, 09:00–10:00 … 16:00–17:00 |
| 09:00–17:00 | 12:00–13:00 | 7 slots — 3 before, 4 after |
| 09:00–10:30 | none | **1** slot 09:00–10:00; the 30-minute remainder is dropped |
| 09:00–09:45 | none | **0** slots — a segment shorter than one session yields nothing |
| 09:00–17:00 | none, 90-min coach | 5 slots; 16:30–17:00 dropped |

**Four existing tests assert the old whole-segment behaviour and must be rewritten, not deleted:**
`computeAvailableSlots_noBlocks_returnsFullWindows:500`, `_fullBlock_returnsEmpty:512`,
`_partialOverlap_returnsTwoSegments:527`, `_multipleWindows_multipleBlocks:547`. `_fullBlock_returnsEmpty`
should still pass unchanged (zero segments → zero slots); the other three change shape. Rewrite each to
assert the sliced result for its existing fixture — renaming `_returnsTwoSegments` to reflect that it
now yields slots, not segments.

**Preserve, do not touch:** the 48-hour fetch pad and its rationale comment (`:78-96`), the DST
inversion guard and duration-change WARN added by `uat-1` AC7 (`:138-179`), the transient pseudo-block
merge (`:186-198`), and `blockResponses`' exact week-scoping (`:210-214`). Slicing happens strictly
downstream of all of it.

`AvailabilityResourceIT` has three tests whose assertions depend on slot shape —
`getAvailability_noWindowsNoBlocks_returnsEmpty:97` (unaffected),
`_windowTimezoneDivergesFromProfile_responseUsesProfileTimezone:128`, and
`_activeBookingFromAnotherRequester_isCarvedOutOfComputedSlots:162`. The third is the important one:
it must still prove the carve-out, now expressed as "the slots covering the booked hour are absent"
rather than "the segment is split".

### AC3 — The single-booking and reschedule paths reject a wrong-length session

Both write paths gain an exact-duration check. Add `INVALID_SESSION_DURATION` to `BookingError`, whose
`getErrorCode()` returns `"booking.invalidSessionDuration"` — following the two existing constants
exactly (`BookingError.java:6-16`).

**`BookingService.createBookingRequest`** — insert immediately after the end-after-start check
(`:178-182`), before the availability-window lookup at `:184`:

```java
Duration required = sessionDurationResolver.resolve(req.coachId());
Duration requested = Duration.between(req.requestedStartTime(), req.requestedEndTime());
if (!requested.equals(required)) {
    throw new OperationNotAllowedException("Requested session length does not match this coach's session length",
        Map.of("requested minutes", requested.toMinutes(), "required minutes", required.toMinutes()),
        BookingError.INVALID_SESSION_DURATION);
}
```

Placed **before** the window check and the `findByIdForUpdate` pessimistic lock (`:197`) so a malformed
request costs neither a window query nor a row lock. Placed **after** the end-after-start check so a
reversed range still reports the clearer error.

**`RescheduleService`** — the third write path, not in `deferred-17` D1; without a check here a parent
reschedules a compliant 60-minute session into an eight-hour one and the whole AC is bypassed. But the
check must be a **different one**: after `:67`, require that the proposed range has the **same duration
as the booking being rescheduled**, not the coach's currently-configured length.

```java
Duration original = Duration.between(booking.getRequestedStartTime(), booking.getRequestedEndTime());
Duration proposed = Duration.between(req.proposedStartTime(), req.proposedEndTime());
if (!proposed.equals(original)) { throw … BookingError.INVALID_SESSION_DURATION }
```

**Resolving against the coach's current length here would be a functional regression, and it is the
same trap this story already sidesteps for `BookingDuplicationService`.** Duration has been entirely
unconstrained until now — that is the premise of P0-5 — so bookings already in any UAT database have
arbitrary lengths. `CreateRescheduleRequest` takes freely-chosen bounds
(`CreateRescheduleRequest.java:7-10`) and `ParentBookingsPage.vue:96-99` is two independent
`datetime-local` inputs sent raw (`:163-164`), with nothing deriving the end from either the coach's
session length or the original booking. So a parent moving a legacy 3-hour session to a new time *at
its own length* would be hard-rejected — and, since `booking.invalidSessionDuration` resolves nowhere,
they would see only "something went wrong". A reschedule is a **move, not a resize**: same-duration is
the invariant that actually matters, it closes the escalation hole completely, and it never
retroactively invalidates a booking that was legal when it was made.

**Make the constraint satisfiable in the UI.** Two free datetime inputs where the second must exactly
equal the first plus the original duration is a trap the parent cannot see. In
`ParentBookingsPage.vue`, derive `rescheduleProposedEnd` from `rescheduleProposedStart` plus the
selected booking's existing duration and render it read-only, rather than leaving it editable. This is
a small contained change in a file this story does not otherwise touch — keep it to that.

Do **not** also add the missing availability-window check here — see "Items examined and NOT folded in".

**Enforce duration only. Do not enforce grid alignment.** It is tempting to also require the start to
land on a slot boundary, and it would be wrong: the grid is anchored to segment starts (AC2), which
move whenever a coach adds or removes a block, so an alignment rule would retroactively invalidate a
booking whose slot was legal when it was made — and `RescheduleService` and
`BookingDuplicationService` both write times derived from existing bookings. Exact duration + the
existing window check + the `excl_bkg_coach_slot_overlap` exclusion constraint (`V87`) already prevent
every harm D1 describes.

**Existing bookings are untouched.** The check runs on create, never on read. A row already in the
database with a 3-hour duration keeps loading, displaying, accepting and completing exactly as before.

**On the error surface:** neither `booking.slotUnavailable` nor `booking.coachUnavailable` resolves
anywhere — zero hits across all four frontend bundles **and** all four
`src/main/resources/i18n/messages*.properties` files. `BookingRequestPage.submit()`'s catch shows the
generic `booking.requests.submitError` toast for any failure. A new unresolved code is therefore
exactly consistent with what is already there. Do **not** build a per-code toast mapping or a partial
bundle entry in this story; record the whole gap under AC7 instead.

### AC4 — The batch path gets the three checks it never had

`BookingBatchService.createBatch:107-122` is the weakest of the three write paths: per slot it checks
only future-start and end-after-start, and across slots only that start times are distinct. Extend the
loop at `:107-114`:

1. **The same duration check as AC3**, resolved **once** before the loop (`resolve(req.coachId())`), not per slot.
2. **The availability-window check it has never had.** `BookingService.isSlotWithinAvailabilityWindow` (`:769-796`) is currently `private`. **Widen it to package-private and call it from here — do not copy it.** `BookingBatchService` already injects `BookingService` (`:58`), and a second copy of that method would drift from the single path exactly the way the `ACTIVE_SLOT_STATUSES` comment warns about. Fetch the coach's windows once before the loop via `coachAvailabilityWindowRepository.findByCoachId(req.coachId())` (`BookingBatchService` will need that repository injected) and pass the same list to every slot — one query for the batch, not ten.
3. **Intra-batch overlap.** The existing distinct-start-time check (`:116-122`) does not stop two slots in one batch from overlapping each other — `09:00–10:00` and `09:30–10:30` both pass today. With AC1's fixed lengths this becomes easy: sort the slots by start and reject any pair where the next start is before the previous end, raising `BatchRuleViolationException("booking.overlappingSlots")`. Keep the distinct-start-time check as well: it produces a clearer message for the common duplicate-click case, and it is what `booking.duplicateSlotStartTime` already means.

**Deliberately NOT added: a cross-booking overlap check at batch-create time.** The single path has one
(`:213-221`) and the batch path does not, which looks like a parity gap but is not one worth closing
here. Batch rows are created `REQUESTED`, and `V87`'s exclusion constraint deliberately excludes
`REQUESTED` — its own comment explains that two overlapping `REQUESTED` bookings competing for a slot
is expected in-band behaviour that the accept-time re-check resolves. `acceptAll` already runs that
re-check against `ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED`. Adding a create-time check would reject
legitimate competing requests. Leave it.

**`booking.overlappingSlots` needs no bundle entry.** Verified: `booking.duplicateSlotStartTime` and
`booking.batchSizeExceeded` exist nowhere but the `throw` sites —
`BatchRuleViolationException.java` just carries the string through, and the code appears in none of
the four frontend bundles nor in any of `src/main/resources/i18n/messages*.properties`. A bare code is
therefore consistent with its siblings. Do not invent a bundle entry for one code while its two
neighbours have none; record the whole gap under AC7 instead.

### AC5 — A parent's own pending request renders as a disabled slot, not a hole

The backend behaviour is correct and must not change: `ACTIVE_SLOT_STATUSES` stays as it is, and the
pseudo-block merge stays as it is. What is missing is the frontend affordance.

In `BookingRequestPage.vue`:

- Add `await bookingStore.loadParentBookings()` to `onMounted` (`:333-344`), alongside the existing `loadAvailability` and `loadPlayerPacks` calls. The store action and its API call already exist (`booking.store.js:289-300`) — do not write a new one.
- Derive a computed list of the parent's **own** blocking bookings for this page: filter `bookingStore.parentBookings` to `String(b.coachId) === String(coachId)`, status in `['REQUESTED','ACCEPTED','PAYMENT_PENDING','CONFIRMED','UPCOMING','IN_PROGRESS','PAUSED']`, and `requestedStartTime` inside the week currently displayed.
- **The week boundary must be computed as instants anchored in the coach's timezone, not as a bare local-date range.** `bookingStore.weekStart` is a `YYYY-MM-DD` string built from the *browser's* local date (`booking.store.js:155-162`, `currentMonday()`), while the backend anchors the week it computed slots for in the coach's zone (`AvailabilityService.java:93-96`). Comparing `requestedStartTime` against browser-local or UTC midnight therefore misclassifies bookings at the week edges for any tester whose zone differs from the coach's — and a booking wrongly judged out-of-week is dropped from the merged list while its slot is still carved out of `computedSlots`, silently reproducing the exact hole AC5 exists to close, just at the boundary instead of everywhere. Convert `weekStart` and `weekStart + 7 days` to instants in `bookingStore.coachTimezone` (already populated by the same `loadAvailability` call, `booking.store.js:180`) before comparing, falling back to `'UTC'` when it is null, as `formatSlot:289` already does.
- **Known residual, do not try to close it here:** the backend's week bounds come from `windows.get(0).getCanonicalTimezone()` (`AvailabilityService.java:60,93-96`) while `coachTimezone` in the response is the *profile* column (`:68`). These are independently writable and can diverge — that is `deferred-17` D8 / `deferred-18` D2, both still open and both blocked on a migration and a product decision. Using the profile zone is correct for every coach whose two columns agree, which after `uat-1` AC4 is every new coach. Note the residual in the completion notes rather than inventing a third rule.
- Merge those into the rendered list as **disabled** `q-item`s, sorted with `computedSlots` by start time, each carrying a status chip (reuse `BookingStateChip.vue` — `src/frontend/src/components/booking/BookingStateChip.vue` already exists) and a caption explaining the slot is already yours. They must not be selectable, must not enter the batch basket, and must not count toward `batchAtMax`.
- Render them via the same `formatSlot` the available rows use, so the coach-timezone labelling stays consistent. **Do not fix `formatSlot`'s hardcoded `'en'` while you are in this file** — see "Items examined and NOT folded in".
- New i18n keys in all four bundles under the existing `booking.requests.*` namespace (`:755-759` in `en-US`) for the caption.

This is only coherent because of AC2: with fixed-length slots a booking occupies exactly one slot
position, so a merged list reads as a continuous grid with some rows greyed out. Against the old
whole-segment behaviour the same merge would have produced overlapping rows of different lengths.

**A player or parent viewing a coach they have never booked sees no change at all** — the filtered list
is empty and the page renders as it does today. Verify that path explicitly; a `parentBookings` fetch
failure must not blank the slot list (the store swallows into `bookingsError`, so guard on the array,
not on the absence of an error).

### AC6 — Three ops fixes: survive a rebuild, stop typing SHAs

**(a) `acme.json` onto the persistent volume.**

- `docker-compose.yml:113`: `/opt/skillars/traefik/acme.json` → `/opt/skillars/data/traefik/acme.json`.
- `deploy/provision.sh`: **move the acme.json creation block (`:119-140`, the `ACME_JSON=` assignment at `:120` through the closing `fi` before the `.env` block at `:142`) out of section 6.5 and into section 7**, after the volume is mounted and beside the existing `mkdir -p "${MOUNT_POINT}/postgres"` group (`:190-198`). This ordering is the entire risk in this change: section 6.5 currently runs **before** section 7 mounts `/dev/sdb` at `${DEPLOY_ROOT}/data`, so creating the file at the new path from its current position would write it to the root disk and then have the mount hide it — Traefik would start against an empty file and silently reissue every certificate. Keep the symlink refusal and the `chmod 600` failure handling exactly as they are; only the path and the position change.
- Also drop `"${DEPLOY_ROOT}/traefik"` from the `mkdir -p` at `:108-111` and create the directory inside section 7's mounted-volume block instead.
- **The `else` branch matters.** Section 7 only runs its mounted-volume work when `/dev/sdb` exists (`if [ -b … ]` at `:166`); on a box with no volume it logs a warning and falls through (`else` at `:199`, `fi` at `:203`). acme.json must still be created in that case — a no-volume host must not end up with Traefik missing its storage file entirely. Handle both branches, or create the directory and file after the `fi` so one code path covers both.
- Update `deploy/traefik/README.md:5-12`, `docs/deployment/traefik-tls.md:16`, and — since it enumerates on-disk state — `docs/deployment/backup-restore.md` if it names the old path.
- **Migration note for the existing UAT box**, in `docs/deployment/uat-deployment.md`: an operator upgrading in place must `mv /opt/skillars/traefik/acme.json /opt/skillars/data/traefik/acme.json` (preserving mode 600) *before* `docker compose up -d`, or Traefik reissues from scratch and may hit the rate limit. One sentence, but omitting it turns a safe change into an outage.

**(b) Redis onto the persistent volume.**

- `docker-compose.yml:92`: `redis-data:/data` → `/opt/skillars/data/redis:/data`, and delete the now-orphaned `redis-data` entry from the top-level `volumes:` block (`:293-294`). That leaves `volumes:` with no entries — remove the key entirely rather than leaving an empty mapping, and confirm `docker-compose.uat.yml`'s own `volumes:` block (`skillars-uat-minio`) still merges correctly. Leave `--appendonly yes` alone.
- `deploy/provision.sh` section 7: `mkdir -p "${MOUNT_POINT}/redis"` with the ownership the image expects, following the `chown` pattern the prometheus/loki/tempo/grafana lines already use. **Verify the uid rather than trusting this story** — `docker run --rm redis:7-alpine id redis` — and use what it reports; a wrong uid makes redis fail to start with a permissions error rather than fall back gracefully.
- Note in `docs/deployment/uat-deployment.md` that this discards the existing Redis contents on the next deploy. That is acceptable (sessions and cache only) but a tester who is suddenly logged out should not have to guess why.

**(c) A stable tag alongside the SHA tag.**

`.github/workflows/ci.yml:75` — `tags` is newline-separated (`.github/actions/docker-build/action.yml:19`):

```yaml
          tags: |
            ghcr.io/${{ github.repository }}:sha-${{ steps.sha.outputs.short }}
            ghcr.io/${{ github.repository }}:latest
```

The SHA tag stays and stays first — it is what `docs/deployment/rollback.md` pins to, and `latest` is
an addition for convenience, not a replacement. Add one line to `docs/deployment/uat-deployment.md`
noting that `APP_IMAGE=ghcr.io/<repo>:latest` now works for a routine redeploy while a rollback still
requires the explicit SHA.

### AC7 — Ledger updated

Edit `_bmad-output/implementation-artifacts/deferred-work.md`. Each edit carries a one-line note naming
this story and the date, matching `deferred-13`/`-14`/`-16`/`uat-1`.

| Item | Action |
|---|---|
| `deferred-17` D1 | **Close.** Slicing (AC2) plus enforcement on all three write paths (AC3, AC4). Note that the fix went wider than D1 described — D1 named two write paths, there were three. |
| `deferred-18` D3 | **Close.** AC5 supplies the missing affordance; the backend behaviour D3 called correct is unchanged. |
| P2 #2 / #3 / #5 rows under the `deploy-*` headings | **Close** the three AC6 covers. Leave `deploy-3-1` (backup retention) open. |

Then append a `## Deferred from: skillars-uat-2 …` section, following the existing convention. It must
carry at minimum:

- **`RescheduleService` has no availability-window check** — the pre-existing defect found while scoping AC3, with the reasoning from "Items examined and NOT folded in" for why it was left.
- **Booking error codes resolve nowhere.** `booking.slotUnavailable`, `booking.coachUnavailable`, `booking.duplicateSlotStartTime`, `booking.batchSizeExceeded` and now `booking.invalidSessionDuration` and `booking.overlappingSlots` appear in none of the four frontend bundles and none of the four `src/main/resources/i18n/messages*.properties` files — they travel to the client as raw codes, and `BookingRequestPage.submit()` shows one generic toast for every failure regardless. A parent whose booking is rejected for a wrong length sees "something went wrong" with no way to learn the coach runs 90-minute sessions. Worth its own small story once the error set stabilises; note it interacts with `deferred-18` D6 (P1 #8), since the backend bundles it would populate are the ones `ApiAdvice` cannot currently select.
- Anything else the implementation turns up.

**Also update `_bmad-output/implementation-artifacts/uat-readiness-priorities.md`'s "Story claims"
table** — it is the index a subsequent story-creation pass reads to avoid re-picking work. This story's
row and its inline `CLAIMED` markers were added at story-creation time; if implementation changes what
this story actually covers, correct that table to match rather than leaving it aspirational.

## Tasks / Subtasks

- [x] **Task 1 — Duration model (AC1)**
  - [x] Confirm the highest migration version and `SELECT max(id) FROM main.platform_config` (**expect 602 — V91 holds it; use 603**, and note `ON CONFLICT (key)` cannot catch an id collision); write `V93__session_duration.sql` with the nullable column, the CHECK, and the config seed
  - [x] `CoachPricing.sessionDurationMinutes` as boxed `Integer`
  - [x] `SessionDurationResolver` in `platform.booking.service`, using `getBoundedLong(key, 60, 15, 240)`
  - [x] `ProfileBuilderStep3Request` nullable `@Min(15) @Max(240)` field; `CoachProfileService.saveStep3` writes it, including writing `null` to clear an override
  - [x] `ProfileBuilderStep3.vue` select with a "platform default" option → `null`; i18n keys in all four bundles
  - [x] Unit test the resolver: coach override wins; `null` override falls back to config; **no `coach_pricing` row at all** falls back to config; an out-of-range config value falls back to 60
  - [x] IT: Step 3 round-trips a set value, and round-trips `null` back to inherit
- [x] **Task 2 — Slot slicing (AC2)**
  - [x] New `slotLength` parameter; slice after segment computation; resolve once per request in `getAvailabilityCalendar`
  - [x] Rewrite the four `computeAvailableSlots_*` tests; add the five worked cases from AC2's table, including the 0-slot and remainder-dropped ones
  - [x] Confirm the `uat-1` DST guard tests (`getAvailabilityCalendar_windowStraddlingDstGap_contributesNoSlot`) still pass unchanged
  - [x] Update the three affected `AvailabilityResourceIT` tests; the carve-out test must still prove the carve-out
- [x] **Task 3 — Single + reschedule enforcement (AC3)**
  - [x] `BookingError.INVALID_SESSION_DURATION`
  - [x] Check in `createBookingRequest` positioned after end-after-start, before the window lookup and the pessimistic lock
  - [x] Check in `RescheduleService` after `:67` — **same-as-original duration, not same-as-coach's-current-length**
  - [x] `ParentBookingsPage.vue`: derive the reschedule end input from start + the booking's existing duration, read-only
  - [x] Tests, create path: exact length accepted; longer rejected; shorter rejected; a coach override of 90 accepts 90 and rejects 60
  - [x] Tests, reschedule path: **a booking whose existing duration is 3 hours (i.e. predates this story) reschedules successfully to a new 3-hour range** — this is the regression the same-as-original rule exists to prevent, and it must fail if the check is switched to resolve against the coach's length; inflating that booking to 8 hours is still rejected
  - [x] **Mutation check** — remove the `createBookingRequest` check and confirm a named test fails; record it
- [x] **Task 4 — Batch enforcement (AC4)**
  - [x] Widen `isSlotWithinAvailabilityWindow` to package-private; call it from `BookingBatchService` with one pre-fetched window list
  - [x] Duration check resolved once before the loop
  - [x] Intra-batch overlap check raising `BatchRuleViolationException("booking.overlappingSlots")` — a bare code, no bundle entry (see AC4)
  - [x] Tests: an out-of-window slot in a batch is rejected (this is the regression that never had coverage); a wrong-length slot is rejected; two overlapping in-batch slots are rejected; ten valid slots still succeed
- [x] **Task 5 — Own-request affordance (AC5)**
  - [x] `loadParentBookings()` on mount; computed own-blocking-bookings filtered by coach, status and displayed week — **week bounds as instants anchored in `coachTimezone`, not a bare local-date range**; verify with a booking on the last day of the week and a coach zone several hours from the browser's
  - [x] Merged, sorted, disabled rows with `BookingStateChip`; excluded from selection, the batch basket and `batchAtMax`
  - [x] i18n keys in all four bundles
  - [x] Verify the never-booked-this-coach path renders unchanged, and that a `parentBookings` fetch failure does not blank the slot list
- [x] **Task 6 — Ops (AC6)**
  - [x] `acme.json` path + **provision.sh block moved into section 7**; `deploy/traefik/README.md`, `traefik-tls.md`, `backup-restore.md` if it names the path; in-place migration sentence in `uat-deployment.md`
  - [x] Redis bind mount; orphaned named volume deleted; `mkdir`/`chown` in section 7 with the uid **verified from the image**; data-loss note
  - [x] `latest` tag added after the SHA tag in `ci.yml`
  - [x] `docker compose -f docker-compose.yml -f docker-compose.uat.yml config` still resolves; confirm no service still references `redis-data`
- [x] **Task 7 — Ledger (AC7)**
  - [x] Close `deferred-17` D1, `deferred-18` D3, the three P2 rows
  - [x] Append the `skillars-uat-2` deferred section with at least the two items named in AC7
  - [x] Reconcile the `uat-readiness-priorities.md` claims table with what actually shipped
- [x] **Task 8 — Full verification**
  - [x] `mvn -o verify` green; report **unit and integration totals separately** and derive your contribution by diffing test-method counts against `HEAD`, not by subtracting totals — see Dev Notes
  - [x] Walk all eleven files in the regression surface; for each, state whether it needed a fixture change and why
  - [x] ESLint clean; record Prettier state honestly (the files this story touches were already failing at `bf9c828` — do not bury the diff in a reformat)
  - [x] List every `.vue` behaviour verified by code reading only

### Review Findings

**Group A** (backend duration-model files: `SessionDurationResolver`, `V93` migration, `AvailabilityService`, `BookingService`, `BookingBatchService`, `RescheduleService`, `BookingError`, `ProfileBuilderStep3Request`, `CoachPricing`, `CoachProfileService`), **Group B** (backend tests: `SessionDurationResolverTest`, `AvailabilityServiceTest`, `BookingServiceTest`, `BookingBatchServiceTest`, `RescheduleServiceTest`, `AvailabilityResourceIT`, `BookingBatchResourceIT`, `CoachProfileBuilderIT`, `ExpiredPackBookingValidationTest`) reviewed against AC1–AC4, and **Group C** (frontend + i18n: `ProfileBuilderStep3.vue`, `BookingRequestPage.vue`, `ParentBookingsPage.vue`, `i18n/{en,en-US,de,fr-FR}/index.js`) reviewed against AC1/AC3/AC5. Group D (ops/infra/docs + AC7 ledger) is still pending a follow-up review run.

**Review audit, 2026-08-11.** Every open `[Patch]` finding above was re-verified against the working
tree before being actioned; outcomes are recorded inline below. One finding was **rejected on its
premise** (Group A, the batch overlap check) and one was found to rest on a **wrong reading of the
code** (Group D, the `else`-branch warning) — in that case the underlying defect was real but larger
than described, and was fixed as described. Two rewritten tests were mutation-checked rather than
merely re-run, because "this test cannot fail" was the finding being closed.

**Group A findings:**

- [x] [Review][Reject] Batch overlap check's adjacent-pair-only comparison silently depends on every slot sharing the same resolved duration, undocumented at the check site [BookingBatchService.java:191-199] — **the premise is false.** Adjacent-pair comparison after sorting by start is correct for *arbitrary* durations: the per-slot loop above has already rejected any slot whose end is not after its start, so once every adjacent pair satisfies `start[i] >= end[i-1]` the ends are strictly increasing and no slot can overlap anything earlier than its immediate predecessor. No behavioural change was needed. The "undocumented" half was valid and is closed: a comment at the check site now states why adjacent comparison suffices and warns off a "fix" to a running-maximum-end.
- [x] [Review][Patch] Misaligned indentation left on `isSlotWithinAvailabilityWindow`'s second parameter line after widening from `private` to package-private [BookingService.java:258-259] — **fixed**, continuation line realigned to the new signature.
- [x] [Review][Defer] Session duration and availability windows are resolved once per request/batch with no re-validation against concurrent config changes before persistence, and a coach changing their session length between a GET availability call and a subsequent POST booking can turn a just-displayed slot into a rejection [BookingBatchService.java:149-179, AvailabilityService.java:113, BookingService.java:235] — deferred, pre-existing class of read-then-write staleness across the booking module, not introduced uniquely by this change
- [x] [Review][Defer] `V93__session_duration.sql` combines `ADD COLUMN` and `ADD CONSTRAINT ... CHECK` in one `ALTER TABLE`, taking an `ACCESS EXCLUSIVE` lock while validating the constraint against existing rows, instead of `ADD CONSTRAINT ... CHECK (...) NOT VALID` + a separate `VALIDATE CONSTRAINT` [V93__session_duration.sql:9-13] — deferred, low impact at `coach_pricing`'s expected row count and consistent with this repo's existing migration convention

**Group B findings:**

- [x] [Review][Patch] `computeAvailableSlots_partialOverlap_slicesBothSidesOfTheBlock` (renamed from `_returnsTwoSegments`) keeps a fixture where both free segments are already exactly 60 minutes, so sliced and un-sliced output are numerically identical — it cannot fail against a regression to whole-segment behavior; needs a fixture where slicing actually changes the result [AvailabilityServiceTest.java:240-256] — **fixed and mutation-checked.** Refixtured to a 09:00–14:00 window with a 10:30–11:00 block: free segments of 90 and 180 minutes yield 1 + 3 slots with a half-hour dropped, so sliced and whole-segment output now differ in both count and bounds. Verified by mutating `computeAvailableSlots` back to emitting whole segments — the test **fails** (along with 11 others in the class); restored and re-verified green.
- [x] [Review][Patch] `createBatch_slotOutsideCoachAvailability_isRejected` stubs `isSlotWithinAvailabilityWindow` to return `false` unconditionally, so it only proves the first slot in the batch is checked, not every slot — add a case where an early slot passes and a later one fails [BookingBatchServiceTest.java:105-135] — **fixed.** The unconditional test is kept and a second, `createBatch_laterSlotOutsideCoachAvailability_isRejected`, stubs the answer per slot so the first passes and the second fails, additionally asserting the check ran twice.
- [x] [Review][Patch] `requestReschedule_legacyThreeHourBooking_movesAtItsOwnLength` only verifies `save(any())` was called and never inspects the persisted proposal's duration — use the file's existing `ArgumentCaptor` pattern to confirm the 3-hour length round-trips [RescheduleServiceTest.java:698-714] — **fixed**, now captures the saved `BookingRescheduleRequest` and asserts both bounds and the 3-hour span.
- [x] [Review][Patch] `computeAvailableSlots_nonPositiveSlotLength_throwsRatherThanLooping` only exercises `Duration.ZERO`; the guard's `isNegative()` branch is untested [AvailabilityServiceTest.java:379-385] — **fixed**, a `Duration.ofMinutes(-30)` case added alongside the zero case.
- [x] [Review][Patch] `CoachProfileBuilderIT` tests only the below-minimum (`5`) bound for `sessionDurationMinutes`; no symmetric above-maximum (`>240`) test exists [CoachProfileBuilderIT.java:823-838] — **fixed**, `saveStep3_sessionDurationMinutesAboveMaximum_returns400` added with `300`.
- [x] [Review][Patch] `computeAvailableSlots_ninetyMinuteCoach_yieldsFiveSlotsAndDropsTheTail` only asserts slot 0's end and slot 4's bounds, leaving slots 1–3 unverified — extend with `extracting(...).containsExactly(...)` like its sibling lunch-block test [AvailabilityServiceTest.java:346-355] — **fixed**, all five starts and all five ends now pinned.
- [x] [Review][Patch] `ExpiredPackBookingValidationTest` copy-pastes an identical three-line window+resolver stub block into three test bodies instead of a shared `@BeforeEach`/helper — the class's own comment notes every fixture is identical [ExpiredPackBookingValidationTest.java:95-103,140-148,255-263] — **fixed**, though the count was off by one: the class already had a `setupCommonMocks(DayOfWeek)` helper used by three tests, and it was the other **two** bodies that inlined a copy. Both now call the helper; ~40 duplicated lines removed.
- [x] [Review][Patch] `createBookingRequest_coachOverrideOfNinety_acceptsNinetyAndRejectsSixty` bundles two independent assertions (accept-90, reject-60) in one test, obscuring which half failed on a red run — split into two tests [BookingServiceTest.java:622-652] — **fixed**, split into `_acceptsNinety` and `_rejectsSixty`. **The Task 3 mutation check was re-run** rather than assumed to carry over: disabling the create-path duration check still fails exactly three named tests, now `_longerThanTheCoachSessionLength_isRejected`, `_shorterThanTheCoachSessionLength_isRejected` and `_coachOverrideOfNinety_rejectsSixty`.
- [x] [Review][Patch] Tautological assertion: `noneMatch(...)` is added directly after a `containsExactlyInAnyOrder(...)` that already excludes the same pair, so it cannot ever catch anything the preceding assertion wouldn't already catch [AvailabilityServiceTest.java:135-136] — **fixed**, removed and replaced by a one-line comment recording why no such assertion belongs there.

**Group C findings:**

- [x] [Review][Patch] `rescheduleProposedEnd` silently resolves to `''` when `rescheduleDurationMs` can't be derived (malformed booking start/end), leaving the now-readonly end field permanently blank with no error surfaced to the parent [ParentBookingsPage.vue:449-454] — **fixed at the open, not at the submit.** `openRescheduleDialog` now computes the duration first and, if it is not positive, shows a negative toast and does **not** open the dialog — a dead-end form is worse than a refusal. New key `booking.reschedule.endDerivedLengthUnavailable` in all four bundles.
- [x] [Review][Patch] `durationOptions` in `ProfileBuilderStep3.vue` hand-writes five near-identical option objects instead of deriving them from `[30, 45, 60, 90, 120].map(...)` [ProfileBuilderStep3.vue:46-53] — **fixed**, derived from a `DURATION_CHOICES` constant.
- [x] [Review][Patch] fr-FR i18n quoting inconsistency: `step3SessionDuration` escapes an apostrophe inside single quotes while the neighboring `step3PackHelper` key switches to double quotes specifically to avoid that [fr-FR/index.js:170] — **fixed**, now `"Durée d'une séance"`, matching `step2Title` / `step2AgeGroups` in the same block.
- [x] [Review][Defer] `OWN_BLOCKING_STATUSES` in `BookingRequestPage.vue` duplicates the backend's `ACTIVE_SLOT_STATUSES` with no shared source of truth — currently identical (verified), but nothing prevents drift if the backend set changes later [BookingRequestPage.vue:295-303] — deferred, not fixable within this diff without exposing the status set via an API/shared contract
- [x] [Review][Defer] The session-duration `q-select` offers only 5 discrete values (30/45/60/90/120 + platform default) while the backend/DB accepts any integer 15–240 — a value set outside that set via direct API access would render unselected in this dropdown [ProfileBuilderStep3.vue:46-53] — deferred, narrow/low-probability, needs a product decision on how to surface an out-of-list value

**Group D findings** (ops/infra/docs + AC7 ledger; excludes `deferred-work.md`, reviewed separately across Groups A–C):

- [x] [Review][Decision] `docker-compose.uat-hostwinds.yml` — a fourth deployment overlay outside this story's stated scope — merged its `redis` service against the base `docker-compose.yml` with no `volumes:` override of its own, unlike `docker-compose.local.yml` which this story correctly patched. That Hostwinds box does not run `provision.sh`'s Hetzner-volume-mount logic at all, so `/opt/skillars/data/redis` would have been freshly auto-created by Docker as root-owned there, and `redis:7-alpine` (uid 999) would likely have failed to write to it. **Resolved 2026-08-11 (Mbah): add a named-volume override**, mirroring `docker-compose.local.yml`. Fixed: `docker-compose.uat-hostwinds.yml` now gives `redis` its own `skillars-uat-hostwinds-redis` named volume; verified via `docker compose -f docker-compose.yml -f docker-compose.uat-hostwinds.yml config --volumes` resolving to `skillars-uat-hostwinds-minio` + `skillars-uat-hostwinds-redis`, no reference to the Hetzner-only bind mount. [docker-compose.uat-hostwinds.yml — `redis:` service definition]
- [x] [Review][Patch] `uat-deployment.md`'s one-time migration script only `mv`s `acme.json`; it never creates/`chown`s `/opt/skillars/data/redis`, so an operator following the copy-pasted block literally (without separately re-running `provision.sh`) hits a Redis permission crash on the exact upgrade path this doc exists to make safe [docs/deployment/uat-deployment.md:527-551] — **fixed.** The block now also `chmod 700`s the traefik directory and creates `/opt/skillars/data/redis` owned `999:1000`, and a following sentence states that re-running `provision.sh` does everything in the block **except** the `mv`, which is the one step it cannot do.
- [x] [Review][Patch] The new `${MOUNT_POINT}/traefik` directory created in `provision.sh` section 7.5 is never `chmod 700`, while `deploy/traefik/README.md`'s manual fallback instructions explicitly do `chmod 700` on the same directory — automated and documented paths now diverge [deploy/provision.sh:288] — **fixed** in the script, and `deploy/traefik/README.md`'s opening sentence now names both modes so the two descriptions stay checkable against each other.
- [x] [Review][Patch] The `else` branch warning when the Hetzner Volume isn't attached only mentions Postgres data being unprotected; it doesn't mention that acme.json/redis (created in section 7.5, which runs unconditionally after the `fi`) also end up on the root disk in that case [deploy/provision.sh:273-277] — **fixed, and the finding understated it.** The parenthetical was wrong about the code: acme.json is created in 7.5, but the redis `mkdir`/`chown` was **inside** section 7's volume-only branch, so a no-Volume host got no redis directory at all — Docker would auto-create `/opt/skillars/data/redis` root-owned on first `up` and redis (uid 999) would crash-loop on it. That is the same defect already fixed for Hostwinds above, on a different path. The redis directory creation was therefore **moved into 7.5** alongside acme.json, where one code path covers both branches, with a comment on why it sits there rather than with the LGTM directories (it fails hard on a wrong owner; they degrade). The warning text was then rewritten to name Postgres, Redis, the LGTM data and acme.json, and to say a rebuild loses all of it.
- [x] [Review][Patch] `first-time-setup.md`'s numbered walkthrough (items 5–7) presents acme.json/redis-directory creation as happening before the volume mount, re-describing the exact ordering bug this story's AC6a fix eliminated [docs/deployment/first-time-setup.md:92-94] — **fixed**, items 5–7 reordered to base dirs → mount + on-volume dirs → redis/acme.json, with a callout explaining why that order is load-bearing and what happens on a host with no Volume attached.
- [x] [Review][Patch] `local-deployment.md`'s illustrative compose YAML snippet doesn't show the new `redis:` override even though the prose paragraph directly below it says the override is needed [docs/deployment/local-deployment.md:129-163] — **fixed**, the snippet gains the `redis` service and the `skillars-local-redis` volume, and the lead-in prose now lists redis among the bind mounts and says "all six" rather than "all five".
- [x] [Review][Patch] `sprint-status.yaml`'s completion-notes entry says `runbook.md` and `local-deployment.md` "named the acme.json path" — neither file mentions acme.json at all; their changes are entirely about the redis path (AC6b), not acme.json (AC6a) [sprint-status.yaml, `skillars-uat-2` entry] — **confirmed and fixed.** `grep -c acme` is 0 in both files and 3 in `first-time-setup.md`; the entry now separates the acme.json file from the two redis-path files and dates the correction.
- [x] [Review][Defer] `runbook.md`'s "clear Redis data" emergency procedure targets `appendonly.aof`/`dump.rdb`, but Redis 7's default Multi-Part AOF stores data under `appendonlydir/` instead — the same wrong filenames were already referenced before this diff (just accessed via a different volume mechanism), so this is pre-existing, but the procedure would silently no-op if ever run [docs/deployment/runbook.md:463-468] — deferred, pre-existing inaccuracy not introduced by this change
- [x] [Review][Defer] If `provision.sh` is run once before the Hetzner Volume is attached (acme.json created on the root disk via section 7.5's unconditional path) and the volume is attached and the script rerun later, the pre-volume acme.json is orphaned rather than migrated — the story's migration note in `uat-deployment.md` covers the pre-story path, not this sequencing case [deploy/provision.sh:279-308] — deferred, narrow/low-probability ops scenario
- [x] [Review][Defer] `.github/workflows/ci.yml`'s new `:latest` tag has no explicit concurrency group; two master pushes completing out of trigger order could theoretically leave `:latest` pointing at an older commit than the newest push [.github/workflows/ci.yml:72-79] — deferred, narrow race (push-to-master only, not PR builds), low practical impact

## Dev Notes

### Regression surface — read this first

This story adds the first duration constraint the booking package has ever had, and the fallback
default is 60 minutes for every coach without an override. **Any existing test that creates a booking
through one of the three enforcement points with a duration other than exactly 60 minutes will start
failing.**

Eleven test files construct booking time ranges:

```
admin/api/SuspendedCoachBookingBlockIT.java      booking/service/AvailabilityServiceTest.java
booking/api/BookingBatchResourceIT.java          booking/service/BookingBatchServiceTest.java
booking/api/BookingRequestResourceIT.java        booking/service/BookingDuplicationServiceTest.java
booking/api/RescheduleResourceIT.java            booking/service/BookingServiceConcurrencyIT.java
booking/repo/BookingRepositoryIT.java            booking/service/BookingServiceTest.java
                                                 booking/service/RescheduleServiceTest.java
```

The blast radius is narrower than that list looks, and knowing which is which up front is the point:

- **Affected — must be triaged:** anything driving `createBookingRequest` or `createBatch` with a duration other than 60 minutes. That is `BookingServiceTest`, `BookingRequestResourceIT`, `BookingBatchServiceTest`, `BookingBatchResourceIT`, `BookingServiceConcurrencyIT` and `SuspendedCoachBookingBlockIT`.
- **Largely unaffected:** `RescheduleServiceTest` and `RescheduleResourceIT`. Because AC3's reschedule rule is same-as-original rather than same-as-coach's-length, a fixture that moves a booking without resizing it keeps passing whatever its duration is. Only a test that deliberately changes duration mid-reschedule will fail — and if one does, that is the defect being fixed, not a fixture problem.
- **Untouched:** `BookingRepositoryIT` (inserts rows directly), `BookingDuplicationServiceTest` (a path AC3 deliberately exempts), `AvailabilityServiceTest` (affected by AC2's slicing, not by the duration checks).

**Prefer fixing the fixture over relaxing the check.** A create-path test that needs a 3-hour booking
should either insert the row directly or give its coach a `coach_pricing` row with
`session_duration_minutes = 180` — not motivate a weaker constraint.

### Architecture constraints (from `_bmad-output/project-context.md`)

- **Java 17 / Spring Boot 3.5.11.** DTOs are `record` types. Resources are suffixed `Resource`, live in `api`, and every method carries `@PreAuthorize` using `SecurityConstants`.
- **Module boundary is enforced.** `SessionDurationResolver` is a booking rule → `platform.booking.service`. It reads a `marketplace` repository, which is the same cross-module read `BookingService` already performs; it must not land in `infrastructure`.
- **Schema changes are Flyway-only.** No DDL from Java.
- **Frontend:** Quasar 2.16 / Vue 3.5 with `<script setup>`, Pinia for shared state, all API calls in `src/api/*.api.js`, `async/await` not `.then()`, all user-facing text through `vue-i18n`.

### Testing — the guardrails are build-failing

`skillars-deferred-19` consolidated the Spring test contexts and left enforcement behind:

- **Every IT extends `com.softropic.skillars.config.AbstractIntegrationTest` and adds no class-level annotations.** Adding `@SpringBootTest`, `@ActiveProfiles`, `@Import` or `@TestPropertySource` to a concrete `*IT` forks the context and `IntegrationTestConventionTest` fails the build in the `test` phase, before any container starts.
- **`EXPECTED_TEST_PROPERTY_SOURCE_COUNT = 5` is pinned.** This story's config key is database-seeded, not property-driven, so nothing here should need to touch it. If you conclude otherwise, bump it **and** add the `// context-fork:` comment the guardrail's javadoc demands — never silently.
- **Test data:** Instancio for generation, AssertJ for assertions, Awaitility for async, Testcontainers with a real database.
- **Test-count reporting is a repeat trap.** Four consecutive stories had to correct inflated totals. Report unit and IT separately and derive your delta by diffing test-method counts against `HEAD` — `uat-1` proved that subtracting from a CI baseline is wrong whenever an intervening commit deleted test files. The last verified figures are **823 unit + 863 IT** at `8a76652`.
- **There is no frontend test suite.** No `*.spec.js` outside `node_modules`, no `src/frontend/test`. Do not introduce a framework here. Every `.vue` change in AC1 and AC5 is unverifiable by CI — say so explicitly in the completion notes rather than letting a green build imply coverage. Standing gap: `deferred-17` D6, `skillars-5-4` W9, `uat-1`.

### Files being modified — current state and what must be preserved

| File | Current state | This story changes | Must not break |
|---|---|---|---|
| `AvailabilityService.java:275-306` | Returns whole segments | Slices them | The 48h pad (`:78-96`), the DST inversion guard and shortening WARN (`:138-179`), the pseudo-block merge (`:186-198`), `blockResponses` week-scoping (`:210-214`) |
| `BookingService.java:174-189` | future / end-after-start / in-window | Adds a duration check between them and the window lookup | The pessimistic-lock-then-refresh sequence (`:197-211`) and its rationale comments — the check goes *before* the lock, never inside it |
| `BookingService.java:769-796` | `private isSlotWithinAvailabilityWindow` | Widened to package-private | The cross-midnight anchoring comment (`:783-785`) and the invalid-timezone `continue` (`:775-779`) |
| `BookingBatchService.java:107-122` | future / end-after-start / distinct starts | Adds window, duration, intra-batch overlap | The `maxSize` config check (`:90-93`), the deliberately non-atomic `acceptAll` design (`:162-173`) |
| `RescheduleService.java:63-67` | future / end-after-start | Adds a **same-as-original** duration check | Everything else — the window check stays absent by decision, and the check must not resolve against the coach's current length |
| `ParentBookingsPage.vue:96-99,163-164` | Two free `datetime-local` inputs sent raw | End derived read-only from start + the booking's duration | The `formatDateTime(…, booking.canonicalTimezone)` rendering at `:60`; the rest of the page |
| `CoachProfileService.saveStep3:187-219` | Writes price + packs | Writes duration too | The step-out-of-order guard (`:189-193`), the delete-then-recreate pack handling |
| `BookingRequestPage.vue` | Loads availability + packs; renders `computedSlots` | Loads parent bookings; merges disabled rows | `formatSlot`'s Invalid-Date and unknown-zone fallbacks (`:263-290`) — and its hardcoded `'en'`, which stays |
| `docker-compose.yml:92,113,293-294` | Redis named volume; acme.json on root disk | Both onto `/opt/skillars/data` | Every other bind mount and the `skillars-internal` network comment (`:290-292`) |
| `deploy/provision.sh:108-140,160-203` | acme.json created in §6.5 (`:119-140`), before §7 mounts the volume (`:166`) | Block relocated past the mount | The symlink refusal, the `chmod 600` failure handling, the `.env` permission block (`:142-157`), the fstab logic, and the no-volume `else` branch (`:199-203`) |

### Latest technical notes

- **`Duration.between(Instant, Instant)`** is exact and calendar-free — the right comparison here. Do **not** compare `LocalTime`s or minute counts derived through a zone: a session that crosses a DST transition has a wall-clock length different from its true length, and the booking's stored bounds are instants. A 60-minute session starting at 02:30 on a spring-forward Sunday is still 60 minutes of elapsed time.
- **`ConfigService.getBoundedLong`** (`:88-100`) exists for exactly this class of key and logs a WARN on an out-of-range value before falling back. Use it; `getLong(key)` throws `IllegalStateException` on a missing key, which would take down the availability endpoint if the seed were ever rolled back.
- **`ConfigService` caches with a 5-minute TTL** (`@Scheduled(fixedDelayString = "${app.config.cache-ttl-seconds:300}")`, `:47`). An admin changing the platform default sees it apply within five minutes, not instantly. Worth one sentence wherever the key is documented — a UAT operator who changes it and immediately re-checks will otherwise think it did not work.
- **`platform_config.value_type` is constrained to `('STRING','LONG')`** (`V20:10`). The new row is `LONG`.
- **Quasar `q-select`**: follow `ProfileBuilderStep4.vue:14-25` for `outlined dense` + `emit-value map-options`. A five-option list needs no `use-input` filtering — that was only necessary for the ~486-entry timezone picker `uat-1` added.

### Git intelligence

`8a76652` (`uat-1`) is the immediate parent and it landed in the same files: `AvailabilityService`
(the DST guard), `AvailabilityManagerPage.vue`, `ProfileBuilderStep1/4.vue`, `profileBuilder.store.js`,
`docker-compose.local.yml`. Read its Completion Notes before starting — three defects its spec did not
anticipate were found by tests, and one of them is directly relevant: **`V92` broke eight IT classes**
because `authorityData.sql` inserted a conflicting row and the fixtures wired foreign keys by literal
id. `V93` touches `coach_pricing` and `platform_config`, so check whether any test fixture inserts a
`coach_pricing` row positionally or a `platform_config` row at a literal id before assuming the
migration is inert.

Also from `uat-1`: the last five substantive stories all had review findings about **tests that could
not fail against unfixed code**. Task 3 prescribes a mutation check for that reason. Perform it and
record the result.

### Project Structure Notes

- Migration: `src/main/resources/db/migration/V93__session_duration.sql`
- Backend new: `platform/booking/service/SessionDurationResolver.java`
- Backend edited: `booking/service/AvailabilityService.java`, `BookingService.java`, `BookingBatchService.java`, `RescheduleService.java`, `booking/contract/BookingError.java`, `marketplace/repo/CoachPricing.java`, `marketplace/contract/ProfileBuilderStep3Request.java`, `marketplace/service/CoachProfileService.java`
- Frontend edited: `components/profileBuilder/ProfileBuilderStep3.vue`, `pages/parent/BookingRequestPage.vue`, `pages/parent/ParentBookingsPage.vue`, `i18n/{en,en-US,de,fr-FR}/index.js`
- Ops/docs edited: `docker-compose.yml`, `deploy/provision.sh`, `deploy/traefik/README.md`, `.github/workflows/ci.yml`, `docs/deployment/{uat-deployment,traefik-tls,backup-restore}.md`, `_bmad-output/implementation-artifacts/{deferred-work,uat-readiness-priorities}.md`

No new module. Every change lands in an existing bounded context.

### References

- [Source: `_bmad-output/implementation-artifacts/uat-readiness-priorities.md`] — P0-5, P1 #1, P2 #2/#3/#5
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`] — `deferred-17` D1; `deferred-18` D3
- [Source: `_bmad-output/implementation-artifacts/skillars-uat-1-admin-bootstrap-and-onboarding-unblock.md`] — the DST guard this story must preserve; the `V92`/fixture lesson; the i18n and Prettier decisions
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-19-test-context-container-consolidation.md`] — `AbstractIntegrationTest` and the `IntegrationTestConventionTest` guardrail
- [Source: `_bmad-output/project-context.md`] — module boundaries, DTO/record rules, testing rules
- [Source: `src/main/resources/db/migration/V87__booking_overlap_exclusion_constraint.sql`] — why AC4 does not add a create-time cross-booking overlap check
- Product decision on session length: Mbah, 2026-08-10 — system-wide default of one hour, coach-overridable

## Dev Agent Record

### Agent Model Used

Claude Opus 5 (`claude-opus-5`), bmad-dev-story workflow.

### Debug Log References

- **Mutation check (Task 3, prescribed).** Disabling the `createBookingRequest` duration check
  (`if (false && !requestedDuration.equals(requiredDuration))`) fails **three named tests**:
  `createBookingRequest_longerThanTheCoachSessionLength_isRejected`,
  `createBookingRequest_shorterThanTheCoachSessionLength_isRejected` and
  `createBookingRequest_coachOverrideOfNinety_acceptsNinetyAndRejectsSixty`. All three assert
  `hasMessageContaining("session length")`, so they discriminate against the *other* exception the
  unfixed code throws later on the same request rather than merely against "something threw".
  Restored and re-verified green.
- **Mutation check RE-RUN on 2026-08-11**, because the review audit split the third test above into
  `_acceptsNinety` / `_rejectsSixty` and a renamed test would otherwise leave the record above
  unverifiable. Same mutation, same result: **three** named tests fail —
  `_longerThanTheCoachSessionLength_isRejected`, `_shorterThanTheCoachSessionLength_isRejected`,
  `_coachOverrideOfNinety_rejectsSixty`. Restored; `BookingServiceTest` 23/23 green.
- **Second mutation check, 2026-08-11 (review audit).** The audit rewrote
  `computeAvailableSlots_partialOverlap_slicesBothSidesOfTheBlock` specifically because its old
  fixture could not fail against a regression to whole-segment behaviour. Verified by making that
  regression: emitting `[segStart, segEnd]` instead of slicing fails **12** of
  `AvailabilityServiceTest`'s 21 tests, the rewritten one among them (its old fixture would have
  passed). Restored; 21/21 green.
- **Redis uid verified from the image, not assumed** (AC6b explicitly required this):
  `docker run --rm redis:7-alpine id redis` -> `uid=999(redis) gid=1000(redis)`. `provision.sh`
  uses `chown -R 999:1000`.
- **`docker compose config` on both merges** (Task 6 gate): `docker-compose.yml` +
  `docker-compose.uat.yml` resolves, `skillars-uat-minio` still merges, and `redis-data` appears
  **zero** times. `docker-compose.yml` + `docker-compose.local.yml` resolves with redis bound to
  the new `skillars-local-redis` named volume.
- **`bash -n deploy/provision.sh`** clean after relocating the acme.json block.

### Completion Notes List

**All 7 ACs implemented. Full `mvn -o verify` BUILD SUCCESS (08:57).**

**Test totals, reported separately and derived by diffing, not subtracting.** **846 unit + 866 IT,
0 failures, 0 errors.** Contribution derived by diffing `@Test` counts against `HEAD` per file:
`AvailabilityServiceTest` 14->21 (+7), `BookingBatchServiceTest` 13->18 (+5), `BookingServiceTest`
19->22 (+3), `RescheduleServiceTest` 12->15 (+3), new `SessionDurationResolverTest` (+5) =
**+23 unit**; `CoachProfileBuilderIT` 26->29 = **+3 IT**. That reconciles exactly with the story's
`8a76652` baseline of 823 unit + 863 IT, independently confirming it. **No class silently stopped
running:** 138 `*IT.java` sources minus 4 abstract bases = 134 concrete, and the 135 failsafe
reports contain exactly one extra — a stale `ProbeIT` report timestamped 15:15, before this session
began and with no corresponding source file. Its 1 test is excluded above; the 866 figure is
failsafe's own count for this run, not a sum of report files.

**Regression surface — all eleven named files walked, plus a twelfth the story did not name.**

| File | Needed a fixture change? | Why |
|---|---|---|
| `BookingServiceTest` | **Yes** — resolver mock + lenient 60-min stub | Constructs `BookingService` by hand; `makeValidRequest` was already exactly 1h, so only the new collaborator needed wiring |
| `BookingBatchServiceTest` | **Yes** — 2 new `@Mock`s + lenient stubs | `@InjectMocks` needs `SessionDurationResolver` and `CoachAvailabilityWindowRepository`; `buildRequest` slots were already 1h and non-overlapping |
| `BookingBatchResourceIT` | **Yes** — seeded a window, anchored the slot base | Never seeded `coach_availability_windows` at all, and AC4 adds the window check. Base moved from `Instant.now().plus(3, DAYS)` (arbitrary time of day) to 09:00 Europe/Berlin so an 08:00–18:00 window is deterministic. Only ONE of its tests actually reaches `createBatch`; the 401/403 pair fails at auth and the six-slot test at `maxSize` |
| `BookingRequestResourceIT` | **No** | Every slot is already `nextDaySlot.plusHours(1)`, and it already seeds a window |
| `BookingServiceConcurrencyIT` | **No** | `slotEnd = nextDaySlot.plusHours(1)` |
| `SuspendedCoachBookingBlockIT` | **No** | 1h slots, seeds a window, and only uses `accept-all` on the batch side |
| `RescheduleServiceTest` | **Yes** — but for a real reason, not the duration rule | Two fixtures built start and end from **two separate `Instant.now()` calls**, making the booking 1 hour *plus a few microseconds*; AC3's exact same-duration rule then rejected every 1h proposal. Anchored both bounds to one instant. Largely-unaffected was otherwise correct |
| `RescheduleResourceIT` | **No** | Confirmed: every proposal is `proposedStart.plus(1, HOURS)` against a 1h booking, which same-as-original accepts unchanged — exactly as the story predicted |
| `BookingRepositoryIT` | **No** | Inserts rows directly, bypassing all three enforcement points |
| `BookingDuplicationServiceTest` | **No** | AC3 deliberately exempts that path |
| `AvailabilityServiceTest` | **Yes** — 4 tests rewritten, 8 added | Affected by AC2's slicing, not by the duration checks, as predicted |
| **`ExpiredPackBookingValidationTest`** (payment) | **Yes** | **Twelfth file, not in the story's list of eleven.** It hand-constructs `BookingService`, so it broke on the new constructor arg and then on an unstubbed resolver. Fixtures were already exactly 10:00–11:00; only stubs were added |

**Three findings the spec did not anticipate, all found by running things rather than reasoning:**

1. **`docker-compose.local.yml` never overrode redis's volume.** AC6b replaces the `redis-data`
   named volume with an absolute bind mount at `/opt/skillars/data/redis` — a *production* path.
   The local override file overrides postgres, loki, tempo, prometheus, grafana and minio but had
   never needed one for redis, so as specified this change would have created
   `/opt/skillars/data/redis` on every developer's laptop. Added a `redis` override with a
   `skillars-local-redis` named volume, matching the pattern the other five already use, and
   recorded the general hazard as deferred D7.
2. **`RescheduleServiceTest`'s two-`Instant.now()` fixtures** (above). This is the "prefer fixing
   the fixture over relaxing the check" case the Dev Notes name — a nanosecond-tolerant comparison
   would have been the wrong fix and would have silently weakened the rule.
3. **The story's doc list for AC6 was incomplete.** It named `deploy/traefik/README.md`,
   `traefik-tls.md` and `backup-restore.md`. `backup-restore.md` turned out **not** to name the
   acme.json path (no change needed); `first-time-setup.md` did (2 references). Two further files
   needed changes for AC6**b** rather than AC6a — neither mentions acme.json at all: `runbook.md`
   (4 `redis-data` references, including a `docker volume prune` safeguard that is now actively
   misleading, since the production stack no longer declares *any* named volume) and
   `local-deployment.md` (the now-removed `redis-data` declaration). All updated. *(Wording
   corrected 2026-08-11 during the review audit — the original note lumped all three under
   acme.json; the same inaccurate sentence in `sprint-status.yaml` was corrected with it.)*

**One deliberate addition beyond the spec:** `computeAvailableSlots` throws
`IllegalArgumentException` on a non-positive `slotLength`. `getBoundedLong` already prevents it in
production, but the method is package-private and directly test-driven, and the failure mode
without a guard is an **infinite loop** in the availability endpoint rather than an error. It
immediately earned its place: it caught four `getAvailabilityCalendar` tests whose resolver mock was
unstubbed, which would otherwise have hung the suite.

**Decisions taken, with reasoning:**

- **AC2's per-segment grid anchoring kept and pinned by a test**
  (`computeAvailableSlots_offGridBlock_anchorsThePostBlockRunToTheBlockEnd`), because it is the kind
  of thing a later reader will "tidy" into a window-anchored grid. Its consequence — slot start
  times shift when a coach edits a block — is recorded as deferred D3 rather than pretended away.
- **The two divergent-zone `AvailabilityServiceTest` cases were re-expressed at 60 minutes rather
  than given a 15-minute resolver to preserve their old assertions verbatim.** Both remain
  discriminating: without the two-day fetch pad the Kiritimati window slices into 10:00 and 11:00
  slots, and the shipped assertions require the 10:00 one to be **absent**. A 15-minute length would
  have kept the diff smaller while testing a length no coach can select.
- **`AvailabilityResourceIT`'s carve-out test now asserts both halves** — seven slots, *and* that
  10:00Z is absent while 09:00Z and 11:00Z are present. A bare `hasSize(7)` would pass against
  several wrong implementations.
- **`BookingBatchServiceTest` gained a back-to-back test.** Slots that merely touch (one ends exactly
  where the next starts) must be accepted; the overlap check uses `isBefore`, and a `!isAfter` typo
  would reject every legitimate consecutive batch. Nothing else pinned that boundary.

**Known residual, deliberately not closed (AC5 requires it be noted):** the backend's week bounds
come from `windows.get(0).getCanonicalTimezone()` while `coachTimezone` in the response is the
*profile* column. These are independently writable and can diverge — `deferred-17` D8 /
`deferred-18` D2, both still open, both blocked on a migration and a product decision. AC5 uses the
profile zone, which is correct for every coach whose two columns agree; after `uat-1` AC4 that is
every new coach. No third rule was invented.

**Frontend verified by code reading and a successful `quasar build` ONLY — there is no frontend test
suite, so CI cannot cover any of it.** The behaviours needing a human or browser-tooled spot-check:

1. `ProfileBuilderStep3.vue` — the session-length `q-select`, that "Use platform default (60 min)"
   submits `null`, and that a previously-set override can be cleared back to inherit through the UI.
2. `BookingRequestPage.vue` — that a parent's own `REQUESTED` booking renders as a **disabled** row
   with a `BookingStateChip`, sorted into the grid by start time, and that it neither enters the
   batch basket nor counts toward `batchAtMax`.
3. `BookingRequestPage.vue` — the coach-timezone week bounds, with a booking on the **last day of
   the displayed week** and a coach zone several hours from the browser's. The backend half of this
   is unverifiable by any existing test.
4. `BookingRequestPage.vue` — that a coach the parent has never booked renders exactly as before,
   and that a `parentBookings` fetch failure does not blank the slot list (guarded on the array,
   not on `bookingsError`).
5. `ParentBookingsPage.vue` — that the reschedule end input is read-only and tracks
   start + the booking's own duration, including for a legacy booking of a non-60-minute length.

**Prettier, reported honestly.** `BookingRequestPage.vue` was Prettier-**clean** at `HEAD` and my
edits broke it, so I ran `prettier --write` on that file alone and it is clean again. The other six
touched frontend files (`ParentBookingsPage.vue`, `ProfileBuilderStep3.vue`, all four
`i18n/*/index.js`) were **already failing at `HEAD`** — verified by piping the `HEAD` copy through
`prettier --check` before comparing — and were left as-is, the same call `uat-1` made, so the diff
is not buried in an unrelated reformat. This remains `uat-1` D5: `project-context.md` calls Prettier
mandatory, so the repo violates its own rule and wants a dedicated sweep. ESLint exits 0.

**Not verified:** no live application run. `V93` was exercised by Flyway in every integration test
(so the migration applies cleanly and `platform_config` id 603 does not collide), but no UAT box was
touched — the AC6 acme.json relocation and its in-place `mv` migration note are reasoned from
`provision.sh` and `docker compose config`, not from a real deploy.

### File List

All paths relative to the repository root.

**Added (2)**

- `src/main/resources/db/migration/V93__session_duration.sql`
- `src/main/java/com/softropic/skillars/platform/booking/service/SessionDurationResolver.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/SessionDurationResolverTest.java`

**Modified — backend main (7)**

- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/contract/ProfileBuilderStep3Request.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachPricing.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java`

**Modified — tests (7)**

- `src/test/java/com/softropic/skillars/platform/booking/service/AvailabilityServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/AvailabilityResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingBatchResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/marketplace/api/CoachProfileBuilderIT.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/ExpiredPackBookingValidationTest.java`

**Modified — frontend (7)**

- `src/frontend/src/components/profileBuilder/ProfileBuilderStep3.vue`
- `src/frontend/src/pages/parent/BookingRequestPage.vue`
- `src/frontend/src/pages/parent/ParentBookingsPage.vue`
- `src/frontend/src/i18n/en/index.js`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/de/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`

**Modified — ops / CI (4)**

- `docker-compose.yml`
- `docker-compose.local.yml` — *not in the story's predicted list; see Completion Notes finding 1*
- `deploy/provision.sh`
- `.github/workflows/ci.yml`

**Modified — docs (6)**

- `deploy/traefik/README.md`
- `docs/deployment/traefik-tls.md`
- `docs/deployment/uat-deployment.md`
- `docs/deployment/first-time-setup.md` — *not in the story's predicted list*
- `docs/deployment/runbook.md` — *not in the story's predicted list*
- `docs/deployment/local-deployment.md` — *not in the story's predicted list*
- `docs/deployment/backup-restore.md` — **not modified**: verified it does not name the acme.json
  path, so the story's conditional ("if it names the old path") did not fire

**Modified — BMAD artifacts (3)**

- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/uat-readiness-priorities.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

| Date | Change |
|---|---|
| 2026-08-10 | Story implemented. AC1: `V93__session_duration.sql` (nullable `marketplace.coach_pricing.session_duration_minutes` + `booking.session.defaultDurationMinutes` at `platform_config` id 603) and `SessionDurationResolver`; Profile Builder Step 3 gained a session-length selector in all four i18n bundles. AC2: `computeAvailableSlots` slices free segments into fixed-length slots, resolved once per request. AC3: exact-duration check on `createBookingRequest`, same-as-**original**-duration check on `requestReschedule`, and a derived read-only reschedule end input. AC4: the batch path gained the duration check, the availability-window check it had never had, and an intra-batch overlap check. AC5: a parent's own blocking bookings render as disabled rows merged into the slot grid, with week bounds anchored in the coach's timezone. AC6: `acme.json` and redis moved onto the Hetzner Volume (with the `provision.sh` block relocated past the mount), plus a `latest` image tag. AC7: ledger updated. |
| 2026-08-10 | Full `mvn -o verify` BUILD SUCCESS (08:57) — 846 unit + 866 IT, 0 failures, 0 errors. ESLint clean; `quasar build` succeeded. Mutation check on the AC3 create-path duration check performed and recorded (3 named tests fail without it). |
| 2026-08-11 | **Review audit.** All 20 open `[Patch]` findings across Groups A–D re-verified against the working tree and actioned: 18 patched, 1 rejected on a false premise (the batch overlap check is correct for arbitrary durations; only its missing comment was real), and 1 found to understate a real defect — the redis `mkdir`/`chown` sat inside `provision.sh`'s volume-only branch, so a host with no Hetzner Volume attached would have got a root-owned redis directory and a crash-looping redis; it was moved into the unconditional section 7.5 beside acme.json. Two rewritten tests were mutation-checked rather than merely re-run. Unit totals: +2 tests (`BookingBatchServiceTest` 18→19, `BookingServiceTest` 22→23, `AvailabilityServiceTest` unchanged at 21); IT: `CoachProfileBuilderIT` 29→30. New i18n key `booking.reschedule.endDerivedLengthUnavailable` in all four bundles. |
