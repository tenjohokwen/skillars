# Story Video-5.2: Observability Instrumentation

Status: review

## Story

As a system operator,
I want named Micrometer metrics, structured logging with request context, and confirmed `@Observed` tracing across all video operations,
So that I can monitor video module health, diagnose latency spikes, and trace requests end-to-end in production.

## Acceptance Criteria

**AC-1: `@Observed` audit — every public method instrumented**
- Given all service and resource methods from Epics 1–5 are in place
- When `@Observed` annotations are audited
- Then every public method in `AdminVideoResource`, `VideoService`, `PlaybackService`, `AdminVideoService`, `VideoLifecycleService`, `WebhookEventProcessorScheduler`, and `ReconciliationWorkerScheduler` carries `@Observed(name = "video.{descriptive-operation-name}")`
- Note: `AdminVideoResource` already has `@Observed` on all 4 methods from Story 5.1 — do NOT re-add or modify those annotations

**AC-2: Micrometer metrics recorded via `VideoMetrics` constants**
- Given an upload, playback, webhook, or reconciliation event occurs
- When it completes (success or failure)
- Then the following metrics are recorded using named constants from a `VideoMetrics` class:
  - `video.upload.init.latency` (timer, tags: `provider`, `status`)
  - `video.upload.confirm.latency` (timer, tags: `status`)
  - `video.playback.authorize.latency` (timer, tags: `status`)
  - `video.webhook.processing.latency` (timer, tags: `event_type`, `status`)
  - `video.reconciliation.cycle.duration` (timer, no tags)
  - `video.webhook.queue.depth` (gauge: count of PENDING `video_webhook_events`, sampled each webhook scheduler cycle)
  - `video.upload.session.active` (gauge: count of PENDING `upload_sessions`, sampled each webhook scheduler cycle)
  - `video.error.count` (counter, tags: `operation`, `error_code`)

**AC-3: Structured logging with MDC context**
- Given any service or scheduler method executes
- When an exception occurs or an operation completes
- Then `@Slf4j` structured logging includes MDC context fields: `videoId`, `ownerId`, `viewerId`, `operation`, `provider` where applicable
- And raw credentials, raw webhook payloads, and JWT signing secrets are never logged (NFR-10)
- Note: Most MDC fields already set in `VideoService` and `PlaybackService` — this story adds the missing `provider` field and MDC context to `AdminVideoService`

**AC-4: Unit test — `VideoMetricsTest`**
- Given `VideoMetrics` is instantiated with a `SimpleMeterRegistry`
- When all recording methods are called
- Then: all 8 metric name constants have the correct string values; gauges register at zero on `initGauges()`; `updateWebhookQueueDepth(42)` reflects 42.0 in the registered gauge; `updateActiveUploadSessions(10)` reflects 10.0 in the registered gauge; `recordUploadInitLatency("bunny", "success", 1_000_000L)` records 1 timer observation; `recordWebhookProcessingLatency("video.encoding.success", "success", 500_000L)` records with correct tags; `recordError("initialize_upload", "QUOTA_EXCEEDED")` increments the counter with both tags

## Tasks / Subtasks

- [x] Task 1: Add `countByStatus` query methods to repositories (AC: 2)
  - [x] Add `long countByStatus(VideoWebhookStatus status)` to `VideoWebhookEventRepository` — Spring Data JPA derives the query automatically, no `@Query` needed
  - [x] Add `long countByStatus(UploadSessionStatus status)` to `UploadSessionRepository` — same pattern

- [x] Task 2: Create `VideoMetrics` in `platform.video.service` (AC: 2, 4)
  - [x] 8 `public static final String` constants for all metric names
  - [x] Two `AtomicLong` fields for gauge backing: `webhookQueueDepth` and `activeUploadSessions`
  - [x] `@PostConstruct initGauges()` — registers both `Gauge` instances with the `MeterRegistry`
  - [x] Recording methods: `recordUploadInitLatency`, `recordUploadConfirmLatency`, `recordPlaybackAuthorizeLatency`, `recordWebhookProcessingLatency`, `recordReconciliationCycleDuration`, `recordError`
  - [x] Gauge update methods: `updateWebhookQueueDepth(long)`, `updateActiveUploadSessions(long)`

- [x] Task 3: Add `@Observed` to `VideoService` + inject `VideoMetrics` for latency recording (AC: 1, 2, 3)
  - [x] Add `@Observed(name = "video.upload.init")` to `initializeUpload`
  - [x] Add `@Observed(name = "video.upload.confirm")` to `confirmUpload`
  - [x] Add `@Observed(name = "video.upload.retry")` to `retryUpload`
  - [x] Inject `VideoMetrics` via `@RequiredArgsConstructor`
  - [x] Record `video.upload.init.latency` in `initializeUpload` finally block (`provider` = `properties.getProvider()`)
  - [x] Record `video.upload.confirm.latency` in `confirmUpload` finally block
  - [x] Record `video.upload.init.latency` in `retryUpload` finally block (same metric, retry is another init path)
  - [x] Add `MDC.put("provider", properties.getProvider())` at the start of `initializeUpload` and `retryUpload`
  - [x] Add `MDC.put("provider", video.getProvider())` in `confirmUpload` after loading the video entity
  - [x] Add corresponding `MDC.remove("provider")` in each finally block

- [x] Task 4: Add `@Observed` to `PlaybackService` + inject `VideoMetrics` for latency recording (AC: 1, 2)
  - [x] Add `@Observed(name = "video.playback.authorize")` to `authorizePlayback`
  - [x] Add `@Observed(name = "video.playback.revokeTokens")` to `revokeTokensForViewer`
  - [x] Add `@Observed(name = "video.playback.validateToken")` to `validateToken`
  - [x] Inject `VideoMetrics` via `@RequiredArgsConstructor`
  - [x] Record `video.playback.authorize.latency` in `authorizePlayback` finally block

- [x] Task 5: Add `@Observed` + MDC to `AdminVideoService` (AC: 1, 3)
  - [x] Add `@Observed(name = "video.admin.setAccessState")` to `setVideoAccessState`
  - [x] Add `@Observed(name = "video.admin.deleteVideo")` to `deleteVideo`
  - [x] Add `@Observed(name = "video.admin.getUploadSession")` to `getUploadSession`
  - [x] Add `@Observed(name = "video.admin.listVideoSessions")` to `listVideoSessions`
  - [x] Add `@Observed(name = "video.admin.triggerReconciliation")` to `triggerReconciliation`
  - [x] Add MDC `operation` and `videoId` (where applicable) context to each public method with try/finally cleanup

- [x] Task 6: Add `@Observed` to `VideoLifecycleService` (AC: 1)
  - [x] Add `@Observed(name = "video.lifecycle.transitionState")` to `transitionOperationalState`
  - [x] Add `@Observed(name = "video.lifecycle.setAccessState")` to `setAccessState`
  - [x] Add `@Observed(name = "video.lifecycle.isPlaybackEligible")` to `isPlaybackEligible`

- [x] Task 7: Add `@Observed` + metrics to `WebhookEventProcessorScheduler` (AC: 1, 2)
  - [x] Add `@Observed(name = "video.webhook.processQueue")` to `processPending`
  - [x] Inject `VideoMetrics`, `VideoWebhookEventRepository` (for count query), `UploadSessionRepository` (for count query) via `@RequiredArgsConstructor`
  - [x] At the start of `processPending()`, sample gauges: `videoMetrics.updateWebhookQueueDepth(webhookEventRepository.countByStatus(PENDING))` and `videoMetrics.updateActiveUploadSessions(uploadSessionRepository.countByStatus(PENDING))`
  - [x] Track per-event start time; record `video.webhook.processing.latency` per event in the per-event try/catch with `event.getEventType()` and `success/error` status

- [x] Task 8: Add `@Observed` + metrics to `ReconciliationWorkerScheduler` (AC: 1, 2, 3)
  - [x] Add `@Observed(name = "video.reconciliation.runCycle")` to `reconcile`
  - [x] Inject `VideoMetrics` via `@RequiredArgsConstructor`
  - [x] Track overall cycle start time at top of `reconcile()`; record `video.reconciliation.cycle.duration` in a finally block wrapping the entire method body
  - [x] Add `MDC.put("provider", video.getProvider())` in the per-video loop (alongside existing `videoId`, `providerAssetId`, `localState`); add corresponding `MDC.remove("provider")` in the per-video finally block

- [x] Task 9: Add error counter recording to `VideoApiAdvice` (AC: 2)
  - [x] Add `VideoMetrics videoMetrics` field to `VideoApiAdvice`; add it as a second constructor parameter alongside `MessageSource`
  - [x] In each `@ExceptionHandler`, call `videoMetrics.recordError(MDC.get("operation") != null ? MDC.get("operation") : "unknown", errorCode)` where `errorCode` is the `VideoErrorCode.X.getErrorCode()` string
  - [x] Add `import org.slf4j.MDC`

- [x] Task 10: Create `VideoMetricsTest` unit test (AC: 4)
  - [x] Use `new SimpleMeterRegistry()` — no Spring context needed; pure unit test
  - [x] Call `videoMetrics.initGauges()` explicitly in `@BeforeEach` (same pattern as `StorageMetricsTest`)
  - [x] See Dev Notes for full test structure

## Dev Notes

### Architecture Compliance

- `VideoMetrics` → `platform.video.service` ✓ (business-specific metric names; NOT infrastructure)
- All `@Observed` imports → `io.micrometer.observation.annotation.Observed` (already on classpath — `AdminVideoResource` and `VideoWebhookResource` already use it)
- `VideoMetrics` is a `@Service` bean — Spring registers it automatically; no manual `@Bean` registration needed
- `AdminVideoResource` already has `@Observed` on all 4 methods from Story 5.1 — DO NOT modify that file
- `VideoWebhookResource` already has `@Observed(name = "video.webhook.receive")` from Story 4.1 — DO NOT modify
- `UploadSessionExpiryScheduler` is NOT in the AC-1 audit list — DO NOT add `@Observed` to it

### Critical: Timer Recording Pattern

Follow the exact same pattern as `StorageMetrics` + `FileStorageService`:

```java
// At method start (outside try block):
long start = System.nanoTime();
boolean success = false;

// ... method body ...
success = true;

// In finally block:
String status = success ? "success" : "error";
videoMetrics.recordXxxLatency(/* tags */, status, System.nanoTime() - start);
```

For `VideoService`, `success` is already tracked as a local boolean in `initializeUpload()` and `retryUpload()` — use those existing flags. For `confirmUpload()`, add a `boolean success = false` local variable.

### Critical: Gauge Sampling Approach

Gauges use `AtomicLong` backing (same as `StorageMetrics.replicationQueueDepth`). They are NOT read-through to the DB on each access — they are updated by scheduler calls:

```java
// In WebhookEventProcessorScheduler.processPending() at top of method (before batch fetch):
videoMetrics.updateWebhookQueueDepth(webhookEventRepository.countByStatus(VideoWebhookStatus.PENDING));
videoMetrics.updateActiveUploadSessions(uploadSessionRepository.countByStatus(UploadSessionStatus.PENDING));
```

The `countByStatus` Spring Data derived methods are added in Task 1 — zero `@Query` boilerplate needed.

### VideoMetrics — Full Implementation

**File:** `src/main/java/com/softropic/skillars/platform/video/service/VideoMetrics.java`

```java
package com.softropic.skillars.platform.video.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class VideoMetrics {

    public static final String UPLOAD_INIT_LATENCY = "video.upload.init.latency";
    public static final String UPLOAD_CONFIRM_LATENCY = "video.upload.confirm.latency";
    public static final String PLAYBACK_AUTHORIZE_LATENCY = "video.playback.authorize.latency";
    public static final String WEBHOOK_PROCESSING_LATENCY = "video.webhook.processing.latency";
    public static final String RECONCILIATION_CYCLE_DURATION = "video.reconciliation.cycle.duration";
    public static final String WEBHOOK_QUEUE_DEPTH = "video.webhook.queue.depth";
    public static final String UPLOAD_SESSION_ACTIVE = "video.upload.session.active";
    public static final String ERROR_COUNT = "video.error.count";

    private final MeterRegistry meterRegistry;
    private final AtomicLong webhookQueueDepth = new AtomicLong(0L);
    private final AtomicLong activeUploadSessions = new AtomicLong(0L);

    public VideoMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void initGauges() {
        Gauge.builder(WEBHOOK_QUEUE_DEPTH, webhookQueueDepth, AtomicLong::get)
            .description("Count of PENDING video webhook events")
            .register(meterRegistry);
        Gauge.builder(UPLOAD_SESSION_ACTIVE, activeUploadSessions, AtomicLong::get)
            .description("Count of PENDING upload sessions")
            .register(meterRegistry);
    }

    public void recordUploadInitLatency(String provider, String status, long nanos) {
        Timer.builder(UPLOAD_INIT_LATENCY)
            .tag("provider", provider)
            .tag("status", status)
            .register(meterRegistry)
            .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordUploadConfirmLatency(String status, long nanos) {
        Timer.builder(UPLOAD_CONFIRM_LATENCY)
            .tag("status", status)
            .register(meterRegistry)
            .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordPlaybackAuthorizeLatency(String status, long nanos) {
        Timer.builder(PLAYBACK_AUTHORIZE_LATENCY)
            .tag("status", status)
            .register(meterRegistry)
            .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordWebhookProcessingLatency(String eventType, String status, long nanos) {
        Timer.builder(WEBHOOK_PROCESSING_LATENCY)
            .tag("event_type", eventType)
            .tag("status", status)
            .register(meterRegistry)
            .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordReconciliationCycleDuration(long nanos) {
        Timer.builder(RECONCILIATION_CYCLE_DURATION)
            .register(meterRegistry)
            .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordError(String operation, String errorCode) {
        Counter.builder(ERROR_COUNT)
            .tag("operation", operation)
            .tag("error_code", errorCode)
            .register(meterRegistry)
            .increment();
    }

    public void updateWebhookQueueDepth(long count) {
        webhookQueueDepth.set(count);
    }

    public void updateActiveUploadSessions(long count) {
        activeUploadSessions.set(count);
    }
}
```

### Repository Additions (Task 1)

Add one line to each repository interface — Spring Data derives the COUNT query automatically:

```java
// VideoWebhookEventRepository.java — add:
long countByStatus(VideoWebhookStatus status);

// UploadSessionRepository.java — add:
long countByStatus(UploadSessionStatus status);
```

No `@Query` annotation needed. The enum value is passed directly.

### VideoService — Minimal Diff

Current `VideoService` already has `boolean success = false` tracking in `initializeUpload()` and `retryUpload()`. Changes needed:

1. Add `VideoMetrics videoMetrics` to the constructor injection list
2. Add `@Observed` to all 3 public methods
3. Add `provider` MDC field and metric recording in each method's finally block
4. Add `boolean success = false` local variable to `confirmUpload()` (currently lacks it)

**`initializeUpload` changes:**
```java
@Observed(name = "video.upload.init")
public InitializeUploadResponse initializeUpload(InitializeUploadRequest request) {
    MDC.put("operation", "initialize_upload");
    MDC.put("ownerId", request.ownerId());
    MDC.put("provider", properties.getProvider());   // NEW
    long start = System.nanoTime();                  // NEW
    // ... existing code unchanged ...
    finally {
        String timerStatus = success ? "success" : "error";             // NEW
        videoMetrics.recordUploadInitLatency(                           // NEW
            properties.getProvider(), timerStatus, System.nanoTime() - start); // NEW
        MDC.remove("operation");
        MDC.remove("ownerId");
        MDC.remove("videoId");
        MDC.remove("provider");  // NEW
    }
}
```

**`confirmUpload` changes:**
```java
@Observed(name = "video.upload.confirm")
@Transactional
public ConfirmUploadResponse confirmUpload(UUID videoId) {
    MDC.put("operation", "confirm_upload");
    MDC.put("videoId", videoId.toString());
    long start = System.nanoTime();   // NEW
    boolean success = false;           // NEW
    try {
        Video video = videoRepository.findById(videoId)...;
        MDC.put("provider", video.getProvider());  // NEW — after loading video

        // ... existing session logic ...
        success = true;  // NEW — set before return
        return new ConfirmUploadResponse(videoId, OperationalState.PROCESSING);
    } finally {
        videoMetrics.recordUploadConfirmLatency(success ? "success" : "error",  // NEW
            System.nanoTime() - start);
        MDC.remove("operation");
        MDC.remove("videoId");
        MDC.remove("provider");  // NEW
    }
}
```

**`retryUpload` changes:** Same pattern — add `provider` MDC from `properties.getProvider()`, track `start`, record `video.upload.init.latency` in finally (retry is an init path).

### PlaybackService — Minimal Diff

Add `VideoMetrics videoMetrics` to constructor injection. Add `@Observed` to all 3 methods. Add `start` tracking and `success` flag to `authorizePlayback`; record latency in finally block:

```java
@Observed(name = "video.playback.authorize")
@Transactional
public PlaybackAuthorizationResponse authorizePlayback(UUID videoId, String viewerId) {
    long start = System.nanoTime();   // NEW
    boolean success = false;           // NEW
    MDC.put(...);
    try {
        // ... existing code ...
        success = true;  // before return
        return new PlaybackAuthorizationResponse(...);
    } finally {
        videoMetrics.recordPlaybackAuthorizeLatency(  // NEW
            success ? "success" : "error", System.nanoTime() - start);
        MDC.remove(...);
    }
}
```

`@Observed(name = "video.playback.revokeTokens")` on `revokeTokensForViewer` — no latency recording needed, just the annotation.

`@Observed(name = "video.playback.validateToken")` on `validateToken` — no latency recording needed, just the annotation.

### AdminVideoService — MDC Additions

Add operation/videoId MDC context to each public method (try/finally pattern, same as VideoService). The existing `log.info("Admin deleted video {}", videoId)` in `deleteVideo` should remain — just add MDC context around it:

```java
@Observed(name = "video.admin.setAccessState")
public Video setVideoAccessState(UUID videoId, AccessState newAccessState) {
    MDC.put("operation", "admin.setAccessState");
    MDC.put("videoId", videoId.toString());
    try {
        return videoLifecycleService.setAccessState(videoId, newAccessState);
    } finally {
        MDC.remove("operation");
        MDC.remove("videoId");
    }
}

@Observed(name = "video.admin.deleteVideo")
public void deleteVideo(UUID videoId) {
    MDC.put("operation", "admin.deleteVideo");
    MDC.put("videoId", videoId.toString());
    try {
        // ... existing implementation ...
    } finally {
        MDC.remove("operation");
        MDC.remove("videoId");
    }
}
// Apply same pattern to getUploadSession, listVideoSessions, triggerReconciliation
```

Note: `getUploadSession` uses `uploadSessionId`, not `videoId` — set `MDC.put("uploadSessionId", uploadSessionId.toString())`.

### VideoLifecycleService — Annotation-Only Changes

Only add `@Observed` imports and annotations. No other changes:

```java
import io.micrometer.observation.annotation.Observed;

@Observed(name = "video.lifecycle.transitionState")
@Transactional
public Video transitionOperationalState(UUID videoId, OperationalState newState) { ... }

@Observed(name = "video.lifecycle.setAccessState")
@Transactional
public Video setAccessState(UUID videoId, AccessState newAccessState) { ... }

@Observed(name = "video.lifecycle.isPlaybackEligible")
@Transactional(readOnly = true)
public boolean isPlaybackEligible(UUID videoId) { ... }
```

### WebhookEventProcessorScheduler — Gauge Sampling + Per-Event Latency

New injected field: `VideoMetrics videoMetrics` (add to `@RequiredArgsConstructor`).
Also need `UploadSessionRepository uploadSessionRepository` for counting active sessions (add to `@RequiredArgsConstructor`).

```java
@Observed(name = "video.webhook.processQueue")
@Scheduled(fixedDelayString = "${app.video.webhook.processor-delay-ms:5000}")
public void processPending() {
    // Sample gauges at start of each cycle
    videoMetrics.updateWebhookQueueDepth(
        webhookEventRepository.countByStatus(VideoWebhookStatus.PENDING));
    videoMetrics.updateActiveUploadSessions(
        uploadSessionRepository.countByStatus(UploadSessionStatus.PENDING));

    int batchSize = properties.getReconciliation().getBatchSize();
    // ... existing batch fetch ...

    for (VideoWebhookEvent event : events) {
        long eventStart = System.nanoTime();  // NEW
        MDC.put("webhookEventId", ...);
        // ...
        try {
            // ... existing dispatch logic ...
            videoMetrics.recordWebhookProcessingLatency(  // NEW
                event.getEventType(), "success", System.nanoTime() - eventStart);
        } catch (Exception ex) {
            videoMetrics.recordWebhookProcessingLatency(  // NEW
                event.getEventType(), "error", System.nanoTime() - eventStart);
            log.warn(...);
            handleFailure(event, ex);
        } finally {
            MDC.remove(...);
        }
    }
}
```

### ReconciliationWorkerScheduler — Cycle Duration + Provider MDC

New injected field: `VideoMetrics videoMetrics` (add to `@RequiredArgsConstructor`).

```java
@Observed(name = "video.reconciliation.runCycle")
@Scheduled(fixedDelayString = "${app.video.reconciliation.fixed-delay-ms:60000}")
public void reconcile() {
    long cycleStart = System.nanoTime();  // NEW
    try {
        // ... existing batch logic ...
        for (Video video : videos) {
            MDC.put("videoId", ...);
            MDC.put("providerAssetId", ...);
            MDC.put("localState", ...);
            MDC.put("provider", video.getProvider());  // NEW
            try {
                // ... existing reconciliation logic ...
            } catch (VideoProviderException e) { ... }
            finally {
                MDC.remove("videoId");
                MDC.remove("providerAssetId");
                MDC.remove("localState");
                MDC.remove("provider");  // NEW
            }
        }
    } finally {
        videoMetrics.recordReconciliationCycleDuration(System.nanoTime() - cycleStart);  // NEW
    }
}
```

### VideoApiAdvice — Error Counter

Add `VideoMetrics videoMetrics` as second constructor parameter:

```java
import org.slf4j.MDC;

public VideoApiAdvice(MessageSource messageSource, VideoMetrics videoMetrics) {
    this.messageSource = messageSource;
    this.videoMetrics = videoMetrics;
}
```

Add `private final VideoMetrics videoMetrics;` field.

In each handler, add the error recording after calling `logErrorAndReturnDTO`:

```java
@ExceptionHandler(VideoNotFoundException.class)
@ResponseStatus(HttpStatus.NOT_FOUND)
public ErrorDto videoNotFoundHandler(final VideoNotFoundException ex) {
    ErrorDto dto = logErrorAndReturnDTO(ex, "video.notFound", VideoErrorCode.VIDEO_NOT_FOUND.getErrorCode());
    videoMetrics.recordError(operationFromMdc(), VideoErrorCode.VIDEO_NOT_FOUND.getErrorCode());
    return dto;
}
```

Add a private helper to `VideoApiAdvice`:
```java
private String operationFromMdc() {
    String op = MDC.get("operation");
    return op != null ? op : "unknown";
}
```

Apply `operationFromMdc()` + `VideoErrorCode.X.getErrorCode()` to all 7 `@ExceptionHandler` methods.

### VideoMetricsTest — Unit Test Structure

**File:** `src/test/java/com/softropic/skillars/platform/video/service/VideoMetricsTest.java`

```java
package com.softropic.skillars.platform.video.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class VideoMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private VideoMetrics videoMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        videoMetrics = new VideoMetrics(meterRegistry);
        videoMetrics.initGauges();
    }

    @Test
    void constantsHaveExpectedValues() {
        assertThat(VideoMetrics.UPLOAD_INIT_LATENCY).isEqualTo("video.upload.init.latency");
        assertThat(VideoMetrics.UPLOAD_CONFIRM_LATENCY).isEqualTo("video.upload.confirm.latency");
        assertThat(VideoMetrics.PLAYBACK_AUTHORIZE_LATENCY).isEqualTo("video.playback.authorize.latency");
        assertThat(VideoMetrics.WEBHOOK_PROCESSING_LATENCY).isEqualTo("video.webhook.processing.latency");
        assertThat(VideoMetrics.RECONCILIATION_CYCLE_DURATION).isEqualTo("video.reconciliation.cycle.duration");
        assertThat(VideoMetrics.WEBHOOK_QUEUE_DEPTH).isEqualTo("video.webhook.queue.depth");
        assertThat(VideoMetrics.UPLOAD_SESSION_ACTIVE).isEqualTo("video.upload.session.active");
        assertThat(VideoMetrics.ERROR_COUNT).isEqualTo("video.error.count");
    }

    @Test
    void initGauges_registersWebhookQueueDepthAtZero() {
        Gauge gauge = meterRegistry.find(VideoMetrics.WEBHOOK_QUEUE_DEPTH).gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(0.0);
    }

    @Test
    void initGauges_registersActiveUploadSessionsAtZero() {
        Gauge gauge = meterRegistry.find(VideoMetrics.UPLOAD_SESSION_ACTIVE).gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(0.0);
    }

    @Test
    void updateWebhookQueueDepth_reflectsNewValue() {
        videoMetrics.updateWebhookQueueDepth(42L);
        Gauge gauge = meterRegistry.find(VideoMetrics.WEBHOOK_QUEUE_DEPTH).gauge();
        assertThat(gauge.value()).isEqualTo(42.0);
    }

    @Test
    void updateActiveUploadSessions_reflectsNewValue() {
        videoMetrics.updateActiveUploadSessions(10L);
        Gauge gauge = meterRegistry.find(VideoMetrics.UPLOAD_SESSION_ACTIVE).gauge();
        assertThat(gauge.value()).isEqualTo(10.0);
    }

    @Test
    void recordUploadInitLatency_registersTimerWithProviderAndStatusTags() {
        videoMetrics.recordUploadInitLatency("bunny", "success", 1_000_000L);

        Timer timer = meterRegistry.find(VideoMetrics.UPLOAD_INIT_LATENCY)
            .tag("provider", "bunny").tag("status", "success").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void recordWebhookProcessingLatency_registersTimerWithEventTypeAndStatusTags() {
        videoMetrics.recordWebhookProcessingLatency("video.encoding.success", "success", 500_000L);

        Timer timer = meterRegistry.find(VideoMetrics.WEBHOOK_PROCESSING_LATENCY)
            .tag("event_type", "video.encoding.success").tag("status", "success").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void recordError_incrementsCounterWithBothTags() {
        videoMetrics.recordError("initialize_upload", "QUOTA_EXCEEDED");

        Counter counter = meterRegistry.find(VideoMetrics.ERROR_COUNT)
            .tag("operation", "initialize_upload").tag("error_code", "QUOTA_EXCEEDED").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordUploadConfirmLatency_registersTimerWithStatusTag() {
        videoMetrics.recordUploadConfirmLatency("error", 200_000L);

        Timer timer = meterRegistry.find(VideoMetrics.UPLOAD_CONFIRM_LATENCY)
            .tag("status", "error").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void recordReconciliationCycleDuration_registersTimer() {
        videoMetrics.recordReconciliationCycleDuration(5_000_000_000L);

        Timer timer = meterRegistry.find(VideoMetrics.RECONCILIATION_CYCLE_DURATION).timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isEqualTo(5_000_000_000.0);
    }
}
```

### Existing Files — Change Summary

| Component | Path | Action | What Changes |
|---|---|---|---|
| `VideoWebhookEventRepository` | `platform.video.repo` | UPDATE | Add `countByStatus(VideoWebhookStatus)` |
| `UploadSessionRepository` | `platform.video.repo` | UPDATE | Add `countByStatus(UploadSessionStatus)` |
| `VideoService` | `platform.video.service` | UPDATE | `@Observed` × 3, inject `VideoMetrics`, `provider` MDC, latency recording |
| `PlaybackService` | `platform.video.service` | UPDATE | `@Observed` × 3, inject `VideoMetrics`, latency recording on `authorizePlayback` |
| `AdminVideoService` | `platform.video.service` | UPDATE | `@Observed` × 5, MDC context on all public methods |
| `VideoLifecycleService` | `platform.video.service` | UPDATE | `@Observed` × 3 — annotation-only, no other changes |
| `WebhookEventProcessorScheduler` | `platform.video.service` | UPDATE | `@Observed`, inject `VideoMetrics` + `UploadSessionRepository`, gauge sampling, per-event latency |
| `ReconciliationWorkerScheduler` | `platform.video.service` | UPDATE | `@Observed`, inject `VideoMetrics`, cycle duration timer, `provider` MDC |
| `VideoApiAdvice` | `platform.video.api` | UPDATE | Inject `VideoMetrics`, add `operationFromMdc()` helper, `recordError` call in all 7 handlers |
| `AdminVideoResource` | `platform.video.api` | NO CHANGE | Already has `@Observed` from Story 5.1 |
| `VideoWebhookResource` | `platform.video.api` | NO CHANGE | Already has `@Observed` from Story 4.1 |
| `UploadSessionExpiryScheduler` | `platform.video.service` | NO CHANGE | Not in AC-1 audit scope |

### Import Reference

```java
// @Observed
import io.micrometer.observation.annotation.Observed;

// VideoMetrics (in platform.video.service)
import com.softropic.skillars.platform.video.service.VideoMetrics;

// MDC (already imported in VideoService and PlaybackService)
import org.slf4j.MDC;

// Micrometer (in VideoMetrics.java)
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
```

### No New Flyway Migration Needed

This story adds only Java instrumentation — no schema changes.

### References

- `StorageMetrics.java`: `src/main/java/com/softropic/skillars/infrastructure/blobstore/service/StorageMetrics.java` — reference pattern for `AtomicLong` gauges, `Timer.builder`, `Counter.builder`, `@PostConstruct initGauge()`
- `StorageMetricsTest.java`: `src/test/java/com/softropic/skillars/infrastructure/storage/service/StorageMetricsTest.java` — reference for `SimpleMeterRegistry` test pattern
- `FileStorageService.java`: `src/main/java/com/softropic/skillars/platform/filestorage/service/FileStorageService.java` — reference for `System.nanoTime()` timer recording with `success/error` status tags
- `StorageResource.java`: `src/main/java/com/softropic/skillars/platform/filestorage/api/StorageResource.java` — reference for `@Observed` on REST controllers
- `AdminVideoResource.java`: already has `@Observed` — DO NOT modify
- Epic 5 Story 5.2 ACs: `_bmad-output/planning-artifacts/video-module/epics.md` §"Story 5.2: Observability Instrumentation"
- FRs covered: NFR-14 (`@Observed` on all service/resource methods)
- `project-context.md` rule: `@Observed(name = "...")` on resources to enable metrics; `@Slf4j` on services

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

- Created `VideoMetrics` as a `@Service` in `platform.video.service` with 8 metric name constants, 2 AtomicLong-backed gauges, 6 recording methods. Follows exact `StorageMetrics` pattern.
- Added `countByStatus` Spring Data derived methods to `VideoWebhookEventRepository` and `UploadSessionRepository` — no `@Query` needed.
- Added `@Observed` to all 3 `VideoService` methods; injected `VideoMetrics`; added `provider` MDC field and latency recording in each finally block. `confirmUpload` got `boolean success` tracking and `MDC.put("provider", video.getProvider())` after entity load.
- Added `@Observed` to all 3 `PlaybackService` methods; injected `VideoMetrics`; `authorizePlayback` records `video.playback.authorize.latency` with success/error status.
- Added `@Observed` and MDC try/finally context (`operation` + `videoId`/`uploadSessionId`) to all 5 `AdminVideoService` public methods.
- Added `@Observed` annotation-only to all 3 `VideoLifecycleService` public methods.
- Added `@Observed`, `VideoMetrics`, and `UploadSessionRepository` injection to `WebhookEventProcessorScheduler`; gauge sampling at start of each cycle; per-event latency tracking with success/error in try/catch.
- Added `@Observed`, `VideoMetrics` injection, and `video.reconciliation.cycle.duration` timer wrapping the full `reconcile()` body; `provider` MDC field added in per-video loop.
- Updated `VideoApiAdvice` constructor to accept `VideoMetrics` as second parameter; added `operationFromMdc()` helper; added `recordError` call in all 7 `@ExceptionHandler` methods.
- Fixed regression in `AdminLoginResourceTest` (`@WebMvcTest` slice doesn't scan `@Service` beans) by adding `@MockitoBean VideoMetrics videoMetrics` alongside the existing `@MockitoBean JwtSecretService`.
- `VideoMetricsTest`: 10 pure unit tests (no Spring context) using `SimpleMeterRegistry` — all 8 constant values, both gauges register at zero, gauge updates, timer recordings with tags, counter increment. All pass.
- Full regression suite: BUILD SUCCESS (all test classes pass, 0 failures, 0 errors).

### File List

- src/main/java/com/softropic/skillars/platform/video/repo/VideoWebhookEventRepository.java
- src/main/java/com/softropic/skillars/platform/video/repo/UploadSessionRepository.java
- src/main/java/com/softropic/skillars/platform/video/service/VideoMetrics.java (NEW)
- src/main/java/com/softropic/skillars/platform/video/service/VideoService.java
- src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java
- src/main/java/com/softropic/skillars/platform/video/service/AdminVideoService.java
- src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java
- src/main/java/com/softropic/skillars/platform/video/service/WebhookEventProcessorScheduler.java
- src/main/java/com/softropic/skillars/platform/video/service/ReconciliationWorkerScheduler.java
- src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java
- src/test/java/com/softropic/skillars/platform/video/service/VideoMetricsTest.java (NEW)
- src/test/java/com/softropic/skillars/platform/video/service/PlaybackRevocationWindowUnitTest.java
- src/test/java/com/softropic/skillars/platform/security/api/AdminLoginResourceTest.java

## Change Log

- 2026-06-02: Implemented observability instrumentation for video module — created VideoMetrics service, added @Observed to all 12 service/scheduler methods, added provider MDC fields, recorded 5 latency timers + 2 AtomicLong gauges + 1 error counter, added VideoMetricsTest (10 unit tests). Fixed @WebMvcTest regression in AdminLoginResourceTest caused by new VideoMetrics dependency on VideoApiAdvice.
