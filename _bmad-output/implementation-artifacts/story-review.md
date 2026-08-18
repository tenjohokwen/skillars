# Senior-Dev Story Audit — `skillars-deferred-30`

**Story reviewed:** `_bmad-output/implementation-artifacts/skillars-deferred-30-error-toast-mapping-and-repository-boundary-test-coverage-fixes.md`
**Status at review:** `ready-for-dev`
**Reviewed:** 2026-08-18
**Verdict:** **Changes required before dev.** Two ACs rest on a factually wrong premise about the
backend (AC1's error-code uniqueness, AC3's batch-accept throw path), one AC misses a fifth
unmapped error code that already has translations shipped, and two ACs prescribe i18n/fixture-id
work that duplicates or miscounts what is already in the tree. The remaining ACs are sound.

Every finding below was verified by reading the current working tree — file and line references are
what a `git checkout` at `08f3a61` actually contains. A "Verified correct" section at the end lists
the story claims I checked and could **not** fault, so the finding list is not padded.

---

## Severity key

| Grade | Meaning |
|---|---|
| **BLOCKER** | The AC as written will produce wrong behaviour, dead code, or a demonstrably false completion note. Fix the AC before dev starts. |
| **SHOULD-FIX** | The AC will produce working code, but its stated rationale, scope, or verification step is wrong and will mislead the dev or the reviewer. |
| **MINOR** | Accuracy/hygiene drift. Fix while in the file; not worth blocking on. |

---

## BLOCKER findings

### B1 — AC3: `handleAcceptAll`'s `booking.slotUnavailable` branch is dead code, and the real defect in that flow is a **false success toast**, not a vague error toast

AC3 states:

> `BookingBatchService.acceptOneBooking` (called by `handleAcceptAll` via `bookingStore.handleAcceptAllBatch`)
> throws the identical two codes (`BookingBatchService.java:256-257,353-357`)

`acceptOneBooking` does throw both codes — but **neither its `SLOT_UNAVAILABLE` nor its
`COACH_UNAVAILABLE` ever reaches the client.** `BookingBatchService.acceptAll` wraps each
per-booking call in `try { … } catch (Exception e) { log.warn(…) }`
(`BookingBatchService.java:263-278`), and the method's own comment says so explicitly:

> `// … without it the per-booking throws are swallowed by the loop's catch and the caller sees a`
> `// silent no-op.`

The only code that can surface to `handleAcceptAll` is the **pre-flight** `COACH_UNAVAILABLE` at
`BookingBatchService.java:255-257`, which is thrown before the loop. Consequences:

1. **The `booking.slotUnavailable` branch AC3 prescribes for `handleAcceptAll` is unreachable
   dead code.** Nothing in the batch-accept path can produce that errorKey at the HTTP boundary.
2. **The genuinely user-visible defect in this exact flow is untouched by AC3.** When every booking
   in the batch fails, `acceptAll` hits `if (acceptedIds.isEmpty()) { … return; }`
   (`BookingBatchService.java:280-283`) and returns **HTTP 200**. `handleAcceptAll` then fires
   `$q.notify({ message: t('booking.batch.acceptedAll'), type: 'positive' })`
   (`CoachBookingRequestsPage.vue:179`) — the coach is told "All sessions accepted" when **zero**
   were accepted. A partial success (3 of 5) is reported the same way.

AC3's stated goal is "coach-side accept flows show accurate errors." Shipping AC3 as written and
writing a completion note to that effect would be false for `handleAcceptAll`: it adds one
never-firing branch to a flow whose worst error-reporting bug is a green toast on total failure.

**Required AC edit.** Either:
- **(a)** Scope `handleAcceptAll` to the **`booking.coachUnavailable` branch only**, and state
  in the AC that `booking.slotUnavailable` is deliberately excluded because `acceptAll` swallows
  per-booking throws — plus file the silent-success case as a new `deferred-work.md` item
  (it needs a contract change: `acceptAll` must return per-booking outcomes or a 4xx on
  zero-accepted, which is outside a bundled-fix story); **or**
- **(b)** explicitly widen the AC to close the silent-success bug too — but that is a backend
  contract change and contradicts this story's own "Scope discipline" note.

**(a) is the right call** for a bundled small-fix story. Do not ship (a) without the new ledger item.

---

### B2 — AC2: a **fifth** unmapped errorKey is missed — `payment.coachStripeNotConfigured` — and its translation already exists in all three bundles

AC2's title and body assert the `else` branch is "the live landing zone for **four** rejection
codes `BookingService.createBookingRequest` throws today." A direct re-read of the method finds a
fifth `PaymentGatewayException`:

```java
// BookingService.java:187-189
if (!paymentGateway.isCoachPaymentReady(coach.getId())) {
    throw new PaymentGatewayException("payment.coachStripeNotConfigured");
}
```

Same `BookingApiAdvice` mechanism AC2 already relies on (`BookingApiAdvice.java:19-23`), so the
wire `errorMsg.errorKey` is the literal `payment.coachStripeNotConfigured`, and it lands in
`submit()`'s generic `else` today.

Three reasons this one matters **more** than the four AC2 does list:

1. **It is the only one of the five reachable with no stale client state.** Nothing gates the
   parent's route to `BookingRequestPage` on coach payment readiness — `grep` for
   `isCoachPaymentReady|paymentReady|stripeReady` under `src/frontend/src` returns **zero hits**.
   A parent who reaches a coach whose Stripe onboarding is incomplete gets a 422 and a generic
   "Could not submit request", with no path to understanding why. (Contrast the three `pack*`
   codes — see S2.)
2. **The message is already written, in all three locales**, under `payment.error`:
   `en-US/index.js:1038` `'This coach has not completed their payment setup yet'`;
   `de-DE/index.js:948`; `fr-FR/index.js:861`. Zero new i18n work.
3. It is *precisely* actionable — unlike `MISSING_RIGHTS`, which AC2's own Dev Notes correctly
   concede is only a category hint.

**Required AC edit.** Add a fifth branch `errorKey === 'payment.coachStripeNotConfigured'` →
`t('payment.error.coachStripeNotConfigured')`, and correct AC2's "four" to "five" throughout
(title, body, Task 2). No new i18n key.

> Note this is not a ledger transcription error the story inherited — the story's own "Why this
> story exists" claims "every throw site … was read directly from the working tree." A full read of
> `createBookingRequest`'s throw sites would have surfaced `:188`.

---

### B3 — AC1: `DRILL_UPLOAD_NOT_ALLOWED` is **not** unique to "a video is already linked", so the prescribed message asserts a cause the wire code does not carry

AC1 instructs: on `errorKey === 'DRILL_UPLOAD_NOT_ALLOWED'`, show
`"A video is already linked to this drill. Remove it before uploading a new one."`

`SessionErrorCode.DRILL_UPLOAD_NOT_ALLOWED` is thrown at **three** sites, two of them in the very
method the panel calls:

| Site | Condition |
|---|---|
| `DrillUploadService.java:57` | `!"COACH".equals(drill.getLibraryType()) \|\| !coachId.equals(drill.getOwnerCoachId())` — **inside `initiateUpload`**, before the gate check |
| `DrillUploadService.java:78` | READY video already linked — the case AC1 describes |
| `DrillUploadService.java:109` | same ownership check inside `deleteVideo` (different catch block; not AC1's concern) |

The frontend cannot tell `:57` from `:78`. AC1's story text ("the video-already-linked check …
so the wire `errorKey` is the literal string `DRILL_UPLOAD_NOT_ALLOWED`") treats the code as though
`:78` were its only source, which is false.

**Honest reachability assessment** (so this is not overstated): the `:57` path is *not* reachable
from the panel today. `DrillLibraryService.list` scopes non-PLATFORM results to
`findByOwnerCoachIdAndStatus(coachId, "ACTIVE")` (`DrillLibraryService.java:70`), so a coach never
sees another coach's `COACH` drill; and the template gates the upload block on
`props.drill.libraryType === 'COACH'` (`DrillDetailPanel.vue:94`, `:241`), covering the
library-type half. So today the message will be right by accident.

That is exactly the fragility this story exists to remove. A shared library, a team-drill feature,
or any future caller that passes a drill id the panel did not source from the scoped listing makes
the toast confidently wrong — and, per the story's own Dev Notes, there is no frontend test to
catch it.

**Required AC edit.** Pick one and state the reasoning in the AC:
- **(a)** Use a message that is true for *both* causes and still non-retryable, e.g.
  `"This drill can't accept a new video right now. If it already has one, remove it first."`; or
- **(b)** Keep the specific wording, but **add an explicit note in AC1** that the code is shared
  with `DrillUploadService.java:57`, that `:57` is currently unreachable via
  `DrillLibraryService.list`'s owner-scoped query, and that splitting the code
  (`DRILL_VIDEO_ALREADY_LINKED`) is the real fix — filed as a new `deferred-work.md` item.

**(b) is acceptable** for a bundled-fix story; **(a) with no note is not**, and silently shipping the
current AC text is not.

---

## SHOULD-FIX findings

### S1 — AC2 creates duplicate i18n keys: `packCoachMismatch` and `packExhausted` already exist in all three bundles

AC2 says "Add **four new** i18n keys under the existing `booking.errors` block." Two of the four
already exist verbatim-in-meaning under `payment.sessionPack`:

| Key | Existing | Existing English | AC2's proposed English |
|---|---|---|---|
| `packCoachMismatch` | `en-US:1060`, `de-DE:972`, `fr-FR:884` | `This session pack is for a different coach.` | `This session pack is not valid for this coach.` |
| `packExhausted` | `en-US:1061`, `de-DE:973`, `fr-FR:885` | `This session pack has no remaining sessions.` | `This session pack has no sessions remaining.` |

Neither existing key is referenced from any `.vue`/`.js` outside the bundles — they were added
speculatively. Shipping AC2 as written leaves **two near-identical strings per locale, six total**,
diverging in wording. The next translator or copy pass will not know which is live.

**Recommended AC edit.** Either reuse `payment.sessionPack.packCoachMismatch` /
`payment.sessionPack.packExhausted` from `submit()` (so AC2 adds only `packExpired` and
`requestNotAllowed` under `booking.errors`), **or** keep the `booking.errors.*` additions and
delete the unused `payment.sessionPack.*` pair in the same edit. Reuse is the smaller diff and the
better fit for this story's "match the existing idiom" discipline. Do not leave both.

---

### S2 — AC2's "live landing zone" framing is wrong for the three `payment.pack*` codes; Task 2's verification recipe will not reproduce them

AC2 calls all four codes "thrown today" and frames them as live parent-facing paths. For the three
pack codes that is only true via **stale client state or direct API calls**, because the page
filters the selector to packs that already satisfy the backend's checks:

```js
// BookingRequestPage.vue:268-272
const activePacksForCoach = computed(() =>
  bookingStore.sessionPacks.filter(
    (p) => String(p.coachId) === String(coachId) && p.status === 'ACTIVE',
  ),
)
```

and `status` is **derived server-side** at response time —
`SessionPackPaymentService.computeStatus` (`:248+`) returns `EXHAUSTED` when
`remainingSessions == 0`, `PAUSED` when `pausedUntil` is in the future, etc. So on a freshly
loaded page:

- **`payment.packCoachMismatch` is unreachable through the UI at all** — the `coachId` filter is
  identical to the backend check at `BookingService.java:269-271`.
- **`payment.packExpired` / `payment.packExhausted`** fire only if the pack changes state between
  page load and submit (another tab, another device, expiry crossing).

This does not make the branches wrong — mapping them is correct defensive work, and the
`packExhausted` race is real. But two things in the story must change:

1. Drop or qualify "live landing zone"/"user-actionable" for the three pack codes; the only
   consistently live one is B2's `payment.coachStripeNotConfigured`.
2. **Task 2's manual-verification step is not performable as written.** It says
   `payment.packExhausted` is "easiest to reproduce by exhausting a real pack's
   `remainingSessions`" — a fresh page load after exhausting it will simply not offer the pack.
   The step must spell out the stale-state procedure: load `BookingRequestPage` with the pack
   selected, exhaust the pack in a second tab/session, then submit from the first tab **without
   reloading**. Without that, this step will be silently skipped or falsely ticked.

---

### S3 — AC5's rationale is wrong about what the new pin buys; the rollover it names is exercised by nothing

AC5 asserts the current pin "never crosses an ISO week-based-year boundary, **the one case a fixed
clock exists to reach cheaply**", and the inherited ledger item calls the compound `(year, week)`
range predicate "never exercised across an ISO week-based-year rollover."

`SluDashboardServiceTest` is a **pure Mockito unit test** — `@Mock private SluWeeklySnapshotRepository
snapshotRepository` — and every one of the three tests stubs the repository call
(`SluDashboardServiceTest.java:72`, `:107`, `:126`). The compound predicate lives in JPQL:

```java
// SluWeeklySnapshotRepository.java:30-33
"AND (s.id.isoYear > :fromYear OR (s.id.isoYear = :fromYear AND s.id.isoWeek >= :fromWeek)) " +
"AND (s.id.isoYear < :toYear   OR (s.id.isoYear = :toYear   AND s.id.isoWeek <= :toWeek)) "
```

Moving the clock to a rollover date therefore exercises **zero** rollover behaviour. It only
changes which literal `short`s the test passes to `eq(...)`. The thing that actually breaks the
self-mirroring is the **hardcoded literals**, and that works at *any* pinned date.

Two corrections needed:

1. **Rewrite AC5's justification.** The change of substance is "replace mirrored `IsoFields`
   computations with hand-computed literals"; the rollover date is a *nice-to-have* that makes the
   literals span two ISO years so an author cannot accidentally reintroduce a same-year shortcut.
   Say that. Do not claim the pin gives rollover coverage.
2. **Record the real gap.** `grep -rn "findByPlayerIdFromWeek" src/test/` returns **only** the three
   mocked stubs in this file — the query has no repository-level test at any date, rollover or not.
   That is a genuine coverage hole and belongs in `deferred-work.md` as a new item (a
   `SluWeeklySnapshotRepositoryIT` seeding weeks 47/2026 → 01/2027), explicitly out of scope here.

*(AC5's arithmetic itself is correct — see "Verified correct" V4. This finding is about the stated
rationale and the coverage claim, not the numbers.)*

---

### S4 — AC1's manual verification (Task 1) cannot be performed as written; **both** new branches are defensive-only

Task 1 says: "trip `DrillUploadService.java:75-79`'s video-already-linked guard and `:135-140`'s
feature-gate guard against a real running instance." Neither is reachable through the rendered UI,
because the template pre-empts both backend checks:

```html
<!-- DrillDetailPanel.vue:94-96 (and the desktop twin at :241-243) -->
<div v-if="props.drill.libraryType === 'COACH' && sessionStore.canUploadVideo === true" …>
  <template v-if="!props.drill.hasVideo">
```

- **Feature gate.** `canUploadVideo` comes from `GET …/upload-eligible` →
  `DrillUploadService.isVideoUploadEligible`, which reads the **same**
  `feature.drillVideoUpload.enabled.<tier>` config key as `checkDrillUploadGate`. It is also cached
  for the session (`session.store.js:113` — `if (canUploadVideo.value !== null) return`). A gated
  coach never sees the button.
- **Already-linked.** `hasVideo` is `ref.getVideoId() != null` (`DrillLibraryService.java:290`) —
  true for *any* linked video ref, including `PROCESSING`/`FAILED`. The backend throws only for
  `OperationalState.READY` (`DrillUploadService.java:76-79`). The UI guard is therefore **strictly
  broader** than the backend's.

Both branches are reachable only from a **stale panel**: toggle the config (or let the video reach
READY elsewhere) *after* the panel and store cache are populated, then click upload without
reloading.

**Recommended AC edit.** Keep both branches — defensive mapping of a code the API genuinely returns
is correct — but (i) stop describing them as live coach-facing defects, and (ii) rewrite Task 1's
verification step with the actual stale-state procedure:

- feature gate: load the drill library as an eligible coach, flip
  `feature.drillVideoUpload.enabled.<tier>` to `false` in `config`, then upload **without
  reloading**;
- already-linked: load the panel while the drill's video is `PROCESSING`, let it reach `READY`,
  then upload **without reloading**.

Given the story's own Dev Notes ("Do them for real, not as a formality"), leaving an unperformable
step in the task list is the failure mode most likely to repeat.

---

### S5 — AC4's fixture-id arithmetic is wrong, and the widened range re-creates the very contradiction AC6 exists to remove

Two separate problems.

**(a) The count is wrong.** AC4 says to claim "two new fixture ids for the new coach's `userId` and
**the new pack's implicit rows**", widening `9620000001`–`9620000003` → `…0005`.
`SessionPackPurchase.purchaseId` is a **UUID**, generated by the DB — packs consume no id from the
`962…` long range (see the existing test: every pack is created via `newPack(...)` and identified by
`getPurchaseId()`, a `UUID`). Only the second coach's `userId` needs an id. The correct range is
`9620000001`–**`9620000004`**. As written, AC4 claims an id nobody uses and bakes the wrong
rationale into a doc whose entire purpose is being accurate.

**(b) The test's own header comment is left contradicting the doc.**
`SessionPackPurchaseRepositoryIT.java:26` reads:

```java
// Fixture id range 9620000001-9620000003, claimed in docs/testing/test-data-isolation.md.
```

AC4 and AC6 update `docs/testing/test-data-isolation.md` but neither mentions this line, and it is
not in the story's "File paths this story touches" for AC4 beyond the test file itself. Shipping as
written closes one doc/code contradiction (AC6) while opening another in the same commit.

**Required AC edit.** Change the range to `9620000001`–`9620000004` in AC4, AC6 and Task 6, drop the
"new pack's implicit rows" rationale, and add an explicit subtask: update the comment at
`SessionPackPurchaseRepositoryIT.java:26` to match.

---

### S6 — AC6 doesn't address the commit-anchored wording of the sentence it edits

The line AC6 amends is not an open-ended list — it is a snapshot pinned to a commit:

> `The claimed four-digit prefixes at ` `` `21ef489` `` ` are: `9000`, `9070`, … `9900`.
> — `docs/testing/test-data-isolation.md:217-219`

Inserting `9620` makes the sentence assert something about commit `21ef489` that is not true of
that commit. AC6 instructs the insertion but says nothing about the anchor.

**Recommended AC edit.** In the same edit, either re-anchor to the current commit or reword to
"The claimed four-digit prefixes are:" (dropping the pin). Re-wording is preferable — this list has
now drifted from its anchor twice.

---

## MINOR findings

### m1 — AC4 doesn't say whether the second coach needs its own `SessionPackTier`

`newPack(playerId, coachId, packTierId)` takes an explicit `packTierId`. AC4 says "seed one
additional pack for the same player but the new coach" without saying whether to reuse coach A's
tier (satisfies the `pack_tier_id` FK, but leaves a pack whose coach and whose tier's coach
disagree — the exact inconsistency the test is proving the query rejects) or seed a second tier.
Reusing coach A's tier is fine for the assertion and is the smaller diff, but the AC should say so
explicitly, or the dev will guess.

### m2 — AC3's target catch blocks also cover post-success reload failures

All three flows `await` a refresh **inside** the same `try` as the mutating call:
`booking.store.js:348-351` (`approveBooking` → `acceptBooking` then `loadCoachBookingRequests`),
`:543-555` (`handleAcceptAllBatch`, same shape), and `CoachCommandCenterPage.vue:373-376`
(`handleAcceptReschedule` then `loadCoachSchedule`). A failed refresh after a **successful** accept
already reports the accept as failed.

This is pre-existing and **AC3 does not make it worse** — a failed `GET` carries no `booking.*`
errorKey, so it lands in the unchanged generic fallback. But AC3 is the moment someone is reading
these blocks. File it as a new `deferred-work.md` item; do not fix it here (scope).

### m3 — Line-reference drift throughout the story

Not behaviour-affecting, but a story that leans this hard on "re-verified against current code"
should be exact:

| Story says | Actual |
|---|---|
| `DrillDetailPanel.vue:388-390` / `382-391` | catch is `382-391`; generic `else` body is `389-390` — the `382-391` form is right, `388-390` is off by one |
| `en-US/index.js:341-343` (`quotaExceeded`/`constraintViolated`/`uploadFailed`) | `342-344` |
| `CoachBookingRequestsPage.vue:151-181` | `handleAccept` `152-162`; `handleAcceptAll` `174-186` (catch `180-182`) |
| `CoachCommandCenterPage.vue:372-379` | `372-383` |
| `SluDashboardServiceTest.java:56-60,84-88,117-121` (§AC5 heading) vs `59-65, 84-100, 118-124` (§AC5 body) | **self-inconsistent**; `setClock` is at `59`, `84`, `118` |
| `BookingService.java:220,262,265,270,273` | correct, but the `MISSING_RIGHTS` sites are `167,171,184,193,198,222,244,267` — **eight**, not the "six unrelated rejection reasons" AC2 states |

`en-US/index.js:916-917` (`booking.errors.coachUnavailable`/`slotUnavailable`),
`en-US/index.js:491` (`security.featureGated`), `SessionPackPurchaseRepository.java:37-46`,
`docs/testing/test-data-isolation.md:206,217-220`, `ApiAdvice.java:326-330` and
`BookingApiAdvice.java:18-23` all check out exactly.

---

## Verified correct — claims I tried to break and could not

Listed so the findings above are not read as a general indictment of the story. Each was
independently checked against the tree.

- **V1 — AC4's core premise holds.** Deleting `AND p.coachId = :coachId` from
  `SessionPackPurchaseRepository.findActivePacks` really would leave both existing tests green.
  I specifically checked for cross-test row leakage (both methods seed the same `PLAYER_ID`, so a
  shared DB would make the mutation fail for the wrong reason): `AbstractIntegrationTest` registers
  `DatabaseResetTestExecutionListener`, which resets in **`beforeTestMethod`** (`:102`), not
  per class. No leakage. The AC's diagnosis and its mutation-verification step are both sound.
- **V2 — AC3's error idiom works for the two flows where it applies.** Every store method rethrows
  the original `AxiosError` (`booking.store.js:348-351`, `:466-477`, `:543-555`), and the response
  interceptor's 403 handler only `console.warn`s before
  `return Promise.reject(error)` (`boot/axios.js:154-176`) — it does not swallow or redirect. So
  `err?.response?.data?.errorMsg?.errorKey` is genuinely available in
  `handleAccept` and `handleAcceptReschedule`. Both of those flows do propagate
  `booking.coachUnavailable` and `booking.slotUnavailable` (`BookingService.java:324-325,333-337`;
  `RescheduleService.java:186-188,193-199`), including the `acceptReschedule` `SLOT_UNAVAILABLE`
  the AC calls out as coach-only. AC3 is correct for two of its three flows — see **B1** for the third.
- **V3 — AC1's wire values are right.** `FeatureGatedException` → `security.featureGated`
  (`ApiAdvice.java:326-331`, a literal string, not an enum name — as the AC states);
  `OperationNotAllowedException` → `exception.getErrorCode().getErrorCode()`
  (`ApiAdvice.java:267-277`), and `SessionErrorCode.getErrorCode()` returns `name()`, so
  `DRILL_UPLOAD_NOT_ALLOWED` is the literal wire value. `security.featureGated` exists in all three
  bundles (`en-US:491`, `de-DE:998`, `fr-FR:506`), so reusing it is correct.
- **V4 — AC5's ISO arithmetic is correct.** Recomputed by hand: 2027-01-01 is a Friday → ISO week 1
  of 2027 begins Mon 2027-01-04, so **2027-01-06 is a Wednesday in week 1 of 2027** (`curYear=2027`,
  `curWeek=1`). `minusWeeks(8-1)` → 2026-11-18, ordinal day 322, weekday Wed(3) →
  `(322-3+10)/7 = 47` → **week 47 of 2026** (`fromYear=2026`, `fromWeek=47`). All four literals in
  the AC are right. (The AC's own instruction to re-verify on a real JVM before hardcoding is still
  good practice — keep it.)
- **V5 — AC5's mutation-verification step will genuinely go red.** `MockitoExtension` defaults to
  `Strictness.STRICT_STUBS`, so an argument mismatch raises `PotentialStubbingProblem` at call
  time. This matters most for `getWeeklyExposure_withNoData_returnsEmptyCurrentWeekAndEmptyTrend`,
  whose `isEmpty()` assertions would otherwise still pass on Mockito's default empty-list return.
  All three tests fail on a formula regression once the literals are in.
- **V6 — `findByPlayerIdFromWeek`'s JPQL is correct across a year boundary.** The OR-form
  (`SluWeeklySnapshotRepository.java:30-33`) handles `(2026,47) → (2027,01)` properly; there is no
  latent bug behind S3, only absent coverage.
- **V7 — AC6's three contradictions are exactly as described.** Registry row at `:206` reads
  `9620000001`–`9620000003`; the claimed-prefix list at `:217-219` omits `9620`; the free-blocks
  line at `:220` still advertises `9620`–`9690`.
- **V8 — "no frontend test infrastructure" is accurate.** No `vitest.config.*`; the only
  `*.test.js` under `src/frontend` is inside a vendored `lib/node_modules` copy of a Quasar CLI
  dependency. AC1/AC2/AC3 will genuinely ship with zero automated coverage.
- **V9 — the exclusion list is honest.** I spot-checked the four excluded items with available
  evidence (`batchSizeExceeded`'s `{max}`, `submitReschedule`'s `MISSING_RIGHTS`, the
  `QUOTA_EXCEEDED` conflation, the `console.warn` PII note) against `deferred-work.md:1470-1492`.
  Each exclusion is correctly characterised; none is a small mechanical fix being dodged.

---

## Recommended pre-dev edit list

Ordered by cost of getting it wrong.

1. **AC3 / Task 3** — drop the `booking.slotUnavailable` branch for `handleAcceptAll`; keep
   `booking.coachUnavailable` only; state why. File the `acceptAll`-returns-200-on-zero-accepted
   silent-success bug as a new `deferred-work.md` item. *(B1)*
2. **AC2 / Task 2 / AC2 title** — add the fifth branch `payment.coachStripeNotConfigured` →
   existing `t('payment.error.coachStripeNotConfigured')`; change "four" → "five". *(B2)*
3. **AC1 / Task 1** — resolve the shared `DRILL_UPLOAD_NOT_ALLOWED` code: either neutral wording, or
   keep the specific wording plus an explicit note on the `:57` co-tenant and a new ledger item for
   splitting the code. *(B3)*
4. **AC2 / Task 2** — reuse `payment.sessionPack.packCoachMismatch` / `packExhausted` instead of
   adding duplicates (or delete the unused pair in the same edit). *(S1)*
5. **AC4 / AC6 / Task 6** — range `9620000001`–**`9620000004`**, drop the "new pack's implicit rows"
   rationale, add a subtask to update `SessionPackPurchaseRepositoryIT.java:26`. *(S5)*
6. **AC5** — rewrite the justification (literals break the mirror, the date is a bonus); add a new
   ledger item for the entirely-absent `findByPlayerIdFromWeek` repository coverage. *(S3)*
7. **Task 1 / Task 2** — replace both manual-verification recipes with the actual stale-state
   procedures; neither branch is reachable from a freshly-loaded page. *(S4, S2)*
8. **AC2** — soften the "live landing zone / user-actionable" framing for the three `pack*` codes;
   note `packCoachMismatch` is UI-unreachable. *(S2)*
9. **AC6** — re-anchor or reword the `at 21ef489` sentence. *(S6)*
10. **AC4** — state whether the second coach reuses coach A's `SessionPackTier`. *(m1)*
11. **Housekeeping** — fix the line references in m3, including AC5's self-inconsistent citation and
    AC2's "six" `MISSING_RIGHTS` reasons (there are eight sites).
12. **New ledger item** — post-success reload failure reported as accept failure across all three
    coach accept flows. *(m2)*

---

## Overall assessment

The story's **structure** is right for a bundled small-fix story, its exclusion reasoning is
genuinely honest (V9), and four of its six items (AC4, AC5's mechanism, AC6, and two-thirds of AC3)
are correctly diagnosed and safely scoped. AC5's Dev Note insisting on real mutation verification,
and AC2's Dev Note refusing to overclaim on `MISSING_RIGHTS`, are both the right instincts.

What it did not survive is the claim in its own preamble that "every throw site, every wire
`errorKey`, every i18n key … was read directly from the working tree." Three of the four
BLOCKER/SHOULD-FIX findings on the frontend ACs (B1, B2, S1) are things a full re-read of the throw
sites and the locale bundles would have caught: a swallowing loop, a fifth `PaymentGatewayException`,
and two keys already shipped. The verification appears to have re-confirmed the ledger's own claims
rather than independently re-enumerating the code.

With the twelve edits above, this is a sound, low-risk story. Without edits 1–3 it will ship one
piece of dead code, one confidently-wrong toast, and a completion note that overstates what the
coach-side accept flows now do.
