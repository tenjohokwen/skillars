# Story Deferred-65: Pack-Selection Parity, Timezone-Validation Strictness & Availability Week-Scoping Fixes

Status: done

## Story

As an engineer operating this platform,
I want four items from `deferred-work.md` shipped together — aligning session-pack deduction order
with what parents already see displayed, tightening `@IanaTimezone` to reject non-IANA fixed offsets,
fixing the arbitrary per-request timezone an availability calendar uses to compute its week boundaries,
and adding a timezone hint to the reschedule dialog —
so that the Booking/Availability/Reschedule module's decision-light and freshly-decided backlog keeps
draining in the same disciplined, one-bundled-story-at-a-time way the `skillars-deferred-*` series has
followed since `skillars-deferred-59`.

### Why this story exists

This story was scoped by re-mining `deferred-work.md` in full (1570 lines, every section read, not just
the recent tail) specifically for items touching the Booking/Availability/Reschedule module, immediately
after `skillars-deferred-64` (still `review`, not yet merged as of this writing). Every open-looking item
was live-verified against the current tree rather than trusted from ledger text — this file's own
repeatedly-stated convention.

**Most of the module's freshest, highest-value items are not available to pick up here** — they were
already decided directly with the project owner during `skillars-deferred-64`'s own creation pass
(2026-08-25) and are recorded there as deliberate "leave as-is" outcomes: whether a coach can contest a
dispute (no), whether `ProfileBuilderStep4.vue`'s per-window timezone picker should stop drifting from the
coach profile (no — deliberate feature), whether `NO_SHOW_COACH` should fire from `IN_PROGRESS` (no),
whether overnight availability windows should be supported (no), and whether `duplicateNextWeek`'s
DST wall-clock shift is worth fixing (no). This story does not re-open any of those five.

**Two older items were found to be effectively resolved already, just never annotated** — closed by this
story's own AC5 (ledger hygiene) rather than picked up as fixes:

- `skillars-3-3` Group B's "no duplicate-booking guard for same slot" (multiple `REQUESTED` bookings for
  the same player/coach/timeslot). `V87__booking_overlap_index.sql`'s sibling migration comment (the
  `excl_bkg_coach_slot_overlap` constraint) documents this as a **deliberate** Story 3.11 follow-up
  decision: "`REQUESTED` is deliberately excluded here: two overlapping `REQUESTED` bookings competing for
  the same slot is expected, in-band behavior... not a data-integrity violation." The 2026-06-15 item's
  concern was already decided, just at a different call site (the DB constraint) than the item's own text
  anticipated (an app-layer unique index).
- `skillars-7-2` Group 4 D15 ("past-elapsed `requestedStartTime` at `CANCEL_PARENT` gives `NONE` refund
  eligibility — correct path is `NO_SHOW_COACH`"). Superseded by `skillars-deferred-64` AC4's product
  decision: a late `cancelBookingAsParent` is now refund-eligible on its own terms (widened
  `refundEligible`), explicitly **not** by converting to a no-show event. D15's premise (route it through
  `NO_SHOW_COACH`) was the option the project owner rejected.

**Four items are genuinely open and are this story's four ACs.** Three needed a decision from the project
owner, made directly in a round of questions during this story's creation (2026-08-25):

- **Pack-selection mismatch** (`skillars-11-2` D2 / `skillars-deferred-11` D2): a player+coach pair with
  2+ simultaneously-active packs is deducted from oldest-created-first by the backend but displayed as
  soonest-expiring-first by the frontend. **Decision: the backend changes to match the frontend** —
  deduct soonest-expiring first (see AC1).
- **`@IanaTimezone` strictness** (`skillars-deferred-18` review D4): the validator accepts fixed offsets
  (`"+01:00"`), not just real IANA region ids, reversing the codebase's own 2026-08-07 scope decision.
  **Decision: tighten it — but tighten-only, no audit/backfill of already-stored non-conforming values**
  (see AC2).
- **Availability week-scoping arbitrary zone** (`skillars-deferred-18` review D2): `getAvailabilityCalendar`
  derives its outer timezone from `windows.get(0)` of an *unordered* list, which is now unblocked by
  `skillars-deferred-63`/`-64`'s decision that per-window coach timezones are a deliberate feature (so this
  arbitrariness is a live, standalone bug, not something waiting on a bigger reconciliation). **Decision:
  the coach profile's own `canonical_timezone` is authoritative for week-scoping, not any window's** (see
  AC3).

The fourth is decision-light, a plain UX gap open since 2026-06-16 (`skillars-3-8` D7):

- **Reschedule dialog has no timezone hint** — see AC4.

## Acceptance Criteria

1. **`SessionPackPurchaseRepository.findActivePacks` orders by soonest-expiring first, not
   oldest-created-first, so the pack the backend deducts matches the pack the frontend already displays
   as "current."**
   Today (`[src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java:36-45]`)
   the query is `ORDER BY p.createdAt ASC`. This single method backs three
   `PackSessionService` methods (`[src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java:82-110]`):
   `hasActivePack` (existence only, unaffected by ordering), `getActivePackId` (`packs.get(0)`, used by
   `HomeworkAssignmentService.resolvePackId` — `[src/main/java/com/softropic/skillars/platform/session/service/HomeworkAssignmentService.java:168-170]`,
   itself called only from `handleBookingCompleted` to tag which pack a homework assignment is associated
   with — no session/credit is deducted through this path), and `findActivePackId` (`packs.get(0)`, used by
   `BookingDuplicationService.duplicateNextWeek` — `[src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java:114]`
   — this is the one real "which pack gets charged" call site outside the normal booking-request flow).
   The normal create-booking-request flow is **not** affected: `BookingRequestPage.vue` already lets the
   parent explicitly pick a pack from a radio group of every active pack for that coach
   (`[src/frontend/src/pages/parent/BookingRequestPage.vue:122-125,271-279,484]`,
   `selectedPackId` sent verbatim as `sessionPackPurchaseId`), so `findActivePacks`' ordering never enters
   that decision. The mismatch is specifically: `SessionPackPurchasePage.vue`
   (`[src/frontend/src/pages/parent/SessionPackPurchasePage.vue:124-134]`) and `ParentPlayerPortalPage.vue`
   (identical tiebreak, same comment cross-reference) both compute `currentPack` as the
   soonest-`expiresAt` pack for display, while `duplicateNextWeek`'s actual charge goes to whatever
   `createdAt ASC` puts first — which can be a *different* pack than the one the parent was just shown.
   **Decision (2026-08-25): change the backend to match the display** — "use it or lose it" (consuming the
   soonest-expiring pack first) is also better practice than FIFO, since it reduces credits expiring
   unused.
   **Fix:** change the `ORDER BY` clause to `p.expiresAt ASC, p.createdAt DESC`, not `p.expiresAt ASC`
   alone. **Story-review finding (2026-08-25):** two active packs can share an identical `expiresAt`
   (e.g. two same-tier packs bought for the same coach on the same day), and Postgres gives no ordering
   guarantee among tied rows without a secondary sort key. The frontend's `currentPack` tiebreak
   (`SessionPackPurchasePage.vue:126-134`/`ParentPlayerPortalPage.vue`,
   `reduce((soonest, p) => expiresAt(p) < expiresAt(soonest) ? p : soonest)` over an array from
   `findByParentIdOrderByCreatedAtDesc` — verified, `SessionPackPaymentService.java:84-87`) keeps the
   first-encountered element on a tie, which is deterministically the **newest-created** pack. The added
   `p.createdAt DESC` secondary key mirrors that exactly, so a tie resolves to the same pack on both sides
   instead of reopening the mismatch AC1 exists to close, just narrowed to the tie case. (Not a new
   regression — the old `createdAt ASC` clause had the same class of gap, untested either way — but cheap
   to close while this clause is already being touched.)
   Update the stale ordering-focused
   comments/Javadoc that reference "oldest"/`createdAt` (the Javadoc at
   `PackSessionService.java:96-100` describing `findActivePackId`, and any inline comment in the
   repository referencing `createdAt` ordering intent). **Do not touch**
   `findTopByPlayerIdAndCoachIdOrderByCreatedAtDesc`
   (`[SessionPackPurchaseRepository.java:47]`) — that is `getActivePackId`'s fallback for when
   **no active pack exists at all** (a "most recently created, possibly exhausted, pack" informational
   fallback), an unrelated question from "which of several active packs is deducted first."
   **Tests:** `SessionPackPurchaseRepositoryIT.findActivePacks_returnsOldestCreatedAtFirst`
   (`[src/test/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepositoryIT.java:48-108]`)
   is the real-database proof of the current ordering and must be rewritten (rename to
   `findActivePacks_returnsSoonestExpiringFirst`): seed two packs whose `createdAt` and `expiresAt`
   orderings are **inverted** relative to each other (e.g. the pack created first has the *later*
   `expiresAt`), exactly mirroring this test's existing "insertion order is deliberately the reverse of
   expected order" technique — so the assertion only passes under the new `ORDER BY p.expiresAt ASC, ...`
   and fails under the old clause, the same rigor the existing test applies to `createdAt`. **Add a second
   new test** for the tie-break secondary key: two packs with an identical `expiresAt` but different
   `createdAt`, asserting the newer-`createdAt` one comes first — this is the only thing that actually
   proves the `p.createdAt DESC` secondary key (above) is present and correctly ordered, since the primary
   test's distinct `expiresAt` values never exercise it.
   `PackSessionServiceParityTest`
   (`[src/test/java/com/softropic/skillars/platform/payment/service/PackSessionServiceParityTest.java]`)
   mocks `findActivePacks`'s return order directly and needs no behavior change, but **story-review
   correction (2026-08-25):** there is exactly **one** such comment, at lines 75-76 (not "approximately
   75 and 90" as an earlier draft of this AC said) — `// findActivePacks is queried ORDER BY createdAt
   ASC...` — correct it to describe the new `expiresAt ASC, createdAt DESC` ordering. Additionally, two
   test methods near it — `getActivePackId_activePackExists_returnsFirstResultFromFindActivePacks`
   (around line 82) and `findActivePackId_activePackExists_returnsFirstResultFromFindActivePacks` (around
   lines 115-118) — use local variable names like `oldestId`/`older`/`newer` to label the pack expected
   first; both tests mock `findActivePacks`'s return order directly so they pass unchanged, but rename
   these variables (e.g. to `expectedId`/`firstPack`/`secondPack`) since "first returned" is no longer "the
   older one," and the stale names would mislead a future reader.
   `BookingDuplicationServiceTest` and `HomeworkAssignmentServiceTest` mock at the `PackSessionService`
   method boundary (`findActivePackId`/`getActivePackId` directly) and need no change.

2. **`@IanaTimezone` requires a genuine IANA region id, rejecting fixed offsets like `"+01:00"` or
   `"UTC+02:00"`.**
   Today (`[src/main/java/com/softropic/skillars/infrastructure/validation/IanaTimezoneValidator.java]`)
   the check is bare `ZoneId.of(value)` inside a try/catch on `DateTimeException` — `ZoneId.of` also
   accepts fixed offsets and other non-region forms (`"Z"`, `"GMT+2"`), which are DST-blind. The
   `@IanaTimezone` annotation's own Javadoc
   (`[src/main/java/com/softropic/skillars/infrastructure/validation/IanaTimezone.java]`) documents this as
   a deliberate 2026-08-07 scope decision. **Decision (2026-08-25): reverse that decision and tighten —
   tighten-only, no audit or backfill of any already-stored non-conforming value.**
   **Fix:** change the validity check to also require `ZoneId.getAvailableZoneIds().contains(value)`
   (verified experimentally on this project's JDK 17: this set contains `"Europe/Berlin"` and `"Etc/UTC"`
   but not `"+01:00"`, `"UTC+02:00"`, `"GMT+2"`, or `"Z"` — exactly the fixed-offset forms this AC exists to
   reject). **Do not use `ZoneId.of(v) instanceof java.time.ZoneRegion`**, the alternative the ledger item's
   own text also floats — `ZoneRegion` is package-private in `java.time` (verified: `javac` rejects any
   `import java.time.ZoneRegion` or `instanceof ZoneRegion` from outside that package), so it does not
   compile from this codebase's packages. Keep `ZoneId.of(value)` as the first check (still needed to catch
   outright garbage inside a `DateTimeException` catch, exactly as today) and add the
   `getAvailableZoneIds().contains(value)` check alongside it — a value must pass both. Note
   `getAvailableZoneIds()` still contains legacy no-slash aliases like `"Navajo"` (verified: `contains("Navajo")`
   is `true`) — this AC does not reject those, since they resolve through real (if deprecated) DST rules and
   are not the DST-blind case this item is about; only fixed-offset forms are newly rejected. Update
   `IanaTimezone.java`'s Javadoc (currently
   documents the 2026-08-07 looseness as current behavior) and the validator's own inline comment
   ("Wording deliberately says 'recognized', not 'valid IANA'") to reflect the new, stricter contract —
   the message wording itself may now honestly say IANA, or stay neutral; either is fine, just keep the
   comment and the actual behavior consistent.
   **Verify (do not change) this is safe for the picker-driven write paths:**
   `CoachProfileService.getSupportedTimezones()`
   (`[src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:83-131]`)
   already allow-lists ~486 real `Continent/City` region ids plus one explicit `"Etc/UTC"` addition for the
   `ProfileBuilderStep1`/`Step4` dropdowns — none of those are fixed offsets, so every option the picker
   can submit continues to validate after this tightening. That method's own Javadoc line 117-119
   ("`IanaTimezoneValidator` stays permissive on purpose (2026-08-07 decision)...") becomes stale once this
   AC ships and should be corrected to reflect the new decision.
   **Resolves the ledger item's own dangling "Pairs with D5" cross-reference (story-review finding,
   2026-08-25).** `skillars-deferred-18` review D4's text ends "Pairs with D5: tightening this makes D5
   strictly worse" — D5 no longer exists in `deferred-work.md` (closed and deleted when
   `skillars-deferred-18` shipped), so this AC's own re-mine never encountered it directly. D5 was: the
   profile builder used to hard-400 on any zone the JVM's tzdb didn't recognize (a browser on newer tzdata
   than the deployed JVM, e.g. `Europe/Kyiv`, could permanently lock a coach out of finishing the
   builder) — tightening the validator would have narrowed that escape hatch further. **This is already
   moot, not something this AC needs to additionally guard against:** `CoachProfileService
   .getSupportedTimezones()` (`:83-131`) shipped the exact fix D5 itself asked for — a server-side
   allow-list dropdown offering only zones the deployed JVM recognizes — so no coach can submit an
   out-of-tzdata zone through the picker regardless of validator strictness. D5's concern and this AC's
   tightening are now fully decoupled.
   **Accepted consequence, not a defect to work around:** an existing coach whose stored
   `canonicalTimezone` predates the dropdown (a legacy alias or fixed offset — the same Javadoc explicitly
   acknowledges "a coach whose zone was stored as `Navajo` or `+01:00` before this shipped") is untouched by
   this AC (no backfill, per the decision) and stays that way — nothing revalidates a stored value except a
   fresh write through `ProfileBuilderStep1Request`/`Step4Request`. **Story-review correction (2026-08-25):**
   an earlier draft of this AC illustrated this with a resubmission scenario that does not exist today —
   verified neither `ProfileBuilderStep1.vue` nor `ProfileBuilderStep4.vue` ever prefills its
   `canonicalTimezone` ref from a coach's already-saved value (both start `null`/from the current
   onboarding session's own fresh pick), and `UpdateWindowRequest`
   (`AvailabilityResource.java:57-63` → `AvailabilityService.updateWindow`, the only endpoint for editing an
   existing window) carries no `canonicalTimezone` field at all — so there is no live UI action that
   resubmits an existing stale value unchanged and gets newly rejected. The tightened validator only ever
   sees a genuinely fresh value, which can only come from the region-only dropdown. Existing bad rows
   simply sit untouched, exactly per the no-backfill decision — there is no reachable reject-on-resubmit
   flow to regression-test.
   **Tests:** no unit-test class exists for this validator today (mirror the sibling pattern at
   `[src/test/java/com/softropic/skillars/infrastructure/validation/CamMobileValidatorTest.java]`) — add
   `IanaTimezoneValidatorTest.java` in the same package, parameterized: valid region ids (including
   `"Etc/UTC"`) still pass; fixed offsets (`"+01:00"`, `"+05:30"`, `"UTC+02:00"`, `"GMT+2"`, `"Z"`) now
   fail; pre-existing garbage (`"Not/AZone"`) still fails; null/blank still passes. Extend
   `CoachProfileBuilderIT`'s two existing invalid-timezone tests — `saveStep1_invalidTimezone_returns400WithResolvedMessage`
   and `saveStep4_invalidWindowTimezone_returns400WithResolvedMessage`
   (`[src/test/java/com/softropic/skillars/platform/marketplace/api/CoachProfileBuilderIT.java:193,487]`,
   both currently exercise only `"Not/AZone"`) with an additional fixed-offset case each, asserting 400.

3. **`AvailabilityService.getAvailabilityCalendar` uses the coach profile's own `canonical_timezone` to
   compute week boundaries, instead of an arbitrary window's timezone picked from an unordered list.**
   Today (`[src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java:52-99]`)
   the method already loads `profile` at `:58` and already computes `coachTimezone` from
   `profile.getCanonicalTimezone()` at `:71-72` (with a `"UTC"` fallback for blank) — that value already
   feeds the response's own `canonicalTimezone` field. But the **separate** `timezone`/`zoneId` derivation
   at `:61-79`, which actually drives `weekStartInstant`/`weekEndInstant`/`weekStartExact`/`weekEndExact`
   (the fetch bounds for blocks/bookings and the exact-week filter for `blockResponses`), instead reads
   `windows.get(0).getCanonicalTimezone()` (`:63`) — and `windowRepository.findByCoachId` issues no
   `ORDER BY` (`CoachAvailabilityWindowRepository`), so which window is "first" is row-order luck. For a
   coach with windows in two timezones, two identical requests can return different week boundaries and a
   different `blocks` set purely from that luck.
   **Decision (2026-08-25): the coach profile's `canonical_timezone` is authoritative for this purpose.**
   Per-window timezone divergence remains a deliberate feature (`skillars-deferred-63`/`-64`), so the
   fix is specifically about which value drives *week-scoping bounds*, not about eliminating divergence.
   **Fix:** remove the separate `windows.get(0)`-based `timezone` variable and its `windows.isEmpty()`
   branch (`:61-64`) entirely; compute `zoneId` directly from the already-computed `coachTimezone`
   (`:71-72`) instead, keeping the same try/catch → `"UTC"` fallback shape currently at `:74-79`. Nothing
   else changes: the 48-hour padding rationale at `:81-95` (covering divergence between the outer zone and
   each window's own zone) still applies unchanged — it now covers divergence between the coach's
   *profile* zone and each window's zone rather than between an arbitrary window's zone and every other
   window's zone, which is the same or a narrower gap, never wider. The per-window zone handling inside the
   day loop (`:122-186`, `:132-140`) is untouched — each window still resolves its own slots in its own
   zone exactly as today.
   **Explicit scope boundary (story-review finding, 2026-08-25) — do not claim this AC fully resolves the
   ledger item's "unordered `findByCoachId`" framing.** `CoachAvailabilityWindowRepository.findByCoachId`
   itself still issues no `ORDER BY` after this fix. That residual nondeterminism no longer affects
   week-scoping bounds (this AC's whole point), but it still affects `windowResponses` — the calendar's
   per-window listing returned to the frontend — which can still come back in a different row order
   between two identical requests. This is the same divergence class the ledger item itself calls "blocked
   on D8" and this story's own Dev Notes already exclude ("resist the urge to also fix per-window
   divergence"), so it is correctly out of scope here — but when AC5's `[PICKED UP by skillars-deferred-65
   AC3]` tag on this item is flipped to `[CLOSED by ...]` after this AC ships, the closing annotation must
   say so was fixed **for week-scoping only**, not describe the ledger item as fully resolved.
   **One intended behavior change to call out explicitly, not a regression:** a coach with **zero**
   configured availability windows previously got week-scoping computed in plain `"UTC"` (the
   `windows.isEmpty() ? "UTC" : ...` branch). After this fix, it's computed in that coach's own profile
   `canonicalTimezone` instead — which is what the response's `canonicalTimezone` field already always
   reported for that coach regardless. This removes a latent internal inconsistency; it is not a defect.
   **Tests:** three existing `AvailabilityServiceTest` cases currently rely on `windows.get(0)` driving the
   outer zone and must be updated — `getAvailabilityCalendar_padsFetchBoundsByOneDayEachSide_toCoverDivergentWindowZones`
   (`[src/test/java/com/softropic/skillars/platform/booking/service/AvailabilityServiceTest.java:348-383]`),
   `getAvailabilityCalendar_windowZoneDivergesWidelyFromOuterZone_blockStillSubtractedButStaysOutOfBlockResponses`
   (`:385-447`), and `getAvailabilityCalendar_bookingOnDivergentZoneWindow_excludedEvenBeyondOneDayOfPadding`
   (`:449-499`). `makeCoachProfile` defaults `canonicalTimezone` to `"Europe/Berlin"`
   (`:200-206`), so each of these three tests must now explicitly call `profile.setCanonicalTimezone(...)`
   with the same zone the test previously relied on `windows.get(0)` supplying (`"America/Los_Angeles"` for
   the pad test, `"Pacific/Niue"` for the two divergence tests) — this keeps every existing numeric/instant
   assertion in those tests unchanged while re-pointing the actual source of truth being exercised. Update
   each test's comments that say "`windows.get(0)` ... drives the OUTER zone" (around `:368-369`, `:388`,
   `:400`, `:464`) to instead say the coach profile's `canonicalTimezone` drives it. **Add one new
   regression test** proving the fix itself, not just preserving old assertions: seed multiple windows in
   different zones with `profile.canonicalTimezone` set to a third, distinct zone (or return the same
   windows in reversed order from the `windowRepository` mock across two otherwise-identical calls),
   asserting the outer fetch bounds always follow `profile.canonicalTimezone` and are invariant to window
   list order — the exact case that would have failed against the pre-fix `windows.get(0)` code.

4. **The reschedule dialog on `ParentBookingsPage.vue` tells the parent which timezone their typed
   "New session start" time is interpreted in.**
   Today (`[src/frontend/src/pages/parent/ParentBookingsPage.vue:93-116]`) the dialog's proposed-start
   `q-input` (`:100-101`, `type="datetime-local"`) has no timezone indication at all, while the read-only
   proposed-end field right below it already carries a `:hint` (`:105-107`,
   `t('booking.reschedule.endDerivedHint')`) explaining its own derivation. `datetime-local` always
   collects browser-local wall-clock time; the page already computes `browserTimezone`
   (`:135`, `Intl.DateTimeFormat().resolvedOptions().timeZone`) for its top-of-page `TimezoneNotice`, and
   each booking already carries its own `canonicalTimezone` (used throughout this same file, e.g. `:242`
   `firstBookingTimezone`, `:45,59-60` `formatDateTime(booking.requestedStartTime, booking.canonicalTimezone)`)
   — but `openRescheduleDialog(booking)` (`:174-193`) does not currently retain the booking's timezone in
   any dialog-scoped state.
   **Fix:** add a new ref (e.g. `rescheduleBookingTimezone`) set from `booking.canonicalTimezone` inside
   `openRescheduleDialog`, and add a `:hint` to the proposed-start `q-input` (`:100-101`) — mirroring the
   existing `endDerivedHint` pattern on the field just below it — stating the browser-local interpretation
   and the session's own timezone, using the already-available `browserTimezone` and the new
   `rescheduleBookingTimezone` ref. Add a new i18n key alongside the existing `reschedule` block
   (`[src/frontend/src/i18n/en-US/index.js:880-894]`, insert near `endDerivedHint` at `:885`) — e.g.
   `startTimezoneHint` interpolating `{browser}` and `{session}`, phrased consistently with the existing
   `booking.timezone.noticeDiffers` key's wording style (`[en-US/index.js:819-824]`) rather than inventing a
   new convention. Mirror the same key into `de-DE` and `fr-FR`'s equivalent `reschedule` blocks.
   **Tests:** this repository has no frontend test framework (`deferred-17` D6 already records this gap:
   no `*.spec.js` outside `node_modules`, no `src/frontend/test`) — no automated test to add. Verify
   manually via the `run` skill: open a booking's reschedule dialog and confirm the hint renders with both
   timezones.

5. **Ledger hygiene — already applied during this story's creation (2026-08-25), no dev-story work needed
   for this AC.** In `deferred-work.md`:
   - The `skillars-3-3` Group B "no duplicate-booking guard for same slot" bullet and the `skillars-7-2`
     Group 4 D15 bullet are both already annotated `[CLOSED ...]`, since their closure was a
     fact-finding correction (superseded by decisions already shipped elsewhere), not contingent on this
     story's own code.
   - The four source items this story picks up (`skillars-11-2` D2 / `skillars-deferred-11` D2,
     `skillars-deferred-18` review D2 and D4, `skillars-3-8` D7) are already tagged
     `[PICKED UP by skillars-deferred-65 ACn]`. **Once AC1-AC4 actually ship, flip each to
     `[CLOSED by skillars-deferred-65 ACn: ...]`** citing the exact fix, per this ledger's own established
     PICKED-UP-at-creation / CLOSED-at-shipment convention — do not flip them before the code lands.
   - Leave everything else exactly as found, in particular the five `[DECIDED 2026-08-25: ...]` items this
     story's own creation pass confirmed are `skillars-deferred-64`'s territory, not this story's — do not
     re-annotate or re-touch them.

## Tasks / Subtasks

- [x] Task 1: Pack-selection ordering fix (AC1)
  - [x] 1.1: Change `SessionPackPurchaseRepository.findActivePacks`'s `ORDER BY` to `p.expiresAt ASC`
  - [x] 1.2: Update stale `createdAt`/"oldest" comments in `PackSessionService` and
        `PackSessionServiceParityTest`
  - [x] 1.3: Rewrite `SessionPackPurchaseRepositoryIT.findActivePacks_returnsOldestCreatedAtFirst` →
        `findActivePacks_returnsSoonestExpiringFirst` with an inverted `createdAt`/`expiresAt` fixture
- [x] Task 2: `@IanaTimezone` strictness (AC2)
  - [x] 2.1: Tighten `IanaTimezoneValidator` to also require `ZoneId.getAvailableZoneIds().contains(value)`
  - [x] 2.2: Update `IanaTimezone` Javadoc and validator inline comments to match the new decision
  - [x] 2.3: Correct `CoachProfileService.getSupportedTimezones()`'s stale Javadoc reference to the
        2026-08-07 permissive decision
  - [x] 2.4: New `IanaTimezoneValidatorTest`; extend `CoachProfileBuilderIT`'s two invalid-timezone tests
        with a fixed-offset case each
- [x] Task 3: Availability week-scoping authoritative zone (AC3)
  - [x] 3.1: Replace `getAvailabilityCalendar`'s `windows.get(0)`-derived `timezone`/`zoneId` with one
        derived from the already-computed `coachTimezone` (profile-sourced)
  - [x] 3.2: Update the three affected `AvailabilityServiceTest` cases to set `profile.canonicalTimezone`
        explicitly and correct their stale comments
  - [x] 3.3: Add a new regression test proving week-scoping is invariant to window list order
- [x] Task 4: Reschedule dialog timezone hint (AC4)
  - [x] 4.1: Track the booking's `canonicalTimezone` in dialog-scoped state, set in `openRescheduleDialog`
  - [x] 4.2: Add a `:hint` to the proposed-start input; new i18n key in en-US, de-DE, fr-FR
  - [x] 4.3: Manual verification via the `run` skill (no frontend test framework exists)
- [x] Task 5: Ledger hygiene (AC5) — applied at story creation; the four `[PICKED UP]` tags still need
      flipping to `[CLOSED]` once AC1-AC4 ship

### Review Findings

Adversarial code review (Blind Hunter + Edge Case Hunter + Acceptance Auditor) run 2026-08-25 against the
implementation diff. Acceptance Auditor found zero AC violations. 4 `patch`, 3 `defer`, 11 dismissed as
noise (mostly Blind Hunter false positives from lacking project/DB-schema access — e.g. flagged
`booking.canonicalTimezone` as unguarded-nullable, verified `NOT NULL` in `V31`; flagged `browserTimezone`
as undefined, it's pre-existing unchanged code the diff hunk didn't show; flagged the `expiresAt` ORDER BY
as unindexed, `idx_session_pack_purchases_expiry_notify` already covers it since `V88`).

- [x] [Review][Patch] Stale test-name cross-reference in `SessionPackPurchaseRepositoryIT.java:171` still
      says `findActivePacks_returnsOldestCreatedAtFirst above` — that test was renamed to
      `findActivePacks_returnsSoonestExpiringFirst` by this story's own AC1 work.
      [`src/test/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepositoryIT.java:171`]
      — fixed, comment now references the current test name.
- [x] [Review][Patch] `IanaTimezoneValidator.fail(ConstraintValidatorContext)` uses no instance state and
      should be `private static`.
      [`src/main/java/com/softropic/skillars/infrastructure/validation/IanaTimezoneValidator.java:142`]
      — fixed.
- [x] [Review][Patch] No inline comment on `findActivePacks`'s `ORDER BY p.expiresAt ASC, p.createdAt DESC`
      explaining why the secondary key is `DESC` (mirrors the frontend's own newest-created-wins tiebreak on
      an `expiresAt` tie) — a future reader has no way to tell this was deliberate.
      [`src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java:41`]
      — fixed, added a rationale comment above the query.
- [x] [Review][Patch] Two active packs tied on both `expiresAt` AND `createdAt` still have no deterministic
      order (Edge Case Hunter finding) — add `p.purchaseId ASC` as a tertiary sort key to close the
      remaining theoretical nondeterminism completely while this clause is already open.
      [`src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java:41`]
      — fixed, added `p.purchaseId ASC` as a tertiary key. `mvn -o test -Dtest=IanaTimezoneValidatorTest,
      PackSessionServiceParityTest` (20/20 green) and `mvn -o test -Dtest=SessionPackPurchaseRepositoryIT`
      (3/3 green, real Testcontainers Postgres) both re-verified after all four patches.
- [x] [Review][Defer] `ZoneId.getAvailableZoneIds()` still contains DST-blind fixed-offset-equivalent forms
      outside `+HH:MM` notation (verified: `contains("Etc/GMT+1")` is `true`) that AC2's tightening does not
      reject — same accepted-gap class as the already-documented `Navajo` exception, but never named in AC2's
      text or test suite. [`src/main/java/com/softropic/skillars/infrastructure/validation/IanaTimezoneValidator.java`]
      — deferred, pre-existing scope decision (tighten-only, not exclusion-list), not a new bug from this diff.
- [x] [Review][Defer] Tightened validator's correctness now depends on `ZoneId.getAvailableZoneIds()`'s
      contents, which can change across JDK/tzdata updates — a value valid today could in principle stop
      validating after a routine JDK patch, with no stability-risk discussion anywhere.
      [`src/main/java/com/softropic/skillars/infrastructure/validation/IanaTimezoneValidator.java`]
      — deferred, architectural characteristic of the tighten-only design (the original loose validator had
      the same JDK-version dependency, just laxer), not introduced by this diff.
- [x] [Review][Defer] The reschedule dialog's paired "New session end" field is shown in the same
      browser-local `datetime-local` format as "New session start" but received no timezone-clarifying hint —
      only the start field was in AC4's scope. [`src/frontend/src/pages/parent/ParentBookingsPage.vue:105-107`]
      — deferred, real potential UX-consistency polish but explicitly out of this story's AC4 scope (which
      named only the start field, and end already has its own "derived from start" hint).

## Dev Notes

**AC1 and AC3 both change what a shared, previously-order-dependent value is derived from — read the
whole affected method before editing, not just the cited lines.** `findActivePacks` backs three different
`PackSessionService` callers with three different purposes (existence check, homework-tagging, and the
real duplication-charge path); only the last one is the actual product concern, but the fix applies
uniformly to the query since all three should agree on "the same pack" for consistency.

**AC2's tightening is safe for every current write path because the dropdown was already stricter than
the validator.** `CoachProfileService.getSupportedTimezones()` was already an allow-list of true region
ids before this story — this AC is closing a gap between the validator and a restriction the picker UI
already enforced, not introducing a new restriction the frontend has to catch up to. Do not add any new
frontend validation for this AC; none is needed.

**AC3 is a smaller change than it looks.** `AvailabilityService` already has every piece it needs
(`coachProfileRepository`, `profile`, `coachTimezone`) — this is a one-variable substitution, not a new
dependency or a new lookup. Resist the urge to also "fix" per-window divergence; that is explicitly a
deliberate feature per `skillars-deferred-63`/`-64` and out of scope here.

**AC4 has no backend component.** Do not touch `RescheduleService` or any `RescheduleRequest` contract —
this is purely a frontend label/hint addition using data the page already has.

**Existing test files to extend (do not create new ones for these classes) except where noted:**
`SessionPackPurchaseRepositoryIT.java` (AC1), `PackSessionServiceParityTest.java` (AC1, comments only),
`AvailabilityServiceTest.java` (AC3), `CoachProfileBuilderIT.java` (AC2). **New test class needed:**
`IanaTimezoneValidatorTest.java` (AC2) — no unit-test home exists for this validator today, mirror
`CamMobileValidatorTest.java`'s parameterized style.

### Project Structure Notes

Backend: `platform.payment.repo`/`platform.payment.service` (`SessionPackPurchaseRepository`,
`PackSessionService` — AC1), `infrastructure.validation` (`IanaTimezoneValidator`, `IanaTimezone` — AC2),
`platform.marketplace.service` (`CoachProfileService`, comment-only — AC2), `platform.booking.service`
(`AvailabilityService` — AC3). Frontend: `src/frontend/src/pages/parent/ParentBookingsPage.vue` and the
three `src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js` locale bundles (AC4). No new migrations — all
four ACs are code-only (query/validator/logic/UI changes, no schema change).

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` — source of all four items; see this story's
  own creation-time investigation above for exact sections and the AC5 ledger-hygiene closures.
- `skillars-deferred-63` AC6, `skillars-deferred-64` AC1/AC7 — the "per-window coach timezone is a
  deliberate feature" decision AC3 respects and does not re-litigate.
- `skillars-deferred-18` (2026-08-07 review) — the original scope decision AC2 reverses, and the source of
  AC3's `windows.get(0)`/D2 finding.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

None — no failing test needed root-causing during implementation. All targeted test runs passed on
first or second attempt (AC1's `SessionPackPurchaseRepositoryIT` rewrite and `IanaTimezoneValidatorTest`
both passed on the first run).

### Completion Notes List

- **AC1:** `SessionPackPurchaseRepository.findActivePacks` reordered to `ORDER BY p.expiresAt ASC,
  p.createdAt DESC`. Stale ordering comments corrected in `PackSessionServiceParityTest` (the one
  comment at lines 75-76, and two tests' `oldestId`/`older`/`newer` local variables renamed to
  `expectedId`/`firstPack`/`secondPack`). No stale "oldest"/`createdAt`-ordering comment was found in
  `PackSessionService.java` itself (`findActivePackId`'s Javadoc at :96-100 never described ordering
  semantics) — nothing to change there. `SessionPackPurchaseRepositoryIT.findActivePacks_returnsOldestCreatedAtFirst`
  renamed to `findActivePacks_returnsSoonestExpiringFirst` with an inverted `createdAt`/`expiresAt`
  fixture; added a new `findActivePacks_tiedExpiresAt_returnsNewestCreatedFirst` test proving the
  `p.createdAt DESC` secondary key. `mvn -o test -Dtest=PackSessionServiceParityTest` 7/7 green;
  `mvn -o test -Dtest=SessionPackPurchaseRepositoryIT` 3/3 green.
- **AC2:** `IanaTimezoneValidator` now also requires `ZoneId.getAvailableZoneIds().contains(value)` in
  addition to `ZoneId.of` parseability. `IanaTimezone.java`'s Javadoc and the validator's inline
  comments rewritten for the new, stricter contract; the validation failure message text kept
  unchanged (neutral wording) to avoid touching the four `messages*.properties` i18n bundles and the
  existing `CoachProfileBuilderIT` message-content assertions, since the story explicitly allows either
  wording choice. `CoachProfileService.getSupportedTimezones()`'s stale Javadoc reference to the
  2026-08-07 permissive decision corrected. New `IanaTimezoneValidatorTest` (13 parameterized/plain
  cases: valid region ids, fixed offsets, outright garbage, null/blank) mirroring
  `CamMobileValidatorTest`'s style. `CoachProfileBuilderIT` gained one new fixed-offset test each for
  `saveStep1`/`saveStep4`, alongside the two pre-existing `"Not/AZone"` tests. `mvn -o test
  -Dtest=IanaTimezoneValidatorTest` 13/13 green; `mvn -o test -Dtest=CoachProfileBuilderIT` 34/34 green.
- **AC3:** `AvailabilityService.getAvailabilityCalendar`'s `windows.get(0)`-derived `timezone`/`zoneId`
  removed; `zoneId` now derives directly from the already-computed, profile-sourced `coachTimezone`.
  Three existing `AvailabilityServiceTest` cases updated to call `profile.setCanonicalTimezone(...)`
  explicitly (previously implicit via `windows.get(0)`) and their stale comments corrected. Added a new
  `getAvailabilityCalendar_outerFetchBoundsFollowCoachProfileZone_invariantToWindowListOrder` regression
  test: calls the service twice with the same two windows in opposite list order and asserts the outer
  fetch bounds are identical both times and follow the coach profile's zone, not either window's own
  zone — the exact case that would have failed against the pre-fix `windows.get(0)` code. `mvn -o test
  -Dtest=AvailabilityServiceTest` 22/22 green.
- **AC4:** Added `rescheduleBookingTimezone` ref to `ParentBookingsPage.vue`, set from
  `booking.canonicalTimezone` in `openRescheduleDialog`. Added a `:hint` to the proposed-start
  `q-input`, interpolating the already-available `browserTimezone` and the new ref via a new
  `booking.reschedule.startTimezoneHint` i18n key (en-US, de-DE, fr-FR). `npx eslint` clean on all
  touched frontend files. **Manual verification via the `run` skill** (no frontend test framework
  exists): stood up a full local stack (dockerized Postgres/Redis/MinIO + `mvn spring-boot:run` against
  the `dev` profile), seeded a parent/coach/booking directly via SQL (backend has no dev-data seeding
  mechanism), and drove the app with Playwright — logged in as the seeded parent, opened the reschedule
  dialog, and confirmed the hint renders both the browser timezone and the booking's session timezone.
  This surfaced a real, unanticipated layout defect not caught by ESLint or code review: the new
  two-line hint (Quasar's `.q-field__bottom` is absolutely positioned and does not reserve document-flow
  space for wrapped hint text) visually overlapped the "New session end" field below it. Fixed by adding
  `class="q-mb-lg"` to the proposed-start `q-input`, re-verified via a second Playwright pass with DOM
  geometry assertions (no overlap: first field's hint bottom at 404px, second field's top at 416px) and
  a screenshot. Manual verification environment fully torn down afterward (background `mvn
  spring-boot:run` process killed, `docker compose down -v` on the throwaway stack, temporary
  `docker-compose.manual-verify.yml` port-mapping override deleted) — no artifacts of this verification
  session were left running or committed.
- **AC5:** Ledger hygiene was already applied at story creation (Task 5 pre-checked). This session's
  remaining piece — flipping the four `[PICKED UP by skillars-deferred-65 ACn]` tags in
  `deferred-work.md` to `[CLOSED by skillars-deferred-65 ACn: ...]` citing the exact fix — completed
  after AC1-AC4 shipped, per the ledger's own PICKED-UP-at-creation / CLOSED-at-shipment convention. The
  AC3 closure tag explicitly notes "for week-scoping only," not full resolution, per this story's own
  scope-boundary finding.
- No deviations from the spec beyond the AC4 CSS-spacing fix noted above (not anticipated by the story
  text, root-caused and fixed during manual verification rather than deferred). `mvn verify` not run
  locally, per `docs/validation-strategy.md` — targeted `mvn -o test` runs only; full regression left to
  GitHub CI.

### File List

- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java` (AC1)
- `src/test/java/com/softropic/skillars/platform/payment/service/PackSessionServiceParityTest.java` (AC1)
- `src/test/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepositoryIT.java` (AC1)
- `src/main/java/com/softropic/skillars/infrastructure/validation/IanaTimezoneValidator.java` (AC2)
- `src/main/java/com/softropic/skillars/infrastructure/validation/IanaTimezone.java` (AC2)
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java` (AC2, comment only)
- `src/test/java/com/softropic/skillars/infrastructure/validation/IanaTimezoneValidatorTest.java` (AC2, new file)
- `src/test/java/com/softropic/skillars/platform/marketplace/api/CoachProfileBuilderIT.java` (AC2)
- `src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java` (AC3)
- `src/test/java/com/softropic/skillars/platform/booking/service/AvailabilityServiceTest.java` (AC3)
- `src/frontend/src/pages/parent/ParentBookingsPage.vue` (AC4)
- `src/frontend/src/i18n/en-US/index.js` (AC4)
- `src/frontend/src/i18n/de-DE/index.js` (AC4)
- `src/frontend/src/i18n/fr-FR/index.js` (AC4)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC5, ledger hygiene)

## Change Log

| Date | Description |
|------|-------------|
| 2026-08-25 | Story created via story-creation process. Full re-mine of `deferred-work.md` (1570 lines, all sections) scoped to the Booking/Availability/Reschedule module found 4 genuinely open items (2 requiring a fresh decision from the project owner, made directly during this story's creation: pack-selection ordering and IANA-validator strictness; 1 requiring a decision on which value is authoritative: availability week-scoping zone; 1 decision-light UX fix) and 2 older items found effectively superseded (closed via AC5 rather than picked up). Five additional open questions this module might have raised were confirmed to already be decided "leave as-is" by `skillars-deferred-64`'s own creation pass and are deliberately not re-touched here. |
| 2026-08-25 | Story-review adjustments applied (`story-review.md`), status remains ready-for-dev. 5 findings, none blocking AC1-AC4 as scoped — no decision needed for any of them. **AC1 (Low, no decision needed):** `ORDER BY p.expiresAt ASC` alone has no tiebreaker for two packs sharing an identical `expiresAt`; changed to `p.expiresAt ASC, p.createdAt DESC` to mirror the frontend's own tie-break (newest-created wins), plus a new test proving the secondary key. Also corrected an imprecise test-line pointer (one stale comment at lines 75-76, not "approximately 75 and 90") and added instructions to rename two tests' now-misleading `oldestId`/`older`/`newer`-style local variables. **AC2 (Low-Moderate + Informational, no decision needed, factual corrections):** connected the ledger item's own dangling "Pairs with D5" cross-reference to its resolution — D5 (profile-builder tzdb lockout) is already structurally moot because `CoachProfileService.getSupportedTimezones()`'s region-only dropdown means no coach can submit an out-of-tzdata zone regardless of validator strictness. Also corrected an illustrative "accepted consequence" example that cited a `ProfileBuilderStep4.vue` resubmission path verified not to exist (neither profile-builder step prefills its timezone ref from a saved value, and `UpdateWindowRequest` has no `canonicalTimezone` field at all) — no live UI action reproduces a reject-on-resubmit flow, so there is nothing to regression-test, only a corrected explanation. **AC3 (Informational, no decision needed):** added an explicit scope boundary — `findByCoachId`'s missing `ORDER BY` still causes `windowResponses` display-order nondeterminism, separate from the week-scoping bug this AC fixes; the eventual `[CLOSED by ...]` ledger tag must say "for week-scoping only," not claim full resolution. Two "false assumption" candidates story-review chased down (AC1 caller completeness, AC3's padding-rationale claim) both resolved in the story's favor and needed no change. |
| 2026-08-25 | Dev-story implementation complete, status review. AC1-AC4 all shipped; AC5's remaining ledger-hygiene piece (flipping the four `[PICKED UP]` tags to `[CLOSED]`) completed. One deviation from spec: AC4's manual verification (via the `run` skill) surfaced a real layout defect the story text did not anticipate — the new two-line timezone hint overlapped the field below it, fixed with a `q-mb-lg` class, re-verified via Playwright DOM-geometry assertions and a screenshot. Targeted `mvn -o test` runs green across all touched backend classes (47 unit tests, 46 integration tests, 0 failures); `npx eslint` clean on all touched frontend files. `mvn verify` not run locally per `docs/validation-strategy.md`; full regression left to GitHub CI. Full detail in Dev Agent Record above. |
| 2026-08-25 | Adversarial code review (`bmad-code-review`: Blind Hunter + Edge Case Hunter + Acceptance Auditor) run against the implementation diff, status set to `done`. Acceptance Auditor found zero AC violations. 4 `patch` findings applied and re-verified (`mvn -o test` green — 20 unit + 3 integration tests): fixed a stale post-rename test-name reference in `SessionPackPurchaseRepositoryIT.java:171`; made `IanaTimezoneValidator.fail()` `static`; added a rationale comment on `findActivePacks`'s `createdAt DESC` secondary sort key; added `p.purchaseId ASC` as a tertiary sort key closing the last theoretical tie-order gap. 3 `defer` findings appended to `deferred-work.md` (residual `Etc/GMT±N` validator gap, JDK/tzdata stability dependency, missing timezone hint on the paired "session end" field) — real but explicitly out of this story's scope. 11 findings dismissed as noise, mostly Blind Hunter false positives from lacking project/DB-schema access. Full detail in the Review Findings section above. |
