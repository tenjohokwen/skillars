# Story Video-1.2: QuotaProvider Interface & Integration Contract

Status: review

## Story

As a developer building a consuming application on javatemplate,
I want a well-defined `QuotaProvider` interface with clear concurrency contract documentation and fail-fast wiring validation,
so that I can implement quota enforcement for my specific use case without modifying the Video module.

## Acceptance Criteria

**AC-1: QuotaProvider interface defined in `platform.video.contract`**
- Interface declares: `boolean check(String ownerId, long requestedBytes)`, `String reserve(String ownerId, long bytes)`, `void commit(String reservationHandle)`, `void release(String reservationHandle)`
- Interface Javadoc explicitly states: "Implementations are responsible for concurrent-safe quota enforcement. The module orchestrates the check → reserve → commit/release sequence but does not add supplementary locking. Recommended approaches: transactional reservation, atomic compare-and-swap (UPDATE quota SET used = used + ? WHERE used + ? <= limit), or pessimistic SELECT FOR UPDATE."

**AC-2: Fail-fast startup if no QuotaProvider bean**
- If no `QuotaProvider` bean is registered in the application context, the application fails to start with a clear error message indicating a `QuotaProvider` bean is required
- The module does not start silently with a no-op fallback

**AC-3: NoOpQuotaProvider available as explicit opt-in**
- `NoOpQuotaProvider.check()` always returns `true`
- `NoOpQuotaProvider.reserve()` returns a non-null no-op handle string (e.g., `"noop-handle"`)
- `NoOpQuotaProvider.commit()` and `release()` complete without error (void, no-op)
- `NoOpQuotaProvider` is NOT auto-configured or auto-registered by the module; consuming apps must explicitly declare `@Bean QuotaProvider quotaProvider() { return new NoOpQuotaProvider(); }`

**AC-4: Integration contract documented in `platform.video.contract` package-info.java**
- Already created in Story 1.1 — verify it explicitly states all three boundaries:
  1. `teamId` is absent from the `Video` entity — consuming apps that need team-video association must supply their own linkage
  2. `Visibility` (PRIVATE/GROUP/UNLISTED) is stored by the module but enforcement against group membership is the consuming app's responsibility
  3. End-user REST controllers for video operations are the consuming app's responsibility — the module provides the service layer only
- If any of the three are missing or vague, update the file accordingly

**AC-5: Tests**
- Unit test verifying `NoOpQuotaProvider` satisfies all four method contracts: `check()` returns `true`, `reserve()` returns non-null, `commit()` and `release()` complete without exception
- Startup failure test verifying the context fails to start when no `QuotaProvider` bean is present (using `ApplicationContextRunner` targeting `VideoConfig` is the recommended approach; a full `@SpringBootTest` approach is also valid but slow)

## Tasks / Subtasks

- [x] Task 1: Define `QuotaProvider` interface (AC: 1)
  - [x] Create `src/main/java/com/softropic/skillars/platform/video/contract/QuotaProvider.java`
  - [x] Declare the four methods: `check`, `reserve`, `commit`, `release` with parameter names and types as specified
  - [x] Add class-level Javadoc with the full concurrency contract statement (copy exact text from AC-1 above)

- [x] Task 2: Add fail-fast `QuotaProvider` wiring validation to `VideoConfig` (AC: 2)
  - [x] Modify `src/main/java/com/softropic/skillars/platform/video/config/VideoConfig.java`
  - [x] Add a `@Bean` validator using `ObjectProvider<QuotaProvider>` that throws `IllegalStateException` at startup if no bean is present
  - [x] Error message must be descriptive: state that a `QuotaProvider` bean is required and how to opt out using `NoOpQuotaProvider`
  - [x] See Dev Notes for the exact implementation pattern

- [x] Task 3: Implement `NoOpQuotaProvider` (AC: 3)
  - [x] Create `src/main/java/com/softropic/skillars/platform/video/service/NoOpQuotaProvider.java`
  - [x] Implement `QuotaProvider`; add Javadoc stating it is an explicit opt-in for apps without quota enforcement
  - [x] Do NOT annotate with `@Service`, `@Component`, or any Spring stereotype — it must NOT be auto-registered

- [x] Task 4: Verify and update `platform.video.contract` `package-info.java` (AC: 4)
  - [x] Read `src/main/java/com/softropic/skillars/platform/video/contract/package-info.java` (created in Story 1.1)
  - [x] Confirm all three integration boundary statements are present and complete
  - [x] If any are missing or vague, update the file — do not recreate it from scratch

- [x] Task 5: Write unit tests for `NoOpQuotaProvider` (AC: 5a)
  - [x] Create `src/test/java/com/softropic/skillars/platform/video/service/NoOpQuotaProviderTest.java`
  - [x] Test `check()` returns `true` for any ownerId/bytes combination
  - [x] Test `reserve()` returns a non-null string
  - [x] Test `commit()` completes without exception
  - [x] Test `release()` completes without exception for any handle string

- [x] Task 6: Write startup failure test (AC: 5b)
  - [x] Create `src/test/java/com/softropic/skillars/platform/video/config/QuotaProviderWiringTest.java`
  - [x] Use `ApplicationContextRunner` (NOT `@SpringBootTest`) for a fast, focused wiring test
  - [x] Verify context fails when no `QuotaProvider` bean is supplied
  - [x] Verify error message or failure cause references the missing `QuotaProvider` requirement
  - [x] See Dev Notes for the exact `ApplicationContextRunner` pattern

## Dev Notes

### What Story 1.1 Delivered (Do Not Repeat)

Story 1.1 created the complete module scaffold. The following already exist and must NOT be recreated:

- `platform.video.contract/` — package with `VideoErrorCode`, all enums, all exception classes
- `platform.video.contract/package-info.java` — integration contract documentation (verify; update if needed)
- `platform.video.config/VideoConfig.java` — bare `@Configuration` + `@EnableConfigurationProperties(VideoProperties.class)`
- `platform.video.service/VideoService.java` — empty placeholder class
- `BaseVideoIT.java` — WireMock + PostgreSQL integration test base class
- All three JPA entities and Spring Data repositories

This story adds **only**: `QuotaProvider.java`, `NoOpQuotaProvider.java`, the fail-fast validator in `VideoConfig`, and two test classes.

### QuotaProvider Interface: Exact Javadoc Text

The interface Javadoc must say (verbatim per epics FR-30):

```java
/**
 * Contract for quota enforcement on video uploads.
 *
 * <p>Implementations are responsible for concurrent-safe quota enforcement.
 * The module orchestrates the check → reserve → commit/release sequence but does
 * not add supplementary locking. Recommended approaches:
 * <ul>
 *   <li>Transactional reservation with row-level lock</li>
 *   <li>Atomic compare-and-swap: {@code UPDATE quota SET used = used + ? WHERE used + ? <= limit}</li>
 *   <li>Pessimistic {@code SELECT FOR UPDATE} on a quota row</li>
 * </ul>
 *
 * <p>The call sequence orchestrated by the module is:
 * <ol>
 *   <li>{@link #check(String, long)} — verify quota is available (no reservation yet)</li>
 *   <li>{@link #reserve(String, long)} — reserve the bytes; returns an opaque handle</li>
 *   <li>{@link #commit(String)} — on successful upload confirmation</li>
 *   <li>{@link #release(String)} — on upload failure, expiry, or session cancellation</li>
 * </ol>
 */
public interface QuotaProvider {
    boolean check(String ownerId, long requestedBytes);
    String reserve(String ownerId, long bytes);
    void commit(String reservationHandle);
    void release(String reservationHandle);
}
```

### Fail-Fast Startup Validator in VideoConfig

The cleanest approach that gives a helpful error message uses `ObjectProvider`:

```java
// In VideoConfig.java — add this method:

@Bean
public QuotaProviderValidator quotaProviderValidator(ObjectProvider<QuotaProvider> quotaProviderProvider) {
    QuotaProvider quotaProvider = quotaProviderProvider.getIfAvailable();
    if (quotaProvider == null) {
        throw new IllegalStateException(
            "Video module requires a QuotaProvider bean. " +
            "Register an implementation in your application @Configuration. " +
            "To disable quota enforcement, use: @Bean QuotaProvider quotaProvider() { return new NoOpQuotaProvider(); }");
    }
    return new QuotaProviderValidator(quotaProvider);
}

// QuotaProviderValidator can be a simple record or class — just a marker bean:
public record QuotaProviderValidator(QuotaProvider quotaProvider) {}
```

**Why `ObjectProvider` instead of direct injection?** Direct injection (`VideoConfig(@Bean ... QuotaProvider qp)`) also works but produces a less helpful Spring error. `ObjectProvider` lets us throw our own `IllegalStateException` with module-specific guidance in the message.

**Alternative (simpler, slightly less informative):** Direct injection:
```java
@Bean
public VideoModuleMarker videoModuleMarker(QuotaProvider quotaProvider) {
    // QuotaProvider is required; Spring will fail with NoSuchBeanDefinitionException if absent
    return new VideoModuleMarker();
}
```
This also passes AC-2 since startup does fail — the error message is Spring's standard "No qualifying bean" message which mentions `QuotaProvider`. Either approach is acceptable; the `ObjectProvider` approach with custom message is preferred per the AC wording.

### NoOpQuotaProvider: Exact Implementation

```java
package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.contract.QuotaProvider;

/**
 * Explicit opt-in no-op implementation of {@link QuotaProvider}.
 * Consuming apps that do not need quota enforcement must register this bean explicitly:
 * <pre>{@code
 *   @Bean
 *   public QuotaProvider quotaProvider() {
 *       return new NoOpQuotaProvider();
 *   }
 * }</pre>
 * This class is NOT auto-configured. The Video module will fail at startup if no
 * {@link QuotaProvider} bean is present.
 */
public class NoOpQuotaProvider implements QuotaProvider {

    @Override
    public boolean check(String ownerId, long requestedBytes) {
        return true;
    }

    @Override
    public String reserve(String ownerId, long bytes) {
        return "noop-reservation-handle";
    }

    @Override
    public void commit(String reservationHandle) {
        // no-op
    }

    @Override
    public void release(String reservationHandle) {
        // no-op
    }
}
```

### Startup Failure Test Pattern

Use `ApplicationContextRunner` for a focused, fast test that does NOT spin up the full Spring Boot application:

```java
package com.softropic.skillars.platform.video.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class QuotaProviderWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(VideoConfig.class)
        .withPropertyValues(
            "app.video.provider=bunny",
            "app.video.bunny.api-key=test-key",
            "app.video.bunny.library-id=123",
            "app.video.bunny.cdn-hostname=test.b-cdn.net",
            "app.video.upload.max-bytes=5368709120",
            "app.video.upload.allowed-mime-types=video/mp4",
            "app.video.upload.allowed-formats=MP4",
            "app.video.upload.session-ttl-minutes=60",
            "app.video.upload.rate-limit.requests-per-minute=10",
            "app.video.playback.token-ttl-minutes=15",
            "app.video.playback.token-max-ttl-minutes=120",
            "app.video.playback.revocation-window-hours=24",
            "app.video.reconciliation.fixed-delay-ms=60000",
            "app.video.reconciliation.batch-size=10"
        );

    @Test
    void failsAtStartupWhenNoQuotaProviderBeanRegistered() {
        contextRunner.run(context ->
            assertThat(context).hasFailed()
                .getFailure()
                .hasMessageContaining("QuotaProvider")
        );
    }

    @Test
    void startsWhenNoOpQuotaProviderRegistered() {
        contextRunner
            .withBean(QuotaProvider.class, NoOpQuotaProvider::new)
            .run(context -> assertThat(context).hasNotFailed());
    }
}
```

**Note on property values:** `VideoConfig` extends `@EnableConfigurationProperties(VideoProperties.class)`, so the `VideoProperties` bean will need the `app.video.*` properties to bind correctly. Include enough properties to avoid binding errors. You may not need all of them — run the test and add what's missing.

**Alternative (full `@SpringBootTest` approach):** While the `ApplicationContextRunner` approach is fast and targeted, a full `@SpringBootTest` will also verify wiring in the real application context. The `ApplicationContextRunner` approach is preferred for this story because Story 1.2 is testing module wiring logic, not application-level integration.

### package-info.java — Already Done

`package-info.java` was created in Story 1.1 at `platform.video.contract/package-info.java`. It already contains all three integration boundary statements. Read it first — if it's complete, **no change is needed**. Do NOT delete and recreate it.

Current content (from Story 1.1):
- teamId absence
- Visibility storage-without-enforcement boundary
- End-user REST controllers are consuming app's responsibility

### File Locations: Do NOT Deviate

| Component | Package | File |
|---|---|---|
| `QuotaProvider` interface | `platform.video.contract` | `QuotaProvider.java` |
| `NoOpQuotaProvider` implementation | `platform.video.service` | `NoOpQuotaProvider.java` |
| `VideoConfig` update | `platform.video.config` | `VideoConfig.java` (existing) |
| `package-info.java` | `platform.video.contract` | `package-info.java` (existing — verify only) |
| `NoOpQuotaProviderTest` | test: `platform.video.service` | `NoOpQuotaProviderTest.java` |
| `QuotaProviderWiringTest` | test: `platform.video.config` | `QuotaProviderWiringTest.java` |

### Architecture Compliance Check

Before committing any class, verify:
- `QuotaProvider` lives in `platform.video.contract` — ✅ it's a public API contract
- `NoOpQuotaProvider` lives in `platform.video.service` — ✅ an implementation of the contract interface
- `NoOpQuotaProvider` has zero Spring stereotypes — ✅ no auto-registration
- `VideoConfig` modification adds no business logic — ✅ just validates the required bean is wired
- No `import com.softropic.skillars.infrastructure.*` in `QuotaProvider` or `NoOpQuotaProvider` — ✅

### Project Structure Rules (from project-context.md)

- `QuotaProvider` in `platform.video.contract` — correct; it is the public API surface of the module
- Platform module layers: `api`, `service`, `repo`, `contract`, `config` — both `contract` and `service` placements are correct per layer rules
- `VideoConfig` is in `config` package — correct placement for Spring wiring beans
- Infrastructure layer must not import from `platform.*` — this story does not touch `infrastructure.*`
- Tests: use `AssertJ` (`assertThat`) for assertions — use throughout
- Tests: `ApplicationContextRunner` is a Spring Boot Test utility; import from `org.springframework.boot.test.context.runner`

### Existing Code to Preserve

`VideoConfig.java` currently contains only:
```java
@Configuration
@EnableConfigurationProperties(VideoProperties.class)
public class VideoConfig {
}
```

When modifying, preserve:
- `@Configuration` annotation
- `@EnableConfigurationProperties(VideoProperties.class)` annotation

Do not remove these. Add the `@Bean` validator method to the existing class.

### References

- FR-29: QuotaProvider interface definition — `_bmad-output/planning-artifacts/video-module/epics.md` §FR-29
- FR-30: Concurrency contract Javadoc requirement — `_bmad-output/planning-artifacts/video-module/epics.md` §FR-30
- Story 1.2 ACs: `_bmad-output/planning-artifacts/video-module/epics.md` §Story 1.2
- Package structure rules: `_bmad-output/project-context.md` §Module Internal Structure
- Existing `VideoConfig.java`: `src/main/java/com/softropic/skillars/platform/video/config/VideoConfig.java`
- Existing `package-info.java`: `src/main/java/com/softropic/skillars/platform/video/contract/package-info.java`
- Previous story learnings: `_bmad-output/implementation-artifacts/video-1-1-module-scaffold-database-schema-configuration.md` §Completion Notes

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

- Created `QuotaProvider` interface in `platform.video.contract` with four methods (`check`, `reserve`, `commit`, `release`) and full concurrency contract Javadoc per FR-30 exact text requirement.
- Modified `VideoConfig` to add `quotaProviderValidator` bean using `ObjectProvider<QuotaProvider>` fail-fast pattern with descriptive error message including opt-out instructions. `QuotaProviderValidator` defined as a nested record.
- Created `NoOpQuotaProvider` in `platform.video.service` with zero Spring stereotypes — explicit opt-in only as required by AC-3.
- Verified `package-info.java` — all three integration boundaries already present and complete from Story 1.1; no update required.
- All `VideoProperties` fields carry defaults so `ApplicationContextRunner` test requires no property overrides.
- 6 new tests: `NoOpQuotaProviderTest` (4 unit tests), `QuotaProviderWiringTest` (2 wiring tests). All pass. Full suite: 222 tests, 0 failures.

### File List

**New files created:**

`src/main/java/com/softropic/skillars/platform/video/contract/QuotaProvider.java`
`src/main/java/com/softropic/skillars/platform/video/service/NoOpQuotaProvider.java`
`src/test/java/com/softropic/skillars/platform/video/service/NoOpQuotaProviderTest.java`
`src/test/java/com/softropic/skillars/platform/video/config/QuotaProviderWiringTest.java`

**Modified files:**

`src/main/java/com/softropic/skillars/platform/video/config/VideoConfig.java` — added `quotaProviderValidator` bean with `ObjectProvider<QuotaProvider>` fail-fast check; nested `QuotaProviderValidator` record

**Verified (no change):**

`src/main/java/com/softropic/skillars/platform/video/contract/package-info.java` — all three integration boundary statements confirmed present and complete

## Change Log

- 2026-05-29: Story Video-1.2 implemented — `QuotaProvider` interface defined, fail-fast validator added to `VideoConfig`, `NoOpQuotaProvider` created (no Spring stereotypes), `package-info.java` verified complete, 6 new tests added (222 total, 0 failures)
