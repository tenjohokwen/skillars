# Story skillars-1.6: Age-Tier Enforcement & Family Data Isolation

Status: done

## Story

As a platform operator,
I want age-based restrictions and parent-scoped data isolation enforced uniformly across all service calls,
So that minor players are protected and no parent can ever access another family's data regardless of how the API is called.

## Acceptance Criteria

1. **Age tier calculation**: `AgePolicyService.getAgeTier(dateOfBirth)` returns the correct `AgeTier` for any date of birth: `U10` (age ≤ 9), `AGE_10_12` (age 10–12), `AGE_13_17` (age 13–17), `ADULT` (age ≥ 18). Tier boundaries are read from `ConfigService` (`security.age-policy.u10-max-age`, `security.age-policy.young-teen-max-age`, `security.age-policy.teen-max-age`) — these keys must be seeded. Calculation occurs in the `@Service` layer only.

2. **Parent ownership guard**: All player-scoped API endpoints that accept a `playerId` path variable use `@PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")`. If the authenticated parent does not own the player, the response is `403 Forbidden` with `ErrorDto` — never `404`.

3. **Repository parent isolation**: `PlayerProfileRepository` never exposes a `findByPlayerId(Long playerId)` method without a corresponding `parentId` filter. The existing `findByIdAndParentId()` is the sole single-player lookup method. `PlayerOwnershipGuard` MUST use this method internally.

4. **U10 messaging prohibition**: `AgePolicyService.getMessagingPolicy(Long playerId)` returns `MessagingPolicy(canMessage=false, parentVisible=true, directAllowed=false)` for a U10 player. No message endpoint is callable for U10 players (enforced by service layer when the messaging module is built; this story provides the policy contract).

5. **AGE_10_12 messaging**: `getMessagingPolicy()` returns `MessagingPolicy(canMessage=true, parentVisible=true, directAllowed=false)` — parent-managed only, all messages flagged `parentVisible`.

6. **AGE_13_17 messaging**: `getMessagingPolicy()` returns `MessagingPolicy(canMessage=true, parentVisible=true, directAllowed=true)` — player may message directly; all threads remain parent-visible permanently.

7. **Feature gate 403**: `FeatureGatedException` is a throwable exception for tier-gated service methods. `ApiAdvice` maps it to `403 Forbidden` with `ErrorDto` code `security.featureGated`. Future module stories throw this when a Scout-tier coach or free-tier parent attempts a premium-only action.

8. **Frontend feature gate overlay**: A reusable `FeatureGateOverlay.vue` component renders a soft teaser overlay (blurred slot content + upgrade CTA) when a feature is tier-gated. It is never a full-screen block. i18n keys added to all four locale files.

## Tasks / Subtasks

- [x] Task 1: Flyway V25 — seed age policy ConfigService keys (AC: 1)
  - [x] Create `V25__age_policy_config_seed.sql` in `src/main/resources/db/migration/`:
    ```sql
    INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
    VALUES
        (112, 'security.age-policy.u10-max-age',        '9',  'LONG', 'Max age (inclusive) for U10 tier',       NOW()),
        (113, 'security.age-policy.young-teen-max-age', '12', 'LONG', 'Max age (inclusive) for AGE_10_12 tier', NOW()),
        (114, 'security.age-policy.teen-max-age',       '17', 'LONG', 'Max age (inclusive) for AGE_13_17 tier', NOW())
    ON CONFLICT (key) DO NOTHING;
    ```
  - [x] IDs 112–114 are safe: V23 seeded IDs up to 111 (IDs 110–111); V24 was a DDL-only patch (no inserts)
  - [x] Verify the existing `AgePolicyService` uses exactly these key names — it does (`security.age-policy.u10-max-age` etc.)

- [x] Task 2: `MessagingPolicy` record in `platform.security.contract` (AC: 4, 5, 6)
  - [x] Create `src/main/java/com/softropic/skillars/platform/security/contract/MessagingPolicy.java`:
    ```java
    package com.softropic.skillars.platform.security.contract;

    public record MessagingPolicy(boolean canMessage, boolean parentVisible, boolean directAllowed) {

        public static MessagingPolicy prohibited() {
            return new MessagingPolicy(false, true, false);
        }

        public static MessagingPolicy parentManaged() {
            return new MessagingPolicy(true, true, false);
        }

        public static MessagingPolicy supervised() {
            return new MessagingPolicy(true, true, true);
        }

        public static MessagingPolicy unrestricted() {
            return new MessagingPolicy(true, false, true);
        }
    }
    ```

- [x] Task 3: Extend `AgePolicyService` with `getMessagingPolicy(Long playerId)` (AC: 4, 5, 6)
  - [x] Add `PlayerProfileRepository playerProfileRepository` field to constructor injection — import `com.softropic.skillars.platform.security.repo.PlayerProfileRepository`
  - [x] Add the following method:
    ```java
    public MessagingPolicy getMessagingPolicy(Long playerId) {
        PlayerProfile player = playerProfileRepository.findById(playerId)
            .orElseThrow(() -> new UserNotFoundException("Player profile not found: " + playerId));
        AgeTier tier = getAgeTier(player.getDateOfBirth());
        return switch (tier) {
            case U10       -> MessagingPolicy.prohibited();
            case AGE_10_12 -> MessagingPolicy.parentManaged();
            case AGE_13_17 -> MessagingPolicy.supervised();
            case ADULT     -> MessagingPolicy.unrestricted();
        };
    }
    ```
  - [x] Import: `com.softropic.skillars.platform.security.contract.MessagingPolicy`
  - [x] Import: `com.softropic.skillars.platform.security.repo.PlayerProfile`
  - [x] Import: `com.softropic.skillars.platform.security.contract.exception.UserNotFoundException`
  - [x] Do NOT cache the result — `AgePolicyService` is called fresh per request (architecture mandate: "call ConfigService per use — never cache in a field")

- [x] Task 4: `PlayerOwnershipGuard` Spring @Component (AC: 2, 3)
  - [x] Create `src/main/java/com/softropic/skillars/platform/security/service/PlayerOwnershipGuard.java`:
    ```java
    package com.softropic.skillars.platform.security.service;

    import com.softropic.skillars.platform.security.contract.Principal;
    import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
    import lombok.RequiredArgsConstructor;
    import org.springframework.security.core.Authentication;
    import org.springframework.stereotype.Component;

    @Component("playerOwnershipGuard")
    @RequiredArgsConstructor
    public class PlayerOwnershipGuard {

        private final PlayerProfileRepository playerProfileRepository;

        public boolean check(Authentication authentication, Long playerId) {
            if (authentication == null || !authentication.isAuthenticated()) {
                return false;
            }
            Object principal = authentication.getPrincipal();
            if (!(principal instanceof Principal skillarsP)) {
                return false;
            }
            try {
                Long parentId = Long.parseLong(skillarsP.getBusinessId());
                return playerProfileRepository.findByIdAndParentId(playerId, parentId).isPresent();
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }
    ```
  - [x] Bean name MUST be `"playerOwnershipGuard"` — matches SpEL expression `@playerOwnershipGuard.check(...)`
  - [x] Uses `findByIdAndParentId()` — the only safe player-by-id lookup (AC: 3)
  - [x] `Principal` is `com.softropic.skillars.platform.security.contract.Principal` (Skillars Principal, not `java.security.Principal`)

- [x] Task 5: `ShadowAccountService.getPlayerProfile()` + `ShadowAccountResource GET /{playerId}` (AC: 2)
  - [x] In `ShadowAccountService`, add:
    ```java
    @Transactional(readOnly = true)
    public PlayerProfileResponse getPlayerProfile(Long playerId, Long parentId) {
        PlayerProfile profile = playerProfileRepository.findByIdAndParentId(playerId, parentId)
            .orElseThrow(() -> new UserNotFoundException(playerId));
        return playerProfileMapper.toResponse(profile);
    }
    ```
    Note: `UserNotFoundException` (→ 404) is correct here — `ShadowAccountException` maps to 409 Conflict which is semantically wrong for a missing player on a GET endpoint.
  - [x] In `ShadowAccountResource`, add a `GET /{playerId}` endpoint with `@PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")`:
    ```java
    @PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")
    @GetMapping("/{playerId}")
    public ResponseEntity<PlayerProfileResponse> getPlayerProfile(@PathVariable Long playerId) {
        Long parentId = Long.parseLong(((Principal) securityUtil.getCurrentUser()).getBusinessId());
        return ResponseEntity.ok(shadowAccountService.getPlayerProfile(playerId, parentId));
    }
    ```
  - [x] This endpoint is also needed by `FamilyDataIsolationIT` for cross-family 403 verification

- [x] Task 6: `FeatureGatedException` + `ApiAdvice` handler (AC: 7)
  - [x] Create `src/main/java/com/softropic/skillars/platform/security/contract/exception/FeatureGatedException.java`:
    ```java
    package com.softropic.skillars.platform.security.contract.exception;

    public class FeatureGatedException extends RuntimeException {

        private final String requiredTier;
        private final String featureKey;

        public FeatureGatedException(String featureKey, String requiredTier) {
            super("Feature '" + featureKey + "' requires tier: " + requiredTier);
            this.requiredTier = requiredTier;
            this.featureKey = featureKey;
        }

        public String getRequiredTier() { return requiredTier; }
        public String getFeatureKey() { return featureKey; }
    }
    ```
  - [x] In `ApiAdvice.java`, add the following handler BEFORE the existing broad `secExceptionHandler`:
    ```java
    @ExceptionHandler(FeatureGatedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorDto featureGatedHandler(final FeatureGatedException ex) {
        log.warn("Feature gate blocked: feature={} requiredTier={}", ex.getFeatureKey(), ex.getRequiredTier());
        return logErrorAndReturnDTO(ex, ex.getMessage(), "security.featureGated");
    }
    ```
  - [x] Add import to `ApiAdvice.java`: `import com.softropic.skillars.platform.security.contract.exception.FeatureGatedException;`
  - [x] Confirm `logErrorAndReturnDTO` exists in `ApiAdvice` — it is used by `SkillarsAccountNotVerifiedException` handler (Story 1.5)
  - [x] Future pattern: any service checks `configService.find("coach.tier.scout.session_builder").map("true"::equals).orElse(false)` and throws `new FeatureGatedException("session_builder", "INSTRUCTOR")` if false

- [x] Task 7: Frontend — `FeatureGateOverlay.vue` component (AC: 8)
  - [x] Create `src/frontend/src/components/common/FeatureGateOverlay.vue`:
    ```vue
    <script setup>
    import { useI18n } from 'vue-i18n'

    const { t } = useI18n()

    const props = defineProps({
      requiredTier: {
        type: String,
        required: true,
      },
      featureLabel: {
        type: String,
        required: true,
      },
    })

    const emit = defineEmits(['upgrade-clicked'])
    </script>

    <template>
      <div class="feature-gate-wrapper">
        <div class="gate-preview-blur" aria-hidden="true">
          <slot />
        </div>
        <div class="gate-overlay glass-card">
          <p class="gate-tier-label text-caption q-mb-xs">
            {{ t('featureGate.requiredTier', { tier: requiredTier }) }}
          </p>
          <p class="gate-feature-label text-subtitle2 q-mb-md">{{ featureLabel }}</p>
          <p class="gate-description text-body2 q-mb-lg">
            {{ t('featureGate.description', { tier: requiredTier }) }}
          </p>
          <q-btn
            color="primary"
            :label="t('featureGate.upgradeCTA')"
            unelevated
            @click="emit('upgrade-clicked')"
          />
        </div>
      </div>
    </template>

    <style lang="scss" scoped>
    .feature-gate-wrapper {
      position: relative;
    }
    .gate-preview-blur {
      filter: blur(6px);
      pointer-events: none;
      user-select: none;
    }
    .gate-overlay {
      position: absolute;
      inset: 0;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      text-align: center;
      padding: 24px;
    }
    </style>
    ```
  - [x] Uses `glass-card` class from the Skillars design system (established in Story 1.2)
  - [x] Slot receives any content that should appear blurred behind the overlay
  - [x] Parent component MUST handle `upgrade-clicked` event to open the subscription upgrade flow

- [x] Task 8: i18n keys — add to all four locale files (AC: 8)
  - [x] Add to ALL FOUR files (`en/index.js`, `en-US/index.js`, `de/index.js`, `fr-FR/index.js`):
    ```js
    featureGate: {
      requiredTier: 'Requires {tier} plan',
      description: 'Upgrade to {tier} to unlock this feature and accelerate your coaching.',
      upgradeCTA: 'Upgrade plan',
    },
    security: {
      // ... existing keys preserved ...
      featureGated: 'This feature requires a higher subscription tier.',
    },
    ```
  - [x] German (`de/index.js`):
    ```js
    featureGate: {
      requiredTier: 'Erfordert {tier}-Plan',
      description: 'Upgraden Sie auf {tier}, um diese Funktion freizuschalten und Ihr Coaching zu beschleunigen.',
      upgradeCTA: 'Plan upgraden',
    },
    security: {
      featureGated: 'Diese Funktion erfordert ein höheres Abonnement-Tier.',
    },
    ```
  - [x] French (`fr-FR/index.js`):
    ```js
    featureGate: {
      requiredTier: 'Nécessite le plan {tier}',
      description: "Passez à {tier} pour débloquer cette fonctionnalité et accélérer votre coaching.",
      upgradeCTA: 'Mettre à niveau',
    },
    security: {
      featureGated: 'Cette fonctionnalité nécessite un niveau d\'abonnement supérieur.',
    },
    ```
  - [x] Verify `en-US` is the app default — all four files must have this key (missing = raw key in production)

- [x] Task 9: Tests — `AgePolicyServiceTest` (unit) (AC: 1, 4, 5, 6)
  - [x] Create `src/test/java/com/softropic/skillars/platform/security/service/AgePolicyServiceTest.java`:
    - `@ExtendWith(MockitoExtension.class)` — pure unit test, no Spring context
    - Mock `ConfigService` to return defaults (9, 12, 17) via `configService.find(key)` returning `Optional.empty()` (exercises `orElse(DEFAULTS)` path)
    - Mock `PlayerProfileRepository.findById()` with test player profiles
    - Test cases:
      - `getAgeTier_age9_returnsU10`
      - `getAgeTier_age10_returnsAge1012`
      - `getAgeTier_age12_returnsAge1012`
      - `getAgeTier_age13_returnsAge1317`
      - `getAgeTier_age17_returnsAge1317`
      - `getAgeTier_age18_returnsAdult`
      - `getAgeTier_configOverride_respectsCustomBoundary` — mock ConfigService to return `"8"` for u10-max-age, verify age 9 returns AGE_10_12
      - `getMessagingPolicy_u10Player_returnsProhibited`
      - `getMessagingPolicy_age1012Player_returnsParentManaged`
      - `getMessagingPolicy_age1317Player_returnsSupervised`
      - `getMessagingPolicy_adultPlayer_returnsUnrestricted`
      - `getMessagingPolicy_unknownPlayer_throwsUserNotFoundException`

- [x] Task 10: Tests — `FamilyDataIsolationIT` (integration) (AC: 2, 3)
  - [x] Create `src/test/java/com/softropic/skillars/platform/security/api/FamilyDataIsolationIT.java`:
    - `@SpringBootTest(webEnvironment = RANDOM_PORT) @AutoConfigureTestDatabase(replace = NONE) @Testcontainers`
    - Setup: Insert Parent A + Player A1 and Parent B + Player B1 directly via `JdbcTemplate` (follow pattern from `AuthResourceIT`)
    - Test cases:
      - `getPlayerProfile_ownPlayer_returns200` — Parent A reads Player A1 → 200
      - `getPlayerProfile_crossFamily_returns403` — Parent B reads Player A1 → 403 with `ErrorDto`
      - `getPlayerProfile_crossFamily_neverReturns404` — 403 response (not 404 — no resource enumeration)
      - `getPlayerProfile_unauthenticated_returns401` — no auth header → 401
      - `listPlayerProfiles_parentSeeOwnPlayersOnly` — Parent A's list does not include Player B1
    - All assertions use AssertJ
    - Insert users with `skillars_role = 'PARENT'` and `verification_status = 'BASIC_VERIFIED'` so login succeeds
    - Login via `POST /api/auth/login` to obtain JWT cookies (same pattern as `AuthResourceIT`)

---

## Dev Notes

### ⚠️ CRITICAL — AgePolicyService already exists; DO NOT recreate it

`AgePolicyService.java` is already fully implemented in `platform.security.service` from Story 1.2's design work. Tasks 1 and 3 extend it — do NOT create a new class. Read the existing file before any modification:
```
src/main/java/com/softropic/skillars/platform/security/service/AgePolicyService.java
```
The service already:
- Injects `ConfigService`
- Implements `getAgeTier(LocalDate)` with config-driven boundaries
- Implements `isMinor(AgeTier)`, `isMinor(LocalDate)`, `isIndependentAccountAllowed(AgeTier)`

Task 3 adds `getMessagingPolicy(Long playerId)` which requires injecting `PlayerProfileRepository` alongside the existing `ConfigService`.

### ⚠️ CRITICAL — Age policy config keys MISSING from V20 — must be seeded in V25

`AgePolicyService` references keys `security.age-policy.u10-max-age`, `security.age-policy.young-teen-max-age`, `security.age-policy.teen-max-age` but they are NOT in `V20__platform_config.sql`. The service falls back to `AgePolicy.defaults()` (9, 12, 17) which is correct behaviour, but the keys must be seeded so admins can override them without code deployment.

V25 is the correct next version — V24 was a DDL-only patch (`rotated_at` column on `refresh_tokens`).

### ⚠️ CRITICAL — Principal is Skillars Principal, not java.security.Principal

`@PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")` passes the Spring Security `Authentication` object. Inside the guard, cast `authentication.getPrincipal()` to `com.softropic.skillars.platform.security.contract.Principal` (NOT `java.security.Principal`). The `getBusinessId()` method on the Skillars `Principal` returns the parent's `userId` as a String — parse it with `Long.parseLong()`.

Pattern verified in `ShadowAccountResource.java`:
```java
Long parentId = Long.parseLong(((Principal) securityUtil.getCurrentUser()).getBusinessId());
```

### PlayerOwnershipGuard — checks done before service runs

When `@PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")` returns `false`, Spring Security throws `AccessDeniedException` which the `ApiAdvice` converts to 403. This means the service method body NEVER executes on a cross-family access — the guard is a pre-condition, not a post-filter.

This is belt-and-suspenders: the service also uses `findByIdAndParentId()` internally so a second check occurs at the repository level.

### Repository parent isolation — existing design is already correct

`PlayerProfileRepository` already has the correct isolation contract:
```java
Optional<PlayerProfile> findByIdAndParentId(Long id, Long parentId);
List<PlayerProfile> findByParentId(Long parentId);
```
There is no `findById(Long id)` declared (though it inherits one from `JpaRepository` — the declared methods are the enforced interface). `PlayerOwnershipGuard` uses `findByIdAndParentId()`.

`AgePolicyService.getMessagingPolicy()` uses `findById()` (inherited from JpaRepository) since it is called in a context where the caller (messaging service in the future) has already verified authorization. If in doubt, use `findById()` for internal cross-module lookups by trusted callers; use `findByIdAndParentId()` for external/parent-authenticated paths.

### FeatureGatedException — a forward-looking contract

No existing service method currently throws `FeatureGatedException`. This story creates the exception class and registers its `ApiAdvice` handler so that every future module story (starting from Epic 2 onward) can gate features without reinventing the exception or the handler.

Usage pattern for future stories:
```java
boolean allowed = configService.find("coach.tier.scout.session_builder")
    .map("true"::equals).orElse(false);
if (!allowed) {
    throw new FeatureGatedException("session_builder", "INSTRUCTOR");
}
```

### logErrorAndReturnDTO in ApiAdvice — confirmed present

`SkillarsAccountNotVerifiedException` handler in `ApiAdvice` (added in Story 1.5) uses `logErrorAndReturnDTO`. The same method is used for `FeatureGatedException`. Before adding the handler, grep to confirm:
```bash
grep -n "logErrorAndReturnDTO" src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java
```

### ShadowAccountService.getPlayerProfile — new method

`ShadowAccountService` currently has `createPlayerProfile()` and `listPlayerProfiles()`. The `getPlayerProfile(Long playerId, Long parentId)` method added in Task 5 is a thin read-only wrapper over `findByIdAndParentId` + `playerProfileMapper.toResponse()`. The `PlayerProfileMapper` is already injected in the service.

Uses `UserNotFoundException(playerId)` (→ 404) for the not-found path. `ShadowAccountException` was initially prescribed but maps to 409 Conflict — semantically incorrect for a GET endpoint safety-net; corrected during code review.

### Frontend — glass-card class origin

`glass-card` is a CSS class defined in `src/frontend/src/css/app.scss` (or a referenced partial) as part of the Skillars Design System (Story 1.2). Confirm the class exists before referencing it:
```bash
grep -rn "glass-card" src/frontend/src/css/
```

### Frontend — FeatureGateOverlay usage pattern (for Epic 2+ implementers)

```vue
<FeatureGateOverlay required-tier="INSTRUCTOR" feature-label="Session Builder" @upgrade-clicked="router.push('/coach/subscription')">
  <SessionBuilderPreviewImage />
</FeatureGateOverlay>
```
The blurred slot content should be a static preview image or a skeleton render of the feature — never live data.

### i18n — four-file parity check

Run after updating locale files:
```bash
node -e "
const en   = require('./src/frontend/src/i18n/en/index.js').default;
const enUS = require('./src/frontend/src/i18n/en-US/index.js').default;
const de   = require('./src/frontend/src/i18n/de/index.js').default;
const frFR = require('./src/frontend/src/i18n/fr-FR/index.js').default;
const k = o => JSON.stringify(Object.keys(o?.featureGate ?? {}).sort());
const all = [k(en), k(enUS), k(de), k(frFR)];
console.log('featureGate key parity:', all.every(x => x === all[0]) ? 'OK' : 'MISMATCH');
"
```

### Verification commands

```bash
# Confirm age policy keys seeded
grep -c "age-policy" src/main/resources/db/migration/V25__age_policy_config_seed.sql

# Confirm PlayerOwnershipGuard bean name is correct
grep -n '"playerOwnershipGuard"' src/main/java/com/softropic/skillars/platform/security/service/PlayerOwnershipGuard.java

# Confirm getMessagingPolicy added
grep -n "getMessagingPolicy" src/main/java/com/softropic/skillars/platform/security/service/AgePolicyService.java

# Confirm FeatureGatedException handler in ApiAdvice
grep -n "FeatureGatedException" src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java

# Confirm GET /{playerId} endpoint on ShadowAccountResource
grep -n "playerOwnershipGuard" src/main/java/com/softropic/skillars/platform/security/api/ShadowAccountResource.java

# Confirm no .then() in new JS files (none expected — no new API files in this story)
# Confirm glass-card exists in design system
grep -rn "\.glass-card" src/frontend/src/css/
```

### Project structure

New backend files:
```
src/main/resources/db/migration/
└── V25__age_policy_config_seed.sql

src/main/java/com/softropic/skillars/platform/security/
├── contract/
│   ├── MessagingPolicy.java                        (new)
│   └── exception/
│       └── FeatureGatedException.java              (new)
└── service/
    └── PlayerOwnershipGuard.java                   (new)
```

New frontend files:
```
src/frontend/src/components/common/
└── FeatureGateOverlay.vue                          (new)
```

New test files:
```
src/test/java/com/softropic/skillars/platform/security/
├── service/AgePolicyServiceTest.java               (new)
└── api/FamilyDataIsolationIT.java                  (new)
```

Modified backend files:
- `platform/security/service/AgePolicyService.java` — add `getMessagingPolicy(Long playerId)` + inject `PlayerProfileRepository`
- `platform/security/service/ShadowAccountService.java` — add `getPlayerProfile(Long playerId, Long parentId)`
- `platform/security/api/ApiAdvice.java` — add `FeatureGatedException` handler (403)
- `platform/security/api/ShadowAccountResource.java` — add `GET /{playerId}` with `@PreAuthorize`

Modified frontend files:
- `src/frontend/src/i18n/en/index.js`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/de/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`

**No changes to:**
- `AgeTier.java`, `AgePolicy.java` — already complete
- `PlayerProfile.java`, `PlayerProfileRepository.java` — already complete
- JWT infrastructure, SecurityConfiguration, registration services

### References

- Story 1.5 (skillars-1-5-authentication-jwt-security.md) — `ApiAdvice` handler pattern, `ShadowAccountResource` pattern, `Principal.getBusinessId()` usage, `AuthResourceIT` setup pattern for `FamilyDataIsolationIT`
- `project-context.md` — Java 17, Spring Boot 3.5, `@PreAuthorize` on all endpoints, `record` DTOs, no entity return from controllers, `@Service` layer only for business logic
- `architecture.md#Authentication & Security` — belt-and-suspenders family isolation (Spring Security + repository filter both required)
- `architecture.md#Structure Patterns` — Age-Tier Enforcement Point: service layer only; Parent ID Filter Repository Contract
- `ux-design-specification.md#Age-Tier Restriction Pattern` — "Restricted features are absent from the UI — not blocked, not error-styled. Invisible." (UX-DR21)
- `ux-design-specification.md#Tier Gating Pattern` — "soft teaser overlay appears over a blurred feature preview... never a full-screen block" (UX-DR22)

---

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- `UserNotFoundException(Long id)` constructor used (not `String`) — verified from existing constructor signature in `UserNotFoundException.java`
- `@MockitoSettings(strictness = Strictness.LENIENT)` added to `AgePolicyServiceTest` — the `getMessagingPolicy_unknownPlayer_throwsUserNotFoundException` test throws before reaching `getAgeTier()`, causing Mockito to flag the `configService.find(anyString())` stub in `@BeforeEach` as unnecessary without LENIENT mode
- `FeatureGateOverlay.vue` initially written as `const props = defineProps({...})` which caused Quasar build `no-unused-vars` ESLint error — fixed to `defineProps({...})` without assignment (in `<script setup>`, props are available in the template automatically)
- `FamilyDataIsolationIT` required three fixes before all 5 tests passed: (1) `iso2_country` NOT NULL — insertUser rewritten with all NOT NULL columns; (2) `ClientIdAccessDecisionManager` denies unlisted API keys — fixed by adding `allowed.clients=testClientId` to `@TestPropertySource`; (3) `Long` IDs serialize as JSON strings globally (see `CommonConfig.addSerializer(Long.class, ToStringSerializer.instance)`) — fixed `((Number) body.get("id")).longValue()` to `Long.parseLong((String) body.get("id"))`

### Completion Notes List

- All 10 tasks implemented and all tests pass: 12 unit tests (`AgePolicyServiceTest`) + 5 integration tests (`FamilyDataIsolationIT`) + pre-existing 7 (`ShadowAccountServiceIT`) = 24 total, all green
- `AuthResourceIT` has a pre-existing `iso2_country` NOT NULL constraint bug (11 failures before this story); not in scope to fix
- `getMessagingPolicy()` uses `findById()` (JpaRepository inherited) as intended — this method is called from trusted internal paths; `findByIdAndParentId()` is the externally-authenticated path used by `PlayerOwnershipGuard`
- `FeatureGatedException extends RuntimeException` (not `ApplicationException`) — deliberately avoids the `supportId` tracking overhead since tier-gate denials are expected, not errors
- `PlayerOwnershipGuard` is a `@Component("playerOwnershipGuard")` not a `@Service` — this is a Spring Security SpEL bean, not a service layer component

### File List

**New files:**
- `src/main/resources/db/migration/V25__age_policy_config_seed.sql`
- `src/main/java/com/softropic/skillars/platform/security/contract/MessagingPolicy.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/exception/FeatureGatedException.java`
- `src/main/java/com/softropic/skillars/platform/security/service/PlayerOwnershipGuard.java`
- `src/frontend/src/components/common/FeatureGateOverlay.vue`
- `src/test/java/com/softropic/skillars/platform/security/service/AgePolicyServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/security/api/FamilyDataIsolationIT.java`

**Modified files:**
- `src/main/java/com/softropic/skillars/platform/security/service/AgePolicyService.java`
- `src/main/java/com/softropic/skillars/platform/security/service/ShadowAccountService.java`
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java`
- `src/main/java/com/softropic/skillars/platform/security/api/ShadowAccountResource.java`
- `src/frontend/src/i18n/en/index.js`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/de/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

### Review Findings

- [x] [Review][Decision] ShadowAccountService.getPlayerProfile() throws ShadowAccountException (→ 409 Conflict) in the belt-check fallback path — resolved: changed to UserNotFoundException (→ 404); spec corrected.
- [x] [Review][Patch] PlayerOwnershipGuard.check() swallows NumberFormatException silently — fixed: added @Slf4j + log.warn() before return false [PlayerOwnershipGuard.java:26]
- [x] [Review][Patch] security.userNotFound i18n key missing from all 4 locale files — fixed: key added to en, en-US, de, fr-FR security blocks [i18n/en/index.js, en-US/index.js, de/index.js, fr-FR/index.js]
- [x] [Review][Defer] Flyway V25 hardcoded IDs 112–114 — ON CONFLICT (key) DO NOTHING does not guard against PK collision if those IDs are occupied by different rows [V25__age_policy_config_seed.sql:1–6] — deferred, spec explicitly verified ID range safety; established codebase pattern

### Change Log

- 2026-06-12: Implemented all 10 tasks — V25 migration, MessagingPolicy record, AgePolicyService.getMessagingPolicy(), PlayerOwnershipGuard, ShadowAccountResource GET /{playerId}, FeatureGatedException + ApiAdvice handler, FeatureGateOverlay.vue, i18n keys (4 locales), AgePolicyServiceTest (12 unit tests), FamilyDataIsolationIT (5 integration tests). All 24 tests pass.
