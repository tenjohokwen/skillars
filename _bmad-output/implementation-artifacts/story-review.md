# Senior-dev audit — `skillars-deferred-90`

**Target:** `_bmad-output/implementation-artifacts/skillars-deferred-90-session-and-integrity-bug-fixes-rolling-deploy-migration-safety-convention-i18n-locale-sweep-and-n-plus-1-query-batching.md`
**Reviewed:** 2026-09-02 · against `story/deferred-90-bugs-migration-i18n-n1` working tree
**Method:** every claim in every AC was checked against current source. Findings below carry file:line evidence. Claims I could not substantiate were dropped rather than reported.

**Verdict: do not start Task 1 as written.** Four findings (F1–F4) mean the AC would either ship a regression or fix the wrong thing. Six ACs prescribe work that is already done. Three real defects inside the story's own stated goal are not covered by any AC.

---

## Summary table

| # | AC | Severity | Finding |
| --- | --- | --- | --- |
| F1 | AC1 | **Blocker** | Wrong root cause; prescribed fix silently kills the booking double-booking 409 contract |
| F2 | AC3 | **Blocker** | `tick()` runs on every anonymous response — the new check logs out the public site |
| F3 | AC2↔AC3 | **Blocker** | The two ACs' fixes contradict each other on the `user` cookie |
| F4 | AC8 | **Blocker** | Root-cause hypothesis is wrong; real leak is in a different class |
| F5 | AC8 | Stale | Status asserts already present in both tests |
| F6 | AC13 | Stale | `getParentBookings` already fully batched; no effective-credit lookup exists |
| F7 | AC11 | Stale | Frontend key parity is already exact (1021/1021, both locales) |
| F8 | AC11 | Stale | `formatSlot` already uses `locale.value` |
| F9 | AC14 | Stale | `commissionRate` parse is already guarded |
| F10 | AC12 | **Missed scope** | Backend `messages_de.properties` is 43 keys short of English |
| F11 | AC12 | **Missed scope** | Backend `messages_fr.properties` is 19 keys short + 25 foreign keys |
| F12 | AC13 | **Missed scope** | 4th per-row query in `getConversations` PARENT branch blocks the AC's own test |
| F13 | AC13 | **Missed scope** | Second identical S3 loop in `erase()` left in place |
| F14 | AC13 | Correctness | Option (a) as specified weakens the GDPR erasure guarantee |
| F15 | AC11 | Trap | Literal sweep breaks 4 deliberate machine-format call sites |
| F16 | AC11 | Trap | Real sweep is ~15 sites, not the 2 named |
| F17 | AC6 | Trap | `Currency.getInstance` throws a type the existing catch does not catch |
| F18 | AC7 | Trap | Prescribed escape class omits the `sed` address delimiter |
| F19 | AC10 | Trap | Lint fixtures under `classpath:db/migration` will be executed by Flyway |
| F20 | AC10 | Gap | `CREATE INDEX CONCURRENTLY` rule has no failure-recovery procedure |
| F21 | AC9 | Contradiction | The 409 endpoint test contradicts its own prescribed fixture and cannot be driven sequentially |
| F22 | AC5 | Design | Security alert on every tokenless request = audit-trail write amplification |
| F23 | AC12 | Weak gate | The only objective acceptance gate is satisfiable without doing the work |
| F24 | AC2 | Severity | Backend half is defense-in-depth, not a live emitter |
| F25 | AC5 | Stale | Code 401 interceptor already gates on both keys |

---

## Blockers

### F1 — AC1: the stated root cause is wrong, and the prescribed fix would silently break the booking double-booking contract

**AC1 claims:** "a `23502` (NOT NULL) or `23514` (CHECK) violation from e.g. `phone_otp_tokens` yields `constraintName == null`".

**That is false.** Decompiled `PostgreSQLDialect` from the exact resolved artifact (`hibernate-core-6.6.53.Final`, via Spring Boot 3.5.16):

```
lambda$static$0(SQLException):
   1: invokestatic  JdbcExceptionHelper.extractSqlState
  10: invokestatic  java/lang/Integer.parseInt
  13: lookupswitch  { 23001: 120, 23502: 106, 23503: 92, 23505: 78, 23514: 64, default: 122 }
  64: ldc "violates check constraint \""
  78: ldc "violates unique constraint \""
  92: ldc "violates foreign key constraint \""
 106: ldc "null value in column \""  /  "\" violates not-null constraint"
```

`23502` and `23514` are both templated and **do** return a name.

**The real null trigger is `23P01` (`exclusion_violation`)** — and this codebase has exactly one exclusion constraint:

- `src/main/resources/db/migration/V87__booking_overlap_exclusion_constraint.sql:15` — `ADD CONSTRAINT excl_bkg_coach_slot_overlap EXCLUDE USING gist (...)`
- `Integer.parseInt("23P01")` throws `NumberFormatException`, which `TemplatedViolatedConstraintNameExtractor.extractConstraintName` catches and converts to `null` (verified in bytecode: `Exception table: 0–50 → 51, Class java/lang/NumberFormatException; 51: astore_2; 52: aconst_null; 53: areturn`).

**Consequence today:** the two `excl_bkg_coach_slot_overlap` entries at `ApiAdvice.java:142` (`CONSTRAINT_MAPPINGS`) and `:155` (`CONFLICT_CONSTRAINTS`) are **dead code** — they can never match, because the name arrives as `null` and the immutable-collection lookup NPEs first. This is independently corroborated by the codebase's own test javadoc:

> `RescheduleResourceIT.java:412-416` — *"a collision was caught only by the V87 exclusion constraint at commit — **an unmapped 500** rather than the clean `booking.slotUnavailable` every other accept path returns."*

Every passing `booking.slotUnavailable` assertion in the suite comes from the **app-layer** `OperationNotAllowedException(BookingError.SLOT_UNAVAILABLE)` (`BookingServiceConcurrencyIT.java:169-171, 232`), never from `ApiAdvice`.

**Why the AC's fix is a regression:** AC1 says *"Null → fall through to the existing `generic.dataError` / `HttpStatus.BAD_REQUEST` branch"*. That converts the V87 backstop breach from a loud 500 into a quiet **400 `generic.dataError`** — permanently retiring the DB-level double-booking guard's intended 409 `booking.slotUnavailable` response, on the one write path (`BookingBatchService`, `RescheduleService`) that has no app-layer check.

**Required change to AC1:**
1. Guard `null` before the two immutable lookups (correct, keep).
2. **Also** recover the exclusion-constraint name before falling through — either `extractUsingTemplate("violates exclusion constraint \"", "\"", …)` on the `SQLException` message, or map SQLSTATE `23P01` directly to `booking.slotUnavailable` + 409.
3. The test must cover **both**: a genuinely-unmappable null → clean 400, *and* a V87 exclusion violation → 409 `booking.slotUnavailable`.

**Also correct the AC's exposure claim.** "Newly reachable unauthenticated via the two `permitAll()` `/resend-otp` endpoints" is wrong: `uq_pot_one_active_per_user` is a **partial unique index** (`V121__phone_otp_tokens_one_active_per_user.sql:30-32`) → SQLSTATE `23505` → name extracted normally → no NPE. The `/resend-otp` path is not an ingress-exposed crash.

---

### F2 — AC3: `tick()` fires on every anonymous API response; the prescribed check would force-logout the public site

AC3 prescribes: *"in `sessionManager.js`, `tick()` re-checks session-cookie presence each interval; when the cookies are gone, dispatch `session:expired`"*.

`tick()` is not only an interval callback:

- `boot/axios.js:126` — response **success** interceptor calls `refreshExpiryState()` → `tick()`
- `boot/axios.js:142` — response **error** interceptor calls `refreshExpiryState()` → `tick()`
- `sessionManager.js:100` — `refreshExpiryState()` is literally `tick()`

Both run on **every** request through `api`, authenticated or not — the login POST itself, registration, OTP resend, password reset, marketplace browsing.

And the listener is registered unconditionally, *before* the auth gate:

```js
// App.vue:46-51
window.addEventListener('session:expired', handleSessionExpired);
if (isAuthenticated()) {
  startSessionMonitoring();
}
```

So on any anonymous request, the new check finds no session cookie, dispatches `session:expired`, and `handleSessionExpired` (`App.vue:26-38`) runs `router.push({ path: '/login', query: { redirect, expired: 'true' } })`. **The public marketplace and the login page itself would bounce to `/login?expired=true` on their first API call.**

**Required:** the missing-cookie branch must be gated on monitoring actually being active (e.g. `checkIntervalId !== null`) **and** the "has `rint` ever been observed" flag, not just cookie absence.

**Second defect in the same AC:** the "has `rint` been seen this session?" flag is module-level state in `sessionManager.js`. It is lost on page reload. After tab A logs out and tab B is reloaded, the flag is `false` again, `rint` is absent, and the missing-cookie branch cannot distinguish "legacy backend" from "dead session" — the original bug returns. If the flag is the discriminator, it needs `sessionStorage` (per-tab, survives reload, dies with the tab) or an equivalent durable store.

**Third, smaller:** the AC's premise — "once `rint` is gone, `computeTimeUntilExpiry()` falls to the purely local estimate" — is narrower than the actual code. `sessionManager.js:88-89`:

```js
const remaining = expiresAt - Date.now();
if (remaining <= 0 && localEstimate > 0) return localEstimate;
```

The legacy estimate also wins while `rint` is **present but expired** — and `rint` is written with `maxAge = JWT_TTL + 60s` (`JwtManagerImpl.java:241-245`), so there is a 60-second window where the AC's cookie-presence check would see the cookie and take the wrong branch anyway.

---

### F3 — AC2 and AC3 prescribe mutually incompatible fixes to the same cookie

- **AC2:** *"Guard: **skip writing the cookie** (or write a sentinel) when `displayName` is null/blank"* (`JwtManagerImpl.createLoginCookies`, `:217-220`).
- **AC3:** *"`tick()` re-checks session-cookie presence each interval (**reuse the AC2-hardened cookie check**); when the cookies are gone … run the expiry path"*.

Both key on the same `user` cookie. If AC2 takes the "skip" option, then for any user with a blank display name: `App.vue:49`'s `isAuthenticated()` returns `false` (no monitoring ever starts), and AC3's new check reads a live, valid session as dead and forces a logout.

**Required:** AC2 must commit to the **sentinel** option, not "skip", and say so explicitly. Or AC3 must key its liveness check on something other than the `user` cookie — but `skp` and `rint` are both worse choices here (see F2's third point, and note `App.vue:29`'s `handleSessionExpired` clears only `user`, while `boot/axios.js:48-51`'s `deleteUserCookie` clears `user` + `skp` and never `rint`).

---

### F4 — AC8: the root-cause hypothesis is wrong, and fixing only `LoginAttemptsServiceTest` leaves the actual leak live

AC8 says: *"test-order-dependent state pollution from a shared cache/clock singleton … Fix: identify the shared singleton (likely a `Clock`/`ConcurrentHashMap`-backed rate-limit or attempts cache)."*

There is no shared cache. `LoginAttemptsService` builds all four Guava caches **per instance, in the constructor** (`LoginAttemptsService.java:87-94`), and the test constructs a fresh instance in `@BeforeEach` (`LoginAttemptsServiceTest.java:35-37`) with an explicit `Ticker`.

**The actual shared state is `RequestMetadataProvider.CONTEXT_HOLDER`, a `ThreadLocal<RequestMetadata>` (`RequestMetadataProvider.java:22`), and the leak is in a different class:**

```java
// LoginInfoServiceIT.java:57-64  @BeforeEach
TestRequestMetadataProvider.setApiKey(CLIENT_ID);
TestRequestMetadataProvider.setSessionId(SESSION_ID);
TestRequestMetadataProvider.setRequestId(REQUEST_ID);

// LoginInfoServiceIT.java:66-73  @AfterEach
// TestClockProvider + DELETE FROM main.sec only — the ThreadLocal is never cleared
```

`LoginAttemptsServiceTest` resets only three fields (`LoginAttemptsServiceTest.java:45-49`: `userName`, `browserCookie`, `ipAddress`) — **not `apiKey`**. And the cache key prefers `apiKey`:

```java
// LoginAttemptsService.java:241-243
private String getClientIdUserKey(final RequestMetadata metadata) {
    return escapeField(StringUtils.defaultString(metadata.getClientIdentifier())) + KEY_SEPARATOR + …
}
// RequestMetadata.java:138-144
public String getClientIdentifier() { return StringUtils.isNotBlank(apiKey) ? apiKey : browserClientCookie(); }
```

Both classes live in `platform/security/service/`, so a wildcard module-bundle run puts them in the same Surefire fork on the same thread. The stale `apiKey` displaces `defaultClient` in every client-keyed cache key — which breaks the client- and IP-keyed assertions while the pure user-keyed ones still pass. That shape matches the reported "12/14".

**Required:**
1. `RequestMetadataProvider.cleanup()` in `LoginInfoServiceIT.tearDown()` (and audit other `TestRequestMetadataProvider.set*` callers for the same omission — `JwtManagerImplTest` already does this correctly at `:142, :158, :891, :905`).
2. Defensively, `RequestMetadataProvider.cleanup()` at the top of `LoginAttemptsServiceTest`'s reset so it is immune to *any* upstream leak, not just this one.

Fixing only (2) makes `LoginAttemptsServiceTest` green while leaving `LoginInfoServiceIT` free to poison the next class that lands after it.

---

## Stale premises — ACs prescribing work that is already done

Each of these would produce a no-op change and a false "fixed" entry in `deferred-work.md`. They should be reclassified as **confirm-and-close-as-stale**, which the ledger's own delete-outright convention handles cleanly.

### F5 — AC8 bullet 2 is already done

Both tests already assert the creation status before reading the body:

- `BookingRequestResourceIT.java:555` — `assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);` in `acceptBooking_wrongCoach_returns403`
- `BookingRequestResourceIT.java:586` — same, in `declineBooking_wrongCoach_returns403`

### F6 — AC13 bullet 1 is already done, and half of it never existed

`BookingService.getParentBookings` (`:484-525`) already batches **everything**:

| Lookup | Line | Mechanism |
| --- | --- | --- |
| coach names | `:488` | `coachProfileRepository.findAllById(coachIds)` |
| **player names** | `:492` → `:939-942` | `resolvePlayerNames` → `playerProfileRepository.findAllById` |
| pending reschedules | `:497` | `findPendingByBookingIdIn(bookingIds)` |
| batch sizes | `:510` | `countByBatchIdIn(batchIds)` |

The "per-row effective-credit lookup" does not exist — `grep -n "effectiveCredit\|creditsRemaining" BookingService.java` returns nothing, and `:522` passes `null` for that `toResponse` parameter.

### F7 — AC11's stub-keys bullet is entirely stale

Deep recursive key diff of the three frontend bundles (script run against `src/frontend/src/i18n/*/index.js`):

```
de-DE: total=1021  en=1021  missing=0  extra=0
fr-FR: total=1021  en=1021  missing=0  extra=0
```

There is no `portal` gap in `de-DE`, and `auth` / `profile` / `session` are present and fully populated in all three (`en-US:2/557/313`, `de-DE:31/1211/654`, `fr-FR:49/575/321`). Untranslated values are also effectively nil — 13 in de-DE and 26 in fr-FR are byte-identical to English, and on inspection every one is a legitimate cognate or loanword (`Position`, `Status`, `Date`, `Menu`, `Score (1–100)`, `Elastico / Flip-Flap`, `Commission`).

This also collapses most of AC12's fr-FR half: the parity audit finds nothing to fill.

### F8 — AC11's `formatSlot` bullet is stale

`BookingRequestPage.vue:346-351`'s `formatSlot` delegates to `formatInZone` (`:325-344`), which already uses `locale.value` on both the primary and the RangeError-fallback path. The ledger item (`skillars-deferred-18` D3, "`formatSlot` hardcodes `'en'`") no longer describes this code.

### F9 — AC14 residual 5 is not an open defect

`StripePaymentGateway.java:43-51`:

```java
try {
    commissionRate = new BigDecimal(configService.getString("platform.commission.rate"));
    currency       = configService.getString("platform.payment.currency");
} catch (IllegalStateException | NumberFormatException e) {
    log.error("Payment configuration unavailable: error={}", e.getMessage());
    throw new PaymentGatewayException("payment.configurationUnavailable", e);
}
```

The `new BigDecimal(...)` parse is already guarded. Recording it as a carried-forward residual is misleading — close it as stale instead.

### F25 — AC5's interceptor bullet is already satisfied in code

`boot/axios.js:151` already reads:

```js
if (errorKey === 'security.sessionExpired' || errorKey === 'security.unauthorized') {
```

Only the *reference / documentation* interceptors may lag. Scope the AC bullet to those.

---

## Missed scope — real defects inside the story's own goal that no AC covers

The story's goal sentence promises *"DE/FR users stop seeing English on core paths"*. AC12 delivers a frontend-only content pass plus exactly **two** new backend German keys. The backend bundles are where the actual English leakage lives.

### F10 — `messages_de.properties` is 43 keys short of `messages_en.properties`

Key-set diff of `src/main/resources/i18n/`:

```
messages.properties     81 keys
messages_en.properties 125 keys
messages_de.properties  82 keys   ← 43 en-keys absent
messages_fr.properties 131 keys
```

The 43 missing German keys are not obscure — they are the user's first and most-repeated touchpoints:

- **Account activation email**: `email.activation.callToAction`, `.greeting`, `.preheader`, `.text2`
- **OTP email**: `email.otp.title`, `.intro`, `.expiry`, `.ignore`
- **All five booking notification titles**: `email.booking.{confirmed,declined,expired,reminder,requested}.title`
- **All nine profile-change notices**: `email.profile_change.{2fa_disabled,2fa_enabled,address,email,generic,not_you,password,phone,title}`
- **Duplicate-registration email**: 10 × `email.creation_dup.*`
- **Password reset**: `email.pw_reset.callToAction`, `.text2`
- **Storage errors**: `storage.{objectNotFound,providerError,quotaExceeded,uploadNotConfirmed,validationFailed}`
- **Phone validation**: `validation.phone.{digitCount,firstDigit,operator}`

Only **29 of the 43** have a fallback entry in the base `messages.properties`. The other **14** have no German and no base entry, so resolution falls through to `ReloadableResourceBundleMessageSource`'s `fallbackToSystemLocale` (default `true`, not overridden — `MvcConfig.java:26-33`) and lands on whatever the server JVM's default locale bundle is. That is environment-dependent behaviour on user-facing error text.

**Recommendation:** either add a backend-bundle parity AC, or amend AC12 to cover `messages_de.properties` and record an explicit, honest carve-out. Silently shipping "the i18n bucket, maximal" while a German user gets an English activation email is the gap most likely to come back as a UAT defect.

### F11 — `messages_fr.properties` is 19 keys short, plus 25 foreign keys

The story's premise that fr-FR is already at parity holds only for the **frontend** bundle.

**Missing from `messages_fr.properties` (present in `_en`):**
`security.otpResendInProgress`, `security.accountLocked`, `security.emailTokenExpired`, `security.emailTokenInvalid`, `security.emailTokenUsed`, all five `email.booking.*.title`, four `email.coach.otp.*`, five `email.coach.verify.*`.

Note `security.otpResendInProgress` — that is the very key `skillars-deferred-89` AC7 shipped and AC9 is about to write a test for. French users get the English default on it.

**Present only in `messages_fr.properties`, absent from every other bundle (25 keys):**
`brand.name`, `brand.tagline`, `email.download.ticket`, `email.tickets.access`, `email.tickets.count`, `email.purchase.date`, `email.next.steps.{1,2,3,4,title}`, `email.why.choose.us`, `email.visit.website`, … — copy from a different product (ticketing). Dead weight that should be deleted, and evidence the French bundle was never audited against English.

### F12 — AC13's `getConversations` bullet misses a 4th per-row query that will fail its own acceptance test

AC13 names *"the per-conversation `findLastApproved` + `countUnread` + `resolveOtherPartyName` tri3"*. The PARENT branch has a fourth, fired **before** `toSummary` even runs:

```java
// MessagingService.java:107-119
// N+1 note: getMessagingPolicy is called once here to filter, then again in resolveOtherPartyName —
// 2 lookups per surviving conversation. Acceptable for MVP; reduce in a later optimisation story.
conversations = all.stream()
    .filter(c -> agePolicyService.findMessagingPolicy(c.getPlayerId())   // ← per row
        .map(policy -> AgeMessagingPolicy.from(policy).parentHasAccess())
        …
```

AC13's own test requirement — *"an IT asserting the query count is O(1) in the row count … with a multi-row fixture"* — **will fail on the PARENT path** even after the three named lookups are batched. Add the filter's policy lookup to the AC (batch `findMessagingPolicy` by the playerId set before filtering), or the task is not completable as specified.

### F13 — AC13 leaves a second, identical S3 loop in place

AC13 targets the loop in `deletePlayerDevelopmentData` (`GdprErasureService.java:197-208`). There is a structurally identical loop in `erase()` itself, in the same `REQUIRES_NEW` transaction (`:74`):

```java
// GdprErasureService.java:132-140
gdprRequestRepository.findByUserIdAndRequestTypeAndStatus(userId, "EXPORT", "COMPLETED")
    .forEach(completedExport -> {
        try {
            fileStorageService.deleteRawBytes("gdpr/exports/" + completedExport.getId() + ".zip");
        } catch (Exception e) { … }
    });
```

Same unbounded, sequential, blocking, in-transaction shape; same connection-hold scaling. Moving one and not the other does not fix the stated problem — the DB connection is still held across N blocking S3 round-trips.

### F14 — AC13's preferred option (a) weakens the GDPR erasure guarantee as specified

Today the S3 delete at `:203` runs **before** `performanceReportRepository.deleteAllByPlayerId(playerId)` at `:209`. The storage keys are still readable from the DB while the delete is attempted, so a failure is recoverable by re-running erasure.

AC13 option (a) — *"collect the storage keys, commit the DB deletion, then delete from S3"* — inverts that. After commit, a failed S3 delete leaves an orphaned PII PDF **with its pointer row already gone**. The AC's mitigation is *"a logged, re-drivable signal"*, but an in-memory `List<String>` plus a `log.warn` is not re-drivable: the keys die with the JVM, and nothing in the DB can enumerate the orphans.

**Required:** if option (a) is chosen, the storage keys must be written to a durable outbox/pending-deletion table **inside the same transaction** as the DB delete, with the AFTER_COMMIT step draining and clearing that table. Otherwise take option (b) (cap + batch in-transaction), which preserves the current recoverability.

Also flag: AC13's test requirement *"an IT … asserting … the connection isn't held across them"* is not something an integration test can assert. Reduce it to something checkable — e.g. assert the AFTER_COMMIT listener observes the report rows already deleted, and assert the transaction-synchronization ordering.

---

## Implementation traps

### F15 — AC11's sweep instruction would break 4 deliberately-hardcoded machine-format call sites

AC11 says *"sweep … any remaining `'en'`-hardcoded formatter"*. Four of those hardcodes are **load-bearing** and must be explicitly excluded:

| Site | Locale | Why it must not change |
| --- | --- | --- |
| `WeeklyCalendar.vue:128,132` | `'en-CA'` | Yields ISO `YYYY-MM-DD` used as a lookup key |
| `AvailabilityManagerPage.vue:301` | `'en-CA'` | Same — ISO date keying |
| `WeeklyCalendar.vue:176` | `'en'` | `fmt(dt, field)` extracts **numeric** hour/minute for pixel geometry |
| `BookingRequestPage.vue:373` | `'en-US'` | `zoneOffsetMs` parses numeric parts to compute a UTC offset |

`CoachCommandCenterPage.vue:333-342` already carries a comment saying its `'en'` is deliberate and paired with the English array — AC11's rewrite of `getDayIndex` is correct and should compare `Date.getDay()` numerically, but the AC must not let the same reasoning leak onto the four sites above.

### F16 — AC11 understates the real sweep by roughly 5×

AC11 names 2 `formatDate` sites. The actual set of call sites that render in the **visitor's browser locale** (no locale argument, or explicit `undefined`) is ~15:

```
components/development/SkillsRadarChart.vue:149      toLocaleDateString()
components/marketplace/SessionPackPricingDisplay.vue:38  Intl.NumberFormat(undefined, …)
pages/marketplace/CoachPublicProfilePage.vue:525     Intl.NumberFormat(undefined, …)
pages/marketplace/CoachPublicProfilePage.vue:613     toLocaleDateString()          ← named in AC
pages/coach/RevenueDashboardPage.vue:163             Intl.DateTimeFormat(undefined, …)
pages/coach/ReceiptView.vue:89                       Intl.DateTimeFormat(undefined, …)
pages/coach/CoachReliabilityPage.vue:123             toLocaleDateString()          ← named in AC
pages/coach/SessionTemplateVault.vue:126             toLocaleDateString()
pages/coach/CoachSubscriptionPage.vue:256            toLocaleDateString()
pages/parent/CreditStatementPage.vue:126             Intl.DateTimeFormat(undefined, …)
pages/parent/PlayerSubscriptionPage.vue:255          toLocaleDateString()
pages/parent/ParentReceiptView.vue:86                Intl.DateTimeFormat(undefined, …)
pages/parent/ParentApprovalPage.vue:120              toLocaleDateString()
pages/parent/SessionPackPurchasePage.vue:139         Intl.NumberFormat(undefined, …)
pages/parent/SessionPackDashboardPage.vue:172,177    toLocaleDateString/String(undefined, …)
```

Note the `Intl.NumberFormat(undefined, { style: 'currency' })` sites are the same class of bug for money, not just dates — a German user sees `€1,234.56` instead of `1.234,56 €`. AC11's "Files likely touched" table lists 3 pages; the real list is 13 files.

(`Intl.DateTimeFormat().resolvedOptions().timeZone` at `auth.store.js:6`, `TimezoneSelect.vue:73`, `CoachCommandCenterPage.vue:241`, `ParentBookingsPage.vue:181` is timezone detection, **not** formatting — correctly excluded.)

### F17 — AC6's prescribed validator throws a type the existing catch does not catch

AC6 says use `java.util.Currency.getInstance(...)`. That throws `IllegalArgumentException` for an unknown or malformed code (and `NullPointerException` for null). The surrounding block catches:

```java
// StripePaymentGateway.java:48
} catch (IllegalStateException | NumberFormatException e) {
```

`IllegalArgumentException` is the **supertype** of `NumberFormatException`, not a subtype — it is not caught. A validator dropped inside that `try` would escape as a raw `IllegalArgumentException` → generic 500, not the intended `PaymentGatewayException("payment.configurationUnavailable")`. Either catch and rethrow inside the validator, or widen the catch to `IllegalArgumentException`.

Also note there is exactly **one** read site (`StripePaymentGateway.java:47`), which makes AC6's "keep the check in one place" straightforward.

### F18 — AC7's escape character class omits the `sed` address delimiter

The vulnerable construct is `sed -i -E "\\,${_fstab_stale_re},d"` (`deploy/provision.sh:537`) — the address delimiter is `,`.

AC7 proposes `s/[.[\*^$+?(){}|]/\\&/g`. That class does not include `,`, so a `MOUNT_POINT` containing a comma still terminates the address early and corrupts the `sed` expression — the exact failure class the AC exists to prevent. It also omits `]` and `-`.

**Recommendation:** either add `,` (and `]`) to the class, or — cleaner — drop the regex approach entirely and match with `grep -F` on the mount-point token plus `awk '$2 == mp'`, which sidesteps escaping altogether.

AC7's second half is correctly anticipated as stale: `deploy/provision.sh:363-367` sets `VOLUME_LINK="/dev/sdb"` in the `else`, so `FSTAB_ENTRY` at `:373` always has a non-empty device field. Confirm and close, as the AC already permits.

### F19 — AC10's lint fixtures must not live anywhere Flyway scans

Flyway config (`application.yaml:98-102`): `enabled: true`, `validateMigrationNaming: true`, `baseline-on-migrate: true`, default `locations = classpath:db/migration`. Version `11.7.2` (`mvn help:evaluate -Dexpression=flyway.version`).

AC10 Deliverable 2 requires *"one deliberately-violating sample per rule"*. If those fixtures land under `src/main/resources/db/migration/` or `src/test/resources/db/migration/`, Flyway will either **execute them against the test database** or fail `validateMigrationNaming`. The guard must take the scanned directory as a parameter and the fixtures must live somewhere Flyway never looks (e.g. `src/test/resources/migration-lint/{valid,invalid}/`).

### F20 — AC10 rule 4 has no failure-recovery procedure, and rule scoping has a hole

**Recovery:** `V121__phone_otp_tokens_one_active_per_user.sql:14-16` documents the current standard: *"Non-CONCURRENTLY: Flyway runs migrations in a transaction."* Switching to `executeInTransaction=false` for `CREATE INDEX CONCURRENTLY` is correct and supported on Flyway 11.7.2 — but a failed concurrent build leaves an **INVALID index** plus a failed `flyway_schema_history` row, and the next deploy will not proceed until someone manually `DROP INDEX`es and runs `flyway repair`. The convention doc trades a lock risk for a stuck-deploy risk and must state the recovery steps, or the first person to hit it will be doing incident archaeology.

**Scope hole (minor):** the guard keys on "version greater than the checked-in baseline". Current corpus is 115 files, max `V121`, with **152** non-concurrent `CREATE INDEX` and **33** `ADD CONSTRAINT` statements — all grandfathered, which is the right call. But a later-added migration with a *lower* version (a backport) and any `R__` repeatable migration would bypass the guard entirely. Worth one sentence in the guard, not a redesign.

**Also:** an inline `ALTER TABLE … ADD COLUMN … REFERENCES x(y)` creates a foreign key without the literal text `ADD CONSTRAINT`, so the FK rule has a bypass. Text-level linting cannot close that; say so in the doc rather than implying the guard is exhaustive.

### F21 — AC9's 409 endpoint test contradicts its own prescribed fixture and cannot be driven sequentially

AC9 asks to *"drive `POST /api/security/coach/resend-otp` … into the V121 `uq_pot_one_active_per_user` collision and assert HTTP `409`"*, and to *"reuse the spy-free `secondActiveOtpInsert_…` fixture shape"*.

Those two instructions are incompatible:

1. **The sanctioned fixture never calls the endpoint.** `ParentRegistrationResourceIT.java:482-511` does two raw `jdbcTemplate.update(...)` inserts in `transactionTemplate.execute(...)` and asserts a `DataIntegrityViolationException` at the **service/JDBC** layer. It cannot produce an HTTP status. Its own javadoc concedes this: *"The `ApiAdvice` 409 … mapping for this constraint name **rides the same already-tested** `DataIntegrityViolationException` handler path"* — i.e. it deliberately does not prove the 409.

2. **A sequential endpoint call can never collide.** `CoachRegistrationService.resendPhoneOtp` (`:247`) does `otpTokenRepository.deleteByUserIdAndUsedFalse(user.getId())` inside its own transaction *before* `saveAndFlush` (`:254`). Any committed active row is deleted first. The only way to hit the index is a genuinely concurrent transaction whose insert is uncommitted — where the loser **blocks** on the unique index until the winner commits, then raises `23505`.

3. **Rate limiting will bite.** `@RateLimited(key = "coach_resend_otp", capacity = 3, duration = 30)` (per client IP, `:230`) plus `rateLimitingService.tryConsume(String.valueOf(userId), "coach_resend_otp_user", 3, 30, TimeUnit.MINUTES)` (`:236`). `CoachRegistrationResourceIT` already issues **7** calls to `RESEND_OTP_ENDPOINT`. Two more concurrent calls may trip either bucket and return `security.otpMismatch` instead of the expected 409 — a silent false pass if the assertion only checks "not 200".

**Required:** AC9 must either (a) accept a two-thread, two-connection collision harness with explicit rate-limit budgeting and per-test user isolation, or (b) drop the "through the endpoint" requirement and prove the mapping at the `ApiAdvice` slice level instead (which, given F1, is where the real gap is anyway).

### F22 — AC5's security alert on every filter 401 is audit-trail write amplification

AC5 asks `writeUnauthorized` to *"emit the security alert on the access-denied branch"*.

The catch it feeds from is broad:

```java
// JWTAuthorizationFilter.java:135
catch (AccountStatusException | AuthorizationException | AccessDeniedException e) {
```

`AccessDeniedException` here includes `MissingAuthenticationException("Cannot find access token cookie.")` (`:212`) — thrown for **every** request to a secured URL with no cookie: every crawler, every stale bookmark, every user whose 15-minute session simply lapsed, every SPA route hit before login.

`SecurityAuditListener.handleAlert(SecurityAlertEvent)` (`:85-107`) writes a full `AuditTrail` **row to the database** per event. Wiring this into the filter turns an unauthenticated, unrate-limited ingress path into an unbounded DB-write amplifier, and floods the audit trail with the least interesting event class.

**Required:** scope the alert to genuine denial signals — `JWTTheftException`, `InvalidJWTDataException`, `AccountStatusException` — and explicitly exclude `MissingAuthenticationException` and `JWTExpiredException`. The `helpCode` half of AC5 (route through `logError` so filter 401s get a support id) has no such problem and should proceed as written.

---

## Weak acceptance gates and severity corrections

### F23 — AC12's only objective gate is satisfiable without doing the work

AC12's verification is *"a quick script asserting `de-DE` and `fr-FR` have exactly the same key set as `en-US` and zero `// TODO` / `native review` markers remain."*

Per F7, the key-set half **already passes** — it proves nothing. The marker half is satisfied by deleting 55 comments. There is no frontend test runner (standing convention), and residual #4 concedes the German is AI-authored and unverified. So nothing in the story distinguishes a genuine native pass from `sed -i '/TODO: native review/d'`.

**Recommendation — add the one gate that catches the real regression risk of a bulk string rewrite:** assert that for every key, `de-DE` and `fr-FR` preserve exactly the same set of `{placeholder}` tokens and the same pipe-pluralization arity as `en-US`. That is mechanically checkable, it is the failure mode a 55-string rewrite actually produces, and it is worth more than the key-set assertion that already passes. Also record the count of strings whose value actually changed in the File List.

### F24 — AC2's backend half is defense-in-depth, not a live emitter

AC2 states *"a principal with a null display name puts `user=` on the wire"*. Trace:

- `JwtManagerImpl.java:219-220` writes `USER_COOKIE` from `claims.get(DISPLAY_NAME)`
- `TokenCreatorImpl.java:58` sets that claim from `principal.getDisplayName()`
- `Principal.java:154` builds it from `user.getFirstName()`
- `V10__security_schema.sql:30` — `first_name TEXT NOT NULL`

Null is unreachable for any persisted user. The one explicit `displayName("")` (`ClaimsExtractorImpl.java:94`) belongs to the ANONYMOUS principal, which never reaches `createLoginCookies`.

An **empty or whitespace** `first_name` remains possible (`NOT NULL` does not imply non-blank) if registration validation permits it — worth a 2-minute check on the registration DTO before writing "live defect" into the ledger.

**Practical impact:** the fix is still worth doing, and `StringUtils.isBlank`, not `!= null`, is the correct predicate (it covers the `""` source too). But the AC should describe it as hardening, and the story's framing of AC2 as a shipped-bug pair should be softened — the frontend half is the live one.

---

## Line-number drift (Task 0 checklist)

The story disclaims line numbers by convention; collected here so Task 0 is mechanical:

| AC | Story says | Actual |
| --- | --- | --- |
| AC1 | `ApiAdvice.java` ~L159-177 | `:159-176` ✓ |
| AC2 | `JwtManagerImpl.java` ~L215-216 | `:217-220` |
| AC2 | `CookieUtil.java` ~L17-25 | `:17-25` ✓ |
| AC3 | `boot/axios.js:146` errorKey gate | `:151` (`:142` is `refreshExpiryState`) |
| AC11 | `CoachCommandCenterPage.vue` `getDayIndex` ~L266-272 | `:333-342` |
| AC11 | `CoachReliabilityPage.vue:121-124` | `:123` |
| AC7 | `provision.sh` ~L530/531/537, ~L363-373, ~L516-537 | all ✓ |

---

## What holds up well

Not everything needs changing. These are sound and should be kept as written:

- **AC10's grandfathering decision** — treating V60/V89/V94/V97/V98/V117 as applied-and-immutable, with the guard binding only new migrations, is the right call and correctly justified by "no production system yet".
- **AC7's second half** — the AC already anticipates that the empty-device-field guard is stale and instructs the dev to confirm-and-close rather than add a redundant guard. That is exactly the right instruction, and it is correct (`provision.sh:367`).
- **AC3's "has `rint` ever been seen" flag** — the right discriminator for legacy-backend vs dead-session. It just needs the durability fix (F2) and the anonymous-path gate (F2).
- **AC9's `PlaybackServiceIT` shape assertion** — `assertThat(p99).isLessThan(Math.max(200L, p50 * 20))` is a genuine improvement over the current `isLessThan(5_000L)` (`PlaybackServiceIT.java:145-146`) and carries real regression value without wall-clock flake.
- **AC14's delete-outright hygiene** and the residuals section structure — consistent with the ledger's own 2026-08-24 convention.
- **Task 0** — the pre-implementation re-verification audit is the right instinct. Six of this review's findings (F5–F9, F25) are exactly what Task 0 exists to catch; they are surfaced here so the dev starts from evidence rather than rediscovering them one at a time.

---

## Recommended disposition

1. **Rewrite AC1** around SQLSTATE `23P01` / the exclusion-constraint extractor. Without this, AC1 ships a regression (F1).
2. **Rewrite AC3's trigger condition** to gate on active monitoring + a durable `rint`-seen flag (F2), and **pin AC2 to the sentinel option** (F3).
3. **Replace AC8's root-cause paragraph** with the `LoginInfoServiceIT` ThreadLocal leak, and require the fix in the polluting class (F4).
4. **Demote F5–F9 and F25 to confirm-and-close-as-stale** bullets. Roughly a third of AC8/AC11/AC13/AC14's prescribed work is already merged.
5. **Decide explicitly on the backend bundles** (F10, F11) — either extend AC12 or record an honest carve-out. As written the story does not meet its own goal statement.
6. **Add the `findMessagingPolicy` batching to AC13** (F12), **add the second `erase()` S3 loop** (F13), and **require a durable pending-deletion record** for the AFTER_COMMIT move (F14).
7. **Annotate AC11 with the exclusion list** (F15) and the full 13-file site list (F16).
8. **Resolve AC9's fixture contradiction** (F21) and **narrow AC5's alert scope** (F22) before either task starts.

---
---

# Re-check of story v0.2 — 2026-09-02

Story revised 251 → 330 lines, `v0.2`, with a **§ Senior-dev review resolution** table dispositioning all 25 findings. I re-verified each claimed fix against source rather than accepting the table.

**Verdict: 24 of 25 findings correctly resolved. One regression introduced (R1), plus two small factual errors and one newly-surfaced consumer the ACs don't cover. F1–F25 no longer block; R1 does.**

## Blockers correctly closed — verified

| Finding | v0.2 location | Verified |
| --- | --- | --- |
| **F1** AC1 re-rooted on `23P01` | `:38-45` | ✅ Root cause, V87 reference, dead-code consequence, `RescheduleResourceIT` corroboration, and the "drop the `/resend-otp` exposure framing" correction are all stated accurately. Both test directions required. |
| **F2** AC3 anonymous-path logout | `:62-68` | ✅ Triple gate (`checkIntervalId !== null` + `sessionStorage` `rint`-seen + cookie absence). The `boot/axios.js:126/:142` call sites and the `App.vue:46`-before-`:49` ordering are both named. 60s-window note correct. |
| **F3** AC2↔AC3 conflict | `:53` | ✅ Pinned to sentinel, "skip" explicitly forbidden, rationale cites F3. |
| **F4** AC8 re-rooted | `:112-116` | ✅ ThreadLocal leak, `LoginInfoServiceIT` `@AfterEach` omission, `apiKey`-over-`browserCookie` key precedence, and the "fixing only (2) leaves the poisoner live" point all captured. |

**F5–F9, F25** (stale) → all six correctly demoted to AC14 confirm-and-close (`:198-205`).
**F10–F13** (missed scope) → all added with correct counts (43 / 19 / −25) and correct call sites.
**F15–F20, F22, F23** (traps / gaps / weak gate) → all annotated inline as recommended.
**F21** → `/resend-otp` proof moved to the `ApiAdvice` slice; end-to-end harness recorded as residual 5.
**F24** → reframed as hardening with `StringUtils.isBlank` + registration-DTO precondition.

### F2 gate — specifically checked, and it holds

`checkIntervalId !== null` would be inert if monitoring only ever started at App mount (an SPA login does not remount `App.vue`). It does not:

- `useSession.js:96` `initSession()` → `startSessionMonitoring()`
- invoked at `LoginPage.vue:169` and `OtpPage.vue:132` on successful login

So monitoring is armed after an in-tab login, not only on reload. **The gate is sound.**

---

## R1 — REGRESSION: AC13's newly-preferred option (b) cannot deliver what the AC claims

**Severity: blocker for AC13.** This is new in v0.2 — v0.1 preferred option (a).

v0.2 `:192` now reads: *"**Approach (F14) — prefer option (b):** cap + batch the S3 deletes **in-transaction**."*

There is no bulk-delete capability anywhere in the storage stack:

```
infrastructure/blobstore/service/StorageService.java:11        void delete(String key);          // single key
infrastructure/blobstore/service/S3StorageService.java:115-116 s3Client.deleteObject(...)        // singular, not deleteObjects
platform/filestorage/service/FileStorageService.java:324       public void deleteRawBytes(String storageKey)
```

So under option (b):

- **"batch" is not implementable.** Every delete remains one blocking S3 round-trip. AWS `DeleteObjects` (up to 1000 keys/call) exists but is not wired into `S3StorageService`.
- **"cap" breaks the erasure guarantee.** Capping means deliberately leaving PII objects undeleted, with no durable record to re-drive — the precise outcome AC13 says it *"must not weaken"*.
- **Either way the defect is not fixed.** The ledger item AC13 closes is *"the held DB connection lifetime stops scaling with report-history size"*. Under (b) the connection is still held across N sequential blocking S3 calls. Fewer calls is not "stops scaling".

**My F14 wording contributed to this** — it observed that (b) *"preserves current recoverability"* without checking whether a bulk API existed. It does not. Correcting that:

**Recommended resolution — re-flip AC13 to option (a) with the durable table.** Given no bulk API, (a) is the only form that actually fixes the defect:
1. Write the storage keys to a durable pending-deletion table **inside** the erasure transaction (this is also what makes a post-commit S3 failure re-drivable, which a `log.warn` is not).
2. Commit the DB deletion.
3. Drain the table from an `AFTER_COMMIT` step, clearing rows as deletes succeed.
4. That table's migration must itself pass AC10's guard — which v0.2 already anticipates at `:192`.

If option (b) is kept instead, AC13 must explicitly scope **"add a bulk-delete method to `StorageService` / `S3StorageService`"** and **drop the word "cap" entirely** — a capped GDPR erasure is not an acceptable outcome.

Applies to **both** loops (`GdprErasureService:132-140` and `:197-208`), correctly identified in v0.2 `:191`.

---

## R2 — AC1's SQLSTATE list is slightly wrong

v0.2 `:38`: *"…only templates a name for SQLSTATEs `23001 / 23502 / 23503 / 23505 / 23514`"*.

`23001` is in the `lookupswitch` but branches to `aconst_null` — it is **explicitly mapped to null**, not templated:

```
13: lookupswitch { 23001: 120, 23502: 106, 23503: 92, 23505: 78, 23514: 64, default: 122 }
...
120: aconst_null
121: areturn
```

**Correct statement:** the templated set is `23502 / 23503 / 23505 / 23514`. `23001` (RESTRICT violation) is a **second** null-name SQLSTATE alongside `23P01`.

Low impact — `23001` is a fine input for AC1's test (a) ("null name, non-`23P01`") and behaves as the AC expects. But the sentence as written is inaccurate, and `23001` deserves a mention as a real null-name path that correctly lands in the `generic.dataError` / 400 branch.

## R3 — good news: `booking.slotUnavailable` needs no new i18n work

AC1's new 409 path renders localized text out of the box — the key already exists in all four backend bundles:

```
messages_en.properties:124  booking.slotUnavailable=This time slot is no longer available.
messages_de.properties:67   booking.slotUnavailable=Dieses Zeitfenster ist nicht mehr verfügbar.
messages_fr.properties:112  booking.slotUnavailable=Ce créneau horaire n'est plus disponible.
messages.properties:78      booking.slotUnavailable=This time slot is no longer available.
```

Worth stating in AC1 so the dev does not add a duplicate key during AC12's bundle pass. Note `messages_de` already has it — it is one of the 82 keys the German bundle *does* carry.

## R4 — AC2's sentinel has a display consumer the AC doesn't name

A fourth reader of the `user` cookie reads its **value** and renders it:

```js
// pages/DashboardPage.vue:44-49
function getUsernameFromCookie() {
  const match = document.cookie.match(/user=([^;]+)/);
  if (match) {
    try { return decodeURIComponent(match[1]); } catch { return match[1]; }
  }
  return 'User';
}
const username = computed(() => getUsernameFromCookie())
```

Two consequences for AC2 (`:53`):

1. **The sentinel would be shown to the user** as the dashboard greeting. AC2 says "write a documented sentinel value" without constraining it or naming this consumer. Since this function already has a display fallback (`return 'User'`), the fix is small: have the reader treat the sentinel (and blank) as "no name" and fall through to its existing default. **Add `DashboardPage.vue:44-49` to AC2's touched files**, or the hardening ships a visible regression on the dashboard.

2. **That regex has the exact bug AC2 exists to fix.** `/user=([^;]+)/` is unanchored, so it matches `xuser=…` — the substring bug `App.vue:21`'s own comment warns about. (The empty-value half is accidentally safe: `[^;]+` requires ≥1 char, so a bare `user=` falls through to `'User'`.) Anchor it to `(?:^|;\s*)user=` while in the file.

**Consolidation recommendation (not a defect):** the `user` cookie now has six hand-rolled consumers — cleared with the same literal string at `App.vue:29`, `useSession.js:70`, `boot/axios.js:49`, `MainLayout.vue:312`; read for presence at `App.vue:22`; read for value at `DashboardPage.vue:45` — and AC3 adds a seventh (the liveness check). Since AC2 is explicitly hardening this cookie's contract, extracting one shared `readUserCookie()` / `clearSessionCookies()` helper is the natural place to do it and would prevent the next drift.

## R5 — the drift table has its own drift

v0.2 `:255` cites `NeglectedSkillDetectionServiceIT :41, :47` for the hardcoded `PLAYER_ID`. The constant is at **`:35`**:

```java
35:    private static final long PLAYER_ID = 9360000001L;
```

`:41` and `:47` are comment lines that mention it. Trivial, but the drift table exists to be trusted verbatim in Task 0.

---

## Disposition

| Item | Status |
| --- | --- |
| F1–F25 | ✅ Resolved (F14 superseded by R1 — see below) |
| **R1 — AC13 option (b) unimplementable / breaks erasure** | ❌ **Blocker — fix before Task 13** |
| R2 — AC1 SQLSTATE list (`23001` is null, not templated) | ⚠️ One-line correction |
| R3 — `booking.slotUnavailable` already in all 4 bundles | ℹ️ Add note to AC1 / AC12 |
| R4 — sentinel display consumer + unanchored regex | ⚠️ Add `DashboardPage.vue:44-49` to AC2 |
| R5 — drift table line ref | ⚠️ `:35`, not `:41, :47` |

**R1 is the only one that blocks.** R2–R5 are single-line amendments that can ride along with Task 0. Everything else in v0.2 is accurate and ready — the four original blockers are genuinely resolved, not just annotated, and the six stale bullets are correctly demoted rather than quietly dropped.

---

## Fixes applied to the story — v0.3 (2026-09-02)

All five re-check items were folded into the story document (`…deferred-90….md`, now v0.3, 373 lines). No source code was touched — the story is still `ready-for-dev` and implementation has not begun.

| Item | Applied where |
| --- | --- |
| **R1** (blocker) | **AC13 approach re-flipped to option (a) + durable outbox.** Rejection of option (b) documented with the three single-key signatures as evidence; required shape spelled out in 4 steps (`main.pending_blob_deletions`, commit, AFTER_COMMIT drain with `REQUIRES_NEW`, AC10 dogfood); option (b) retained only as a re-scoped fallback with "cap" forbidden. Propagated to **Task 13**, **Dev Notes → Migrations** (now "exactly one", with Task 10 sequenced before Task 13), the **drift table**, and the **F14 row** (struck through as superseded). |
| **R2** | AC1 root-cause bullet: templated set corrected to `23502/23503/23505/23514`; `23001` documented as a second, explicitly-null-mapped path; test (a) now names `23001` as its input. |
| **R3** | New AC1 bullet recording that `booking.slotUnavailable` is already in all four backend bundles, with an explicit "do not duplicate during AC12" warning. |
| **R4** | AC2: `DashboardPage.vue:44-49` added as the third file in scope (sentinel is user-visible → fall through to its existing `'User'` default), regex anchoring specified, shared-constant + shared-helper consolidation recommended. Propagated to **Task 2** and the **drift table**. |
| **R5** | `PLAYER_ID` corrected to `:35` in both AC8 and the drift table. |
| — | New **AC14 residual 9**: no bulk-delete capability in the storage stack (not needed for option (a); left open deliberately). |
| — | New **§ v0.2 re-check → v0.3** disposition table, updated header comment, and a v0.3 Change Log row. |

Also recorded in the story: AC3's `checkIntervalId !== null` gate was re-verified as sound (`initSession()` → `startSessionMonitoring()` fires at `LoginPage.vue:169` / `OtpPage.vue:132`), so it does not neuter the F2 fix.

**Story is now clear to start.** No open blockers from either review round.
