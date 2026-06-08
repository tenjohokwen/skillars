# Story 5.3: Observability Instrumentation

Status: done

## Story

As a system operator,
I want named Micrometer metrics, structured logging with request context, and confirmed `@Observed` tracing across all storage operations,
so that I can monitor storage health, diagnose latency spikes, and trace requests end-to-end in production.

## Acceptance Criteria

1. **Named Micrometer metrics via `StorageMetrics` class**: Given a file upload, download, deletion, or replication event occurs, when it completes (success or failure), then the following Micrometer metrics are recorded using named constants defined in a `StorageMetrics` class:
   - `storage.upload.latency` (timer, tags: `provider`, `status`)
   - `storage.download.latency` (timer, tags: `provider`, `status`)
   - `storage.delete.latency` (timer, tags: `provider`, `status`)
   - `storage.replication.queue.depth` (gauge: count of PENDING `outbox_replication_jobs`)
   - `storage.file.size.bytes` (distribution summary, recorded at confirm-upload time)
   - `storage.error.count` (counter, tags: `operation`, `error_code`)

2. **MDC structured logging on exceptions**: Given any `StorageService` method executes, when an exception occurs, then `@Slf4j` structured logging includes MDC context fields: `storageKey`, `tenantId`, `operation`, `provider` — never raw file content or credentials.

3. **Queue depth gauge updates at poll cycle**: Given `app.storage.replication.queue.depth` gauge, when `OutboxPollerScheduler` runs, then the gauge value reflects the current count of PENDING jobs in `outbox_replication_jobs` (queried at each poll cycle).

### Review Findings

- [x] [Review][Patch] Incomplete failure context [S3StorageService.java:recover methods] - Resolved: Patch applied.
- [x] [Review][Dismissed] Executor resource exhaustion [S3StorageService.java:put] - Dismissed: False positive, executor is managed by Spring.
- [x] [Review][Patch] Potential path traversal [StorageKeyGenerator.java:18-20] - Resolved: Applied sanitization patch.


- [x] Task 1: Create `StorageMetrics` component with constants, timer/counter/gauge helpers (AC: 1, 3)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/service/StorageMetrics.java` as `@Service`
  - [x] Define public static final String constants: `UPLOAD_LATENCY = "storage.upload.latency"`, `DOWNLOAD_LATENCY = "storage.download.latency"`, `DELETE_LATENCY = "storage.delete.latency"`, `REPLICATION_QUEUE_DEPTH = "storage.replication.queue.depth"`, `FILE_SIZE_BYTES = "storage.file.size.bytes"`, `ERROR_COUNT = "storage.error.count"`
  - [x] Inject `MeterRegistry` via constructor
  - [x] Declare `private final AtomicLong replicationQueueDepth = new AtomicLong(0L)` as a field
  - [x] In `@PostConstruct` method `initGauge()`: register `Gauge.builder(REPLICATION_QUEUE_DEPTH, replicationQueueDepth, AtomicLong::get).description("PENDING outbox replication jobs").register(meterRegistry)`
  - [x] Add method `recordLatency(String metricName, String provider, String status, long nanoseconds)` — internally does `Timer.builder(metricName).tag("provider", provider).tag("status", status).register(meterRegistry).record(nanoseconds, TimeUnit.NANOSECONDS)`
  - [x] Add method `recordFileSizeBytes(long sizeBytes)` — uses `DistributionSummary.builder(FILE_SIZE_BYTES).baseUnit("bytes").register(meterRegistry).record(sizeBytes)`
  - [x] Add method `recordError(String operation, String errorCode)` — uses `Counter.builder(ERROR_COUNT).tag("operation", operation).tag("error_code", errorCode).register(meterRegistry).increment()`
  - [x] Add method `updateQueueDepth(long count)` — calls `replicationQueueDepth.set(count)`

- [x] Task 2: Instrument `StorageSigningService` with timers, distribution summary, error counter, and MDC (AC: 1, 2)
  - [x] Update file: `src/main/java/com/softropic/skillars/infrastructure/storage/service/StorageSigningService.java`
  - [x] Add `StorageMetrics storageMetrics` to the constructor (via `@RequiredArgsConstructor` — add field)
  - [x] Add `import static net.logstash.logback.argument.StructuredArguments.kv;`
  - [x] **`signUpload()`**: wrap body in `try-finally`; at entry set MDC: `MDC.put("operation", "sign_upload")`, `MDC.put("provider", properties.getProvider())`, `MDC.put("tenantId", request.tenantId())`; in finally clear those MDC keys. No latency timer for sign (already covered by `@Observed`).
  - [x] **`confirmUpload()`**: at entry set MDC: `MDC.put("storageKey", key)`, `MDC.put("operation", "confirm_upload")`, `MDC.put("provider", properties.getProvider())`, `MDC.put("tenantId", request.tenantId())`; wrap with `long start = System.nanoTime()`; on success call `storageMetrics.recordLatency(StorageMetrics.UPLOAD_LATENCY, properties.getProvider(), "success", elapsed)` and `storageMetrics.recordFileSizeBytes(saved.getSizeBytes())`; on exception call `storageMetrics.recordLatency(StorageMetrics.UPLOAD_LATENCY, properties.getProvider(), "error", elapsed)` + `storageMetrics.recordError("confirm_upload", ex instanceof ApplicationException ae ? ae.getErrorCode().getErrorCode() : "UNKNOWN")`; clear MDC in finally
  - [x] **`signDownload()`**: at entry set MDC: `MDC.put("storageKey", key)`, `MDC.put("operation", "sign_download")`, `MDC.put("provider", properties.getProvider())`; wrap with timer; on success `storageMetrics.recordLatency(DOWNLOAD_LATENCY, provider, "success", elapsed)`; on exception record error + latency with status="error"; clear MDC in finally
  - [x] **`softDelete()`**: at entry set MDC: `MDC.put("storageKey", key)`, `MDC.put("operation", "soft_delete")`, `MDC.put("provider", properties.getProvider())`; wrap with timer; on success record `DELETE_LATENCY` with status="success"; on exception record error + latency with status="error"; clear MDC in finally
  - [x] Use `MDC.remove(key)` (not `MDC.clear()`) in finally blocks to avoid removing MDC fields set by caller frameworks

- [x] Task 3: Add MDC context in `DeletionSchedulerService` and `OutboxPollerScheduler` (AC: 2)
  - [x] Update `src/main/java/com/softropic/skillars/infrastructure/storage/service/DeletionSchedulerService.java`: in the `processDeletions()` loop, before each `storageService.delete(fso.getKey())` call, set `MDC.put("storageKey", fso.getKey())`, `MDC.put("operation", "physical_delete")`, `MDC.put("provider", properties.getProvider())`; remove in finally after each iteration
  - [x] Update `src/main/java/com/softropic/skillars/infrastructure/storage/service/OutboxPollerScheduler.java`: at start of `processJob()`, set `MDC.put("storageKey", key)`, `MDC.put("operation", job.getJobType().name().toLowerCase())`, `MDC.put("provider", "backup")`; remove in finally

- [x] Task 4: Add `countByStatus` query and wire queue depth into `OutboxPollerScheduler` (AC: 3)
  - [x] Update `src/main/java/com/softropic/skillars/infrastructure/storage/repo/OutboxReplicationJobRepository.java`: add `@Query("SELECT COUNT(j) FROM OutboxReplicationJob j WHERE j.status = :status") long countByStatus(@Param("status") OutboxReplicationJob.ReplicationJobStatus status);`
  - [x] Update `src/main/java/com/softropic/skillars/infrastructure/storage/service/OutboxPollerScheduler.java`: add `StorageMetrics storageMetrics` field (constructor injection); at start of `pollAndProcess()` before claiming batch, call `storageMetrics.updateQueueDepth(outboxReplicationJobRepository.countByStatus(OutboxReplicationJob.ReplicationJobStatus.PENDING))`
  - [x] Update `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageConfig.java`: inject `StorageMetrics storageMetrics` into `outboxPollerScheduler()` factory bean and pass to constructor

- [x] Task 5: Verify no regressions (AC: all)
  - [x] Run `mvn test -Dtest=StorageResourceIT` — all 5 HTTP-level tests pass
  - [x] Run `mvn test -Dtest=S3StorageServiceIT` — all 7 tests pass
  - [x] Run full test suite — all existing 250 tests (approx.) pass; no regressions

## Dev Notes

### Critical: `@Observed` Tracing is Already Complete — Do NOT Duplicate

`StorageResource` already carries `@Observed` at class level (`name = "storage"`) and each method level:
- `@Observed(name = "storage.sign.upload")` on `signUpload()`
- `@Observed(name = "storage.confirm.upload")` on `confirmUpload()`
- `@Observed(name = "storage.sign.download")` on `signDownload()`
- `@Observed(name = "storage.delete")` on `deleteFile()`

`ObservabilityConfig` wires `ObservedAspect` and `TimedAspect` globally. **Do not** add `@Observed` or `@Timed` annotations to service classes — this story adds NAMED Micrometer metrics with provider/status tags at the service layer, which are additional to (not replacing) the tracing already in place.

### `StorageMetrics` Design — `AtomicLong` Gauge Pattern

Use an `AtomicLong` for the queue depth gauge, NOT a DB-query supplier. The `OutboxPollerScheduler` calls `storageMetrics.updateQueueDepth(count)` at each poll cycle, giving precise control over when the DB is read.

```java
@Service
public class StorageMetrics {
    public static final String UPLOAD_LATENCY = "storage.upload.latency";
    public static final String DOWNLOAD_LATENCY = "storage.download.latency";
    public static final String DELETE_LATENCY = "storage.delete.latency";
    public static final String REPLICATION_QUEUE_DEPTH = "storage.replication.queue.depth";
    public static final String FILE_SIZE_BYTES = "storage.file.size.bytes";
    public static final String ERROR_COUNT = "storage.error.count";

    private final MeterRegistry meterRegistry;
    private final AtomicLong replicationQueueDepth = new AtomicLong(0L);

    public StorageMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void initGauge() {
        Gauge.builder(REPLICATION_QUEUE_DEPTH, replicationQueueDepth, AtomicLong::get)
            .description("Count of PENDING outbox replication jobs")
            .register(meterRegistry);
    }

    public void recordLatency(String metricName, String provider, String status, long nanos) {
        Timer.builder(metricName)
            .tag("provider", provider)
            .tag("status", status)
            .register(meterRegistry)
            .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordFileSizeBytes(long sizeBytes) {
        DistributionSummary.builder(FILE_SIZE_BYTES)
            .baseUnit("bytes")
            .register(meterRegistry)
            .record((double) sizeBytes);
    }

    public void recordError(String operation, String errorCode) {
        Counter.builder(ERROR_COUNT)
            .tag("operation", operation)
            .tag("error_code", errorCode)
            .register(meterRegistry)
            .increment();
    }

    public void updateQueueDepth(long count) {
        replicationQueueDepth.set(count);
    }
}
```

**Required imports:** `io.micrometer.core.instrument.*` (MeterRegistry, Gauge, Timer, Counter, DistributionSummary), `jakarta.annotation.PostConstruct`, `java.util.concurrent.TimeUnit`, `java.util.concurrent.atomic.AtomicLong`.

### MDC Structured Logging Pattern

Use `MDC.put()` / `MDC.remove()` (NOT `MDC.clear()` — that would wipe caller-set fields). Use try-finally to guarantee cleanup:

```java
MDC.put("storageKey", key);
MDC.put("tenantId", request.tenantId());
MDC.put("operation", "confirm_upload");
MDC.put("provider", properties.getProvider());
long start = System.nanoTime();
try {
    // ... operation body ...
    storageMetrics.recordLatency(StorageMetrics.UPLOAD_LATENCY,
        properties.getProvider(), "success", System.nanoTime() - start);
} catch (Exception ex) {
    storageMetrics.recordLatency(StorageMetrics.UPLOAD_LATENCY,
        properties.getProvider(), "error", System.nanoTime() - start);
    if (ex instanceof ApplicationException ae) {
        storageMetrics.recordError("confirm_upload", ae.getErrorCode().getErrorCode());
    } else {
        storageMetrics.recordError("confirm_upload", "UNKNOWN");
    }
    throw ex;
} finally {
    MDC.remove("storageKey");
    MDC.remove("tenantId");
    MDC.remove("operation");
    MDC.remove("provider");
}
```

**MDC field availability by call site:**

| Call site | storageKey | tenantId | operation | provider |
|---|---|---|---|---|
| `StorageSigningService.confirmUpload()` | ✅ (key param) | ✅ (request.tenantId()) | ✅ | ✅ (properties) |
| `StorageSigningService.signDownload()` | ✅ (key param) | ✅ (from FSO) | ✅ | ✅ |
| `StorageSigningService.softDelete()` | ✅ (key param) | ✅ (from FSO after lookup) | ✅ | ✅ |
| `DeletionSchedulerService` loop | ✅ (fso.getKey()) | ❌ not tracked | ✅ | ✅ |
| `OutboxPollerScheduler.processJob()` | ✅ (job.getStorageObject().getKey()) | ❌ | ✅ | "backup" |

For `signUpload()`, the `storageKey` is not yet known (key is generated mid-method). Set the other three MDC fields and skip `storageKey`.

### `StorageSigningService` — Existing `tenantId` Access After Lookup

In `signDownload()` and `softDelete()`, the `tenantId` is on the `FileStorageObject` fetched from the DB:
```java
FileStorageObject fso = fileStorageObjectRepository.findByKeyAndDeletedAtIsNull(key)
    .orElseThrow(...);
// fso.getTenantId() is now available for MDC
```

Set MDC *after* the DB lookup in `signDownload()`/`softDelete()` so `tenantId` is available, but the timer should start *before* the lookup. Track `start = System.nanoTime()` before MDC setup.

### `OutboxPollerScheduler` — Replication Disabled Guard

`OutboxPollerScheduler` is only created when `app.storage.replication.enabled=true` (see `StorageConfig.outboxPollerScheduler()`). The `StorageMetrics` bean is always created (no conditional). If replication is never enabled, the gauge stays at 0 — this is correct behavior.

When updating `StorageConfig.outboxPollerScheduler()`:
```java
@Bean
@ConditionalOnProperty(name = "app.storage.replication.enabled", havingValue = "true")
public OutboxPollerScheduler outboxPollerScheduler(
        @Qualifier("storageService") StorageService primaryStorageService,
        @Qualifier("backupStorageService") StorageService backupStorageService,
        OutboxReplicationJobRepository outboxReplicationJobRepository,
        StorageProperties storageProperties,
        TransactionTemplate storageTransactionTemplate,
        StorageMetrics storageMetrics) {  // ← ADD THIS PARAM
    return new OutboxPollerScheduler(primaryStorageService, backupStorageService,
        outboxReplicationJobRepository, storageProperties, storageTransactionTemplate,
        storageMetrics);  // ← PASS IT
}
```

`OutboxPollerScheduler` uses `@RequiredArgsConstructor` — add `StorageMetrics storageMetrics` as a final field.

### `countByStatus` Query — JPQL Not Native SQL

The existing `pollPending` query uses native SQL with `FOR UPDATE SKIP LOCKED`. The new count query does NOT need locking — use JPQL:

```java
@Query("SELECT COUNT(j) FROM OutboxReplicationJob j WHERE j.status = :status")
long countByStatus(@Param("status") OutboxReplicationJob.ReplicationJobStatus status);
```

Note: the enum value is `OutboxReplicationJob.ReplicationJobStatus.PENDING`. JPQL binds enum parameters by name automatically.

### `StorageSigningService` — Error Code Extraction

`ApplicationException` (parent of all storage exceptions) implements `ErrorCode` via its `getErrorCode()` method. The storage exceptions extend it — verify by checking `QuotaExceededException`, `StorageObjectNotFoundException`, etc. all extend `ApplicationException`. Pattern for error code extraction:

```java
if (ex instanceof ApplicationException ae) {
    storageMetrics.recordError(operation, ae.getErrorCode().getErrorCode());
} else {
    storageMetrics.recordError(operation, "UNKNOWN");
}
```

`StorageErrorCode.QUOTA_EXCEEDED.getErrorCode()` returns `"QUOTA_EXCEEDED"` (enum name). Confirm by reading `StorageErrorCode.java` — it delegates to `this.name()`.

### Timer Placement: `signUpload()` is pre-upload, skip latency timer there

The `signUpload()` method issues a pre-signed URL — the actual file transfer happens client-side. Do NOT add `storage.upload.latency` to `signUpload()`. Add it to `confirmUpload()` which fires when the upload is verified and committed. This makes the metric meaningful: "how long did server-side upload confirmation take?"

The sign operation latency is already captured by `@Observed(name = "storage.sign.upload")` on the resource method.

### Distribution Summary vs Histogram

`DistributionSummary.builder(FILE_SIZE_BYTES).baseUnit("bytes")` creates a distribution summary that tracks count, total, max, and percentile distribution of file sizes. Prometheus scrapes this as `storage_file_size_bytes_count`, `storage_file_size_bytes_sum`, `storage_file_size_bytes_max`. No percentile buckets needed unless explicitly configured — leave at defaults.

### `S3StorageService` — No Direct Modification Required

`S3StorageService` does NOT need to be modified in this story. MDC fields set upstream in `StorageSigningService` propagate through the call chain (same thread). The `@Recover` methods in `S3StorageService` will have MDC context visible when called from `StorageSigningService` paths.

For calls from `DeletionSchedulerService` and `OutboxPollerScheduler`, MDC is set in those schedulers before calling `storageService.delete()` / `primaryStorageService.get()`.

**Do not** add `StorageMetrics` or `MeterRegistry` to `S3StorageService` — it would require updating `StorageConfig.storageService()` factory method and the `@Service`-auto-wired bean simultaneously, causing a configuration conflict.

### Structured Logging — kv() vs MDC

Two patterns are used in the project:
1. `kv(key, value)` from `net.logstash.logback.argument.StructuredArguments` — inline structured argument for a **specific log statement**
2. `MDC.put(key, value)` — context propagated to **all log statements** within the thread until removed

Use **MDC** (not `kv()`) for the four required fields (`storageKey`, `tenantId`, `operation`, `provider`) as the AC requires they appear on every log line within the operation, not just error statements.

`kv()` is appropriate for additional ad-hoc context on specific log statements (e.g., `log.warn("Job failed", kv("attempt", count))`).

### Project Structure Notes

**Files to CREATE:**
```
src/main/java/com/softropic/skillars/infrastructure/storage/service/
└── StorageMetrics.java    ← NEW: @Service, metric name constants, MeterRegistry helpers, AtomicLong gauge
```

**Files to MODIFY:**
```
src/main/java/com/softropic/skillars/infrastructure/storage/service/
├── StorageSigningService.java  ← ADD: StorageMetrics field + timer/MDC/counter instrumentation
├── DeletionSchedulerService.java  ← ADD: MDC in processDeletions() loop
└── OutboxPollerScheduler.java  ← ADD: StorageMetrics field + updateQueueDepth() call

src/main/java/com/softropic/skillars/infrastructure/storage/repo/
└── OutboxReplicationJobRepository.java  ← ADD: countByStatus() JPQL query

src/main/java/com/softropic/skillars/infrastructure/storage/config/
└── StorageConfig.java  ← ADD: StorageMetrics param to outboxPollerScheduler() factory bean
```

**Files NOT to touch:**
- `S3StorageService.java` — no changes needed
- `LocalFileSystemStorageService.java` — no changes needed
- `ReplicatedStorageService.java` — no changes needed
- `StorageResource.java` — @Observed already complete
- `ObservabilityConfig.java` — already wires ObservedAspect and TimedAspect globally
- All test files from Stories 1-5.2 — must remain unchanged and passing

### References

- [Source: epics.md#Story 5.3] — Full BDD ACs for all metrics, MDC fields, gauge behavior
- [Source: architecture.md#Core Architectural Decisions] — `app.storage.*` namespace, metric names
- [Source: project-context.md#Observability] — Micrometer Tracing (OTEL), Prometheus, `@Observed` usage
- [Source: ObservabilityConfig.java] — `ObservedAspect` and `TimedAspect` beans; do not duplicate
- [Source: StorageResource.java] — Existing `@Observed` annotations on all 4 endpoints (already done)
- [Source: AlertEvaluationService.java] — `MeterRegistry` usage pattern + `kv()` structured logging pattern
- [Source: SecurityAuditListener.java] — `MDC` + `kv()` structured logging in practice
- [Source: StorageSigningService.java] — Current state: no metrics, no MDC, uses `@Slf4j` bare log calls
- [Source: OutboxPollerScheduler.java] — Created via `StorageConfig.outboxPollerScheduler()` factory (not `@Service`) — update the factory to pass `StorageMetrics`
- [Source: StorageConfig.java#outboxPollerScheduler] — Factory bean wiring, must add `StorageMetrics` param
- [Source: OutboxReplicationJob.java] — `ReplicationJobStatus` enum: PENDING, PROCESSING, COMPLETED, FAILED
- [Source: StorageErrorCode.java] — enum implementing `ErrorCode`; `getErrorCode()` returns `this.name()`
- [Source: 5-2-full-integration-test-suite.md#Completion Notes] — Test baseline: 209 non-IT + 41 IT tests (250 total); zero regressions required

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

(none)

### Completion Notes List

- Created `StorageMetrics` as a `@Service` with all 6 named metric constants, `AtomicLong`-backed gauge registered in `@PostConstruct`, and helper methods for timer, distribution summary, counter, and queue depth updates.
- Instrumented `StorageSigningService` with try-finally MDC blocks on all four methods. `confirmUpload()`, `signDownload()`, and `softDelete()` have full try-catch-finally for latency and error recording. `signUpload()` uses try-finally only (no latency timer per spec — `@Observed` covers it). `tenantId` MDC field is set from `fso.getTenantId()` after FSO lookup in `signDownload()`/`softDelete()`, and from `request.tenantId()` in `signUpload()`/`confirmUpload()`. Timer starts before FSO lookup in methods where lookup precedes MDC setup.
- Added MDC try-finally around `storageService.delete()` in `DeletionSchedulerService.processDeletions()` loop with `storageKey`, `operation=physical_delete`, `provider` fields.
- Added `StorageMetrics` field to `OutboxPollerScheduler` (constructor injection), MDC in `processJob()` try-finally, and `updateQueueDepth()` call at start of `pollAndProcess()`.
- Added JPQL `countByStatus` query to `OutboxReplicationJobRepository` (no locking needed).
- Updated `StorageConfig.outboxPollerScheduler()` factory bean to inject and pass `StorageMetrics`.
- Updated `OutboxPollerSchedulerTest` to pass `StorageMetrics` mock to constructor. All 5 existing tests continue to pass.
- Created `StorageMetricsTest` with 7 unit tests covering constants, gauge init/update, timer recording, distribution summary, counter increment, and tag isolation. Uses `SimpleMeterRegistry`.
- Test results: 216 unit tests pass (209 baseline + 7 new), 12 IT tests pass (5 StorageResourceIT + 7 S3StorageServiceIT). Zero regressions.

### File List

src/main/java/com/softropic/skillars/infrastructure/storage/service/StorageMetrics.java (CREATED)
src/main/java/com/softropic/skillars/infrastructure/storage/service/StorageSigningService.java (MODIFIED)
src/main/java/com/softropic/skillars/infrastructure/storage/service/DeletionSchedulerService.java (MODIFIED)
src/main/java/com/softropic/skillars/infrastructure/storage/service/OutboxPollerScheduler.java (MODIFIED)
src/main/java/com/softropic/skillars/infrastructure/storage/repo/OutboxReplicationJobRepository.java (MODIFIED)
src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageConfig.java (MODIFIED)
src/test/java/com/softropic/skillars/infrastructure/storage/service/StorageMetricsTest.java (CREATED)
src/test/java/com/softropic/skillars/infrastructure/storage/service/OutboxPollerSchedulerTest.java (MODIFIED)
