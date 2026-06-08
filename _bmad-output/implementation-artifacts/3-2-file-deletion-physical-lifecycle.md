# Story 3.2: File Deletion & Physical Lifecycle

Status: review

## Story

As a system administrator and consuming application,
I want soft-delete to immediately hide a file from access, and a scheduler to physically remove it from S3 and backup after a configurable retention period,
so that deletions are safe, reversible within the retention window, and eventually free storage resources.

## Acceptance Criteria

1. **Soft-delete sets `deleted_at`, returns 204**: Given a confirmed file with key `documents/42/2026/05/uuid.pdf`, when `DELETE /api/storage/{key}` is called by an authenticated user, then the `file_storage_objects` record has `deleted_at` set to the current timestamp, the file is immediately inaccessible via the download endpoint (returns 404), HTTP 204 No Content is returned, and the endpoint carries `@Observed(name = "storage.delete")` and `@PreAuthorize`.

2. **V14 migration adds `physical_deleted_at` column**: Given Flyway migration `V14__storage_physical_deletion.sql` runs, then `file_storage_objects` has a new nullable column `physical_deleted_at TIMESTAMP`, and an index exists on `(deleted_at, physical_deleted_at)` to support efficient scheduler queries.

3. **Scheduler respects retention window**: Given `app.storage.deletion.retention-days` is configured (default: 30), when `DeletionSchedulerService` fires, it queries `file_storage_objects WHERE deleted_at < NOW() - retention_days AND physical_deleted_at IS NULL` using `FOR UPDATE SKIP LOCKED` (safe for multi-node deployment), and files within the retention window are NOT physically deleted.

4. **Scheduler physically deletes eligible files**: Given a file whose `deleted_at` is past the retention window, when the scheduler processes it, it calls `StorageService.delete(key)` on the primary provider OUTSIDE `@Transactional`, then within a single `@Transactional` block: inserts an `outbox_replication_jobs` entry with `status = PENDING` and `job_type = 'DELETE'`, and sets `physical_deleted_at` to the current timestamp.

5. **Scheduler skips failed deletions gracefully**: Given `StorageService.delete` fails for a record, when the scheduler processes that record, it logs a warning and skips (does not mark `physical_deleted_at`), allowing retry on the next scheduler run.

6. **Integration test covers end-to-end**: Given a live MinIO + PostgreSQL container (via `BaseStorageIT`), the test covers: soft-delete makes the file inaccessible immediately, a file within the retention window is NOT deleted by the scheduler, and after simulating a past retention date, `DeletionSchedulerService.processDeletions()` removes the object from MinIO, sets `physical_deleted_at`, and creates a `job_type='DELETE'` outbox job.

## Tasks / Subtasks

- [x] Task 1: Flyway migration `V14__storage_physical_deletion.sql` (AC: 2)
  - [x] Create `src/main/resources/db/migration/V14__storage_physical_deletion.sql`
  - [x] Add `physical_deleted_at TIMESTAMPTZ` nullable column to `main.file_storage_objects`
  - [x] Add composite index on `(deleted_at, physical_deleted_at)` for efficient scheduler queries
  - [x] SQL: `ALTER TABLE main.file_storage_objects ADD COLUMN physical_deleted_at TIMESTAMPTZ;`
  - [x] SQL: `CREATE INDEX idx_storage_objects_deletion ON main.file_storage_objects(deleted_at, physical_deleted_at) WHERE deleted_at IS NOT NULL;`

- [x] Task 2: Add `physicalDeletedAt` field to `FileStorageObject` entity (AC: 2)
  - [x] Modify `src/main/java/com/softropic/skillars/infrastructure/storage/repo/FileStorageObject.java`
  - [x] Add field: `@Column(name = "physical_deleted_at") private Instant physicalDeletedAt;`
  - [x] Lombok `@Getter` already on the class — no additional annotation needed
  - [x] Do NOT add a setter — update via JPQL `@Modifying @Query` only

- [x] Task 3: Add JPQL/native query methods to `FileStorageObjectRepository` (AC: 1, 3, 4)
  - [x] Modify `src/main/java/com/softropic/skillars/infrastructure/storage/repo/FileStorageObjectRepository.java`
  - [x] Add `softDeleteByKey` — JPQL UPDATE sets `deletedAt` where `deletedAt IS NULL` (returns int: 1=success, 0=not found/already deleted)
  - [x] Add `findEligibleForPhysicalDeletion` — native SQL with SKIP LOCKED (JPQL cannot express `FOR UPDATE SKIP LOCKED`)
  - [x] Add `markPhysicallyDeleted` — JPQL UPDATE sets `physicalDeletedAt` by entity ID
  - [x] Add required imports: `java.time.Instant`, `org.springframework.data.jpa.repository.Modifying`, `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`, `org.springframework.transaction.annotation.Transactional`, `java.util.List`

- [x] Task 4: Add `softDelete` method to `StorageSigningService` (AC: 1)
  - [x] Modify `src/main/java/com/softropic/skillars/infrastructure/storage/service/StorageSigningService.java`
  - [x] Add public method `softDelete(String key)`: calls `softDeleteByKey`, throws `StorageObjectNotFoundException` if returns 0
  - [x] No new fields needed — `fileStorageObjectRepository` already injected

- [x] Task 5: Add `DELETE /**` endpoint to `StorageResource` (AC: 1)
  - [x] Modify `src/main/java/com/softropic/skillars/infrastructure/storage/api/StorageResource.java`
  - [x] Add `@DeleteMapping("/**")` method with `@PreAuthorize` and `@Observed(name = "storage.delete")`
  - [x] Add import: `org.springframework.web.bind.annotation.DeleteMapping`
  - [x] `HandlerMapping` is already imported; `HttpServletRequest` is already imported

- [x] Task 6: Create `DeletionSchedulerService` (AC: 3, 4, 5)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/service/DeletionSchedulerService.java`
  - [x] Class: `@Slf4j @Service @RequiredArgsConstructor`
  - [x] Fields: `FileStorageObjectRepository`, `OutboxReplicationJobRepository`, `StorageService`, `StorageProperties`, `TransactionTemplate`
  - [x] Public `processDeletions()` method with `@Scheduled`, S3 delete OUTSIDE transaction, DB writes via `TransactionTemplate`

- [x] Task 7: Write `StorageDeletionIT` integration test (AC: 6)
  - [x] Create `src/test/java/com/softropic/skillars/infrastructure/storage/service/StorageDeletionIT.java`
  - [x] Extends `BaseStorageIT`
  - [x] Inject all required beans including `StorageService`, `EntityManager`, `TransactionTemplate`, `StorageProperties`
  - [x] `@BeforeEach` cleanup in FK order
  - [x] Copy `signAndPut` and `confirmUpload` helpers from `StorageDownloadIT`
  - [x] Test 1 `softDelete_makesFileImmediatelyInaccessible` — passes
  - [x] Test 2 `scheduler_doesNotDeleteWithinRetentionWindow` — passes
  - [x] Test 3 `scheduler_physicallyDeletesPastRetentionWindow` — passes
  - [x] Test 4 `softDelete_nonExistentKey_throwsNotFoundException` — passes

- [x] Task 8: Verify build and regression (AC: all)
  - [x] Run `mvn test -Dtest=StorageDeletionIT` — 4 tests pass
  - [x] Run full regression — 189 tests pass, 0 failures

## Dev Notes

### Critical: DELETE Endpoint Wildcard Pattern

Keys contain forward slashes (`documents/42/2026/05/uuid.pdf`). `@DeleteMapping("/{{key}}")` with `@PathVariable` does NOT work — Spring captures only up to the first slash.

**Correct approach** (identical to `confirmUpload` and `signDownload`):
```java
@DeleteMapping("/**")
public ResponseEntity<Void> deleteFile(HttpServletRequest request) {
    String fullPath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
    String key = fullPath.substring("/api/storage/".length());  // strips the base + leading slash
    ...
}
```

`HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE` is already imported in `StorageResource.java`.

### Critical: FileStorageObject Has No Setter — Use JPQL UPDATE

`FileStorageObject` uses `@Getter` (Lombok) but NO `@Setter`. Setting `deletedAt` or `physicalDeletedAt` cannot be done via a setter on a fetched entity. Use `@Modifying @Transactional @Query` repository methods exclusively:

- `softDeleteByKey(key, deletedAt)` — sets `deletedAt`, returns 0 if already deleted or not found
- `markPhysicallyDeleted(id, ts)` — sets `physicalDeletedAt` by entity ID

If `softDeleteByKey` returns 0, the key was not found or was already soft-deleted → throw `StorageObjectNotFoundException(key)`.

### Critical: SKIP LOCKED Requires Native Query, Not JPQL

`FOR UPDATE SKIP LOCKED` is not supported in JPQL. The `findEligibleForPhysicalDeletion` method MUST use `nativeQuery = true`. The table schema uses `main.` prefix (see V12 migration) — use `main.file_storage_objects` in the native query.

The `@Transactional` annotation on the repository method creates a short-lived transaction that acquires row locks (SKIP LOCKED) and commits when the method returns. The returned list of `FileStorageObject` entities is detached but fully populated (no lazy fields at this level). Processing continues outside this transaction.

### Critical: Transaction Boundary in DeletionSchedulerService

Architecture rule: "S3 client calls must be OUTSIDE @Transactional".

Pattern for `processDeletions()`:
1. Fetch eligible records via `findEligibleForPhysicalDeletion` — its own short transaction with SKIP LOCKED
2. For each FSO: `storageService.delete(fso.getKey())` — NO active transaction (method is not @Transactional)
3. Then `transactionTemplate.execute(...)` — new short transaction for DB writes only

`processDeletions()` must NOT be annotated `@Transactional`. `TransactionTemplate` is already wired in `StorageSigningService` (same bean) — same pattern applies here.

### Critical: processDeletions() Must Be Public (Test Access)

The integration test calls `deletionSchedulerService.processDeletions()` directly to simulate the scheduler without waiting for `@Scheduled` to fire. The method must be `public`.

### FileStorageObject Entity: Adding physicalDeletedAt

Add to `FileStorageObject.java`:
```java
@Column(name = "physical_deleted_at")
private Instant physicalDeletedAt;
```

The existing `deletedAt` field pattern:
```java
@Column(name = "deleted_at")
private Instant deletedAt;
```

Follow the same pattern. No `@PrePersist` or default — this starts `null` and is set explicitly by the scheduler.

### DeletionSchedulerService: @Scheduled Interval

Use `fixedDelayString = "${app.storage.poller.fixed-delay-ms:5000}"` — reuses the existing poller interval from `StorageProperties.Poller.fixedDelayMs`. There is no separate deletion-specific scheduler interval in `StorageProperties`; both the outbox poller (Epic 4) and deletion scheduler share this interval.

`@EnableScheduling` is already active in the application (the `notification` module uses `@Scheduled`).

### Cross-Epic Dependency: DELETE Outbox Jobs

The `outbox_replication_jobs` entries created in this story with `job_type='DELETE'` will be consumed by `OutboxPollerScheduler` in **Epic 4 / Story 4.2**. Backup deletion does NOT execute until Story 4.2 is implemented. This is by design.

**Do NOT implement backup deletion logic here.** Only create the outbox entry and mark `physical_deleted_at`. The `OutboxReplicationJob.ReplicationJobType.DELETE` enum value already exists in the codebase.

### Verifying MinIO Deletion in Tests

After `processDeletions()`, verify the file is gone from MinIO using:
```java
boolean stillExists = storageService.exists(confirmedKey);
assertThat(stillExists).isFalse();
```

`StorageService` is available as `@Autowired StorageService storageService` in the test (the `S3StorageService` bean is the active impl).

### Manipulating deleted_at in Tests (Past Retention Window)

To simulate a file past the retention window, use JPQL via `EntityManager`:
```java
@Autowired
private jakarta.persistence.EntityManager em;

@Autowired
private TransactionTemplate transactionTemplate;

void backdateDeletedAt(String key, int daysAgo) {
    transactionTemplate.execute(status -> {
        em.createQuery("UPDATE FileStorageObject f SET f.deletedAt = :past WHERE f.key = :key")
            .setParameter("past", Instant.now().minus(daysAgo, ChronoUnit.DAYS))
            .setParameter("key", key)
            .executeUpdate();
        return null;
    });
}
```

Use `daysAgo = properties.getDeletion().getRetentionDays() + 1` (or just use 31 for default 30-day retention). In tests, inject `StorageProperties properties` to read the configured value.

### @BeforeEach Cleanup FK Order

`OutboxReplicationJob` has a FK to `FileStorageObject`. Always delete in this order:
```java
storageAccessEventRepository.deleteAll();
outboxReplicationJobRepository.deleteAll();
fileStorageObjectRepository.deleteAll();
```
`StorageAccessEvent` has NO FK constraints — can be deleted first or last.

### Imports for DeletionSchedulerService

```java
import com.softropic.skillars.infrastructure.storage.config.StorageProperties;
import com.softropic.skillars.infrastructure.storage.repo.FileStorageObject;
import com.softropic.skillars.infrastructure.storage.repo.FileStorageObjectRepository;
import com.softropic.skillars.infrastructure.storage.repo.OutboxReplicationJob;
import com.softropic.skillars.infrastructure.storage.repo.OutboxReplicationJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
```

### What Is NOT in This Story

- `OutboxPollerScheduler` (Epic 4 / Story 4.2) — backup deletion via the DELETE outbox job
- `ReplicatedStorageService` (Epic 4 / Story 4.1)
- `LocalFileSystemStorageService` (Epic 5 / Story 5.1)
- Observability metrics instrumentation (Epic 5 / Story 5.3)
- Any changes to the quota query (soft-deleted records continue counting until physically deleted)

### Project Structure Notes

New and modified files:

```
src/main/resources/db/migration/
└── V14__storage_physical_deletion.sql           ← NEW: adds physical_deleted_at + index

src/main/java/com/softropic/skillars/infrastructure/storage/
├── api/
│   └── StorageResource.java                     ← MODIFY: add DELETE /** endpoint
├── repo/
│   ├── FileStorageObject.java                   ← MODIFY: add physicalDeletedAt field
│   └── FileStorageObjectRepository.java         ← MODIFY: add softDeleteByKey, findEligibleForPhysicalDeletion, markPhysicallyDeleted
└── service/
    ├── DeletionSchedulerService.java            ← NEW: @Scheduled processor
    └── StorageSigningService.java               ← MODIFY: add softDelete(String key)

src/test/java/com/softropic/skillars/infrastructure/storage/service/
└── StorageDeletionIT.java                       ← NEW (extends BaseStorageIT)
```

**Do NOT modify:**
- `OutboxReplicationJobRepository.java` — no query changes needed in this story
- `StorageAccessEvent.java` — no changes
- `BaseStorageIT.java` — no changes
- `application.yaml` — `app.storage.deletion.retention-days: 30` already configured

### References

- [Source: epics.md#Story 3.2] — Full BDD acceptance criteria, deletion scheduler spec, cross-epic note on DELETE job_type
- [Source: architecture.md#REST Endpoint Naming] — `DELETE /api/storage/{key} → 204 (no body)`
- [Source: architecture.md#Format Patterns] — `DELETE /api/storage/{key} → 204 (no body)`
- [Source: architecture.md#Gap Analysis Results] — `DeletionSchedulerService` with SKIP LOCKED + `physical_deleted_at` column + `app.storage.deletion.retention-days`
- [Source: architecture.md#Process Patterns] — S3 calls OUTSIDE @Transactional; DB writes inside TransactionTemplate
- [Source: architecture.md#Outbox Poller — SKIP LOCKED] — SKIP LOCKED pattern for multi-node safety
- [Source: architecture.md#Enforcement Guidelines] — Never wrap S3 calls inside @Transactional
- [Source: project-context.md#Framework-Specific Rules] — @PreAuthorize on every endpoint; @Observed on resources; @PatchMapping for partial updates; return 204 No Content for body-less success
- [Source: project-context.md#Testing Rules] — @SpringBootTest + @Testcontainers; no mock DB; AssertJ; Awaitility for async
- [Source: StorageResource.java] — existing wildcard pattern; HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE
- [Source: StorageSigningService.java] — existing service methods; @RequiredArgsConstructor pattern; TransactionTemplate already injected
- [Source: FileStorageObject.java] — @SuperBuilder, @Getter, @NoArgsConstructor, existing deletedAt field pattern
- [Source: FileStorageObjectRepository.java] — existing findByKey, findByKeyAndDeletedAtIsNull, sumSizeBytesByTenantId
- [Source: OutboxReplicationJob.java:26-33] — ReplicationJobStatus enum, ReplicationJobType enum (REPLICATE, DELETE already defined)
- [Source: StorageObjectNotFoundException.java] — single-arg (String key) constructor for not-found case
- [Source: StorageDownloadIT.java] — signAndPut helper, confirmUpload helper, @BeforeEach cleanup order, softDelete JPQL pattern
- [Source: V12__storage_schema.sql] — table uses `main.` schema prefix in all DDL
- [Source: V13__storage_access_events.sql] — index naming convention: `idx_storage_access_events_tenant`
- [Source: application.yaml#app.storage.deletion] — `retention-days: 30` already configured
- [Source: BaseEntity.java] — `id` is `Long` (via @Tsid); `getId()` returns `Long`
- [Source: 3-1-pre-signed-download-url-endpoint-egress-tracking.md#Dev Notes] — wildcard endpoint, @Transactional boundary, FK cleanup order, test baseline (38 tests)

### Previous Story Intelligence (Story 3.1)

- **Wildcard mapping** (`/**`) is the established pattern for slash-containing keys. Prefix extraction: `fullPath.substring("/api/storage/".length())` for the DELETE endpoint.
- **No `@Transactional` on service methods**: `softDelete()` must NOT be annotated `@Transactional`. The `softDeleteByKey` repository method manages its own transaction.
- **TransactionTemplate is already injected** in `StorageSigningService` via `@RequiredArgsConstructor`. Adding `softDelete()` to this service requires no additional constructor changes.
- **JPQL UPDATE for soft-delete** (used in `StorageDownloadIT.softDelete()` test helper) is the established pattern — confirmed working. Use the same for the `softDeleteByKey` repository method.
- **FK cleanup order** confirmed: `storageAccessEventRepository.deleteAll()` → `outboxReplicationJobRepository.deleteAll()` → `fileStorageObjectRepository.deleteAll()`.
- **Test baseline**: 38 tests pass as of Story 3.1. Regression suite must continue passing after this story.
- **`signAndPut` and `confirmUpload` helpers** in `StorageDownloadIT` — copy both into `StorageDeletionIT`.
- **`StorageObjectNotFoundException(key)` single-arg constructor** is the correct call for not-found cases.

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

- All 8 tasks implemented following red-green-refactor. V14 migration adds `physical_deleted_at` column with composite partial index. `FileStorageObject` entity updated with new field (no setter per story spec). Repository gains three new query methods: `softDeleteByKey` (JPQL), `findEligibleForPhysicalDeletion` (native with FOR UPDATE SKIP LOCKED), `markPhysicallyDeleted` (JPQL). `StorageSigningService.softDelete()` delegates to repository and throws 404 on 0 rows updated. `StorageResource` DELETE `/**` endpoint added with `@PreAuthorize` and `@Observed(name = "storage.delete")`. `DeletionSchedulerService` implements the physical deletion loop: S3 delete OUTSIDE transaction, then `TransactionTemplate` for outbox job insert + `markPhysicallyDeleted`. `StorageDeletionIT` covers all 4 scenarios end-to-end. Full regression: 189 tests, 0 failures.

### File List

- src/main/resources/db/migration/V14__storage_physical_deletion.sql (NEW)
- src/main/java/com/softropic/skillars/infrastructure/storage/repo/FileStorageObject.java (MODIFIED)
- src/main/java/com/softropic/skillars/infrastructure/storage/repo/FileStorageObjectRepository.java (MODIFIED)
- src/main/java/com/softropic/skillars/infrastructure/storage/service/StorageSigningService.java (MODIFIED)
- src/main/java/com/softropic/skillars/infrastructure/storage/api/StorageResource.java (MODIFIED)
- src/main/java/com/softropic/skillars/infrastructure/storage/service/DeletionSchedulerService.java (NEW)
- src/test/java/com/softropic/skillars/infrastructure/storage/service/StorageDeletionIT.java (NEW)

## Change Log

- Story 3.2 implementation complete (Date: 2026-05-26): V14 migration adds physical_deleted_at; DELETE endpoint added; DeletionSchedulerService with SKIP LOCKED pattern; 4 integration tests covering all ACs; full regression 189 tests green.
