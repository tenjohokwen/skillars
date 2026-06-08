# Story 4.2: Async Replication Outbox Poller

Status: review

## Story

As a system operator,
I want an outbox poller that processes pending replication jobs reliably across multiple application nodes,
so that backup replication completes asynchronously without distributed locking and permanently failed jobs are quarantined for manual review.

## Acceptance Criteria

1. **Poller polls PENDING jobs with SKIP LOCKED**: Given one or more `outbox_replication_jobs` records with `status = 'PENDING'`, when `OutboxPollerScheduler` fires on its `app.storage.poller.fixed-delay-ms` schedule, then it fetches up to `app.storage.poller.batch-size` PENDING jobs using `SELECT ... FOR UPDATE SKIP LOCKED ORDER BY created_at ASC`.

2. **REPLICATE dispatch streams from primary to backup**: For a `job_type = 'REPLICATE'` job, the poller calls `primaryStorageService.get(key)` then streams the result to `backupStorageService.put(key, data, contentLength, contentType)`.

3. **DELETE dispatch removes from backup**: For a `job_type = 'DELETE'` job, the poller calls `backupStorageService.delete(key)`.

4. **Success transitions to COMPLETED**: On successful dispatch, the job status is updated to `COMPLETED`.

5. **Failure increments attempt_count and retries or dead-letters**: On failure, `attempt_count` is incremented and `last_attempted_at` is updated. If `attempt_count < app.storage.replication.max-attempts`, status resets to `PENDING` for retry. If `attempt_count >= max-attempts`, status transitions to `FAILED` and `error_message` is populated — never automatically retried.

6. **SKIP LOCKED ensures disjoint processing across nodes**: Given two application nodes polling simultaneously, `FOR UPDATE SKIP LOCKED` ensures each PENDING job is claimed by exactly one node — no duplicate replication.

7. **Replication disabled exits immediately**: Given `app.storage.replication.enabled=false`, the `OutboxPollerScheduler` bean is never created (conditional bean) and no jobs are ever processed.

8. **`OutboxReplicationJobRepositoryIT` integration test**: Against a real PostgreSQL container, verifies: `pollPending` with SKIP LOCKED returns disjoint job sets from two concurrent transactions; FAILED jobs are not returned by `pollPending`; COMPLETED jobs are not returned by `pollPending`.

## Tasks / Subtasks

- [x] Task 1: Add query methods to `OutboxReplicationJobRepository` (AC: 1, 5, 6, 8)
  - [x] Add `pollPending(@Param("limit") int limit)` — native SQL with `FOR UPDATE SKIP LOCKED`: `@Transactional @Query(value = "SELECT * FROM main.outbox_replication_jobs WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)`
  - [x] Add `markAsProcessing(Long id, Instant ts)` — `@Modifying(clearAutomatically = true) @Transactional @Query("UPDATE OutboxReplicationJob j SET j.status = com.softropic.skillars.infrastructure.storage.repo.OutboxReplicationJob.ReplicationJobStatus.PROCESSING, j.lastAttemptedAt = :ts WHERE j.id = :id")`
  - [x] Add `markAsCompleted(Long id)` — `@Modifying @Transactional @Query`: sets `j.status = ...COMPLETED`
  - [x] Add `markAsPendingForRetry(Long id, int attempts, Instant ts, String error)` — `@Modifying @Transactional @Query`: sets `status = PENDING, attemptCount = :attempts, lastAttemptedAt = :ts, errorMessage = :error`
  - [x] Add `markAsFailed(Long id, int attempts, Instant ts, String error)` — `@Modifying @Transactional @Query`: sets `status = FAILED, attemptCount = :attempts, lastAttemptedAt = :ts, errorMessage = :error`
  - [x] File: `src/main/java/com/softropic/skillars/infrastructure/storage/repo/OutboxReplicationJobRepository.java`

- [x] Task 2: Create `OutboxPollerScheduler` (AC: 1–7)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/service/OutboxPollerScheduler.java`
  - [x] Class: `@Slf4j @RequiredArgsConstructor` — NO `@Service` (bean created conditionally in `StorageConfig`)
  - [x] Fields: `StorageService primaryStorageService`, `StorageService backupStorageService`, `OutboxReplicationJobRepository outboxReplicationJobRepository`, `StorageProperties properties`, `TransactionTemplate transactionTemplate`
  - [x] `pollAndProcess()` annotated `@Scheduled(fixedDelayString = "${app.storage.poller.fixed-delay-ms:5000}")`
  - [x] Atomic claim: call `transactionTemplate.execute()` containing BOTH `pollPending(batchSize)` AND `markAsProcessing(j.getId(), now)` for each job; also call `j.getStorageObject().getKey()` INSIDE this TX to initialize the lazy proxy
  - [x] If claimed list is null/empty, return immediately
  - [x] Dispatch `REPLICATE` using try-with-resources: `primaryStorageService.get(key)` → `backupStorageService.put(key, obj.data(), obj.metadata().contentLength(), obj.metadata().contentType())`
  - [x] Dispatch `DELETE`: `backupStorageService.delete(key)`
  - [x] All S3 calls OUTSIDE any transaction (after the claim TX commits)
  - [x] Success: `transactionTemplate.execute(() -> { outboxReplicationJobRepository.markAsCompleted(job.getId()); return null; })`
  - [x] Failure: `newCount = job.getAttemptCount() + 1`; if `newCount >= properties.getReplication().getMaxAttempts()` → `markAsFailed(id, newCount, now, truncatedMsg)`; else → `markAsPendingForRetry(id, newCount, now, truncatedMsg)`; log key + error at WARN
  - [x] Truncate exception message to 500 chars before persisting as `errorMessage`

- [x] Task 3: Wire `OutboxPollerScheduler` bean in `StorageConfig` (AC: 7)
  - [x] Add bean method annotated `@Bean @ConditionalOnProperty(name = "app.storage.replication.enabled", havingValue = "true")`
  - [x] Inject `@Qualifier("storageService") StorageService primaryStorageService` and `@Qualifier("backupStorageService") StorageService backupStorageService`
  - [x] Also inject `OutboxReplicationJobRepository`, `StorageProperties`, `TransactionTemplate storageTransactionTemplate`
  - [x] File: `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageConfig.java`

- [x] Task 4: Write `OutboxPollerSchedulerTest` unit test (AC: 2–5)
  - [x] Create `src/test/java/com/softropic/skillars/infrastructure/storage/service/OutboxPollerSchedulerTest.java`
  - [x] `@ExtendWith(MockitoExtension.class)`, mocks: `StorageService primaryStorageService`, `StorageService backupStorageService`, `OutboxReplicationJobRepository outboxReplicationJobRepository`, real `StorageProperties` (defaults: batchSize=10, maxAttempts=5), `TransactionTemplate transactionTemplate`
  - [x] `@BeforeEach` constructs `new OutboxPollerScheduler(primary, backup, outboxRepo, props, txTemplate)`
  - [x] Test `pollAndProcess_replicateJob_streamsFromPrimaryToBackup`: mock `transactionTemplate.execute()` (first call) to invoke callback and return a list with one REPLICATE/PENDING/attemptCount=0 job; mock second `execute()` (for markAsCompleted) to invoke callback; verify `primaryStorageService.get(key)` and `backupStorageService.put(...)` called; verify `outboxReplicationJobRepository.markAsCompleted(id)` called
  - [x] Test `pollAndProcess_deleteJob_callsBackupDelete`: mock PENDING DELETE job; verify `backupStorageService.delete(key)` called; verify `markAsCompleted(id)` called; verify `primaryStorageService.get()` never called
  - [x] Test `pollAndProcess_failure_belowMaxAttempts_resetsToPending`: mock backup `.put()` to throw; job has `attemptCount=0`, maxAttempts=5; verify `markAsPendingForRetry(id, 1, any(), any())` called; verify `markAsFailed` never called
  - [x] Test `pollAndProcess_failure_atMaxAttempts_marksAsFailed`: job has `attemptCount=4`, maxAttempts=5; verify `markAsFailed(id, 5, any(), any())` called; verify `markAsPendingForRetry` never called
  - [x] Test `pollAndProcess_emptyPoll_noop`: mock `transactionTemplate.execute()` to return empty list; verify no S3 calls; verify no status update calls

- [x] Task 5: Write `OutboxReplicationJobRepositoryIT` integration test (AC: 6, 8)
  - [x] Create `src/test/java/com/softropic/skillars/infrastructure/storage/repo/OutboxReplicationJobRepositoryIT.java`
  - [x] Extends `BaseStorageIT`
  - [x] `@Autowired`: `OutboxReplicationJobRepository`, `FileStorageObjectRepository`, `TransactionTemplate`
  - [x] `@BeforeEach` cleanup order (FK): `storageAccessEventRepository.deleteAll()` → `outboxReplicationJobRepository.deleteAll()` → `fileStorageObjectRepository.deleteAll()`
  - [x] Helper method `createPendingJob(FileStorageObject fso, ReplicationJobType type)` — saves PENDING job with attemptCount=0
  - [x] Helper method `createFso()` — builds and saves a `FileStorageObject` with required fields: `key`, `tenantId="tenant-test"`, `sizeBytes=1024L`, `provider="s3"`, `bucket="test-bucket"`
  - [x] Test `pollPending_skipLocked_returnDisjointSets`: create 4 PENDING REPLICATE jobs; use `ExecutorService` (2 threads) + `CountDownLatch`; TX1 calls `pollPending(2)` and holds until TX2 completes; TX2 calls `pollPending(2)`; assert TX1 ids and TX2 ids are disjoint and total = 4
  - [x] Test `pollPending_excludesFailedAndCompletedJobs`: insert one FAILED job and one COMPLETED job; call `pollPending(10)`; assert result is empty
  - [x] Test `markAsFailed_setsStatusAndErrorMessage`: insert PENDING job; call `markAsFailed(id, 5, Instant.now(), "test error")`; reload via `findById`; assert status=FAILED, errorMessage="test error", attemptCount=5

- [x] Task 6: Verify build and regression (AC: all)
  - [x] Run `mvn test -Dtest=OutboxPollerSchedulerTest` — all 5 tests pass
  - [x] Run `mvn test -Dtest=OutboxReplicationJobRepositoryIT` — all 3 tests pass
  - [x] Run full test suite — 202 tests pass (5 new unit + 3 new IT tests added; `replication.enabled=false` in test config; `OutboxPollerScheduler` bean never created in tests)

## Dev Notes

### Critical: Atomic Claim — SKIP LOCKED + markAsProcessing in ONE Transaction

The `FOR UPDATE SKIP LOCKED` row locks are held until the owning transaction COMMITS. The moment the transaction commits, the locks release. For exclusive processing, `pollPending` and `markAsProcessing` MUST be in the SAME transaction:

```java
List<OutboxReplicationJob> jobs = transactionTemplate.execute(status -> {
    List<OutboxReplicationJob> pending = outboxReplicationJobRepository.pollPending(batchSize);
    if (pending.isEmpty()) return pending;
    Instant now = Instant.now();
    pending.forEach(j -> {
        j.getStorageObject().getKey(); // REQUIRED: initialize lazy proxy within this TX
        outboxReplicationJobRepository.markAsProcessing(j.getId(), now);
    });
    return pending;
});
```

After this TX commits: jobs are `PROCESSING` — other nodes' `pollPending` queries `WHERE status = 'PENDING'` and will not return them. This is the exclusive processing guarantee.

### Critical: Lazy Proxy Initialization Inside the Claim Transaction

`OutboxReplicationJob.storageObject` is `@ManyToOne` with default `FetchType.LAZY`. Accessing `job.getStorageObject().getKey()` AFTER the claim transaction commits throws `LazyInitializationException`. **Fix:** call `j.getStorageObject().getKey()` INSIDE the `transactionTemplate.execute()` claim block. Hibernate loads the full `FileStorageObject` row (all columns, including `key`, `contentType`, `sizeBytes`) when any field of the proxy is first accessed. After TX commit, the entity is detached but the proxy is initialized — all fields remain accessible via the in-memory loaded data.

### Critical: S3 Calls Outside @Transactional

Architecture rule (non-negotiable): S3 calls inside `@Transactional` hold a DB connection open during network I/O. The claim TX must COMMIT before any S3 call. The `processJob` private method must NOT be `@Transactional`. Correct sequencing:

```
1. transactionTemplate.execute { pollPending + markAsProcessing }  → COMMIT TX
2. primaryStorageService.get(key)     ← OUTSIDE any transaction
3. backupStorageService.put(...)      ← OUTSIDE any transaction
4. transactionTemplate.execute { markAsCompleted }  → COMMIT TX
```

### Critical: OutboxPollerScheduler Must NOT Be @Service

Do NOT annotate `OutboxPollerScheduler` with `@Service`. Component scanning would register an unconditional bean, conflicting with the `@ConditionalOnProperty` wiring in `StorageConfig`. Follow the identical pattern from `ReplicatedStorageService` (also no `@Service`, created in `StorageConfig`). Spring detects `@Scheduled` on all managed beans — beans registered via `@Bean` methods are fully supported.

### Critical: Qualifier Injection for Primary vs. Backup

In `StorageConfig.outboxPollerScheduler()`:
- `@Qualifier("storageService")` → injects the `S3StorageService` (plain primary; NOT `@Primary ReplicatedStorageService`)
- `@Qualifier("backupStorageService")` → injects the backup `S3StorageService`

If you inject `StorageService` without a qualifier when `replication.enabled=true`, Spring resolves the `@Primary` bean which is `ReplicatedStorageService` — the poller would call `ReplicatedStorageService.get()` for REPLICATE jobs (harmless but semantically wrong and creates confusion). Use explicit qualifiers.

### REPLICATE Dispatch — Streaming with try-with-resources

`StorageObject` is `record StorageObject(InputStream data, StorageObjectMetadata metadata)`. `StorageObjectMetadata` is `record StorageObjectMetadata(String key, String contentType, long contentLength, String etag, Instant lastModified)`.

```java
case REPLICATE -> {
    StorageObject obj = primaryStorageService.get(key);
    try (InputStream data = obj.data()) {
        backupStorageService.put(key, data,
            obj.metadata().contentLength(),
            obj.metadata().contentType());
    }
}
```

Use try-with-resources because `StorageObject.data()` wraps `ResponseInputStream<GetObjectResponse>` — a live HTTP stream that must be closed. Failure to close leaks connections.

### JPQL Enum References in @Query

Use fully-qualified enum class names inside JPQL `UPDATE` queries. Example:

```java
@Modifying(clearAutomatically = true)
@Transactional
@Query("UPDATE OutboxReplicationJob j SET " +
       "j.status = com.softropic.skillars.infrastructure.storage.repo.OutboxReplicationJob.ReplicationJobStatus.PROCESSING, " +
       "j.lastAttemptedAt = :ts WHERE j.id = :id")
void markAsProcessing(@Param("id") Long id, @Param("ts") Instant ts);
```

Use `@Modifying(clearAutomatically = true)` on all status-mutation queries to clear the first-level persistence cache after the update — prevents stale reads if the same entity is queried again in the same session.

### Native Query Schema Prefix

Match the existing pattern in `FileStorageObjectRepository.findEligibleForPhysicalDeletion` which uses `main.outbox_replication_jobs` schema prefix in the native query. Use `main.outbox_replication_jobs` (not just `outbox_replication_jobs`).

### @Transactional on pollPending — Propagation Behavior

`pollPending` is annotated `@Transactional` (read-write, default `REQUIRED` propagation). When called inside `TransactionTemplate.execute()`, it JOINS the outer transaction (REQUIRED = join if one exists). This is the correct behavior — the `FOR UPDATE SKIP LOCKED` acquires locks within the outer transaction, which holds them until the `TransactionTemplate` commits. Do NOT use `@Transactional(readOnly = true)` on `pollPending` — `FOR UPDATE` requires a read-write transaction.

### DELETE Job Idempotency

`backupStorageService.delete(key)` → `S3StorageService.delete()` → `s3Client.deleteObject()`. Deleting a non-existent key in S3/MinIO does NOT throw an exception — it is idempotent. This makes DELETE job retry safe without special handling.

### Error Message Truncation

Truncate exception messages before persisting as `errorMessage`. Column is `TEXT` (unbounded), but long stack traces would be unusable. Limit to 500 chars:

```java
String errorMsg = e.getMessage();
if (errorMsg != null && errorMsg.length() > 500) {
    errorMsg = errorMsg.substring(0, 500);
}
```

### Unit Test — TransactionTemplate Mock Pattern

Follow `ReplicatedStorageServiceTest` exactly. The first `execute()` call returns the job list; the second `execute()` call for markAsCompleted/markAsFailed/markAsPendingForRetry invokes the callback:

```java
// First execute (claim) — returns mock job list
when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
    TransactionCallback<?> cb = invocation.getArgument(0);
    cb.doInTransaction(mock(TransactionStatus.class));
    return List.of(mockJob);  // return list for claim
}).thenAnswer(invocation -> {
    TransactionCallback<?> cb = invocation.getArgument(0);
    cb.doInTransaction(mock(TransactionStatus.class));
    return null;  // return null for status update
});
```

For `pollAndProcess_emptyPoll_noop`: mock the first `transactionTemplate.execute()` to invoke the callback but return an empty list (the callback itself calls `pollPending` which you mock to return empty). Verify no S3 service methods are invoked.

### Integration Test — Direct FSO Creation Pattern

For IT tests that need jobs without going through the full sign/upload flow:

```java
// FileStorageObject — required fields: key, tenantId, sizeBytes, provider, bucket
FileStorageObject fso = FileStorageObject.builder()
    .key("documents/42/2026/05/" + UUID.randomUUID() + ".pdf")
    .tenantId("tenant-test")
    .sizeBytes(1024L)
    .provider("s3")
    .bucket("test-bucket")
    .build();
fso = fileStorageObjectRepository.save(fso);

// OutboxReplicationJob
OutboxReplicationJob job = OutboxReplicationJob.builder()
    .storageObject(fso)
    .jobType(OutboxReplicationJob.ReplicationJobType.REPLICATE)
    .status(OutboxReplicationJob.ReplicationJobStatus.PENDING)
    .attemptCount(0)
    .build();
outboxReplicationJobRepository.save(job);
```

`BaseEntity.id` is auto-generated by `@Tsid`. Do NOT set `id` manually.

### Integration Test — SKIP LOCKED Concurrency Test

Requires two concurrent DB connections. `BaseStorageIT` uses `@SpringBootTest(webEnvironment = RANDOM_PORT)` with Testcontainers — the pool has multiple connections. Pattern:

```java
@Autowired ExecutorService storageUploadExecutor; // from StorageConfig
// OR: ExecutorService pool = Executors.newFixedThreadPool(2);

CountDownLatch tx1Fetched = new CountDownLatch(1);
CountDownLatch tx2Done = new CountDownLatch(1);

Future<List<Long>> tx1Future = executor.submit(() ->
    transactionTemplate.execute(status -> {
        List<OutboxReplicationJob> jobs = outboxReplicationJobRepository.pollPending(2);
        tx1Fetched.countDown(); // signal TX1 acquired locks
        tx2Done.await();        // hold TX open while TX2 runs
        return jobs.stream().map(j -> j.getId()).collect(toList());
    })
);

tx1Fetched.await();
List<Long> tx2Ids = transactionTemplate.execute(status ->
    outboxReplicationJobRepository.pollPending(2)
        .stream().map(j -> j.getId()).collect(toList())
);
tx2Done.countDown();
List<Long> tx1Ids = tx1Future.get(5, TimeUnit.SECONDS);

// TX1 gets jobs 1+2 (locked); TX2 gets jobs 3+4 (skipped 1+2 via SKIP LOCKED)
assertThat(tx1Ids).doesNotContainAnyElementsOf(tx2Ids);
assertThat(tx1Ids.size() + tx2Ids.size()).isEqualTo(4);
```

Note: `CountDownLatch.await()` inside `transactionTemplate.execute()` blocks the DB connection. Ensure the test connection pool (HikariCP) allows ≥2 simultaneous connections. With `@SpringBootTest` + Testcontainers this is the default.

### Test Baseline

Story 4.1 completed with **197 tests passing** (8 new unit tests added). This story adds `OutboxPollerSchedulerTest` (~5 unit tests) and `OutboxReplicationJobRepositoryIT` (~3 IT tests). Full regression suite must remain ≥197 passing. With `replication.enabled=false` in test config (`application-test.yaml` or `application-dev.yaml`), `OutboxPollerScheduler` is NOT a bean in tests — zero behavior change to existing tests.

### Previous Story Intelligence (Story 4.1)

- **`@Primary @ConditionalOnProperty` bean pattern** — `ReplicatedStorageService` in `StorageConfig` is the template; use identical approach for `OutboxPollerScheduler`
- **`TransactionTemplate storageTransactionTemplate` bean** — already defined in `StorageConfig.storageTransactionTemplate()`. Inject it as `TransactionTemplate storageTransactionTemplate` in the new `@Bean` method
- **`@RequiredArgsConstructor` + no `@Service`** — confirmed pattern for `ReplicatedStorageService`; same for `OutboxPollerScheduler`
- **`fileStorageObjectRepository.findByKey()`** — already exists (used by `ReplicatedStorageService`); no new method needed for simple key lookup
- **FK cleanup order in IT tests**: `storageAccessEventRepository.deleteAll()` → `outboxReplicationJobRepository.deleteAll()` → `fileStorageObjectRepository.deleteAll()`
- **BaseEntity uses `@Tsid` for id** — Long type, auto-assigned on `@PrePersist`; do NOT set `id` manually in test builders

### Project Structure Notes

```
src/main/java/com/softropic/skillars/infrastructure/storage/
├── config/
│   └── StorageConfig.java                       ← MODIFY: add OutboxPollerScheduler @Bean
├── repo/
│   └── OutboxReplicationJobRepository.java      ← MODIFY: add pollPending + 4 state-transition methods
└── service/
    └── OutboxPollerScheduler.java               ← NEW: @Slf4j @RequiredArgsConstructor (no @Service)

src/test/java/com/softropic/skillars/infrastructure/storage/
├── service/
│   └── OutboxPollerSchedulerTest.java           ← NEW: @ExtendWith(MockitoExtension.class)
└── repo/
    └── OutboxReplicationJobRepositoryIT.java    ← NEW: extends BaseStorageIT
```

**Do NOT modify:**
- `OutboxReplicationJob.java` — entity fields are sufficient; all updates via `@Modifying @Query`
- `ReplicatedStorageService.java` — no changes; its `put()` outbox insert is already the REPLICATE trigger
- `StorageSigningService.java` — no changes; `confirmUpload()` outbox insert is already correct
- `DeletionSchedulerService.java` — no changes; `job_type='DELETE'` outbox insert is already correct
- `BaseStorageIT.java` — no changes
- `application.yaml` — no new config keys needed (all required keys already present: `poller.fixed-delay-ms`, `poller.batch-size`, `replication.max-attempts`, `replication.enabled`)

### References

- [Source: epics.md#Story 4.2] — Full BDD acceptance criteria, SKIP LOCKED semantics, retry/dead-letter rules, replication-disabled guard
- [Source: architecture.md#Outbox Poller — SKIP LOCKED] — `pollPending` JPQL pattern, FAILED dead-letter strategy, never retry FAILED
- [Source: architecture.md#Process Patterns — Transaction Boundary] — S3 outside @Transactional; TransactionTemplate for DB writes
- [Source: architecture.md#Enforcement Guidelines] — Never wrap S3 calls inside @Transactional; always use StorageErrorCode; SKIP LOCKED on all outbox queries
- [Source: FileStorageObjectRepository.java#findEligibleForPhysicalDeletion] — native query SKIP LOCKED pattern with `main.` schema prefix
- [Source: FileStorageObjectRepository.java#markPhysicallyDeleted] — `@Modifying @Transactional @Query` update pattern
- [Source: DeletionSchedulerService.java] — `@Scheduled(fixedDelayString)` + `TransactionTemplate` orchestration pattern; S3 call before TX
- [Source: StorageConfig.java] — `@ConditionalOnProperty`, `@Qualifier` injection, existing `storageTransactionTemplate` bean, `backupStorageService` qualifier name
- [Source: OutboxReplicationJob.java] — entity fields: `status`, `jobType`, `attemptCount`, `lastAttemptedAt`, `errorMessage`; `storageObject` is `@ManyToOne LAZY nullable=false`
- [Source: StorageService.java] — `get(key)` returns `StorageObject`; `put(key, InputStream, long, String)` signature; `delete(key)` void
- [Source: StorageObject.java] — `record StorageObject(InputStream data, StorageObjectMetadata metadata)`
- [Source: StorageObjectMetadata.java] — `record StorageObjectMetadata(String key, String contentType, long contentLength, String etag, Instant lastModified)` — use `contentLength()` and `contentType()` for `put()` call
- [Source: ReplicatedStorageService.java] — `no @Service`, constructor-injected, `TransactionTemplate.execute()` for DB write
- [Source: ReplicatedStorageServiceTest.java] — TransactionCallback mock pattern: `thenAnswer` to invoke callback and return value
- [Source: StorageDeletionIT.java] — IT test structure: FK cleanup order, `transactionTemplate.execute()` usage, `@Autowired` injection pattern
- [Source: 4-1-replicated-storage-service.md#Dev Notes] — `@Primary` wiring, `no @Service` pattern, `backupCredentialsProvider` helper, unit test `TransactionCallback` pattern
- [Source: project-context.md#Testing Rules] — Mockito for unit tests; AssertJ (`assertThat`) for assertions; no mock DB in IT tests
- [Source: project-context.md#Language-Specific Rules] — `@Slf4j`, `@RequiredArgsConstructor` on services
- [Source: AsyncConfig.java] — `@EnableScheduling` is active globally; `@Scheduled` on `@Bean`-registered beans is fully supported

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

(none)

### Completion Notes List

- Implemented `pollPending` native query with `FOR UPDATE SKIP LOCKED` against `main.outbox_replication_jobs`; all four state-transition JPQL update methods added with `@Modifying(clearAutomatically = true)`.
- `OutboxPollerScheduler` follows the critical transaction boundary pattern: atomic claim TX (pollPending + markAsProcessing + lazy proxy init) commits before any S3 calls; success/failure status updates each run in their own `TransactionTemplate.execute()`.
- `StorageConfig.outboxPollerScheduler()` is `@ConditionalOnProperty(replication.enabled=true)` only; uses `@Qualifier("storageService")` and `@Qualifier("backupStorageService")` to avoid injecting the `@Primary` `ReplicatedStorageService`.
- Unit test uses a real `StorageProperties` instance (defaults batchSize=10, maxAttempts=5) to avoid Mockito `UnnecessaryStubbingException` in success-path tests.
- SKIP LOCKED concurrency IT test uses `CountDownLatch` + `ExecutorService` to hold TX1 open while TX2 polls; asserts disjoint sets totalling 4.
- Full suite: 202 tests, 0 failures, 0 errors. `OutboxPollerScheduler` bean absent from test context (`replication.enabled=false`).

### File List

- `src/main/java/com/softropic/skillars/infrastructure/storage/repo/OutboxReplicationJobRepository.java` (modified)
- `src/main/java/com/softropic/skillars/infrastructure/storage/service/OutboxPollerScheduler.java` (created)
- `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageConfig.java` (modified)
- `src/test/java/com/softropic/skillars/infrastructure/storage/service/OutboxPollerSchedulerTest.java` (created)
- `src/test/java/com/softropic/skillars/infrastructure/storage/repo/OutboxReplicationJobRepositoryIT.java` (created)
- `_bmad-output/implementation-artifacts/4-2-async-replication-outbox-poller.md` (updated)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (updated)

## Change Log

- 2026-05-26: Story 4.2 implemented — outbox poller with SKIP LOCKED, retry/dead-letter logic, conditional bean wiring; 5 unit tests + 3 IT tests added (202 total passing)
