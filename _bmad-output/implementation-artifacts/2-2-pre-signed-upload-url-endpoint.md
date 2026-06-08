# Story 2.2: Pre-signed Upload URL Endpoint

Status: review

## Story

As a client application,
I want to request a pre-signed S3 upload URL with quota validation,
so that I can upload files directly to S3 without routing binary data through the backend.

## Acceptance Criteria

1. **Validation chain runs at sign-time**: Given a valid `SignUploadRequest` (entity, entityId, contentType, extension, fileSizeBytes, tenantId, checksum [optional], tags [optional]), when `POST /api/storage/sign/upload` is called by an authenticated user, then `StorageSigningService` maps the request to a `ValidationRequest` and passes it through `ValidationChain` before any quota check or URL generation.

2. **Quota enforcement rejects over-limit uploads**: Given the existing confirmed storage for a tenant (`SUM(size_bytes) WHERE tenant_id = ?`), when `(currentUsage + fileSizeBytes) > app.storage.quota.default-bytes`, then `QuotaExceededException` is thrown (HTTP 429) before a URL is ever issued.

3. **Key generation produces the correct pattern**: Given validation and quota checks pass, when `StorageKeyGenerator.generate(entity, entityId, extension)` is called, then the returned key matches `{entity}/{entityId}/{yyyy}/{mm}/{uuid}.{ext}` where `yyyy/mm` reflects the current UTC date and `uuid` is a random UUID guaranteeing uniqueness.

4. **Pre-signed PUT URL is generated and returned**: Given the key is generated, when `S3Presigner.presignPutObject` is called with TTL `app.storage.upload-ttl-seconds`, then a `SignUploadResponse` is returned with `key`, `uploadUrl`, and `expiresAt` (ISO 8601 from the presigner's expiration).

5. **Jakarta validation rejects incomplete requests**: Given a `SignUploadRequest` missing any required field (`entity`, `entityId`, `contentType`, `extension`, `fileSizeBytes`, `tenantId`), when `POST /api/storage/sign/upload` is called, then HTTP 400 is returned before reaching service logic.

6. **Endpoint is secured and observed**: When any request reaches `StorageResource.signUpload`, then `@PreAuthorize(SecurityConstants.HAS_ANY_ROLE)` guards the method and `@Observed(name = "storage.sign.upload")` is present on the method.

7. **Integration test verifies the full sign-upload flow**: Given a live MinIO container (via `BaseStorageIT`), when the sign-upload endpoint is called with a valid request, then the returned `uploadUrl` accepts a real HTTP PUT directly to MinIO and the response contains the correct key structure. When called with a request that would exceed quota (based on mocked `file_storage_objects` data), then HTTP 429 is returned.

## Tasks / Subtasks

- [x] Task 1: Create `SignUploadRequest` record in `contract/` (AC: 1, 5)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/contract/SignUploadRequest.java`
  - [x] Implement as a Java `record` (per project rule — all HTTP request/response DTOs are records)
  - [x] Fields with Jakarta validation:
    - `@NotBlank String entity` — storage entity category (e.g., "documents", "avatars")
    - `@NotBlank String entityId` — entity instance ID (e.g., user ID, document ID)
    - `@NotBlank String contentType` — declared MIME type
    - `@NotBlank String extension` — declared file extension (without dot, e.g. "pdf")
    - `@Positive long fileSizeBytes` — declared file size in bytes
    - `@NotBlank String tenantId` — tenant identifier for quota scoping
    - `String checksum` — optional SHA-256 hex string; null/blank is accepted at sign-time
    - `Map<String, String> tags` — optional custom metadata (JSONB tags); may be null

- [x] Task 2: Create `SignUploadResponse` record in `contract/` (AC: 4)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/contract/SignUploadResponse.java`
  - [x] Fields:
    - `String key` — the generated storage key (e.g., `documents/42/2026/05/{uuid}.pdf`)
    - `String uploadUrl` — the pre-signed PUT URL for direct S3 upload
    - `Instant expiresAt` — URL expiration time (Jackson serializes `Instant` as ISO 8601 epoch string; Jackson is already configured project-wide)

- [x] Task 3: Add quota query to `FileStorageObjectRepository` (AC: 2)
  - [x] Modify `src/main/java/com/softropic/skillars/infrastructure/storage/repo/FileStorageObjectRepository.java`
  - [x] Add JPQL query method:
    ```java
    @Query("SELECT COALESCE(SUM(f.sizeBytes), 0) FROM FileStorageObject f WHERE f.tenantId = :tenantId")
    long sumSizeBytesByTenantId(@Param("tenantId") String tenantId);
    ```
  - [x] Add import: `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`
  - [x] Note: Does NOT filter on `deletedAt` — follows epics spec exactly; soft-deleted files still count toward quota until physical deletion

- [x] Task 4: Create `StorageSigningService.java` in `service/` (AC: 1, 2, 3, 4)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/service/StorageSigningService.java`
  - [x] Annotate `@Service @RequiredArgsConstructor @Slf4j`
  - [x] Constructor-inject:
    - `ValidationChain validationChain`
    - `FileStorageObjectRepository fileStorageObjectRepository`
    - `StorageKeyGenerator storageKeyGenerator`
    - `S3Presigner s3Presigner`
    - `StorageProperties properties`
  - [x] Implement `public SignUploadResponse signUpload(SignUploadRequest request)`:
    1. Build `ValidationRequest` from request fields: `ValidationRequest.builder().originalFilename(request.entity() + "." + request.extension()).contentType(request.contentType()).extension(request.extension()).fileSizeBytes(request.fileSizeBytes()).checksum(request.checksum()).tenantId(request.tenantId()).tags(request.tags()).build()`
    2. Call `validationChain.validate(validationRequest)` — `StorageValidationException` propagates if fails
    3. Quota check: `long currentUsage = fileStorageObjectRepository.sumSizeBytesByTenantId(request.tenantId())`
    4. If `currentUsage + request.fileSizeBytes() > properties.getQuota().getDefaultBytes()`, throw `new QuotaExceededException(request.tenantId(), currentUsage, request.fileSizeBytes())`
    5. Generate key: `String key = storageKeyGenerator.generate(request.entity(), request.entityId(), request.extension())`
    6. Generate pre-signed URL:
       ```java
       PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(
           PresignPutObjectRequest.builder()
               .signatureDuration(Duration.ofSeconds(properties.getUploadTtlSeconds()))
               .putObjectRequest(r -> r
                   .bucket(properties.getBucket())
                   .key(key)
                   .contentType(request.contentType()))
               .build());
       ```
    7. Return `new SignUploadResponse(key, presigned.url().toString(), presigned.expiration())`
  - [x] Imports needed:
    - `software.amazon.awssdk.services.s3.presigner.S3Presigner`
    - `software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest`
    - `software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest` (the builder class)
    - `java.time.Duration`

- [x] Task 5: Add `signUpload` endpoint to `StorageResource` (AC: 5, 6)
  - [x] Modify `src/main/java/com/softropic/skillars/infrastructure/storage/api/StorageResource.java`
  - [x] Add constructor injection of `StorageSigningService` (replace empty constructor with `@RequiredArgsConstructor`)
  - [x] Add endpoint method:
    ```java
    @PostMapping("/sign/upload")
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    @Observed(name = "storage.sign.upload")
    public ResponseEntity<SignUploadResponse> signUpload(
        @RequestBody @Valid SignUploadRequest request) {
      return ResponseEntity.ok(storageSigningService.signUpload(request));
    }
    ```
  - [x] Add imports: `SecurityConstants`, `SignUploadRequest`, `SignUploadResponse`, `Valid`, `RequestBody`, `PostMapping`, `ResponseEntity`, `PreAuthorize`

- [x] Task 6: Write `StorageSigningServiceIT` integration test (AC: 7)
  - [x] Create `src/test/java/com/softropic/skillars/infrastructure/storage/service/StorageSigningServiceIT.java`
  - [x] Annotate to extend `BaseStorageIT`
  - [x] Inject `@Autowired StorageSigningService storageSigningService`
  - [x] Inject `@Autowired FileStorageObjectRepository fileStorageObjectRepository`
  - [x] Inject `@Autowired StorageProperties storageProperties`
  - [x] Test cases:
    - **signUpload_validRequest_returnsUrlAndKey**: Build a `SignUploadRequest` with valid fields, call `signUpload`, assert response key matches pattern `{entity}/{entityId}/{yyyy}/{mm}/{uuid}.{ext}`, `uploadUrl` is not blank, `expiresAt` is after `Instant.now()`
    - **signUpload_uploadUrlIsUsable**: Call `signUpload`, then use a `RestTemplate` (or `OkHttpClient`) to HTTP PUT a test file to the `uploadUrl` and assert HTTP 200 is returned from MinIO
    - **signUpload_quotaExceeded_throws**: Use `Instancio` to generate and persist a `FileStorageObject` with `sizeBytes = storageProperties.getQuota().getDefaultBytes()`. Then call `signUpload` with `fileSizeBytes = 1`. Assert `QuotaExceededException` is thrown.
    - **signUpload_invalidMimeType_throws**: Call `signUpload` with `contentType = "application/x-executable"`. Assert `StorageValidationException` is thrown (chain is called).
  - [x] Use `AssertJ` (`assertThat`, `assertThatThrownBy`) for all assertions
  - [x] Use `Instancio` for generating test entity data where needed
  - [x] Clean up `file_storage_objects` between quota tests with `@BeforeEach fileStorageObjectRepository.deleteAll()`

- [x] Task 7: Verify build passes (AC: all)
  - [x] Run `mvn test -Dtest=StorageSigningServiceIT` from project root and confirm BUILD SUCCESS
  - [x] Run regression: `mvn test -Dtest=ValidationChainTest,StorageKeyGeneratorTest,S3StorageServiceIT,StorageSigningServiceIT` — all must pass


### Review Findings

- [ ] [Review][Patch] Tenant Indexing Requirement — The new SUM query on tenantId requires a composite or single-column index on tenantId in FileStorageObject for performance.
- [x] [Review][Defer] Potential Path Traversal [S3StorageService.java] — Sanitized keys allow / and _ which could facilitate unintended directory structure if input is not constrained; verify input entity/entityId. — deferred, pre-existing
- [x] [Review][Defer] Unbounded Thread Pool [StorageConfig.java] — storageUploadExecutor uses Executors.newFixedThreadPool(processors). This is suitable for CPU-bound tasks but might need bounds/backpressure for I/O. — deferred, pre-existing

### Package Location — New and Modified Files

```
src/main/java/com/softropic/skillars/infrastructure/storage/
├── api/
│   └── StorageResource.java               ← MODIFY: add signUpload method + inject StorageSigningService
├── contract/
│   ├── SignUploadRequest.java             ← NEW (record with Jakarta validation)
│   └── SignUploadResponse.java            ← NEW (record: key, uploadUrl, expiresAt)
├── repo/
│   └── FileStorageObjectRepository.java   ← MODIFY: add sumSizeBytesByTenantId JPQL query
└── service/
    └── StorageSigningService.java         ← NEW (@Service)

src/test/java/com/softropic/skillars/infrastructure/storage/service/
└── StorageSigningServiceIT.java           ← NEW (extends BaseStorageIT)
```

### Why `entity` and `entityId` Are Required in `SignUploadRequest`

`StorageKeyGenerator.generate(entity, entityId, extension)` requires all three parameters to produce the key pattern `{entity}/{entityId}/{yyyy}/{mm}/{uuid}.{ext}` (e.g., `documents/42/2026/05/uuid.pdf`). Without `entity` and `entityId`, the service cannot generate the correct key. These are application-semantic fields — the client decides the entity category (e.g., `"documents"`) and the associated entity ID (e.g., `"42"` for document ID 42). Both must be `@NotBlank`.

### `SignUploadRequest` Is a Record — Not a Mutable Class

Unlike `ValidationRequest` (an internal chain-processing object), `SignUploadRequest` is an HTTP DTO bound via `@RequestBody`. The project rule "All request/response DTOs must be `record` types" applies here. Jakarta validation annotations work on record components — `@NotBlank String entity` in the record component list is valid.

### `originalFilename` Field in `ValidationRequest`

When mapping `SignUploadRequest` → `ValidationRequest`, `originalFilename` is not present in `SignUploadRequest` (the client is uploading, not submitting a filename yet). Set it to `request.entity() + "." + request.extension()` as a synthetic value. This allows `FilenameSanitizationStep` to run without a null — it will sanitize the constructed string. This is the correct approach.

### Pre-signed URL API — Correct SDK Classes

The AWS SDK v2 presigning API has confusingly similar class names. Use these exact types:

```java
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;  // returned value
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;    // builder input
import software.amazon.awssdk.services.s3.model.PutObjectRequest;                    // inner builder
```

The builder pattern:
```java
PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(
    PutObjectPresignRequest.builder()
        .signatureDuration(Duration.ofSeconds(properties.getUploadTtlSeconds()))
        .putObjectRequest(r -> r
            .bucket(properties.getBucket())
            .key(key)
            .contentType(request.contentType()))
        .build());

String uploadUrl = presigned.url().toString();
Instant expiresAt = presigned.expiration();  // use presigner's expiration, not computed manually
```

### `S3Presigner` Is Already a Spring Bean

`StorageConfig` wires `S3Presigner` as a `@ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3")` bean (see `StorageConfig.java:69-76`). Constructor-inject it directly into `StorageSigningService` — do not create a new `S3Presigner` instance in the service.

### `ValidationChain` Is Already a Spring Bean

`ValidationChain` is `@Service`-annotated and all 5 `ValidationStep` implementations are `@Component`-annotated with `@Order` values. They are already fully wired by Spring from Story 2.1. Simply inject `ValidationChain validationChain` — all steps (including `FilenameSanitizationStep` at `@Order(1)`) are automatically included.

### Transaction Boundary — Sign-Upload Has No Writes

`StorageSigningService.signUpload` is NOT `@Transactional`. There are no DB writes in this method — only a `SUM` read query and a pre-signed URL generation. The `JpaRepository` query runs within Spring Data's default transactional behavior (read-only, auto-commit). Do NOT add `@Transactional` to `signUpload` — this is intentional and consistent with the architectural rule: S3 calls must be outside `@Transactional`, and wrapping a read + S3 call together is unnecessary.

### Quota Check — COALESCE for Empty Tenants

`SUM()` over an empty result set returns `NULL` in SQL. The JPQL `COALESCE(SUM(...), 0)` pattern ensures the query returns `0` instead of `null` for new tenants who have never uploaded. Without `COALESCE`, the `long sumSizeBytesByTenantId(...)` method would throw a `NullPointerException` when unboxing the null `Long` to `long`. Always use `COALESCE`.

### Security Expression — `HAS_ANY_ROLE`

```java
@PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
```

`SecurityConstants.HAS_ANY_ROLE = "hasRole('ROLE_ADMIN') or hasRole('ROLE_LTD_ADMIN') or hasRole('ROLE_USER')"` — this is the correct value for endpoints accessible by any authenticated user. The constant is at `com.softropic.skillars.infrastructure.security.SecurityConstants`.

### `StorageResource` — From Empty Class to `@RequiredArgsConstructor`

Currently `StorageResource.java` is a skeleton with no injected fields:
```java
// Current state — no constructor
@Slf4j @Observed(name = "storage") @RestController @RequestMapping("/api/storage")
public class StorageResource {}
```

Add `@RequiredArgsConstructor` (from Lombok) and declare the `StorageSigningService` field as `final`:
```java
@Slf4j
@Observed(name = "storage")
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
public class StorageResource {
    private final StorageSigningService storageSigningService;
    ...
}
```

### `StorageResource` — Class-Level vs Method-Level `@Observed`

The class already has `@Observed(name = "storage")`. The AC requires `@Observed(name = "storage.sign.upload")` on the method. Both annotations are correct and intentional — the class-level provides a general observation context, while the method-level provides granular naming for this specific operation. Micrometer will use the method-level annotation for instrumentation of this specific method.

### BaseEntity Uses TSID (Not UUID) for `id`

`FileStorageObject` extends `BaseEntity` which uses `@Tsid` for its `id` field (a `Long`). When creating `FileStorageObject` test data for quota tests, use `Instancio` with `FileStorageObject.builder()` — do not set `id` manually (TSID is auto-generated on persist).

### Integration Test — RestTemplate for Presigned URL Verification

`BaseStorageIT` provides a `RestTemplate` bean via its inner `StorageTestConfig`. Inject it with `@Autowired RestTemplate restTemplate`. To verify the presigned URL accepts a PUT:

```java
HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.APPLICATION_PDF);
HttpEntity<byte[]> entity = new HttpEntity<>(new byte[]{1, 2, 3}, headers);
ResponseEntity<Void> putResponse = restTemplate.exchange(
    URI.create(signUploadResponse.uploadUrl()),
    HttpMethod.PUT,
    entity,
    Void.class);
assertThat(putResponse.getStatusCode().is2xxSuccessful()).isTrue();
```

Note: MinIO returns `200 OK` for a successful presigned PUT. The `restTemplate` in the test config is a plain `RestTemplate` without error-throwing behavior for 4xx/5xx — check the status code explicitly.

### Previous Story Learnings (Story 2.1)

- **Pre-existing bugs were fixed**: `StorageProperties.S3` no longer has duplicate `requestTimeoutMs`/`connectionTimeoutMs` fields (cleaned up in Story 2.1). No duplicate field issues expected.
- **`@Recover` placement**: The `@Recover` annotation in `S3StorageService` was fixed — a stray `String sanitizedKey = ...` statement was inside the `@Recover` method body. This has been resolved. No regression expected.
- **Build is clean**: All 37 tests pass on the main branch as of Story 2.1 merge.
- **`StorageProperties` has `@Validated`**: The class-level `@Validated` annotation means all `@NotBlank` and `@Min` annotations on fields are enforced at startup. Adding new config fields must not violate these constraints.

### What Is NOT in This Story

- `POST /api/storage/confirm/{key}` endpoint (Story 2.3)
- Any S3 file verification via `headObject` at sign-time (Story 2.3)
- `file_storage_objects` record insertion (Story 2.3)
- `outbox_replication_jobs` insertion (Story 2.3)
- `StorageObjectConfirmedEvent` publishing (Story 2.3)
- Pre-signed download URL (Story 3.1)
- `StorageAccessEvent` egress tracking (Story 3.1)

### References

- [Source: epics.md#Story 2.2] — Full BDD acceptance criteria and response shape
- [Source: architecture.md#REST Endpoint Naming] — `POST /api/storage/sign/upload`, `app.storage.*` namespace
- [Source: architecture.md#Format Patterns] — `SignUploadResponse` shape: `{ key, uploadUrl, expiresAt }`
- [Source: architecture.md#Quota Enforcement] — `SUM(size_bytes) WHERE tenant_id = ?` quota check
- [Source: architecture.md#Transaction Boundary] — S3 calls outside `@Transactional`
- [Source: architecture.md#Complete Project Directory Structure] — `StorageSigningService.java` in `service/`
- [Source: project-context.md#Language-Specific Rules] — All HTTP DTOs are Java `record` types
- [Source: project-context.md#Framework-Specific Rules] — `@PreAuthorize` on every resource method; `@Observed` on resources
- [Source: project-context.md#Testing Rules] — `@SpringBootTest + @Testcontainers`; no mock DB; AssertJ; Instancio
- [Source: StorageConfig.java:69-76] — `S3Presigner` bean already wired
- [Source: StorageConfig.java:84-91] — `StorageService` / `StorageSigningService` wiring pattern
- [Source: SecurityConstants.java:30] — `HAS_ANY_ROLE` expression
- [Source: ApiAdvice.java:376-380] — `QuotaExceededException` handler already registered → HTTP 429
- [Source: BaseStorageIT.java] — Test base class; inner `StorageTestConfig` provides `RestTemplate` bean
- [Source: StorageKeyGenerator.java] — `generate(entity, entityId, extension)` signature and key pattern
- [Source: ValidationChain.java] — `validate(ValidationRequest)` — already `@Service` bean
- [Source: 2-1-pre-storage-validation-chain.md#Dev Agent Record] — Pre-existing bugs fixed; regression suite passing

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- **Pre-existing credential bug**: `StorageConfig` used `DefaultCredentialsProvider.create()` which failed in tests because MinIO credentials (registered as `app.storage.s3.access-key` / `app.storage.s3.secret-key` by `MinioContainerConfig`) were never read. Fixed by adding `accessKey`/`secretKey` fields to `StorageProperties.S3` and a `credentialsProvider()` helper that uses `StaticCredentialsProvider` when both fields are non-blank.
- **Pre-existing key sanitization bug**: `S3StorageService.put()` used regex `[^a-zA-Z0-9.-]` which replaced `/` (path separator) with `_`, causing `exists(key)` to return false for any key containing `/`. Fixed by adding `/` and `_` to the allowed set: `[^a-zA-Z0-9./_-]`.
- **Pre-existing presigner path-style bug**: `S3Presigner` was built without `serviceConfiguration`, so it generated virtual-hosted style URLs (`bucket.hostname:port/key`) instead of path-style (`hostname:port/bucket/key`), making presigned URLs unresolvable in test. Fixed by adding `.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(...).build())` to the presigner builder.

### Completion Notes List

- Task 1: `SignUploadRequest` Java record created with Jakarta validation (`@NotBlank`, `@Positive`) on all required fields; `checksum` and `tags` are optional.
- Task 2: `SignUploadResponse` Java record created with `key`, `uploadUrl`, `expiresAt` fields.
- Task 3: `sumSizeBytesByTenantId` JPQL query added to `FileStorageObjectRepository` using `COALESCE(SUM(...), 0)` to safely handle new tenants.
- Task 4: `StorageSigningService` implemented: maps request → `ValidationRequest`, runs chain, checks quota, generates key, generates pre-signed PUT URL via `S3Presigner`, returns `SignUploadResponse`. Not `@Transactional` by design.
- Task 5: `StorageResource` wired with `@RequiredArgsConstructor` + `StorageSigningService`; `POST /api/storage/sign/upload` endpoint added with `@PreAuthorize(SecurityConstants.HAS_ANY_ROLE)` and `@Observed(name = "storage.sign.upload")`.
- Task 6: `StorageSigningServiceIT` written with 4 test cases covering happy path, real presigned PUT, quota rejection, and mime-type validation rejection. All 4 pass against live MinIO + Postgres containers.
- Task 7: Regression suite (29 tests) passes: `ValidationChainTest` (21) + `StorageKeyGeneratorTest` (3) + `S3StorageServiceIT` (1) + `StorageSigningServiceIT` (4) = 29/29 BUILD SUCCESS.
- Also fixed 3 pre-existing infrastructure bugs in `StorageConfig` and `S3StorageService` that were blocking all storage integration tests.

### File List

- `src/main/java/com/softropic/skillars/infrastructure/storage/contract/SignUploadRequest.java` (NEW)
- `src/main/java/com/softropic/skillars/infrastructure/storage/contract/SignUploadResponse.java` (NEW)
- `src/main/java/com/softropic/skillars/infrastructure/storage/service/StorageSigningService.java` (NEW)
- `src/test/java/com/softropic/skillars/infrastructure/storage/service/StorageSigningServiceIT.java` (NEW)
- `src/main/java/com/softropic/skillars/infrastructure/storage/repo/FileStorageObjectRepository.java` (MODIFIED: added sumSizeBytesByTenantId)
- `src/main/java/com/softropic/skillars/infrastructure/storage/api/StorageResource.java` (MODIFIED: added signUpload endpoint)
- `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageProperties.java` (MODIFIED: added accessKey/secretKey to S3 inner class)
- `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageConfig.java` (MODIFIED: credentialsProvider helper + presigner path-style fix)
- `src/main/java/com/softropic/skillars/infrastructure/storage/service/S3StorageService.java` (MODIFIED: key sanitization regex fix)

## Change Log

- 2026-05-26: Story 2.2 implementation complete. Added pre-signed upload URL endpoint (`POST /api/storage/sign/upload`) with validation chain, quota enforcement, S3 presigner integration, and 4 integration tests. Also fixed 3 pre-existing StorageConfig/S3StorageService infrastructure bugs that were blocking all storage integration tests.
