# Senior-Dev Audit: skillars-deferred-65

**Story:** `skillars-deferred-65-pack-selection-parity-timezone-validation-strictness-and-availability-week-scoping-fixes.md`
**Audit method:** every code citation, line number, and factual claim in the story was checked against
the current tree (not trusted from the story text) — repository/service/validator/frontend source, the
three affected test files, both locale bundles, and `deferred-work.md`'s own ledger entries. The IANA/
`ZoneRegion` claims in AC2 were re-verified experimentally on this machine's JDK 17 (`javac`/`java`), which
reproduced the story's exact results.

**Overall assessment:** the story is unusually well-researched — every file/line citation checked out,
the two "false assumption" candidates I chased down both resolved in the story's favor on closer
inspection, and the AC3 test-impact analysis (exactly which 3 of ~15 `AvailabilityServiceTest` cases break)
is precisely correct. Below are the gaps that survived verification, ordered by severity. None of them
block AC1–AC4 as scoped; they're refinements a careful dev-story pass should still pick up.

---

## Findings

### 1. [Low-Moderate] AC2 doesn't chase down the ledger's own "Pairs with D5" warning

The ledger item this AC executes (`skillars-deferred-18` review D4, `deferred-work.md:1164`) ends with:
*"Pairs with D5: tightening this makes D5 strictly worse."* No D5 exists anywhere in the current
`deferred-work.md` — grepped the whole file, only this one dangling reference. The story's own premise
is that it re-mines the ledger "specifically... live-verified against the current tree rather than
trusted from ledger text," but AC2 never addresses this specific caveat attached to its own source item.

I traced the original text via `git log -S` (introduced in `21ef489`, the `skillars-deferred-18` story
commit, later deleted from the ledger when closed): D5 was *"the profile builder hard-400s on any zone
the JVM's tzdb doesn't know"* — `ProfileBuilderStep1.vue`/`Step4.vue` used to send
`Intl.DateTimeFormat().resolvedOptions().timeZone` verbatim with no picker, so a browser on newer tzdata
than the deployed JVM (e.g. `Europe/Kyiv`) could permanently lock a coach out of finishing the builder.
"Tightening makes D5 strictly worse" meant: a stricter validator narrows the coach's escape hatch even
further if their browser reports an unrecognized zone.

**This is resolved, but not by anything AC2 says.** `CoachProfileService.getSupportedTimezones()`
(already shipped, `CoachProfileService.java:83-131`) added a server-side allow-list dropdown — the exact
fix D5 itself suggested ("an explicit zone picker in the profile builder"). Since the picker only ever
offers zones the JVM recognizes, no coach can submit an out-of-tzdata zone through it regardless of how
strict `@IanaTimezone` is, which structurally moots D5. AC2's own Dev Notes ("the dropdown was already
stricter than the validator... not introducing a new restriction the frontend has to catch up to")
independently arrives at the correct conclusion — it just never connects it back to D5 or acknowledges
the cross-reference existed. Net effect: AC2's decision is safe, but the story's audit trail has a gap
a future reader chasing the same ledger item would trip over.

### 2. [Low] AC1's `ORDER BY p.expiresAt ASC` has no tiebreaker, and the frontend's tiebreak is deterministic in a different direction

`SessionPackPurchaseRepositoryIT`'s existing test data model (and realistic cases — two same-tier packs
bought for the same coach on the same day) can produce two active packs with an **identical** `expiresAt`.
Postgres gives no ordering guarantee among tied rows absent a secondary sort key, so which pack lands at
`packs.get(0)` on a tie is DB-plan-dependent and can vary between calls.

The frontend's `currentPack` computed (`SessionPackPurchasePage.vue:126-134`,
`ParentPlayerPortalPage.vue`) breaks ties deterministically by array order via
`reduce((soonest, p) => expiresAt(p) < expiresAt(soonest) ? p : soonest)` — keeping the
first-encountered element on a tie. That array itself comes from
`SessionPackPaymentService.getPacksForParent` → `findByParentIdOrderByCreatedAtDesc` (verified,
`SessionPackPaymentService.java:84-87`), i.e. **newest-created-first**. So on an exact `expiresAt` tie,
the frontend deterministically shows the *most recently created* pack as "current," while the backend's
new query has no matching secondary key to guarantee it picks the same one — reopening, in the tie case
only, exactly the kind of frontend/backend mismatch AC1 exists to close.

Not a new regression (the old `createdAt ASC` clause had the same class of gap), and not present in
today's test suite for either ordering. Cheap fix while this exact clause is already being touched:
`ORDER BY p.expiresAt ASC, p.createdAt DESC` (mirroring the frontend's own tiebreak) — or at minimum call
out that the tie case is a known, accepted gap.

### 3. [Low] AC1's test-instruction line pointer is imprecise and misses two stale variable names

The story says to correct "comments... at (approximately) lines 75 and 90" in
`PackSessionServiceParityTest.java` referencing `createdAt` ordering. Verified: there is only **one**
such comment, at lines 75-76 (`// findActivePacks is queried ORDER BY createdAt ASC...`). What's actually
near lines 82 and 115-118 are two different test methods —
`getActivePackId_activePackExists_returnsFirstResultFromFindActivePacks` and
`findActivePackId_activePackExists_returnsFirstResultFromFindActivePacks` — using local variable names
`oldestId`/`older`/`newer` to label the pack expected first. These aren't comments, so a dev following the
story's literal instruction ("update the comments") may not think to touch them. They still pass unchanged
post-fix (both tests mock `findActivePacks`'s return order directly, per the story's own correct note), but
the names become semantically stale — "first returned" is no longer "the older one," it's "the
soonest-expiring one" — and are worth a rename for a future reader's sake while this file is already open.

### 4. [Informational] AC2's "Accepted consequence" example cites a resubmission path that doesn't exist today

The story's illustrative scenario: a coach with a legacy fixed-offset `canonicalTimezone` gets rejected
"if that coach's `ProfileBuilderStep4.vue` `TimezoneSelect` resubmits that same stale value unchanged on
their next Step 4 save." Verified this can't currently happen through that component:
`ProfileBuilderStep4.vue`'s `canonicalTimezone` ref is seeded from `store.selectedTimezone` (Step 1's
*own fresh pick from the same onboarding session*) or `null` — `form.windows` starts as `[]` and is never
populated from a coach's already-saved window rows, so there is no "existing stale value" for the picker
to resubmit unchanged. Separately, `UpdateWindowRequest` (the only endpoint for editing an existing
window, `AvailabilityResource.java:57-63` → `AvailabilityService.updateWindow`) has no `canonicalTimezone`
field at all — window timezone is immutable after creation via that path. Same story for Step 1: its
`canonicalTimezone` ref also always starts `null`, never prefilled from the profile's current value.

Practical effect is the same as the story concludes (no live UI action reproduces the described
resubmit-and-reject flow, so there's nothing to regression-test) — this is purely a factual inaccuracy in
the illustrative example, not a gap in the actual fix or its safety. Worth a fix to the story text only so
whoever does the "verify this is safe for picker-driven write paths" step doesn't go looking for a
resubmission flow that isn't reachable.

### 5. [Informational, explicitly out of scope] `findByCoachId`'s missing `ORDER BY` still causes display-order nondeterminism, separate from week-scoping

AC3 correctly fixes which value drives the *week-scoping bounds*. It does not touch
`CoachAvailabilityWindowRepository.findByCoachId` itself, which still issues no `ORDER BY`
(confirmed — same repository AC3's Dev Notes already name as out of scope). That means the `windows` list
built from it — also used to build `windowResponses`, the calendar's per-window listing returned to the
frontend — can still return in a different row order between two identical requests, independent of the
week-boundary bug AC3 closes. This matches the ledger item's own text ("not independently fixable...
blocked on D8") and the story's Dev Notes ("resist the urge to also fix per-window divergence"), so it's
correctly out of scope — flagging only so AC3 isn't mistaken for having fully resolved the "unordered
`findByCoachId`" arbitrariness the ledger item's title describes.

---

## Verified clean (checked specifically because they looked like plausible gaps, found not to be)

- **AC1 caller completeness:** grepped the entire `src/main` tree — `findActivePacks` has exactly the 3
  call sites the story lists (`hasActivePack`, `getActivePackId`, `findActivePackId`, all in
  `PackSessionService`), and exactly the 2 test files reference it. No hidden 4th consumer.
- **AC1 `pausePack` interaction:** pausing extends `expiresAt` (`PackSessionService.java:174`), which
  correctly deprioritizes a paused pack under the new soonest-expiring ordering — no adverse interaction.
- **AC2 experimental claims:** `ZoneId.getAvailableZoneIds().contains(...)` behavior for `Navajo`,
  `Europe/Berlin`, `Etc/UTC`, `+01:00`, `UTC+02:00`, `GMT+2`, `Z` all reproduced exactly as the story
  states, on this project's JDK 17. `ZoneRegion` package-private compile failure also reproduced exactly.
- **AC2 write-path coverage:** `@IanaTimezone` is used in exactly 2 request DTOs
  (`ProfileBuilderStep1Request`, `ProfileBuilderStep4Request`) — no other write path stores a
  `canonicalTimezone` without going through the validator or the profile-derived `addWindow` copy.
- **AC3 test-impact list:** confirmed by grepping every `setCanonicalTimezone(...)` call in
  `AvailabilityServiceTest.java` — the 3 tests the story names are the *only* ones that set a window zone
  different from the `makeCoachProfile`/`makeWindow` shared default (`"Europe/Berlin"`), so no other
  existing test silently breaks.
- **AC3 padding-rationale claim** ("profile-vs-window gap is the same or narrower than the old
  window-vs-window gap, never wider"): holds, because the 48h pad was already derived as an absolute
  worst-case bound over everything `ZoneId.of` can accept, independent of which specific zone pairing
  (window-vs-window vs. profile-vs-window) is compared.
- **AC4 null-safety:** `canonical_timezone` is `NOT NULL` on both `coach_profiles` and the bookings table
  (`V26`, `V31`), so `booking.canonicalTimezone` is always present for the new hint — no blank-value UI
  case to guard against.
- **AC5 ledger tags:** all four `[PICKED UP by skillars-deferred-65 ACn]` annotations exist exactly as
  described, on the exact ledger items cited.
