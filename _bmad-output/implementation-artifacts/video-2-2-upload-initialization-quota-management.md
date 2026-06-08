# Story Video-2.2: Upload Initialization & Quota Management

Status: review

## Story

As a consuming application,
I want to call `VideoService.initializeUpload()` to get signed Bunny.net upload credentials with quota safety and rate limiting enforced,
so that end users can upload videos directly to Bunny.net without routing binary payloads through the backend.

## Acceptance Criteria

**AC-1: Full initialization flow — happy path**
- Given `InitializeUploadRequest(ownerId, fileName, fileSizeBytes, mimeType)`
- When `VideoService.initializeUpload(request)` is called
- Then `VideoValidationChain.validate()` runs FIRST (before any quota or provider call)
- And `QuotaProvider.check(ownerId, fileSizeBytes)` is called after validation passes
- And `QuotaProvider.reserve(ownerId, fileSizeBytes)` is called after check passes; returned `reservationHandle` stored on `UploadSession`
- And `Video` record is created: `operationalState=UPLOADING`, `accessState=ACTIVE`, `visibility=PRIVATE`, `provider="bunny"`, `title=fileName`, `ownerId` set
- And `UploadSession` record is created: `status=PENDING`, `expiresAt=now+sessionTtlMinutes`, `reservedBytes=fileSizeBytes`, `reservationHandle` set
- And `VideoProviderAdapter.initializeUpload(fileName, fileSizeBytes)` is called (AFTER DB records committed)
- And `Video.providerAssetId` and `UploadSession.providerUploadId` are updated with `credentials.providerUploadId()`
- And returns `InitializeUploadResponse(videoId, uploadSessionId, providerUploadId, signedUploadUrl, expiresAt)`
- And `signedUploadUrl` is the Bunny.net TUS upload URL — no video bytes flow through the backend (FR-3)

**AC-2: Validation failure blocks quota and provider calls**
- Given `VideoValidationChain.validate()` throws `VideoValidationException`
- Then HTTP 422 is returned before any quota check or provider call
- (VideoValidationChain from Story 2.1 is called as-is; no changes to chain)

**AC-3: Quota check failure blocks reservation and provider calls**
- Given `QuotaProvider.check(ownerId, fileSizeBytes)` returns `false`
- Then `QuotaExceededException` is thrown (HTTP 429) before reserve or provider call

**AC-4: Rate limiting per ownerId blocks before validation**
- Given a caller has exceeded `app.video.upload.rate-limit.requests-per-minute` for a given `ownerId`
- When `VideoService.initializeUpload()` is called
- Then the request is rejected with HTTP 429 BEFORE validation or quota checks

**AC-5: Quota released on failure after reservation — pre-commit failure**
- Given `QuotaProvider.reserve()` succeeds but the DB insert fails (transaction rolls back)
- Then `QuotaProvider.release(reservationHandle)` is called in a `finally` block
- No stranded quota reservation remains

**AC-6: Quota released on failure after reservation — provider failure**
- Given `VideoProviderAdapter.initializeUpload()` throws `VideoProviderException` after DB records already committed
- Then `QuotaProvider.release(reservationHandle)` is called in the `finally` block
- And caller receives HTTP 502 with `VideoErrorCode.PROVIDER_ERROR`
- And orphaned `PENDING` UploadSession and `UPLOADING` Video records remain in DB (expiry scheduler handles them in Story 2.3)

**AC-7: NFR-1 performance — 99th-percentile latency < 500ms**
- Given an integration test that calls `initializeUpload()` 100 times with WireMock responding within 50ms
- Then 99% of calls complete within 500ms as measured by test execution timing

**AC-8: Integration test — full happy-path flow**
- Given an integration test extending `BaseVideoIT`
- When it exercises the full initialization flow with WireMock stubbing Bunny.net's create-video endpoint
- Then a `Video` and `UploadSession` are persisted with correct state and fields
- And `QuotaProvider` methods are called in order: `check` → `reserve`
- And the returned `signedUploadUrl` matches the WireMock-stubbed TUS URL

## Tasks / Subtasks

- [x] Task 1: Add DTO records to `platform.video.contract` (AC: 1, 8)
  - [x] Create `InitializeUploadRequest.java` record: `String ownerId`, `String fileName`, `long fileSizeBytes`, `String mimeType`
  - [x] Create `InitializeUploadResponse.java` record: `UUID videoId`, `UUID uploadSessionId`, `String providerUploadId`, `String signedUploadUrl`, `Instant expiresAt`
  - [x] Place both in `com.softropic.skillars.platform.video.contract`

- [x] Task 2: Add `@Setter` to `Video` and `UploadSession` entities (AC: 1)
  - [x] Add `@Setter` annotation to `Video.java` (class level) — `id`, `createdAt`, `updatedAt` stay managed by `@PrePersist`/`@PreUpdate`; `@Setter` on the class generates setters for ALL fields but JPA lifecycle callbacks still fire correctly
  - [x] Add `@Setter` annotation to `UploadSession.java` (class level) — same pattern
  - [x] These are UPDATE files; do NOT remove existing `@Getter @NoArgsConstructor`

- [x] Task 3: Add overloaded constructor to `QuotaExceededException` for rate limiting (AC: 4)
  - [x] Add constructor: `public QuotaExceededException(String ownerId, String reason)` that calls `super("Upload rate limit exceeded", Map.of("ownerId", ownerId, "reason", reason), VideoErrorCode.QUOTA_EXCEEDED)`
  - [x] Do NOT modify `VideoApiAdvice` — the existing `QuotaExceededException → 429` handler covers both cases

- [x] Task 4: Implement `VideoService.initializeUpload()` (AC: 1–8)
  - [x] Inject `VideoValidationChain`, `QuotaProvider`, `VideoProviderAdapter`, `VideoRepository`, `UploadSessionRepository`, `VideoProperties`, `TransactionTemplate`, `RateLimitingService`
  - [x] Add `@Slf4j` to the class (MDC logging)
  - [x] Implement rate limit check using `RateLimitingService.tryConsume(ownerId, "video.upload.init", capacity, 1, TimeUnit.MINUTES)` — capacity from `properties.getUpload().getRateLimit().getRequestsPerMinute()`; throw `QuotaExceededException(ownerId, "rate limit exceeded")` if rejected
  - [x] Call `VideoValidationChain.validate()` with derived `UploadValidationRequest` (extract containerFormat from fileName extension)
  - [x] Call `QuotaProvider.check()`; throw `QuotaExceededException(ownerId, currentBytes, requestedBytes)` on false — use existing 3-arg constructor
  - [x] Use `try/finally` pattern with `success` flag for quota release (see Dev Notes)
  - [x] Use `TransactionTemplate.execute()` for the Video + UploadSession DB insert so records commit BEFORE provider call
  - [x] Call `VideoProviderAdapter.initializeUpload(fileName, fileSizeBytes)` outside any transaction
  - [x] Update `Video.providerAssetId` and `UploadSession.providerUploadId` in a second transactional block
  - [x] Add MDC fields: `ownerId`, `videoId`, `operation="initialize_upload"`; clear in finally

- [x] Task 5: Write integration test `VideoUploadInitializationIT` (AC: 7, 8)
  - [x] Extend `BaseVideoIT`
  - [x] Provide `@MockitoBean QuotaProvider` and `@MockitoBean VideoProviderAdapter` that record method calls
  - [x] Happy-path test: verify Video+UploadSession saved, providerAssetId="test-asset-guid-123", correct states
  - [x] Quota check order test: verify `check` called before `reserve`
  - [x] Validation failure test: call with fileSizeBytes = maxBytes+1 → `VideoValidationException` not `QuotaExceededException`; provider never called
  - [x] NFR-1 test: loop 100 calls, assert 99th percentile < 500ms using `LongStream` sorted array
  - [x] Provider failure test: VideoProviderAdapter throws `VideoProviderException` → `QuotaProvider.release()` called

## Dev Notes

### Entity Construction — Add @Setter

Both `Video` and `UploadSession` currently have only `@Getter @NoArgsConstructor`. You MUST add `@Setter` at the class level to enable field mutation before `save()`:

```java
// Video.java — add @Setter
@Getter
@Setter           // ADD THIS
@NoArgsConstructor
@Entity
@Table(name = "videos", schema = "main")
public class Video { ... }

// UploadSession.java — add @Setter
@Getter
@Setter           // ADD THIS
@NoArgsConstructor
@Entity
@Table(name = "upload_sessions", schema = "main")
public class UploadSession { ... }
```

The `@PrePersist` callbacks in both entities still fire correctly when JPA flushes, setting `createdAt`/`updatedAt`. The generated setters for those fields will exist but are simply never called.

### VideoService — Full Implementation Pattern

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoService {

    private final VideoValidationChain validationChain;
    private final QuotaProvider quotaProvider;
    private final VideoProviderAdapter videoProviderAdapter;
    private final VideoRepository videoRepository;
    private final UploadSessionRepository uploadSessionRepository;
    private final VideoProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final RateLimitingService rateLimitingService;

    public InitializeUploadResponse initializeUpload(InitializeUploadRequest request) {
        MDC.put("operation", "initialize_upload");
        MDC.put("ownerId", request.ownerId());

        // 1. Rate limit check (before everything else — per ownerId, not IP)
        int rpm = properties.getUpload().getRateLimit().getRequestsPerMinute();
        boolean allowed = rateLimitingService.tryConsume(
            request.ownerId(), "video.upload.init", rpm, 1, TimeUnit.MINUTES);
        if (!allowed) {
            throw new QuotaExceededException(request.ownerId(), "rate limit exceeded");
        }

        // 2. Validate file metadata
        String ext = request.fileName().contains(".")
            ? request.fileName().substring(request.fileName().lastIndexOf('.') + 1)
            : "";
        validationChain.validate(
            new UploadValidationRequest(request.fileName(), request.fileSizeBytes(),
                                        request.mimeType(), ext.toUpperCase()));

        // 3. Quota check (no reservation yet)
        if (!quotaProvider.check(request.ownerId(), request.fileSizeBytes())) {
            throw new QuotaExceededException(request.ownerId(), 0L, request.fileSizeBytes());
        }

        // 4. Reserve + persist + provider call with guaranteed release on failure
        String reservationHandle = null;
        boolean success = false;
        try {
            reservationHandle = quotaProvider.reserve(request.ownerId(), request.fileSizeBytes());

            Instant expiresAt = Instant.now().plus(
                properties.getUpload().getSessionTtlMinutes(), ChronoUnit.MINUTES);
            final String handle = reservationHandle;

            // 5. Commit DB records BEFORE calling provider
            //    (orphaned records stay if provider fails — expiry scheduler handles them)
            UUID[] ids = transactionTemplate.execute(status -> {
                Video video = new Video();
                video.setOwnerId(request.ownerId());
                video.setProvider(properties.getProvider());
                video.setTitle(request.fileName());
                video.setOperationalState(OperationalState.UPLOADING);
                video.setAccessState(AccessState.ACTIVE);
                video.setVisibility(Visibility.PRIVATE);
                Video savedVideo = videoRepository.save(video);

                UploadSession session = new UploadSession();
                session.setVideoId(savedVideo.getId());
                session.setStatus(UploadSessionStatus.PENDING);
                session.setReservedBytes(request.fileSizeBytes());
                session.setReservationHandle(handle);
                session.setExpiresAt(expiresAt);
                uploadSessionRepository.save(session);

                MDC.put("videoId", savedVideo.getId().toString());
                return new UUID[]{savedVideo.getId(), session.getId()};
            });

            UUID videoId = ids[0];
            UUID sessionId = ids[1];

            // 6. Call provider — outside any transaction
            UploadCredentials credentials = videoProviderAdapter.initializeUpload(
                request.fileName(), request.fileSizeBytes());

            // 7. Persist provider IDs now that we have them
            transactionTemplate.execute(status -> {
                Video video = videoRepository.findById(videoId).orElseThrow();
                video.setProviderAssetId(credentials.providerUploadId());
                videoRepository.save(video);

                UploadSession session = uploadSessionRepository.findById(sessionId).orElseThrow();
                session.setProviderUploadId(credentials.providerUploadId());
                uploadSessionRepository.save(session);
                return null;
            });

            success = true;
            return new InitializeUploadResponse(
                videoId, sessionId,
                credentials.providerUploadId(), credentials.signedUploadUrl(),
                expiresAt);

        } finally {
            if (!success && reservationHandle != null) {
                try {
                    quotaProvider.release(reservationHandle);
                } catch (Exception e) {
                    log.warn("Failed to release quota reservation after failure", e);
                }
            }
            MDC.remove("operation");
            MDC.remove("ownerId");
            MDC.remove("videoId");
        }
    }
}
```

### Rate Limiting — Use RateLimitingService Directly

**Do NOT use `@RateLimited` annotation.** The existing `RateLimitingAspect` extracts the identifier from `RequestMetadataProvider.getClientInfo().getIpAddress()` — it is IP-based and fires before a Spring service method can receive parameters. This story requires **per-`ownerId`** rate limiting at the service level.

Instead, inject `RateLimitingService` directly:
```java
// infrastructure.security.RateLimitingService
boolean tryConsume(String identifier, String limitKey, long capacity, long duration, TimeUnit unit)
// usage:
rateLimitingService.tryConsume(ownerId, "video.upload.init", rpm, 1, TimeUnit.MINUTES)
```

`RateLimitingService` uses a `ConcurrentHashMap<String, Bucket>` with key `"video.upload.init:${ownerId}"` — in-memory, no Redis needed for this scope. The Bucket4j dependency is already present in pom.xml (`bucket4j-core`, `bucket4j-redis`).

### Transaction Pattern — Why Two Transactions + TransactionTemplate

The spec requires DB records be **committed** before the provider call (AC-6: "orphaned records remain when provider fails"). This is impossible with a single `@Transactional` method because the transaction commits only when the method returns.

**Solution:** Use `TransactionTemplate` to explicitly commit the DB insert before the provider call:
```
reserve quota
  ↓
transactionTemplate.execute { video + session insert → commit }
  ↓
videoProviderAdapter.initializeUpload()   ← outside any transaction
  ↓ (success)
transactionTemplate.execute { update providerAssetId + providerUploadId → commit }
  ↓
return response
```

`TransactionTemplate` is already used in `FileStorageService.confirmUpload()` — reference `src/main/java/com/softropic/skillars/platform/filestorage/service/FileStorageService.java:144`.

`TransactionTemplate` bean is auto-configured by Spring Boot when JPA is on the classpath. Inject it like any other bean — no configuration class changes needed.

### QuotaExceededException — New Overloaded Constructor

Add this constructor to the existing `QuotaExceededException.java`:
```java
public QuotaExceededException(String ownerId, String reason) {
    super("Upload quota or rate limit exceeded",
          Map.of("ownerId", ownerId, "reason", reason),
          VideoErrorCode.QUOTA_EXCEEDED);
}
```

Do NOT modify `VideoApiAdvice`. The existing `@ExceptionHandler(QuotaExceededException.class) → 429` handler covers both quota and rate limit violations.

### InitializeUploadRequest — Exact Record Structure

```java
package com.softropic.skillars.platform.video.contract;

public record InitializeUploadRequest(
    String ownerId,
    String fileName,
    long fileSizeBytes,
    String mimeType
) {}
```

`containerFormat` is NOT a field — it is derived from `fileName` in `VideoService` before building `UploadValidationRequest`. This keeps the API contract minimal.

### InitializeUploadResponse — Exact Record Structure

```java
package com.softropic.skillars.platform.video.contract;

import java.time.Instant;
import java.util.UUID;

public record InitializeUploadResponse(
    UUID videoId,
    UUID uploadSessionId,
    String providerUploadId,
    String signedUploadUrl,
    Instant expiresAt
) {}
```

### UploadCredentials — Already Exists in infrastructure.video

`UploadCredentials` record is already defined: `record UploadCredentials(String providerUploadId, String signedUploadUrl)`.

In `BunnyVideoProviderAdapter.initializeUpload()`:
- `providerUploadId` = Bunny.net video GUID (used as both the TUS asset ID and `providerAssetId` on `Video`)
- `signedUploadUrl` = TUS upload URL like `https://video.bunnycdn.com/tusupload`

### WireMock Stub Pattern for Integration Tests

WireMock in `BaseVideoIT` is named `"bunny-service"` and its base URL is bound to `app.video.bunny.api-base-url` and `app.video.bunny.cdn-hostname` via `application-test.yaml`.

Bunny.net create-video stub:
```java
wireMockServer.stubFor(
    post(urlPathMatching("/library/.*/videos"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withFixedDelay(20)  // Simulate realistic latency for NFR-1
            .withBody("""
                {"guid":"test-asset-guid-123",
                 "uploadUrl":"http://localhost:%d/tusupload"}
                """.formatted(wireMockServer.port()))));
```

Adapt field names to match `BunnyVideoProviderAdapter`'s JSON deserialization — check `src/main/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapter.java` for actual field names.

### QuotaProvider in Integration Tests

`NoOpQuotaProvider` lives in `src/test/java/com/softropic/skillars/platform/video/service/NoOpQuotaProvider.java` (implemented in Story 1.2). For integration tests needing a spy to verify call order:

```java
@TestBean  // Spring Boot test replacement
QuotaProvider quotaProvider() {
    return new QuotaProvider() {
        final List<String> calls = new CopyOnWriteArrayList<>();
        public boolean check(String o, long b) { calls.add("check"); return true; }
        public String reserve(String o, long b) { calls.add("reserve"); return "handle-" + UUID.randomUUID(); }
        public void commit(String h) { calls.add("commit"); }
        public void release(String h) { calls.add("release"); }
        public List<String> getCalls() { return calls; }
    };
}
```

### Package & File Structure

```
src/main/java/com/softropic/skillars/platform/video/
├── contract/
│   ├── InitializeUploadRequest.java      ← NEW: record
│   └── InitializeUploadResponse.java     ← NEW: record
├── contract/exception/
│   └── QuotaExceededException.java       ← UPDATE: add overloaded constructor
├── repo/
│   ├── Video.java                         ← UPDATE: add @Setter
│   └── UploadSession.java                 ← UPDATE: add @Setter
└── service/
    └── VideoService.java                  ← UPDATE: add initializeUpload()

src/test/java/com/softropic/skillars/platform/video/service/
└── VideoUploadInitializationIT.java       ← NEW: integration test
```

**Do NOT create files anywhere else.** DTO records go in `contract` (not `contract/dto`). No REST endpoint in this story — the module provides service layer only; consuming apps own their REST controllers.

### What Story 1.x and 2.1 Delivered (Do NOT Repeat)

These ALREADY EXIST — do NOT recreate:
- `VideoValidationChain`, `VideoValidationStep`, `FileSizeValidationStep`, `MimeTypeValidationStep`, `FormatValidationStep` — call as-is
- `UploadValidationRequest` record in `platform.video.contract`
- `VideoProperties` with `Upload.maxBytes`, `Upload.allowedMimeTypes`, `Upload.allowedFormats`, `Upload.sessionTtlMinutes`, `Upload.rateLimit.requestsPerMinute`
- `QuotaProvider` interface in `platform.video.contract`
- `VideoProviderAdapter` interface + `BunnyVideoProviderAdapter` in `infrastructure.video`
- `UploadCredentials` record in `infrastructure.video`
- All entities, repositories, `VideoApiAdvice`, `BaseVideoIT`, all exception classes
- `NoOpQuotaProvider` + `NoOpQuotaProviderTest` in test directory
- `RateLimitingService` in `infrastructure.security` — inject, do not recreate
- Enums: `OperationalState`, `AccessState`, `Visibility`, `UploadSessionStatus` — import from `platform.video.contract`

### Architecture Compliance Checks

- `InitializeUploadRequest`, `InitializeUploadResponse` → `platform.video.contract` ✓ (public module API — DTOs/records)
- `VideoService` → `platform.video.service` ✓ (business logic in platform)
- No `infrastructure.*` imports in `VideoService` (only inject `VideoProviderAdapter` via its interface) ✓
- Rate limiting via `RateLimitingService` from `infrastructure.security` is acceptable — it's a technical capability, not business logic
- `@Setter` on entities is JPA-safe — `@PrePersist` hooks fire on flush, not on setter calls

### Project Context Rules (Must Follow)

- Java 17: `record` for all DTOs
- Spring Boot 3.5.x: `@RequiredArgsConstructor` over `@Autowired`; `@Slf4j` for logging
- Testing: JUnit 5 + AssertJ + Testcontainers (integration) — real DB, no mocks
- Package hierarchy: `com.softropic.skillars.platform.video.{contract, service, repo}`
- MDC: clear all MDC keys in `finally` block (see FileStorageService pattern)
- No comments explaining WHAT code does; only non-obvious WHY comments

### References

- Rate limiting infra: `src/main/java/com/softropic/skillars/infrastructure/security/RateLimitingService.java`
- Rate limiting annotation: `src/main/java/com/softropic/skillars/infrastructure/security/RateLimited.java` (NOT used here — IP-based)
- TransactionTemplate pattern: `src/main/java/com/softropic/skillars/platform/filestorage/service/FileStorageService.java:144`
- BunnyVideoProviderAdapter: `src/main/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapter.java`
- UploadCredentials: `src/main/java/com/softropic/skillars/infrastructure/video/UploadCredentials.java`
- BaseVideoIT: `src/test/java/com/softropic/skillars/platform/video/BaseVideoIT.java`
- NoOpQuotaProvider (test): `src/test/java/com/softropic/skillars/platform/video/service/NoOpQuotaProvider.java`
- QuotaExceededException: `src/main/java/com/softropic/skillars/platform/video/contract/exception/QuotaExceededException.java`
- VideoProperties: `src/main/java/com/softropic/skillars/platform/video/config/VideoProperties.java`
- Epic 2 Story 2.2 AC: `_bmad-output/planning-artifacts/video-module/epics.md` §"Story 2.2: Upload Initialization & Quota Management"
- Project context rules: `_bmad-output/project-context.md`

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

None.

### Completion Notes List

- Implemented `VideoService.initializeUpload()` with full rate-limit → validate → quota-check → reserve → DB commit → provider call → update flow using `TransactionTemplate` for two-phase commit as specified.
- Added `@Setter` to both JPA entities to enable field mutation; `@PrePersist`/`@PreUpdate` lifecycle callbacks unaffected.
- Added `QuotaExceededException(String ownerId, String reason)` overload for rate-limit rejections.
- Created `InitializeUploadRequest` and `InitializeUploadResponse` record DTOs in `platform.video.contract`.
- Fixed pre-existing bean-name conflicts: `FileSizeValidationStep` and `MimeTypeValidationStep` in `platform.video.service.validation` given explicit bean names (`videoFileSizeValidationStep`, `videoMimeTypeValidationStep`) to avoid collisions with identically-named beans in `platform.filestorage.service.validation`.
- Integration test uses `@MockitoBean` for both `QuotaProvider` and `VideoProviderAdapter` because `wiremock-spring-boot` 4.0.9 registers its server URL post-context-refresh (after `@ConfigurationProperties` binding), making URL injection into beans created at context-load time impossible via YAML placeholders. HTTP behaviour of the Bunny adapter is covered by the existing `BunnyVideoProviderAdapterTest`.
- All 5 integration test scenarios pass (happy-path, call-order, validation-first, NFR-1, provider-failure). Full test suite: 251 tests, 0 failures.

### File List

- src/main/java/com/softropic/skillars/platform/video/contract/InitializeUploadRequest.java (NEW)
- src/main/java/com/softropic/skillars/platform/video/contract/InitializeUploadResponse.java (NEW)
- src/main/java/com/softropic/skillars/platform/video/contract/exception/QuotaExceededException.java (MODIFIED — added 2-arg constructor)
- src/main/java/com/softropic/skillars/platform/video/repo/Video.java (MODIFIED — added @Setter)
- src/main/java/com/softropic/skillars/platform/video/repo/UploadSession.java (MODIFIED — added @Setter)
- src/main/java/com/softropic/skillars/platform/video/service/VideoService.java (MODIFIED — implemented initializeUpload)
- src/main/java/com/softropic/skillars/platform/video/service/validation/FileSizeValidationStep.java (MODIFIED — explicit bean name to avoid conflict)
- src/main/java/com/softropic/skillars/platform/video/service/validation/MimeTypeValidationStep.java (MODIFIED — explicit bean name to avoid conflict)
- src/test/java/com/softropic/skillars/platform/video/service/VideoUploadInitializationIT.java (NEW)
- _bmad-output/implementation-artifacts/sprint-status.yaml (MODIFIED — status in-progress → review)
