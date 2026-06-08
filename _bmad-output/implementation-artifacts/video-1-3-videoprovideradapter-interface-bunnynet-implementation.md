# Story Video-1.3: VideoProviderAdapter Interface & Bunny.net Implementation

Status: review

## Story

As a developer building upload and playback features,
I want a provider-agnostic `VideoProviderAdapter` interface backed by a working Bunny.net implementation,
so that all video operations can delegate to a consistent provider contract without coupling to Bunny.net's specific HTTP API.

## Acceptance Criteria

**AC-1: VideoProviderAdapter interface defined in `infrastructure.video`**
- Interface is in package `com.softropic.skillars.infrastructure.video`
- Declares transport types as Java `record` or `enum`:
  - `UploadCredentials` (record: `String providerUploadId`, `String signedUploadUrl`)
  - `AssetStatus` (enum: `UPLOADING`, `PROCESSING`, `READY`, `FAILED`, `DELETED`)
  - `SignedPlaybackUrl` (record: `String url`, `Instant expiresAt`)
  - `PlaybackTokenClaims` (record: `String viewerId`, `Instant expiresAt`)
  - `WebhookEvent` (record: `String eventType`, `String providerAssetId`, `Instant timestamp`)
- Declares required methods:
  - `UploadCredentials initializeUpload(String fileName, long fileSizeBytes)`
  - `AssetStatus getAssetStatus(String providerAssetId)`
  - `SignedPlaybackUrl generatePlaybackUrl(String providerAssetId, PlaybackTokenClaims claims)`
  - `void deleteAsset(String providerAssetId)`
  - `WebhookEvent verifyWebhook(String payload, String signature)`
- Declares optional default methods:
  - `default void archiveAsset(String providerAssetId) { throw new UnsupportedOperationException("archiveAsset not supported"); }`
  - `default void restoreAsset(String providerAssetId) { throw new UnsupportedOperationException("restoreAsset not supported"); }`
- Interface contains NO Bunny.net SDK types, HTTP client types, or infrastructure-specific imports

**AC-2: BunnyVideoProviderAdapter wired as active bean when `app.video.provider=bunny`**
- `VideoProviderConfig` in `platform.video.config` uses `@ConditionalOnProperty(name = "app.video.provider", havingValue = "bunny")` to register `BunnyVideoProviderAdapter` as the `VideoProviderAdapter` bean
- Adapter is configured with `app.video.bunny.api-key`, `app.video.bunny.library-id`, `app.video.bunny.cdn-hostname`, and `app.video.bunny.api-base-url` (new, default `https://video.bunnycdn.com`)
- Raw API key is NEVER logged or returned in responses

**AC-3: initializeUpload calls Bunny.net and returns TUS credentials**
- Calls `POST {bunny-api-base-url}/library/{libraryId}/videos` with header `AccessKey: {apiKey}` and body `{"title": "{fileName}", "fileSizeBytes": {fileSizeBytes}}`
- Returns `UploadCredentials` with:
  - `providerUploadId` = the `guid` from Bunny.net response
  - `signedUploadUrl` = `{bunny-api-base-url}/tusupload` — direct TUS endpoint; the consuming service is responsible for forwarding TUS auth headers to the client

**AC-4: verifyWebhook validates HMAC-SHA256 signature**
- Computes `HMAC-SHA256(apiKey, rawPayload)` and hex-encodes the result
- Compares using constant-time comparison (`MessageDigest.isEqual()`) against the provided signature
- If valid: parses the JSON payload and returns `WebhookEvent` with `eventType`, `providerAssetId`, and `timestamp`
- If invalid: throws `VideoProviderException` with `VideoErrorCode.PROVIDER_ERROR` — raw payload is never logged

**AC-5: generatePlaybackUrl produces a signed HLS URL**
- Returns `SignedPlaybackUrl` with:
  - `url` = `https://{cdnHostname}/{providerAssetId}/playlist.m3u8?token={signedToken}&expires={epochSeconds}`
  - `expiresAt` = `claims.expiresAt`
- CDN token is signed using HMAC-SHA256 (see Dev Notes for exact algorithm)
- Unsigned public playback URLs are never returned (NFR-6)

**AC-6: WireMock unit tests cover all required adapter operations**
- `BunnyVideoProviderAdapterTest` (standalone `@WireMockTest`, not extending BaseVideoIT) covers:
  - `initializeUpload`: stubs `POST /library/{libraryId}/videos` with 200 JSON response → verifies returned `UploadCredentials` fields
  - `getAssetStatus`: stubs `GET /library/{libraryId}/videos/{videoId}` for each Bunny status integer → verifies correct `AssetStatus` enum mapping
  - `generatePlaybackUrl`: verifies the returned `url` is a correctly signed HLS manifest URL
  - `deleteAsset`: stubs `DELETE /library/{libraryId}/videos/{videoId}` → verifies the correct DELETE request fires
  - `verifyWebhook` (valid HMAC): verifies `WebhookEvent` fields are correctly parsed
  - `verifyWebhook` (invalid HMAC): verifies `VideoProviderException` is thrown

## Tasks / Subtasks

- [x] Task 1: Add `app.video.bunny.api-base-url` property (AC: 2)
  - [x] Add `apiBaseUrl` field to `VideoProperties.Bunny` with default `https://video.bunnycdn.com`
  - [x] Add corresponding `app.video.bunny.api-base-url: https://video.bunnycdn.com` to `src/main/resources/application.yaml`
  - [x] Add test override to `src/test/resources/application-test.yaml`: `app.video.bunny.api-base-url: ${wiremock.server.bunny-service.baseUrl}`

- [x] Task 2: Define `VideoProviderAdapter` interface and all transport types in `infrastructure.video` (AC: 1)
  - [x] Create `VideoProviderAdapter.java` — interface with all 5 required methods and 2 optional default methods
  - [x] Create `UploadCredentials.java` — record in `infrastructure.video`
  - [x] Create `AssetStatus.java` — enum in `infrastructure.video`
  - [x] Create `SignedPlaybackUrl.java` — record in `infrastructure.video`
  - [x] Create `PlaybackTokenClaims.java` — record in `infrastructure.video`
  - [x] Create `WebhookEvent.java` — record in `infrastructure.video`
  - [x] Verify zero imports from `com.softropic.skillars.platform.*` in any of these files

- [x] Task 3: Implement `BunnyVideoProviderAdapter` in `infrastructure.video` (AC: 3, 4, 5)
  - [x] Create `BunnyVideoProviderAdapter.java` implementing `VideoProviderAdapter`; annotate with `@Slf4j`; NO Spring stereotype annotations (bean registration handled by VideoProviderConfig)
  - [x] Constructor: `BunnyVideoProviderAdapter(RestTemplate restTemplate, String apiKey, String libraryId, String cdnHostname, String apiBaseUrl)`
  - [x] Implement `initializeUpload()` — see Dev Notes for exact HTTP call and response mapping
  - [x] Implement `getAssetStatus()` — see Dev Notes for Bunny status integer → AssetStatus mapping
  - [x] Implement `generatePlaybackUrl()` — see Dev Notes for CDN token signing algorithm
  - [x] Implement `deleteAsset()` — fires DELETE request; throws `VideoProviderException` on non-2xx
  - [x] Implement `verifyWebhook()` — HMAC-SHA256 validation; throws `VideoProviderException` on invalid signature; never log raw payload

- [x] Task 4: Create `VideoProviderConfig` in `platform.video.config` (AC: 2)
  - [x] Create `VideoProviderConfig.java` annotated `@Configuration`
  - [x] Add `@ConditionalOnProperty(name = "app.video.provider", havingValue = "bunny")` method that creates `BunnyVideoProviderAdapter` bean
  - [x] Inject `VideoProperties` to read `bunny.apiKey`, `bunny.libraryId`, `bunny.cdnHostname`, `bunny.apiBaseUrl`
  - [x] Create a dedicated `RestTemplate` for Bunny.net calls with 10s connect + 30s read timeouts (use `SimpleClientHttpRequestFactory`)
  - [x] Verify `apiKey` is never logged or exposed

- [x] Task 5: Write `BunnyVideoProviderAdapterTest` (AC: 6)
  - [x] Create `src/test/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapterTest.java`
  - [x] Annotate with `@WireMockTest` (standalone, NOT extending BaseVideoIT)
  - [x] Inject `WireMockRuntimeInfo wireMock` in each test to get the WireMock base URL
  - [x] Instantiate `BunnyVideoProviderAdapter` directly with the WireMock base URL (no Spring context needed)
  - [x] Cover all 6 WireMock test scenarios from AC-6
  - [x] Use AssertJ (`assertThat`) for all assertions

## Dev Notes

### What Stories 1.1 and 1.2 Delivered (Do NOT Repeat)

Story 1.1 established the full module scaffold. These already exist:
- All JPA entities and repositories (`Video`, `UploadSession`, `PlaybackToken`)
- `VideoProperties` in `platform.video.config` — has `Bunny` nested class with `apiKey`, `libraryId`, `cdnHostname` (add `apiBaseUrl` in Task 1)
- `VideoConfig` in `platform.video.config` — existing; do NOT modify for this story
- `VideoApiAdvice`, `VideoErrorCode`, all exception classes
- `BaseVideoIT` test base class with WireMock server on `"bunny-service"` name
- `infrastructure.video.package-info.java` — the package exists but is empty; this story populates it

Story 1.2 added:
- `QuotaProvider` interface in `platform.video.contract`
- `NoOpQuotaProvider` in `src/test/java` (test-only, has `@Component` for test context satisfaction)
- `QuotaProviderValidator` in `VideoConfig`

### Package & File Structure

```
src/main/java/com/softropic/skillars/
├── infrastructure/
│   └── video/                           ← ALL new interface + impl files here
│       ├── package-info.java            ← exists from Story 1.1; keep as-is
│       ├── VideoProviderAdapter.java    ← NEW: interface
│       ├── UploadCredentials.java       ← NEW: record
│       ├── AssetStatus.java             ← NEW: enum
│       ├── SignedPlaybackUrl.java       ← NEW: record
│       ├── PlaybackTokenClaims.java     ← NEW: record
│       ├── WebhookEvent.java            ← NEW: record
│       └── BunnyVideoProviderAdapter.java ← NEW: implementation
└── platform/
    └── video/
        └── config/
            └── VideoProviderConfig.java ← NEW: @Configuration bean wiring

src/test/java/com/softropic/skillars/
└── infrastructure/
    └── video/
        └── BunnyVideoProviderAdapterTest.java ← NEW: standalone @WireMockTest
```

### Architecture Compliance Check (Do This Before Committing)

Verify every file in `infrastructure.video`:
- [ ] Zero imports from `com.softropic.skillars.platform.*`
- [ ] Zero business logic (no quota checks, no file-type validation, no domain state)
- [ ] No JPA `@Entity` or Spring Data `Repository` present
- [ ] Class names contain no domain-specific terms (`FileStorage`, `Tenant`, `Quota`)
- [ ] `BunnyVideoProviderAdapter` has ZERO Spring stereotype annotations (`@Service`, `@Component`, etc.)

`VideoProviderConfig` in `platform.video.config` MAY import from `infrastructure.video` (platform → infrastructure is the allowed dependency direction).

### Bunny.net Stream API — Exact HTTP Contracts

**Authentication header for all Stream API calls:** `AccessKey: {apiKey}` (NOT `Authorization: Bearer`)

#### initializeUpload

```http
POST {apiBaseUrl}/library/{libraryId}/videos
AccessKey: {apiKey}
Content-Type: application/json

{"title": "{fileName}"}
```

**Response (HTTP 200):**
```json
{
  "guid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "status": 0,
  "views": 0,
  "length": 0,
  "storageSize": 0
}
```

Extract `guid` as `providerUploadId`. The `signedUploadUrl` = `{apiBaseUrl}/tusupload` (the TUS base URL; clients must send `AuthorizationSignature`, `AuthorizationExpire`, `LibraryId`, `VideoId` headers when POSTing to this URL — the consuming service is responsible for forwarding these auth details).

**TUS auth signature formula (for downstream use by VideoService in Epic 2):**
```
SHA256(libraryId + apiKey + expiryEpochSeconds + videoGuid)
```
Use `java.security.MessageDigest` with `SHA-256`; hex-encode the result.

**Error handling:** Any non-2xx response → throw `VideoProviderException("initializeUpload", cause)`.

#### getAssetStatus

```http
GET {apiBaseUrl}/library/{libraryId}/videos/{providerAssetId}
AccessKey: {apiKey}
```

**Bunny `status` integer → `AssetStatus` enum mapping:**
```java
switch (bunnyStatus) {
    case 0, 1 -> AssetStatus.UPLOADING      // Created, Uploaded
    case 2, 3, 7 -> AssetStatus.PROCESSING  // Processing, Transcoding, JitSegmenting
    case 4, 8 -> AssetStatus.READY          // Finished, JitPlaylistsCreated
    case 5, 6 -> AssetStatus.FAILED         // Error, UploadFailed
    default -> AssetStatus.PROCESSING       // Unknown future states: conservative mapping
}
```

**404 response from Bunny.net** → map to `AssetStatus.DELETED` (asset no longer exists).

**Other non-2xx (not 404)** → throw `VideoProviderException("getAssetStatus", cause)`.

#### deleteAsset

```http
DELETE {apiBaseUrl}/library/{libraryId}/videos/{providerAssetId}
AccessKey: {apiKey}
```

**Response (HTTP 200):** `{"success": true, "message": null, "statusCode": 200}`

Non-2xx → throw `VideoProviderException("deleteAsset", cause)`.

#### verifyWebhook

```java
// 1. Compute expected signature
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(apiKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
byte[] expected = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));

// 2. Decode provided signature (lowercase hex)
byte[] provided = HexFormat.of().parseHex(signature.toLowerCase());

// 3. Constant-time comparison (prevents timing attacks)
if (!MessageDigest.isEqual(expected, provided)) {
    throw new VideoProviderException("verifyWebhook: invalid signature", null);
}

// 4. Parse JSON payload — use ObjectMapper; NEVER log rawPayload
WebhookEvent event = objectMapper.readValue(payload, BunnyWebhookPayload.class);
return new WebhookEvent(event.eventType(), event.videoGuid(), event.timestamp());
```

Bunny.net webhook JSON fields:
- `EventType` (String): e.g., `"video.upload.success"`, `"video.encoding.success"`, `"video.encoding.failed"`
- `VideoGuid` (String): the provider asset ID
- `Timestamp` (String, ISO-8601 or Unix epoch)

Create a private inner record or static class `BunnyWebhookPayload` for deserialization. DO NOT include this in the public interface.

#### generatePlaybackUrl

CDN Token signing (Bunny.net Token Authentication):

```java
// Signing key = apiKey bytes
// Path = "/{providerAssetId}/playlist.m3u8"
// Expires = claims.expiresAt().getEpochSecond()

String signaturePath = "/" + providerAssetId + "/playlist.m3u8";
long expires = claims.expiresAt().getEpochSecond();
String message = signaturePath + expires;  // no extra signing data, no IP

Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(apiKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
byte[] rawToken = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));

String token = "HS256-" + Base64.getEncoder().encodeToString(rawToken)
    .replace("\n", "")
    .replace("+", "-")
    .replace("/", "_")
    .replace("=", "");

String url = "https://" + cdnHostname + signaturePath + "?token=" + token + "&expires=" + expires;
return new SignedPlaybackUrl(url, claims.expiresAt());
```

**Note:** The `cdnHostname` should NOT include the `https://` prefix. The final URL always uses `https://`.

### VideoProviderConfig — Exact Pattern

```java
@Configuration
public class VideoProviderConfig {

    @Bean
    @ConditionalOnProperty(name = "app.video.provider", havingValue = "bunny")
    public VideoProviderAdapter videoProviderAdapter(VideoProperties properties) {
        VideoProperties.Bunny bunny = properties.getBunny();
        RestTemplate restTemplate = buildBunnyRestTemplate();
        return new BunnyVideoProviderAdapter(
            restTemplate,
            bunny.getApiKey(),
            bunny.getLibraryId(),
            bunny.getCdnHostname(),
            bunny.getApiBaseUrl()
        );
    }

    private RestTemplate buildBunnyRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        return new RestTemplate(new BufferingClientHttpRequestFactory(factory));
    }
}
```

**No `@Qualifier` needed** — there is only one `VideoProviderAdapter` bean. Do NOT use `@Primary`.

### BunnyVideoProviderAdapterTest — WireMock Pattern

```java
@WireMockTest
class BunnyVideoProviderAdapterTest {

    private static final String API_KEY = "test-api-key";
    private static final String LIBRARY_ID = "123";
    private static final String CDN_HOSTNAME = "test.b-cdn.net";

    private BunnyVideoProviderAdapter adapter;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wireMock) {
        RestTemplate restTemplate = new RestTemplate();
        adapter = new BunnyVideoProviderAdapter(
            restTemplate, API_KEY, LIBRARY_ID, CDN_HOSTNAME, wireMock.getHttpBaseUrl()
        );
    }

    @Test
    void initializeUpload_success() {
        stubFor(post(urlEqualTo("/library/123/videos"))
            .withHeader("AccessKey", equalTo(API_KEY))
            .willReturn(okJson("{\"guid\":\"abc-guid\",\"status\":0}")));

        UploadCredentials credentials = adapter.initializeUpload("test.mp4", 1024L);

        assertThat(credentials.providerUploadId()).isEqualTo("abc-guid");
        assertThat(credentials.signedUploadUrl()).contains("/tusupload");
    }

    @Test
    void getAssetStatus_mapsFinishedToReady() {
        stubFor(get(urlEqualTo("/library/123/videos/some-guid"))
            .willReturn(okJson("{\"guid\":\"some-guid\",\"status\":4}")));

        assertThat(adapter.getAssetStatus("some-guid")).isEqualTo(AssetStatus.READY);
    }

    // ... test each Bunny status integer mapping, deleteAsset, verifyWebhook valid/invalid
}
```

**Key:** `@WireMockTest` starts a standalone WireMock server per test; no Spring context, no Testcontainers. This makes the test fast.

Use `import static com.github.tomakehurst.wiremock.client.WireMock.*;` for all stub/assertion helpers.

### VideoProperties.Bunny — Required Addition

Add to `VideoProperties.Bunny` (existing class in `platform.video.config`):
```java
private String apiBaseUrl = "https://video.bunnycdn.com";
```

Add to `application.yaml` under `app.video.bunny:`:
```yaml
api-base-url: https://video.bunnycdn.com
```

Add to `src/test/resources/application-test.yaml` under `app.video.bunny:`:
```yaml
api-base-url: ${wiremock.server.bunny-service.baseUrl}
```

This makes the Stream API URL testable via WireMock in integration tests (BaseVideoIT subclasses).

### Exception Handling — Use Existing VideoProviderException

```java
// VideoProviderException(String operation, Throwable cause) is already defined in Story 1.1
// at platform.video.contract.exception.VideoProviderException

// Usage in BunnyVideoProviderAdapter:
} catch (RestClientException e) {
    throw new VideoProviderException("initializeUpload", e);
}
```

Do NOT catch generic `Exception`. Only catch `RestClientException` from `RestTemplate` calls and `JsonProcessingException` from Jackson parsing.

### NoOpQuotaProvider in Test Context

The `NoOpQuotaProvider` in `src/test/java` (has `@Component`) satisfies the `QuotaProvider` requirement in `VideoConfig` for integration tests. This is test-only — do NOT touch it. The `BunnyVideoProviderAdapterTest` does not spin up Spring context, so this is irrelevant for Task 5.

### Project Structure Rules

| Component | Package | Reason |
|---|---|---|
| `VideoProviderAdapter` + transport types | `infrastructure.video` | Pure technical contract — no domain |
| `BunnyVideoProviderAdapter` | `infrastructure.video` | Provider implementation — infrastructure |
| `VideoProviderConfig` | `platform.video.config` | Config layer creates and wires beans |
| `BunnyVideoProviderAdapterTest` | `test: infrastructure.video` | Mirrors source package |

### References

- FR-19: VideoProviderAdapter in infrastructure.video — `_bmad-output/planning-artifacts/video-module/epics.md` §FR-19
- FR-20: 5 required adapter operations — §FR-20
- FR-21: Optional archiveAsset/restoreAsset — §FR-21
- FR-22: Provider capability baseline — §FR-22
- FR-23: Bunny.net as MVP; `app.video.provider` property — §FR-23
- Story 1.3 ACs: `_bmad-output/planning-artifacts/video-module/epics.md` §Story 1.3
- Infrastructure/Platform boundary rules: `_bmad-output/project-context.md` §"Provider Adapter Pattern"
- Violation checklist: `_bmad-output/project-context.md` §"Violation Checklist"
- `VideoProviderException`: `platform.video.contract.exception.VideoProviderException`
- `VideoProperties.Bunny` (existing): `platform.video.config.VideoProperties`
- `VideoConfig` (existing): `platform.video.config.VideoConfig`
- `BaseVideoIT` (existing): `src/test/java/.../platform/video/BaseVideoIT.java`
- Reference blobstore config pattern: `infrastructure.blobstore.config.BlobstoreConfig`
- Bunny.net Stream API: Create Video `POST /library/{id}/videos`; Get Video `GET /library/{id}/videos/{videoId}`; Delete `DELETE /library/{id}/videos/{videoId}`
- Bunny.net CDN Token Auth: HMAC-SHA256 signed token, prefix `HS256-`, Base64URL encoded
- Bunny.net Webhook signature: HMAC-SHA256 with `apiKey` as secret, hex-encoded, constant-time compare
- Previous story notes: `video-1-2-quotaprovider-interface-integration-contract.md` §"Completion Notes"

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

None — implementation completed without debugging issues.

### Completion Notes List

- Implemented `VideoProviderAdapter` interface with 5 required methods and 2 optional defaults (archiveAsset/restoreAsset throw UnsupportedOperationException per spec).
- Created all 5 transport types as Java records/enum in `infrastructure.video` — zero platform imports in any of these files (AC-1 verified).
- `BunnyVideoProviderAdapter` constructor takes ObjectMapper as an additional parameter beyond the story spec (needed for webhook JSON deserialization); `VideoProviderConfig` injects the Spring-managed ObjectMapper.
- HMAC-SHA256 webhook verification uses constant-time `MessageDigest.isEqual()` to prevent timing attacks; raw payload is never logged.
- CDN token signing follows Bunny.net Token Authentication spec: `HS256-` prefix, Base64URL-encoded (no padding).
- `getAssetStatus` maps HTTP 404 → `AssetStatus.DELETED`; all other non-2xx → `VideoProviderException`.
- 16 tests in `BunnyVideoProviderAdapterTest` (10 parameterized status-mapping + 6 scenario tests) — all pass, zero regressions.
- Known architecture note: `BunnyVideoProviderAdapter` imports `VideoProviderException` from `platform.video.contract.exception` as required by the story spec. Future refactor could move this exception to `infrastructure.video.contract.exception` to match the blobstore pattern.

### File List

- `src/main/java/com/softropic/skillars/platform/video/config/VideoProperties.java` (modified — added `apiBaseUrl` to Bunny nested class)
- `src/main/resources/application.yaml` (modified — added `api-base-url` under `app.video.bunny`)
- `src/test/resources/application-test.yaml` (modified — added `api-base-url` WireMock override)
- `src/main/java/com/softropic/skillars/infrastructure/video/VideoProviderAdapter.java` (new)
- `src/main/java/com/softropic/skillars/infrastructure/video/UploadCredentials.java` (new)
- `src/main/java/com/softropic/skillars/infrastructure/video/AssetStatus.java` (new)
- `src/main/java/com/softropic/skillars/infrastructure/video/SignedPlaybackUrl.java` (new)
- `src/main/java/com/softropic/skillars/infrastructure/video/PlaybackTokenClaims.java` (new)
- `src/main/java/com/softropic/skillars/infrastructure/video/WebhookEvent.java` (new)
- `src/main/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapter.java` (new)
- `src/main/java/com/softropic/skillars/platform/video/config/VideoProviderConfig.java` (new)
- `src/test/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapterTest.java` (new)

## Change Log

- 2026-05-29: Initial implementation of Video-1.3 — VideoProviderAdapter interface, 5 transport types, BunnyVideoProviderAdapter with full Bunny.net Stream API integration, VideoProviderConfig bean wiring, and BunnyVideoProviderAdapterTest with 16 WireMock tests. All ACs satisfied. (claude-sonnet-4-6)
