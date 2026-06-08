# Story 2.1: Pre-Storage Validation Chain

Status: review

## Story

As a developer building the upload endpoint,
I want a pluggable, ordered validation chain that enforces filename safety, MIME type, extension, file size, and checksum rules before any upload is accepted,
so that invalid or malicious files are rejected before a pre-signed URL is ever issued.

## Acceptance Criteria

1. **Chain executes in order, halts on first failure**: When `ValidationChain` is invoked with a `ValidationRequest`, it executes each `ValidationStep` in registration order (`@Order`), stopping on the first failure. A failed step throws `StorageValidationException` with `StorageErrorCode.VALIDATION_FAILED` and a descriptive message.

2. **FilenameSanitizationStep runs first and mutates the request**: Given a filename containing path traversal sequences (`../`, `./`), null bytes (`\0`), control characters (0x00–0x1F, 0x7F), or Unicode that normalizes unsafely (using NFC), `FilenameSanitizationStep` (annotated `@Order(1)`) strips/normalizes those sequences and writes the sanitized value back to `originalFilename` on the `ValidationRequest`. Filenames longer than 255 characters are truncated. This step always completes without throwing (sanitization, not rejection) and runs before any other step.

3. **MimeTypeValidationStep rejects unlisted MIME types**: Given a declared `contentType` not present in `app.storage.validation.allowed-mime-types`, `StorageValidationException` is thrown with a message referencing the rejected MIME type.

4. **ExtensionValidationStep rejects unlisted extensions**: Given a declared `extension` not in `app.storage.validation.allowed-extensions`, `StorageValidationException` is thrown.

5. **FileSizeValidationStep rejects oversized files**: Given a declared `fileSizeBytes` exceeding `app.storage.validation.max-size-bytes`, `StorageValidationException` is thrown.

6. **ChecksumValidationStep validates format only at sign-time**: Given a `SignUploadRequest` with a declared `checksum` field, `ChecksumValidationStep` validates format only: the value must be a valid SHA-256 hex string (exactly 64 lowercase hex characters). Content integrity is NOT verified at this stage (file not yet uploaded). If the format is invalid, `StorageValidationException` is thrown indicating the expected format. If `checksum` is null/blank, the step passes without error (checksum is optional at sign-time).

7. **Pluggable extension point**: Given the `ValidationStep` interface is defined as a `@Component`-annotated Spring bean, any developer can add a new step (e.g., image verification, antivirus) by implementing `ValidationStep`, annotating it `@Component`, and setting an `@Order` value — no changes to `ValidationChain` required.

8. **Unit tests pass covering all scenarios**: `ValidationChainTest` (pure unit test, no containers, no Spring context) covers: each step independently, chain halts on first failure and skips subsequent steps, chain passes when all steps pass, `FilenameSanitizationStep` ordering runs before all other steps, checksum step passes on null/blank checksum, checksum step passes on valid 64-char hex, checksum step fails on invalid format.

## Tasks / Subtasks

- [x] Task 1: Add `Validation` config block to `StorageProperties` and `application.yaml` (AC: 3, 4, 5)
  - [x] In `StorageProperties.java`, add a new inner static class `Validation` with `@Getter @Setter`:
    - `private List<String> allowedMimeTypes = List.of("image/jpeg", "image/png", "image/gif", "image/webp", "application/pdf", "text/plain", "application/octet-stream");`
    - `private List<String> allowedExtensions = List.of("jpg", "jpeg", "png", "gif", "webp", "pdf", "txt", "bin");`
    - `private long maxSizeBytes = 104857600L;` (100 MB default)
  - [x] Add `private Validation validation = new Validation();` field to `StorageProperties` (same level as `replication`, `quota`, etc.)
  - [x] In `application.yaml` under `app.storage:`, add:
    ```yaml
    validation:
      allowed-mime-types:
        - image/jpeg
        - image/png
        - image/gif
        - image/webp
        - application/pdf
        - text/plain
        - application/octet-stream
      allowed-extensions:
        - jpg
        - jpeg
        - png
        - gif
        - webp
        - pdf
        - txt
        - bin
      max-size-bytes: 104857600
    ```
  - [x] Run `mvn clean compile` — expect BUILD SUCCESS (no duplicate field errors; note existing S3 inner class has duplicate `requestTimeoutMs`/`connectionTimeoutMs` fields at lines 78-83 — leave them as-is, do NOT remove them)

- [x] Task 2: Create `ValidationRequest.java` mutable class in `contract/` (AC: 1, 2)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/contract/ValidationRequest.java`
  - [x] Use `@Getter @Setter @Builder @AllArgsConstructor @NoArgsConstructor` from Lombok (NOT a `record` — must be mutable so `FilenameSanitizationStep` can write back the sanitized filename)
  - [x] Fields: `String originalFilename`, `String contentType`, `String extension`, `long fileSizeBytes`, `String checksum` (nullable), `String tenantId`, `Map<String, String> tags`
  - [x] This is an internal service-layer object, NOT an HTTP DTO — mutable class is intentional and correct

- [x] Task 3: Create `ValidationStep.java` interface in `service/` (AC: 7)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/service/ValidationStep.java`
  - [x] Single method: `void validate(ValidationRequest request)` — throws `StorageValidationException` on failure
  - [x] No class-level annotation (implementations will be `@Component @Order(N)`)

- [x] Task 4: Create `FilenameSanitizationStep.java` (AC: 2)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/service/validation/FilenameSanitizationStep.java`
  - [x] Annotate `@Component @Order(1)` — MUST be first
  - [x] In `validate(ValidationRequest request)`:
    - If `originalFilename` is null or blank, set to `"unnamed"` and return
    - Normalize Unicode to NFC: `Normalizer.normalize(filename, Normalizer.Form.NFC)`
    - Strip null bytes: `filename.replace("\0", "")`
    - Strip control characters (0x00–0x1F, 0x7F): `filename.replaceAll("[\\x00-\\x1F\\x7F]", "")`
    - Strip path traversal: `filename.replaceAll("(\\.\\./|\\.\\./|^\\.\\./|^\\./)", "")` — use: `filename.replaceAll("\\.\\.[\\\\/]|[\\\\/]\\.\\.", "").replaceAll("^\\./", "")`
    - Truncate to 255 chars: `if (filename.length() > 255) filename = filename.substring(0, 255);`
    - Write back: `request.setOriginalFilename(filename)`
    - This step NEVER throws — it always sanitizes and continues
  - [x] Import: `java.text.Normalizer`

- [x] Task 5: Create `MimeTypeValidationStep.java` (AC: 3)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/service/validation/MimeTypeValidationStep.java`
  - [x] Annotate `@Component @Order(10) @RequiredArgsConstructor`
  - [x] Constructor-inject `StorageProperties properties`
  - [x] In `validate(ValidationRequest request)`: if `contentType` is null or not in `properties.getValidation().getAllowedMimeTypes()` (case-insensitive check), throw `new StorageValidationException("MIME type not allowed: " + request.getContentType())`

- [x] Task 6: Create `ExtensionValidationStep.java` (AC: 4)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/service/validation/ExtensionValidationStep.java`
  - [x] Annotate `@Component @Order(20) @RequiredArgsConstructor`
  - [x] Constructor-inject `StorageProperties properties`
  - [x] In `validate(ValidationRequest request)`: normalize extension to lowercase, check against `properties.getValidation().getAllowedExtensions()` (already lowercase). If null or not in list, throw `new StorageValidationException("Extension not allowed: " + request.getExtension())`

- [x] Task 7: Create `FileSizeValidationStep.java` (AC: 5)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/service/validation/FileSizeValidationStep.java`
  - [x] Annotate `@Component @Order(30) @RequiredArgsConstructor`
  - [x] Constructor-inject `StorageProperties properties`
  - [x] In `validate(ValidationRequest request)`: if `fileSizeBytes > properties.getValidation().getMaxSizeBytes()`, throw `new StorageValidationException("File size %d bytes exceeds maximum %d bytes".formatted(request.getFileSizeBytes(), properties.getValidation().getMaxSizeBytes()))`

- [x] Task 8: Create `ChecksumValidationStep.java` (AC: 6)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/service/validation/ChecksumValidationStep.java`
  - [x] Annotate `@Component @Order(40)`
  - [x] In `validate(ValidationRequest request)`:
    - If `checksum` is null or blank → return immediately (optional field at sign-time)
    - Define pattern: `private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$")`
    - If checksum does NOT match the pattern → throw `new StorageValidationException("Checksum must be a 64-character lowercase SHA-256 hex string, got: " + request.getChecksum())`
    - Do NOT verify content integrity here — that is Story 2.3's responsibility

- [x] Task 9: Create `ValidationChain.java` in `service/` (AC: 1, 7)
  - [x] Create `src/main/java/com/softropic/skillars/infrastructure/storage/service/ValidationChain.java`
  - [x] Annotate `@Service @RequiredArgsConstructor`
  - [x] Constructor-inject `List<ValidationStep> steps` — Spring automatically injects ALL `ValidationStep` beans, sorted by `@Order` value (ascending)
  - [x] One public method: `public void validate(ValidationRequest request)` — iterates `steps` in order, calling `step.validate(request)` on each. Steps that fail throw `StorageValidationException`, which propagates immediately (no try-catch in chain)
  - [x] No other logic in this class

- [x] Task 10: Write `ValidationChainTest.java` and run all tests (AC: 8)
  - [x] Create `src/test/java/com/softropic/skillars/infrastructure/storage/service/ValidationChainTest.java`
  - [x] Pure unit test — `@ExtendWith(MockitoExtension.class)`, no Spring context, no containers
  - [x] Build test instances directly: create `StorageProperties` with known config values, instantiate step classes with that config, assemble `ValidationChain` with a `List.of(step1, step2, ...)` passed directly to constructor
  - [x] Test cases to cover:
    - **FilenameSanitizationStep: strips path traversal** — input `"../../etc/passwd"` → sanitized (no `../` remaining)
    - **FilenameSanitizationStep: strips null bytes** — input `"file\0name.pdf"` → null byte removed
    - **FilenameSanitizationStep: strips control chars** — input `"filename.pdf"` → control char stripped
    - **FilenameSanitizationStep: truncates to 255** — input of 300-char filename → result.length() == 255
    - **FilenameSanitizationStep: handles null filename** — sets "unnamed"
    - **FilenameSanitizationStep: NFC normalization** — decomposed Unicode normalizes to NFC
    - **MimeTypeValidationStep: passes allowed type** — "image/jpeg" passes
    - **MimeTypeValidationStep: rejects unlisted type** — "application/x-executable" throws StorageValidationException
    - **ExtensionValidationStep: passes allowed extension** — "pdf" passes
    - **ExtensionValidationStep: rejects unlisted** — "exe" throws StorageValidationException
    - **FileSizeValidationStep: passes under limit** — 1MB passes
    - **FileSizeValidationStep: rejects over limit** — maxSizeBytes + 1 throws StorageValidationException
    - **ChecksumValidationStep: passes valid 64-char hex** — valid sha256 string passes
    - **ChecksumValidationStep: rejects wrong length** — 63 chars throws
    - **ChecksumValidationStep: rejects uppercase hex** — uppercase throws (must be lowercase)
    - **ChecksumValidationStep: passes on null checksum** — null passes (optional)
    - **ChecksumValidationStep: passes on blank checksum** — blank passes (optional)
    - **Chain: halts on first failure** — if MimeType step throws, subsequent steps are NOT called (use Mockito spy or order verification)
    - **Chain: passes when all steps pass** — valid request passes all steps without exception
    - **Chain: sanitization runs first** — even if chain has MIME step before sanitization step in the list, using `@Order` on real beans via the ordered List means sanitization actually goes first. For the unit test, manually order the list with sanitization first.
  - [x] Use `AssertJ` `assertThat`, `assertThatThrownBy`, `assertThatNoException`
  - [x] Run `mvn test -Dtest=ValidationChainTest` — BUILD SUCCESS, all tests pass
  - [x] Run `mvn test -Dtest=ValidationChainTest,StorageKeyGeneratorTest,S3StorageServiceTest,S3StorageServiceIT` to verify no regressions

## Dev Notes

### Package Location — New Files

```
src/main/java/com/softropic/skillars/infrastructure/storage/
├── contract/
│   └── ValidationRequest.java          ← NEW (mutable class, NOT record)
├── service/
│   ├── ValidationStep.java             ← NEW (interface)
│   ├── ValidationChain.java            ← NEW (@Service)
│   └── validation/                     ← NEW subdirectory
│       ├── FilenameSanitizationStep.java  ← @Order(1) — ALWAYS FIRST
│       ├── MimeTypeValidationStep.java    ← @Order(10)
│       ├── ExtensionValidationStep.java   ← @Order(20)
│       ├── FileSizeValidationStep.java    ← @Order(30)
│       └── ChecksumValidationStep.java    ← @Order(40)

src/main/java/com/softropic/skillars/infrastructure/storage/config/
└── StorageProperties.java              ← MODIFY: add Validation inner class

src/main/resources/
└── application.yaml                    ← MODIFY: add app.storage.validation.*

src/test/java/com/softropic/skillars/infrastructure/storage/service/
└── ValidationChainTest.java            ← NEW (unit test, no Spring context)
```

### Why ValidationRequest Is a Mutable Class, Not a Record

The project rule "All DTOs must be records" applies to HTTP request/response objects (bound via `@RequestBody` or `@ResponseBody`). `ValidationRequest` is an internal chain-processing object in the `service` layer. Since `FilenameSanitizationStep` must write back the sanitized filename (AC2: "the sanitized value replaces `originalFilename` in the request"), the object must be mutable. Use `@Getter @Setter @Builder @AllArgsConstructor @NoArgsConstructor` from Lombok.

### Spring @Order on List Injection — How It Works

When `ValidationChain` declares `List<ValidationStep> steps` in its constructor, Spring injects all `ValidationStep` beans sorted by their `@Order` value (ascending). This is Spring Framework's built-in behavior for ordered bean lists. `FilenameSanitizationStep` at `@Order(1)` is guaranteed to be first. Adding a new step just requires `@Component + @Order(N)` — no `ValidationChain` changes needed.

### StorageValidationException — Already Registered in ApiAdvice

`StorageValidationException` already exists at `contract/exception/StorageValidationException.java` and is already handled in `ApiAdvice.storageValidationHandler()` → HTTP 422. Do NOT create a new exception, do NOT add a new handler. Just throw `new StorageValidationException(reason)` from step implementations.

### Existing Duplicate Field Bug in StorageProperties.S3

`StorageProperties.S3` inner class (lines 78-83) has duplicate fields `requestTimeoutMs` and `connectionTimeoutMs`. This is a pre-existing bug in the codebase. **DO NOT fix it** in this story — it's out of scope and touching it risks breaking other things. Add the new `Validation` inner class independently. The compile may succeed if Java handles this (it shouldn't normally — if it causes a compile failure, report it to the user as a blocker).

### ChecksumValidationStep — Sign-Time vs Confirm-Time

**CRITICAL DISTINCTION**: At sign-time (this story), checksum validation is FORMAT-ONLY:
- Checksum must be a 64-char lowercase hex string (SHA-256 format)
- The file has NOT been uploaded yet — no content to verify
- Actual content integrity (ETag vs declared checksum) is Story 2.3's responsibility at confirm-time via `s3Client.headObject`

The regex pattern: `^[0-9a-f]{64}$` — must be lowercase hex only (no uppercase A-F).

### FilenameSanitizationStep — Sanitization Logic Detail

The step must handle all of these:
1. **Null bytes**: `\0` (char code 0) — `replace("\0", "")`
2. **Control chars 0x00–0x1F and 0x7F**: `replaceAll("[\\x00-\\x1F\\x7F]", "")`
3. **Path traversal**: `../` and `./` sequences — use: `replaceAll("(\\.\\.)[\\/\\\\]|[\\/\\\\](\\.\\.)|(^\\.\\.[\\/\\\\])|(^\\.[\\/\\\\])", "")`
   - After stripping, re-check result doesn't start with `../` 
   - Simpler robust approach: repeatedly strip `../` and `./` prefixes until stable: strip all `../`, `./`, `..\`, `.\` anywhere in the string
4. **Unicode NFC normalization**: `java.text.Normalizer.normalize(filename, Normalizer.Form.NFC)` — run this BEFORE other transformations
5. **Truncation to 255 chars**: AFTER all stripping, `substring(0, Math.min(filename.length(), 255))`
6. **Null/blank input**: set to `"unnamed"` and return — never leave originalFilename null

The order of operations: NFC normalize → strip null bytes → strip control chars → strip traversal sequences → truncate → write back. Never throw from this step.

### ValidationChain Test Strategy — No Spring Context

Unit tests should NOT use Spring context. Build test objects directly:

```java
StorageProperties props = new StorageProperties();
props.setBucket("test-bucket");
props.setEndpointUrl("http://localhost:9000");
StorageProperties.Validation validationConfig = new StorageProperties.Validation();
validationConfig.setAllowedMimeTypes(List.of("image/jpeg", "application/pdf"));
validationConfig.setAllowedExtensions(List.of("jpg", "pdf"));
validationConfig.setMaxSizeBytes(1024 * 1024L); // 1MB
props.setValidation(validationConfig);

FilenameSanitizationStep sanitization = new FilenameSanitizationStep();
MimeTypeValidationStep mimeType = new MimeTypeValidationStep(props);
FileSizeValidationStep fileSize = new FileSizeValidationStep(props);
ExtensionValidationStep extension = new ExtensionValidationStep(props);
ChecksumValidationStep checksum = new ChecksumValidationStep();

ValidationChain chain = new ValidationChain(List.of(sanitization, mimeType, extension, fileSize, checksum));
```

### Chain Halt Verification in Tests

To verify the chain halts on first failure (AC1 — "stopping on the first failure"), use a Mockito spy on subsequent steps and assert they were never called. Alternatively, use a custom `ValidationStep` that records whether it was called, then assert the recording:

```java
@Test
void chain_haltsOnFirstFailure() {
    AtomicBoolean secondStepCalled = new AtomicBoolean(false);
    ValidationStep alwaysFails = req -> { throw new StorageValidationException("first fails"); };
    ValidationStep recorder = req -> secondStepCalled.set(true);
    ValidationChain chain = new ValidationChain(List.of(alwaysFails, recorder));
    ValidationRequest req = validRequest();
    assertThatThrownBy(() -> chain.validate(req)).isInstanceOf(StorageValidationException.class);
    assertThat(secondStepCalled).isFalse();
}
```

### What Is NOT in This Story

- `SignUploadRequest` record (Story 2.2 — the HTTP DTO)
- `StorageSigningService` or quota enforcement (Story 2.2)
- `StorageResource` endpoint methods (Story 2.2)
- Any S3 calls or pre-signed URLs (Story 2.2)
- Antivirus / image verification step implementations (just the hook point — FR-04.05)
- `ValidationChain` will be called from `StorageSigningService` in Story 2.2

### References

- [Source: epics.md#Story 2.1] — Full BDD acceptance criteria
- [Source: architecture.md#Gap 2 — Filename Sanitization] — `FilenameSanitizationStep` as first chain step, FR-04.03
- [Source: architecture.md#ValidationChain] — `ValidationStep` interface, pluggable chain
- [Source: architecture.md#Package Layout] — `service/validation/` subdirectory for step implementations
- [Source: architecture.md#Enforcement Guidelines] — `StorageValidationException` extends `ApplicationException`
- [Source: project-context.md#Testing Rules] — Unit tests: no container; AssertJ for assertions; Instancio optional for test data
- [Source: ApiAdvice.java:382-386] — `storageValidationHandler` already registered for HTTP 422
- [Source: StorageValidationException.java] — Constructor takes `String reason`
- [Source: 1-3-storage-test-infrastructure.md#Dev Notes] — `@Order` pattern, correct Spring import paths

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

Encountered pre-existing compile blockers: duplicate fields in `StorageProperties.S3` and misplaced `@Recover` annotation in `S3StorageService`. Fixed both with user approval. BUILD SUCCESS achieved after fixes.

### Completion Notes List

- Implemented full validation chain: `ValidationRequest` (mutable Lombok class), `ValidationStep` interface, 5 step implementations (`FilenameSanitizationStep @Order(1)`, `MimeTypeValidationStep @Order(10)`, `ExtensionValidationStep @Order(20)`, `FileSizeValidationStep @Order(30)`, `ChecksumValidationStep @Order(40)`), and `ValidationChain @Service`.
- `FilenameSanitizationStep` applies NFC normalization, strips null bytes and control chars, removes path traversal sequences, truncates to 255 chars — never throws.
- `ChecksumValidationStep` validates format only (regex `^[0-9a-f]{64}$`); null/blank checksum is treated as absent and passes.
- All 21 unit tests pass. Regression suite (37 tests total) passes with no failures.
- Pre-existing bugs fixed with user approval: duplicate `requestTimeoutMs`/`connectionTimeoutMs` fields in `StorageProperties.S3`, and stray `String sanitizedKey = ...` statement inside `@Recover` method body in `S3StorageService`.

### File List

- src/main/java/com/softropic/skillars/infrastructure/storage/config/StorageProperties.java (modified)
- src/main/resources/application.yaml (modified)
- src/main/java/com/softropic/skillars/infrastructure/storage/contract/ValidationRequest.java (new)
- src/main/java/com/softropic/skillars/infrastructure/storage/service/ValidationStep.java (new)
- src/main/java/com/softropic/skillars/infrastructure/storage/service/ValidationChain.java (new)
- src/main/java/com/softropic/skillars/infrastructure/storage/service/validation/FilenameSanitizationStep.java (new)
- src/main/java/com/softropic/skillars/infrastructure/storage/service/validation/MimeTypeValidationStep.java (new)
- src/main/java/com/softropic/skillars/infrastructure/storage/service/validation/ExtensionValidationStep.java (new)
- src/main/java/com/softropic/skillars/infrastructure/storage/service/validation/FileSizeValidationStep.java (new)
- src/main/java/com/softropic/skillars/infrastructure/storage/service/validation/ChecksumValidationStep.java (new)
- src/main/java/com/softropic/skillars/infrastructure/storage/service/S3StorageService.java (bugfix)
- src/test/java/com/softropic/skillars/infrastructure/storage/service/ValidationChainTest.java (new)

### Change Log

- 2026-05-26: Implemented pre-storage validation chain (Tasks 1-10). Created ValidationRequest, ValidationStep interface, 5 step implementations with @Order(1/10/20/30/40), ValidationChain service, and ValidationChainTest with 21 unit tests. Fixed pre-existing compile blockers in StorageProperties.S3 and S3StorageService with user approval.
