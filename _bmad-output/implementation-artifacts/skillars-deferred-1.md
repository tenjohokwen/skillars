# Story Deferred-1: Config Access & SecurityUtil Hardening

Status: done

## Story

As a platform engineer,
I want config reads to fail safely and SecurityUtil casts to be guarded,
so that misconfigured keys and unexpected principal types produce clear errors instead of 500s or silently broken behaviour.

## Acceptance Criteria

1. **Given** any scheduler or service reads a numeric config key via `configService.getString(key)`
   **When** the key is absent, null, whitespace, or non-numeric
   **Then** the scheduler/service logs a WARN and uses a safe default rather than throwing `NumberFormatException` or `NullPointerException`
   **And** no `Long.parseLong(...)` / `Integer.parseInt(...)` call on a raw `configService.getString()` result remains unguarded anywhere in the codebase

2. **Given** any scheduler reads a config key via `configService.getLong(key)` with no fallback
   **When** the key is absent from `platform_config`
   **Then** the scheduler logs a WARN and skips that tick (or uses a documented default), rather than crashing and silently dropping the entire scheduled job for that interval
   **Affected schedulers**: `BookingExpiryScheduler`, `BookingReminderScheduler` (expiry/reminder window keys)

3. **Given** `slu.neglected.threshold` config value is read
   **When** the value is ≥ 1 or ≤ 0
   **Then** neglected-skill detection logs an ERROR and skips evaluation for that run rather than silently disabling all neglected-skill detection with wrong arithmetic
   **And** a startup-time `@PostConstruct` check on `NeglectedSkillDetectionService` logs a clear WARN if the threshold is out of the valid range (0, 1)
   **Note**: the range is the open interval (0, 1) — `threshold == 1` must also be rejected, since it makes `1 - threshold == 0`, so the neglected-lower-bound collapses to zero and detection silently never flags anything (the exact bug this AC exists to prevent). The wording was corrected during code review (2026-07-01); the original draft said "> 1" which would have permitted this silent-no-op case.

4. **Given** any `@RestController` resource resolves the current user's ID
   **When** `securityUtil.getCurrentUser()` returns a non-`Principal` type
   **Then** the resource throws `InsufficientAuthenticationException` (→ 401), NOT a `ClassCastException` (→ 500)
   **And** a shared helper `SecurityUtil.requireCurrentUserId()` (or equivalent static util) encapsulates this safe cast so it is not duplicated across resources

## Tasks / Subtasks

- [x] **Task 1 — Add `getLongOrDefault` and `getIntOrDefault` to `ConfigService`** (AC: 1, 2)
  - [x] Add to `platform.security.service.ConfigService` (or wherever `ConfigService` lives):
    ```java
    public long getLongOrDefault(String key, long defaultValue) {
        String raw = getString(key);
        if (raw == null || raw.isBlank()) {
            log.warn("Config key '{}' is absent or blank — using default {}", key, defaultValue);
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Config key '{}' has non-numeric value '{}' — using default {}", key, raw, defaultValue);
            return defaultValue;
        }
    }

    public int getIntOrDefault(String key, int defaultValue) {
        String raw = getString(key);
        if (raw == null || raw.isBlank()) {
            log.warn("Config key '{}' is absent or blank — using default {}", key, defaultValue);
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Config key '{}' has non-numeric value '{}' — using default {}", key, raw, defaultValue);
            return defaultValue;
        }
    }
    ```
  - [x] If `ConfigService.getLong(key)` already exists but throws on missing key, add an overload `getLong(key, defaultValue)` instead

- [x] **Task 2 — Replace unguarded `Long.parseLong(configService.getString(...))` calls** (AC: 1)
  - [x] `AdminCoachEnforcementService.java:deleteStrike()` — `Long.parseLong(configService.getString("reliability.strike.visibilityThreshold"))`:
    ```java
    // BEFORE:
    long threshold = Long.parseLong(configService.getString("reliability.strike.visibilityThreshold"));
    // AFTER:
    long threshold = configService.getLongOrDefault("reliability.strike.visibilityThreshold", 3L);
    ```
  - [x] `ReliabilityStrikeService.java:issue()` — same key, same fix
  - [x] Grep for remaining raw `Long.parseLong(configService` and `Integer.parseInt(configService` calls project-wide and apply the same guard: `find src/main/java -name "*.java" | xargs grep -l "parseLong(configService\|parseInt(configService"` — fix every hit
  - [x] `SluCalculationService.java` — config keys read for SLU scales (5.1 W3): wrap in `getLongOrDefault` / `getDoubleOrDefault` as appropriate

- [x] **Task 3 — Fix `BookingExpiryScheduler` and `BookingReminderScheduler`** (AC: 2)
  - [x] In `BookingExpiryScheduler.java` — replace `configService.getLong(key)` (throws on absent) with `configService.getLongOrDefault(key, 48L)` (or the appropriate documented default per the migration seed value)
  - [x] Same in `BookingReminderScheduler.java` for its window key
  - [x] Log a WARN at start-up if a fallback is used, so ops can detect a misconfigured environment

- [x] **Task 4 — Add range guard to `NeglectedSkillDetectionService`** (AC: 3)
  - [x] In `NeglectedSkillDetectionService.java`, add a `@PostConstruct` validation:
    ```java
    @PostConstruct
    void validateThreshold() {
        double threshold = configService.getLongOrDefault("slu.neglected.threshold", ...) / 100.0;
        // Or however the threshold is read — verify the actual key and type
        if (threshold <= 0 || threshold >= 1) {
            log.error("slu.neglected.threshold is out of valid range (0,1): {} — neglected-skill detection will be skipped until corrected", threshold);
        }
    }
    ```
  - [x] In the detection method body, re-check the threshold before each evaluation and skip (log ERROR) if invalid, rather than producing wrong results silently
  - [x] Read `NeglectedSkillDetectionService.java` lines around the `oneMinus` calculation (5.2 D4) to confirm the exact field name and type before writing the guard

- [x] **Task 5 — Add `SecurityUtil.requireCurrentUserId()`** (AC: 4)
  - [x] Add a method to `platform.security.service.SecurityUtil` (or create a `SecurityUtil` helper if it is an interface):
    ```java
    public Long requireCurrentUserId() {
        Object principal = getCurrentUser();
        if (!(principal instanceof Principal p)) {
            throw new InsufficientAuthenticationException("Unexpected security principal type");
        }
        try {
            return Long.parseLong(p.getBusinessId());
        } catch (NumberFormatException e) {
            throw new InsufficientAuthenticationException("Principal businessId is not a valid user ID");
        }
    }
    ```
  - [x] This mirrors the safe pattern already used in `AdminCoachEnforcementResource.resolveAdminId()`

- [x] **Task 6 — Replace unsafe `(Principal) securityUtil.getCurrentUser()` casts** (AC: 4)
  - [x] Grep for unsafe cast: `find src/main/java -name "*.java" | xargs grep -n "(Principal) securityUtil.getCurrentUser()"` — replace every hit with `securityUtil.requireCurrentUserId()` (or inline the instanceof guard where injection of SecurityUtil is not available)
  - [x] Known locations to fix (verify each before editing):
    - `ReviewResource.java` — already used the safe `instanceof Principal` pattern; no unsafe cast present, left unchanged
    - `SessionCompletionResource.java` — `currentUserId()` — fixed, now delegates to `securityUtil.requireCurrentUserId()`
    - `RescheduleResource.java` — `currentUserId()` — fixed
    - `BookingResource.java` — `currentParentId()` / `currentCoachUserId()` — fixed (both methods)
    - `BookingEventResource.java` — already used the safe `instanceof Principal` pattern; no unsafe cast present, left unchanged
    - `PerformanceReportResource.java` — resolves coach ID via `securityUtil.getCurrentCoachUserId()`, already safe; no unsafe cast present, left unchanged
    - `VideoResource.java:resolveCurrentOwnerId()` — fixed, plus `currentPlayerProfileId()` (same file, same pattern)
    - `GdprResource.java:resolveCurrentUserId()` — fixed
  - [x] **Do NOT change** `AdminCoachEnforcementResource.resolveAdminId()` — it already uses the safe instanceof pattern; leave it as-is for clarity
  - [x] After replacing, run `mvn compile -q` to confirm no compilation errors

- [x] **Task 7 — Integration test: `ConfigGuardIT`** (AC: 1, 2, 3)
  - [x] Create `platform.admin.api.ConfigGuardIT` (TSID range `9300_xxx`):
    - Seed a platform_config row with a non-numeric value for `reliability.strike.visibilityThreshold`
    - Verify `DELETE /api/admin/coaches/{coachId}/strikes/{strikeId}` returns `200` (uses default) rather than `500`
  - [x] This is a smoke test; the main value is catching regressions when ConfigService changes

### Review Findings

- [x] [Review][Patch] Config numeric guards don't validate semantic range (negative/zero/overflow), only non-numeric/blank input — `configService.getLong(key, default)` lets a syntactically-valid but semantically-broken value (negative, zero, or huge) pass straight through. Affected: `BookingExpiryScheduler.java:39` (negative `booking.request_expiry_hours` pushes the expiry threshold into the future, mass-declining all pending booking requests on the next tick), `BookingReminderScheduler.java:42-43` (negative hours invert the reminder window so reminders are silently never sent; extremely large hours overflow `Duration.ofHours` and crash the scheduled job every run), `ReliabilityStrikeService.java:44-45` (threshold ≤0 immediately suspends every coach on their next strike), `AdminCoachEnforcementService.java:212` (threshold ≤0 means a coach's PENDING_REVIEW/REDUCED status can never be reverted). **Fixed**: added `ConfigService.getBoundedLong(key, default, min, max)` and applied it at all five call sites — hour-based schedulers bounded to `[1, 8760]` (1 hour to 1 year, also closes the `Duration.ofHours` overflow path), threshold-based reads bounded to `[1, Long.MAX_VALUE]`.
- [x] [Review][Patch] `StorageResource.java:signUpload()` and `FileStorageService.java:getOwnerId()` duplicate the safe instanceof-cast-and-throw logic instead of sharing a helper, and neither guards against `principal.getBusinessId()` returning null — a null businessId passes the `instanceof Principal` check but then throws an unhandled NPE (on `.equals()` in `StorageResource`, or on return/downstream use in `FileStorageService`) instead of the intended `InsufficientAuthenticationException` → 401. **Fixed**: added `SecurityUtil.requireCurrentBusinessId(): String` (instanceof + null/blank check, throws `InsufficientAuthenticationException`) and routed both call sites through it, closing the AC4 gap where the safe-cast pattern was still duplicated across two files.
- [x] [Review][Patch] `NeglectedSkillDetectionService.readThreshold()` [NeglectedSkillDetectionService.java:65-71] swallows the real exception (`IllegalStateException | NumberFormatException | NullPointerException`) and returns `null`, so both the `@PostConstruct` warning and the scheduled-run error log can no longer distinguish "key missing" from "value malformed." **Fixed**: now logs `e.getMessage()` at WARN before returning `null`.
- [x] [Review][Patch] Default values `3L` (`reliability.strike.visibilityThreshold`) and `5L` (`reliability.strike.suspensionThreshold`) are hardcoded independently in both `AdminCoachEnforcementService.java:212` and `ReliabilityStrikeService.java:44-45` — extract to shared constants so the two call sites can't silently drift apart. **Fixed**: added `ReliabilityStrikeConfig` (keys + defaults) in `platform.payment.service`, used by both call sites.
- [x] [Review][Defer] Test coverage is thin relative to the diff's size [various] — deferred, pre-existing test-strategy gap, not a regression. No unit test covers `isInValidRange`'s boundary values (0, 1, and just inside/outside), and none of the 15 refactored `Resource` classes have a test asserting the actual 401 response when `requireCurrentUserId()` throws; only one E2E smoke test (`ConfigGuardIT`) was added for a ~1080-line diff.
- [x] [Review][Defer] `ConfigGuardIT.java` mutates the shared `main.platform_config` row directly (`setUp`/`tearDown`) and depends on `@AfterEach` running to restore the original value and invalidate the `ConfigService` cache — deferred, real but low-probability test-isolation hazard (interrupted test run before teardown corrupts config for concurrent/subsequent tests), not something this review blocks on.

## Dev Notes

### Why `getLongOrDefault` and not an exception

The existing `Long.parseLong(configService.getString(...))` pattern was safe when the config was always seeded by migrations. As the config table grows and ops occasionally edits it manually, a missing or typo'd key should never take down a running scheduler or an admin endpoint. Failing safely with a default and a WARN log is the correct production behaviour.

### `SecurityUtil.requireCurrentUserId()` placement

If `SecurityUtil` is an interface, add the default method there. If it is a concrete `@Service`, add it as a public method. The key constraint is that it must be callable from `@RestController` classes that already inject `SecurityUtil` — no new injection is needed.

### `InsufficientAuthenticationException` maps to 401

Confirm that `ApiAdvice` handles `InsufficientAuthenticationException` and returns `401`. If not, add a handler:
```java
@ExceptionHandler(InsufficientAuthenticationException.class)
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public ErrorDto insufficientAuth(InsufficientAuthenticationException ex) {
    return new ErrorDto("security.unauthenticated", ex.getMessage());
}
```

### References — Files to Read Before Implementing

- `ConfigService.java` — confirm method signatures for `getString`, `getLong`, `getBoolean`
- `ReliabilityStrikeService.java` — confirm the `visibilityThreshold` key and how it is currently read
- `AdminCoachEnforcementService.java:deleteStrike()` — exact line using unsafe `parseLong`
- `NeglectedSkillDetectionService.java` — threshold reading and `oneMinus` calculation
- `BookingExpiryScheduler.java`, `BookingReminderScheduler.java` — config key names and fallback values
- `SluCalculationService.java:78-85` — config key reading pattern for SLU scales
- `ApiAdvice.java` — confirm `InsufficientAuthenticationException` handler exists
- `AdminCoachEnforcementResource.java:resolveAdminId()` — the safe pattern to replicate

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `mvn -q compile` — clean compile after all Task 1–6 changes.
- `mvn -q test -Dtest=ConfigGuardIT` — new integration test passes; log confirms guard fires:
  `WARN c.s.s.p.config.service.ConfigService - Config key 'reliability.strike.visibilityThreshold' has non-numeric value 'not-a-number' — using default 3`,
  followed by a 200 response instead of a 500.
- `mvn -q test` (full regression suite) — initial run piped through `tail` masked Maven's real exit code and silently hid real failures; re-ran without piping and caught 6 genuine regressions (see note below).
- After fixing the 6 regressions: `mvn test > full_test_run.log 2>&1; echo "MVN_EXIT_CODE:$?"` — final confirmed-clean run: `MVN_EXIT_CODE:0`, log shows `Tests run: 736, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

### Completion Notes List

- **Post-review fix (Task 6 regressions)**: A follow-up full-suite run (run without piping to `tail`, which had masked the true exit code the first time) surfaced 6 real failures caused by the `requireCurrentUserId()` migration:
  - `FileStorageService.getOwnerId()` and `StorageResource.signUpload()`'s owner-bound-entity check previously returned the *raw* `businessId` string with no numeric requirement (used as a generic owner/tenant key, not always a real user ID — proven by `FileStorageServiceIT.signUpload_quotaExceeded_throws` using a non-numeric `"tenant-quota"` owner key). Routing these through `requireCurrentUserId()` incorrectly forced numeric parsing, throwing `InsufficientAuthenticationException` for legitimate non-numeric owner keys. **Fixed** by reverting both call sites to a safe inline `instanceof Principal` cast (no `Long.parseLong`), preserving the original raw-string semantics while still eliminating the unguarded cast.
  - `VideoApprovalResourceIT`, `VideoListQuotaResourceIT`, `PlayerUploadResourceIT`, `PlayerSubscriptionOwnershipIT` all mock `SecurityUtil` with `@MockitoBean` and previously only stubbed `securityUtil.getCurrentUser()`. Since the Resources now call `securityUtil.requireCurrentUserId()` directly (bypassing `getCurrentUser()`), the unstubbed mock method returned `null`/`0`. **Fixed** by adding `when(securityUtil.requireCurrentUserId()).thenReturn(...)` alongside the existing stub in each test's principal-stubbing helper.
  - `ReliabilityStrikeServiceTest` had the same class of issue (found earlier, before the regression report) — stubbed `configService.getString(...)` which is no longer called; fixed by stubbing `configService.getLong(key, anyLong())` instead.
  - All fixes re-verified individually and via a full, non-piped `mvn test` run.

- **Task 1**: `ConfigService.java` actually lives in `platform.config.service` (not `platform.security.service` as the story assumed). It already had `getLong(key, defaultValue)` / `getInt(key, defaultValue)` overloads; rather than adding duplicate `getLongOrDefault`/`getIntOrDefault` methods, hardened the existing overloads to also WARN-log on absent/blank keys (previously only non-numeric values were logged). No new method names were introduced, avoiding duplicate abstractions.
- **Task 2**: Fixed the two real unguarded `Long.parseLong(configService.getString(...))` call sites (`ReliabilityStrikeService.issue()` — both `suspensionThreshold` and `visibilityThreshold`; `AdminCoachEnforcementService.deleteStrike()`). Project-wide grep confirmed no other `parseLong(configService`/`parseInt(configService` hits remain. `SluCalculationService.java` was inspected and already wraps its `BigDecimal(configService.getString(...))` SLU-scale reads in a try/catch that logs an ERROR and safely aborts the calculation — already compliant, no change needed.
- **Task 3**: Both schedulers now use `configService.getLong(key, <seed-default>)` (48h expiry, 24h/2h reminder windows, matching the Flyway seed values) instead of the throwing single-arg `getLong(key)`. The WARN-on-fallback behavior is provided by the hardened `getLong(key, defaultValue)` from Task 1.
- **Task 4**: Added `@PostConstruct validateThresholdOnStartup()` plus a re-check inside `detectNeglectedSkills()` via two new private helpers (`readThreshold()`, `isInValidRange()`). Invalid/missing threshold now logs ERROR and skips the run instead of silently running with wrong arithmetic.
- **Task 5**: Added `SecurityUtil.requireCurrentUserId()` exactly mirroring `AdminCoachEnforcementResource.resolveAdminId()`'s existing safe `instanceof Principal` pattern.
- **Task 6**: Grepped for the literal unsafe cast `(Principal) securityUtil.getCurrentUser()` and found 20 occurrences across 17 files — more than the 8 "known locations" listed in the story. Fixed all 20 (see File List), removing now-unused `Principal`/`InsufficientAuthenticationException` imports where applicable. Several of the story's "known locations" (`ReviewResource`, `BookingEventResource`, `PerformanceReportResource`) turned out to already use the safe `instanceof`/delegating pattern — left unchanged per the same "leave already-safe code as-is for clarity" precedent the story set for `AdminCoachEnforcementResource`. Confirmed `ApiAdvice` already maps `InsufficientAuthenticationException` to 401 via its `AuthenticationException` handler (it's a subclass) — no new exception handler needed.
- **Task 7**: Added `ConfigGuardIT` (TSID range `9300_xxx`). Had to autowire `ConfigService` and call `invalidate()` after corrupting/restoring the config row directly via SQL, since `ConfigService` caches platform_config in memory — a `TestPropertySource` override of `app.config.cache-ttl-seconds=0` was tried first but breaks `scheduleWithFixedDelay` (period must be > 0), so switched to explicit cache invalidation instead.

### File List

**Modified Files:**
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java`
- `src/main/java/com/softropic/skillars/platform/security/service/SecurityUtil.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/NeglectedSkillDetectionService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingExpiryScheduler.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingReminderScheduler.java`
- `src/main/java/com/softropic/skillars/platform/video/api/VideoApprovalResource.java`
- `src/main/java/com/softropic/skillars/platform/video/api/VideoResource.java`
- `src/main/java/com/softropic/skillars/platform/security/api/ShadowAccountResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/SessionCompletionResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/BookingResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/AvailabilityResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/SessionPackResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/BookingBatchResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/CancellationResource.java`
- `src/main/java/com/softropic/skillars/platform/payment/api/SubscriptionResource.java`
- `src/main/java/com/softropic/skillars/platform/payment/api/ReliabilityStrikeResource.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/api/ProfileBuilderResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/ScheduleResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/RescheduleResource.java`
- `src/main/java/com/softropic/skillars/platform/filestorage/api/StorageResource.java`
- `src/main/java/com/softropic/skillars/platform/filestorage/service/FileStorageService.java`
- `src/main/java/com/softropic/skillars/platform/admin/api/GdprResource.java`

- `src/test/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeServiceTest.java` (fixed stale `configService.getString(...)` stub)
- `src/test/java/com/softropic/skillars/platform/video/api/VideoApprovalResourceIT.java` (added `requireCurrentUserId()` stub)
- `src/test/java/com/softropic/skillars/platform/video/api/VideoListQuotaResourceIT.java` (added `requireCurrentUserId()` stub)
- `src/test/java/com/softropic/skillars/platform/video/api/PlayerUploadResourceIT.java` (added `requireCurrentUserId()` stub)
- `src/test/java/com/softropic/skillars/platform/payment/api/PlayerSubscriptionOwnershipIT.java` (added `requireCurrentUserId()` stub, 2 locations)

**New Files:**
- `src/test/java/com/softropic/skillars/platform/admin/api/ConfigGuardIT.java`

**Inspected, no change needed (already safe/compliant):**
- `src/main/java/com/softropic/skillars/platform/development/service/SluCalculationService.java`
- `src/main/java/com/softropic/skillars/platform/reviews/api/ReviewResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/BookingEventResource.java`
- `src/main/java/com/softropic/skillars/platform/development/api/PerformanceReportResource.java`
- `src/main/java/com/softropic/skillars/platform/admin/api/AdminCoachEnforcementResource.java`
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java`

### Change Log

- Hardened `ConfigService.getLong(key, defaultValue)`/`getInt(key, defaultValue)` to WARN-log on absent/blank keys, not just non-numeric ones (AC 1).
- Replaced all unguarded `Long.parseLong(configService.getString(...))` call sites with the safe default-backed overload (AC 1).
- `BookingExpiryScheduler` and `BookingReminderScheduler` no longer crash the entire scheduled tick when their config keys are missing (AC 2).
- `NeglectedSkillDetectionService` now validates `slu.neglected.threshold` is in (0,1) at startup and before each run, skipping with an ERROR log otherwise (AC 3).
- Added `SecurityUtil.requireCurrentUserId()` and replaced 20 unsafe `(Principal) securityUtil.getCurrentUser()` casts across 17 Resource/Service files with it, preventing `ClassCastException` → 500s in favor of `InsufficientAuthenticationException` → 401 (AC 4).
- Added `ConfigGuardIT` smoke test covering the non-numeric config guard end-to-end.
