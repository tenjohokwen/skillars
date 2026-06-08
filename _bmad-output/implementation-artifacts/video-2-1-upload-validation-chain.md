# Story Video-2.1: Upload Validation Chain

Status: review

## Story

As a developer using the Video module,
I want a pluggable validation chain that enforces file size, MIME type, and container format rules before any upload is initialized,
so that invalid video files are rejected before any provider call or quota reservation is made.

## Acceptance Criteria

**AC-1: VideoValidationChain stops on the first failing step**
- `VideoValidationChain.validate(UploadValidationRequest)` iterates registered `VideoValidationStep` beans in `@Order` sequence
- On the first step that throws `VideoValidationException`, execution stops immediately — subsequent steps do NOT run
- When all steps pass, the method returns without exception

**AC-2: FileSizeValidationStep rejects files exceeding max-bytes**
- Given `fileSizeBytes` > `app.video.upload.max-bytes` (default 5 GB = 5368709120)
- `FileSizeValidationStep` throws `VideoValidationException` with `VideoErrorCode.VALIDATION_FAILED` and a message identifying the declared size and the limit
- Thrown before any quota check or provider call (this step runs before Story 2.2's VideoService proceeds)

**AC-3: MimeTypeValidationStep rejects unlisted MIME types**
- Given `mimeType` not present in `app.video.upload.allowed-mime-types`
- `MimeTypeValidationStep` throws `VideoValidationException` with a message identifying the rejected MIME type
- Client-provided MIME types are advisory only — the step validates the declared value against the configured allowlist; it does NOT trust it as authoritative server-side content sniffing

**AC-4: FormatValidationStep rejects unlisted container formats**
- Given `containerFormat` not present in `app.video.upload.allowed-formats`
- `FormatValidationStep` throws `VideoValidationException`
- Comparison is case-insensitive

**AC-5: VideoValidationStep is pluggable via Spring bean registration**
- Implementing `VideoValidationStep` and registering the implementation as a Spring `@Component` is sufficient to include it in the chain automatically — no modification of `VideoValidationChain` required

**AC-6: Unit tests cover all required scenarios**
- `VideoValidationChainTest` covers:
  - Each step passes valid input
  - Each step rejects invalid input with `VideoValidationException`
  - Chain halts on the first failing step (second step is NOT called)
  - Chain passes when all steps pass
  - Steps execute in declared `@Order` order

## Tasks / Subtasks

- [x] Task 1: Define `UploadValidationRequest` record in `platform.video.contract` (AC: 1–4)
  - [x] Create `UploadValidationRequest.java` as a Java `record` with fields: `String fileName`, `long fileSizeBytes`, `String mimeType`, `String containerFormat`
  - [x] Place in `com.softropic.skillars.platform.video.contract`

- [x] Task 2: Define `VideoValidationStep` interface in `platform.video.service` (AC: 1, 5)
  - [x] Create `VideoValidationStep.java` as a single-method interface: `void validate(UploadValidationRequest request)` throws `VideoValidationException`
  - [x] Place in `com.softropic.skillars.platform.video.service`

- [x] Task 3: Implement `VideoValidationChain` service in `platform.video.service` (AC: 1, 5)
  - [x] Create `VideoValidationChain.java` annotated `@Service @RequiredArgsConstructor`
  - [x] Constructor-inject `List<VideoValidationStep> steps` (Spring collects all registered beans in `@Order` sequence)
  - [x] `validate(UploadValidationRequest request)` loops over steps; stops on first exception

- [x] Task 4: Implement `FileSizeValidationStep` in `platform.video.service.validation` (AC: 2)
  - [x] Create `FileSizeValidationStep.java` annotated `@Component @Order(10) @RequiredArgsConstructor`
  - [x] Inject `VideoProperties` to read `upload.maxBytes`
  - [x] Throw `VideoValidationException("File size %d bytes exceeds maximum %d bytes".formatted(...))`

- [x] Task 5: Implement `MimeTypeValidationStep` in `platform.video.service.validation` (AC: 3)
  - [x] Create `MimeTypeValidationStep.java` annotated `@Component @Order(20) @RequiredArgsConstructor`
  - [x] Inject `VideoProperties` to read `upload.allowedMimeTypes`
  - [x] Throw `VideoValidationException("MIME type not allowed: " + mimeType)` if null or not in allowlist

- [x] Task 6: Implement `FormatValidationStep` in `platform.video.service.validation` (AC: 4)
  - [x] Create `FormatValidationStep.java` annotated `@Component @Order(30) @RequiredArgsConstructor`
  - [x] Inject `VideoProperties` to read `upload.allowedFormats`
  - [x] Case-insensitive match: `allowedFormats.stream().noneMatch(f -> f.equalsIgnoreCase(containerFormat))`
  - [x] Throw `VideoValidationException("Container format not allowed: " + containerFormat)`

- [x] Task 7: Write `VideoValidationChainTest` unit test (AC: 6)
  - [x] Create `src/test/java/com/softropic/skillars/platform/video/service/VideoValidationChainTest.java`
  - [x] Use `@ExtendWith(MockitoExtension.class)` — NO Spring context, NO Testcontainers
  - [x] Instantiate `VideoProperties` and steps directly (no mocks needed for the steps themselves)
  - [x] Cover all AC-6 scenarios using AssertJ

## Dev Notes

### Reference Implementation: File Storage Validation Chain

The video validation chain mirrors the file storage module's pattern exactly. Read and reuse the following files as the definitive blueprint — do NOT reinvent:

| Video (new) | File Storage (reference) |
|---|---|
| `platform.video.service.VideoValidationStep` | `platform.filestorage.service.ValidationStep` |
| `platform.video.service.VideoValidationChain` | `platform.filestorage.service.ValidationChain` |
| `platform.video.service.validation.FileSizeValidationStep` | `platform.filestorage.service.validation.FileSizeValidationStep` |
| `platform.video.service.validation.MimeTypeValidationStep` | `platform.filestorage.service.validation.MimeTypeValidationStep` |
| `platform.video.service.validation.FormatValidationStep` | `platform.filestorage.service.validation.ExtensionValidationStep` (closest analogue) |
| `platform.video.contract.UploadValidationRequest` | `platform.filestorage.contract.ValidationRequest` (reference only — video version is a record, not a mutable class) |

Key difference: `ValidationRequest` in filestorage is a mutable Lombok class (because `FilenameSanitizationStep` mutates the filename field in-place). There is no mutation step for video, so `UploadValidationRequest` MUST be an immutable Java `record` per project rules.

### Package & File Structure

```
src/main/java/com/softropic/skillars/platform/video/
├── contract/
│   └── UploadValidationRequest.java       ← NEW: record
├── service/
│   ├── VideoValidationStep.java           ← NEW: interface
│   ├── VideoValidationChain.java          ← NEW: @Service
│   └── validation/
│       ├── FileSizeValidationStep.java    ← NEW: @Component @Order(10)
│       ├── MimeTypeValidationStep.java    ← NEW: @Component @Order(20)
│       └── FormatValidationStep.java      ← NEW: @Component @Order(30)

src/test/java/com/softropic/skillars/platform/video/service/
└── VideoValidationChainTest.java          ← NEW: pure unit test
```

**Do NOT create files anywhere else.** The `VideoValidationStep` interface and `VideoValidationChain` are in `service` (not `service.validation`), matching the filestorage module layout.

### UploadValidationRequest — Exact Record Structure

```java
package com.softropic.skillars.platform.video.contract;

public record UploadValidationRequest(
    String fileName,
    long fileSizeBytes,
    String mimeType,
    String containerFormat
) {}
```

`containerFormat` is the uppercase format name (e.g., "MP4", "MOV", "WebM", "AVI"). The caller (Story 2.2's `VideoService.initializeUpload()`) is responsible for deriving it from the filename extension before building this request:
```java
// Example extraction in VideoService (Story 2.2, not this story):
String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "";
new UploadValidationRequest(fileName, fileSizeBytes, mimeType, ext.toUpperCase())
```

### VideoValidationChain — Exact Pattern

```java
@Service
@RequiredArgsConstructor
public class VideoValidationChain {

    private final List<VideoValidationStep> steps;

    public void validate(UploadValidationRequest request) {
        for (VideoValidationStep step : steps) {
            step.validate(request);
        }
    }
}
```

Spring auto-collects all `VideoValidationStep` beans into the `List` in `@Order` sequence. Adding a new step requires only: implement the interface, annotate `@Component @Order(N)` — no change to `VideoValidationChain`.

### FileSizeValidationStep — Exact Pattern

```java
@Component
@Order(10)
@RequiredArgsConstructor
public class FileSizeValidationStep implements VideoValidationStep {

    private final VideoProperties properties;

    @Override
    public void validate(UploadValidationRequest request) {
        if (request.fileSizeBytes() > properties.getUpload().getMaxBytes()) {
            throw new VideoValidationException(
                "File size %d bytes exceeds maximum %d bytes".formatted(
                    request.fileSizeBytes(), properties.getUpload().getMaxBytes()));
        }
    }
}
```

Note: `VideoProperties` uses `getUpload().getMaxBytes()` (Lombok `@Getter` on the nested `Upload` class). Default is `5368709120L` (5 GB) — already set in `VideoProperties.Upload`.

### MimeTypeValidationStep — Exact Pattern

```java
@Component
@Order(20)
@RequiredArgsConstructor
public class MimeTypeValidationStep implements VideoValidationStep {

    private final VideoProperties properties;

    @Override
    public void validate(UploadValidationRequest request) {
        String mimeType = request.mimeType();
        if (mimeType == null || properties.getUpload().getAllowedMimeTypes().stream()
                .noneMatch(allowed -> allowed.equalsIgnoreCase(mimeType))) {
            throw new VideoValidationException("MIME type not allowed: " + mimeType);
        }
    }
}
```

Default allowedMimeTypes already configured in `VideoProperties.Upload`: `["video/mp4", "video/quicktime", "video/webm", "video/x-msvideo"]`.

### FormatValidationStep — Exact Pattern

```java
@Component
@Order(30)
@RequiredArgsConstructor
public class FormatValidationStep implements VideoValidationStep {

    private final VideoProperties properties;

    @Override
    public void validate(UploadValidationRequest request) {
        String format = request.containerFormat();
        if (format == null || properties.getUpload().getAllowedFormats().stream()
                .noneMatch(allowed -> allowed.equalsIgnoreCase(format))) {
            throw new VideoValidationException("Container format not allowed: " + format);
        }
    }
}
```

Default allowedFormats already configured in `VideoProperties.Upload`: `["MP4", "MOV", "WebM", "AVI"]`. Case-insensitive comparison handles "mp4" == "MP4".

### VideoValidationException — Already Exists

`VideoValidationException` in `platform.video.contract.exception` already exists (Story 1.1). Constructor: `VideoValidationException(String reason)`. It wraps `VideoErrorCode.VALIDATION_FAILED` automatically. Do NOT create a new exception class.

```java
// Usage in all steps:
throw new VideoValidationException("descriptive reason message");
```

### VideoValidationChainTest — Exact Pattern

```java
@ExtendWith(MockitoExtension.class)
class VideoValidationChainTest {

    private VideoProperties props;
    private FileSizeValidationStep fileSize;
    private MimeTypeValidationStep mimeType;
    private FormatValidationStep format;
    private VideoValidationChain chain;

    @BeforeEach
    void setUp() {
        props = new VideoProperties();
        // Props uses defaults: maxBytes=5368709120, allowedMimeTypes=[...], allowedFormats=[...]

        fileSize = new FileSizeValidationStep(props);
        mimeType = new MimeTypeValidationStep(props);
        format = new FormatValidationStep(props);

        chain = new VideoValidationChain(List.of(fileSize, mimeType, format));
    }

    private UploadValidationRequest validRequest() {
        return new UploadValidationRequest("video.mp4", 1024L, "video/mp4", "MP4");
    }
    // ... test methods
}
```

**Chain halt test pattern** (most important — mirrors the file storage test):
```java
@Test
void chain_haltsOnFirstFailure() {
    AtomicBoolean secondStepCalled = new AtomicBoolean(false);
    VideoValidationStep alwaysFails = req -> { throw new VideoValidationException("first fails"); };
    VideoValidationStep recorder = req -> secondStepCalled.set(true);
    VideoValidationChain testChain = new VideoValidationChain(List.of(alwaysFails, recorder));

    assertThatThrownBy(() -> testChain.validate(validRequest()))
        .isInstanceOf(VideoValidationException.class);
    assertThat(secondStepCalled).isFalse();
}
```

Use `assertThatNoException()` for passing cases, `assertThatThrownBy(...).isInstanceOf(VideoValidationException.class)` for rejections.

### What Story 1.x Delivered (Do NOT Repeat)

These already exist — do NOT recreate:
- `VideoProperties` in `platform.video.config` — `Upload` nested class with `maxBytes`, `allowedMimeTypes`, `allowedFormats`, `sessionTtlMinutes`, `rateLimit`
- `VideoValidationException` in `platform.video.contract.exception`
- `VideoErrorCode.VALIDATION_FAILED` in `platform.video.contract`
- All JPA entities, repositories, `VideoApiAdvice`, `BaseVideoIT`
- `VideoService` in `platform.video.service` — currently empty stub; this story does NOT add methods to it (that is Story 2.2)

### VideoService Scope Boundary

This story delivers **ONLY** the validation chain infrastructure. `VideoService` stays as the empty stub. Story 2.2 will add `initializeUpload()` to `VideoService` and call `VideoValidationChain.validate()` there. Do NOT implement `initializeUpload()` in this story.

### Architecture Compliance Checks

- `VideoValidationChain` and `VideoValidationStep` → `platform.video.service` ✓ (business logic in platform)
- Steps → `platform.video.service.validation` ✓
- `UploadValidationRequest` → `platform.video.contract` ✓ (public API record)
- None of these go in `infrastructure.video` (no technical infrastructure here — pure business validation)
- All steps use `@RequiredArgsConstructor` (not `@Autowired` field injection)
- `UploadValidationRequest` is a Java `record` (not Lombok mutable class)

### Project Context Rules (Must Follow)

- Java 17: use `record` for `UploadValidationRequest`
- Spring Boot 3.5.x: use `@Component`, `@Service`, `@Order`, `@RequiredArgsConstructor`
- Testing: JUnit 5 + AssertJ (`assertThat`, `assertThatThrownBy`, `assertThatNoException`) — NO Mockito mocks needed (steps and properties are instantiated directly)
- Package hierarchy: `com.softropic.skillars.platform.video.{service, service.validation, contract}`
- No comments explaining what code does; only non-obvious WHY comments

### References

- Reference validation chain: `src/main/java/com/softropic/skillars/platform/filestorage/service/ValidationChain.java`
- Reference step interface: `src/main/java/com/softropic/skillars/platform/filestorage/service/ValidationStep.java`
- Reference MimeTypeValidationStep: `src/main/java/com/softropic/skillars/platform/filestorage/service/validation/MimeTypeValidationStep.java`
- Reference FileSizeValidationStep: `src/main/java/com/softropic/skillars/platform/filestorage/service/validation/FileSizeValidationStep.java`
- Reference test: `src/test/java/com/softropic/skillars/platform/filestorage/service/ValidationChainTest.java`
- `VideoProperties`: `src/main/java/com/softropic/skillars/platform/video/config/VideoProperties.java`
- `VideoValidationException`: `src/main/java/com/softropic/skillars/platform/video/contract/exception/VideoValidationException.java`
- Epic 2 Story 2.1 AC: `_bmad-output/planning-artifacts/video-module/epics.md` §"Story 2.1: Upload Validation Chain"
- Project context rules: `_bmad-output/project-context.md`

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

None.

### Completion Notes List

- Implemented `UploadValidationRequest` as an immutable Java `record` (4 fields: fileName, fileSizeBytes, mimeType, containerFormat) in `platform.video.contract`.
- Implemented `VideoValidationStep` single-method interface in `platform.video.service`; no modification needed to add new steps.
- Implemented `VideoValidationChain` as a `@Service` that constructor-injects `List<VideoValidationStep>` — Spring auto-collects all registered beans in `@Order` sequence and iterates until the first `VideoValidationException`.
- Implemented `FileSizeValidationStep` (`@Order(10)`), `MimeTypeValidationStep` (`@Order(20)`), `FormatValidationStep` (`@Order(30)`) in `platform.video.service.validation`; all inject `VideoProperties` via `@RequiredArgsConstructor`.
- `FormatValidationStep` uses case-insensitive match (`equalsIgnoreCase`) to handle "mp4" == "MP4" per AC-4.
- `VideoValidationChainTest`: 12 pure unit tests — no Spring context, no Testcontainers. Covers: each step passes valid input, each step rejects invalid input, chain halts on first failure (second step NOT called via `AtomicBoolean`), chain passes all steps, order is enforced. All 249 tests in the full suite pass; zero regressions.

### File List

- `src/main/java/com/softropic/skillars/platform/video/contract/UploadValidationRequest.java` (new)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoValidationStep.java` (new)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoValidationChain.java` (new)
- `src/main/java/com/softropic/skillars/platform/video/service/validation/FileSizeValidationStep.java` (new)
- `src/main/java/com/softropic/skillars/platform/video/service/validation/MimeTypeValidationStep.java` (new)
- `src/main/java/com/softropic/skillars/platform/video/service/validation/FormatValidationStep.java` (new)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoValidationChainTest.java` (new)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (updated: video-2-1 in-progress → review)

## Change Log

- 2026-05-29: Implemented video upload validation chain — UploadValidationRequest record, VideoValidationStep interface, VideoValidationChain service, FileSizeValidationStep, MimeTypeValidationStep, FormatValidationStep, and VideoValidationChainTest (12 unit tests, all passing). (Agent: claude-sonnet-4-6)
