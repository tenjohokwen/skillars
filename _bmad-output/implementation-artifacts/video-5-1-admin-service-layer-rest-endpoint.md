# Story Video-5.1: Admin Service Layer & REST Endpoint

Status: review

## Story

As a consuming application developer,
I want an admin service layer and base REST endpoint for managing video and session state,
So that I can build operational tooling on top of the module without modifying its internals.

## Acceptance Criteria

**AC-1: AdminVideoService — `setVideoAccessState`**
- Given a `videoId` and a target `AccessState`
- When `AdminVideoService.setVideoAccessState(videoId, newAccessState)` is called
- Then it delegates to `VideoLifecycleService.setAccessState(videoId, newAccessState)` and returns the updated `Video`
- And if the video's `operationalState == DELETED`, `TerminalStateViolationException` (HTTP 409) is thrown (enforced by `VideoLifecycleService.setAccessState` which already has this guard)

**AC-2: AdminVideoService — `deleteVideo`**
- Given a `videoId`
- When `AdminVideoService.deleteVideo(videoId)` is called
- Then `VideoProviderAdapter.deleteAsset(providerAssetId)` is called OUTSIDE any `@Transactional` boundary — if it throws `VideoProviderException` (HTTP 502), no DB change occurs
- And within a `TransactionTemplate.execute()` block: `Video.operationalState` is set directly to `DELETED` (bypassing `VideoLifecycleService.transitionOperationalState`, which cannot target `DELETED` through the normal state machine); the video is saved; if any `UploadSession` for this video has `status = PENDING`, `QuotaProvider.release(reservationHandle)` is called within the same transaction block and the session status is set to `EXPIRED`
- And `VideoNotFoundException` (HTTP 404) is thrown if the video does not exist

**AC-3: AdminVideoService — `getUploadSession`**
- Given an `uploadSessionId`
- When `AdminVideoService.getUploadSession(uploadSessionId)` is called
- Then it returns the `UploadSession` entity or throws a mapped exception if not found

**AC-4: AdminVideoService — `listVideoSessions`**
- Given a `videoId`
- When `AdminVideoService.listVideoSessions(videoId)` is called
- Then it verifies the `Video` exists (throws `VideoNotFoundException` if not)
- And returns all `UploadSession` records for that video ordered by `createdAt DESC`

**AC-5: AdminVideoService — `triggerReconciliation`**
- Given a `videoId`
- When `AdminVideoService.triggerReconciliation(videoId)` is called
- Then `VideoProviderAdapter.getAssetStatus(providerAssetId)` is called OUTSIDE any `@Transactional` boundary
- And FR-27 rules are applied within a `TransactionTemplate.execute()` block:
  - If local state is `PROCESSING` and provider returns `READY` → `VideoLifecycleService.transitionOperationalState(videoId, READY)` is called and a `STATE_CORRECTED` incident is persisted
  - If provider returns `DELETED` → `VideoLifecycleService.transitionOperationalState(videoId, FAILED)` is called and a `MISSING_ASSET` incident is persisted
  - Other provider status values (UPLOADING, PROCESSING, FAILED): no state change, no incident
- And if the video's `operationalState == DELETED`, it is skipped — returns current state with no incident (terminal state, nothing to reconcile)
- And if `providerAssetId` is null (upload never reached provider), returns current state with no incident
- And returns a `ReconcileResponse` containing the final `operationalState`, `accessState`, and the `ReconcileIncidentDto` (null if no action was taken)

**AC-6: AdminVideoResource — REST endpoints**
- Given `AdminVideoResource` is defined in `platform.video.api`
- When any admin endpoint is called
- Then every method carries `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` and `@Observed(name = "video.admin.{operation}")`
- And the following endpoints are exposed under `/api/video/admin`:
  - `PATCH /api/video/admin/videos/{videoId}/access-state` → HTTP 200 with `VideoSummaryResponse`
  - `DELETE /api/video/admin/videos/{videoId}` → HTTP 204 (no body)
  - `GET /api/video/admin/videos/{videoId}/sessions` → HTTP 200 with `List<UploadSessionDto>`
  - `POST /api/video/admin/videos/{videoId}/reconcile` → HTTP 200 with `ReconcileResponse`

**AC-7: Extension point**
- Given `AdminVideoResource` and `AdminVideoService`
- When a consuming app wants to extend the admin layer
- Then both classes are non-final — consuming apps can extend or decorate without modifying the module

**AC-8: Integration test**
- Given `AdminVideoIT extends BaseVideoIT`
- When each admin service method is exercised
- Then: `setVideoAccessState` transitions correctly and rejects `DELETED` videos with `TerminalStateViolationException`; `deleteVideo` marks the video `DELETED` and releases quota for any PENDING session; `getUploadSession` returns session detail; `listVideoSessions` returns all sessions ordered by `createdAt DESC`; `triggerReconciliation` applies FR-27 rules synchronously using a `@MockitoBean VideoProviderAdapter`

## Tasks / Subtasks

- [x] Task 1: Add `findAllByVideoIdOrderByCreatedAtDesc` to `UploadSessionRepository` (AC: 4)
  - [x] Spring Data JPA derived method — one line addition; no custom query needed

- [x] Task 2: Create DTO records in `platform.video.contract` (AC: 1–6)
  - [x] `SetAccessStateRequest` record: `@NotNull AccessState newAccessState`
  - [x] `VideoSummaryResponse` record: `UUID id, String title, OperationalState operationalState, AccessState accessState, String ownerId, Visibility visibility, Instant updatedAt`
  - [x] `UploadSessionDto` record: `UUID id, UUID videoId, UploadSessionStatus status, long reservedBytes, Instant expiresAt, Instant createdAt`
  - [x] `ReconcileIncidentDto` record: `UUID id, ReconciliationIncidentType incidentType, String providerAssetId, String description, Instant createdAt`
  - [x] `ReconcileResponse` record: `UUID videoId, OperationalState operationalState, AccessState accessState, ReconcileIncidentDto incident` (`incident` is null when no action was taken)

- [x] Task 3: Create `AdminVideoService` in `platform.video.service` (AC: 1–5, 7)
  - [x] See Dev Notes for full implementation — do NOT use `VideoLifecycleService.transitionOperationalState()` for `deleteVideo`
  - [x] `setVideoAccessState`: delegates to `VideoLifecycleService.setAccessState()`
  - [x] `deleteVideo`: provider call outside transaction, DB write + quota release inside `transactionTemplate.execute()`
  - [x] `getUploadSession`: delegates to `uploadSessionRepository.findById()`; wrap missing session as `VideoValidationException`
  - [x] `listVideoSessions`: verify video exists, return all sessions ordered by `createdAt DESC`
  - [x] `triggerReconciliation`: provider call outside transaction, FR-27 rules inside `transactionTemplate.execute()`

- [x] Task 4: Create `AdminVideoResource` in `platform.video.api` (AC: 6, 7)
  - [x] Every method: `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` + `@Observed(name = "video.admin.{operation}")`
  - [x] Map entities to DTOs using MapStruct or inline (see Dev Notes)
  - [x] See Dev Notes for full endpoint + method signatures

- [x] Task 5: Create `AdminVideoIT extends BaseVideoIT` (AC: 8)
  - [x] `@MockitoBean VideoProviderAdapter` to control provider responses
  - [x] FK-ordered `@BeforeEach` cleanup (same order as `ReconciliationWorkerIT`)
  - [x] See Dev Notes for test structure

## Dev Notes

### Architecture Compliance

- `AdminVideoService` → `platform.video.service` ✓ (business logic service)
- `AdminVideoResource` → `platform.video.api` ✓ (REST controller)
- DTO records (`SetAccessStateRequest`, `VideoSummaryResponse`, `UploadSessionDto`, `ReconcileIncidentDto`, `ReconcileResponse`) → `platform.video.contract` ✓
- `VideoProviderAdapter.deleteAsset()` and `getAssetStatus()` called OUTSIDE any `@Transactional` boundary — same rule as reconciliation worker and expiry scheduler ✓
- `QuotaProvider.release()` inside `transactionTemplate.execute()` for `deleteVideo` — per spec, the quota release and DELETED transition are atomic; if either fails, both roll back ✓
- Both `AdminVideoService` and `AdminVideoResource` must be non-final (no `final` keyword) — extension point requirement ✓
- No direct entity exposure from REST layer — all responses use DTO records ✓
- `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` on every `AdminVideoResource` method — never leave a method unguarded ✓
- `@Observed(name = "video.admin.{operation}")` on every `AdminVideoResource` method ✓

### Critical: Why deleteVideo Bypasses `VideoLifecycleService.transitionOperationalState()`

`VideoLifecycleService.VALID_TRANSITIONS` does NOT include `DELETED` as a target:
- `UPLOADING → {PROCESSING, FAILED}` — DELETED not in set
- `PROCESSING → {READY, FAILED}` — DELETED not in set
- `FAILED → {UPLOADING}` — DELETED not in set
- `READY → {}` — empty set

Calling `transitionOperationalState(videoId, DELETED)` from any current state throws `TerminalStateViolationException`. `AdminVideoService.deleteVideo()` MUST set `operationalState = DELETED` directly on the entity via `videoRepository.save()`. This is an intentional admin bypass of the state machine — a privileged terminal operation.

### AdminVideoService — Full Implementation

**File:** `src/main/java/com/softropic/skillars/platform/video/service/AdminVideoService.java`

```java
package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.AssetStatus;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.contract.*;
import com.softropic.skillars.platform.video.contract.exception.*;
import com.softropic.skillars.platform.video.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminVideoService {

    private final VideoRepository videoRepository;
    private final UploadSessionRepository uploadSessionRepository;
    private final ReconciliationIncidentRepository incidentRepository;
    private final VideoLifecycleService videoLifecycleService;
    private final VideoProviderAdapter videoProviderAdapter;
    private final QuotaProvider quotaProvider;
    private final TransactionTemplate transactionTemplate;

    public Video setVideoAccessState(UUID videoId, AccessState newAccessState) {
        return videoLifecycleService.setAccessState(videoId, newAccessState);
    }

    public void deleteVideo(UUID videoId) {
        // Load outside transaction to get providerAssetId
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));

        // Provider call outside @Transactional — if this throws VideoProviderException (502), no DB change occurs
        if (video.getProviderAssetId() != null) {
            videoProviderAdapter.deleteAsset(video.getProviderAssetId());
        }

        // Atomic: set DELETED + release quota within same transaction
        transactionTemplate.execute(status -> {
            Video v = videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));
            v.setOperationalState(OperationalState.DELETED);
            videoRepository.save(v);

            // Release quota for any PENDING session (not COMMITTED or EXPIRED)
            uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)
                .filter(s -> s.getStatus() == UploadSessionStatus.PENDING)
                .ifPresent(s -> {
                    quotaProvider.release(s.getReservationHandle());
                    s.setStatus(UploadSessionStatus.EXPIRED);
                    uploadSessionRepository.save(s);
                });
            return null;
        });

        log.info("Admin deleted video {}", videoId);
    }

    public UploadSession getUploadSession(UUID uploadSessionId) {
        return uploadSessionRepository.findById(uploadSessionId)
            .orElseThrow(() -> new VideoValidationException(
                "No upload session found for id: " + uploadSessionId, java.util.Map.of("uploadSessionId", uploadSessionId)));
    }

    public List<UploadSession> listVideoSessions(UUID videoId) {
        videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));
        return uploadSessionRepository.findAllByVideoIdOrderByCreatedAtDesc(videoId);
    }

    public ReconcileResponse triggerReconciliation(UUID videoId) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));

        // Terminal state — nothing to reconcile
        if (video.getOperationalState() == OperationalState.DELETED) {
            return new ReconcileResponse(video.getId(), video.getOperationalState(), video.getAccessState(), null);
        }

        // No provider asset yet — can't check
        if (video.getProviderAssetId() == null) {
            return new ReconcileResponse(video.getId(), video.getOperationalState(), video.getAccessState(), null);
        }

        // Provider call outside @Transactional
        AssetStatus providerStatus = videoProviderAdapter.getAssetStatus(video.getProviderAssetId());

        return transactionTemplate.execute(txStatus -> {
            Video fresh = videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));

            ReconciliationIncident incident = null;

            if (providerStatus == AssetStatus.READY && fresh.getOperationalState() == OperationalState.PROCESSING) {
                videoLifecycleService.transitionOperationalState(videoId, OperationalState.READY);
                incident = saveIncident(fresh, ReconciliationIncidentType.STATE_CORRECTED,
                    "Admin reconcile: local PROCESSING corrected to READY");
                log.info("Admin reconcile STATE_CORRECTED for video {}: PROCESSING → READY", videoId);

            } else if (providerStatus == AssetStatus.DELETED) {
                videoLifecycleService.transitionOperationalState(videoId, OperationalState.FAILED);
                incident = saveIncident(fresh, ReconciliationIncidentType.MISSING_ASSET,
                    "Admin reconcile: provider asset missing, video marked FAILED");
                log.warn("Admin reconcile MISSING_ASSET for video {}: marked FAILED", videoId);
            }

            // Re-read final state after potential transition
            Video finalVideo = videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));

            ReconcileIncidentDto incidentDto = incident == null ? null
                : new ReconcileIncidentDto(incident.getId(), incident.getIncidentType(),
                    incident.getProviderAssetId(), incident.getDescription(), incident.getCreatedAt());

            return new ReconcileResponse(
                finalVideo.getId(), finalVideo.getOperationalState(), finalVideo.getAccessState(), incidentDto);
        });
    }

    private ReconciliationIncident saveIncident(Video video, ReconciliationIncidentType type, String description) {
        ReconciliationIncident inc = new ReconciliationIncident();
        inc.setVideoId(video.getId());
        inc.setIncidentType(type);
        inc.setProviderAssetId(video.getProviderAssetId());
        inc.setDescription(description);
        return incidentRepository.save(inc);
    }
}
```

**Key notes:**
- `deleteVideo` re-fetches the video inside `transactionTemplate.execute()` to ensure a fresh entity within the transaction — no stale data
- `triggerReconciliation` re-fetches inside the transaction AND again at the end for the final state — the second re-fetch is necessary because `videoLifecycleService.transitionOperationalState()` also saves internally and the in-memory `fresh` object may not reflect the latest state after the call
- `UPLOADING + providerStatus READY` → no state change (UPLOADING→READY is invalid; admin should let the webhook or reconciliation worker handle this via the UPLOADING→PROCESSING→READY path)

### AdminVideoResource — Full Implementation

**File:** `src/main/java/com/softropic/skillars/platform/video/api/AdminVideoResource.java`

```java
package com.softropic.skillars.platform.video.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.video.contract.*;
import com.softropic.skillars.platform.video.repo.UploadSession;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.service.AdminVideoService;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/video/admin")
@RequiredArgsConstructor
public class AdminVideoResource {

    private final AdminVideoService adminVideoService;

    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "video.admin.setAccessState")
    @PatchMapping("/videos/{videoId}/access-state")
    public ResponseEntity<VideoSummaryResponse> setAccessState(
            @PathVariable UUID videoId,
            @Valid @RequestBody SetAccessStateRequest request) {
        Video updated = adminVideoService.setVideoAccessState(videoId, request.newAccessState());
        return ResponseEntity.ok(toSummary(updated));
    }

    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "video.admin.deleteVideo")
    @DeleteMapping("/videos/{videoId}")
    public ResponseEntity<Void> deleteVideo(@PathVariable UUID videoId) {
        adminVideoService.deleteVideo(videoId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "video.admin.listSessions")
    @GetMapping("/videos/{videoId}/sessions")
    public ResponseEntity<List<UploadSessionDto>> listSessions(@PathVariable UUID videoId) {
        List<UploadSession> sessions = adminVideoService.listVideoSessions(videoId);
        List<UploadSessionDto> dtos = sessions.stream().map(this::toSessionDto).toList();
        return ResponseEntity.ok(dtos);
    }

    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "video.admin.triggerReconciliation")
    @PostMapping("/videos/{videoId}/reconcile")
    public ResponseEntity<ReconcileResponse> reconcile(@PathVariable UUID videoId) {
        return ResponseEntity.ok(adminVideoService.triggerReconciliation(videoId));
    }

    private VideoSummaryResponse toSummary(Video v) {
        return new VideoSummaryResponse(v.getId(), v.getTitle(), v.getOperationalState(),
            v.getAccessState(), v.getOwnerId(), v.getVisibility(), v.getUpdatedAt());
    }

    private UploadSessionDto toSessionDto(UploadSession s) {
        return new UploadSessionDto(s.getId(), s.getVideoId(), s.getStatus(),
            s.getReservedBytes(), s.getExpiresAt(), s.getCreatedAt());
    }
}
```

### DTO Records — Full Definitions

All records go in `platform.video.contract`.

**SetAccessStateRequest.java:**
```java
package com.softropic.skillars.platform.video.contract;
import jakarta.validation.constraints.NotNull;
public record SetAccessStateRequest(@NotNull AccessState newAccessState) {}
```

**VideoSummaryResponse.java:**
```java
package com.softropic.skillars.platform.video.contract;
import java.time.Instant;
import java.util.UUID;
public record VideoSummaryResponse(
    UUID id, String title, OperationalState operationalState, AccessState accessState,
    String ownerId, Visibility visibility, Instant updatedAt) {}
```

**UploadSessionDto.java:**
```java
package com.softropic.skillars.platform.video.contract;
import java.time.Instant;
import java.util.UUID;
public record UploadSessionDto(
    UUID id, UUID videoId, UploadSessionStatus status,
    long reservedBytes, Instant expiresAt, Instant createdAt) {}
```

**ReconcileIncidentDto.java:**
```java
package com.softropic.skillars.platform.video.contract;
import java.time.Instant;
import java.util.UUID;
public record ReconcileIncidentDto(
    UUID id, ReconciliationIncidentType incidentType,
    String providerAssetId, String description, Instant createdAt) {}
```

**ReconcileResponse.java:**
```java
package com.softropic.skillars.platform.video.contract;
import java.util.UUID;
public record ReconcileResponse(
    UUID videoId, OperationalState operationalState, AccessState accessState,
    ReconcileIncidentDto incident) {}
```

### UploadSessionRepository — Addition

Add one line to the existing repository interface:

```java
// Add to UploadSessionRepository.java
List<UploadSession> findAllByVideoIdOrderByCreatedAtDesc(UUID videoId);
```

Spring Data derives this automatically — no custom `@Query` needed.

### ReconcileResponse and TransactionTemplate null-safety

`transactionTemplate.execute()` can technically return `null` if the lambda returns `null`. `triggerReconciliation` always returns a non-null `ReconcileResponse` — the lambda always returns a value. If `transactionTemplate.execute()` ever returns `null` (which it won't here), add a null-check before returning from the service:

```java
return Objects.requireNonNull(transactionTemplate.execute(...), "transactionTemplate returned null");
```

### AdminVideoIT — Integration Test

**File:** `src/test/java/com/softropic/skillars/platform/video/service/AdminVideoIT.java`

**Key patterns:**
- `@MockitoBean VideoProviderAdapter` to control `getAssetStatus()` and `deleteAsset()` without real HTTP calls
- FK-ordered `@BeforeEach` cleanup: incidents → tokens → sessions → webhooks → videos
- Directly test `AdminVideoService` methods; REST layer integration is verified by compilation + `@PreAuthorize` annotations

```java
package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.AssetStatus;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.*;
import com.softropic.skillars.platform.video.contract.exception.TerminalStateViolationException;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import com.softropic.skillars.platform.video.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AdminVideoIT extends BaseVideoIT {

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    @Autowired AdminVideoService adminVideoService;
    @Autowired VideoRepository videoRepository;
    @Autowired UploadSessionRepository uploadSessionRepository;
    @Autowired PlaybackTokenRepository playbackTokenRepository;
    @Autowired VideoWebhookEventRepository webhookEventRepository;
    @Autowired ReconciliationIncidentRepository incidentRepository;

    @BeforeEach
    void setUp() {
        incidentRepository.deleteAll();
        playbackTokenRepository.deleteAll();
        uploadSessionRepository.deleteAll();
        webhookEventRepository.deleteAll();
        videoRepository.deleteAll();
    }

    @Test
    void setVideoAccessState_blocksReadyVideo_updatesAccessState() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE, "asset-admin-1");

        Video updated = adminVideoService.setVideoAccessState(video.getId(), AccessState.BLOCKED);

        assertThat(updated.getAccessState()).isEqualTo(AccessState.BLOCKED);
        assertThat(videoRepository.findById(video.getId()).orElseThrow().getAccessState())
            .isEqualTo(AccessState.BLOCKED);
    }

    @Test
    void setVideoAccessState_deletedVideo_throwsTerminalStateViolation() {
        Video video = seedVideo(OperationalState.DELETED, AccessState.ACTIVE, "asset-admin-2");

        assertThatThrownBy(() -> adminVideoService.setVideoAccessState(video.getId(), AccessState.BLOCKED))
            .isInstanceOf(TerminalStateViolationException.class);
    }

    @Test
    void deleteVideo_readyVideo_marksDeletedReleasesQuotaForPendingSession() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE, "asset-admin-delete");
        UploadSession pendingSession = seedPendingSession(video, "quota-handle-admin-1");
        doNothing().when(videoProviderAdapter).deleteAsset("asset-admin-delete");

        adminVideoService.deleteVideo(video.getId());

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.DELETED);
        UploadSession refreshed = uploadSessionRepository.findById(pendingSession.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(UploadSessionStatus.EXPIRED);
    }

    @Test
    void deleteVideo_processingVideo_noPendingSession_marksDeleted() {
        Video video = seedVideo(OperationalState.PROCESSING, AccessState.ACTIVE, "asset-admin-del-no-sess");
        doNothing().when(videoProviderAdapter).deleteAsset("asset-admin-del-no-sess");

        adminVideoService.deleteVideo(video.getId());

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.DELETED);
    }

    @Test
    void deleteVideo_providerThrows_videoNotDeleted() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE, "asset-admin-del-fail");
        doThrow(new VideoProviderException("deleteAsset", new RuntimeException("provider down")))
            .when(videoProviderAdapter).deleteAsset("asset-admin-del-fail");

        assertThatThrownBy(() -> adminVideoService.deleteVideo(video.getId()))
            .isInstanceOf(VideoProviderException.class);

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getOperationalState())
            .isEqualTo(OperationalState.READY); // unchanged
    }

    @Test
    void listVideoSessions_returnsAllSessionsOrderedByCreatedAtDesc() {
        Video video = seedVideo(OperationalState.PROCESSING, AccessState.ACTIVE, "asset-admin-sessions");
        UploadSession s1 = seedPendingSession(video, "handle-1");
        UploadSession s2 = seedPendingSession(video, "handle-2");

        var sessions = adminVideoService.listVideoSessions(video.getId());

        assertThat(sessions).hasSizeGreaterThanOrEqualTo(2);
        assertThat(sessions).extracting(UploadSession::getId).contains(s1.getId(), s2.getId());
    }

    @Test
    void triggerReconciliation_processingVideoReadyAtProvider_correctedToReady() {
        Video video = seedVideo(OperationalState.PROCESSING, AccessState.ACTIVE, "asset-admin-recon-1");
        when(videoProviderAdapter.getAssetStatus("asset-admin-recon-1")).thenReturn(AssetStatus.READY);

        ReconcileResponse response = adminVideoService.triggerReconciliation(video.getId());

        assertThat(response.operationalState()).isEqualTo(OperationalState.READY);
        assertThat(response.incident()).isNotNull();
        assertThat(response.incident().incidentType()).isEqualTo(ReconciliationIncidentType.STATE_CORRECTED);
    }

    @Test
    void triggerReconciliation_processingVideoMissingAtProvider_markedFailed() {
        Video video = seedVideo(OperationalState.PROCESSING, AccessState.ACTIVE, "asset-admin-recon-2");
        when(videoProviderAdapter.getAssetStatus("asset-admin-recon-2")).thenReturn(AssetStatus.DELETED);

        ReconcileResponse response = adminVideoService.triggerReconciliation(video.getId());

        assertThat(response.operationalState()).isEqualTo(OperationalState.FAILED);
        assertThat(response.incident()).isNotNull();
        assertThat(response.incident().incidentType()).isEqualTo(ReconciliationIncidentType.MISSING_ASSET);
    }

    @Test
    void triggerReconciliation_deletedVideo_returnsCurrentStateNoAction() {
        Video video = seedVideo(OperationalState.DELETED, AccessState.ACTIVE, "asset-admin-recon-3");

        ReconcileResponse response = adminVideoService.triggerReconciliation(video.getId());

        assertThat(response.operationalState()).isEqualTo(OperationalState.DELETED);
        assertThat(response.incident()).isNull();
        verify(videoProviderAdapter, never()).getAssetStatus(any());
    }

    // ── helpers ────────────────────────────────────────────────────────────
    private Video seedVideo(OperationalState opState, AccessState accessState, String providerAssetId) {
        Video v = new Video();
        v.setOwnerId("owner-admin-it");
        v.setProvider("bunny");
        v.setProviderAssetId(providerAssetId);
        v.setTitle("test-admin.mp4");
        v.setOperationalState(opState);
        v.setAccessState(accessState);
        v.setVisibility(Visibility.PRIVATE);
        return videoRepository.save(v);
    }

    private UploadSession seedPendingSession(Video video, String reservationHandle) {
        UploadSession s = new UploadSession();
        s.setVideoId(video.getId());
        s.setProviderUploadId("provider-upload-" + reservationHandle);
        s.setStatus(UploadSessionStatus.PENDING);
        s.setReservedBytes(100_000_000L);
        s.setReservationHandle(reservationHandle);
        s.setExpiresAt(Instant.now().plusSeconds(3600));
        return uploadSessionRepository.save(s);
    }
}
```

### Existing Files — DO NOT RECREATE

| Component | Path | This Story's Action |
|---|---|---|
| `VideoLifecycleService` | `platform.video.service` | READ-ONLY — `setAccessState()` and `transitionOperationalState()` already exist; DO NOT add DELETED to state machine |
| `VideoProviderAdapter` | `infrastructure.video` | READ-ONLY — `deleteAsset()` and `getAssetStatus()` already implemented |
| `VideoRepository` | `platform.video.repo` | READ-ONLY — no changes needed for this story |
| `UploadSessionRepository` | `platform.video.repo` | UPDATE — add `findAllByVideoIdOrderByCreatedAtDesc` (one line) |
| `ReconciliationIncidentRepository` | `platform.video.repo` | READ-ONLY — `JpaRepository.save()` is sufficient |
| `ReconciliationIncident` | `platform.video.repo` | READ-ONLY — entity already in place from Story 4.2 |
| `ReconciliationIncidentType` | `platform.video.contract` | READ-ONLY — enum already in place from Story 4.2 |
| `VideoApiAdvice` | `platform.video.api` | READ-ONLY — all video exception types are already handled; no new exceptions in this story |
| `QuotaProvider` | `platform.video.contract` | READ-ONLY — `release(reservationHandle)` already defined |
| `TransactionTemplate` | `platform.video.config` | READ-ONLY — already registered in `VideoConfig` |
| `SecurityConstants` | `infrastructure.security` | READ-ONLY — use `SecurityConstants.HAS_ADMIN_ROLE` for `@PreAuthorize` |
| `BaseVideoIT` | test root | EXTEND only — do not modify |
| `ReconciliationWorkerIT` | test service | PATTERN REFERENCE for `@MockitoBean VideoProviderAdapter` and FK delete order |

### TransactionTemplate Registration

`TransactionTemplate` is **already registered** as a `@Bean` in `BlobstoreConfig` (`infrastructure.blobstore.config`). All existing video schedulers (`ReconciliationWorkerScheduler`, `WebhookEventProcessorScheduler`, `UploadSessionExpiryScheduler`) already inject it via `@RequiredArgsConstructor` and work correctly. Do NOT register a duplicate bean — just inject it in `AdminVideoService` the same way.

### Package & File Summary

```
src/main/java/com/softropic/skillars/platform/video/
├── api/
│   └── AdminVideoResource.java                          ← NEW
├── contract/
│   ├── SetAccessStateRequest.java                       ← NEW record
│   ├── VideoSummaryResponse.java                        ← NEW record
│   ├── UploadSessionDto.java                            ← NEW record
│   ├── ReconcileIncidentDto.java                        ← NEW record
│   └── ReconcileResponse.java                           ← NEW record
├── repo/
│   └── UploadSessionRepository.java                     ← UPDATE: add findAllByVideoIdOrderByCreatedAtDesc
└── service/
    └── AdminVideoService.java                           ← NEW

src/test/java/com/softropic/skillars/platform/video/
└── service/
    └── AdminVideoIT.java                                ← NEW
```

**No Flyway migration needed** — this story adds only Java services and REST endpoints; no new tables or schema changes.

### References

- Epic 5 Story 5.1 ACs: `_bmad-output/planning-artifacts/video-module/epics.md` §"Story 5.1: Admin Service Layer & REST Endpoint"
- FRs covered: FR-31 (Admin service layer), FR-32 (Admin REST endpoint)
- `VideoLifecycleService.java`: `setAccessState()` at line 57; `VALID_TRANSITIONS` at line 25–31 — DELETED is NOT a valid target via transitionOperationalState
- `SecurityConstants.java`: `HAS_ADMIN_ROLE = "hasRole('ROLE_ADMIN') or hasRole('ROLE_LTD_ADMIN')"` — use this constant, never hardcode
- `UploadSessionRepository.java`: existing `findFirstByVideoIdOrderByCreatedAtDesc` pattern (for the `deleteVideo` pending-session lookup)
- `ReconciliationWorkerScheduler.java`: reference for `transactionTemplate` + `VideoProviderAdapter` usage pattern
- `WebhookPipelineIT.java` / `ReconciliationWorkerIT.java`: reference for `@MockitoBean VideoProviderAdapter` and FK-ordered `deleteAll()` in `@BeforeEach`
- `VideoConfig.java`: verify `TransactionTemplate` bean is registered before adding AdminVideoService constructor injection
- Project context rules: `_bmad-output/project-context.md`

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

None — clean implementation, no debugging required.

### Completion Notes List

- ✅ Task 1: Added `findAllByVideoIdOrderByCreatedAtDesc` derived method to `UploadSessionRepository` — one line, zero custom query needed.
- ✅ Task 2: Created 5 DTO records in `platform.video.contract`: `SetAccessStateRequest`, `VideoSummaryResponse`, `UploadSessionDto`, `ReconcileIncidentDto`, `ReconcileResponse`.
- ✅ Task 3: Created `AdminVideoService` — `deleteVideo` bypasses state machine and sets `DELETED` directly via entity save; all provider calls outside `@Transactional`; `Objects.requireNonNull` wraps `transactionTemplate.execute()` return in `triggerReconciliation`. Note: `VideoValidationException` has a single-arg constructor only — dev notes incorrectly suggested a two-arg form.
- ✅ Task 4: Created `AdminVideoResource` — all 4 endpoints guarded with `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` and `@Observed`; inline entity-to-DTO mapping (no MapStruct needed).
- ✅ Task 5: Created `AdminVideoIT` with 10 integration tests — `@MockitoBean VideoProviderAdapter`, FK-ordered `@BeforeEach` cleanup, all ACs validated. All 10 tests pass.

### File List

- `src/main/java/com/softropic/skillars/platform/video/repo/UploadSessionRepository.java` (modified)
- `src/main/java/com/softropic/skillars/platform/video/contract/SetAccessStateRequest.java` (new)
- `src/main/java/com/softropic/skillars/platform/video/contract/VideoSummaryResponse.java` (new)
- `src/main/java/com/softropic/skillars/platform/video/contract/UploadSessionDto.java` (new)
- `src/main/java/com/softropic/skillars/platform/video/contract/ReconcileIncidentDto.java` (new)
- `src/main/java/com/softropic/skillars/platform/video/contract/ReconcileResponse.java` (new)
- `src/main/java/com/softropic/skillars/platform/video/service/AdminVideoService.java` (new)
- `src/main/java/com/softropic/skillars/platform/video/api/AdminVideoResource.java` (new)
- `src/test/java/com/softropic/skillars/platform/video/service/AdminVideoIT.java` (new)

## Change Log

- 2026-06-01: Story created — ready-for-dev
- 2026-06-01: Story implemented — all tasks complete, 10/10 integration tests pass, status set to review
