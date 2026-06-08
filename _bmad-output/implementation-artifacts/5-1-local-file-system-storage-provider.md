# Story 5.1: Local File System Storage Provider

Status: done

## Story

As a developer working locally or writing unit tests,
I want a `LocalFileSystemStorageService` that mirrors the S3 bucket directory structure,
so that I can develop and test storage logic without needing a running S3 or MinIO instance.

## Acceptance Criteria

1. **Provider config routes to LocalFS**: Given `app.storage.provider=local` is configured with a base directory, when `StorageConfig` creates the `StorageService` bean, then `LocalFileSystemStorageService` is wired as the active implementation using `app.storage.local.base-dir`.

2. **put creates the correct directory structure**: Given `LocalFileSystemStorageService.put(key, data, contentLength, contentType)` is called with key `documents/42/2026/05/uuid.pdf`, when the operation executes, then the file is written to `{baseDir}/documents/42/2026/05/uuid.pdf` (mirroring the S3 key as a relative path), intermediate directories are created automatically, and all I/O uses streaming — no `readAllBytes()` or byte array buffering.

3. **get returns the correct stream**: Given the key exists, when `get(key)` is called, then a `StorageObject` is returned with an open `InputStream` and populated `StorageObjectMetadata` (key, contentLength, lastModified, contentType); get on a non-existent key throws `StorageObjectNotFoundException`.

4. **delete removes the file**: Given the key exists, when `delete(key)` is called, then the file is removed; calling `delete` on a non-existent key does NOT throw (idempotent, matching S3 semantics).

5. **exists returns true/false correctly**: Given a key that exists, `exists(key)` returns `true`; given a key that does not exist, `exists(key)` returns `false`.

6. **stat returns correct metadata**: Given the key exists, when `stat(key)` is called, then `StorageObjectMetadata` is returned with the correct key, contentLength (actual file size), and lastModified; stat on a non-existent key throws `StorageObjectNotFoundException`.

7. **copy duplicates the file to a new key**: Given `copy(sourceKey, destinationKey)` is called, then the file at `sourceKey` is duplicated to `destinationKey` including its directory structure; copy from a non-existent key throws `StorageObjectNotFoundException`.

8. **Pure unit test covers all operations**: Given `LocalFileSystemStorageServiceTest` (pure unit test, no containers, no Spring context), when it runs, then it covers: put creates directory structure; get returns correct content stream; delete removes the file; exists returns true/false correctly; stat returns metadata; copy duplicates file to new key; get/stat/copy of non-existent key throws `StorageObjectNotFoundException`.

## Tasks / Subtasks

- [x] Task 1: Add `Local` nested class to `StorageProperties` and YAML config (AC: 1)
  - [x] Add `private Local local = new Local();` field to `StorageProperties`
  - [x] Add `Local` nested class: `@Getter @Setter public static class Local { private String baseDir = "/tmp/skillars-storage"; }`
  - [x] Add `app.storage.local.base-dir: ${APP_STORAGE_LOCAL_BASE_DIR:/tmp/skillars-storage}` under `app.storage:` in `application.yaml`
  - [x] File: `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageProperties.java`
  - [x] File: `src/main/resources/application.yaml`

- [x] Task 2: Add `@ConditionalOnProperty` to `S3StorageService` (AC: 1)
  - [x] Add class-level annotation: `@ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3", matchIfMissing = true)` to `S3StorageService`
  - [x] This prevents Spring component scan from constructing `S3StorageService` (and failing on missing `S3Client` bean) when `provider=local`
  - [x] **DO NOT** remove `@Service` — keep it; the `@ConditionalOnProperty` acts as a guard
  - [x] File: `src/main/java/com/softropic/skillars/infrastructure/storage/service/S3StorageService.java`

- [x] Task 3: Create `LocalFileSystemStorageService` (AC: 2–7)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/service/LocalFileSystemStorageService.java`
  - [x] Class: `@Slf4j @RequiredArgsConstructor` — NO `@Service` annotation (created via `@Bean` in `StorageConfig`, following `ReplicatedStorageService`/`OutboxPollerScheduler` pattern)
  - [x] Single constructor field: `private final Path baseDir`
  - [x] `put(key, data, contentLength, contentType)`: call `resolvePath(key)`, create parent dirs with `Files.createDirectories(target.getParent())`, stream with `Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING)`. Wrap `IOException` in `StorageProviderException("put", e)`.
  - [x] `get(key)`: call `resolvePath(key)`. If `!Files.exists(target)` → throw `StorageObjectNotFoundException(key)`. Build metadata via `buildMetadata(key, target)`. Return `new StorageObject(Files.newInputStream(target), metadata)`. Wrap `IOException` in `StorageProviderException("get", e)`.
  - [x] `delete(key)`: call `Files.deleteIfExists(resolvePath(key))`. Wrap `IOException` in `StorageProviderException("delete", e)`. **DO NOT** throw if file is absent (matches S3 idempotent delete).
  - [x] `exists(key)`: return `Files.exists(resolvePath(key))`.
  - [x] `stat(key)`: call `resolvePath(key)`. If `!Files.exists(target)` → throw `StorageObjectNotFoundException(key)`. Return `buildMetadata(key, target)`. Wrap `IOException` in `StorageProviderException("stat", e)`.
  - [x] `copy(sourceKey, destinationKey)`: resolve source; if `!Files.exists(source)` → throw `StorageObjectNotFoundException(sourceKey)`. Resolve dest, create parent dirs, call `Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING)`. Wrap `IOException` in `StorageProviderException("copy", e)`.
  - [x] Private `resolvePath(String key)`: `Path resolved = baseDir.resolve(key).normalize(); if (!resolved.startsWith(baseDir)) throw new StorageProviderException("path-resolve", new IllegalArgumentException("key escapes base dir")); return resolved;`
  - [x] Private `buildMetadata(String key, Path path)`: `long size = Files.size(path); Instant lastModified = Files.getLastModifiedTime(path).toInstant(); String ct = Files.probeContentType(path); if (ct == null) ct = "application/octet-stream"; return new StorageObjectMetadata(key, ct, size, "", lastModified);`
  - [x] **STREAMING RULE**: `Files.copy(InputStream, Path, ...)` is fully streaming. `Files.newInputStream(Path)` returns a stream without loading bytes. `InputStream.readAllBytes()` is NEVER called anywhere in this class.

- [x] Task 4: Wire `LocalFileSystemStorageService` bean in `StorageConfig` (AC: 1)
  - [x] Add a new `@Bean` method named `storageService` with `@ConditionalOnProperty(name = "app.storage.provider", havingValue = "local")`:
    ```java
    @Bean("localStorageService")
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "local")
    public StorageService localStorageService(StorageProperties properties) {
        Path baseDir = Path.of(properties.getLocal().getBaseDir());
        return new LocalFileSystemStorageService(baseDir);
    }
    ```
  - [x] The bean is named `"localStorageService"` to avoid method name collision with the existing `storageService` @Bean (which is conditional on `provider=s3`). With `provider=local` exactly ONE `StorageService` bean exists in context → resolved by type without ambiguity.
  - [x] Add required imports: `java.nio.file.Path`
  - [x] File: `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageConfig.java`

- [x] Task 5: Write `LocalFileSystemStorageServiceTest` (AC: 8)
  - [x] Create `src/test/java/com/softropic/skillars/infrastructure/storage/service/LocalFileSystemStorageServiceTest.java`
  - [x] Class-level `@TempDir Path tempDir;` for file isolation
  - [x] `@BeforeEach` constructs `service = new LocalFileSystemStorageService(tempDir);` — no Spring context
  - [x] Test `put_createsCorrectDirectoryStructure`: call `service.put("docs/42/2026/05/uuid.txt", ...)` with a `ByteArrayInputStream`; assert `Files.exists(tempDir.resolve("docs/42/2026/05/uuid.txt"))` is true.
  - [x] Test `get_returnsCorrectStreamAndMetadata`: put then get; use `try (InputStream in = obj.data())` and `in.readAllBytes()` in the test to verify content (using `readAllBytes()` IN TESTS IS FINE — forbidden only in production code); assert `obj.metadata().contentLength()` equals the byte count.
  - [x] Test `delete_removesFile`: put then delete; assert `Files.exists(target)` is false; verify second `delete` does NOT throw (idempotent).
  - [x] Test `exists_returnsTrueForExistingKey`: put then assert `exists(key)` is true; assert `exists("nonexistent/key.txt")` is false.
  - [x] Test `stat_returnsCorrectMetadata`: put then stat; assert `meta.key()` and `meta.contentLength()` match; assert `meta.lastModified()` is not null; do NOT assert `contentType` (OS-dependent from `probeContentType`).
  - [x] Test `copy_duplicatesFileToNewKey`: put to sourceKey; copy to destKey; assert both files exist with same content.
  - [x] Test `get_nonExistentKey_throwsStorageObjectNotFoundException`: assert `assertThatThrownBy(() -> service.get("missing/key.txt")).isInstanceOf(StorageObjectNotFoundException.class)`.
  - [x] Test `stat_nonExistentKey_throwsStorageObjectNotFoundException`: same pattern.
  - [x] Test `copy_nonExistentSourceKey_throwsStorageObjectNotFoundException`: same pattern.
  - [x] **AssertJ** for all assertions (`assertThat`, `assertThatThrownBy`). **No Mockito** — this is a pure filesystem test.

- [x] Task 6: Verify build and regression (AC: all)
  - [x] Run `mvn test -Dtest=LocalFileSystemStorageServiceTest` — all 9 unit tests pass
  - [x] Run full test suite — 211 tests pass (202 baseline + 9 new; existing IT tests use MinIO via `provider=s3`; no change to their behavior)
  - [x] Verify no new bean conflicts: with `@ActiveProfiles({"dev","test"})`, `provider=s3` (from dev profile) → `S3StorageService` is component-scanned (condition matches) → no change in behavior

## Dev Notes

### Critical: NO `@Service` on `LocalFileSystemStorageService`

Pattern established by `ReplicatedStorageService` and `OutboxPollerScheduler`: do NOT annotate with `@Service`. Register exclusively via `@Bean @ConditionalOnProperty` in `StorageConfig`. If `@Service` is added, Spring component scan registers it unconditionally as a bean — then it tries to be a `StorageService` candidate alongside the S3 bean when `provider=s3`, causing `NoUniqueBeanDefinitionException` or priority conflicts.

### Critical: `S3StorageService` Must Be Made Conditional

`S3StorageService` is currently annotated `@Service` with no condition. Its constructor requires `S3Client` and `S3TransferManager`, which are `@ConditionalOnProperty(provider=s3)` beans. When `provider=local`, those beans are absent → Spring context fails to construct `S3StorageService`. **Fix: add `@ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3", matchIfMissing = true)` at class level to `S3StorageService`.**

This is a retroactive fix that has ZERO impact on existing behavior: when `provider=s3` (all current configs, including test profiles), the condition matches and `S3StorageService` is registered exactly as before.

### Known Limitation: `StorageSigningService` is S3-Specific

`StorageSigningService` directly injects `S3Presigner` and `S3Client`. These beans are absent when `provider=local`. Consequence: a full application startup with `provider=local` will fail at the `StorageSigningService` bean creation. **This is outside Story 5.1 scope.** The local provider is intended for:
1. **Unit tests** that directly instantiate `new LocalFileSystemStorageService(baseDir)` (no Spring context)
2. **Future work** to make `StorageSigningService` provider-conditional (can be addressed in Story 5.2 or 5.3)

For Story 5.1, the deliverable is the `LocalFileSystemStorageService` class and its `StorageConfig` `@Bean` wiring. The unit tests validate all operations without needing a Spring context.

### Streaming — `Files.copy(InputStream, Path)` Is the Correct Pattern

```java
// ✅ CORRECT — fully streaming
Files.createDirectories(target.getParent());
Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);

// ✅ CORRECT — for reading back
return new StorageObject(Files.newInputStream(target), metadata);

// ❌ FORBIDDEN — violates NFR-02
byte[] bytes = data.readAllBytes();
Files.write(target, bytes);
```

`Files.copy(InputStream, Path, CopyOption...)` uses an internal fixed buffer (8KB default) to transfer bytes from stream to file — no heap allocation for file content.

### `buildMetadata` — eTag is Empty String for Local Provider

S3 eTag is an MD5 of the object. Local FS has no equivalent. Return `""` (empty string). The eTag field is only used in `StorageSigningService.confirmUpload()` (validate actual vs declared) — that flow uses S3 directly, never `LocalFileSystemStorageService.stat()`. Returning `""` is safe and consistent.

### `resolvePath` Security Guard (Path Traversal Prevention)

```java
private Path resolvePath(String key) {
    Path resolved = baseDir.resolve(key).normalize();
    if (!resolved.startsWith(baseDir)) {
        throw new StorageProviderException("path-resolve",
            new IllegalArgumentException("key escapes base directory: " + key));
    }
    return resolved;
}
```

`normalize()` collapses `..` sequences. The `startsWith` check ensures the result stays under `baseDir`. This mirrors the `FilenameSanitizationStep` logic in `ValidationChain` but acts as a defense-in-depth at the service layer.

### `copy` — Use `Files.copy(Path, Path, ...)` Not Stream-Based

For file-to-file copying, the NIO `Files.copy(Path, Path, CopyOption...)` is OS-optimized (zero-copy on Linux) and does not go through Java heap. It is NOT the same as loading the file — it is streaming at the OS level.

```java
Files.createDirectories(dest.getParent());
Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
```

### `StorageConfig` Bean Naming: `"localStorageService"` vs `"storageService"`

The existing `storageService` @Bean method creates a bean named `"storageService"` conditional on `provider=s3`. A new `@Bean` method in the SAME `@Configuration` class cannot share the same method name (Java does not allow duplicate method names with the same signature). Name the local provider bean `"localStorageService"`.

With `provider=local`, exactly one `StorageService` bean (`localStorageService`) exists in context → Spring resolves injections by type without ambiguity. Existing `@Qualifier("storageService")` usages (in `OutboxPollerScheduler` wiring) are only active when `provider=s3` and `replication.enabled=true` — no conflict.

### `StorageProperties` Change — Addition Only

The `StorageProperties` change is additive: a new `local` nested object with `baseDir`. Existing `@NotBlank` on `provider`, `bucket`, and `endpointUrl` remain. Note: `endpointUrl` has `@NotBlank` — when `provider=local`, there's no real endpoint URL. The existing `application.yaml` already has `endpoint-url: ${APP_STORAGE_ENDPOINT_URL:http://localhost:9000}` which provides a non-blank default. This constraint remains satisfied even when `provider=local`.

### Unit Test Pattern — Direct Construction with `@TempDir`

```java
class LocalFileSystemStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalFileSystemStorageService service;

    @BeforeEach
    void setUp() {
        service = new LocalFileSystemStorageService(tempDir);
    }
}
```

`@TempDir` creates a fresh directory per test class. Because `LocalFileSystemStorageService` requires only `Path baseDir`, it can be instantiated directly — no Spring context, no mocks. This is the cleanest unit test pattern for a filesystem-backed service.

### Integration with Existing Tests — Zero Impact

The test profile (`@ActiveProfiles({"dev", "test"})`) uses `app.storage.provider=s3` (from `application-dev.yaml` or the default in `application.yaml`). Adding `@ConditionalOnProperty(provider=s3)` to `S3StorageService` does NOT change behavior for existing tests: the condition matches, `S3StorageService` is component-scanned and registered, MinIO is used — everything remains identical.

The new `LocalFileSystemStorageServiceTest` is a standalone class that does not extend `BaseStorageIT` and does not start a Spring context. It runs entirely in JVM without any container.

### Previous Story Intelligence (Story 4.2)

- **`@ConditionalOnProperty` pattern** — `@Bean @ConditionalOnProperty(replication.enabled=true)` for `OutboxPollerScheduler`; use identical approach `@ConditionalOnProperty(provider=local)` for `LocalFileSystemStorageService`
- **No `@Service` pattern** — `OutboxPollerScheduler` and `ReplicatedStorageService` both omit `@Service`; same for `LocalFileSystemStorageService`
- **`StorageProviderException(String operation, Throwable cause)`** — the only available constructor; wrap all `IOException` as this exception with a descriptive operation name
- **`StorageObjectNotFoundException(String key)`** — single-arg constructor for "file not found" case (no cause available from filesystem operation); use this for `get`/`stat`/`copy` on missing keys
- **Test baseline: 202 tests** — full regression suite must remain ≥202 passing after this story

### Project Structure Notes

**Files to CREATE:**
```
src/main/java/com/softropic/skillars/infrastructure/storage/service/
└── LocalFileSystemStorageService.java       ← NEW: no @Service, @Slf4j @RequiredArgsConstructor

src/test/java/com/softropic/skillars/infrastructure/storage/service/
└── LocalFileSystemStorageServiceTest.java   ← NEW: pure unit test, @TempDir
```

**Files to MODIFY:**
```
src/main/java/com/softropic/skillars/infrastructure/storage/
├── config/StorageProperties.java            ← ADD: Local nested class + local field
├── config/StorageConfig.java                ← ADD: @Bean localStorageService(provider=local)
└── service/S3StorageService.java            ← ADD: @ConditionalOnProperty(provider=s3) at class level

src/main/resources/application.yaml          ← ADD: app.storage.local.base-dir
```

**Files to NOT modify:**
- `StorageSigningService.java` — S3-specific; leave as-is (provider=local limitation is documented)
- `StorageResource.java` — no change
- `BaseStorageIT.java` — no change
- `application-dev.yaml` — no change (tests continue using provider=s3 via MinIO)
- All existing test files — no change

### References

- [Source: epics.md#Story 5.1] — Full BDD acceptance criteria, directory mirroring, streaming rule, unit test coverage
- [Source: architecture.md#Structure Patterns] — `LocalFileSystemStorageService.java` in `service/` package
- [Source: architecture.md#Enforcement Guidelines] — no `readAllBytes()`, streaming I/O only
- [Source: architecture.md#Gaps Resolved — Addendum] — `LocalFileSystemStorageService` must mirror the directory structure of the cloud bucket strategy
- [Source: project-context.md#Testing Rules] — No Spring context in unit tests; AssertJ assertions; use `@TempDir` for filesystem tests
- [Source: project-context.md#Language-Specific Rules] — `@Slf4j`, `@RequiredArgsConstructor` on services
- [Source: StorageService.java] — Interface to implement: `put`, `get`, `delete`, `exists`, `stat`, `copy`
- [Source: S3StorageService.java] — Current implementation; `@Service`-annotated; requires `@ConditionalOnProperty` addition
- [Source: StorageConfig.java] — Bean wiring patterns; existing `storageService` bean conditional on `provider=s3`; `backupStorageService` bean name pattern; `@ConditionalOnProperty` usage
- [Source: StorageProperties.java] — `@Getter @Setter @Validated @ConfigurationProperties`; existing nested class pattern (`Replication`, `Quota`, `Poller`, etc.)
- [Source: StorageObject.java] — `record StorageObject(InputStream data, StorageObjectMetadata metadata)`
- [Source: StorageObjectMetadata.java] — `record StorageObjectMetadata(String key, String contentType, long contentLength, String eTag, Instant lastModified)`
- [Source: StorageObjectNotFoundException.java] — `StorageObjectNotFoundException(String key)` — single-arg constructor
- [Source: StorageProviderException.java] — `StorageProviderException(String operation, Throwable cause)` — wraps IOExceptions
- [Source: ReplicatedStorageService.java] — No `@Service`; constructor-injected; created via `@Bean` in `StorageConfig` — follow this exact pattern
- [Source: S3StorageServiceTest.java] — Unit test pattern; `@BeforeEach` direct construction; Mockito for S3 mocks (not needed here — use `@TempDir` instead)
- [Source: 4-2-async-replication-outbox-poller.md#Dev Notes] — `@ConditionalOnProperty` bean pattern, no `@Service`, TransactionCallback mock pattern (not relevant here but shows established conventions)

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

(none)

### Completion Notes List

- Implemented `LocalFileSystemStorageService` with all 6 `StorageService` operations (put, get, delete, exists, stat, copy) using fully streaming NIO APIs — no `readAllBytes()` in production code.
- Added path traversal guard in `resolvePath()`: normalizes key and asserts result stays under `baseDir`.
- Added `@ConditionalOnProperty(provider=s3, matchIfMissing=true)` to `S3StorageService` — zero impact on existing tests (all use `provider=s3`).
- Wired `@Bean("localStorageService")` conditional on `provider=local` in `StorageConfig`; named to avoid method-name conflict with existing `storageService` bean.
- 9 pure unit tests with `@TempDir` — no Spring context, no mocks. All pass.
- Full regression: 211 tests pass (202 baseline + 9 new). No regressions.

### File List

- `src/main/java/com/softropic/skillars/infrastructure/storage/service/LocalFileSystemStorageService.java` (NEW)
- `src/test/java/com/softropic/skillars/infrastructure/storage/service/LocalFileSystemStorageServiceTest.java` (NEW)
- `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageProperties.java` (MODIFIED)
- `src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageConfig.java` (MODIFIED)
- `src/main/java/com/softropic/skillars/infrastructure/storage/service/S3StorageService.java` (MODIFIED)
- `src/main/resources/application.yaml` (MODIFIED)

### Change Log

- 2026-05-26: Story 5.1 implemented — Added `LocalFileSystemStorageService` with all 6 storage operations, `@ConditionalOnProperty` guard on `S3StorageService`, `Local` nested class in `StorageProperties`, local bean wiring in `StorageConfig`, and 9 pure unit tests. 211/211 tests pass.
