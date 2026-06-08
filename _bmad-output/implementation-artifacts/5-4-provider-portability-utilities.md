# Story 5.4: Provider Portability Utilities

Status: review

## Story

As a system operator performing a provider migration,
I want a streaming copy utility and metadata export/import capability,
so that I can migrate all stored objects from one S3-compatible provider to another without loading files into memory or losing metadata.

## Acceptance Criteria

1. **Streaming single-object migration**: Given a source `StorageService` (Provider A) and a destination `StorageService` (Provider B), when `StorageMigrationService.migrate(sourceKey, destinationKey)` is called, then the object is streamed directly from Provider A to Provider B using `source.get(key)` piped to `destination.put(key, ...)`. `InputStream.readAllBytes()` is **never** called — migration is fully streaming.

2. **Batch tenant migration**: Given `StorageMigrationService.migrateAll(tenantId)` is called, when it executes, then it iterates all non-deleted `file_storage_objects` for the tenant and calls `migrate` for each. It logs progress for each object and skips keys that already exist in the destination. Failures on individual objects are logged with the key and error but do not abort the batch.

3. **Metadata export**: Given `StorageMigrationService.exportMetadata(tenantId)` is called, when it executes, then it returns a serializable `List<StorageObjectDto>` containing all non-deleted objects owned by the tenant (key, tenantId, originalFilename, contentType, sizeBytes, checksum, tags, provider, bucket, uploadConfirmedAt).

4. **Metadata import (idempotent)**: Given `StorageMigrationService.importMetadata(List<StorageObjectDto>)` is called, when it executes, then it inserts `file_storage_objects` records for each DTO, skipping any key that already exists (idempotent). Inserted records get status `ACTIVE`.

5. **Integration test against two MinIO containers**: Given `StorageMigrationServiceIT`, when it runs against a source MinIO (from `BaseStorageIT`) and a standalone destination MinIO container, then `migrate` streams a file from source to destination and the file is readable from the destination. `exportMetadata` returns all confirmed objects. `importMetadata` re-inserts them idempotently (second call inserts nothing new).

## Tasks / Subtasks

- [x] Task 1: Create `StorageObjectDto` record (AC: 3, 4)
  - [x] Create file: `src/main/java/com/softropic/skillars/infrastructure/storage/contract/StorageObjectDto.java`
  - [x] Implement as a Java record with fields: `String key`, `String tenantId`, `String originalFilename`, `String contentType`, `long sizeBytes`, `String checksum`, `Map<String, String> tags`, `String provider`, `String bucket`, `Instant uploadConfirmedAt`
  - [x] All fields nullable except `key`, `tenantId`, `provider`, `bucket`, `sizeBytes` — use primitive `long` for `sizeBytes`
  - [x] Do NOT include `id`, `createdBy`, `status`, or other auditing fields — those are infrastructure concerns set at import time

- [x] Task 2: Add `findAllByTenantIdAndDeletedAtIsNull` to repository (AC: 2, 3)
  - [x] Update file: `src/main/java/com/softropic/skillars/infrastructure/storage/repo/FileStorageObjectRepository.java`
  - [x] Add derived method: `List<FileStorageObject> findAllByTenantIdAndDeletedAtIsNull(String tenantId);`
  - [x] No `@Query` annotation needed — Spring Data derives the JPQL automatically from the method name

- [x] Task 3: Create `StorageMigrationService` (AC: 1, 2, 3, 4)
  - [x] Create file: `src/main/java/com/softropic/skillars/infrastructure/storage/service/StorageMigrationService.java`
  - [x] Annotate with `@Slf4j` only — this is NOT a Spring `@Service`; it is a plain Java class instantiated by operators/tests
  - [x] Constructor: `StorageMigrationService(StorageService source, StorageService destination, FileStorageObjectRepository fileStorageObjectRepository)` — store all three as `private final` fields
  - [x] **`migrate(String sourceKey, String destinationKey)`**:
    - Call `source.get(sourceKey)` → returns `StorageObject(InputStream data, StorageObjectMetadata metadata)`
    - Open a `try (InputStream data = obj.data())` block — **never** call `data.readAllBytes()`
    - Inside the try block: `destination.put(destinationKey, data, obj.metadata().contentLength(), obj.metadata().contentType())`
    - Catch `IOException` from the try-with-resources and rethrow as `new StorageProviderException("migrate", ex, sourceKey + "->" + destinationKey)`
    - Log `log.info("Migrated: {} → {}", sourceKey, destinationKey)` on success before returning
  - [x] **`migrateAll(String tenantId)`**:
    - Query: `List<FileStorageObject> objects = fileStorageObjectRepository.findAllByTenantIdAndDeletedAtIsNull(tenantId)`
    - Log: `log.info("Starting migration for tenant {}, {} objects", tenantId, objects.size())`
    - For each `fso` in `objects`: if `destination.exists(fso.getKey())` → `log.info("Skipping already-migrated key: {}", fso.getKey())` and `continue`
    - Otherwise: `try { migrate(fso.getKey(), fso.getKey()); } catch (Exception ex) { log.error("Failed to migrate key: {}, error: {}", fso.getKey(), ex.getMessage()); }` — swallow exception to continue batch
    - Log `log.info("Migration complete for tenant {}", tenantId)` when loop finishes
  - [x] **`exportMetadata(String tenantId)`** returns `List<StorageObjectDto>`:
    - Query: `fileStorageObjectRepository.findAllByTenantIdAndDeletedAtIsNull(tenantId)`
    - Map each `FileStorageObject` to `new StorageObjectDto(fso.getKey(), fso.getTenantId(), fso.getOriginalFilename(), fso.getContentType(), fso.getSizeBytes(), fso.getChecksum(), fso.getTags(), fso.getProvider(), fso.getBucket(), fso.getUploadConfirmedAt())`
    - Return via `.stream().map(...).toList()`
  - [x] **`importMetadata(List<StorageObjectDto> dtos)`**:
    - For each `dto` in `dtos`:
      - If `fileStorageObjectRepository.findByKey(dto.key()).isPresent()` → `log.info("Skipping existing key: {}", dto.key())` and `continue`
      - Otherwise build and save: `FileStorageObject fso = FileStorageObject.builder().key(dto.key()).tenantId(dto.tenantId()).originalFilename(dto.originalFilename()).contentType(dto.contentType()).sizeBytes(dto.sizeBytes()).checksum(dto.checksum()).tags(dto.tags()).provider(dto.provider()).bucket(dto.bucket()).uploadConfirmedAt(dto.uploadConfirmedAt()).status(EntityStatus.ACTIVE).build();`
      - Then `fileStorageObjectRepository.save(fso)`

- [x] Task 4: Create `StorageMigrationServiceIT` integration test (AC: 5)
  - [x] Create file: `src/test/java/com/softropic/skillars/infrastructure/storage/service/StorageMigrationServiceIT.java`
  - [x] Extend `BaseStorageIT` to inherit the full Spring context (primary MinIO + PostgreSQL + Redis + Security)
  - [x] Add a SECOND static `@Container MinIOContainer destinationMinio = new MinIOContainer(DockerImageName.parse("minio/minio:RELEASE.2024-01-13T07-53-03Z"))` field — this is the destination provider. Do NOT use `MinioContainerConfig` for it; wire it manually.
  - [x] Add static fields: `static StorageService destinationService;` — initialized in `@BeforeAll`
  - [x] In `@BeforeAll static void setUpDestination()`:
    - Build destination `S3Client`: configured with destinationMinio URL, credentials, region us-east-1, path-style access
    - Create the test bucket on destination
    - Build `S3AsyncClient destAsyncClient` and `S3TransferManager destTransferManager`
    - Build `StorageProperties destProperties` with bucket=DEST_BUCKET, credentials, pathStyle=true
    - Set `destinationService = new S3StorageService(destS3Client, destTransferManager, destProperties, Executors.newFixedThreadPool(2))`
  - [x] Autowire: `@Autowired StorageService storageService` (primary), `@Autowired FileStorageObjectRepository fileStorageObjectRepository`, `@Autowired StorageProperties storageProperties`
  - [x] Add `private final List<String> sourceKeysToCleanup` and `destKeysToCleanup`
  - [x] `@BeforeEach setUp()`: `fileStorageObjectRepository.deleteAll()`; clear both cleanup lists
  - [x] `@AfterEach tearDown()`: delete all cleanup keys from source and destination (suppress exceptions)
  - [x] **Test `migrate_streamsFileFromSourceToDestination`**: put → migrate → assert exists on dest + correct content + source intact
  - [x] **Test `migrateAll_skipsAlreadyMigratedKeys`**: 2 records, 1 pre-migrated; assert both on dest, no exception
  - [x] **Test `exportMetadata_returnsAllNonDeletedObjects`**: 3 records (2 active, 1 deleted) → 2 in result
  - [x] **Test `importMetadata_isIdempotent`**: 2 DTOs → 2 records; call again → still 2 records; both ACTIVE

- [x] Task 5: Verify no regressions (AC: all)
  - [x] Run `mvn test -Dtest=StorageMigrationServiceIT` — all 4 new IT tests pass
  - [x] Run `mvn test -Dtest=StorageResourceIT` — all existing tests pass
  - [x] Run `mvn test -Dtest=S3StorageServiceIT` — all existing tests pass
  - [x] Run full test suite — all 216 unit tests + 12 existing IT tests pass; zero regressions

## Dev Notes

### `StorageMigrationService` is NOT a Spring Bean

Do NOT annotate it with `@Service`. It is a plain Java class with constructor injection. This is intentional: migration is an operator-driven tool, not a production service. Tests construct it directly with explicit source/destination instances. Future callers (e.g., an admin endpoint) would construct it by injecting the desired source/destination `StorageService` beans.

This avoids:
- Bean ambiguity (two `StorageService` qualifiers needed at the same time)
- Conditional bean conflicts with replication beans
- Unnecessary singleton lifecycle for a tool used episodically

### Streaming Pattern — Why `readAllBytes()` is Forbidden

`S3StorageService.get(key)` returns a `ResponseInputStream<GetObjectResponse>` wrapped as `StorageObject.data()`. This is a live HTTP stream from the S3 provider — the file bytes are NOT in memory yet. By piping this stream directly to `destination.put(key, data, contentLength, contentType)`, which calls `S3TransferManager.upload(AsyncRequestBody.fromInputStream(data, ...))`, the data flows:

```
Source MinIO → ResponseInputStream → AsyncRequestBody → Destination MinIO
```

If you call `data.readAllBytes()`, you load the entire file into a `byte[]` on the JVM heap — a violation of the module's streaming contract (see architecture.md#Gap Analysis, NFR Performance).

The `contentLength` is available from `obj.metadata().contentLength()` — pass it directly to `destination.put()` so `AsyncRequestBody.fromInputStream()` knows the size without buffering.

### Streaming Pattern — Correct `migrate()` Implementation

```java
public void migrate(String sourceKey, String destinationKey) {
    StorageObject obj = source.get(sourceKey);
    try (InputStream data = obj.data()) {
        destination.put(destinationKey, data, obj.metadata().contentLength(), obj.metadata().contentType());
        log.info("Migrated: {} -> {}", sourceKey, destinationKey);
    } catch (IOException e) {
        throw new StorageProviderException("migrate", e, sourceKey + "->" + destinationKey);
    }
}
```

`source.get()` is called OUTSIDE the try-with-resources — the stream must be open when `put()` is called. The `try (InputStream data = ...)` ensures the stream is closed after `put()` completes (or on error). Do not call any other read methods on `data` before or after `destination.put()`.

### `FileStorageObject.builder()` in `importMetadata` — Status Override

`AbstractAuditingEntity` has `@Builder.Default` on `status` with value `EntityStatus.INACTIVE`. When building via `@SuperBuilder`, the default applies unless you explicitly override it. For imported objects that represent confirmed, live data, you MUST set `.status(EntityStatus.ACTIVE)` in the builder chain:

```java
FileStorageObject.builder()
    .key(dto.key())
    .tenantId(dto.tenantId())
    .originalFilename(dto.originalFilename())
    .contentType(dto.contentType())
    .sizeBytes(dto.sizeBytes())
    .checksum(dto.checksum())
    .tags(dto.tags())
    .provider(dto.provider())
    .bucket(dto.bucket())
    .uploadConfirmedAt(dto.uploadConfirmedAt())
    .status(EntityStatus.ACTIVE)   // ← required; default is INACTIVE
    .build();
```

The `id` field is generated by `@Tsid` (time-sorted TSID via Hypersistence Utils) — do NOT set it manually in the builder. Hibernate populates it via the `@Id @Tsid` generation on persist.

### Integration Test — Two MinIO Containers

`StorageMigrationServiceIT` extends `BaseStorageIT` to reuse the full Spring context (primary MinIO + PostgreSQL + Redis). A second `MinIOContainer` is added as a `static @Container` field in the test class itself. It is started by the Testcontainers JUnit extension before `@BeforeAll` runs.

The destination `S3StorageService` must be constructed manually — do NOT use `MinioContainerConfig` for the second MinIO. The primary MinIO is bound to `app.storage.*` properties; the destination is wired programmatically in `@BeforeAll`.

For the `StorageProperties` needed by destination `S3StorageService`: Construct a `new StorageProperties()` instance, set `bucket`, `endpointUrl`, path style access, credentials via setters. The `S3StorageService` only uses `properties.getBucket()` for all operations — so a minimal `StorageProperties` suffices.

Full `@BeforeAll` pattern:

```java
@BeforeAll
static void setUpDestination() {
    // Destination S3 synchronous client
    S3Client destS3Client = S3Client.builder()
        .endpointOverride(URI.create(destinationMinio.getS3URL()))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(destinationMinio.getUserName(), destinationMinio.getPassword())))
        .region(Region.of("us-east-1"))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();

    // Create bucket on destination MinIO
    try {
        destS3Client.headBucket(r -> r.bucket(DEST_BUCKET));
    } catch (NoSuchBucketException e) {
        destS3Client.createBucket(r -> r.bucket(DEST_BUCKET));
    }

    // Destination S3 async client + transfer manager
    S3AsyncClient destAsyncClient = S3AsyncClient.builder()
        .endpointOverride(URI.create(destinationMinio.getS3URL()))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(destinationMinio.getUserName(), destinationMinio.getPassword())))
        .region(Region.of("us-east-1"))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
    S3TransferManager destTransferManager = S3TransferManager.builder().s3Client(destAsyncClient).build();

    // Minimal StorageProperties for destination
    StorageProperties destProperties = new StorageProperties();
    destProperties.setBucket(DEST_BUCKET);
    destProperties.setEndpointUrl(destinationMinio.getS3URL());
    StorageProperties.S3 s3Props = new StorageProperties.S3();
    s3Props.setPathStyleAccess(true);
    s3Props.setAccessKey(destinationMinio.getUserName());
    s3Props.setSecretKey(destinationMinio.getPassword());
    destProperties.setS3(s3Props);

    destinationService = new S3StorageService(destS3Client, destTransferManager,
        destProperties, Executors.newFixedThreadPool(2));
}
```

Declare `static final String DEST_BUCKET = "test-dest"` as a constant.

### `StorageMigrationService` — `migrateAll` Failure Isolation Pattern

Individual object failures must NOT abort the batch:

```java
for (FileStorageObject fso : objects) {
    if (destination.exists(fso.getKey())) {
        log.info("Skipping already-migrated key: {}", fso.getKey());
        continue;
    }
    try {
        migrate(fso.getKey(), fso.getKey());
    } catch (Exception ex) {
        log.error("Failed to migrate key: {}, error: {}", fso.getKey(), ex.getMessage());
    }
}
```

Do NOT rethrow inside the catch. The log.error call provides the operator visibility without aborting the batch.

### `importMetadata` — `@Transactional` Boundary

Each `save()` in the import loop runs in its own auto-commit transaction (Spring Data default). For large imports, this could be slow. For MVP, individual auto-commits are acceptable. Do NOT manually batch or wrap in a single `@Transactional` across all items — failure mid-batch would rollback all inserts, violating the per-record idempotency contract.

### Test: `FileStorageObject` Construction for Test Data

When creating `FileStorageObject` records directly in IT tests (for `migrateAll` and `exportMetadata` tests), you need the `@SuperBuilder` and must set at minimum: `key`, `tenantId`, `provider`, `bucket`, `sizeBytes`, `status`. Example:

```java
FileStorageObject testFso = FileStorageObject.builder()
    .key("tenant/migrate-it/file1.txt")
    .tenantId("tenant-migrate-it")
    .originalFilename("file1.txt")
    .contentType("text/plain")
    .sizeBytes(5L)
    .provider("s3")
    .bucket("test-storage")   // use storageProperties.getBucket() to be portable
    .uploadConfirmedAt(Instant.now())
    .status(EntityStatus.ACTIVE)
    .build();
fileStorageObjectRepository.save(testFso);
```

Do NOT manually set the `id` — it is TSID-generated on persist.

### Test Baseline

Story 5.3 completion: 216 unit tests + 12 IT tests = 228 total. This story adds 4 new IT tests in `StorageMigrationServiceIT`. No new unit tests are needed since `StorageMigrationService` has no complex logic that requires isolation testing — the IT tests exercise the full stack.

Expected post-story baseline: 216 unit + 16 IT = 232 total. Zero regressions required.

### Project Structure Notes

**Files to CREATE:**
```
src/main/java/com/softropic/skillars/infrastructure/storage/contract/
└── StorageObjectDto.java              ← NEW: record for portability metadata transfer

src/main/java/com/softropic/skillars/infrastructure/storage/service/
└── StorageMigrationService.java       ← NEW: @Slf4j, plain class, 4 methods

src/test/java/com/softropic/skillars/infrastructure/storage/service/
└── StorageMigrationServiceIT.java     ← NEW: extends BaseStorageIT, 2 MinIO containers, 4 tests
```

**Files to MODIFY:**
```
src/main/java/com/softropic/skillars/infrastructure/storage/repo/
└── FileStorageObjectRepository.java   ← ADD: findAllByTenantIdAndDeletedAtIsNull(String tenantId)
```

**Files NOT to touch:**
- `StorageConfig.java` — no new factory beans needed for this story
- `StorageService.java` — interface is already complete
- `S3StorageService.java` — used as-is via constructor in tests
- `StorageResource.java` — no new endpoints in this story
- `OutboxPollerScheduler.java`, `StorageMetrics.java`, `StorageSigningService.java` — unaffected
- All existing test files — must remain passing

### References

- [Source: epics.md#Story 5.4] — Full BDD acceptance criteria, streaming requirement, two-MinIO integration test spec
- [Source: architecture.md#Gap Analysis] — "Provider migration utility (stream-copy A → B)" noted as deferred until this story
- [Source: architecture.md#Enforcement Guidelines] — "Never call `InputStream.readAllBytes()` on file content in any service method"
- [Source: architecture.md#Package Layout] — contract/ for DTOs, service/ for services
- [Source: project-context.md#Critical Don't-Miss Rules] — no entity exposure from REST, no `@Service` without `@PreAuthorize` (not applicable here — no REST endpoint added)
- [Source: StorageService.java] — `get(key): StorageObject`, `put(key, InputStream, contentLength, contentType)`; `StorageObject` = `record(InputStream data, StorageObjectMetadata metadata)`
- [Source: StorageObjectMetadata.java] — `contentLength()`, `contentType()`, `key()` accessors
- [Source: S3StorageService.java] — constructor: `(S3Client, S3TransferManager, StorageProperties, ExecutorService)`; `get()` returns live `ResponseInputStream`; `put()` uses `AsyncRequestBody.fromInputStream()` — streaming, never buffers
- [Source: FileStorageObject.java] — entity fields: key, tenantId, originalFilename, contentType, sizeBytes, checksum, tags, provider, bucket, uploadConfirmedAt, deletedAt, physicalDeletedAt
- [Source: BaseEntity.java] — `id` is `Long` with `@Tsid` generation; never set manually
- [Source: AbstractAuditingEntity.java] — `status` field `@Builder.Default = EntityStatus.INACTIVE`; must override to `ACTIVE` on import
- [Source: EntityStatus.java] — `ACTIVE`, `INACTIVE`, `DELETED`
- [Source: FileStorageObjectRepository.java] — existing `findByKey(String)` used by `importMetadata` for idempotency check; add `findAllByTenantIdAndDeletedAtIsNull`
- [Source: StorageProviderException.java] — constructor: `(String operation, Throwable cause, String context)`
- [Source: MinioContainerConfig.java] — docker image `minio/minio:RELEASE.2024-01-13T07-53-03Z`; use same image for destination container
- [Source: BaseStorageIT.java] — imports: MinioContainerConfig, PostgresContainerConfig, RedisContainerConfig, TestMailConfig; profiles: dev, test
- [Source: S3StorageServiceIT.java] — pattern for cleanup with `List<String> keysToCleanup` + `@AfterEach`
- [Source: 5-3-observability-instrumentation.md#Completion Notes] — test baseline: 216 unit + 12 IT = 228 total

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- Fixed pre-existing regression: `LocalFileSystemStorageService.java` and `ReplicatedStorageServiceTest.java` used a 2-arg `StorageProviderException` constructor that was removed in story 5-3. Updated both to the 3-arg `(operation, cause, context)` constructor to restore compilation.

### Completion Notes List

- Created `StorageObjectDto` record (10 fields, no auditing fields, primitive `long sizeBytes`)
- Added `findAllByTenantIdAndDeletedAtIsNull(String tenantId)` Spring Data derived method to `FileStorageObjectRepository`
- Created `StorageMigrationService` as plain `@Slf4j` class (not a Spring bean) with 4 methods: `migrate`, `migrateAll`, `exportMetadata`, `importMetadata`
- `migrate()` uses `try (InputStream data = obj.data())` — never calls `readAllBytes()` on the live S3 stream
- `migrateAll()` skips already-migrated keys and swallows per-object failures to continue the batch
- `importMetadata()` checks `findByKey()` for idempotency and sets `status=ACTIVE` to override the `@Builder.Default` of `INACTIVE`
- Created `StorageMigrationServiceIT` with 2 MinIO containers and 4 tests covering: stream migration, batch skip logic, metadata export/delete filter, and idempotent import
- Test baseline: 216 unit + 16 IT = 232 total; 0 regressions

### File List

- `src/main/java/com/softropic/skillars/infrastructure/storage/contract/StorageObjectDto.java` (created)
- `src/main/java/com/softropic/skillars/infrastructure/storage/service/StorageMigrationService.java` (created)
- `src/test/java/com/softropic/skillars/infrastructure/storage/service/StorageMigrationServiceIT.java` (created)
- `src/main/java/com/softropic/skillars/infrastructure/storage/repo/FileStorageObjectRepository.java` (modified)
- `src/main/java/com/softropic/skillars/infrastructure/storage/service/LocalFileSystemStorageService.java` (modified — fixed pre-existing 2-arg constructor regression from story 5-3)
- `src/test/java/com/softropic/skillars/infrastructure/storage/service/ReplicatedStorageServiceTest.java` (modified — fixed pre-existing 2-arg constructor regression from story 5-3)
- `_bmad-output/implementation-artifacts/5-4-provider-portability-utilities.md` (story file)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (updated to review)

### Change Log

- 2026-05-26: Story 5.4 implemented — StorageObjectDto, StorageMigrationService (migrate/migrateAll/exportMetadata/importMetadata), StorageMigrationServiceIT (4 IT tests with dual MinIO containers). Fixed pre-existing 3-arg StorageProviderException constructor regression in LocalFileSystemStorageService and ReplicatedStorageServiceTest.
