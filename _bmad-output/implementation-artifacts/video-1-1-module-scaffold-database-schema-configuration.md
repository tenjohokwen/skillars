# Story 1.1: Video Module Scaffold, Database Schema & Configuration

Status: review

## Story

As a developer integrating the Video module into a javatemplate application,
I want the module's package structure, database schema, configuration model, exception hierarchy, error handling, and test base class in place,
so that all subsequent video stories have a consistent, compiling foundation to build on.

## Acceptance Criteria

**AC-1: Package structure compiles cleanly**
- `com.softropic.skillars.platform.video.{api, service, repo, contract, contract/exception, contract/event, config}` all exist
- `com.softropic.skillars.infrastructure.video` exists
- Project builds without errors after this story

**AC-2: VideoProperties is bound**
- `VideoProperties` is annotated `@ConfigurationProperties(prefix = "app.video")` and registered in `VideoConfig`
- All keys in AC-3 are mapped as typed fields with sensible defaults

**AC-3: app.video.* keys present in application.yaml**
- `app.video.provider` (default: `bunny`)
- `app.video.upload.max-bytes` (default: 5368709120 = 5 GB)
- `app.video.upload.allowed-mime-types` (list: video/mp4, video/quicktime, video/webm, video/x-msvideo)
- `app.video.upload.allowed-formats` (list: MP4, MOV, WebM, AVI)
- `app.video.upload.session-ttl-minutes` (default: 60)
- `app.video.upload.rate-limit.requests-per-minute` (default: 10)
- `app.video.playback.token-ttl-minutes` (default: 15)
- `app.video.playback.token-max-ttl-minutes` (default: 120)
- `app.video.playback.revocation-window-hours` (default: 24)
- `app.video.reconciliation.fixed-delay-ms` (default: 60000)
- `app.video.reconciliation.batch-size` (default: 10)
- `app.video.bunny.api-key` (default: `${APP_VIDEO_BUNNY_API_KEY:}`)
- `app.video.bunny.library-id` (default: `${APP_VIDEO_BUNNY_LIBRARY_ID:}`)
- `app.video.bunny.cdn-hostname` (default: `${APP_VIDEO_BUNNY_CDN_HOSTNAME:}`)

**AC-4: Flyway V15 creates videos table**
- `V15__video_schema.sql` creates `main.videos`: `id` UUID PK, `owner_id` VARCHAR NOT NULL, `provider` VARCHAR NOT NULL, `provider_asset_id` VARCHAR nullable, `operational_state` VARCHAR NOT NULL, `access_state` VARCHAR NOT NULL, `title` VARCHAR NOT NULL, `description` TEXT nullable, `duration_ms` BIGINT nullable, `storage_bytes` BIGINT nullable, `visibility` VARCHAR NOT NULL CHECK (visibility IN ('PRIVATE', 'GROUP', 'UNLISTED')), `created_at` TIMESTAMP NOT NULL, `updated_at` TIMESTAMP NOT NULL

**AC-5: Flyway V16 creates upload_sessions table**
- `V16__upload_sessions.sql` creates `main.upload_sessions`: `id` UUID PK, `video_id` UUID NOT NULL FK → videos, `provider_upload_id` VARCHAR nullable, `status` VARCHAR NOT NULL CHECK (status IN ('PENDING', 'COMMITTED', 'EXPIRED')), `reserved_bytes` BIGINT NOT NULL, `reservation_handle` VARCHAR nullable, `expires_at` TIMESTAMP NOT NULL, `created_at` TIMESTAMP NOT NULL
- Index on `(status, expires_at)` for expiry scheduler queries

**AC-6: Flyway V17 creates playback_tokens table**
- `V17__playback_tokens.sql` creates `main.playback_tokens`: `id` UUID PK, `video_id` UUID NOT NULL FK → videos, `viewer_id` VARCHAR NOT NULL, `expires_at` TIMESTAMP NOT NULL, `revoked_at` TIMESTAMP nullable, `created_at` TIMESTAMP NOT NULL
- Index on `(viewer_id, revoked_at)` for revocation lookup

**AC-7: JPA entities are valid**
- `Video`, `UploadSession`, and `PlaybackToken` are `@Entity` classes with Lombok `@Getter`
- Entities use UUID primary keys (NOT Long/TSID — see Dev Notes)
- `VideoRepository`, `UploadSessionRepository`, `PlaybackTokenRepository` are Spring Data `JpaRepository` sub-interfaces

**AC-8: VideoApiAdvice exception handlers return correct HTTP status**
- `VideoNotFoundException` → 404
- `VideoValidationException` → 422
- `QuotaExceededException` → 429 (video module's own, not the filestorage one)
- `PlaybackDeniedException` → 403
- `VideoProviderException` → 502
- `VideoSessionExpiredException` → 410
- `TerminalStateViolationException` → 409

**AC-9: VideoErrorCode enum implements ErrorCode**
- Values: `VIDEO_NOT_FOUND`, `VALIDATION_FAILED`, `QUOTA_EXCEEDED`, `PLAYBACK_DENIED`, `PROVIDER_ERROR`, `SESSION_EXPIRED`, `TERMINAL_STATE_VIOLATION`
- `getErrorCode()` returns `this.name()`

**AC-10: i18n keys present in all three message files**
- `messages.properties`, `messages_en.properties`, `messages_fr.properties` all contain:
  - `video.notFound`
  - `video.validationFailed`
  - `video.quotaExceeded`
  - `video.playbackDenied`
  - `video.providerError`
  - `video.sessionExpired`
  - `video.terminalStateViolation`

**AC-11: BaseVideoIT base class exists**
- `@SpringBootTest` + `@Testcontainers` base class in `src/test/java/.../platform/video/`
- Imports `PostgresContainerConfig` (the existing reusable container config)
- Has WireMock server wired via `wiremock-spring-boot` 4.0.9 (already in pom.xml)
- WireMock is configured to stub Bunny.net's base URL via `@DynamicPropertySource` overriding `app.video.bunny.*` endpoint properties
- No Minio container needed (this module does not use S3/blob storage)

## Tasks / Subtasks

- [x] Task 1: Create package skeleton (AC: 1)
  - [x] Create `platform.video.api` — empty `VideoResource.java` shell `@RestController @RequestMapping("/api/video")`
  - [x] Create `platform.video.service` — placeholder `VideoService.java` (empty class, no methods yet)
  - [x] Create `platform.video.repo` — placeholder `VideoRepository.java` (empty interface stub)
  - [x] Create `platform.video.contract` — `package-info.java` documenting integration contract (see Dev Notes)
  - [x] Create `platform.video.contract.exception` — placeholder file to establish package
  - [x] Create `platform.video.contract.event` — placeholder file to establish package
  - [x] Create `platform.video.config` — `VideoConfig.java` (empty `@Configuration` for now)
  - [x] Create `infrastructure.video` — placeholder `package-info.java` to establish package

- [x] Task 2: Create VideoProperties and register in VideoConfig (AC: 2, 3)
  - [x] Create `VideoProperties.java` in `platform.video.config` with `@ConfigurationProperties(prefix = "app.video")` and `@Getter @Setter`
  - [x] Nested static classes: `Upload` (maxBytes, allowedMimeTypes, allowedFormats, sessionTtlMinutes, RateLimit), `Playback` (tokenTtlMinutes, tokenMaxTtlMinutes, revocationWindowHours), `Reconciliation` (fixedDelayMs, batchSize), `Bunny` (apiKey, libraryId, cdnHostname)
  - [x] Add `@EnableConfigurationProperties(VideoProperties.class)` to `VideoConfig`
  - [x] Add `app.video.*` section to `src/main/resources/application.yaml`

- [x] Task 3: Create Flyway migration V15 — videos table (AC: 4)
  - [x] Create `src/main/resources/db/migration/V15__video_schema.sql`
  - [x] `main.videos` table with UUID PK and all required columns + CHECK constraint on `visibility`

- [x] Task 4: Create Flyway migration V16 — upload_sessions table (AC: 5)
  - [x] Create `src/main/resources/db/migration/V16__upload_sessions.sql`
  - [x] `main.upload_sessions` table with UUID PK, FK to videos, CHECK on status
  - [x] Composite index on `(status, expires_at)` for expiry scheduler efficiency

- [x] Task 5: Create Flyway migration V17 — playback_tokens table (AC: 6)
  - [x] Create `src/main/resources/db/migration/V17__playback_tokens.sql`
  - [x] `main.playback_tokens` table with UUID PK, FK to videos
  - [x] Index on `(viewer_id, revoked_at)` for revocation lookup

- [x] Task 6: Create JPA entities (AC: 7)
  - [x] Create `Video.java` in `platform.video.repo` with UUID PK — do NOT extend BaseEntity (see Dev Notes: UUID vs TSID)
  - [x] Create `UploadSession.java` in `platform.video.repo` with UUID PK and enum fields
  - [x] Create `PlaybackToken.java` in `platform.video.repo` with UUID PK and nullable revokedAt
  - [x] Add `@Table(schema = "main")` on all three entities (matches existing convention)
  - [x] Create enum types in `platform.video.repo` or `platform.video.contract`: `OperationalState` (UPLOADING, PROCESSING, READY, FAILED, DELETED), `AccessState` (ACTIVE, BLOCKED, ARCHIVED), `Visibility` (PRIVATE, GROUP, UNLISTED), `UploadSessionStatus` (PENDING, COMMITTED, EXPIRED)

- [x] Task 7: Create Spring Data repositories (AC: 7)
  - [x] Create `VideoRepository.java` extending `JpaRepository<Video, UUID>`
  - [x] Create `UploadSessionRepository.java` extending `JpaRepository<UploadSession, UUID>`
  - [x] Create `PlaybackTokenRepository.java` extending `JpaRepository<PlaybackToken, UUID>`

- [x] Task 8: Create VideoErrorCode and all exceptions (AC: 8, 9)
  - [x] Create `VideoErrorCode.java` in `platform.video.contract` implementing `ErrorCode` with 7 values
  - [x] Create `VideoNotFoundException.java` in `platform.video.contract.exception` extending `ApplicationException`
  - [x] Create `VideoValidationException.java` in `platform.video.contract.exception` extending `ApplicationException`
  - [x] Create `QuotaExceededException.java` in `platform.video.contract.exception` (video-module specific, separate from filestorage's `QuotaExceededException`)
  - [x] Create `PlaybackDeniedException.java` in `platform.video.contract.exception` extending `ApplicationException`
  - [x] Create `VideoProviderException.java` in `platform.video.contract.exception` extending `ApplicationException`
  - [x] Create `VideoSessionExpiredException.java` in `platform.video.contract.exception` extending `ApplicationException`
  - [x] Create `TerminalStateViolationException.java` in `platform.video.contract.exception` extending `ApplicationException`

- [x] Task 9: Create VideoApiAdvice (AC: 8)
  - [x] Create `VideoApiAdvice.java` in `platform.video.api` with `@RestControllerAdvice` and `@Slf4j`
  - [x] Add `@ExceptionHandler` method per exception type → correct HTTP status + `ErrorDto` (follow same pattern as `ApiAdvice.java`)
  - [x] Use `MessageSource` + `VideoErrorCode` to build `ErrorDto` responses consistent with global `ApiAdvice`

- [x] Task 10: Add i18n messages (AC: 10)
  - [x] Add video error keys to `src/main/resources/i18n/messages.properties`
  - [x] Add same keys to `messages_en.properties` (English copy of messages.properties)
  - [x] Add translated keys to `messages_fr.properties` (French translations)

- [x] Task 11: Create BaseVideoIT base class (AC: 11)
  - [x] Create `BaseVideoIT.java` in `src/test/java/com/softropic/skillars/platform/video/`
  - [x] Import `PostgresContainerConfig` from `com.softropic.skillars.config` (reuse existing)
  - [x] Wire WireMock via `wiremock-spring-boot` annotations
  - [x] Override `app.video.bunny.*` properties via `application-test.yaml` referencing `wiremock.server.bunny-service.baseUrl`
  - [x] No Minio container needed; Redis imported for full app context compatibility

- [x] Task 12: Create contract package-info.java with integration boundaries (integration contract per Story 1.2 prep)
  - [x] Document in `platform.video.contract` package-info that: teamId absent from Video; Visibility enforcement is consuming app's responsibility; end-user REST controllers are out of scope for this module

## Dev Notes

### CRITICAL: UUID Primary Keys — Do NOT Extend BaseEntity

The existing `BaseEntity` (`infrastructure.persistence.BaseEntity`) uses `@Tsid` annotation and BIGINT `Long` IDs. **Video module entities MUST use UUID primary keys** as specified by the data model (epics.md Data Model section). Do NOT extend `BaseEntity` or `AbstractAuditingEntity` for `Video`, `UploadSession`, or `PlaybackToken`.

Instead, implement standalone JPA entities:
```java
@Entity
@Table(name = "videos", schema = "main")
@Getter
@NoArgsConstructor
public class Video {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;
    // ...
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

Add `@PrePersist` / `@PreUpdate` hooks on entities to populate `createdAt` / `updatedAt` since `AbstractAuditingEntity` won't be used. Alternatively: `@Column(columnDefinition = "TIMESTAMP DEFAULT NOW()")` in SQL + Hibernate does not override it if insertable=false, updatable=false.

No Hibernate Envers `@Audited` needed on video entities — the epics spec does not require auditing for this module.

### Enum Mapping for JPA

Use `@Enumerated(EnumType.STRING)` on all enum columns. Enums should be defined in `platform.video.contract` (or `platform.video.repo` if repo-only concern) — prefer `contract` so later stories can reference them from the service layer.

```java
public enum OperationalState { UPLOADING, PROCESSING, READY, FAILED, DELETED }
public enum AccessState { ACTIVE, BLOCKED, ARCHIVED }
public enum Visibility { PRIVATE, GROUP, UNLISTED }
public enum UploadSessionStatus { PENDING, COMMITTED, EXPIRED }
```

### VideoApiAdvice Pattern

Follow `ApiAdvice.java` exactly at `platform.security.api.ApiAdvice`. Key points:
- Inject `MessageSource` constructor-injected (not autowired)
- Use `RequestMetadataProvider.getClientInfo().getChosenLang()` for locale
- Return `ErrorDto` with `ErrorMsg(errorCode, message)` pattern
- `logErrorAndReturnDTO` helper recommended (copy pattern from ApiAdvice or delegate)

`VideoApiAdvice` is separate from the global `ApiAdvice` — Spring supports multiple `@RestControllerAdvice` beans. Place it in `platform.video.api`.

Note: The global `ApiAdvice` already handles `QuotaExceededException` from the filestorage module. The video module's `QuotaExceededException` is a **different class** in `platform.video.contract.exception`. Use fully qualified imports to avoid confusion.

### Flyway Migration Numbering

Current last migration: `V14__storage_physical_deletion.sql`. New migrations:
- `V15__video_schema.sql` — videos table
- `V16__upload_sessions.sql` — upload_sessions table
- `V17__playback_tokens.sql` — playback_tokens table

All tables in `main` schema (matches `spring.jpa.properties.hibernate.default_schema: main`).

### WireMock Setup for BaseVideoIT

`wiremock-spring-boot` 4.0.9 is already in pom.xml. Key setup pattern:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import({PostgresContainerConfig.class, RedisContainerConfig.class, 
         TestMailConfig.class, BaseVideoIT.VideoTestConfig.class})
@ActiveProfiles({"dev", "test"})
@EnableWireMock(@ConfigureWireMock(name = "bunny-service"))
public abstract class BaseVideoIT {

    @InjectWireMock("bunny-service")
    protected WireMockServer wireMockServer;

    @DynamicPropertySource
    static void configureWireMock(DynamicPropertyRegistry registry) {
        // Will be populated from WireMock server port — see wiremock-spring-boot docs
        // This override must point app.video.bunny.* URL properties to the WireMock server
    }
}
```

Alternative pattern (using `@WireMockTest` for unit tests, `@EnableWireMock` for SpringBootTest integration tests). The `@DynamicPropertySource` on a static method can reference the WireMock server port injected via `@InjectWireMock` — but this requires a workaround since `@DynamicPropertySource` runs before field injection. Common approach: use a static `WireMockServer` field or rely on `wiremock-spring-boot`'s built-in property injection.

Check `wiremock-spring-boot` 4.x docs — it automatically registers `wiremock.server.{name}.port` and `wiremock.server.{name}.baseUrl` as Spring properties. Override `app.video.bunny.*` in `application-test.yaml` using those property references.

### Application YAML for Bunny.net Properties

The `BunnyVideoProviderAdapter` (Story 1.3) will need URL properties for Bunny.net API endpoints. Anticipate these in VideoProperties or as separate properties. For Story 1.1, the key `app.video.bunny.api-key`, `app.video.bunny.library-id`, and `app.video.bunny.cdn-hostname` are sufficient. Bunny.net API base URL can be hardcoded in the adapter or made configurable.

### VideoProperties Bunny Sub-Properties

Keep Bunny credentials in `BunnyProperties` nested class within `VideoProperties`. Never expose raw API key values in logs — mark the class with appropriate `@toString = false` or use a custom `toString()` that masks `apiKey`.

### SQL Schema Notes

Videos table has `provider_asset_id VARCHAR` as nullable — newly created videos (before upload) won't have this set yet. `updated_at` must be kept current via `@PreUpdate` or trigger. FK constraints use standard PostgreSQL syntax:

```sql
CONSTRAINT fk_upload_session_video FOREIGN KEY (video_id) REFERENCES main.videos(id)
```

### Package-Info for Integration Contract

Per epics Story 1.2 prep, create `platform.video.contract/package-info.java` documenting:
1. `teamId` is intentionally absent from the `Video` entity — consuming apps own the linkage
2. `Visibility` is stored but not enforced — consuming apps gate on Visibility in their layer
3. End-user REST controllers for video operations are the consuming app's responsibility

### Reference Implementation

Examine the completed filestorage module for concrete patterns:
- `FileStorageConfig.java` → `VideoConfig.java` (simpler, no conditional S3 beans yet)
- `FileStorageProperties.java` → `VideoProperties.java` (similar nested structure)
- `FileStorageObject.java` → `Video.java` (but Video uses UUID not Long TSID)
- `FileStorageErrorCode.java` → `VideoErrorCode.java` (more values)
- `QuotaExceededException.java` (filestorage) → reference for video's own `QuotaExceededException`
- `BaseStorageIT.java` → `BaseVideoIT.java` (swap Minio for WireMock)

### Project Structure Notes

All new packages follow `com.softropic.skillars.platform.video.*` and `com.softropic.skillars.infrastructure.video` conventions. No deviation from the modular monolith structure. The `infrastructure.video` package is created now but populated in Story 1.3 — placeholder `package-info.java` is sufficient.

The `VideoApiAdvice` goes in `platform.video.api` — multiple `@RestControllerAdvice` beans are fully supported by Spring. Do NOT modify the global `ApiAdvice` in `platform.security.api`.

### References

- Data model specification: epics.md "Data Model (from addendum A5)" section
- Module structure rules: `_bmad-output/project-context.md` — "Architecture & Module Design" section
- Provider Adapter Pattern: `project-context.md` — "The Provider Adapter Pattern" section
- Exception patterns: `platform.security.api.ApiAdvice` — `@ExceptionHandler` methods
- Config patterns: `platform.filestorage.config.FileStorageProperties`
- Entity patterns: `platform.filestorage.repo.FileStorageObject`
- Integration test pattern: `infrastructure.storage.BaseStorageIT`
- WireMock dependency: `pom.xml` `wiremock-spring-boot` version 4.0.9

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

_No blocking issues encountered._

### Completion Notes List

- All 12 tasks completed in a single session. Project compiles cleanly (main + test sources) and all 216 existing tests pass.
- Enums (`OperationalState`, `AccessState`, `Visibility`, `UploadSessionStatus`) placed in `platform.video.contract` so both service and repo layers can reference them.
- Video JPA entities do NOT extend `BaseEntity` or `AbstractAuditingEntity`; standalone `@PrePersist`/`@PreUpdate` hooks manage timestamps.
- `VideoProperties.Bunny.toString()` masks the `apiKey` field to prevent secret leakage in logs.
- `VideoApiAdvice` follows the storage-handler convention from `ApiAdvice`: `ErrorMsg(errorCode, i18nKey)` so the client receives the error code as `errorKey` and the i18n key as the human-readable message (frontend resolves it).
- `BaseVideoIT` wires WireMock via `@EnableWireMock(@ConfigureWireMock(name = "bunny-service"))`; `app.video.bunny.cdn-hostname` is overridden in `application-test.yaml` using the auto-registered `${wiremock.server.bunny-service.baseUrl}` property. RedisContainerConfig is included to satisfy the full Spring Boot context (Redis is wired at the application level).
- `application.yaml` `app.video.*` keys added inside the existing `app:` block (no duplicate root key).
- Flyway migrations V15–V17 follow the `main` schema convention; all FK constraints and indexes implemented as specified.

### File List

**New files to create:**

`src/main/java/com/softropic/skillars/platform/video/api/VideoResource.java`
`src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java`
`src/main/java/com/softropic/skillars/platform/video/service/VideoService.java`
`src/main/java/com/softropic/skillars/platform/video/repo/Video.java`
`src/main/java/com/softropic/skillars/platform/video/repo/UploadSession.java`
`src/main/java/com/softropic/skillars/platform/video/repo/PlaybackToken.java`
`src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java`
`src/main/java/com/softropic/skillars/platform/video/repo/UploadSessionRepository.java`
`src/main/java/com/softropic/skillars/platform/video/repo/PlaybackTokenRepository.java`
`src/main/java/com/softropic/skillars/platform/video/contract/VideoErrorCode.java`
`src/main/java/com/softropic/skillars/platform/video/contract/package-info.java`
`src/main/java/com/softropic/skillars/platform/video/contract/OperationalState.java`
`src/main/java/com/softropic/skillars/platform/video/contract/AccessState.java`
`src/main/java/com/softropic/skillars/platform/video/contract/Visibility.java`
`src/main/java/com/softropic/skillars/platform/video/contract/UploadSessionStatus.java`
`src/main/java/com/softropic/skillars/platform/video/contract/exception/VideoNotFoundException.java`
`src/main/java/com/softropic/skillars/platform/video/contract/exception/VideoValidationException.java`
`src/main/java/com/softropic/skillars/platform/video/contract/exception/QuotaExceededException.java`
`src/main/java/com/softropic/skillars/platform/video/contract/exception/PlaybackDeniedException.java`
`src/main/java/com/softropic/skillars/platform/video/contract/exception/VideoProviderException.java`
`src/main/java/com/softropic/skillars/platform/video/contract/exception/VideoSessionExpiredException.java`
`src/main/java/com/softropic/skillars/platform/video/contract/exception/TerminalStateViolationException.java`
`src/main/java/com/softropic/skillars/platform/video/config/VideoConfig.java`
`src/main/java/com/softropic/skillars/platform/video/config/VideoProperties.java`
`src/main/java/com/softropic/skillars/infrastructure/video/package-info.java`
`src/main/resources/db/migration/V15__video_schema.sql`
`src/main/resources/db/migration/V16__upload_sessions.sql`
`src/main/resources/db/migration/V17__playback_tokens.sql`
`src/test/java/com/softropic/skillars/platform/video/BaseVideoIT.java`

**Modified files:**

`src/main/resources/application.yaml` — add `app.video.*` section
`src/main/resources/i18n/messages.properties` — add 7 video error keys
`src/main/resources/i18n/messages_en.properties` — add 7 video error keys (English)
`src/main/resources/i18n/messages_fr.properties` — add 7 video error keys (French)
`src/test/resources/application-test.yaml` — add `app.video.bunny.cdn-hostname` override for WireMock

## Change Log

- 2026-05-29: Story 1.1 implemented — Video module scaffold, database schema, and configuration complete. 31 new files created, 5 files modified. All 216 existing tests pass.
