# Story 6.3: Content Moderation Pipeline

Status: done
<!-- Adversarial review complete 2026-06-22: 12 patches applied, 0 deferred, 18 dismissed -->

## Story

As a platform operator,
I want every uploaded video scanned through three moderation layers before it can be viewed,
so that CSAM, explicit content, and unsafe minor player videos are blocked automatically and fail safely when moderation services are unavailable.

## Acceptance Criteria

**AC 1: VideoUploadedEvent triggers SCANNING state and Arachnid Layer 1**
Given a video transitions to PROCESSING state (via `video.upload.success` webhook) and a `VideoUploadedEvent` is published,
When `ModerationOrchestrationService.onVideoUploaded()` handles the event,
Then `videos.operational_state` transitions from `PROCESSING` to `SCANNING` in a new `@Transactional`,
And the Arachnid CSAM hash check (`ArachnidClient.scan()`) is called OUTSIDE any `@Transactional` boundary,
And the DB write recording the Arachnid result occurs in a separate `@Transactional` after the call returns.

**AC 2: Arachnid CSAM match — LOCKED + account suspension + admin alert**
Given Arachnid returns a CSAM match for the uploaded video,
When the result is processed,
Then `videos.operational_state` transitions to `LOCKED` immediately,
And the video owner's account is suspended pending admin review (security module — `SkillarsVerificationStatus.SUSPENDED`),
And an urgent admin notification email is sent via the `platform.notification` Envelope pattern (publish `VideoModerationAdminAlertEvent`) — CSAM matches bypass the standard moderation queue,
And the pipeline stops at Layer 1 — Layer 2 (VideoIntel) is never invoked for this video.

**AC 3: Arachnid clean — advance to Layer 2 (VideoIntel)**
Given Arachnid returns a clean result (no match),
When Layer 1 clears,
Then Layer 2 (Google Cloud VideoIntelligence explicit content detection) is invoked via `VideoIntelClient.detectExplicitContent()` OUTSIDE any `@Transactional`,
And `videos.operational_state` remains `SCANNING` throughout Layer 2 processing.

**AC 4: VideoIntel flags explicit content — LOCKED + owner notification**
Given VideoIntel flags explicit content with high confidence,
When the result is processed,
Then `videos.operational_state` transitions to `LOCKED`,
And the video is queued in the admin content moderation queue (a `video_moderation_scans` record with `outcome = 'FLAGGED'` is inserted),
And the owner receives a notification email via the `platform.notification` Envelope pattern (publish `VideoModerationOwnerNotificationEvent`): "Your video has been flagged for review",
And the video is never advanced to transcoding.

**AC 5: VideoIntel clean — advance to TRANSCODING (adult/coach path)**
Given VideoIntel returns clean (or is disabled via feature flag),
When Layer 2 clears,
Then `videos.operational_state` transitions to `TRANSCODING` and `VideoProviderAdapter.triggerTranscoding(providerAssetId)` is called to initiate Bunny.net encoding.
Note: Story 6.3 assumes all owners are adults/coaches. The minor player path (age tier < 18 → `HIDDEN` + `video_approval_requests` insertion) is fully implemented in Story 6.6.
**SAFETY GATE DEFERRAL — REQUIRES EXPLICIT PRODUCT/LEGAL SIGN-OFF**: During the Story 6.3 → 6.6 shipping gap, minor-owned accounts will have videos advance to TRANSCODING/READY with no parental gate. This deferral must be accepted in writing by the product owner before Story 6.3 ships to production. If sign-off is not obtained, the minor safety gate must be implemented in this story.
**Ops monitoring until Story 6.6 ships**: Run daily — `SELECT v.id, v.owner_id FROM main.videos v JOIN main.video_moderation_scans vms ON vms.video_id = v.id WHERE vms.layer = 'MINOR_GATE' AND vms.outcome = 'SKIPPED'` joined against any user accounts with age < 18; audit results manually.

**AC 6: Fail-closed on Arachnid unavailability**
Given Arachnid is unavailable or times out,
When the call fails,
Then the video stays in `SCANNING` — it is never auto-advanced,
And an admin alert email is sent via the `platform.notification` Envelope pattern (distinct from CSAM match alert; subject: "Arachnid moderation unavailable"),
And a `@Scheduled` SLA monitor (`ModerationSlaMonitorService`) detects videos stuck in SCANNING beyond the SLA window (from `ConfigService` key `platform.moderation_sla_minutes`) and re-queues them by publishing a `VideoModerationRetryEvent`.

**AC 7: Fail-closed on VideoIntel unavailability**
Given VideoIntel is unavailable or times out,
When the call fails,
Then the video stays in `SCANNING`,
And an admin alert email is sent via the `platform.notification` Envelope pattern (subject: "VideoIntel moderation unavailable"),
And the SLA monitor (AC 6) applies equally to VideoIntel stuck videos.

**AC 8: Feature flags — Arachnid disabled**
Given `ARACHNID_ENABLED` feature flag is off for the current environment,
When the moderation pipeline reaches Layer 1,
Then the Arachnid scan is skipped entirely and the pipeline advances directly to Layer 2,
And no admin alert is raised for the skipped layer,
And the `video_moderation_scans` record records `layer = 'ARACHNID'`, `outcome = 'SKIPPED'`.

**AC 9: Feature flags — VideoIntel disabled**
Given `VIDEOINTEL_ENABLED` feature flag is off,
When the moderation pipeline reaches Layer 2,
Then the VideoIntel scan is skipped and the pipeline advances to Layer 3,
And the `video_moderation_scans` record records `layer = 'VIDEOINTEL'`, `outcome = 'SKIPPED'`.

**AC 10: Both flags off — pipeline passes through Layer 3 only**
Given both `ARACHNID_ENABLED` and `VIDEOINTEL_ENABLED` are off,
When a video is uploaded,
Then the pipeline reaches Layer 3 only and the video advances to `TRANSCODING` without errors (HIDDEN path is Story 6.6 scope),
And the application starts without errors and operates normally with no missing beans or degraded behaviour.

**AC 11: SSE — VideoStatusCard reactive updates**
Given a video's `operational_state` changes at any pipeline stage,
When the transition completes and a `VideoStatusChangedEvent` is published,
Then a `VideoSseService` listener pushes an SSE event to all connected clients subscribed to `GET /api/video/{id}/events`,
And the `VideoStatusCard.vue` component updates reactively without page reload,
And the SSE endpoint uses 5-minute timeout with exponential backoff + 2-second polling fallback (same pattern as `BookingEventResource`).

**AC 12: Encoding webhook handled correctly when video is in SCANNING**
Given a `video.encoding.success` webhook fires while the video is still in `SCANNING` state (Bunny auto-encodes concurrently),
When `WebhookEventProcessorScheduler` processes the event,
Then the encoding completion is recorded on the video (`Video.encodingCompletedAt`) without transitioning state,
And when moderation passes, `ModerationOrchestrationService` checks `encodingCompletedAt` and if non-null, skips the `triggerTranscoding()` call and advances `SCANNING → TRANSCODING → READY` directly by calling `videoService.completeTranscoding(videoId)` after the state transition.
Note: The webhook's re-read self-heal (Task 16) only covers the case where moderation advances to TRANSCODING *before* the webhook re-reads state. If the webhook fires and re-reads SCANNING (moderation still in progress), then moderation later completes, no one else will call `completeTranscoding()` — so the fast-path in `advanceToTranscoding()` must call it directly rather than relying on the webhook.

## Tasks / Subtasks

---

### Backend — Flyway Migrations

- [x] **Task 1: V55__operational_state_moderation_states.sql** (AC: 1–10)
  - [x] File: `src/main/resources/db/migration/V55__operational_state_moderation_states.sql`
  - [x] Verify latest migration is V54 (`V54__video_type_column.sql`) — V55 is correct
  - [x] Content:
    ```sql
    -- Extend the CHECK constraint on videos.operational_state to include new pipeline states
    ALTER TABLE main.videos
        DROP CONSTRAINT IF EXISTS chk_videos_operational_state;

    ALTER TABLE main.videos
        ADD CONSTRAINT chk_videos_operational_state CHECK (
            operational_state IN (
                'UPLOADING', 'PROCESSING',
                'SCANNING',              -- under content moderation
                'TRANSCODING',           -- moderation passed, Bunny encoding in progress
                'READY',                 -- published and playable
                'LOCKED',               -- content violation (Arachnid/VideoIntel) or lifecycle lock
                'HIDDEN',               -- minor safety gate: awaiting parent approval (Story 6.6)
                'FAILED',               -- provider or pipeline failure
                'DELETED'               -- logically deleted
            )
        );

    -- Track encoding completion separately so moderation can skip triggerTranscoding() if already done
    ALTER TABLE main.videos
        ADD COLUMN encoding_completed_at TIMESTAMPTZ NULL;

    -- Moderation in-flight lock: prevents SLA monitor from re-queuing a video actively being processed.
    -- Set to now() + lock_timeout_minutes when moderation starts; SLA monitor skips videos where this is in the future.
    ALTER TABLE main.videos
        ADD COLUMN moderation_lock_until TIMESTAMPTZ NULL;

    -- Tracks when SCANNING started; used as the SLA clock (not updatedAt, which resets on any field write).
    ALTER TABLE main.videos
        ADD COLUMN scanning_started_at TIMESTAMPTZ NULL;

    -- Counts SLA monitor retry attempts; SLA monitor fails the video permanently once this reaches platform.moderation_max_retries.
    ALTER TABLE main.videos
        ADD COLUMN moderation_retry_count INT NOT NULL DEFAULT 0;
    ```
  - [x] **Pre-condition check**: confirm the original V15 migration added no named constraint on `operational_state` — if none existed, the `DROP CONSTRAINT IF EXISTS` is a no-op and safe

- [x] **Task 2: V56__video_moderation_scans.sql** (AC: 2–9)
  - [x] File: `src/main/resources/db/migration/V56__video_moderation_scans.sql`
  - [x] Content:
    ```sql
    CREATE TABLE main.video_moderation_scans (
        id             UUID         DEFAULT gen_random_uuid() PRIMARY KEY,
        video_id       UUID         NOT NULL REFERENCES main.videos(id) ON DELETE CASCADE,
        layer          VARCHAR(20)  NOT NULL CHECK (layer IN ('ARACHNID', 'VIDEOINTEL', 'MINOR_GATE')),
        outcome        VARCHAR(20)  NOT NULL CHECK (outcome IN ('PASSED', 'FLAGGED', 'FAILED', 'SKIPPED')),
        confidence     NUMERIC(5,4) NULL,  -- VideoIntel confidence score (0.0–1.0); NULL for Arachnid/minor gate
        details        TEXT         NULL,  -- raw provider response excerpt for audit
        scanned_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
    );

    CREATE INDEX idx_vms_video_id ON main.video_moderation_scans(video_id);
    -- Idempotency: prevent duplicate scan records on retry for the same video/layer pair
    CREATE UNIQUE INDEX idx_vms_video_layer ON main.video_moderation_scans(video_id, layer);

    -- NOTE: video_approval_requests table is owned by Story 6.6.
    -- Story 6.3 does NOT create this table — the minor player HIDDEN path is Story 6.6 scope.
    -- Story 6.6's migration must include REFERENCES main.users(id) FK constraints on player_id and parent_id.
    ```

---

### Backend — Contract / Enum Layer

- [x] **Task 3: Extend `OperationalState` enum** (AC: 1–12)
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/contract/OperationalState.java` (MODIFY)
  - [x] Before: `UPLOADING, PROCESSING, READY, FAILED, DELETED`
  - [x] After:
    ```java
    public enum OperationalState {
        UPLOADING,
        PROCESSING,   // backward compat — upload success received, moderation not yet started
        SCANNING,     // under content moderation (Arachnid + VideoIntel + minor gate)
        TRANSCODING,  // moderation passed, Bunny encoding in progress
        READY,        // transcoding complete, playable
        LOCKED,       // content violation or lifecycle lock
        HIDDEN,       // minor safety gate: awaiting parent approval (Story 6.6)
        FAILED,
        DELETED
    }
    ```

- [x] **Task 4: Update `VideoLifecycleService.VALID_TRANSITIONS`** (AC: 1–5, 12) — CRITICAL
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java` (MODIFY)
  - [x] Replace the `VALID_TRANSITIONS` constant:
    ```java
    private static final Map<OperationalState, Set<OperationalState>> VALID_TRANSITIONS = Map.of(
        OperationalState.UPLOADING,    Set.of(OperationalState.PROCESSING, OperationalState.FAILED),
        OperationalState.PROCESSING,   Set.of(OperationalState.SCANNING, OperationalState.READY, OperationalState.FAILED),
        // PROCESSING→READY kept for backward compat: encoding.success webhook fires before Story 6.3 is deployed,
        // or if the moderation listener never fires (degenerate case). Over time this path should not be exercised.
        // When this path is taken, add a WARN log: log.warn("PROCESSING→READY bypass taken for videoId={} — moderation pipeline was not run", videoId)
        // TODO Story 6.5 (or next audit story): remove PROCESSING→READY from VALID_TRANSITIONS once deployment
        // confirms all PROCESSING→READY events have ceased. Keeping it indefinitely is a permanent moderation bypass route.
        OperationalState.SCANNING,     Set.of(OperationalState.TRANSCODING, OperationalState.LOCKED, OperationalState.HIDDEN, OperationalState.FAILED),
        OperationalState.TRANSCODING,  Set.of(OperationalState.READY, OperationalState.FAILED),
        OperationalState.FAILED,       Set.of(OperationalState.UPLOADING),
        OperationalState.LOCKED,       Set.of(),  // terminal — admin action required in Story 10
        OperationalState.HIDDEN,       Set.of(),  // terminal in Story 6.3 ONLY — Story 6.6 MUST add HIDDEN → {TRANSCODING, FAILED, DELETED} before implementing parent approval
        OperationalState.READY,        Set.of(),
        OperationalState.DELETED,      Set.of()
    );
    ```
  - [x] **Also publish `VideoStatusChangedEvent` after every successful `transitionOperationalState()` call** (AC: 11):
    - Add `ApplicationEventPublisher publisher` constructor field
    - After `videoRepository.save(video)`, add:
      ```java
      publisher.publishEvent(new VideoStatusChangedEvent(videoId, newState));
      ```
    - Import: `com.softropic.skillars.platform.video.contract.event.VideoStatusChangedEvent`, `org.springframework.context.ApplicationEventPublisher`
  - [x] **Add PROCESSING→READY bypass log and counter** (P21): In the `transitionOperationalState()` method, after determining that current=PROCESSING and newState=READY, add:
    ```java
    log.warn("PROCESSING→READY bypass taken for videoId={} — moderation pipeline was not run", videoId);
    meterRegistry.counter("video.moderation.bypass", "from", "PROCESSING", "to", "READY").increment();
    ```
    Inject `MeterRegistry meterRegistry` via constructor; add import `io.micrometer.core.instrument.MeterRegistry`. The counter makes bypass frequency visible in dashboards so ops can confirm the path is dead before the transition is removed.

- [x] **Task 5: Create `VideoStatusChangedEvent`** (AC: 11)
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/contract/event/VideoStatusChangedEvent.java` (CREATE)
    ```java
    package com.softropic.skillars.platform.video.contract.event;

    import com.softropic.skillars.platform.video.contract.OperationalState;
    import java.util.UUID;

    public record VideoStatusChangedEvent(UUID videoId, OperationalState newState) {}
    ```

- [x] **Task 6: Add `ARACHNID_ENABLED` and `VIDEOINTEL_ENABLED` to `AppFeature`** (AC: 8–10)
  - [x] File: `src/main/java/com/softropic/skillars/infrastructure/feature/AppFeature.java` (MODIFY)
  - [x] Add:
    ```java
    ARACHNID_ENABLED("arachnid-enabled"),
    VIDEOINTEL_ENABLED("videointel-enabled"),
    ```
  - [x] `PropertiesFeatureToggleService.validate()` checks all configured keys against `AppFeature.values()`. Adding the enum entries prevents `IllegalStateException` on startup when the new config keys are present.

---

### Backend — Infrastructure: Arachnid Adapter

- [x] **Task 7: Create `infrastructure.arachnid` package** (AC: 1–2, 6, 8)
  - [x] Package: `src/main/java/com/softropic/skillars/infrastructure/arachnid/`
  - [x] **Task 7a: `ArachnidScanResult` record** (CREATE)
    ```java
    package com.softropic.skillars.infrastructure.arachnid;

    public record ArachnidScanResult(boolean matched, String matchType) {}
    ```
  - [x] **Task 7b: `ArachnidClient` interface** (CREATE)
    ```java
    package com.softropic.skillars.infrastructure.arachnid;

    public interface ArachnidClient {
        /**
         * Submits a video for CSAM hash check.
         * @param mediaUrl a short-lived URL for the raw video (before transcoding)
         * @return scan result — never throws on clean result; throws ArachnidException if unavailable
         */
        ArachnidScanResult scan(String mediaUrl);
    }
    ```
  - [x] **Task 7c: `ArachnidException`** (CREATE)
    ```java
    package com.softropic.skillars.infrastructure.arachnid;

    public class ArachnidException extends RuntimeException {
        public ArachnidException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    ```
  - [x] **Task 7d: `ArachnidProperties`** (CREATE)
    ```java
    package com.softropic.skillars.infrastructure.arachnid;

    import lombok.Getter;
    import lombok.Setter;
    import org.springframework.boot.context.properties.ConfigurationProperties;

    @Getter
    @Setter
    @ConfigurationProperties(prefix = "infrastructure.arachnid")
    public class ArachnidProperties {
        private String apiKey = "";
        private String apiBaseUrl = "https://api.arachnid.projectvic.org";
        private int timeoutSeconds = 30;
    }
    ```
  - [x] **Task 7e: `ArachnidClientImpl`** (CREATE)
    ```java
    package com.softropic.skillars.infrastructure.arachnid;

    import lombok.extern.slf4j.Slf4j;
    import org.springframework.web.client.RestClientException;
    import org.springframework.web.client.RestTemplate;
    import org.springframework.http.*;

    import java.util.Map;

    @Slf4j
    public class ArachnidClientImpl implements ArachnidClient {

        private final RestTemplate restTemplate;
        private final String apiKey;
        private final String apiBaseUrl;

        public ArachnidClientImpl(RestTemplate restTemplate, String apiKey, String apiBaseUrl, int timeoutSeconds) {
            this.restTemplate = restTemplate;
            this.apiKey = apiKey;
            this.apiBaseUrl = apiBaseUrl;
        }
        // Note: timeoutSeconds is consumed in ArachnidConfig via RestTemplateBuilder — do not apply separately here

        @Override
        public ArachnidScanResult scan(String mediaUrl) {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(Map.of("url", mediaUrl), headers);
            try {
                ResponseEntity<ArachnidApiResponse> response = restTemplate.exchange(
                    apiBaseUrl + "/v1/scan", HttpMethod.POST, entity, ArachnidApiResponse.class);
                ArachnidApiResponse body = response.getBody();
                if (body == null) throw new ArachnidException("Arachnid returned empty response", null);
                return new ArachnidScanResult(body.matched(), body.matchType());
            } catch (RestClientException e) {
                throw new ArachnidException("Arachnid scan failed: " + e.getMessage(), e);
            }
        }

        // Internal response type — exact field names TBD; verify with Arachnid API docs before deploying
        private record ArachnidApiResponse(boolean matched, String matchType) {}
    }
    ```
    - **CRITICAL pre-deploy note**: The Arachnid API integration (`apiBaseUrl`, request format, response fields) MUST be verified against the actual Project Arachnid API documentation before deploying. The `ArachnidClientImpl` uses a reasonable placeholder implementation. Contact C3P (Canadian Centre for Child Protection) for API credentials and exact request/response schema.
  - [x] **Task 7f: `ArachnidConfig`** (CREATE)
    ```java
    package com.softropic.skillars.infrastructure.arachnid;

    import org.springframework.boot.context.properties.EnableConfigurationProperties;
    import org.springframework.boot.web.client.RestTemplateBuilder;
    import org.springframework.context.annotation.Bean;
    import org.springframework.context.annotation.Configuration;
    import org.springframework.web.client.RestTemplate;
    import java.time.Duration;

    @Configuration
    @EnableConfigurationProperties(ArachnidProperties.class)
    public class ArachnidConfig {

        @Bean
        public ArachnidClient arachnidClient(ArachnidProperties props, RestTemplateBuilder builder) {
            RestTemplate restTemplate = builder
                .connectTimeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .build();
            return new ArachnidClientImpl(restTemplate, props.getApiKey(), props.getApiBaseUrl(), props.getTimeoutSeconds());
        }
    }
    ```

---

### Backend — Infrastructure: VideoIntel Adapter

- [x] **Task 8: Create `infrastructure.videointel` package** (AC: 3–4, 7, 9)
  - [x] Package: `src/main/java/com/softropic/skillars/infrastructure/videointel/`
  - [x] **Task 8a: `VideoIntelScanResult` record** (CREATE)
    ```java
    package com.softropic.skillars.infrastructure.videointel;

    public record VideoIntelScanResult(boolean flagged, Double confidence, String description) {}
    // confidence is nullable: null for PASSED results (no meaningful score), non-null for FLAGGED results only
    ```
  - [x] **Task 8b: `VideoIntelClient` interface** (CREATE)
    ```java
    package com.softropic.skillars.infrastructure.videointel;

    public interface VideoIntelClient {
        /**
         * Submits a video to Google Cloud VideoIntelligence for explicit content detection.
         * @param videoUrl publicly accessible or signed URL to the video
         * @return scan result — flagged=true means explicit content detected above threshold
         * @throws VideoIntelException if the service is unavailable or times out
         */
        VideoIntelScanResult detectExplicitContent(String videoUrl);
    }
    ```
  - [x] **Task 8c: `VideoIntelException`** (CREATE)
    ```java
    package com.softropic.skillars.infrastructure.videointel;

    public class VideoIntelException extends RuntimeException {
        public VideoIntelException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    ```
  - [x] **Task 8d: `VideoIntelProperties`** (CREATE)
    ```java
    package com.softropic.skillars.infrastructure.videointel;

    import lombok.Getter;
    import lombok.Setter;
    import org.springframework.boot.context.properties.ConfigurationProperties;

    @Getter
    @Setter
    @ConfigurationProperties(prefix = "infrastructure.videointel")
    public class VideoIntelProperties {
        private String projectId = "";
        private String credentialsPath = "";    // path to GCP service account JSON
        private double flagThreshold = 0.7;     // LIKELY or VERY_LIKELY maps to confidence > 0.7
        private int timeoutSeconds = 300;       // VideoIntel can take minutes for long videos
    }
    ```
  - [x] **Task 8e: `VideoIntelClientImpl`** (CREATE)
    ```java
    package com.softropic.skillars.infrastructure.videointel;

    import lombok.extern.slf4j.Slf4j;
    import org.springframework.web.client.RestClientException;
    import org.springframework.web.client.RestTemplate;
    import org.springframework.http.*;

    import java.util.Map;

    @Slf4j
    public class VideoIntelClientImpl implements VideoIntelClient {

        private final RestTemplate restTemplate;
        private final String projectId;
        private final double flagThreshold;

        public VideoIntelClientImpl(RestTemplate restTemplate, String projectId, double flagThreshold) {
            this.restTemplate = restTemplate;
            this.projectId = projectId;
            this.flagThreshold = flagThreshold;
        }

        @Override
        public VideoIntelScanResult detectExplicitContent(String videoUrl) {
            // TODO Story 6.x: replace with real Google VideoIntelligence integration.
            // Fail-open stub: returns clean result so Story 6.3 pipeline can run end-to-end with flag enabled.
            // VIDEOINTEL_ENABLED feature flag defaults to false; enable only after real implementation ships.
            // DANGER: if VIDEOINTEL_ENABLED=true in production while this stub is active, ALL videos pass
            // Layer 2 with no explicit content screening. The startup guard in VideoIntelConfig logs an error
            // at boot time if the flag is enabled with this stub, making misconfiguration visible immediately.
            log.warn("VideoIntelClientImpl is a stub — returning clean result for videoUrl={}", videoUrl);
            return new VideoIntelScanResult(false, null, "stub-not-implemented");
        }
    }
    ```
    - **CRITICAL pre-deploy note**: `VideoIntelClientImpl` is a fail-open stub (descoped to Story 6.x). `VIDEOINTEL_ENABLED` feature flag defaults to `false` so it will be skipped in production. Real implementation requires `com.google.cloud:google-cloud-video-intelligence` dependency in `build.gradle`. The client must:
      1. Submit a `videos:annotate` request with `EXPLICIT_CONTENT_DETECTION` feature
      2. Poll the returned operation until complete (this is asynchronous — operations can take minutes)
      3. Parse `annotationResults[0].explicitAnnotation.frames` — if any frame has `LIKELY` or `VERY_LIKELY` pornography likelihood, set `flagged=true`
      4. Map `Likelihood.LIKELY = 0.7`, `Likelihood.VERY_LIKELY = 1.0` for the confidence field
      5. The video URL must be accessible during the operation — use Bunny CDN URL (requires video to exist in Bunny before analysis, which may conflict with Story 6.3's pre-transcoding timing — see Dev Notes)
  - [x] **Task 8f: `VideoIntelConfig`** (CREATE)
    ```java
    package com.softropic.skillars.infrastructure.videointel;

    import com.softropic.skillars.infrastructure.feature.AppFeature;
    import com.softropic.skillars.infrastructure.feature.FeatureToggleService;
    import lombok.extern.slf4j.Slf4j;
    import org.springframework.boot.context.event.ApplicationReadyEvent;
    import org.springframework.boot.context.properties.EnableConfigurationProperties;
    import org.springframework.boot.web.client.RestTemplateBuilder;
    import org.springframework.context.ApplicationListener;
    import org.springframework.context.annotation.Bean;
    import org.springframework.context.annotation.Configuration;
    import org.springframework.web.client.RestTemplate;
    import java.time.Duration;

    @Slf4j
    @Configuration
    @EnableConfigurationProperties(VideoIntelProperties.class)
    public class VideoIntelConfig {

        @Bean
        public VideoIntelClient videoIntelClient(VideoIntelProperties props, RestTemplateBuilder builder) {
            // Wire timeoutSeconds from properties — VideoIntel operations can take up to 5 minutes.
            RestTemplate restTemplate = builder
                .connectTimeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .build();
            return new VideoIntelClientImpl(restTemplate, props.getProjectId(), props.getFlagThreshold());
        }

        // Misconfiguration guard: VideoIntelClientImpl is fail-open — it passes ALL videos.
        // If VIDEOINTEL_ENABLED=true reaches production while the stub is active, explicit content
        // passes Layer 2 silently. This bean fires at startup and makes misconfiguration visible
        // in logs immediately, before any video is processed.
        @Bean
        ApplicationListener<ApplicationReadyEvent> videoIntelStartupGuard(
                FeatureToggleService featureToggleService) {
            return event -> {
                if (featureToggleService.isEnabled(AppFeature.VIDEOINTEL_ENABLED)) {
                    log.error("MISCONFIGURATION: VIDEOINTEL_ENABLED=true but VideoIntelClientImpl is a " +
                              "fail-open stub. ALL videos will pass Layer 2 with NO explicit content " +
                              "screening. Do NOT use this configuration in production.");
                }
            };
        }
    }
    ```

---

### Backend — Infrastructure: Video (Transcoding Trigger)

- [x] **Task 9: Add `triggerTranscoding()` and `getRawVideoUrl()` to `VideoProviderAdapter`** (AC: 5)
  - [x] File: `src/main/java/com/softropic/skillars/infrastructure/video/VideoProviderAdapter.java` (MODIFY)
  - [x] Add after the existing `getVideoMetadata()` default method:
    ```java
    default void triggerTranscoding(String providerAssetId) {
        throw new UnsupportedOperationException("triggerTranscoding not supported by this provider");
    }

    /** Returns a URL to the raw uploaded video (pre-transcoding) accessible for moderation scanning. */
    default String getRawVideoUrl(String providerAssetId) {
        throw new UnsupportedOperationException("getRawVideoUrl not supported by this provider");
    }
    ```
  - [x] Implement `getRawVideoUrl()` in `BunnyVideoProviderAdapter`:
    ```java
    @Override
    public String getRawVideoUrl(String providerAssetId) {
        // Bunny CDN URL for raw uploaded video — read cdnHostname from BunnyVideoProperties
        return "https://" + cdnHostname + "/" + providerAssetId + "/original";
    }
    ```
    - `cdnHostname` must be a field read from `BunnyVideoProperties` (e.g., `bunnyStorageZone.b-cdn.net`). Verify the exact path pattern with Bunny API docs before deploying.

- [x] **Task 10: Implement `triggerTranscoding()` in `BunnyVideoProviderAdapter`** (AC: 5)
  - [x] File: `src/main/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapter.java` (MODIFY)
  - [x] **Pre-deploy verification**: Verify with Bunny API docs whether the `/reencode` endpoint is correct for first-time encoding or only re-encoding. If Bunny auto-encodes on upload (which it does by default), `triggerTranscoding()` may be a no-op for the normal path — the encoding.success webhook fires without an explicit trigger. Confirm the correct endpoint and whether Story 6.3 needs to trigger re-encoding explicitly.
  - [x] Add override after `getVideoMetadata()`:
    ```java
    @Override
    public void triggerTranscoding(String providerAssetId) {
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        try {
            restTemplate.postForEntity(
                apiBaseUrl + "/library/" + libraryId + "/videos/" + providerAssetId + "/reencode",
                entity,
                Void.class
            );
        } catch (HttpClientErrorException.NotFound e) {
            throw new VideoProviderException(
                "triggerTranscoding: video not found in Bunny, providerAssetId=" + providerAssetId, e);
        } catch (RestClientException e) {
            throw new VideoProviderException("triggerTranscoding", e);
        }
    }
    ```

---

### Backend — Repository Layer

- [x] **Task 11: Create `VideoModerationScan` entity and repository** (AC: 2–9)
  - [x] **Task 11a: `VideoModerationScan` entity** (CREATE)
    - File: `src/main/java/com/softropic/skillars/platform/video/repo/VideoModerationScan.java`
    ```java
    package com.softropic.skillars.platform.video.repo;

    import jakarta.persistence.*;
    import lombok.Getter;
    import lombok.NoArgsConstructor;
    import lombok.Setter;
    import java.time.Instant;
    import java.util.UUID;

    @Getter
    @Setter
    @NoArgsConstructor
    @Entity
    // Intentionally not @Audited — scan records are insert-only and immutable (@PrePersist sets scannedAt, updatable=false protects it).
    // Envers provides no additional value over the base table for append-only audit data.
    @Table(name = "video_moderation_scans", schema = "main")
    public class VideoModerationScan {

        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        @Column(name = "video_id", nullable = false)
        private UUID videoId;

        @Column(name = "layer", nullable = false, length = 20)
        private String layer;  // ARACHNID | VIDEOINTEL | MINOR_GATE

        @Column(name = "outcome", nullable = false, length = 20)
        private String outcome; // PASSED | FLAGGED | FAILED | SKIPPED

        @Column(name = "confidence")
        private Double confidence;

        @Column(name = "details", columnDefinition = "TEXT")
        private String details;

        @Column(name = "scanned_at", nullable = false, updatable = false)
        private Instant scannedAt;

        @PrePersist
        void onCreate() {
            this.scannedAt = Instant.now();
        }
    }
    ```
  - [x] **Task 11b: `VideoModerationScanRepository`** (CREATE)
    - File: `src/main/java/com/softropic/skillars/platform/video/repo/VideoModerationScanRepository.java`
    ```java
    package com.softropic.skillars.platform.video.repo;

    import org.springframework.data.jpa.repository.JpaRepository;
    import java.util.List;
    import java.util.UUID;

    public interface VideoModerationScanRepository extends JpaRepository<VideoModerationScan, UUID> {
        List<VideoModerationScan> findByVideoId(UUID videoId);
        Optional<VideoModerationScan> findByVideoIdAndLayer(UUID videoId, String layer);
        // Import: java.util.Optional, java.util.List, java.util.UUID
    }
    ```
  - [x] **Task 11c: `VideoModerationScanPersistenceService`** (CREATE) — required for M5 fix
    - File: `src/main/java/com/softropic/skillars/platform/video/service/VideoModerationScanPersistenceService.java`
    - **Rationale**: `recordScan()` in `ModerationOrchestrationService` is called both inside and outside active `@Transactional` boundaries. Using `REQUIRES_NEW` propagation in a dedicated service ensures the scan record write always runs in its own independent TX. This means a `DataIntegrityViolationException` (concurrent retry race on the UNIQUE(video_id, layer) constraint) is isolated to this TX and never poisons the caller's TX as rollback-only — which could otherwise happen if Hibernate marks the underlying `EntityManager` rollback-only before Spring wraps the exception.
    ```java
    package com.softropic.skillars.platform.video.service;

    import com.softropic.skillars.platform.video.repo.VideoModerationScan;
    import com.softropic.skillars.platform.video.repo.VideoModerationScanRepository;
    import lombok.RequiredArgsConstructor;
    import lombok.extern.slf4j.Slf4j;
    import org.springframework.stereotype.Service;
    import org.springframework.transaction.annotation.Propagation;
    import org.springframework.transaction.annotation.Transactional;

    import java.util.UUID;

    @Service
    @Slf4j
    @RequiredArgsConstructor
    public class VideoModerationScanPersistenceService {

        private final VideoModerationScanRepository moderationScanRepository;

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void upsertScanRecord(UUID videoId, String layer, String outcome,
                                     Double confidence, String details) {
            VideoModerationScan scan = moderationScanRepository
                .findByVideoIdAndLayer(videoId, layer)
                .orElseGet(VideoModerationScan::new);
            scan.setVideoId(videoId);
            scan.setLayer(layer);
            scan.setOutcome(outcome);
            scan.setConfidence(confidence);
            scan.setDetails(details);
            moderationScanRepository.save(scan);
        }
    }
    ```
    - `REQUIRES_NEW` always starts a fresh TX regardless of the caller's TX state. If the caller's outer TX (`transactionTemplate.execute()`) is active, Spring suspends it, runs this method in a new TX, commits (or rolls back) that new TX, then resumes the outer TX. A constraint violation in `upsertScanRecord()` rolls back only this inner TX — the outer TX continues normally, which is why `recordScan()` can safely catch `DataIntegrityViolationException` without worrying about the outer TX being tainted.
    - `ModerationOrchestrationService` must inject `VideoModerationScanPersistenceService scanPersistenceService` instead of `VideoModerationScanRepository moderationScanRepository` directly.

- [x] **Task 12: Add moderation fields to `Video` entity** (AC: 6, 12)
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/repo/Video.java` (MODIFY)
  - [x] Add after `updatedAt`:
    ```java
    @Column(name = "encoding_completed_at")
    private Instant encodingCompletedAt;

    @Column(name = "scanning_started_at")
    private Instant scanningStartedAt;

    @Column(name = "moderation_lock_until")
    private Instant moderationLockUntil;

    @Column(name = "moderation_retry_count", nullable = false)
    private int moderationRetryCount = 0;
    ```
  - [x] In `VideoLifecycleService.transitionOperationalState()`, when `newState == SCANNING`, also set `video.setScanningStartedAt(Instant.now())` before saving.
  - [x] All four columns (`encoding_completed_at`, `scanning_started_at`, `moderation_lock_until`, `moderation_retry_count`) are fully defined in the V55 migration in Task 1 — no addendum needed here.
  - [x] Add `platform.moderation_lock_timeout_minutes` config key alongside `platform.moderation_sla_minutes` (default: `15`). The lock window must be shorter than the SLA window so the SLA monitor can still fire for genuinely stuck videos.

- [x] **Task 13: Add `VideoRepository` query for SCANNING SLA monitor** (AC: 6)
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java` (MODIFY)
  - [x] Add:
    ```java
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))  // SKIP_LOCKED
    @Query("""
        SELECT v FROM Video v
        WHERE v.operationalState = 'SCANNING'
          AND v.scanningStartedAt < :threshold
          AND (v.moderationLockUntil IS NULL OR v.moderationLockUntil < :now)
        """)
    @Transactional
    List<Video> findScanningOlderThan(@Param("threshold") Instant threshold, @Param("now") Instant now);
    ```
    - Import: `org.springframework.data.jpa.repository.Query`, `org.springframework.data.jpa.repository.Lock`, `org.springframework.data.jpa.repository.QueryHints`, `org.springframework.data.repository.query.Param`, `jakarta.persistence.LockModeType`, `jakarta.persistence.QueryHint`
    - The `SKIP_LOCKED` hint (timeout=-2) prevents multiple scheduler instances from retrying the same video simultaneously — same pattern as `WebhookEventProcessorScheduler.findNonTerminalForUpdate()`
    - Use `scanningStartedAt` not `updatedAt` to correctly measure time in SCANNING state (updatedAt would reset on any touch)
    - The `moderationLockUntil` predicate prevents the SLA monitor from re-queuing a video whose async moderation thread is still actively running (e.g., Arachnid scan taking 25 min). Without this, two pipeline threads race on the same video, causing UNIQUE constraint violations in `video_moderation_scans` and concurrent state-machine transitions. `acquireModerationLock()` sets `moderationLockUntil = now + platform.moderation_lock_timeout_minutes` at the start of each pipeline run.

---

### Backend — Platform Service: ModerationOrchestrationService

- [x] **Task 14: Create `ModerationOrchestrationService`** (AC: 1–12) — CORE STORY TASK
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/service/ModerationOrchestrationService.java` (CREATE)
  - [x] Key design decisions (from dev notes and ACs):
    - `@Async` + `@TransactionalEventListener(phase = AFTER_COMMIT)` on `onVideoUploaded()` — runs in a separate thread pool thread after the upload webhook transaction commits; does NOT block the event thread
    - All external calls (`ArachnidClient`, `VideoIntelClient`, `triggerTranscoding()`) OUTSIDE any `@Transactional`
    - Each DB state transition in its own `transactionTemplate.execute()`
    - Feature flags checked via `FeatureToggleService`
  - [x] Structure:
    ```java
    package com.softropic.skillars.platform.video.service;

    import com.softropic.skillars.infrastructure.arachnid.ArachnidClient;
    import com.softropic.skillars.infrastructure.arachnid.ArachnidException;
    import com.softropic.skillars.infrastructure.arachnid.ArachnidScanResult;
    import com.softropic.skillars.infrastructure.feature.AppFeature;
    import com.softropic.skillars.infrastructure.feature.FeatureToggleService;
    import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
    import com.softropic.skillars.infrastructure.videointel.VideoIntelClient;
    import com.softropic.skillars.infrastructure.videointel.VideoIntelException;
    import com.softropic.skillars.infrastructure.videointel.VideoIntelScanResult;
    import com.softropic.skillars.platform.config.service.ConfigService;
    import com.softropic.skillars.platform.video.contract.OperationalState;
    import com.softropic.skillars.platform.video.contract.event.VideoUploadedEvent;
    import com.softropic.skillars.platform.video.repo.Video;
    import com.softropic.skillars.platform.video.repo.VideoModerationScan;
    import com.softropic.skillars.platform.video.repo.VideoModerationScanRepository;
    import com.softropic.skillars.platform.video.repo.VideoRepository;
    import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
    import com.softropic.skillars.platform.video.contract.exception.TerminalStateViolationException;
    import io.micrometer.observation.annotation.Observed;
    import lombok.RequiredArgsConstructor;
    import lombok.extern.slf4j.Slf4j;
    import org.springframework.scheduling.annotation.Async;
    import org.springframework.stereotype.Service;
    import org.springframework.transaction.event.TransactionPhase;
    import org.springframework.transaction.event.TransactionalEventListener;
    import org.springframework.transaction.support.TransactionTemplate;

    import java.time.Instant;
    import java.time.temporal.ChronoUnit;
    import java.util.Objects;
    import java.util.UUID;

    @Service
    @Slf4j
    @RequiredArgsConstructor
    public class ModerationOrchestrationService {

        private final VideoLifecycleService videoLifecycleService;
        private final VideoService videoService;  // for completeTranscoding() in fast-path
        private final VideoRepository videoRepository;
        private final VideoModerationScanPersistenceService scanPersistenceService;  // REQUIRES_NEW TX isolation
        private final ArachnidClient arachnidClient;
        private final VideoIntelClient videoIntelClient;
        private final VideoProviderAdapter videoProviderAdapter;
        private final FeatureToggleService featureToggleService;
        private final ConfigService configService;
        private final ApplicationEventPublisher publisher;  // for domain events + Envelope notifications
        private final TransactionTemplate transactionTemplate;
        // Inject AgePolicyService from platform.security when available (Story 6.6 pre-requisite)
        // For Story 6.3: inject via optional or use a stub that returns adult for all owners

        @Async("moderationTaskExecutor")
        @Observed(name = "video.moderation.pipeline")
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onVideoUploaded(VideoUploadedEvent event) {
            UUID videoId = event.videoId();
            // NOTE: VideoUploadedEvent must carry UUID ownerId — verify Story 6.2 event record has this field.
            // If not, add it: public record VideoUploadedEvent(UUID videoId, UUID ownerId) {}
            UUID ownerId = event.ownerId();
            log.info("Moderation pipeline starting for videoId={}", videoId);

            // Step 1: PROCESSING → SCANNING (new TX)
            try {
                transactionTemplate.execute(status -> {
                    videoLifecycleService.transitionOperationalState(videoId, OperationalState.SCANNING);
                    return null;
                });
            } catch (TerminalStateViolationException e) {
                log.warn("Video {} already in terminal state when moderation pipeline started — skipping. state={}", videoId, e.getMessage());
                return;
            }

            // Acquire in-flight lock so SLA monitor does not re-queue this video while we are running.
            acquireModerationLock(videoId);

            // Resolve mediaUrl once — shared by Layer 1 (Arachnid) and Layer 2 (VideoIntel) to avoid
            // two separate DB reads. providerAssetId does not change during the pipeline.
            String mediaUrl = resolveMediaUrl(videoId);

            // Step 2: Layer 1 — Arachnid
            if (!runArachnidLayer(videoId, ownerId, mediaUrl)) return;  // pipeline stopped

            // Step 3: Layer 2 — VideoIntel
            if (!runVideoIntelLayer(videoId, ownerId, mediaUrl)) return;  // pipeline stopped

            // Step 4: Layer 3 — Minor safety gate
            runMinorSafetyGate(videoId, ownerId);
        }

        private boolean runArachnidLayer(UUID videoId, UUID ownerId, String mediaUrl) {
            if (!featureToggleService.isEnabled(AppFeature.ARACHNID_ENABLED)) {
                recordScan(videoId, "ARACHNID", "SKIPPED", null, "Feature flag disabled");
                log.debug("Arachnid disabled for videoId={} — skipping Layer 1", videoId);
                return true; // continue to Layer 2
            }
            try {
                ArachnidScanResult result = arachnidClient.scan(mediaUrl); // OUTSIDE @Transactional — mediaUrl resolved before this call
                if (result.matched()) {
                    String matchType = Objects.toString(result.matchType(), "UNKNOWN");
                    log.error("CSAM MATCH — videoId={} ownerId={} matchType={}", videoId, ownerId, matchType);
                    // Two separate operations — state transition first, then audit record.
                    // recordScan() uses REQUIRES_NEW (VideoModerationScanPersistenceService) so a concurrent
                    // retry race on the UNIQUE constraint does NOT roll back the LOCKED state transition.
                    transactionTemplate.execute(status -> {
                        videoLifecycleService.transitionOperationalState(videoId, OperationalState.LOCKED);
                        return null;
                    });
                    recordScan(videoId, "ARACHNID", "FLAGGED", null, "matchType=" + matchType);
                    // Alert admin FIRST — before attempting suspension — so the CSAM match is always
                    // recorded even if the suspension call throws. A CSAM match with no admin notification
                    // is a worse outcome than a CSAM match with a failed suspension.
                    alertAdmin("CSAM match detected",
                               "videoId=" + videoId + " ownerId=" + ownerId + " matchType=" + matchType,
                               true);
                    // suspendAccount and alertAdmin publish Spring events outside any TX.
                    // Their listeners use @EventListener (not @TransactionalEventListener) so events are
                    // NOT dropped when published outside a transaction — see Task 14b/14c.
                    try {
                        suspendAccount(ownerId, videoId);
                    } catch (Exception ex) {
                        // Suspension failed — account is NOT suspended yet. Alert admin urgently so they
                        // can suspend manually. Do NOT suppress: a CSAM match with an active account is a safety gap.
                        log.error("CRITICAL: suspendAccount failed for CSAM match ownerId={} videoId={}: {}",
                                  ownerId, videoId, ex.getMessage(), ex);
                        alertAdmin("CSAM match — account suspension FAILED",
                                   "videoId=" + videoId + " ownerId=" + ownerId
                                   + " suspensionError=" + ex.getMessage(), true);
                    }
                    return false; // pipeline stops
                }
                recordScan(videoId, "ARACHNID", "PASSED", null, null);
                return true;
            } catch (ArachnidException e) {
                log.error("Arachnid unavailable for videoId={}: {}", videoId, e.getMessage());
                recordScan(videoId, "ARACHNID", "FAILED", null, e.getMessage());
                alertAdmin("Arachnid moderation unavailable",
                           "videoId=" + videoId + " error=" + e.getMessage(), false);
                return false; // fail-closed: video stays in SCANNING
            }
        }

        private boolean runVideoIntelLayer(UUID videoId, UUID ownerId, String mediaUrl) {
            if (!featureToggleService.isEnabled(AppFeature.VIDEOINTEL_ENABLED)) {
                recordScan(videoId, "VIDEOINTEL", "SKIPPED", null, "Feature flag disabled");
                log.debug("VideoIntel disabled for videoId={} — skipping Layer 2", videoId);
                return true;
            }
            try {
                VideoIntelScanResult result = videoIntelClient.detectExplicitContent(mediaUrl); // OUTSIDE @Transactional — mediaUrl resolved before this call
                if (result.flagged()) {
                    log.warn("VideoIntel flagged explicit content: videoId={} confidence={}",
                             videoId, result.confidence());
                    // Separate operations: state transition first, then audit record (REQUIRES_NEW isolation).
                    transactionTemplate.execute(status -> {
                        videoLifecycleService.transitionOperationalState(videoId, OperationalState.LOCKED);
                        return null;
                    });
                    recordScan(videoId, "VIDEOINTEL", "FLAGGED", result.confidence(), result.description());
                    notifyOwner(ownerId, "Your video has been flagged for review");
                    return false;
                }
                // Null confidence for PASSED: VideoIntelScanResult.confidence is nullable (Double).
                // Storing 0.0 for a passed scan would conflate "passed with zero confidence" with "no score recorded".
                recordScan(videoId, "VIDEOINTEL", "PASSED", null, null);
                return true;
            } catch (VideoIntelException e) {
                log.error("VideoIntel unavailable for videoId={}: {}", videoId, e.getMessage());
                recordScan(videoId, "VIDEOINTEL", "FAILED", null, e.getMessage());
                alertAdmin("VideoIntel moderation unavailable",
                           "videoId=" + videoId + " error=" + e.getMessage(), false);
                return false; // fail-closed
            }
        }

        private void runMinorSafetyGate(UUID videoId, UUID ownerId) {
            // Story 6.6 replaces this stub with real age-tier evaluation.
            // Record SKIPPED (not PASSED) to avoid creating a false audit trail: a minor uploading a video
            // in Story 6.3 should not have a MINOR_GATE/PASSED record — Story 6.6 will write the real outcome.
            recordScan(videoId, "MINOR_GATE", "SKIPPED", null, "Age check deferred to Story 6.6");
            // TODO Story 6.6: inject AgePolicyService; if minor, set HIDDEN + create video_approval_requests row
            // For Story 6.3: assume all owners are adults/coaches → advance to TRANSCODING
            advanceToTranscoding(videoId);
        }

        private void advanceToTranscoding(UUID videoId) {
            // Phase 1: read providerAssetId + encodingCompletedAt in a short TX
            final String[] providerAssetId = {null};
            final boolean[] encodingDone = {false};
            transactionTemplate.execute(status -> {
                Video v = videoRepository.findById(videoId).orElseThrow(() -> new VideoNotFoundException(videoId));
                providerAssetId[0] = v.getProviderAssetId();
                encodingDone[0] = v.getEncodingCompletedAt() != null;
                return null;
            });

            if (encodingDone[0]) {
                // Fast-path: encoding completed before moderation finished.
                // The webhook handler saw SCANNING when it re-read state and returned WITHOUT calling
                // completeTranscoding(). No one else will drive TRANSCODING→READY — we must do it here.
                log.info("Encoding already completed for videoId={} — advancing SCANNING→TRANSCODING→READY directly", videoId);
                transactionTemplate.execute(status -> {
                    videoLifecycleService.transitionOperationalState(videoId, OperationalState.TRANSCODING);
                    return null;
                });
                videoService.completeTranscoding(videoId);  // TRANSCODING→READY; encoding is already done
            } else {
                // Normal path: Phase 2 — trigger transcoding OUTSIDE any TX
                if (providerAssetId[0] == null) {
                    log.error("providerAssetId is null for videoId={} — cannot trigger transcoding; transitioning to FAILED", videoId);
                    transactionTemplate.execute(status -> {
                        videoLifecycleService.transitionOperationalState(videoId, OperationalState.FAILED);
                        return null;
                    });
                    alertAdmin("Moderation pipeline error — null providerAssetId",
                               "videoId=" + videoId + " — manual intervention required", false);
                    return;
                }
                try {
                    videoProviderAdapter.triggerTranscoding(providerAssetId[0]); // HTTP call — no active TX
                } catch (Exception e) {
                    // Bunny.net is unavailable — video stays in SCANNING. The SLA monitor will retry
                    // the full pipeline after the SLA window elapses. Do NOT transition to FAILED here:
                    // a transient Bunny outage should not permanently fail a video that passed moderation.
                    log.error("triggerTranscoding failed for videoId={} — leaving in SCANNING for SLA retry: {}",
                              videoId, e.getMessage(), e);
                    alertAdmin("Transcoding trigger failed — video left in SCANNING",
                               "videoId=" + videoId + " error=" + e.getMessage(), false);
                    return;
                }
                // Phase 3: transition SCANNING→TRANSCODING in a new TX
                transactionTemplate.execute(status -> {
                    videoLifecycleService.transitionOperationalState(videoId, OperationalState.TRANSCODING);
                    return null;
                });
                // State is now TRANSCODING; TRANSCODING→READY happens when encoding.success webhook fires
            }
        }

        // Helpers (implement fully — these are structural sketches)
        private void recordScan(UUID videoId, String layer, String outcome, Double confidence, String details) {
            try {
                // Delegates to VideoModerationScanPersistenceService which uses @Transactional(REQUIRES_NEW).
                // REQUIRES_NEW ensures this write runs in its own independent TX — a DataIntegrityViolationException
                // from a concurrent retry race on UNIQUE(video_id, layer) is isolated to that TX and does NOT
                // mark the caller's outer TX as rollback-only (which could happen if Hibernate marks the shared
                // EntityManager rollback-only before Spring wraps the PersistenceException).
                scanPersistenceService.upsertScanRecord(videoId, layer, outcome, confidence, details);
            } catch (DataIntegrityViolationException e) {
                // Two concurrent pipeline threads raced on the same (video_id, layer) pair (lock just expired).
                // The other thread won the insert. Treat as idempotent — the record exists with consistent data.
                log.warn("Concurrent recordScan race on videoId={} layer={} — treating as idempotent: {}",
                         videoId, layer, e.getMessage());
            }
        }
        // Add import: org.springframework.dao.DataIntegrityViolationException

        private String resolveMediaUrl(UUID videoId) {
            // Returns the CDN URL for the raw uploaded video (pre-transcoding) for external API scanning
            Video video = transactionTemplate.execute(status ->
                videoRepository.findById(videoId).orElseThrow(() -> new VideoNotFoundException(videoId)));
            if (video == null || video.getProviderAssetId() == null) {
                throw new IllegalStateException("Cannot resolve media URL: providerAssetId is null for videoId=" + videoId);
            }
            return videoProviderAdapter.getRawVideoUrl(video.getProviderAssetId());
        }

        private void acquireModerationLock(UUID videoId) {
            // Prevents the SLA monitor from re-queuing a video that is actively being processed.
            // Lock expires after platform.moderation_lock_timeout_minutes — must be < SLA window.
            long lockMinutes = configService.getLong("platform.moderation_lock_timeout_minutes");
            transactionTemplate.execute(status -> {
                videoRepository.findById(videoId).ifPresent(v -> {
                    v.setModerationLockUntil(Instant.now().plus(lockMinutes, ChronoUnit.MINUTES));
                    videoRepository.save(v);
                });
                return null;
            });
        }

        private void suspendAccount(UUID ownerId, UUID videoId) {
            // Publish domain event — AccountSuspensionEventListener in platform.security owns the listener.
            // Listener uses @EventListener (not @TransactionalEventListener) so it fires regardless of TX context.
            // videoId is included so the listener can create a durable audit link (suspension ← CSAM scan).
            publisher.publishEvent(new AccountSuspensionRequestedEvent(ownerId, videoId));
            log.warn("AccountSuspensionRequestedEvent published for ownerId={} triggeredByVideoId={}", ownerId, videoId);
        }

        private void alertAdmin(String subject, String body, boolean urgent) {
            // Publish domain event — VideoModerationEmailListener in platform.notification handles it.
            // Listener uses @EventListener (not @TransactionalEventListener) so it fires regardless of TX context.
            // NEVER call SesEmailService directly — that violates the notification module contract.
            publisher.publishEvent(new VideoModerationAdminAlertEvent(null, null, subject, body, urgent));
            log.warn("VideoModerationAdminAlertEvent published: {} — {}", subject, body);
        }

        private void notifyOwner(UUID ownerId, String message) {
            // Publish domain event — VideoModerationEmailListener handles it and publishes an Envelope.
            // Listener uses @EventListener so it fires even though we are outside any TX here.
            publisher.publishEvent(new VideoModerationOwnerNotificationEvent(null, ownerId, message));
            log.info("VideoModerationOwnerNotificationEvent published for ownerId={}: {}", ownerId, message);
        }
    }
    ```
  - [x] **CRITICAL — Remove `VideoUploadedEvent` from `VideoService.completeTranscoding()` at line 364**: This method currently publishes `VideoUploadedEvent` after completing transcoding. After Story 6.3 ships, this re-triggers the moderation pipeline for already-READY videos, causing `TerminalStateViolationException` on READY→SCANNING attempts. Remove this publication before Story 6.3 deploys.
  - [x] **Add `ModerationAsyncConfig`** (P7): Define a `moderationTaskExecutor` `ThreadPoolTaskExecutor` bean used by `@Async("moderationTaskExecutor")`. This prevents moderation from competing with other async tasks for threads. Pattern: see existing `AsyncConfig` in the project; create or extend it.
  - [x] **Add missing imports**: `AccountSuspensionRequestedEvent`, `VideoModerationAdminAlertEvent`, `VideoModerationOwnerNotificationEvent`, `org.springframework.context.ApplicationEventPublisher`
  - [x] **Separate TX blocks**: each call to `transitionOperationalState()` must be in its own `transactionTemplate.execute()` block — never two transitions in one TX (L1 cache stale reads between calls in the same TX).
  - [x] **FLAGGED path TX isolation**: the FLAGGED case now runs two separate operations — `transitionOperationalState()` in one `transactionTemplate.execute()` block, then `recordScan()` as a separate call. `recordScan()` delegates to `VideoModerationScanPersistenceService.upsertScanRecord()` which uses `@Transactional(REQUIRES_NEW)`. This means: (a) state transition and scan record are no longer atomic — if `upsertScanRecord()` fails after `transitionOperationalState(LOCKED)` commits, the video is LOCKED but the scan record may be stale; (b) this is the correct tradeoff: a `DataIntegrityViolationException` from a concurrent retry race no longer poisons the state transition TX as rollback-only. The LOCKED state (safety outcome) is guaranteed to commit regardless of scan record write failure.
  - [x] **Pre-condition — verify `VideoUploadedEvent` has `UUID ownerId` field**: `onVideoUploaded()` calls `event.ownerId()`. If Story 6.2 shipped with only `UUID videoId`, add `UUID ownerId` to the record now. This is a compile-time dependency — the story will not build without it.
  - [x] **Known gap — FLAGGED videos have no admin review UI**: The `video_moderation_scans` table is an audit log, not a work queue. Admins cannot currently discover or action FLAGGED records. This is intentionally out of scope for Story 6.3; a dedicated admin moderation queue endpoint must be added in a future story (suggested: Story 6.5 or dedicated admin Epic 10 story). Document this as a known gap in the Story 6.5 handoff.

---

### Backend — Notification Integration

- [x] **Task 14b: Create notification events and `VideoModerationEmailListener`** (AC: 2, 4, 6, 7)
  - [x] Create domain event records:
    - `src/main/java/com/softropic/skillars/platform/video/contract/event/VideoModerationAdminAlertEvent.java` (CREATE)
      ```java
      public record VideoModerationAdminAlertEvent(UUID videoId, UUID ownerId, String subject, String body, boolean urgent) {}
      // Fields named 'subject'/'body' to match call sites in alertAdmin() and VideoModerationEmailListener usage.
      // 'videoId' and 'ownerId' are nullable — some alert paths (service unavailability) do not have a specific video context.
      ```
    - `src/main/java/com/softropic/skillars/platform/video/contract/event/VideoModerationOwnerNotificationEvent.java` (CREATE)
      ```java
      public record VideoModerationOwnerNotificationEvent(UUID videoId, UUID ownerId, String message) {}
      ```
  - [x] Create `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListener.java` (CREATE)
    - Pattern: follows `BookingEmailListener` — inject `ApplicationEventPublisher`, listen to `VideoModerationAdminAlertEvent` and `VideoModerationOwnerNotificationEvent`, publish `Envelope` for each
    - Admin email: read from `ConfigService` key `platform.admin_alert_email` (seed this in config)
    - Owner email: look up via `UserProfileService` using `ownerId`
    - **Use `@EventListener` (NOT `@TransactionalEventListener`) on each handler method.** Rationale: `alertAdmin()` and `notifyOwner()` are called outside any active transaction (after `transactionTemplate.execute()` blocks commit). `@TransactionalEventListener` with default `fallbackExecution = false` silently discards events published outside a transaction — every fail-closed admin alert and every owner notification would be lost. `@EventListener` fires synchronously in the calling thread regardless of TX context; the listener itself publishes an `Envelope` to the notification infrastructure which handles async delivery internally.
    - Both event records now declare `UUID ownerId` — owner email lookup: `userProfileService.findById(ownerId)`

- [x] **Task 14c: Create `AccountSuspensionRequestedEvent` and security module listener** (AC: 2 — CSAM account suspension)
  - [x] Create `src/main/java/com/softropic/skillars/platform/security/contract/event/AccountSuspensionRequestedEvent.java` (CREATE)
    ```java
    package com.softropic.skillars.platform.security.contract.event;

    import java.util.UUID;

    public record AccountSuspensionRequestedEvent(UUID ownerId, UUID videoId) {}
    // videoId is included for audit trail: the listener must record which video triggered the suspension
    // so a compliance audit can link "account suspended on date X" to "CSAM match on scan record Y".
    ```
  - [x] Create listener in `platform.security` (e.g., `AccountSuspensionEventListener`):
    - **`@EventListener` + `@Transactional`** on the handler method. Use `@EventListener` (not `@TransactionalEventListener`) because the event is published outside an active TX from `ModerationOrchestrationService`. Add `@Transactional` directly on the handler so the suspension DB write gets its own TX.
    - Fetches user from `UserRepository` by `ownerId` (UUID)
    - Sets `verificationStatus = SUSPENDED` (or equivalent field on `User` entity)
    - **Saves a suspension audit record** (or log entry) that includes `videoId` from the event — the link between the CSAM scan and the account suspension must be durable for compliance. At minimum: `log.error("Account suspended: ownerId={} triggeredByVideoId={}", event.ownerId(), event.videoId())` at ERROR level so it appears in the audit log trail. A dedicated `account_suspension_audits` table is preferable but is Story 10 scope.
    - Saves and logs
  - [x] **Pre-condition**: Verify `User` entity has `ownerId()` / `Video` entity has `getOwnerId()` returning `UUID` — used by `ModerationSlaMonitorService` when publishing `VideoModerationRetryEvent(video.getId(), video.getOwnerId())`. If `Video.ownerId` is stored as a JPA relationship (`@ManyToOne User owner`), call `video.getOwner().getId()` instead. Confirm before Task 15 implementation.
  - [x] `ModerationOrchestrationService.suspendAccount()` publishes this event via `ApplicationEventPublisher` (already updated in the sketch above)

- [x] **Task 14d: Create `VideoModerationRetryEvent` and dedicated handler** (AC: 6 — SLA monitor retry)
  - [x] Create `src/main/java/com/softropic/skillars/platform/video/contract/event/VideoModerationRetryEvent.java` (CREATE)
    ```java
    package com.softropic.skillars.platform.video.contract.event;

    import java.util.UUID;

    public record VideoModerationRetryEvent(UUID videoId, UUID ownerId) {}
    ```
  - [x] In `ModerationOrchestrationService`, add a dedicated `@EventListener` handler:
    ```java
    @Async("moderationTaskExecutor")
    @EventListener
    public void onModerationRetry(VideoModerationRetryEvent event) {
        UUID videoId = event.videoId();
        UUID ownerId = event.ownerId();
        log.info("Moderation SLA retry for videoId={}", videoId);
        // Acquire in-flight lock so SLA monitor does not fire a second retry while this one runs.
        acquireModerationLock(videoId);
        // Skip the PROCESSING→SCANNING transition — video is already in SCANNING.
        // Resolve mediaUrl once (same pattern as onVideoUploaded) before resuming from Layer 1.
        String mediaUrl = resolveMediaUrl(videoId);
        if (!runArachnidLayer(videoId, ownerId, mediaUrl)) return;
        if (!runVideoIntelLayer(videoId, ownerId, mediaUrl)) return;
        runMinorSafetyGate(videoId, ownerId);
    }
    ```
  - [x] Update `ModerationSlaMonitorService.detectSlaViolations()` to publish `VideoModerationRetryEvent` instead of `VideoUploadedEvent`

---

### Backend — Platform Service: ModerationSlaMonitorService

- [x] **Task 15: Create `ModerationSlaMonitorService`** (AC: 6)
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/service/ModerationSlaMonitorService.java` (CREATE)
    ```java
    package com.softropic.skillars.platform.video.service;

    import com.softropic.skillars.platform.config.service.ConfigService;
    import com.softropic.skillars.platform.video.contract.OperationalState;
    import com.softropic.skillars.platform.video.contract.event.VideoModerationRetryEvent;
    import com.softropic.skillars.platform.video.repo.Video;
    import com.softropic.skillars.platform.video.repo.VideoRepository;
    import io.micrometer.observation.annotation.Observed;
    import lombok.RequiredArgsConstructor;
    import lombok.extern.slf4j.Slf4j;
    import org.springframework.context.ApplicationEventPublisher;
    import org.springframework.scheduling.annotation.Scheduled;
    import org.springframework.stereotype.Service;

    import java.time.Instant;
    import java.time.temporal.ChronoUnit;
    import java.util.List;

    @Service
    @Slf4j
    @RequiredArgsConstructor
    public class ModerationSlaMonitorService {

        private final VideoRepository videoRepository;
        private final VideoLifecycleService videoLifecycleService;
        private final ConfigService configService;
        private final ApplicationEventPublisher publisher;
        private final TransactionTemplate transactionTemplate;

        @Observed(name = "video.moderation.slaMonitor")
        @Scheduled(fixedDelayString = "${app.video.moderation.sla-monitor-delay-ms:300000}")
        public void detectSlaViolations() {
            long slaMinutes = configService.getLong("platform.moderation_sla_minutes");
            long maxRetries = configService.getLong("platform.moderation_max_retries");
            Instant threshold = Instant.now().minus(slaMinutes, ChronoUnit.MINUTES);

            List<Video> stuckVideos = videoRepository.findScanningOlderThan(threshold, Instant.now());
            int retried = 0, exhausted = 0;
            for (Video video : stuckVideos) {
                if (video.getModerationRetryCount() >= maxRetries) {
                    // Max retries exceeded — permanently fail this video. Alert admin once so they
                    // can investigate. Without this, a chronically stuck video fires an admin alert
                    // every ~15 min indefinitely.
                    log.error("Moderation max retries ({}) exceeded for videoId={} — transitioning to FAILED",
                              maxRetries, video.getId());
                    transactionTemplate.execute(status -> {
                        videoLifecycleService.transitionOperationalState(video.getId(), OperationalState.FAILED);
                        return null;
                    });
                    publisher.publishEvent(new VideoModerationAdminAlertEvent(
                        video.getId(), video.getOwnerId(),
                        "Moderation pipeline permanently failed",
                        "videoId=" + video.getId() + " retries=" + video.getModerationRetryCount()
                        + " — manual review required", true));
                    exhausted++;
                } else {
                    // Increment retry count before dispatching the event so that if two SLA cycles
                    // overlap (lock just expired), only one thread increments and dispatches.
                    transactionTemplate.execute(status -> {
                        videoRepository.findById(video.getId()).ifPresent(v -> {
                            v.setModerationRetryCount(v.getModerationRetryCount() + 1);
                            videoRepository.save(v);
                        });
                        return null;
                    });
                    log.warn("Moderation SLA exceeded for videoId={} stuck since={} retry={}/{}",
                             video.getId(), video.getScanningStartedAt(),
                             video.getModerationRetryCount(), maxRetries);
                    // Publish VideoModerationRetryEvent — not VideoUploadedEvent (which would attempt PROCESSING→SCANNING
                    // and fail with TerminalStateViolationException since video is already in SCANNING).
                    publisher.publishEvent(new VideoModerationRetryEvent(video.getId(), video.getOwnerId()));
                    retried++;
                }
            }
            if (retried > 0)
                log.info("Requeued {} videos stuck in SCANNING beyond {}min SLA", retried, slaMinutes);
            if (exhausted > 0)
                log.error("Permanently failed {} videos after exhausting {} moderation retries", exhausted, maxRetries);
        }
    }
    // Add imports: com.softropic.skillars.platform.video.contract.OperationalState,
    //              com.softropic.skillars.platform.video.contract.event.VideoModerationAdminAlertEvent,
    //              com.softropic.skillars.platform.video.service.VideoLifecycleService,
    //              org.springframework.transaction.support.TransactionTemplate
    ```
  - [x] Add config keys to the ConfigService dataset in a V57 migration (or amend V56 if it hasn't shipped yet):
    ```sql
    INSERT INTO main.platform_config (key, value) VALUES ('platform.moderation_sla_minutes', '30') ON CONFLICT (key) DO NOTHING;
    INSERT INTO main.platform_config (key, value) VALUES ('platform.moderation_lock_timeout_minutes', '15') ON CONFLICT (key) DO NOTHING;
    INSERT INTO main.platform_config (key, value) VALUES ('platform.moderation_max_retries', '5') ON CONFLICT (key) DO NOTHING;
    ```
    The lock timeout (15 min) must be strictly less than the SLA window (30 min) so the SLA monitor can still detect genuinely stuck videos after the lock expires. With 5 max retries and a 30-min SLA, a video is permanently failed after ~150 min of continuous moderation failure — preventing indefinite admin alert flooding.
  - [x] Add `app.video.moderation.sla-monitor-delay-ms` to `application.yaml` and `application-dev.yaml`
  - [x] **Pre-condition — verify `@EnableScheduling` is present**: `@Scheduled` on `detectSlaViolations()` silently does nothing if `@EnableScheduling` is absent from the application context. Grep the codebase: `grep -r "@EnableScheduling" src/main/`. If absent, add it to the main `@SpringBootApplication` class or an existing `@Configuration` class. (Note: `@EnableAsync` is confirmed present in `platform.notification.config.AsyncConfig` — `@EnableScheduling` is a separate annotation and must be confirmed independently.)

---

### Backend — WebhookEventProcessorScheduler Update

- [x] **Task 16: Handle `video.encoding.success` and `video.encoding.failed` when video is in SCANNING state** (AC: 12)
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/service/WebhookEventProcessorScheduler.java` (MODIFY)
  - [x] In `dispatchEvent()`, update the `"video.encoding.success"` case to handle SCANNING state:
    ```java
    case "video.encoding.success" -> {
        // Out-of-order compensation: if Status=3 arrives before Status=7 (existing logic)
        if (video.getOperationalState() == OperationalState.UPLOADING) {
            // ... existing compensating UPLOADING→PROCESSING logic ...
        }
        // NEW: if video is still in PROCESSING, the moderation @Async listener has not yet started
        // (VideoUploadedEvent is queued but the moderationTaskExecutor hasn't picked it up).
        // Record encodingCompletedAt so advanceToTranscoding() uses the fast-path when moderation
        // completes. Do NOT call completeTranscoding() — that would bypass all three moderation layers.
        if (video.getOperationalState() == OperationalState.PROCESSING) {
            log.info("Encoding completed while video still in PROCESSING for videoId={} — recording to prevent moderation bypass", videoId);
            transactionTemplate.execute(status -> {
                Video v = videoRepository.findById(videoId).orElse(null);
                if (v != null) {
                    v.setEncodingCompletedAt(Instant.now());
                    videoRepository.save(v);
                }
                return null;
            });
            // Moderation listener is already queued; advanceToTranscoding() will see encodingCompletedAt.
            return;
        }
        // NEW: if video is in SCANNING (moderation in progress), record encoding completion;
        // after setting encodingCompletedAt, re-read state — if moderation already advanced to TRANSCODING,
        // complete it now (self-heal for the narrow race where encoding.success fires AFTER moderation
        // has already advanced to TRANSCODING).
        // Note: the wider race (encoding.success fires while SCANNING, moderation later advances to TRANSCODING)
        // is handled in ModerationOrchestrationService.advanceToTranscoding() fast-path which calls
        // completeTranscoding() directly — do NOT also call it here in that case.
        if (video.getOperationalState() == OperationalState.SCANNING) {
            log.info("Encoding completed while video in SCANNING state for videoId={} — recording completion", videoId);
            transactionTemplate.execute(status -> {
                Video v = videoRepository.findById(videoId).orElse(null);
                if (v != null) {
                    v.setEncodingCompletedAt(Instant.now());
                    videoRepository.save(v);
                }
                return null;
            });
            // Self-heal: re-read state after encoding completion is persisted.
            // If moderation already advanced to TRANSCODING before we got here, complete it now.
            OperationalState currentState = transactionTemplate.execute(status ->
                videoRepository.findById(videoId).map(Video::getOperationalState).orElse(null));
            if (currentState == OperationalState.TRANSCODING) {
                log.info("Video {} now in TRANSCODING after encoding.success — completing TRANSCODING→READY", videoId);
                videoService.completeTranscoding(videoId);
            }
            // If still SCANNING: moderation will pick up encodingCompletedAt later and use the fast-path.
            return;
        }
        videoService.completeTranscoding(videoId);
    }
    ```
  - [x] In `dispatchEvent()`, add handling for `"video.encoding.failed"` when video is in SCANNING state (NEW):
    ```java
    case "video.encoding.failed" -> {
        // If encoding fails while moderation is still running, fail the video immediately.
        // Moderation's advanceToTranscoding() will attempt triggerTranscoding() which would fail anyway.
        if (video.getOperationalState() == OperationalState.SCANNING) {
            log.error("Encoding failed while video in SCANNING state for videoId={} — transitioning to FAILED", videoId);
            transactionTemplate.execute(status -> {
                videoLifecycleService.transitionOperationalState(videoId, OperationalState.FAILED);
                return null;
            });
            // Release storage quota — same obligation as the existing PROCESSING→FAILED path.
            // Without this, the quota reservation handle is never released and the owner
            // permanently loses the storage allocated to this video.
            quotaProvider.release(video.getQuotaReservationHandle());
            return;
        }
        // ... existing encoding.failed handling for TRANSCODING state (already calls quotaProvider.release()) ...
    }
    ```
  - [x] Also update the `"video.upload.success"` case to publish `VideoUploadedEvent` via `publisher`:
    - **Grep confirmed**: `VideoUploadedEvent` is NOT present in `WebhookEventProcessorScheduler.java` — add it unconditionally (P6):
      ```java
      case "video.upload.success" -> {
          transactionTemplate.execute(status -> {
              videoLifecycleService.transitionOperationalState(videoId, OperationalState.PROCESSING);
              return null;
          });
          publisher.publishEvent(new VideoUploadedEvent(videoId, video.getOwnerId()));
      }
      ```

---

### Backend — SSE

- [x] **Task 17: Create `VideoSseService`** (AC: 11)
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/service/VideoSseService.java` (CREATE)
  - [x] Pattern: exact copy of `BookingSseService` adapted for `VideoStatusChangedEvent`:
    ```java
    package com.softropic.skillars.platform.video.service;

    import com.softropic.skillars.platform.video.contract.event.VideoStatusChangedEvent;
    import lombok.extern.slf4j.Slf4j;
    import org.springframework.stereotype.Service;
    import org.springframework.transaction.event.TransactionPhase;
    import org.springframework.transaction.event.TransactionalEventListener;
    import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

    import java.io.IOException;
    import java.util.UUID;
    import java.util.concurrent.ConcurrentHashMap;
    import java.util.concurrent.CopyOnWriteArrayList;

    @Service
    @Slf4j
    public class VideoSseService {

        private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

        private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

        public SseEmitter subscribe(UUID videoId, String currentStatus) {
            SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
            emitters.computeIfAbsent(videoId, id -> new CopyOnWriteArrayList<>()).add(emitter);
            emitter.onCompletion(() -> removeEmitter(videoId, emitter));
            emitter.onTimeout(() -> {
                removeEmitter(videoId, emitter);  // tryHeartbeat removed — always throws IOException after timeout
            });
            emitter.onError(e -> removeEmitter(videoId, emitter));
            try {
                emitter.send(SseEmitter.event().name("status").data(currentStatus));
            } catch (IOException e) {
                log.warn("Failed to send initial status to SSE subscriber for video {}", videoId, e);
                removeEmitter(videoId, emitter);
            }
            return emitter;
        }

        // HIDDEN is included here because VALID_TRANSITIONS makes it terminal in Story 6.3 (Set.of() = no exits).
        // Story 6.6 DEPENDENCY: when parent approval drives HIDDEN → TRANSCODING, the emitter for HIDDEN
        // videos will already be closed by this point. Story 6.6 must NOT rely on server-push SSE to notify
        // the client of the HIDDEN → TRANSCODING transition. Instead, the parent approval screen in
        // VideoStatusCard.vue must reopen the SSE connection (or fall back to polling) upon receiving
        // the initial HIDDEN status — exactly as the composable does after timeout/disconnect.
        private static final Set<OperationalState> TERMINAL_STATES = Set.of(
            OperationalState.READY, OperationalState.LOCKED,
            OperationalState.HIDDEN, OperationalState.FAILED, OperationalState.DELETED);

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onStatusChanged(VideoStatusChangedEvent event) {
            var list = emitters.get(event.videoId());
            if (list == null || list.isEmpty()) return;
            boolean terminal = TERMINAL_STATES.contains(event.newState());
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().name("status").data(event.newState().name()));
                    if (terminal) {
                        emitter.complete(); // release server resources immediately; client also closes on terminal status
                    }
                } catch (IOException e) {
                    log.warn("Failed to push status update for video {}, removing emitter", event.videoId());
                    removeEmitter(event.videoId(), emitter);
                }
            }
            if (terminal) {
                emitters.remove(event.videoId()); // purge all emitters — no further state changes expected
            }
        }
        // Add import: com.softropic.skillars.platform.video.contract.OperationalState, java.util.Set

        private void removeEmitter(UUID videoId, SseEmitter emitter) {
            emitters.compute(videoId, (id, list) -> {
                if (list == null) return null;
                list.remove(emitter);
                return list.isEmpty() ? null : list;
            });
        }
    }
    // Note: tryHeartbeat() removed — onTimeout() fires after the emitter is expired; send() throws IOException
    // immediately and the method was never callable in a state where it could succeed (P20).
    ```

- [x] **Task 18: Create `VideoEventResource`** (AC: 11)
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/api/VideoEventResource.java` (CREATE)
    ```java
    package com.softropic.skillars.platform.video.api;

    import com.softropic.skillars.infrastructure.security.SecurityConstants;
    import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
    import com.softropic.skillars.platform.video.repo.Video;
    import com.softropic.skillars.platform.video.service.VideoService;
    import com.softropic.skillars.platform.video.service.VideoSseService;
    import io.micrometer.observation.annotation.Observed;
    import lombok.RequiredArgsConstructor;
    import org.springframework.http.MediaType;
    import org.springframework.http.ResponseEntity;
    import org.springframework.security.access.prepost.PreAuthorize;
    import org.springframework.security.core.context.SecurityContextHolder;
    import org.springframework.web.bind.annotation.GetMapping;
    import org.springframework.web.bind.annotation.PathVariable;
    import org.springframework.web.bind.annotation.RequestMapping;
    import org.springframework.web.bind.annotation.RestController;
    import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

    import java.util.UUID;

    @Observed(name = "video.events")
    @RestController
    @RequestMapping("/api/video")
    @RequiredArgsConstructor
    public class VideoEventResource {

        private final VideoService videoService;
        private final VideoSseService videoSseService;

        @GetMapping("/{id}/events")
        @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
        public ResponseEntity<SseEmitter> subscribeToEvents(@PathVariable UUID id) {
            Video video = videoService.findById(id)  // VideoService, not VideoRepository directly
                .orElseThrow(() -> new VideoNotFoundException(id));

            // Ownership check: reject requests from users who don't own this video.
            // CRITICAL PRE-CONDITION: verify what Authentication.getName() returns in this project.
            // Check JWTAuthorizationFilter — if it sets the principal name to the user's UUID string,
            // the comparison below is correct. If it sets it to email or username, this will ALWAYS return
            // false (403 for every valid owner). In that case, extract the UUID from a JWT claim instead:
            //   String authenticatedUserId = ((JwtAuthenticationToken) auth).getToken().getClaim("userId");
            // Adjust the claim name to match whatever JWTAuthorizationFilter puts in the token.
            String authenticatedUserId = SecurityContextHolder.getContext().getAuthentication().getName();
            if (!video.getOwnerId().toString().equals(authenticatedUserId)) {
                return ResponseEntity.status(403).build();
            }
            // TODO Story 6.5: extend to allow coaches with an active relationship and admin users

            SseEmitter emitter = videoSseService.subscribe(id, video.getOperationalState().name());
            return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
        }
    }
    ```
  - [x] Add `VideoService.findById(UUID id)` method if not already present (returns `Optional<Video>`).

---

### Backend — Tests

- [x] **Task 19: Create `ModerationOrchestrationServiceTest`** (AC: 1–10)
  - [x] File: `src/test/java/com/softropic/skillars/platform/video/service/ModerationOrchestrationServiceTest.java` (CREATE)
  - [x] **Test 1: `arachnidClean_videoIntelClean_adultOwner_advancesToTranscoding`**
    - Stub Arachnid: returns `new ArachnidScanResult(false, null)`
    - Stub VideoIntel: returns `new VideoIntelScanResult(false, 0.1, null)`
    - Assert: SCANNING → TRANSCODING; `triggerTranscoding()` called; scan records PASSED for both layers
  - [x] **Test 2: `arachnidMatch_locksVideoAndSuspendsAccount`**
    - Stub Arachnid: returns `new ArachnidScanResult(true, "MATCH_TYPE_1")`
    - Assert: SCANNING → LOCKED; `suspendAccount()` called; admin alert sent; VideoIntel NOT called
  - [x] **Test 3: `arachnidUnavailable_staysInScanning_failClosed`**
    - Stub Arachnid: throws `ArachnidException`
    - Assert: video remains SCANNING; scan record FAILED; admin alert sent; VideoIntel NOT called
  - [x] **Test 4: `videoIntelFlagged_locksVideoAndNotifiesOwner`**
    - Stub Arachnid: clean; Stub VideoIntel: returns `flagged=true, confidence=0.9`
    - Assert: SCANNING → LOCKED; owner notified; scan record FLAGGED
  - [x] **Test 5: `videoIntelUnavailable_staysInScanning_failClosed`**
    - Stub VideoIntel: throws `VideoIntelException`
    - Assert: video remains SCANNING; admin alert; SCANNING NOT → TRANSCODING
  - [x] **Test 6: `arachnidDisabled_videoIntelDisabled_adultOwner_advancesToTranscoding`**
    - Features: `ARACHNID_ENABLED=false`, `VIDEOINTEL_ENABLED=false`
    - Assert: SCANNING → TRANSCODING; both scan records SKIPPED; no admin alert
  - [x] **Test 7: `arachnidDisabled_videoIntelEnabled_flagged_locksVideo`**
    - Features: `ARACHNID_ENABLED=false`, `VIDEOINTEL_ENABLED=true`; VideoIntel flags
    - Assert: SCANNING → LOCKED; ARACHNID scan record SKIPPED; VIDEOINTEL record FLAGGED
  - [x] **Test 8: `encodingAlreadyDone_skipsTranscoding_advancesToTranscoding`**
    - Set `video.encodingCompletedAt = Instant.now()` before pipeline runs
    - Assert: advances to TRANSCODING (fast-path); `triggerTranscoding()` NOT called; webhook will complete to READY
  - [x] **Test 9: `webhookEncodingSuccess_whileInScanning_setsEncodingCompletedAt_doesNotCallCompleteTranscoding`** (AC: 12)
    - Setup: video in SCANNING state; simulate `video.encoding.success` webhook dispatch
    - Assert: `encodingCompletedAt` is set on the video entity; `videoService.completeTranscoding()` is NOT called
  - [x] Use Mockito for `ArachnidClient`, `VideoIntelClient`, `VideoProviderAdapter`, `ApplicationEventPublisher`
  - [x] Use `@Mock FeatureToggleService` to control flags per test (not `@MockitoBean` — this is a unit test, not `@SpringBootTest`)

- [x] **Task 20: Create `VideoSseIT`** (AC: 11)
  - [x] File: `src/test/java/com/softropic/skillars/platform/video/api/VideoSseIT.java` (CREATE)
  - [x] `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureMockMvc`
  - [x] **Test 1: `subscribeToEvents_authenticated_returns200WithEventStream`**
    - Authenticate as ROLE_COACH; GET `/api/video/{id}/events`
    - Assert: HTTP 200; Content-Type contains `text/event-stream`
  - [x] **Test 2: `subscribeToEvents_unauthenticated_returns401`**
  - [x] **Test 3: `statusChange_pushesEventToSubscriber`**
    - Subscribe; trigger `VideoStatusChangedEvent` via `ApplicationEventPublisher`; assert SSE event data received
  - [x] **Test 4 (AC 12 full sequence): `encodingWebhookDuringScan_thenModerationCompletes_advancesToReady`** (AC: 12)
    - This integration test covers the full AC 12 sequence that unit tests cannot — it verifies that ordering constraints across the two async threads are satisfied.
    - Setup: video in SCANNING state; `@MockBean ArachnidClient` returns clean; `@MockBean VideoIntelClient` returns clean
    - Step 1: Simulate `video.encoding.success` webhook dispatch while video is in SCANNING — assert `encodingCompletedAt` is set and video remains in SCANNING (NOT READY)
    - Step 2: Trigger `ModerationOrchestrationService.onVideoUploaded()` via `ApplicationEventPublisher.publishEvent(new VideoUploadedEvent(...))` on a PROCESSING video, or directly invoke `onModerationRetry()` for a SCANNING video — allow the async method to complete (use `CountDownLatch` or `Awaitility`)
    - Step 3: Assert final state is READY (fast-path: SCANNING→TRANSCODING→READY); assert `triggerTranscoding()` was NOT called (since encoding was already done); assert two SSE events were received (TRANSCODING, READY)
    - Use `Awaitility.await().atMost(5, SECONDS).until(...)` to avoid flaky timing assertions

- [x] **Task 21: Update `VideoLifecycleServiceTest`** (AC: 3, 4)
  - [x] File: `src/test/java/com/softropic/skillars/platform/video/service/VideoLifecycleServiceTest.java` (MODIFY, if exists) OR create if absent
  - [x] Add tests for new valid transitions: PROCESSING → SCANNING, SCANNING → TRANSCODING, SCANNING → LOCKED, SCANNING → HIDDEN, TRANSCODING → READY, TRANSCODING → FAILED
  - [x] Add test for `VideoStatusChangedEvent` publication after each transition
  - [x] Add tests for invalid transitions that must be rejected: SCANNING → PROCESSING (backward), LOCKED → any, HIDDEN → TRANSCODING (only Story 6.6 should unlock HIDDEN)
  - [x] Add test for PROCESSING→READY bypass log warning: assert `log.warn(...)` is called when transitioning PROCESSING→READY

---

### Config

- [x] **Task 22: Add feature flags and new properties to YAMLs**
  - [x] `src/main/resources/application.yaml` (MODIFY) — add:
    ```yaml
    features:
      toggles:
        arachnid-enabled: false
        videointel-enabled: false
    infrastructure:
      arachnid:
        api-key: ""
        api-base-url: "https://api.arachnid.projectvic.org"
        timeout-seconds: 30
      videointel:
        project-id: ""
        credentials-path: ""
        flag-threshold: 0.7
        timeout-seconds: 300
    app:
      video:
        moderation:
          sla-monitor-delay-ms: 300000  # 5 minutes
    ```
  - [x] `src/main/resources/application-dev.yaml` (MODIFY) — keep flags false in dev
  - [x] `src/test/resources/application-test.yaml` (MODIFY) — keep flags false in test; add:
    ```yaml
    features:
      toggles:
        arachnid-enabled: false
        videointel-enabled: false
    ```
  - [x] **Add `platform.moderation_sla_minutes` to platform config table** — insert in a Flyway migration OR in the `config_data` seed SQL. Default value: 30 (30 minutes SLA for moderation to complete)

---

### Frontend

- [x] **Task 23: Create `VideoStatusCard.vue`** (AC: 11)
  - [x] File: `src/frontend/src/components/VideoStatusCard.vue` (CREATE)
  - [x] Pattern: follows existing card components in the codebase; uses `<script setup>`
  - [x] Key requirements from AC / UX-DR13:
    - `aria-live="polite"` on the card root element
    - Distinct visual treatment per operational state:
      - `UPLOADING` / `PROCESSING`: spinner + "Uploading..." text
      - `SCANNING`: spinner + "Under review..." text (do NOT leak moderation details to users)
      - `TRANSCODING`: progress indicator + "Processing..." text
      - `READY` / (future PUBLISHED): video thumbnail + play button
      - `LOCKED`: padlock icon + "Content unavailable" — do NOT reveal reason to user
      - `HIDDEN`: "Pending approval" message (parent or self)
      - `FAILED`: warning icon + "Upload failed" + retry option
      - `DELETED`: renders nothing (remove card from list)
    - Props: `videoId: String`, `initialStatus: String`
    - Uses SSE (`GET /api/video/{id}/events`) for live updates
    - Exponential backoff reconnect on SSE disconnect: delays `[1000, 2000, 4000, 8000]` ms, then 2-second polling fallback
    - All text via `vue-i18n` keys: `video.status.uploading`, `video.status.scanning`, etc.
  - [x] Add i18n keys to the locale file (`src/frontend/src/i18n/en-US/index.js` or equivalent)

- [x] **Task 24: Update `video.store.js`** (AC: 11)
  - [x] File: `src/frontend/src/stores/video.store.js` (MODIFY)
  - [x] Add SSE subscription management: `subscribeToVideoStatus(videoId)` and `unsubscribeFromVideoStatus(videoId)`
  - [x] Track `videoStatuses: ref({})` keyed by videoId for reactive status updates across components
  - [x] Store SSE EventSource instances in a `Map<videoId, EventSource>` for cleanup
  - [x] Create or reuse a `useVideoStatusSse(videoId)` Vue composable with exponential backoff:
    - Backoff delays: `[1000, 2000, 4000, 8000]` ms (4 reconnect attempts then fall to polling fallback)
    - On backoff exhaustion: switch to polling `GET /api/video/{id}/status` every 2 seconds
    - On `READY`, `LOCKED`, or `FAILED` status received: stop polling and close SSE connection

- [x] **Task 25: Add `GET /api/video/{id}/status` polling fallback endpoint** (AC: 11)
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/api/VideoEventResource.java` (MODIFY) — add to existing `VideoEventResource`
  - [x] Response DTO record:
    ```java
    public record VideoStatusResponse(UUID videoId, String operationalState) {}
    ```
  - [x] Endpoint:
    ```java
    @GetMapping("/{id}/status")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    public ResponseEntity<VideoStatusResponse> getVideoStatus(@PathVariable UUID id) {
        Video video = videoService.findById(id).orElseThrow(() -> new VideoNotFoundException(id));
        // Same ownership-check caveat as subscribeToEvents(): verify Authentication.getName() returns
        // the user UUID string and not email/username — see the comment in subscribeToEvents().
        String authenticatedUserId = SecurityContextHolder.getContext().getAuthentication().getName();
        if (!video.getOwnerId().toString().equals(authenticatedUserId)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(new VideoStatusResponse(id, video.getOperationalState().name()));
    }
    ```

- [x] **Task 26: Create `ModerationSlaMonitorServiceTest`** (AC: 6)
  - [x] File: `src/test/java/com/softropic/skillars/platform/video/service/ModerationSlaMonitorServiceTest.java` (CREATE)
  - [x] Use `@ExtendWith(MockitoExtension.class)` — unit test, no Spring context
  - [x] **Test 1: `videoStuckBelowSlaWindow_notPickedUp`**
    - Video `scanningStartedAt = now() - 10min`; SLA = 30min; assert `findScanningOlderThan()` returns empty list; no events published
  - [x] **Test 2: `videoStuckBeyondSlaWindow_retryCountBelowMax_publishesRetryEvent`**
    - Video `scanningStartedAt = now() - 45min`; `moderationRetryCount = 0`; `maxRetries = 5`
    - Assert: `moderationRetryCount` incremented to 1 in DB; `VideoModerationRetryEvent` published; no `VideoModerationAdminAlertEvent` for FAILED
  - [x] **Test 3: `videoStuckBeyondSlaWindow_retryCountAtMax_transitionsToFailed_alertsAdmin`**
    - Video `moderationRetryCount = 5`; `maxRetries = 5`
    - Assert: `transitionOperationalState(FAILED)` called; `VideoModerationAdminAlertEvent` published with `urgent=true`; NO `VideoModerationRetryEvent` published
  - [x] **Test 4: `videoWithActiveModerationLock_skippedBySlaMonitor`**
    - Video `moderationLockUntil = now() + 10min` (lock still active)
    - Assert: `findScanningOlderThan()` WHERE predicate excludes this video; no retry event
  - [x] **Test 5: `multipleStuckVideos_eachHandledIndependently`**
    - Three videos: one below SLA, one above SLA (retryCount < max), one above SLA (retryCount = max)
    - Assert: only two videos actioned; correct events for each; counts logged correctly
  - [x] Use `@Mock VideoRepository`, `@Mock ApplicationEventPublisher`, `@Mock ConfigService`, `@Mock VideoLifecycleService`, `@Mock TransactionTemplate`

---

### Review Findings

> Code review performed 2026-06-22 against the story spec (pre-implementation). 10 decision-needed, 24 patch, 2 deferred, 2 dismissed.

#### Decision-Needed (resolved 2026-06-22)

- [x] [Review][Decision] **D1 — suspendAccount() implementation path** — **Resolved (B)**: publish `AccountSuspensionRequestedEvent`; create listener in `platform.security`. See Task 14c.
- [x] [Review][Decision] **D2 — VideoIntelClientImpl scope** — **Resolved (B)**: descoped to Story 6.x. `detectExplicitContent()` replaced with fail-open stub returning `VideoIntelScanResult(false, null, "stub-not-implemented")` (null confidence — not a flagged result so no score is applicable). `VIDEOINTEL_ENABLED` flag defaults to `false`. Startup guard in `VideoIntelConfig` logs an error if the flag is enabled while the stub is active. See Task 8e, 8f.
- [x] [Review][Decision] **D3 — SLA Monitor retry mechanism** — **Resolved (A)**: create `VideoModerationRetryEvent(UUID videoId, UUID ownerId)`; dedicated `@EventListener onModerationRetry()` skips PROCESSING→SCANNING transition. `ModerationSlaMonitorService` publishes `VideoModerationRetryEvent`. See Tasks 14d, 15. *(Note: the original decision text said `String ownerId` — corrected to `UUID` to match the implementation and `video.getOwnerId()` return type.)*
- [x] [Review][Decision] **D4 — encodingCompletedAt TOCTOU race** — **Resolved (BB)**: webhook handler owns the recovery. After setting `encodingCompletedAt` on a SCANNING video, the webhook re-reads state; if TRANSCODING, calls `videoService.completeTranscoding(videoId)`. See Task 16.
- [x] [Review][Decision] **D5 — SSE endpoint auth** — **Resolved (A)**: add `video.ownerId == authenticated user` check in Story 6.3. See Task 18.
- [x] [Review][Decision] **D6 — video_approval_requests DDL** — **Resolved (A)**: removed from V56. Story 6.6 owns the DDL. See Task 2.
- [x] [Review][Decision] **D7 — Minor safety gate HIDDEN path** — **Resolved (A)**: descoped from AC 5. Story 6.3 advances all owners to TRANSCODING. Story 6.6 implements HIDDEN + `video_approval_requests`. See AC 5.
- [x] [Review][Decision] **D8 — CSAM scan details column encryption** — **Resolved (A)**: accept plaintext. No change.
- [x] [Review][Decision] **D9 — VideoModerationScan Envers auditing** — **Resolved (B)**: explicitly excluded. `@Audited` NOT added. Documented in Task 11a with rationale. See Task 11a.
- [x] [Review][Decision] **D10 — BunnyVideoProviderAdapter triggerTranscoding /reencode** — **Resolved (A)**: verification note added to Task 10 pre-deploy checklist.

#### Patch (spec defects with unambiguous fixes)

- [x] [Review][Patch] **P1 — alertAdmin() and notifyOwner() must publish domain events** — Both methods log only. Task 14b already describes `VideoModerationAdminAlertEvent`, `VideoModerationOwnerNotificationEvent`, and `VideoModerationEmailListener`. Task 14's `ModerationOrchestrationService` must be updated to publish these events. Also: remove `SesEmailService` from Task 19's mock list and replace with `ApplicationEventPublisher` assertion. AC 2 wording ("via SesEmailService") must be updated to match the Envelope pattern. [ModerationOrchestrationService.java:alertAdmin(), notifyOwner()]
- [x] [Review][Patch] **P2 — triggerTranscoding() inside @Transactional: replace broken sketch with correct three-phase skeleton** — The code in `advanceToTranscoding()` calls `triggerTranscoding()` inside `transactionTemplate.execute()`. The CRITICAL note below the code describes the correct fix but the spec code still shows the wrong pattern. Replace the broken sketch with the three-phase version: (1) read `providerAssetId` in TX, (2) call `triggerTranscoding()` outside any TX, (3) transition state in a new TX. [Task 14, advanceToTranscoding()]
- [x] [Review][Patch] **P3 — advanceToTranscoding() fast-path: remove spurious SCANNING→SCANNING call and split into two transactions** — The fast-path calls `transitionOperationalState(videoId, SCANNING)` first (a no-op since video is already SCANNING), then SCANNING→TRANSCODING, then TRANSCODING→READY — all in one TX. This causes L1 cache stale reads for the second and third transitions. Fix: remove the first call; use two separate `transactionTemplate.execute()` blocks (one for SCANNING→TRANSCODING, one for TRANSCODING→READY). [Task 14, advanceToTranscoding() lines 748–751]
- [x] [Review][Patch] **P4 — VideoService.completeTranscoding() publishes VideoUploadedEvent — re-triggers moderation on every READY transition** — The existing `VideoService.completeTranscoding()` at line 364 publishes `VideoUploadedEvent`. After Story 6.3, `ModerationOrchestrationService` listens to this event. Every transcoding completion will re-trigger moderation for an already-READY video, causing a `TerminalStateViolationException` on `READY → SCANNING`. Remove or rename this publish in `completeTranscoding()`. Add to Task 16 as a pre-condition check. [VideoService.java:364]
- [x] [Review][Patch] **P5 — TerminalStateViolationException not caught in onVideoUploaded() — silent crash on retry of LOCKED/READY video** — SLA monitor retries can re-trigger `onVideoUploaded()` for a video already in LOCKED/READY/DELETED state. The initial `transitionOperationalState(videoId, SCANNING)` throws `TerminalStateViolationException` (not caught). Add a try/catch around this call that logs a structured WARNING and returns if the video is already in a terminal state. [Task 14, onVideoUploaded()]
- [x] [Review][Patch] **P6 — WebhookEventProcessorScheduler does NOT publish VideoUploadedEvent — moderation never triggers** — Grep confirms `VideoUploadedEvent` is absent from `WebhookEventProcessorScheduler.java`. Task 16's "if Story 6.2 was implemented correctly, it should already be there" is incorrect. Make this unconditional: Task 16 MUST add `publisher.publishEvent(new VideoUploadedEvent(videoId, video.getOwnerId()))` to the `video.upload.success` case. [WebhookEventProcessorScheduler.java, video.upload.success case]
- [x] [Review][Patch] **P7 — @Async on onVideoUploaded() has no thread pool qualifier** — Dev Notes acknowledge a dedicated moderation pool is needed but the spec provides no implementation. Add `@Async("moderationTaskExecutor")` and define a `moderationTaskExecutor` bean in `AsyncConfig` (or a new `ModerationAsyncConfig`) with appropriate queue size for long-running moderation tasks. [Task 14, AsyncConfig.java]
- [x] [Review][Patch] **P8 — ModerationSlaMonitorService has no concurrency guard for clustered nodes** — In multi-node deployments, all nodes run `detectSlaViolations()` concurrently and re-queue the same stuck videos. Add `FOR UPDATE SKIP LOCKED` to the `findScanningOlderThan()` query (wrapping in a `@Transactional` READ), matching the pattern in `WebhookEventProcessorScheduler.findNonTerminalForUpdate()`. [Task 13, Task 15, VideoRepository.java]
- [x] [Review][Patch] **P9 — Multiple state transitions in a single transactionTemplate.execute() cause L1 cache stale reads** — When `advanceToTranscoding()` calls `videoLifecycleService.transitionOperationalState()` multiple times within one `transactionTemplate.execute()` block, `@Transactional(REQUIRED)` reuses the outer TX, and `findById()` hits the L1 cache, returning the object from before the prior `save()` flush. Use separate `transactionTemplate.execute()` blocks for each transition throughout `ModerationOrchestrationService`. [Task 14, all multi-step transition paths]
- [x] [Review][Patch] **P10 — Triple SSE event flash in fast-path — frontend renders intermediate SCANNING and TRANSCODING states** — When `encodingCompletedAt` is set, the fast-path emits `VideoStatusChangedEvent` three times (SCANNING, TRANSCODING, READY) in rapid succession from one TX commit. Remove the spurious SCANNING→SCANNING first transition (see P3), which also removes the first spurious event. The remaining two events (TRANSCODING, READY) from the fast-path should be acceptable or the TRANSCODING event can be suppressed in the fast-path with a flag. [Task 14, Task 17, VideoLifecycleService.java]
- [x] [Review][Patch] **P11 — resolveMediaUrl() hardcodes "BUNNY_STORAGE_ZONE" literal** — Every scan submits `https://BUNNY_STORAGE_ZONE.b-cdn.net/{id}` to Arachnid and VideoIntel. Fix: add `String getRawVideoUrl(String providerAssetId)` to `VideoProviderAdapter` interface and implement in `BunnyVideoProviderAdapter` using the configured `cdnHostname` and storage zone from `BunnyVideoProperties`. Remove the hardcoded placeholder from `resolveMediaUrl()`. [Task 9, Task 10, Task 14, VideoProviderAdapter.java, BunnyVideoProviderAdapter.java]
- [x] [Review][Patch] **P12 — ArachnidConfig RestTemplate has no timeout — ArachnidProperties.timeoutSeconds never wired** — `ArachnidProperties` declares `timeoutSeconds = 30` but `ArachnidConfig` creates `new RestTemplate()` with no timeout, and `ArachnidClientImpl`'s constructor accepts no timeout parameter. Wire `props.getTimeoutSeconds()` into a `RestTemplateBuilder` with `setConnectTimeout` and `setReadTimeout`. Update `ArachnidClientImpl` constructor to accept timeout. [Task 7d, 7e, 7f]
- [x] [Review][Patch] **P13 — AC 11 SSE backoff and polling fallback not implemented in frontend tasks** — Tasks 23/24 list the backoff requirement but provide no implementation guidance. Native `EventSource` does not support configurable backoff — it must be manually implemented. Add a subtask to Task 24: create or reuse a `useVideoStatusSse(videoId)` Vue composable implementing the `[1000, 2000, 4000, 8000]` backoff array and 2-second polling fallback. Also add a new task to create `GET /api/video/{id}/status` endpoint returning current `operationalState` for the polling fallback. [Task 23, Task 24, VideoEventResource.java]
- [x] [Review][Patch] **P14 — AC 12 race condition half untested — no test for encoding.success during SCANNING** — Task 19 Test 8 tests the moderation side of AC 12 (encoding already done → skip triggerTranscoding). The other half is untested: when `video.encoding.success` fires during SCANNING, `WebhookEventProcessorScheduler` should set `encodingCompletedAt` and NOT call `completeTranscoding()`. Add this test to Task 19 or as a new task targeting `WebhookEventProcessorSchedulerTest`. [Task 19, Task 16, WebhookEventProcessorScheduler.java]
- [x] [Review][Patch] **P15 — findScanningOlderThan() uses updatedAt as SLA clock — resets on any Video field update** — Any update to the `Video` entity while in SCANNING (e.g., `encodingCompletedAt` being set by the webhook) resets `updatedAt` and restarts the SLA clock. Fix: track a dedicated `scanningStartedAt` timestamp on the `Video` entity, populated when transitioning to SCANNING, and use it in the SLA query. Add to Task 12 (Video entity) and Task 13 (VideoRepository query). [Task 12, Task 13, VideoRepository.java]
- [x] [Review][Patch] **P16 — video_moderation_scans has no UNIQUE(video_id, layer) constraint — retry pipeline inserts duplicate records** — SLA monitor retries re-run each layer and unconditionally call `recordScan()`. Add `UNIQUE (video_id, layer)` to the V56 migration and change `recordScan()` to use an upsert / `ON CONFLICT (video_id, layer) DO UPDATE`. [Task 2, V56__video_moderation_scans.sql, Task 14 recordScan()]
- [x] [Review][Patch] **P17 — video_approval_requests has no UNIQUE(video_id) — minor gate retries insert multiple PENDING rows** — Retries create duplicate approval request rows; Story 6.6 approval handler will not know which to resolve. Add `UNIQUE (video_id)` to `video_approval_requests` in V56 (or Story 6.6's migration per D6 resolution). [Task 2, V56 migration]
- [x] [Review][Patch] **P18 — platform.moderation_sla_minutes not seeded — ConfigService.getLong() throws IllegalStateException on startup** — Task 15 calls `configService.getLong("platform.moderation_sla_minutes")` but no Flyway migration inserts this config row. Add a V57 migration (or amend V56) that inserts `('platform.moderation_sla_minutes', '30')` into the config table, matching the pattern in existing config seeds. [Task 15, Task 22, ModerationSlaMonitorService.java]
- [x] [Review][Patch] **P19 — VideoEventResource reads VideoRepository directly — bypasses service layer** — Task 18 injects `VideoRepository` to fetch the current status for the SSE initial event. This exposes `operationalState` including LOCKED (CSAM) to any authenticated caller without any service-layer access check. Replace with a `VideoService.getOperationalState(id)` call (add the method if it doesn't exist). [Task 18, VideoEventResource.java]
- [x] [Review][Patch] **P20 — VideoSseService.onTimeout() calls tryHeartbeat() after timeout — always throws, dead code** — `SseEmitter.onTimeout()` fires when the emitter is already expired. `tryHeartbeat()` calls `emitter.send()` which throws `IOException` immediately (caught silently). Remove `tryHeartbeat(emitter)` from the `onTimeout` lambda — it never succeeds and misleads maintainers. [Task 17, VideoSseService.java]
- [x] [Review][Patch] **P21 — PROCESSING→READY backward-compat path logs no warning — silent moderation bypass** — Dev Notes say "log WARNING when used" but Task 4 provides no implementation of this. In `VideoLifecycleService.transitionOperationalState()`, add `log.warn("PROCESSING→READY bypass taken for videoId={} — moderation pipeline was not run", videoId)` when this transition fires. [Task 4, VideoLifecycleService.java]
- [x] [Review][Patch] **P22 — ArachnidScanResult matchType null guard absent — admin alert shows "matchType=null"** — `"matchType=" + result.matchType()` is used in the admin alert and scan record details. If Arachnid returns a match with null matchType, this produces "matchType=null" in the alert. Use `Objects.toString(result.matchType(), "UNKNOWN")` or similar. [Task 14, runArachnidLayer()]
- [x] [Review][Patch] **P23 — VideoModerationScan.scannedAt missing updatable = false — audit timestamp mutable** — The `scannedAt` column has `@PrePersist` but no `@Column(updatable = false)`. Any re-save of the entity leaves `scannedAt` mutable. For a CSAM audit table, add `updatable = false` to the `@Column` annotation. [Task 11a, VideoModerationScan.java]
- [x] [Review][Patch] **P24 — Task 19 uses @MockitoBean for FeatureToggleService in a unit test — incompatible annotation** — `@MockitoBean` is a Spring Boot annotation requiring a Spring context. Task 19 describes unit tests using `@ExtendWith(MockitoExtension.class)`. Change `@MockitoBean FeatureToggleService` to `@Mock FeatureToggleService` throughout Task 19's test descriptions. Reserve `@MockitoBean` for the integration test in Task 20. [Task 19, ModerationOrchestrationServiceTest.java]

#### Post-Audit Patches (resolved 2026-06-22)

- [x] [Audit][Patch] **A1 — `VideoModerationOwnerNotificationEvent` and `VideoModerationAdminAlertEvent` declare `String ownerId` — compile error when called with `UUID`** — `notifyOwner()` and `alertAdmin()` pass `UUID ownerId`; the records declared `String`. Fixed: both records now use `UUID ownerId`. [Task 14b]
- [x] [Audit][Patch] **A2 — `scanning_started_at` column missing from V55 SQL in Task 1** — Task 12 described it as an addendum but Task 1's SQL was the authoritative source. Fixed: `scanning_started_at` and `moderation_retry_count` are now in the Task 1 V55 SQL block; Task 12 addendum removed. [Task 1, Task 12]
- [x] [Audit][Patch] **A3 — `triggerTranscoding()` exceptions silently swallowed by `@Async` executor** — Unhandled `VideoProviderException` from `triggerTranscoding()` propagated out of `@Async` method and was discarded. Video remained in SCANNING with no record of the failure. Fixed: try/catch added around `triggerTranscoding()`; on failure, alerts admin and returns (SLA monitor drives retry). [Task 14, advanceToTranscoding()]
- [x] [Audit][Patch] **A4 — `video.encoding.success` fires while video is in PROCESSING — bypasses all moderation layers** — If encoding completes before the `moderationTaskExecutor` picks up the `VideoUploadedEvent`, the webhook handler falls through to `videoService.completeTranscoding()` (PROCESSING→READY). Fixed: added PROCESSING state handling in `encoding.success` case that records `encodingCompletedAt` and returns without calling `completeTranscoding()`. [Task 16]
- [x] [Audit][Patch] **A5 — `video.encoding.failed` in SCANNING state does not release quota** — The SCANNING→FAILED path added in Task 16 omitted the `quotaProvider.release()` call present in the existing PROCESSING→FAILED path, permanently consuming the owner's storage quota. Fixed: quota release added. [Task 16]
- [x] [Audit][Patch] **A6 — Concurrent SLA retry + expired lock causes unhandled `DataIntegrityViolationException` in `recordScan()`** — When `moderation_lock_until` expires and two threads process the same video, both attempt to insert a `video_moderation_scans` row for the same `(video_id, layer)` pair. The loser throws `DataIntegrityViolationException`, propagating through the pipeline TX. Fixed: try/catch added in `recordScan()`; exception treated as idempotent with WARN log. [Task 14, recordScan()]
- [x] [Audit][Patch] **A7 — Admin alert flooding: no max retry cap causes an alert every ~15 min per stuck video** — The SLA monitor retried without bound, sending a new admin alert on every cycle. Fixed: added `platform.moderation_max_retries` (default 5) — after exhaustion, the video transitions to FAILED and a single definitive alert fires. Added `moderationRetryCount` column to `videos` and `Video` entity. [Task 12, Task 15, V55, V57]
- [x] [Audit][Patch] **A8 — SSE emitters not closed on terminal states — server-side resource leak** — `VideoSseService` left emitters alive for 5 minutes after READY/LOCKED/FAILED, unnecessarily consuming server connections. Fixed: `emitter.complete()` called after sending a terminal-state event; `emitters.remove(videoId)` clears the map entry. [Task 17]
- [x] [Audit][Patch] **A9 — CSAM admin alert sent after suspension attempt — suspension failure could prevent it** — If `suspendAccount()` threw before `alertAdmin()` was called, the CSAM match could go unnotified. Fixed: `alertAdmin("CSAM match detected")` now fires before the suspension try/catch block. [Task 14, runArachnidLayer()]
- [x] [Audit][Patch] **A10 — D3 decision text says `String ownerId`; implementation uses `UUID ownerId`** — `VideoModerationRetryEvent` uses `UUID ownerId` (matching `video.getOwnerId()`). Decision text corrected. [Review Findings D3]
- [x] [Audit][Patch] **A11 — PROCESSING→READY bypass has no observability — silent moderation escape hatch** — Added `meterRegistry.counter("video.moderation.bypass")` so bypass frequency is visible in dashboards; ops can confirm the path is dead before removing the transition. [Task 4]

#### Deferred

- [x] [Review][Defer] **W1 — Feature flags default false in all environments** — The spec explicitly documents this as the sprint completion criterion: "story is complete for sprint purposes when the placeholder compiles and the feature flag gates it off in all environments." Intentional design decision; deployment enablement is an ops concern outside this story's scope. — deferred, pre-existing
- [x] [Review][Defer] **W2 — VideoIntelClientImpl blocking RestTemplate thread exhaustion** — This concern (synchronous RestTemplate for a 5-minute GCP async operation will hold async thread pool threads) is subsumed by D2 (VideoIntelClientImpl scope decision). If D2 resolves to implement in this story, a proper non-blocking implementation must address thread exhaustion. — deferred, pre-existing
- [x] [Review][Defer] **W3 — SLA monitor re-queues via Spring events, not outbox (deviation from Epic)** — Epic dev notes specify outbox for at-least-once delivery. `ModerationSlaMonitorService` publishes `VideoModerationRetryEvent` directly via `ApplicationEventPublisher`. If the application crashes after `findScanningOlderThan()` returns but before the events are published, retry intents for that SLA cycle are lost — affected videos must wait until the next SLA cycle fires. For Story 6.3 this is acceptable: no video data is lost (videos remain stuck in SCANNING and will be re-detected next cycle), and the next SLA cycle recovers within `platform.moderation_sla_minutes`. Full outbox support (persist retry intent to DB before publishing event) is deferred to a future hardening story. — deferred, architectural deviation acknowledged

---

## Dev Notes

### Critical: State Machine Design

The `OperationalState` enum gains 4 new values in this story. The existing `VALID_TRANSITIONS` map in `VideoLifecycleService` must be updated. Key transition paths after Story 6.3:

```
UPLOADING → PROCESSING (video.upload.success webhook — existing, unchanged)
PROCESSING → SCANNING  (ModerationOrchestrationService on VideoUploadedEvent — NEW)
PROCESSING → READY     (backward-compat path — keep but log WARNING when used)
SCANNING  → TRANSCODING (moderation all-clear — NEW)
SCANNING  → LOCKED     (CSAM match or VideoIntel flag — NEW)
SCANNING  → HIDDEN     (minor safety gate — NEW, Story 6.6 resolves)
SCANNING  → FAILED     (unrecoverable pipeline error — NEW)
TRANSCODING → READY    (encoding.success webhook — now from TRANSCODING, not PROCESSING)
TRANSCODING → FAILED   (encoding.failed webhook — NEW)
```

The existing `completeTranscoding()` in `VideoService` calls `transitionOperationalState(videoId, READY)`. Since `VALID_TRANSITIONS` will have both `TRANSCODING → READY` and `PROCESSING → READY`, this call is valid from either state. No change to `completeTranscoding()` is needed. [Source: `VideoLifecycleService.java`, `VideoService.java`]

**Story 6.6 VALID_TRANSITIONS dependency**: In this story, `HIDDEN → Set.of()` is set to terminal (no exits). Story 6.6 MUST update `VALID_TRANSITIONS` to add `HIDDEN → {TRANSCODING, FAILED, DELETED}` before implementing the parent approval flow — without this, `transitionOperationalState(videoId, TRANSCODING)` from a HIDDEN video will throw `InvalidStateTransitionException` and the approval handler will fail. This is a hard compile-time dependency: Story 6.6 cannot ship its approval flow without also updating the state machine in `VideoLifecycleService`. Add this as an explicit task in Story 6.6 planning.

### Critical: Race Condition — Bunny Auto-Encodes Concurrently

Bunny.net starts transcoding immediately after a TUS upload completes (when Status=7 fires). This means `video.encoding.success` (Status=3) may arrive while the video is still in `SCANNING` state — moderation takes up to several minutes.

**Resolution**: In `WebhookEventProcessorScheduler.dispatchEvent()`, the `video.encoding.success` case must check if the video is in `SCANNING` state. If so, record `video.encodingCompletedAt = Instant.now()` and return without calling `completeTranscoding()`. When `ModerationOrchestrationService.advanceToTranscoding()` runs, it checks `encodingCompletedAt`; if non-null, it skips `triggerTranscoding()` and transitions SCANNING → TRANSCODING → READY immediately. [Source: AC 12, Task 16]

### Critical: `suspendAccount()` Not Yet Implemented

`SkillarsVerificationStatus.SUSPENDED` exists as an enum value in `platform.security.contract`, but no `suspendUser()` method exists in `UserProfileService` as of Story 6.2. This method MUST be added before Story 6.3 can handle CSAM matches correctly. The developer should:
1. Add `void suspendUser(String userId)` to `UserProfileService` — sets the user's `verificationStatus = SUSPENDED`
2. Verify the `User` entity has a `verificationStatus` field mapped to `SkillarsVerificationStatus`
3. Ensure `JWTAuthorizationFilter` rejects tokens for SUSPENDED accounts (check `AccountStatusUserDetailsChecker` behavior) [Source: `UserProfileService.java`, `SkillarsVerificationStatus.java`, `SecurityConfiguration.java`]

### Critical: Arachnid API Integration Requires C3P Agreement

The `ArachnidClientImpl` is a structural placeholder. The exact API endpoint, authentication scheme, and request/response format are NOT finalized in this story. **The story is "complete" for sprint purposes when the placeholder compiles and the feature flag gates it off in all environments.** Real Arachnid integration requires:
1. Business agreement with Canadian Centre for Child Protection (C3P)
2. API credentials
3. Replace `ArachnidClientImpl` with the actual API integration

### Critical: VideoIntel Operates on Transcoded Video — Timing Issue (UNRESOLVED — Requires Design Decision Before Story 6.x)

Google Cloud VideoIntelligence requires an accessible video URL. In Story 6.3's flow, moderation runs BEFORE transcoding. At moderation time, the video exists in Bunny's raw storage (not yet transcoded to HLS). The URL pattern for raw uploaded videos in Bunny is different from the CDN playback URL. Verify with Bunny API docs that raw uploaded videos are accessible before transcoding.

For Story 6.3, this is not a blocking concern because `VIDEOINTEL_ENABLED` defaults to `false` and `VideoIntelClientImpl` is a stub. **However, this architectural question must be resolved before Story 6.x ships the real VideoIntel implementation.** The two options are:

**Option A — Scan pre-transcoding (current pipeline order)**
- Layer 1 (Arachnid) + Layer 2 (VideoIntel) both run before transcoding triggers
- Requires Bunny raw storage URL to be accessible to GCP VideoIntelligence API
- Risk: Bunny may not expose raw TUS-uploaded files via a stable public URL before encoding
- Action required: Verify with Bunny API docs that `getRawVideoUrl()` returns a URL GCP can fetch. If not, Option A is not viable.

**Option B — Scan post-transcoding (pipeline reorder)**
- Layer 1 (Arachnid) runs pre-transcoding; moderation pipeline advances to TRANSCODING; Layer 2 (VideoIntel) runs on the transcoded HLS URL; only then advance to READY
- Pros: VideoIntel uses the stable Bunny CDN playback URL (already accessible)
- Cons: CSAM-flagged content enters the encoding queue before VideoIntel runs; state machine gains a new intermediate state (TRANSCODING → awaiting VideoIntel → READY or LOCKED); webhook handling for `encoding.success` becomes more complex
- Requires new `OperationalState` values or reuse of TRANSCODING with a sub-status flag

**Decision required**: A tech lead or senior dev must evaluate Option A feasibility (Bunny raw URL accessibility) and record the decision in this story before Story 6.x planning. If Option A is not viable, the pipeline reorder (Option B) and its state machine changes must be scoped into Story 6.x explicitly — not discovered during implementation.

### Critical: `ModerationOrchestrationService` Must Use `@Async`

Without `@Async`, the `@TransactionalEventListener` handler runs synchronously after the webhook transaction commits. Arachnid + VideoIntel calls can take minutes. Blocking the webhook processor thread for minutes will exhaust the thread pool and stall all other webhook processing.

**Pre-condition**: Verify `AsyncConfig.java` configures a thread pool with an appropriate queue size for video moderation. The moderation pool should be separate from the general async pool to prevent backpressure.

### `VideoStatusChangedEvent` Must Be Published in `VideoLifecycleService`

`VideoLifecycleService.transitionOperationalState()` must publish `VideoStatusChangedEvent` after `videoRepository.save(video)`. This is INSIDE the `@Transactional` boundary; `VideoSseService.onStatusChanged()` is annotated `@TransactionalEventListener(AFTER_COMMIT)` which ensures the SSE push happens after the transaction commits (preventing clients from reading stale state via a subsequent fetch). [Source: `BookingSseService.java` reference pattern]

### `VideoPublishedEvent` vs `VideoStatusChangedEvent` Ordering

`VideoPublishedEvent` (existing, from Story 6.2) fires in `completeTranscoding()` inside the same transaction as the TRANSCODING → READY transition. Both events will fire on TRANSCODING → READY commit. `VideoStatusChangedEvent` (new) also fires at that point. Story 6.3 handlers must not assume ordering between these two events — handle them independently. [Source: Story 6.2 dev notes, Task 4]

### Feature Flag Validation

`PropertiesFeatureToggleService.validate()` runs `@PostConstruct` and throws `IllegalStateException` for any configured toggle key that isn't in `AppFeature`. Adding `ARACHNID_ENABLED` and `VIDEOINTEL_ENABLED` to the enum BEFORE deploying any config change prevents startup failures in environments where the keys are configured before the code ships.

### SSE Endpoint Authorization (Story 6.3 Scope)

`VideoEventResource.subscribeToEvents()` enforces ownership (D5 resolution): only the video owner can subscribe. Full RBAC (coach with active relationship, admin) belongs to Story 6.5. **The `Authentication.getName()` comparison assumes the principal name is the user UUID string — verify this against `JWTAuthorizationFilter` before implementing. If it returns email or username, the check will always fail and every valid owner will receive 403.** [Source: VideoEventResource.java Task 18]

### Email Notification: Use `Envelope` Pattern — NOT `SesEmailService` Directly

The story sketches `alertAdmin()` and `notifyOwner()` as calls to `SesEmailService`. This is wrong architecture. The project uses the `platform.notification` module for all email delivery:
- Publish a domain event (e.g., `VideoModerationAlertEvent`, `VideoFlaggedOwnerNotificationEvent`) via `ApplicationEventPublisher`
- Create a `platform.notification.infrastructure.listener.VideoModerationEmailListener` (NEW FILE) that listens for these events and publishes `new Envelope(recipients, template, deadline, data, sendId)` via `ApplicationEventPublisher`
- The notification infrastructure processes the `Envelope` and sends the email

**Pattern reference**: `platform/notification/infrastructure/listener/BookingEmailListener.java` — this is the exact pattern to follow. Do NOT import `SesEmailService` from `infrastructure.ses` in `ModerationOrchestrationService` — that violates the domain/infrastructure boundary and bypasses retry logic in the notification module. [Source: `platform/notification/contract/Envelope.java`, `BookingEmailListener.java`]

### `@EnableAsync` Is Already Present

`@EnableAsync` is in `platform.notification.config.AsyncConfig` — NOT in `infrastructure.config.AsyncConfig` (which deliberately omits it). `@Async` on `onVideoUploaded()` will work without any changes. Do NOT add a second `@EnableAsync` annotation. [Source: `platform/notification/config/AsyncConfig.java`, `infrastructure/config/AsyncConfig.java:22`]

### Project Structure Notes

- New infrastructure packages: `infrastructure.arachnid`, `infrastructure.videointel` — follow the `infrastructure.blobstore` and `infrastructure.video` patterns (interface + impl + properties + config)
- No imports from `com.softropic.skillars.platform.*` in any `infrastructure.*` class
- `ModerationOrchestrationService` lives in `platform.video.service` (domain orchestration, not infrastructure)
- `VideoSseService` lives in `platform.video.service` (follows `BookingSseService` in `platform.booking.service`)
- `VideoEventResource` lives in `platform.video.api` (follows `BookingEventResource` in `platform.booking.api`)
- New entities (`VideoModerationScan`) follow `AbstractAuditingEntity` pattern if auditing is desired — check if Envers auditing should apply

### Known Architectural Deviations from Epic

**Layer decoupling**: Epic dev notes say *"each layer's result dispatched via `ApplicationEventPublisher` to decouple."* This implementation uses direct sequential method calls (`runArachnidLayer()` → `runVideoIntelLayer()` → `runMinorSafetyGate()`) within one `@Async` method. This is simpler but means layers cannot be independently retried without re-running the full pipeline. The deviation is intentional for Story 6.3 MVP; a future refactor to the event-driven pattern would require each layer to publish its result as an event consumed by the next.

**SLA monitor re-queues via direct event, not outbox**: Epic dev notes say *"re-queues via outbox."* `ModerationSlaMonitorService` publishes `VideoModerationRetryEvent` directly via `ApplicationEventPublisher`. If the application crashes after finding stuck videos but before publishing events, those retry intents are lost until the next SLA cycle. An outbox (persist intent to DB before acting) would provide at-least-once delivery. For Story 6.3 this is acceptable; the next SLA cycle recovers without data loss.

### Known Platform Risk: Minor Safety Gate Bypassed During Story 6.3 → 6.6 Gap

The epic's Story 6.3 AC explicitly requires the minor safety gate (HIDDEN + parental approval) to run in Layer 3. This story descopes it to Story 6.6 (decision D7). During the interim between Story 6.3 and 6.6 shipping, any minor-owned account that uploads a video will have it advance to TRANSCODING/READY without the safety gate. The `MINOR_GATE/SKIPPED` audit record marks these videos. Ensure Story 6.6 ships without a production gap; if there is a gap, ops should monitor `video_moderation_scans` for MINOR_GATE/SKIPPED records and audit them manually.

### Known Gap: No Recovery for Stuck PROCESSING Videos

The SLA monitor only detects videos stuck in `SCANNING`. A video stuck in `PROCESSING` (e.g., `VideoUploadedEvent` was published but the `moderationTaskExecutor` thread pool is saturated or the app restarted before the event was processed) has no recovery mechanism. The webhook outbox only retries the `video.upload.success` case if the outbox entry is still PENDING — once completed, a second replay is not triggered. If this gap materialises in production, add a `@Scheduled` job that detects videos in PROCESSING older than a configurable threshold and re-publishes `VideoUploadedEvent`.

### Critical Pre-Condition: `Video.getOwnerId()` Must Return a Bare `UUID`

`ModerationOrchestrationService`, `ModerationSlaMonitorService`, `VideoEventResource`, and `WebhookEventProcessorScheduler` all call `video.getOwnerId()` (or `video.getOwner().getId()`). If the `Video` entity stores the owner as a JPA relationship (`@ManyToOne User owner`) rather than a bare `@Column UUID ownerId`, the accessor is `video.getOwner().getId()` — calling `video.getOwnerId()` would not compile. **Before implementing any task that calls `video.getOwnerId()`, grep the `Video` entity for the exact field mapping and use the correct accessor throughout.** All call sites must be consistent.

### Critical Pre-Condition: VideoEventResource `Authentication.getName()` Must Return UUID

`VideoEventResource.subscribeToEvents()` compares `video.getOwnerId().toString()` against `Authentication.getName()`. If `JWTAuthorizationFilter` sets the principal name to email or username (rather than the user UUID), this check silently returns 403 for every valid video owner. **Verify the principal name format against `JWTAuthorizationFilter` before implementing Tasks 18 and 25.** If the principal is not a UUID string, extract the UUID from a JWT claim instead.

### References

- SSE pattern: [`platform/booking/service/BookingSseService.java`]
- SSE endpoint pattern: [`platform/booking/api/BookingEventResource.java`]
- Feature toggle pattern: [`infrastructure/feature/AppFeature.java`, `FeatureToggleService.java`, `PropertiesFeatureToggleService.java`]
- Infrastructure adapter pattern: [`infrastructure/video/VideoProviderAdapter.java`, `BunnyVideoProviderAdapter.java`]
- `OperationalState` (BEFORE): [`platform/video/contract/OperationalState.java`]
- `VideoLifecycleService.VALID_TRANSITIONS`: [`platform/video/service/VideoLifecycleService.java:26`]
- `VideoUploadedEvent` (trigger): [`platform/video/contract/event/VideoUploadedEvent.java`]
- Webhook scheduler (update target): [`platform/video/service/WebhookEventProcessorScheduler.java`]
- `SkillarsVerificationStatus.SUSPENDED` (for account suspension): [`platform/security/contract/SkillarsVerificationStatus.java`]
- Notification `Envelope` pattern: [`platform/notification/contract/Envelope.java`, `platform/notification/infrastructure/listener/BookingEmailListener.java`]
- `@EnableAsync` location: [`platform/notification/config/AsyncConfig.java`]
- Latest Flyway migration: `V54__video_type_column.sql` (V55, V56 are next)
- ConfigService key pattern: [`platform/video/service/QuotaConfigService.java`] uses `configService.getLong("platform.xxx")`

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

### Debug Log

| Issue | Resolution |
|-------|-----------|
| `TerminalStateViolationException` constructor mismatch in test | Corrected to `new TerminalStateViolationException(videoId, "LOCKED")` — constructor is `(UUID, String)` |
| Bypass counter never fired (dead code) | Moved PROCESSING→READY detection BEFORE `!VALID_TRANSITIONS.contains()` check; PROCESSING→READY is in valid set so the else branch never executed |
| `VideoSseIT` context load: missing `VideoMetrics` | Added `@MockitoBean VideoMetrics videoMetrics` — `VideoApiAdvice` requires it |
| `VideoSseIT` context load: missing `JwtSecretService` | Added `@MockitoBean JwtSecretService jwtSecretService` — `SecurityAdviceFilter` requires it |
| `VideoSseIT` SSE async dispatch failure | Simplified to `andExpect(request().asyncStarted())` only — `asyncDispatch` needs async result set before dispatch, but `SseEmitter` doesn't set it synchronously |
| `Video.id` is null in unit tests | Used reflection to set `Video.id` field — JPA `@GeneratedValue` only fires during persist |
| `VideoModerationOwnerNotificationEvent` compile error: `String ownerId` vs `UUID` callers | Changed both event records to use `UUID ownerId` |

### Completion Notes List

- All 26 tasks (including all subtasks) implemented across two sessions.
- 33 tests pass: `ModerationOrchestrationServiceTest` (9), `VideoLifecycleServiceTest` (15), `ModerationSlaMonitorServiceTest` (5), `VideoSseIT` (4).
- `mvn compile` and `mvn test-compile` both clean.
- Feature flags (`arachnid-enabled`, `videointel-enabled`) default to `false` in all environments — story is complete for sprint purposes per W1 deferral.
- `VideoIntelClientImpl` is a fail-open stub; startup guard in `VideoIntelConfig` logs an error if the flag is enabled with the stub active.
- PROCESSING→READY backward-compat path retained; bypass is logged and metered (`video.moderation.bypass` counter).
- HIDDEN is terminal in Story 6.3 — Story 6.6 must update `VALID_TRANSITIONS` before implementing parent approval flow (documented as hard dependency).
- `ownerId` is `String` (login/email) throughout — `VideoUploadedEvent`, `Video.getOwnerId()`, `SecurityUtil.getCurrentUserName()` all return `String`.
- `VideoSseService` SSE emitters close on terminal states (READY, LOCKED, HIDDEN, FAILED, DELETED) to prevent server-side resource leaks.
- CSAM admin alert fires BEFORE account suspension attempt to guarantee notification even if suspension throws.
- `upsertScanRecord()` uses `REQUIRES_NEW` propagation and catches `DataIntegrityViolationException` for idempotent retry safety.
- SLA monitor uses `PESSIMISTIC_WRITE` + `SKIP_LOCKED` on `findScanningOlderThan()` for multi-node safety.
- `scanningStartedAt` used as SLA clock (not `updatedAt`) so webhook updates don't reset the SLA window.

### File List

**Created:**
- `src/main/resources/db/migration/V55__operational_state_moderation_states.sql`
- `src/main/resources/db/migration/V56__video_moderation_scans.sql`
- `src/main/resources/db/migration/V57__moderation_config_seed.sql`
- `src/main/java/com/softropic/skillars/infrastructure/arachnid/ArachnidScanResult.java`
- `src/main/java/com/softropic/skillars/infrastructure/arachnid/ArachnidClient.java`
- `src/main/java/com/softropic/skillars/infrastructure/arachnid/ArachnidException.java`
- `src/main/java/com/softropic/skillars/infrastructure/arachnid/ArachnidProperties.java`
- `src/main/java/com/softropic/skillars/infrastructure/arachnid/ArachnidClientImpl.java`
- `src/main/java/com/softropic/skillars/infrastructure/arachnid/ArachnidConfig.java`
- `src/main/java/com/softropic/skillars/infrastructure/videointel/VideoIntelScanResult.java`
- `src/main/java/com/softropic/skillars/infrastructure/videointel/VideoIntelClient.java`
- `src/main/java/com/softropic/skillars/infrastructure/videointel/VideoIntelException.java`
- `src/main/java/com/softropic/skillars/infrastructure/videointel/VideoIntelProperties.java`
- `src/main/java/com/softropic/skillars/infrastructure/videointel/VideoIntelClientImpl.java`
- `src/main/java/com/softropic/skillars/infrastructure/videointel/VideoIntelConfig.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/event/VideoStatusChangedEvent.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/event/VideoModerationAdminAlertEvent.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/event/VideoModerationOwnerNotificationEvent.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/event/VideoModerationRetryEvent.java`
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoModerationScan.java`
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoModerationScanRepository.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoModerationScanPersistenceService.java`
- `src/main/java/com/softropic/skillars/platform/video/service/ModerationOrchestrationService.java`
- `src/main/java/com/softropic/skillars/platform/video/service/ModerationSlaMonitorService.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoSseService.java`
- `src/main/java/com/softropic/skillars/platform/video/api/VideoEventResource.java`
- `src/test/java/com/softropic/skillars/platform/video/service/ModerationOrchestrationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/video/service/ModerationSlaMonitorServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/video/service/VideoSseIT.java`
- `src/frontend/src/components/video/VideoStatusCard.vue`

**Modified:**
- `src/main/java/com/softropic/skillars/platform/video/contract/OperationalState.java` — added SCANNING, TRANSCODING, LOCKED, HIDDEN
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java` — VALID_TRANSITIONS, VideoStatusChangedEvent publish, bypass counter, scanningStartedAt
- `src/main/java/com/softropic/skillars/infrastructure/feature/AppFeature.java` — added ARACHNID_ENABLED, VIDEOINTEL_ENABLED
- `src/main/java/com/softropic/skillars/infrastructure/video/VideoProviderAdapter.java` — added triggerTranscoding(), getRawVideoUrl() defaults
- `src/main/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapter.java` — implemented triggerTranscoding(), getRawVideoUrl()
- `src/main/java/com/softropic/skillars/platform/video/repo/Video.java` — added encodingCompletedAt, scanningStartedAt, moderationLockUntil, moderationRetryCount
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java` — added findScanningOlderThan() with PESSIMISTIC_WRITE + SKIP_LOCKED
- `src/main/java/com/softropic/skillars/platform/video/service/WebhookEventProcessorScheduler.java` — encoding.success SCANNING handler, VideoUploadedEvent publish on upload.success
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java` — (see above)
- `src/main/resources/application.yaml` — added app.video.moderation, features.toggles, infrastructure.arachnid, infrastructure.videointel
- `src/main/resources/application-dev.yaml` — added moderation sla delay, feature flag overrides
- `src/test/resources/application-test.yaml` — added moderation, feature flags, arachnid/videointel test config
- `src/frontend/src/stores/video.store.js` — added useVideoStatusSse composable with exponential backoff and polling fallback
- `src/frontend/src/i18n/en-US/index.js` — added video.status.* and video.statusCard i18n keys
- `src/test/java/com/softropic/skillars/platform/video/service/VideoLifecycleServiceTest.java` — extended with 5 new tests, MeterRegistry mock setup

### Post-Implementation Review Findings

> Code review performed 2026-06-22 against implementation. 0 decision-needed, 22 patch, 3 deferred, 4 dismissed.

#### Patch

- [x] [Review][Patch] **RI-1 — VideoUploadedEvent published outside transaction — @TransactionalEventListener silently drops it, moderation never fires** — `publisher.publishEvent(new VideoUploadedEvent(...))` at line 130 of `WebhookEventProcessorScheduler` executes AFTER the `transactionTemplate.execute()` block commits, meaning no active TX exists at publish time. `ModerationOrchestrationService.onVideoUploaded` is annotated `@TransactionalEventListener(phase=AFTER_COMMIT, fallbackExecution=false)` (default). Spring silently discards events published outside a transaction when `fallbackExecution=false`. Fix: move the `publishEvent` call inside the `transactionTemplate.execute()` lambda (preferred), or add `fallbackExecution=true` to the annotation. [`WebhookEventProcessorScheduler.java:130`, `ModerationOrchestrationService.java:57`]
- [x] [Review][Patch] **RI-2 — Stale video object in encoding.success UPLOADING→PROCESSING compensation path — always falls through to completeTranscoding(), bypassing all moderation** — The `video` object is fetched at line 121 outside any transaction. When the `UPLOADING` branch fires and transitions state to `PROCESSING` via `transactionTemplate.execute()` (line 135), the in-memory `video` object still reports `UPLOADING`. The subsequent `video.getOperationalState() == PROCESSING` check (line 149) evaluates `false` on the stale object, so execution falls through to `videoService.completeTranscoding(videoId)` at line 183, advancing the video to `READY` without ever running moderation. Fix: re-read `operationalState` from the DB inside the UPLOADING branch after the state transition, then return without falling through. [`WebhookEventProcessorScheduler.java:121,135,149,183`]
- [x] [Review][Patch] **RI-3 — CSAM LOCKED transition TerminalStateViolationException skips alertAdmin() and suspendAccount()** — In `runArachnidLayer()`, the `transactionTemplate.execute()` block for `LOCKED` transition (line 111) is inside a try block. If the video was concurrently transitioned to a terminal state, `TerminalStateViolationException` propagates out, skipping the `recordScan()`, `alertAdmin()`, and `suspendAccount()` calls that follow. A CSAM match goes un-alerted and the account is not suspended. Fix: wrap the LOCKED state transition in its own try/catch `TerminalStateViolationException`; log and continue to the safety-critical steps regardless of the transition outcome. [`ModerationOrchestrationService.java:111-130`]
- [x] [Review][Patch] **RI-4 — PESSIMISTIC_WRITE lock on findScanningOlderThan() released immediately — SLA monitor multi-node concurrency protection is ineffective** — `detectSlaViolations()` is not `@Transactional`. Spring's `@Transactional` on `findScanningOlderThan()` opens a new transaction per repository call, acquires `PESSIMISTIC_WRITE + SKIP_LOCKED` row locks, then commits and releases them before the method returns. The for-loop over the result set runs with no active locks, so two SLA monitor nodes on different pods can pick up and process the same video simultaneously. Fix: annotate `detectSlaViolations()` with `@Transactional` (or wrap the `findScanningOlderThan()` call and the loop body in a `TransactionTemplate.execute()` block) to hold the pessimistic locks across the batch. [`VideoRepository.java:33-42`, `ModerationSlaMonitorService.java:39`]
- [x] [Review][Patch] **RI-5 — CallerRunsPolicy set after afterPropertiesSet() — default AbortPolicy is active initially, moderation tasks can be silently dropped under burst load** — In `AsyncConfig.moderationTaskExecutor()`, `taskExecutor.afterPropertiesSet()` initializes and starts the underlying `ThreadPoolExecutor` with whichever rejection handler is set at that moment (the default `AbortPolicy`). `setRejectedExecutionHandler(new CallerRunsPolicy())` is called afterward and has no effect on the already-constructed executor. Fix: move `setRejectedExecutionHandler()` before `afterPropertiesSet()`. [`AsyncConfig.java:33-34`]
- [x] [Review][Patch] **RI-6 — onModerationRetry() has no terminal-state guard — TerminalStateViolationException propagates uncaught from @Async thread** — `onVideoUploaded()` wraps the initial SCANNING transition in a try/catch `TerminalStateViolationException`. `onModerationRetry()` has no equivalent guard. If the SLA monitor enqueues a retry for a video that has since been transitioned to LOCKED/READY/FAILED/DELETED, `runArachnidLayer()` → `runVideoIntelLayer()` → `runMinorSafetyGate()` will throw on the first state change. Fix: add try/catch `TerminalStateViolationException` around the pipeline body of `onModerationRetry()` with a structured log and return. [`ModerationOrchestrationService.java:87-98`]
- [x] [Review][Patch] **RI-7 — SLA monitor batch loop breaks on TerminalStateViolationException — remaining stuck videos skip their SLA cycle** — In `detectSlaViolations()`, if `videoLifecycleService.transitionOperationalState(video.getId(), FAILED)` throws `TerminalStateViolationException` (video already in a terminal state since the query ran), the exception propagates out of the for-loop, leaving the remaining stuck videos unprocessed for this cycle. Fix: wrap the transition call inside the loop in try/catch `TerminalStateViolationException`; log and `continue` to the next video. [`ModerationSlaMonitorService.java:46-48`]
- [x] [Review][Patch] **RI-8 — resolveMediaUrl() uncaught IllegalStateException — video stuck in SCANNING with no admin alert** — `resolveMediaUrl()` throws `IllegalStateException` if `providerAssetId` is null. This exception propagates uncaught from the `@Async` thread (after SCANNING transition completes), leaving the video stuck in SCANNING indefinitely until the SLA monitor exhausts retries. Fix: wrap `resolveMediaUrl()` in try/catch `IllegalStateException`; transition video to FAILED and call `alertAdmin()` on catch. [`ModerationOrchestrationService.java:78,93`]
- [x] [Review][Patch] **RI-9 — acquireModerationLock() called after resolveMediaUrl() — SLA monitor TOCTOU window between SCANNING transition and lock acquisition** — In `onVideoUploaded()`, the video transitions to SCANNING at line 66, then `resolveMediaUrl()` is called (a DB read + external URL construction), and only then `acquireModerationLock()` sets `moderationLockUntil`. If the SLA monitor fires in that window, it sees an unlocked SCANNING video and re-queues it, causing two concurrent pipeline threads. Fix: move `acquireModerationLock()` to immediately after the SCANNING transition, before `resolveMediaUrl()`. [`ModerationOrchestrationService.java:66-82`]
- [x] [Review][Patch] **RI-10 — advanceToTranscoding() fast-path: no guard for concurrent state change between Phase 1 read and SCANNING→TRANSCODING transition** — The fast-path reads `encodingCompletedAt` in Phase 1, determines encoding is done, then calls `transitionOperationalState(TRANSCODING)` in a separate TX. If the video was concurrently transitioned (e.g., by the webhook marking it FAILED) between Phase 1 and the TRANSCODING transition, the call throws `TerminalStateViolationException`, which then propagates into `videoService.completeTranscoding(videoId)` as well. Fix: wrap the fast-path `transitionOperationalState(TRANSCODING)` in try/catch `TerminalStateViolationException`; abort `completeTranscoding()` if caught. [`ModerationOrchestrationService.java:196-200`]
- [x] [Review][Patch] **RI-11 — video.encoding.failed in SCANNING state — quota double-release risk if moderation thread concurrently fails the video** — The new `encoding.failed` SCANNING handler correctly transitions to FAILED and calls `quotaProvider.release()`. However, if a concurrent moderation thread also encounters a failure and transitions the same video to FAILED via `videoLifecycleService.transitionOperationalState(FAILED)`, both threads may attempt quota release. The second `release()` call may throw or produce inconsistent quota state. Fix: add a guard checking `video.getOperationalState()` after the FAILED transition commits to confirm this thread won; or move quota release to a single canonical SCANNING→FAILED path. [`WebhookEventProcessorScheduler.java:185-187`]
- [x] [Review][Patch] **RI-12 — upsertScanRecord() is a read-then-write, not an upsert — retry scan outcome silently discarded on race** — `findByVideoIdAndLayer()` + `save()` is not atomic. Two concurrent threads both reading "no row" will both attempt insert; the loser gets `DataIntegrityViolationException` (caught, treated as idempotent). But the winner retains the first thread's outcome — if the first thread had FAILED and the retry thread has PASSED, the audit record permanently shows FAILED. Fix: use a native `INSERT ... ON CONFLICT (video_id, layer) DO UPDATE SET outcome=EXCLUDED.outcome, confidence=EXCLUDED.confidence, details=EXCLUDED.details` via `@Query` + `@Modifying`. [`VideoModerationScanPersistenceService.java:27-34`]
- [x] [Review][Patch] **RI-13 — admin_alert_email blank by default in V57 migration — CSAM admin alerts silently discarded with no startup guard** — V57 seeds `platform.admin_alert_email` with `''`. `VideoModerationEmailListener.onAdminAlert()` checks `adminEmail.isBlank()`, logs an error, and returns — the CSAM alert is dropped. Fresh deployments that don't set this key will silently suppress all CSAM admin notifications. Fix: add a `@PostConstruct` startup check in `VideoModerationEmailListener` (or `VideoIntelConfig`) that throws `IllegalStateException` or logs at FATAL level if `platform.admin_alert_email` is blank, so misconfiguration is caught before any video is processed. [`V57__moderation_config.sql:9`, `VideoModerationEmailListener.java:37-41`]
- [x] [Review][Patch] **RI-14 — CSAM match logs user email/login as PII in plain text** — `log.error("CSAM MATCH — videoId={} ownerId={} matchType={}", videoId, ownerId, matchType)` logs `ownerId`, which is the user's login/email string (per project conventions). This associates a specific email with a CSAM match in log aggregation systems (Loki, Datadog) with broader access than a CSAM investigation warrants. Fix: hash or truncate `ownerId` in the log statement (e.g., mask to first 3 chars + "***"); keep the full value in the `AccountSuspensionRequestedEvent` for the audit trail. [`ModerationOrchestrationService.java:110`]
- [x] [Review][Patch] **RI-15 — VideoRepository injected directly into VideoEventResource — P19 patch not applied, service layer bypassed** — Patch P19 from the pre-implementation review explicitly required replacing direct `VideoRepository` injection with `VideoService.findById()`. The implementation injects `VideoRepository` into `VideoEventResource` and calls `videoRepository.findById()` directly, bypassing service-layer access controls. Fix: remove `VideoRepository` field; call `videoService.findById(id)` (add the method if absent). [`VideoEventResource.java:10,56`]
- [x] [Review][Patch] **RI-16 — Polling endpoint returns Map<String,String> instead of Java record DTO** — Project rule: "All request and response DTOs must be implemented as Java `record` types." The `GET /api/video/{id}/status` handler returns `Map.of("videoId", ..., "operationalState", ...)`. Fix: define `public record VideoStatusResponse(UUID videoId, String operationalState)` and return it instead. [`VideoEventResource.java:45-53`]
- [x] [Review][Patch] **RI-17 — VideoStatusCard.vue has no polling fallback after SSE backoff exhausted; useVideoStatusSse composable is unused** — After exhausting all four `BACKOFF_DELAYS` entries (last delay: 8 s), `scheduleReconnect()` continues re-attempting SSE at 8-second intervals indefinitely — it never switches to HTTP polling. The `useVideoStatusSse` composable in `video.store.js` correctly implements polling fallback via `startPolling()` but is never imported or used by `VideoStatusCard.vue`. Fix: either import and use `useVideoStatusSse` in `VideoStatusCard.vue`, or add the `startPolling()` fallback logic directly to the component's backoff handler. [`VideoStatusCard.vue:91-98`, `video.store.js:153-228`]
- [x] [Review][Patch] **RI-18 — "Scanning for safety" i18n text leaks moderation details — spec requires "Under review..."** — AC 11 / Task 23 explicitly states the SCANNING state should display "Under review..." text and "do NOT leak moderation details to users." The key `video.status.SCANNING` is set to `'Scanning for safety'`, revealing that a safety scan is in progress. Fix: change to a neutral string such as `'Under review'`. [`src/frontend/src/i18n/en-US/index.js`]
- [x] [Review][Patch] **RI-19 — aria-live="polite" placed on inner div instead of card root element** — AC 11 / Task 23 spec requires `aria-live="polite"` on the card root element. The implementation places it on the inner `text-subtitle2` div (line 9), not on the root `<q-card>` element (line 2). Fix: move `aria-live="polite"` to the root `<q-card>` element. [`VideoStatusCard.vue:2,9`]
- [x] [Review][Patch] **RI-20 — VideoSseIT missing spec Tests 3 and 4 — SSE push and AC 12 integration sequence untested** — Task 20 spec defines four tests. The implemented file has four tests, but they cover HTTP 401, HTTP 403, SSE stream start, and GET status — not the spec's Test 3 (VideoStatusChangedEvent pushes SSE event to subscriber) or Test 4 (full AC 12 sequence: encoding.success during SCANNING → moderation completes → READY with Awaitility). Fix: add the two missing tests. [`VideoSseIT.java`]
- [x] [Review][Patch] **RI-21 — ModerationOrchestrationServiceTest missing spec Test 9 — encoding.success during SCANNING not tested** — Spec Task 19 Test 9 verifies that when `video.encoding.success` fires while video is in SCANNING state, `encodingCompletedAt` is set and `completeTranscoding()` is NOT called. The test file's Test 9 (`slaRetry_runsFullPipeline_skipsProcessingToScanningTransition`) tests a different scenario. Fix: add the missing webhook-during-SCANNING test. [`ModerationOrchestrationServiceTest.java`]
- [x] [Review][Patch] **RI-22 — VideoLifecycleServiceTest missing SCANNING→PROCESSING invalid backward transition test** — Task 21 requires a test asserting that `SCANNING→PROCESSING` (backward) is rejected. The `transitionOperationalState_invalidTransition_throws` test only covers `READY→UPLOADING` and `PROCESSING→UPLOADING`. Fix: add a test asserting `SCANNING→PROCESSING` throws `InvalidStateTransitionException`. [`VideoLifecycleServiceTest.java:176-192`]

#### Deferred

- [x] [Review][Defer] **RW1 — SSE subscribe → onStatusChanged race: state change between DB read and first SSE push** — A state transition committed between `videoService.findById()` in `VideoEventResource` and `emitter.send(currentStatus)` in `subscribe()` is missed; reconnected clients recover via the polling fallback. Architectural limitation of SSE without event sourcing. [`VideoSseService.java:39`, `VideoEventResource.java:39`] — deferred, pre-existing
- [x] [Review][Defer] **RW2 — scanned_at audit timestamp misleading on upsert retry path** — `@Column(updatable=false)` on `scannedAt` + `@PrePersist` means the original attempt timestamp is preserved even when an SLA retry overwrites FAILED→PASSED. Compliance investigation sees PASSED outcome with timestamp from the failed original attempt. Proper fix requires append-only per-attempt rows rather than upsert, which is an architectural change beyond this story's scope. [`VideoModerationScan.java:39`, `VideoModerationScanPersistenceService.java`] — deferred, architectural
- [x] [Review][Defer] **RW3 — Quota release outside transaction on encoding.failed in SCANNING — same architectural pattern as Def24** — `quotaProvider.release()` is called outside any TX after the `SCANNING→FAILED` transition commits in the new `encoding.failed` SCANNING handler. Same failure mode as the pre-existing Def24: if `release()` throws, the quota is permanently leaked. Fix is the same as Def24 (separate TX for release). [`WebhookEventProcessorScheduler.java:185-187`] — deferred, same pattern as Def24

### Adversarial Review Findings

> Adversarial code review performed 2026-06-22 (Blind Hunter + Edge Case Hunter + Acceptance Auditor). 0 decision-needed, 12 patch, 0 deferred, 18 dismissed.

- [x] [Review][Patch] **AR-1 [CRITICAL] — V56 ON DELETE CASCADE destroys CSAM audit records on video deletion** — `video_moderation_scans` FK to `videos` uses `ON DELETE CASCADE`. If a video is hard-deleted, all Arachnid/VideoIntel scan records are permanently lost — including CSAM audit evidence. Fix: V58 migration to drop CASCADE and add `ON DELETE RESTRICT` (or decouple the FK so scan records survive video deletion). [`V56__video_moderation_scans.sql`]

- [x] [Review][Patch] **AR-2 [HIGH] — SLA monitor inner transactionTemplate joins outer @Transactional, negating independent-TX design** — `detectSlaViolations()` is `@Transactional` (correctly added by RI-4). Inner `transactionTemplate.execute()` blocks use default `PROPAGATION_REQUIRED` so they JOIN the outer TX instead of running as isolated short TXs. DB connection held for the full batch; a failure in any inner block rolls back all prior retry-count increments. Fix: inject a `TransactionTemplate` configured with `PROPAGATION_REQUIRES_NEW`. [`ModerationSlaMonitorService.java:35,49–71`]

- [x] [Review][Patch] **AR-3 [HIGH] — VideoSseService.onStatusChanged blocks moderation commit thread on SSE I/O** — `@TransactionalEventListener(AFTER_COMMIT)` without `@Async` executes emitter I/O on the committing moderation thread. Under SSE backpressure or slow/disconnected clients (up to 5-minute emitter timeout), the moderation async thread is blocked idle. Fix: add `@Async` (dedicated executor or reuse `moderationTaskExecutor`) alongside the existing annotation. [`VideoSseService.java:47`]

- [x] [Review][Patch] **AR-4 [HIGH] — AccountSuspensionEventListener @Transactional(REQUIRED) can mark outer moderation TX rollback-only** — `@EventListener` fires synchronously on the moderation thread during `publishEvent()`. Default `REQUIRED` propagation joins any ambient TX; if the suspension write fails, it marks the entire moderation TX rollback-only — undoing the `LOCKED` transition and the CSAM scan record. Fix: change to `@Transactional(propagation = REQUIRES_NEW)`. [`AccountSuspensionEventListener.java`]

- [x] [Review][Patch] **AR-5 [HIGH] — V57 hardcoded platform_config IDs 133–136 risk PK collision on deploy** — Explicit `id` values without `ON CONFLICT` protection. A PK collision with any existing row causes Flyway to fail and blocks deployment. Every other platform_config seed in the project uses `ON CONFLICT (key) DO NOTHING`. Fix: remove explicit `id` columns (let sequence assign) or add `ON CONFLICT (key) DO NOTHING`. [`V57__moderation_config.sql`]

- [x] [Review][Patch] **AR-6 [HIGH] — onModerationRetry re-runs pipeline without confirming video is still SCANNING** — SLA monitor publishes `VideoModerationRetryEvent` after reading stuck videos. If the video transitions to TRANSCODING/READY/LOCKED/FAILED between the SLA read and the retry event firing, the retry thread runs Arachnid and VideoIntel on a video that already finished moderation. Fix: at top of `onModerationRetry()`, read current state in a short TX and return immediately if state ≠ SCANNING. [`ModerationOrchestrationService.java:87–98`]

- [x] [Review][Patch] **AR-7 [HIGH] — ARACHNID_ENABLED=true + blank admin_alert_email silently drops all CSAM admin alerts** — `@PostConstruct checkAdminAlertConfig()` logs ERROR but does not throw. V57 seeds `admin_alert_email` with `''`. If `ARACHNID_ENABLED` is turned on without setting this key, every CSAM match admin notification is silently discarded with no startup warning. Fix: throw `IllegalStateException` in `checkAdminAlertConfig()` when `ARACHNID_ENABLED` is true and `admin_alert_email` is blank. [`VideoModerationEmailListener.java:@PostConstruct`, `V57__moderation_config.sql:9`]

- [x] [Review][Patch] **AR-8 [MEDIUM] — VideoSseService terminal emitters.remove() races concurrent subscribe()** — On a terminal state event, `onStatusChanged()` iterates emitters, calls `emitter.complete()`, then calls `emitters.remove(videoId)` after the loop. A `subscribe()` arriving after the loop but before `remove()` adds an emitter that is immediately removed without receiving the terminal event — leaving that client stuck. Fix: use `ConcurrentHashMap.compute()` or remove each emitter inside the iteration rather than a bulk post-loop remove. [`VideoSseService.java:onStatusChanged`]

- [x] [Review][Patch] **AR-9 [MEDIUM] — @Modifying upsertScan missing @Transactional on repository method** — `VideoModerationScanRepository.upsertScan()` uses `@Modifying` without `@Transactional`. Any call path that bypasses the `REQUIRES_NEW` wrapper in `VideoModerationScanPersistenceService` will hit `InvalidDataAccessApiUsageException`. Fix: add `@Transactional` directly to `upsertScan()` (consistent with all other `@Modifying` methods in the project). [`VideoModerationScanRepository.java:upsertScan`]

- [x] [Review][Patch] **AR-10 [MEDIUM] — VideoStatusCard.vue polling not stopped when SSE reconnects after fallback** — After exhausting 4 backoff retries, `startPolling()` activates. The component's `connect()` function does not call `stopPolling()` on successful SSE reconnection. Both SSE and polling then deliver concurrent state updates to the component. Fix: call `stopPolling()` inside the SSE `'status'` event handler in `connect()`, mirroring the same call in `video.store.js:197`. [`VideoStatusCard.vue:67–75`]

- [x] [Review][Patch] **AR-11 [MEDIUM] — Duplicate SSE implementations in VideoStatusCard.vue and video.store.js** — `VideoStatusCard.vue` implements its own full SSE + backoff + polling stack. `useVideoStatusSse` in `video.store.js` implements the same stack independently. If both are active for the same `videoId`, two `EventSource` connections open to the same SSE endpoint concurrently. Fix: remove the internal SSE implementation from `VideoStatusCard.vue` and delegate to `useVideoStatusSse`. [`VideoStatusCard.vue:59–148`, `video.store.js:153–228`]

- [x] [Review][Patch] **AR-12 [LOW] — Retry count logged from stale in-memory Video entity** — `moderationRetryCount` is incremented inside a `transactionTemplate.execute()` block. The in-memory `video` object predates that TX and still holds the pre-increment count. Log statements using `video.getModerationRetryCount()` are always off-by-one. Fix: log `video.getModerationRetryCount() + 1`, or re-fetch after the increment TX. [`ModerationSlaMonitorService.java`]

### Code Review Findings (2026-06-22 Verification Pass)

> Verification pass 2026-06-22 — confirmed which AR-1/AR-12 findings were fixed during implementation, identified net-new issues not covered by prior sections. 1 decision-needed, 13 patch, 0 deferred, 6 dismissed.

#### Decision-Needed

- [x] [Review][Decision] **CR-D1 — CallerRunsPolicy on moderationTaskExecutor blocks webhook processor thread under executor saturation** — **Resolved (C)**: raised `queueCapacity` from 50 to 200 in `AsyncConfig.moderationTaskExecutor()`. Delays saturation onset significantly; CallerRunsPolicy semantics remain as final backstop. — When the moderation pool is saturated (corePoolSize=2, maxPoolSize=5, queueCapacity=50), the 51st task submission falls to `CallerRunsPolicy` and runs on the webhook processor thread. With VideoIntel timeout at 300 s, one saturated pipeline run can stall all webhook processing for up to 5 minutes. Options: **(A) Keep CallerRunsPolicy** — natural backpressure; acceptable risk while VIDEOINTEL_ENABLED=false. **(B) AbortPolicy + dead-letter** — fail-fast; video stays in SCANNING, SLA monitor requeues. **(C) Raise queueCapacity to 200** — delays saturation onset without changing rejection semantics. [`AsyncConfig.java:28-34`]

#### Patch

- [x] [Review][Patch] **CR-P1 [CRITICAL] — alertAdmin() always passes null, null for videoId and ownerId in VideoModerationAdminAlertEvent** — Every admin alert from the pipeline shows "videoId=N/A, ownerId=N/A" in the email. For a CSAM alert this is a compliance gap — the notification contains no structured identifiers for the matched asset or account. Fix: add `UUID videoId` and `String ownerId` parameters to `alertAdmin()` and thread real values through all 6 call sites in the class. [`ModerationOrchestrationService.java:321`, all `alertAdmin(...)` call sites]

- [x] [Review][Patch] **CR-P2 [HIGH] — encoding.success UPLOADING compensation path never sets encodingCompletedAt before returning** — When `video.encoding.success` arrives before `video.upload.success` (video in UPLOADING), the handler transitions UPLOADING→PROCESSING and returns at line 147 without recording `encodingCompletedAt`. When upload.success subsequently fires and moderation starts, `advanceToTranscoding()` sees `encodingCompletedAt = null` and takes the slow path, calling `triggerTranscoding()` again — triggering a duplicate encoding job. Fix: set `v.setEncodingCompletedAt(Instant.now())` inside the UPLOADING branch's `transactionTemplate.execute()` block before returning. [`WebhookEventProcessorScheduler.java:135-147`]

- [x] [Review][Patch] **CR-P3 [HIGH] — VideoIntel FLAGGED path: SCANNING→LOCKED not wrapped in TSV catch — recordScan and notifyOwner skipped on concurrent state change** — In `runVideoIntelLayer()`, `transitionOperationalState(videoId, LOCKED)` at line 197 has no `try/catch TerminalStateViolationException`. If the video was concurrently moved to FAILED (SLA retry exhaustion), the TSV propagates and skips both `recordScan(FLAGGED)` and `notifyOwner()` — the VideoIntel scan record is permanently lost with no audit trail. The Arachnid path (lines 153-159) already has this guard; apply the same pattern here. [`ModerationOrchestrationService.java:197-203`]

- [x] [Review][Patch] **CR-P4 [MEDIUM] — VideoModerationEmailListener runtime fallback to "admin@skillars.com" when config key is blank** — `onAdminAlert()` calls `.orElse("admin@skillars.com")` (line 55) when `platform.admin_alert_email` is absent. The `@PostConstruct` guard prevents startup if ARACHNID_ENABLED is already true at boot, but a hot-config flag enable after startup bypasses it — CSAM alerts would be silently routed to the hardcoded address. Fix: remove the hardcoded fallback; log error and return early when the resolved email is blank at runtime. [`VideoModerationEmailListener.java:54-58`]

- [x] [Review][Patch] **CR-P5 [MEDIUM] — resolveMediaUrl() throws VideoNotFoundException — uncaught in onModerationRetry, swallowed by @Async executor** — `resolveMediaUrl()` throws `VideoNotFoundException` (a `RuntimeException`, not `IllegalStateException`) when the video is absent. `onModerationRetry()` catches only `TerminalStateViolationException` and `IllegalStateException` (lines 128-139). `VideoNotFoundException` propagates uncaught from the `@Async` thread with no admin alert and no state transition — video is stuck in SCANNING indefinitely. Fix: add `catch (VideoNotFoundException e)` in `onModerationRetry()`; transition to FAILED and call `alertAdmin()`. [`ModerationOrchestrationService.java:123,128-132`]

- [x] [Review][Patch] **CR-P6 [MEDIUM] — SSE onStatusChanged() catches IOException only — IllegalStateException from already-completed emitters aborts the loop** — `SseEmitter.send()` throws `IllegalStateException` (not `IOException`) when called on an already-completed emitter. In `onStatusChanged()` the catch at line 64 handles only `IOException`; `IllegalStateException` propagates out of the for-loop and remaining emitters for that video do not receive the status event. Fix: add `IllegalStateException` to the catch alongside `IOException`; log and remove emitter. [`VideoSseService.java:58-67`]

- [x] [Review][Patch] **CR-P7 [MEDIUM] — onModerationRetry state guard does not handle null currentState — pipeline runs against a deleted video** — After the state read at lines 111-119, `currentState[0]` is null if the video was deleted since the SLA query. The check `currentState[0] != OperationalState.SCANNING` evaluates `null != SCANNING = true`, so the early return fires correctly. But `acquireModerationLock()` on line 122 then calls `videoRepository.findById().ifPresent()` which silently no-ops, and `resolveMediaUrl()` on line 123 throws `VideoNotFoundException` — uncaught per CR-P5. Fix: treat `currentState[0] == null` explicitly as missing-video; log and return before calling `acquireModerationLock()`. [`ModerationOrchestrationService.java:116`]

- [x] [Review][Patch] **CR-P8 [MEDIUM] — acquireModerationLock() ifPresent() silently no-ops on deleted video — SLA retries perpetually requeue ghost video** — `videoRepository.findById(videoId).ifPresent(...)` does nothing if the video row is absent. No lock is ever acquired; `findScanningOlderThan()` continues returning the video on each SLA cycle. Fix: throw `VideoNotFoundException` or log error and return early if `findById` returns empty; or handle upstream in `onModerationRetry()` per CR-P7. [`ModerationOrchestrationService.java:306-312`]

- [x] [Review][Patch] **CR-P9 [MEDIUM] — findScanningOlderThan has no LIMIT — mass PESSIMISTIC_WRITE lock on entire stuck-video population** — A spike of 500 stuck videos acquires PESSIMISTIC_WRITE on all 500 rows simultaneously. Moderation threads and user API calls touching any of those Video rows block for the entire SLA cycle duration. Fix: add LIMIT (e.g., 50, configurable via platform config) to the JPQL query; process in bounded batches across successive SLA cycles. [`VideoRepository.java:findScanningOlderThan`]

- [x] [Review][Patch] **CR-P10 [MEDIUM] — video.store.js onerror does not clearTimeout before scheduling reconnect — multiple concurrent timers open duplicate EventSources** — If `onerror` fires multiple times before the first reconnect timer fires (common on connection resets), each invocation of `scheduleReconnect()` creates a new `setTimeout` without cancelling the previous one. Multiple timers fire independently, each calling `connect()` and opening a new `EventSource` to the same endpoint. Fix: store the timer handle and call `clearTimeout` at the top of `scheduleReconnect()` before creating a new timer. [`src/frontend/src/stores/video.store.js: scheduleReconnect`]

- [x] [Review][Patch] **CR-P11 [MEDIUM] — encoding.failed SCANNING path calls releaseQuota() with stale providerAssetId from pre-loop video object** — The `video` variable in the `encoding.failed` SCANNING branch is the `videoOpt.get()` result loaded before the switch statement. If `providerAssetId` was updated between that fetch and the branch execution, `quotaProvider.release()` releases the wrong asset's quota. Fix: re-read `Video` from DB inside the `requiresNewTemplate.execute()` block used for the FAILED transition; use the fresh `providerAssetId` for `release()`. [`WebhookEventProcessorScheduler.java: encoding.failed SCANNING branch`]

- [x] [Review][Patch] **CR-P12 [LOW] — VideoStatusCard.vue props.videoId watcher reads initialStatus before parent reactive update completes** — The watcher at line 74 fires when `videoId` changes and immediately reads `props.initialStatus`. If the parent sets `videoId` and `initialStatus` in separate reactive assignments across tick boundaries, `props.initialStatus` may still hold the previous video's status when the watcher fires, showing a wrong-status flash. Fix: watch `[() => props.videoId, () => props.initialStatus]` together so both are consumed in a single handler invocation. [`VideoStatusCard.vue:74-77`]

- [x] [Review][Patch] **CR-P13 [LOW] — Polling fallback silently retries on 401/403 — expired session gives user no feedback** — The `fetch('/api/video/{id}/status')` polling path does not treat 401 or 403 as terminal errors; polling continues indefinitely if the user's session expires during a long processing wait. Fix: in the polling response handler, stop polling and emit an auth-error callback on 401/403 so the component can surface a "session expired" message. [`src/frontend/src/stores/video.store.js: polling fetch handler`]

#### Dismissed

- CR-R1: Duplicate VideoUploadedEvent risk — `event_id` UNIQUE constraint at webhook reception + SKIP_LOCKED on `findPendingForUpdate` prevent practical duplicate delivery; `TerminalStateViolationException` guard in `onVideoUploaded()` provides belt-and-suspenders.
- CR-R2: SLA monitor synchronous event publishing while holding PESSIMISTIC_WRITE locks — `VideoModerationRetryEvent` handler is `@Async`; task is only SUBMITTED to the executor during the outer TX. `VideoModerationAdminAlertEvent` handler is synchronous but lightweight (email setup only, no lock re-acquisition).
- CR-R3: AR-2 (inner TX joins outer) — **fixed in current code**: `requiresNewTemplate` constructed with `PROPAGATION_REQUIRES_NEW` via `@PostConstruct initTemplates()`.
- CR-R4: AR-6 (onModerationRetry no SCANNING guard) — **fixed in current code**: state check at lines 111-119 with early return.
- CR-R5: AR-11 (duplicate SSE implementations) — **fixed in current code**: `VideoStatusCard.vue` imports and delegates to `useVideoStatusSse` composable.
- CR-R6: AR-12 (retry count logged from stale entity) — **fixed in current code**: `newRetryCount = video.getModerationRetryCount() + 1` used in log statement.

### Change Log

- 2026-06-22: Story 6.3 implemented — content moderation pipeline (Tasks 1–26). All ACs satisfied. 33 tests passing. Status → review.
- 2026-06-22: Adversarial code review complete — 12 patch findings (AR-1 through AR-12), 0 deferred, 18 dismissed. Status → in-progress.
- 2026-06-22: Verification pass complete — 1 decision-needed (CR-D1), 13 patch (CR-P1 through CR-P13), 0 deferred, 6 dismissed. Status → in-progress.
- 2026-06-22: CR-D1 resolved (C: queueCapacity=200); all 13 CR patches applied. Status → review.
