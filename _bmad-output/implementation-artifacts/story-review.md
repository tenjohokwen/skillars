# Senior-Dev Review — Story `skillars-deferred-29-clockprovider-error-mapping-and-repository-boundary-test-coverage-fixes`

Reviewer: senior dev (adversarial audit for missed corner cases, false assumptions, missed flows)
Date: 2026-08-17
Story reviewed: `_bmad-output/implementation-artifacts/skillars-deferred-29-clockprovider-error-mapping-and-repository-boundary-test-coverage-fixes.md` (status `ready-for-dev`)
Verified against: working tree at branch `master`, HEAD `5ea940f`

> **Supersedes** the previous contents of this file (the senior-dev review of
> `skillars-deferred-28`, now merged). Nothing in this document refers to that story.

Every finding below was confirmed by reading current source. Line numbers were re-derived at review
time, not taken from the story's own citations. A "Verified correct" section at the end lists the
claims I checked and found sound, so the blocker list can be read as complete rather than partial.

---

## Verdict

**Rework AC2 and AC3 before handoff. AC1, AC4, AC5, AC6 and AC7 ship with the edits noted below.**

The story is unusually well-researched — the ledger citations all resolve, the AC3 correction of the
ledger's stale `ParentBookingsPage.vue:208` citation is right, the fixture-id claim is right, and the
AC1 clock analysis is right down to the Monday-00:00-UTC mechanism. Three things do not survive
verification:

- **AC2's prescribed fix does not fix the quota branch.** The real `errorMsg.errorKey` is
  `"QUOTA_EXCEEDED"`, not `"video.quotaExceeded"`. The AC converts a never-firing branch into a
  still-never-firing branch (B1).
- **AC2's second premise is backwards.** The `constraintViolated` branch *does* fire today, because
  `SessionApiAdvice` puts the literal string in `helpCode`. "Its two specific error branches have
  never fired in production" is false for one of the two (B2).
- **AC3 fixes a defect that cannot occur.** `router.push` is not awaited, so a rejection never
  reaches the enclosing `catch` at all; and in vue-router 4.6.4 the AC's own example (a guard
  redirect) *resolves*, it does not reject (B3).

---

## Blockers

### B1 — AC2's replacement value is wrong; the quota toast still never fires

AC2 says:

> Both throw sites exist and are reachable: `VideoApiAdvice.java:75`'s `QuotaExceededException`
> handler sets `errorKey = "video.quotaExceeded"`

It does not. Trace the actual value:

| Step | Code | Result |
|---|---|---|
| `VideoApiAdvice.java:75` | `logErrorAndReturnDTO(ex, "video.quotaExceeded", VideoErrorCode.QUOTA_EXCEEDED.getErrorCode())` | — |
| `VideoApiAdvice.java:149` | `private ErrorDto logErrorAndReturnDTO(Throwable throwable, String defaultMsg, String msgKey)` | `defaultMsg = "video.quotaExceeded"`, `msgKey = QUOTA_EXCEEDED.getErrorCode()` |
| `VideoErrorCode.java:18` | `public String getErrorCode() { return this.name(); }` | `msgKey = "QUOTA_EXCEEDED"` |
| `VideoApiAdvice.java:160` | `return new ErrorDto(helpCode, new ErrorMsg(msgKey, message))` | `errorMsg.errorKey = "QUOTA_EXCEEDED"` |

The third argument is the **message key**, not the default message — the parameter order reads
`(throwable, defaultMsg, msgKey)` but the call site's readable string is in the *middle* slot. So
`"video.quotaExceeded"` is only ever the `messageSource` fallback text; it is never the wire
`errorKey`.

The path is confirmed reachable and confirmed to be this handler: `DrillUploadService.initiateUpload`
(`DrillUploadService.java:50-100`) calls `videoService.initializeUpload(...)`, which throws
`platform.video.contract.exception.QuotaExceededException` at `VideoService.java:231` (rate limit) and
`:249` (storage quota). `VideoApiAdvice` is `@Order(HIGHEST_PRECEDENCE)` and is the only advice
handling that type — `ApiAdvice.java:514`'s same-named handler imports the *filestorage*
`QuotaExceededException` (`ApiAdvice.java:13`), a different class, and `ApiAdvice` carries no
`@Order` anyway (lowest precedence).

**Consequence:** implementing AC2 exactly as written leaves the quota toast dead, the ledger item at
`deferred-work.md:1462` gets annotated `[CLOSED by skillars-deferred-29 AC2]`, and the bug is now
harder to find because the obvious wrong thing (`helpCode`) has been corrected.

**Required change:** AC2 must specify `errorKey === 'QUOTA_EXCEEDED'` for that branch, and state
explicitly that the value is the enum name because `VideoApiAdvice` passes
`VideoErrorCode.*.getErrorCode()` as `msgKey`. Alternatively, decide to namespace the backend's
`msgKey` — but that is a contract change affecting all ten `VideoApiAdvice` handlers and is out of
this story's bundled-fix bar, so it should be a new ledger item, not a silent widening of AC2.

### B2 — AC2's "never fired in production" claim is false for `video.constraintViolated`

AC2 (and the ledger item at `deferred-work.md:1462`) asserts:

> `helpCode` is `ApiAdvice`/`VideoApiAdvice`'s per-request SQIDS-encoded support id … never a value
> like `'video.quotaExceeded'`

That is true of `VideoApiAdvice` (`ApplicationException.getSupportId()` is a per-instance
`SQIDS.encode(UUID.randomUUID().hashCode())` — `ApplicationException.java:22,43`). It is **not** true
of the other advice on this path. `SessionApiAdvice.java:21`:

```java
ErrorDto dto = new ErrorDto("video.constraintViolated", new ErrorMsg("video.constraintViolated", ex.getMessage()));
```

`ErrorDto`'s constructor is `(helpCode, errorMsg)`. So for `DrillConstraintViolationException` —
thrown at `DrillUploadService.java:68` when `videoTypeConstraints.validate(...)` rejects the file —
`helpCode` **is** literally `"video.constraintViolated"`, and the existing
`helpCode === 'video.constraintViolated'` branch fires correctly today.

**Consequence:** two ways this misleads the dev. First, the story's framing ("its two specific error
branches have never fired in production", "the exact bug class `skillars-deferred-28` AC2 fixed") is
half-wrong and will be carried verbatim into the ledger closure note. Second, and more practically,
AC2's Task 2 verification step — "manually verify … that `video.quotaExceeded`/`video.constraintViolated`
toasts render for their respective errorKeys" — will *pass* on the constraint case whether or not the
fix is correct, and a dev who verifies only the easy one (upload an oversized file: no rate limiter to
trip, no quota to fill) will sign off on B1 unnoticed.

**Required change:** correct AC2's rationale to "one branch (`quotaExceeded`) never fires because
`VideoApiAdvice`'s `helpCode` is a support id; the other (`constraintViolated`) happens to work
because `SessionApiAdvice` puts the key in `helpCode` — the change to `errorMsg.errorKey` unifies both
onto the documented field." And state that manual verification **must** exercise the quota/rate-limit
path, not just the constraint path.

### B3 — AC3's failure mode is unreachable; the AC as written verifies nothing

AC3 states that a rejected `router.push` "lands in the `catch` at line 480" and produces a false
`booking.requests.submitError` toast, and that `submitBatchRequest()` "can show both the success toast
and a failure toast for the same submission."

Neither can happen, for two independent reasons.

**1. The call is not awaited.** `BookingRequestPage.vue:479` and `:507` are bare
`router.push('/parent/bookings')` — no `await`. A `try`/`catch` only intercepts synchronous throws and
awaited rejections. An un-awaited rejected promise escapes the block entirely and surfaces as an
unhandled rejection; it never reaches `catch (err)`. (Both enclosing functions *are* `async`, which is
what makes this easy to misread — but the `await` is on the `bookingStore` call, not on the push.)

**2. The cited trigger does not reject anyway.** `vue-router` is `4.6.4` (`package-lock.json`). In
Vue Router 4, `router.push()` returns `Promise<NavigationFailure | void>`; aborted, cancelled,
duplicated and **redirected** navigations *resolve* with a `NavigationFailure` object. Only an error
thrown inside a guard rejects. The app's only global guard (`router/index.js:46-104`) redirects via
`next({...})` / `next('/path')` — the resolving case — and throws nothing.

I looked for a synchronous throw path as well: `router.push` can throw synchronously only on a
resolution failure (e.g. an unknown named route). Both call sites pass the static string literal
`'/parent/bookings'`, which resolves.

**Consequence:** AC3's hard requirement ("a navigation failure must never produce a
`booking.requests.submitError`/`booking.batch.submitError` toast") is already satisfied by the current
code. Implementing it is harmless, but the story asserts a user-visible defect that does not exist,
and AC7 will annotate `deferred-work.md:1471` as closed — cementing the same wrong mechanism in the
ledger. There is also a live risk that a dev writes a regression test asserting the pre-fix behaviour
and cannot make it fail.

**Required change:** either (a) drop AC3 and rewrite the ledger item at `:1471` to record that the
`try`-scoped `router.push` is *not* a defect because it is un-awaited and Vue Router 4 resolves on
redirect; or (b) keep the restructure explicitly as defensive hardening ("no known reachable failure
today; the shape is fragile if someone later adds `await`"), with the ledger note phrased accordingly.
Option (b) is defensible and cheap — but it must not be described as a bug fix.

---

## Should fix before handoff

### M1 — AC1 orphans the `java.time.DayOfWeek` import and doesn't say to remove it

`SluDashboardService.java:42` is the file's only use of `DayOfWeek`; deleting `.with(DayOfWeek.MONDAY)`
leaves the `import java.time.DayOfWeek;` at line 17 unused. AC1 remembers to add the `ClockProvider`
import but says nothing about removing this one, and Task 1's two bullets don't either.

Not build-breaking — `pom.xml` configures no checkstyle, PMD or spotbugs, and no `-Werror` — so this
is hygiene, not a failure. But `skillars-deferred-27` was partly a *formatting hygiene* story, and
leaving a dead import behind in its follow-up is the kind of thing its next code review will file as a
new ledger item. Add the bullet.

### M2 — AC1 leaves the test's `.with(DayOfWeek.MONDAY)` unaddressed, breaking the mirror it was built for

`SluDashboardServiceTest.java:54, 88, 111` each compute
`from = now.minusWeeks(8 - 1).with(DayOfWeek.MONDAY)` — deliberately mirroring the production formula,
which is the stated point of `skillars-deferred-27` AC4's `eq()` tightening. AC1 removes the adjuster
from production and is silent about the test.

The adjuster is inert either way, so *both* choices are correct; the problem is that the story doesn't
make one, so the dev will pick arbitrarily and the "test mirrors the service formula" property either
quietly breaks or quietly survives with no record of the decision. Say explicitly: remove it from the
test too (recommended — it keeps the mirror exact), or keep it and note why.

### M3 — AC4's `console.warn` will mostly log `undefined`, not an unmapped booking code

AC4's stated purpose is "an unrecognised `booking.*` error code becomes visible instead of silently
falling through." But the `else` branches at `BookingRequestPage.vue:488`, `:521` and
`ParentBookingsPage.vue:213` are also the landing zone for every failure that has no
`response.data.errorMsg` at all: network failures, 401/403 redirects, 500s, and aborted requests. The
axios interceptor (`boot/axios.js:112-181`) rejects with the original error in all of those cases and
does not synthesise an `errorMsg`.

So `console.warn('[booking] unmapped errorKey:', errorKey)` will print `undefined` on every transport
failure, which is both noise and — worse — reads exactly like the signal it was added to produce. A
future engineer grepping for "unmapped errorKey" will find mostly non-events.

Recommend `console.warn('[booking] unmapped errorKey:', errorKey, err)` so the two cases are
distinguishable at a glance, and one sentence in AC4 noting `undefined` is the expected value for
transport-level failures. No `no-console` rule exists in `eslint.config.js:55-60`, so the call itself
is fine, and `console.warn` is an established convention (`boot/axios.js:159`).

### M4 — AC5 doubles a duplication that is an *open, uncited* ledger item

`deferred-work.md:1456` is an open item in the same review block AC5's own item comes from:

> `SessionPackPurchaseRepositoryIT` re-implements `BasePaymentIT.insertTestCoach`'s raw-SQL
> `main."user"` seed inline, dropping that helper's `ON CONFLICT (id) DO NOTHING` idempotency guard.

AC5 instructs the dev to add a second test method "reusing … the same `main."user"`/`CoachProfile`/
`SessionPackTier` seeding pattern the existing test already uses" — i.e. a second verbatim copy of the
21-line raw-SQL seed inside the same class.

The story's exclusion list claims "every [other ledger item] inspected during this story's creation
either needed a product/design decision, targeted an unreachable/already-closed code path, or
duplicated a fix a prior story already made." `:1456` fits none of those three and appears neither in
the **Deferred Items Closed** table nor in the **Explicitly NOT in this story** list. It is the only
open item I found that the story neither closes nor consciously rejects.

Cheapest resolution, fully in scope and zero risk: have AC5 extract the seed into a private
`seedCoachUser()` / `seedCoach()` helper in `SessionPackPurchaseRepositoryIT` as part of adding the
second method, and close `:1456` under AC5 too. Otherwise, add `:1456` to the explicit exclusions with
a reason.

---

## Minor

**m1 — AC6's choice of `'ACCEPTED'` silently invalidates a documented class invariant.**
`BookingRepositoryIT.java:16-21`'s class comment states: *"All seeded rows use REQUESTED status, which
is outside the DB-level `excl_bkg_coach_slot_overlap` exclusion constraint's scope (see V87), so
overlapping rows here don't trip that constraint."* `V87__booking_overlap_exclusion_constraint.sql:20-22`
scopes that constraint to `status IN ('ACCEPTED','PAYMENT_PENDING','CONFIRMED','UPCOMING','IN_PROGRESS','PAUSED')`.
AC6 moves the row into that scope. It is harmless *today* — the method holds exactly one booking (DB is
truncated before every test method, see below) and an `EXCLUDE` constraint never conflicts a row with
itself — but the comment becomes false, and the next author who adds an overlapping row while trusting
it gets a confusing constraint violation. Use `'DECLINED'` instead (valid per
`V37__session_pack_expiry_pause.sql:26-32`'s `chk_bkg_status`, outside the exclusion scope, and equally
proves `status` is updatable), or update the comment in the same commit.

**m2 — Dev Notes cite a precedent that isn't one.** AC1's Dev Note says the
`withZoneSameInstant(ZoneOffset.UTC)` conversion "mirrors how other `ClockProvider` call sites in this
codebase (e.g. `StripePaymentGateway.java:84`) already handle zone conversion." `StripePaymentGateway.java:84`
is `Instant.now(ClockProvider.getClock()).getEpochSecond()` — `Instant` is zone-independent, so that
line demonstrates no zone handling at all. AC1 is *introducing* the pattern, not following it. The
substitution is still correct (see Verified correct, below); only the cited evidence is wrong. Delete
or replace the citation so a dev doesn't go looking for a pattern to copy.

**m3 — Task 1's spot-check command isn't runnable as written.** `mvn -o verify -Dit.test=none -Dtest=*`
would attempt the whole suite, not a spot-check. The bullet's parenthetical (`grep SluDashboardService`
under `src/test`) is the actual check and is sufficient — I ran it: `SluDashboardServiceTest` is the
only test class referencing the service. Drop the command.

**m4 — AC3's suggested `try/finally`-with-flag shape assumes a `finally` both functions have.**
`submit()` has one (`:491-493`, resetting `submitting`); `submitBatchRequest()` (`:496-524`) has none.
If AC3 survives B3, say so, or the dev will add a `finally` to one function purely to host a
`router.push`.

**m5 — Line-number drift (cosmetic).** `submitBatchRequest()` is `:496-524`, not `:496-525`.
`deferred-work.md` is 1472 lines, not 1473. `Booking.status` is `Booking.java:45-46` ✓.

**m6 — There is no frontend test infrastructure, so AC2/AC3/AC4 ship with zero automated coverage.**
No `vitest.config.*`, no `*.spec.js` and no `*.test.js` exist anywhere under `src/frontend` (excluding
`node_modules`). AC2's Task-2 bullet — "add a component test if this file already has one" — is dead
text. This is worth stating plainly in Dev Notes rather than leaving implied, because it is precisely
why the `helpCode` bug (B1/B2) survived from the original write to now, and because B1 means the one
thing that *would* have caught it is a manual test against a real 429.

---

## Verified correct — no action needed

Listed so the blocker set above can be read as exhaustive rather than sampled.

**AC1 (production change).** Behaviour-preserving, confirmed empirically rather than assumed. No
production code anywhere calls `ClockProvider.setClock` or `setClockWithZoneId` — the only match in
`src/main/java` is `ClockProvider`'s own declaration — so `getClock()` always returns
`Clock.systemDefaultZone()` in production, and `withZoneSameInstant(ZoneOffset.UTC)` preserves the
instant, making the result identical to today's `ZonedDateTime.now(ZoneOffset.UTC)`. `ZoneOffset` stays
in use, so only the `DayOfWeek` import is orphaned (M1). The "17 production files" figure is right (18
files match `ClockProvider`, one of which is the class itself).

**AC1 (`.with(DayOfWeek.MONDAY)` is inert).** Correct. `DayOfWeek.adjustInto` sets `DAY_OF_WEEK` within
the same ISO week, and `IsoFields.WEEK_BASED_YEAR` / `WEEK_OF_WEEK_BASED_YEAR` are constant across a
week. `from` (`SluDashboardService.java:42`) feeds nothing but those two reads at `:43-44`. Under a
fixed `ZoneOffset.UTC` there is no DST edge to worry about either.

**AC1 (the flake diagnosis).** Correct and correctly bounded to Monday 00:00:00 UTC: all four `eq()`
matchers derive only from ISO week fields, which change only at that boundary. `MockitoExtension`'s
default strictness is `STRICT_STUBS`, which does throw `PotentialStubbingProblem` on an argument
mismatch against an existing stub. The three affected tests are exactly the three named
(`SluDashboardServiceTest.java:51, 75, 108`); the three `getNarrativeSummary_*` tests never reach
`getWeeklyExposure`.

**AC1 (the test-side API).** `TestClockProvider` exists at
`src/test/java/com/softropic/skillars/infrastructure/util/TestClockProvider.java`, in the same package
as `ClockProvider`, which is what lets it reach the package-private `setClock`. It exposes
`setClock`/`getClock`/`unsetClock` as public statics. `StripePaymentGatewayTest.java:49-51,91` is the
pattern claimed. `2026-08-19` is a Wednesday. Pinning with `ZoneOffset.UTC` matters and the AC says so
— a non-UTC fixed clock would put the test's `now` in a different zone from the service's converted
`now` and could straddle a date boundary.

**AC3's ledger correction.** Right, and worth keeping even if AC3 is dropped.
`ParentBookingsPage.vue`'s `submitReschedule()` (`:195-219`) has no `router.push`; the only
`router.push` calls in `src/frontend/src/pages/parent/` are `BookingRequestPage.vue:464,479,507` and
`ParentDevelopmentPortalPage.vue:189`.

**AC4's targets and convention.** The three `else` branches are at `BookingRequestPage.vue:488`,
`:521` and `ParentBookingsPage.vue:213`, each immediately preceding the generic `$q.notify`. All three
already read `err?.response?.data?.errorMsg?.errorKey`, so the variable AC4 wants to log is in scope.
The axios response interceptor unwraps success bodies but rejects errors with the original axios error
(`boot/axios.js:180`), so `err.response.data` is intact at these call sites.

**AC5's premise.** Verified by inspection of `SessionPackPurchaseRepository.java:36-46` against
`SessionPackPurchaseRepositoryIT.java:34-99`: deleting any one of `remainingSessions > 0`,
`expiresAt > :now`, the `pausedUntil` clause, `playerId = :playerId` or `coachId = :coachId` leaves
`assertThat(activePacks).hasSize(2)` passing, because the database holds only those two rows and both
sit mid-range on every predicate.

**AC5's fixture-id claim, and the reuse hazard I went looking for.**
`docs/testing/test-data-isolation.md:206` claims `9620000001`–`9620000002` for this class and lists
`9620`–`9690` as a free block, so extending to `9620000003` is correct. I specifically checked whether
a *second* test method re-inserting `main."user"` id `9_620_000_001` would collide with the first
method's row: it will not. `DatabaseResetTestExecutionListener.beforeTestMethod` (registered on
`AbstractIntegrationTest`) deletes from every application table **before every test method**, so each
method starts clean. AC5's "reuse the existing constants" instruction is safe.

**AC5's row list is complete enough.** `SessionPackPurchase.@PrePersist onCreate()` defaults
`createdAt` when null, and `version` is a real `@Version` field, so the five seeded rows need only the
fields the AC enumerates plus the `parentId`/`packTierId`/`pricePerSession` the existing test's pattern
already supplies. Ordering does not matter to the AC's `containsExactly(control)` assertion.

**AC5's deliberate `coachId` gap.** Reasonable and correctly labelled as accepted scope rather than
oversight.

**AC6.** `'ACCEPTED'` satisfies `chk_bkg_status` (`V37__session_pack_expiry_pause.sql:26-32`).
`Booking.status` (`Booking.java:45-46`) is a plain `@Column(nullable = false, length = 30)` with no
`updatable = false`, so the new assertion passes without production change, as the AC predicts. The
existing test's reasoning about the reload being a genuine round-trip (no ambient transaction, OSIV
off, no L2 cache) holds. The `@AfterEach` cleanup keys off the test's own `coachId` field, which the
`setCoachId(UUID.randomUUID())` mutation cannot corrupt precisely because the column is
`updatable = false`. Only the status *choice* is worth changing (m1). The AC's mutation-verify
instruction in Dev Notes is the right discipline and should be kept.

**AC7.** Every ledger citation resolves at the stated line: `:1453` (AC5), `:1454` (AC1 Monday),
`:1455` (AC6), `:1457` (AC1 clock), `:1462` (AC2), `:1471` (AC3), `:1472` (AC4). The exclusions the
story lists are all real, still-open, and genuinely blocked on a decision or a contract change — I
spot-checked `:1410`, `:1424`, `:1434`, `:1444`, `:1461`, `:1463`, `:1464`, `:1470`.
`sprint-status.yaml:1039` exists at `ready-for-dev` with the AC summary block above it.
Caveat: the closure notes for AC2 and AC3 must be reworded per B1/B2/B3 before they are written, or
the ledger will preserve two false mechanisms.

---

## Adjacent observation (not a story defect — file as a new ledger item, do not fix here)

`DrillUploadService.initiateUpload` wraps only `videoTypeConstraints.validate(...)` in its
`VideoValidationException` → `DrillConstraintViolationException` translation
(`DrillUploadService.java:65-69`). `videoService.initializeUpload(...)`, called nine lines later, can
throw `VideoValidationException` on its own; that one reaches `VideoApiAdvice.java:67` and arrives as
`errorKey = "VALIDATION_FAILED"`, landing on the generic `uploadFailed` toast. Consistent with the
story's scope discipline — mention it in `deferred-work.md`, don't widen AC2.
