# Story 1.2: StorageService Interface, S3 Implementation & Key Generation

Status: done

## Story

As a developer building upload, download, and deletion features,
I want a provider-agnostic `StorageService` interface backed by a working `S3StorageService` and a key generator,
so that all higher-level storage features can delegate to a consistent, retryable S3 gateway without knowing provider details.

## Acceptance Criteria

1. **StorageService interface declared**: `StorageService` defines `put(String key, InputStream data, long contentLength, String contentType)`, `get(String key): InputStream`, `delete(String key)`, `exists(String key): boolean`, `stat(String key): StorageObjectMetadata`, `copy(String sourceKey, String destinationKey)`. No AWS SDK types anywhere in the interface.

2. **S3 beans wired by StorageConfig**: When `app.storage.provider=s3`, `StorageConfig` creates `S3Client`, `S3AsyncClient`, `S3Presigner`, and `S3TransferManager` beans. `S3Client` is configured with `app.storage.s3.request-timeout-ms` as the API call timeout and `app.storage.s3.connection-timeout-ms` as the connection acquisition timeout — never using SDK defaults. `S3StorageService` is registered as the active `StorageService` bean.

3. **Retry resilience on all S3 calls**: When an S3 call fails transiently fewer than `app.storage.retry.max-attempts` times, Spring Retry retries with exponential backoff using `backoff-initial-ms` and `backoff-multiplier`. `@Recover` catches final failure and throws `StorageProviderException(operation, cause)`. `@Retryable` is applied to every S3 operation: `put`, `get`, `delete`, `exists`, `stat`, `copy`.

4. **Streaming enforced**: All file I/O uses `InputStream`/`OutputStream` or `S3TransferManager` — `InputStream.readAllBytes()` is never called. `put` uses `S3TransferManager.upload()`, not `s3Client.putObject()` directly.

5. **StorageKeyGenerator produces canonical keys**: `StorageKeyGenerator.generate(entity, entityId, extension)` returns `{entity}/{entityId}/{yyyy}/{mm}/{uuid}.{ext}` where `yyyy`/`mm` reflect the current date and `uuid` is random. No two calls with the same args return the same key.

## Tasks / Subtasks

- [x] Task 1: Add AWS SDK v2 Maven dependencies (AC: 2)
  - [x] Add AWS SDK v2 BOM to `<dependencyManagement>` in `pom.xml`
  - [x] Add `software.amazon.awssdk:s3` to `<dependencies>` (provides S3Client, S3AsyncClient, S3Presigner)
  - [x] Add `software.amazon.awssdk:s3-transfer-manager` to `<dependencies>`
  - [x] Verify `mvn dependency:resolve` succeeds

- [x] Task 2: Create `StorageObjectMetadata` record in `contract/` (AC: 1)
  - [x] Create `StorageObjectMetadata.java` as a Java `record` with fields: `String key`, `String contentType`, `long contentLength`, `String eTag`, `java.time.Instant lastModified`

- [x] Task 3: Create `StorageService` interface (AC: 1)
  - [x] Create `StorageService.java` in `service/` package
  - [x] Declare all 6 methods with exact signatures from AC-1
  - [x] Import only `java.io.InputStream` and `StorageObjectMetadata` — zero AWS SDK types

- [x] Task 4: Create `StorageKeyGenerator` (AC: 5)
  - [x] Create `StorageKeyGenerator.java` in `service/` as a `@Service`
  - [x] Implement `generate(String entity, String entityId, String extension)` returning `{entity}/{entityId}/{yyyy}/{mm}/{uuid}.{ext}`
  - [x] Use `LocalDate.now()` for year/month; `UUID.randomUUID()` for uuid component

- [x] Task 5: Create `S3StorageService` (AC: 2, 3, 4)
  - [x] Create `S3StorageService.java` in `service/` as a `@Service`
  - [x] Implement `StorageService` interface
  - [x] Add `@Retryable` (retrying on `SdkException`) to every method: `put`, `get`, `delete`, `exists`, `stat`, `copy`
  - [x] Add single `@Recover` method per retryable operation catching final failure → throws `StorageProviderException(operationName, cause)`
  - [x] Implement `put` via `S3TransferManager.upload()` — NEVER `s3Client.putObject()`
  - [x] Implement `get` via `s3Client.getObject()` returning `ResponseInputStream` — pass directly as `InputStream` (no buffering)
  - [x] Implement `delete` via `s3Client.deleteObject()`
  - [x] Implement `exists` via `s3Client.headObject()` wrapped in try/catch for `NoSuchKeyException` → return false
  - [x] Implement `stat` via `s3Client.headObject()` → map response fields to `StorageObjectMetadata`
  - [x] Implement `copy` via `s3Client.copyObject()`

- [x] Task 6: Update `StorageConfig` to wire S3 beans (AC: 2)
  - [x] Inject `StorageProperties` into `StorageConfig`
  - [x] Add `@Bean S3Client` — use `S3Client.builder()` with `endpointOverride`, credentials from env, region; set `apiCallTimeout(Duration.ofMillis(s3.requestTimeoutMs))` and `connectionAcquisitionTimeout` via `SdkHttpClient` config
  - [x] Add `@Bean S3AsyncClient` — same config as `S3Client` but async builder
  - [x] Add `@Bean S3Presigner` — use `S3Presigner.builder()` with endpoint and credentials
  - [x] Add `@Bean S3TransferManager` — `S3TransferManager.builder().s3Client(s3AsyncClient).build()`
  - [x] Add `@Bean StorageService` factory method that returns `new S3StorageService(s3Client, transferManager, properties)` when `provider=s3`
  - [x] All 5 beans are conditional on `@ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3", matchIfMissing = true)`

- [x] Task 7: Verify compilation (all AC)
  - [x] Run `mvn clean compile -pl . -am` — expect BUILD SUCCESS
  - [x] Confirm no `platform.*` imports appear in any new storage class

### Review Findings

- [x] [Review][Decision] Metadata Loss in get() — Resolved: StorageService now returns StorageObject record.
- [x] [Review][Patch] Thread pool leak in S3StorageService.put [src/main/java/com/softropic/skillars/infrastructure/storage/service/S3StorageService.java:45]
- [x] [Review][Patch] Missing S3 Request and Connection Timeouts [src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageConfig.java:23]
- [x] [Review][Patch] Incomplete @Recover methods in S3StorageService [src/main/java/com/softropic/skillars/infrastructure/storage/service/S3StorageService.java:54]
- [x] [Review][Patch] Synchronous blocking on S3TransferManager [src/main/java/com/softropic/skillars/infrastructure/storage/service/S3StorageService.java:48]
- [x] [Review][Patch] Hardcoded EnvironmentVariableCredentialsProvider [src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageConfig.java:26]
- [x] [Review][Patch] Inconsistent Storage Key Formatting [src/main/java/com/softropic/skillars/infrastructure/storage/service/StorageKeyGenerator.java:14]
- [x] [Review][Patch] Incorrect 502 mapping for missing objects [src/main/java/com/softropic/skillars/infrastructure/storage/service/S3StorageService.java:65]
- [x] [Review][Patch] Missing Jakarta Validation in StorageProperties [src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageProperties.java:1]
- [x] [Review][Patch] Timezone-dependent key generation [src/main/java/com/softropic/skillars/infrastructure/storage/service/StorageKeyGenerator.java:11]
- [x] [Review][Patch] Hardcoded AWS Region in Config [src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageConfig.java:28]
- [x] [Review][Patch] Restricted Copy Logic (Same Bucket Only) [src/main/java/com/softropic/skillars/infrastructure/storage/service/S3StorageService.java:144]

## Dev Notes

### CRITICAL: AWS SDK v2 Dependencies Are Not In pom.xml

The AWS SDK v2 is **NOT currently in the pom.xml**. This is the first story that uses it. You MUST add three changes to `pom.xml` before writing any Java code:

**1. Add BOM to `<dependencyManagement>` block (after the existing `spring-cloud-dependencies` entry):**
```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>bom</artifactId>
    <version>2.28.29</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

**2. Add S3 client to `<dependencies>` block:**
```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
</dependency>
```

**3. Add Transfer Manager to `<dependencies>` block:**
```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3-transfer-manager</artifactId>
</dependency>
```

The `s3` artifact provides: `S3Client`, `S3AsyncClient`, `S3Presigner`. The `s3-transfer-manager` artifact provides `S3TransferManager`.

### StorageObjectMetadata — New Contract Record

`stat(String key)` returns `StorageObjectMetadata`. This record does NOT yet exist; create it in `contract/`. It must use only JDK types (no AWS SDK types):

```java
// src/main/java/com/softropic/skillars/infrastructure/storage/contract/StorageObjectMetadata.java
package com.softropic.skillars.infrastructure.storage.contract;

import java.time.Instant;

public record StorageObjectMetadata(
    String key,
    String contentType,
    long contentLength,
    String eTag,
    Instant lastModified
) {}
```

### StorageService Interface — Exact Signature

Place in `service/` package (replaces the `package-info.java` stub created in Story 1.1):

```java
public interface StorageService {
    void put(String key, InputStream data, long contentLength, String contentType);
    InputStream get(String key);
    void delete(String key);
    boolean exists(String key);
    StorageObjectMetadata stat(String key);
    void copy(String sourceKey, String destinationKey);
}
```

**Zero AWS SDK types** — the interface lives in `service/` and references only `StorageObjectMetadata` from `contract/`. Consumers never see AWS classes.

### S3StorageService — Retry Pattern (Critical)

`@EnableRetry` is already on `SkillarsApplication`. Spring Retry is already in pom.xml. DO NOT add `@EnableRetry` again.

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class S3StorageService implements StorageService {

    private final S3Client s3Client;
    private final S3TransferManager transferManager;
    private final StorageProperties properties;

    @Retryable(
        retryFor = SdkException.class,
        maxAttemptsExpression = "${app.storage.retry.max-attempts:3}",
        backoff = @Backoff(
            delayExpression = "${app.storage.retry.backoff-initial-ms:1000}",
            multiplierExpression = "${app.storage.retry.backoff-multiplier:2.0}"
        )
    )
    @Override
    public void put(String key, InputStream data, long contentLength, String contentType) {
        // use S3TransferManager — see below
    }

    @Recover
    public void recoverPut(SdkException ex, String key, InputStream data, long contentLength, String contentType) {
        throw new StorageProviderException("put", ex);
    }
    // Repeat @Retryable + @Recover for every method
}
```

**Each method requires its own `@Recover` method.** The recover method signature must match the retryable method signature (same parameters, first param is the exception type).

### put() — Must Use S3TransferManager

```java
@Override
public void put(String key, InputStream data, long contentLength, String contentType) {
    UploadRequest uploadRequest = UploadRequest.builder()
        .putObjectRequest(r -> r
            .bucket(properties.getBucket())
            .key(key)
            .contentType(contentType)
            .contentLength(contentLength))
        .requestBody(AsyncRequestBody.fromInputStream(data, contentLength, Executors.newSingleThreadExecutor()))
        .build();
    transferManager.upload(uploadRequest).completionFuture().join();
}
```

**NEVER call `s3Client.putObject()` for file content.** `S3TransferManager` automatically switches to multipart above the configured threshold. `InputStream.readAllBytes()` is strictly forbidden — use `AsyncRequestBody.fromInputStream(...)`.

### get() — Streaming, No Buffering

```java
@Override
public InputStream get(String key) {
    return s3Client.getObject(r -> r.bucket(properties.getBucket()).key(key));
    // ResponseInputStream<GetObjectResponse> IS an InputStream — return directly
}
```

The `ResponseInputStream` extends `InputStream`. Return it as-is; callers are responsible for closing it. Do NOT buffer into a `ByteArrayInputStream`.

### exists() — HeadObject Pattern

```java
@Override
public boolean exists(String key) {
    try {
        s3Client.headObject(r -> r.bucket(properties.getBucket()).key(key));
        return true;
    } catch (NoSuchKeyException e) {
        return false;
    }
}
```

`NoSuchKeyException` extends `S3Exception` extends `SdkException`. The `@Retryable` annotation will NOT catch `NoSuchKeyException` for retry because it is used here for control flow, not as an error. However, other `SdkException` types (network errors, throttling) WILL be retried.

### stat() — HeadObject to StorageObjectMetadata

```java
@Override
public StorageObjectMetadata stat(String key) {
    HeadObjectResponse r = s3Client.headObject(req -> req.bucket(properties.getBucket()).key(key));
    return new StorageObjectMetadata(key, r.contentType(), r.contentLength(), r.eTag(), r.lastModified());
}
```

If the key doesn't exist, `headObject()` throws `NoSuchKeyException` (a `SdkException` subclass). The `@Recover` method should catch and re-throw as `StorageObjectNotFoundException` if appropriate — or let it propagate. For Story 1.2, letting `StorageProviderException` cover it is acceptable; Stories 2.x will add proper `StorageObjectNotFoundException` throwing.

### StorageConfig — S3 Bean Wiring

```java
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3", matchIfMissing = true)
    public S3Client s3Client(StorageProperties properties) {
        return S3Client.builder()
            .endpointOverride(URI.create(properties.getEndpointUrl()))
            .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
            .region(Region.US_EAST_1)  // region from config or default
            .httpClient(ApacheHttpClient.builder()
                .connectionAcquisitionTimeout(Duration.ofMillis(properties.getS3().getConnectionTimeoutMs()))
                .build())
            .overrideConfiguration(c -> c
                .apiCallTimeout(Duration.ofMillis(properties.getS3().getRequestTimeoutMs())))
            .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3", matchIfMissing = true)
    public S3AsyncClient s3AsyncClient(StorageProperties properties) {
        return S3AsyncClient.builder()
            .endpointOverride(URI.create(properties.getEndpointUrl()))
            .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
            .region(Region.US_EAST_1)
            .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3", matchIfMissing = true)
    public S3Presigner s3Presigner(StorageProperties properties) {
        return S3Presigner.builder()
            .endpointOverride(URI.create(properties.getEndpointUrl()))
            .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
            .region(Region.US_EAST_1)
            .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3", matchIfMissing = true)
    public S3TransferManager s3TransferManager(S3AsyncClient s3AsyncClient) {
        return S3TransferManager.builder().s3Client(s3AsyncClient).build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3", matchIfMissing = true)
    public StorageService storageService(S3Client s3Client, S3TransferManager transferManager,
                                         StorageProperties properties) {
        return new S3StorageService(s3Client, transferManager, properties);
    }
}
```

**Region note**: Externalize `region` to `app.storage.s3.region` and add it to `StorageProperties.S3`. Default `us-east-1` for MinIO compatibility. Do NOT hardcode `Region.US_EAST_1` — read from `StorageProperties`.

**Credentials note**: `EnvironmentVariableCredentialsProvider` reads `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` env vars. This works for both real AWS and MinIO (which accepts any key/secret). Never hardcode credentials.

**ApacheHttpClient** is part of `software.amazon.awssdk:apache-client` — add this dependency too:
```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>apache-client</artifactId>
</dependency>
```

### StorageKeyGenerator — Implementation

```java
@Service
public class StorageKeyGenerator {

    public String generate(String entity, String entityId, String extension) {
        LocalDate now = LocalDate.now();
        String uuid = UUID.randomUUID().toString();
        return String.format("%s/%s/%04d/%02d/%s.%s",
            entity, entityId, now.getYear(), now.getMonthValue(), uuid, extension);
    }
}
```

The UUID guarantees cache-busting — two calls with identical args produce different keys.

### Application YAML — Add region

Add to the `app.storage.s3` block in `application.yaml`:
```yaml
app:
  storage:
    s3:
      request-timeout-ms: 5000
      connection-timeout-ms: 3000
      region: ${APP_STORAGE_REGION:us-east-1}
```

Also add the `region` field to `StorageProperties.S3` inner class:
```java
public static class S3 {
    private long requestTimeoutMs = 5000;
    private long connectionTimeoutMs = 3000;
    private String region = "us-east-1";
}
```

### Anti-Patterns — Mandatory Avoidance

| Anti-Pattern | Correct Alternative |
|---|---|
| `s3Client.putObject(...)` for uploads | `transferManager.upload(...)` |
| `InputStream.readAllBytes()` anywhere | Stream directly via `ResponseInputStream` or `AsyncRequestBody` |
| `@Transactional` wrapping S3 calls | S3 outside transaction; DB writes in separate `@Transactional` method |
| Hardcoding `"us-east-1"` or bucket name | Read from `StorageProperties` |
| `@EnableRetry` on `StorageConfig` | Already on `SkillarsApplication` — DO NOT add again |
| Platform imports in storage classes | `infrastructure.storage` MUST NOT import from `platform.*` |

### Module Boundary Reminder (from Story 1.1)

All new files live under `com.softropic.skillars.infrastructure.storage.*`. Zero imports from `com.softropic.skillars.platform.*` are allowed. Consumers (platform modules) depend on infrastructure, not the reverse.

### Project Structure Notes

**New files to create:**
```
src/main/java/com/softropic/skillars/infrastructure/storage/
├── contract/
│   └── StorageObjectMetadata.java          ← new Java record
└── service/
    ├── StorageService.java                  ← new interface
    ├── S3StorageService.java               ← new @Service
    └── StorageKeyGenerator.java            ← new @Service
```

**Modified files:**
```
pom.xml                                      ← add 4 AWS SDK v2 dependencies
src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageConfig.java  ← add 5 @Bean methods
src/main/resources/application.yaml         ← add app.storage.s3.region
```

**Note:** `service/package-info.java` already exists from Story 1.1 — it will be replaced/overridden by the new service files. Leave it in place or delete it; it has no functional impact.

**Story 1.3 scope (DO NOT IMPLEMENT NOW):** `BaseStorageIT`, `MinioContainerConfig`, any integration test class. Those come in Story 1.3.

### References

- [Source: epics.md#Story 1.2] — full BDD acceptance criteria
- [Source: architecture.md#Naming Patterns] — `StorageService`, `S3StorageService`, `StorageKeyGenerator` class names
- [Source: architecture.md#Retry Pattern] — `@Retryable` configuration, `@Recover` wrapping to `StorageProviderException`
- [Source: architecture.md#Gap Analysis#Gap 3] — `S3TransferManager` is mandatory for all put operations
- [Source: architecture.md#Enforcement Guidelines] — anti-patterns table
- [Source: architecture.md#Package Layout] — exact file placement
- [Source: architecture.md#Data Architecture] — transaction boundary rule (S3 outside `@Transactional`)
- [Source: StorageConfig.java] — skeleton awaiting S3 bean wiring
- [Source: StorageProperties.java] — all config sub-classes with typed fields; `S3` sub-class needs `region` field added
- [Source: StorageProviderException.java] — `(String operation, Throwable cause)` constructor
- [Source: SkillarsApplication.java] — `@EnableRetry` already present; do NOT add again
- [Source: pom.xml] — `spring-retry` at line 78 (already present); AWS SDK v2 NOT present (must add)
- [Source: project-context.md#Critical Rules] — no `InputStream.readAllBytes()`, streaming mandatory

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- Pre-existing `ApiAdvice.java` compile error fixed: `ErrorMsg` import was missing (added `import com.softropic.skillars.infrastructure.message.ErrorMsg`).
- `CompletedUpload.builder().response(null)` rejected at runtime; fixed to use `PutObjectResponse.builder().build()` as the response.
- Ambiguous `transferManager.upload(any())` in test; fixed by using `any(UploadRequest.class)`.

### Completion Notes List

- Added AWS SDK v2 BOM (2.28.29) plus `s3`, `s3-transfer-manager`, and `apache-client` dependencies to `pom.xml`.
- Created `StorageObjectMetadata` Java record in `contract/` package with zero AWS SDK types.
- Created `StorageService` interface with all 6 methods; only JDK + contract imports.
- Created `StorageKeyGenerator` `@Service` producing `{entity}/{entityId}/{yyyy}/{mm}/{uuid}.{ext}` keys.
- Created `S3StorageService` with `@Retryable`+`@Recover` on all 6 methods; `put` uses `S3TransferManager`, `get` streams via `ResponseInputStream`, `exists` catches `NoSuchKeyException` for control flow.
- Updated `StorageConfig` to wire `S3Client`, `S3AsyncClient`, `S3Presigner`, `S3TransferManager`, and `StorageService` beans, all gated on `@ConditionalOnProperty(provider=s3)`.
- Added `region` field to `StorageProperties.S3` and `app.storage.s3.region` to `application.yaml`.
- 16 unit tests pass (13 `S3StorageServiceTest` + 3 `StorageKeyGeneratorTest`); full build `BUILD SUCCESS`; zero regressions.

### File List

- `pom.xml` — added AWS SDK v2 BOM + 3 dependencies
- `src/main/java/com/softropic/skillars/infrastructure/storage/contract/StorageObjectMetadata.java` — new record
- `src/main/java/com/softropic/skillars/infrastructure/storage/service/StorageService.java` — new interface
- `src/main/java/com/softropic/skillars/infrastructure/storage/service/StorageKeyGenerator.java` — new @Service
- `src/main/java/com/softropic/skillars/infrastructure/storage/service/S3StorageService.java` — new @Service
- `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageConfig.java` — updated with 5 S3 beans
- `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageProperties.java` — added `region` field to S3 inner class
- `src/main/resources/application.yaml` — added `app.storage.s3.region`
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java` — fixed missing `ErrorMsg` import (pre-existing bug)
- `src/test/java/com/softropic/skillars/infrastructure/storage/service/StorageKeyGeneratorTest.java` — new unit tests
- `src/test/java/com/softropic/skillars/infrastructure/storage/service/S3StorageServiceTest.java` — new unit tests

## Change Log

- 2026-05-25: Implemented full story — AWS SDK v2 dependencies added, StorageObjectMetadata record, StorageService interface, StorageKeyGenerator service, S3StorageService with retry/recover on all 6 operations, StorageConfig S3 bean wiring, region externalized; 16 unit tests pass, BUILD SUCCESS.
