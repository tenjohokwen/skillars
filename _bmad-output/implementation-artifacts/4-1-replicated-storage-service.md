# Story 4.1: Replicated Storage Service

Status: review

## Story

As a system operator,
I want a replication-aware storage service that automatically queues backup jobs when `replication.enabled=true`,
so that every uploaded file is durably mirrored to a secondary provider without changing any upload-path code.

## Acceptance Criteria

1. **`replication.enabled=true` wires `ReplicatedStorageService`**: Given `app.storage.replication.enabled=true` is configured, when `StorageConfig` creates the `StorageService` bean, then `ReplicatedStorageService` is wired as the active `@Primary StorageService` bean, wrapping `S3StorageService` as the primary delegate; and a secondary `S3StorageService` (pointing to the backup endpoint configured under `app.storage.replication.backup.*`) is wired as the backup target.

2. **`replication.enabled=false` uses `S3StorageService` directly**: Given `app.storage.replication.enabled=false` (default), when `StorageConfig` creates the `StorageService` bean, then `S3StorageService` is wired directly as the active bean — no `ReplicatedStorageService` is created.

3. **`put()` success creates REPLICATE outbox entry**: Given `ReplicatedStorageService.put(key, data, contentLength, contentType)` is called and a `FileStorageObject` record exists for that key, when the primary `S3StorageService.put` succeeds, then an `outbox_replication_jobs` record with `status=PENDING`, `job_type='REPLICATE'`, and `attemptCount=0` is inserted referencing that `FileStorageObject`.

4. **`put()` failure creates no outbox entry**: Given `ReplicatedStorageService.put()` is called, when the primary put throws an exception, then no outbox record is created and the exception propagates unmodified.

5. **All other `StorageService` operations delegate to primary**: Given any call to `get`, `delete`, `exists`, `stat`, or `copy` on `ReplicatedStorageService`, when the call executes, then it is delegated unchanged to the primary `S3StorageService` — no outbox writes, no backup calls.

6. **Unit test**: Given `ReplicatedStorageServiceTest` (Mockito, no containers), when it runs, then it verifies: primary success with FSO present → outbox REPLICATE entry saved; primary failure → no outbox save; FSO absent after primary success → outbox skipped (log warning, no exception); all non-put operations → delegated to primary mock only.

## Tasks / Subtasks

- [x] Task 1: Extend `StorageProperties.Replication` with `Backup` inner class (AC: 1)
  - [x] Add `Backup` nested static class to `StorageProperties.Replication` with fields: `endpointUrl`, `bucket`, `region` (default `us-east-1`), `accessKey`, `secretKey`, `pathStyleAccess` (default false) — all `@Getter @Setter`
  - [x] Add `private Backup backup = new Backup();` field to `Replication` class
  - [x] File: `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageProperties.java`

- [x] Task 2: Add backup config to `application.yaml` (AC: 1)
  - [x] Under `app.storage.replication:`, add `backup:` section with env-var-sourced defaults:
    ```yaml
    backup:
      endpoint-url: ${APP_STORAGE_BACKUP_ENDPOINT_URL:}
      bucket: ${APP_STORAGE_BACKUP_BUCKET:}
      region: ${APP_STORAGE_BACKUP_REGION:us-east-1}
      access-key: ${APP_STORAGE_BACKUP_ACCESS_KEY:}
      secret-key: ${APP_STORAGE_BACKUP_SECRET_KEY:}
      path-style-access: false
    ```
  - [x] File: `src/main/resources/application.yaml`

- [x] Task 3: Create `ReplicatedStorageService` (AC: 3, 4, 5)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/service/ReplicatedStorageService.java`
  - [x] Class: `@Slf4j @RequiredArgsConstructor` — no `@Service` (bean created in `StorageConfig`)
  - [x] Fields: `StorageService primaryStorageService`, `FileStorageObjectRepository fileStorageObjectRepository`, `OutboxReplicationJobRepository outboxReplicationJobRepository`, `TransactionTemplate transactionTemplate`
  - [x] `put()`: call `primaryStorageService.put(...)`, then `transactionTemplate.execute(...)` → look up FSO by key, if present save REPLICATE outbox entry, if absent log `warn("No FSO found for key {}; skipping outbox insert", key)`
  - [x] `get/delete/exists/stat/copy`: single-line delegation to `primaryStorageService`
  - [x] Method `put()` must NOT be `@Transactional` — all S3 calls must be outside transaction boundary (critical architecture rule)

- [x] Task 4: Wire `ReplicatedStorageService` in `StorageConfig` (AC: 1, 2)
  - [x] Add backup `S3Client` bean (`backupS3Client`) with `@Bean("backupS3Client") @ConditionalOnProperty(name = "app.storage.replication.enabled", havingValue = "true")` — built from `properties.getReplication().getBackup().*` the same way `s3Client` is built from primary config
  - [x] Add backup `S3TransferManager` bean with `@Bean("backupS3TransferManager") @ConditionalOnProperty(name = "app.storage.replication.enabled", havingValue = "true")`
  - [x] Add backup `StorageService` bean: `@Bean("backupStorageService") @ConditionalOnProperty(name = "app.storage.replication.enabled", havingValue = "true")` creating a new `S3StorageService` from backup beans
  - [x] Add `ReplicatedStorageService` bean: `@Bean @Primary @ConditionalOnProperty(name = "app.storage.replication.enabled", havingValue = "true")` — inject primary `StorageService storageService` by name (the existing non-primary `storageService` @Bean), plus `FileStorageObjectRepository`, `OutboxReplicationJobRepository`, `TransactionTemplate storageTransactionTemplate`
  - [x] File: `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageConfig.java`

- [x] Task 5: Write `ReplicatedStorageServiceTest` unit test (AC: 6)
  - [x] Create `src/test/java/com/softropic/skillars/infrastructure/storage/service/ReplicatedStorageServiceTest.java`
  - [x] `@ExtendWith(MockitoExtension.class)`, mocks: `StorageService primaryStorageService`, `FileStorageObjectRepository fileStorageObjectRepository`, `OutboxReplicationJobRepository outboxReplicationJobRepository`, `TransactionTemplate transactionTemplate`
  - [x] Construct `new ReplicatedStorageService(primary, fsoRepo, outboxRepo, txTemplate)` in `@BeforeEach`
  - [x] Test `put_primarySuccess_fsoPresent_savesOutboxEntry`: mock `transactionTemplate.execute(any())` to invoke the callback and return a mock FSO from `findByKey()`, verify `outboxRepo.save()` called with REPLICATE/PENDING job
  - [x] Test `put_primaryFailure_noOutboxEntry`: primary.put() throws `StorageProviderException`, verify `outboxRepo` never called
  - [x] Test `put_primarySuccess_fsoAbsent_skipsOutbox`: `findByKey()` returns empty, verify `outboxRepo` never called, no exception
  - [x] Tests `get/delete/exists/stat/copy`: each delegates to `primaryStorageService`; verify via `verify(primaryStorageService).<method>(...)`

- [x] Task 6: Verify build and regression (AC: all)
  - [x] Run `mvn test -Dtest=ReplicatedStorageServiceTest` — all tests pass
  - [x] Run full test suite — all existing tests still pass (replication.enabled=false in test config; no behavior change)

## Dev Notes

### Critical: `ReplicatedStorageService.put()` — FK Constraint Requires FSO Lookup

`OutboxReplicationJob.storageObject` is `nullable = false` (see `OutboxReplicationJob.java:34-36`). The `ReplicatedStorageService.put()` path is exclusively for **programmatic/migration** uploads where a `FileStorageObject` record already exists. Before creating the outbox entry, look up the FSO by key via `fileStorageObjectRepository.findByKey(key)`. If the FSO is not found (should not happen in the migration path), log a warning and skip — do NOT throw.

**For the client-direct-to-S3 path** (sign → PUT to S3 → confirm): The backend never calls `StorageService.put()` in this flow. Replication for this path is triggered exclusively by the outbox entry already inserted in `StorageSigningService.confirmUpload()` (Story 2.3). `ReplicatedStorageService.put()` will **never** be called for this flow, so there is no risk of duplicate outbox entries.

### Critical: S3 Calls Must Be OUTSIDE @Transactional

Architecture rule: S3 calls inside a `@Transactional` boundary hold a DB connection open during network I/O — forbidden. Pattern for `put()`:

```java
// 1. S3 call OUTSIDE any transaction (method is NOT @Transactional)
primaryStorageService.put(key, data, contentLength, contentType);

// 2. DB write INSIDE TransactionTemplate
transactionTemplate.execute(status -> {
    fileStorageObjectRepository.findByKey(key).ifPresent(fso -> {
        OutboxReplicationJob job = OutboxReplicationJob.builder()
            .storageObject(fso)
            .jobType(OutboxReplicationJob.ReplicationJobType.REPLICATE)
            .status(OutboxReplicationJob.ReplicationJobStatus.PENDING)
            .attemptCount(0)
            .build();
        outboxReplicationJobRepository.save(job);
    });
    return null;
});
```

### Critical: `@Primary` Wiring and Disambiguation

The `ReplicatedStorageService` bean must be `@Primary` when `replication.enabled=true`. This overrides the existing `storageService` `@Bean` (S3) for any `@Autowired StorageService` injection (e.g., `DeletionSchedulerService`, `StorageSigningService`).

When `replication.enabled=false` (default / test environment), the `@Primary` bean does not exist, and Spring resolves `StorageService` injections by field name matching `storageService` → the existing `@Bean storageService` (the S3 one). **No change to existing behavior.**

`DeletionSchedulerService.processDeletions()` calls `storageService.delete(key)` to delete from primary. When `replication.enabled=true`, this goes through `ReplicatedStorageService.delete()` → delegates to primary S3. The `job_type='DELETE'` outbox entry it creates for backup deletion is processed by the poller in Story 4.2. `ReplicatedStorageService.delete()` does NOT create a second outbox entry.

### Critical: `ReplicatedStorageService` Must NOT Be `@Service`

Do NOT annotate `ReplicatedStorageService` with `@Service`. Component scanning would create a bean unconditionally, conflicting with the conditional `@Bean` in `StorageConfig`. The bean is created exclusively by `StorageConfig` under the `@ConditionalOnProperty(replication.enabled=true)` condition.

### Backup S3Client Construction — Reuse `credentialsProvider()` Pattern

The private `credentialsProvider(StorageProperties properties)` method in `StorageConfig` reads from `properties.getS3().getAccessKey/SecretKey()`. For the backup client, create a similar helper `backupCredentialsProvider(StorageProperties properties)` that reads from `properties.getReplication().getBackup().getAccessKey/SecretKey()`. Follow the exact same blank-check pattern as the primary method.

```java
private AwsCredentialsProvider backupCredentialsProvider(StorageProperties props) {
    StorageProperties.Replication.Backup b = props.getReplication().getBackup();
    if (b.getAccessKey() != null && !b.getAccessKey().isBlank()
        && b.getSecretKey() != null && !b.getSecretKey().isBlank()) {
        return StaticCredentialsProvider.create(
            AwsBasicCredentials.create(b.getAccessKey(), b.getSecretKey()));
    }
    return DefaultCredentialsProvider.create();
}
```

The backup `S3Client` and `S3TransferManager` beans need `@Bean("backupS3AsyncClient")` (for the async client used by `S3TransferManager`), analogous to the primary `s3AsyncClient` bean.

### Unit Test — TransactionTemplate Callback Execution

`TransactionTemplate.execute(callback)` is a real method, not void. In Mockito, mock it to capture and invoke the callback:

```java
when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
    TransactionCallback<?> cb = invocation.getArgument(0);
    cb.doInTransaction(mock(TransactionStatus.class));
    return null;
});
```

This makes `findByKey()` calls inside the callback reachable during unit tests.

### `FileStorageObjectRepository.findByKey()` — Already Exists

`FileStorageObjectRepository.findByKey(String key)` returns `Optional<FileStorageObject>` — already implemented (used in `StorageSigningService`). Do NOT add a new repository method; reuse the existing one.

### Outbox Entity Builder Pattern

Follow the same builder pattern used in `StorageSigningService.confirmUpload()` (line 134-139):

```java
OutboxReplicationJob.builder()
    .storageObject(persistedFso)
    .jobType(OutboxReplicationJob.ReplicationJobType.REPLICATE)
    .status(OutboxReplicationJob.ReplicationJobStatus.PENDING)
    .attemptCount(0)
    .build();
```

`@PrePersist` on `OutboxReplicationJob` sets `createdAt` and defaults `status` if null (but always set it explicitly for clarity).

### No Integration Test Required in This Story

The `ReplicatedStorageService` integration with MinIO is deferred to Story 4.2 (`OutboxReplicationJobRepositoryIT` covers the SKIP LOCKED behavior). This story requires only a pure unit test (`ReplicatedStorageServiceTest`). The full regression suite (no MinIO backup needed — `replication.enabled=false` in test config) is sufficient to verify backward compatibility.

### Test Baseline After Story 3.2

Story 3.2 completed with **189 tests passing**. This story adds unit tests only (no containers). Regression suite must remain ≥189 passing.

### Project Structure Notes

New and modified files:
```
src/main/java/com/softropic/skillars/infrastructure/storage/
├── config/
│   ├── StorageProperties.java                  ← MODIFY: add Backup inner class to Replication
│   └── StorageConfig.java                      ← MODIFY: backup beans + ReplicatedStorageService bean
└── service/
    └── ReplicatedStorageService.java           ← NEW: @Slf4j @RequiredArgsConstructor (no @Service)

src/main/resources/
└── application.yaml                            ← MODIFY: add replication.backup.* keys

src/test/java/com/softropic/skillars/infrastructure/storage/service/
└── ReplicatedStorageServiceTest.java           ← NEW: @ExtendWith(MockitoExtension.class)
```

**Do NOT modify:**
- `S3StorageService.java` — no changes needed; used as primary delegate
- `StorageSigningService.java` — no changes; its `confirmUpload()` outbox insert is the replication trigger for the client flow
- `DeletionSchedulerService.java` — no changes; DELETE outbox jobs already handled
- `FileStorageObjectRepository.java` — `findByKey()` already exists
- `OutboxReplicationJobRepository.java` — no new queries needed for this story
- `BaseStorageIT.java` — no changes
- `application-dev.yaml` / `application-prod.yaml` — backup config sourced from env vars

### References

- [Source: epics.md#Story 4.1] — Full BDD acceptance criteria, two-path note (programmatic vs client-direct), unit test requirements
- [Source: architecture.md#Java Class Names] — `ReplicatedStorageService` in `service/`
- [Source: architecture.md#Process Patterns — Transaction Boundary] — S3 outside @Transactional, DB writes inside TransactionTemplate
- [Source: architecture.md#Outbox Poller — SKIP LOCKED] — SKIP LOCKED handled in Story 4.2; this story only inserts PENDING entries
- [Source: architecture.md#Enforcement Guidelines] — Never wrap S3 calls inside @Transactional; always use StorageErrorCode
- [Source: architecture.md#YAML Configuration Namespace] — `app.storage.replication.*` namespace
- [Source: StorageConfig.java] — existing bean wiring, `credentialsProvider()` helper, ExecutorService bean
- [Source: StorageProperties.java] — existing `Replication` inner class; `Backup` must be nested inside it
- [Source: StorageSigningService.java:132-143] — outbox entry builder pattern to replicate
- [Source: OutboxReplicationJob.java:26-44] — `ReplicationJobStatus.PENDING`, `ReplicationJobType.REPLICATE`, `nullable=false` FK
- [Source: OutboxReplicationJobRepository.java] — bare `JpaRepository`; `save()` is inherited
- [Source: FileStorageObjectRepository.java] — `findByKey(String key)` returns `Optional<FileStorageObject>`; reuse existing method
- [Source: S3StorageServiceTest.java] — unit test pattern with `@ExtendWith(MockitoExtension.class)` and `new S3StorageService(...)` construction
- [Source: project-context.md#Testing Rules] — Mockito for unit tests; no mock DB for integration tests
- [Source: project-context.md#Language-Specific Rules] — `@Slf4j`, `@RequiredArgsConstructor`, Lombok on services
- [Source: 3-2-file-deletion-physical-lifecycle.md#Dev Notes — Transaction Boundary] — `TransactionTemplate` pattern; `processDeletions()` not @Transactional

### Previous Story Intelligence (Story 3.2)

- **`TransactionTemplate` is injected via `@RequiredArgsConstructor`** in `StorageSigningService` — same bean `storageTransactionTemplate` from `StorageConfig.storageTransactionTemplate()`. Use the same bean name qualifier if needed.
- **No `@Transactional` on orchestration methods** — confirmed pattern: use `TransactionTemplate` for short DB write transactions; outer method stays non-transactional.
- **FK cleanup order** (for tests): `storageAccessEventRepository.deleteAll()` → `outboxReplicationJobRepository.deleteAll()` → `fileStorageObjectRepository.deleteAll()`.
- **`@Service` on existing services** — `DeletionSchedulerService`, `StorageSigningService`, etc. have `@Service`. `ReplicatedStorageService` does NOT — created in `StorageConfig` under condition.
- **`@Observed` and `@PreAuthorize`** — NOT needed on `ReplicatedStorageService` (it's a service, not a REST resource). Only `StorageResource` methods need those annotations.

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

(none)

### Completion Notes List

- Implemented `StorageProperties.Replication.Backup` inner class with all required fields and env-var-backed YAML defaults.
- Created `ReplicatedStorageService` with `@Slf4j @RequiredArgsConstructor`, no `@Service`. `put()` is deliberately NOT `@Transactional`: S3 call runs outside any transaction boundary, then a `TransactionTemplate` wraps the short FSO-lookup + outbox-save DB write.
- Used `ifPresentOrElse` for the FSO lookup: present → save PENDING REPLICATE outbox job; absent → log WARN, skip.
- `StorageConfig` gains `backupCredentialsProvider()` helper, four new conditional beans (`backupS3Client`, `backupS3AsyncClient`, `backupS3TransferManager`, `backupStorageService`), and the `@Primary @ConditionalOnProperty` `replicatedStorageService` bean. When `replication.enabled=false` (test default), none of these beans are created — zero behavior change.
- 8 unit tests added in `ReplicatedStorageServiceTest`; all pass. Full suite: 197 tests, 0 failures (baseline was 189).

### File List

- src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageProperties.java
- src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageConfig.java
- src/main/java/com/softropic/skillars/infrastructure/storage/service/ReplicatedStorageService.java
- src/main/resources/application.yaml
- src/test/java/com/softropic/skillars/infrastructure/storage/service/ReplicatedStorageServiceTest.java

## Change Log

- 2026-05-26: Story 4.1 implemented — added Backup config class, application.yaml backup keys, ReplicatedStorageService, conditional wiring in StorageConfig, and ReplicatedStorageServiceTest (8 tests). Full suite: 197 passing.
