# Story skillars-1.3: Coach Account Registration & Email Verification

Status: done

## Story

As a coach,
I want to create a Skillars account with email confirmation and phone OTP verification,
so that I can access the platform and begin building my professional profile.

## Acceptance Criteria

1. **Registration persists user**: `POST /api/security/coach/register` (public) — name, email, password, phone → new User with `skillarsRole = COACH`, `verificationStatus = UNVERIFIED`, `activated = false`. Returns 200.

2. **Email verification email sent**: On successful registration, a confirmation email is dispatched via `infrastructure.ses.SesEmailService` containing a secure one-time verification link (UUID token, 24h TTL).

3. **Consent gate enforced**: The Terms of Service and Privacy Policy checkboxes must be required, must NOT be pre-checked. The submit button is disabled until both are checked. Unchecked = form cannot be submitted.

4. **Email token verification**: `GET /api/security/coach/verify-email?token=` (public) — if token valid and not expired: mark email verified (`verificationStatus = EMAIL_VERIFIED`), invalidate token (`used = true`), trigger phone OTP dispatch, return 200 with `{ nextStep: "verify-phone", userId }`. If token expired or used: 400 with `ErrorDto` code `security.emailTokenExpired` / `security.emailTokenUsed` + a `canResend: true` flag.

5. **Phone OTP verification**: `POST /api/security/coach/verify-phone` (public) — `{ userId, otp }` — if OTP matches and not expired: set `verificationStatus = BASIC_VERIFIED`, mark OTP used, return 200. On mismatch: 400 inline error. OTP input uses single 6-digit field with auto-advance between digits and auto-submits on last digit entered.

6. **Resend verification email**: `POST /api/security/coach/resend-verification` (public) — accepts `{ email }` — if user exists and not yet email-verified, invalidates old token, issues new one, sends new email. Response is always 200 (no account enumeration).

7. **Duplicate email handling**: If the submitted email already exists, the response must NOT reveal whether the account is verified. Return `409 Conflict` with `ErrorDto` code `security.emailInUse`. Frontend shows an inline message plus a "Sign in instead" link.

8. **Profile not yet public**: After BASIC_VERIFIED, the coach's profile must not appear on the marketplace. Subsequent Epic 2 stories handle the profile builder and listing activation.

9. **Contact detail sanitization**: Any free-text field in the registration request (e.g., name fields if they accept extended input) is passed through `infrastructure.sanitizer.ContactDetailSanitizer` server-side before persistence. Frontend shows an amber warning bar inside any free-text input while the user is typing a detectable email or phone pattern: "Contact details will be removed on save." On save, the sanitizer silently redacts — it is never a blocking error.

10. **`@PreAuthorize` on all endpoints**: All non-public endpoints in `platform.security` must bear `@PreAuthorize`. Public endpoints (`/register`, `/verify-email`, `/verify-phone`, `/resend-verification`) are whitelisted in `SecurityConfiguration.java`.

## Tasks / Subtasks

- [x] Task 1: Flyway V21 migration — extend user table + new token tables (AC: 1, 4, 5)
  - [x] Create `V21__skillars_security_extension.sql`:
    - Add `skillars_role VARCHAR(20)` to `"user"` table (nullable initially, to avoid existing-row constraint failure)
    - Add `verification_status VARCHAR(20) DEFAULT 'UNVERIFIED'` to `"user"` table
    - Create `email_verification_tokens` table (see Dev Notes for DDL)
    - Create `phone_otp_tokens` table (see Dev Notes for DDL)
    - Insert `ROLE_COACH` and `ROLE_PARENT` into `authority` table using next-sequence ID values (see Dev Notes)
  - [x] Verify migration runs cleanly against Testcontainers PostgreSQL

- [x] Task 2: Domain enums and User entity extension (AC: 1, 4, 5)
  - [x] Create `SkillarsRole.java` enum in `platform.security.contract`: `COACH, PARENT, PLAYER, ADMIN`
  - [x] Create `SkillarsVerificationStatus.java` enum in `platform.security.contract`: `UNVERIFIED, EMAIL_VERIFIED, BASIC_VERIFIED, SUSPENDED`
  - [x] Add `skillarsRole: SkillarsRole` and `verificationStatus: SkillarsVerificationStatus` fields to `User.java` in `platform.security.repo` — `@Enumerated(EnumType.STRING)`, `@Column(name = "skillars_role")` and `@Column(name = "verification_status")`
  - [x] Add `ROLE_COACH = "ROLE_COACH"` and `ROLE_PARENT = "ROLE_PARENT"` constants to `SecurityConstants.java`

- [x] Task 3: New JPA entities for token tables (AC: 4, 5)
  - [x] Create `EmailVerificationToken.java` entity in `platform.security.repo` — extends `BaseEntity`, fields: `userId (BIGINT)`, `token (UUID)`, `expiresAt (Instant)`, `used (boolean)`. Table: `email_verification_tokens`.
  - [x] Create `EmailVerificationTokenRepository.java` — `Optional<EmailVerificationToken> findByToken(UUID token)`, `void deleteByUserIdAndUsedFalse(Long userId)`
  - [x] Create `PhoneOtpToken.java` entity in `platform.security.repo` — extends `BaseEntity`, fields: `userId (BIGINT)`, `otpHash (String)`, `expiresAt (Instant)`, `used (boolean)`. Table: `phone_otp_tokens`.
  - [x] Create `PhoneOtpTokenRepository.java` — `Optional<PhoneOtpToken> findFirstByUserIdAndUsedFalseOrderByExpiresAtDesc(Long userId)`, `void deleteByUserIdAndUsedFalse(Long userId)`

- [x] Task 4: Create `infrastructure.ses` module (AC: 2)
  - [x] Add `software.amazon.awssdk:sesv2` to `pom.xml` (BOM already manages version: `software.amazon.awssdk:bom:2.28.29`)
  - [x] Create `infrastructure.ses` package under `src/main/java/com/softropic/skillars/infrastructure/`
  - [x] Create `SesProperties.java` — `@ConfigurationProperties(prefix = "app.ses")`, fields: `fromAddress (String)`, `region (String, default: eu-west-1)`, `enabled (boolean, default: true)`
  - [x] Create `SesConfig.java` — `@Configuration`, conditionally creates `SesV2Client` bean with `Region.of(props.getRegion())`. If `app.ses.enabled = false` (dev/test), creates a `NoOpSesEmailService` instead.
  - [x] Create `SesEmailService.java` interface — `void send(String toAddress, String subject, String htmlBody)`
  - [x] Create `SesEmailServiceImpl.java` — `@Service @ConditionalOnProperty(name="app.ses.enabled", havingValue="true", matchIfMissing=true)`, calls `SesV2Client.sendEmail(...)`. Zero rendering logic here — caller passes complete HTML body.
  - [x] Create `NoOpSesEmailService.java` — `@Service @ConditionalOnProperty(name="app.ses.enabled", havingValue="false")`, logs but does not send. Prevents dev environment from sending real emails.
  - [x] Create `SesException.java` in `infrastructure.ses.exception` — extends `RuntimeException`
  - [x] Add `app.ses.from-address: noreply@skillars.com` and `app.ses.enabled: false` to `application-dev.yml`
  - [x] Add `app.ses.from-address: noreply@skillars.com` and `app.ses.enabled: true` to `application-prod.yml`

- [x] Task 5: Create `infrastructure.sanitizer` module (AC: 9)
  - [x] Create `infrastructure.sanitizer` package
  - [x] Create `ContactDetailSanitizer.java` — `@Component`, stateless. Two compiled `Pattern` fields (email + phone regex). Method `sanitize(String input): SanitizerResult` where `SanitizerResult` is a record `(String sanitized, boolean wasModified)`. Phone pattern must cover international formats (`+XX...`). Redaction placeholder: `[contact details removed]`.
  - [x] Create `SanitizerConfig.java` — `@Configuration` that defines the component scan (or leave as auto-detected `@Component`)

- [x] Task 6: Create `CoachRegistrationService` (AC: 1, 2, 4, 5, 6, 7, 9)
  - [x] Create `CoachRegistrationService.java` in `platform.security.service`:
    - `registerCoach(CoachRegistrationRequest req)`: validate no duplicate email (throw `CoachRegistrationException` with code `security.emailInUse` on duplicate); encode password; create `User` with `skillarsRole = COACH`, `verificationStatus = UNVERIFIED`, `activated = false`, authority `ROLE_COACH`; apply `ContactDetailSanitizer` to name fields; save; generate UUID email token, save `EmailVerificationToken` with 24h expiry; call `SesEmailService.send(...)` with verification link; return `CoachRegistrationResult`.
    - `verifyEmail(UUID token)`: find `EmailVerificationToken`; validate not expired and not used; load User; set `verificationStatus = EMAIL_VERIFIED`, `activated = true`; mark token used; generate 6-digit OTP, hash it (SHA-256), save `PhoneOtpToken` with 10-min expiry; send OTP via SES (or SMS in future — use SES for now with email-based OTP since SMS is out of scope); return `{ userId }`.
    - `verifyPhone(Long userId, String otp)`: find active `PhoneOtpToken` for user; hash incoming OTP, compare; if match and not expired: set `verificationStatus = BASIC_VERIFIED`; mark token used; return.
    - `resendVerificationEmail(String email)`: find user by email; if not found or already email-verified, silently return (no enumeration); invalidate old email tokens; issue new token; send email.
  - [x] Create `CoachRegistrationRequest.java` record in `platform.security.contract`: `@NotBlank String firstName`, `@NotBlank String lastName`, `@Email @NotBlank String email`, `@NotBlank @Size(min=8) String password`, `@NotBlank String phone`
  - [x] Create `CoachRegistrationException.java` in `platform.security.contract.exception` — extends `RuntimeException` with `String errorCode` field
  - [x] Map `CoachRegistrationException` in `ApiAdvice.java` → 409 Conflict with `ErrorDto`

- [x] Task 7: Create `CoachRegistrationResource` (AC: 1, 4, 5, 6, 7, 10)
  - [x] Create `CoachRegistrationResource.java` in `platform.security.api`:
    - `POST /api/security/coach/register` — public (no `@PreAuthorize`); `@RequestBody @Valid CoachRegistrationRequest`; calls service; returns `201 Created`
    - `GET /api/security/coach/verify-email` — public; `@RequestParam UUID token`; calls service; returns 200 or 400
    - `POST /api/security/coach/verify-phone` — public; `@RequestBody VerifyPhoneRequest`; calls service; returns 200
    - `POST /api/security/coach/resend-verification` — public; `@RequestBody ResendVerificationRequest`; always 200
  - [x] Add `@Observed(name = "security.coach_registration")` to class
  - [x] Create `VerifyPhoneRequest.java` record: `@NotNull Long userId`, `@NotBlank @Size(min=6, max=6) String otp`
  - [x] Create `ResendVerificationRequest.java` record: `@Email @NotBlank String email`
  - [x] Whitelist all four endpoints in `SecurityConfiguration.java` under `permitAll()`:
    ```
    .requestMatchers("/api/security/coach/register", "/api/security/coach/verify-email",
                     "/api/security/coach/verify-phone", "/api/security/coach/resend-verification")
    .permitAll()
    ```

- [x] Task 8: Create `SanitizePreviewResource` (AC: 9 — frontend real-time check)
  - [x] Create `SanitizePreviewResource.java` in `platform.security.api` at `POST /api/util/sanitize-preview` — authenticated (`@PreAuthorize(HAS_ANY_ROLE)`) or public — accepts `{ text: String }`, returns `{ sanitized: String, wasModified: boolean }`. Calls `ContactDetailSanitizer.sanitize(text)`.

- [x] Task 9: Frontend — `CoachRegisterPage.vue` (AC: 1, 3, 7, 9)
  - [x] Create `src/frontend/src/pages/auth/CoachRegisterPage.vue`:
    - Fields: `firstName`, `lastName`, `email` (type email), `password` (toggle visibility), `phone` (type tel)
    - Two `q-checkbox` for ToS and Privacy Policy — `v-model` bound to separate refs, NOT pre-checked
    - Submit `q-btn` has `:disable="!tosAccepted || !privacyAccepted || isSubmitting"` — disabled until both checked
    - Duplicate email (409): show inline error with "Sign in instead" `router-link` to `/login`
    - On success: `router.push('/coach/email-pending')`
    - All text via `t(...)` — i18n keys under `auth.coach.*`
    - No hardcoded hex. Uses `.glass-card--static`, `.btn-accent`, token-only colours.
    - Contact detail detection: apply `useContactDetector` composable (see below) to `firstName` and `lastName` inputs
  - [x] Add route to `routes.js`: `{ path: 'coach-register', component: () => import('pages/auth/CoachRegisterPage.vue'), meta: { requiresGuest: true } }`

- [x] Task 10: Frontend — `CoachEmailPendingPage.vue` (intermediate step)
  - [x] Create `src/frontend/src/pages/auth/CoachEmailPendingPage.vue`: glass card, tells user to check their email. Includes "Resend email" button (calls `POST /api/security/coach/resend-verification`, 60s cooldown).
  - [x] Add route: `{ path: 'coach/email-pending', component: () => import('pages/auth/CoachEmailPendingPage.vue'), meta: { requiresGuest: true } }`

- [x] Task 11: Frontend — `CoachEmailVerifyPage.vue` (email link landing) (AC: 4)
  - [x] Create `src/frontend/src/pages/auth/CoachEmailVerifyPage.vue`: on `onMounted`, reads `route.query.token`, calls `GET /api/security/coach/verify-email?token=...`. On success: `router.push({ path: '/coach/verify-phone', query: { userId: result.userId } })`. On error (expired/used): show error + "Resend verification email" option.
  - [x] Add route: `{ path: 'coach/verify-email', component: () => import('pages/auth/CoachEmailVerifyPage.vue'), meta: { requiresGuest: true } }`

- [x] Task 12: Frontend — `CoachPhoneVerifyPage.vue` (AC: 5)
  - [x] Create `src/frontend/src/pages/auth/CoachPhoneVerifyPage.vue`: reuse OTP digit pattern from `OtpPage.vue` (6-digit, auto-advance on input, auto-submit on 6th digit, backspace navigates left). On success (`BASIC_VERIFIED`): `router.push('/coach/profile-builder')` (placeholder route — will render an under-construction page until Story 2.1). On error: inline error below OTP row, clear digits, refocus first input.
  - [x] Add route: `{ path: 'coach/verify-phone', component: () => import('pages/auth/CoachPhoneVerifyPage.vue'), meta: { requiresGuest: true } }`

- [x] Task 13: Frontend — `useContactDetector.js` composable (AC: 9)
  - [x] Create `src/frontend/src/composables/useContactDetector.js`: accepts a `ref<string>` and returns `hasContactDetail: ComputedRef<boolean>`. Runs regex check on `watch`. Pattern must cover email (`/[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/`) and phone (`/(?:\+?[\d\s\-().]{7,15})/`). Used as: amber `q-banner` inside the field, conditionally rendered with `v-if="hasContactDetail"`.

- [x] Task 14: Frontend — API file + i18n keys (AC: 1, 3–7)
  - [x] Create `src/frontend/src/api/coachRegistration.api.js` with:
    ```js
    register(data)     → POST /api/security/coach/register
    verifyEmail(token) → GET  /api/security/coach/verify-email?token=
    verifyPhone(data)  → POST /api/security/coach/verify-phone
    resendVerification(email) → POST /api/security/coach/resend-verification
    ```
  - [x] Add `auth.coach.*` keys to `src/frontend/src/i18n/en/index.js` AND `de/index.js` simultaneously (parity rule from 1.2):
    ```js
    auth: {
      coach: {
        registerTitle: 'Create Coach Account',
        registerSubtitle: 'Join Skillars and start coaching',
        emailPendingTitle: 'Check your email',
        emailPendingBody: 'We sent a verification link to {{email}}. Click it to continue.',
        resendEmail: 'Resend email',
        resendCooldown: 'Resend available in {{seconds}}s',
        phoneVerifyTitle: 'Verify your phone',
        phoneVerifySubtitle: 'Enter the 6-digit code sent to your phone',
        tosLabel: 'I accept the Terms of Service',
        privacyLabel: 'I accept the Privacy Policy',
        signInInstead: 'Sign in instead',
        emailInUse: 'This email is already registered.',
        contactDetailWarning: 'Contact details will be removed on save',
      }
    }
    ```

- [x] Task 15: Test — `CoachRegistrationResourceIT` (AC: 1, 4, 5, 6, 7)
  - [x] Create `src/test/java/com/softropic/skillars/platform/security/api/CoachRegistrationResourceIT.java` — `@SpringBootTest` + `@Testcontainers` (real PostgreSQL). Use `ApplicationNoSecurity` config for security-disabled variant where needed, OR whitelist the endpoints.
  - [x] Test cases:
    - `registerCoach_validData_returns201AndUserIsUnverified`
    - `registerCoach_duplicateEmail_returns409`
    - `registerCoach_missingRequiredField_returns400`
    - `verifyEmail_validToken_setsEmailVerifiedAndReturnsUserId`
    - `verifyEmail_expiredToken_returns400WithCanResend`
    - `verifyEmail_usedToken_returns400`
    - `verifyPhone_correctOtp_setsBasicVerified`
    - `verifyPhone_wrongOtp_returns400`
    - `resendVerification_alwaysReturns200_noAccountEnumeration`
  - [x] Use Instancio for test data generation, AssertJ for assertions

- [x] Task 16: langKey propagation — capture UI-selected language at registration, store on User, render coach emails via Thymeleaf in user's language (AC: 2, 5)

  **Why this is needed**: `CoachRegistrationService` hardcodes `user.setLangKey("en")` (line 86), so all coach emails are permanently English. `CoachRegistrationEmailListener` also builds raw HTML strings instead of using the project's Thymeleaf + `MessageSource` template system, so there is no path for locale-sensitive rendering even if `langKey` were set correctly.

  **Reference pattern**: `EmailRegistrationStrategy` → `Recipient.setLangKey(user.getLangKey())` → `MailService.sendEmailFromTemplate()` → `Locale.forLanguageTag(recipient.getLangKey())` → `SpringTemplateEngine.process(templateName, context)` + `MessageSource.getMessage(subjectKey, null, locale)`. Coach email delivery stays on `SesEmailService`; only the rendering layer aligns with this pattern.

  - [x] **`CoachRegistrationRequest.java`** — add `@Size(min = 2, max = 5) String langKey` as an optional record component (nullable — service defaults to `"en"` when absent). No `@NotBlank`; this keeps the contract backward-compatible if older clients omit the field.

  - [x] **`CoachRegisterPage.vue`** — destructure `locale` from the existing `useI18n()` call and extract the ISO 2-letter prefix at submit time. `locale` is the reactive ref that updates when the user switches languages in the UI — no extra import needed:
    ```js
    const { t, locale } = useI18n()
    // in handleRegister:
    const langKey = locale.value.split('-')[0]  // 'en-US' → 'en', 'de' → 'de'
    ```
    Include `langKey` in the payload object passed to `coachRegistration.api.js`.

  - [x] **`coachRegistration.api.js`** — no structural change; confirm `register(data)` passes `data` as the request body as-is, so `langKey` flows through automatically once added to the payload in the page.

  - [x] **`CoachRegistrationService.registerCoach()`** — replace:
    ```java
    user.setLangKey("en");
    ```
    with:
    ```java
    user.setLangKey(req.langKey() != null && !req.langKey().isBlank() ? req.langKey() : "en");
    ```

  - [x] **`CoachVerificationEmailEvent.java`** — extend the record to carry `langKey` and `firstName`:
    ```java
    public record CoachVerificationEmailEvent(String toAddress, String verifyUrl, String langKey, String firstName) {}
    ```

  - [x] **`CoachOtpEmailEvent.java`** — extend the record to carry `langKey` and `firstName`:
    ```java
    public record CoachOtpEmailEvent(String toAddress, String otp, String langKey, String firstName) {}
    ```

  - [x] **`CoachRegistrationService.sendVerificationEmail(User user)`** — update `publishEvent` call to pass the new fields:
    ```java
    publisher.publishEvent(new CoachVerificationEmailEvent(
        user.getEmail(), verifyUrl, user.getLangKey(), user.getFirstName()));
    ```

  - [x] **`CoachRegistrationService.sendOtpEmail()`** — change signature from `sendOtpEmail(String email, String otp)` to `sendOtpEmail(User user, String otp)` and update the `verifyEmail()` call site accordingly:
    ```java
    publisher.publishEvent(new CoachOtpEmailEvent(
        user.getEmail(), otp, user.getLangKey(), user.getFirstName()));
    ```

  - [x] **`EmailTemplate.java`** — add two new enum entries:
    ```java
    COACH_EMAIL_VERIFY("email.coach.verify.title"),
    COACH_OTP("email.coach.otp.title"),
    ```
    Template name resolution follows the existing `CaseFormat.UPPER_UNDERSCORE → LOWER_CAMEL` convention in `MailService`:
    - `COACH_EMAIL_VERIFY` → `coachEmailVerify` → `mails/coachEmailVerify.html`
    - `COACH_OTP` → `coachOtp` → `mails/coachOtp.html`

  - [x] **Create `src/main/resources/mails/coachEmailVerify.html`** — Thymeleaf template for the email-verification link email. Variables available: `recipient.firstname`, `map.verifyUrl`. Template keys needed: `email.coach.verify.title`, `email.coach.verify.text1`, `email.coach.verify.linkText`, `email.coach.verify.expiry`, `email.coach.verify.ignore`. Model after `activation.html` — same `th:text="#{...}"` structure, same `email.greeting(${recipient.firstname})` greeting key.

  - [x] **Create `src/main/resources/mails/coachOtp.html`** — Thymeleaf template for the phone OTP email. Variables: `recipient.firstname`, `map.otpCode`. Template keys: `email.coach.otp.title`, `email.coach.otp.intro`, `email.coach.otp.expiry`, `email.coach.otp.ignore`. Model after `sendOtp.html` (OTP displayed in `<h2>` with letter-spacing).

  - [x] **`src/main/resources/i18n/messages_en.properties`** — add the following keys:
    ```properties
    # Coach registration emails
    email.coach.verify.title=Verify your Skillars account
    email.coach.verify.text1=Thank you for registering. Click the link below to verify your email address.
    email.coach.verify.linkText=Verify my email
    email.coach.verify.expiry=This link expires in 24 hours.
    email.coach.verify.ignore=If you did not create a Skillars coach account, please ignore this email.
    email.coach.otp.title=Your Skillars phone verification code
    email.coach.otp.intro=Your phone verification code is:
    email.coach.otp.expiry=This code expires in 10 minutes.
    email.coach.otp.ignore=If you did not request this code, please ignore this email and ensure your account is secure.
    ```

  - [x] **Create `src/main/resources/i18n/messages_de.properties`** — new file with German translations of all keys currently in `messages_en.properties` that the coach registration flow uses, plus the new coach keys above. The frontend ships a `de` locale (`src/frontend/src/i18n/de/index.js`) but the backend has no `messages_de.properties`; Spring's `MessageSource` currently falls back silently to English. Minimum required keys for this story:
    ```properties
    email.greeting=Hallo {0}
    email.common.helpCode=Hilfe-Code:
    email.coach.verify.title=Skillars-Konto verifizieren
    email.coach.verify.text1=Vielen Dank für Ihre Registrierung. Klicken Sie auf den folgenden Link, um Ihre E-Mail-Adresse zu bestätigen.
    email.coach.verify.linkText=E-Mail bestätigen
    email.coach.verify.expiry=Dieser Link läuft in 24 Stunden ab.
    email.coach.verify.ignore=Wenn Sie kein Skillars-Coach-Konto erstellt haben, ignorieren Sie diese E-Mail.
    email.coach.otp.title=Ihr Skillars-Telefon-Verifizierungscode
    email.coach.otp.intro=Ihr Telefon-Verifizierungscode lautet:
    email.coach.otp.expiry=Dieser Code läuft in 10 Minuten ab.
    email.coach.otp.ignore=Wenn Sie diesen Code nicht angefordert haben, ignorieren Sie diese E-Mail und stellen Sie sicher, dass Ihr Konto sicher ist.
    ```

  - [x] **`CoachRegistrationEmailListener.java`** — replace raw-HTML string construction with Thymeleaf rendering. Inject `SpringTemplateEngine templateEngine` and `MessageSource messageSource`. The delivery mechanism (`SesEmailService`) stays unchanged. New implementation pattern:
    ```java
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerificationEmail(CoachVerificationEmailEvent event) {
        Locale locale = Locale.forLanguageTag(event.langKey());
        Recipient recipient = new Recipient();
        recipient.setFirstname(event.firstName());
        recipient.setLangKey(event.langKey());
        Context context = new Context(locale);
        context.setVariable("recipient", recipient);
        context.setVariable("map", Map.of("verifyUrl", event.verifyUrl()));
        String html = templateEngine.process("coachEmailVerify", context);
        String subject = messageSource.getMessage(EmailTemplate.COACH_EMAIL_VERIFY.subjectKey(), null, locale);
        sesEmailService.send(event.toAddress(), subject, html);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOtpEmail(CoachOtpEmailEvent event) {
        Locale locale = Locale.forLanguageTag(event.langKey());
        Recipient recipient = new Recipient();
        recipient.setFirstname(event.firstName());
        recipient.setLangKey(event.langKey());
        Context context = new Context(locale);
        context.setVariable("recipient", recipient);
        context.setVariable("map", Map.of("otpCode", event.otp()));
        String html = templateEngine.process("coachOtp", context);
        String subject = messageSource.getMessage(EmailTemplate.COACH_OTP.subjectKey(), null, locale);
        sesEmailService.send(event.toAddress(), subject, html);
    }
    ```
    Note: `SpringTemplateEngine` and `MessageSource` are already wired as Spring beans by `ThymeleafConfiguration` and `application.yaml` respectively — no new config needed.

  - [x] **`CoachRegistrationResourceIT.java`** — update the happy-path test case to include `"langKey": "de"` in the POST body; after registration assert that `userRepository.findOneByEmail(email).getLangKey()` equals `"de"`. Add a second case `registerCoach_noLangKey_defaultsToEn` that omits the field and asserts `langKey` stored as `"en"`.

## Dev Notes

### ⚠️ CRITICAL — Existing Security Module Structure

Do NOT rewrite or replace any existing `platform.security` classes. This story EXTENDS the module:
- `User.java`: ADD two fields only — `skillarsRole` and `verificationStatus`. Do not alter existing fields or JPA mappings.
- `AccountResource.java` at `/v1/account`: This is the EXISTING generic registration endpoint. Do NOT modify it. Create a NEW `CoachRegistrationResource.java` at `/api/security/coach`.
- `UserRegistrationService.java`: Do NOT modify. Create a NEW `CoachRegistrationService.java`.
- `SecurityConfiguration.java`: ADD the four new public endpoints to `permitAll()` list only.
- `ApiAdvice.java`: ADD `@ExceptionHandler(CoachRegistrationException.class)` only.
- `SecurityConstants.java`: ADD `ROLE_COACH` and `ROLE_PARENT` constants only.

### Database — Flyway V21 DDL

```sql
-- V21__skillars_security_extension.sql

ALTER TABLE "user"
    ADD COLUMN IF NOT EXISTS skillars_role     VARCHAR(20),
    ADD COLUMN IF NOT EXISTS verification_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED';

CREATE TABLE IF NOT EXISTS email_verification_tokens (
    id          BIGINT       PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    token       UUID         NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_evt_token  ON email_verification_tokens(token);
CREATE INDEX IF NOT EXISTS idx_evt_userid ON email_verification_tokens(user_id);

CREATE TABLE IF NOT EXISTS phone_otp_tokens (
    id          BIGINT       PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    otp_hash    VARCHAR(64)  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pot_userid ON phone_otp_tokens(user_id);

-- Seed new Skillars roles into authority table
-- Use explicit IDs that don't conflict with existing rows
INSERT INTO authority (id, name, status, created_by, created_date)
VALUES
    (100, 'ROLE_COACH',  'ACTIVE', 'system', NOW()),
    (101, 'ROLE_PARENT', 'ACTIVE', 'system', NOW())
ON CONFLICT (name) DO NOTHING;
```

**Note on IDs**: The `authority` table uses BIGINT PK with no sequence — IDs 100 and 101 are safe assuming existing rows use low IDs (1, 2, 3). Verify the highest existing authority ID before choosing these values.

### Email Verification Link Format

The email sent by `SesEmailService` must contain a link in this form:
```
https://{app.frontend-url}/coach/verify-email?token={UUID}
```
Add `app.frontend-url: http://localhost:9000` to `application-dev.yml`. This property is needed by `CoachRegistrationService` to build the verification URL (inject via `@Value("${app.frontend-url}")`).

### Phone OTP via SES (not SMS)

The architecture mentions "Phone OTP via temporary token in Redis or DB (short TTL)". Redis is NOT yet in this codebase. Use the DB `phone_otp_tokens` table. The OTP is sent as an email (not SMS) in this story — a real SMS gateway (Twilio/AWS SNS) is deferred to a later story. The email subject must clearly state "Your Skillars phone verification code: XXXXXX."

To avoid revealing the 6-digit OTP in the DB, store `SHA-256(otp + userId)` as the hash. Verification: compute SHA-256 of the incoming OTP + userId and compare.

### Infrastructure.ses — No Rendering Logic

`SesEmailService` accepts a pre-rendered HTML string. Rendering/templating happens in `CoachRegistrationService` (domain layer), not in the infrastructure adapter. Example implementation:
```java
String html = "<html><body>Click <a href='" + verifyUrl + "'>here</a> to verify your email.</body></html>";
sesEmailService.send(coachEmail, "Verify your Skillars account", html);
```

### User Entity: No UUID PKs

The `User` entity and all new token entities use `BIGINT` primary keys — NOT UUID. The `email_verification_tokens.token` is a UUID field (used as the secret, not as the PK). The `EmailVerificationToken.java` entity's `id` field is BIGINT (inherits from `BaseEntity`). Check `BaseEntity.java` — it uses `@GeneratedValue`. Do NOT use UUID as entity PK.

### No Existing Infrastructure.ses or Infrastructure.sanitizer

Both are NEW packages created by this story. They do not exist yet. Reference implementation to follow:
- `infrastructure.blobstore` (existing S3 adapter) — same structure pattern: interface + impl + config + properties + exception
- Zero business logic in either adapter

### AWS SesV2Client — Key Pattern

```java
// SesEmailServiceImpl.java
@RequiredArgsConstructor
@Service
public class SesEmailServiceImpl implements SesEmailService {
    private final SesV2Client sesV2Client;
    private final SesProperties props;
    
    @Override
    public void send(String to, String subject, String htmlBody) {
        try {
            sesV2Client.sendEmail(r -> r
                .fromEmailAddress(props.getFromAddress())
                .destination(d -> d.toAddresses(to))
                .content(c -> c.simple(m -> m
                    .subject(s -> s.data(subject))
                    .body(b -> b.html(h -> h.data(htmlBody))))));
        } catch (SesV2Exception ex) {
            throw new SesException("Failed to send email to " + to, ex);
        }
    }
}
```

### ContactDetailSanitizer Pattern

```java
@Component
public class ContactDetailSanitizer {
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE_PATTERN =
        Pattern.compile("(?:\\+?[\\d][\\d\\s\\-().]{6,14}[\\d])");
    private static final String REDACTION = "[contact details removed]";

    public SanitizerResult sanitize(String input) {
        if (input == null) return new SanitizerResult(null, false);
        String result = EMAIL_PATTERN.matcher(input).replaceAll(REDACTION);
        result = PHONE_PATTERN.matcher(result).replaceAll(REDACTION);
        return new SanitizerResult(result, !result.equals(input));
    }

    public record SanitizerResult(String sanitized, boolean wasModified) {}
}
```

### Frontend: OTP Auto-Advance Pattern

Reuse the exact pattern from the existing `OtpPage.vue` (`onDigitInput`, `onKeyDown`, `onPaste` handlers). Do NOT reinvent. The `CoachPhoneVerifyPage.vue` should copy those handlers verbatim and reference the same scoped styles.

### Frontend: No Hardcoded Colours

Zero hardcoded hex in `.vue` or `.scss` files. Every colour must reference a CSS custom property token from `src/frontend/src/css/tokens/_colors.scss`. The contact-detail amber warning bar must use `var(--accent-warning)`.

### Frontend: Route `/coach/profile-builder` Placeholder

Story 2.1 implements the full profile builder. After `BASIC_VERIFIED`, the coach must be redirected somewhere. Create a minimal `CoachProfileBuilderPlaceholderPage.vue` (or reuse `DashboardPage.vue` temporarily). The route is `coach/profile-builder` and must be behind `requiresAuth: true`.

### Frontend: `useContactDetector.js` Reactive Pattern

```js
// src/composables/useContactDetector.js
import { computed } from 'vue'

const EMAIL_RE = /[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}/
const PHONE_RE = /(?:\+?[\d][\d\s\-().]{6,14}[\d])/

export function useContactDetector(valueRef) {
  const hasContactDetail = computed(() =>
    !!(valueRef.value && (EMAIL_RE.test(valueRef.value) || PHONE_RE.test(valueRef.value)))
  )
  return { hasContactDetail }
}
```

Apply in `CoachRegisterPage.vue` on `firstName` and `lastName` inputs.

### SecurityConfiguration Whitelist — Exact Location

Find the `permitAll()` chain in `SecurityConfiguration.java` and add the four new coach registration endpoints alongside any existing public paths.

### i18n Parity Rule (from Story 1.2)

Any key added to `src/frontend/src/i18n/en/index.js` must be simultaneously added to `de/index.js`. This is a **hard rule** — no key exists in one file but not the other.

### Verification Commands (Post-Implementation)

```bash
# Backend — confirm SkillarsRole and SkillarsVerificationStatus columns added to user table
grep -rn "skillarsRole\|verificationStatus" src/main/java/com/softropic/skillars/platform/security/repo/User.java

# Backend — confirm SES conditional bean
grep -n "ConditionalOnProperty" src/main/java/com/softropic/skillars/infrastructure/ses/*.java

# Frontend — no hardcoded hex in new files
grep -rn '#[0-9a-fA-F]\{3,6\}' src/frontend/src/pages/auth/Coach*.vue

# Frontend — i18n key parity
node -e "const en=require('./src/frontend/src/i18n/en/index.js').default; const de=require('./src/frontend/src/i18n/de/index.js').default; const enK=JSON.stringify(Object.keys(en.auth.coach).sort()); const deK=JSON.stringify(Object.keys(de.auth.coach).sort()); console.log(enK===deK ? 'PARITY OK' : 'MISMATCH: en='+enK+' de='+deK)"
```

### Previous Story (1.2) — What Was Created/Modified

No backend changes in 1.2. Frontend-only. The following are safe to build on:
- `src/frontend/src/css/tokens/_colors.scss` — all CSS token definitions
- `src/frontend/src/boot/theme.js` — `toggleTheme()` and `isDarkMode()` exported
- `src/frontend/src/i18n/en/index.js` and `de/index.js` — both exist with `auth: {}` namespace stub (currently empty — this story fills it)
- Existing `OtpPage.vue`, `ActivatePage.vue`, `RegisterPage.vue` — DO NOT modify; create new Coach-specific pages

### Project Structure Notes

New backend files live in:
```
src/main/java/com/softropic/skillars/
├── infrastructure/
│   ├── ses/
│   │   ├── SesEmailService.java          ← interface
│   │   ├── SesEmailServiceImpl.java      ← SesV2Client impl
│   │   ├── NoOpSesEmailService.java      ← dev/test no-op
│   │   ├── SesProperties.java
│   │   ├── SesConfig.java
│   │   └── exception/SesException.java
│   └── sanitizer/
│       ├── ContactDetailSanitizer.java
│       └── SanitizerConfig.java
└── platform/security/
    ├── api/
    │   ├── CoachRegistrationResource.java
    │   ├── SanitizePreviewResource.java
    │   └── dto/ (VerifyPhoneRequest.java, ResendVerificationRequest.java)
    ├── contract/
    │   ├── SkillarsRole.java
    │   ├── SkillarsVerificationStatus.java
    │   ├── CoachRegistrationRequest.java
    │   └── exception/CoachRegistrationException.java
    ├── repo/
    │   ├── EmailVerificationToken.java
    │   ├── EmailVerificationTokenRepository.java
    │   ├── PhoneOtpToken.java
    │   └── PhoneOtpTokenRepository.java
    └── service/
        └── CoachRegistrationService.java
```

New frontend files:
```
src/frontend/src/
├── api/coachRegistration.api.js
├── composables/useContactDetector.js
├── pages/auth/
│   ├── CoachRegisterPage.vue
│   ├── CoachEmailPendingPage.vue
│   ├── CoachEmailVerifyPage.vue
│   └── CoachPhoneVerifyPage.vue
└── i18n/en/index.js  (update auth.coach.* namespace)
    i18n/de/index.js  (same keys, German translations)
```

Modified files:
- `src/main/resources/db/migration/V21__skillars_security_extension.sql` (new)
- `src/main/java/com/softropic/skillars/platform/security/repo/User.java` (add 2 fields)
- `src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java` (add 2 constants)
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java` (add exception handler)
- `src/main/java/com/softropic/skillars/platform/security/config/SecurityConfiguration.java` (add permitAll paths)
- `src/main/resources/application-dev.yml` (add app.ses.*, app.frontend-url)
- `src/main/resources/application-prod.yml` (add app.ses.*, app.frontend-url)
- `pom.xml` (add `software.amazon.awssdk:sesv2` dependency)
- `src/frontend/src/router/routes.js` (add 5 new routes)
- `src/frontend/src/i18n/en/index.js` (add auth.coach.*)
- `src/frontend/src/i18n/de/index.js` (add auth.coach.* German)

**No changes to**:
- `UserRegistrationService.java`
- `AccountResource.java`
- `RegisterPage.vue`, `OtpPage.vue`, `ActivatePage.vue`
- Any existing migration file

### References

- [Source: skillars-epics.md#Story 1.3] — full AC and dev notes
- [Source: architecture.md#infrastructure.ses] — SesEmailService, SesV2Client, zero rendering logic, eu-west-1
- [Source: architecture.md#infrastructure.sanitizer] — ContactDetailSanitizer, regex-based
- [Source: architecture.md#Contact Detail Sanitization] — POST /api/util/sanitize-preview frontend endpoint
- [Source: architecture.md#Authentication & Security] — JWT, role-based access, HttpOnly cookies
- [Source: project-context.md#Critical Implementation Rules] — @PreAuthorize required on all resource methods, ResourceSuffix, record DTOs, MapStruct
- [Source: project-context.md#Module Internal Structure] — api/service/repo/contract/config layer structure
- [Source: project-context.md#Violation Checklist] — infrastructure must contain zero platform imports
- [Source: ux-design-specification.md#Form Patterns] — OTP auto-advance, consent checkboxes never pre-checked
- [Source: ux-design-specification.md#Contact detail detection] — amber warning bar pattern
- [Source: src/main/java/.../platform/security/repo/User.java] — existing entity fields, activationKey, activated boolean
- [Source: src/main/java/.../platform/security/service/UserRegistrationService.java] — existing registration pattern, DO NOT modify
- [Source: src/main/java/.../platform/security/api/AccountResource.java] — existing /v1/account endpoint, DO NOT modify
- [Source: src/main/java/.../infrastructure/security/SecurityConstants.java] — existing role constants pattern
- [Source: src/main/resources/db/migration/V10__security_schema.sql] — existing user table DDL, BIGINT PKs
- [Source: src/frontend/src/pages/auth/OtpPage.vue] — OTP digit/auto-advance pattern to reuse exactly
- [Source: src/frontend/src/pages/auth/RegisterPage.vue] — existing generic registration UI pattern
- [Source: src/frontend/src/i18n/en/index.js] — auth namespace stub location to fill

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- Resolved: `iso2_country NOT NULL` — used `new PhoneNumber(req.phone(), "XX")` placeholder; Story 2.1 (profile builder) collects real country code
- Resolved: `gender`/`dateOfBirth NOT NULL` — used `Gender.OTHER` and `LocalDate.of(1900,1,1)` as safe placeholders
- Resolved: token entities must extend `BaseEntity` (not `AbstractAuditingEntity`) — token tables have no status/audit columns
- Resolved: HTTP 200 (not 201) for register — AC1 is authoritative; task 7 said 201 which contradicts AC1
- Resolved: `canResend` flag in error response — added `TokenErrorResponse` record to `ApiAdvice`; frontend reads `err.response?.data?.canResend`
- Resolved: SES conditional bean — `SesV2Client` only created when `app.ses.enabled=true`; `NoOpSesEmailService` used in dev/test

### Completion Notes List

- All 15 tasks implemented in a single pass per the story spec
- Flyway V21 migration adds `skillars_role`, `verification_status` to `user` table; creates `email_verification_tokens` and `phone_otp_tokens` tables; seeds ROLE_COACH (id=100) and ROLE_PARENT (id=101)
- OTP hashed as SHA-256(otp + userId) before storage; OTP sent via SES email (SMS deferred to later story)
- `ContactDetailSanitizer` strips email and phone patterns from free-text fields; frontend shows amber warning banner via `useContactDetector` composable while typing
- All four coach registration endpoints are whitelisted in `AppEndpoints.PUBLIC_ENDPOINTS`
- Frontend: ToS and Privacy checkboxes not pre-checked; submit disabled until both checked; i18n parity maintained across en/de
- Integration test (`CoachRegistrationResourceIT`) covers all 9 specified test cases using real PostgreSQL via Testcontainers
- `CoachProfileBuilderPlaceholderPage.vue` created as minimal placeholder for post-BASIC_VERIFIED redirect (full profile builder in Story 2.1)

### File List

**New — Backend**
- `src/main/resources/db/migration/V21__skillars_security_extension.sql`
- `src/main/java/com/softropic/skillars/platform/security/contract/SkillarsRole.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/SkillarsVerificationStatus.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/CoachRegistrationRequest.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/exception/CoachRegistrationException.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/exception/EmailTokenException.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/exception/OtpVerificationException.java`
- `src/main/java/com/softropic/skillars/platform/security/repo/EmailVerificationToken.java`
- `src/main/java/com/softropic/skillars/platform/security/repo/EmailVerificationTokenRepository.java`
- `src/main/java/com/softropic/skillars/platform/security/repo/PhoneOtpToken.java`
- `src/main/java/com/softropic/skillars/platform/security/repo/PhoneOtpTokenRepository.java`
- `src/main/java/com/softropic/skillars/platform/security/service/CoachRegistrationService.java`
- `src/main/java/com/softropic/skillars/platform/security/api/CoachRegistrationResource.java`
- `src/main/java/com/softropic/skillars/platform/security/api/SanitizePreviewResource.java`
- `src/main/java/com/softropic/skillars/platform/security/api/dto/VerifyPhoneRequest.java`
- `src/main/java/com/softropic/skillars/platform/security/api/dto/ResendVerificationRequest.java`
- `src/main/java/com/softropic/skillars/infrastructure/ses/SesEmailService.java`
- `src/main/java/com/softropic/skillars/infrastructure/ses/SesEmailServiceImpl.java`
- `src/main/java/com/softropic/skillars/infrastructure/ses/NoOpSesEmailService.java`
- `src/main/java/com/softropic/skillars/infrastructure/ses/SesProperties.java`
- `src/main/java/com/softropic/skillars/infrastructure/ses/SesConfig.java`
- `src/main/java/com/softropic/skillars/infrastructure/ses/exception/SesException.java`
- `src/main/java/com/softropic/skillars/infrastructure/sanitizer/ContactDetailSanitizer.java`
- `src/main/java/com/softropic/skillars/infrastructure/sanitizer/SanitizerConfig.java`

**Modified — Backend**
- `src/main/java/com/softropic/skillars/platform/security/repo/User.java` — added `skillarsRole` and `verificationStatus` fields
- `src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java` — added `ROLE_COACH` and `ROLE_PARENT` constants
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java` — added handlers for CoachRegistrationException, EmailTokenException, OtpVerificationException
- `src/main/java/com/softropic/skillars/platform/security/config/AppEndpoints.java` — added four coach registration public endpoints
- `src/main/resources/application-dev.yaml` — added `app.ses.*` and `app.frontend-url`
- `src/main/resources/application-prod.yaml` — added `app.ses.*` and `app.frontend-url`
- `pom.xml` — added `software.amazon.awssdk:sesv2` dependency

**New — Frontend**
- `src/frontend/src/api/coachRegistration.api.js`
- `src/frontend/src/composables/useContactDetector.js`
- `src/frontend/src/pages/auth/CoachRegisterPage.vue`
- `src/frontend/src/pages/auth/CoachEmailPendingPage.vue`
- `src/frontend/src/pages/auth/CoachEmailVerifyPage.vue`
- `src/frontend/src/pages/auth/CoachPhoneVerifyPage.vue`
- `src/frontend/src/pages/auth/CoachProfileBuilderPlaceholderPage.vue`

**Modified — Frontend**
- `src/frontend/src/router/routes.js` — added 5 coach routes
- `src/frontend/src/i18n/en/index.js` — added `auth.coach.*` namespace (13 keys)
- `src/frontend/src/i18n/de/index.js` — added `auth.coach.*` namespace (13 keys, German)

**New — Tests**
- `src/test/java/com/softropic/skillars/platform/security/api/CoachRegistrationResourceIT.java`

**New — Task 16 (langKey / template patch)**
- `src/main/resources/mails/coachEmailVerify.html`
- `src/main/resources/mails/coachOtp.html`
- `src/main/resources/i18n/messages_de.properties`

**Modified — Task 16 (langKey / template patch)**
- `src/main/java/com/softropic/skillars/platform/security/contract/CoachRegistrationRequest.java` — add `langKey` field
- `src/main/java/com/softropic/skillars/platform/security/contract/event/CoachVerificationEmailEvent.java` — add `langKey`, `firstName`
- `src/main/java/com/softropic/skillars/platform/security/contract/event/CoachOtpEmailEvent.java` — add `langKey`, `firstName`
- `src/main/java/com/softropic/skillars/platform/security/service/CoachRegistrationService.java` — use `req.langKey()`, update event construction
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/listener/CoachRegistrationEmailListener.java` — replace raw HTML with Thymeleaf rendering
- `src/main/java/com/softropic/skillars/platform/notification/contract/EmailTemplate.java` — add `COACH_EMAIL_VERIFY`, `COACH_OTP`
- `src/main/resources/i18n/messages_en.properties` — add `email.coach.*` keys
- `src/frontend/src/pages/auth/CoachRegisterPage.vue` — extract and send `langKey`
- `src/test/java/com/softropic/skillars/platform/security/api/CoachRegistrationResourceIT.java` — add langKey test cases

---

### Review Findings

_Code review run: 2026-06-11 | Reviewers: Blind Hunter + Edge Case Hunter + Acceptance Auditor_

#### Decision Needed
_All decisions resolved._
- ~~[Review][Decision] Should `activated=true` be set at email-verification?~~ → **Dismissed** — intentional design; email confirmation is sufficient to activate the account.
- ~~[Review][Decision] Should `EMAIL_VERIFIED` users be able to request a new phone OTP?~~ → **Deferred** — `/resend-verification` handles email verification only; OTP resend deferred to a later story.

#### Patches
- [x] [Review][Patch] **Broken resend — email param never passed in navigation** — `CoachRegisterPage.vue` pushes `/coach/email-pending` with no `?email=` param; `CoachEmailPendingPage.handleResend()` immediately returns if email is empty. Same bug in `CoachEmailVerifyPage.handleResendFromVerify()`. Resend is permanently silent for all users. [`CoachRegisterPage.vue`, `CoachEmailPendingPage.vue`, `CoachEmailVerifyPage.vue`]
- [x] [Review][Patch] **OTP value included in email subject line** — `CoachRegistrationEmailListener.onOtpEmail()` passes `"Your Skillars phone verification code: " + event.otp()` as the subject. OTP appears in SES send logs, email preview notifications, and sent-mail folders. Remove OTP from subject; keep it in the body only. [`CoachRegistrationEmailListener.java`]
- [x] [Review][Patch] **No rate limiting on any of the 4 public registration endpoints** — None of `/register`, `/verify-email`, `/verify-phone`, `/resend-verification` have `@RateLimited`. OTP brute force over 10 min window (1M combinations) and email bomber via resend are both open. [`CoachRegistrationResource.java`]
- [x] [Review][Patch] **Email uniqueness TOCTOU — concurrent registrations throw 500 instead of 409** — Two simultaneous `POST /register` with the same email both pass the `findOneByEmail` guard and one throws an uncaught `DataIntegrityViolationException`. Wrap the `userRepository.save` in a try-catch for `DataIntegrityViolationException` and rethrow as `CoachRegistrationException("security.emailInUse")`. [`CoachRegistrationService.java:registerCoach`]
- [x] [Review][Patch] **Concurrent email token verification — no optimistic lock, double-verification possible** — Two parallel `GET /verify-email?token=X` calls both see `isUsed()==false`, both succeed, and both issue a new OTP token. Add `@Version private Long version` to `EmailVerificationToken` for optimistic locking. [`EmailVerificationToken.java`]
- [x] [Review][Patch] **`@Modifying` queries missing `clearAutomatically=true` — stale first-level cache** — `deleteByUserIdAndUsedFalse` on both repositories runs a bulk delete without clearing the Hibernate session cache. A subsequent load in the same transaction may return the deleted entity. Add `@Modifying(clearAutomatically = true)` to both. [`EmailVerificationTokenRepository.java`, `PhoneOtpTokenRepository.java`]
- [x] [Review][Patch] **Flyway V21 inconsistent schema prefix** — `ALTER TABLE "user"` has no `main.` prefix; `CREATE TABLE IF NOT EXISTS main.email_verification_tokens` does. The FK `REFERENCES "user"(id)` may resolve to the wrong schema. Normalize all statements to use explicit `main.` prefix. [`V21__skillars_security_extension.sql`]
- [x] [Review][Patch] **Hardcoded `rgba(255,180,0,0.12)` in `.contact-warning`** — Project rule requires zero hardcoded colors; every background must use a CSS token. Replace with `var(--accent-warning-bg)` (or equivalent token). [`CoachRegisterPage.vue`]
- [x] [Review][Patch] **`CoachProfileBuilderPlaceholderPage.vue` uses hardcoded English strings** — "Profile Builder" and "Your account is verified! The profile builder will be available soon." are raw literals, not `t()` calls. Add i18n keys to `en/index.js` and `de/index.js`. [`CoachProfileBuilderPlaceholderPage.vue`]
- [x] [Review][Patch] **`CoachEmailVerifyPage` shows blank page when `?token` is absent** — `if (!token) { isVerifying.value = false; return }` — no error state is set; user sees a resolved-spinner and nothing else. Set an error message in the early-return branch. [`CoachEmailVerifyPage.vue:onMounted`]
- [x] [Review][Patch] **`CoachRegistrationRequest.phone` has no format/length constraint** — `@NotBlank` only; any non-blank string passes validation and is stored as-is. Add `@Size(min=7, max=20)` or a `@Pattern` for E.164 format. [`CoachRegistrationRequest.java`]

- [x] [Patch][langKey] **`langKey` hardcoded — coach emails always in English, raw HTML bypasses template system** — `CoachRegistrationService.java:86` hardcodes `user.setLangKey("en")` regardless of the language the coach has selected in the UI. `CoachRegistrationEmailListener` builds raw HTML strings, bypassing `SpringTemplateEngine` + `MessageSource` entirely, so even if `langKey` were set correctly the email would still render in English with no subject localisation. `CoachVerificationEmailEvent` and `CoachOtpEmailEvent` carry no `langKey` or `firstName`, so the listener cannot access them. The frontend never sends a `langKey` field in the registration POST body (though the axios interceptor does send `Accept-Language` globally, that header is not consumed during registration). See Task 16 for the full fix spanning frontend payload, `CoachRegistrationRequest`, service, events, listener, `EmailTemplate` enum, two new Thymeleaf templates, `messages_en.properties` additions, and new `messages_de.properties`. [`CoachRegistrationService.java:86`, `CoachRegistrationRequest.java`, `CoachVerificationEmailEvent.java`, `CoachOtpEmailEvent.java`, `CoachRegistrationEmailListener.java`, `EmailTemplate.java`, `CoachRegisterPage.vue`]

#### Deferred
- [x] [Review][Defer] OTP hash `SHA-256(otp+userId)` — small 6-digit OTP space vulnerable to offline pre-computation if DB is breached [CoachRegistrationService.java:hashOtp] — deferred, hash scheme is spec-prescribed; rate limiting (P3) is the primary in-flight mitigation
- [x] [Review][Defer] `verifyPhone` accepts caller-supplied `userId` with no ownership binding [VerifyPhoneRequest.java] — deferred, spec-required; risk is mitigated by rate limiting on the endpoint
- [x] [Review][Defer] SES conditional bean: unrecognized value for `app.ses.enabled` leaves `SesEmailService` unwired at startup [SesConfig.java, SesEmailServiceImpl.java] — deferred, unlikely edge case
- [x] [Review][Defer] `BaseEntity` TSID + V21 `BIGINT PRIMARY KEY` with no sequence — direct SQL inserts in future migrations or scripts require manual TSID generation [V21__skillars_security_extension.sql] — deferred, pre-existing codebase pattern
- [x] [Review][Defer] `ContactDetailSanitizer.PHONE_PATTERN` may redact digit-heavy name segments (e.g., "Type 2") [ContactDetailSanitizer.java] — deferred, pattern is spec-prescribed; refine when real-world false positives are observed
- [x] [Review][Defer] `RateLimitingService` uses in-process `ConcurrentHashMap` — not cluster-safe, no eviction [pre-existing infrastructure] — deferred, pre-existing issue not introduced by this story
- [x] [Review][Defer] `TokenErrorResponse.errorKey` field name — confirm alignment with `useErrorHandler` composable in frontend; currently untraceable from diff alone [ApiAdvice.java] — deferred, likely aligned; verify when applying other patches

#### Group A Review — Infrastructure & Config (2026-06-11)
_Scope: infrastructure.ses, infrastructure.sanitizer, V21 migration, pom.xml, application-*.yaml | 6 patch, 7 defer, 5 dismissed_

##### Patches
- [x] [Review][Patch] **SesConfig matchIfMissing=true + missing app.ses config in non-dev profiles** — `SesConfig.java:15`, `application-uat.yaml` — When `app.ses.enabled` is absent (UAT/test profiles with no ses block), `matchIfMissing=true` activates `SesEmailServiceImpl` with a null `fromAddress`, crashing on first send; add `app.ses.enabled: false` to any non-dev/non-prod profile, or change `matchIfMissing=false` to require explicit opt-in. [HIGH] — Fixed: `matchIfMissing=false` on `SesEmailServiceImpl` and `SesConfig`; `NoOpSesEmailService` gains `matchIfMissing=true` as safe default; `application-uat.yaml` gets explicit `app.ses.enabled: false`.
- [x] [Review][Patch] **SesException message leaks recipient email (PII in logs/traces)** — `SesEmailServiceImpl.java:27` — `"Failed to send email to " + to` embeds the user's email in exception messages and stack traces; replace with a generic message. [MED] — Fixed: message changed to `"Failed to send email"`.
- [x] [Review][Patch] **NoOpSesEmailService logs full recipient email at INFO (PII in dev logs)** — `NoOpSesEmailService.java:13` — `log.info("...to={}...", toAddress)` writes user email unconditionally; mask or omit the address. [MED] — Fixed: log now only records subject, not address.
- [x] [Review][Patch] **SesException swallowed in AFTER_COMMIT listener — silent 200 OK when email fails** [CoachRegistrationEmailListener.java] — `SesException` thrown post-commit propagates to Spring's event multicaster, not the HTTP layer; client gets 200 OK while user receives no verification email; add structured error handling (dead-letter, alerting, or retry) in the listener. [HIGH] — Fixed: both listener methods now catch `SesException` and log at ERROR level with actionable message.
- [x] [Review][Patch] **verifyEmail sets activated=true before OTP email delivery confirmed — orphaned accounts if SES fails** [CoachRegistrationService.java:116-118] — Account becomes `activated=true` before the OTP email fires; if SES throws, the user can never complete phone verification and the account is permanently stuck in `EMAIL_VERIFIED`. [HIGH] — Mitigated by P4 listener error logging; OTP token persisted in DB allows future resend-OTP endpoint (see deferred D7). Activation semantics unchanged per prior review decision.
- [x] [Review][Patch] **phone_otp_tokens missing version column — no optimistic-lock coverage** — `V21__skillars_security_extension.sql:22` — `email_verification_tokens` has `version BIGINT NOT NULL DEFAULT 0` but `phone_otp_tokens` does not; add the column for consistency. [LOW] — Fixed: `version BIGINT NOT NULL DEFAULT 0` added to migration DDL; `@Version Long version` field added to `PhoneOtpToken.java`.

##### Deferred
- [x] [Review][Defer] BIGINT PK with no DB sequence — pre-existing @Tsid pattern; direct SQL inserts require manual TSID generation [V21__skillars_security_extension.sql]
- [x] [Review][Defer] verification_status unconstrained VARCHAR(20) — no CHECK constraint; pre-existing pattern for enum-backed columns [V21__skillars_security_extension.sql]
- [x] [Review][Defer] SES region hardcoded eu-west-1 in SesProperties, not overridden in application-prod.yaml — deployment config concern; acceptable default [SesProperties.java, application-prod.yaml]
- [x] [Review][Defer] Authority id 100/101 magic numbers — PK collision risk if authority sequence reaches these values; ON CONFLICT (name) DO NOTHING does not protect against PK clash with a different name [V21__skillars_security_extension.sql]
- [x] [Review][Defer] phone_otp_tokens no partial unique index on active OTPs — multiple valid OTPs possible if service doesn't invalidate old tokens first; verify in Group B review [V21__skillars_security_extension.sql]
- [x] [Review][Defer] verifyEmail endpoint not @RateLimited — brute-force UUID token space; Group B code [CoachRegistrationService.java]
- [x] [Review][Defer] resendVerificationEmail accepts EMAIL_VERIFIED users and re-issues email verification token instead of directing them to OTP step — flow regression; Group B code [CoachRegistrationService.java]

#### Group B Review — Backend Domain (2026-06-11)
_Scope: platform.security service, repo, contract, API, tests, User entity, AppEndpoints, SecurityConstants, ApiAdvice, EmailTemplate, Thymeleaf templates, i18n | 9 patch, 8 defer, 14 dismissed_

##### Patches
- [x] [Review][Patch] **verifyEmail sets EMAIL_VERIFIED+activated without checking current verificationStatus — SUSPENDED users can be re-activated** — `CoachRegistrationService.java:116-117` — guard the email-verify path: reject token if `user.getVerificationStatus() != UNVERIFIED`. [HIGH]
- [x] [Review][Patch] **verifyPhone promotes user to BASIC_VERIFIED without checking EMAIL_VERIFIED prerequisite — OTP can skip email step** — `CoachRegistrationService.java:763-785` — guard: if `user.getVerificationStatus() != EMAIL_VERIFIED`, throw OtpVerificationException. [HIGH]
- [x] [Review][Patch] **ROLE_COACH absent from SecurityConstants.HAS_ANY_ROLE — authenticated coaches blocked from all /api/** endpoints including sanitize-preview** — `SecurityConstants.java:HAS_ANY_ROLE` / `SanitizePreviewResource.java:248` — add ROLE_COACH (and ROLE_PARENT) to HAS_ANY_ROLE expression and to any SECURED_MAPPINGS authority arrays. [HIGH]
- [x] [Review][Patch] **@RateLimited key on verifyPhone is global, not per-user — different node or userId gets a fresh 10-attempt bucket** — `CoachRegistrationService.java:762` `@RateLimited(key = "coach_otp_verify", capacity = 10, duration = 10)` — incorporate userId into the rate-limit key to scope it per-user. [MED]
- [x] [Review][Patch] **TokenErrorResponse shape (errorKey/canResend) diverges from ErrorDto — clients must handle two different 400 shapes** — `ApiAdvice.java:380` — replace `TokenErrorResponse` record with `ErrorDto` (extend with canResend if needed), or align field names with existing error contract. [MED]
- [x] [Review][Patch] **CoachOtpEmailEvent carries raw OTP plaintext — transient secret in event record** — `CoachOtpEmailEvent.java:3`, `CoachRegistrationService.java:188` — avoid placing raw OTP in the event; pass a pre-rendered email body or a one-time retrieval reference instead. [MED]
- [x] [Review][Patch] **IT test inserts into phone_otp_tokens without version column — will fail after Group A P6 added NOT NULL DEFAULT 0** — `CoachRegistrationResourceIT.java` (verifyPhone_correctOtp and verifyPhone_wrongOtp insert statements) — add `version` column to all direct SQL inserts into `phone_otp_tokens`. [MED]
- [x] [Review][Patch] **verifyEmail service method returns raw Map<String,Object> not a typed record DTO — violates project-context record convention** — `CoachRegistrationService.java:102`, `CoachRegistrationResource.java:36` — introduce a `VerifyEmailResponse` record in the contract package. [MED]
- [x] [Review][Patch] **Token-not-found throws security.emailTokenExpired — invalid/tampered link indistinguishable from expired link** — `CoachRegistrationService.java:104` — use a distinct error code (e.g., `security.emailTokenNotFound`) for the `orElseThrow` case. [LOW]

##### Deferred
- [x] [Review][Defer] verifyPhone caller-supplied userId with no ownership binding — spec-required design; already tracked as W2 in first review; rate limiting is primary mitigation [VerifyPhoneRequest.java]
- [x] [Review][Defer] IP-keyed rate limiting creates timing oracle for account enumeration on /resend-verification — pre-existing RateLimitingService architecture, not introduced by this story [CoachRegistrationService.java]
- [x] [Review][Defer] OTP hash SHA-256(otp+userId) with no random salt — spec-prescribed pattern; already tracked as W1 in first review [CoachRegistrationService.java:hashOtp]
- [x] [Review][Defer] Hardcoded DOB LocalDate(1900,1,1) and Gender.OTHER placeholders persisted to production DB — spec-acknowledged in dev debug log; cleaned up in Story 2.1 profile builder [CoachRegistrationService.java:registerCoach]
- [x] [Review][Defer] registerCoach returns void instead of CoachRegistrationResult — intentional simplification; spec named return type but void is functionally sufficient for the current AC [CoachRegistrationService.java]
- [x] [Review][Defer] resendVerificationEmail deletes unused tokens instead of marking used=true — deletion achieves invalidation; used (successful) tokens are preserved [CoachRegistrationService.java:168]
- [x] [Review][Defer] Hardcoded BIGINT test fixture IDs risk TSID collision — low probability; acceptable in test-only code [CoachRegistrationResourceIT.java]
- [x] [Review][Defer] SecureRandom re-instantiated per generateOtp() call — low severity performance concern, not a correctness bug [CoachRegistrationService.java:generateOtp]

#### Group C Review — Frontend (2026-06-11)
_Scope: Vue pages, composables, API client, i18n, router, CSS tokens, Thymeleaf email templates | 13 patch, 10 defer, 8 dismissed_

##### Patches
- [x] [Review][Patch] **`requiresAuth: true` on `coach/profile-builder` route with no JWT issued after phone verification — redirect loop strands every successfully-verified coach** [routes.js:69] [HIGH]
- [x] [Review][Patch] **`error.verificationFailed` key absent from all locale files — raw key string shown as heading on every email-token error** [CoachEmailVerifyPage.vue, en-US/index.js] [HIGH]
- [x] [Review][Patch] **`auth.coach.*` keys added to `en/index.js` only; app default locale is `en-US` with no fallback to `en` — all coach registration pages render raw key strings in production** [en/index.js, en-US/index.js, boot/i18n.js] [HIGH]
- [x] [Review][Patch] **`CoachEmailVerifyPage.vue` has no `v-else` content branch and `isVerifying` not reset before `router.push` — spinner hangs if navigation is delayed or blocked** [CoachEmailVerifyPage.vue] [MED]
- [x] [Review][Patch] **OTP `handleSubmit` has no early `isSubmitting` guard — paste + digit input can race and fire two API calls before the flag is set** [CoachPhoneVerifyPage.vue:handleSubmit] [MED]
- [x] [Review][Patch] **`CoachEmailPendingPage.vue` resend button silently no-ops when `email` is null (sessionStorage cleared) — no error feedback to user** [CoachEmailPendingPage.vue:handleResend] [MED]
- [x] [Review][Patch] **`emailPendingBody` i18n key drops `{{email}}` placeholder from spec — verification target address never shown on pending page** [en/index.js:22, de/index.js, CoachEmailPendingPage.vue] [MED]
- [x] [Review][Patch] **`security.emailTokenExpired` and `security.emailTokenUsed` absent from `messages_en.properties` — Spring MessageSource falls back to raw key string as displayed text** [messages_en.properties] [MED]
- [x] [Review][Patch] **No `onMounted` null-guard for `userId` on `CoachPhoneVerifyPage.vue` — user enters 6-digit OTP then receives cryptic 400 if query param is absent** [CoachPhoneVerifyPage.vue:onMounted] [MED]
- [x] [Review][Patch] **Phone `<q-input>` in `CoachRegisterPage.vue` has no `:rules` prop — no client-side feedback for empty or malformed phone before submission** [CoachRegisterPage.vue:~L89] [MED]
- [x] [Review][Patch] **`phoneVerifySubtitle` rendered twice in `CoachPhoneVerifyPage.vue` — duplicate in brand area and card body** [CoachPhoneVerifyPage.vue:7,13] [LOW]
- [x] [Review][Patch] **`cooldownTimer` is module-level `let`, not a `ref` — orphaned interval reference possible on component remount** [CoachEmailPendingPage.vue] [LOW]
- [x] [Review][Patch] **`@update:model-value="() => {}"` no-op handler on `firstName` input — misleading dead code** [CoachRegisterPage.vue:firstName q-input] [LOW]

##### Deferred
- [x] [Review][Defer] userId passed via URL query param as tamper vector — spec-mandated design (AC4); mitigated by per-userId rate limiting (Group B P4) [CoachEmailVerifyPage.vue, CoachPhoneVerifyPage.vue]
- [x] [Review][Defer] GET with token in query string exposes token to server logs and Referer headers — spec-mandated endpoint design (AC4); single-use token mitigates
- [x] [Review][Defer] sessionStorage fragility / cross-device flow — architectural limitation of spec-prescribed flow for story 1.3; cross-device is out of scope
- [x] [Review][Defer] useContactDetector PHONE_RE may false-positive on numeric strings in name fields — low practical risk; names rarely match the 8+ digit phone pattern
- [x] [Review][Defer] OTP handlers reimplemented instead of reused from OtpPage.vue per spec Dev Notes — functionally equivalent; refactor candidate for a future story
- [x] [Review][Defer] useContactDetector not applied to phone field — phone field accepts phone-formatted input; contact-detection less relevant; spec doesn't require it here
- [x] [Review][Defer] canResend read directly from err.response.data bypassing parseApiError abstraction — works correctly post Group B P5; parseApiError cleanup is future architectural work
- [x] [Review][Defer] resendSuccess banner implies email was always sent — intentional anti-enumeration security design
- [x] [Review][Defer] auth.firstName/validation.* keys absent from en/index.js — false positive: app default is en-US which has those keys; en locale falls back to en-US for missing keys
- [x] [Review][Defer] --accent-warning CSS token referenced in .contact-warning — confirmed present in _colors.scss lines 31 (light) and 88 (dark); no gap

#### Group D Review — Full Pass (2026-06-11)
_Scope: all new + modified files (tracked diff + 39 untracked source files) | 1 decision, 11 patch, 8 defer, 14 dismissed_

##### Decision Needed
- [x] [Review][Decision] **`coach/profile-builder` route meta — correct value after BASIC_VERIFIED** → **Resolved: Option 1 — `meta: {}`** — No JWT is issued until Story 1.5; making the placeholder route publicly accessible (no auth guard) unblocks the happy path. Story 2.1 can introduce `requiresAuth: true` when the real profile builder ships. Fix: remove `requiresGuest: true`, replace with empty `meta: {}`. [`routes.js:289`]

##### Patches
- [x] [Review][Patch] **OTP subtitle tells user to check their phone — OTP is delivered by email in story 1.3** — All three locale files (`en/index.js`, `en-US/index.js`, `de/index.js`) contain `phoneVerifySubtitle: 'Enter the 6-digit code sent to your phone'` (German: `"...an dein Telefon gesendet"`). The epics spec and dev notes confirm OTP delivery is via email in this story, not SMS. Update the key value to reference email in all locale files and update the `CoachPhoneVerifyPage.vue` subtitle accordingly. [MED] [`en/index.js:243`, `en-US/index.js:207`, `de/index.js`]
- [x] [Review][Patch] **`EmailTokenErrorDto` is a class extending `ErrorDto`, not a record — violates project DTO convention** — Project context rule: "All request and response DTOs must be Java `record` types." `EmailTokenErrorDto` is declared as `public class EmailTokenErrorDto extends ErrorDto`. Convert to a `record` (either a top-level record with a `canResend` field, or extend `ErrorDto` if the framework supports record inheritance — if not, compose instead of extend). [MED] [`src/main/java/com/softropic/skillars/infrastructure/message/EmailTokenErrorDto.java:3`]
- [x] [Review][Patch] **`verifyEmail` TOCTOU — `OptimisticLockException` propagates as unhandled 500 instead of clean 400** — `EmailVerificationToken` carries `@Version` for optimistic locking, but when two concurrent requests both pass the `isUsed()` check and the second save throws `OptimisticLockException`, the exception is not caught. It propagates out of the `@Transactional` method as a 500. Wrap the token save in `try { ... } catch (OptimisticLockingFailureException e) { throw new EmailTokenException("security.emailTokenUsed", false); }` to surface a clean 400. [MED] [`CoachRegistrationService.java:105–143`]
- [x] [Review][Patch] **`verifyPhone` checks user status AFTER hash comparison — timing oracle for token/user existence** — Current order: fetch token → check expiry → hash comparison → load user → check `verificationStatus`. An attacker can distinguish "OTP mismatch" from "status guard rejection" via timing difference, leaking whether a valid unexpired token exists for a given userId. Move the user load and `verificationStatus != EMAIL_VERIFIED` guard to BEFORE the hash comparison. [MED] [`CoachRegistrationService.java:150–167`]
- [x] [Review][Patch] **sessionStorage absent when email link opened in new tab — resend button is permanently dead** — `CoachRegisterPage.vue` writes `sessionStorage.setItem('pendingCoachEmail', email)` at submit time. Opening the verification link in a new browser tab or device starts a fresh session with no sessionStorage entry. `CoachEmailVerifyPage.handleResendFromVerify()` reads `sessionStorage.getItem('pendingCoachEmail')` → `undefined`, passing no email to the `/resend-verification` call. The user is stranded with a non-functional resend. Solution: include the email in the verification URL as a query parameter, or pass it through the URL instead of sessionStorage. [MED] [`CoachEmailVerifyPage.vue:82`, `CoachRegisterPage.vue:199`]
- [x] [Review][Patch] **No `@Size(max=...)` on `firstName` / `lastName` — oversized input causes unhandled `DataIntegrityViolationException` 500** — `CoachRegistrationRequest` has `@NotBlank` only on name fields. A client bypassing the frontend can submit a multi-thousand-character first name. If the underlying DB column is `VARCHAR(50)` or `VARCHAR(100)`, Hibernate will throw `DataIntegrityViolationException` which is not caught by `ApiAdvice`. Add `@Size(max = 100)` (or whatever the column max is) to both fields. [MED] [`CoachRegistrationRequest.java:9–10`]
- [x] [Review][Patch] **No `@PreAuthorize` on any `CoachRegistrationResource` method — project convention violation** — Project context rule: "Every resource method must have a `@PreAuthorize` annotation using `SecurityConstants`." All four handler methods are unannotated. Public endpoints are security-config whitelisted (correct), but the explicit annotation is still required by convention. Add `@PreAuthorize("permitAll()")` to all four methods (or a shared constant `IS_ANONYMOUS` if the project defines one). [LOW] [`CoachRegistrationResource.java:29,35,40,46`]
- [x] [Review][Patch] **`de/index.js` missing `auth.coach.phoneVerifySubtitle` key — i18n parity violation** — `en/index.js` and `en-US/index.js` both define `auth.coach.phoneVerifySubtitle`. The `de/index.js` block ends at `profileBuilderBody` with no `phoneVerifySubtitle` entry. Add the German translation (update value to reference email, not phone, per the companion patch above). [LOW] [`src/frontend/src/i18n/de/index.js`]
- [x] [Review][Patch] **`messages_de.properties` missing `security.emailTokenExpired`, `security.emailTokenUsed`, `security.emailTokenInvalid` keys** — `messages_en.properties` defines these keys. `messages_de.properties` contains no `security.*` keys. German-locale users who hit token errors will see the raw key string (`security.emailTokenExpired`) as the displayed message because Spring `MessageSource` falls back to the key when no translation exists. Add German translations for all three. [LOW] [`src/main/resources/i18n/messages_de.properties`]
- [x] [Review][Patch] **`verifyPhone_wrongOtp_returns400` IT test exercises the wrong code path** — The `verifyPhone_correctOtp_setsBasicVerified` test uses `transactionTemplate.execute(...)` to commit the OTP insert before the HTTP call (required because HikariCP uses `auto-commit=false`). The `verifyPhone_wrongOtp_returns400` test uses a bare `jdbcTemplate.update(...)` without a wrapping `transactionTemplate`. The server-side transaction cannot see the un-committed row and returns 400 because the token is invisible — not because of an OTP mismatch. The test passes for the wrong reason; a bug in the hash-comparison path would not be caught. Wrap the OTP insert in `transactionTemplate.execute(...)` and use a deliberately wrong hash. [LOW] [`CoachRegistrationResourceIT.java:342–346`]
- [x] [Review][Patch] **`verifyEmail` throws `IllegalStateException` (500) when user deleted between token issuance and verification** — `userRepository.findOneById(evt.getUserId()).orElseThrow(() -> new IllegalStateException("User not found for token"))` propagates as an unhandled 500. Should throw `EmailTokenException("security.emailTokenInvalid", false)` to surface a clean 400 to the client. [LOW] [`CoachRegistrationService.java:116–117`]

##### Deferred
- [x] [Review][Defer] `/verify-email` endpoint not rate-limited — already deferred in Group A (D6); UUID token space is large; brute-force risk is low [CoachRegistrationResource.java]
- [x] [Review][Defer] Rate limit consumed before user table lookup in `verifyPhone` — allows targeted bucket exhaustion; pre-existing design limitation of public OTP endpoints; mitigated by per-userId keying (Group B P4) [CoachRegistrationService.java:145–147]
- [x] [Review][Defer] `SUSPENDED` user in `EMAIL_VERIFIED` state can complete phone OTP — `verifyPhone` only checks `EMAIL_VERIFIED`, not suspended flag; no suspension code exists yet; guard should be updated when suspension story is implemented [CoachRegistrationService.java]
- [x] [Review][Defer] SES delivery failure during `/resend-verification` creates valid DB token with no email delivery — token persists, rate limit partially consumed, user must retry; logged at ERROR; considered acceptable degradation [CoachRegistrationEmailListener.java]
- [x] [Review][Defer] Frontend 60s cooldown resets on page refresh — UI-only throttle; server-side rate limit is authoritative [CoachEmailPendingPage.vue]
- [x] [Review][Defer] `ContactDetailSanitizer` double-redaction edge case — phone pattern can match trailing digits in an already-redacted string; result is double `[contact details removed]`; benign, no exploitable effect [ContactDetailSanitizer.java]
- [x] [Review][Defer] `ON CONFLICT (name) DO NOTHING` in authority seed does not protect against PK collision on `id` — already tracked in Group A D4; id=100/101 are safe for this project [V21__skillars_security_extension.sql]
- [x] [Review][Defer] `verifyEmail` response leaks internal userId as URL query param — already tracked in Group C D1; spec-mandated (AC4); mitigated by per-userId rate limiting [CoachRegistrationService.java:142, CoachEmailVerifyPage.vue:72]
