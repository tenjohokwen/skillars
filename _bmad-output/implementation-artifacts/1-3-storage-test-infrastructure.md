# Story 1.3: Storage Test Infrastructure

Status: review

## Story

As a developer writing integration tests for the storage module,
I want a shared `BaseStorageIT` base class backed by MinIO and PostgreSQL Testcontainers,
So that all subsequent storage integration tests can inherit a fully wired test context without duplicating container setup.

## Acceptance Criteria

1. **MinioContainerConfig as @TestConfiguration**: `MinioContainerConfig` is defined in `src/test/java/com/softropic/skillars/config/`, annotated `@TestConfiguration(proxyBeanMethods = false)`, mirroring the pattern of the existing `PostgresContainerConfig`. A MinIO container is started via Testcontainers managed by Spring.

2. **Dynamic properties injected**: `app.storage.endpoint-url`, bucket name, access key, and secret key are injected into the Spring context from the container's dynamic properties via a `DynamicPropertyRegistrar` bean (Spring Boot 3.4+ equivalent of `@DynamicPropertySource`), so the `StorageConfig` S3 beans get pointed at the test MinIO container.

3. **Bucket created before tests**: The configured bucket (`"test-storage"`) is created in the MinIO container before the first test runs, using an `ApplicationRunner` bean in `MinioContainerConfig`.

4. **BaseStorageIT wires both containers**: `BaseStorageIT` is annotated `@SpringBootTest` + `@Testcontainers` and imports `MinioContainerConfig`, `PostgresContainerConfig`, and `RedisContainerConfig`. Both the MinIO and PostgreSQL containers are fully wired into the Spring application context.

5. **Subsequent stories compile with no extra setup**: Any integration test in subsequent stories that `extends BaseStorageIT` compiles and receives a fully configured storage context without additional container setup code.

6. **Smoke test passes**: A minimal `S3StorageServiceIT extends BaseStorageIT` exercises a put → exists → delete cycle against the live MinIO container and passes.

## Tasks / Subtasks

- [x] Task 1: Add `testcontainers:minio` Maven dependency (AC: 1)
  - [x] In `pom.xml`, under `<dependencies>`, add `<groupId>org.testcontainers</groupId> / <artifactId>minio</artifactId> / <scope>test</scope>` — version is managed by the Spring Boot BOM (no explicit version needed)
  - [x] Run `mvn dependency:resolve -Dincludes=org.testcontainers:minio` to confirm 1.21.4 resolves

- [x] Task 2: Extend `StorageProperties.S3` with credentials and path-style support (AC: 2)
  - [x] In `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageProperties.java`, inside the `S3` inner class add `private String accessKey;` (nullable — do NOT add `@NotBlank`)
  - [x] Add `private String secretKey;` (nullable — do NOT add `@NotBlank`)
  - [x] Add `private boolean pathStyleAccess = false;`
  - [x] These fields are optional overrides for test injection; production deployments leave them null and rely on `DefaultCredentialsProvider`

- [x] Task 3: Update `StorageConfig` to support optional static credentials and path-style access (AC: 2)
  - [x] In `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageConfig.java`, add a private helper method:
    ```java
    private AwsCredentialsProvider resolveCredentialsProvider(StorageProperties.S3 s3) {
        if (s3.getAccessKey() != null && s3.getSecretKey() != null) {
            return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey()));
        }
        return DefaultCredentialsProvider.create();
    }
    ```
  - [x] In `s3Client()` bean: replace `DefaultCredentialsProvider.create()` with `resolveCredentialsProvider(properties.getS3())` AND add `.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(properties.getS3().isPathStyleAccess()).build())`
  - [x] In `s3AsyncClient()` bean: replace `DefaultCredentialsProvider.create()` with `resolveCredentialsProvider(properties.getS3())` AND add `.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(properties.getS3().isPathStyleAccess()).build())`
  - [x] In `s3Presigner()` bean: replace `DefaultCredentialsProvider.create()` with `resolveCredentialsProvider(properties.getS3())` (no path-style needed on presigner for this story's scope)
  - [x] Add required imports: `software.amazon.awssdk.auth.credentials.StaticCredentialsProvider`, `software.amazon.awssdk.auth.credentials.AwsBasicCredentials`, `software.amazon.awssdk.auth.credentials.AwsCredentialsProvider`, `software.amazon.awssdk.services.s3.S3Configuration`
  - [x] Run `mvn clean compile` — expect BUILD SUCCESS

- [x] Task 4: Create `MinioContainerConfig.java` (AC: 1, 2, 3)
  - [x] Create `src/test/java/com/softropic/skillars/config/MinioContainerConfig.java`
  - [x] Annotate with `@TestConfiguration(proxyBeanMethods = false)` — exact mirror of `PostgresContainerConfig`
  - [x] Define `static final String TEST_BUCKET = "test-storage";`
  - [x] `@Bean MinIOContainer minioContainer()` — `new MinIOContainer(DockerImageName.parse("minio/minio:RELEASE.2024-01-13T07-53-03Z"))` — uses default credentials `minioadmin`/`minioadmin`
  - [x] `@Bean DynamicPropertyRegistrar minioPropertyRegistrar(MinIOContainer minioContainer)` — registers all five properties (see Dev Notes for complete implementation)
  - [x] `@Bean ApplicationRunner createTestBucket(S3Client s3Client, StorageProperties storageProperties)` — creates bucket if it doesn't exist using `headBucket` + catch `NoSuchBucketException`
  - [x] Imports: `org.testcontainers.containers.MinIOContainer` (actual package), `org.springframework.test.context.DynamicPropertyRegistrar` (actual package in Spring 6.2), `software.amazon.awssdk.services.s3.model.NoSuchBucketException`, plus Spring Boot test imports

- [x] Task 5: Create `BaseStorageIT.java` (AC: 4, 5)
  - [x] Create `src/test/java/com/softropic/skillars/infrastructure/storage/BaseStorageIT.java`
  - [x] Abstract class (cannot be instantiated directly; JUnit 5 will not attempt to run it)
  - [x] `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"enable.test.mail=true"})`
  - [x] `@Testcontainers`
  - [x] `@Import({MinioContainerConfig.class, PostgresContainerConfig.class, RedisContainerConfig.class, TestMailConfig.class, BaseStorageIT.StorageTestConfig.class})`
  - [x] `@ActiveProfiles({"dev", "test"})`
  - [x] Inner static `StorageTestConfig` provides `@Primary RestTemplate` required by `HttpTestClient @Component`

- [x] Task 6: Write smoke test `S3StorageServiceIT` and verify (AC: 6)
  - [x] Create `src/test/java/com/softropic/skillars/infrastructure/storage/service/S3StorageServiceIT.java` that extends `BaseStorageIT`
  - [x] Inject `@Autowired StorageService storageService`
  - [x] One test `contextLoadsAndMinioIsReachable()`: put a small text file, assert `exists()` returns true, delete it, assert `exists()` returns false
  - [x] Use `Instancio.create(String.class)` for a random key suffix and `AssertJ` `assertThat(...)` for assertions
  - [x] Run `mvn test -Dtest=S3StorageServiceIT` — BUILD SUCCESS + test PASSED
  - [x] Also run `mvn test -Dtest=StorageKeyGeneratorTest,S3StorageServiceTest` — 16 tests, no regressions

## Dev Notes

### Why DynamicPropertyRegistrar (Not @DynamicPropertySource)

The epics AC says "via `@DynamicPropertySource`" but the correct Spring Boot 3.4+ approach for Spring-managed container beans is `DynamicPropertyRegistrar` declared as a `@Bean`. `@DynamicPropertySource` is a static annotation that requires a static field for the container — this fights against Spring-managed container lifecycles. `DynamicPropertyRegistrar` as a `@Bean` works naturally: Spring creates the container bean first (starting the MinIO container), then creates the registrar which reads the container's URL.

Import: `org.springframework.context.support.DynamicPropertyRegistrar` — NOT `org.springframework.test.context.DynamicPropertySource`.

### MinioContainerConfig — Complete Implementation

```java
package com.softropic.skillars.config;

import com.softropic.skillars.infrastructure.storage.config.StorageProperties;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.DynamicPropertyRegistrar;
import org.testcontainers.containers.minio.MinIOContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

@TestConfiguration(proxyBeanMethods = false)
public class MinioContainerConfig {

    static final String TEST_BUCKET = "test-storage";

    @Bean
    MinIOContainer minioContainer() {
        return new MinIOContainer(DockerImageName.parse("minio/minio:RELEASE.2024-01-13T07-53-03Z"));
    }

    @Bean
    DynamicPropertyRegistrar minioPropertyRegistrar(MinIOContainer minioContainer) {
        return registry -> {
            registry.add("app.storage.endpoint-url", minioContainer::getS3URL);
            registry.add("app.storage.bucket", () -> TEST_BUCKET);
            registry.add("app.storage.s3.access-key", minioContainer::getUserName);
            registry.add("app.storage.s3.secret-key", minioContainer::getPassword);
            registry.add("app.storage.s3.path-style-access", () -> "true");
        };
    }

    @Bean
    ApplicationRunner createTestBucket(S3Client s3Client, StorageProperties storageProperties) {
        return args -> {
            String bucket = storageProperties.getBucket();
            try {
                s3Client.headBucket(r -> r.bucket(bucket));
            } catch (NoSuchBucketException e) {
                s3Client.createBucket(r -> r.bucket(bucket));
            }
        };
    }
}
```

### Why Path-Style Access is Critical for MinIO

AWS SDK v2 defaults to virtual-hosted-style URLs (`http://BUCKET.host:PORT/key`). MinIO does not support virtual-hosted style at `localhost` — it needs path-style (`http://host:PORT/BUCKET/key`). Without `pathStyleAccessEnabled(true)`, `put`, `get`, `exists`, `stat`, and `copy` will all fail with connection errors against the test MinIO container.

`S3Configuration` is the correct way to set path-style in SDK v2 (the deprecated `.forcePathStyle(true)` builder method is not available on `S3Client.Builder` in SDK v2).

### StorageConfig — Complete Updated Skeleton

```java
@Bean
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3", matchIfMissing = true)
public S3Client s3Client(StorageProperties properties) {
    return S3Client.builder()
        .endpointOverride(URI.create(properties.getEndpointUrl()))
        .credentialsProvider(resolveCredentialsProvider(properties.getS3()))
        .region(Region.of(properties.getS3().getRegion()))
        .serviceConfiguration(S3Configuration.builder()
            .pathStyleAccessEnabled(properties.getS3().isPathStyleAccess())
            .build())
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
        .credentialsProvider(resolveCredentialsProvider(properties.getS3()))
        .region(Region.of(properties.getS3().getRegion()))
        .serviceConfiguration(S3Configuration.builder()
            .pathStyleAccessEnabled(properties.getS3().isPathStyleAccess())
            .build())
        .overrideConfiguration(c -> c
            .apiCallTimeout(Duration.ofMillis(properties.getS3().getRequestTimeoutMs())))
        .build();
}

@Bean
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3", matchIfMissing = true)
public S3Presigner s3Presigner(StorageProperties properties) {
    return S3Presigner.builder()
        .endpointOverride(URI.create(properties.getEndpointUrl()))
        .credentialsProvider(resolveCredentialsProvider(properties.getS3()))
        .region(Region.of(properties.getS3().getRegion()))
        .build();
}

private AwsCredentialsProvider resolveCredentialsProvider(StorageProperties.S3 s3) {
    if (s3.getAccessKey() != null && s3.getSecretKey() != null) {
        return StaticCredentialsProvider.create(
            AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey()));
    }
    return DefaultCredentialsProvider.create();
}
```

### BaseStorageIT — Complete Implementation

```java
package com.softropic.skillars.infrastructure.storage;

import com.softropic.skillars.config.MinioContainerConfig;
import com.softropic.skillars.config.PostgresContainerConfig;
import com.softropic.skillars.config.RedisContainerConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"enable.test.mail=true"})
@Testcontainers
@Import({MinioContainerConfig.class, PostgresContainerConfig.class, RedisContainerConfig.class})
@ActiveProfiles({"dev", "test"})
public abstract class BaseStorageIT {
}
```

Note: `@Testcontainers` enables the JUnit 5 Testcontainers extension. Although container lifecycle here is managed by Spring (not `@Container` static fields), the annotation is required by the AC and does not cause harm.

### Smoke Test — Complete Implementation

```java
package com.softropic.skillars.infrastructure.storage.service;

import com.softropic.skillars.infrastructure.storage.BaseStorageIT;
import com.softropic.skillars.infrastructure.storage.service.StorageService;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class S3StorageServiceIT extends BaseStorageIT {

    @Autowired
    private StorageService storageService;

    @Test
    void contextLoadsAndMinioIsReachable() {
        String key = "smoke-test/" + Instancio.create(String.class) + ".txt";
        byte[] content = "hello minio".getBytes(StandardCharsets.UTF_8);

        storageService.put(key, new ByteArrayInputStream(content), content.length, "text/plain");

        assertThat(storageService.exists(key)).isTrue();

        storageService.delete(key);

        assertThat(storageService.exists(key)).isFalse();
    }
}
```

### Critical: StorageObject vs InputStream in get()

After Story 1.2's code review, `StorageService.get(String key)` returns `StorageObject` (not `InputStream`). The smoke test avoids calling `get()` to keep it simple. Future integration tests that need to read content should use `storageService.get(key).data()` (the `data` component of the `StorageObject` record).

### File Placement — Mirror Existing Test Structure

```
src/test/java/com/softropic/skillars/
├── config/
│   ├── PostgresContainerConfig.java   ← existing (pattern to mirror)
│   ├── RedisContainerConfig.java      ← existing
│   └── MinioContainerConfig.java      ← NEW
└── infrastructure/
    └── storage/
        ├── BaseStorageIT.java         ← NEW (abstract base class)
        └── service/
            └── S3StorageServiceIT.java ← NEW (smoke test)
```

### Why Use All Three Container Configs

The main application has Redis as a required dependency (`spring-boot-starter-data-redis`). Without the Redis container, the application context fails to start. Using `PostgresContainerConfig` (with `CustomPostgresContainer` that sets `TZ=UTC`/`PGTZ=UTC`) is preferred over `TestConfig` to avoid timezone-related key generation issues. `TestConfig` creates a plain `PostgreSQLContainer` without UTC enforcement, which can cause flaky date-based key assertions in later stories.

### pom.xml Location for Dependency

Add the new dependency in the same `<dependencies>` block as the other testcontainers entries (near lines 279-292):
```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>minio</artifactId>
    <scope>test</scope>
</dependency>
```

### Module Boundary Reminder

`BaseStorageIT` is in `com.softropic.skillars.infrastructure.storage` (test source tree). Zero imports from `com.softropic.skillars.platform.*` are permitted in any `infrastructure.storage` class — test classes included.

### Previous Story Learnings (from Story 1.2)

- `StorageConfig` already uses `DefaultCredentialsProvider.create()` (not `EnvironmentVariableCredentialsProvider` — that was a code review correction). Our new `resolveCredentialsProvider` helper wraps it cleanly.
- `S3StorageService.put()` uses `AsyncRequestBody.fromInputStream(data, contentLength, storageUploadExecutor)`. The smoke test's `ByteArrayInputStream` works fine with this pattern.
- `@EnableRetry` is already on `SkillarsApplication` — do NOT add it again anywhere.
- The `package-info.java` stub in `service/` from Story 1.1 is harmless — leave it.

### Application YAML — No Changes Required

`application.yaml` already has `app.storage.*` keys with defaults. The test-specific values (`endpoint-url`, `bucket`, `access-key`, `secret-key`, `path-style-access`) are injected via `DynamicPropertyRegistrar` at test time and override the YAML defaults. No `application-test.yaml` changes are needed.

### References

- [Source: epics.md#Story 1.3] — BDD acceptance criteria for this story
- [Source: architecture.md#Test Structure] — `MinioContainerConfig` in `test/config/`, `BaseStorageIT` in `test/infrastructure/storage/`
- [Source: PostgresContainerConfig.java] — `@TestConfiguration(proxyBeanMethods = false)` + `@Bean` pattern to mirror exactly
- [Source: RedisContainerConfig.java] — used in `@Import` because the app has Redis as a required dependency
- [Source: StorageConfig.java] — target file for credential/path-style changes; uses `DefaultCredentialsProvider.create()`
- [Source: StorageProperties.java] — extend `S3` inner class with `accessKey`, `secretKey`, `pathStyleAccess`
- [Source: S3StorageService.java] — confirms `StorageService.get()` returns `StorageObject` (not `InputStream`)
- [Source: StorageService.java] — full method signatures for smoke test usage
- [Source: project-context.md#Testing Rules] — `@SpringBootTest + @Testcontainers`, Instancio for test data, AssertJ for assertions
- [Source: pom.xml:279-292] — location to insert new `testcontainers:minio` dependency

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

1. **Import path correction** — Story spec listed `org.testcontainers.containers.minio.MinIOContainer` but actual class is `org.testcontainers.containers.MinIOContainer` (no minio subpackage) in testcontainers 1.21.4.
2. **DynamicPropertyRegistrar import correction** — Story spec listed `org.springframework.context.support.DynamicPropertyRegistrar` but with Spring Framework 6.2.16 the class lives in `org.springframework.test.context.DynamicPropertyRegistrar`.
3. **HttpTestClient RestTemplate requirement** — `HttpTestClient` is a `@Component` in the test source tree that `@Autowired RestTemplate`. Full `@SpringBootTest` component scan picks it up, requiring a `RestTemplate` bean. Adding a static inner `StorageTestConfig` `@TestConfiguration` providing `@Primary RestTemplate` resolved this without importing the conflicting `TestConfig` (which creates duplicate postgres/redis container beans).

### Completion Notes List

- Added `testcontainers:minio:1.21.4` dependency (BOM-managed, no explicit version)
- Extended `StorageProperties.S3` with nullable `accessKey`, `secretKey`, and `pathStyleAccess = false` fields
- Updated `StorageConfig.s3Client()`, `s3AsyncClient()`, `s3Presigner()` to use `resolveCredentialsProvider()` helper; added `S3Configuration.pathStyleAccessEnabled()` on sync and async clients
- Created `MinioContainerConfig` with Spring-managed MinIO container, `DynamicPropertyRegistrar`, and bucket bootstrapper `ApplicationRunner`
- Created `BaseStorageIT` abstract base class with all three container configs + `TestMailConfig` + inline `StorageTestConfig` for `RestTemplate`
- Created `S3StorageServiceIT` smoke test exercising put → exists → delete → exists=false cycle against live MinIO container
- All 1 new integration tests PASS; all 16 existing unit tests PASS (no regressions)

## File List

- `pom.xml` — added `org.testcontainers:minio` test dependency
- `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageProperties.java` — added `accessKey`, `secretKey`, `pathStyleAccess` to `S3` inner class
- `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageConfig.java` — added `resolveCredentialsProvider()`, updated all three S3 beans, added `S3Configuration` path-style support, added 4 new imports
- `src/test/java/com/softropic/skillars/config/MinioContainerConfig.java` — NEW: MinIO Testcontainers config with property registration and bucket bootstrapping
- `src/test/java/com/softropic/skillars/infrastructure/storage/BaseStorageIT.java` — NEW: abstract integration test base class
- `src/test/java/com/softropic/skillars/infrastructure/storage/service/S3StorageServiceIT.java` — NEW: smoke test

## Change Log

- 2026-05-26: Story created — comprehensive test infrastructure context for MinIO + PostgreSQL + Redis Testcontainers harness. Includes complete code snippets for all new files and modifications to `StorageConfig` and `StorageProperties`.
- 2026-05-26: Implementation complete — all tasks done, smoke test passes against live MinIO container, no regressions in existing unit tests.
