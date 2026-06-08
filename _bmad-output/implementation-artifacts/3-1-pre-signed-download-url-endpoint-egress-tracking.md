# Story 3.1: Pre-signed Download URL Endpoint & Egress Tracking

Status: review

## Story

As a client application,
I want to request a pre-signed S3 download URL for a stored file,
so that I can serve file downloads directly from S3 without routing binary data through the backend, while the system tracks egress for usage reporting.

## Acceptance Criteria

1. **Verified non-deleted record; absent → 404**: Given a stored file with key `documents/42/2026/05/uuid.pdf` that has a confirmed `file_storage_objects` record, when `GET /api/storage/sign/download/{key}` is called by an authenticated user, then `StorageSigningService` verifies a non-deleted `file_storage_objects` record exists for the key; if absent, throws `StorageObjectNotFoundException` (HTTP 404).

2. **Egress event persisted before URL generation**: Given the record exists and is not soft-deleted, when the service processes the request, then a `storage_access_events` record (`key`, `tenantId`, `sizeBytes`, `accessedAt`) is inserted before generating the signed URL.

3. **Pre-signed GET URL generated**: Given egress tracking succeeds, then a pre-signed GET URL is generated via `S3Presigner.presignGetObject()` with TTL of `app.storage.presign-ttl-seconds`.

4. **SignDownloadResponse returned**: Given a successful flow, then `SignDownloadResponse` is returned with `key`, `downloadUrl`, and `expiresAt` (ISO 8601 — matching the architecture spec `{ "key": "...", "downloadUrl": "https://...", "expiresAt": "..." }`).

5. **Soft-deleted file returns 404**: Given the key exists but `deleted_at` is set on the `file_storage_objects` record, when `GET /api/storage/sign/download/{key}` is called, then `StorageObjectNotFoundException` is returned (HTTP 404) — soft-deleted files are not downloadable.

6. **Endpoint is secured and observed**: When any request reaches `StorageResource.signDownload`, then it carries `@Observed(name = "storage.sign.download")` and `@PreAuthorize(SecurityConstants.HAS_ANY_ROLE)`.

7. **Integration test covers all scenarios**: Given a live MinIO + PostgreSQL container (via `BaseStorageIT`), the integration test covers: the returned `downloadUrl` successfully retrieves file content directly from MinIO, a `storage_access_events` row is persisted after each successful URL issuance, and soft-deleted files return `StorageObjectNotFoundException`.

## Tasks / Subtasks

- [x] Task 1: Create `SignDownloadResponse` record in `contract/` (AC: 4)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/contract/SignDownloadResponse.java`
  - [x] Implement as Java `record` (per project rule — all HTTP response DTOs are records)
  - [x] Fields:
    - `String key` — the storage key
    - `String downloadUrl` — pre-signed GET URL
    - `Instant expiresAt` — URL expiry timestamp
  - [x] No Jakarta validation annotations needed (response DTO, not request)

- [x] Task 2: Add `findByKeyAndDeletedAtIsNull` to `FileStorageObjectRepository` (AC: 1, 5)
  - [x] Modify `src/main/java/com/softropic/skillars/infrastructure/storage/repo/FileStorageObjectRepository.java`
  - [x] Add: `Optional<FileStorageObject> findByKeyAndDeletedAtIsNull(String key);`
  - [x] Spring Data derives the query automatically — `AND deleted_at IS NULL` filter enforced at DB level
  - [x] Do NOT modify the existing `findByKey(String key)` — it is used by `confirmUpload` for idempotency (returns even soft-deleted records, which is correct for that flow)

- [x] Task 3: Add `signDownload` method to `StorageSigningService` (AC: 1, 2, 3, 4, 5)
  - [x] Modify `src/main/java/com/softropic/skillars/infrastructure/storage/service/StorageSigningService.java`
  - [x] Add new constructor field (Lombok `@RequiredArgsConstructor` picks it up automatically):
    - `private final StorageAccessEventRepository storageAccessEventRepository;`
  - [x] Implement `public SignDownloadResponse signDownload(String key)`
  - [x] Required imports added

- [x] Task 4: Add `GET /sign/download/**` endpoint to `StorageResource` (AC: 6)
  - [x] Modify `src/main/java/com/softropic/skillars/infrastructure/storage/api/StorageResource.java`
  - [x] Added `@GetMapping("/sign/download/**")` with `@PreAuthorize` and `@Observed`
  - [x] Added `SignDownloadResponse` and `GetMapping` imports

- [x] Task 5: Write `StorageDownloadIT` integration test (AC: 7)
  - [x] Created `src/test/java/com/softropic/skillars/infrastructure/storage/service/StorageDownloadIT.java`
  - [x] Extends `BaseStorageIT`
  - [x] All required injections present
  - [x] `@BeforeEach` cleanup in correct FK order
  - [x] `signAndPut` and `confirmUpload` helpers copied
  - [x] All 4 test cases implemented and passing
  - [x] AssertJ used for all assertions

- [x] Task 6: Verify build passes (AC: all)
  - [x] `mvn test -Dtest=StorageDownloadIT` — 4/4 PASS
  - [x] Full regression — 38/38 PASS (also fixed pre-existing FK cleanup gap in `StorageSigningServiceIT`)

## Dev Notes

### Critical: Path Variable With Slashes (Same Pattern as confirmUpload)

The storage key `documents/42/2026/05/uuid.pdf` contains forward slashes. Using `@GetMapping("/sign/download/{key}")` with `@PathVariable String key` will NOT work — Spring captures only up to the first slash. The correct approach (identical to `confirmUpload`):

```java
@GetMapping("/sign/download/**")
public ResponseEntity<SignDownloadResponse> signDownload(HttpServletRequest request) {
    String fullPath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
    String key = fullPath.substring("/api/storage/sign/download/".length());
    return ResponseEntity.ok(storageSigningService.signDownload(key));
}
```

`HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE` is already imported in `StorageResource.java`.

### Critical: Use `presignTtlSeconds`, NOT `uploadTtlSeconds`

The architecture specifies `app.storage.presign-ttl-seconds` for DOWNLOAD URLs and `app.storage.upload-ttl-seconds` for upload URLs. In `StorageProperties`:
- `properties.getPresignTtlSeconds()` → for download URL TTL (default: 300s)
- `properties.getUploadTtlSeconds()` → for upload URL TTL (default: 600s), already used in `signUpload`

Using `getUploadTtlSeconds()` for the download endpoint would be a wrong property — use `getPresignTtlSeconds()`.

### Critical: `findByKeyAndDeletedAtIsNull` vs `findByKey`

Two separate repository methods serve different purposes:
- `findByKey(String key)` — returns ANY FSO regardless of `deletedAt`. Used by `confirmUpload` for idempotency (a soft-deleted confirmed file should still return idempotently). **Do NOT change this method.**
- `findByKeyAndDeletedAtIsNull(String key)` — returns FSO only if NOT soft-deleted. Used by `signDownload`. Spring Data derives: `WHERE key = ? AND deleted_at IS NULL`.

### Transaction Boundary: Access Event Inside, Presigner Outside

The architecture rule "S3 client calls must be OUTSIDE @Transactional" applies here. The `S3Presigner` is an S3 SDK component. Following the existing `signUpload`/`confirmUpload` pattern:

1. DB read (Spring Data auto-tx): `findByKeyAndDeletedAtIsNull`
2. DB write (explicit tx via `TransactionTemplate`): `storageAccessEventRepository.save(...)`
3. S3 SDK call OUTSIDE tx: `s3Presigner.presignGetObject(...)`
4. Return response

The `transactionTemplate` bean is already injected in `StorageSigningService` from Story 2.3. No additional wiring needed.

Note: `s3Presigner.presignGetObject()` is a LOCAL cryptographic signing operation (no network call), but we still keep it outside `@Transactional` to follow the architecture rule consistently.

### StorageAccessEvent Builder Pattern

`StorageAccessEvent` uses `@SuperBuilder` (same as `FileStorageObject`). The `accessedAt` field is set automatically by `@PrePersist` — do NOT set it manually. The builder fields to populate:

```java
StorageAccessEvent event = StorageAccessEvent.builder()
    .key(key)                    // String — the storage key
    .tenantId(fso.getTenantId()) // String — from the FSO record
    .sizeBytes(fso.getSizeBytes()) // long — cast to Long if needed (entity field is Long)
    .build();
```

`id` is set by `BaseEntity`'s `@Tsid` — do NOT set it manually (same as `FileStorageObject`).

### AWS SDK Presigner Imports for GET

The `signUpload` method uses `PutObjectPresignRequest` and `PresignedPutObjectRequest`. For the download endpoint, use the GET variants:

```java
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
```

Full presign call:
```java
PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(
    GetObjectPresignRequest.builder()
        .signatureDuration(Duration.ofSeconds(properties.getPresignTtlSeconds()))
        .getObjectRequest(r -> r
            .bucket(properties.getBucket())
            .key(key))
        .build());
return new SignDownloadResponse(key, presigned.url().toString(), presigned.expiration());
```

`S3Presigner` is already configured with `pathStyleAccessEnabled(true)` for MinIO — no additional setup needed.

### Soft-Delete Test — Setting deletedAt on FSO

`FileStorageObject` is a `@SuperBuilder` entity with a `deletedAt` field. Since it has no setter (Lombok `@Getter` only), the test must use Instancio or builder to set the field. The simplest approach for the soft-delete test:

Use a JPQL update query in the test itself, or modify the FSO entity in-flight using Spring's `@Modifying` query via a test-specific repository method. Simplest approach:

```java
// After confirming the upload, directly update via JPQL in the test:
// Option 1: Add a test helper JPQL update in a @Transactional test method:
@Autowired
private jakarta.persistence.EntityManager entityManager;

// Then in the test:
// entityManager.createQuery("UPDATE FileStorageObject f SET f.deletedAt = :now WHERE f.key = :key")
//     .setParameter("now", Instant.now())
//     .setParameter("key", key)
//     .executeUpdate();
```

Or, since `FileStorageObject` uses `@SuperBuilder`, create a new instance via builder with `deletedAt` set and save it:
- However, this won't update an existing record. Use JPQL.

Recommendation: Inject `EntityManager` and use an `@Transactional` helper method, or alternatively add a `@Modifying @Query` to `FileStorageObjectRepository` for tests only. Simplest clean approach: use JPQL via `EntityManager`:

```java
@Autowired
private jakarta.persistence.EntityManager em;

// In a @Transactional test-helper method or directly:
@org.springframework.transaction.annotation.Transactional
void softDelete(String key) {
    em.createQuery("UPDATE FileStorageObject f SET f.deletedAt = :now WHERE f.key = :key")
        .setParameter("now", java.time.Instant.now())
        .setParameter("key", key)
        .executeUpdate();
}
```

### @RequiredArgsConstructor — Adding New Field

`StorageSigningService` uses `@RequiredArgsConstructor` (Lombok). Simply add `private final StorageAccessEventRepository storageAccessEventRepository;` as a field — the constructor is auto-generated. Field declaration order determines constructor parameter order. Spring autowires by type, so order in the constructor doesn't matter for wiring.

### Integration Test — signDownload Returns Usable URL

After the full upload flow (signAndPut → confirmUpload), call `signDownload(key)` and verify the URL works:

```java
SignDownloadResponse downloadResponse = storageSigningService.signDownload(confirmedKey);

// Verify URL is usable
ResponseEntity<byte[]> getResponse = restTemplate.getForEntity(
    URI.create(downloadResponse.downloadUrl()), byte[].class);
assertThat(getResponse.getStatusCode().is2xxSuccessful()).isTrue();
assertThat(getResponse.getBody()).isEqualTo(originalContent);
```

This follows the same `restTemplate` pattern from `StorageSigningServiceIT.signUpload_uploadUrlIsUsable()`.

### What Is NOT in This Story

- `DELETE /api/storage/{key}` endpoint (Story 3.2)
- `DeletionSchedulerService` and physical deletion logic (Story 3.2)
- `V14__storage_physical_deletion.sql` migration with `physical_deleted_at` column (Story 3.2)
- Setting `deleted_at` on an FSO (soft-delete REST endpoint — Story 3.2)
- `OutboxPollerScheduler` for REPLICATE jobs (Epic 4 / Story 4.2)

### Project Structure Notes

New and modified files:

```
src/main/java/com/softropic/skillars/infrastructure/storage/
├── api/
│   └── StorageResource.java              ← MODIFY: add GET /sign/download/** endpoint
├── contract/
│   └── SignDownloadResponse.java         ← NEW: record (key, downloadUrl, expiresAt)
├── repo/
│   └── FileStorageObjectRepository.java  ← MODIFY: add findByKeyAndDeletedAtIsNull
└── service/
    └── StorageSigningService.java        ← MODIFY: add signDownload + inject StorageAccessEventRepository

src/test/java/com/softropic/skillars/infrastructure/storage/service/
└── StorageDownloadIT.java                ← NEW (extends BaseStorageIT)
```

`StorageAccessEvent.java`, `StorageAccessEventRepository.java` — already exist; NO changes needed.

### References

- [Source: epics.md#Story 3.1] — Full BDD acceptance criteria, SignDownloadResponse shape
- [Source: architecture.md#Format Patterns] — `GET /api/storage/sign/download/{key} → 200 { "key": "...", "downloadUrl": "https://...", "expiresAt": "..." }`
- [Source: architecture.md#REST Endpoint Naming] — `GET /api/storage/sign/download/{key}`
- [Source: architecture.md#Data Flow — Download] — verify FSO → insert access event → presignGetObject → return
- [Source: architecture.md#Process Patterns] — S3 calls OUTSIDE @Transactional; DB writes inside TransactionTemplate
- [Source: architecture.md#Complete Project Directory Structure] — package layout
- [Source: project-context.md#Language-Specific Rules] — All HTTP DTOs are Java `record` types
- [Source: project-context.md#Framework-Specific Rules] — `@PreAuthorize` on every method; `@Observed` on resources
- [Source: project-context.md#Testing Rules] — `@SpringBootTest + @Testcontainers`; no mock DB; AssertJ
- [Source: StorageResource.java] — existing `confirmUpload` wildcard pattern; `HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE` already imported
- [Source: StorageSigningService.java] — existing `signUpload`/`confirmUpload` patterns; `S3Presigner`, `TransactionTemplate` already injected; `@RequiredArgsConstructor` pattern
- [Source: StorageProperties.java:28] — `presignTtlSeconds` field (getter: `getPresignTtlSeconds()`)
- [Source: StorageAccessEvent.java] — `@SuperBuilder`; fields: `key`, `tenantId`, `sizeBytes`, `accessedAt` (@PrePersist)
- [Source: StorageAccessEventRepository.java] — plain `JpaRepository<StorageAccessEvent, Long>` — no custom queries yet
- [Source: FileStorageObjectRepository.java] — existing `findByKey` and `sumSizeBytesByTenantId`; do NOT change `findByKey`
- [Source: StorageObjectNotFoundException.java:10-13] — single-arg `(String key)` constructor exists — use it for DB-not-found case
- [Source: StorageConfirmUploadIT.java] — `signAndPut` helper, `@BeforeEach` cleanup, `assertThatThrownBy` patterns
- [Source: StorageSigningServiceIT.java:61-76] — `restTemplate.exchange` for URL verification pattern
- [Source: 2-3-upload-confirmation-endpoint.md#Dev Agent Record] — 34 tests pass; clean build baseline

### Previous Story Intelligence (Story 2.3)

- **`@RequiredArgsConstructor` pattern**: Adding `private final StorageAccessEventRepository storageAccessEventRepository;` to `StorageSigningService` auto-includes it in the constructor. No manual constructor changes.
- **No `@Transactional` on the service method**: `signDownload` must NOT be annotated `@Transactional`. Use `transactionTemplate.execute()` for the DB write, identical to `confirmUpload`.
- **MinIO path-style URLs**: `s3Presigner` is configured with `pathStyleAccessEnabled(true)`. The generated GET URL works directly with MinIO via `restTemplate.getForEntity()` in tests.
- **BaseStorageIT provides RestTemplate**: Inject with `@Autowired RestTemplate restTemplate` in the test class — no additional configuration.
- **FK cleanup order**: `OutboxReplicationJob` has FK to `FileStorageObject`. `StorageAccessEvent` has NO FK to either. Always: `storageAccessEventRepository.deleteAll()` → `outboxReplicationJobRepository.deleteAll()` → `fileStorageObjectRepository.deleteAll()`.
- **Test count baseline**: 34 tests passing as of Story 2.3. Regression suite must continue passing.
- **`signAndPut` helper** in `StorageConfirmUploadIT` — copy it into `StorageDownloadIT` to reuse the full upload preparation pattern.

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- Pre-existing FK cleanup gap in `StorageSigningServiceIT` caused failures when run after `StorageConfirmUploadIT` in combined regression. Fixed by adding `storageAccessEventRepository.deleteAll()` + `outboxReplicationJobRepository.deleteAll()` to its `@BeforeEach`.
- `@Transactional` annotation on non-test helper methods in `@SpringBootTest` classes does not create Spring proxies; used `TransactionTemplate` for the `softDelete` JPQL helper in `StorageDownloadIT`.

### Completion Notes List

- Implemented full pre-signed download URL flow: FSO existence check → egress event persisted via `TransactionTemplate` → `S3Presigner.presignGetObject()` outside transaction → `SignDownloadResponse` returned.
- `findByKeyAndDeletedAtIsNull` Spring Data method derived automatically — enforces soft-delete at DB level.
- `/**` wildcard endpoint pattern used (same as `confirmUpload`) to support slash-containing storage keys.
- `presignTtlSeconds` (default 300s) used for download TTL per architecture spec.
- 4 integration tests covering: usable URL retrieval, access event persistence, non-existent key 404, soft-deleted file 404.
- Full regression: 38 tests pass (34 baseline + 4 new).

### File List

- `src/main/java/com/softropic/skillars/infrastructure/storage/contract/SignDownloadResponse.java` (NEW)
- `src/main/java/com/softropic/skillars/infrastructure/storage/repo/FileStorageObjectRepository.java` (MODIFIED)
- `src/main/java/com/softropic/skillars/infrastructure/storage/service/StorageSigningService.java` (MODIFIED)
- `src/main/java/com/softropic/skillars/infrastructure/storage/api/StorageResource.java` (MODIFIED)
- `src/test/java/com/softropic/skillars/infrastructure/storage/service/StorageDownloadIT.java` (NEW)
- `src/test/java/com/softropic/skillars/infrastructure/storage/service/StorageSigningServiceIT.java` (MODIFIED — fixed FK cleanup gap)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (MODIFIED)

### Change Log

- 2026-05-26: Story 3.1 implemented — pre-signed download URL endpoint with egress tracking. 4 new tests, 38 total passing.
