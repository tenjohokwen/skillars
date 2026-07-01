# Story Deferred-4: Payment Resilience & Scheduler Distributed Locking

Status: done

## Story

As a platform operator,
I want schedulers to be safe in multi-instance deployments and payment-critical operations to be correctly transacted,
so that duplicate scheduler runs do not corrupt data and payment service self-calls correctly participate in transactions.

## Acceptance Criteria

1. **Given** the platform runs on multiple instances (horizontal scale or rolling deploy)
   **When** any `@Scheduled` method fires
   **Then** only one instance executes the scheduled work per firing — duplicate execution is prevented by ShedLock (or equivalent DB-backed distributed lock)
   **Affected schedulers**: `NeglectedSkillDetectionService` (weekly batch), `SessionPackExpiryScheduler`, `BookingExpiryScheduler`, `BookingReminderScheduler`, `BandwidthResetService`, `QuotaReservationTimeoutService`

2. **Given** a coach requests a session pack extension (`extendPack`)
   **When** two concurrent requests arrive for the same pack before either commits
   **Then** a pessimistic write lock (`SELECT FOR UPDATE`) on the `SessionPackPurchase` row prevents double-extension (+30+30 days)
   **And** the second concurrent request reads the already-extended pack and proceeds normally (idempotent) or is rejected with `409`

3. **Given** `PaymentLifecycleService` calls private helper methods (`persistPaymentSuccess`, `persistPaymentFailure`, `persistPackBatchPayment`, `persistCreditBatchPayment`, `writeCashOutLedgerEntries`, `getOrCreateStripeCustomer`, `createPurchase`) from within `@Transactional` methods
   **When** those helpers are called via `this.helperMethod()`
   **Then** the helpers participate in the caller's transaction (no Spring proxy bypass)
   **And** the same fix is applied to `SessionPackPaymentService` and `CashOutService` self-calls that use the same pattern
   **Solution**: extract each helper into a sibling `@Service` bean so the call goes through the Spring proxy

4. **Given** a parent submits a booking request with a `sessionPackPurchaseId`
   **When** the pack belongs to a different parent
   **Then** `BookingService.createBookingRequest()` rejects the request with `403` — a parent cannot deduct credits from another parent's pack
   **And** the ownership check is: `sessionPackPurchase.getParentId() != authenticatedParentId` → throw `OperationNotAllowedException`

## Tasks / Subtasks

- [x] **Task 1 — Add ShedLock dependency** (AC: 1)
  - [x] Add to `pom.xml`:
    ```xml
    <dependency>
        <groupId>net.javacrumbs.shedlock</groupId>
        <artifactId>shedlock-spring</artifactId>
        <version>5.13.0</version>  <!-- use latest stable -->
    </dependency>
    <dependency>
        <groupId>net.javacrumbs.shedlock</groupId>
        <artifactId>shedlock-provider-jdbc-template</artifactId>
        <version>5.13.0</version>
    </dependency>
    ```
  - [x] Add `@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")` to the main `@SpringBootApplication` class or a `@Configuration` class
  - [x] Create `ShedLockConfig.java`:
    ```java
    @Configuration
    public class ShedLockConfig {
        @Bean
        public LockProvider lockProvider(DataSource dataSource) {
            return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                    .withJdbcTemplate(new JdbcTemplate(dataSource))
                    .usingDbTime()
                    .build()
            );
        }
    }
    ```

- [x] **Task 2 — Flyway V80: Create ShedLock table** (AC: 1)
  - [x] Create `src/main/resources/db/migration/V80__shedlock_table.sql`:
    ```sql
    CREATE TABLE IF NOT EXISTS main.shedlock (
        name       VARCHAR(64)  NOT NULL,
        lock_until TIMESTAMPTZ  NOT NULL,
        locked_at  TIMESTAMPTZ  NOT NULL,
        locked_by  VARCHAR(255) NOT NULL,
        CONSTRAINT pk_shedlock PRIMARY KEY (name)
    );
    ```
  - [x] ShedLock requires exactly this table name and schema — use `main` schema consistent with platform conventions; override the default table name if ShedLock defaults to `shedlock` without schema prefix (see `JdbcTemplateLockProvider` configuration for `tableName` override)

- [x] **Task 3 — Add `@SchedulerLock` to all affected schedulers** (AC: 1)
  - [x] `NeglectedSkillDetectionService.java` — find the `@Scheduled` method and add:
    ```java
    @Scheduled(cron = "${slu.neglected.cron:0 0 2 * * MON}")
    @SchedulerLock(name = "NeglectedSkillDetectionService_detect",
                   lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    public void detectNeglectedSkills() { ... }
    ```
  - [x] `SessionPackExpiryScheduler.java`:
    ```java
    @SchedulerLock(name = "SessionPackExpiryScheduler_expire",
                   lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")
    ```
  - [x] `BookingExpiryScheduler.java`:
    ```java
    @SchedulerLock(name = "BookingExpiryScheduler_expire",
                   lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")
    ```
  - [x] `BookingReminderScheduler.java`:
    ```java
    @SchedulerLock(name = "BookingReminderScheduler_remind",
                   lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")
    ```
  - [x] `BandwidthResetService.java` — monthly cron:
    ```java
    @SchedulerLock(name = "BandwidthResetService_reset",
                   lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    ```
  - [x] `QuotaReservationTimeoutService.java`:
    ```java
    @SchedulerLock(name = "QuotaReservationTimeoutService_expire",
                   lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    ```
  - [x] Read each scheduler's `@Scheduled` annotation to confirm the cron expressions and pick appropriate `lockAtMostFor` values (should be greater than the maximum expected runtime of the method)

- [x] **Task 4 — Pessimistic lock on `extendPack`** (AC: 2)
  - [x] In `SessionPackPaymentService.java` (or wherever `extendPack` lives), add a pessimistic lock on the pack load:
    - Add to `SessionPackPurchaseRepository`:
      ```java
      @Lock(LockModeType.PESSIMISTIC_WRITE)
      @Query("SELECT s FROM SessionPackPurchase s WHERE s.id = :id")
      Optional<SessionPackPurchase> findByIdForUpdate(@Param("id") UUID id);
      ```
    - In `extendPack()`:
      ```java
      @Transactional
      public void extendPack(UUID packId, Long parentId) {
          SessionPackPurchase pack = sessionPackPurchaseRepository.findByIdForUpdate(packId)
              .orElseThrow(() -> new ResourceNotFoundException("SessionPackPurchase", packId.toString()));
          // ownership check
          if (!pack.getParentId().equals(parentId)) throw new OperationNotAllowedException(...);
          // idempotency: if already extended recently, skip or return
          if (pack.getExtendedAt() != null && pack.getExtendedAt().isAfter(Instant.now().minus(1, ChronoUnit.MINUTES))) {
              return; // concurrent call already extended it
          }
          pack.setExpiresAt(pack.getExpiresAt().plus(30, ChronoUnit.DAYS));
          pack.setExtendedAt(Instant.now());
          sessionPackPurchaseRepository.save(pack);
      }
      ```
  - [x] Confirm `SessionPackPurchase` has `extendedAt` field (from Story 3.10 / 7.2). If not, add it and create a Flyway migration to add the column

- [x] **Task 5 — Fix `@Transactional` proxy bypass in payment services** (AC: 3)
  - [x] Read `PaymentLifecycleService.java` — identify all private helper methods called via `this.` that are annotated `@Transactional` (the proxy bypass means they run in the caller's TX, not a new one, even when annotated `REQUIRES_NEW`)
  - [x] Create a `PaymentPersistenceService.java` sibling `@Service` containing the extracted helpers:
    ```java
    @Service
    @RequiredArgsConstructor
    @Transactional
    public class PaymentPersistenceService {
        // extracted from PaymentLifecycleService:
        public void persistPaymentSuccess(...) { ... }
        public void persistPaymentFailure(...) { ... }
        // etc.
    }
    ```
  - [x] Inject `PaymentPersistenceService` into `PaymentLifecycleService` and replace `this.persistPaymentSuccess(...)` with `paymentPersistenceService.persistPaymentSuccess(...)` — the call now goes through the Spring proxy
  - [x] Apply the same extraction pattern to `SessionPackPaymentService` and `CashOutService` — create `SessionPackPersistenceService` and `CashOutPersistenceService` as needed, or consolidate into the existing `PaymentPersistenceService` if appropriate
  - [x] Read the dev notes from Story 7.2 Group 2 (D5) for the exact list of methods to extract in each service

- [x] **Task 6 — Pack ownership check in `createBookingRequest`** (AC: 4)
  - [x] In `BookingService.createBookingRequest()` — after loading the `SessionPackPurchase` by `sessionPackPurchaseId`, add:
    ```java
    if (sessionPackPurchase != null
            && !sessionPackPurchase.getParentId().equals(authenticatedParentId)) {
        throw new OperationNotAllowedException(
            "Session pack does not belong to this parent",
            SecurityError.MISSING_RIGHTS);
    }
    ```
  - [x] `authenticatedParentId` is the Long userId resolved from the JWT (same as what `BookingResource` passes in)
  - [x] Add an IT test: `bookingWithOtherParentsPack_returns403()` — seed two parent accounts, two packs (one per parent), attempt booking with the wrong parent's pack → expect 403

- [x] **Task 7 — Integration tests** (AC: 1, 2, 4)
  - [x] TSID range `9320_xxx`
  - [x] `extendPack_concurrentRequests_noDuplicateExtension()` — two concurrent calls; verify `expires_at` extended exactly 30 days, not 60
  - [x] `bookingWithOtherParentsPack_returns403()` (from Task 6)
  - [x] ShedLock integration: verify `main.shedlock` table exists after application startup (schema validation test)

## Dev Notes

### ShedLock version

Check the Spring Boot version in `pom.xml` before picking a ShedLock version — ShedLock 5.x requires Spring Boot 3.x. The `shedlock-provider-jdbc-template` does NOT require a separate JDBC driver — it reuses the existing `DataSource` bean.

### `@EnableSchedulerLock` placement

`@EnableSchedulerLock` must be on a `@Configuration` class that is loaded by the application context. Adding it to the main `@SpringBootApplication` class is safe and commonly done. Check if `@EnableScheduling` is already present — `@EnableSchedulerLock` replaces it.

### ShedLock table schema prefix

ShedLock by default uses a table named `shedlock` with no schema prefix. The `JdbcTemplateLockProvider` can be configured with `tableName("main.shedlock")` to use the project's schema. Alternatively, set the search path in the JDBC URL. Confirm by reading `application.properties` for the `spring.datasource.url` search_path setting.

### Pessimistic lock requires `@Transactional`

`@Lock(PESSIMISTIC_WRITE)` only works inside an active transaction. `extendPack()` must be `@Transactional` (confirm it is). If the test spawns two threads without an outer TX, each thread's `@Transactional` method creates its own TX and the lock works as expected.

### Proxy bypass — why it matters

When `PaymentLifecycleService` calls `this.persistPaymentSuccess()`, Spring's AOP proxy is bypassed — the `@Transactional` annotation on `persistPaymentSuccess` is ignored, and the method runs in the caller's TX. This is particularly dangerous when `persistPaymentSuccess` is annotated `REQUIRES_NEW` (the intent was to commit payment records independently so a failure doesn't roll back the outer booking state). The fix (separate `@Service`) is the standard Spring recommendation.

### `extendedAt` column

If `SessionPackPurchase` does not already have `extendedAt`: add `@Column(name = "extended_at") private Instant extendedAt;` to the entity and add a migration:
```sql
-- in V80 or a separate V81 if V80 is already ShedLock
ALTER TABLE booking.session_pack_purchases ADD COLUMN IF NOT EXISTS extended_at TIMESTAMPTZ;
```

### References — Files to Read Before Implementing

- `PaymentLifecycleService.java` — Story 7.2 Group 2 dev notes list the exact private methods to extract
- `SessionPackPaymentService.java` — `extendPack()` current implementation and `createTier` TOCTOU note
- `CashOutService.java` — self-call methods
- `NeglectedSkillDetectionService.java` — `@Scheduled` annotation and method name
- `SessionPackExpiryScheduler.java`, `BookingExpiryScheduler.java`, `BookingReminderScheduler.java` — scheduler cron values
- `BandwidthResetService.java`, `QuotaReservationTimeoutService.java` — scheduler cron values
- `BookingService.java:createBookingRequest()` — where to add the pack ownership check
- `SessionPackPurchase.java` — entity fields; confirm `parentId`, `expiresAt`, `extendedAt`

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `mvn compile` / `mvn test-compile` — clean after adding ShedLock deps and all annotation changes.
- `mvn test -Dtest=ShedLockConfigIT,PackExtensionIT,BookingRequestResourceIT` — 2/6/12 tests passed respectively (new tests included).
- `mvn test -Dtest=QuotaReservationTimeoutServiceTest,NeglectedSkillDetectionServiceTest,BookingReminderSchedulerTest,SessionPackExpirySchedulerTest,BookingExpirySchedulerTest` — all pre-existing unit tests for modified schedulers still pass after adding `@SchedulerLock`.
- `mvn test` (full suite) — run for final regression check before marking story ready for review.

### Completion Notes List

**Scope-narrowing discovery:** before implementing, I read the current state of every file in Dev Notes "References" and found that AC3 and AC4 were already fully implemented by a prior story (7.2-era work):
- AC3 (proxy-bypass fix): `PaymentLifecycleService` already delegates all persistence to a sibling `BookingPaymentPersistenceService` bean (no `this.` self-calls to `@Transactional` methods remain). `SessionPackPaymentService.getOrCreateStripeCustomer`/`createPurchase` and all of `CashOutService` are plain (non-`@Transactional`) private helpers, so there is no proxy-bypass hazard there either — confirmed via `grep "this\.[a-zA-Z]*("` returning no matches in any of the three services.
- AC4 (pack ownership check): `BookingService.createBookingRequest()` already throws `OperationNotAllowedException` when `pack.getParentId() != parentId` (line ~191), before the coach-mismatch check.
Given this, Task 5 and Task 6's code changes were no-ops; I verified the existing behavior with a new IT test (Task 6's `bookingWithOtherParentsPack_returns403`-equivalent) rather than re-implementing something already correct.

**What was actually built:**
- **AC1 (ShedLock):** Added `shedlock-spring` + `shedlock-provider-jdbc-template` 5.13.0 to `pom.xml` (compatible with the project's Spring Boot 3.5.11). Created `ShedLockConfig` (`infrastructure/config`) with a `JdbcTemplateLockProvider` bean pointed at `main.shedlock` (the project's single "main" schema convention — confirmed via `application.yaml`'s `default_schema: main` / `flyway.defaultSchema: main`, no `search_path` override). Replaced the existing `@EnableScheduling` on `platform/notification/config/AsyncConfig.java` (the only place scheduling was enabled) with `@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")`, per Dev Notes guidance that the two annotations are mutually exclusive.
- **AC1 (Flyway):** `V80__shedlock_table.sql` creates `main.shedlock` with the exact ShedLock-required schema.
- **AC1 (`@SchedulerLock`):** Added to all six affected schedulers with the lock names/timeouts specified in Task 3. Note: the actual scheduler classes live in `platform.booking.service` (not `platform.booking.scheduler` as the story's File List assumed) — corrected in the File List below.
- **AC2 (pessimistic lock):** `SessionPackPaymentService.extendPack()` was calling `sessionPackPurchaseRepository.findById(...)` — a plain read with no row lock — even though `findByIdForUpdate()` (PESSIMISTIC_WRITE) already existed on the repository from earlier work. Changed the one line to use `findByIdForUpdate`. This was the one real concurrency gap: two concurrent `extendPack` calls could previously both pass the `extendedAt == null` check before either committed. Verified with a new 2-thread `CyclicBarrier` IT test — exactly one thread succeeds, the other is rejected via the existing `payment.packExtensionNotEligible` check (not a `409`, but the story explicitly allows either "idempotent" or "409" outcome, and rejection here is the existing, already-tested behavior for double-extension).
- **Task 7 (tests):** Added `extendPack_concurrentRequests_noDuplicateExtension()` to `PackExtensionIT`, `createBookingRequest_otherParentsSessionPack_returns403()` to `BookingRequestResourceIT`, and a new `ShedLockConfigIT` (schema + bean wiring check). Deviated from the story's suggested `9320_xxx` TSID range: both target test files already had established local ID conventions from earlier stories (`BookingRequestResourceIT` uses `95000000xx`, `PackExtensionIT` uses `9000x` coach IDs) — introducing a new unrelated range would have been inconsistent with the file's existing seed/cleanup logic, so the new tests reuse each file's existing convention instead.

**Verification:** `mvn compile` and `mvn test-compile` clean. Targeted IT/unit runs for all touched classes pass. Full `mvn test` regression suite run before completion (see Debug Log).

### File List

**New Files:**
- `src/main/resources/db/migration/V80__shedlock_table.sql`
- `src/main/java/com/softropic/skillars/infrastructure/config/ShedLockConfig.java`
- `src/test/java/com/softropic/skillars/infrastructure/config/ShedLockConfigIT.java`

**Modified Files:**
- `pom.xml`
- `src/main/java/com/softropic/skillars/platform/notification/config/AsyncConfig.java` (`@EnableScheduling` → `@EnableSchedulerLock`)
- `src/main/java/com/softropic/skillars/platform/development/service/NeglectedSkillDetectionService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/SessionPackExpiryScheduler.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingExpiryScheduler.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingReminderScheduler.java`
- `src/main/java/com/softropic/skillars/platform/video/service/BandwidthResetService.java`
- `src/main/java/com/softropic/skillars/platform/video/service/QuotaReservationTimeoutService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java` (`extendPack` now uses `findByIdForUpdate`)
- `src/test/java/com/softropic/skillars/platform/payment/service/PackExtensionIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java`

**Verified already correct (no changes needed):**
- `src/main/java/com/softropic/skillars/platform/payment/service/PaymentLifecycleService.java` (AC3 — already delegates to `BookingPaymentPersistenceService`)
- `src/main/java/com/softropic/skillars/platform/payment/service/CashOutService.java` (AC3 — no self-call bypass pattern present)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` (AC4 — ownership check already present)
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java` (AC2 — `findByIdForUpdate` already existed)
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchase.java` (AC2 — `extendedAt` field already present)

## Change Log

- 2026-07-01: Implemented ShedLock distributed scheduler locking (AC1: dependency, Flyway `main.shedlock` table, `@SchedulerLock` on all 6 affected schedulers), fixed `extendPack` to use the existing pessimistic-lock repository method (AC2), and added integration test coverage for concurrent pack extension, cross-parent pack booking rejection, and ShedLock schema wiring (Task 7). AC3 and AC4 were found already implemented by prior work and verified rather than re-implemented.

### Review Findings

- [x] [Review][Decision] AC2 rejection returns HTTP 422, not the spec's literal "409" — resolved: user chose to make the code return 409. `SessionPackPaymentService.extendPack()` now checks `extendedAt != null` *before* the timing-window check and throws `ResponseStatusException(HttpStatus.CONFLICT, "payment.packAlreadyExtended")` → 409, matching the existing `DisputeService`/`GdprRequestService` convention (a plain `ResponseStatusException` caught by the app-wide `ApiAdvice.responseStatusExceptionHandler`); the timing-window check still throws `PaymentGatewayException` → 422. Ordering matters here: checking the window first (as originally written) would have masked the conflict, because once a pack is extended, `expiresAt` moves 30 days forward and the recomputed window start also shifts forward — that made the *concurrent loser* fail the timing-window check instead of the already-extended check, silently returning 422 instead of 409. Caught by re-running `PackExtensionIT` after the initial fix (the pre-existing sequential `extendPack_doubleExtension_rejected` test also had to be updated to expect 409, since the ordering bug affected it too, not just the new concurrency test). [src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java:84-97, src/test/java/com/softropic/skillars/platform/payment/service/PackExtensionIT.java]
- [x] [Review][Patch] `@EnableSchedulerLock` replacing `@EnableScheduling` stops every `@Scheduled` method in the app from ever firing — fixed: re-added `@EnableScheduling` alongside `@EnableSchedulerLock` on `AsyncConfig`; the two annotations are complementary (one triggers `@Scheduled` methods, the other adds the distributed-lock interceptor), not mutually exclusive as the Dev Notes assumed. [src/main/java/com/softropic/skillars/platform/notification/config/AsyncConfig.java:23-24]
- [x] [Review][Patch] `PackExtensionIT`'s concurrency test swallows `CyclicBarrier.await()` exceptions (`catch (Exception ignored) {}`), so a barrier timeout/break would silently degrade the test to non-concurrent execution while it could still report a pass — fixed: removed the swallow; `barrier.await()` now propagates through the `Callable`, failing the test loudly on a real barrier failure. [src/test/java/com/softropic/skillars/platform/payment/service/PackExtensionIT.java:121]
- [x] [Review][Defer] `lockAtMostFor` timeouts on `QuotaReservationTimeoutService` and `NeglectedSkillDetectionService` are not validated against their unbounded work loops (an un-chunked `do/while` batch drain and an un-chunked per-player loop, respectively) — under data growth, ShedLock could force-expire the lock mid-run and let a second instance start an overlapping execution, reopening the exact race AC1 exists to close. [src/main/java/com/softropic/skillars/platform/video/service/QuotaReservationTimeoutService.java:28, src/main/java/com/softropic/skillars/platform/development/service/NeglectedSkillDetectionService.java:58] — deferred, pre-existing loop pattern, needs production-scale data to size lock timeouts correctly
- [x] [Review][Defer] No log or metric is emitted when `@SchedulerLock` skips a run because another instance already holds the lock — indistinguishable in production from a job silently failing to run due to a bug. [src/main/java/com/softropic/skillars/platform/notification/config/AsyncConfig.java] — deferred, observability enhancement
- [x] [Review][Defer] `@SchedulerLock` and `@Transactional` are stacked on the same method with no explicit `@Order`, so their AOP advisor nesting order (lock vs. transaction boundary) is unspecified — plausible but unconfirmed risk that the distributed lock could release before the DB transaction commits. [src/main/java/com/softropic/skillars/platform/booking/service/BookingExpiryScheduler.java:40, src/main/java/com/softropic/skillars/platform/booking/service/BookingReminderScheduler.java, src/main/java/com/softropic/skillars/platform/video/service/BandwidthResetService.java] — deferred, no concrete repro, needs verification against ShedLock's documented ordering guarantees
