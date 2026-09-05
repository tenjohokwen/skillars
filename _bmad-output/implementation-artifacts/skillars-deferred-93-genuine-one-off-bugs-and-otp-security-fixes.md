# skillars-deferred-93: Genuine One-Off Bugs & OTP Security Fixes

**Status:** ready-for-dev | **Epic:** deferred | **Priority:** high

---

## Story Overview

A comprehensive bundle of 8 implementable one-off bugs and gaps discovered across the codebase, plus one security surface fix for OTP account enumeration. No migrations, no design decisions needed. AC8 (OTP security) uses option A (token/handle-based verification).

**Genuine one-off bugs** from the post-deferred-92 ledger audit, each re-verified against source:
- CSS dead code (`transition: all` declarations still present in multiple files)
- i18n vestigial configuration (single-key file with no locale variants)
- Frontend stub text in production UI (deliberately gated behind Epic 9, should be removed)
- Scheduler exception handling (abort-on-error vs. catch-per-item in `ModerationSlaMonitorService`)
- Concurrent bandwidth-reset race condition (display-only counter, benign self-correcting skew; analysis + documentation, no code fix expected)
- Test coverage gaps in outbox failure paths (FAILED envelope branches untested)
- Missing email subject-key validation test (enum↔bundle parity, no live bug; test value is drift detection)

**Security fix:**
- OTP resend endpoints account-enumeration surface: userId currently a client-set query param on `permitAll` endpoints. Replace with signed token or opaque handle in email-verify response DTO; update all three roles (Coach/Parent/Player) together.

---

## User Story

**As a** developer maintaining Skillars,
**I want** these true defects closed before they accumulate,
**So that** the codebase stays clean and production vulnerabilities do not fester under a growing ledger.

---

## Acceptance Criteria

### AC1: CSS `transition: all` cleanup
- [ ] **Task:** Find and replace all remaining `transition: all` declarations across all frontend styles (`.scss` + `.vue` `<style>` blocks)
- [ ] **Details:** `components.scss:38` and `:81` have `transition: all`. AC25 of deferred-92 cleaned up `glass.scss` only. Additionally, 3 more `transition: all` declarations exist in scoped `<style>` blocks: `MainLayout.vue:418`, `MainLayout.vue:456`, and `CoachProfileBuilderPlaceholderPage.vue:243`. Widen scope to include all `.vue` files.
- [ ] **Rationale:** `transition: all` is a performance anti-pattern; CSS should specify exact properties. Deferred-92 set this pattern; finish it.
- [ ] **Files affected:** `src/frontend/src/css/components.scss`, `src/frontend/src/layouts/MainLayout.vue`, `src/frontend/src/pages/auth/CoachProfileBuilderPlaceholderPage.vue` (and any others found)
- [ ] **Verification:** Grep confirms zero `transition: all` **declarations** remain across all `.scss` and `.vue` files. Note: `glass.scss:17` contains a comment with the literal string "transition: all" — this is not a declaration and should be excluded from the grep check.
- [ ] **Test:** ESLint + `quasar build` green

### AC2: Remove vestigial `i18n/error-messages.properties`
- [ ] **Task:** Delete `src/main/resources/i18n/error-messages.properties` and remove its basename from `MvcConfig`, after ensuring the key is safe to delete or has been folded into the main bundles
- [ ] **Details:** File holds exactly one key (`security.msg.unauthorized`) with no locale variants. Before deleting:
  1. Run `git log -S'security.msg.unauthorized'` to understand why the key was introduced and whether it is currently dead or referenced elsewhere (e.g., via reflection or external configuration).
  2. **Decision:** Either delete outright (if the key was never wired to any code path) OR fold into the main bundles (if it is referenced, even externally).
  3. If folding in: Add `security.msg.unauthorized=...` to all four bundles: `messages.properties`, `messages_en.properties`, `messages_de.properties`, and `messages_fr.properties`. This is required because `MessageBundleParityTest` enforces an exact key-set match.
- [ ] **Current state:** `MvcConfig.messageSource()` registers `error-messages` as a basename (`:54`); the file contains only `security.msg.unauthorized`; the key is NOT currently in any of the four main bundles. The key has no code paths that resolve it today.
- [ ] **New state:** If deleting: remove the basename registration and the file. Also remove/update references in `MvcConfig.java` Javadoc (lines ~60–71) and a comment in `messages.properties` (line ~139) that mention `error-messages`. If folding in: add the key to all four bundles, then remove the basename.
- [ ] **Rationale:** Removes dead configuration and simplifies i18n resolution. Decouples i18n bundles from a vestigial file.
- [ ] **Verification:** 
  - After deletion/removal: `git grep error-messages` returns zero results (after updating Javadoc and comments)
  - After deletion: Ensure no `NoSuchMessageException` by verifying the key is either gone from all code paths or present in all four main bundles
  - If folded in: `security.msg.unauthorized` is present in all three locale bundles + the default `messages.properties`
- [ ] **Test:** Boot the app in all three locales (en, de, fr), verify no `NoSuchMessageException` for any i18n key resolution

### AC3: Remove `marketplace.sortRatingStub` production text
- [ ] **Task:** Remove the frontend sort option for "Rating" from the UI, since it is deliberately gated behind Epic 9 (not yet shipped)
- [ ] **Details:** The backend `case "rating"` sort IS implemented (`CoachSearchService.java:118`, `CoachSearchParams` documents it). However, the frontend sort option in the marketplace UI is `disable: true` with placeholder "Rating (Epic 9)" because the ratings/reviews feature is not live yet and `average_rating` is unpopulated. The option should be removed entirely (or hidden) rather than shipped disabled.
- [ ] **Current state:** `marketplace.sortRatingStub` is defined in `src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js` and used in the sort dropdown UI (likely `CoachListPage` or similar).
- [ ] **Pre-delete check:** Before removing the option, verify that `filters.sortBy` does not default to or persist `'rating'` (check for initial value, URL query params, or localStorage). If it does, users may land on this page with a binding to a missing option (a UI bug).
- [ ] **Action:** Remove the sort option entirely from the UI. Delete `marketplace.sortRatingStub` from all three locale i18n files.
- [ ] **Verification:** Grep finds no `sortRatingStub` references in `src/frontend/src/` (except deleted i18n keys); sort dropdown has no disabled "Rating" option
- [ ] **Test:** MarketplacePage renders; sort dropdown works; ESLint green

### AC4: Fix `ModerationSlaMonitorService` exception handling
- [ ] **Task:** Wrap each video iteration in a try/catch to prevent one stuck video from aborting the scheduled run
- [ ] **Details:** The service's `detectSlaViolations()` loop has no exception handler around the `for` body, so any `IllegalStateException` from `enqueueRetry`/`enqueueAdminAlert`, or a `DataAccessException`, propagates out and ends the cycle, starving every video after the offender.
- [ ] **Current code:** `ModerationSlaMonitorService.detectSlaViolations():~90` — the `for (VideoApprovalRequest video : …)` loop has a try/catch only around the nested `requiresNewTemplate.execute(...)` and only catches `TerminalStateViolationException`
- [ ] **Fix pattern:** Mirror `SessionPackForfeitureScheduler` / `SessionPackExpiryNotifier`, which catch `Exception` per item — wrap each iteration or the entire loop body in a try/catch that logs the failure per-video and continues
- [ ] **Preservation:** Keep the existing `catch (TerminalStateViolationException) { … continue; }` semantics—it intentionally skips incrementing `exhausted` for videos that are already terminal. A broader outer catch must not inadvertently count those as exhausted.
- [ ] **Transactional safety:** `detectSlaViolations()` is `@Transactional` with `PESSIMISTIC_WRITE` locks. The per-item work inside is `REQUIRES_NEW` (commits/rolls back independently). Catching per-item and continuing is safe; do not restructure the outer transaction.
- [ ] **Rationale:** One bad video should not abort the run. This is a pre-existing pattern in the codebase for other schedulers; apply it here.
- [ ] **Files affected:** `src/main/java/com/softropic/skillars/platform/video/service/ModerationSlaMonitorService.java:~90`
- [ ] **Verification:** Add a test mutation: temporarily throw an exception inside the loop; verify the run continues to the next video
- [ ] **Test:** Extend `ModerationSlaMonitorServiceTest`: add a case where video 1 throws an exception and verify video 2 still processes

### AC5: Analyze bandwidth-reset race condition and document findings
- [ ] **Task:** Inspect `QuotaService.incrementBandwidthUsedBytes()` to understand the bandwidth-reset window and document the acceptable risk
- [ ] **Details:** `BandwidthResetChunkProcessor` processes the monthly reset in 500-row chunks, releasing the lock between chunks. `incrementBandwidthUsedBytes()` (called on every video playback at `PlaybackService.java:143`) reads and increments `bandwidth_used_bytes` without a row lock or period check. A concurrent race window exists if reset and increment happen on the same row within the same month. **However**, `bandwidth_used_bytes` is *read* in only one place: `VideoResource.java:122`, to populate a display field on `VideoQuotaResponse`. It gates **nothing**—there is no bandwidth cap enforcement anywhere in `check()`, `reserve()`, or playback authorization. The worst outcome of a collision is a once-a-month, sub-minute skew in a cosmetic counter that the next monthly run corrects anyway.
- [ ] **Current state:** 
  - `QuotaService.reserve()` operates on *storage* quota only (`storage_used_bytes`, `video_quota_reservations` table); it has zero bandwidth logic
  - `incrementBandwidthUsedBytes()` is a bare, unlocked `UPDATE main.video_quotas SET bandwidth_used_bytes = bandwidth_used_bytes + ?`; it does no period check, no self-heal, nothing
  - `BandwidthResetChunkProcessor` splits the reset into 500-row chunks with locks released between chunks (~00:00 UTC on the 1st)
  - `bandwidth_used_bytes` is display-only; no rate-limit or quota check depends on it
- [ ] **Scope clarification:** This AC does NOT require code changes to increment or reset logic. It requires documenting the benign, self-correcting skew and accepting it as a known limitation.
- [ ] **Action:**
  1. Add a comment on `incrementBandwidthUsedBytes()` documenting that a benign, self-correcting sub-minute skew is theoretically possible once per month during the reset window and is accepted (counter is display-only).
  2. Add a similar comment on `BandwidthResetChunkProcessor` documenting the window and the lack of enforcement downstream.
  3. No code change to WHERE clauses, no new locking, no period-aware upsert needed.
- [ ] **Rationale:** Bandwidth is a cosmetic counter, not an enforced quota. The sub-minute skew once per month is within acceptable tolerance and self-corrects on the next run.
- [ ] **Files affected:** `src/main/java/com/softropic/skillars/platform/video/service/QuotaService.java` (incrementBandwidthUsedBytes method) and `BandwidthResetChunkProcessor.java`
- [ ] **Verification:** Code inspection confirms `bandwidth_used_bytes` is not gated/checked anywhere; comments are in place
- [ ] **Test:** No new test needed; the analysis confirms the race is self-correcting and acceptable

### AC6: Add test coverage for admin-alert send failure paths
- [ ] **Task:** Add test cases for `VideoModerationEmailListener.sendAdminAlertSync()` FAILED envelope branch
- [ ] **Details:** The synchronous send was added to `VideoModerationEmailListener` so that SMTP failures throw before the method returns, keeping the outbox row for retry. But `ModerationOutboxIT` covers only successful delivery and early-return (unconfigured recipient). The FAILED envelope branch and retry logic are untested. **Note:** `TestMailManager` writes no `EnvelopeEntity` row (only maps to an internal cache), so it cannot produce a FAILED status. A **real** `MailManager` is required, with a mock `JavaMailSender` configured to throw.
- [ ] **Current test gaps:**
  - `EnvelopeEntity.status == FAILED` path — neither the throw nor the permanent-failure log are tested
  - The retry logic (determined by `isRetry()` = whether the exception is retryable per `isRetryable(exception)`), not by `attempts >= MAX_ATTEMPTS`
  - Permanent-failure path **deletes the row** (not "marks terminal"); the only observable is a log line
- [ ] **Fix:** Add test cases to `ModerationOutboxIT`:
  1. **Retryable case:** Throw a retryable SMTP exception (e.g., socket timeout) in `MailService.sendEmailSync()` → `EnvelopeEntity` persisted with `status=FAILED, isRetry=true` → `VideoModerationEmailListener.sendAdminAlertSync()` throws → `handle()` rethrows → outbox row retained for the next scheduled cycle
  2. **Permanent-failure case:** Throw a non-retryable exception (e.g., authentication failure) → `EnvelopeEntity` persisted with `status=FAILED, isRetry=false` → `sendAdminAlertSync()` returns normally → `handle()` returns normally → outbox row **deleted** (not terminal). The only observable is a `[VIDEO_MODERATION_ADMIN_ALERT_UNDELIVERABLE]` ERROR log line — assert this with Logback `ListAppender` or `OutputCaptureExtension`.
- [ ] **Infra needed:** A real `MailManager` (not `TestMailManager`) with a mock `JavaMailSender` configured to throw on demand. Study `NotificationEmailOutboxAtomicityIT` for the pattern.
- [ ] **Rationale:** The retry semantics (throw to keep the row; log and delete on permanent failure) are correct but untested. Without this, a real SMTP failure is unproven.
- [ ] **Files affected:** `src/test/java/com/softropic/skillars/platform/outbox/ModerationOutboxIT.java` (or new IT class)
- [ ] **Verification:** `ModerationOutboxIT` has test cases for both retryable and permanent-failure paths; both pass
- [ ] **Test:** Retryable case: row retained for retry; permanent-failure case: row deleted and log line present. Mutation test confirms these fail if the retry throw or permanent-failure log is removed

### AC7: Add email subject-key validation test
- [ ] **Task:** Add a test tying the `EmailTemplate` enum to i18n bundles, catching enum-vs-bundle drift
- [ ] **Details:** **Current state:** All 39 `EmailTemplate.subjectKey()` values ARE already present in all four bundles (`messages.properties`, `messages_en.properties`, `messages_de.properties`, `messages_fr.properties`). There is no live bug today; bundle-to-bundle parity is already enforced by `MessageBundleParityTest`. However, this test will catch a subtle class of bugs: a subjectKey constant renamed in every bundle but not in the enum, or a typo in the enum constant—something `MessageBundleParityTest` (which only compares bundles to each other) cannot see.
- [ ] **The issue:** If an enum value is added (e.g., `NEW_TEMPLATE("new.template.subject")`) and the key is added to `messages_en`, `messages_de`, `messages_fr` but accidentally left out of the default `messages.properties`, the enum→bundle tie will break for the default locale. Current bundle-vs-bundle tests miss this.
- [ ] **Fix:** Add a test class `EmailTemplateSubjectKeyParityTest` (or extend the existing `MessageBundleParityTest`):
  1. For each `EmailTemplate` enum value **except `NONE` (which is special-cased in `MailService` and has an empty string, not a resolvable key)**:
     - Call `subjectKey()` and resolve it against `messageSource.getMessage(…)` in all four bundles
     - Assert all four resolve without `NoSuchMessageException`
  2. Fail the test if any key is missing from any bundle
- [ ] **Scope clarification:** This test validates **subject keys** resolved in Java (`MailService.sendEmailFromTemplate` calls `messageSource.getMessage(emailTemplate.subjectKey(), null, locale)`). Do **not** conflate with in-template `#{...}` i18n keys inside Thymeleaf templates, which are a separate set with different resolution rules.
- [ ] **Rationale:** Ties the enum to the bundles, catching enum-vs-bundle drift (missing key, renamed constant). Prevents silent `NoSuchMessageException` at runtime.
- [ ] **Files affected:** `src/test/java/com/softropic/skillars/i18n/EmailTemplateSubjectKeyParityTest.java` (new)
- [ ] **Verification:** Test runs and passes on first run (no missing keys today); test fails if you intentionally remove a key, confirming the test is live
- [ ] **Test:** CI gate ensures this test runs and passes on every build

### AC8: Secure OTP resend endpoints against account enumeration (Option A)
- [ ] **Task:** Replace the query-string `userId` in OTP verification flow with a signed token or opaque handle; remove account-enumeration surface from `resend-otp` endpoints
- [ ] **Current state — CRITICAL MISUNDERSTANDING:** `userId` does NOT come from an "emailed phone-verification link." Instead:
  1. User completes email verification → `CoachRegistrationService.verifyEmail(...)` returns `VerifyEmailResponse("verify-phone", user.getId())`
  2. SPA receives userId in the **response body**, then calls `router.push({ path: '/coach/verify-phone', query: { userId } })`
  3. `userId` is a **client-set SPA route query param**, not a server-generated link—it appears in the URL bar and browser history
  4. The resend control (AC28 of deferred-92) posts it to `POST /api/security/{coach,player}/resend-otp` (`permitAll`), exposing account enumeration
- [ ] **What changes:** The email-verify endpoint's **response DTO** (`VerifyEmailResponse`) must return an opaque/signed handle instead of a raw userId. The three roles (Coach/Parent/Player) must all be updated together.
- [ ] **Implementation approach (choose one):**
  - **Signed JWT token:** Generate a short-lived signed token encoding `{userId, verificationState, timestamp}`, return in response DTO. Frontend stores in route query. On resend, POST the token; backend verifies signature and extracts userId.
  - **Random session handle:** Generate and store a random handle in Redis/in-memory tied to userId + state. Return handle in response DTO. Frontend stores in route query. On resend, POST handle; backend looks up userId.
  - **Session-based:** App is stateless (`JWTAuthorizationFilter`); pre-auth registration flow has no `HttpSession`. This option is a non-starter.
- [ ] **Scope — three roles, ALL must be updated together:**
  - Email-verify response: `CoachRegistrationService.verifyEmail()`, `ParentRegistrationService.verifyEmail()`, `PlayerRegistrationService.verifyEmail()`
  - Phone-verify pages: `CoachPhoneVerifyPage.vue`, `ParentPhoneVerifyPage.vue`, `PlayerPhoneVerifyPage.vue` (all accept raw `userId` in route.query and POST body)
  - Resend endpoints: `POST /api/security/coach/resend-otp`, `/api/security/parent/resend-otp`, `/api/security/player/resend-otp` (all go through shared `RegistrationOtpResendSupport`)
  - DTOs: `VerifyEmailResponse`, `VerifyPhoneRequest`, `ResendOtpRequest`
- [ ] **Malformed-token handling (CRITICAL):** deferred-92 repeatedly fixed "garbage param → raw 500" via `ApiAdvice`'s `@ExceptionHandler(Throwable.class)`. A bad signature / expired / tampered token on the `permitAll` `resend-otp` endpoint must map to a **clean 400**, NOT 500. Test this explicitly.
- [ ] **Out-of-scope (state decision):** `verify-phone` also accepts a raw `userId` in its POST body (`VerifyPhoneRequest`, `permitAll`). This is defensible—it requires a correct OTP, returns uniform `security.otpMismatch` for both unknown-user and wrong-OTP, and is rate-limited per userId. Leaving it unchanged is acceptable; the AC should state this decision (do not silently scope it out).
- [ ] **Frontend inconsistency (nice-to-have):** `ParentPhoneVerifyPage.vue:115` and `PlayerPhoneVerifyPage.vue:120` parse `route.query.userId` with weak `Number(route.query.userId)` (lets `12.5` through, sloppy NaN). deferred-92 hardened only `CoachPhoneVerifyPage.vue` (`Number.isInteger(parsed) && parsed > 0`). If the query param is being reworked, align all three.
- [ ] **Rationale:** Removes the enumeration surface (anyone can walk userIds 1..N) while keeping the resend UX intact (user has the link/token, no re-entry needed).
- [ ] **Files affected:**
  - Response DTOs: `VerifyEmailResponse` (currently returns raw userId)
  - Services: `CoachRegistrationService`, `ParentRegistrationService`, `PlayerRegistrationService` (verifyEmail methods)
  - Frontend pages: `CoachPhoneVerifyPage.vue`, `ParentPhoneVerifyPage.vue`, `PlayerPhoneVerifyPage.vue`
  - Resend support: `RegistrationOtpResendSupport.java` and the three role-specific endpoints that call it
  - Token/handle service (new, if using JWT or Redis handle)
- [ ] **Verification:**
  1. Grep confirms no raw `userId` in the query string of `verify-phone` or `resend-otp` (only in `verify-phone` POST body)
  2. Manual test: Follow email-verify link → enter phone-verify code → resend OTP works without URL bar userId
  3. Manual test: Modify token/handle in URL → resend fails with 400 (not 500)
  4. Manual test: All three roles (Coach/Parent/Player) follow the same flow
  5. ESLint + `quasar build` green; backend tests green
- [ ] **Test:**
  - Unit test: Token/handle service generates, verifies, and rejects tampered inputs
  - Integration test: Email-verify → phone-verify → resend succeeds with valid token/handle; resend with invalid/expired token fails 400
  - Rate-limit test: resend-otp rate limiting still works (per userId, not token)

### AC9: Deferred-work.md ledger hygiene
- [ ] **Task:** Record the genuine one-off bugs this story closes, following the established pruning convention
- [ ] **Details:** After this story ships and is merged:
  1. Mark each closed item in the ledger with a `[PICKED UP by skillars-deferred-93]` tag in-place (do not delete yet)
  2. Once the PR is merged to master, prune each tagged item by deleting the line (following deferred-92's documented convention)
  3. Items to tag: CSS, i18n, stub text, scheduler, bandwidth analysis, admin-alert test, subject-key test, OTP security
  4. New residuals (if any): If the investigation in AC5 or AC8 uncovers a subtlety worth recording for future work, add a bullet under `## Deferred from: skillars-deferred-93 story creation`
- [ ] **Verification:** The deferred-work.md file has `[PICKED UP]` tags in place before marking the story done. After merge, items are pruned per convention.

---

## Technical Requirements

### Migrations & Spring Contexts
- **Zero database migrations** across all ACs.
- **Zero new Spring beans — with a caveat on AC8:** If AC8 chooses the JWT-token approach, a token-generation/verification service may be introduced as a new Spring bean or @Component. If the Redis-handle approach is chosen, `RedisTemplate` dependency will be added. The "zero new contexts" guarantee holds; beans are architectural plumbing, not new contexts. Flagged for awareness during implementation.
- No new application contexts.

### Affected Modules
- **Frontend:** `src/frontend/src/css/`, `src/frontend/src/pages/auth/`, `src/frontend/src/i18n/`
- **Backend:** `platform.notification` (admin-alert test), `platform.video` (bandwidth reset), `platform.security` (OTP), `platform.config` (i18n)
- **Database:** Zero migrations
- **Tests:** `platform.outbox`, `platform.i18n`, `platform.security`

### Testing Strategy
- **Unit tests:** `RegistrationOtpResendSupport`, CSS/i18n verification, `ModerationSlaMonitorService` exception path
- **Integration tests:** `ModerationOutboxIT` (admin-alert FAILED envelope), `EmailTemplateSubjectKeyParityTest` (subject-key parity)
- **ESLint + quasar build:** Must pass cleanly
- **No mvn verify locally** (CI gate — per docs/validation-strategy.md)

---

## Developer Context & Guardrails

### Previous Story Intelligence (deferred-92)
- **Executor shutdown:** `ExecutorConfigurationSupport` is a `DisposableBean`, not `SmartLifecycle`. Only `storageUploadExecutor` uses the forced-termination escalation; the other six pools rely on Spring's 55s `stop_grace_period`.
- **I18n parity:** `messages.properties` is now the DEFAULT fallback (deferred-92 AC12 brought it to parity with `messages_en`). Removing `error-messages.properties` is safe only if `security.msg.unauthorized` is in all three bundles — verify this before deleting.
- **Email listeners:** All 22 email listeners are now `@TransactionalEventListener(BEFORE_COMMIT)`, so their enqueue is atomic with the business transaction. AC4 of deferred-92 wired them onto the outbox.
- **Frontend hardcoded-English:** deferred-92 AC13-AC14 swept the entire frontend for hardcoded English. If a new string appears in this story, ensure it is i18n'd or documented as deliberate (brand names, acronyms, etc.).

### Code Patterns to Follow
- **Scheduler exception handling:** Model AC4 after `SessionPackForfeitureScheduler` or `SessionPackExpiryNotifier` — catch `Exception` per item, log, and continue
- **Outbox test coverage:** Model AC6 after existing outbox IT test cases (study `NotificationEmailOutboxAtomicityIT` for the pattern)
- **I18n validation:** Model AC7 after `MessageBundleParityTest` — use the properties parser, not string counting

### Known Gotchas
- **AC2 — Error-messages key:** `security.msg.unauthorized` exists ONLY in `error-messages.properties`, NOT in the four main bundles. Before deleting, `git log -S'security.msg.unauthorized'` to understand the history. If deleted, the key disappears entirely. If folded in, it must go into ALL four bundles (default + en + de + fr), or `MessageBundleParityTest` fails. Javadoc in `MvcConfig.java` (~lines 60-71) and a comment in `messages.properties` (~line 139) reference `error-messages`—these must be updated/removed as part of the cleanup.
- **AC3 — Rating sort:** The backend sort IS implemented (`CoachSearchService:118`); the frontend option is deliberately disabled (Epic 9 gated). Check whether `filters.sortBy` defaults to or persists `'rating'`—if yes, removing the option leaves a bound value with no matching option. Verify default and persistence before deleting.
- **AC4 — Scheduler line number:** `detectSlaViolations()` loop starts around line 90, not `:60` as the original AC stated. Find the exact line; it's in the `for (VideoApprovalRequest video : …)` loop body.
- **AC5 — Bandwidth race:** `QuotaService.reserve()` handles STORAGE quota only, not bandwidth. The bandwidth writer is `incrementBandwidthUsedBytes()` (unlocked, no period check). Counter is display-only—no enforcement downstream. Analysis will likely conclude: add comments, no code change.
- **AC6 — Test infra:** `TestMailManager` cannot produce a FAILED envelope (it only caches, does not persist `EnvelopeEntity`). Use a real `MailManager` with a mock `JavaMailSender` that throws. Study `NotificationEmailOutboxAtomicityIT` for the pattern.
- **AC7 — Subject keys:** All 39 keys ARE present in all bundles today. No live bug; test will pass on first run. Value is enum↔bundle drift detection. Must exclude `EmailTemplate.NONE("")` (special-cased in `MailService`; empty string is not resolvable).
- **AC8 — OTP userId:** userId does NOT come from an emailed link. It comes from the **email-verify response DTO**, then a client `router.push` into the SPA. Signing userId into a "link" actually requires changing the response DTO for all three roles (Coach/Parent/Player) to return an opaque token/handle instead of a raw id. This cascades to phone-verify pages, resend endpoints, and DTOs. Malformed tokens on the `permitAll` endpoint MUST return 400, not 500. Parent role is completely absent from the original AC—must update all three roles together.

### Files to Read Before Starting
1. **AC1:** `src/frontend/src/css/components.scss` — find the two `transition: all` declarations at lines 38 and 81. Also check `src/frontend/src/layouts/MainLayout.vue:418/456` and `src/frontend/src/pages/auth/CoachProfileBuilderPlaceholderPage.vue:243` for scoped `<style>` blocks.
2. **AC2:** `src/main/resources/i18n/error-messages.properties` — confirm it contains only `security.msg.unauthorized`. Check `git log -S'security.msg.unauthorized'` to understand history. Read `src/main/java/com/softropic/skillars/platform/security/config/MvcConfig.java:54` (basename registration) and Javadoc (~lines 60–71); also `messages.properties:~139` for comments mentioning `error-messages`.
3. **AC3:** `src/frontend/src/i18n/en-US/index.js` — search for `sortRatingStub`. Check whether `CoachSearchParams.sortBy` has a default or persistent value. Check `CoachSearchService.java:118` to confirm backend sort exists.
4. **AC4:** `src/main/java/com/softropic/skillars/platform/video/service/ModerationSlaMonitorService.java:~90` — current exception handling in the `for (VideoApprovalRequest video : …)` loop. Check `SessionPackForfeitureScheduler` or `SessionPackExpiryNotifier` for the exception-handling pattern to mirror.
5. **AC5:** `src/main/java/com/softropic/skillars/platform/video/service/QuotaService.incrementBandwidthUsedBytes()` — note it is unlocked and has no period check. Check `BandwidthResetChunkProcessor.java` for the chunking logic. Note: `reserve()` is for storage quota, not bandwidth.
6. **AC6:** `src/test/java/com/softropic/skillars/platform/outbox/ModerationOutboxIT.java` — current test cases (cover success and unconfigured recipient). Check `NotificationEmailOutboxAtomicityIT` for the pattern on how to set up a real `MailManager` with failing `JavaMailSender`.
7. **AC7:** `src/main/java/com/softropic/skillars/i18n/EmailTemplateSubjectKeyParityTest.java` if it exists; or `MessageBundleParityTest.java` to extend. Grep all email subject keys in `messages*.properties` to confirm they are present.
8. **AC8:** `src/frontend/src/pages/auth/CoachPhoneVerifyPage.vue`, `ParentPhoneVerifyPage.vue`, `PlayerPhoneVerifyPage.vue` — check how they currently receive `userId`. Check `CoachRegistrationService.verifyEmail()` (and Parent/Player variants) for the response DTO. Check the three `resend-otp` endpoints and `RegistrationOtpResendSupport`. Read `PostalService` / email verification flow to understand how verification links are generated and sent.

---

## Git & Commit Strategy

**Commit per AC** (or group small related ACs):
1. `skillars-deferred-93 AC1: remove CSS transition: all declarations`
2. `skillars-deferred-93 AC2: remove vestigial i18n/error-messages.properties`
3. `skillars-deferred-93 AC3: remove marketplace.sortRatingStub stub text`
4. `skillars-deferred-93 AC4: fix ModerationSlaMonitorService exception handling`
5. `skillars-deferred-93 AC5: document bandwidth-reset race condition (no code change)`
6. `skillars-deferred-93 AC6: add admin-alert test coverage`
7. `skillars-deferred-93 AC7: add email subject-key validation test`
8. `skillars-deferred-93 AC8: secure OTP resend against account enumeration`
9. `skillars-deferred-93 AC9: ledger hygiene`

**Note on AC5:** Analysis will likely conclude the bandwidth race is self-correcting and acceptable (counter is display-only, skew corrects on next run). Outcome is comments on two methods, not a functional code fix. If further investigation reveals a true fix is needed, it becomes a separate story (payload would be substantial: period-aware upsert in `incrementBandwidthUsedBytes` with row-level locking).

**Branch name:** `story/deferred-93-one-off-bugs-otp-security` (max 50 chars: ✓ 48 chars)

---

## Story Completion Checklist

- [ ] All 9 ACs implemented and tested
- [ ] ESLint + `quasar build` green
- [ ] Backend unit + integration tests green (CI gate)
- [ ] No mvn verify locally (CI is the gate)
- [ ] Commits staged, pushed, PR created
- [ ] Deferred-work.md updated with tags
- [ ] Story marked review

---

## Success Criteria

**This story is complete when:**
1. All 8 genuine bugs are addressed:
   - AC1: All `transition: all` declarations removed from `.scss` and `.vue` files
   - AC2: `error-messages.properties` safely handled (deleted or key folded into main bundles)
   - AC3: `marketplace.sortRatingStub` removed from UI and i18n files
   - AC4: `ModerationSlaMonitorService` exception handling fixed; per-video try/catch in place
   - AC5: Bandwidth-reset race analyzed and documented (comments on `incrementBandwidthUsedBytes` and `BandwidthResetChunkProcessor`; no code change expected)
   - AC6: Admin-alert FAILED envelope branch tested with both retryable and permanent-failure cases
   - AC7: `EmailTemplateSubjectKeyParityTest` added; enum↔bundle parity enforced
   - AC8: OTP verification flow secured; `userId` replaced with signed token/opaque handle across all three roles
2. Every AC is tested and passing (unit + integration)
3. ESLint + `quasar build` green; backend CI green
4. Deferred-work.md marked with `[PICKED UP by skillars-deferred-93]` tags
5. PR passes CI and is merged to master
6. After merge, deferred-work.md pruned per convention (tags → deleted)

---

## Ledger Reference

**Deferred items this story closes:**
- `components.scss` — two `transition: all` declarations (deferred-92 AC25 residual)
- `i18n/error-messages.properties` — vestigial single-key file (deferred-92 AC12 residual)
- `marketplace.sortRatingStub` — stub text in production UI (deferred-92 AC13-AC14 residual)
- `ModerationSlaMonitorService` — abort-on-exception (code review chunk 1, 2026-09-04)
- Chunked bandwidth-reset race (code review chunk 1, 2026-09-04)
- Admin-alert send test coverage gap (code review patches, 2026-09-04)
- Missing email subject-key validation (code review chunk 3, 2026-09-04)
- OTP-resend account enumeration (code review chunk 4, 2026-09-04, AC28 residual)

---

## Dev Agent Record

*(Leave blank until implementation — this is where the developer documents surprises, decisions, and corrections)*
