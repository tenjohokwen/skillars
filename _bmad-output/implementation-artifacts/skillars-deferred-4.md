# Story Deferred-4: Payment Resilience & Scheduler Distributed Locking

Status: backlog

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

- [ ] **Task 1 — Add ShedLock dependency** (AC: 1)
  - [ ] Add to `pom.xml`:
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
  - [ ] Add `@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")` to the main `@SpringBootApplication` class or a `@Configuration` class
  - [ ] Create `ShedLockConfig.java`:
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

- [ ] **Task 2 — Flyway V80: Create ShedLock table** (AC: 1)
  - [ ] Create `src/main/resources/db/migration/V80__shedlock_table.sql`:
    ```sql
    CREATE TABLE IF NOT EXISTS main.shedlock (
        name       VARCHAR(64)  NOT NULL,
        lock_until TIMESTAMPTZ  NOT NULL,
        locked_at  TIMESTAMPTZ  NOT NULL,
        locked_by  VARCHAR(255) NOT NULL,
        CONSTRAINT pk_shedlock PRIMARY KEY (name)
    );
    ```
  - [ ] ShedLock requires exactly this table name and schema — use `main` schema consistent with platform conventions; override the default table name if ShedLock defaults to `shedlock` without schema prefix (see `JdbcTemplateLockProvider` configuration for `tableName` override)

- [ ] **Task 3 — Add `@SchedulerLock` to all affected schedulers** (AC: 1)
  - [ ] `NeglectedSkillDetectionService.java` — find the `@Scheduled` method and add:
    ```java
    @Scheduled(cron = "${slu.neglected.cron:0 0 2 * * MON}")
    @SchedulerLock(name = "NeglectedSkillDetectionService_detect",
                   lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    public void detectNeglectedSkills() { ... }
    ```
  - [ ] `SessionPackExpiryScheduler.java`:
    ```java
    @SchedulerLock(name = "SessionPackExpiryScheduler_expire",
                   lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")
    ```
  - [ ] `BookingExpiryScheduler.java`:
    ```java
    @SchedulerLock(name = "BookingExpiryScheduler_expire",
                   lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")
    ```
  - [ ] `BookingReminderScheduler.java`:
    ```java
    @SchedulerLock(name = "BookingReminderScheduler_remind",
                   lockAtMostFor = "PT15M", lockAtLeastFor = "PT2M")
    ```
  - [ ] `BandwidthResetService.java` — monthly cron:
    ```java
    @SchedulerLock(name = "BandwidthResetService_reset",
                   lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    ```
  - [ ] `QuotaReservationTimeoutService.java`:
    ```java
    @SchedulerLock(name = "QuotaReservationTimeoutService_expire",
                   lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    ```
  - [ ] Read each scheduler's `@Scheduled` annotation to confirm the cron expressions and pick appropriate `lockAtMostFor` values (should be greater than the maximum expected runtime of the method)

- [ ] **Task 4 — Pessimistic lock on `extendPack`** (AC: 2)
  - [ ] In `SessionPackPaymentService.java` (or wherever `extendPack` lives), add a pessimistic lock on the pack load:
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
  - [ ] Confirm `SessionPackPurchase` has `extendedAt` field (from Story 3.10 / 7.2). If not, add it and create a Flyway migration to add the column

- [ ] **Task 5 — Fix `@Transactional` proxy bypass in payment services** (AC: 3)
  - [ ] Read `PaymentLifecycleService.java` — identify all private helper methods called via `this.` that are annotated `@Transactional` (the proxy bypass means they run in the caller's TX, not a new one, even when annotated `REQUIRES_NEW`)
  - [ ] Create a `PaymentPersistenceService.java` sibling `@Service` containing the extracted helpers:
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
  - [ ] Inject `PaymentPersistenceService` into `PaymentLifecycleService` and replace `this.persistPaymentSuccess(...)` with `paymentPersistenceService.persistPaymentSuccess(...)` — the call now goes through the Spring proxy
  - [ ] Apply the same extraction pattern to `SessionPackPaymentService` and `CashOutService` — create `SessionPackPersistenceService` and `CashOutPersistenceService` as needed, or consolidate into the existing `PaymentPersistenceService` if appropriate
  - [ ] Read the dev notes from Story 7.2 Group 2 (D5) for the exact list of methods to extract in each service

- [ ] **Task 6 — Pack ownership check in `createBookingRequest`** (AC: 4)
  - [ ] In `BookingService.createBookingRequest()` — after loading the `SessionPackPurchase` by `sessionPackPurchaseId`, add:
    ```java
    if (sessionPackPurchase != null
            && !sessionPackPurchase.getParentId().equals(authenticatedParentId)) {
        throw new OperationNotAllowedException(
            "Session pack does not belong to this parent",
            SecurityError.MISSING_RIGHTS);
    }
    ```
  - [ ] `authenticatedParentId` is the Long userId resolved from the JWT (same as what `BookingResource` passes in)
  - [ ] Add an IT test: `bookingWithOtherParentsPack_returns403()` — seed two parent accounts, two packs (one per parent), attempt booking with the wrong parent's pack → expect 403

- [ ] **Task 7 — Integration tests** (AC: 1, 2, 4)
  - [ ] TSID range `9320_xxx`
  - [ ] `extendPack_concurrentRequests_noDuplicateExtension()` — two concurrent calls; verify `expires_at` extended exactly 30 days, not 60
  - [ ] `bookingWithOtherParentsPack_returns403()` (from Task 6)
  - [ ] ShedLock integration: verify `main.shedlock` table exists after application startup (schema validation test)

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

### Debug Log References

### Completion Notes List

### File List

**New Files:**
- `src/main/resources/db/migration/V80__shedlock_table.sql`
- `src/main/java/com/softropic/skillars/infrastructure/config/ShedLockConfig.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/PaymentPersistenceService.java`

**Modified Files:**
- `pom.xml`
- `src/main/java/com/softropic/skillars/platform/development/service/NeglectedSkillDetectionService.java`
- `src/main/java/com/softropic/skillars/platform/booking/scheduler/SessionPackExpiryScheduler.java`
- `src/main/java/com/softropic/skillars/platform/booking/scheduler/BookingExpiryScheduler.java`
- `src/main/java/com/softropic/skillars/platform/booking/scheduler/BookingReminderScheduler.java`
- `src/main/java/com/softropic/skillars/platform/video/service/BandwidthResetService.java`
- `src/main/java/com/softropic/skillars/platform/video/service/QuotaReservationTimeoutService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/PaymentLifecycleService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/CashOutService.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
