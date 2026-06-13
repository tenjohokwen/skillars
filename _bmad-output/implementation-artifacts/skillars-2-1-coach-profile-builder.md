# Story skillars-2.1: Coach Profile Builder

Status: done

## Story

As a newly verified coach,
I want to complete a guided profile builder so my listing goes live on the marketplace,
So that parents and players can find and book me without requiring admin approval.

## Acceptance Criteria

1. **AC 1: Post-Verification Onboarding Redirect** — Given a coach has `BASIC_VERIFIED` status and logs in for the first time, when authentication succeeds, then they are redirected to `/coach/profile-builder` (Step 1). The Command Center is not accessible until all 5 steps are complete and profile status is `ACTIVE`. A progress indicator shows current step and total steps throughout the flow.

2. **AC 2: Step 1 — Identity & Location** — Given the coach is on Step 1, when they submit display name, bio, city, district, and coaching languages (plus auto-detected `canonicalTimezone`), then values are saved to `coach_profiles` (status: `DRAFT`). `ContactDetailSanitizer` runs server-side on `bio` before persistence — detected email/phone is silently redacted. `canonicalTimezone` is auto-detected from browser `Intl.DateTimeFormat().resolvedOptions().timeZone` and must not be blank.

3. **AC 3: Step 2 — Specialties & Age Groups** — Given the coach is on Step 2, when they select specializations and age groups, then selections are saved to `coach_specialties` and `coach_age_groups`. At least one specialization and one age group must be selected before advancing.

4. **AC 4: Step 3 — Pricing** — Given the coach is on Step 3, when they configure per-session price and optional session packs, then data is saved to `coach_pricing` (currency locked to `EUR`) and `session_packs`. Per-session price is required; packs are optional.

5. **AC 5: Step 4 — Availability** — Given the coach is on Step 4, when they define recurring weekly windows (day, start, end, timezone), then windows are saved to `coach_availability_windows`. Times are stored in UTC derived from `canonicalTimezone`. At least one window required before advancing.

6. **AC 6: Step 5 — Profile Photo** — Given the coach is on Step 5, when they upload a JPEG/PNG ≤5MB via the `filestorage` module and confirm the upload, then the confirmed file URL is submitted and saved to `coach_profiles.photo_url`. A "Skip for now" option advances without a photo.

7. **AC 7: Profile Publication** — Given the coach completes Step 5, when they submit the final step, then `coach_profiles.status` transitions from `DRAFT` to `ACTIVE`, `coach_subscriptions` row is created with tier `SCOUT`, the profile is immediately visible on the marketplace, and the coach is redirected to `/coach/command-center`.

8. **AC 8: Mid-Flow Exit & Resume** — Given a coach exits mid-flow and returns, when they log in again, then they resume from the last incomplete step. Completed step data is not lost. `DRAFT` profiles are never indexed or visible on the marketplace.

9. **AC 9: Bio Re-Edit & Sanitization** — Given a coach re-edits their bio after going live, when they save the updated bio, then `ContactDetailSanitizer` runs server-side again before persistence. The sanitized value is reflected immediately on next load.

## Tasks / Subtasks

- [x] Task 1: Flyway V26 — marketplace schema (AC: 2, 3, 4, 5, 6, 7)
  - [x] Create `src/main/resources/db/migration/V26__marketplace_coach_profiles.sql` with a new `marketplace` schema and these tables:
    ```sql
    CREATE SCHEMA IF NOT EXISTS marketplace;

    CREATE TABLE marketplace.coach_profiles (
        id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
        user_id          BIGINT      NOT NULL UNIQUE REFERENCES main.skillars_users(id),
        display_name     VARCHAR(120) NOT NULL,
        bio              TEXT,
        city             VARCHAR(100),
        district         VARCHAR(100),
        languages        VARCHAR[]   NOT NULL DEFAULT '{}',
        canonical_timezone VARCHAR(64) NOT NULL,
        photo_url        VARCHAR(512),
        status           VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
        created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
        CONSTRAINT chk_coach_profile_status CHECK (status IN ('DRAFT','ACTIVE'))
    );

    CREATE TABLE marketplace.coach_specialties (
        id       UUID     PRIMARY KEY DEFAULT gen_random_uuid(),
        coach_id UUID     NOT NULL REFERENCES marketplace.coach_profiles(id),
        skill    VARCHAR(100) NOT NULL,
        CONSTRAINT uq_coach_specialty UNIQUE (coach_id, skill)
    );

    CREATE TABLE marketplace.coach_age_groups (
        id       UUID     PRIMARY KEY DEFAULT gen_random_uuid(),
        coach_id UUID     NOT NULL REFERENCES marketplace.coach_profiles(id),
        age_tier VARCHAR(20) NOT NULL,
        CONSTRAINT uq_coach_age_group UNIQUE (coach_id, age_tier),
        CONSTRAINT chk_age_tier CHECK (age_tier IN ('U10','AGE_10_12','AGE_13_17','ADULT'))
    );

    CREATE TABLE marketplace.coach_pricing (
        coach_id          UUID        PRIMARY KEY REFERENCES marketplace.coach_profiles(id),
        per_session_price NUMERIC(10,2) NOT NULL,
        currency          VARCHAR(3)  NOT NULL DEFAULT 'EUR',
        CONSTRAINT chk_coach_pricing_currency CHECK (currency = 'EUR')
    );

    CREATE TABLE marketplace.session_packs (
        id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
        coach_id      UUID        NOT NULL REFERENCES marketplace.coach_profiles(id),
        session_count INT         NOT NULL CHECK (session_count > 0),
        total_price   NUMERIC(10,2) NOT NULL,
        label         VARCHAR(100),
        CONSTRAINT uq_session_pack UNIQUE (coach_id, session_count)
    );

    CREATE TABLE marketplace.coach_availability_windows (
        id                 UUID     PRIMARY KEY DEFAULT gen_random_uuid(),
        coach_id           UUID     NOT NULL REFERENCES marketplace.coach_profiles(id),
        day_of_week        SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
        start_time         TIME     NOT NULL,
        end_time           TIME     NOT NULL,
        canonical_timezone VARCHAR(64) NOT NULL,
        CONSTRAINT chk_availability_time_order CHECK (end_time > start_time)
    );

    CREATE TABLE marketplace.coach_subscriptions (
        coach_id     UUID        PRIMARY KEY REFERENCES marketplace.coach_profiles(id),
        tier         VARCHAR(20) NOT NULL DEFAULT 'SCOUT',
        active_since TIMESTAMPTZ NOT NULL DEFAULT now(),
        CONSTRAINT chk_coach_subscription_tier CHECK (tier IN ('SCOUT','INSTRUCTOR','ACADEMY'))
    );

    CREATE INDEX idx_coach_profiles_user_id  ON marketplace.coach_profiles(user_id);
    CREATE INDEX idx_coach_profiles_status   ON marketplace.coach_profiles(status);
    CREATE INDEX idx_coach_specialties_coach ON marketplace.coach_specialties(coach_id);
    CREATE INDEX idx_coach_age_groups_coach  ON marketplace.coach_age_groups(coach_id);
    CREATE INDEX idx_availability_coach      ON marketplace.coach_availability_windows(coach_id);
    ```
  - [x] Verify V26 is the next free version — V25 was `age_policy_config_seed.sql`

- [x] Task 2: Create `platform.marketplace` module structure (AC: 2–9)
  - [x] Create package hierarchy under `src/main/java/com/softropic/skillars/platform/marketplace/`:
    - `api/` — REST controllers
    - `service/` — business logic
    - `repo/` — JPA entities + repositories
    - `contract/` — DTO records, enums, exceptions
    - `config/` — Spring @Configuration

- [x] Task 3: JPA Entities in `platform.marketplace.repo` (AC: 2–7)
  - [x] `CoachProfile.java` — `@Entity @Table(schema="marketplace", name="coach_profiles")`:
    ```java
    @Entity @Table(schema = "marketplace", name = "coach_profiles")
    @Getter @Setter
    public class CoachProfile {
        @Id @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        @Column(name = "user_id", nullable = false, unique = true)
        private Long userId;

        @Column(name = "display_name", nullable = false)
        private String displayName;

        @Column(columnDefinition = "TEXT")
        private String bio;

        private String city;
        private String district;

        @Column(columnDefinition = "varchar[]")
        @Type(ListArrayType.class)   // io.hypersistence:hypersistence-utils
        private List<String> languages = new ArrayList<>();

        @Column(name = "canonical_timezone", nullable = false)
        private String canonicalTimezone;

        @Column(name = "photo_url")
        private String photoUrl;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false)
        private CoachProfileStatus status = CoachProfileStatus.DRAFT;

        @Column(name = "created_at", nullable = false, updatable = false)
        private OffsetDateTime createdAt = OffsetDateTime.now();
    }
    ```
  - [x] **IMPORTANT:** Check if `io.hypersistence:hypersistence-utils` is already in `build.gradle`. If not, use `@JdbcTypeCode(SqlTypes.ARRAY)` from Hibernate 6 directly:
    ```java
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "varchar[]")
    private List<String> languages = new ArrayList<>();
    ```
    Run `grep -r "hypersistence\|ListArrayType" build.gradle` to check first.
  - [x] `CoachProfileStatus.java` enum: `DRAFT, ACTIVE`
  - [x] `CoachSpecialty.java` — `@Entity @Table(schema="marketplace", name="coach_specialties")` with `UUID id`, `UUID coachId`, `String skill`
  - [x] `CoachAgeGroup.java` — `@Entity @Table(schema="marketplace", name="coach_age_groups")` with `UUID id`, `UUID coachId`, `AgeTier ageTier` (reuse `com.softropic.skillars.platform.security.contract.AgeTier`)
  - [x] `CoachPricing.java` — `@Entity @Table(schema="marketplace", name="coach_pricing")` with `UUID coachId` (`@Id`), `BigDecimal perSessionPrice`, `String currency = "EUR"`
  - [x] `SessionPack.java` — `@Entity @Table(schema="marketplace", name="session_packs")` with `UUID id`, `UUID coachId`, `int sessionCount`, `BigDecimal totalPrice`, `String label`
  - [x] `CoachAvailabilityWindow.java` — `@Entity @Table(schema="marketplace", name="coach_availability_windows")` with `UUID id`, `UUID coachId`, `short dayOfWeek`, `LocalTime startTime`, `LocalTime endTime`, `String canonicalTimezone`
  - [x] `CoachSubscription.java` — `@Entity @Table(schema="marketplace", name="coach_subscriptions")` with `UUID coachId` (`@Id`), `CoachSubscriptionTier tier = SCOUT`, `OffsetDateTime activeSince`
  - [x] `CoachSubscriptionTier.java` enum: `SCOUT, INSTRUCTOR, ACADEMY`
  - [x] Repositories (each `JpaRepository`):
    - `CoachProfileRepository` — add `Optional<CoachProfile> findByUserId(Long userId)` and `boolean existsByUserId(Long userId)`
    - `CoachSpecialtyRepository` — add `List<CoachSpecialty> findByCoachId(UUID coachId)` and `void deleteByCoachId(UUID coachId)`
    - `CoachAgeGroupRepository` — add `List<CoachAgeGroup> findByCoachId(UUID coachId)` and `void deleteByCoachId(UUID coachId)`
    - `CoachPricingRepository` — `Optional<CoachPricing> findByCoachId(UUID coachId)`
    - `SessionPackRepository` — `List<SessionPack> findByCoachId(UUID coachId)` and `void deleteByCoachId(UUID coachId)`
    - `CoachAvailabilityWindowRepository` — `List<CoachAvailabilityWindow> findByCoachId(UUID coachId)` and `void deleteByCoachId(UUID coachId)`
    - `CoachSubscriptionRepository` — `Optional<CoachSubscription> findByCoachId(UUID coachId)`

- [x] Task 4: DTO records in `platform.marketplace.contract` (AC: 2–7)
  - [x] `ProfileBuilderStep1Request.java`:
    ```java
    public record ProfileBuilderStep1Request(
        @NotBlank @Size(max=120) String displayName,
        @Size(max=2000) String bio,
        @Size(max=100) String city,
        @Size(max=100) String district,
        @NotEmpty List<@NotBlank String> languages,
        @NotBlank String canonicalTimezone
    ) {}
    ```
  - [x] `ProfileBuilderStep2Request.java`:
    ```java
    public record ProfileBuilderStep2Request(
        @NotEmpty List<@NotBlank String> specialties,
        @NotEmpty List<AgeTier> ageGroups
    ) {}
    ```
  - [x] `ProfileBuilderStep3Request.java`:
    ```java
    public record ProfileBuilderStep3Request(
        @NotNull @DecimalMin("0.01") BigDecimal perSessionPrice,
        List<SessionPackRequest> sessionPacks  // nullable = optional
    ) {
        public record SessionPackRequest(
            @Positive int sessionCount,
            @NotNull @DecimalMin("0.01") BigDecimal totalPrice,
            @Size(max=100) String label
        ) {}
    }
    ```
  - [x] `ProfileBuilderStep4Request.java`:
    ```java
    public record ProfileBuilderStep4Request(
        @NotEmpty List<AvailabilityWindowRequest> windows
    ) {
        public record AvailabilityWindowRequest(
            @Min(1) @Max(7) short dayOfWeek,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @NotBlank String canonicalTimezone
        ) {}
    }
    ```
  - [x] `ProfileBuilderStep5Request.java`:
    ```java
    public record ProfileBuilderStep5Request(
        String photoUrl  // null if skipped
    ) {}
    ```
  - [x] `ProfileBuilderStatusResponse.java`:
    ```java
    public record ProfileBuilderStatusResponse(
        UUID coachId,
        CoachProfileStatus status,
        int lastCompletedStep,  // 0 = not started, 1-5
        boolean profileComplete
    ) {}
    ```
  - [x] `ProfileBuilderStepResponse.java`:
    ```java
    public record ProfileBuilderStepResponse(UUID coachId, int stepSaved, int nextStep) {}
    ```
  - [x] `MarketplaceException.java` — `extends RuntimeException` with `String errorCode`:
    ```java
    public class MarketplaceException extends RuntimeException {
        private final String errorCode;
        public MarketplaceException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
        public String getErrorCode() { return errorCode; }
    }
    ```

- [x] Task 5: MapStruct mapper in `platform.marketplace.contract` (AC: 2–5)
  - [x] `CoachProfileMapper.java` — `@Mapper(componentModel = "spring")`:
    - `CoachProfile toEntity(ProfileBuilderStep1Request req, Long userId)` — bio is NOT mapped here (sanitization happens first)
    - Helper mapping methods for specialties and availability windows

- [x] Task 6: `CoachProfileService` in `platform.marketplace.service` (AC: 2–9)
  - [x] Inject: `CoachProfileRepository`, `CoachSpecialtyRepository`, `CoachAgeGroupRepository`, `CoachPricingRepository`, `SessionPackRepository`, `CoachAvailabilityWindowRepository`, `CoachSubscriptionRepository`, `ContactDetailSanitizer`
  - [x] `getOrCreateDraft(Long userId)` — finds existing DRAFT or creates new `CoachProfile` row
  - [x] `saveStep1(Long userId, ProfileBuilderStep1Request req)`:
    - Call `contactDetailSanitizer.sanitize(req.bio())` before assigning to entity
    - Set `canonicalTimezone` from request
    - Save via `CoachProfileRepository`
    - Return `ProfileBuilderStepResponse(coachId, 1, 2)`
  - [x] `saveStep2(Long userId, ProfileBuilderStep2Request req)`:
    - Load coach profile by userId
    - Delete existing rows for this coachId: `coachSpecialtyRepository.deleteByCoachId(coachId)`
    - Persist new `CoachSpecialty` rows
    - Delete and re-insert `CoachAgeGroup` rows
    - Return step response
  - [x] `saveStep3(Long userId, ProfileBuilderStep3Request req)`:
    - Upsert `CoachPricing` (save or update if exists)
    - Delete and re-insert `SessionPack` rows if provided
    - Currency is always `EUR` — ignore any client value
    - Return step response
  - [x] `saveStep4(Long userId, ProfileBuilderStep4Request req)`:
    - Delete and re-insert `CoachAvailabilityWindow` rows
    - Timezone conversion note: store times as-is in `canonical_timezone` — UTC conversion happens in Epic 3 (booking engine). For this story, store the window exactly as submitted with its timezone.
    - Return step response
  - [x] `saveStep5(Long userId, ProfileBuilderStep5Request req)`:
    - Update `coach_profiles.photo_url` if `req.photoUrl()` is non-null
    - If null/skipped, leave `photo_url` as-is
    - Return step response
  - [x] `publishProfile(Long userId)`:
    - Load profile, verify status is `DRAFT`
    - Validate all required steps complete (profile has displayName, at least 1 specialty, 1 age group, perSessionPrice, 1 availability window)
    - Set `status = ACTIVE`
    - Create `CoachSubscription(coachId, tier=SCOUT, activeSince=now())`
    - Save both in `@Transactional`
    - Return `ProfileBuilderStatusResponse` with `profileComplete=true`
  - [x] `getBuilderStatus(Long userId)`:
    - Load profile if exists; if not, return status with `lastCompletedStep=0`
    - Determine last completed step by checking which tables have data
    - Return `ProfileBuilderStatusResponse`

- [x] Task 7: `ApiAdvice` handler for `MarketplaceException` (AC: 7)
  - [x] In `platform.security.api.ApiAdvice.java`, add handler:
    ```java
    @ExceptionHandler(MarketplaceException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorDto marketplaceExceptionHandler(final MarketplaceException ex) {
        log.warn("Marketplace error: code={} msg={}", ex.getErrorCode(), ex.getMessage());
        return logErrorAndReturnDTO(ex, ex.getMessage(), ex.getErrorCode());
    }
    ```
  - [x] Add import: `import com.softropic.skillars.platform.marketplace.contract.MarketplaceException;`
  - [x] **WHY ApiAdvice lives in platform.security.api:** It is the global `@RestControllerAdvice` for all modules. Pattern established in Story 1.5 (`FeatureGatedException` handler). Check line count before adding — keep handlers in logical order (more specific before broad).

- [x] Task 8: `CoachProfilePhotoEventListener` in `platform.marketplace.service` (AC: 6)
  - [x] Create event listener for `StorageObjectConfirmedEvent` from filestorage module:
    ```java
    @Component
    @RequiredArgsConstructor
    @Slf4j
    public class CoachProfilePhotoEventListener {
        private final CoachProfileRepository coachProfileRepository;

        @EventListener
        @Transactional
        public void onStorageConfirmed(StorageObjectConfirmedEvent event) {
            FileStorageObject fso = event.getStorageObject();
            if (!"coach_profile".equals(fso.getEntity())) {
                return;
            }
            // entityId is the userId (Long string) set by frontend in SignUploadRequest
            Long userId = Long.parseLong(fso.getEntityId());
            coachProfileRepository.findByUserId(userId).ifPresent(profile -> {
                profile.setPhotoUrl(fso.getKey());
                coachProfileRepository.save(profile);
                log.info("Updated coach profile photo: userId={}", userId);
            });
        }
    }
    ```
  - [x] **NOTE on entityId:** The frontend passes the coach's userId (Long as String) as `entityId` in `SignUploadRequest`. The event listener extracts it to look up the coach profile. This is the cross-module bridge — no repository injection needed from filestorage module.
  - [x] Verify `FileStorageObject` fields: check `getEntity()`, `getEntityId()`, `getKey()` exist in `platform.filestorage.repo.FileStorageObject`

- [x] Task 9: `ProfileBuilderResource` in `platform.marketplace.api` (AC: 1–9)
  - [x] Create `ProfileBuilderResource.java`:
    ```java
    @Observed(name = "marketplace.profile_builder")
    @RestController
    @RequestMapping("/api/marketplace/coaches/me/profile")
    @RequiredArgsConstructor
    @Slf4j
    public class ProfileBuilderResource {
        private final CoachProfileService coachProfileService;
        private final SecurityUtil securityUtil;

        @GetMapping("/status")
        @PreAuthorize("hasRole('ROLE_COACH')")
        public ResponseEntity<ProfileBuilderStatusResponse> getStatus() {
            Long userId = currentUserId();
            return ResponseEntity.ok(coachProfileService.getBuilderStatus(userId));
        }

        @PutMapping("/steps/1")
        @PreAuthorize("hasRole('ROLE_COACH')")
        public ResponseEntity<ProfileBuilderStepResponse> saveStep1(
                @RequestBody @Valid ProfileBuilderStep1Request req) {
            return ResponseEntity.ok(coachProfileService.saveStep1(currentUserId(), req));
        }

        @PutMapping("/steps/2")
        @PreAuthorize("hasRole('ROLE_COACH')")
        public ResponseEntity<ProfileBuilderStepResponse> saveStep2(
                @RequestBody @Valid ProfileBuilderStep2Request req) {
            return ResponseEntity.ok(coachProfileService.saveStep2(currentUserId(), req));
        }

        @PutMapping("/steps/3")
        @PreAuthorize("hasRole('ROLE_COACH')")
        public ResponseEntity<ProfileBuilderStep3Response> saveStep3(  // fix return type
                @RequestBody @Valid ProfileBuilderStep3Request req) {
            return ResponseEntity.ok(coachProfileService.saveStep3(currentUserId(), req));
        }

        @PutMapping("/steps/4")
        @PreAuthorize("hasRole('ROLE_COACH')")
        public ResponseEntity<ProfileBuilderStepResponse> saveStep4(
                @RequestBody @Valid ProfileBuilderStep4Request req) {
            return ResponseEntity.ok(coachProfileService.saveStep4(currentUserId(), req));
        }

        @PutMapping("/steps/5")
        @PreAuthorize("hasRole('ROLE_COACH')")
        public ResponseEntity<ProfileBuilderStepResponse> saveStep5(
                @RequestBody @Valid ProfileBuilderStep5Request req) {
            return ResponseEntity.ok(coachProfileService.saveStep5(currentUserId(), req));
        }

        @PostMapping("/publish")
        @PreAuthorize("hasRole('ROLE_COACH')")
        public ResponseEntity<ProfileBuilderStatusResponse> publish() {
            return ResponseEntity.ok(coachProfileService.publishProfile(currentUserId()));
        }

        private Long currentUserId() {
            return Long.parseLong(((Principal) securityUtil.getCurrentUser()).getBusinessId());
        }
    }
    ```
  - [x] Note: `hasRole('ROLE_COACH')` used inline since `SecurityConstants.HAS_COACH_ROLE` does not exist. Optionally add `public static final String HAS_COACH_ROLE = "hasRole('ROLE_COACH')"` to `SecurityConstants.java` for consistency — check with team.

- [x] Task 10: `MarketplaceConfig` in `platform.marketplace.config` (AC: structure)
  - [x] Create `MarketplaceConfig.java` — empty `@Configuration` class to make module self-contained and allow future bean declarations.

- [x] Task 11: Frontend — `marketplace.api.js` (AC: 1–9)
  - [x] Create `src/frontend/src/api/marketplace.api.js`:
    ```js
    import { api } from 'src/boot/axios'

    export const getProfileBuilderStatus = () =>
      api.get('/api/marketplace/coaches/me/profile/status')

    export const saveProfileBuilderStep = (stepNumber, data) =>
      api.put(`/api/marketplace/coaches/me/profile/steps/${stepNumber}`, data)

    export const publishProfile = () =>
      api.post('/api/marketplace/coaches/me/profile/publish')
    ```

- [x] Task 12: Frontend — `profileBuilder.store.js` (Pinia) (AC: 1, 7, 8)
  - [x] Create `src/frontend/src/stores/profileBuilder.store.js`:
    ```js
    import { defineStore } from 'pinia'
    import { ref, computed } from 'vue'
    import { getProfileBuilderStatus, saveProfileBuilderStep, publishProfile } from 'src/api/marketplace.api'

    export const useProfileBuilderStore = defineStore('profileBuilder', () => {
      const status = ref(null)  // ProfileBuilderStatusResponse
      const currentStep = ref(1)
      const loading = ref(false)
      const error = ref(null)

      const isComplete = computed(() => status.value?.profileComplete === true)
      const lastCompletedStep = computed(() => status.value?.lastCompletedStep ?? 0)

      async function loadStatus() {
        loading.value = true
        error.value = null
        try {
          const res = await getProfileBuilderStatus()
          status.value = res.data
          currentStep.value = Math.min((res.data.lastCompletedStep ?? 0) + 1, 5)
        } catch (e) {
          error.value = e
        } finally {
          loading.value = false
        }
      }

      async function submitStep(stepNumber, data) {
        loading.value = true
        error.value = null
        try {
          await saveProfileBuilderStep(stepNumber, data)
          if (status.value) {
            status.value = { ...status.value, lastCompletedStep: stepNumber }
          }
          currentStep.value = stepNumber + 1
        } catch (e) {
          error.value = e
          throw e
        } finally {
          loading.value = false
        }
      }

      async function finishAndPublish() {
        loading.value = true
        error.value = null
        try {
          const res = await publishProfile()
          status.value = res.data
        } catch (e) {
          error.value = e
          throw e
        } finally {
          loading.value = false
        }
      }

      return { status, currentStep, loading, error, isComplete, lastCompletedStep, loadStatus, submitStep, finishAndPublish }
    })
    ```

- [x] Task 13: Frontend — `ProfileBuilderPage.vue` (AC: 1–9)
  - [x] Replace placeholder at `src/frontend/src/pages/auth/CoachProfileBuilderPlaceholderPage.vue` with full implementation:
    - **Do NOT create a new file** — update the existing placeholder
    - Multi-step Quasar `<q-stepper>` layout with 5 steps
    - On `onMounted`: call `profileBuilderStore.loadStatus()` to resume mid-flow
    - Each step is a separate `<component>` loaded by `currentStep` (see Task 14 for step components)
    - Step submissions call `profileBuilderStore.submitStep(n, formData)`
    - After step 5 + publish: call `profileBuilderStore.finishAndPublish()` then `router.push('/coach/command-center')`
    - Block navigation to Command Center if `!isComplete` (guard handled in router — verify `/coach/command-center` has `requiresAuth: true` and add a custom guard that checks auth.store for `ACTIVE` profile)
    - `canonicalTimezone` auto-detection in Step 1: `const tz = Intl.DateTimeFormat().resolvedOptions().timeZone`
    - Use `glass-card--static` CSS class (consistent with placeholder page already using it)
    - Profile photo upload in Step 5: call `/api/storage/sign/upload` with `entity: 'coach_profile'`, `entityId: String(userId)` → upload to pre-signed URL → call `/api/storage/confirm/{key}` → pass returned URL to step 5 submit
    - "Skip for now" in Step 5: call `submitStep(5, { photoUrl: null })` then publish
  - [x] Create step sub-components in `src/frontend/src/components/profileBuilder/`:
    - `ProfileBuilderStep1.vue` — display name, bio, city, district, languages (QChipSelect or multi-select), timezone (hidden field auto-populated)
    - `ProfileBuilderStep2.vue` — specialties (multi-select from predefined list), age groups (checkbox group: U10, 10–12, 13–17, Adult)
    - `ProfileBuilderStep3.vue` — per-session price (EUR, decimal input), session packs (dynamic add/remove rows)
    - `ProfileBuilderStep4.vue` — availability windows (dynamic add/remove: day-of-week dropdown + time pickers)
    - `ProfileBuilderStep5.vue` — file upload with `<q-file>` (accept=".jpg,.jpeg,.png", max-file-size=5242880), preview, skip button

- [x] Task 14: Frontend — Router update (AC: 1, 7)
  - [x] Update `src/frontend/src/router/routes.js`:
    - Profile builder route already exists at `coach/profile-builder` with `requiresAuth: true` — verify this is correct
    - Add navigation guard for Command Center: if auth user is `ROLE_COACH` and `verificationStatus !== 'ACTIVE'` (profile not complete), redirect to profile builder
    - Guard logic belongs in `src/frontend/src/router/index.js` (or `routes.js` meta), check where existing `requiresAuth` guard is implemented first
  - [x] Check `src/frontend/src/router/index.js` for existing `beforeEach` guard and extend it

- [x] Task 15: Frontend — i18n keys in all 4 locale files (AC: 2–9)
  - [x] Update `auth.coach` key block in all four locale files (`en/index.js`, `en-US/index.js`, `de/index.js`, `fr-FR/index.js`):
    ```js
    // English (en / en-US)
    profileBuilderTitle: 'Complete Your Profile',
    profileBuilderBody: 'Complete these 5 steps to go live on the marketplace.',
    step1Title: 'Identity & Location',
    step2Title: 'Specialties & Age Groups',
    step3Title: 'Pricing',
    step4Title: 'Availability',
    step5Title: 'Profile Photo',
    step5SkipLabel: 'Skip for now',
    publishSuccess: 'Your profile is live! Welcome to Skillars.',
    bioSanitizationWarning: 'Contact details will be removed on save.',
    ```
  - [x] After updating, run the i18n parity check:
    ```bash
    node -e "
    const en   = require('./src/frontend/src/i18n/en/index.js').default;
    const enUS = require('./src/frontend/src/i18n/en-US/index.js').default;
    const de   = require('./src/frontend/src/i18n/de/index.js').default;
    const frFR = require('./src/frontend/src/i18n/fr-FR/index.js').default;
    const k = o => JSON.stringify(Object.keys(o?.auth?.coach ?? {}).sort());
    const all = [k(en), k(enUS), k(de), k(frFR)];
    console.log('auth.coach key parity:', all.every(x => x === all[0]) ? 'OK' : 'MISMATCH');
    "
    ```

- [x] Task 16: Integration tests — `CoachProfileBuilderIT` (AC: 1–9)
  - [x] Create `src/test/java/com/softropic/skillars/platform/marketplace/api/CoachProfileBuilderIT.java`:
    - `@SpringBootTest(webEnvironment = RANDOM_PORT) @AutoConfigureTestDatabase(replace = NONE) @Testcontainers`
    - `@TestPropertySource(properties = "allowed.clients=testClientId")` — required for `ClientIdAccessDecisionManager`
    - Setup: Insert a `BASIC_VERIFIED` `ROLE_COACH` user via `JdbcTemplate` (follow pattern from `FamilyDataIsolationIT`; see note about NOT NULL columns below)
    - Login via `POST /api/auth/login` to obtain JWT cookies
    - Test cases:
      - `saveStep1_validRequest_returns200AndDraftCreated`
      - `saveStep1_bioWithEmail_sanitizedOnPersistence` — verify stored bio has email redacted
      - `saveStep1_missingDisplayName_returns400`
      - `saveStep2_validRequest_returns200`
      - `saveStep2_noSpecialties_returns400`
      - `saveStep3_validRequest_returns200`
      - `saveStep3_missingPerSessionPrice_returns400`
      - `saveStep4_validRequest_returns200`
      - `saveStep4_noWindows_returns400`
      - `saveStep5_withPhoto_returns200`
      - `saveStep5_skip_returns200WithNullPhotoUrl`
      - `publishProfile_allStepsComplete_returnsActiveStatus`
      - `publishProfile_missingStep_returns422` — test that publishing without completing all required steps fails
      - `getStatus_noDraft_returnsStep0`
      - `getStatus_afterStep2_returnsLastCompletedStep2`
      - `saveStep1_unauthenticated_returns401`
      - `saveStep1_asParent_returns403` — parent cannot access coach profile builder
    - All assertions use AssertJ
    - Verify Coach user insert includes: `iso2_country` (NOT NULL), `skillars_role = 'COACH'`, `verification_status = 'BASIC_VERIFIED'`
    - Long IDs serialize as JSON strings — use `Long.parseLong((String) body.get("userId"))` pattern from Story 1.6

## Dev Notes

### CRITICAL: `Principal.getBusinessId()` returns a Long string for coaches

```java
private Long currentUserId() {
    return Long.parseLong(((Principal) securityUtil.getCurrentUser()).getBusinessId());
}
```

**Do NOT use UUID for the userId lookup.** `Principal.getBusinessId()` is set as `String.valueOf(user.getId())` for all user types — it is a Long (the SkillarsUser PK). The marketplace `coach_profiles.id` (UUID) is a separate generated identifier for the marketplace entity. The bridge is `coach_profiles.user_id BIGINT REFERENCES main.skillars_users(id)`.

### CRITICAL: PostgreSQL array column for languages

Hibernate 6 (Spring Boot 3.5+) supports array types natively via `@JdbcTypeCode(SqlTypes.ARRAY)`:

```java
@JdbcTypeCode(SqlTypes.ARRAY)
@Column(columnDefinition = "varchar[]")
private List<String> languages = new ArrayList<>();
```

Run `grep -r "hypersistence\|ListArrayType" build.gradle` before deciding — if `hypersistence-utils` is already on classpath, you can use `@Type(ListArrayType.class)` which has better null-handling. If not present, use the Hibernate 6 native approach above.

### CRITICAL: ContactDetailSanitizer is in infrastructure, already implemented

`ContactDetailSanitizer` lives at `com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer`. It is already in production (used by `SanitizePreviewResource` in Story 1.5). **Do NOT recreate it.** Just inject it in `CoachProfileService`:

```java
private final ContactDetailSanitizer contactDetailSanitizer;
// ...
String sanitizedBio = contactDetailSanitizer.sanitize(req.bio());
profile.setBio(sanitizedBio);
```

Check the `ContactDetailSanitizer` signature: `sanitize(String text)` returns `String`. Verify with:
```bash
grep -n "public.*sanitize\|String sanitize" \
  src/main/java/com/softropic/skillars/infrastructure/sanitizer/ContactDetailSanitizer.java
```

### CRITICAL: `SanitizePreviewResource` already exists — do NOT recreate

`POST /api/util/sanitize-preview` is already live at `platform.security.api.SanitizePreviewResource` (built in Story 1.5). Story 2.4 will add the frontend amber warning bar UX. This story only needs server-side sanitization in `CoachProfileService`.

### CRITICAL: FileStorageObject entity fields — verify before writing listener

Before writing `CoachProfilePhotoEventListener`, verify the fields of `FileStorageObject`:
```bash
grep -n "entity\|entityId\|key\|@Column" \
  src/main/java/com/softropic/skillars/platform/filestorage/repo/FileStorageObject.java
```

The entity field names in `FileStorageObject` (e.g., `entity`, `entityId`, `key`) must match what the listener uses. The `SignUploadRequest` contract shows `entity` and `entityId` are set by the caller — the filestorage module stores them as-is.

### CRITICAL: `ApiAdvice` lives in `platform.security.api` — add handler there

The global `@RestControllerAdvice` is `ApiAdvice.java` in `platform.security.api`. All exception handlers for all modules live here. Confirmed pattern from Stories 1.5 and 1.6. Do NOT create a second `@RestControllerAdvice` in `platform.marketplace.api`.

### CRITICAL: TestPropertySource for `allowed.clients`

Every IT that calls the API must include:
```java
@TestPropertySource(properties = "allowed.clients=testClientId")
```
Without this, `ClientIdAccessDecisionManager` rejects all requests (403). Confirmed from Story 1.6 debug log.

### CRITICAL: `iso2_country` is NOT NULL in the users table

When inserting test users in `CoachProfileBuilderIT`, include `iso2_country` (e.g., `'DE'`). Missing this caused 11 test failures in `FamilyDataIsolationIT` (Story 1.6 debug log).

### CRITICAL: Long IDs serialize as JSON strings

`CommonConfig` registers `ToStringSerializer.instance` globally for `Long.class`. When reading response bodies, cast ID fields as:
```java
Long.parseLong((String) body.get("id"))
```
Not as `Integer` or `Long` directly. Confirmed bug in `FamilyDataIsolationIT`.

### CoachSubscriptionTier — AgeTier enum reuse

`AgeTier` (`U10, AGE_10_12, AGE_13_17, ADULT`) is in `platform.security.contract.AgeTier`. Reuse it in `CoachAgeGroup` entity — do NOT redeclare it in `platform.marketplace.contract`.

`CoachSubscriptionTier` (`SCOUT, INSTRUCTOR, ACADEMY`) is a new enum only needed in the marketplace module — create it at `platform.marketplace.contract.CoachSubscriptionTier`.

### Frontend: No placeholder page for profile builder

The existing file `CoachProfileBuilderPlaceholderPage.vue` at `src/frontend/src/pages/auth/` is a stub with only a title/body text. **Update this file in-place** — do NOT create a new `ProfileBuilderPage.vue`. The router already points to this exact path.

### Frontend: timezone auto-detection

```js
const canonicalTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone
// e.g. "Europe/Berlin" — included in Step 1 form submission
```

This is a standard browser API — no library needed.

### Frontend: file upload with Quasar

Use `<q-file>` for Step 5. The upload flow is:
1. User selects file → preview in component
2. On "Next"/"Save": call `signUpload({ entity: 'coach_profile', entityId: String(userId), contentType: 'image/jpeg', extension: 'jpg', fileSizeBytes: file.size })`
3. PUT file to pre-signed URL with `Content-Type` header
4. Call `confirmUpload(key, {})` → response includes the file URL
5. Call `saveProfileBuilderStep(5, { photoUrl: confirmedUrl })`

### Frontend: router guard for Command Center

The Command Center route at `/coach/command-center` should redirect coaches with incomplete profiles to the profile builder. Check the existing `beforeEach` guard location:
```bash
grep -n "beforeEach\|requiresAuth\|meta\." src/frontend/src/router/index.js | head -20
```

Add a coach-specific guard:
```js
if (to.path === '/coach/command-center' && authStore.userRole === 'ROLE_COACH') {
  const pbStore = useProfileBuilderStore()
  await pbStore.loadStatus()
  if (!pbStore.isComplete) {
    return next('/coach/profile-builder')
  }
}
```

### DRAFT visibility gate

`GET /api/marketplace/coaches` (Story 2.2) will filter `status = ACTIVE` — DRAFT profiles are never returned. No extra work needed in Story 2.1 beyond correctly setting status. But verify: Step 5 completion alone does NOT publish — `POST /api/marketplace/coaches/me/profile/publish` must be called after Step 5.

### Session pack currency lock

Currency is always `EUR`. The `CoachProfileService.saveStep3()` must ignore any `currency` field in the request and always persist `"EUR"`. The `ProfileBuilderStep3Request` should not include a `currency` field at all.

### Marketplace schema isolation

All marketplace tables live in the `marketplace` schema (not `main`). Flyway creates the schema in V26. Spring Boot app must be able to access this schema — verify `spring.datasource.url` includes `currentSchema=main,marketplace` or that Hibernate can find entities across schemas. Check existing `application.yml`:
```bash
grep -n "schema\|currentSchema\|datasource" src/main/resources/application.yml | head -15
```

If `currentSchema` is set to `main` only, either remove the restriction or add `marketplace` to the allowed schemas.

### Project Structure

**New backend files:**
```
src/main/resources/db/migration/
└── V26__marketplace_coach_profiles.sql

src/main/java/com/softropic/skillars/platform/marketplace/
├── api/
│   └── ProfileBuilderResource.java           (new)
├── service/
│   ├── CoachProfileService.java              (new)
│   └── CoachProfilePhotoEventListener.java   (new)
├── repo/
│   ├── CoachProfile.java                     (new @Entity)
│   ├── CoachProfileRepository.java           (new)
│   ├── CoachSpecialty.java                   (new @Entity)
│   ├── CoachSpecialtyRepository.java         (new)
│   ├── CoachAgeGroup.java                    (new @Entity)
│   ├── CoachAgeGroupRepository.java          (new)
│   ├── CoachPricing.java                     (new @Entity)
│   ├── CoachPricingRepository.java           (new)
│   ├── SessionPack.java                      (new @Entity)
│   ├── SessionPackRepository.java            (new)
│   ├── CoachAvailabilityWindow.java          (new @Entity)
│   ├── CoachAvailabilityWindowRepository.java(new)
│   ├── CoachSubscription.java                (new @Entity)
│   └── CoachSubscriptionRepository.java      (new)
├── contract/
│   ├── ProfileBuilderStep1Request.java       (new record)
│   ├── ProfileBuilderStep2Request.java       (new record)
│   ├── ProfileBuilderStep3Request.java       (new record)
│   ├── ProfileBuilderStep4Request.java       (new record)
│   ├── ProfileBuilderStep5Request.java       (new record)
│   ├── ProfileBuilderStatusResponse.java     (new record)
│   ├── ProfileBuilderStepResponse.java       (new record)
│   ├── CoachProfileStatus.java               (new enum)
│   ├── CoachSubscriptionTier.java            (new enum)
│   ├── CoachProfileMapper.java               (new @Mapper)
│   └── MarketplaceException.java             (new)
└── config/
    └── MarketplaceConfig.java                (new @Configuration)
```

**Modified backend files:**
- `platform/security/api/ApiAdvice.java` — add `MarketplaceException` handler (Task 7)

**New frontend files:**
```
src/frontend/src/api/
└── marketplace.api.js                        (new)

src/frontend/src/stores/
└── profileBuilder.store.js                   (new)

src/frontend/src/components/profileBuilder/
├── ProfileBuilderStep1.vue                   (new)
├── ProfileBuilderStep2.vue                   (new)
├── ProfileBuilderStep3.vue                   (new)
├── ProfileBuilderStep4.vue                   (new)
└── ProfileBuilderStep5.vue                   (new)
```

**Modified frontend files:**
- `src/frontend/src/pages/auth/CoachProfileBuilderPlaceholderPage.vue` — full implementation (Task 13)
- `src/frontend/src/router/index.js` — add Command Center guard (Task 14)
- `src/frontend/src/i18n/en/index.js`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/de/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`

**New test files:**
```
src/test/java/com/softropic/skillars/platform/marketplace/api/
└── CoachProfileBuilderIT.java                (new)
```

### Verification commands

```bash
# Verify no marketplace module exists yet (expected: nothing)
ls src/main/java/com/softropic/skillars/platform/marketplace 2>/dev/null || echo "OK: not yet created"

# Verify ContactDetailSanitizer signature
grep -n "public.*sanitize" \
  src/main/java/com/softropic/skillars/infrastructure/sanitizer/ContactDetailSanitizer.java

# Verify FileStorageObject fields
grep -n "entity\|entityId\|key\|@Column" \
  src/main/java/com/softropic/skillars/platform/filestorage/repo/FileStorageObject.java

# Verify next Flyway migration version
ls src/main/resources/db/migration/ | sort | tail -3

# Verify datasource schema config
grep -n "schema\|currentSchema" src/main/resources/application.yml | head -10

# Verify AgeTier enum location
find src -name "AgeTier.java" -path "*/security/*"

# Verify router beforeEach guard location
grep -n "beforeEach\|requiresAuth" src/frontend/src/router/index.js | head -10

# Verify i18n auth.coach block (both locales should match)
node -e "const en=require('./src/frontend/src/i18n/en/index.js').default; console.log(JSON.stringify(en.auth?.coach, null, 2))"
```

### References

- Story 1.5 (`skillars-1-5-authentication-jwt-security.md`) — `ApiAdvice` pattern, `SanitizePreviewResource` location, `ContactDetailSanitizer` confirmed in infrastructure
- Story 1.6 (`skillars-1-6-age-tier-enforcement-family-data-isolation.md`) — `@TestPropertySource(allowed.clients)`, Long-as-JSON-string bug, `iso2_country` NOT NULL, `Principal.getBusinessId()` returns Long string, `FamilyDataIsolationIT` setup pattern
- `project-context.md` — Java records for DTOs, `@PreAuthorize` on every endpoint, no entity return from controllers, MapStruct for mapping, Instancio for test data, AssertJ assertions
- `architecture.md#marketplace` — `platform.marketplace` bounded context, tables list, cross-module via events/REST only
- `ux-design-specification.md` — glassmorphism design tokens, glass-card CSS class, contact detail warning (UX-DR24 — frontend part deferred to Story 2.4), 5-step progressive onboarding, `glass-card--static` already used in placeholder

---

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

- Implemented full `platform.marketplace` module from scratch: 7 JPA entities, 7 repositories, 5 DTO request records, 2 response records, `CoachProfileService`, `ProfileBuilderResource`, `CoachProfilePhotoEventListener`, `MarketplaceConfig`, `CoachProfileMapper`.
- **Key divergence from spec (Task 8)**: `FileStorageObject` has no `entity`/`entityId` fields. The `StorageKeyGenerator` encodes entity+entityId in the key path (`{entity}/{entityId}/{year}/{month}/{uuid}.ext`). Event listener parses the key `split("/")[0]` for entity and `[1]` for entityId.
- **Key divergence from spec (Task 6)**: `ContactDetailSanitizer.sanitize()` returns `SanitizerResult`, not `String`. Used `.sanitized()` to extract the value.
- V26 migration: fixed `REFERENCES main.skillars_users(id)` → `REFERENCES main."user"(id)` (actual table name).
- `hypersistence-utils-hibernate-63` confirmed in pom.xml — used `@Type(ListArrayType.class)` for `varchar[]` array column.
- Added `HAS_COACH_ROLE = "hasRole('ROLE_COACH')"` to `SecurityConstants` for consistency with `HAS_PARENT_ROLE` pattern.
- All 16 IT test cases in `CoachProfileBuilderIT` cover happy path, validation errors, bio sanitization, auth/403 scenarios.
- i18n parity check passes: all 4 locales (en, en-US, de, fr-FR) have identical `auth.coach` key sets.
- fr-FR was missing the entire `auth.coach` block — added it with French translations.
- Router guard added for `/coach/command-center`: COACH users with incomplete profiles are redirected to `/coach/profile-builder`.

### File List

**New backend files:**
- src/main/resources/db/migration/V26__marketplace_coach_profiles.sql
- src/main/java/com/softropic/skillars/platform/marketplace/api/ProfileBuilderResource.java
- src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java
- src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfilePhotoEventListener.java
- src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfile.java
- src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java
- src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachSpecialty.java
- src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachSpecialtyRepository.java
- src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachAgeGroup.java
- src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachAgeGroupRepository.java
- src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachPricing.java
- src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachPricingRepository.java
- src/main/java/com/softropic/skillars/platform/marketplace/repo/SessionPack.java
- src/main/java/com/softropic/skillars/platform/marketplace/repo/SessionPackRepository.java
- src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachAvailabilityWindow.java
- src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachAvailabilityWindowRepository.java
- src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachSubscription.java
- src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachSubscriptionRepository.java
- src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachProfileStatus.java
- src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachSubscriptionTier.java
- src/main/java/com/softropic/skillars/platform/marketplace/contract/ProfileBuilderStep1Request.java
- src/main/java/com/softropic/skillars/platform/marketplace/contract/ProfileBuilderStep2Request.java
- src/main/java/com/softropic/skillars/platform/marketplace/contract/ProfileBuilderStep3Request.java
- src/main/java/com/softropic/skillars/platform/marketplace/contract/ProfileBuilderStep4Request.java
- src/main/java/com/softropic/skillars/platform/marketplace/contract/ProfileBuilderStep5Request.java
- src/main/java/com/softropic/skillars/platform/marketplace/contract/ProfileBuilderStatusResponse.java
- src/main/java/com/softropic/skillars/platform/marketplace/contract/ProfileBuilderStepResponse.java
- src/main/java/com/softropic/skillars/platform/marketplace/contract/MarketplaceException.java
- src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachProfileMapper.java
- src/main/java/com/softropic/skillars/platform/marketplace/config/MarketplaceConfig.java
- src/test/java/com/softropic/skillars/platform/marketplace/api/CoachProfileBuilderIT.java

**Modified backend files:**
- src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java
- src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java

**New frontend files:**
- src/frontend/src/api/marketplace.api.js
- src/frontend/src/stores/profileBuilder.store.js
- src/frontend/src/components/profileBuilder/ProfileBuilderStep1.vue
- src/frontend/src/components/profileBuilder/ProfileBuilderStep2.vue
- src/frontend/src/components/profileBuilder/ProfileBuilderStep3.vue
- src/frontend/src/components/profileBuilder/ProfileBuilderStep4.vue
- src/frontend/src/components/profileBuilder/ProfileBuilderStep5.vue

**Modified frontend files:**
- src/frontend/src/pages/auth/CoachProfileBuilderPlaceholderPage.vue
- src/frontend/src/router/index.js
- src/frontend/src/i18n/en/index.js
- src/frontend/src/i18n/en-US/index.js
- src/frontend/src/i18n/de/index.js
- src/frontend/src/i18n/fr-FR/index.js


## Change Log

- 2026-06-12: Initial implementation — all 16 tasks complete. Created marketplace schema (V26), full platform.marketplace module (7 entities, service, REST resource, event listener, MapStruct mapper), frontend profile builder (5 steps, Pinia store, router guard), i18n for all 4 locales, and CoachProfileBuilderIT integration tests.
