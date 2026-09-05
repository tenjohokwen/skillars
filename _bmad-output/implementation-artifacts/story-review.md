# Senior-Dev Audit — skillars-deferred-93 (One-Off Bugs & OTP Security)

**Reviewed:** 2026-09-05
**Method:** every factual claim in the story cross-checked against current `master`/branch source. Findings below are only those confirmed by reading the code — no speculative "might be" items.

**Verdict:** the story is directionally right about *which* defects exist, but four ACs rest on stale or incorrect premises that will send a developer down the wrong path or produce a broken change if followed literally. AC2, AC5, AC7, and AC8 need rework before dev. AC1/AC3/AC6 need smaller corrections. AC4 is sound.

---

## CRITICAL — will mislead the developer or break production if implemented as written

### C1 — AC2: `security.msg.unauthorized` is NOT in `messages*.properties`, and nothing resolves it

The AC says *"ensure `security.msg.unauthorized` exists in `messages*.properties` (AC12 of deferred-92 brought `messages.properties` to parity — verify it is there)"* and *"the key is already resolved from `messages*.properties` by every other code path."*

Both statements are false:

- **The key exists in exactly one place:** `src/main/resources/i18n/error-messages.properties`. It is absent from `messages.properties`, `messages_en.properties`, `messages_de.properties`, and `messages_fr.properties` (verified by grep across all four).
- **No code path resolves it.** A full-tree grep (`*.java`, `*.xml`, `*.yaml`, `*.yml`, `*.html`, `*.properties`) finds `security.msg.unauthorized` only in `error-messages.properties` itself and in one Javadoc line of `MvcConfig.java`. There is no `getMessage("security.msg.unauthorized", …)` call anywhere.

Consequences the story does not account for:

1. **Deleting the file + basename without first adding the key elsewhere is only safe because the key is currently dead.** But `MvcConfig.messageSource()` sets `setFallbackToSystemLocale(false)` and does *not* set `useCodeAsDefaultMessage`, so the moment anything *does* resolve this key after the file is gone, it throws `NoSuchMessageException`. The AC must be reworded from *"verify it is there"* to *"decide delete-outright vs. fold-in, then, if folding in, ADD it to all four bundles first."*
2. **Before choosing, run `git log -S'security.msg.unauthorized'`** to learn why the key was introduced. If it was only ever wired to a Spring Security entry-point `MessageSource` that has since been removed, delete it outright. If it is resolved reflectively/externally, it must be folded in.
3. **`MessageBundleParityTest` enforces an exact key-set match** between `messages_en` / `messages_de` / `messages_fr` and checks the default bundle. If you fold the key into `messages.properties` only, that test fails. It must go into all four (base + en + de + fr).
4. **The AC's verification step (*"Grep `error-messages` in the codebase returns zero results"*) is unsatisfiable as written.** `error-messages` is also named in the `MvcConfig.java` Javadoc block (roughly lines 60–71) and in a comment in `messages.properties` (~line 139). Both must be edited/removed, and the AC should list them as required changes.
5. **Basename order matters and is currently load-bearing.** `setBasenames("classpath:/i18n/error-messages", "classpath:/i18n/messages")` lists `error-messages` *first*, so it wins resolution for any shared key. Removing it shifts resolution of `security.msg.unauthorized` (if re-added) to `messages`. Fine, but call it out.

### C2 — AC5: points at the wrong method, the decision branch is dead, and the proposed SQL fix is broken

The AC tells the dev to *"Read `QuotaService.reserve()` and verify … Does it self-heal the period"*.

- **`QuotaService.reserve()` has nothing to do with bandwidth.** It operates only on *storage* quota: `storage_used_bytes`, the `video_quota_reservations` table, and `findByIdForUpdate`. It never reads or writes `bandwidth_used_bytes` or `bandwidth_period_start`. A developer following the AC verbatim will read `reserve()`, find no bandwidth logic, and stall.
- **The actual bandwidth writer is `QuotaService.incrementBandwidthUsedBytes(ownerId, bytes)`** — a bare, unlocked `UPDATE main.video_quotas SET bandwidth_used_bytes = bandwidth_used_bytes + ? WHERE user_id = ?`, called once from `PlaybackService.java:143` on each playback authorization. It does **no** period check and **no** rollover. Nothing in the codebase self-heals the bandwidth period except the monthly `BandwidthResetChunkProcessor`.
- **Therefore the AC's "If `reserve()` self-heals → no code change needed" branch is unreachable.** The decision tree only ever resolves to "no self-heal."
- **Severity is far lower than the AC implies.** `bandwidth_used_bytes` is *read* in exactly one place: `VideoResource.java:122`, to populate a display field on `VideoQuotaResponse`. It gates nothing — there is no bandwidth cap anywhere in `check()`, `reserve()`, or playback authorization. The worst outcome of the race is a once-a-month, sub-minute skew in a cosmetic counter that the next monthly run zeroes anyway. This almost certainly warrants a one-line explanatory comment, not a locking change.
- **The proposed remediation (`WHERE bandwidth_period_start = CURRENT_DATE - INTERVAL 1 month` on the chunk) is wrong on four counts:**
  1. `INTERVAL 1 month` is MySQL syntax. This is PostgreSQL (`DATE_TRUNC`, `main.` schema, `TIMESTAMPTZ`) — it needs `INTERVAL '1 month'`.
  2. `CURRENT_DATE - INTERVAL '1 month'` is a single calendar day, not a month. An equality predicate against it matches almost no rows.
  3. It breaks the self-excluding / crash-resumable predicate that `BandwidthResetChunkProcessor`'s Javadoc goes to great length to guarantee. Rows more than one month stale (an inactive user), or rows left behind by a skipped run, would never be reset.
  4. It does not achieve "non-overlapping row sets." `incrementBandwidthUsedBytes` still targets those same prior-month rows; adding a WHERE clause to the *chunk* does nothing to change which rows the *increment* touches.
- **If a real fix is genuinely wanted,** it belongs in `incrementBandwidthUsedBytes` — make it period-aware under a row lock (conditional UPDATE / upsert that resets the period when `DATE_TRUNC('month', bandwidth_period_start) < DATE_TRUNC('month', NOW())` before adding). Not in the chunk WHERE clause.
- **Minor:** the "~1s window once a month" figure is invented. `BandwidthResetService.drainReset()` is a tight `for` loop with no inter-chunk sleep. The exposure window for any given not-yet-processed row is "from 00:00 UTC on the 1st until the chunk loop reaches that row."

**Recommended rewrite of AC5:** "Inspect `incrementBandwidthUsedBytes` (not `reserve()`). Confirm the counter is display-only (`VideoResource:122`, no enforcement). Add a comment on both `incrementBandwidthUsedBytes` and `BandwidthResetChunkProcessor` documenting that a benign, self-correcting sub-minute skew is possible once a month and is accepted. No functional change."

### C3 — AC8: the "verification link" being hardened may not exist in that form; parent role and the `userId` origin are missing

1. **There is no emailed phone-verification link carrying `userId`.** `userId` reaches the SPA as the **response body of email verification**: `CoachRegistrationService.verifyEmail(...)` returns `new VerifyEmailResponse("verify-phone", user.getId())` (line ~164), and `CoachEmailVerifyPage.vue:77` then does `router.push({ path: '/coach/verify-phone', query: { userId } })`. So `userId` is a client-set SPA route query param, exposed in the URL bar/history — not a server-generated link. Option A ("sign `userId` into the link") therefore actually requires changing the **email-verify endpoint's response DTO** for all three roles to return an opaque/signed handle instead of a raw id, plus the phone-verify page carrying that handle, plus `verify-phone` and `resend-otp` accepting it. That is a much larger surface than "sign userId into the link," and the story's framing hides it.

2. **The parent role is entirely absent from AC8.** The AC names only `/coach/verify-phone` and `/player/verify-phone`. But `ParentPhoneVerifyPage.vue`, `parentRegistrationApi.resendOtp`, `POST /api/security/parent/resend-otp`, and `ParentRegistrationService.resendPhoneOtp` all exist and route through the same shared `RegistrationOtpResendSupport`. Implemented per the AC's file list, parent stays enumerable and the three flows diverge.

3. **"Files affected" is materially incomplete.** A correct Option A touches: `Coach/Parent/PlayerEmailVerifyPage.vue`, `Coach/Parent/PlayerPhoneVerifyPage.vue`, `Coach/Parent/PlayerRegistrationResource.java` + `…Service.java` (email-verify + verify-phone + resend paths), and the `VerifyEmailResponse` / `VerifyPhoneRequest` / `ResendOtpRequest` DTOs. `ConfigResource.java` (listed as a guess) is unrelated to this flow.

4. **Each of the three suggested implementations carries an unflagged risk:**
   - **Signed JWT:** `jjwt` is on the classpath, so feasible — but token TTL must cover the *entire* email-verify → phone-verify window, which a distracted user can stretch to hours. A short-lived token breaks legitimate resends. The AC says "short-lived" without addressing this.
   - **Random handle in Redis:** feasible (`spring-boot-starter-data-redis` + `bucket4j-redis` are on the classpath) but adds a datastore dependency to a currently-stateless pre-auth flow, and contradicts the story's own "zero new Spring beans" guarantee (see X1).
   - **Bind to session:** the app is stateless JWT auth (`JWTAuthorizationFilter`); the pre-auth registration flow has no `HttpSession`. This option is close to a non-starter and should not be presented as equivalent.

5. **Malformed-token handling is a corner case with a track record in this codebase.** deferred-92 repeatedly fixed "unauthenticated garbage param → raw 500 via `ApiAdvice`'s `@ExceptionHandler(Throwable.class)`" (see the cookie/locale comments in `MvcConfig`). A bad signature / expired / tampered token on the `permitAll` `resend-otp` endpoint must be mapped to a clean 400. AC8's verification step 3 should explicitly assert **not 500**.

6. **`verify-phone` also accepts a raw `userId` in its POST body** (`VerifyPhoneRequest`, `permitAll`). Leaving it is defensible — it requires a correct OTP, returns a uniform `security.otpMismatch` for both unknown-user and wrong-OTP, and is rate-limited per `userId` (`coach_otp_verify`, 5/10 min). But the story silently scopes it out; it should state that decision.

7. **Pre-existing frontend inconsistency worth sweeping while here:** `ParentPhoneVerifyPage.vue:115` and `PlayerPhoneVerifyPage.vue:120` still parse the id with the weak `route.query.userId ? Number(route.query.userId) : null` (lets `12.5` through, sloppy NaN handling). deferred-92 hardened only `CoachPhoneVerifyPage.vue` (`Number.isInteger(parsed) && parsed > 0 ? parsed : null`). If the query param is being reworked anyway, align all three.

---

## MODERATE — AC intent is fine, but a stated fact is wrong and will misdirect effort

### M1 — AC7: "17 of 39 email subject keys missing from one or more bundles" is not true on current source

Verified key-by-key: **all 39 `EmailTemplate.subjectKey()` values are present in all four bundles** (`messages.properties`, `messages_en`, `messages_de`, `messages_fr`). The "current risk" the AC describes — a missing subject key throwing `NoSuchMessageException` in `MailService.sendEmailFromTemplate` — does not currently exist.

Further, `MessageBundleParityTest` **already** enforces an exact key-set match across `_en`/`_de`/`_fr` plus the default bundle, so bundle-to-bundle drift for these keys is already covered.

- **Reframe the AC.** The proposed `EmailTemplateSubjectKeyParityTest` will pass on first run; there are no keys to add. Its net-new value is narrow but real: it ties the **enum** to the bundles, catching a mistyped enum constant or a key renamed in every bundle but not the enum — something the existing bundle-vs-bundle test cannot see. Say that, so the dev doesn't waste time hunting a 17-key bug that isn't there.
- **Spec bug:** "for each `EmailTemplate` enum value" includes `NONE("")`. `getMessage("", …)` throws `NoSuchMessageException`. `NONE` is special-cased in `MailService` and must be filtered out (`t != EmailTemplate.NONE` or `!subjectKey.isBlank()`).
- **"9 behind a live Thymeleaf template … `MailService:74` turns them into runtime exceptions"** conflates two key sets. Subject keys are resolved in Java (`messageSource.getMessage(emailTemplate.subjectKey(), null, locale)`), never in Thymeleaf `th:text`. In-template `#{...}` keys are a different set that may have separate gaps — but this AC's test does not cover them.

### M2 — AC1: the "finish the cleanup" scope stops short of the actual remaining declarations

`transition: all` declarations confirmed at:
- `src/frontend/src/css/components.scss:38` (`.q-drawer .q-item`) and `:81` (`.q-btn`) — in the AC's scope.
- `src/frontend/src/layouts/MainLayout.vue:418` and `:456` — **not** in scope.
- `src/frontend/src/pages/auth/CoachProfileBuilderPlaceholderPage.vue:243` — **not** in scope.

The AC scopes the task and its verification grep to `src/frontend/src/css/` only, so it goes green while three declarations remain in scoped `<style>` blocks — contradicting the AC's own rationale ("finish it") and deferred-92's intent. Either widen to all frontend styles (`.scss` + `.vue` `<style>`), or explicitly record the three `.vue` occurrences as deliberately out of scope.

- **Minor:** `glass.scss:17` is a *comment* containing the literal string "transition: all". A verification worded "grep confirms zero `transition: all`" will report a hit there. Word it "zero `transition: all` **declarations**."

---

## MINOR — corrections and missing nuance

### m1 — AC3: the "rating" sort *is* implemented on the backend

`CoachSearchService.java:118` has `case "rating" -> statusSort.and(Sort.by(Sort.Order.desc("averageRating").nullsLast()))`, and `CoachSearchParams` documents `sortBy` as `"price" | "rating" | "displayName"`. The AC's dichotomy ("remove if not implemented, else move to real i18n") misses the real state: backend supports it; the frontend option is deliberately `disable: true` with "Rating (Epic 9)" stub text because the ratings/reviews feature is not live (and `average_rating` is presumably unpopulated until then). Removing the frontend option is likely correct, but frame it as deliberate feature-gating, not "unimplemented."

- **Edge case:** if `filters.sortBy` ever defaults to or persists `'rating'` (initial value, URL query, or localStorage), removing the option leaves the `q-select` bound to a value with no matching option. Check the default and any persistence before deleting the line.

### m2 — AC6: test-infra gap understated; two branch descriptions are factually wrong

- **`TestMailManager` cannot produce a `FAILED` envelope.** Its `sendEmailSync()` override only puts the envelope in a map — it writes no `EnvelopeEntity` row. So in `VideoModerationEmailListener.sendAdminAlertSync`, `envelopeEntityRepository.findBySendId(...)` returns `null`, and the `status == FAILED` branch is **unreachable**; every existing `ModerationOutboxIT` case exercises only the happy path. Reaching the FAILED branch requires a **real** `MailManager` whose `MailService`/`JavaMailSender` throws (GreenMail set to reject, or a `@MockBean` sender) so the real `sendEmailSync` runs its catch and persists `status=FAILED`, `retry=isRetryable(e)`. `TestMailManager`'s `super(null,null,null,null)` constructor means it cannot be extended to persist without new plumbing. The AC's "`TestMailManager` or a mock that can stamp a FAILED envelope" hides this fork.
- **Wrong discriminant.** The branch in `sendAdminAlertSync` is chosen by `persisted.isRetry()`, and `retry` is set to `isRetryable(exception)` in `MailManager.toEnvelopeEntity` — i.e. a transient SMTP error vs. a `NON_REPAIRABLE_ERRORS` type. There is **no `attempts >= MAX_ATTEMPTS` check** in this path (that mechanism lives in `EmailRetryScheduler`). So the AC's two cases ("isRetry() case: attempts < MAX_ATTEMPTS" / "permanent-failure: attempts >= MAX_ATTEMPTS") are inaccurate. Correct framing:
  - **Case 1 (retryable):** exception classified retryable → `isRetry()==true` → `sendAdminAlertSync` throws `IllegalStateException` → `handle()` rethrows → outbox row retained, `attempts++`, backoff.
  - **Case 2 (permanent):** exception in `NON_REPAIRABLE_ERRORS` → `isRetry()==false` → `[VIDEO_MODERATION_ADMIN_ALERT_UNDELIVERABLE]` logged at ERROR → returns normally.
- **"row marked terminal" is wrong for case 2.** `sendAdminAlertSync` returns normally, `handle()` returns normally, and the generic outbox **deletes** the row. There is no terminal status for this aggregate. The only observable for case 2 is the log line — assert it with a Logback `ListAppender` / `OutputCaptureExtension`, since there is no post-state to check.

### m3 — AC4: sound; three notes for the implementer

Confirmed the defect: in `ModerationSlaMonitorService.detectSlaViolations()`, the `else` (retry) branch of the `for` loop has **no** try/catch. A `DataAccessException` from `videoRepository.findById`/`save`, or an `IllegalStateException` from `moderationOutboxSupport.enqueueRetry`, propagates out and ends the scheduled run, starving every video after the offender (and, since it stays `SCANNING`, it is re-selected and re-poisons the next cycle). The max-retries branch has a try/catch, but only for `TerminalStateViolationException`.

- The line is ~90, not `:60` as the AC states.
- Preserve the existing `catch (TerminalStateViolationException) { … continue; }` semantics — it intentionally skips `exhausted++` for already-terminal videos. A naïve broad wrap must not start counting those.
- `detectSlaViolations()` is itself `@Transactional`, and `findScanningOlderThan` takes `PESSIMISTIC_WRITE` locks held to method end. Catching per-item and continuing is safe (the per-item work is `REQUIRES_NEW` and commits/rolls back independently) — just don't restructure the outer transaction.
- The existing `ModerationSlaMonitorServiceTest` is a Mockito unit test with `transactionTemplate.execute(any())` stubbed to invoke the callback. Making `videoLifecycleService`/`moderationOutboxSupport` throw for video 1 and asserting video 2 still processes is a straightforward addition.

---

## CROSS-CUTTING

### X1 — "Zero new Spring beans / no new contexts" is likely violated by a real AC8

Any Option-A implementation (signed-token service, or a Redis-backed handle store) almost certainly introduces at least one new bean or a new dependency on `RedisTemplate`. The "No Migrations, No New Spring Contexts" guarantee in *Technical Requirements* should be softened to acknowledge AC8 may add a token/handle collaborator.

### X2 — Commit strategy assumes AC5 produces a fix

Commit 5 is templated as "analyze + fix bandwidth-reset race condition." Per C2, the correct outcome of AC5 is very likely "analyzed, documented, no functional change." That is a valid result; the checklist/commit message should allow for it rather than presuming a code fix.

### X3 — AC9 ledger: follow the ledger's own pruning convention

The AC says "delete them once the story is merged." deferred-92 AC6 established that the ledger is pruned per its own documented convention (tag `[PICKED UP by …]`, then prune) rather than bulk-deleted. Match that.

---

## Summary table

| AC  | Status | Core issue |
|-----|--------|-----------|
| AC1 | Fix scope | Verification scoped to `css/`; 3 live `transition: all` declarations remain in `.vue` files. Comment match in `glass.scss:17` will confuse the grep check. |
| AC2 | **Rework** | Key is NOT in `messages*.properties` and is referenced by zero code paths. "Verify it is there" is wrong — must add to all 4 bundles (or delete outright after `git log -S`). Javadoc/comment references block the verification step. |
| AC3 | Nuance | Backend `case "rating"` sort IS implemented; frontend option is deliberately Epic-9-gated. Check for a persisted `sortBy='rating'` before removing. |
| AC4 | OK | Sound. Line is ~90 not `:60`; preserve `TerminalStateViolationException`→`continue`; outer `@Transactional` + pessimistic locks are fine with per-item catch. |
| AC5 | **Rework** | Names `reserve()` — wrong method (storage only). Bandwidth writer is `incrementBandwidthUsedBytes` (unlocked, no self-heal). Counter is display-only (`VideoResource:122`), not enforced. Proposed WHERE-clause SQL is MySQL syntax, wrong semantics, breaks the resumable predicate, and doesn't separate row sets. Likely outcome: a comment, no code change. |
| AC6 | Fix framing | `TestMailManager` writes no `EnvelopeEntity` → FAILED branch unreachable with it; needs a real failing `MailManager`. Branch discriminant is `isRetry()` (= exception retryability), NOT `attempts vs MAX_ATTEMPTS`. Permanent-failure path deletes the row (not "terminal") — only observable is a log line. |
| AC7 | Fix premise | All 39 subject keys are present in all 4 bundles today; no live bug. `MessageBundleParityTest` already covers bundle parity. New test's real value = enum↔bundle drift; will pass on first run. Must exclude `EmailTemplate.NONE`. "Thymeleaf template" claim conflates subject keys (Java-resolved) with in-template keys. |
| AC8 | **Rework** | `userId` comes from the email-verify *response DTO* + a client `router.push`, not an emailed link. Parent role omitted entirely (3 roles share `RegistrationOtpResendSupport`). File list incomplete. App is stateless (session option ≈ dead). JWT TTL vs. UX window unaddressed. Malformed token must not 500. |
| AC9 | Minor | Follow the ledger's documented prune convention, not bulk delete. |
