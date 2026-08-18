# Story Deferred-30: Non-Retryable & Pack-Rejection Error Toasts, Coach Accept-Flow Error Mapping, ISO-Week Rollover Test & Repository Boundary Coverage

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Skillars maintainer,
I want six small, independently-verified deferred items closed — a video-upload catch block that tells a
coach to "try again" for two rejections that will never succeed on retry, a parent booking-request catch
block that swallows five backend-thrown rejection codes behind one generic toast, two-of-three coach-side
accept flows that show one undifferentiated error for every rejection reason, a repository test whose sole
cross-coach predicate is unproven, an ISO week-based-year rollover that no existing test ever exercises,
and a self-contradicting fixture-id doc — so that each of six unrelated, previously-deferred defects,
spanning the session/video, booking, payment, and development modules plus one docs file, gets fixed
without bundling any of them into a larger story that would need its own design pass.

### Why this story exists

Drawn directly from `_bmad-output/implementation-artifacts/deferred-work.md`, per Mbah's direction to
group small, unrelated, already-deferred items into one story to reduce dev overhead — the same spirit as
`skillars-deferred-11/20/21/22/23/24/25/26/27/28/29`. All items below were independently re-verified
against **current** code during this story's creation (2026-08-18), not trusted from the ledger's text.

**Senior-dev review correction (2026-08-18):** a full audit of the draft (`story-review.md`) found the
"read directly from the working tree" claim above did not fully hold — the original draft re-confirmed
the ledger's own citations rather than independently re-enumerating every throw site and every locale
bundle. Three corrections of substance came out of it, each verified again before being folded in below:

- **AC3 originally prescribed a `booking.slotUnavailable` branch for `handleAcceptAll` that is dead code.**
  `BookingBatchService.acceptAll` wraps every per-booking call in a `catch (Exception e) { log.warn(...) }`
  (`BookingBatchService.java:263-278`) — its own code comment says the per-booking throws are "swallowed by
  the loop's catch" without the pre-flight suspension check. Neither `SLOT_UNAVAILABLE` nor
  `COACH_UNAVAILABLE` thrown *inside* the loop (`acceptOneBooking`) can reach the client; only the
  **pre-flight** `COACH_UNAVAILABLE` check before the loop (`:255-257`) can. AC3 below is narrowed
  accordingly for `handleAcceptAll`, and the flow's real defect — `acceptAll` returns **HTTP 200** with a
  positive "All sessions accepted" toast when **zero** bookings were actually accepted
  (`BookingBatchService.java:280-283`, `CoachBookingRequestsPage.vue:179`) — is recorded as a new,
  out-of-scope ledger item rather than silently left unaddressed.
- **AC2 originally listed four unmapped rejection codes; there is a fifth, and it is the only one of the
  five reachable through ordinary use with no stale client state.**
  `BookingService.createBookingRequest` also throws `PaymentGatewayException("payment.
  coachStripeNotConfigured")` when `!paymentGateway.isCoachPaymentReady(coach.getId())`
  (`BookingService.java:186-188`) — nothing on the frontend gates a parent's route to a coach whose Stripe
  onboarding is incomplete (`grep` for `isCoachPaymentReady|paymentReady|stripeReady` under
  `src/frontend/src` returns zero hits), and its message already exists in all three locale bundles under
  `payment.error.coachStripeNotConfigured`. AC2 below adds it as a fifth branch with zero new i18n work.
  The review also found two of the four originally-proposed **new** `booking.errors.*` keys
  (`packCoachMismatch`, `packExhausted`) duplicate existing, unused keys already shipped under
  `payment.sessionPack.*` — AC2 now reuses those instead of adding near-duplicate strings.
- **AC1's `DRILL_UPLOAD_NOT_ALLOWED` branch is not unique to "a video is already linked".** The same wire
  `errorKey` is also thrown by `initiateUpload`'s drill-ownership check (`DrillUploadService.java:56-57`,
  "Drill upload not allowed") — a case the frontend cannot distinguish from the already-linked case
  (`:75-78`). That ownership-check path is not reachable from the panel today (`DrillLibraryService.list`
  scopes results to the calling coach's own drills, and the template gates on
  `libraryType === 'COACH'`), so the prescribed message is accurate by accident, not by construction. AC1
  keeps the specific wording but now says so explicitly, and files the code-sharing as a new ledger item
  (splitting `DRILL_UPLOAD_NOT_ALLOWED` into two codes is the real fix, and it's a backend contract change
  beyond this story's bar).

Two further, lower-severity corrections: AC5's rationale originally implied the rollover-dated clock pin
exercises rollover *behaviour* — it doesn't (`SluDashboardServiceTest` is a pure Mockito unit test; the
compound `(year, week)` JPQL predicate lives in `SluWeeklySnapshotRepository`, entirely untested at any
date). What actually breaks the test's self-mirroring is the switch to **hardcoded literal** expected
values; the rollover-spanning date is a hygiene choice, not the mechanism. And Task 1/Task 2's manual
verification steps as originally drafted described rejections that a freshly-loaded page cannot produce —
both are rewritten below with the actual stale-client-state reproduction steps.

A full read of `deferred-work.md` (all 1492 lines, at story-creation time) was performed before selecting
these six, focused on the most recent, least-mined section — the code review of `skillars-deferred-29`
(2026-08-17) — since every older section has already been swept by multiple prior `skillars-deferred-N`
stories. The following categories of ledger items in that same section, and nearby, were deliberately
excluded as too large, blocked, or needing a decision this story's bundled-fix bar does not cover — not
omitted by oversight:

- **`booking.errors.batchSizeExceeded`'s wrong `{max}` figure** (`deferred-work.md:1470`) — the item's own
  text states the correct fix is a backend contract change (`BatchRuleViolationException` needs to carry
  the real limit as a message argument), not a frontend edit. Already excluded by `skillars-deferred-29`
  for the same reason.
- **`ParentBookingsPage.submitReschedule()`'s `else` branch swallowing `MISSING_RIGHTS`**
  (`deferred-work.md:1480`) — unlike the `BookingRequestPage.submit()` item this story does close (AC2
  below), the ledger item's own text states plainly that fixing this "properly needs distinct backend
  error codes, not just frontend branches" (`RescheduleService.requestReschedule` throws the identical
  `MISSING_RIGHTS` code for four unrelated rejection reasons, and no frontend branch can tell them apart).
  A contract change, not a mechanical patch.
- **`'QUOTA_EXCEEDED'` conflating a transient rate limit with a hard storage quota**
  (`deferred-work.md:1484`) — already **mitigated** (not closed) by `skillars-deferred-29`'s code-review
  response, which reworded the toast copy to stop promising an upgrade for a transient condition. The
  item's own text states the real fix needs `VideoErrorCode.QUOTA_EXCEEDED` split into two distinct wire
  codes — a backend contract change, not a frontend patch.
- **The `console.warn` PII-in-console-buffer observation** (`deferred-work.md:1488`) — the item's own text
  frames this as "revisit if a console-capturing telemetry integration is ever added", not as a live
  defect with an available fix today; there is no auth-token exposure and the behaviour (`console.warn`
  with the `err` argument) was itself a deliberate, documented decision of `skillars-deferred-29` AC4.
- **The `jakarta.persistence.lock.timeout` dead-code gap** (`deferred-work.md:1424`) — confirmed empirically
  dead against this project's Postgres/Hibernate combination; the item itself states a real fix needs a
  design decision between two competing approaches. Same exclusion every prior `skillars-deferred-N` story
  since `-23` has made.
- **`ConfigService.getBoolean`'s fail-open behavior on security-sensitive gates**
  (`deferred-work.md:1410`) — real, but "not alertable" is an observability/infrastructure gap, not a
  mechanical code fix.
- **`DrillMetadata.repDensity`'s `int`-vs-`Integer` gap** (`deferred-work.md:1444`) — needs a backend
  contract change plus a product decision on whether "unset" is a reachable state.
- **The two product questions** (`deferred-work.md:1463-1464`: post-start-time parent cancellation
  settling as a no-show; two independently-computed refund-eligibility rules that can disagree) — both
  explicitly need product/design input, not a mechanical patch.
- Every ledger item explicitly marked "needs sign-off", "product decision", or targeting a currently
  unreachable/already-mitigated code path — none of those are small, independently-safe, mechanical fixes.

## Deferred Items Closed

| Source | Item | Current location (re-verified 2026-08-18) | AC |
|---|---|---|---|
| code review of `skillars-deferred-29-...` (2026-08-17) | `DrillDetailPanel.vue`'s upload catch block's `else` branch tells a coach to "try again" for two deterministically non-retryable rejections (`security.featureGated`, `DRILL_UPLOAD_NOT_ALLOWED`) | `DrillDetailPanel.vue:382-391` | 1 |
| code review of `skillars-deferred-29-...` (2026-08-17) | `BookingRequestPage.submit()`'s `else` branch is the landing zone for five unmapped codes `BookingService.createBookingRequest` throws today | `BookingRequestPage.vue:481-491`, `BookingService.java:167-267` | 2 |
| `skillars-deferred-28-...` story creation (2026-08-17), still open | Two of the three coach-side accept flows (`CoachBookingRequestsPage.vue`'s `handleAccept`, `CoachCommandCenterPage.vue`'s `handleAcceptReschedule`) show one undifferentiated toast regardless of the thrown `booking.*` code; the third (`handleAcceptAll`) is narrower — see AC3 | `CoachBookingRequestsPage.vue:152-186`, `CoachCommandCenterPage.vue:372-383` | 3 |
| code review of `skillars-deferred-29-...` (2026-08-17) | `SessionPackPurchaseRepositoryIT`'s `p.coachId = :coachId` predicate is unproven — deleting it from the JPQL leaves both existing tests green | `SessionPackPurchaseRepositoryIT.java`, `SessionPackPurchaseRepository.java:37-46` | 4 |
| code review of `skillars-deferred-29-...` (2026-08-17) | `SluDashboardServiceTest`'s three `getWeeklyExposure_*` tests mirror the production date formula and assert nothing that would catch a regression in it | `SluDashboardServiceTest.java:59-65,84-100,118-124`, `SluDashboardService.java:41-44` | 5 |
| code review of `skillars-deferred-29-...` (2026-08-17) | `docs/testing/test-data-isolation.md`'s summary lists contradict its own registry table for the `9620` block | `docs/testing/test-data-isolation.md:206,217-220` | 6 |

**Explicitly NOT in this story** (considered during story creation and review, and rejected — do not
implement):

- **`ParentBookingsPage.submitReschedule()`'s `MISSING_RIGHTS` mapping, `booking.errors.batchSizeExceeded`'s
  wrong figure, the `QUOTA_EXCEEDED` rate-limit/storage-quota conflation, and the `console.warn`
  PII-in-console observation** — each needs a backend contract change or is explicitly framed by its own
  ledger text as not a live, closeable defect. See "Why this story exists" above.
- **The `lock.timeout` dead-code gap and `ConfigService.getBoolean` fail-open/no-alert gap** — both need a
  design decision or new infrastructure, not a same-file fix. See "Why this story exists" above.
- **`acceptAll`'s silent HTTP 200 on zero bookings accepted** (found by AC3's review correction) — a
  backend contract change (`acceptAll` needs to return per-booking outcomes, or a non-2xx status when
  nothing was accepted); file as a new ledger item during Task 7, do not fix here.
- **Splitting `SessionErrorCode.DRILL_UPLOAD_NOT_ALLOWED` into two distinct codes** (found by AC1's review
  correction) — a backend contract change; file as a new ledger item during Task 7, do not fix here.
- **Repository-level test coverage for `SluWeeklySnapshotRepository.findByPlayerIdFromWeek`** (found by
  AC5's review correction — currently exercised by nothing but mocked unit-test stubs, at any date) — a
  new `SluWeeklySnapshotRepositoryIT` is its own test-writing task, not a one-line fix; file as a new
  ledger item during Task 7, do not fix here.
- **The post-success reload failure reported as an accept failure**, present in all three coach accept
  flows (`booking.store.js:348-351,543-555`, `CoachCommandCenterPage.vue:373-376` all `await` a refresh
  call inside the same `try` as the mutating call) — pre-existing, not made worse by AC3, and a failed
  `GET` carries no `booking.*` errorKey so it lands in the unchanged generic fallback either way; file as
  a new ledger item during Task 7, do not fix here.
- **All other open ledger items** not listed in the table above — every one inspected during this story's
  creation either needed a product/design decision, targeted an unreachable/already-mitigated code path,
  or duplicated a fix a prior story already made.

## Acceptance Criteria

1. **`DrillDetailPanel.vue`'s video-upload catch block's `else` branch shows "Upload failed. Please try
   again." for two rejections that are never retryable — a feature-gate rejection and a
   video-already-linked rejection — and must instead show a distinct, non-retry message for each.**
   `DrillDetailPanel.vue:382-391` currently branches only on `errorKey === 'QUOTA_EXCEEDED'` and
   `errorKey === 'video.constraintViolated'` (both fixed by `skillars-deferred-29` AC2); everything else,
   including these two, falls to the generic retry message. Verified both throw sites and their wire
   `errorKey` values directly:
   - **`checkDrillUploadGate`** (`DrillUploadService.java:135-140`) throws `FeatureGatedException` when the
     coach's subscription tier lacks drill upload. `ApiAdvice.java:326-330`'s `featureGatedHandler` maps
     this to wire `errorKey = "security.featureGated"` (via `logErrorAndReturnDTO(ex, ex.getMessage(),
     "security.featureGated")` — the literal string, not an enum name). This condition self-heals only by
     upgrading, never by retrying the same upload.
   - **`SessionErrorCode.DRILL_UPLOAD_NOT_ALLOWED`** maps (via `exception.getErrorCode().getErrorCode()`,
     `ApiAdvice.java:267-277`, and `SessionErrorCode.getErrorCode()` returning `this.name()`) to the wire
     `errorKey = "DRILL_UPLOAD_NOT_ALLOWED"`. **This code is not unique to "a video is already linked" —
     `initiateUpload` throws the identical code from a separate drill-ownership check
     (`DrillUploadService.java:56-57`, `"Drill upload not allowed"`) before it ever reaches the
     already-linked check (`:75-78`), and the frontend cannot tell the two apart from `errorKey` alone.**
     The ownership-check path is **not reachable from this panel today**: `DrillLibraryService.list`
     scopes non-`PLATFORM` results to `findByOwnerCoachIdAndStatus(coachId, "ACTIVE")`
     (`DrillLibraryService.java:70`), so a coach is never handed another coach's `COACH`-owned drill id to
     begin with, and the template independently gates the whole upload block on
     `props.drill.libraryType === 'COACH'` (`DrillDetailPanel.vue:94,241`). So the already-linked-specific
     message this AC prescribes is correct **by accident of today's data flow**, not because the wire code
     guarantees it. Do not let that accident go unrecorded — Task 7 files a new ledger item proposing the
     real fix (split `DRILL_UPLOAD_NOT_ALLOWED` into two distinct `SessionErrorCode` values, one per throw
     site), explicitly out of scope for this AC.

   Add two new `else if` branches **before** the final generic `else`, in this order (most specific to
   least): `errorKey === 'DRILL_UPLOAD_NOT_ALLOWED'`, `errorKey === 'security.featureGated'`, then the
   existing generic fallback. For the feature-gated branch, reuse the existing `security.featureGated`
   i18n key (`t('security.featureGated')`, present in all three locale bundles — confirmed at
   `en-US/index.js:491`: `"This feature requires a higher subscription tier."`) — do **not** invent a new
   key for this branch, and do not add an "Upgrade" CTA button; a plan-tier message is sufficient to stop
   a coach from re-clicking upload expecting a different outcome. For the `DRILL_UPLOAD_NOT_ALLOWED`
   branch, add a **new** i18n key `session.drillLibrary.upload.videoAlreadyLinked` to all three locale
   bundles (`src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js`, alongside the existing `quotaExceeded` /
   `constraintViolated` / `uploadFailed` keys at `en-US/index.js:342-344`), with English text mirroring the
   backend's own already-linked message verbatim: `"A video is already linked to this drill. Remove it
   before uploading a new one."` German/French translations should be accurate, idiomatic translations of
   that sentence, not machine-literal — match the tone of the sibling keys already in each bundle.

2. **`BookingRequestPage.submit()`'s `else` branch is the live landing zone for five rejection codes
   `BookingService.createBookingRequest` throws today, none of which resolve to a specific toast.**
   `submit()`'s catch (`BookingRequestPage.vue:481-491`) currently branches only on
   `booking.coachUnavailable`, `booking.slotUnavailable`, `booking.invalidSessionDuration` (all fixed by
   `skillars-uat-2`/`skillars-deferred-28`). Verified five further throw sites directly:
   - **`payment.coachStripeNotConfigured`** (`BookingService.java:186-188`, thrown when
     `!paymentGateway.isCoachPaymentReady(coach.getId())`) — **the only one of the five reachable through
     ordinary use with no stale client state.** Nothing on the frontend gates a parent's route to
     `BookingRequestPage` on the coach's Stripe-onboarding status (`grep` for
     `isCoachPaymentReady|paymentReady|stripeReady` under `src/frontend/src` returns zero hits). Message
     already exists in all three locale bundles: `payment.error.coachStripeNotConfigured` (`en-US/
     index.js:1038`, `de-DE/index.js:948`, `fr-FR/index.js:861`) — **zero new i18n work for this branch.**
   - `payment.packExpired` (`BookingService.java:262`, thrown via `PaymentGatewayException("payment.
     packExpired")` — `BookingApiAdvice.java:18-23`'s handler returns the constructor argument verbatim as
     both `helpCode` and `errorMsg.errorKey`)
   - `payment.packCoachMismatch` (`BookingService.java:270`, same `PaymentGatewayException` mechanism) —
     **not reachable via the UI today.** `BookingRequestPage.vue:268-272`'s pack selector already filters
     to `p.coachId === coachId && p.status === 'ACTIVE'`, identical to the backend check this throws from.
     Mapping it is still correct defensive work (a direct API call, or a future selector change, can still
     hit it), but do not describe it as a live user-facing path.
   - `payment.packExhausted` (`BookingService.java:273`, same mechanism) — reachable only if the pack's
     server-computed `status` changes to `EXHAUSTED` between page load and submit (another tab, another
     device); not reachable from a single freshly-loaded page in one sitting.
   - `MISSING_RIGHTS` (`SecurityError.MISSING_RIGHTS`, thrown at **eight** points inside
     `createBookingRequest`, six distinct rejection reasons — `BookingService.java:167` "Parent does not
     own this player", `:171` "Player does not own this profile", `:184` and `:244` "Coach profile is not
     active" (two sites, one reason), `:193` and `:198` invalid requested-time range (two sites, one
     reason), `:222` "Requested slot is not within coach availability", `:267` "Pack does not belong to
     this parent")

   **`MISSING_RIGHTS` is genuinely overloaded across six unrelated rejection reasons within this one
   method — do not present it to the parent as if it were pack-specific.** Add it as a branch with an
   honest, generically-worded message (not a claim about packs specifically), distinct only from the fully
   generic fallback in that it signals "your selection or eligibility changed, not a system error" — this
   is a real, if partial, improvement over today's undifferentiated toast, not full precision.

   Add all five branches to `submit()`'s `if/else if` chain, in this order (most specific/actionable
   first): `errorKey === 'payment.coachStripeNotConfigured'`, `errorKey === 'payment.packExpired'`,
   `errorKey === 'payment.packCoachMismatch'`, `errorKey === 'payment.packExhausted'`,
   `errorKey === 'MISSING_RIGHTS'`, before the existing `console.warn` + generic-toast `else`.

   **i18n: reuse existing keys wherever they already exist; do not create near-duplicates.**
   `payment.error.coachStripeNotConfigured` already exists (see above — reuse verbatim). Two of the
   remaining four already exist, unused, under `payment.sessionPack.*` in all three bundles:
   `payment.sessionPack.packCoachMismatch` (`"This session pack is for a different coach."`,
   `en-US/index.js:1060`) and `payment.sessionPack.packExhausted` (`"This session pack has no remaining
   sessions."`, `en-US/index.js:1061`) — **reuse these two verbatim; do not add
   `booking.errors.packCoachMismatch`/`packExhausted`.** Add exactly **two new** i18n keys under the
   existing `booking.errors` block in all three locale bundles
   (`src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js`, alongside `coachUnavailable`/`slotUnavailable` at
   `en-US/index.js:916-917`):
   - `packExpired`: `"This session pack has expired."`
   - `requestNotAllowed`: `"Your request could not be completed. Please review your selection and try
     again."`

   `submitBatchRequest()` is **out of scope for this AC** — `createBatch` cannot throw
   `payment.coachStripeNotConfigured`/`packExpired`/`packCoachMismatch`/`packExhausted` (batch bookings do
   not resolve a `sessionPackPurchaseId` the same way; confirm at implementation time by re-reading
   `BookingBatchService.createBatch` before assuming this), and its existing four-branch chain already
   covers its own throw sites per `skillars-deferred-28`/`-29`.

3. **Two of the three coach-side accept flows show one undifferentiated toast for every rejection reason,
   unlike their parent-side siblings; the third (`handleAcceptAll`) cannot be fixed the same way, because
   its backend path swallows the codes before they ever reach the client.**
   - **`CoachBookingRequestsPage.vue`'s `handleAccept`** (`:152-162`, catch at `:155-156`) calls
     `bookingStore.approveBooking` → `BookingService.acceptBooking`, which throws
     `BookingError.COACH_UNAVAILABLE` (`BookingService.java:324-325`) and `BookingError.SLOT_UNAVAILABLE`
     (`:333-337`) directly, uncaught, to the HTTP boundary. Verified the response interceptor does not
     swallow this: every store method rethrows the original `AxiosError`
     (`booking.store.js:348-351`), and `boot/axios.js:154-176`'s 403 handler only `console.warn`s before
     `return Promise.reject(error)`. **Both codes are genuinely live for this flow.**
   - **`CoachCommandCenterPage.vue`'s `handleAcceptReschedule`** (`:372-383`, catch at `:378-379`) calls
     `bookingStore.handleAcceptReschedule` → `RescheduleService.acceptReschedule`, which throws the
     identical two codes (`RescheduleService.java:186-188,193-199`), also uncaught to the boundary.
     Confirmed the `excl_bkg_coach_slot_overlap` DB constraint's mapping to `booking.slotUnavailable` **is**
     reachable from this method, unlike from any parent-side flow (whose partial index excludes
     `REQUESTED`-status rows). **Both codes are genuinely live for this flow too.**
   - **`CoachBookingRequestsPage.vue`'s `handleAcceptAll`** (`:174-186`, catch at `:180-182`) calls
     `bookingStore.handleAcceptAllBatch` → `BookingBatchService.acceptAll`, whose per-booking loop
     (`:263-278`) wraps `acceptOneBooking` — the method that actually throws `COACH_UNAVAILABLE`
     (`:256-257`) and `SLOT_UNAVAILABLE` (`:353-357`) — in a bare `try { … } catch (Exception e) {
     log.warn(...) }` with no rethrow. **Neither code, thrown from inside that loop, can ever reach the
     client.** The only `COACH_UNAVAILABLE` that can surface to `handleAcceptAll` is the separate
     **pre-flight** check before the loop opens (`BookingBatchService.java:255-257`, thrown for a
     suspended coach before any per-booking processing starts) — `SLOT_UNAVAILABLE` has no pre-flight
     equivalent and cannot reach the client from this flow at all today.

   `BookingError.getErrorCode()` maps both codes to the wire strings `"booking.coachUnavailable"` /
   `"booking.slotUnavailable"` (`BookingError.java:13-14`) — the **same two keys**
   `booking.errors.coachUnavailable`/`booking.errors.slotUnavailable` already exist in all three locale
   bundles (`en-US/index.js:916-917`, added by `skillars-uat-2`/`skillars-deferred-28`). **No new i18n keys
   are needed for this AC.**

   For `handleAccept` and `handleAcceptReschedule`: change both catch blocks to inspect
   `err?.response?.data?.errorMsg?.errorKey` and branch on `'booking.coachUnavailable'` /
   `'booking.slotUnavailable'` before falling back to each flow's existing generic message, following the
   exact `errorMsg.errorKey` idiom `BookingRequestPage.vue`/`ParentBookingsPage.vue` already use.

   For `handleAcceptAll`: add **only** the `'booking.coachUnavailable'` branch (the pre-flight check can
   still surface it) — **do not add a `booking.slotUnavailable` branch here; it would be dead code that
   can never execute.** This flow's real defect — `acceptAll` returns HTTP 200 with a positive "All
   sessions accepted" toast even when zero bookings were actually accepted
   (`BookingBatchService.java:280-283` returns silently on an empty `acceptedIds`;
   `CoachBookingRequestsPage.vue:179` fires the positive toast unconditionally on a 2xx response) — is a
   backend contract change (per-booking outcomes, or a non-2xx on zero-accepted) outside this AC's bar.
   File it as a new ledger item in Task 7; do not fix it here.

   Do not add a `console.warn` for the unmapped-fallback case in this AC — that pattern was deliberately
   scoped to the three parent-initiating flows by `skillars-deferred-29` AC4 and extending it here is scope
   creep beyond what this AC asks for.

4. **`SessionPackPurchaseRepositoryIT`'s `findActivePacks_excludesExhaustedExpiredPausedAndOtherPlayerPacks`
   test seeds every row under the same coach, so `p.coachId = :coachId` is unproven.** Verified directly:
   the test's `otherPlayerPack` row (and every other row in the method) calls `coach.getId()` — the same
   coach fixture created at the top of the test — for its `coachId` argument; only the *player* id varies.
   Deleting `AND p.coachId = :coachId` from `SessionPackPurchaseRepository.findActivePacks`'s JPQL
   (`SessionPackPurchaseRepository.java:37-46`) would leave both existing tests in this class green.
   Undetected symptom in production: a parent holding packs with two different coaches could be offered
   the wrong coach's pack credits when booking. Add a second `CoachProfile` fixture (a new coach, new
   `userId`) to `findActivePacks_excludesExhaustedExpiredPausedAndOtherPlayerPacks`, seed one additional
   pack for the **same player** but the **new coach**, and assert `findActivePacks` (called with the
   *original* coach's id) still excludes it. **Reuse the existing `SessionPackTier` fixture (the one
   created for coach A) for this new pack rather than seeding a second tier** — the assertion only depends
   on the pack row's own `coach_id` column, not on its tier's coach, and a second tier fixture would be
   unnecessary weight for what this AC is proving.

   `SessionPackPurchase.purchaseId` is a database-generated `UUID` (`SessionPackPurchase.java:26-29`), not
   a claimed long id — only the **new coach's `userId`** needs a claimed fixture id. Claim exactly one new
   id, extending the existing `SessionPackPurchaseRepositoryIT` block in
   `docs/testing/test-data-isolation.md`'s registry from `9620000001`–`9620000003` to
   `9620000001`–`9620000004` (fold this into AC6's doc edit rather than a second separate edit — see AC6).
   **Also update the stale range comment inside the test file itself**,
   `SessionPackPurchaseRepositoryIT.java:26` (`// Fixture id range 9620000001-9620000003, claimed in
   docs/testing/test-data-isolation.md.`), to match the widened range — leaving it unchanged would recreate
   the exact doc/code contradiction AC6 exists to remove, one line away.

5. **`SluDashboardServiceTest`'s three `getWeeklyExposure_*` tests compute their expected
   `fromYear`/`fromWeek`/`curYear`/`curWeek` with the exact same formula
   `SluDashboardService.getWeeklyExposure` uses, so they cannot detect a regression in that formula.**
   Verified directly in `SluDashboardServiceTest.java` (all three tests, currently at lines 59-65, 84-100,
   118-124): each computes `now`/`from` via `ZonedDateTime.now(TestClockProvider.getClock())` /
   `.minusWeeks(8 - 1)` and then reads `IsoFields.WEEK_BASED_YEAR`/`WEEK_OF_WEEK_BASED_YEAR` off both —
   byte-identical to `SluDashboardService.java:41-44`'s own computation. An off-by-one introduced later
   into the production formula (e.g. `minusWeeks(weeksBack)` instead of `minusWeeks(weeksBack - 1)`) would
   move the test's expectation in lockstep and the test would stay green.

   **CORRECTED by the 2026-08-18 code review — this AC's stated premise is wrong.** The old mirrored-formula
   tests would *not* have stayed green under that off-by-one. The mirrored values are fed to the mocked
   repository through `eq()` matchers, so a production regression desyncs the call arguments from the stub
   and Mockito's default `STRICT_STUBS` fails the test with `PotentialStubbingProblem` — which is exactly
   what the AC's own mutation-verification step observed when it was carried out. The literal-value rewrite
   is still worth keeping (an expectation you can check by reading it beats one you have to re-derive, and
   it fails with a legible assertion mismatch rather than a stubbing error), but it closes a readability
   gap, not the detection gap this AC claims. The shipped `SluDashboardServiceTest` carries a comment
   saying the same thing; this note reconciles the AC with it.

   **What actually fixes this is switching to hardcoded literal expected values — not the choice of
   date.** `SluDashboardServiceTest` is a pure Mockito unit test (`@Mock private
   SluWeeklySnapshotRepository snapshotRepository`); the compound `(year, week)` range predicate this test
   feeds into via `eq()` matchers lives entirely in JPQL
   (`SluWeeklySnapshotRepository.java:30-33`) and is not exercised by this test at any date — a real ISO
   week-based-year rollover in the *query itself* remains completely uncovered regardless of which instant
   this test pins its clock to (see the new ledger item below). Re-pin all three tests'
   `TestClockProvider.setClock(Clock.fixed(...))` call from `Instant.parse("2026-08-19T10:00:00Z")` to
   `Instant.parse("2027-01-06T10:00:00Z")` — verified by hand computation this is a Wednesday (no
   Monday-midnight boundary risk, same discipline as the original pin) in ISO week 1 of 2027, with `from`
   (7 weeks earlier) landing in ISO week 47 of **2026**. The rollover-spanning date is chosen only as a
   hygiene measure — it makes the two literal values span two different ISO years, so a future author
   cannot accidentally hardcode a same-year shortcut that happens to pass; it does not itself provide
   rollover *coverage*, and no claim in this AC should be read as saying it does.

   In each of the three tests, **replace the computed `eq(fromYear)`/`eq(fromWeek)`/`eq(curYear)`/
   `eq(curWeek)` arguments with hardcoded literal `short` values** — `curYear = (short) 2027`,
   `curWeek = (short) 1`, `fromYear = (short) 2026`, `fromWeek = (short) 47` (re-verified by hand:
   2027-01-01 is a Friday, so ISO week 1 of 2027 begins Monday 2027-01-04, making 2027-01-06 a Wednesday in
   week 1; `minusWeeks(7)` from there lands on 2026-11-18, ISO week 47 of 2026) — computed once, not
   derived from `IsoFields` inside the test. This is the change that actually breaks the self-mirroring:
   with literal expected values, a regression in `SluDashboardService`'s date-math formula will now
   produce a real assertion mismatch instead of moving the expectation to match. Re-verify the exact ISO
   week numbers via a real JVM computation before hardcoding — do not trust this AC's arithmetic blindly,
   even though it has been checked twice already. Do not touch `getNarrativeSummary_*` tests in the same
   file — they do not call `getWeeklyExposure` and are unaffected.

   **File a new ledger item (Task 7) for the coverage gap this AC does not close**:
   `SluWeeklySnapshotRepository.findByPlayerIdFromWeek`'s compound `(year, week)` JPQL predicate has zero
   repository-level test coverage at any date (`grep -rn "findByPlayerIdFromWeek" src/test/` returns only
   this file's three mocked stubs) — a real `SluWeeklySnapshotRepositoryIT` seeding rows either side of an
   ISO week-based-year boundary is its own test-writing task, out of scope here.

6. **`docs/testing/test-data-isolation.md`'s two summary lists contradict its own registry table for the
   `9620` id block, and the sentence they sit in is pinned to a stale commit anchor.** Verified directly:
   the registry table (`:206`) already lists `` `9620000001`–`9620000003` | `SessionPackPurchaseRepositoryIT` ``,
   but the "claimed four-digit prefixes" summary line (`:217-219`) omits `9620` from its list, and the
   "**Free blocks** for new classes" summary line (`:220`) still advertises `` `9620`–`9690` `` as entirely
   free — a future author following the free-blocks line would collide with
   `SessionPackPurchaseRepositoryIT`. That summary sentence also reads `"The claimed four-digit prefixes at
   `21ef489` are:"` — a snapshot pinned to a specific commit that is already stale (the list has drifted
   from that commit at least once before). Fix, in the same edit that widens the registry row per AC4:
   - Update the registry row to `` `9620000001`–`9620000004` | `SessionPackPurchaseRepositoryIT` `` (not
     `...005` — see AC4's correction on why only one new id is needed)
   - Add `9620` to the "claimed four-digit prefixes" list (`:217-219`), in numeric order alongside its
     existing neighbours (`9611`, `9700`), **and drop the stale `` at `21ef489` `` commit anchor from the
     sentence** (reword to `"The claimed four-digit prefixes are:"`) rather than re-pinning it to a new
     commit that will just as quickly go stale
   - Narrow the "Free blocks" line (`:220`) from `` `9620`–`9690` `` to `` `9630`–`9690` ``

7. **Ledger hygiene.** Annotate each of the six items in the **Deferred Items Closed** table above with
   `[CLOSED by skillars-deferred-30 ACn]` in `deferred-work.md`, following this file's own established
   annotation convention (see e.g. the `[CLOSED by skillars-deferred-29 AC1]` annotations already present
   in the same file). **Also file four new items**, discovered during this story's creation and review,
   each explicitly out of scope for this story (see "Explicitly NOT in this story" above for the reasoning
   behind each exclusion):
   - `acceptAll` returns HTTP 200 with a positive toast when zero bookings in the batch were actually
     accepted (found via AC3)
   - `SessionErrorCode.DRILL_UPLOAD_NOT_ALLOWED` is thrown by two unrelated conditions in
     `DrillUploadService`, indistinguishable by the frontend (found via AC1)
   - `SluWeeklySnapshotRepository.findByPlayerIdFromWeek`'s compound `(year, week)` predicate has zero
     repository-level test coverage at any date (found via AC5)
   - All three coach accept flows report a post-success refresh failure as an accept failure, because the
     refresh call is `await`ed inside the same `try` as the mutating call (found during AC3's review)

   Update `sprint-status.yaml`'s `skillars-deferred-30-...` entry status as this story progresses
   (`ready-for-dev` → `in-progress` → `review` → `done`), per this repo's established convention.

## Tasks / Subtasks

- [x] Task 1 — `DrillDetailPanel.vue` non-retryable-rejection branches (AC: #1)
  - [x] Add `errorKey === 'DRILL_UPLOAD_NOT_ALLOWED'` branch, using new key
    `session.drillLibrary.upload.videoAlreadyLinked`
  - [x] Add `errorKey === 'security.featureGated'` branch, reusing existing key `security.featureGated`
  - [x] Add `session.drillLibrary.upload.videoAlreadyLinked` to `en-US`, `de-DE`, `fr-FR` bundles
  - [ ] Manually verify both new toasts render (no automated frontend test infra exists in this repo —
    see Dev Notes). **Neither is reachable from a freshly-loaded panel — the UI guard is stricter than
    (feature gate) or broader than (already-linked) the backend check, so both require deliberately stale
    client state:**
    - feature gate: as an eligible coach, load the drill library page (populating the cached
      `sessionStore.canUploadVideo`), then — **without reloading** — flip
      `feature.drillVideoUpload.enabled.<tier>` to `false` for that coach's tier via the config
      admin/DB, and attempt an upload
    - already-linked: open the panel for a drill whose video is `PROCESSING`, let that video transition
      to `READY` (e.g. via the moderation/webhook pipeline) while the panel or its underlying data is
      still considered fresh, then attempt a second upload **without reloading**

- [x] Task 2 — `BookingRequestPage.submit()` pack-rejection and payment-readiness branches (AC: #2)
  - [x] Add `payment.coachStripeNotConfigured` branch (reuse existing key `payment.error.
    coachStripeNotConfigured` — no new i18n)
  - [x] Add `payment.packExpired` branch (new key `booking.errors.packExpired`)
  - [x] Add `payment.packCoachMismatch` branch (reuse existing key `payment.sessionPack.
    packCoachMismatch` — no new i18n)
  - [x] Add `payment.packExhausted` branch (reuse existing key `payment.sessionPack.packExhausted` — no
    new i18n)
  - [x] Add `MISSING_RIGHTS` branch (new key `booking.errors.requestNotAllowed`)
  - [x] Add exactly two new keys (`packExpired`, `requestNotAllowed`) under `booking.errors` in all three
    locale bundles — do **not** add `packCoachMismatch`/`packExhausted` under `booking.errors`, they
    already exist under `payment.sessionPack`
  - [x] Confirm `submitBatchRequest()` is unchanged — re-read `BookingBatchService.createBatch` first to
    confirm it genuinely cannot throw these five codes before leaving it untouched
    - **CORRECTED by the 2026-08-18 code review:** the re-read behind this checkmark was wrong.
      `createBatch` *did* throw one of the five, `SecurityError.MISSING_RIGHTS`, at six sites, so
      `submitBatchRequest()`'s `else` branch was in fact a live landing zone and the AC's out-of-scope
      reasoning did not hold. The four `payment.*` codes were correctly out of scope — those are the
      claim's only sound half. Closed during that review under Decision 1: the four non-authorization
      `MISSING_RIGHTS` causes in `createBatch` were re-coded to distinct `BookingError` values and
      `submitBatchRequest()` gained six mapped branches, so the gap this checkmark hid no longer
      exists.
  - [ ] Manually verify `payment.coachStripeNotConfigured` (the one branch reachable from a normal,
    freshly-loaded page — pick a coach fixture whose Stripe onboarding is incomplete and submit)
  - [ ] Manually verify `payment.packExhausted` via the actual reachable path — **not** a fresh page
    load, which will simply not offer an exhausted pack: load `BookingRequestPage` with an
    almost-exhausted pack selected, exhaust it via its last session completing (or a second browser
    session), then submit from the **first, un-reloaded** tab

- [x] Task 3 — Coach-side accept-flow error mapping (AC: #3)
  - [x] `CoachBookingRequestsPage.vue`'s `handleAccept` — branch on `booking.coachUnavailable` /
    `booking.slotUnavailable` via `errorMsg.errorKey`, reusing existing `booking.errors.*` keys
  - [x] `CoachCommandCenterPage.vue`'s `handleAcceptReschedule` — same two branches
  - [x] `CoachBookingRequestsPage.vue`'s `handleAcceptAll` — **`booking.coachUnavailable` branch only**;
    do not add a `booking.slotUnavailable` branch here (dead code — see AC3)
  - [ ] Manually verify at least one branch fires per flow (e.g. suspend a coach mid-session via the
    admin path, then attempt an accept as that coach, to trigger `booking.coachUnavailable` on all
    three; `booking.slotUnavailable` is separately verifiable on `handleAccept`/`handleAcceptReschedule`
    only, via a genuinely conflicting slot)

- [x] Task 4 — `findActivePacks` `coachId`-mismatch boundary test (AC: #4)
  - [x] Add a second `CoachProfile` fixture to
    `findActivePacks_excludesExhaustedExpiredPausedAndOtherPlayerPacks` (new coach, new `userId`; reuse
    coach A's existing `SessionPackTier` fixture for the new pack)
  - [x] Seed one additional pack for the same player under the new coach; assert it's excluded when
    querying with the original coach's id
  - [x] `mvn -o verify -Dit.test=SessionPackPurchaseRepositoryIT` green
  - [x] Mutation-verify: temporarily delete `AND p.coachId = :coachId` from
    `SessionPackPurchaseRepository.findActivePacks` and confirm the new assertion fails; revert

- [x] Task 5 — `SluDashboardServiceTest` literal-expectation rewrite (AC: #5)
  - [x] Re-pin all three `getWeeklyExposure_*` tests' fixed clock to `2027-01-06T10:00:00Z`
  - [x] Replace each test's computed `eq(fromYear)`/`eq(fromWeek)`/`eq(curYear)`/`eq(curWeek)` arguments
    with hardcoded literal shorts, verified against a real ISO-week computation
  - [x] `mvn -o test -Dtest=SluDashboardServiceTest` green
  - [x] Mutation-verify: temporarily reintroduce an off-by-one into
    `SluDashboardService.getWeeklyExposure`'s `from` computation and confirm at least one test now fails
    (proving the literal values actually catch it); revert

- [x] Task 6 — `docs/testing/test-data-isolation.md` fixture-id registry fix (AC: #6, folds AC4's range
  widening)
  - [x] Widen the `SessionPackPurchaseRepositoryIT` registry row to `9620000001`–`9620000004`
  - [x] Update the matching comment at `SessionPackPurchaseRepositoryIT.java:26` to the same range
  - [x] Add `9620` to the claimed-prefixes summary list; reword the sentence to drop the stale
    `` at `21ef489` `` commit anchor
  - [x] Narrow the free-blocks summary line to `9630`–`9690`

- [x] Task 7 — Ledger hygiene (AC: #7)
  - [x] Annotate all 6 items in the **Deferred Items Closed** table with
    `[CLOSED by skillars-deferred-30 ACn]` in `deferred-work.md`
  - [x] File the four new ledger items listed in AC7 (`acceptAll` silent-200, shared
    `DRILL_UPLOAD_NOT_ALLOWED`, absent `SluWeeklySnapshotRepositoryIT` coverage, post-success reload
    reported as accept failure)
  - [x] Update `sprint-status.yaml`'s `skillars-deferred-30-...` entry status as this story progresses

### Review Findings

Second, independent code review (2026-08-18) — 3 adversarial layers (Blind Hunter, Edge Case Hunter,
Acceptance Auditor) run against the full diff, including the first review's already-applied patches.
0 `decision-needed`, 6 `patch`, 1 `defer`, 13 dismissed as noise/false-positive/already-handled.
One raised finding (inconsistent refresh-on-failure across the three coach-side accept flows) was
found independently by two of the three layers but turned out to duplicate an item the *first* review
already filed in `deferred-work.md` (`## Deferred from: code review of skillars-deferred-30...`,
"The three coach-side accept flows now have three different post-failure refresh behaviours...") —
counted among the 13 dismissed rather than re-filed.

- [x] [Review][Patch] `handleAcceptAll` missing `MISSING_RIGHTS` branch for the batch-ownership
  pre-flight rejection [src/frontend/src/pages/coach/CoachBookingRequestsPage.vue:187-199] —
  `BookingBatchService.acceptAll` has a third pre-flight check reachable before the per-booking loop
  (`:241-243`, "Coach does not own this booking batch" → `OperationNotAllowedException(...,
  SecurityError.MISSING_RIGHTS)`), unlocked and not swallowed, same as the two codes the catch already
  maps. It falls to the generic `booking.batch.acceptError` toast today, and the code comment at
  `:189-192` incorrectly claims only two pre-flight checks can reach the client. Fix: add an
  `errorKey === 'MISSING_RIGHTS'` branch reusing the existing `booking.errors.requestNotAllowed` key
  (same pattern already used in `submit()`/`submitBatchRequest()`/`handleAccept`), and correct the
  comment to describe three reachable pre-flight checks, not two.
- [x] [Review][Patch] `BATCH_ALREADY_PROCESSED` misattributed to `createBatch` instead of `acceptAll`
  in 3 docs [story Resolutions section; deferred-work.md; sprint-status.yaml] — all three claim
  `createBatch`'s "four non-authorization causes" include `BATCH_ALREADY_PROCESSED`. The check
  actually lives in `acceptAll` (`BookingBatchService.java:245-247`), a different method; `createBatch`
  (`:95-219`) has no such check. Fix: correct the attribution in all three docs.
- [x] [Review][Patch] `submitBatchRequest()` branch count overstated as six; diff shows five
  [story Resolutions section] — the diff adds exactly five branches (`booking.coachUnavailable`,
  `booking.startTimeInPast`, `booking.invalidTimeRange`, `booking.slotOutsideAvailability`,
  `MISSING_RIGHTS`), correctly with no `batchAlreadyProcessed` branch (unreachable from `createBatch`).
  Fix: correct "six" to "five".
- [x] [Review][Patch] AC2 Completion Note stale re: `MISSING_RIGHTS`'s remaining overload [story Dev
  Agent Record → Completion Notes, AC2 bullet] — the note still says `MISSING_RIGHTS` conflates
  "pack not owned" / "slot outside availability" / "coach profile inactive", but Decision 1 already
  split the latter two out into dedicated `BookingError` codes. Post-split, `submit()`'s
  `MISSING_RIGHTS` only reaches 3 genuine authorization sites. Fix: append a correction note.
- [x] [Review][Patch] AC5's stated rationale contradicted by a correction the first review inserted
  into the test file, never reconciled in the story [story AC #5;
  src/test/java/.../SluDashboardServiceTest.java] — AC5 states the old mirrored-formula test "would
  stay green" under a production off-by-one; the shipped test file now carries a comment (from the
  first review) explaining this premise was wrong — a production regression would desync Mockito's
  `eq()` matchers and fail via `STRICT_STUBS`'s `PotentialStubbingProblem` regardless. Verified
  correct by direct reasoning about strict-stubs behavior. The literal-value rewrite is still good
  practice (readability, independent checkability) even though the original premise was wrong. Fix:
  add a correction note to AC5, following this story's own established pattern for prior corrections.
- [x] [Review][Patch] AC3 Completion Note doesn't disclose the `batchAlreadyProcessed` branch, which
  exceeds AC3's literal "add only coachUnavailable" instruction [story AC #3 / Completion Notes] —
  AC3 said add **only** `coachUnavailable` to `handleAcceptAll`; Decision 1 later added
  `batchAlreadyProcessed` too (functionally correct and beneficial), but neither AC3's text nor its
  Completion Note mention the addition. Fix: note that Decision 1 superseded AC3's original scope.
- [x] [Review][Defer] `handleAccept`'s post-failure refresh call is unguarded — a rejecting refresh
  could throw an unhandled promise rejection [src/frontend/src/pages/coach/
  CoachBookingRequestsPage.vue:164] — deferred, pre-existing. `await
  bookingStore.loadCoachBookingRequests()` inside the catch block has no try/catch of its own. Not
  introduced by this diff.

**All 6 patches applied 2026-08-18.** Each claim was re-verified against source before applying, and all
six held: `acceptAll` does have three client-reachable pre-flight checks (`:242` ownership, `:246`
already-processed, `:255` suspended coach), `BATCH_ALREADY_PROCESSED` does live in `acceptAll` and not
`createBatch`, and the batch chain does add five branches, not six. `handleAcceptAll` gained the
`MISSING_RIGHTS` branch and its comment now counts three; the misattribution and the branch count were
corrected in all three docs; and correction notes were added to AC2's and AC3's Completion Notes and to
AC5's rationale. The one deferred item was left as filed. Post-patch `mvn -o verify` and ESLint results
are recorded in the Resolutions section below.

## Dev Notes

- **Scope discipline.** Six small, independently-safe items across session/video, booking (frontend x3),
  payment (test coverage), development (test coverage), and one docs file. Do not use this as a pretext to
  "clean up while you're in there" — e.g. don't extend AC3's coach-side mapping into a shared error-mapping
  composable, don't wire `MISSING_RIGHTS` handling into `ParentBookingsPage.submitReschedule()` (explicitly
  excluded, see "Why this story exists" — it needs distinct backend codes, not a frontend branch), don't
  fix `acceptAll`'s silent-200 bug or split `DRILL_UPLOAD_NOT_ALLOWED` inline even though both are
  discovered by this story — file them per AC7/Task 7 instead. Don't touch `ConfigService.getBoolean` or
  the `lock.timeout` gap even though they're referenced in this same ledger region.

- **AC1, AC2, AC3 are all instances of the same underlying bug class `skillars-deferred-28`/`-29` already
  fixed repeatedly**: a catch block either inspects the wrong response field, or inspects the right field
  but doesn't recognize a code the backend actually throws. This story applies the same fix pattern —
  branch on `err?.response?.data?.errorMsg?.errorKey`, fall back to the existing generic toast — to
  sibling call sites `-28`/`-29` didn't reach, **except where the backend itself cannot deliver the code to
  the client at all** (AC3's `handleAcceptAll`/`booking.slotUnavailable` — do not add a branch that can
  never fire). Match the existing idiom exactly; do not introduce a shared error-extraction helper or
  refactor the pattern into a composable — that would be scope creep beyond a bundled small-fix story.

- **Prefer existing i18n keys over new ones.** This story's own review found two of its originally-proposed
  new keys already existed, unused, elsewhere in the same bundles. Before adding any new locale key in
  AC1/AC2, grep the three bundles for the English string you're about to write — if a near-identical key
  already exists, reuse it rather than creating a second, diverging copy.

- **There is no frontend test infrastructure in this repo** — no `vitest.config.*`, no `*.spec.js`, no
  `*.test.js` exist anywhere under `src/frontend` (excluding `node_modules`; the only `*.test.js` under
  `src/frontend` is inside a vendored `lib/node_modules` copy of a Quasar CLI dependency, not this
  project's own code), a standing gap recorded by every prior frontend-touching story since `skillars-5-4`.
  AC1/AC2/AC3 therefore ship with zero automated coverage; the manual-verification steps in Tasks 1-3 are
  the only check that will ever run. Do them for real, following the exact stale-client-state procedures
  written into each task — a naive "click the button on a fresh page load" attempt will not reproduce most
  of these branches and will produce a false-negative "couldn't repro" that gets the step silently skipped.
  This is precisely the gap that let `DrillDetailPanel.vue`'s `helpCode` bug (fixed by `-29` AC2) go
  unnoticed for multiple stories.

- **AC2's `MISSING_RIGHTS` branch is a real but partial improvement, not full precision — say so honestly
  in the commit/PR description.** It cannot distinguish "pack not owned by this parent" from "requested
  slot outside coach availability" from "coach profile inactive". Do not claim in any completion note that
  this AC gives parents a precise reason for every rejection; it gives them a *category* (their selection
  needs review) instead of a fully generic failure. Similarly, do not describe `payment.packCoachMismatch`
  or `payment.packExhausted` as "live" defects in any completion note — `packCoachMismatch` is not
  reachable via the UI at all today (defensive-only), and `packExhausted` requires a stale-tab race. Only
  `payment.coachStripeNotConfigured` is reachable from ordinary, single-session use.

- **AC4 and AC6 touch the same registry line in `docs/testing/test-data-isolation.md`, plus AC4 touches a
  comment inside the test file itself — do all three in one edit, not as separate diffs that could
  conflict or leave one out of sync.** AC6's task list already folds AC4's range widening into itself for
  this reason; don't forget the `SessionPackPurchaseRepositoryIT.java:26` comment, which is easy to miss
  since it isn't in `docs/`.

- **AC5's mutation-verification step is not optional.** This project's own history now records this
  pattern failing silently three times (`skillars-deferred-13`, `-15`, and `skillars-uat-3`'s own review
  D11) — a lock or an assertion that "looks" like it proves something but was never actually exercised
  against a reverted fix. Do not mark Task 5 complete without actually reintroducing the off-by-one and
  watching a test go red first. Separately: do not describe AC5 as adding rollover *coverage* in any
  completion note — it doesn't; the literal-value switch is what matters, and the actual rollover gap
  (the JPQL predicate itself) is filed as a new ledger item, not closed here.

- **File paths this story touches:**
  - `src/frontend/src/components/session/DrillDetailPanel.vue` (AC1)
  - `src/frontend/src/pages/parent/BookingRequestPage.vue` (AC2)
  - `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue` (AC3)
  - `src/frontend/src/pages/coach/CoachCommandCenterPage.vue` (AC3)
  - `src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js` (AC1, AC2)
  - `src/test/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepositoryIT.java`
    (AC4, including its `:26` fixture-range comment)
  - `src/test/java/com/softropic/skillars/platform/development/service/SluDashboardServiceTest.java` (AC5)
  - `docs/testing/test-data-isolation.md` (AC4, AC6)
  - `_bmad-output/implementation-artifacts/deferred-work.md` (AC7)
  - `_bmad-output/implementation-artifacts/sprint-status.yaml` (AC7, status line only)

### Project Structure Notes

- AC1, AC2, AC3 are pure frontend catch-block edits plus i18n additions — no production Java code changes,
  no new components, no new routes. AC4 and AC5 are pure test additions with zero production-code risk
  (both add fixture rows / literal values to existing test methods rather than new production logic). AC6
  is a docs-only edit (plus the one-line test-file comment AC4 folds in). No new migrations, no changes to
  any already-applied Flyway migration.
- Follows the same flat, non-epic-nested tracking convention every other `skillars-deferred-N` story uses
  in `sprint-status.yaml` (the "DEFERRED WORK" block).

### References

- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — "## Deferred from: code review of
  skillars-deferred-29-clockprovider-error-mapping-and-repository-boundary-test-coverage-fixes
  (2026-08-17)" (AC1 at `:1482`, AC2 at `:1478`, AC4 at `:1486`, AC5 at `:1492`); "## Deferred from:
  skillars-deferred-28-booking-error-messaging-subscription-coverage-and-media-timestamp-test story
  creation (2026-08-17)" (AC3 at `:1461`)
- [Source: _bmad-output/implementation-artifacts/story-review.md] — senior-dev audit (2026-08-18) that
  produced the AC1/AC2/AC3/AC5 corrections folded into this version; findings B1-B3, S1-S6, m1-m3
- [Source: src/frontend/src/components/session/DrillDetailPanel.vue:382-391,94,241] — confirms AC1's
  current three-branch catch block, the two unhandled `errorKey` values, and the template's
  `libraryType === 'COACH'` gate
- [Source: src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java:44-141] —
  confirms AC1's three `DRILL_UPLOAD_NOT_ALLOWED`/`FeatureGatedException` throw sites (`:57`, `:78`,
  `:135-140`) and `DrillLibraryService.java:70`'s owner-scoped listing
- [Source: src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java:267-277,326-330] —
  confirms AC1's `security.featureGated` wire value and `OperationNotAllowedException`'s
  `exception.getErrorCode().getErrorCode()` mapping
- [Source: src/main/java/com/softropic/skillars/platform/session/contract/SessionErrorCode.java] — confirms
  `DRILL_UPLOAD_NOT_ALLOWED`'s wire value is its own enum name
- [Source: src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:160-270] —
  confirms AC2's five throw sites and their exact rejection reasons
- [Source: src/main/java/com/softropic/skillars/platform/booking/api/BookingApiAdvice.java:18-23] — confirms
  AC2's `PaymentGatewayException` wire-value mechanism
- [Source: src/frontend/src/i18n/en-US/index.js:1038,1060-1061,916-917,491] — confirms AC1/AC2's reusable
  existing keys
- [Source: src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java] — confirms
  AC3's `booking.coachUnavailable`/`booking.slotUnavailable` wire values
- [Source: src/frontend/src/pages/coach/CoachBookingRequestsPage.vue:152-186,
  src/frontend/src/pages/coach/CoachCommandCenterPage.vue:372-383] — confirms AC3's three current catch
  blocks
- [Source: src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:242-283] —
  confirms AC3's `handleAcceptAll` swallowing loop and the silent-200-on-zero-accepted path
- [Source: src/test/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepositoryIT.java] —
  confirms AC4's single-coach fixture across all seeded rows and the stale `:26` range comment
- [Source: src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchase.java:26-29] —
  confirms AC4's `purchaseId` is a DB-generated `UUID`, not a claimed fixture id
- [Source: src/test/java/com/softropic/skillars/platform/development/service/SluDashboardServiceTest.java] —
  confirms AC5's current mirrored-formula assertion shape, and that the class is a pure Mockito unit test
- [Source: src/main/java/com/softropic/skillars/platform/development/repo/SluWeeklySnapshotRepository.java:30-33] —
  confirms AC5's compound `(year, week)` predicate lives in JPQL, untested at any date
- [Source: docs/testing/test-data-isolation.md:206,217-220] — confirms AC6's self-contradiction and stale
  commit anchor

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `mvn -o test -Dtest=SessionPackPurchaseRepositoryIT` — 2/2 green (AC4)
- `mvn -o test -Dtest=SluDashboardServiceTest` — 6/6 green (AC5)
- Mutation-verify AC4: deleted `AND p.coachId = :coachId` from `SessionPackPurchaseRepository.findActivePacks` → new assertion failed at `SessionPackPurchaseRepositoryIT.java:210` as expected; reverted.
- Mutation-verify AC5: changed `SluDashboardService.getWeeklyExposure`'s `from` computation from `minusWeeks(weeksBack - 1)` to `minusWeeks(weeksBack)` → all 3 `getWeeklyExposure_*` tests errored (`PotentialStubbingProblem`, Mockito strict stubs) as expected; reverted.
- `npx eslint` on all 7 modified frontend files (3 pages/components, 3 i18n bundles) — clean, zero errors/warnings.
- Full regression: `mvn -o verify` — 887 unit + 916 IT tests, 0 failures, 0 errors, 5 skipped, BUILD SUCCESS (18:42 min for the Java modules); `npx quasar build` compiled the SPA successfully as part of the same run.

### Completion Notes List

- **AC1** (`DrillDetailPanel.vue`): Added `DRILL_UPLOAD_NOT_ALLOWED` and `security.featureGated` branches before the generic fallback. Feature-gated branch reuses the existing `security.featureGated` key verbatim, no upgrade CTA added per the AC's own scope decision. Added new key `session.drillLibrary.upload.videoAlreadyLinked` to all three locale bundles, English text mirroring the backend's already-linked message verbatim. As the story itself documents, `DRILL_UPLOAD_NOT_ALLOWED` is not unique to "already linked" — `initiateUpload`'s drill-ownership check throws the identical code — but that path is unreachable from this panel today by accident of the current data flow, not by contract. The real fix (splitting the code) was already filed in the ledger at story creation.
- **AC2** (`BookingRequestPage.submit()`): Added all five branches (`payment.coachStripeNotConfigured`, `payment.packExpired`, `payment.packCoachMismatch`, `payment.packExhausted`, `MISSING_RIGHTS`). Reused three existing i18n keys verbatim (`payment.error.coachStripeNotConfigured`, `payment.sessionPack.packCoachMismatch`, `payment.sessionPack.packExhausted`) and added exactly two new keys under `booking.errors` (`packExpired`, `requestNotAllowed`) in all three bundles. Per the story's own honesty framing: only `payment.coachStripeNotConfigured` is reachable via ordinary single-session use; `packCoachMismatch` is defensive-only (not reachable via the UI today) and `packExhausted`/`packExpired` need a stale-tab race — none of that is claimed otherwise here. `MISSING_RIGHTS` is a partial improvement (a rejection *category*, not per-reason precision) — it cannot distinguish "pack not owned" from "slot outside availability" from "coach profile inactive", and no claim to the contrary is made. Re-read `BookingBatchService.createBatch` directly and confirmed it never resolves a session pack or calls the payment gateway, so `submitBatchRequest()` is correctly left untouched. **SUPERSEDED by Decision 1 of the 2026-08-18 code review — both sentences above are now stale.** "Slot outside availability" and "coach profile inactive" were split out into dedicated `BookingError` codes, so `submit()`'s `MISSING_RIGHTS` branch now reaches only three genuine authorization sites and its copy was reworded accordingly. And the `createBatch` re-read, while correct about the four `payment.*` codes, missed the fifth: `createBatch` threw `MISSING_RIGHTS` at six sites, so `submitBatchRequest()` was *not* correctly left untouched — it has since gained five mapped branches.
- **AC3** (coach-side accept flows): Added `booking.coachUnavailable`/`booking.slotUnavailable` branches to `CoachBookingRequestsPage.vue`'s `handleAccept` and `CoachCommandCenterPage.vue`'s `handleAcceptReschedule` (both catch blocks changed from bare `catch {` to `catch (err) {` to read `err?.response?.data?.errorMsg?.errorKey`). `handleAcceptAll` got only the `coachUnavailable` branch, deliberately omitting `slotUnavailable` — it is dead code there, since `BookingBatchService.acceptAll`'s per-booking loop swallows that throw before it can reach the client; only the pre-flight suspension check can surface `coachUnavailable` to this flow. **SCOPE EXTENDED past AC3's literal "add only `coachUnavailable`" instruction, by the 2026-08-18 code review — recorded here because neither AC3's text nor this note originally disclosed it.** Decision 1 added a `booking.batchAlreadyProcessed` branch (the already-processed pre-flight check, split out of `MISSING_RIGHTS`), and the second review added a `MISSING_RIGHTS` branch for the batch-ownership pre-flight check at `BookingBatchService.java:241-243` — a third reachable pre-flight condition that the original code comment here wrongly said did not exist. `slotUnavailable` remains correctly omitted. No new i18n keys needed — both wire values already exist in `booking.errors.*`. Did not add a `console.warn` fallback here, per the story's explicit scope note that pattern was deliberately limited to the three parent-initiating flows.
- **AC4** (`SessionPackPurchaseRepositoryIT`): Added a second `CoachProfile` fixture (new coach, claimed `userId` `9620000004`) and one additional pack for the same player under that new coach, reusing coach A's existing `SessionPackTier` fixture. The pre-existing `containsExactlyInAnyOrder(controlPack, elapsedPausePack)` assertion already implicitly proves exclusion (the new pack is absent from the expected set). Mutation-verified per the story's non-optional instruction: reverting `AND p.coachId = :coachId` from the JPQL failed the new assertion, confirming the predicate is now actually exercised.
- **AC5** (`SluDashboardServiceTest`): Re-pinned all three `getWeeklyExposure_*` tests' fixed clock to `2027-01-06T10:00:00Z` and replaced the four mocked-call arguments (`fromYear`/`fromWeek`/`curYear`/`curWeek`) with hardcoded literal shorts. Independently re-verified the ISO week arithmetic via a real JVM computation before hardcoding (did not trust the story's arithmetic blindly, per its own instruction) — confirmed `curYear=2027, curWeek=1, fromYear=2026, fromWeek=47`. Left `prevYear`/`prevWeek`/`prevPrevYear`/`prevPrevWeek` in the second test computed dynamically off the pinned clock, since those are not part of the mocked call's arguments, only fixture-building for the `trend` assertion. Mutation-verified per the story's non-optional instruction: reintroducing an off-by-one in production (`minusWeeks(weeksBack)` instead of `minusWeeks(weeksBack - 1)`) made all three tests fail with Mockito's `PotentialStubbingProblem`, confirming the literal values — not the choice of date — are what breaks the self-mirroring. Per the story's explicit instruction, this AC does **not** add rollover coverage for the JPQL predicate itself (`SluWeeklySnapshotRepository.findByPlayerIdFromWeek`); that gap was already filed as a new ledger item at story creation.
- **AC6** (`docs/testing/test-data-isolation.md`): Widened the registry row to `9620000001`–`9620000004`, added `9620` to the claimed-prefixes list, narrowed the free-blocks line to `9630`–`9690`, and dropped the stale `` at `21ef489` `` commit anchor from the claimed-prefixes sentence (reworded rather than re-pinned, per the AC's own reasoning that a new pin would just as quickly go stale). Done as one edit alongside AC4's range widening and the `SessionPackPurchaseRepositoryIT.java:26` comment update, per the story's explicit instruction not to split these into separate diffs.
- **AC7** (ledger hygiene): Annotated all six items `[CLOSED by skillars-deferred-30 ACn]` with a closure description each, following the file's established annotation convention. **WORDING CORRECTED by the 2026-08-18 code review:** this note originally said the six markers were "flipped from `[OWNED BY skillars-deferred-30 ACn]` (already present in `deferred-work.md` from story creation)" — no such marker ever existed on these items, at story creation or since; the file's only `[OWNED BY]` markers belong to deferred-15/16/18. The six closure annotations themselves are real and verified; only the provenance claim was wrong. The four new ledger items (`acceptAll` silent-200-on-zero-accepted, shared `DRILL_UPLOAD_NOT_ALLOWED` code, absent `SluWeeklySnapshotRepositoryIT` coverage, post-success reload reported as accept failure) were already filed under a "Deferred from: skillars-deferred-30 story creation and review" section at story-creation time — verified present, not re-filed. `sprint-status.yaml` updated `ready-for-dev` → `in-progress` → `review` as the story progressed.
- **Manual verification not performed**: This repo has no frontend test infrastructure and no browser-automation tooling was available in this session (a standing gap the story's own Dev Notes document, present since `skillars-5-4`). The manual-verification subtasks in Tasks 1–3 (feature-gate/already-linked toast rendering; `payment.coachStripeNotConfigured`/`packExhausted` reachability; coach-side `coachUnavailable`/`slotUnavailable` toast firing) were **not** exercised live and are left unchecked in Tasks/Subtasks rather than falsely marked done. All five branches' wire-value/i18n-key wiring was independently re-verified by direct code/grep inspection against the actual throw sites and locale bundles (see Completion Notes above), and ESLint passed clean on every changed file, but none of that substitutes for the live reproduction steps the story itself specifies. **Flagged for a human spot-check**, consistent with this project's established practice (e.g. `skillars-deferred-17`'s and `skillars-deferred-18`'s reviews, which recorded the identical gap and still proceeded to review status).

### File List

- `src/frontend/src/components/session/DrillDetailPanel.vue` (AC1)
- `src/frontend/src/pages/parent/BookingRequestPage.vue` (AC2)
- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue` (AC3)
- `src/frontend/src/pages/coach/CoachCommandCenterPage.vue` (AC3)
- `src/frontend/src/i18n/en-US/index.js` (AC1, AC2)
- `src/frontend/src/i18n/de-DE/index.js` (AC1, AC2)
- `src/frontend/src/i18n/fr-FR/index.js` (AC1, AC2)
- `src/test/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepositoryIT.java` (AC4, including its `:26` fixture-range comment)
- `src/test/java/com/softropic/skillars/platform/development/service/SluDashboardServiceTest.java` (AC5)
- `docs/testing/test-data-isolation.md` (AC4, AC6)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC7)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (AC7, status line only)

### Review Findings

Adversarial code review 2026-08-18 (3 layers: Blind Hunter, Edge Case Hunter, Acceptance Auditor; all
three completed, no layer failures). Every load-bearing claim below was independently re-verified by the
review orchestrator against current source before being written here. 2 decisions, 11 patches, 3 deferred,
4 dismissed as false positives.

**Bottom line: zero AC violations in `src/main` behaviour that this story introduced.** All 7 ACs are
implemented. The findings are (a) one confirmed pre-existing broken i18n key sitting inside the exact
`if/else` chain AC2 rewrote, (b) a false verification claim baked into a shipped source comment, and
(c) documentation/ledger inaccuracies in the AC7 artifacts.

- [x] [Review][Decision] `MISSING_RIGHTS` branch in `submit()` — keep, reword, or revert? — AC2 added a `MISSING_RIGHTS` → `booking.errors.requestNotAllowed` branch, but that one wire code covers six semantically distinct causes in `BookingService` (`:183` coach profile not active, `:189-192` start not in future, `:193-197` end ≤ start, `:218-221` slot not within coach availability, `:242-243`, `:264-268` pack not owned by this parent). The new copy — "Please review your selection and try again" — is wrong for at least three: "coach profile is not active" and "pack does not belong to this parent" are deterministically non-retryable, and the stale-slot case (`:189-192`, reached by leaving the page open past the slot's start time) is not a selection problem at all and reproduces indefinitely because nothing re-fetches the slot. This is the same retry-advice-for-non-retryable defect class AC1 exists to fix, shipped in AC2. Additionally, giving `MISSING_RIGHTS` its own branch removes it from the `console.warn('[booking] unmapped errorKey:', …)` diagnostic that `skillars-deferred-29` AC4 added deliberately for exactly this code — net effect is a still-generic toast, minus the log. Finally, `sprint-status.yaml` excludes `ParentBookingsPage.submitReschedule()`'s `MISSING_RIGHTS` mapping on the rationale "needs distinct backend codes, not a frontend branch" — a rationale that applies with *more* force to the case that shipped (6 causes / 8 throw sites) than to the one excluded (4 conditions). One of the two decisions is unjustified. [`src/frontend/src/pages/parent/BookingRequestPage.vue:497-498`]
- [x] [Review][Decision] `payment.packExpired` is thrown before the pack-ownership check, and AC2 just made the difference observable — fix ordering now, or defer to a backend story? — `BookingService.createBookingRequest` checks `pack.getExpiresAt().isBefore(Instant.now())` at `:261-263` *before* `!pack.getParentId().equals(parentId)` at `:264-268`. `packCoachMismatch` (`:269-271`) and `packExhausted` correctly sit after the ownership check; only `packExpired` leaks. Before this diff all three collapsed into one undifferentiated toast; AC2's new distinct toasts turn it into an oracle that distinguishes "an unowned pack id exists and is expired" (422 `payment.packExpired`) from "exists but is not yours" (`MISSING_RIGHTS`) from "does not exist" (404). Exploitability is low — it requires guessing a v4 UUID — but authorization should precede state validation, and the fix is a six-line move of the ownership check above the expiry check. Out of this story's stated frontend-only scope. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:258-271`]
- [x] [Review][Patch] `t('booking.requests.submitError')` resolves to nothing in all three locales — the generic fallback toast renders the raw key path [`src/frontend/src/pages/parent/BookingRequestPage.vue:500`]
- [x] [Review][Patch] Test comment asserts a detection property the change does not add — strict stubs already caught production date-math mutations [`src/test/java/com/softropic/skillars/platform/development/service/SluDashboardServiceTest.java:57-59`]
- [x] [Review][Patch] Newly-filed ledger item describes a defect that cannot occur — all three refresh loaders swallow their errors and never rethrow [`_bmad-output/implementation-artifacts/deferred-work.md:1507`]
- [x] [Review][Patch] Newly-filed ledger item undercounts `DRILL_UPLOAD_NOT_ALLOWED` throw sites (2, actually 3), so its "one code per throw site" fix prescription is wrong [`_bmad-output/implementation-artifacts/deferred-work.md`]
- [x] [Review][Patch] `sprint-status.yaml` claims 6 items were "flipped from `[OWNED BY]`" — no `[OWNED BY]` marker exists on any of them, before or after [`_bmad-output/implementation-artifacts/sprint-status.yaml:136`]
- [x] [Review][Patch] Ledger section header attributes the 4 new items to corrections of "AC1/AC3/AC5" — there were 4 corrections (AC2's fifth code is omitted) and the 4th item is not a premise correction [`_bmad-output/implementation-artifacts/deferred-work.md:41-44`]
- [x] [Review][Patch] Task 2's checked subtask "confirm `createBatch` genuinely cannot throw these five codes" is false — `createBatch` throws `MISSING_RIGHTS` at six sites [`skillars-deferred-30…md` Task 2]
- [x] [Review][Patch] IT method name enumerates four exclusion dimensions but now covers five — rename to include the other-coach boundary [`src/test/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepositoryIT.java:104`]
- [x] [Review][Patch] de-DE `booking.errors.requestNotAllowed` uses informal *du* beside formal *Sie* siblings in the same object [`src/frontend/src/i18n/de-DE/index.js:464-465`]
- [x] [Review][Patch] Test comment's fixture-vs-call-arg dichotomy is wrong — `curYear`/`curWeek` build fixture snapshots too [`src/test/java/com/softropic/skillars/platform/development/service/SluDashboardServiceTest.java:88-93`]
- [x] [Review][Patch] `$q.notify({ type, message })` vs `({ message, type })` ordering inconsistent within the same touched catch chains [`src/frontend/src/pages/coach/CoachBookingRequestsPage.vue:189-192`, `CoachCommandCenterPage.vue:380-385`]
- [x] [Review][Defer] `submitBatchRequest()` leaves `MISSING_RIGHTS` unmapped, the same defect AC2 closed 30 lines above [`src/frontend/src/pages/parent/BookingRequestPage.vue:523-538`] — **no longer deferred: closed by Decision 1's implementation**, which re-coded `createBatch`'s four non-authorization causes and gave `submitBatchRequest()` six mapped branches. Not re-filed in `deferred-work.md`.
- [x] [Review][Defer] Three sibling accept catch blocks now have three different post-failure refresh behaviours; `handleAccept` toasts before reloading [`src/frontend/src/pages/coach/CoachBookingRequestsPage.vue:155-168`] — deferred, pre-existing
- [x] [Review][Defer] Four `payment.*` wire codes resolve into three different i18n namespaces with no stated rule [`src/frontend/src/pages/parent/BookingRequestPage.vue:489-498`] — deferred, pre-existing

**Dismissed as false positives (4)** — each refuted against source, recorded so they are not re-raised:
`security.featureGated` "missing from bundles" (exists at `en-US:492`, `de-DE:1003`, `fr-FR:508`);
`video.constraintViolated` "is a dead branch" (`SessionApiAdvice.java:21` emits exactly that key — the
bare-enum-name rule inferred from `QUOTA_EXCEEDED` does not apply, this exception has its own
module-level advice); "the IT adds no assertion so AC4's mutation claim is unfounded" (the pre-existing
assertion is `containsExactlyInAnyOrder`, which is exhaustive, so the added row does discriminate —
AC4 is genuinely mutation-sensitive); `acceptAll` returning 200 + "All sessions accepted" for zero
accepted bookings (real, but this story already filed it as an out-of-scope ledger item — no duplicate
entry needed).

**Verified sound, recorded so they are not re-walked:** all three `story-review.md` pre-dev corrections
hold against current source; AC5's four week literals are correct (`2027-01-06` = Wed of ISO week 1/2027,
`minusWeeks(7)` → `2026-11-18` = ISO week 47/2026) and test 2 additionally exercises a genuine 53-week
ISO year; AC4 is mutation-sensitive; AC6's registry edit is exact (24 → 25 prefixes, none dropped,
`9630`–`9690` genuinely unclaimed); all nine new/reused i18n keys resolve at their exact nesting path in
all three bundles with no placeholder drift; every new `errorKey` literal matches a real throw site in
the `data.errorMsg.errorKey` field the frontend reads; every loading flag is reset in a `finally`; no
path produces two toasts or none.

#### Resolutions (2026-08-18)

**Decision 1 — Mbah chose option 3: split the backend codes now.** `SecurityError.MISSING_RIGHTS` in
`BookingService.createBookingRequest` and `BookingBatchService.createBatch` covered both genuine
authorization failures and four ordinary validation failures. The four validation causes were given
distinct codes on `BookingError` (`START_TIME_IN_PAST`, `INVALID_TIME_RANGE`, `SLOT_OUTSIDE_AVAILABILITY`,
plus reuse of the existing `COACH_UNAVAILABLE`), with backend messages added to
all four `messages*.properties` bundles and `booking.errors.*` keys added to all three frontend bundles.
A fifth code, `BATCH_ALREADY_PROCESSED`, was added for `BookingBatchService.acceptAll`'s already-processed
pre-flight check. **CORRECTED by the second review (2026-08-18):** an earlier version of this text listed `BATCH_ALREADY_PROCESSED` among `createBatch`'s re-coded causes. That check lives in `acceptAll` (`BookingBatchService.java:245-247`), a different method — `createBatch` has no such check. `createBatch`'s four causes are the ones named above; `BATCH_ALREADY_PROCESSED` is a fifth, re-coded in `acceptAll` and surfaced by `handleAcceptAll`, not `submitBatchRequest()`.
`MISSING_RIGHTS` now appears in `createBookingRequest` at exactly three sites, all authorization, and its
toast copy was reworded to authorization-only wording with the retry advice removed. `submit()` gained
three new branches and `submitBatchRequest()` five (**CORRECTED by the second review:** originally stated
as six; the diff adds exactly five, correctly with no `batchAlreadyProcessed` branch since that code is
unreachable from `createBatch`), which also closes deferred item W1 above. This is a wire
contract change; blast radius was small because `OperationNotAllowedException` maps to 403 regardless of
code and the booking tests assert on exception messages, so exactly one test needed updating.

**Decision 2 — Mbah chose option 1: fix the ordering now.** The pack-ownership check was moved above the
pack-expiry check in `createBookingRequest`, closing the three-way oracle (expired-and-unowned → 422
`payment.packExpired`, unexpired-and-unowned → `MISSING_RIGHTS`, nonexistent → 404) that AC2's distinct
toasts had made observable. The comparison was made null-safe (`Objects.equals`, matching the same
method's player-ownership checks) after the reorder exposed a fixture that never set `parentId` — a real
gap the old ordering had been hiding by short-circuiting first. A new mutation-verified test,
`createBookingRequest_expiredPackOwnedByAnotherParent_reportsMissingRightsNotExpiry`, pins the ordering;
reverting the reorder fails that test and only that test.

**Patches (11)** — all applied. The two `SluDashboardServiceTest` comments were corrected to state what the
change actually does (and that the AC5 detection premise was wrong); `booking.requests.submitError` was
added to all three bundles; the IT method was renamed to name all five exclusion dimensions; the de-DE
string was switched from informal *du* to formal *Sie*; `$q.notify` option ordering was normalized; and
four documentation claims were corrected — the `DRILL_UPLOAD_NOT_ALLOWED` throw-site count (2 → 3, with
the fix prescription changed to a split by cause), the withdrawn post-success-reload ledger item (premise
refuted: all three loaders swallow their errors; the real residual — a silently stale list — is recorded
in its place), the ledger section header, and the `[OWNED BY]` provenance claim in both `sprint-status.yaml`
and this file's AC7 note.
