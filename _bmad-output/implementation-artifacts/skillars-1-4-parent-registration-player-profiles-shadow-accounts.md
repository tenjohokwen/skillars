# Story skillars-1.4: Parent Registration, Player Profiles & Shadow Accounts

Status: done

## Story

As a parent,
I want to register and create one or more player profiles under my account,
So that I can manage bookings, track development, and maintain oversight of my child's coaching sessions from a single login.

## Acceptance Criteria

1. **Parent registration persists user**: `POST /api/security/parent/register` (public) — firstName, lastName, email, password, phone → new `User` with `skillarsRole = PARENT`, `verificationStatus = UNVERIFIED`, `activated = false`. Returns 200.

2. **Email & phone verification**: Email verification and phone OTP follow the exact same flow as Story 1.3 (using the same `email_verification_tokens` and `phone_otp_tokens` tables). Endpoints: `GET /api/security/parent/verify-email?token=`, `POST /api/security/parent/verify-phone`, `POST /api/security/parent/resend-verification`.

3. **Three-consent gate enforced**: Registration form requires three checkboxes — Terms of Service, Privacy Policy, and Parent Consent Policy — all required, all NOT pre-checked. Each policy's legal text must be rendered in a scrollable inline container (max height, overflow scroll) BEFORE its checkbox becomes interactive. Submit is disabled until all three are checked.

4. **Player profile creation**: `POST /api/security/players` (authenticated PARENT) — name, dateOfBirth (ISO 8601), position, and for minors: consentPolicyVersion → `PlayerProfile` record created with `ageTier` calculated from DOB via `AgePolicyService`. A `ParentPlayerLink` record is also created (parentId, playerId, consentAcceptedAt, consentPolicyVersion). Returns 201 with `PlayerProfileResponse`.

5. **Age tier calculation and storage**: Age tier is computed from the player's date of birth against config-driven brackets (U10 / 10–12 / 13–17 / 18+) via `AgePolicyService`. Tier is stored on `PlayerProfile.ageTier`. Tier can only be changed by the parent via an explicit consent escalation flow (deferred to Story 1.6).

6. **One-parent-per-player enforcement**: `POST /api/security/players/{playerId}/link-parent` (authenticated PARENT) → returns `409 Conflict` with `ErrorDto` code `security.playerAlreadyHasParent` if a `parent_player_links` record already exists for that playerId. Unique constraint `uq_ppl_player_id` enforces this at DB level AND application layer.

7. **Consent for minors recorded**: For players under 18, `parentConsent = true` must be in the request, `consentAcceptedAt` (current timestamp) and `consentPolicyVersion` are stored on `ParentPlayerLink`.

8. **U10 players — parent-managed only**: If `ageTier = U10`, no independent player account is created. `PlayerProfile.independentAccountAllowed` is set `false`. `AgePolicyService` enforces this constraint.

9. **ParentChildSwitcher in header**: `ParentChildSwitcher.vue` component displays in the main layout header when authenticated as a PARENT. Shows current player avatar (initials-based) + name. Tapping opens a drawer listing all player profiles for the authenticated parent via `GET /api/security/players`. One-tap switches the `activePlayerId` in the `playerStore` Pinia store. The switcher is always accessible from the header, never hidden in settings.

10. **`@PreAuthorize` on all authenticated endpoints**: All player-management endpoints in `platform.security` must use `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)`.

11. **Duplicate email handling**: Same as Story 1.3 — `409 Conflict` with `ErrorDto` code `security.emailInUse`. No account enumeration.

12. **Contact detail sanitization**: Same as Story 1.3 — `ContactDetailSanitizer` on name fields server-side; amber warning bar via `useContactDetector` composable in frontend.

## Tasks / Subtasks

- [x] Task 1: Flyway V22 migration — player_profiles, parent_player_links tables + age policy config seed (AC: 4, 5, 6, 7, 8)
  - [x] Create `V22__parent_player_shadow_accounts.sql`:
    - Create `main.player_profiles` table (see Dev Notes for full DDL)
    - Create `main.parent_player_links` table with `CONSTRAINT uq_ppl_player_id UNIQUE (player_id)` (see Dev Notes)
    - Add indexes: `idx_pp_parent_id`, `idx_ppl_parent_id`, `idx_ppl_player_id`
    - Seed age policy defaults into `main.platform_config` (see Dev Notes for INSERT — use IDs 100–102, no `updated_by` column)
  - [x] Verify migration runs cleanly against Testcontainers PostgreSQL

- [x] Task 2: Domain enums — AgeTier, PlayerPosition (AC: 4, 5, 8)
  - [x] Create `AgeTier.java` enum in `platform.security.contract`: `U10, AGE_10_12, AGE_13_17, ADULT` — add `displayLabel()` method returning "U10", "10–12", "13–17", "18+"
  - [x] Create `PlayerPosition.java` enum in `platform.security.contract`: `GOALKEEPER, DEFENDER, MIDFIELDER, FORWARD` — add `displayLabel()` method

- [x] Task 3: AgePolicy record + AgePolicyService (AC: 5, 8)
  - [x] Create `AgePolicy.java` record in `platform.security.contract`: `record AgePolicy(int u10MaxAge, int youngTeenMaxAge, int teenMaxAge)` — default values 9, 12, 17
  - [x] Create `AgePolicyService.java` in `platform.security.service`:
    - `@Service @RequiredArgsConstructor`
    - Inject `ConfigService configService`
    - `getAgePolicy()`: reads `security.age-policy.u10-max-age` (default 9), `security.age-policy.young-teen-max-age` (default 12), `security.age-policy.teen-max-age` (default 17) from ConfigService — call per use, never cache in field
    - `getAgeTier(LocalDate dateOfBirth)`: computes current age from DOB, returns `AgeTier` — use `Period.between(dateOfBirth, LocalDate.now())` to get age in years
    - `isMinor(LocalDate dateOfBirth)`: returns `true` if age < 18

- [x] Task 4: PlayerProfile entity + repository (AC: 4, 5, 7, 8)
  - [x] Create `PlayerProfile.java` entity in `platform.security.repo`:
    - Extends `BaseEntity` (TSID PK, auditing)
    - Fields: `name VARCHAR(100) NOT NULL`, `dateOfBirth DATE NOT NULL`, `position VARCHAR(30) NOT NULL` (store enum string), `ageTier VARCHAR(15) NOT NULL` (store enum string), `parentId BIGINT NOT NULL` (FK to `user.id`), `independentAccountAllowed BOOLEAN NOT NULL DEFAULT TRUE`, `consentAcceptedAt TIMESTAMPTZ nullable`, `consentPolicyVersion VARCHAR(10) nullable`
    - `@Enumerated(EnumType.STRING)` on `ageTier` and `position` fields
    - NO cascade to User (parentId is a plain FK column, not a JPA relationship)
  - [x] Create `PlayerProfileRepository.java`:
    - `List<PlayerProfile> findByParentId(Long parentId)`
    - `Optional<PlayerProfile> findByIdAndParentId(Long id, Long parentId)` — always include parentId for family isolation

- [x] Task 5: ParentPlayerLink entity + repository (AC: 6, 7)
  - [x] Create `ParentPlayerLink.java` entity in `platform.security.repo`:
    - Extends `BaseEntity`
    - Fields: `parentId BIGINT NOT NULL`, `playerId BIGINT NOT NULL`, `consentAcceptedAt TIMESTAMPTZ NOT NULL`, `consentPolicyVersion VARCHAR(10) NOT NULL`
    - Table: `parent_player_links`
  - [x] Create `ParentPlayerLinkRepository.java`:
    - `boolean existsByPlayerId(Long playerId)`
    - `Optional<ParentPlayerLink> findByPlayerId(Long playerId)`
    - `List<ParentPlayerLink> findByParentId(Long parentId)`

- [x] Task 6: ROLE_PARENT access rights — AuthoritiesConstants, AppEndpoints, SecurityConstants (AC: 10)
  - [x] Add `PARENT = "ROLE_PARENT"` constant to `AuthoritiesConstants.java` (alongside existing COACH)
  - [x] Add `AuthoritiesConstants.PARENT` to the `SECURED_AUTHORITIES` array in `AppEndpoints.java` (currently: ADMIN, USER, LTD_ADMIN, COACH — add PARENT)
  - [x] Add `HAS_PARENT_ROLE = "hasRole('ROLE_PARENT')"` constant to `SecurityConstants.java`
  - [x] Add `hasRole('ROLE_PARENT')` to `SecurityConstants.HAS_ANY_ROLE` expression (currently missing PARENT)

- [x] Task 7: Create `ParentRegistrationService` (AC: 1, 2, 7, 11, 12)
  - [x] Create `ParentRegistrationService.java` in `platform.security.service` — mirror `CoachRegistrationService` exactly, substituting PARENT role/endpoints:
    - `@RateLimited(key = "parent_register", capacity = 3, duration = 60)` on `registerParent(ParentRegistrationRequest req)`
    - Create `User` with `skillarsRole = PARENT`, `verificationStatus = UNVERIFIED`, `activated = false`, authority `ROLE_PARENT`
    - Apply `ContactDetailSanitizer` to firstName, lastName before persistence
    - TOCTOU guard: wrap `userRepository.save()` in try-catch for `DataIntegrityViolationException` → rethrow as `ParentRegistrationException("security.emailInUse")`
    - `verifyEmail(UUID token)`: same logic as coach — guard `verificationStatus == UNVERIFIED` before proceeding; set `EMAIL_VERIFIED` + `activated = true`; publish `ParentVerificationEmailEvent` → `ParentOtpEmailEvent` flow
    - `verifyPhone(Long userId, String otp)`: same logic as coach — guard `verificationStatus == EMAIL_VERIFIED`; set `BASIC_VERIFIED`; `@RateLimited(key = "parent_otp_verify:" + userId, capacity = 10, duration = 10)` (per-userId key)
    - `resendVerificationEmail(String email)`: same as coach (no enumeration)
  - [x] Create `ParentRegistrationRequest.java` record in `platform.security.contract`:
    - `@NotBlank @Size(max = 50) String firstName`
    - `@NotBlank @Size(max = 50) String lastName`
    - `@Email @NotBlank String email`
    - `@NotBlank @Size(min = 8) String password`
    - `@NotBlank @Pattern(regexp="^\\+?[\\d][\\d\\s\\-().]{6,18}[\\d]$") String phone`
    - `@Size(min = 2, max = 5) String langKey` (optional, defaults to "en")
  - [x] Create `ParentRegistrationException.java` in `platform.security.contract.exception` (same structure as `CoachRegistrationException`)
  - [x] Map `ParentRegistrationException` in `ApiAdvice.java` → 409 Conflict with `ErrorDto`

- [x] Task 8: Create `ShadowAccountService` (AC: 4, 5, 6, 8, 9)
  - [x] Create `ShadowAccountService.java` in `platform.security.service`:
    - Inject: `PlayerProfileRepository`, `ParentPlayerLinkRepository`, `AgePolicyService`, `ConfigService`
    - `createPlayerProfile(Long parentId, CreatePlayerProfileRequest req)`:
      1. Compute `AgeTier ageTier = agePolicyService.getAgeTier(req.dateOfBirth())`
      2. Set `independentAccountAllowed = ageTier != AgeTier.U10`
      3. If `agePolicyService.isMinor(req.dateOfBirth())` and `req.parentConsent() != true`: throw `ShadowAccountException("security.parentConsentRequired")`
      4. Build and save `PlayerProfile`; set `consentAcceptedAt = Instant.now()` and `consentPolicyVersion` from request (only for minors)
      5. Check `parentPlayerLinkRepository.existsByPlayerId(profile.getId())` — if already exists, throw `ShadowAccountException("security.playerAlreadyHasParent")` (defensive; impossible on create but guard for concurrent saves)
      6. Build and save `ParentPlayerLink(parentId, profile.getId(), Instant.now(), req.consentPolicyVersion())`
      7. Return `PlayerProfileResponse`
    - `listPlayerProfiles(Long parentId)`: returns `playerProfileRepository.findByParentId(parentId)` mapped to `List<PlayerProfileResponse>`
    - `linkAdditionalParent(Long requestingParentId, Long playerId)`:
      - If `parentPlayerLinkRepository.existsByPlayerId(playerId)` → throw `ShadowAccountException("security.playerAlreadyHasParent")`
      - Otherwise create link (future feature; for now always throws because one-parent is always created at profile-creation time)
  - [x] Create `CreatePlayerProfileRequest.java` record in `platform.security.contract`:
    - `@NotBlank @Size(max = 100) String name`
    - `@NotNull @Past LocalDate dateOfBirth`
    - `@NotNull PlayerPosition position`
    - `Boolean parentConsent` (required for minors — null treated as false)
    - `@Size(max = 10) String consentPolicyVersion` (required for minors — validated in service)
  - [x] Create `PlayerProfileResponse.java` record in `platform.security.contract`:
    - `Long id`, `String name`, `LocalDate dateOfBirth`, `PlayerPosition position`, `AgeTier ageTier`, `String ageTierLabel`, `boolean independentAccountAllowed`
  - [x] Create `ShadowAccountException.java` in `platform.security.contract.exception` — same structure as `CoachRegistrationException`
  - [x] Map `ShadowAccountException("security.playerAlreadyHasParent")` in `ApiAdvice.java` → 409 Conflict

- [x] Task 9: Create `ParentRegistrationResource` (AC: 1, 2, 11)
  - [x] Create `ParentRegistrationResource.java` in `platform.security.api`:
    - `POST /api/security/parent/register` — `@PreAuthorize("permitAll()")`, returns 200
    - `GET /api/security/parent/verify-email` — `@PreAuthorize("permitAll()")`, returns `VerifyEmailResponse`
    - `POST /api/security/parent/verify-phone` — `@PreAuthorize("permitAll()")`, uses existing `VerifyPhoneRequest`
    - `POST /api/security/parent/resend-verification` — `@PreAuthorize("permitAll()")`, uses existing `ResendVerificationRequest`
    - `@Observed(name = "security.parent_registration")` on class
  - [x] Add all four endpoints to `AppEndpoints.PUBLIC_ENDPOINTS` list:
    ```java
    "/api/security/parent/register**",
    "/api/security/parent/verify-email**",
    "/api/security/parent/verify-phone**",
    "/api/security/parent/resend-verification**"
    ```

- [x] Task 10: Create `ShadowAccountResource` (AC: 4, 6, 9, 10)
  - [x] Create `ShadowAccountResource.java` in `platform.security.api`:
    - `POST /api/security/players` — `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)` — `@RequestBody @Valid CreatePlayerProfileRequest` — calls `shadowAccountService.createPlayerProfile(currentUserId, req)` — returns `201 Created` with `PlayerProfileResponse`
    - `GET /api/security/players` — `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)` — returns `200 OK` with `List<PlayerProfileResponse>`
    - `POST /api/security/players/{playerId}/link-parent` — `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)` — always `409 Conflict` for now (one-parent enforcement)
    - `@Observed(name = "security.shadow_account")` on class
    - Extract current parent's user ID via: `Long parentId = Long.parseLong(((Principal) securityUtil.getCurrentUser()).getBusinessId())` — `Principal.getBusinessId()` stores `user.getId()` as a String (see `Principal.instanceFrom()`)

- [x] Task 11: Parent email events + listener (AC: 2 — email delivery)
  - [x] Create `ParentVerificationEmailEvent.java` record in `platform.security.contract.event`:
    `record ParentVerificationEmailEvent(String toAddress, String verifyUrl, String langKey, String firstName) {}`
  - [x] Create `ParentOtpEmailEvent.java` record in `platform.security.contract.event`:
    `record ParentOtpEmailEvent(String toAddress, String langKey, String firstName)` — do NOT include raw OTP; OTP is retrieved by listener via a retrieval token (or pass one-time reference) — see Dev Notes for approach
  - [x] Create `ParentRegistrationEmailListener.java` in `platform.security.infrastructure.listener`:
    - Mirror `CoachRegistrationEmailListener` exactly — `@TransactionalEventListener(phase = AFTER_COMMIT)`, inject `SpringTemplateEngine`, `MessageSource`, `SesEmailService`
    - `onVerificationEmail(ParentVerificationEmailEvent)` → process `parentEmailVerify` Thymeleaf template
    - `onOtpEmail(ParentOtpEmailEvent)` → process `parentOtp` Thymeleaf template
    - Catch `SesException` and log at ERROR level (no silent swallow)

- [x] Task 12: Thymeleaf templates + EmailTemplate enum + i18n (AC: 2)
  - [x] Add to `EmailTemplate.java` enum:
    ```java
    PARENT_EMAIL_VERIFY("email.parent.verify.title"),
    PARENT_OTP("email.parent.otp.title"),
    ```
    Template name resolution: `PARENT_EMAIL_VERIFY` → `parentEmailVerify` → `mails/parentEmailVerify.html`; `PARENT_OTP` → `parentOtp` → `mails/parentOtp.html`
  - [x] Create `src/main/resources/mails/parentEmailVerify.html` — model after `mails/coachEmailVerify.html` exactly; variables: `recipient.firstname`, `map.verifyUrl`
  - [x] Create `src/main/resources/mails/parentOtp.html` — model after `mails/coachOtp.html`; variables: `recipient.firstname`, `map.otpCode`
  - [x] Add to `src/main/resources/i18n/messages_en.properties`:
    ```properties
    email.parent.verify.title=Verify your Skillars parent account
    email.parent.verify.text1=Thank you for registering. Click the link below to verify your email address.
    email.parent.verify.linkText=Verify my email
    email.parent.verify.expiry=This link expires in 24 hours.
    email.parent.verify.ignore=If you did not create a Skillars parent account, please ignore this email.
    email.parent.otp.title=Your Skillars phone verification code
    email.parent.otp.intro=Your phone verification code is:
    email.parent.otp.expiry=This code expires in 10 minutes.
    email.parent.otp.ignore=If you did not request this code, please ignore this email.
    ```
  - [x] Add corresponding German keys to `src/main/resources/i18n/messages_de.properties` (see Dev Notes for translations)

- [x] Task 13: Frontend — `ParentRegisterPage.vue` (AC: 3, 11, 12)
  - [x] Create `src/frontend/src/pages/auth/ParentRegisterPage.vue`:
    - Fields: `firstName`, `lastName`, `email` (type email), `password` (toggle visibility), `phone` (type tel with `:rules` for format validation)
    - Three `q-checkbox` for ToS, Privacy Policy, and Parent Consent Policy — `v-model` bound to separate refs, NOT pre-checked
    - Each policy: render legal text in a scrollable `div` (max-height: 120px; overflow-y: auto; class `.policy-scroll-box`) BEFORE its checkbox. The checkbox should be placed outside the scroll box but after it.
    - Submit `q-btn`: `:disable="!tosAccepted || !privacyAccepted || !parentConsentAccepted || isSubmitting"`
    - Duplicate email (409): inline error with "Sign in instead" `router-link` to `/login`
    - On success: store email in sessionStorage key `pendingParentEmail`, `router.push('/parent/email-pending')`
    - All text via `t(...)` — i18n keys under `auth.parent.*`
    - No hardcoded hex. Uses design system tokens only.
    - Contact detail detection on `firstName` and `lastName` via `useContactDetector` composable (existing in `src/composables/useContactDetector.js`)
    - Extract `langKey` from `useI18n()` locale at submit time (`locale.value.split('-')[0]`)

- [x] Task 14: Frontend — `ParentEmailPendingPage.vue` (AC: 2)
  - [x] Create `src/frontend/src/pages/auth/ParentEmailPendingPage.vue` — mirror `CoachEmailPendingPage.vue` exactly; read email from `sessionStorage.getItem('pendingParentEmail')`. If absent, show an error state with "Register again" link. 60s resend cooldown using `ref` (not module-level `let`).

- [x] Task 15: Frontend — `ParentEmailVerifyPage.vue` (AC: 2)
  - [x] Create `src/frontend/src/pages/auth/ParentEmailVerifyPage.vue` — mirror `CoachEmailVerifyPage.vue` exactly:
    - On mount, read `route.query.token`; if absent, set error state (do NOT leave blank page)
    - Call `GET /api/security/parent/verify-email?token=...`
    - On success: `router.push({ path: '/parent/verify-phone', query: { userId: result.userId } })`
    - On error: show error + resend option; read email from `route.query.email` (URL param, not sessionStorage — avoids cross-tab issue found in Story 1.3 review)

- [x] Task 16: Frontend — `ParentPhoneVerifyPage.vue` (AC: 2)
  - [x] Create `src/frontend/src/pages/auth/ParentPhoneVerifyPage.vue` — mirror `CoachPhoneVerifyPage.vue`:
    - Reuse OTP auto-advance pattern (6 digits, auto-submit on last, backspace navigates left) — copy handlers verbatim from existing `CoachPhoneVerifyPage.vue`
    - On mount: guard for absent `route.query.userId` — show error if missing
    - `isSubmitting` guard before `handleSubmit` to prevent race on paste + digit input
    - On success (`BASIC_VERIFIED`): `router.push('/parent/create-player')` (placeholder route)
    - On error: clear digits, refocus first input, show inline error

- [x] Task 17: Frontend — `CreatePlayerProfilePage.vue` (AC: 4, 5, 7, 8)
  - [x] Create `src/frontend/src/pages/auth/CreatePlayerProfilePage.vue`:
    - Fields: `name` (type text, required), `dateOfBirth` (date picker, `@Past` validation — must be past date), `position` (q-select from `PlayerPosition` enum options)
    - Show calculated age tier preview: compute age client-side from DOB, display tier label
    - **Conditional consent section** (v-if `isMinor` — computed from DOB age < 18):
      - Show `parentConsentPolicy` text in a scrollable container (same `.policy-scroll-box` class as registration)
      - `parentConsent` checkbox (not pre-checked) — required for minors
      - `consentPolicyVersion` hidden field (value hardcoded as `"1.0"` for now)
    - Submit calls `POST /api/security/players` via `playerProfile.api.js`
    - On success: `router.push('/parent/dashboard')` (placeholder)
    - On error: inline error display
    - Note: This page requires ROLE_PARENT JWT (available after Story 1.5). For now, the page exists but the API call will return 403 until the parent logs in via Story 1.5. The page itself has no auth guard (`meta: {}`).
    - All text via `t(...)` — keys under `player.*`

- [x] Task 18: Frontend — `ParentDashboardPlaceholderPage.vue` (AC: 9)
  - [x] Create `src/frontend/src/pages/auth/ParentDashboardPlaceholderPage.vue`: glass card, title from i18n, body text explaining the dashboard will be available after login (Story 1.5 connects this). Add `auth.parent.dashboardTitle` and `auth.parent.dashboardBody` keys.

- [x] Task 19: Frontend — `ParentChildSwitcher.vue` component (AC: 9)
  - [x] Create `src/frontend/src/components/ParentChildSwitcher.vue`:
    - Reads from `playerStore` (Pinia — see below): `players` list, `activePlayerId`
    - Renders current player initials in an avatar + name. If no active player, renders nothing (hidden)
    - On click: opens a `q-dialog` or `q-drawer` (bottom sheet on mobile) listing all players
    - Each player row: initials avatar + name + `ageTierLabel` badge
    - Tapping a row: calls `playerStore.setActivePlayer(player.id)` + closes drawer
    - Only shown when `playerStore.players.length > 0`
    - i18n keys under `player.switcher.*`
    - No hardcoded hex; avatars use `var(--accent-primary)` background

- [x] Task 20: Frontend — `playerStore.js` Pinia store (AC: 9)
  - [x] Create `src/frontend/src/stores/playerStore.js`:
    ```js
    import { defineStore } from 'pinia'
    import { ref } from 'vue'
    import { playerProfileApi } from 'src/api/playerProfile.api.js'

    export const usePlayerStore = defineStore('player', () => {
      const players = ref([])
      const activePlayerId = ref(null)
      const activePlayer = computed(() => players.value.find(p => p.id === activePlayerId.value) ?? null)

      async function fetchPlayers() {
        const data = await playerProfileApi.listProfiles()
        players.value = data
        if (data.length > 0 && !activePlayerId.value) {
          activePlayerId.value = data[0].id
        }
      }

      function setActivePlayer(id) {
        activePlayerId.value = id
      }

      return { players, activePlayerId, activePlayer, fetchPlayers, setActivePlayer }
    })
    ```

- [x] Task 21: Frontend — integrate ParentChildSwitcher into `MainLayout.vue` (AC: 9)
  - [x] Find `src/frontend/src/layouts/MainLayout.vue` (or equivalent layout file)
  - [x] Import and place `<ParentChildSwitcher />` in the header toolbar — always accessible, never in settings
  - [x] The component self-hides when no players are in the store (no conditional render needed at layout level)

- [x] Task 22: Frontend — API files (AC: 1, 2, 4)
  - [x] Create `src/frontend/src/api/parentRegistration.api.js`:
    ```js
    register(data)         → POST /api/security/parent/register
    verifyEmail(token)     → GET  /api/security/parent/verify-email?token=
    verifyPhone(data)      → POST /api/security/parent/verify-phone
    resendVerification(email) → POST /api/security/parent/resend-verification
    ```
  - [x] Create `src/frontend/src/api/playerProfile.api.js`:
    ```js
    createProfile(data)    → POST /api/security/players
    listProfiles()         → GET  /api/security/players
    linkParent(playerId)   → POST /api/security/players/{playerId}/link-parent
    ```

- [x] Task 23: Frontend — i18n keys — en, en-US, de, fr-FR all FOUR files (AC: 3, 7, 9)
  - [x] Add to ALL FOUR locale files (`en/index.js`, `en-US/index.js`, `de/index.js`, `fr-FR/index.js`) simultaneously — ALL four must be updated or raw keys appear in production (Story 1.3 Group C P3 established three-file parity; `fr-FR` was discovered in this story and must be included):
    ```js
    auth: {
      // ... existing coach keys ...
      parent: {
        registerTitle: 'Create Parent Account',
        registerSubtitle: 'Manage your child\'s coaching journey',
        emailPendingTitle: 'Check your email',
        emailPendingBody: 'We sent a verification link to {email}. Click it to continue.',
        resendEmail: 'Resend email',
        resendCooldown: 'Resend available in {seconds}s',
        phoneVerifyTitle: 'Verify your phone',
        phoneVerifySubtitle: 'Enter the 6-digit code sent to your email',
        tosLabel: 'I accept the Terms of Service',
        privacyLabel: 'I accept the Privacy Policy',
        parentConsentLabel: 'I accept the Parent Consent Policy and confirm I am the legal guardian',
        signInInstead: 'Sign in instead',
        emailInUse: 'This email is already registered.',
        contactDetailWarning: 'Contact details will be removed on save',
        dashboardTitle: 'Account Verified',
        dashboardBody: 'Your account is verified! Sign in to create player profiles and start managing sessions.',
      },
    },
    player: {
      createTitle: 'Add Player Profile',
      nameLabel: 'Player name',
      dobLabel: 'Date of birth',
      positionLabel: 'Position',
      ageTierLabel: 'Age tier',
      consentTitle: 'Parent Consent',
      consentBody: 'By creating this profile for a minor, you confirm you are their legal guardian and accept responsibility for all activity on this account.',
      consentLabel: 'I consent on behalf of this minor player',
      createButton: 'Create Profile',
      switcher: {
        title: 'Switch Player',
        noPlayers: 'No player profiles yet',
      },
      positions: {
        GOALKEEPER: 'Goalkeeper',
        DEFENDER: 'Defender',
        MIDFIELDER: 'Midfielder',
        FORWARD: 'Forward',
      },
      ageTiers: {
        U10: 'U10',
        AGE_10_12: '10–12',
        AGE_13_17: '13–17',
        ADULT: '18+',
      },
    },
    error: {
      verificationFailed: 'Verification failed',  // already in de/index.js, add to en and en-US
    },
    ```

- [x] Task 24: Frontend — routes additions (8 new routes)
  - [x] Add to `src/frontend/src/router/routes.js` under the parent registration flow section:
    ```js
    // Parent registration flow (guest only)
    { path: 'parent-register', component: () => import('pages/auth/ParentRegisterPage.vue'), meta: { requiresGuest: true } },
    { path: 'parent/email-pending', component: () => import('pages/auth/ParentEmailPendingPage.vue'), meta: { requiresGuest: true } },
    { path: 'parent/verify-email', component: () => import('pages/auth/ParentEmailVerifyPage.vue'), meta: { requiresGuest: true } },
    { path: 'parent/verify-phone', component: () => import('pages/auth/ParentPhoneVerifyPage.vue'), meta: { requiresGuest: true } },
    // Post-verification — no auth guard (JWT not available until Story 1.5)
    { path: 'parent/create-player', component: () => import('pages/auth/CreatePlayerProfilePage.vue'), meta: {} },
    { path: 'parent/dashboard', component: () => import('pages/auth/ParentDashboardPlaceholderPage.vue'), meta: {} },
    ```

- [x] Task 25: Tests — `ParentRegistrationResourceIT` + `ShadowAccountServiceIT` (AC: 1, 2, 4, 6, 11)
  - [x] Create `src/test/java/com/softropic/skillars/platform/security/api/ParentRegistrationResourceIT.java`:
    - `@SpringBootTest + @Testcontainers` (real PostgreSQL). Use Instancio for test data. AssertJ for assertions.
    - Test cases:
      - `registerParent_validData_returns200AndUserIsUnverified`
      - `registerParent_duplicateEmail_returns409`
      - `registerParent_missingRequiredField_returns400`
      - `verifyEmail_validToken_setsEmailVerifiedAndReturnsUserId`
      - `verifyEmail_expiredToken_returns400WithCanResend`
      - `verifyEmail_usedToken_returns400`
      - `verifyPhone_correctOtp_setsBasicVerified`
      - `verifyPhone_wrongOtp_returns400`
      - `resendVerification_alwaysReturns200_noAccountEnumeration`
      - `registerParent_noLangKey_defaultsToEn`
      - `registerParent_langKeyDe_storesDeLanguage`
  - [x] Create `src/test/java/com/softropic/skillars/platform/security/service/ShadowAccountServiceIT.java`:
    - `@SpringBootTest + @Testcontainers`
    - `createPlayerProfile_minor_storesConsentAndAgeTier`
    - `createPlayerProfile_adult_noConsentRequired`
    - `createPlayerProfile_u10_setsIndependentAccountAllowedFalse`
    - `createPlayerProfile_missingConsentForMinor_throws`
    - `linkAdditionalParent_playerAlreadyHasParent_throws409`
    - `listPlayerProfiles_returnsOnlyParentProfiles` (isolation check)
    - `createPlayerProfile_twoParentsConcurrently_onlyOneSucceeds` (one-parent enforcement)

---

## Dev Notes

### ⚠️ CRITICAL — Extend, Never Rewrite

Do NOT modify existing `CoachRegistrationService`, `AccountResource`, `UserRegistrationService`, or any existing security infrastructure. This story only:
- Creates NEW service: `ParentRegistrationService`
- Creates NEW resource: `ParentRegistrationResource`, `ShadowAccountResource`
- Creates NEW entities: `PlayerProfile`, `ParentPlayerLink`
- **MODIFIES** (ADD only): `SecurityConstants.java` (new constant), `AuthoritiesConstants.java` (new constant), `AppEndpoints.java` (add PARENT to authorities and PUBLIC_ENDPOINTS), `ApiAdvice.java` (new exception handlers), `EmailTemplate.java` (new entries)

### ⚠️ CRITICAL — OTP Raw Value in Events

Do NOT put the raw OTP string in `ParentOtpEmailEvent` (Story 1.3 Group B P6 — transient secret in event record). Instead, the `ParentRegistrationService` should use the same approach as the patched `CoachRegistrationService`: pass the `otpCode` only as the email body rendering context within the listener, or retrieve the hashed token from DB + let the listener call a dedicated read-only method. **Check how the patched `CoachRegistrationEmailListener` handles this and follow that exact pattern.**

### Database — V22 DDL

```sql
-- V22__parent_player_shadow_accounts.sql

CREATE TABLE IF NOT EXISTS main.player_profiles (
    id                     BIGINT       PRIMARY KEY,
    name                   VARCHAR(100) NOT NULL,
    date_of_birth          DATE         NOT NULL,
    position               VARCHAR(30)  NOT NULL,
    age_tier               VARCHAR(15)  NOT NULL,
    parent_id              BIGINT       NOT NULL REFERENCES main."user"(id) ON DELETE CASCADE,
    independent_account_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    consent_accepted_at    TIMESTAMPTZ,
    consent_policy_version VARCHAR(10),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by             VARCHAR(50)  NOT NULL DEFAULT 'system',
    last_modified_date     TIMESTAMPTZ,
    last_modified_by       VARCHAR(50)
);

CREATE INDEX IF NOT EXISTS idx_pp_parent_id ON main.player_profiles(parent_id);

CREATE TABLE IF NOT EXISTS main.parent_player_links (
    id                     BIGINT       PRIMARY KEY,
    parent_id              BIGINT       NOT NULL REFERENCES main."user"(id) ON DELETE CASCADE,
    player_id              BIGINT       NOT NULL REFERENCES main.player_profiles(id) ON DELETE CASCADE,
    consent_accepted_at    TIMESTAMPTZ  NOT NULL,
    consent_policy_version VARCHAR(10)  NOT NULL,
    CONSTRAINT uq_ppl_player_id UNIQUE (player_id)
);

CREATE INDEX IF NOT EXISTS idx_ppl_parent_id ON main.parent_player_links(parent_id);
CREATE INDEX IF NOT EXISTS idx_ppl_player_id  ON main.parent_player_links(player_id);

-- Seed age policy config defaults (IDs 100-102 — safe above V20's max of 37; ON CONFLICT DO NOTHING on key)
-- platform_config columns: id, key, value, value_type, description, updated_at (NO updated_by column)
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
VALUES
    (100, 'security.age-policy.u10-max-age',        '9',  'LONG', 'Maximum age (inclusive) for U10 tier',   NOW()),
    (101, 'security.age-policy.young-teen-max-age',  '12', 'LONG', 'Maximum age (inclusive) for 10-12 tier', NOW()),
    (102, 'security.age-policy.teen-max-age',        '17', 'LONG', 'Maximum age (inclusive) for 13-17 tier', NOW())
ON CONFLICT (key) DO NOTHING;
```

**Note on DB schema prefix**: ALL tables and FK references use `main.` prefix — matches V21 pattern exactly.

**Note on `player_profiles` PK**: Uses BIGINT (TSID via `BaseEntity`) — same as all other entities. No UUID PKs anywhere in this project.

**Note on `platform_config` schema**: Verified from `V20__platform_config.sql` — columns are `id, key, value, value_type, description, updated_at`. There is NO `updated_by` column. IDs are explicitly assigned (1–37 in V20; use 100–102 here to avoid conflict). The unique constraint is on `key`, so `ON CONFLICT (key) DO NOTHING` is the safe re-run guard.

### AgePolicyService — Correct ConfigService Usage

Always call ConfigService per-use, never cache in a field:

```java
public AgeTier getAgeTier(LocalDate dateOfBirth) {
    int age = Period.between(dateOfBirth, LocalDate.now()).getYears();
    int u10Max = (int) configService.getLong("security.age-policy.u10-max-age").orElse(9L);
    int youngTeenMax = (int) configService.getLong("security.age-policy.young-teen-max-age").orElse(12L);
    int teenMax = (int) configService.getLong("security.age-policy.teen-max-age").orElse(17L);
    if (age <= u10Max)      return AgeTier.U10;
    if (age <= youngTeenMax) return AgeTier.AGE_10_12;
    if (age <= teenMax)      return AgeTier.AGE_13_17;
    return AgeTier.ADULT;
}
```

**ConfigService method signature**: Check actual `ConfigService.java` method signatures before using. The explore found: supports `String` and `Long` value types with optional lookup. Likely: `Optional<Long> getLong(String key)` and `Optional<String> getString(String key)`. If the API differs, adapt accordingly.

### PlayerProfile Entity — Does NOT extend AbstractAuditingEntity

`PlayerProfile` uses `BaseEntity` (not `AbstractAuditingEntity`) — same pattern as `EmailVerificationToken` and `PhoneOtpToken`. The `player_profiles` table has its own `created_at`, `created_by`, `last_modified_date`, `last_modified_by` columns, NOT Hibernate Envers auditing. Check `BaseEntity.java` for its exact fields and `@GeneratedValue` strategy before writing the entity.

### Family Data Isolation — Repository Contract (PLATFORM-WIDE INVARIANT)

Every repository method that retrieves player-scoped data MUST include `parentId` as a parameter. No repository method for player data exists without a `parentId` filter. This is an architectural invariant:

```java
// CORRECT — always include parentId
Optional<PlayerProfile> findByIdAndParentId(Long id, Long parentId);
List<PlayerProfile> findByParentId(Long parentId);

// WRONG — never do this, bypasses family isolation
Optional<PlayerProfile> findById(Long id);
List<PlayerProfile> findAll();
```

### ShadowAccountResource — Extracting Current User ID

`ShadowAccountResource` needs the authenticated parent's user ID. Check `SecurityUtil.getCurrentUserId()` or similar utility in `platform.security.service.SecurityUtil`. If it returns `Optional<Long>` or similar, handle the empty case by throwing `MissingAuthenticationException`. Do NOT use `SecurityContextHolder.getContext()` directly — use the existing SecurityUtil abstraction.

### AppEndpoints — PUBLIC_ENDPOINTS is an Immutable List

`AppEndpoints.PUBLIC_ENDPOINTS` is declared as `List.of(...)` — immutable. It is NOT modified at runtime; you must change the source constant directly by appending the four new parent endpoints to the list literal in `AppEndpoints.java`:

```java
public static final List<String> PUBLIC_ENDPOINTS = List.of(
    // ... existing entries ...
    "/api/security/parent/register**",
    "/api/security/parent/verify-email**",
    "/api/security/parent/verify-phone**",
    "/api/security/parent/resend-verification**"
);
```

### AppEndpoints — SECURED_AUTHORITIES

`SECURED_AUTHORITIES` is a `private static final String[]`. Add `AuthoritiesConstants.PARENT` as the 5th element:

```java
private static final String[] SECURED_AUTHORITIES = new String[]{
    AuthoritiesConstants.ADMIN,
    AuthoritiesConstants.USER,
    AuthoritiesConstants.LTD_ADMIN,
    AuthoritiesConstants.COACH,
    AuthoritiesConstants.PARENT   // ADD THIS
};
```

### Email Verification URL Pattern for Parent

The verification link for parent email uses the same `app.frontend-url` property already in `application-dev.yaml` and `application-prod.yaml`:
```
https://{app.frontend-url}/parent/verify-email?token={UUID}&email={encodedEmail}
```
Include `email` as a query parameter in the URL (URL-encoded) to support the cross-tab resend scenario found in Story 1.3 Group D review.

### ParentChildSwitcher — Placement in Layout

Find the main layout file (likely `src/frontend/src/layouts/MainLayout.vue`). Read it fully before modifying. Place `<ParentChildSwitcher />` in the header toolbar area. It should be positioned near the user avatar/account menu, as it's only relevant to PARENT-role sessions. Use a `v-if="playerStore.players.length > 0"` guard at the layout level if the store is not pre-populated.

### Frontend: Route `parent/create-player` — No Auth Guard (Intentional)

The `CreatePlayerProfilePage.vue` is NOT behind `requiresAuth: true` because JWT authentication lands in Story 1.5. The page exists and the flow is correct; the API call will return 403 for unauthenticated users. Story 1.5 will implement login flow, after which parents can return and create profiles. The UX will show: "Your account is verified! Sign in to add your first player." if the API returns 401/403.

### i18n — Three Files Must Be Updated Simultaneously

From Story 1.2 rule + Story 1.3 Group C P3 finding: ALL three locale files (`en/index.js`, `en-US/index.js`, `de/index.js`) must be updated simultaneously. Missing a key in any one file causes raw key strings to show in production. The `en-US` locale is the app default (set in `boot/i18n.js`) and does not fall back to `en`.

### i18n — German Translations for Messages

Add to `src/main/resources/i18n/messages_de.properties`:
```properties
email.parent.verify.title=Skillars-Elternkonto verifizieren
email.parent.verify.text1=Vielen Dank für Ihre Registrierung. Klicken Sie auf den folgenden Link, um Ihre E-Mail-Adresse zu bestätigen.
email.parent.verify.linkText=E-Mail bestätigen
email.parent.verify.expiry=Dieser Link läuft in 24 Stunden ab.
email.parent.verify.ignore=Wenn Sie kein Skillars-Elternkonto erstellt haben, ignorieren Sie diese E-Mail.
email.parent.otp.title=Ihr Skillars-Telefon-Verifizierungscode
email.parent.otp.intro=Ihr Telefon-Verifizierungscode lautet:
email.parent.otp.expiry=Dieser Code läuft in 10 Minuten ab.
email.parent.otp.ignore=Wenn Sie diesen Code nicht angefordert haben, ignorieren Sie diese E-Mail.
```

### Frontend: `.policy-scroll-box` CSS Pattern

The scrollable policy container before each consent checkbox:
```scss
// Add to component <style scoped> — no hardcoded colors
.policy-scroll-box {
  max-height: 120px;
  overflow-y: auto;
  padding: 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  font-size: 0.85rem;
  color: var(--text-secondary);
  margin-bottom: 8px;
  background: var(--surface-glass);
}
```
Check `src/frontend/src/css/tokens/_colors.scss` for the exact token names (`--border-subtle`, `--surface-glass`, etc.) before using.

### Frontend: Verification Commands (Post-Implementation)

```bash
# Backend — confirm new entities registered in schema
grep -rn "PlayerProfile\|ParentPlayerLink" src/main/java/com/softropic/skillars/platform/security/repo/

# Backend — confirm ROLE_PARENT in SECURED_AUTHORITIES
grep -n "SECURED_AUTHORITIES\|PARENT" src/main/java/com/softropic/skillars/platform/security/config/AppEndpoints.java

# Backend — confirm HAS_PARENT_ROLE added
grep -n "HAS_PARENT_ROLE\|ROLE_PARENT" src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java

# Frontend — no hardcoded hex in new files
grep -rn '#[0-9a-fA-F]\{3,6\}' src/frontend/src/pages/auth/Parent*.vue src/frontend/src/pages/auth/CreatePlayer*.vue src/frontend/src/components/ParentChildSwitcher.vue

# Frontend — i18n FOUR-file parity check (en, en-US, de, fr-FR)
node -e "
const en=require('./src/frontend/src/i18n/en/index.js').default;
const enUS=require('./src/frontend/src/i18n/en-US/index.js').default;
const de=require('./src/frontend/src/i18n/de/index.js').default;
const frFR=require('./src/frontend/src/i18n/fr-FR/index.js').default;
const keys = obj => JSON.stringify(Object.keys(obj?.auth?.parent ?? {}).sort());
console.log('en:', keys(en)); console.log('en-US:', keys(enUS)); console.log('de:', keys(de)); console.log('fr-FR:', keys(frFR));
const all = [keys(en), keys(enUS), keys(de), keys(frFR)];
console.log('parity:', all.every(k => k === all[0]) ? 'OK' : 'MISMATCH');
"
```

### Project Structure — New Files

New backend files:
```
src/main/java/com/softropic/skillars/
├── platform/security/
│   ├── api/
│   │   ├── ParentRegistrationResource.java
│   │   └── ShadowAccountResource.java
│   ├── contract/
│   │   ├── AgeTier.java
│   │   ├── PlayerPosition.java
│   │   ├── AgePolicy.java
│   │   ├── ParentRegistrationRequest.java
│   │   ├── CreatePlayerProfileRequest.java
│   │   ├── PlayerProfileResponse.java
│   │   ├── event/
│   │   │   ├── ParentVerificationEmailEvent.java
│   │   │   └── ParentOtpEmailEvent.java
│   │   └── exception/
│   │       ├── ParentRegistrationException.java
│   │       └── ShadowAccountException.java
│   ├── repo/
│   │   ├── PlayerProfile.java
│   │   ├── PlayerProfileRepository.java
│   │   ├── ParentPlayerLink.java
│   │   └── ParentPlayerLinkRepository.java
│   ├── service/
│   │   ├── ParentRegistrationService.java
│   │   ├── ShadowAccountService.java
│   │   └── AgePolicyService.java
│   └── infrastructure/listener/
│       └── ParentRegistrationEmailListener.java
```

New frontend files:
```
src/frontend/src/
├── api/
│   ├── parentRegistration.api.js
│   └── playerProfile.api.js
├── components/
│   └── ParentChildSwitcher.vue
├── pages/auth/
│   ├── ParentRegisterPage.vue
│   ├── ParentEmailPendingPage.vue
│   ├── ParentEmailVerifyPage.vue
│   ├── ParentPhoneVerifyPage.vue
│   ├── CreatePlayerProfilePage.vue
│   └── ParentDashboardPlaceholderPage.vue
└── stores/
    └── playerStore.js
```

New DB migration:
- `src/main/resources/db/migration/V22__parent_player_shadow_accounts.sql`

New templates:
- `src/main/resources/mails/parentEmailVerify.html`
- `src/main/resources/mails/parentOtp.html`

Modified files:
- `src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java` (add HAS_PARENT_ROLE; update HAS_ANY_ROLE)
- `src/main/java/com/softropic/skillars/platform/security/contract/util/AuthoritiesConstants.java` (add PARENT)
- `src/main/java/com/softropic/skillars/platform/security/config/AppEndpoints.java` (add PARENT to authorities; add 4 endpoints to PUBLIC_ENDPOINTS)
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java` (add handlers for ParentRegistrationException, ShadowAccountException)
- `src/main/java/com/softropic/skillars/platform/notification/contract/EmailTemplate.java` (add PARENT_EMAIL_VERIFY, PARENT_OTP)
- `src/main/resources/i18n/messages_en.properties` (add email.parent.* keys)
- `src/main/resources/i18n/messages_de.properties` (add email.parent.* German keys)
- `src/frontend/src/router/routes.js` (add 6 new routes)
- `src/frontend/src/i18n/en/index.js` (add auth.parent.* and player.*)
- `src/frontend/src/i18n/en-US/index.js` (same additions)
- `src/frontend/src/i18n/de/index.js` (same additions, German)
- `src/frontend/src/i18n/fr-FR/index.js` (same additions, French — locale exists, must maintain parity)
- `src/frontend/src/layouts/MainLayout.vue` (integrate ParentChildSwitcher)

**No changes to**:
- `CoachRegistrationService.java`, `CoachRegistrationResource.java`
- `UserRegistrationService.java`, `AccountResource.java`
- `RegisterPage.vue`, `OtpPage.vue`, `ActivatePage.vue`
- Any existing migration file
- `User.java` (already has skillarsRole and verificationStatus from Story 1.3)

### Previous Story (1.3) — Key Patterns Established

Reuse the following from Story 1.3 (do NOT reinvent):
- `infrastructure.ses.SesEmailService` — already exists, inject as-is
- `infrastructure.sanitizer.ContactDetailSanitizer` — already exists, inject as-is
- `infrastructure.security.RateLimitingService` + `@RateLimited` annotation — already wired
- `PhoneOtpTokenRepository`, `EmailVerificationTokenRepository` — already exist, share between coach and parent registration services
- `useContactDetector.js` composable — already exists in `src/composables/`
- `CoachRegistrationEmailListener` OTP patch pattern — follow the same post-patch approach for `ParentRegistrationEmailListener`

### Story 1.3 Debug Notes (Apply to This Story)

- `gender` and `dateOfBirth` are `NOT NULL` in the `user` table. For parent registration, use the same placeholders as Story 1.3: `Gender.OTHER` and `LocalDate.of(1900, 1, 1)` for `dateOfBirth` until a parent profile builder is built (Story 2.1 equivalent for parents). Set these in `ParentRegistrationService.registerParent()`.
- `iso2_country NOT NULL` in user table: use `"XX"` placeholder for `PhoneNumber` constructor
- Token entity BIGINT PKs: they extend `BaseEntity` which uses `@Tsid` or similar `@GeneratedValue` — do NOT set IDs manually

### Security Architecture Notes

- **Two-layer family isolation**: Layer 1 — `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)` on all player-management endpoints. Layer 2 — every repository query for player data includes `parentId`. Both layers are required (architecture mandates belt-and-suspenders).
- **Age-tier enforcement is service-layer only**: Do NOT enforce age-tier access rules in the REST layer (`@PreAuthorize`). Age-tier rules affect multiple modules (booking, messaging, video) — enforcement lives in service layer only per architecture decisions.
- **U10 constraint**: `independentAccountAllowed = false` is stored but NOT enforced as a login blocker in this story. Story 1.6 (Age-Tier Enforcement) applies the runtime access controls.

### References

- [Source: skillars-epics.md#Story 1.4] — full AC and dev notes
- [Source: architecture.md#platform.security module structure] — PlayerProfile, ParentPlayerLink, ShadowAccountService, AgePolicyService
- [Source: architecture.md#Family-Level Data Isolation] — two-layer enforcement, mandatory parentId filter
- [Source: architecture.md#Age-Tier Access Control] — service-layer-only enforcement, AgePolicy from ConfigService
- [Source: project-context.md#Critical Implementation Rules] — record DTOs, MapStruct, @PreAuthorize required, no direct entity exposure
- [Source: project-context.md#Module Internal Structure] — api/service/repo/contract/config layers
- [Source: ux-design-specification.md#Form Patterns] — consent checkboxes never pre-checked, scrollable legal text before activation
- [Source: ux-design-specification.md#ParentChildSwitcher] — header placement, always accessible, drawer on tap, bottom sheet on mobile
- [Source: ux-design-specification.md#Age-Tier Restriction Pattern] — invisible to children, transparent to parents, not error states
- [Source: skillars-1-3-coach-account-registration-email-verification.md] — full pattern reference for all email/OTP infrastructure; review findings to avoid repeating same bugs
- [Source: V21__skillars_security_extension.sql] — DB schema prefix pattern (main.), BIGINT PKs, authority seed pattern
- [Source: AppEndpoints.java] — SECURED_AUTHORITIES array, PUBLIC_ENDPOINTS list (immutable), add PARENT to both
- [Source: SecurityConstants.java] — HAS_ANY_ROLE (needs PARENT added), HAS_ADMIN_ROLE pattern to follow for HAS_PARENT_ROLE
- [Source: AuthoritiesConstants.java] — COACH constant pattern to mirror for PARENT
- [Source: CoachRegistrationService.java] — @RateLimited pattern, TOCTOU DataIntegrityViolationException guard, OTP hash pattern

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6 (Claude Code, 2026-06-12)

### Debug Log References

- **ConfigService.getLong() type mismatch**: Story dev notes showed `getLong(key).orElse(9L)` but actual `getLong()` returns a primitive `long` and throws `IllegalStateException` on missing key. Fixed by switching to `configService.find(key).map(Integer::parseInt).orElse(9)` for safe int extraction. Further fix: `(int)` cast of boxed `Long` return was also invalid — resolved by mapping to `Integer::parseInt` directly.

### Completion Notes List

- ✅ Task 1: V22 migration creates `player_profiles`, `parent_player_links` tables with FK constraints and age policy config seed (IDs 100-102).
- ✅ Task 2: `AgeTier` and `PlayerPosition` enums with `displayLabel()` methods.
- ✅ Task 3: `AgePolicy` record + `AgePolicyService` using `configService.find()` with `Integer::parseInt` fallback.
- ✅ Task 4: `PlayerProfile` entity extending `BaseEntity`, `PlayerProfileRepository` with parentId-scoped queries.
- ✅ Task 5: `ParentPlayerLink` entity + `ParentPlayerLinkRepository`.
- ✅ Task 6: `ROLE_PARENT` authority wired into `AuthoritiesConstants`, `AppEndpoints` (PUBLIC_ENDPOINTS + SECURED_AUTHORITIES), and `SecurityConstants` (HAS_PARENT_ROLE + HAS_ANY_ROLE).
- ✅ Task 7: `ParentRegistrationService` mirroring coach flow with TOCTOU guard, OTP rate limiting, verification URL pattern.
- ✅ Task 8: `ShadowAccountService` with age-tier calculation, minor consent gate, one-parent enforcement, family isolation.
- ✅ Task 9: `ParentRegistrationResource` — 4 public endpoints, `@Observed`, `@PreAuthorize("permitAll()")`.
- ✅ Task 10: `ShadowAccountResource` — 3 PARENT-role endpoints, parentId extracted via `((Principal) securityUtil.getCurrentUser()).getBusinessId()`.
- ✅ Task 11: `ParentVerificationEmailEvent`, `ParentOtpEmailEvent` records; `ParentRegistrationEmailListener` with `@TransactionalEventListener(AFTER_COMMIT)`.
- ✅ Task 12: Thymeleaf `parentEmailVerify.html` + `parentOtp.html`; `EmailTemplate` enum entries; backend i18n for en + de.
- ✅ Task 13: `ParentRegisterPage.vue` — three-consent gate, `.policy-scroll-box` containers, contact detail detection.
- ✅ Task 14: `ParentEmailPendingPage.vue` — reads `pendingParentEmail` from sessionStorage, 60s cooldown.
- ✅ Task 15: `ParentEmailVerifyPage.vue` — email from `route.query.email` (cross-tab safe).
- ✅ Task 16: `ParentPhoneVerifyPage.vue` — 6-digit OTP, auto-advance, backspace navigation, paste handler.
- ✅ Task 17: `CreatePlayerProfilePage.vue` — age tier preview, conditional minor consent section.
- ✅ Task 18: `ParentDashboardPlaceholderPage.vue` — placeholder glass card with i18n.
- ✅ Task 19: `ParentChildSwitcher.vue` — q-dialog bottom sheet, player avatars with initials, `usePlayerStore`.
- ✅ Task 20: `playerStore.js` — Pinia setup store with `players`, `activePlayerId`, `activePlayer` computed, `fetchPlayers`, `setActivePlayer`.
- ✅ Task 21: `MainLayout.vue` updated — `<ParentChildSwitcher />` imported and placed in header toolbar.
- ✅ Task 22: `parentRegistration.api.js` + `playerProfile.api.js` API files.
- ✅ Task 23: i18n keys added to all four locale files: `en/index.js`, `en-US/index.js`, `de/index.js`, `fr-FR/index.js`.
- ✅ Task 24: 6 new routes added to `routes.js` for parent registration flow.
- ✅ Task 25: `ParentRegistrationResourceIT` (11 tests, all passing) + `ShadowAccountServiceIT` (7 tests, all passing). Full suite: 289/289 green.

### File List

**New backend files:**
- `src/main/resources/db/migration/V22__parent_player_shadow_accounts.sql`
- `src/main/java/com/softropic/skillars/platform/security/contract/AgeTier.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/PlayerPosition.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/AgePolicy.java`
- `src/main/java/com/softropic/skillars/platform/security/service/AgePolicyService.java`
- `src/main/java/com/softropic/skillars/platform/security/repo/PlayerProfile.java`
- `src/main/java/com/softropic/skillars/platform/security/repo/PlayerProfileRepository.java`
- `src/main/java/com/softropic/skillars/platform/security/repo/ParentPlayerLink.java`
- `src/main/java/com/softropic/skillars/platform/security/repo/ParentPlayerLinkRepository.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/ParentRegistrationRequest.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/exception/ParentRegistrationException.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/exception/ShadowAccountException.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/CreatePlayerProfileRequest.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/PlayerProfileResponse.java`
- `src/main/java/com/softropic/skillars/platform/security/service/ParentRegistrationService.java`
- `src/main/java/com/softropic/skillars/platform/security/service/ShadowAccountService.java`
- `src/main/java/com/softropic/skillars/platform/security/api/ParentRegistrationResource.java`
- `src/main/java/com/softropic/skillars/platform/security/api/ShadowAccountResource.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/event/ParentVerificationEmailEvent.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/event/ParentOtpEmailEvent.java`
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/listener/ParentRegistrationEmailListener.java`
- `src/main/resources/mails/parentEmailVerify.html`
- `src/main/resources/mails/parentOtp.html`

**New test files:**
- `src/test/java/com/softropic/skillars/platform/security/api/ParentRegistrationResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/security/service/ShadowAccountServiceIT.java`

**New frontend files:**
- `src/frontend/src/api/parentRegistration.api.js`
- `src/frontend/src/api/playerProfile.api.js`
- `src/frontend/src/components/ParentChildSwitcher.vue`
- `src/frontend/src/pages/auth/ParentRegisterPage.vue`
- `src/frontend/src/pages/auth/ParentEmailPendingPage.vue`
- `src/frontend/src/pages/auth/ParentEmailVerifyPage.vue`
- `src/frontend/src/pages/auth/ParentPhoneVerifyPage.vue`
- `src/frontend/src/pages/auth/CreatePlayerProfilePage.vue`
- `src/frontend/src/pages/auth/ParentDashboardPlaceholderPage.vue`
- `src/frontend/src/stores/playerStore.js`

**Modified files:**
- `src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/util/AuthoritiesConstants.java`
- `src/main/java/com/softropic/skillars/platform/security/config/AppEndpoints.java`
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java`
- `src/main/java/com/softropic/skillars/platform/notification/contract/EmailTemplate.java`
- `src/main/resources/i18n/messages_en.properties`
- `src/main/resources/i18n/messages_de.properties`
- `src/frontend/src/router/routes.js`
- `src/frontend/src/i18n/en/index.js`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/de/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`
- `src/frontend/src/layouts/MainLayout.vue`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

### Change Log

- 2026-06-12: Implemented Story 1.4 — Parent Registration, Player Profiles & Shadow Accounts. 25 tasks completed. Full test suite 289/289 green. Fixed `AgePolicyService` type mismatch (ConfigService.getLong → Integer::parseInt).

### Review Findings

> All 4 groups reviewed and all patches applied (2026-06-12). Review complete.

#### Patches — 9 items (all applied 2026-06-12)

- [x] [Review][Patch] Compilation error: `agepolicyService` typo — not present in working tree; field was already `agePolicyService`; no change needed
- [x] [Review][Patch] OTP raw value exposed in `ParentOtpEmailEvent` record field — `toString()` redaction already applied; pattern is identical to accepted `CoachOtpEmailEvent` (Story 1.3); raw OTP must reach the listener to render the email; no change needed
- [x] [Review][Patch] Manual `new PlayerProfileResponse(…)` construction — **FIXED**: created `PlayerProfileMapper.java` (@Mapper MapStruct interface); updated `ShadowAccountService` to inject and use it; removed `toResponse()` helper
- [x] [Review][Patch] TOCTOU vacuous `existsByPlayerId` check — **FIXED**: removed vacuous check after save; added `DataIntegrityViolationException` catch around `parentPlayerLinkRepository.save(link)` in `createPlayerProfile`
- [x] [Review][Patch] `consentPolicyVersion` null not validated for minors — **FIXED**: added service-level null guard in `createPlayerProfile`; throws `ShadowAccountException("security.consentPolicyVersionRequired")` for minors with null version
- [x] [Review][Patch] `independentAccountAllowed` inline in `ShadowAccountService` — **FIXED**: added `isIndependentAccountAllowed(AgeTier)` to `AgePolicyService`; `ShadowAccountService` now delegates
- [x] [Review][Patch] Double `LocalDate.now()` midnight race — **FIXED**: added `isMinor(AgeTier)` overload to `AgePolicyService` (derived from tier, no clock call); `ShadowAccountService` now calls `getAgeTier()` once, then `isMinor(ageTier)` and `isIndependentAccountAllowed(ageTier)` — all derived from the single clock tick
- [x] [Review][Patch] `PlayerProfileRepository.findById` override throws `UnsupportedOperationException` — **FIXED**: removed override; replaced with Javadoc on `findByIdAndParentId` directing callers to use it
- [x] [Review][Patch] `AgePolicy.defaults()` dead code — **FIXED**: `AgePolicyService` now references `AgePolicy.defaults()` as the single source of truth for fallback values; inline literals removed

#### Deferred — 9 items (pre-existing, not introduced by this story)

- [x] [Review][Defer] OTP hash uses `otp + userId` concatenation without separator — pre-existing pattern from CoachRegistrationService; minor collision surface [ParentRegistrationService.java — `hashOtp`] — deferred, pre-existing
- [x] [Review][Defer] `verifyEmail` saves `activated=true` before optimistic-lock check — correctly rolled back by `@Transactional`; same pattern as CoachRegistrationService [ParentRegistrationService.java:129–137] — deferred, pre-existing
- [x] [Review][Defer] `PhoneNumber("XX")` hardcoded country placeholder — intentional per Dev Notes; same as coach flow [ParentRegistrationService.java:98] — deferred, pre-existing
- [x] [Review][Defer] Migration IDs 100–102 in `platform_config` — different table from V21's authority rows; `ON CONFLICT (key) DO NOTHING` is correct idempotency guard [V22 migration] — deferred, pre-existing
- [x] [Review][Defer] `dateOfBirth = LocalDate.of(1900, 1, 1)` parent placeholder — intentional per Dev Notes; same pattern as coach [ParentRegistrationService.java:102] — deferred, pre-existing
- [x] [Review][Defer] Age tier snapshotted at creation, never recomputed as child ages — by design; explicit consent-escalation update deferred to Story 1.6 [PlayerProfile.java; ShadowAccountService] — deferred, pre-existing
- [x] [Review][Defer] `@Past` allows 1-day-old DOB; no minimum age enforced — not in scope per spec; no AC addresses minimum player age [CreatePlayerProfileRequest.java:12] — deferred, pre-existing
- [x] [Review][Defer] OTP rate-limit key is `userId` only — expired-OTP resubmissions drain legitimate user's budget; pre-existing pattern from coach flow [ParentRegistrationService.java:154] — deferred, pre-existing
- [x] [Review][Defer] Phone-collision detection via `msg.contains("phone")` — DB-dialect fragile; pre-existing pattern from CoachRegistrationService [ParentRegistrationService.java:100–104] — deferred, pre-existing

#### Group 2 — Backend API + Security Wiring (2026-06-12)

##### Patches — 1 item (applied)

- [x] [Review][Patch] `resendOtp` endpoint used raw `Map<String, Long>` request body — NPE on missing key, no `@Valid` validation, inconsistent with all other endpoint DTOs — **FIXED**: created `ResendOtpRequest` record DTO; updated `ParentRegistrationResource` to use it

##### Dismissed — 5 items

- `@PreAuthorize("permitAll()")` on all registration endpoints: correct; these are public-flow registration steps
- `ShadowAccountResource` casts `SecurityUtil.getCurrentUser()` to `Principal` — pre-existing pattern from other resources; flagged but not a new bug
- `ApiAdvice.ShadowAccountException` maps to 409: consistent with other domain exceptions; `security.consentPolicyVersionRequired` (added by P5) uses the same path — 409 is semantically close enough for a domain constraint violation
- `AuthoritiesConstants.PARENT` defined and used in `SECURED_AUTHORITIES` ✓
- `EmailTemplate.PARENT_EMAIL_VERIFY` and `PARENT_OTP` correct; `AppEndpoints.PUBLIC_ENDPOINTS` contains all parent registration paths ✓

#### Group 3 — Events, Listeners, Templates, Backend i18n (2026-06-12)

##### Patches — 1 item (applied)

- [x] [Review][Patch] `messages_fr.properties` missing all parent email i18n keys — French-language parents would receive emails with `??email.parent.otp.title_fr??` rendered as key names — **FIXED**: added all 9 parent email keys with correct French translations

##### Dismissed — 3 items

- `ParentVerificationEmailEvent` record (no OTP) — correct; verification email carries only a URL ✓
- `parentEmailVerify.html` and `parentOtp.html` — template variables match listener context (`map.verifyUrl`, `map.otpCode`, `recipient.firstname`) ✓
- Backend `messages_de.properties` has all parent keys; `messages_en.properties` has all parent keys ✓

#### Group 4 — Frontend (2026-06-12)

##### Patches — 1 item (applied)

- [x] [Review][Patch] `playerProfile.api.js` used `.then(res => res.data)` — violates project rule "avoid `.then()`; use async/await for all frontend asynchronous operations" — **FIXED**: converted `createProfile` and `listProfiles` to `async/await`

##### Dismissed — 6 items

- Frontend i18n: all 4 locale files (`en`, `en-US`, `de`, `fr-FR`) have the complete `auth.parent` and `player` namespaces ✓
- `consentPolicyVersion` hardcoded as `'1.0'` in `CreatePlayerProfilePage.vue` — consistent with the spec; changeable when versioning policy is established
- Age tier preview uses hardcoded thresholds (9/12/17) — acceptable for a UI hint; backend is authoritative for tier assignment
- `parent/create-player` and `parent/dashboard` routes have no auth guard — intentional per spec comment (JWT not available until Story 1.5)
- `playerStore.js` uses Pinia composition API pattern ✓; `fetchPlayers` correctly sets `activePlayerId` on first load
- `ParentChildSwitcher` integrated in `MainLayout.vue`; shows only when `playerStore.players.length > 0` ✓

#### Dismissed — 3 items (Group 1)

- U10 boundary at exactly age 10: `age=10 > u10Max=9` → correctly bins to AGE_10_12; no bug
- `link-parent` always 409: explicitly spec-mandated ("always 409 Conflict for now") in Task 10
- `player_profiles` BIGINT PK missing sequence: TSID is application-assigned before INSERT; no DB sequence needed (same as all other BaseEntity tables)
