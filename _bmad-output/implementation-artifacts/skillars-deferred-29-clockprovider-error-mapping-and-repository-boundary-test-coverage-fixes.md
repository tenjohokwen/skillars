# Story Deferred-29: ClockProvider Adoption, Frontend Error-Mapping Fixes & Repository Boundary Test Coverage

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Skillars maintainer,
I want six small, independently-verified deferred items closed — `SluDashboardService` reading the wall
clock directly instead of this codebase's established `ClockProvider` (which also makes its own test
suite flaky once a week), a video-upload error handler that branches on the wrong response field and so
never shows its two specific error toasts, two parent-booking submit flows whose success navigation can
be misreported as a failure, three booking error-toast catch blocks that silently swallow unrecognised
error codes, and two repository integration tests whose assertions don't actually exercise the
conditions they're supposed to prove — so that each of six unrelated, previously-deferred defects,
spanning the development, session/video, booking, and payment modules, gets fixed without bundling any
of them into a larger story that would need its own design pass.

### Why this story exists

Drawn directly from `_bmad-output/implementation-artifacts/deferred-work.md`, per Mbah's direction to
group small, unrelated, already-deferred items into one story to reduce dev overhead — the same spirit
as `skillars-deferred-11/20/21/22/23/24/25/26/27/28`. All items below were independently re-verified
against **current** code during this story's creation (2026-08-17), not trusted from the ledger's text,
which the ledger's own header warns can be stale. One item's ledger citation (AC3 below) was found to be
partially wrong during this re-verification and is corrected in this story rather than carried forward.
**Senior-dev review correction (2026-08-17):** deeper re-verification during review found AC2's and AC3's
own analysis, not just the ledger citations they were drawn from, needed correction too — AC2's replacement
value for the quota branch was wrong (see AC2) and AC3's described failure mode is unreachable (see AC3).
Both are corrected below rather than carried forward as originally drafted.

A full read of `deferred-work.md` (all 1472 lines) was performed before selecting these six, focused on
the most recent, least-mined sections (post `skillars-deferred-24`, 2026-08-15 onward) since every older
section has already been swept by multiple prior `skillars-deferred-N` stories. The following categories
of ledger items were deliberately excluded as too large, blocked, or needing a decision this story's
bundled-fix bar does not cover — not omitted by oversight:

- **The `jakarta.persistence.lock.timeout` dead-code gap** (`skillars-deferred-23`'s finding, restated at
  `deferred-work.md:1424`) — confirmed empirically dead against this project's Postgres/Hibernate
  combination across four repositories; the ledger item itself states a real fix needs a design decision
  between two competing approaches (`PESSIMISTIC_WRITE`+`NO_WAIT`+retry vs. an explicit `SET LOCAL
  lock_timeout`). Not touched here — same exclusion `skillars-deferred-27` already made.
- **`ConfigService.getBoolean`'s fail-open behavior on security-sensitive gates** (`deferred-work.md:1410`)
  — real, but "not alertable" is an observability/infrastructure gap, not a mechanical code fix.
- **The three coach-side accept flows' generic error catch** (`deferred-work.md:1461`) — mirrors this
  story's AC4 pattern but on `CoachBookingRequestsPage.vue`'s `handleAccept`/`handleAcceptAll` and the
  coach reschedule-accept flow; the ledger item itself notes it "would roughly double [the parent-side
  fix]'s file count" — left for its own story rather than doubling this one's scope.
- **`DrillMetadata.repDensity`'s `int`-vs-`Integer` gap** (`deferred-work.md:1444`) — needs a backend
  contract change plus a product decision on whether "unset" is a real state coaches can reach.
- **The two product questions** (`deferred-work.md:1463-1464`: post-start-time parent cancellation
  settling as a no-show; two independent refund-eligibility computations that can disagree) — both
  explicitly need product/design input, not a mechanical patch.
- **`booking.errors.batchSizeExceeded`'s wrong `{max}` figure** (`deferred-work.md:1470`) — the ledger
  item itself states the correct fix is a backend contract change (the exception needs to carry the real
  limit as a message argument), not a frontend edit.
- **The `GUARD_PATH`-duplication-class backup-script items** and **the `.env`-guard family** — already
  closed by `deferred-20`/`-21`/`-24`, confirmed via their `[CLOSED by ...]` annotations already present
  in the ledger; the one still-open duplication item (`deferred-work.md:1434`) is spec-directed (AC4's
  code block explicitly prescribed the per-caller shape) and needs its own sign-off to change, per that
  item's own text.
- Every ledger item explicitly marked "Deliberate", "needs sign-off", "product decision", or targeting a
  currently-unreachable code path (e.g. `CoachMediaItem`'s `@PrePersist`, already closed by
  `skillars-deferred-28`) — none of those are small, independently-safe, mechanical fixes.

## Deferred Items Closed

| Source | Item | Current location (re-verified) | AC |
|---|---|---|---|
| code review of `skillars-deferred-27-repository-ordering-updatable-guard-and-test-coverage-fixes` (2026-08-17) | `SluDashboardService.getWeeklyExposure` reads the wall clock directly instead of `ClockProvider`, and `SluDashboardServiceTest`'s `eq()` matchers (added by `deferred-27` AC4) inherit a once-a-week ISO-week-rollover race from it | `SluDashboardService.java:41`, `SluDashboardServiceTest.java:51,75,108` | 1 |
| code review of `skillars-deferred-27-repository-ordering-updatable-guard-and-test-coverage-fixes` (2026-08-17) | The inert `.with(DayOfWeek.MONDAY)` in the same `fromYear`/`fromWeek` computation | `SluDashboardService.java:42` | 1 |
| `skillars-deferred-28-...` story creation (2026-08-17) | `DrillDetailPanel.vue`'s video-upload catch block branches on `helpCode` instead of `errorMsg.errorKey`. **Correction from senior-dev review (2026-08-17):** only the quota branch was actually dead (`helpCode` there is a random per-request support id); the constraint-violation branch already worked because `SessionApiAdvice` happens to put the same string in `helpCode`. The quota branch's correct replacement value is the enum name `'QUOTA_EXCEEDED'`, not `'video.quotaExceeded'` (which is only the fallback message text) | `DrillDetailPanel.vue:382-390` | 2 |
| code review of `skillars-deferred-28-...` (2026-08-17) | `router.push` sits inside the `try` in `BookingRequestPage.vue`'s `submit()`/`submitBatchRequest()`. **Correction from senior-dev review (2026-08-17):** the described failure mode is unreachable today — `router.push` is not awaited, so a rejection never reaches `catch`, and vue-router 4.6.4 resolves (not rejects) on the cited guard-redirect trigger anyway. Implemented as defensive hardening against a future `await` addition, not as a bug fix | `BookingRequestPage.vue:479,507` | 3 |
| code review of `skillars-deferred-28-...` (2026-08-17) | Nothing logs when an unhandled `booking.*` code falls through to the generic toast, in all three catch blocks `deferred-28` AC2 wired | `BookingRequestPage.vue:488,521`, `ParentBookingsPage.vue:213` | 4 |
| code review of `skillars-deferred-27-...` (2026-08-17) | `SessionPackPurchaseRepositoryIT`'s single test never exercises `findActivePacks`' other 4 `WHERE` predicates at their boundaries | `SessionPackPurchaseRepositoryIT.java:59-86`, `SessionPackPurchaseRepository.java:37-46` | 5 |
| code review of `skillars-deferred-27-...` (2026-08-17) | `BookingRepositoryIT`'s `updatable = false` test can't distinguish "these 3 columns are immutable" from "the whole entity is immutable" | `BookingRepositoryIT.java` | 6 |

**Explicitly NOT in this story** (considered during story creation and rejected — do not implement):

- **The `lock.timeout` dead-code gap and `ConfigService.getBoolean` fail-open/no-alert gap** — both need a
  design decision or new infrastructure, not a same-file fix. See "Why this story exists" above.
- **Coach-side accept-flow error mapping, `repDensity` nullability, the two refund/no-show product
  questions, and `batchSizeExceeded`'s wrong figure** — each needs a product decision or a backend
  contract change beyond a mechanical patch. See "Why this story exists" above.
- **All other open ledger items** not listed in the table above — every one inspected during this story's
  creation either needed a product/design decision, targeted an unreachable/already-closed code path, or
  duplicated a fix a prior story already made.

## Acceptance Criteria

1. **`SluDashboardService.getWeeklyExposure` reads time via this codebase's established `ClockProvider`
   instead of a bare `ZonedDateTime.now(ZoneOffset.UTC)`, and the inert `.with(DayOfWeek.MONDAY)` call is
   removed.** `SluDashboardService.java:41` currently reads `ZonedDateTime now =
   ZonedDateTime.now(ZoneOffset.UTC);` directly. `infrastructure/util/ClockProvider`
   (`src/main/java/com/softropic/skillars/infrastructure/util/ClockProvider.java`) is this codebase's
   established time-source convention — a `ThreadLocal<Clock>` falling back to
   `Clock.systemDefaultZone()`, already read by 17 production files (e.g.
   `StripePaymentGateway.java:84`'s `Instant.now(ClockProvider.getClock())`). Change line 41 to
   `ZonedDateTime now = ZonedDateTime.now(ClockProvider.getClock()).withZoneSameInstant(ZoneOffset.UTC);`
   (add the `com.softropic.skillars.infrastructure.util.ClockProvider` import) — behaviour-identical in
   production when no clock is pinned (`ClockProvider.getClock()` returns `Clock.systemDefaultZone()`,
   and `withZoneSameInstant(ZoneOffset.UTC)` produces the same instant `ZonedDateTime.now(ZoneOffset.UTC)`
   already did). **Also remove `.with(DayOfWeek.MONDAY)`** from line 42's `from` computation — it is
   provably inert: the adjuster moves to the Monday of the *same* ISO week, so
   `IsoFields.WEEK_BASED_YEAR`/`IsoFields.WEEK_OF_WEEK_BASED_YEAR` (the only two fields ever extracted
   from `from`) are identical with or without it. Removing it is a pure simplification with no behaviour
   change — do not replace it with a comment explaining why it's safe to remove; just delete it. Also
   remove the now-unused `import java.time.DayOfWeek;` at the top of the file — it has no other use in
   this class once line 42's adjuster is deleted.

   **Also delete the mirroring `.with(DayOfWeek.MONDAY)` in `SluDashboardServiceTest`'s three
   `getWeeklyExposure_*` tests** (`from = now.minusWeeks(8 - 1).with(DayOfWeek.MONDAY)`, currently at
   `SluDashboardServiceTest.java:54,88,111`), so the "test mirrors the service formula" property stays
   exact rather than silently breaking once the adjuster is removed from `SluDashboardService`. The
   adjuster is inert on both sides (it doesn't change `fromYear`/`fromWeek`), so this is a like-for-like
   removal, not a behaviour change to the test.

   **Then fix `SluDashboardServiceTest` to pin a fixed clock instead of reading the real wall clock**,
   which is what actually closes the once-a-week flake `skillars-deferred-27`'s code review found (AC4's
   new `eq()` matchers there compute `now` at T1 in the test and `SluDashboardService` computes its own
   `now` at T2 > T1; if T1/T2 straddle Monday 00:00:00.000 UTC the two diverge and Mockito's
   `STRICT_STUBS` throws `PotentialStubbingProblem`). Follow the exact pattern
   `StripePaymentGatewayTest.java` already uses (`TestClockProvider.setClock(Clock.fixed(Instant.parse(
   "..."), ZoneOffset.UTC))` in each test, `TestClockProvider.unsetClock()` in an `@AfterEach`) — import
   `com.softropic.skillars.infrastructure.util.TestClockProvider` and `java.time.Clock`. In each of the
   three affected tests (`getWeeklyExposure_returnsCurrentWeekSluPerSkill`,
   `getWeeklyExposure_withFewerThanRequestedWeeks_returnsAvailableWeeks`,
   `getWeeklyExposure_withNoData_returnsEmptyCurrentWeekAndEmptyTrend`, all in
   `SluDashboardServiceTest.java`), pin a fixed instant (any concrete UTC instant, not straddling a
   Monday-midnight boundary — e.g. `Instant.parse("2026-08-19T10:00:00Z")`, a Wednesday) via
   `TestClockProvider.setClock(...)` **before** computing `now`, then compute `now` from
   `ZonedDateTime.now(TestClockProvider.getClock())` instead of `ZonedDateTime.now(ZoneOffset.UTC)` so the
   test's own `now` and the service's `now` are now the exact same fixed instant, deterministically. Add
   `TestClockProvider.unsetClock()` in an `@AfterEach` (new import: `org.junit.jupiter.api.AfterEach`) so
   the pinned clock doesn't leak into other tests in the same JVM. Do not touch
   `getNarrativeSummary_*` tests — they don't call `getWeeklyExposure` and aren't affected by this clock
   read.

2. **`DrillDetailPanel.vue`'s video-upload catch block checks the response's `helpCode` field instead of
   `errorMsg.errorKey`. This is confirmed dead for the quota branch and already-working for the
   constraint-violation branch — the fix unifies both onto the documented field, it does not make both
   "start working".** `DrillDetailPanel.vue:382-390` currently reads `const helpCode =
   e?.response?.data?.helpCode` and branches `helpCode === 'video.quotaExceeded'` / `helpCode ===
   'video.constraintViolated'`. The correct field, confirmed both by `ErrorDto`/`ErrorMsg`'s wire shape
   (`src/main/java/com/softropic/skillars/infrastructure/message/ErrorDto.java`, `ErrorMsg.java` — JSON is
   `{helpCode, errorMsg: {errorKey, message}, fieldErrors}`) and by the sibling fix already applied in
   `BookingRequestPage.vue`/`ParentBookingsPage.vue` (`err?.response?.data?.errorMsg?.errorKey`), is
   `errorMsg.errorKey`. But the two branches' *current* values differ, and the replacement values are
   **not** `'video.quotaExceeded'`/`'video.constraintViolated'` in both cases — trace each throw site
   individually:
   - **Quota branch — currently dead, and the value is not what it looks like.** `helpCode` for this path
     is `ApiAdvice`/`VideoApiAdvice`'s per-request SQIDS-encoded support id (`ApplicationException.
     getSupportId()`, a random id per request — see `VideoApiAdvice.java:154-160`'s `toErrorDTO(msgKey,
     defaultMessage, helpCode)`), so `helpCode === 'video.quotaExceeded'` never matches today.
     `VideoApiAdvice.java:75`'s `QuotaExceededException` handler is `logErrorAndReturnDTO(ex,
     "video.quotaExceeded", VideoErrorCode.QUOTA_EXCEEDED.getErrorCode())` — read the parameter order at
     `VideoApiAdvice.java:149` (`logErrorAndReturnDTO(Throwable throwable, String defaultMsg, String
     msgKey)`): `"video.quotaExceeded"` is the **`messageSource` fallback message text**, not the wire
     `errorKey`. The wire `errorKey` is the third argument, `VideoErrorCode.QUOTA_EXCEEDED.getErrorCode()`,
     which (`VideoErrorCode.java`) returns `this.name()` — the literal string `"QUOTA_EXCEEDED"`. So the
     correct replacement condition for this branch is **`errorKey === 'QUOTA_EXCEEDED'`**, not
     `errorKey === 'video.quotaExceeded'`. Using the string `'video.quotaExceeded'` here would convert a
     never-firing branch into a still-never-firing branch.
   - **Constraint-violation branch — already fires today; the fix changes *which* field is read, not
     whether the branch works.** `DrillConstraintViolationException` (thrown at
     `DrillUploadService.java:68`) is handled by `SessionApiAdvice.java:21`:
     `new ErrorDto("video.constraintViolated", new ErrorMsg("video.constraintViolated", ex.getMessage()))`
     — `ErrorDto`'s constructor is `(helpCode, errorMsg)`, so *this* advice puts the literal string
     `"video.constraintViolated"` in **both** `helpCode` and `errorMsg.errorKey`. The existing
     `helpCode === 'video.constraintViolated'` branch already fires correctly today; the correct
     replacement condition, **`errorKey === 'video.constraintViolated'`**, is unchanged in value from what
     the story originally stated — only the field it's read from moves from `helpCode` to
     `errorMsg.errorKey`, for consistency with the quota branch and the sibling booking-page fix.

   Change `DrillDetailPanel.vue:383` to `const errorKey = e?.response?.data?.errorMsg?.errorKey` and line
   384 to check `errorKey === 'QUOTA_EXCEEDED'` (**not** `'video.quotaExceeded'`) and line 386 to check
   `errorKey === 'video.constraintViolated'` (unchanged value, new field). Do not change the i18n keys
   used in the notify calls (`session.drillLibrary.upload.quotaExceeded`/`constraintViolated`/
   `uploadFailed`, all already present in `src/frontend/src/i18n/en-US/index.js:341-342` and its sibling
   locale files) — only the field-name/value bugs above are being fixed.

   **Manual verification must exercise the quota/rate-limit path, not just the constraint-violation
   path.** Because the constraint branch already works today (see above), verifying only "upload an
   oversized file" will pass whether or not the quota-branch fix (`'QUOTA_EXCEEDED'`, not
   `'video.quotaExceeded'`) is correct — that check exercises no rate limiter and no quota. Confirm the
   quota toast specifically, by tripping `VideoService.java:231`'s rate limit or `:249`'s storage quota.

3. **`BookingRequestPage.vue`'s `submit()` and `submitBatchRequest()` call `router.push('/parent/bookings')`
   inside the `try` block. This is defensive hardening, not a live bug fix — re-verify the two claims
   below before implementing, because both of the story's original defect claims do not hold against
   current code.** `submit()` (`BookingRequestPage.vue:467-494`) calls `router.push('/parent/bookings')` at
   line 479, still inside `try`; `submitBatchRequest()` (`BookingRequestPage.vue:496-525`) has the
   identical shape at line 507, after the positive `booking.batch.submitted` toast fires at line 506.

   **Correction from senior-dev review (2026-08-17): the described failure mode cannot occur today, for
   two independent reasons.** (1) Neither call site `await`s `router.push` — both are bare
   `router.push('/parent/bookings')` statements. A `try`/`catch` only intercepts synchronous throws and
   *awaited* rejections; an un-awaited rejected promise never reaches `catch (err)` at all — it surfaces
   as an unhandled rejection instead. (2) Even if it were awaited, the cited trigger (a route guard
   redirect) does not reject in this app's router: `vue-router` is `4.6.4` (`package-lock.json`), where
   `router.push()` resolves with a `NavigationFailure` object on aborted/cancelled/duplicated/redirected
   navigations and rejects only on an error thrown inside a guard. The app's one global guard
   (`router/index.js:46-104`) redirects via `next({...})`/`next('/path')` — the resolving case — and
   throws nothing. Both call sites also pass the static literal `'/parent/bookings'`, which always
   resolves as a known route, so there is no synchronous-throw path either. **Practical consequence: a
   `booking.requests.submitError`/`booking.batch.submitError` toast on a successful booking is already
   impossible today**, not a live defect this story is fixing.

   **Implement the restructure anyway, as defensive hardening against a future regression** (e.g. someone
   later adds `await` in front of one of these `router.push` calls, which would then reintroduce exactly
   the failure mode originally described) — it is cheap and harmless. Move `router.push('/parent/bookings')`
   to run **after** the `try/catch` block completes successfully — e.g. restructure as `try {
   ...await calls...; navigated = true } catch (err) { ...} finally { if (navigated)
   router.push('/parent/bookings') }`, or equivalently move the `router.push` call to immediately follow
   the `try/catch` statement (outside it) guarded by a local `let succeeded = false` flag set at the end
   of the `try` block, whichever reads more naturally in this file's existing style. **Do not describe
   this in commit messages, PR text, or the AC7 ledger closure note as fixing a reachable bug** — describe
   it as hardening the shape so a future `await` addition can't reintroduce the failure mode.
   `submitBatchRequest()` (`:496-525`) has no `finally` block today (only `submit()` does, at `:491-493`,
   resetting `submitting`); do not add one solely to host the relocated `router.push` unless the chosen
   restructure needs it — the "move outside `try/catch`, guarded by a local flag" shape avoids that.

   **Correction from story creation:** the ledger item this closes (`deferred-work.md:1471`) also cited
   `ParentBookingsPage.vue:208` (`submitReschedule()`) as having the same shape — re-verified against
   current code and that function (`ParentBookingsPage.vue:195-219`) has **no `router.push` call at all**;
   it only closes a dialog (`rescheduleDialogOpen.value = false`) and shows a toast. That citation was
   stale — do not touch `ParentBookingsPage.vue` for this AC; scope is `BookingRequestPage.vue` only.

4. **All three catch blocks `skillars-deferred-28` AC2 wired end in a bare `else` with only the generic
   toast — add a `console.warn` there so an unrecognised `booking.*` error code becomes visible instead of
   silently falling through**, matching this codebase's existing `console.warn` convention (e.g.
   `axios.js:159`, `video.store.js:189`). This is the same symptom-of-silence that let the original
   one-generic-toast bug survive undetected from `skillars-uat-2` (2026-08-10) to `skillars-deferred-28`
   (2026-08-17) — a `console.warn` makes the next such gap self-reporting instead of silent. Add
   `console.warn('[booking] unmapped errorKey:', errorKey, err)` (or equivalent wording matching this
   file's style — note the extra `err` argument, see below) as the first line inside each of these three
   `else` blocks, immediately before the existing `$q.notify(...)` call — do not remove or alter the
   existing notify calls:
   - `BookingRequestPage.vue:488` (`submit()`'s final `else`)
   - `BookingRequestPage.vue:521` (`submitBatchRequest()`'s final `else`)
   - `ParentBookingsPage.vue:213` (`submitReschedule()`'s final `else`)

   **These `else` branches are also the landing zone for every failure that has no
   `response.data.errorMsg` at all** — network failures, 401/403 redirects, 500s, aborted requests (the
   axios response interceptor, `boot/axios.js:112-181`, rejects with the original error in all of those
   cases and never synthesises an `errorMsg`). So `errorKey` will be `undefined` on every one of those,
   and a bare `console.warn('[booking] unmapped errorKey:', errorKey)` would print `undefined` far more
   often than it prints an actual unmapped code — noise that reads exactly like the signal it exists to
   produce. Logging the second `err` argument (as above) lets a reader distinguish "genuinely unmapped
   booking code" from "transport-level failure, `errorKey` is expected to be undefined" at a glance.

5. **A new test proves `SessionPackPurchaseRepository.findActivePacks`'s other 4 `WHERE` predicates
   (`remainingSessions > 0`, `expiresAt > :now`, `pausedUntil IS NULL OR pausedUntil <= :now`,
   `playerId`/`coachId` match) actually filter out non-matching rows, not just that ordering works.**
   `SessionPackPurchaseRepositoryIT.findActivePacks_returnsOldestCreatedAtFirst`
   (`SessionPackPurchaseRepositoryIT.java:34-86`) seeds two rows that both sit at comfortable mid-range
   values on every predicate except `createdAt` (`remainingSessions = 5`, `expiresAt = now + 86400s`,
   `pausedUntil` null on both, same `playerId`/`coachId`) — `assertThat(activePacks).hasSize(2)` would
   still hold if any of those four predicates were deleted from the JPQL
   (`SessionPackPurchaseRepository.java:37-46`). Add a new test method
   `findActivePacks_excludesExhaustedExpiredPausedAndOtherPlayerPacks` to the same
   `SessionPackPurchaseRepositoryIT.java`, reusing the existing `COACH_USER_ID`
   (`9_620_000_001L`)/`PLAYER_ID` (`9_620_000_002L`) constants and the same
   `main."user"`/`CoachProfile`/`SessionPackTier` seeding pattern the existing test already uses.

   **While adding this second test method, also close the open, uncited `deferred-work.md:1456` item
   from the same code-review block this AC's own item comes from**, rather than doubling the duplication
   it describes: `SessionPackPurchaseRepositoryIT` re-implements `BasePaymentIT.insertTestCoach`'s raw-SQL
   `main."user"` seed inline (dropping that helper's `ON CONFLICT (id) DO NOTHING` idempotency guard), and
   this AC's second test method would otherwise be a second verbatim copy of that same ~21-line raw-SQL
   seed in the same class. Extract the existing seed into a private `seedCoachUser()` (or equivalently
   named) helper in `SessionPackPurchaseRepositoryIT` as part of adding the new method, have both test
   methods call it, and annotate `deferred-work.md:1456` `[CLOSED by skillars-deferred-29 AC5]` alongside
   AC7's other closures. This is a same-class, same-file extraction with zero risk beyond this AC's
   existing scope — it is not the larger `BasePaymentIT`-reuse option `:1456` itself floats as a
   possibility, which is out of scope here. Seed:
   - one **active** control row (`remainingSessions = 5`, `expiresAt = now + 1 day`, `pausedUntil = null`,
     same `playerId`/`coachId`) — expected to be returned
   - one **exhausted** row (`remainingSessions = 0`, otherwise identical to the control) — expected to be
     excluded
   - one **expired** row (`expiresAt = now.minusSeconds(3600)`, otherwise identical to the control) —
     expected to be excluded
   - one **currently-paused** row (`pausedUntil = now.plusSeconds(3600)`, otherwise identical to the
     control) — expected to be excluded
   - one row for a **different player** (`playerId = 9_620_000_003L` — claim this new fixture id per Dev
     Notes below; same `coachId`, otherwise identical to the control) — expected to be excluded

   Call `findActivePacks(PLAYER_ID, coach.getId(), Instant.now())` (the same `playerId`/`coachId` the
   control row and the excluded rows other than the different-player one share) and assert the result
   contains **only** the control row's `purchaseId` (`assertThat(activePacks).extracting(
   SessionPackPurchase::getPurchaseId).containsExactly(controlPack.getPurchaseId())`). A `coachId`-mismatch
   row is not required — `coachId` is part of every seeded row's identity already (each test method
   creates its own `CoachProfile`), and adding a second full coach+profile fixture just to cover the
   symmetric `coachId` predicate would be disproportionate to this AC's bundled-fix scope; note this as an
   accepted narrower-than-ideal scope, not an oversight. Do not modify the existing
   `findActivePacks_returnsOldestCreatedAtFirst` test.

6. **`BookingRepositoryIT.updatableFalseColumns_mutationIsIgnoredAfterFlushAndReload` only mutates and
   asserts `parentId`/`playerId`/`coachId` — it cannot distinguish "these three columns are specifically
   immutable" from "the whole entity happens to be immutable" (e.g. an accidental `@Immutable` on
   `Booking`, or `updatable = false` fat-fingered onto a genuinely-updatable column like `status`).** Add
   one more mutation to the same test method (`BookingRepositoryIT.java:118-140`): after asserting
   `parentId`/`playerId`/`coachId` survived unchanged, also mutate `managed.setStatus(...)` to a different
   value — **use `"DECLINED"`, not `"ACCEPTED"`** — `seedExisting()`, `BookingRepositoryIT.java:142-155`,
   seeds `booking.setStatus("REQUESTED")`) **before** the `saveAndFlush` call (i.e. mutate `status`
   alongside `parentId`/`playerId`/`coachId` in the same mutate-then-flush-then-reload sequence, not a
   second separate flush), then after the reload assert `reloaded.get().getStatus()` **did** change to the
   new value — turning this test from a one-sided "these stay the same" check into an actual
   characterization that only the three intended columns are guarded, while a genuinely-updatable column
   still persists a change. **Why `"DECLINED"` and not `"ACCEPTED"`:** this class's own header comment
   (`BookingRepositoryIT.java:16-21`) states "All seeded rows use REQUESTED status, which is outside the
   DB-level `excl_bkg_coach_slot_overlap` exclusion constraint's scope (see V87), so overlapping rows here
   don't trip that constraint." `V87__booking_overlap_exclusion_constraint.sql:20-22` scopes that
   constraint to `status IN ('ACCEPTED','PAYMENT_PENDING','CONFIRMED','UPCOMING','IN_PROGRESS','PAUSED')`
   — mutating into `'ACCEPTED'` would move this method's row into that scope. It is harmless in practice
   (this method seeds exactly one booking and an `EXCLUDE` constraint never conflicts a row with itself),
   but it makes the class comment false for a future author who adds a second overlapping row while
   trusting it. `"DECLINED"` is valid per `V37__session_pack_expiry_pause.sql:26-32`'s `chk_bkg_status`,
   stays outside the exclusion scope, and equally proves `status` is updatable. `Booking.status`
   (`Booking.java:45-46`) has no `updatable = false` and is a plain `@Column`, so this assertion should
   pass without any production change.

7. **Ledger hygiene in `deferred-work.md`.** Annotate every item this story closes (see **Deferred Items
   Closed** table, plus the `:1456` item AC5 also closes as a bundled side-fix) with
   `[CLOSED by skillars-deferred-29 ACn]` at its current ledger location once implemented, following this
   file's established annotation convention (do not delete the original item text — append the closure
   note the same way `skillars-deferred-24` through `-28` did).

## Tasks / Subtasks

- [x] Task 1 — `SluDashboardService` reads via `ClockProvider`; remove inert `.with(DayOfWeek.MONDAY)`;
  pin `SluDashboardServiceTest` to a fixed clock (AC: #1)
  - [x] `SluDashboardService.java:41` — replace `ZonedDateTime.now(ZoneOffset.UTC)` with
    `ZonedDateTime.now(ClockProvider.getClock()).withZoneSameInstant(ZoneOffset.UTC)`; add the
    `ClockProvider` import
  - [x] `SluDashboardService.java:42` — delete `.with(DayOfWeek.MONDAY)` from the `from` computation
  - [x] In `SluDashboardServiceTest.java`'s three `getWeeklyExposure_*` tests: pin
    `TestClockProvider.setClock(Clock.fixed(...))` before computing `now`, compute `now` from
    `TestClockProvider.getClock()` instead of `ZoneOffset.UTC` directly, add `@AfterEach
    TestClockProvider.unsetClock()`
  - [x] `mvn -o test -Dtest=SluDashboardServiceTest` green
  - [x] Spot-check no other test relies on `SluDashboardService`'s prior clock-read behavior: `grep -rn
    SluDashboardService src/test` — `SluDashboardServiceTest` should be the only match

- [x] Task 2 — Fix `DrillDetailPanel.vue`'s video-upload catch block to read `errorMsg.errorKey` (AC: #2)
  - [x] `DrillDetailPanel.vue:383` — `helpCode` → `errorKey`, sourced from
    `e?.response?.data?.errorMsg?.errorKey`
  - [x] `DrillDetailPanel.vue:384` — `errorKey === 'QUOTA_EXCEEDED'` (the `VideoErrorCode` enum name —
    **not** `'video.quotaExceeded'`, which is only the fallback message text; see AC2)
  - [x] `DrillDetailPanel.vue:386` — `errorKey === 'video.constraintViolated'` (value unchanged from the
    original `helpCode` check, only the field moves)
  - [x] Manually verify **both** toasts render: trip `VideoService.java:231`'s rate limit or `:249`'s
    storage quota for the quota toast (the constraint-violation toast already fired pre-fix and is not
    sufficient evidence the quota-branch fix is correct — see AC2) — **verified by Mbah** in a real
    browser after the code-review pass's i18n reword

- [x] Task 3 — Hoist `router.push` out of `try` in `BookingRequestPage.vue`'s `submit()` and
  `submitBatchRequest()`, as defensive hardening (AC: #3 — the described failure mode is not reachable
  today; see AC3 for why)
  - [x] `submit()` (`BookingRequestPage.vue:467-494`) — `router.push` no longer reachable from `catch`
  - [x] `submitBatchRequest()` (`BookingRequestPage.vue:496-525`) — same restructure
  - [x] Confirm `ParentBookingsPage.vue` is untouched by this task (no `router.push` in
    `submitReschedule()`)

- [x] Task 4 — `console.warn` in the three unmapped-`errorKey` `else` branches (AC: #4)
  - [x] `BookingRequestPage.vue:488`
  - [x] `BookingRequestPage.vue:521`
  - [x] `ParentBookingsPage.vue:213`

- [x] Task 5 — Add `findActivePacks_excludesExhaustedExpiredPausedAndOtherPlayerPacks` to
  `SessionPackPurchaseRepositoryIT` (AC: #5)
  - [x] Claim fixture id `9_620_000_003L` for the different-player row in
    `docs/testing/test-data-isolation.md`'s registry (extend the existing
    `SessionPackPurchaseRepositoryIT` row's range)
  - [x] Extract the existing raw-SQL `main."user"` seed into a private `seedCoachUser()` helper; have both
    test methods call it; annotate `deferred-work.md:1456` `[CLOSED by skillars-deferred-29 AC5]`
  - [x] Seed control + 4 excluded rows, call `findActivePacks`, assert only the control row returns
  - [x] `mvn -o verify -Dit.test=SessionPackPurchaseRepositoryIT` green

- [x] Task 6 — Extend `BookingRepositoryIT`'s `updatableFalseColumns_...` test with a genuinely-updatable
  column mutation (AC: #6)
  - [x] Mutate `status` to `"DECLINED"` (not `"ACCEPTED"` — see AC6 for why) alongside the three immutable
    columns in the same flush; assert it changed after reload
  - [x] `mvn -o verify -Dit.test=BookingRepositoryIT` green

- [x] Task 7 — Ledger hygiene (AC: #7)
  - [x] Annotate all 6 closed items per the **Deferred Items Closed** table, plus `:1456` (closed by
    AC5), in `deferred-work.md` with
    `[CLOSED by skillars-deferred-29 ACn]`
  - [x] Update `sprint-status.yaml`'s `skillars-deferred-29-...` entry status as this story progresses
    (`ready-for-dev` → `in-progress` → `review` → `done`), per this repo's established convention

### Review Findings

Code review 2026-08-17. **All three layers ran and returned findings** — Blind Hunter (14), Acceptance
Auditor (8), Edge Case Hunter (8) — no layer failed, unlike `skillars-deferred-28`'s run. 30 raw findings
deduplicated to 15 actionable.

Ten findings were dismissed as verified-false or spec-directed. Notably, the Blind Hunter's two HIGH
findings were both refuted by the two project-aware layers reading real source: (a) its claim that
`errorKey === 'QUOTA_EXCEEDED'` and `errorKey === 'video.constraintViolated'` cannot both be correct
because they use different naming conventions — they are emitted by two *different* advices
(`VideoApiAdvice` passes `VideoErrorCode.QUOTA_EXCEEDED.getErrorCode()` = `this.name()`;
`SessionApiAdvice.java:21` passes the literal `"video.constraintViolated"`), so both values are right;
and (b) its claim that both IT methods calling `seedCoachUser(COACH_USER_ID)` will collide on a PK —
`DatabaseResetTestExecutionListener.beforeTestMethod` (order 3000) truncates every application table
before each method. Also dismissed: `parentId = 1L` in `newPack()` (pre-existing convention, matching the
existing test's `:74,:85`; no FK on `parent_id`); the missing comment on the `.with(DayOfWeek.MONDAY)`
deletion (AC1 explicitly forbade one); `submitting` re-enabling before `router.push` (no microtask
boundary between the `finally` and the guarded push, and `canSubmit` already gates re-entry); unsurfaced
`router.push` rejections (unchanged from pre-diff — it was never awaited); the claim that the new
`status` assertion proves nothing (it is in fact load-bearing — without a dirty *updatable* field
Hibernate emits no UPDATE at all, so the three `updatable = false` assertions were previously vacuous).

- [x] [Review][Decision] `'QUOTA_EXCEEDED'` conflates a transient rate limit with a storage quota, and
  this diff made the wrong message newly reachable — `VideoService.java:231` (upload rate limit) and
  `:249` (storage quota) both throw `QuotaExceededException`, both reach
  `VideoApiAdvice.videoQuotaExceededHandler`, and both emit `errorMsg.errorKey = "QUOTA_EXCEEDED"`. A
  coach who merely clicks upload too fast now sees `en-US/index.js:341`'s *"Storage quota exceeded.
  Upgrade your plan to upload more videos."* — an upsell for a condition that clears itself in under 60
  seconds. This is a **regression introduced by AC2**: pre-fix, `helpCode === 'video.quotaExceeded'` never
  matched, so both paths fell through to the generic "Upload failed" toast, which was vague but not
  actively misleading. Options: (a) accept and log a ledger item; (b) reword the i18n string to cover both
  conditions without promising an upgrade; (c) split the backend into two error codes — a contract change
  the story's scope discipline excludes. [`src/frontend/src/components/session/DrillDetailPanel.vue:384`]
  — **RESOLVED (Mbah): (b) reworded.** Changed all three locale bundles'
  `session.drillLibrary.upload.quotaExceeded` string from "Storage quota exceeded. Upgrade your plan..."
  to a phrasing that covers both conditions without promising an upgrade for a rate-limit hit — EN:
  "Upload limit reached. Try again in a moment, or upgrade your plan for more storage." (FR/DE
  equivalents). Confirmed the key has exactly one call site (`DrillDetailPanel.vue:385`), so no other
  surface is affected. ESLint clean; the 3 files Prettier flags (`en-US/index.js`,
  `DrillDetailPanel.vue`, `ParentBookingsPage.vue`) all carry pre-existing violations confirmed present at
  `HEAD` before this session's changes — not introduced here, same precedent `skillars-deferred-28`
  documented.
- [x] [Review][Decision] AC2's mandatory manual quota/rate-limit browser verification was not performed,
  yet the story sits at `review` with "All 7 ACs closed as scoped" — Task 2's box at story line 353 is the
  only unchecked item in the file, and the Dev Agent Record discloses the gap honestly (no browser tooling
  in session). Two independent layers hand-traced the chain and confirm `'QUOTA_EXCEEDED'` is the correct,
  reachable wire value, so the residual risk is confined to browser-side toast rendering. AC2 and the Dev
  Notes both insist this check is "the only check that will ever run". Options: (a) Mbah spot-checks before
  merge; (b) accept the disclosed gap on the strength of the hand-trace, as `skillars-deferred-28` did.
  — **RESOLVED (Mbah): (a) Mbah spot-checked manually.** Both toasts confirmed rendering correctly in a
  real browser, including the reworded quota/rate-limit copy. Task 2's manual-verification checkbox (story
  line 353) now checked.
- [x] [Review][Patch] `pausedUntil <= :now` disjunct is never exercised, so half the pause predicate stays
  mutation-survivable — the only paused row is `now + 3600s`, excluded by *both* halves of the OR. Delete
  `OR p.pausedUntil <= :now` from the JPQL and the new test still passes, which is precisely what its own
  comment claims it prevents. This matters more than a normal coverage gap: `pausedUntil` is never
  cleared (`PackSessionService.java:171` only ever sets it forward; no `setPausedUntil(null)` exists in
  `src/main/java`), so that disjunct is the *sole* mechanism by which an elapsed pause makes a pack
  spendable again. Add a row with `pausedUntil` in the past, expected to be **returned**, and widen the
  `containsExactly` accordingly. [`src/test/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepositoryIT.java:152-156`]
  — resolved: added `elapsedPausePack` (`pausedUntil = now - 3600s`), widened the assertion to
  `containsExactlyInAnyOrder(controlPack, elapsedPausePack)`. Re-run: 2/2 green.
- [x] [Review][Patch] `seedCoachUser()` still lacks the `ON CONFLICT (id) DO NOTHING` guard that ledger
  item `deferred-work.md:1456` was closed for — that item's headline is literally "dropping that helper's
  `ON CONFLICT (id) DO NOTHING` idempotency guard", and `BasePaymentIT.java:42,59,78` confirms the guard
  exists there. AC5's closure note discloses only that the larger `BasePaymentIT`-reuse option was
  skipped; it is silent on the guard, so the item is now marked closed with its stated defect intact. The
  seed is harmless today only because of an undocumented dependency on
  `DatabaseResetTestExecutionListener` truncating tables — exactly the coupling the guard existed to
  remove. Add the clause. [`src/test/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepositoryIT.java:33-43`]
  — resolved: added `ON CONFLICT (id) DO NOTHING` to `seedCoachUser()`'s INSERT.
- [x] [Review][Patch] The IT seeds against one `Instant.now()` but queries with a second, later one, and
  no row sits at or adjacent to the `expiresAt > :now` boundary — `expiredPack` is 3600s below it and
  `controlPack` 86400s above it, so relaxing `>` to `>=` (the off-by-one that lets a pack be spent on the
  exact second it expires) leaves the test green. Pass the seeded `now` into `findActivePacks` instead of
  a fresh `Instant.now()`, which both removes the structural drift and makes an exact-boundary row
  possible. [`src/test/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepositoryIT.java:146-150,165`]
  — resolved: `findActivePacks` now called with the seeded `now`; added `exactlyExpiredPack`
  (`expiresAt = now` exactly, expected excluded) to actually exercise the boundary. Re-run: 2/2 green.
- [x] [Review][Patch] `SluDashboardServiceTest` omits the `.withZoneSameInstant(ZoneOffset.UTC)` the
  service applies, so AC1's stated "test mirrors the service formula exactly" property is inexact — the
  two `now` values coincide only because the pinned clock happens to carry `ZoneOffset.UTC`. Pinning a
  non-UTC `Clock.fixed(...)` in a future edit silently desynchronises them again, reintroducing the exact
  class of flake AC1 exists to eliminate. Spec-inherited (AC1 prescribed this literal form), not a dev
  deviation. [`src/test/java/com/softropic/skillars/platform/development/service/SluDashboardServiceTest.java:57,84,118`]
  — resolved: added `.withZoneSameInstant(ZoneOffset.UTC)` to all three tests' `now` computation. Re-run:
  6/6 green.
- [x] [Review][Patch] AC6's mutation-verification exercised the pre-existing assertions, not the one AC6
  adds — the Debug Log records removing `updatable = false` from `Booking.parentId`, which fails the *old*
  `getParentId()` assertion and says nothing about the new `getStatus()` one. AC6 offered "or fat-finger it
  onto `status`" as an alternative, so the letter is satisfied, but the variant that would prove the new
  assertion has teeth was not run. Re-run with `updatable = false` temporarily added to `Booking.status`
  and confirm the new assertion fails. [`src/test/java/com/softropic/skillars/platform/booking/repo/BookingRepositoryIT.java:146`]
  — resolved: re-ran with `updatable = false` added to `Booking.status`; failed as expected
  (`expected: "DECLINED"` at `BookingRepositoryIT.java:146`, 1 failure). Reverted; re-ran clean, 9/9 green.
- [x] [Review][Patch] Import-order regression in `SluDashboardServiceTest` — `TestClockProvider` was
  appended *after* the `platform.*` imports (so `infrastructure` now sorts after `platform`) and the blank
  line separating `com.softropic.*` from `org.junit.*` was deleted. The production-side change in the same
  AC did it correctly, and `BookingRepositoryIT:3-5` / `SessionPackPurchaseRepositoryIT:3-7` both keep the
  separator. Same patch: `newPack()` uses fully-qualified `java.util.UUID` twice in its signature while the
  class imports `java.util.List` normally. [`SluDashboardServiceTest.java:7-11`,
  `SessionPackPurchaseRepositoryIT.java:172`]
  — resolved: moved `TestClockProvider` import before `platform.*` imports, restored the blank-line
  separator; added a `java.util.UUID` import to `SessionPackPurchaseRepositoryIT` and changed `newPack()`'s
  signature to use the imported `UUID` type.
- [x] [Review][Defer] `submit()`'s new `console.warn` else-branch is the live landing zone for four
  unmapped, user-actionable pack-selection rejections [`src/frontend/src/pages/parent/BookingRequestPage.vue:489-492`]
  — deferred, pre-existing
- [x] [Review][Defer] `submitReschedule()`'s else-branch swallows `MISSING_RIGHTS`, which covers four
  distinct conditions including the common "a pending reschedule request already exists"
  [`src/frontend/src/pages/parent/ParentBookingsPage.vue:213-216`] — deferred, pre-existing
- [x] [Review][Defer] `DrillDetailPanel`'s else-branch shows "Upload failed. Please try again." for two
  deterministically non-retryable rejections (`security.featureGated`, `DRILL_UPLOAD_NOT_ALLOWED`)
  [`src/frontend/src/components/session/DrillDetailPanel.vue:388-390`] — deferred, pre-existing
- [x] [Review][Defer] `findActivePacks`' `p.coachId = :coachId` predicate remains unproven — every seeded
  row shares `coach.getId()` [`src/test/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepositoryIT.java:158-162`]
  — deferred, AC5 explicitly accepted this as narrower-than-ideal scope
- [x] [Review][Defer] `console.warn(..., err)` fires on every transport failure (logging `undefined`) and
  during 401 session-expiry redirects, and serializes `err.config.data` — including the free-text `notes`
  field and `playerId` — into the browser console [`BookingRequestPage.vue:490,526`,
  `ParentBookingsPage.vue:214`] — deferred, AC4 prescribed the `err` argument deliberately and reasoned
  about the `undefined` noise; no auth-token leak (cookie-based auth, confirmed against `boot/axios.js`)
- [x] [Review][Defer] `docs/testing/test-data-isolation.md` contradicts itself about the block AC5 touched
  — `:206` claims `9620000001`–`9620000003` for `SessionPackPurchaseRepositoryIT` while `:217-219`'s
  claimed-prefix list still omits `9620` and `:220` still advertises `9620`–`9690` as a free block
  [`docs/testing/test-data-isolation.md:206,217-220`] — deferred, pre-existing (introduced by
  `skillars-deferred-27`)
- [x] [Review][Defer] The three `getWeeklyExposure_*` tests compute their expected `fromYear`/`fromWeek`
  with the identical expression the service uses and were edited in lockstep with it, so they cannot
  detect a change to that arithmetic (e.g. `minusWeeks(weeksBack)`); relatedly, all three pin the same
  mid-August instant, covering no ISO week-based-year rollover — the one case a fixed clock exists to
  reach [`src/test/java/com/softropic/skillars/platform/development/service/SluDashboardServiceTest.java:56-60,84-88,117-121`]
  — deferred, pre-existing test design that AC1 explicitly directed be preserved

## Dev Notes

- **Scope discipline.** Six small, independently-safe items across development, session/video, booking
  (frontend), and payment/booking (test coverage). Do not use this as a pretext to "clean up while you're
  in there" — e.g. don't extend AC4's `console.warn` fix into the coach-side accept flows (explicitly
  excluded, see "Why this story exists"), don't add a `coachId`-mismatch fixture to AC5 beyond what's
  asked, don't touch `ConfigService.getBoolean` or the `lock.timeout` gap even though they're referenced
  in this same ledger region. If something adjacent looks wrong, note it as a new `deferred-work.md` item;
  don't fix it here.

- **AC1 is the only item touching production logic in a way that changes a currently-executing time
  computation** (a behaviour-preserving refactor, not a behaviour change) — verify the
  `withZoneSameInstant(ZoneOffset.UTC)` substitution is correct at implementation time:
  `ZonedDateTime.now(ClockProvider.getClock())` uses `ClockProvider.getClock()`'s zone (which defaults to
  `Clock.systemDefaultZone()`'s zone, i.e. the JVM's default zone, not UTC), so the result must be
  explicitly converted to the UTC zone before `IsoFields.WEEK_BASED_YEAR`/`WEEK_OF_WEEK_BASED_YEAR` are
  read from it — `withZoneSameInstant` does that without changing the underlying instant. **Correction:**
  this is not a pattern copied from an existing call site — `StripePaymentGateway.java:84` (`Instant.now(
  ClockProvider.getClock()).getEpochSecond()`) reads an `Instant`, which is zone-independent, so it
  demonstrates no zone handling at all. AC1 introduces the `withZoneSameInstant` zone-conversion pattern
  to this codebase's `ClockProvider` usage, it does not follow an existing one — the substitution is still
  correct (verified above), there is just no precedent to go looking for.

- **AC1's test fix is the actual payoff — it removes a real, previously-undiagnosed source of CI flake**
  introduced by `skillars-deferred-27` AC4's `eq()` tightening (deferred at that story's own code review,
  2026-08-17, as "the fix is a one-line production change beyond that story's promised footprint"). This
  story is that follow-up. Do not skip pinning the clock in the test even though the production fix alone
  would also (mostly) work — the whole point is eliminating the test's own dependency on real wall-clock
  timing, not just narrowing the race window.

- **AC2, AC3, AC4 are all instances of the same underlying bug class `skillars-deferred-28` AC2 already
  fixed once** (branching on the wrong/absent response field, or letting a `try` block catch an unrelated
  post-success failure) — this story applies the same fix pattern to sibling files `deferred-28` didn't
  touch. Match `BookingRequestPage.vue`'s/`ParentBookingsPage.vue`'s existing `errorMsg?.errorKey` idiom
  exactly; do not introduce a new error-extraction helper or refactor the pattern into a shared utility —
  that would be scope creep beyond a bundled small-fix story.

- **There is no frontend test infrastructure in this repo** — no `vitest.config.*`, no `*.spec.js`, no
  `*.test.js` exist anywhere under `src/frontend` (excluding `node_modules`). AC2/AC3/AC4 therefore ship
  with zero automated coverage; the manual-verification steps in Tasks 2–4 are the only check that will
  ever run. This is precisely why AC2's `helpCode` bug went unnoticed from its original introduction to
  this story's re-verification — do the manual verification steps for real, especially AC2's quota/
  rate-limit path (not just the constraint-violation path, which already passed pre-fix).

- **AC3 is defensive hardening, not a bug fix — the described failure mode is unreachable today**
  (see AC3's text: `router.push` is never awaited, and vue-router 4.6.4 resolves rather than rejects on
  the cited guard-redirect trigger anyway). Implement it regardless — it's cheap and protects against a
  future `await` addition reintroducing the failure mode — but do not write the commit message, PR
  description, or the AC7 ledger closure note as if it fixes a live user-visible defect.

- **AC3's exact restructuring approach (try/finally with a flag vs. moving the call after the
  try/catch) is left to whichever reads most naturally in `BookingRequestPage.vue`'s existing style** —
  the hard requirement is only that `router.push` cannot execute inside the `catch` block's triggering
  scope. Note `submitBatchRequest()` has no existing `finally` block (only `submit()` does); prefer the
  "move `router.push` after the `try/catch`, guarded by a local flag" shape over adding a `finally` to
  `submitBatchRequest()` purely to host the relocated call. Re-read the current file at implementation
  time — line numbers above are from this story's creation (2026-08-17) and may drift if other changes
  land first.

- **AC5's `coachId`-mismatch predicate is deliberately left unverified** — see AC5's text for the
  reasoning (a second full coach fixture is disproportionate to this AC's scope). If a future story
  revisits `findActivePacks`, that's the one remaining boundary gap.

- **AC6 mutation-verify before marking the story `review`** — temporarily remove `updatable = false` from
  one of the three immutable columns (or fat-finger it onto `status`) and confirm the extended test
  actually fails, the same discipline `skillars-deferred-27`'s AC2 review demanded. Revert immediately
  after confirming.

- **File paths this story touches:**
  - `src/main/java/com/softropic/skillars/platform/development/service/SluDashboardService.java` (AC1)
  - `src/test/java/com/softropic/skillars/platform/development/service/SluDashboardServiceTest.java` (AC1)
  - `src/frontend/src/components/session/DrillDetailPanel.vue` (AC2)
  - `src/frontend/src/pages/parent/BookingRequestPage.vue` (AC3, AC4)
  - `src/frontend/src/pages/parent/ParentBookingsPage.vue` (AC4)
  - `src/test/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepositoryIT.java` (AC5)
  - `docs/testing/test-data-isolation.md` (AC5, fixture id registry only)
  - `src/test/java/com/softropic/skillars/platform/booking/repo/BookingRepositoryIT.java` (AC6)
  - `_bmad-output/implementation-artifacts/deferred-work.md` (AC7)
  - `_bmad-output/implementation-artifacts/sprint-status.yaml` (AC7, status line only)

### Project Structure Notes

- AC1 changes one production line plus a same-file dead-code removal, and pins an existing test suite's
  clock; AC2 is a one-field frontend bugfix; AC3 is a frontend control-flow reorder (no new logic); AC4
  adds three one-line `console.warn` calls; AC5 and AC6 are pure test additions with zero production-code
  risk (AC6 adds an assertion to an existing test method rather than a new one). No new production
  classes, no new migrations, no changes to any already-applied Flyway migration.
- Follows the same flat, non-epic-nested tracking convention every other `skillars-deferred-N` story uses
  in `sprint-status.yaml` (the "DEFERRED WORK" block).

### References

- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — "## Deferred from: code review of
  skillars-deferred-27-repository-ordering-updatable-guard-and-test-coverage-fixes (2026-08-17)" (AC1,
  AC5, AC6, at `deferred-work.md:1457`, `:1453`, `:1455`); "## Deferred from: skillars-deferred-28-...
  story creation (2026-08-17)" (AC2, at `:1462`); "## Deferred from: code review of skillars-deferred-28-...
  (2026-08-17)" (AC3, AC4, at `:1471`, `:1472`)
- [Source: src/main/java/com/softropic/skillars/platform/development/service/SluDashboardService.java:39-49]
  — confirms AC1's current `ZonedDateTime.now(ZoneOffset.UTC)` read and the inert `.with(DayOfWeek.MONDAY)`
- [Source: src/main/java/com/softropic/skillars/infrastructure/util/ClockProvider.java] — confirms AC1's
  target API (`getClock()`, `Clock.systemDefaultZone()` fallback)
- [Source: src/test/java/com/softropic/skillars/infrastructure/util/TestClockProvider.java] — confirms
  AC1's test-side `setClock`/`unsetClock` API
- [Source: src/test/java/com/softropic/skillars/platform/payment/service/StripePaymentGatewayTest.java:49-51,91]
  — confirms the exact `TestClockProvider.setClock(Clock.fixed(...))`/`@AfterEach unsetClock()` pattern
  AC1's test fix should follow
- [Source: src/test/java/com/softropic/skillars/platform/development/service/SluDashboardServiceTest.java:50-123]
  — confirms AC1's three affected test methods' current wall-clock-reading structure
- [Source: src/frontend/src/components/session/DrillDetailPanel.vue:370-393] — confirms AC2's current
  `helpCode`-based branching and the surrounding try/catch/finally shape
- [Source: src/main/java/com/softropic/skillars/infrastructure/message/ErrorDto.java,
  src/main/java/com/softropic/skillars/infrastructure/message/ErrorMsg.java] — confirm AC2's wire shape:
  `{helpCode, errorMsg: {errorKey, message}, fieldErrors}`
- [Source: src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java:73-78,150-160] —
  confirms AC2's `video.quotaExceeded` throw site and the `helpCode`≠`errorKey` distinction
- [Source: src/main/java/com/softropic/skillars/platform/session/api/SessionApiAdvice.java:18-23] —
  confirms AC2's `video.constraintViolated` throw site
- [Source: src/frontend/src/i18n/en-US/index.js:341-342] — confirms AC2's existing i18n keys are already
  present and unaffected by this fix
- [Source: src/frontend/src/pages/parent/BookingRequestPage.vue:467-525] — confirms AC3's/AC4's current
  `submit()`/`submitBatchRequest()` structure and exact line numbers
- [Source: src/frontend/src/pages/parent/ParentBookingsPage.vue:195-219] — confirms AC4's
  `submitReschedule()` structure, and confirms (correcting the ledger) it has no `router.push` call
- [Source: src/frontend/src/boot/axios.js:159] — confirms AC4's existing `console.warn` convention in this
  codebase
- [Source: src/test/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepositoryIT.java]
  — confirms AC5's existing test's fixture/seeding pattern and the single-happy-path scope gap
- [Source: src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java:37-46]
  — confirms AC5's `findActivePacks` query and its 5 `WHERE`/`ORDER BY` clauses
- [Source: src/test/java/com/softropic/skillars/platform/payment/service/PackSessionServiceParityTest.java]
  — confirms `findActivePacks` has no other real-database coverage of these predicates (fully mocked)
- [Source: docs/testing/test-data-isolation.md:206,219-220] — confirms AC5's fixture id registry entry and
  free-block convention (`9620`–`9690` is free; `9620000001`–`002` already claimed by this class)
- [Source: src/test/java/com/softropic/skillars/platform/booking/repo/BookingRepositoryIT.java:118-155] —
  confirms AC6's existing test method and `seedExisting()`'s `status = "REQUESTED"` seed value
- [Source: src/main/java/com/softropic/skillars/platform/booking/repo/Booking.java:31-46] — confirms AC6's
  three `updatable = false` columns and that `status` carries no such guard

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5

### Debug Log References

- No implementation blockers encountered — all 6 items matched the story's pre-verified throw
  sites/line numbers/entity shapes on the first implementation pass.
- AC1: `SluDashboardServiceTest` pinned to `Clock.fixed(Instant.parse("2026-08-19T10:00:00Z"), ZoneOffset.UTC)`
  (a Wednesday, confirmed by manual ISO-weekday calculation from 2026-01-01 = Thursday, chosen to sit far
  from any Monday-midnight boundary). 6/6 green on first run.
- AC5: `SessionPackPurchaseRepositoryIT` initially attempted with `-DskipUTs=true -Dsurefire.skip=true`,
  which this project's POM does not honor (both flags no-op here) — the unit suite ran anyway alongside
  the IT. Not a blocker, just extra runtime; switched to a plain `mvn -o verify -Dit.test=...` for
  subsequent single-class runs. 2/2 green (`findActivePacks_returnsOldestCreatedAtFirst` unaffected by the
  `seedCoachUser()` extraction, `findActivePacks_excludesExhaustedExpiredPausedAndOtherPlayerPacks` new).
- AC6: mutation-verified per the story's Dev Notes — temporarily removed `updatable = false` from
  `Booking.parentId`, confirmed `updatableFalseColumns_mutationIsIgnoredAfterFlushAndReload` failed
  (1 failure, as expected), then reverted and confirmed all 9 `BookingRepositoryIT` tests pass again.
- AC2's manual browser verification (tripping the quota/rate-limit path to confirm the toast renders) was
  **not performed** — this session has no browser-driving tooling (no `chromium-cli`, no project-specific
  `run` skill for this app) and setting one up (real coach account, near-quota state, a running frontend +
  backend + Bunny.net webhook stand-in) was out of proportion to this bundled-fix story. Hand-traced
  instead: `VideoErrorCode.QUOTA_EXCEEDED.getErrorCode()` returns `this.name()` = the literal string
  `"QUOTA_EXCEEDED"`, `VideoApiAdvice.java:75`'s handler passes that as the third (`msgKey`/wire-`errorKey`)
  argument to `logErrorAndReturnDTO`, confirming the new `errorKey === 'QUOTA_EXCEEDED'` check matches the
  real wire value. Matches this project's established convention for UI changes with no test runner and no
  browser tooling in-session (see `skillars-deferred-28`'s Debug Log References for the same disclosed
  limitation on its AC2). Flagging for a human spot-check per the story's own Dev Notes, which state this
  is "the only check that will ever run."
- Full `mvn -o verify` regression suite run before marking the story `review`: exit code 0. 887 unit tests
  + 916 integration tests, 0 failures, 0 errors, 5 skipped combined. `npm run lint` (ESLint) clean on all
  touched frontend files.

### Completion Notes List

- All 7 ACs closed as scoped. AC1 is the only item touching production time-computation logic
  (behaviour-preserving); AC2 is a one-field frontend bugfix; AC3 is defensive hardening with no live bug
  (per the story's own senior-dev-review correction); AC4 adds three `console.warn` calls; AC5 and AC6 are
  pure test additions with zero production-code risk; AC7 is ledger hygiene.
- AC1: `SluDashboardService.java:41` now reads `ZonedDateTime.now(ClockProvider.getClock())
  .withZoneSameInstant(ZoneOffset.UTC)`; deleted the inert `.with(DayOfWeek.MONDAY)` from the `from`
  computation and the now-unused `DayOfWeek` import. Mirrored the adjuster removal in all three
  `SluDashboardServiceTest.getWeeklyExposure_*` tests and pinned each to `TestClockProvider.setClock(...)`
  with `@AfterEach TestClockProvider.unsetClock()`, following `StripePaymentGatewayTest`'s exact pattern.
  Confirmed `SluDashboardServiceTest` is the only test file referencing `SluDashboardService`.
- AC2: `DrillDetailPanel.vue`'s video-upload catch block now reads `e?.response?.data?.errorMsg?.errorKey`
  instead of `helpCode`. Quota branch corrected to `errorKey === 'QUOTA_EXCEEDED'` (the `VideoErrorCode`
  enum name, not the `'video.quotaExceeded'` fallback message text); constraint-violation branch keeps its
  existing value, only the field moves. i18n keys unchanged at initial implementation; the `quotaExceeded`
  string itself was later reworded in the code-review response (see Post-review pass below).
  **Manual browser verification completed by Mbah after the review pass — both toasts confirmed
  rendering correctly.**
- AC3: `BookingRequestPage.vue`'s `submit()` and `submitBatchRequest()` both restructured with a local
  `succeeded` flag — `router.push('/parent/bookings')` now runs after the `try/catch` block instead of
  inside the `try`. `submitBatchRequest()` deliberately did not gain a `finally` block, per the story's own
  guidance to prefer the "move the call after `try/catch`" shape. Confirmed `ParentBookingsPage.vue` has no
  `router.push` call — untouched by this AC, matching the story's own correction of the stale ledger
  citation.
- AC4: added `console.warn('[booking] unmapped errorKey:', errorKey, err)` as the first line inside all
  three unmapped-`errorKey` `else` blocks (`BookingRequestPage.vue`'s `submit()`/`submitBatchRequest()`,
  `ParentBookingsPage.vue`'s `submitReschedule()`), immediately before the existing `$q.notify(...)` calls.
- AC5: extracted the raw-SQL `main."user"` seed into a private `seedCoachUser(long coachUserId)` helper in
  `SessionPackPurchaseRepositoryIT`; both test methods now call it. Added
  `findActivePacks_excludesExhaustedExpiredPausedAndOtherPlayerPacks`, seeding one control row plus one
  negative row each for exhausted (`remainingSessions = 0`), expired (`expiresAt` in the past), paused
  (`pausedUntil` in the future), and a different player (`OTHER_PLAYER_ID = 9_620_000_003L`), asserting
  `findActivePacks` returns only the control row. Claimed the new fixture id in
  `docs/testing/test-data-isolation.md`. `coachId`-mismatch predicate deliberately left unverified, per the
  AC's own accepted narrower-than-ideal scope. 2/2 green.
- AC6: added a `status` mutation (`"DECLINED"`, not `"ACCEPTED"` — see AC6's V87 exclusion-constraint
  reasoning) to `BookingRepositoryIT.updatableFalseColumns_mutationIsIgnoredAfterFlushAndReload`, alongside
  the three immutable-column mutations in the same flush, asserting `status` *did* change after reload.
  Mutation-verified per the story's Dev Notes (see Debug Log References). 9/9 green.
- AC7: annotated all 6 ledger items in the **Deferred Items Closed** table plus the bundled `:1456`
  side-fix with `[CLOSED by skillars-deferred-29 ACn]` and a prose description of the actual change, in
  `deferred-work.md`, matching every prior `skillars-deferred-N` closure's convention (8 annotations total
  — AC1 and AC5 each close two ledger items). `sprint-status.yaml` updated `ready-for-dev` → `in-progress`
  at start of dev; → `review` at story completion (this update). Story file's own `Status:` field updated
  the same way.
- Full `mvn -o verify` regression suite run before marking the story `review`: exit code 0, 887 unit + 916
  integration tests, 0 failures, 0 errors, 5 skipped combined. `npm run lint` clean.
- **Resolved limitation:** AC2's required manual browser verification of the quota/rate-limit toast could
  not be performed in this session — no browser-driving tooling was available (confirmed via the `run`
  skill: no project-specific run skill for this app, `chromium-cli` not installed). Flagged explicitly for
  Mbah to spot-check, per the story's own Dev Notes ("do the manual verification steps for real, especially
  AC2's quota/rate-limit path"). **Mbah confirmed the spot-check afterward: both toasts render correctly,
  including the reworded quota/rate-limit copy.** Task 2's manual-verification checkbox now checked; this
  was the last incomplete item in the story.
- **Post-review pass (2026-08-17):** code review appended a "Review Findings" section with 2 Decision + 6
  Patch items (plus 7 already-resolved Defer items the code-review skill logged directly into
  `deferred-work.md`, requiring no action here). Resolved all 8: the 6 Patches were mechanical
  (`SessionPackPurchaseRepositoryIT`: added `elapsedPausePack` to exercise the `pausedUntil <= :now`
  disjunct, `ON CONFLICT (id) DO NOTHING` on `seedCoachUser()`, passed the seeded `now` into
  `findActivePacks` plus an exact-boundary `exactlyExpiredPack` row, fixed `newPack()`'s fully-qualified
  `UUID`; `SluDashboardServiceTest`: added the missing `.withZoneSameInstant(ZoneOffset.UTC)` to all three
  tests, fixed the `TestClockProvider` import order; `BookingRepositoryIT`: re-ran the AC6
  mutation-verification against `Booking.status` specifically — confirmed it fails as required
  (`expected: "DECLINED"`), then reverted). The 2 Decisions were put to Mbah: (1) the `'QUOTA_EXCEEDED'`
  message-conflation regression — Mbah chose reword-the-string; reworded all three locale bundles'
  `quotaExceeded` key to not promise an upgrade for a transient rate limit, and annotated the
  corresponding `deferred-work.md` item `[MITIGATED by skillars-deferred-29 code review response]` (the
  underlying wire-code conflation itself stays open — that still needs a backend contract change). (2)
  AC2's still-outstanding manual browser verification — Mbah spot-checked personally and confirmed both
  toasts render correctly, including the reworded quota/rate-limit copy; Task 2's manual-verification
  checkbox now checked. `SessionPackPurchaseRepositoryIT`
  re-verified 2/2, `SluDashboardServiceTest` re-verified 6/6, `BookingRepositoryIT` re-verified 9/9. `npm
  run lint` clean on all touched frontend files after the i18n reword; the 3 files `npx prettier --check`
  flags (`en-US/index.js`, `DrillDetailPanel.vue`, `ParentBookingsPage.vue`) all carry pre-existing
  violations confirmed present at `HEAD` before this session started — not introduced by this story. Full
  `mvn -o verify` re-run after all patches: exit code 0, 887 unit + 916 integration tests, 0 failures, 0
  errors, 5 skipped combined — no regressions from the review-response changes.

### File List

- `src/main/java/com/softropic/skillars/platform/development/service/SluDashboardService.java` (AC1)
- `src/main/java/com/softropic/skillars/platform/booking/repo/Booking.java` (AC6 review — mutation-verify
  only, no net change)
- `src/test/java/com/softropic/skillars/platform/development/service/SluDashboardServiceTest.java` (AC1)
- `src/frontend/src/components/session/DrillDetailPanel.vue` (AC2)
- `src/frontend/src/i18n/en-US/index.js` (AC2 review — quotaExceeded reword)
- `src/frontend/src/i18n/fr-FR/index.js` (AC2 review — quotaExceeded reword)
- `src/frontend/src/i18n/de-DE/index.js` (AC2 review — quotaExceeded reword)
- `src/frontend/src/pages/parent/BookingRequestPage.vue` (AC3, AC4)
- `src/frontend/src/pages/parent/ParentBookingsPage.vue` (AC4)
- `src/test/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepositoryIT.java` (AC5)
- `docs/testing/test-data-isolation.md` (AC5, fixture id registry only)
- `src/test/java/com/softropic/skillars/platform/booking/repo/BookingRepositoryIT.java` (AC6)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC7)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (AC7, status line only)
