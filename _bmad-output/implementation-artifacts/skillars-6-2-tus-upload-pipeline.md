# Story skillars-6.2: TUS Upload Pipeline

Status: Done

## Story

As a coach or eligible player,
I want to upload videos via resumable upload so that large files complete reliably even on poor mobile connections,
And so that the platform tracks each upload through its full lifecycle from initiation to published state.

## Acceptance Criteria

**AC 1: POST /api/video/uploads/initiate creates video record and returns TUS credentials**
Given a coach calls `POST /api/video/uploads/initiate` with valid fileName, fileSizeBytes, mimeType, and videoType,
Then a `videos` record is created with `operational_state = 'UPLOADING'` and `video_type` set from the request,
And a quota reservation is created in `video_quota_reservations` with the `video_type` populated,
And the response includes `videoId`, `uploadSessionId`, `providerUploadId` (Bunny.net video GUID), `signedUploadUrl` (`https://video.bunnycdn.com/tusupload`), `tusAuthorizationSignature`, `tusAuthorizationExpire`, and `tusLibraryId`,
And the endpoint returns HTTP 201 Created.

**AC 2: TUS resumable upload via tus-js-client**
Given a TUS upload session is active,
When the client uploads using `tus-js-client` directly to `https://video.bunnycdn.com/tusupload` with the four required Bunny.net TUS headers (`AuthorizationSignature`, `AuthorizationExpire`, `LibraryId`, `VideoId`) and TUS metadata (`filetype`, `title`),
Then the upload is resumable — interrupted uploads resume from the last confirmed byte offset without restarting.

**AC 3: Webhook received, signature verified, enqueued in outbox**
Given Bunny.net fires a status-change webhook (numeric `Status` field — 7: PresignedUploadFinished, 3: Finished, 5: Failed),
When the webhook reaches `POST /api/video/webhooks/bunny`,
Then the HMAC-SHA256 signature in the `X-BunnyStream-Signature` header is verified against the library Read-Only API key — unverified webhooks return `400` and are discarded,
And idempotency is enforced via `video_webhook_events.event_id` UNIQUE constraint — duplicate deliveries are silently acknowledged `200`,
And `BunnyVideoProviderAdapter.verifyWebhook()` maps the numeric `Status` to an internal event string: 7 → `"video.upload.success"`, 3 → `"video.encoding.success"`, 5 → `"video.encoding.failed"`, 8 → `"video.upload.failed"`.

**AC 4: `video.upload.success` webhook advances state to PROCESSING**
Given `video.upload.success` (mapped from Bunny Status=7) arrives and is enqueued in the `video_webhook_events` outbox,
When `WebhookEventProcessorScheduler.processPending()` fires,
Then `videos.operational_state` transitions `UPLOADING → PROCESSING` (existing behaviour in `VideoLifecycleService` — no change needed).

**AC 5: `video.encoding.success` webhook sets metadata, advances to READY, commits quota**
Given `video.encoding.success` (mapped from Bunny Status=3) arrives and is enqueued,
When `WebhookEventProcessorScheduler.processPending()` fires,
Then `VideoService.completeTranscoding(videoId)` is called,
And it calls `VideoProviderAdapter.getVideoMetadata(providerAssetId)` — a GET to `/library/{libraryId}/videos/{videoId}` — outside the transaction,
And `videos.duration_ms` is set to `VideoModel.length * 1000` and `videos.storage_bytes` is set to `VideoModel.storageSize`,
And `videos.operational_state` transitions `PROCESSING → READY`,
And `QuotaService.commit(reservationHandle)` is called — reserved bytes become permanently committed storage,
And a `VideoPublishedEvent(videoId, ownerId)` is published via `ApplicationEventPublisher` AFTER the transaction commits.

**AC 6: `video.encoding.failed` webhook transitions to FAILED and releases quota**
Given `video.encoding.failed` (mapped from Bunny Status=5) arrives and is enqueued,
When `WebhookEventProcessorScheduler.processPending()` fires,
Then `videos.operational_state` transitions `PROCESSING → FAILED`,
And `QuotaService.release(reservationHandle)` is called — reserved quota is restored (storage_used_bytes NOT decremented).

**AC 7: InitializeUploadRequest carries VideoType; quota reservation stores it**
Given `VideoService.initializeUpload()` is called with a `VideoType` in `InitializeUploadRequest`,
Then `QuotaService.reserve()` stores `videoType` in `video_quota_reservations.video_type`,
And `videos.video_type` is populated from the request.

**AC 8: Frontend video.api.js + video.store.js**
Given `tus-js-client@4.3.1` is already installed in package.json,
When a coach initiates an upload from the frontend,
Then `video.api.js` calls `POST /api/video/uploads/initiate` and passes the returned TUS credentials to `tus-js-client` as required headers,
And `video.store.js` tracks upload progress (0–100%) and state (idle/initiating/uploading/processing/error).

## Tasks / Subtasks

---

### Backend — Flyway V54

- [x] **Task 1: Create V54__video_type_column.sql** (AC: 1, 7)
  - [x] File: `src/main/resources/db/migration/V54__video_type_column.sql`
  - [x] Verify latest migration is V53 (`V53__video_quota_system.sql`) — V54 is correct
  - [x] Content:
    ```sql
    ALTER TABLE main.videos
        ADD COLUMN video_type VARCHAR(30) NULL
            CONSTRAINT chk_videos_video_type CHECK (
                video_type IS NULL OR video_type IN ('HOMEWORK', 'DRILL_DEMO', 'COACH_REVIEW')
            );
    ```
  - [x] No CHECK constraint on `video_quota_reservations.video_type` needed — V53 already added `chk_vqr_type`

---

### Backend — Contract Layer

- [x] **Task 2: Add `videoType` to `InitializeUploadRequest`** (AC: 7)
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/contract/InitializeUploadRequest.java` (MODIFY)
  - [x] Change from 4-arg to 5-arg record:
    ```java
    package com.softropic.skillars.platform.video.contract;

    public record InitializeUploadRequest(
        String ownerId,
        String fileName,
        long fileSizeBytes,
        String mimeType,
        VideoType videoType   // nullable — null means no type constraint applies
    ) {}
    ```
  - [x] **ALL CALLERS MUST BE UPDATED** — see Task 17 (DrillUploadService) and Tasks 19–20 (tests)

- [x] **Task 3: Create `VideoUploadInitiateRequest`** (AC: 1)
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/contract/VideoUploadInitiateRequest.java` (CREATE)
  - [x] External-facing DTO (ownerId resolved from principal in Resource layer):
    ```java
    package com.softropic.skillars.platform.video.contract;

    import jakarta.validation.constraints.Min;
    import jakarta.validation.constraints.NotBlank;
    import jakarta.validation.constraints.NotNull;
    import jakarta.validation.constraints.Pattern;

    public record VideoUploadInitiateRequest(
        @NotBlank String fileName,
        @Min(1) long fileSizeBytes,
        @Pattern(regexp = "video/.+", message = "mimeType must be a video/* MIME type")
        @NotBlank String mimeType,
        @NotNull VideoType videoType
    ) {}
    ```
  - [x] **Audit note (M-2)**: without the `@Pattern` constraint, callers can pass `"application/pdf"` or `"image/jpeg"`. Bunny rejects the TUS upload, but a quota reservation and video record are created first and left as orphans for the reaper to clean up. The regex `video/.+` is intentionally permissive (covers `video/mp4`, `video/quicktime`, `video/webm`, etc.) — add import `jakarta.validation.constraints.Pattern`.

- [x] **Task 4: Create `VideoPublishedEvent`** (AC: 5)
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/contract/event/VideoPublishedEvent.java` (CREATE)
  - [x] Package already exists (`event/package-info.java` is present)
    ```java
    package com.softropic.skillars.platform.video.contract.event;

    import java.util.UUID;

    public record VideoPublishedEvent(UUID videoId, String ownerId) {}
    ```

- [x] **Task 5: Extend `QuotaProvider` interface with 3-arg reserve()** (AC: 7)
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/contract/QuotaProvider.java` (MODIFY)
  - [x] Add default method after existing 2-arg `reserve()`:
    ```java
    default String reserve(@NotBlank String ownerId, @Min(1) long bytes, VideoType videoType) {
        return reserve(ownerId, bytes);
    }
    ```
  - [x] Do NOT add `@Observed` to the default method — Spring AOP cannot proxy interface default methods; the annotation would never fire. The `@Observed` on `QuotaService`'s concrete override (Task 13) is sufficient.
  - [x] Import: `com.softropic.skillars.platform.video.contract.VideoType`
  - [x] `NoOpQuotaProvider` (test tree) inherits the default — no change required for that class
  - [x] `@MockitoBean QuotaProvider` in tests does NOT inherit interface default methods — see Task 13 for required test mock updates

- [x] **Task 6: Create `VideoMetadata` record** (AC: 5)
  - [x] File: `src/main/java/com/softropic/skillars/infrastructure/video/VideoMetadata.java` (CREATE)
  - [x] Carries duration and storage size from a Bunny.net `GET /library/{libraryId}/videos/{id}` response:
    ```java
    package com.softropic.skillars.infrastructure.video;

    public record VideoMetadata(long durationMs, long storageBytes) {}
    ```

- [x] **Task 7: Add `getVideoMetadata()` default method to `VideoProviderAdapter`** (AC: 5)
  - [x] File: `src/main/java/com/softropic/skillars/infrastructure/video/VideoProviderAdapter.java` (MODIFY)
  - [x] Add after existing `getThumbnailUrl()` default:
    ```java
    default VideoMetadata getVideoMetadata(String providerAssetId) {
        throw new UnsupportedOperationException("getVideoMetadata not supported by this provider");
    }
    ```
  - [x] Import: already in the same package — no import needed

---

### Backend — Repository Layer

- [x] **Task 8: Add `videoType` field to `Video` entity** (AC: 7)
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/repo/Video.java` (MODIFY)
  - [x] Add after `private Visibility visibility`:
    ```java
    @Enumerated(EnumType.STRING)
    @Column(name = "video_type", nullable = true)
    private VideoType videoType;
    ```
  - [x] Import: `com.softropic.skillars.platform.video.contract.VideoType`

---

### Backend — Infrastructure Layer

- [x] **Task 9: Fix `VideoWebhookResource` — webhook header, eventId idempotency, signature hardening, and transaction safety** (AC: 3) — CRITICAL
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/api/VideoWebhookResource.java` (MODIFY)
  - [x] **Fix 1 — Wrong webhook header name**: The existing code reads `@RequestHeader(value = "BunnyCDN-Signature", ...)`. Bunny.net actually sends `X-BunnyStream-Signature`. All webhooks are currently rejected due to this mismatch.
    ```java
    // BEFORE:
    @RequestHeader(value = "BunnyCDN-Signature", required = false, defaultValue = "") String signature
    // AFTER:
    @RequestHeader(value = "X-BunnyStream-Signature", required = false, defaultValue = "") String signature
    ```
  - [x] **Fix 2 — Broken eventId idempotency key** (CRITICAL): The existing code constructs:
    ```java
    String eventId = event.providerAssetId() + ":" + event.eventType() + ":" + event.timestamp().getEpochSecond();
    ```
    After the webhook fix, `verifyWebhook()` returns `timestamp = Instant.now()` (Bunny sends no timestamp in the payload — see Task 12 Sub-task A). Two re-deliveries of the same webhook arriving in different clock-seconds produce **different** `eventId` values → both pass `existsByEventId()` → both are stored → both processed → double state transition and double quota commit. Fix: use only `providerAssetId + ":" + eventType` as the key — there is exactly one of each event type per video asset in the Bunny lifecycle:
    ```java
    // BEFORE (broken — re-deliveries in different seconds produce different keys):
    String eventId = event.providerAssetId() + ":" + event.eventType() + ":" + event.timestamp().getEpochSecond();
    // AFTER:
    String eventId = event.providerAssetId() + ":" + event.eventType();
    ```
  - [x] **Fix 3 — Race condition on concurrent re-delivery and missing transaction** (CRITICAL): The existing `receiveBunnyWebhook()` method has no `@Transactional` boundary. Two simultaneous re-deliveries both pass `existsByEventId()` (both read false), both attempt `save()`, and one throws `DataIntegrityViolationException` on the unique `event_id` constraint — returning 500 to Bunny, triggering yet another retry. Also, a partial failure between the EXISTS check and `save()` returns 500 with no stored record — the scheduler never sees the event.
    - Add `@Transactional` to `receiveBunnyWebhook()`.
    - Catch `DataIntegrityViolationException` around the `save()` call and return 200 (idempotent duplicate):
    ```java
    import org.springframework.dao.DataIntegrityViolationException;
    import org.springframework.transaction.annotation.Transactional;

    @Transactional
    @PreAuthorize("permitAll()")
    @Observed(name = "video.webhook.receive")
    @PostMapping("/webhooks/bunny")
    public ResponseEntity<Void> receiveBunnyWebhook(...) {
        // ... signature verification unchanged ...

        String eventId = event.providerAssetId() + ":" + event.eventType();

        if (webhookEventRepository.existsByEventId(eventId)) {
            return ResponseEntity.ok().build();
        }

        VideoWebhookEvent outboxEvent = new VideoWebhookEvent();
        // ... set fields ...
        try {
            webhookEventRepository.save(outboxEvent);
        } catch (DataIntegrityViolationException e) {
            // Concurrent delivery — the other thread won the INSERT race; idempotent ack
            log.debug("Duplicate webhook delivery absorbed for eventId={}", eventId);
            return ResponseEntity.ok().build();
        }

        return ResponseEntity.ok().build();
    }
    ```

- [x] **Task 10: Extend `UploadCredentials` with TUS credential fields** (AC: 1, 2) — CRITICAL
  - [x] File: `src/main/java/com/softropic/skillars/infrastructure/video/UploadCredentials.java` (MODIFY)
  - [x] Bunny.net TUS uploads require four headers set by the client. The server computes the signature and expiry (apiKey must never reach the client). Add these fields to `UploadCredentials` so `BunnyVideoProviderAdapter.initializeUpload()` can return them to the service layer.
    ```java
    package com.softropic.skillars.infrastructure.video;

    public record UploadCredentials(
        String providerUploadId,
        String signedUploadUrl,
        String tusAuthorizationSignature,  // hex SHA-256(libraryId + apiKey + expireEpoch + videoId)
        long tusAuthorizationExpire,       // Unix epoch seconds (≥ 3600s from now)
        long tusLibraryId                  // numeric library ID, passed as LibraryId header by client
    ) {}
    ```
  - [x] `VideoId` (= `providerUploadId`) does not need a separate field — the client already receives `providerUploadId` in the response.
  - [x] Note: `tusAuthorizationExpire` must be aligned with `VideoProperties.upload.sessionTtlMinutes` — do NOT hardcode 3600. The correct value is `sessionTtlMinutes × 60` seconds from now. See Task 12 Sub-task B for the `BunnyVideoProviderAdapter` constructor change and the required `VideoProviderConfig` update.

- [x] **Task 11: Extend `InitializeUploadResponse` with TUS credential fields** (AC: 1) — CRITICAL
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/contract/InitializeUploadResponse.java` (MODIFY)
  - [x] These fields are returned to the frontend so `tus-js-client` can set the required Bunny.net TUS authentication headers:
    ```java
    package com.softropic.skillars.platform.video.contract;

    import java.time.Instant;
    import java.util.UUID;

    public record InitializeUploadResponse(
        UUID videoId,
        UUID sessionId,
        String providerUploadId,
        String signedUploadUrl,
        Instant expiresAt,
        String tusAuthorizationSignature,
        long tusAuthorizationExpire,
        long tusLibraryId
    ) {}
    ```
  - [x] ALL callers that construct `new InitializeUploadResponse(...)` must be updated — that is `VideoService.initializeUpload()` and `VideoService.retryUpload()`. See Task 14.
  - [x] **BREAKING API CHANGE — field renamed**: The existing `InitializeUploadResponse` field is named `uploadSessionId`. This record replaces it with `sessionId`. The JSON key in the HTTP response changes from `"uploadSessionId"` to `"sessionId"`. The `video.store.js` (Task 22) correctly reads `data.sessionId`. Before closing this task, run the following and update every hit:
    ```bash
    grep -rn "uploadSessionId" src/
    ```
    Known consumers: `VideoService.initializeUpload()`, `VideoService.retryUpload()`, `VideoUploadInitializationIT` — verify no frontend JS or other tests reference the old name.

- [x] **Task 12: Fix `BunnyVideoProviderAdapter` — webhook payload, TUS credentials, metadata** (AC: 3, 5) — CRITICAL
  - [x] File: `src/main/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapter.java` (MODIFY)
  - [x] **Sub-task A — Fix `BunnyWebhookPayload` inner record and `verifyWebhook()`**
    The existing `BunnyWebhookPayload` parses `@JsonProperty("EventType")` (field does not exist in Bunny payload) and `@JsonProperty("Timestamp")` (field does not exist in Bunny payload). Actual Bunny webhook body:
    ```json
    {"VideoLibraryId": 133, "VideoGuid": "guid", "Status": 3}
    ```
    Map the numeric `Status` to the internal event strings expected by `WebhookEventProcessorScheduler.dispatchEvent()`:
    - 7 (PresignedUploadFinished) → `"video.upload.success"`
    - 3 (Finished) → `"video.encoding.success"`
    - 5 (Failed) → `"video.encoding.failed"`
    - any other → `"video.status." + status` (logged as unknown by scheduler, no state change)

    Replace the three inner records (`BunnyWebhookPayload`) and the `verifyWebhook()` implementation:
    ```java
    private record BunnyWebhookPayload(
        @JsonProperty("VideoLibraryId") long videoLibraryId,
        @JsonProperty("VideoGuid") String videoGuid,
        @JsonProperty("Status") int status
    ) {}
    ```
    Inside `verifyWebhook()`, after HMAC verification, replace the payload mapping:
    ```java
    BunnyWebhookPayload webhookPayload = objectMapper.readValue(payload, BunnyWebhookPayload.class);
    String eventType = switch (webhookPayload.status()) {
        case 7 -> "video.upload.success";
        case 3 -> "video.encoding.success";
        case 5 -> "video.encoding.failed";
        case 8 -> "video.upload.failed";  // Pre-signed upload failed (distinct from encoding failure)
        default -> "video.status." + webhookPayload.status();
    };
    return new WebhookEvent(eventType, webhookPayload.videoGuid(), Instant.now());
    // Bunny.net webhook payloads carry no timestamp — Instant.now() is the receipt time
    ```
  - [x] **Sub-task A also — Harden `HexFormat.parseHex()` against malformed signature**: The existing `verifyWebhook()` calls `HexFormat.of().parseHex(signature.toLowerCase())` as a single statement. If the `X-BunnyStream-Signature` header contains non-hex characters (malformed delivery, CDN mangling), `parseHex()` throws `IllegalArgumentException` — not `VideoProviderException`. `VideoWebhookResource` only catches `VideoProviderException`, so the `IllegalArgumentException` propagates as a 500. Replace the single-line parse with:
    ```java
    byte[] provided;
    try {
        provided = HexFormat.of().parseHex(signature.toLowerCase());
    } catch (IllegalArgumentException e) {
        throw new VideoProviderException("verifyWebhook: malformed signature header", e);
    }
    ```
    This converts the `IllegalArgumentException` to a `VideoProviderException`, which `VideoWebhookResource` already catches and maps to a 400 response.
  - [x] **Sub-task B — Fix `initializeUpload()` to generate TUS credentials**
    TUS auth signature algorithm: plain SHA-256 (NOT HMAC-SHA256) of the concatenated string `libraryId + apiKey + expireEpoch + videoGuid`.
  - [x] **Add `sessionTtlSeconds` constructor parameter and validate `libraryId` is numeric**: The TUS credential expiry must align with the upload session TTL configured in `VideoProperties.upload.sessionTtlMinutes`. Do NOT hardcode 3600 — if `sessionTtlMinutes` is changed by ops, the TUS credential window and the session window drift apart. Also, `initializeUpload()` calls `Long.parseLong(libraryId)` for the `tusLibraryId` field — a misconfigured non-numeric `libraryId` throws `NumberFormatException` at the first TUS upload request with no informative message. Parse and store it as a `long` in the constructor so the failure is at startup, not at request time:
    ```java
    private final long sessionTtlSeconds;
    private final long libraryIdLong;  // ← NEW: parsed once at startup

    public BunnyVideoProviderAdapter(RestTemplate restTemplate,
                                     String apiKey,
                                     String libraryId,
                                     String cdnHostname,
                                     String apiBaseUrl,
                                     ObjectMapper objectMapper,
                                     long sessionTtlSeconds) {   // ← NEW
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
        this.libraryId = libraryId;
        this.cdnHostname = cdnHostname;
        this.apiBaseUrl = apiBaseUrl;
        this.objectMapper = objectMapper;
        this.sessionTtlSeconds = sessionTtlSeconds;
        try {
            this.libraryIdLong = Long.parseLong(libraryId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "BunnyVideoProviderAdapter: app.video.bunny.library-id must be numeric, got: " + libraryId, e);
        }
    }
    ```
    Use `libraryIdLong` (instead of `Long.parseLong(libraryId)`) in `initializeUpload()` when constructing `UploadCredentials`.
  - [x] **Update `VideoProviderConfig.videoProviderAdapter()` bean** to pass the new argument (file: `src/main/java/com/softropic/skillars/platform/video/config/VideoProviderConfig.java` — MODIFY):
    ```java
    return new BunnyVideoProviderAdapter(
        restTemplate,
        bunny.getApiKey(),
        bunny.getLibraryId(),
        bunny.getCdnHostname(),
        bunny.getApiBaseUrl(),
        objectMapper,
        (long) properties.getUpload().getSessionTtlMinutes() * 60L   // ← NEW
    );
    ```
    `VideoProperties properties` is already injected into `videoProviderAdapter()` via the method parameter.
  - [x] Replace the existing `initializeUpload()` return statement with the TUS credential computation:
    ```java
    private static final String TUS_ENDPOINT = "https://video.bunnycdn.com/tusupload";

    @Override
    public UploadCredentials initializeUpload(String fileName, long fileSizeBytes) {
        HttpHeaders headers = buildHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(Map.of("title", fileName), headers);
        try {
            var response = restTemplate.postForEntity(
                apiBaseUrl + "/library/" + libraryId + "/videos",
                entity,
                BunnyCreateVideoResponse.class
            );
            String guid = response.getBody().guid();
            long expireEpoch = Instant.now().getEpochSecond() + sessionTtlSeconds;
            String tusSignature = computeTusSignature(libraryId, apiKey, expireEpoch, guid);
            return new UploadCredentials(guid, TUS_ENDPOINT, tusSignature, expireEpoch,
                libraryIdLong);  // ← use pre-parsed field, never Long.parseLong(libraryId) here
        } catch (RestClientException e) {
            throw new VideoProviderException("initializeUpload", e);
        }
    }

    private String computeTusSignature(String libId, String key, long expireEpoch, String videoGuid) {
        try {
            String input = libId + key + expireEpoch + videoGuid;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new VideoProviderException("computeTusSignature", e);
        }
    }
    ```
    Note: `TUS_ENDPOINT` is hardcoded even though `apiBaseUrl` defaults to `"https://video.bunnycdn.com"` (making `apiBaseUrl + "/tusupload"` accidentally produce the correct URL). The hardcoded constant removes the coupling — the TUS endpoint is structurally distinct from the REST API base URL and must not be derived from it.
    `MessageDigest`, `HexFormat`, and `StandardCharsets` are already imported.
    Use `libraryIdLong` (not `Long.parseLong(libraryId)`) for the `UploadCredentials.tusLibraryId` argument.
  - [x] **Sub-task C — Extend `BunnyVideoResponse` and implement `getVideoMetadata()`**
    Bunny Status=3 (Finished) webhook carries only `{VideoLibraryId, VideoGuid, Status}` — no duration or storage in the webhook body. Fetch them from `GET /library/{libraryId}/videos/{id}` after encoding completes. The `BunnyVideoResponse` private record already calls this endpoint in `getAssetStatus()`. Extend it to capture `length` (int, seconds) and `storageSize` (Long, bytes).
    - [x] **Step 0 — Verify field names against the actual Bunny API** (audit H-1): the field names `length` and `storageSize` are assumed based on prior integration knowledge. If incorrect, Jackson silently ignores unmatched JSON properties and `durationMs`/`storageBytes` would be 0 for all videos. Before coding this sub-task, make a real `GET /library/{libraryId}/videos/{id}` call against a test library (or check Bunny's API reference at `https://docs.bunny.net/reference/video_getvideo`) and confirm exact casing. Add a comment in the record with the verified field names and the date checked.
    ```java
    private record BunnyVideoResponse(String guid, int status, Integer length, Long storageSize) {}
    ```
    Jackson maps JSON `"length"` → `length` and `"storageSize"` → `storageSize` by name. If absent in the response (video still processing), they default to `null` — the null-safe handling in `completeTranscoding()` covers this.

    Add the override (after `getAssetStatus()`):
    ```java
    @Override
    public VideoMetadata getVideoMetadata(String providerAssetId) {
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        try {
            var response = restTemplate.exchange(
                apiBaseUrl + "/library/" + libraryId + "/videos/" + providerAssetId,
                HttpMethod.GET,
                entity,
                BunnyVideoResponse.class
            );
            BunnyVideoResponse body = response.getBody();
            // Audit note (M-3): getBody() can return null on a 200 with empty body during
            // Bunny infrastructure issues — guard before field access.
            if (body == null) {
                throw new VideoProviderException(
                    "getVideoMetadata: empty response body for providerAssetId=" + providerAssetId, null);
            }
            long durationMs = body.length() != null ? body.length() * 1000L : 0L;
            long storageBytes = body.storageSize() != null ? body.storageSize() : 0L;
            return new VideoMetadata(durationMs, storageBytes);
        } catch (HttpClientErrorException.NotFound e) {
            throw new VideoProviderException("getVideoMetadata: asset not found " + providerAssetId, e);
        } catch (RestClientException e) {
            throw new VideoProviderException("getVideoMetadata", e);
        }
    }
    ```
    Import to add: `com.softropic.skillars.infrastructure.video.VideoMetadata`
  - [x] **Sub-task D — Fix `mapBunnyStatus()` — Status=3 (Finished) must map to READY** (CRITICAL):
    The existing `mapBunnyStatus()` maps `case 2, 3, 7 -> AssetStatus.PROCESSING`. Bunny Status=3 is "Finished" (encoding complete) and Status=4 is "Resolution Finished" — both should map to READY. The current mapping means any polling-based reconciliation or admin path that calls `getAssetStatus()` on a finished video returns PROCESSING, incorrectly. The webhook pipeline correctly maps Status=3 → `video.encoding.success` → READY; `getAssetStatus()` must agree.
    ```java
    // BEFORE:
    case 0, 1 -> AssetStatus.UPLOADING;
    case 2, 3, 7 -> AssetStatus.PROCESSING;
    case 4, 8 -> AssetStatus.READY;
    case 5, 6 -> AssetStatus.FAILED;

    // AFTER:
    case 0, 6 -> AssetStatus.UPLOADING;
    case 1, 2, 7 -> AssetStatus.PROCESSING;
    case 3, 4 -> AssetStatus.READY;
    case 5, 8 -> AssetStatus.FAILED;  // 5=Encoding failed, 8=Pre-signed upload failed
    ```
    Note: Status=8 is "Pre-signed upload failed" per Bunny docs — NOT "TitleUpdating" (that was a misidentification). It must map to FAILED. Status=9 (auto-captions) and Status=10 (auto title/description) are informational; default → PROCESSING is acceptable for those.

---

### Backend — Infrastructure Layer (Webhook Signing Key)

- [x] **Task 23: Add `webhookSigningSecret` to `VideoProperties.Bunny` and `BunnyVideoProviderAdapter`** — CRITICAL
  - [x] **Context**: Bunny.net Stream exposes two distinct keys per library: (1) the full API Key (used in `AccessKey` header for REST API calls) and (2) a separate Read-Only API Key (used exclusively to sign outgoing webhook payloads via HMAC-SHA256). These are different values in the Bunny dashboard. The current code uses the same `apiKey` property for both REST calls and webhook HMAC verification — if the configured key is the full write key (the natural default), all webhook signature checks fail and every `POST /api/video/webhooks/bunny` returns 400. This is a deployment day-1 failure.
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/config/VideoProperties.java` (MODIFY)
    - In the `Bunny` nested class, add a new field after `apiKey`:
    ```java
    private String webhookSigningSecret;
    ```
    - Config key: `app.video.bunny.webhook-signing-secret`
  - [x] File: `src/main/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapter.java` (MODIFY)
    - Add `String webhookSigningSecret` constructor parameter (after `sessionTtlSeconds`):
    ```java
    private final String webhookSigningSecret;

    public BunnyVideoProviderAdapter(RestTemplate restTemplate,
                                     String apiKey,
                                     String libraryId,
                                     String cdnHostname,
                                     String apiBaseUrl,
                                     ObjectMapper objectMapper,
                                     long sessionTtlSeconds,
                                     String webhookSigningSecret) {  // ← NEW
        // ... existing assignments ...
        this.webhookSigningSecret = Objects.requireNonNull(webhookSigningSecret,
            "webhookSigningSecret must not be null — set app.video.bunny.webhook-signing-secret");
    }
    ```
    - In `verifyWebhook()`, replace `apiKey` with `webhookSigningSecret` as the HMAC key:
    ```java
    // BEFORE:
    mac.init(new SecretKeySpec(apiKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    // AFTER:
    mac.init(new SecretKeySpec(webhookSigningSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    ```
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/config/VideoProviderConfig.java` (MODIFY)
    - Pass `bunny.getWebhookSigningSecret()` as the final constructor argument:
    ```java
    return new BunnyVideoProviderAdapter(
        restTemplate,
        bunny.getApiKey(),
        bunny.getLibraryId(),
        bunny.getCdnHostname(),
        bunny.getApiBaseUrl(),
        objectMapper,
        (long) properties.getUpload().getSessionTtlMinutes() * 60L,
        bunny.getWebhookSigningSecret()   // ← NEW
    );
    ```
  - [x] Add `app.video.bunny.webhook-signing-secret` to `application-dev.yml` and `application-test.yml` (use a test value; never use the real key in test config).
  - [x] **Before deploying to any environment**: copy the library's **Read-Only API Key** from the Bunny Stream dashboard → Library Settings → API → "Read-Only API Key" and set it in `app.video.bunny.webhook-signing-secret`. The full API Key remains in `app.video.bunny.api-key`.

---

### Backend — Service Layer

- [x] **Task 13: Override 3-arg `reserve()` in `QuotaService`** (AC: 7)
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/service/QuotaService.java` (MODIFY)
  - [x] **Step 0 — Verify `sumActiveReservedBytes()` exists on `VideoQuotaReservationRepository`** (audit M-5): the 3-arg `reserve()` snippet references `reservationRepository.sumActiveReservedBytes(ownerId)`. This was added in Story 6.1's patch run. Before coding, grep for the method:
    ```bash
    grep -rn "sumActiveReservedBytes" src/
    ```
    If it is absent, add it to `VideoQuotaReservationRepository`:
    ```java
    @Query("SELECT COALESCE(SUM(r.reservedBytes), 0) FROM VideoQuotaReservation r " +
           "WHERE r.userId = :userId AND r.status = 'ACTIVE'")
    long sumActiveReservedBytes(@Param("userId") String userId);
    ```
  - [x] Replace the existing 2-arg `reserve()` body with a delegation to the new 3-arg override:
    ```java
    @Override
    @Observed(name = "video.quota.reserve")  // ← audit note (M-1): 2-arg callers go through the Spring
    @Transactional                            //   proxy so @Observed fires here; the self-call to 3-arg
    public String reserve(String ownerId, long bytes) {   //   bypasses the proxy, so @Observed on 3-arg
        return reserve(ownerId, bytes, null);             //   alone would miss this path entirely.
    }

    @Override
    @Observed(name = "video.quota.reserve")  // ← required: 3-arg callers bypass the 2-arg interface proxy
    @Transactional
    public String reserve(String ownerId, long bytes, VideoType videoType) {
        ensureQuotaRowExists(ownerId);
        VideoQuota quota = videoQuotaRepository.findByIdForUpdate(ownerId)
            .orElseThrow(() -> new IllegalStateException("video_quotas row missing after init for: " + ownerId));
        long timeoutMinutes = quotaConfigService.getReservationTimeoutMinutes();
        if (timeoutMinutes <= 0) {
            throw new IllegalStateException(
                "platform.video_reservation_timeout_minutes must be positive, got: " + timeoutMinutes);
        }
        long storageQuota = quotaConfigService.getStorageQuotaBytes(ownerId);
        long activeReservedBytes = reservationRepository.sumActiveReservedBytes(ownerId);
        if (storageQuota == 0 || quota.getStorageUsedBytes() + activeReservedBytes + bytes > storageQuota) {
            throw new QuotaExceededException(ownerId, storageQuota, bytes);
        }
        VideoQuotaReservation reservation = new VideoQuotaReservation();
        reservation.setUserId(ownerId);
        reservation.setReservedBytes(bytes);
        reservation.setStatus("ACTIVE");
        reservation.setVideoType(videoType);
        reservation.setExpiresAt(Instant.now().plus(timeoutMinutes, ChronoUnit.MINUTES));
        VideoQuotaReservation saved = reservationRepository.save(reservation);
        log.debug("Quota reserved: ownerId={} bytes={} type={} reservationId={}",
                  ownerId, bytes, videoType, saved.getId());
        return saved.getId().toString();
    }
    ```
  - [x] Import: `com.softropic.skillars.platform.video.contract.VideoType`

- [x] **Task 14: Update `VideoService` — TUS fields in responses, completeTranscoding, failTranscoding, type constraints** (AC: 5, 6, 7)
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/service/VideoService.java` (MODIFY)
  - [x] **Step 0 — MANDATORY pre-coding verification** (audit M-4): open `VideoLifecycleService.transitionOperationalState()` and confirm it uses the `findById() + save(entity)` pattern, NOT a `@Modifying @Query` JPQL UPDATE. If it uses a JPQL UPDATE, the `video.setDurationMs()` and `video.setStorageBytes()` mutations in Phase 3 of `completeTranscoding()` are silently discarded — `duration_ms` and `storage_bytes` would be 0 for all videos permanently with no exception or log. Document the result in the Completion Notes before closing this task.
  - [x] **Add `VideoLifecycleService videoLifecycleService` to constructor** — required by `completeTranscoding()` for state transitions; `VideoService` does not currently have this field. Lombok `@RequiredArgsConstructor` picks it up automatically once declared. Import: `com.softropic.skillars.platform.video.service.VideoLifecycleService`
  - [x] **Add `ApplicationEventPublisher publisher` to constructor** (Lombok `@RequiredArgsConstructor` picks it up)
  - [x] **Add `VideoTypeConstraints videoTypeConstraints` to constructor** — inject so `initializeUpload()` can enforce per-type file size limits. Import: `com.softropic.skillars.platform.video.service.VideoTypeConstraints`
  - [x] **Update `initializeUpload()` — type constraint check, 3-arg reserve, video_type, TUS fields in response**
    - After `validationChain.validate(...)` and before `quotaProvider.check(...)`, add the per-type constraint guard:
      ```java
      // Enforce video-type-specific size limits (e.g., COACH_REVIEW ≤ 1 GB, HOMEWORK ≤ 250 MB)
      if (request.videoType() != null) {
          videoTypeConstraints.validate(request.videoType(), request.fileSizeBytes(), 0);
      }
      ```
      `VideoValidationException` thrown by `validate()` is already mapped to HTTP 422 by `VideoApiAdvice`. Pass `0` for durationSeconds — duration is unknown at upload initiation; only size can be pre-validated.
    - Change reserve call: `reservationHandle = quotaProvider.reserve(request.ownerId(), request.fileSizeBytes(), request.videoType())`
    - Inside first `transactionTemplate.execute()`, after `video.setVisibility(Visibility.PRIVATE)`: `video.setVideoType(request.videoType())`
    - Change the final `return` statement:
      ```java
      return new InitializeUploadResponse(
          videoId, sessionId,
          credentials.providerUploadId(), credentials.signedUploadUrl(),
          expiresAt,
          credentials.tusAuthorizationSignature(),
          credentials.tusAuthorizationExpire(),
          credentials.tusLibraryId());
      ```
    - **Audit note (H-2) — `expiresAt` source**: `expiresAt` must be derived from the TUS credential expiry, not independently recomputed, to avoid drift. Use `Instant.ofEpochSecond(credentials.tusAuthorizationExpire())`. Do NOT compute `Instant.now().plus(sessionTtlMinutes, MINUTES)` separately — both values would then be computed at slightly different clock instants, causing a spurious mismatch.
  - [x] **Update `retryUpload()` — type constraint check, 3-arg reserve, TUS fields in response**
    - After loading `video` and before the existing `validationChain.validate()` call, add per-type size enforcement (same guard as `initializeUpload()`):
      ```java
      if (video.getVideoType() != null) {
          videoTypeConstraints.validate(video.getVideoType(), request.fileSizeBytes(), 0);
      }
      ```
      `VideoValidationException` is already mapped to 422 by `VideoApiAdvice` — no new exception handling needed.
    - Change reserve call: `reservationHandle = quotaProvider.reserve(request.ownerId(), request.fileSizeBytes(), video.getVideoType())`
    - Change the final `return` statement (same TUS fields pattern as above)
  - [x] **Add `completeTranscoding()` method** — two-phase: fetch metadata before opening a DB transaction, then write atomically:
    ```java
    @Observed(name = "video.transcoding.complete")
    public void completeTranscoding(UUID videoId) {
        // Phase 1: read providerAssetId in a short transaction, release the connection immediately
        String providerAssetId = transactionTemplate.execute(status ->
            videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId))
                .getProviderAssetId());

        // Phase 2: fetch metadata from Bunny with NO active DB transaction.
        // Avoids holding a connection from the pool during the 30-second RestTemplate read timeout.
        VideoMetadata meta = null;
        if (providerAssetId != null) {
            try {
                meta = videoProviderAdapter.getVideoMetadata(providerAssetId);
            } catch (Exception e) {
                log.warn("Could not fetch metadata from provider for videoId={}: {}", videoId, e.getMessage());
                // Non-fatal: video still advances to READY; metadata can be reconciled later
            }
        }

        // Phase 3: write in a single transaction — metadata, state transition, quota commit, event
        final VideoMetadata finalMeta = meta;
        transactionTemplate.execute(status -> {
            Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));
            if (finalMeta != null) {
                if (finalMeta.durationMs() > 0) video.setDurationMs(finalMeta.durationMs());
                if (finalMeta.storageBytes() > 0) video.setStorageBytes(finalMeta.storageBytes());
            }
            // Audit note (H-3): This mutation + transitionOperationalState() pattern is only safe if
            // VideoLifecycleService.transitionOperationalState() internally calls findById() (getting the
            // same L1-cached entity instance) and then save() on it. If it uses a JPQL/native UPDATE
            // statement instead, the durationMs/storageBytes mutations above are silently lost.
            // BEFORE CODING THIS METHOD: open VideoLifecycleService.transitionOperationalState() and
            // confirm it calls findById() + save(entity) (not @Modifying @Query). If it does not,
            // add an explicit videoRepository.save(video) call before this line.
            videoLifecycleService.transitionOperationalState(videoId, OperationalState.READY);

            UploadSession session = uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)
                .orElse(null);
            if (session != null && session.getReservationHandle() != null) {
                quotaProvider.commit(session.getReservationHandle());
            } else {
                log.warn("No reservation handle found for videoId={} during transcoding commit — quota not committed", videoId);
            }
            // Publish inside the TX so @TransactionalEventListener(AFTER_COMMIT) fires after commit
            publisher.publishEvent(new VideoPublishedEvent(videoId, video.getOwnerId()));
            return null;
        });
    }
    ```
    `transactionTemplate` is already a constructor field in `VideoService` — no new dependency needed.
  - [x] **Add `failTranscoding()` method**:
    ```java
    @Observed(name = "video.transcoding.failed")
    @Transactional
    public void failTranscoding(UUID videoId) {
        videoLifecycleService.transitionOperationalState(videoId, OperationalState.FAILED);

        UploadSession session = uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)
            .orElse(null);
        if (session != null && session.getReservationHandle() != null) {
            quotaProvider.release(session.getReservationHandle());
        } else {
            log.warn("No reservation handle found for videoId={} during transcoding failure — quota not released", videoId);
        }
    }
    ```
  - [x] Imports to add: `com.softropic.skillars.infrastructure.video.VideoMetadata`, `com.softropic.skillars.platform.video.contract.event.VideoPublishedEvent`, `org.springframework.context.ApplicationEventPublisher`, `com.softropic.skillars.platform.video.service.VideoTypeConstraints`, `com.softropic.skillars.platform.video.service.VideoLifecycleService`
  - [x] `videoProviderAdapter` is already a constructor field — no new dependency

- [x] **Task 15: Update `WebhookEventProcessorScheduler`** (AC: 4, 5, 6)
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/service/WebhookEventProcessorScheduler.java` (MODIFY)
  - [x] Add `VideoService videoService` to constructor fields
  - [x] Add `ApplicationEventPublisher publisher` to constructor fields — needed to publish `VideoUploadedEvent` (see audit C-2 below)
  - [x] Replace `dispatchEvent()`:
    ```java
    private void dispatchEvent(VideoWebhookEvent event) {
        Optional<Video> videoOpt = videoRepository.findByProviderAssetId(event.getProviderAssetId());
        if (videoOpt.isEmpty()) {
            log.warn("No video found for providerAssetId={}, skipping event", event.getProviderAssetId());
            return;
        }
        Video video = videoOpt.get();
        UUID videoId = video.getId();
        switch (event.getEventType()) {
            case "video.upload.success" -> {
                videoLifecycleService.transitionOperationalState(videoId, OperationalState.PROCESSING);
                // Audit note (C-2): VideoUploadedEvent triggers the Story 6.3 moderation pipeline.
                // Publishing AFTER the state transition so handlers observe PROCESSING state.
                publisher.publishEvent(new VideoUploadedEvent(videoId, video.getOwnerId()));
            }
            case "video.encoding.success" -> {
                // Compensate for out-of-order delivery: if Status=3 arrives before Status=7,
                // the video is still UPLOADING. Advance through PROCESSING first so
                // completeTranscoding()'s PROCESSING→READY transition is valid.
                // transitionOperationalState is idempotent — PROCESSING→PROCESSING is a no-op.
                if (video.getOperationalState() == OperationalState.UPLOADING) {
                    log.warn("video.encoding.success arrived before video.upload.success for videoId={} — compensating", videoId);
                    // Audit note (H-5): wrap the compensating transition in try/catch in case
                    // another scheduler node already advanced the state between our read and this TX.
                    // TerminalStateViolationException here means the state is already PROCESSING —
                    // safe to proceed to completeTranscoding().
                    try {
                        videoLifecycleService.transitionOperationalState(videoId, OperationalState.PROCESSING);
                    } catch (TerminalStateViolationException e) {
                        log.debug("Compensating UPLOADING→PROCESSING skipped — state already advanced for videoId={}", videoId);
                    }
                    // Audit fix (C-2): publish VideoUploadedEvent in the compensation path so
                    // Story 6.3 moderation pipeline fires even for out-of-order webhook delivery.
                    // Note: completeTranscoding() will publish VideoPublishedEvent shortly after —
                    // Story 6.3 handlers MUST NOT assume a time gap between these two events.
                    publisher.publishEvent(new VideoUploadedEvent(videoId, video.getOwnerId()));
                }
                videoService.completeTranscoding(videoId);
            }
            case "video.upload.failed" -> {
                // Bunny Status=8: TUS upload failed at the provider (distinct from Status=5 encoding failure).
                // Transition to FAILED and release the quota reservation immediately.
                videoLifecycleService.transitionOperationalState(videoId, OperationalState.FAILED);
                UploadSession session = uploadSessionRepository
                    .findFirstByVideoIdOrderByCreatedAtDesc(videoId).orElse(null);
                if (session != null && session.getReservationHandle() != null) {
                    quotaProvider.release(session.getReservationHandle());
                } else {
                    log.warn("No reservation handle found for videoId={} during upload failure — quota not released", videoId);
                }
            }
            case "video.encoding.failed" ->
                videoService.failTranscoding(videoId);
            default ->
                log.warn("Unknown webhook event type '{}', completing without state change", event.getEventType());
        }
    }
    ```
  - [x] **Do NOT add `handleEncodingSuccess()` rawPayload-parsing helper** — Duration/StorageSize are NOT in the Bunny webhook body (Status=3 payload is `{VideoLibraryId, VideoGuid, Status}` only); `completeTranscoding()` calls `videoProviderAdapter.getVideoMetadata()` to fetch them.
  - [x] Imports to add: `com.softropic.skillars.platform.video.service.VideoService`, `com.softropic.skillars.platform.video.contract.event.VideoUploadedEvent`, `org.springframework.context.ApplicationEventPublisher`, `com.softropic.skillars.platform.video.service.VideoLifecycleService.TerminalStateViolationException` (or whichever package it lives in — check `VideoLifecycleService.java`)

---

### Backend — API Layer

- [x] **Task 16: Add `POST /api/video/uploads/initiate` to `VideoResource`** (AC: 1)
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/api/VideoResource.java` (MODIFY)
  - [x] Replace the empty class with:
    ```java
    package com.softropic.skillars.platform.video.api;

    import com.softropic.skillars.infrastructure.security.SecurityConstants;
    import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
    import com.softropic.skillars.platform.security.service.SecurityUtil;
    import com.softropic.skillars.platform.video.contract.InitializeUploadRequest;
    import com.softropic.skillars.platform.video.contract.InitializeUploadResponse;
    import com.softropic.skillars.platform.video.contract.VideoUploadInitiateRequest;
    import com.softropic.skillars.platform.video.service.VideoService;
    import io.micrometer.observation.annotation.Observed;
    import jakarta.validation.Valid;
    import lombok.RequiredArgsConstructor;
    import org.springframework.http.HttpStatus;
    import org.springframework.http.ResponseEntity;
    import org.springframework.security.access.prepost.PreAuthorize;
    import org.springframework.web.bind.annotation.PostMapping;
    import org.springframework.web.bind.annotation.RequestBody;
    import org.springframework.web.bind.annotation.RequestMapping;
    import org.springframework.web.bind.annotation.RestController;

    import java.util.UUID;

    @Observed(name = "video.upload")
    @RestController
    @RequestMapping("/api/video")
    @RequiredArgsConstructor
    public class VideoResource {

        private final VideoService videoService;
        private final SecurityUtil securityUtil;
        private final CoachProfileService coachProfileService;

        private static final Set<VideoType> COACH_ALLOWED_VIDEO_TYPES =
            Set.of(VideoType.DRILL_DEMO, VideoType.COACH_REVIEW);

        @PostMapping("/uploads/initiate")
        @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
        @Observed(name = "video.upload.initiate")
        public ResponseEntity<InitializeUploadResponse> initiateUpload(
                @Valid @RequestBody VideoUploadInitiateRequest req) {
            // Coaches may only upload coach-scoped video types. HOMEWORK is a player type.
            if (!COACH_ALLOWED_VIDEO_TYPES.contains(req.videoType())) {
                throw new VideoValidationException(
                    "video.invalidTypeForRole",
                    "Coaches may only upload DRILL_DEMO or COACH_REVIEW videos");
            }
            Long coachUserId = securityUtil.getCurrentCoachUserId();
            UUID coachId = coachProfileService.getCoachIdByUserId(coachUserId);
            // Audit fix (M-2): a COACH-role user with no coach_profiles row (incomplete registration
            // or data inconsistency) returns null here; pass null ownerId into VideoService causes NPE
            // or a VARCHAR NULL that propagates into video_quotas.user_id (violates NOT NULL).
            if (coachId == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Coach profile not found — complete account registration before uploading");
            }
            InitializeUploadResponse resp = videoService.initializeUpload(
                new InitializeUploadRequest(
                    coachId.toString(), req.fileName(), req.fileSizeBytes(),
                    req.mimeType(), req.videoType()));
            return ResponseEntity.status(HttpStatus.CREATED).body(resp);
        }
    }
    ```
  - [x] Add import: `com.softropic.skillars.platform.video.contract.VideoType`, `com.softropic.skillars.platform.video.contract.exception.VideoValidationException`, `java.util.Set`, `org.springframework.web.server.ResponseStatusException`, `org.springframework.http.HttpStatus`
  - [x] **Verify `VideoValidationException` has a 2-arg `(String errorCode, String message)` constructor** — the guard throws `new VideoValidationException("video.invalidTypeForRole", "Coaches may only upload DRILL_DEMO or COACH_REVIEW videos")`. All existing call sites use the 1-arg form. If the 2-arg form does not exist, either add it (update `VideoApiAdvice` to surface the error code in the 422 response body) or collapse to the 1-arg form and omit the error code.
  - [x] `VideoApiAdvice` maps `VideoValidationException` → HTTP 422; the role-type guard uses the same exception type so no new advice handler is needed.
  - [x] `VideoApiAdvice` (global `@RestControllerAdvice`, no `assignableTypes` restriction) covers this resource automatically
  - [x] **Scope note**: This endpoint is **coach-only for the MVP**. The user story says "coach or eligible player" but the player upload path is out of scope for Story 6.2. A future story adds the player path with separate eligibility check and allowed types (`HOMEWORK`).

---

### Backend — DrillUploadService

- [x] **Task 17: Update `DrillUploadService` to pass `VideoType.DRILL_DEMO`** (AC: 7)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java` (MODIFY)
  - [x] Inside `initiateUpload()`, change the `InitializeUploadRequest` construction:
    ```java
    // BEFORE:
    new InitializeUploadRequest(coachId.toString(), req.fileName(), req.fileSizeBytes(), req.mimeType())
    // AFTER:
    new InitializeUploadRequest(coachId.toString(), req.fileName(), req.fileSizeBytes(), req.mimeType(), VideoType.DRILL_DEMO)
    ```
  - [x] `VideoType` import already exists in `DrillUploadService` (added in Story 6.1 via `VideoTypeConstraints.validate()`)

---

### Backend — Tests

- [x] **Task 18: Create `VideoUploadPipelineIT`** (AC: 5, 6)
  - [x] File: `src/test/java/com/softropic/skillars/platform/video/service/VideoUploadPipelineIT.java` (CREATE)
  - [x] Extends `BaseVideoIT`; uses `@MockitoBean VideoProviderAdapter videoProviderAdapter` and `@MockitoBean QuotaProvider quotaProvider`
  - [x] `@TestPropertySource`: disable background schedulers (same settings as `WebhookPipelineIT`)
  - [x] `@BeforeEach`: stub `videoProviderAdapter.getVideoMetadata(anyString())`:
    ```java
    when(videoProviderAdapter.getVideoMetadata(anyString()))
        .thenReturn(new VideoMetadata(120_000L, 52_428_800L));
    ```
  - [x] **Test 1: `encodingSuccess_setsMetadataCommitsQuotaTransitionsToReady`**
    - Seed: video in PROCESSING state with `providerAssetId = "asset-1"` + PENDING upload session with `reservationHandle = "test-handle-1"` + `video.encoding.success` webhook event in outbox (rawPayload = `{"VideoLibraryId":12345,"VideoGuid":"asset-1","Status":3}` — actual Bunny format; eventType already mapped to `"video.encoding.success"`)
    - Action: `scheduler.processPending()`
    - Assert: `video.operational_state = READY`
    - Assert: `video.duration_ms = 120000L`, `video.storage_bytes = 52428800L` (from mock)
    - Assert: `verify(quotaProvider).commit("test-handle-1")`
    - Assert: webhook event status = COMPLETED
  - [x] **Test 2: `encodingSuccess_providerMetadataCallFails_stillTransitionsToReady`**
    - Stub `videoProviderAdapter.getVideoMetadata(anyString())` to throw `VideoProviderException`
    - Assert: video still transitions to READY; `quotaProvider.commit()` still called; no exception propagated by scheduler
  - [x] **Test 3: `encodingFailed_releasesQuotaAndTransitionsToFailed`**
    - Seed: video in PROCESSING + upload session with `reservationHandle = "test-handle-2"` + `video.encoding.failed` webhook
    - Assert: `video.operational_state = FAILED`
    - Assert: `verify(quotaProvider).release("test-handle-2")`
  - [x] **Test 4: `encodingFailed_noSession_gracefullyTransitionsToFailed`**
    - Seed: video in PROCESSING + NO upload session + `video.encoding.failed` webhook
    - Assert: video = FAILED, no exception thrown (null-safe release path)
  - [x] **Test 5: `encodingSuccess_sessionHasNullReservationHandle_stillTransitionsToReady`**
    - Seed: video in PROCESSING with `providerAssetId = "asset-5"` + upload session with `reservationHandle = null` (simulates a partially-failed initiation where provider succeeded but session handle was never set) + `video.encoding.success` webhook
    - Assert: `video.operational_state = READY`
    - Assert: `verify(quotaProvider, never()).commit(any())` — no commit attempt on null handle
    - Assert: WARN log emitted ("No reservation handle found...")
    - Assert: webhook event = COMPLETED, no exception propagated

- [x] **Task 19: Update `VideoUploadInitializationIT` for 5-arg `InitializeUploadRequest` and new `UploadCredentials`** (AC: 7)
  - [x] File: `src/test/java/com/softropic/skillars/platform/video/service/VideoUploadInitializationIT.java` (MODIFY)
  - [x] Update `@MockitoBean VideoProviderAdapter` stub to return new 5-field `UploadCredentials`:
    ```java
    when(videoProviderAdapter.initializeUpload(anyString(), anyLong()))
        .thenReturn(new UploadCredentials(
            "test-asset-id", "https://video.bunnycdn.com/tusupload",
            "abc123def", 9999999999L, 12345L));
    ```
  - [x] Update mock for 3-arg reserve:
    ```java
    when(quotaProvider.reserve(anyString(), anyLong(), any()))
        .thenReturn("test-reservation-handle");
    ```
    Import: `static org.mockito.ArgumentMatchers.any`
  - [x] Update ALL `new InitializeUploadRequest(...)` calls — add `VideoType.DRILL_DEMO` as 5th arg
  - [x] Assert new TUS fields in response: `assertThat(resp.tusAuthorizationSignature()).isEqualTo("abc123def")`

- [x] **Task 20: Update `DrillUploadServiceTest` for 5-arg `InitializeUploadRequest`** (AC: 7)
  - [x] File: `src/test/java/com/softropic/skillars/platform/session/service/DrillUploadServiceTest.java` (MODIFY)
  - [x] Any `@Captor` or `verify` assertions checking the exact `InitializeUploadRequest` constructor — update to include `VideoType.DRILL_DEMO` as 5th arg

- [x] **Task 24: Create `VideoUploadResourceIT` — HTTP-layer integration test for `POST /api/video/uploads/initiate`** (AC: 1, 7)
  - [x] File: `src/test/java/com/softropic/skillars/platform/video/api/VideoUploadResourceIT.java` (CREATE)
  - [x] Use `@WebMvcTest(VideoResource.class)` with `@MockitoBean VideoService videoService`, `@MockitoBean CoachProfileService coachProfileService`, `@MockitoBean SecurityUtil securityUtil`. **Do NOT use `@SpringBootTest(webEnvironment = RANDOM_PORT)` with MockMvc** — these don't compose without `@AutoConfigureMockMvc` and a full context boot is unnecessary for a pure web-layer test.
  - [x] **Test 1: `initiateUpload_validCoachRequest_returns201WithTusFields`**
    - Authenticate as ROLE_COACH; POST `{"fileName":"clip.mp4","fileSizeBytes":10485760,"mimeType":"video/mp4","videoType":"COACH_REVIEW"}`
    - Stub `videoService.initializeUpload(...)` to return a fully-populated `InitializeUploadResponse`
    - Assert: HTTP 201; response body contains `videoId`, `sessionId`, `providerUploadId`, `signedUploadUrl`, `tusAuthorizationSignature`, `tusAuthorizationExpire`, `tusLibraryId`
  - [x] **Test 2: `initiateUpload_missingVideoType_returns400`**
    - POST without `videoType` field
    - Assert: HTTP 400 (Bean Validation on `@NotNull VideoType videoType`)
  - [x] **Test 3: `initiateUpload_homeworkTypeAsCoach_returns422`**
    - POST with `"videoType":"HOMEWORK"` (player type)
    - Assert: HTTP 422 (role-type guard in `VideoResource`)
  - [x] **Test 4: `initiateUpload_unauthenticated_returns401`**
    - POST without auth header
    - Assert: HTTP 401
  - [x] **Test 5: `initiateUpload_playerRole_returns403`**
    - Authenticate as ROLE_PLAYER
    - Assert: HTTP 403 (`@PreAuthorize(HAS_COACH_ROLE)`)
  - [x] **Test 6: `initiateUpload_fileSizeBytesZero_returns400`** (audit M-5)
    - POST `{"fileName":"clip.mp4","fileSizeBytes":0,"mimeType":"video/mp4","videoType":"COACH_REVIEW"}`
    - Assert: HTTP 400 (Bean Validation `@Min(1)` on `fileSizeBytes`)
  - [x] **Test 7: `initiateUpload_invalidVideoTypeString_returns400`** (audit M-5)
    - POST with `"videoType":"NOT_A_TYPE"` (unknown enum value)
    - Assert: HTTP 400 (Jackson deserialization failure before Spring validation runs)
  - [x] **Test 8: `initiateUpload_invalidMimeType_returns400`** (audit M-2, M-5)
    - POST with `"mimeType":"application/pdf"`
    - Assert: HTTP 400 (Bean Validation `@Pattern` on `mimeType`)
  - [x] **Test 9: `initiateUpload_fileSizeExceedsTypeLimit_returns422`** (audit M-5)
    - POST `{"fileName":"big.mp4","fileSizeBytes":2147483648,"mimeType":"video/mp4","videoType":"COACH_REVIEW"}` (2 GB > 1 GB limit)
    - Stub `videoService.initializeUpload(...)` to throw `VideoValidationException` (simulating `videoTypeConstraints.validate()` rejection)
    - Assert: HTTP 422

- [x] **Task 25: Update `BunnyVideoProviderAdapterTest` for constructor changes, new webhook payload, and corrected status mapping** — CRITICAL (AC: 3, 5)
  - [x] File: `src/test/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapterTest.java` (MODIFY)
  - [x] **Fix 1 — Constructor arity**: `setUp()` constructs the adapter with 6 args. After Tasks 12 and 23, the constructor has 8. Update `setUp()`:
    ```java
    private static final String WEBHOOK_SIGNING_SECRET = "test-webhook-secret";
    private static final long SESSION_TTL_SECONDS = 3600L;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wireMock) {
        adapter = new BunnyVideoProviderAdapter(
            new RestTemplate(),
            API_KEY,
            LIBRARY_ID,
            CDN_HOSTNAME,
            wireMock.getHttpBaseUrl(),
            new ObjectMapper(),
            SESSION_TTL_SECONDS,
            WEBHOOK_SIGNING_SECRET
        );
    }
    ```
  - [x] **Fix 2 — `verifyWebhook` test payload**: The test sends `{"EventType":"...","VideoGuid":"...","Timestamp":...}` which no longer matches `BunnyWebhookPayload`. Replace with actual Bunny format. Recompute HMAC using `WEBHOOK_SIGNING_SECRET` (not `API_KEY`):
    ```java
    @Test
    void verifyWebhook_validHmac_returnsWebhookEvent() throws Exception {
        String payload = "{\"VideoLibraryId\":12345,\"VideoGuid\":\"abc-guid\",\"Status\":7}";
        String signature = computeHmac(WEBHOOK_SIGNING_SECRET, payload);

        WebhookEvent event = adapter.verifyWebhook(payload, signature);

        assertThat(event.eventType()).isEqualTo("video.upload.success");
        assertThat(event.providerAssetId()).isEqualTo("abc-guid");
        // Instant.now() is used as timestamp — just assert it's recent
        assertThat(event.timestamp()).isAfter(Instant.now().minusSeconds(5));
    }

    @Test
    void verifyWebhook_invalidHmac_throwsVideoProviderException() {
        String payload = "{\"VideoLibraryId\":12345,\"VideoGuid\":\"abc-guid\",\"Status\":3}";
        assertThatThrownBy(() -> adapter.verifyWebhook(payload, "0".repeat(64)))
            .isInstanceOf(VideoProviderException.class);
    }

    @Test
    void verifyWebhook_malformedSignatureHeader_throwsVideoProviderException() {
        String payload = "{\"VideoLibraryId\":12345,\"VideoGuid\":\"abc-guid\",\"Status\":3}";
        assertThatThrownBy(() -> adapter.verifyWebhook(payload, "not-hex!!!"))
            .isInstanceOf(VideoProviderException.class);
    }
    ```
  - [x] **Fix 3 — `getAssetStatus_mapsStatusCorrectly` parametrized test**: Three CSV rows contradict Task 12 Sub-task D's corrected `mapBunnyStatus()`. Update:
    ```java
    // BEFORE (wrong assertions after Sub-task D fix):
    "3, PROCESSING",
    "6, FAILED",
    "8, READY",
    // AFTER:
    "3, READY",
    "6, UPLOADING",
    "8, PROCESSING",
    ```
    Full corrected `@CsvSource`:
    ```
    "0, UPLOADING", "1, PROCESSING", "2, PROCESSING", "3, READY", "4, READY",
    "5, FAILED", "6, UPLOADING", "7, PROCESSING", "8, FAILED", "99, PROCESSING"
    ```
    Note: `"8, PROCESSING"` in the prior draft was wrong — Status=8 is "Pre-signed upload failed" per Bunny docs, must map to FAILED.
  - [x] **Fix 4 — `initializeUpload_success` assertions**: After Sub-task B, `UploadCredentials` now has 5 fields. The existing test only asserts `providerUploadId` and `signedUploadUrl`. Add assertions for the TUS fields (check they are non-null and non-empty):
    ```java
    assertThat(credentials.tusAuthorizationSignature()).isNotBlank();
    assertThat(credentials.tusAuthorizationExpire()).isGreaterThan(Instant.now().getEpochSecond());
    assertThat(credentials.tusLibraryId()).isEqualTo(Long.parseLong(LIBRARY_ID));
    ```
  - [x] **Add test for `getVideoMetadata()` happy path and 404 handling** (Sub-task C):
    ```java
    @Test
    void getVideoMetadata_success() {
        stubFor(get(urlEqualTo("/library/123/videos/vid-001"))
            .willReturn(okJson("{\"guid\":\"vid-001\",\"status\":3,\"length\":120,\"storageSize\":52428800}")));

        VideoMetadata meta = adapter.getVideoMetadata("vid-001");

        assertThat(meta.durationMs()).isEqualTo(120_000L);
        assertThat(meta.storageBytes()).isEqualTo(52_428_800L);
    }

    @Test
    void getVideoMetadata_notFound_throwsVideoProviderException() {
        stubFor(get(urlEqualTo("/library/123/videos/missing"))
            .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> adapter.getVideoMetadata("missing"))
            .isInstanceOf(VideoProviderException.class);
    }
    ```
  - [x] **Add tests for Status=8 and informational statuses** (audit M-3):
    ```java
    @Test
    void verifyWebhook_status8_returnsUploadFailedEvent() throws Exception {
        String payload = "{\"VideoLibraryId\":12345,\"VideoGuid\":\"abc-guid\",\"Status\":8}";
        String signature = computeHmac(WEBHOOK_SIGNING_SECRET, payload);
        WebhookEvent event = adapter.verifyWebhook(payload, signature);
        assertThat(event.eventType()).isEqualTo("video.upload.failed");
        assertThat(event.providerAssetId()).isEqualTo("abc-guid");
    }

    @ParameterizedTest
    @CsvSource({"9, video.status.9", "10, video.status.10"})
    void verifyWebhook_informationalStatuses_returnStatusEventAndNoException(int status, String expectedType)
            throws Exception {
        String payload = "{\"VideoLibraryId\":12345,\"VideoGuid\":\"abc-guid\",\"Status\":" + status + "}";
        String signature = computeHmac(WEBHOOK_SIGNING_SECRET, payload);
        WebhookEvent event = adapter.verifyWebhook(payload, signature);
        assertThat(event.eventType()).isEqualTo(expectedType);
        // Scheduler's default branch logs and no-ops — verified in WebhookEventProcessorScheduler tests
    }
    ```
  - [x] Import to add: `com.softropic.skillars.infrastructure.video.VideoMetadata`

---

### Backend — New Tasks from Audit

- [x] **Task 26: Create `VideoUploadedEvent` and publish from `video.upload.success` handler** (AC: 4) — CRITICAL (audit C-2)
  - [x] **Context**: The epics spec (Story 6.2, line 2054–2055) requires a `VideoUploadedEvent` to be published when the video transitions `UPLOADING → PROCESSING`. Story 6.3 (Content Moderation Pipeline) listens for this event to start CSAM scanning. Without this event, the entire moderation pipeline is dead on arrival — no compile error will surface the missing dependency.
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/contract/event/VideoUploadedEvent.java` (CREATE)
    ```java
    package com.softropic.skillars.platform.video.contract.event;

    import java.util.UUID;

    public record VideoUploadedEvent(UUID videoId, String ownerId) {}
    ```
  - [x] **Verify `video.getOwnerId()` returns `String`** (audit M-6): `WebhookEventProcessorScheduler` calls `video.getOwnerId()` to populate `ownerId`. The `VideoUploadedEvent` record takes `String ownerId`. Confirm the `Video` entity field type — if it returns `UUID`, this is a compile error. Story 6.1 dev notes confirm `video.owner_id` is `VARCHAR NOT NULL` in V15 but the entity field type must be verified.
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/service/WebhookEventProcessorScheduler.java` (MODIFY — already in Task 15)
    - `publisher.publishEvent(new VideoUploadedEvent(videoId, video.getOwnerId()))` must be called AFTER `videoLifecycleService.transitionOperationalState(videoId, OperationalState.PROCESSING)` in the `"video.upload.success"` branch of `dispatchEvent()` — see Task 15 updated snippet.
    - Use `@TransactionalEventListener` (not `@EventListener`) in Story 6.3 handlers to ensure they run after the state transition commits. Story 6.3 must document its contract: it may see the video in `PROCESSING` state, NOT `UPLOADING`.
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/service/WebhookEventProcessorScheduler.java` (MODIFY)
    - Add `ApplicationEventPublisher publisher` constructor field (alongside the `VideoService videoService` field added in Task 15).
  - [x] **Add to `VideoUploadPipelineIT`** (Task 18): Verify `VideoUploadedEvent` is published when a `video.upload.success` event is processed by the scheduler.
    - Seed: video in UPLOADING + `video.upload.success` webhook event
    - Action: `scheduler.processPending()`
    - Assert: `video.operational_state = PROCESSING`
    - Assert: `VideoUploadedEvent` was published (use `@RecordApplicationEvents` or a test `@EventListener`)

- [x] **Task 27: Create `VideoWebhookResourceIT`** — HTTP-layer integration test for `POST /api/video/webhooks/bunny` (audit H-4) — CRITICAL
  - [x] File: `src/test/java/com/softropic/skillars/platform/video/api/VideoWebhookResourceIT.java` (CREATE)
  - [x] Use `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureMockMvc`. `VideoWebhookResource` needs the full Spring context for `@Transactional`, `DataIntegrityViolationException` catch, and the outbox repository.
  - [x] **Add `@TestPropertySource` to disable background schedulers** (audit M-1): without this, `WebhookEventProcessorScheduler`, `QuotaReservationTimeoutService`, and `BandwidthResetService` all start. The scheduler's batch-drain loop can consume webhook events seeded by tests before assertions run. Use the same scheduler-disable properties as `WebhookPipelineIT` (the reference implementation).
  - [x] `@MockitoBean BunnyVideoProviderAdapter` is NOT appropriate — `VideoWebhookResource` calls `videoProviderAdapter.verifyWebhook()` which must do real HMAC verification. Use a real adapter instance wired with a test signing secret, or stub only the JSON parsing via `@MockitoBean VideoProviderAdapter` with pre-canned `WebhookEvent` responses.
  - [x] **Alternatively**: construct a valid HMAC in the test using the same signing secret configured in `application-test.yml` for `app.video.bunny.webhook-signing-secret`.
  - [x] **Test 1: `validWebhook_encodingSuccess_returns200AndEnqueuesEvent`**
    - POST a valid Status=3 body with correct HMAC-SHA256 signature
    - Assert: HTTP 200
    - Assert: one row in `video_webhook_events` with `event_type = "video.encoding.success"`
    - Note: no video record is seeded — the webhook layer only enqueues the event; video lookup happens in the scheduler (which is disabled). Do NOT add a state-transition assertion here; that belongs in `VideoUploadPipelineIT`.
  - [x] **Test 2: `invalidSignature_returns400AndDiscardsEvent`**
    - POST with wrong HMAC signature
    - Assert: HTTP 400; zero rows added to `video_webhook_events`
  - [x] **Test 3: `malformedSignatureHeader_returns400`**
    - POST with `X-BunnyStream-Signature: not-hex!!!`
    - Assert: HTTP 400 (HexFormat parse guard from Task 12 Sub-task A)
  - [x] **Test 4: `duplicateDelivery_returns200Idempotently`** — tests Task 9 Fix 2 and Fix 3
    - POST the same valid webhook body twice in sequence (same providerAssetId + eventType)
    - Assert: both return HTTP 200
    - Assert: exactly one row in `video_webhook_events` (UNIQUE constraint absorbed duplicate)
  - [x] **Test 5: `concurrentDuplicateDelivery_bothReturn200`** — tests Task 9 Fix 3 race condition
    - POST the same webhook from two threads simultaneously using `ExecutorService` with 2 threads + `CountDownLatch`
    - Assert: both responses are HTTP 200 (DataIntegrityViolationException converted to 200, not 500)
    - Assert: exactly one row in `video_webhook_events`
  - [x] Import helpers: `javax.crypto.Mac`, `javax.crypto.spec.SecretKeySpec`, `java.util.HexFormat` for computing test HMAC signatures

- [x] **Task 28: Add startup assertion for `reservationTimeoutMinutes ≥ sessionTtlMinutes`** (audit M-6)
  - [x] **Context**: If platform config `platform.video_reservation_timeout_minutes` is set lower than `VideoProperties.upload.sessionTtlMinutes`, the quota reaper marks the reservation RELEASED before the TUS upload completes. When encoding finishes, `quotaProvider.commit()` finds no ACTIVE reservation and is a silent no-op — the video reaches READY but `storage_used_bytes` is never incremented, giving the user free storage with no error.
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/config/VideoConfig.java` (MODIFY)
    ```java
    // Bunny's documented minimum TUS AuthorizationExpire is 3600 seconds (1 hour).
    // If sessionTtlMinutes < 60, all TUS uploads fail with 401 from Bunny — no other error surfaced.
    private static final long BUNNY_MIN_TUS_EXPIRY_MINUTES = 60L;

    @PostConstruct
    void validateTimeoutAlignment() {
        long reservationTimeoutMinutes = quotaConfigService.getReservationTimeoutMinutes();
        long sessionTtlMinutes = videoProperties.getUpload().getSessionTtlMinutes();

        // Check 1: Bunny's documented minimum (audit H-2)
        if (sessionTtlMinutes < BUNNY_MIN_TUS_EXPIRY_MINUTES) {
            throw new IllegalStateException(String.format(
                "app.video.upload.session-ttl-minutes (%d) is below Bunny.net's documented minimum " +
                "of %d minutes for TUS AuthorizationExpire. All TUS uploads will receive 401.",
                sessionTtlMinutes, BUNNY_MIN_TUS_EXPIRY_MINUTES));
        }

        // Check 2: quota reservation must outlive the TUS credential window
        if (reservationTimeoutMinutes < sessionTtlMinutes) {
            throw new IllegalStateException(String.format(
                "platform.video_reservation_timeout_minutes (%d) must be ≥ " +
                "app.video.upload.session-ttl-minutes (%d). " +
                "If the reaper fires before the TUS upload completes, quota commits silently no-op.",
                reservationTimeoutMinutes, sessionTtlMinutes));
        }
        log.info("Quota timeout alignment validated: bunnyMin={}min, sessionTtl={}min, reservationTimeout={}min",
                 BUNNY_MIN_TUS_EXPIRY_MINUTES, sessionTtlMinutes, reservationTimeoutMinutes);
    }
    ```
  - [x] `VideoConfig` already injects `VideoProperties` (for `quotaProviderValidator`). Add `QuotaConfigService quotaConfigService` as a constructor field; import from `com.softropic.skillars.platform.video.service.QuotaConfigService`.
  - [x] **Test**: Add a test to `VideoUploadInitializationIT` (or a new `VideoConfigValidationTest`) that configures `reservationTimeoutMinutes = 30` and `sessionTtlMinutes = 60` and asserts that the Spring context fails to start with `IllegalStateException`.

---

### Frontend

- [x] **Task 21: Create `video.api.js`** (AC: 8)
  - [x] File: `src/frontend/src/api/video.api.js` (CREATE)
  - [x] Follows `session.api.js` pattern. `tus-js-client` requires four Bunny.net-specific headers and uses `title` (not `filename`) in TUS Upload-Metadata:
    ```js
    import { api } from 'src/boot/axios'
    import * as tus from 'tus-js-client'

    export const videoApi = {
      // signal is an AbortSignal from AbortController — passed by video.store.js for cancel-during-initiation support
      initiateUpload(payload, signal) {
        return api.post('/api/video/uploads/initiate', payload, { signal })
      },

      createTusUpload({
        file,
        signedUploadUrl,
        providerUploadId,
        tusAuthorizationSignature,
        tusAuthorizationExpire,
        tusLibraryId,
        onProgress,
        onSuccess,
        onError,
      }) {
        return new tus.Upload(file, {
          endpoint: signedUploadUrl,
          retryDelays: [1000, 3000, 5000, 10000, 20000],  // no immediate retry — avoids hammering Bunny on 429/503
          // Bunny.net requires all four headers for TUS auth — computed server-side
          headers: {
            AuthorizationSignature: tusAuthorizationSignature,
            AuthorizationExpire: String(tusAuthorizationExpire),
            LibraryId: String(tusLibraryId),
            VideoId: providerUploadId,
          },
          // Bunny.net requires 'title' and 'filetype' in Upload-Metadata
          metadata: {
            title: file.name,
            filetype: file.type,
          },
          onProgress(bytesUploaded, bytesTotal) {
            onProgress?.(bytesUploaded, bytesTotal)
          },
          onSuccess,
          onError,
        })
      },
    }
    ```

- [x] **Task 22: Create `video.store.js`** (AC: 8)
  - [x] File: `src/frontend/src/stores/video.store.js` (CREATE)
  - [x] Pattern: composable Pinia store (function style, matching `booking.store.js`)
    ```js
    import { defineStore } from 'pinia'
    import { ref } from 'vue'
    import { videoApi } from 'src/api/video.api'

    export const useVideoStore = defineStore('video', () => {
      const uploadProgress = ref(0)
      const uploadState = ref('idle') // 'idle' | 'initiating' | 'uploading' | 'processing' | 'error'
      const currentVideoId = ref(null)
      const currentUpload = ref(null)
      const currentAbortController = ref(null)  // cancels the axios POST during 'initiating' state

      async function initiateAndUpload({ file, videoType, onProgress, onSuccess, onError }) {
        // Guard against concurrent uploads — a second call while uploading orphans the first TUS session
        if (uploadState.value !== 'idle') {
          onError?.(new Error('An upload is already in progress — cancel the current upload before starting a new one'))
          return
        }
        try {
          uploadState.value = 'initiating'
          uploadProgress.value = 0
          currentVideoId.value = null

          const controller = new AbortController()
          currentAbortController.value = controller

          const { data } = await videoApi.initiateUpload({
            fileName: file.name,
            fileSizeBytes: file.size,
            mimeType: file.type,
            videoType,
          }, controller.signal)

          // Guard: cancelUpload() was called while we were awaiting the initiate POST.
          // The store is already reset to 'idle'; bail without starting a TUS upload.
          if (uploadState.value === 'idle') return

          currentVideoId.value = data.videoId
          uploadState.value = 'uploading'
          // Audit note (M-4): clear the AbortController after initiation resolves — its signal
          // is settled and reusing it in cancelUpload() during 'uploading' state is misleading.
          currentAbortController.value = null

          const upload = videoApi.createTusUpload({
            file,
            signedUploadUrl: data.signedUploadUrl,
            providerUploadId: data.providerUploadId,
            tusAuthorizationSignature: data.tusAuthorizationSignature,
            tusAuthorizationExpire: data.tusAuthorizationExpire,
            tusLibraryId: data.tusLibraryId,
            onProgress(bytesUploaded, bytesTotal) {
              uploadProgress.value = bytesTotal > 0
                ? Math.round((bytesUploaded / bytesTotal) * 100)
                : 0
              onProgress?.(uploadProgress.value)
            },
            onSuccess() {
              uploadState.value = 'processing'
              onSuccess?.(currentVideoId.value)
            },
            onError(err) {
              uploadState.value = 'error'
              onError?.(err)
            },
          })

          currentUpload.value = upload
          upload.start()
        } catch (err) {
          // Audit fix (H-3): if cancelUpload() aborted the axios POST, it already reset
          // uploadState to 'idle'. The AbortError rejection reaches this catch block AFTER
          // that reset (JS microtask ordering). Guard here to avoid overwriting 'idle' → 'error'.
          if (uploadState.value === 'idle') return
          uploadState.value = 'error'
          onError?.(err)
        }
      }

      async function cancelUpload() {
        // Cancel the in-flight axios POST if we are still in 'initiating' state.
        // Without this, the POST resolves after state resets and starts an untracked TUS upload.
        if (currentAbortController.value) {
          currentAbortController.value.abort()
          currentAbortController.value = null
        }
        if (currentUpload.value) {
          // Pass true to send a DELETE to Bunny's TUS endpoint and terminate the upload
          // server-side. Without this, chunks may continue in-flight after state resets.
          // abort(true) may fail if the network is gone — swallow but log.
          await currentUpload.value.abort(true).catch((err) => {
            console.warn('TUS abort failed (network may be unavailable):', err)
          })
        }
        uploadState.value = 'idle'
        uploadProgress.value = 0
        currentVideoId.value = null
        currentUpload.value = null
      }

      // Audit note (H-1): after encoding begins, uploadState stays 'processing' indefinitely —
      // there is no server-push in Story 6.2 to signal when Bunny encoding completes.
      // A coach who wants to upload a second video cannot call initiateAndUpload() while
      // uploadState !== 'idle'. They must call resetUpload() (or reload the page) first.
      // resetUpload() is also the correct recovery path after 'error' state.
      async function resetUpload() {
        if (currentAbortController.value) {
          currentAbortController.value.abort()
          currentAbortController.value = null
        }
        if (currentUpload.value) {
          await currentUpload.value.abort(true).catch((err) => {
            console.warn('TUS abort on reset failed (network may be unavailable):', err)
          })
        }
        uploadState.value = 'idle'
        uploadProgress.value = 0
        currentVideoId.value = null
        currentUpload.value = null
      }

      return {
        uploadProgress,
        uploadState,
        currentVideoId,
        initiateAndUpload,
        cancelUpload,
        resetUpload,   // use for: error recovery, post-encoding upload-again, explicit reset
        // currentAbortController is internal — do not expose
      }
    })
    // Note: page-reload TUS resume (tus-js-client's findPreviousUploads() / localStorage fingerprint)
    // is out of scope for Story 6.2. On page reload, the store resets to 'idle' and the user must
    // re-initiate. The server-side session remains PENDING until the reservation timeout reaper
    // expires it. Resume-from-reload is a Story 6.x enhancement.
    ```

---

## Dev Notes

### Bunny.net API Alignment — CRITICAL

All Bunny.net integration details in this section take precedence over the epics description.

#### Webhook Payload Format
Bunny.net sends a fixed three-field JSON body for all video status changes:
```json
{"VideoLibraryId": 133, "VideoGuid": "guid-string", "Status": 3}
```
There is no `EventType` string, no `Timestamp`. `BunnyVideoProviderAdapter.BunnyWebhookPayload` must parse `VideoLibraryId`, `VideoGuid`, and `Status` (int).

The numeric `Status` is mapped to internal event strings inside `verifyWebhook()`:

| Bunny Status | Meaning | Internal event string |
|---|---|---|
| 0 | Queued for encoding | `"video.status.0"` (ignored) |
| 1 | Processing preview and format details | `"video.status.1"` (ignored) |
| 2 | Encoding in progress | `"video.status.2"` (ignored) |
| 3 | Finished encoding | `"video.encoding.success"` |
| 4 | Resolution finished; video now playable | `"video.status.4"` (ignored — 3 already set READY) |
| 5 | Encoding failed | `"video.encoding.failed"` |
| 6 | Pre-signed upload initiated | `"video.status.6"` (ignored) |
| 7 | Pre-signed upload completed | `"video.upload.success"` |
| **8** | **Pre-signed upload FAILED** | **`"video.upload.failed"`** — transitions UPLOADING→FAILED, releases quota |
| 9 | Automatic captions generated | `"video.status.9"` (ignored) |
| 10 | Auto title/description generated | `"video.status.10"` (ignored) |
| other | unknown future status | `"video.status.N"` (ignored) |

**Important**: Status=8 was previously misidentified as "TitleUpdating" in this story. Per Bunny docs it is "Pre-signed upload failed" — a TUS upload failure distinct from encoding failure (Status=5). It requires a FAILED state transition and quota release.

The `WebhookEventProcessorScheduler.dispatchEvent()` switch only reacts to the three internal strings; unknown strings are logged and no-oped.

#### Webhook Signature Verification
- Header: `X-BunnyStream-Signature` (64-char lowercase hex) — NOT `BunnyCDN-Signature`
- Algorithm: HMAC-SHA256(rawBody, readOnlyApiKey) → lowercase hex
- Key: the library's **Read-Only API key** — this is a **separate** key from the full API key used for REST calls. Task 23 adds `app.video.bunny.webhook-signing-secret` as a distinct config property and wires it exclusively into `verifyWebhook()`. The full `app.video.bunny.api-key` is never used for webhook verification. See Task 23 and the "Bunny.net API Keys — Two Distinct Keys Required" dev note below.
- Verification: constant-time byte comparison via `MessageDigest.isEqual()` — already implemented correctly in `BunnyVideoProviderAdapter.verifyWebhook()`

#### TUS Upload Credentials
Bunny.net TUS endpoint: `https://video.bunnycdn.com/tusupload` (hardcoded constant — not derived from `apiBaseUrl`).

Four HTTP headers required by `tus-js-client`:
1. `AuthorizationSignature` — hex SHA-256(`libraryId + apiKey + expireEpoch + videoGuid`). **Plain SHA-256, NOT HMAC-SHA256**.
2. `AuthorizationExpire` — Unix epoch seconds, minimum 3600s from now
3. `LibraryId` — numeric library ID (same value used in API paths)
4. `VideoId` — the video GUID returned from `POST /library/{libraryId}/videos`

These are computed server-side in `BunnyVideoProviderAdapter.initializeUpload()` and returned to the client via `InitializeUploadResponse`. The `apiKey` never reaches the client.

#### TUS Upload-Metadata
Bunny.net requires: `title` (video title, required) and `filetype` (MIME type, required). The field is `title`, NOT `filename`. Using `filename` causes Bunny to reject the upload.

#### Duration and Storage After Encoding
Bunny Status=3 (Finished) webhook body: `{VideoLibraryId, VideoGuid, Status}` only. No `Duration`, `StorageSize`, or any other metadata field is present in the webhook body. To populate `videos.duration_ms` and `videos.storage_bytes`, call `GET /library/{libraryId}/videos/{videoGuid}` after receiving Status=3. The `VideoModel` response includes `length` (int, seconds — convert × 1000 for ms) and `storageSize` (long, bytes). This call is made from `VideoService.completeTranscoding()` via `videoProviderAdapter.getVideoMetadata()`, outside the main `@Transactional` boundary but inside a try/catch (non-fatal if the call fails).

### State Name Alignment — CRITICAL

The epics use idealized state names. The actual `OperationalState` enum and Bunny.net trigger:

| Epic term | Actual `OperationalState` | Bunny Status | Internal event string |
|---|---|---|---|
| PENDING | `UPLOADING` | — (initial state on video create) | — |
| UPLOADED | `PROCESSING` | 7 (PresignedUploadFinished) | `"video.upload.success"` |
| PUBLISHED | `READY` | 3 (Finished) | `"video.encoding.success"` |
| (failure) | `FAILED` | 5 (Failed) | `"video.encoding.failed"` |

**DO NOT rename or add states** — existing tests hardcode `"UPLOADING"`, `"PROCESSING"`, `"READY"`.

### QuotaProvider Interface — Default Method Pattern

The 3-arg `reserve()` is a `default` method on the interface (delegates to 2-arg). This maintains backward compatibility:
- `NoOpQuotaProvider` (test tree) inherits the default — no change needed
- `QuotaService` overrides both: 2-arg delegates to 3-arg with `null` videoType
- Mockito mocks do NOT inherit default interface methods — any test that stubs `quotaProvider.reserve()` with the 2-arg signature will NOT intercept a 3-arg call. See Task 19 for required test updates.

### WebhookEventProcessorScheduler — `VideoService` Added

`WebhookEventProcessorScheduler` gains a `VideoService videoService` constructor field (via `@RequiredArgsConstructor`). No `ObjectMapper` field exists in the current class and none is being removed — the story does not change what fields are removed, only what is added.

No circular dependency: `VideoService` depends on `VideoProviderAdapter`, `QuotaProvider`, etc. — NOT on `WebhookEventProcessorScheduler`. Spring wires correctly.

### `completeTranscoding()` Transaction Semantics — Two-Phase Design

`VideoService.completeTranscoding(UUID videoId)` is NOT `@Transactional` at the method level. It uses `transactionTemplate` (already a field) to split work into two short transactions with the Bunny HTTP call between them. This avoids holding a DB connection from the pool during a 30-second RestTemplate read timeout.

**Phase 1 — Read (short TX via `transactionTemplate.execute()`):**
- Load `video.providerAssetId`. Transaction commits immediately, releasing the connection.

**Phase 2 — HTTP fetch (no transaction):**
- Call `videoProviderAdapter.getVideoMetadata(providerAssetId)`. No DB connection held.
- If the call fails, `meta` is `null` — video still advances to READY (non-fatal; reconciliation can back-fill metadata later).

**Phase 3 — Write (main TX via `transactionTemplate.execute()`):**
1. Load video, set `durationMs` and `storageBytes` from `meta` if non-null (no explicit `save()` needed — JPA dirty-checking flushes on TX commit).
2. Call `videoLifecycleService.transitionOperationalState(videoId, READY)` — joins the TX via REQUIRED propagation.
3. Call `quotaProvider.commit(session.getReservationHandle())` — joins the TX.
4. Call `publisher.publishEvent(new VideoPublishedEvent(...))` — inside the TX, so `@TransactionalEventListener(AFTER_COMMIT)` handlers in Story 6.3 fire after this TX commits, not before.

When `WebhookEventProcessorScheduler.processPending()` calls `videoService.completeTranscoding()`:
- `processPending()` is NOT `@Transactional` — each `transactionTemplate.execute()` inside `completeTranscoding()` creates its own TX.
- The scheduler's subsequent "mark webhook COMPLETED" transaction is a separate TX — this is the existing pattern and continues unchanged.

### `VideoPublishedEvent` — Idempotency Contract for Story 6.3

`VideoPublishedEvent` is published inside Phase 3 of `completeTranscoding()`. If the same Bunny Status=3 webhook is delivered twice (e.g., network retry arriving after the eventId fix deduplicates it), the second delivery is suppressed by the UNIQUE constraint on `video_webhook_events.event_id`. However, if two deliveries arrive with identical `providerAssetId + eventType` eventIds, the second is an `existsByEventId` no-op. The publisher fires exactly once per successful Phase 3 commit.

**Story 6.3 handlers for `VideoPublishedEvent` MUST be idempotent** — `transitionOperationalState(READY)` is already idempotent (READY→READY no-op), and `quotaProvider.commit()` is already idempotent (CTE `WHERE status='ACTIVE'`). Handlers added in 6.3 must follow the same pattern (check before acting, or use an upsert).

### Out-of-Order Bunny Webhook Delivery — UPLOADING State at Status=3

Bunny does not guarantee delivery order. If `Status=3` (encoding.success) arrives before `Status=7` (upload.success), the video is still in `UPLOADING` state. `completeTranscoding()` calls `videoLifecycleService.transitionOperationalState(videoId, READY)` and `VALID_TRANSITIONS` has no UPLOADING→READY edge — this throws `TerminalStateViolationException`. The scheduler catches it, retries up to `maxAttempts=3`, and dead-letters the event. The video is stuck in `UPLOADING` with no automated recovery.

**Known failure scenario**: Status=3 arrives and retries exhaust before Status=7 is delivered. Video remains in `UPLOADING` permanently; quota is never committed; `VideoPublishedEvent` never fires.

**Mitigation in this story**: In `dispatchEvent()`, when the event type is `video.encoding.success` and the video is in `UPLOADING` state, compensate for the missing Status=7 transition before attempting PROCESSING→READY. Also publish `VideoUploadedEvent` in the compensation branch so Story 6.3 moderation fires (audit fix C-2):

```java
case "video.encoding.success" -> {
    if (video.getOperationalState() == OperationalState.UPLOADING) {
        // Compensate for missing Status=7 delivery — advance through PROCESSING first
        log.warn("video.encoding.success arrived before video.upload.success for videoId={}, compensating", videoId);
        try {
            videoLifecycleService.transitionOperationalState(videoId, OperationalState.PROCESSING);
        } catch (TerminalStateViolationException e) {
            log.debug("Compensating transition skipped — already advanced for videoId={}", videoId);
        }
        // Publish VideoUploadedEvent so moderation pipeline runs even in out-of-order path.
        // VideoPublishedEvent fires moments later via completeTranscoding() — Story 6.3 handlers
        // MUST NOT assume a time gap between these two events in the out-of-order case.
        publisher.publishEvent(new VideoUploadedEvent(videoId, video.getOwnerId()));
    }
    videoService.completeTranscoding(videoId);
}
```

`transitionOperationalState()` is idempotent (PROCESSING→PROCESSING is a no-op), so if Status=7 later arrives it simply no-ops the UPLOADING→PROCESSING step. Add this compensation inside `WebhookEventProcessorScheduler.dispatchEvent()` as part of **Task 15**.

### Quota Reservation Timeout vs. Session TTL Alignment

Three separate timeouts gate the upload lifecycle:
- **Quota reservation expiry**: `quotaConfigService.getReservationTimeoutMinutes()` — a platform DB config setting, mutated at runtime.
- **Upload session TTL**: `VideoProperties.upload.sessionTtlMinutes` — YAML config, requires redeploy.
- **TUS credential expiry**: `sessionTtlMinutes × 60` seconds from initiation.

**Failure mode if `reservationTimeoutMinutes < sessionTtlMinutes`**: The quota reaper marks the reservation `RELEASED` before the TUS upload completes. Bunny still accepts the upload (TUS credentials remain valid). When Status=3 fires, `completeTranscoding()` calls `quotaProvider.commit(handle)` — the CTE finds 0 rows matching `status = 'ACTIVE'` and no-ops silently. The video reaches `READY` but `storage_used_bytes` is **never incremented**. The user receives free storage with no error surfaced anywhere.

**Required invariant**: `reservationTimeoutMinutes ≥ sessionTtlMinutes`. Verify both values before every deployment. For the recommended 90-minute `sessionTtlMinutes`, ensure `reservationTimeoutMinutes` is set to at least 90 in the platform config. Consider adding a startup `@PostConstruct` assertion in `VideoConfig` or `QuotaService` that compares the two values and fails fast.

### "Video Not Found" Webhook Events Are Silently Completed

In `WebhookEventProcessorScheduler.dispatchEvent()`, if no video is found for a `providerAssetId`, the method logs WARN and returns. The caller marks the event `COMPLETED`. This means:

- The event is not retried — once the scheduler marks it COMPLETED, it is gone.
- **Failure scenario**: `initializeUpload()` succeeds at Step 6 (Bunny video created, `guid` returned) but fails at Step 7 (second `transactionTemplate.execute()` rolls back). The `videos.provider_asset_id` is never persisted. Bunny later sends Status=7 and Status=3 webhooks for that `guid`. Both webhook lookups find no video — both are COMPLETED silently. The Bunny asset becomes an orphan.

**Story 6.2 does not change this behaviour** — it is pre-existing. Document it as a known gap for the orphan reconciliation work (reconciliation worker already handles this via `ReconciliationWorkerScheduler`). Story 6.x should verify the reconciler detects the guid-with-no-video case.

**Related gap — Bunny video orphan on user cancel** (audit H-4): When `cancelUpload()` calls `tus.Upload.abort(true)`, a TUS `DELETE` request is sent to Bunny, terminating the upload session. However, the Bunny video object created in `POST /library/{libraryId}/videos` during `initializeUpload()` is **not** deleted by the TUS DELETE — it remains in Bunny's library indefinitely as an orphan. The local quota reservation is released by the reaper, but Bunny-side storage accumulates with every cancelled upload. The current `ReconciliationWorkerScheduler` may not cover this case (a Bunny video that has a DB video record in UPLOADING→RELEASED state). **Story 6.x should**: (1) add a `DELETE /library/{libraryId}/videos/{guid}` call in the reaper when releasing stale reservations where a `providerAssetId` exists, or (2) extend the reconciler to detect Bunny orphans by comparing Bunny library contents against local `videos` records.

### `VideoService.confirmUpload()` — Not Part of TUS Flow

`VideoService.confirmUpload()` is used for non-TUS legacy flows. In the TUS pipeline, Bunny.net webhooks drive the full lifecycle — the client does NOT call `confirmUpload()` after upload. The `confirmUpload()` endpoint and its `quotaProvider.commit()` call remain in the codebase (legacy support) but are bypassed for TUS uploads. If `confirmUpload()` is called for a TUS-uploaded video for any reason, the commit is idempotent.

### `BunnyVideoProviderAdapter` — Constructor Changes in Story 6.2

Story 6.2 adds two new constructor parameters to `BunnyVideoProviderAdapter`:

- **`long sessionTtlSeconds`** — passed from `VideoProviderConfig` as `properties.getUpload().getSessionTtlMinutes() * 60L`. Used for the TUS `AuthorizationExpire` epoch. Aligns TUS credential expiry with the upload session TTL so they expire together.
- **`String webhookSigningSecret`** — the Bunny library Read-Only API Key, used exclusively in `verifyWebhook()`. See Task 23.

`libraryId` is stored as `String` but **parsed once at construction time** into `libraryIdLong` (`Long.parseLong(libraryId)`). A non-numeric `libraryId` throws `IllegalArgumentException` at startup, not at request time. The TUS `LibraryId` header is sent as a string by `tus-js-client` (`String(tusLibraryId)`). Always use the pre-parsed `libraryIdLong` field inside `initializeUpload()` — never call `Long.parseLong(libraryId)` again inside a request path.

### Bunny.net API Keys — Two Distinct Keys Required

Bunny.net Stream exposes **two different API keys per library**:

| Key | Where to find | Used for |
|---|---|---|
| Full API Key | Library Settings → API → "API Key" | REST calls (`AccessKey` header) |
| Read-Only API Key | Library Settings → API → "Read-Only API Key" | Webhook HMAC-SHA256 signing |

These are different string values. Story 6.2 uses **two separate config properties**:
- `app.video.bunny.api-key` — the full API Key (REST calls via `buildHeaders()`)
- `app.video.bunny.webhook-signing-secret` — the Read-Only API Key (webhook signature verification in `verifyWebhook()`)

**Before deploying to any environment:**
1. Copy the **Read-Only API Key** from Bunny Stream dashboard → Library Settings → API.
2. Set `app.video.bunny.webhook-signing-secret` to that value in environment config.
3. Set `app.video.bunny.api-key` to the full API Key (may already be correct from prior setup).
4. Verify with a test webhook delivery that `POST /api/video/webhooks/bunny` returns 200, not 400.

If these properties are swapped or the wrong key is used for either, either REST API calls or webhook verification will fail silently.

### TUS Credential Expiry — Upload Duration Constraint

TUS credentials (`AuthorizationExpire`) are valid for `sessionTtlMinutes × 60` seconds. After expiry, Bunny rejects all PATCH (chunk) requests with 401. `tus-js-client` retries with the same stale credentials — all retries fail permanently. The upload is stuck in error state with no automatic recovery path.

**Minimum viable `sessionTtlMinutes` formula:**

```
sessionTtlMinutes ≥ ceil(maxFileSizeBytes / minExpectedBandwidthBytesPerSecond / 60) + safety_margin
```

For the current limits: COACH_REVIEW = 1 GB, minimum expected mobile bandwidth = ~500 KB/s:
`ceil(1_073_741_824 / 512_000 / 60) + 30 min = 35 + 30 = 65 minutes`

**The default `sessionTtlMinutes` must be at least 90 minutes** to cover COACH_REVIEW uploads on mobile. Verify the current default in `application.yml` before deploying. If the current default is 60 minutes (matching `platform.video_reservation_timeout_minutes`), update it.

There is no credential refresh path in `video.store.js` for Story 6.2. If credentials expire mid-upload, the user must re-initiate (start over). This is an acceptable limitation documented here for Story 6.x.

### `VideoPublishedEvent` Ordering — Story 6.3 Handler Contract

`VideoPublishedEvent` fires inside `completeTranscoding()` Phase 3 (`@TransactionalEventListener(AFTER_COMMIT)` handlers run after Phase 3 TX commits). The scheduler then runs a **separate** transaction to mark the `video_webhook_events` row as COMPLETED. At the time `@TransactionalEventListener` handlers execute, the webhook event status is still `PROCESSING`, not `COMPLETED`.

**Story 6.3 handlers MUST NOT** assume `COMPLETED` status when querying `video_webhook_events` for idempotency. Use the `video.operational_state = READY` check or the reservation `status = COMMITTED` check instead.

### `video.store.js` — PROCESSING→READY Feedback Gap

After TUS `onSuccess`, the store sets `uploadState = 'processing'` and calls the caller's `onSuccess` with the `videoId`. There is no mechanism in Story 6.2 for the frontend to know when Bunny encoding completes and the video transitions to READY. The user sees a perpetual "processing" state until a page reload or external polling mechanism is added.

Story 6.3 adds the playback/CDN infrastructure. A polling endpoint or SSE push for video state changes should be added alongside or in a follow-up Story 6.x.

### `VideoResource` Cross-Module Dependency

`VideoResource` in `platform.video.api` imports `CoachProfileService` from `platform.marketplace.service`. This is intentional — same precedent as `DrillUploadService` in `platform.session`. `securityUtil.getCurrentCoachUserId()` returns a `Long`; `coachProfileService.getCoachIdByUserId(Long)` resolves the UUID.

### Quota Accounting Gap — Declared vs. Actual Bytes (Known Limitation) — CRITICAL

`QuotaService.commit()` increments `video_quotas.storage_used_bytes` by `video_quota_reservations.reserved_bytes` — the value declared by the client at upload initiation. Bunny TUS credentials carry no server-enforced upload-size constraint. A client that declares `fileSizeBytes = 1 MB` but uploads `950 MB` via TUS will be charged only `1 MB` of quota while consuming `950 MB` of actual Bunny storage. `videos.storage_bytes` is set from Bunny's actual `storageSize` (via `getVideoMetadata()`) but this value is informational only — it does not feed back into the quota ledger.

**Failure mode**: systematic understatement of `fileSizeBytes` bypasses the quota ceiling. The `VideoTypeConstraints.validate()` size check runs against the declared size, not the actual uploaded size.

**Mitigations available but deferred:**
1. After `getVideoMetadata()` in `completeTranscoding()`, compare `videos.storage_bytes` to `reserved_bytes`. If the delta exceeds a configurable tolerance (e.g., 10%), log a WARN and flag the video for admin review.
2. In a future Story 6.x, adjust `storage_used_bytes` by the actual delta at commit time using a separate CTE update. This is architecturally complex with the current atomic-CTE commit pattern.
3. Add a Bunny library-level upload size limit via the Bunny dashboard (if supported) as a deployment guard.

**Story 6.2 does not fix this** — document the gap for Story 6.x and add the delta-check WARN log as a non-blocking item. Track as a known limitation in admin monitoring.

### `UploadCredentials` Record is Bunny-TUS Specific — Provider Abstraction Note

`UploadCredentials` in `infrastructure.video` now contains `tusAuthorizationSignature`, `tusAuthorizationExpire`, and `tusLibraryId` — fields that are meaningless for any non-TUS provider (S3 multipart, Cloudflare Stream, etc.). This record is the return type of `VideoProviderAdapter.initializeUpload()`, a provider-agnostic interface. `InitializeUploadResponse` and `VideoService` pass these fields verbatim to the client.

**For MVP (Bunny.net only):** this is acceptable. The coupling is confined to `infrastructure.video` and `platform.video.contract`.

**When Provider 2 is added**, the following refactoring is required before Story 6.x:
1. Convert `UploadCredentials` to a sealed interface with provider-specific subtypes: `BunnyTusUploadCredentials`, `S3MultipartUploadCredentials`, etc.
2. `InitializeUploadResponse` must be redesigned to carry only provider-agnostic fields or a provider-specific credential blob.
3. `VideoService.initializeUpload()` must use pattern-matching on the credential subtype to construct the response.

Do NOT extend the current `UploadCredentials` record with fields for a second provider — that path leads to a bag-of-nullable-fields anti-pattern.

### `VideoUploadedEvent` — Contract for Story 6.3 Moderation Pipeline

`VideoUploadedEvent(videoId, ownerId)` is published inside `WebhookEventProcessorScheduler.dispatchEvent()` after `videoLifecycleService.transitionOperationalState(videoId, PROCESSING)` succeeds for a `"video.upload.success"` event. At the moment the event fires, `videos.operational_state = PROCESSING`.

**Story 6.3 handlers MUST:**
- Use `@TransactionalEventListener(phase = AFTER_COMMIT)` — not `@EventListener` — to ensure the PROCESSING state is visible in the DB before the moderation pipeline reads the video record.
- Be idempotent — the `video_webhook_events` outbox deduplication prevents duplicate delivery at the webhook layer, but `@TransactionalEventListener` can fire more than once if the transaction is retried.
- NOT assume `videos.provider_asset_id` is set and the Bunny video is ready for download. The video is in PROCESSING (Bunny is encoding). Story 6.3 should either wait for `VideoPublishedEvent` (READY state) or call Bunny's API to check encoding status.

### `BunnyVideoResponse` — Shared Record Risk

`BunnyVideoResponse(String guid, int status, Integer length, Long storageSize)` is used by both `getAssetStatus()` (reads `guid`, `status`) and `getVideoMetadata()` (reads `length`, `storageSize`). Adding fields for one use-case implicitly changes the deserialization contract for the other. If `getAssetStatus()` is later extended to use a different Bunny endpoint that doesn't return `length`/`storageSize`, the shared record becomes incorrect. Consider splitting into `BunnyVideoStatusResponse` and `BunnyVideoDetailResponse` if either use-case evolves. For Story 6.2 the shared record is acceptable.

### Project Structure Summary

| Component | Location | Status |
|---|---|---|
| `V54__video_type_column.sql` | `src/main/resources/db/migration/` | CREATE |
| `VideoMetadata.java` | `infrastructure.video` | CREATE |
| `VideoUploadInitiateRequest.java` | `platform.video.contract` | CREATE |
| `VideoPublishedEvent.java` | `platform.video.contract.event` | CREATE |
| `UploadCredentials.java` | `infrastructure.video` | MODIFY — add TUS credential fields |
| `InitializeUploadRequest.java` | `platform.video.contract` | MODIFY — add VideoType field |
| `InitializeUploadResponse.java` | `platform.video.contract` | MODIFY — add TUS credential fields |
| `QuotaProvider.java` | `platform.video.contract` | MODIFY — add default 3-arg reserve() |
| `VideoProviderAdapter.java` | `infrastructure.video` | MODIFY — add getVideoMetadata() default |
| `BunnyVideoProviderAdapter.java` | `infrastructure.video` | MODIFY — fix webhook parsing, TUS creds, metadata, add sessionTtlSeconds constructor param |
| `VideoProviderConfig.java` | `platform.video.config` | MODIFY — pass sessionTtlSeconds to BunnyVideoProviderAdapter constructor |
| `VideoWebhookResource.java` | `platform.video.api` | MODIFY — fix X-BunnyStream-Signature header |
| `Video.java` | `platform.video.repo` | MODIFY — add videoType field |
| `QuotaService.java` | `platform.video.service` | MODIFY — override 3-arg reserve() |
| `VideoService.java` | `platform.video.service` | MODIFY — TUS fields in responses, completeTranscoding, failTranscoding, publisher |
| `VideoResource.java` | `platform.video.api` | MODIFY — replace empty shell with initiate endpoint |
| `WebhookEventProcessorScheduler.java` | `platform.video.service` | MODIFY — add VideoService, update dispatchEvent |
| `DrillUploadService.java` | `platform.session.service` | MODIFY — add VideoType.DRILL_DEMO as 5th arg |
| `VideoUploadInitializationIT.java` | `platform.video.service` (test) | MODIFY — 5-arg constructor, 3-arg reserve mock, TUS UploadCredentials |
| `DrillUploadServiceTest.java` | `platform.session.service` (test) | MODIFY — 5-arg InitializeUploadRequest |
| `VideoUploadPipelineIT.java` | `platform.video.service` (test) | CREATE |
| `VideoUploadResourceIT.java` | `platform.video.api` (test) | CREATE — use `@WebMvcTest(VideoResource.class)` |
| `BunnyVideoProviderAdapterTest.java` | `infrastructure.video` (test) | MODIFY — constructor, webhook payload, status assertions, new getVideoMetadata tests |
| `video.api.js` | `src/frontend/src/api/` | CREATE |
| `video.store.js` | `src/frontend/src/stores/` | CREATE |
| `VideoProperties.java` | `platform.video.config` | MODIFY — add `Bunny.webhookSigningSecret` field |
| `VideoUploadedEvent.java` | `platform.video.contract.event` | CREATE — Task 26 |
| `VideoWebhookResourceIT.java` | `platform.video.api` (test) | CREATE — Task 27 |
| `VideoConfig.java` | `platform.video.config` | MODIFY — Task 28 startup assertion |

### References

- `BunnyVideoProviderAdapter.java` — MODIFY: fix BunnyWebhookPayload, computeTusSignature (sessionTtlSeconds + libraryIdLong), getVideoMetadata, extend BunnyVideoResponse, HexFormat hardening, fix mapBunnyStatus, add webhookSigningSecret [`src/main/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapter.java`]
- `VideoProperties.java` — MODIFY: add `Bunny.webhookSigningSecret` field [`src/main/java/com/softropic/skillars/platform/video/config/VideoProperties.java`]
- `VideoProviderConfig.java` — MODIFY: pass `sessionTtlSeconds` and `webhookSigningSecret` as new constructor args [`src/main/java/com/softropic/skillars/platform/video/config/VideoProviderConfig.java`]
- `VideoWebhookResource.java` — MODIFY: fix header to X-BunnyStream-Signature [`src/main/java/com/softropic/skillars/platform/video/api/VideoWebhookResource.java`]
- `VideoService.java` — MODIFY: 3-arg reserve calls, TUS fields in responses, completeTranscoding, failTranscoding [`src/main/java/com/softropic/skillars/platform/video/service/VideoService.java`]
- `VideoLifecycleService.java` — unchanged; VALID_TRANSITIONS must not change [`src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java`]
- `WebhookEventProcessorScheduler.java` — MODIFY: VideoService added, dispatchEvent updated [`src/main/java/com/softropic/skillars/platform/video/service/WebhookEventProcessorScheduler.java`]
- `VideoWebhookResource.java` — unchanged beyond header name fix; existing outbox pattern is correct [`src/main/java/com/softropic/skillars/platform/video/api/VideoWebhookResource.java`]
- `QuotaService.java` — MODIFY: 3-arg reserve override [`src/main/java/com/softropic/skillars/platform/video/service/QuotaService.java`]
- `VideoApiAdvice.java` — unchanged; global @RestControllerAdvice covers VideoResource [`src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java`]
- `DrillUploadService.java` — MODIFY: add VideoType.DRILL_DEMO as 5th arg to InitializeUploadRequest [`src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java`]
- `BunnyVideoProviderAdapterTest.java` — MODIFY: constructor update (8 args), webhook payload fix, mapBunnyStatus CSV corrections, new getVideoMetadata tests [`src/test/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapterTest.java`]
- `BaseVideoIT.java` — base class; extend for VideoUploadPipelineIT [`src/test/java/com/softropic/skillars/platform/video/BaseVideoIT.java`]
- `WebhookPipelineIT.java` — seeding pattern reference [`src/test/java/com/softropic/skillars/platform/video/service/WebhookPipelineIT.java`]
- `VideoUploadInitializationIT.java` — MODIFY: 5-arg InitializeUploadRequest + 3-arg reserve + 5-field UploadCredentials mock [`src/test/java/com/softropic/skillars/platform/video/service/VideoUploadInitializationIT.java`]
- `session.api.js` — frontend API file pattern [`src/frontend/src/api/session.api.js`]
- `booking.store.js` — frontend store composition pattern [`src/frontend/src/stores/booking.store.js`]
- Epic 6 Story 6.2 spec [`_bmad-output/planning-artifacts/skillars-epics.md` lines 2025–2069]
- Story 6.1 dev notes — quota foundation; VideoType nullable in reservations [`_bmad-output/implementation-artifacts/skillars-6-1-video-module-foundation-quota-system.md`]

## Review Findings

_Senior dev audit — 2026-06-20. Story status: ready-for-dev → reviewed. All critical and high findings converted to tasks or task amendments above._

### Critical

- [x] [Review][Task 23] Bunny webhook signing key ≠ REST API key — two distinct Bunny keys; single `apiKey` property causes all webhook verifications to fail on correct deployments. Fixed by: new Task 23 (`webhookSigningSecret` property + `BunnyVideoProviderAdapter` constructor split), updated Dev Notes section. [`VideoProperties.Bunny`, `BunnyVideoProviderAdapter`, `VideoProviderConfig`]
- [x] [Review][Task 9 Fix 3] `receiveBunnyWebhook()` race condition on concurrent delivery — no `@Transactional`, concurrent re-deliveries both pass EXISTS check and one throws `DataIntegrityViolationException` → 500 to Bunny → retry loop. Fixed by: adding `@Transactional` and catching `DataIntegrityViolationException` → 200. [`VideoWebhookResource.java`]
- [x] [Review][Task 12 Sub-task D] `mapBunnyStatus()` maps Bunny Status=3 (Finished) to PROCESSING instead of READY — reconciliation/admin paths classify finished videos as still encoding. Fixed by: new Sub-task D with corrected case mapping. [`BunnyVideoProviderAdapter.java`]

### High

- [x] [Review][Task 14] No `VideoTypeConstraints.validate()` call in `initializeUpload()` — type-specific size limits (e.g., COACH_REVIEW ≤ 1 GB) never enforced; oversized files accepted, uploaded, committed. Fixed by: added `videoTypeConstraints.validate()` call after `validationChain.validate()` in `initializeUpload()`, `VideoTypeConstraints` injected into `VideoService`. [`VideoService.java`]
- [x] [Review][Task 22] TUS credential expiry on long uploads — credentials expire at `sessionTtlMinutes × 60` s; large files on slow connections exceed this; no refresh path. Fixed by: added TUS Credential Expiry dev note with minimum `sessionTtlMinutes` formula; `cancelUpload()` fixed to `await abort(true)`. [`video.store.js`, Dev Notes]
- [x] [Review][Task 16] No role→videoType authorization — coaches could POST `videoType: HOMEWORK` (player type) and bypass intended type segregation. Fixed by: added `COACH_ALLOWED_VIDEO_TYPES` guard in `VideoResource.initiateUpload()` returning 422 for disallowed types. [`VideoResource.java`]
- [x] [Review][Task 24] No HTTP-layer IT for `POST /api/video/uploads/initiate` — only service-layer tests; endpoint auth, request validation, role-type guard untested end-to-end. Fixed by: new Task 24 (`VideoUploadResourceIT` with 5 test cases). [`VideoUploadResourceIT.java`]

### Medium

- [x] [Review][Task 12 Sub-task B] `Long.parseLong(libraryId)` fails at request time with no startup validation — misconfigured non-numeric `libraryId` throws `NumberFormatException` during first TUS upload. Fixed by: parse and store as `libraryIdLong` in constructor with `IllegalArgumentException` on failure. [`BunnyVideoProviderAdapter.java`]
- [x] [Review][Task 14] Phase 3 comment "no explicit save() needed" misrepresents JPA L1 cache mechanics — could mislead future refactors. Fixed by: replaced comment with accurate explanation of L1 cache entity reuse. [`VideoService.java`]
- [x] [Review][Task 18 Test 5] No test for null `reservationHandle` in `completeTranscoding()` — partially-failed initiation leaves session with null handle; WARN log path untested. Fixed by: added Test 5 to `VideoUploadPipelineIT`. [`VideoUploadPipelineIT.java`]
- [x] [Review][Task 13] 3-arg `QuotaService.reserve()` missing `@Observed` — callers invoking 3-arg bypass the 2-arg interface proxy; monitoring gap. Fixed by: added `@Observed(name = "video.quota.reserve")` to 3-arg override. [`QuotaService.java`]
- [x] [Review][Task 22] `cancelUpload()` ignores `abort()` Promise — TUS upload continues in-flight after store resets; race with second upload. Fixed by: converted to `async function`, added `await abort(true).catch(...)`. [`video.store.js`]
- [x] [Review][Dev Notes] `VideoPublishedEvent` fires before webhook marked COMPLETED — Story 6.3 handlers must not assume COMPLETED status at handler execution time. Fixed by: added `VideoPublishedEvent` ordering dev note. [Dev Notes]

### Low

- [x] [Review][Task 11] Missing grep for `uploadSessionId` consumers before closing rename task — added explicit `grep -rn "uploadSessionId" src/` command with known consumers listed. [`InitializeUploadResponse.java`]
- [x] [Review][Dev Notes] PROCESSING→READY UX gap not documented — frontend has no signal for encoding completion. Fixed by: added dev note. [Dev Notes]

---

_Senior dev audit — 2026-06-20, pass 2. Story status: reviewed → re-reviewed. All findings converted to task amendments, new tasks, or dev notes above._

### Critical (pass 2)

- [x] [Review][Task 14] Missing `VideoLifecycleService` injection in `VideoService` — `completeTranscoding()` calls `videoLifecycleService.transitionOperationalState()` but the field is absent; Task 14's injection list only adds `ApplicationEventPublisher` and `VideoTypeConstraints`. Fixed by: added `VideoLifecycleService videoLifecycleService` field and import to Task 14. [`VideoService.java`]
- [x] [Review][Task 25] `BunnyVideoProviderAdapterTest` not listed for update — constructor change breaks compilation (6 → 8 args); `verifyWebhook` test uses stale payload format and wrong HMAC key; `getAssetStatus_mapsStatusCorrectly` CSV has wrong expected values for Status=3 (PROCESSING→READY), Status=6 (FAILED→UPLOADING), Status=8 (READY→PROCESSING) after Sub-task D fix. Fixed by: new Task 25 covering all four breakages. [`BunnyVideoProviderAdapterTest.java`]
- [x] [Review][Task 12 Sub-task B] Snippet contradiction — `initializeUpload()` code uses `Long.parseLong(libraryId)` but the task's own instruction says use `libraryIdLong`; developer copying the snippet reintroduces the bug. Fixed by: corrected snippet to `libraryIdLong`. [`BunnyVideoProviderAdapter.java`]
- [x] [Review][Task 16] `VideoType` import missing from `VideoResource` — class references `VideoType.DRILL_DEMO` and `VideoType.COACH_REVIEW` in a static field initializer; neither the snippet's import block nor the "Add import" note includes `VideoType`. Fixed by: added `com.softropic.skillars.platform.video.contract.VideoType` to Task 16 import list. [`VideoResource.java`]
- [x] [Review][Task 16] `VideoValidationException` 2-arg constructor may not exist — all current call sites use the 1-arg form; Task 16 throws `new VideoValidationException("video.invalidTypeForRole", "...")`. Fixed by: added explicit verification step to Task 16 with fallback instructions. [`VideoResource.java`, `VideoValidationException.java`]

### High (pass 2)

- [x] [Review][Task 15] Out-of-order Bunny webhooks (Status=3 before Status=7) cause dead-lettered events — `completeTranscoding()` tries PROCESSING→READY but video is still in UPLOADING; `TerminalStateViolationException` is thrown, event retries exhaust, video stuck in UPLOADING permanently. Fixed by: added compensating UPLOADING→PROCESSING transition inside `dispatchEvent()` case for `video.encoding.success`; added dev note with explanation. [`WebhookEventProcessorScheduler.java`, Dev Notes]
- [x] [Review][Task 14] `retryUpload()` missing `videoTypeConstraints.validate()` — type-specific size limits enforced at initiation but not on retry; oversized files can bypass the constraint after a failure. Fixed by: added validate call to Task 14's retryUpload() section. [`VideoService.java`]
- [x] [Review][Dev Notes] Quota reservation timeout vs. session TTL drift — if platform `reservationTimeoutMinutes` < app `sessionTtlMinutes`, reaper releases the reservation before TUS upload completes; subsequent `commit()` no-ops silently; `storage_used_bytes` never incremented (free storage). Fixed by: added dev note with failure mode description and startup assertion recommendation. [Dev Notes]
- [x] [Review][Task 21, Task 22] `cancelUpload()` does not abort the in-flight axios POST — if cancelled during 'initiating' state, the POST resolves after state reset, starts an untracked TUS upload with stale callbacks. Fixed by: added `AbortController` ref to store, signal passed to `videoApi.initiateUpload()`, post-await guard added. [`video.store.js`, `video.api.js`]

### Medium (pass 2)

- [x] [Review][Task 24] `@SpringBootTest(RANDOM_PORT)` + `MockMvc` spec is contradictory — changed to `@WebMvcTest(VideoResource.class)` with `@MockitoBean` for service/util dependencies. [`VideoUploadResourceIT.java`]
- [x] [Review][Dev Notes] "Video not found" webhook events silently completed — Step-7 DB failure leaves `provider_asset_id` unpersisted; Bunny webhooks for that GUID find no video and are absorbed as COMPLETED with no retry. Fixed by: added dev note linking to reconciliation worker as the existing mitigation. [Dev Notes]

---

_Senior dev audit — 2026-06-20, pass 3. Story status: re-reviewed → re-reviewed. All critical and high findings converted to task amendments, new tasks (26–28), or dev notes. Medium/low findings converted to task amendments or annotated inline._

### Critical (pass 3)

- [x] [Review][Task 26] `VideoUploadedEvent` missing — `video.upload.success` handler does not publish the event required by Story 6.3 moderation pipeline; without it, CSAM scanning never triggers. Fixed by: new Task 26 (create event record, publish in Task 15 `dispatchEvent()` switch, add `VideoUploadedEvent` publishing test to Task 18, add `VideoUploadedEvent — Contract for Story 6.3` dev note). [`WebhookEventProcessorScheduler.java`, `VideoUploadedEvent.java`]
- [x] [Review][Dev Notes] Quota bypass via file-size understatement — `commit()` debits `reserved_bytes` (declared), not actual Bunny `storageSize`; TUS credentials carry no server-enforced upload-size constraint; a user can declare 1MB, upload 950MB, pay 1MB of quota. Fixed by: added "Quota Accounting Gap — Declared vs. Actual Bytes" dev note with mitigation options and known-limitation designation; no code change in 6.2 scope. [`QuotaService.java:commit`]
- [x] [Review][Dev Notes] `UploadCredentials` record leaks Bunny-TUS specifics into `VideoProviderAdapter` interface — breaks provider abstraction when second provider is added. Fixed by: added "UploadCredentials Record is Bunny-TUS Specific" dev note with refactoring path for Provider 2. [`UploadCredentials.java`, `VideoProviderAdapter.java`]

### High (pass 3)

- [x] [Review][Task 22] `video.store.js` permanently stuck in `'processing'` state — no upload-again path after encoding starts, no error recovery function. Fixed by: added `resetUpload()` function to store, exposed in return object alongside `cancelUpload`; added audit note explaining the lifecycle constraint. [`video.store.js`]
- [x] [Review][Task 14] `expiresAt` in `InitializeUploadResponse` source never traced — Task 14 uses the variable without defining it. Fixed by: added note specifying `expiresAt = Instant.ofEpochSecond(credentials.tusAuthorizationExpire())` to avoid separate clock-instant divergence. [`VideoService.java:initializeUpload`]
- [x] [Review][Task 14] L1-cache entity-reuse assumption in `completeTranscoding()` Phase 3 is unverified — if `VideoLifecycleService.transitionOperationalState()` uses a JPQL UPDATE instead of findById+save, `durationMs`/`storageBytes` mutations are silently lost. Fixed by: replaced misleading comment with an explicit pre-coding verification instruction. [`VideoService.java:completeTranscoding`]
- [x] [Review][Task 27] No `VideoWebhookResourceIT` — Task 9's race-condition fix (`@Transactional` + `DataIntegrityViolationException` catch) is untested at the HTTP layer; concurrent duplicate delivery test is the only way to verify the fix. Fixed by: new Task 27 with 5 test cases including a concurrent-delivery test. [`VideoWebhookResourceIT.java`]
- [x] [Review][Task 15] `dispatchEvent()` compensating UPLOADING→PROCESSING transition can throw `TerminalStateViolationException` spuriously on multi-node schedulers — if another node already advanced the state, the exception dead-letters the event. Fixed by: added try/catch on the compensating transition with debug log; `completeTranscoding()` proceeds regardless. [`WebhookEventProcessorScheduler.java:dispatchEvent`]

### Medium (pass 3)

- [x] [Review][Task 13] `@Observed` missing from 2-arg `QuotaService.reserve()` — the 2-arg→3-arg self-call bypasses the Spring proxy, so `@Observed` on 3-arg alone misses the `DrillUploadService` code path. Fixed by: added `@Observed(name = "video.quota.reserve")` to the 2-arg override with explanatory comment. [`QuotaService.java:reserve`]
- [x] [Review][Task 3] `mimeType` accepts non-video MIME types — no constraint; `"application/pdf"` passes Bean Validation, creating orphaned quota reservations. Fixed by: added `@Pattern(regexp = "video/.+")` to `VideoUploadInitiateRequest.mimeType`. [`VideoUploadInitiateRequest.java`]
- [x] [Review][Task 12 Sub-task C] `response.getBody()` null not guarded in `getVideoMetadata()` — NPE if Bunny returns 200 with empty body. Fixed by: added null guard with explicit `VideoProviderException`. [`BunnyVideoProviderAdapter.java:getVideoMetadata`]
- [x] [Review][Task 22] `AbortController` not cleared after initiation phase resolves — stale ref held during 'uploading' state. Fixed by: `currentAbortController.value = null` after successful initiation `await`. [`video.store.js`]
- [x] [Review][Task 24] `VideoUploadResourceIT` missing 4 boundary test cases — `fileSizeBytes=0`, invalid enum string, invalid MIME type, file-size-exceeds-type-limit not covered. Fixed by: added Tests 6–9 to Task 24. [`VideoUploadResourceIT.java`]
- [x] [Review][Task 28] No startup assertion for `reservationTimeoutMinutes ≥ sessionTtlMinutes` — misconfiguration causes silent quota-commit no-ops when reaper fires before TUS upload completes. Fixed by: new Task 28 (`@PostConstruct` validator in `VideoConfig`). [`VideoConfig.java`]

### Low (pass 3)

- [x] [Review][Dev Notes] Webhook signature dev note contradicted Task 23 — stated "for now, the single `apiKey` value is used for both" after Task 23 already split the keys. Fixed by: replaced stale sentence with accurate reference to Task 23 and the Two-Keys dev note. [Dev Notes:Webhook Signature Verification]
- [x] [Review][Dev Notes] `BunnyVideoResponse` shared record risk documented — added "BunnyVideoResponse — Shared Record Risk" dev note for future maintainers. [Dev Notes]
- [x] [Review][Dev Notes] `VideoUploadedEvent` Story 6.3 contract documented — handler requirements (AFTER_COMMIT, idempotency, no download assumption) added to dev notes. [Dev Notes]
- [x] [Review][Task 15] `videoOpt.get()` re-accessed for two different fields after null check — consolidated to `Video video = videoOpt.get()` at the top of `dispatchEvent()` switch so the entity is held once; eliminates repeated Optional unwrapping and stale-state confusion. [`WebhookEventProcessorScheduler.java:dispatchEvent`]

---

_Senior dev audit — 2026-06-20, pass 4 (external API verification + deep logic review). Story status: re-reviewed → updated. All critical and high findings converted to task amendments or new task steps above._

### Critical (pass 4)

- [x] [Review][C-1] Status=8 misidentified as "TitleUpdating" — Bunny docs confirm Status=8 = "Pre-signed upload failed" (TUS failure, not informational). Previous mapping: default → PROCESSING. Fix: (1) `verifyWebhook()` maps Status=8 → `"video.upload.failed"`, (2) `dispatchEvent()` handles `"video.upload.failed"` with FAILED transition + quota release, (3) `mapBunnyStatus()` maps Status=8 → FAILED, (4) Task 25 CSV `"8, PROCESSING"` → `"8, FAILED"`, (5) Sub-task D comment corrected. [`BunnyVideoProviderAdapter.java`, `WebhookEventProcessorScheduler.java`, `BunnyVideoProviderAdapterTest.java`]
- [x] [Review][C-2] `VideoUploadedEvent` never published in out-of-order webhook compensation path — when Status=3 arrives before Status=7, the video advances UPLOADING→PROCESSING→READY without firing `VideoUploadedEvent`. Story 6.3 moderation pipeline is entirely bypassed. Fix: added `publisher.publishEvent(new VideoUploadedEvent(...))` inside the compensation branch of `dispatchEvent()`, before `completeTranscoding()`. Contract note added to dev notes. [`WebhookEventProcessorScheduler.java`]

### High (pass 4)

- [x] [Review][H-1] `BunnyVideoResponse` field names (`length`, `storageSize`) unverified — Get Video API docs inaccessible; if field names differ, `duration_ms` and `storage_bytes` are silently 0 for all videos. Fix: added explicit Step 0 verification requirement to Sub-task C before coding. [`BunnyVideoProviderAdapter.java:BunnyVideoResponse`]
- [x] [Review][H-2] No startup check that `sessionTtlMinutes ≥ 60` — Bunny docs require TUS `AuthorizationExpire` to be "at least 1 hour (3600 seconds)"; misconfigured value causes all TUS uploads to fail with 401. Fix: added `BUNNY_MIN_TUS_EXPIRY_MINUTES = 60` check to Task 28's `validateTimeoutAlignment()` before the existing reservation-timeout check. [`VideoConfig.java`]
- [x] [Review][H-3] `cancelUpload()` during 'initiating' state overwrites `'idle'` → `'error'` — JS event-loop ordering: `cancelUpload()` sets `uploadState = 'idle'` synchronously, then the AbortError microtask fires the catch block and sets `uploadState = 'error'`. Fix: added `if (uploadState.value === 'idle') return` guard at the top of the catch block in `initiateAndUpload()`. [`video.store.js`]
- [x] [Review][H-4] Pre-created Bunny video not deleted on cancel — `abort(true)` terminates the TUS session but the Bunny video object created in `POST /library/{libraryId}/videos` is an orphan indefinitely. Local reaper releases quota; Bunny storage accumulates. Fix: documented gap in "Video Not Found" dev note; Story 6.x action items added for reaper-side Bunny cleanup. [`video.store.js`, Dev Notes]

### Medium (pass 4)

- [x] [Review][M-1] `VideoWebhookResourceIT` (Task 27) missing `@TestPropertySource` to disable schedulers — background scheduler races with test webhook event assertions. Fix: added `@TestPropertySource` note to Task 27, referencing `WebhookPipelineIT` for exact properties. [`VideoWebhookResourceIT.java`]
- [x] [Review][M-2] `VideoResource` no null guard on `getCoachIdByUserId()` — COACH-role user with no profile row causes NPE or null ownerId propagated into quota tables (NOT NULL violation). Fix: added null guard with `ResponseStatusException(FORBIDDEN)` in Task 16; added `ResponseStatusException`, `HttpStatus` to import list. [`VideoResource.java`]
- [x] [Review][M-3] Status=9 and Status=10 informational webhooks not tested — no coverage for graceful no-op handling. Fix: added two new tests to Task 25 (`verifyWebhook_informationalStatuses_returnStatusEventAndNoException`) parametrized over Status=9 and Status=10. [`BunnyVideoProviderAdapterTest.java`]
- [x] [Review][M-4] L1-cache mutation verification buried in code comment — if `VideoLifecycleService.transitionOperationalState()` uses JPQL UPDATE, `duration_ms`/`storage_bytes` mutations are silently lost. Fix: promoted to explicit "Step 0 — MANDATORY pre-coding verification" at the top of Task 14. [`VideoService.java:completeTranscoding`]
- [x] [Review][M-5] `sumActiveReservedBytes()` reference in Task 13 unconfirmed — method was added in Story 6.1 patch run but not listed in 6.1 File List. Fix: added Step 0 grep verification to Task 13 with fallback JPQL to add if absent. [`VideoQuotaReservationRepository.java`]
- [x] [Review][M-6] `video.getOwnerId()` return type unconfirmed for `VideoUploadedEvent` — if entity returns UUID not String, compile error. Fix: added explicit verification note to Task 26. [`VideoUploadedEvent.java`, `WebhookEventProcessorScheduler.java`]

### Low (pass 4)

- [x] [Review][L-1] Task 25 CSV `"8, PROCESSING"` was wrong — Status=8 = "Pre-signed upload failed" → FAILED. Fixed as part of C-1. [`BunnyVideoProviderAdapterTest.java`]
- [x] [Review][L-2] `retryDelays: [0, ...]` immediate retry worsens 429/503 — changed first entry from `0` to `1000ms`. [`video.api.js`]
- [x] [Review][L-3] `VideoWebhookResourceIT` Test 1 seeds no video record — added comment clarifying this is correct (webhook layer is decoupled from video lookup) and warning against adding state-transition assertions to this test. [`VideoWebhookResourceIT.java`]
- [x] [Review][L-4] Bunny webhook status table in Dev Notes was incomplete — expanded to all 11 documented Bunny statuses (0–10) with accurate descriptions; added Status=8 warning note. [Dev Notes]

---

_Code review — 2026-06-22 (post-implementation review of actual diff). Story status: in-progress → review findings added._

### High (post-impl pass)

- [x] [Review][Patch] `UploadSession.expiresAt` null in first TX save — `@Column(nullable = false)` and `NOT NULL` in V16 migration; removing `session.setExpiresAt(expiresAt)` from the first `transactionTemplate.execute()` block causes every `initializeUpload()` and `retryUpload()` call to throw `DataIntegrityViolationException` at TX commit. Also: `VideoUploadPipelineIT.seedUploadSession()` / `seedUploadSessionWithNullHandle()` test helpers also omit `expiresAt` and will fail against Testcontainers. **Fix**: In first TX, restore `session.setExpiresAt(Instant.now().plus(sessionTtlMinutes, MINUTES))` as a placeholder; update to `credentials.tusAuthorizationExpire()` in second TX. Fix test helpers to set any non-null placeholder. [`VideoService.java:initializeUpload/retryUpload`, `VideoUploadPipelineIT.java:seedUploadSession`]

- [x] [Review][Patch] `DataIntegrityViolationException` catch inside `@Transactional` won't absorb concurrent duplicates — `VideoWebhookEvent` uses `@GeneratedValue(strategy = GenerationType.UUID)` (application-assigned UUID), so `em.persist()` does not flush; the `event_id` unique constraint violation fires at TX commit, AFTER `receiveBunnyWebhook()` returns, outside the try/catch block. The catch is unreachable for concurrent delivery. The concurrent duplicate test (`VideoWebhookResourceIT.concurrentDuplicateDelivery_bothReturn200_onlyOneRowStored`) would fail. **Fix**: Extract the save into a `@Transactional(propagation = REQUIRES_NEW)` helper method that calls `save()` + `flush()`, and catches `DataIntegrityViolationException` from the explicit flush. Remove `@Transactional` from `receiveBunnyWebhook()` (or keep — it no longer affects the idempotency gate). [`VideoWebhookResource.java:receiveBunnyWebhook`]

- [x] [Review][Patch] Out-of-order `video.encoding.success` compensation path publishes `VideoUploadedEvent` outside any transaction — the compensating `transitionOperationalState(UPLOADING→PROCESSING)` commits its own TX; `publisher.publishEvent(new VideoUploadedEvent(...))` is then called with no active transaction. Any `@TransactionalEventListener(AFTER_COMMIT)` listening for `VideoUploadedEvent` has no TX to bind to and drops the event silently (default `fallbackExecution = false`). The normal path (video.upload.success handler) publishes this event inside an active TX — the two paths are inconsistent. **Fix**: Move the `VideoUploadedEvent` publish inside `completeTranscoding()` Phase 3 `transactionTemplate.execute()` block, alongside the `VideoPublishedEvent` publish, so both paths publish `VideoUploadedEvent` from within a TX. [`WebhookEventProcessorScheduler.java:dispatchEvent`, `VideoService.java:completeTranscoding`]

- [x] [Review][Patch] `findFirstByVideoIdOrderByCreatedAtDesc()` selects wrong session after retry — both `completeTranscoding()` and `failTranscoding()` use this query to find the reservation handle; after a `retryUpload()`, there are ≥2 sessions for the same `videoId`. If a late webhook for the ORIGINAL failed upload fires after the retry, `findFirstByVideoIdOrderByCreatedAtDesc()` returns the retry session's handle and releases the active retry reservation — the original session's handle is never released, and the retry upload has its quota freed while still in flight. **Fix**: Filter by session status: `findFirstByVideoIdAndStatusOrderByCreatedAtDesc(videoId, PENDING)` — or add an `ACTIVE`/`COMMITTED` status gate so only the current live session is selected. [`VideoService.java:completeTranscoding+failTranscoding`, `UploadSessionRepository.java`]

### Deferred (post-impl pass, pre-existing)

- [x] [Review][Defer] `retryUpload()` does not transition `videos.operational_state` back to UPLOADING — video stays FAILED during the retry; ReconciliationWorkerScheduler may re-FAIL it based on the original provider state before the new upload registers. [`VideoService.java:retryUpload`] — deferred, pre-existing gap
- [x] [Review][Defer] `confirmUpload()` writes `PROCESSING` directly via `videoRepository.save()`, bypassing `VideoLifecycleService.VALID_TRANSITIONS` enforcement — a future state regression would be silently accepted. [`VideoService.java:confirmUpload`] — deferred, pre-existing gap
- [x] [Review][Defer] `UploadSessionExpiryScheduler` calls `quotaProvider.release()` outside a transaction then marks session EXPIRED in a separate TX — non-atomic two-step; safe because `release()` is idempotent, but ordering is fragile. [`UploadSessionExpiryScheduler.java`] — deferred, pre-existing design decision
- [x] [Review][Defer] No unique index on `videos.provider_asset_id` — `videoRepository.findByProviderAssetId()` could throw `IncorrectResultSizeDataAccessException` if a duplicate is ever stored. [`VideoRepository.java`, schema] — deferred, pre-existing

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

- All 28 tasks + subtasks implemented across backend, frontend, config, and tests.
- `VideoValidationException` 2-arg constructor does not exist — used 1-arg form per story fallback instruction.
- `VideoLifecycleService.transitionOperationalState()` confirmed to use `findById() + save(entity)` pattern, not JPQL UPDATE — L1-cache mutation in `completeTranscoding()` Phase 3 is safe.
- `video.upload.failed` handler in `dispatchEvent()` delegates entirely to `videoService.failTranscoding()` rather than calling `transitionOperationalState()` separately, avoiding double-transition `TerminalStateViolationException`.
- `UploadSessionRepository` field retained in `WebhookEventProcessorScheduler` for metrics in `processPending()`.
- `VideoConfig` constructor now requires `QuotaConfigService` — `QuotaProviderWiringTest` updated with mock bean.
- `application-test.yaml` updated with `app.video.bunny.webhook-signing-secret: test-webhook-signing-secret` to satisfy `VideoConfig` startup validation and `VideoWebhookResourceIT`.
- `VideoUploadedEvent` published in both the normal `video.upload.success` path and the out-of-order `video.encoding.success` compensation branch.
- `DrillUploadInitiateResponse.uploadSessionId` is a separate DTO and was intentionally NOT renamed — only `InitializeUploadResponse.sessionId` was renamed.

### File List

- `src/main/resources/db/migration/V54__video_type_column.sql` (created)
- `src/main/java/com/softropic/skillars/platform/video/contract/InitializeUploadRequest.java` (modified — added VideoType field)
- `src/main/java/com/softropic/skillars/platform/video/contract/VideoUploadInitiateRequest.java` (created)
- `src/main/java/com/softropic/skillars/platform/video/contract/InitializeUploadResponse.java` (modified — renamed uploadSessionId→sessionId, added TUS fields)
- `src/main/java/com/softropic/skillars/platform/video/contract/event/VideoPublishedEvent.java` (created)
- `src/main/java/com/softropic/skillars/platform/video/contract/event/VideoUploadedEvent.java` (created)
- `src/main/java/com/softropic/skillars/platform/video/contract/QuotaProvider.java` (modified — added 3-arg reserve() default)
- `src/main/java/com/softropic/skillars/infrastructure/video/VideoMetadata.java` (created)
- `src/main/java/com/softropic/skillars/infrastructure/video/VideoProviderAdapter.java` (modified — added getVideoMetadata() default)
- `src/main/java/com/softropic/skillars/infrastructure/video/UploadCredentials.java` (modified — expanded to 5 fields with TUS credentials)
- `src/main/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapter.java` (modified — fix webhook payload, TUS creds, getVideoMetadata, mapBunnyStatus, webhookSigningSecret constructor param)
- `src/main/java/com/softropic/skillars/platform/video/repo/Video.java` (modified — added videoType field)
- `src/main/java/com/softropic/skillars/platform/video/service/QuotaService.java` (modified — overrode 3-arg reserve())
- `src/main/java/com/softropic/skillars/platform/video/service/VideoService.java` (modified — completeTranscoding, failTranscoding, TUS fields, videoType, expiresAt, VideoLifecycleService/ApplicationEventPublisher/VideoTypeConstraints injection)
- `src/main/java/com/softropic/skillars/platform/video/service/WebhookEventProcessorScheduler.java` (modified — added VideoService + ApplicationEventPublisher, rewrote dispatchEvent())
- `src/main/java/com/softropic/skillars/platform/video/api/VideoResource.java` (modified — replaced empty shell with initiate endpoint)
- `src/main/java/com/softropic/skillars/platform/video/api/VideoWebhookResource.java` (modified — fix header name, eventId idempotency key, @Transactional + DataIntegrityViolationException catch)
- `src/main/java/com/softropic/skillars/platform/video/config/VideoProperties.java` (modified — added Bunny.webhookSigningSecret)
- `src/main/java/com/softropic/skillars/platform/video/config/VideoProviderConfig.java` (modified — pass sessionTtlSeconds + webhookSigningSecret to adapter constructor)
- `src/main/java/com/softropic/skillars/platform/video/config/VideoConfig.java` (modified — added QuotaConfigService constructor field + @PostConstruct validateTimeoutAlignment())
- `src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java` (modified — added VideoType.DRILL_DEMO + resp.sessionId())
- `src/main/resources/application-dev.yaml` (modified — added app.video.bunny.webhook-signing-secret)
- `src/test/resources/application-test.yaml` (modified — added app.video.bunny.webhook-signing-secret)
- `src/test/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapterTest.java` (modified — 8-arg constructor, webhook payload/HMAC key fix, mapBunnyStatus CSV fix, new getVideoMetadata + Status=8/informational webhook tests)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoUploadInitializationIT.java` (modified — 5-arg InitializeUploadRequest, 3-arg reserve mock, 5-field UploadCredentials, sessionId assertions)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoUploadConfirmationIT.java` (modified — 5-arg UploadCredentials, 3-arg reserve mock, sessionId assertions)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoUploadPipelineIT.java` (created)
- `src/test/java/com/softropic/skillars/platform/session/service/DrillUploadServiceTest.java` (modified — 8-arg InitializeUploadResponse constructors)
- `src/test/java/com/softropic/skillars/platform/video/api/VideoUploadResourceIT.java` (created)
- `src/test/java/com/softropic/skillars/platform/video/api/VideoWebhookResourceIT.java` (created)
- `src/test/java/com/softropic/skillars/platform/video/config/QuotaProviderWiringTest.java` (modified — added QuotaConfigService mock bean + failsAtStartupWhenReservationTimeoutBelowSessionTtl test)
- `src/frontend/src/api/video.api.js` (created)
- `src/frontend/src/stores/video.store.js` (created)
- `src/main/java/com/softropic/skillars/platform/video/service/WebhookEventOutboxService.java` (created — REQUIRES_NEW + flush helper for duplicate-webhook idempotency; implements the patch prescribed in the post-impl review pass; injected into VideoWebhookResource)

### Change Log

- 2026-06-22: Story 6.2 TUS Upload Pipeline fully implemented (claude-sonnet-4-6). All 28 tasks complete across backend Java, frontend JS, configuration, and test layers. Key deliverables: TUS credential generation in BunnyVideoProviderAdapter, VideoWebhookResource hardening (header fix + idempotency + race condition), completeTranscoding/failTranscoding in VideoService, dispatchEvent rewrite in WebhookEventProcessorScheduler (including out-of-order compensation), VideoResource initiate endpoint, video.api.js + video.store.js frontend, and full integration/unit test coverage (VideoUploadPipelineIT, VideoUploadResourceIT, VideoWebhookResourceIT, QuotaProviderWiringTest).
- 2026-06-22: `WebhookEventOutboxService` created as standalone `@Service` (REQUIRES_NEW + explicit flush) to handle the concurrent-duplicate-delivery idempotency fix (post-impl patch). Deviates from patch spec (which prescribed an inner helper on VideoWebhookResource) in favor of a cleaner service-layer separation. Accepted as better-than-spec implementation; File List updated.
- 2026-06-22: Pass-5 review patches applied (claude-sonnet-4-6): (P1) `isBlank()` guard for `webhookSigningSecret` in `BunnyVideoProviderAdapter`; (P2) `setProviderUploadId` added to `VideoUploadPipelineIT` seed helpers; (P3) `VideoUploadedEvent` moved from scheduler `dispatchEvent()` to `completeTranscoding()` Phase 3 — scheduler is now a pure state machine; (P4) `@TestPropertySource` added to `VideoWebhookResourceIT` to suppress background schedulers; (P5) `videoLibraryId` added to `WebhookEvent` record and included in `eventId` for cross-library dedup; (P6) `VideoResource.initiateUpload` wraps `getCoachIdByUserId()` in try-catch for `ResourceNotFoundException` → 403 FORBIDDEN (was dead null-check).

---

_Code review — 2026-06-22, pass 5 (3-layer parallel review: Blind Hunter + Edge Case Hunter + Acceptance Auditor). Story status: done → review findings added. 3 decisions, 5 patches, 1 defer, 9 dismissed._

### Decision-Needed (pass 5)

- [x] [Review][Decision] `WebhookEventOutboxService` is an undocumented new `@Service` not in the spec File List — **Resolved: accepted as better-than-spec; File List and Change Log updated.** — the post-impl patch prescribed a REQUIRES_NEW helper inside `VideoWebhookResource`; the implementation created a separate `@Service` bean (`WebhookEventOutboxService`) that is injected into the resource. Architecturally cleaner but deviates from the spec. **Options**: (A) Accept — the approach is correct and better-structured; update File List and consider adding a test; (B) Refactor — move `tryInsert` logic back as a private helper in `VideoWebhookResource` or an inner service. [`VideoWebhookResource.java`, `WebhookEventOutboxService.java`]
- [x] [Review][Decision] Idempotency key `providerAssetId:eventType` excludes `VideoLibraryId` — **Resolved: fix now (converted to patch below).**
- [x] [Review][Decision] `VideoConfig.@PostConstruct` reads `quotaConfigService.getReservationTimeoutMinutes()` from the DB — **Resolved: accepted — startup-fail-fast is intentional. No change needed.**

### Patches (pass 5)

- [x] [Review][Patch] **CRITICAL** Empty string `webhookSigningSecret` bypasses `Objects.requireNonNull` — `VideoProperties.Bunny.webhookSigningSecret` defaults to `""` when `APP_VIDEO_BUNNY_WEBHOOK_SIGNING_SECRET` is not set. An empty string passes `Objects.requireNonNull`. HMAC-SHA256 with an empty key is trivially bypassable; any actor can forge valid webhook signatures by computing HMAC with an empty key. Add an explicit empty-string guard in `BunnyVideoProviderAdapter` constructor: `if (webhookSigningSecret.isBlank()) throw new IllegalArgumentException("webhookSigningSecret must not be blank...")` [`BunnyVideoProviderAdapter.java:185`, `VideoProperties.java:567`] — **APPLIED 2026-06-22**
- [x] [Review][Patch] **HIGH** `VideoUploadPipelineIT` seed helpers omit `providerUploadId` on `UploadSession` — `completeTranscoding()` and `failTranscoding()` now use `findFirstByVideoIdAndProviderUploadIdOrderByCreatedAtDesc(videoId, providerAssetId)` when `providerAssetId != null`. The video seeds set `providerAssetId`, but `seedUploadSession()` and `seedUploadSessionWithNullHandle()` do not call `session.setProviderUploadId(...)`. The anchored query returns `Optional.empty()`, the reservation handle is never found, `commit()`/`release()` are never called, and `verify(quotaProvider).commit("test-handle-1")` assertions (Tests 1, 3) fail. Fix: call `s.setProviderUploadId("asset-1")` (or the appropriate GUID) in each seed helper. [`VideoUploadPipelineIT.java:seedUploadSession`] — **APPLIED 2026-06-22**
- [x] [Review][Patch] **MEDIUM** `VideoUploadedEvent` publish not moved to `completeTranscoding()` as the post-impl patch prescribed — stale in-memory state in `dispatchEvent()` can cause double-publication in multi-node deployments. When `encoding.success` is processed and the in-memory snapshot shows UPLOADING (stale — another node just committed PROCESSING), the compensation block fires and publishes `VideoUploadedEvent` again, triggering Story 6.3 moderation twice. Fix per patch spec: move `publisher.publishEvent(new VideoUploadedEvent(...))` into `completeTranscoding()` Phase 3 `transactionTemplate.execute()` block (alongside `VideoPublishedEvent`). Remove the publish from both the `upload.success` handler and the compensation block in `dispatchEvent()`. The scheduler becomes a pure state machine; event publication is owned entirely by `completeTranscoding()`. [`WebhookEventProcessorScheduler.java:dispatchEvent`, `VideoService.java:completeTranscoding`] — **APPLIED 2026-06-22**
- [x] [Review][Patch] **MEDIUM** `VideoWebhookResourceIT` missing `@TestPropertySource` to disable background schedulers — `WebhookEventProcessorScheduler` starts with the full `@SpringBootTest` context and can drain webhook events seeded by tests before assertions run, causing flaky test failures. Add the same `@TestPropertySource` properties used in `WebhookPipelineIT`. [`VideoWebhookResourceIT.java`] — **APPLIED 2026-06-22**
- [x] [Review][Patch] **MEDIUM** Idempotency key excludes `VideoLibraryId` — include `videoLibraryId` in `eventId` so key is `libraryId:providerAssetId:eventType`. Requires adding `videoLibraryId` field to `WebhookEvent` record and populating it in `BunnyVideoProviderAdapter.verifyWebhook()` from the parsed `BunnyWebhookPayload.videoLibraryId`. `VideoWebhookResource` then uses `event.videoLibraryId() + ":" + event.providerAssetId() + ":" + event.eventType()`. [`WebhookEvent.java`, `BunnyVideoProviderAdapter.java:verifyWebhook`, `VideoWebhookResource.java`] — **APPLIED 2026-06-22**
- [x] [Review][Patch] **LOW** `VideoResource.getCoachIdByUserId()` null contract unverified — `CoachProfileService.getCoachIdByUserId()` throws `ResourceNotFoundException` (never returns null); the null-check was dead code. Wrapped in try-catch for `ResourceNotFoundException` → 403 FORBIDDEN. [`VideoResource.java`] — **APPLIED 2026-06-22**

### Deferred (pass 5)

- [x] [Review][Defer] `failTranscoding()` state-transition rollback on `quotaProvider.release()` exception — `failTranscoding()` is `@Transactional`; if `QuotaService.release()` throws (e.g., DB connection loss), the entire TX rolls back including `transitionOperationalState(FAILED)`, leaving the video in `PROCESSING`. Scheduler retries recover in the normal case; only fails permanently if max-attempts exhaust during a persistent quota DB outage. Fix requires separating state transition and quota release into independent TXs. [`VideoService.java:failTranscoding`] — deferred, architectural change needed; scheduler retry provides recovery in practice
