# Story skillars-2.4: Contact Detail Sanitization UX

Status: done

## Story

As a platform operator,
I want email addresses and phone numbers automatically detected and removed from all coach free-text inputs,
So that coaches cannot use profile fields to solicit off-platform contact and circumvent the booking system.

## Acceptance Criteria

1. **AC 1: Real-Time Warning Bar** — Given a coach is typing in the bio field in ProfileBuilderStep1, when the field value matches an email address or phone number pattern (detected via a debounced call to `POST /api/util/sanitize-preview` with 400ms debounce), then a non-blocking amber warning bar appears: "Contact details will be removed on save". The warning dismisses automatically if the coach removes the detected contact detail. Form submission is never blocked.

2. **AC 2: Server-Side Sanitization (Already Implemented)** — Given a coach submits Step 1 of the profile builder, when the payload reaches `CoachProfileService.saveStep1()`, then `ContactDetailSanitizer.sanitize()` runs on the `bio` field before any database write. Detected email addresses and phone numbers are replaced with `[contact details removed]`. The sanitized value is persisted. The coach sees the sanitized value on next load. *(This is already implemented — verify it still works, do not change it.)*

3. **AC 3: Sanitize Preview Endpoint Response Format** — Given `POST /api/util/sanitize-preview` is called with a text payload, when the endpoint processes the request, then it returns `{ "original": "...", "sanitized": "...", "detectionFound": true/false }`. The endpoint is only for UX feedback — it does not persist anything. *(Current implementation returns `{ "sanitized": "...", "wasModified": ... }` — this must be fixed.)*

4. **AC 4: Server-Side Gate is Mandatory** — Given a coach pastes contact details quickly before the debounce fires, when the server-side sanitizer runs, the value is still sanitized before persistence. No `400 Bad Request` is returned — the save succeeds with the sanitized value. *(Already enforced by `saveStep1()` — verify, do not change.)*

5. **AC 5: No-Op on Clean Input** — Given `ContactDetailSanitizer.sanitize()` runs on any input containing no email or phone pattern, when no contact details are detected, then the original value passes through unchanged.

## Tasks / Subtasks

- [x] Task 1: Fix `SanitizePreviewResource.SanitizePreviewResponse` to match spec (AC: 3)
  - [x] Open `src/main/java/com/softropic/skillars/platform/security/api/SanitizePreviewResource.java`
  - [x] Change `SanitizePreviewResponse` record to include `original` and `detectionFound`:
    ```java
    public record SanitizePreviewResponse(String original, String sanitized, boolean detectionFound) {}
    ```
  - [x] Update `preview()` method to populate all three fields:
    ```java
    @PostMapping("/sanitize-preview")
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    public ResponseEntity<SanitizePreviewResponse> preview(@RequestBody @Valid SanitizePreviewRequest request) {
        ContactDetailSanitizer.SanitizerResult result = contactDetailSanitizer.sanitize(request.text());
        return ResponseEntity.ok(new SanitizePreviewResponse(request.text(), result.sanitized(), result.wasModified()));
    }
    ```
  - [x] Keep `@PreAuthorize(SecurityConstants.HAS_ANY_ROLE)` — do not restrict to COACH only; parents may also submit text in future modules

- [x] Task 2: Add `sanitizePreview` API function to frontend (AC: 1)
  - [x] Open `src/frontend/src/api/marketplace.api.js`
  - [x] Append at the bottom:
    ```js
    export const sanitizePreview = (text) =>
      api.post('/api/util/sanitize-preview', { text })
    ```
  - [x] Note: The endpoint is under `/api/util/` not `/api/marketplace/` — the api client (`api` from `src/boot/axios`) routes all `/api/` calls to the backend, so this is fine in any api file. Place here since it's called from the profile builder.

- [x] Task 3: Fix `ProfileBuilderStep1.vue` — declare `loading` prop + add bio warning bar (AC: 1)
  - [x] Open `src/frontend/src/components/profileBuilder/ProfileBuilderStep1.vue`
  - [x] In `<script setup>`, add `defineProps` and `ref`/`watch` imports, plus debounce logic:
    ```js
    import { reactive, ref, watch } from 'vue'
    import { useI18n } from 'vue-i18n'
    import { sanitizePreview } from 'src/api/marketplace.api'

    const { t } = useI18n()
    const emit = defineEmits(['submit'])

    const props = defineProps({
      loading: { type: Boolean, default: false },
    })

    const languageOptions = ['English', 'German', 'French', 'Spanish', 'Arabic', 'Portuguese']
    const canonicalTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone

    const form = reactive({
      displayName: '',
      bio: '',
      city: '',
      district: '',
      languages: [],
    })

    const showContactWarning = ref(false)
    let debounceTimer = null

    watch(() => form.bio, async (newVal) => {
      clearTimeout(debounceTimer)
      if (!newVal) {
        showContactWarning.value = false
        return
      }
      debounceTimer = setTimeout(async () => {
        try {
          const res = await sanitizePreview(newVal)
          showContactWarning.value = res.data.detectionFound === true
        } catch {
          showContactWarning.value = false
        }
      }, 400)
    })

    function submit() {
      if (!form.displayName || form.languages.length === 0) return
      emit('submit', {
        displayName: form.displayName,
        bio: form.bio || null,
        city: form.city || null,
        district: form.district || null,
        languages: form.languages,
        canonicalTimezone,
      })
    }
    ```
  - [x] In `<template>`, add the amber warning banner between the bio `q-input` and the city `q-input`:
    ```html
    <q-banner
      v-if="showContactWarning"
      class="q-mb-sm"
      style="background: var(--color-warning-surface, #fff3cd); color: var(--color-warning-text, #856404); border-radius: 8px;"
      rounded
      dense
    >
      <template #avatar>
        <q-icon name="warning" />
      </template>
      {{ t('auth.coach.contactDetailWarning') }}
    </q-banner>
    ```
  - [x] The `contactDetailWarning` i18n key already exists: `'Contact details will be removed on save'` — do NOT add a duplicate
  - [x] Keep `props.loading` on the `q-btn` as `:loading="props.loading"` (or `:loading="loading"` after destructuring)

- [x] Task 4: Unit test — `ContactDetailSanitizerTest` (AC: 2, 5)
  - [x] Create `src/test/java/com/softropic/skillars/infrastructure/sanitizer/ContactDetailSanitizerTest.java`
  - [x] This is a pure unit test — NO `@SpringBootTest`, NO `@Testcontainers`:
    ```java
    package com.softropic.skillars.infrastructure.sanitizer;

    import org.junit.jupiter.api.Test;

    import static org.assertj.core.api.Assertions.assertThat;

    class ContactDetailSanitizerTest {

        private final ContactDetailSanitizer sanitizer = new ContactDetailSanitizer();

        @Test
        void sanitize_email_isRedacted() {
            var result = sanitizer.sanitize("Reach me at coach@example.com for sessions");
            assertThat(result.sanitized()).contains("[contact details removed]");
            assertThat(result.sanitized()).doesNotContain("coach@example.com");
            assertThat(result.wasModified()).isTrue();
        }

        @Test
        void sanitize_internationalPhone_isRedacted() {
            var result = sanitizer.sanitize("Call me on +44 7911 123456 to book");
            assertThat(result.sanitized()).contains("[contact details removed]");
            assertThat(result.wasModified()).isTrue();
        }

        @Test
        void sanitize_europePhone_isRedacted() {
            var result = sanitizer.sanitize("Call me on +49 30 12345678");
            assertThat(result.sanitized()).contains("[contact details removed]");
            assertThat(result.wasModified()).isTrue();
        }

        @Test
        void sanitize_cleanText_passesThrough() {
            String clean = "I am a certified football coach based in Berlin.";
            var result = sanitizer.sanitize(clean);
            assertThat(result.sanitized()).isEqualTo(clean);
            assertThat(result.wasModified()).isFalse();
        }

        @Test
        void sanitize_nullInput_returnsNull() {
            var result = sanitizer.sanitize(null);
            assertThat(result.sanitized()).isNull();
            assertThat(result.wasModified()).isFalse();
        }

        @Test
        void sanitize_multipleContactDetails_allRedacted() {
            var result = sanitizer.sanitize("Email coach@example.com or call +49 30 12345678");
            assertThat(result.sanitized()).doesNotContain("coach@example.com");
            assertThat(result.sanitized()).doesNotContain("+49");
            assertThat(result.wasModified()).isTrue();
        }
    }
    ```

- [x] Task 5: Integration test — `SanitizePreviewResourceIT` (AC: 3, 4)
  - [x] Create `src/test/java/com/softropic/skillars/platform/security/api/SanitizePreviewResourceIT.java`
  - [x] Follow the existing IT pattern from `CoachProfileBuilderIT.java` (same annotations, same HTTP test client):
    ```java
    package com.softropic.skillars.platform.security.api;

    import com.softropic.skillars.config.TestConfig;
    import com.softropic.skillars.e2e.HttpTestClient;
    import com.softropic.skillars.platform.security.SecurityIT;

    import org.junit.jupiter.api.AfterEach;
    import org.junit.jupiter.api.BeforeEach;
    import org.junit.jupiter.api.Test;
    import org.springframework.beans.factory.annotation.Autowired;
    import org.springframework.boot.test.context.SpringBootTest;
    import org.springframework.boot.test.web.server.LocalServerPort;
    import org.springframework.context.annotation.Import;
    import org.springframework.http.HttpStatus;
    import org.springframework.http.ResponseEntity;
    import org.springframework.jdbc.core.JdbcTemplate;
    import org.springframework.security.crypto.password.PasswordEncoder;
    import org.springframework.test.context.ActiveProfiles;
    import org.springframework.test.context.TestPropertySource;
    import org.springframework.test.context.jdbc.Sql;
    import org.springframework.transaction.support.TransactionTemplate;
    import org.springframework.web.client.HttpClientErrorException;

    import java.util.Map;

    import static org.assertj.core.api.Assertions.assertThat;
    import static org.assertj.core.api.Assertions.assertThatThrownBy;

    @ActiveProfiles({"dev", "test"})
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @Import(TestConfig.class)
    @TestPropertySource(properties = {
        "spring.cloud.compatibility-verifier.enabled=false",
        "rate.limiting.enabled=false",
        "allowed.clients=testClientId"
    })
    @Sql({SecurityIT.SEC_DATA_SQL_PATH})
    class SanitizePreviewResourceIT {

        private static final String LOGIN_ENDPOINT = "/api/auth/login";
        private static final String PREVIEW_ENDPOINT = "/api/util/sanitize-preview";
        private static final String CLIENT_ID = "testClientId";
        private static final String TEST_PASSWORD = "CoachPass@123!";
        private static final long COACH_ID = 9100000001L;
        private static final String COACH_EMAIL = "coach.builder@skillars-test.com";

        @LocalServerPort
        private int port;

        @Autowired
        private JdbcTemplate jdbcTemplate;

        @Autowired
        private PasswordEncoder passwordEncoder;

        @Autowired
        private TransactionTemplate transactionTemplate;

        private HttpTestClient client;
        private String coachToken;

        @BeforeEach
        void setUp() {
            client = new HttpTestClient("http://localhost:" + port, CLIENT_ID);
            transactionTemplate.execute(status -> {
                jdbcTemplate.update(
                    "INSERT INTO main.\"user\" (id, email, password_hash, role, email_verified, account_locked, created_at) " +
                    "VALUES (?, ?, ?, 'COACH', true, false, now()) ON CONFLICT DO NOTHING",
                    COACH_ID, COACH_EMAIL, passwordEncoder.encode(TEST_PASSWORD));
                return null;
            });
            var loginRes = client.post(LOGIN_ENDPOINT, Map.of("email", COACH_EMAIL, "password", TEST_PASSWORD, "clientId", CLIENT_ID));
            coachToken = (String) loginRes.getHeaders().getFirst("Set-Cookie");
        }

        @AfterEach
        void tearDown() {
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", COACH_ID);
        }

        @Test
        void preview_emailDetected_returnsDetectionFound() {
            var res = client.post(PREVIEW_ENDPOINT, Map.of("text", "Contact me at coach@example.com"), coachToken);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            var body = (Map<?, ?>) res.getBody();
            assertThat((Boolean) body.get("detectionFound")).isTrue();
            assertThat((String) body.get("original")).isEqualTo("Contact me at coach@example.com");
            assertThat((String) body.get("sanitized")).contains("[contact details removed]");
        }

        @Test
        void preview_cleanText_returnsNotDetected() {
            var res = client.post(PREVIEW_ENDPOINT, Map.of("text", "Certified football coach in Berlin"), coachToken);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            var body = (Map<?, ?>) res.getBody();
            assertThat((Boolean) body.get("detectionFound")).isFalse();
        }

        @Test
        void preview_unauthenticated_returns401() {
            assertThatThrownBy(() ->
                client.post(PREVIEW_ENDPOINT, Map.of("text", "test")))
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));
        }

        @Test
        void preview_nullText_returns400() {
            assertThatThrownBy(() ->
                client.post(PREVIEW_ENDPOINT, Map.of(), coachToken))
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }
    ```
  - [x] Note: Check `CoachProfileBuilderIT.java` and copy its exact `HttpTestClient` usage pattern — the test above uses the same pattern; verify `client.post()` signature matches

### Review Findings

- [ ] [Review][Decision] Warning bar uses inline `style` with hardcoded hex fallbacks (`#fff3cd`, `#856404`) — spec says "color via CSS custom property tokens (not hardcoded hex)"; the implementation uses `var(--color-warning-surface, #fff3cd)` which IS CSS custom properties but with hardcoded fallbacks. Decision needed: do CSS var() fallback values violate the "not hardcoded hex" constraint? [ProfileBuilderStep1.vue]

- [x] [Review][Patch] SQL string concatenation in tearDown — switched all 4 `execute("+COACH_ID")` calls to `update("... WHERE ... = ?", COACH_ID)` [SanitizePreviewResourceIT.java:tearDown]
- [x] [Review][Patch] Bare `DELETE FROM main.sec` with no predicate — removed (dangling fragment with no valid table) [SanitizePreviewResourceIT.java:tearDown]
- [x] [Review][Patch] Missing `@Size(max = 2000)` on `SanitizePreviewRequest.text` — added `@Size(max = 2000)` annotation and `jakarta.validation.constraints.Size` import [SanitizePreviewResource.java]
- [x] [Review][Patch] Debounce timer not cleared on component unmount — added `onUnmounted(() => { clearTimeout(debounceTimer); if (abortController) abortController.abort() })` [ProfileBuilderStep1.vue]
- [x] [Review][Patch] Race condition: stale in-flight preview response — added AbortController; controller aborted on each new keystroke burst; signal threaded through `sanitizePreview(text, signal)` [ProfileBuilderStep1.vue, marketplace.api.js]
- [x] [Review][Patch] `preview_unauthenticated_returns401` accepts 401 or 403 — changed to `.isEqualTo(HttpStatus.UNAUTHORIZED)` [SanitizePreviewResourceIT.java]

- [x] [Review][Defer] Phone regex false positives — `PHONE_PATTERN` can match dates and numeric prose sequences in bio text; no false-positive boundary tests [ContactDetailSanitizer.java] — deferred, pre-existing
- [x] [Review][Defer] `wasModified` semantics in sequential substitution — sequential email-then-phone replacement means the phone regex runs on an already-modified string; edge case may cause unexpected detection flag behavior [ContactDetailSanitizer.java] — deferred, pre-existing
- [x] [Review][Defer] Duplicate i18n key `auth.coach.bioSanitizationWarning` — near-identical to `contactDetailWarning` (trailing period differs); unused by this story but will silently diverge on copy [src/frontend/src/i18n/en/index.js] — deferred, pre-existing

## Dev Notes

### Critical: What's Already Implemented vs. What Needs Work

The backend security gate (`ContactDetailSanitizer`) and service-layer sanitization (`CoachProfileService.saveStep1()` sanitizing `bio`) are **already fully implemented from Story 2.1**. This story's scope is:

| Component | Status | Action |
|---|---|---|
| `ContactDetailSanitizer` | ✅ Done | Write unit tests only |
| `SanitizePreviewResource` endpoint | ✅ Exists | Fix response shape (Task 1) |
| `CoachProfileService.saveStep1()` sanitizing bio | ✅ Done | Verify, no change |
| Frontend warning bar on bio field | ❌ Missing | Implement (Task 3) |
| Frontend `sanitizePreview` API call | ❌ Missing | Add to `marketplace.api.js` (Task 2) |
| `loading` prop declared in `ProfileBuilderStep1` | ❌ Bug | Fix in Task 3 |
| `ContactDetailSanitizerTest` | ❌ Missing | Create (Task 4) |
| `SanitizePreviewResourceIT` | ❌ Missing | Create (Task 5) |

### Response Format Fix (Task 1)

`SanitizePreviewResource.SanitizePreviewResponse` currently returns `{ "sanitized": "...", "wasModified": ... }`. The spec requires `{ "original": "...", "sanitized": "...", "detectionFound": true/false }`. The change is:
- Add `original` field (the raw input text)
- Rename `wasModified` → `detectionFound`

The frontend (Task 3) will check `res.data.detectionFound`. Do NOT read `wasModified` — it will be gone after this fix.

### ProfileBuilderStep1.vue Bug (Task 3)

The current file has `:loading="loading"` in the template but `loading` is NOT declared as a prop in `<script setup>`. The parent `CoachProfileBuilderPlaceholderPage.vue` passes `:loading="store.loading"` but the child silently ignores it (Vue treats undeclared bindings as attributes). Fix: add `defineProps({ loading: { type: Boolean, default: false } })`.

### Bio Field: Only Free-Text Field in Scope

Only the `bio` textarea in `ProfileBuilderStep1.vue` needs the warning bar. Other steps:
- Step 2: Specialties (list select) + Age groups (list select) — no free text
- Step 3: Pricing (numbers) + session packs (numbers + label) — the `label` field IS free text but it's a short descriptor like "5 session pack", not a bio; sanitization there is out of scope for this story
- Step 4: Availability windows (day/time pickers) — no free text
- Step 5: Photo upload — no text

### Debounce Pattern (Task 3)

Use native `setTimeout`/`clearTimeout` — do NOT import a debounce library. The 400ms delay matches the spec. Clear the timer on each keystroke (via `watch`). On error (e.g. network), silently fail — never show a false positive warning.

### i18n: Key Already Exists

The warning text key `auth.coach.contactDetailWarning` already exists in `src/frontend/src/i18n/en/index.js` as `'Contact details will be removed on save'`. Do NOT add a duplicate key.

### Warning Bar Styling (Task 3)

Use Quasar's `q-banner` with `dense` and `rounded` props. Color via CSS custom property tokens (not hardcoded hex). The UX spec (UX-DR24) describes an "amber warning bar" — use `warning` color tokens. Ensure the banner is visually distinct but non-blocking (no form disabling, no button graying).

### Security Constants Reference

```java
SecurityConstants.HAS_ANY_ROLE = "hasRole('ROLE_ADMIN') or ... or hasRole('ROLE_COACH') or hasRole('ROLE_PARENT')"
SecurityConstants.HAS_COACH_ROLE = "hasRole('ROLE_COACH')"
```

Keep `HAS_ANY_ROLE` on the preview endpoint — the spec says "authenticated coaches only" but the sanitizer may serve other authenticated roles in future modules (messaging, reviews). This avoids a future breaking change.

### Test Infrastructure Notes

- `ContactDetailSanitizerTest` is a **pure unit test** — no Spring context needed, instantiate `new ContactDetailSanitizer()` directly
- `SanitizePreviewResourceIT` follows the exact same pattern as `CoachProfileBuilderIT` — same annotations, same `HttpTestClient`, same `SecurityIT.SEC_DATA_SQL_PATH` seed
- ALWAYS use `jdbcTemplate.update("... WHERE id = ?", COACH_ID)` in teardown — never string-concatenate IDs into SQL
- Use `AssertJ` (`assertThat`) for all assertions

### Project Structure Notes

**Backend files modified:**
- `src/main/java/com/softropic/skillars/platform/security/api/SanitizePreviewResource.java` — change response record shape

**New test files:**
- `src/test/java/com/softropic/skillars/infrastructure/sanitizer/ContactDetailSanitizerTest.java`
- `src/test/java/com/softropic/skillars/platform/security/api/SanitizePreviewResourceIT.java`

**Frontend files modified:**
- `src/frontend/src/api/marketplace.api.js` — add `sanitizePreview` export
- `src/frontend/src/components/profileBuilder/ProfileBuilderStep1.vue` — fix `loading` prop + add warning bar

**No Flyway migration needed** — no database schema changes in this story.

### References

- Epic source: `_bmad-output/planning-artifacts/skillars-epics.md` lines 880–916 (Story 2.4 full text)
- Architecture: `_bmad-output/planning-artifacts/architecture.md` — `infrastructure.sanitizer` module (line 78, 193–196, 1040–1041), endpoint naming (line 326)
- UX spec: `_bmad-output/planning-artifacts/ux-design-specification.md` line 781 (UX-DR24: amber warning bar)
- Existing: `src/main/java/com/softropic/skillars/infrastructure/sanitizer/ContactDetailSanitizer.java` — regex implementation
- Existing: `src/main/java/com/softropic/skillars/platform/security/api/SanitizePreviewResource.java` — endpoint to fix
- Existing: `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java` line 84 — bio sanitization already in place
- Previous story: `_bmad-output/implementation-artifacts/skillars-2-3-coach-public-profile-page.md` — IT test patterns, SQL teardown anti-patterns to avoid
- i18n: `src/frontend/src/i18n/en/index.js` line 34 — `contactDetailWarning` key exists at `auth.coach.contactDetailWarning`

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- IT test template in story used non-existent `client.post()` method; adapted to `httpTestClient.makeHttpRequest()` pattern from `CoachProfileBuilderIT`.
- Story template tearDown used `login` column on `login_attempts` table; actual column is `identifier`. Removed that delete statement (not required — `CoachProfileBuilderIT` also skips it).
- Used distinct COACH_ID `9100000003L` and email `coach.sanitize@skillars-test.com` to avoid ID collisions with other IT classes that use `9100000001L`.

### Completion Notes List

- Task 1: Fixed `SanitizePreviewResponse` record — added `original` field, renamed `wasModified` → `detectionFound`. Updated `preview()` to pass `request.text()` as `original`.
- Task 2: Added `sanitizePreview(text)` export to `marketplace.api.js` calling `POST /api/util/sanitize-preview`.
- Task 3: Fixed `ProfileBuilderStep1.vue` — declared `loading` prop via `defineProps`, added `ref`/`watch`/debounce for bio field, added amber `q-banner` warning between bio and city inputs, fixed `:loading="props.loading"` on the submit button.
- Task 4: Created `ContactDetailSanitizerTest.java` — 6 pure unit tests (email, international phone, European phone, clean text, null input, multiple contacts). All pass.
- Task 5: Created `SanitizePreviewResourceIT.java` — 4 integration tests (email detection, clean text, unauthenticated 401, null text 400). All pass.
- Full regression: 307 tests, 0 failures.

### File List

- src/main/java/com/softropic/skillars/platform/security/api/SanitizePreviewResource.java (modified)
- src/frontend/src/api/marketplace.api.js (modified)
- src/frontend/src/components/profileBuilder/ProfileBuilderStep1.vue (modified)
- src/test/java/com/softropic/skillars/infrastructure/sanitizer/ContactDetailSanitizerTest.java (created)
- src/test/java/com/softropic/skillars/platform/security/api/SanitizePreviewResourceIT.java (created)

## Change Log

- 2026-06-13: Implemented Story 2.4 — fixed sanitize-preview response shape, added frontend bio warning bar with 400ms debounce, fixed loading prop bug in ProfileBuilderStep1, added unit tests for ContactDetailSanitizer and IT tests for the preview endpoint. 307 tests pass.
