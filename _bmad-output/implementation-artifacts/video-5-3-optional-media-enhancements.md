# Story Video-5.3: Optional Media Enhancements

Status: review

## Story

As a consuming application developer,
I want access to Bunny.net-generated thumbnails and caption tracks for stored videos,
So that my application can surface richer media experiences without implementing provider-specific API calls directly.

## Acceptance Criteria

**AC-1: VideoProviderAdapter gains two backward-compatible optional default methods**
- Given the `VideoProviderAdapter` interface from Story 1.3
- When optional media enhancement methods are added
- Then the interface gains: `default String getThumbnailUrl(String providerAssetId) { throw new UnsupportedOperationException("thumbnails not supported"); }` and `default void addCaptionTrack(String providerAssetId, String language, String captionFileUrl) { throw new UnsupportedOperationException("captions not supported"); }`
- And these additions are backward-compatible — existing implementations that do not override them throw `UnsupportedOperationException`, which `VideoMediaService` catches and wraps as `VideoProviderException` (HTTP 502)
- And no Bunny.net SDK types or HTTP client types appear in the interface signature

**AC-2: BunnyVideoProviderAdapter.getThumbnailUrl — deterministic URL, no API call**
- Given `BunnyVideoProviderAdapter` is the active provider
- When `getThumbnailUrl(providerAssetId)` is called
- Then it returns `https://{cdn-hostname}/{providerAssetId}/thumbnail.jpg` — deterministic from asset ID and CDN hostname, no HTTP call is made

**AC-3: BunnyVideoProviderAdapter.addCaptionTrack — POST to Bunny.net captions API**
- Given `BunnyVideoProviderAdapter.addCaptionTrack(providerAssetId, language, captionFileUrl)` is called
- When it executes
- Then it calls `POST /library/{libraryId}/videos/{providerAssetId}/captions/{language}` with `AccessKey` header and a JSON body containing `captionFileUrl`
- And on `RestClientException`, throws `VideoProviderException("addCaptionTrack", e)`

**AC-4: VideoMediaService domain service with getThumbnailUrl and addCaptionTrack**
- Given `VideoMediaService` is defined in `platform.video.service`
- When `getThumbnailUrl(videoId)` is called
- Then it verifies the `Video` exists — throws `VideoNotFoundException` (404) if not found
- And if `video.operationalState == DELETED`, throws `VideoValidationException` (422)
- And calls `VideoProviderAdapter.getThumbnailUrl(providerAssetId)` and returns the URL
- And wraps `UnsupportedOperationException` from non-implementing providers as `VideoProviderException` (502)
- And when `addCaptionTrack(videoId, language, captionFileUrl)` is called, applies the same existence + DELETED guard before delegating to the adapter
- And both methods carry `@Observed(name = "video.media.{operation}")` and MDC context

**AC-5: Unit tests for BunnyVideoProviderAdapter media enhancements and VideoMediaService**
- Given WireMock stubs for the Bunny.net captions endpoint
- When `BunnyVideoProviderAdapterTest` runs the media enhancement cases
- Then `getThumbnailUrl` returns the correct deterministic URL (no WireMock stub required — no HTTP call)
- And `addCaptionTrack` fires the correct POST to `/library/{libraryId}/videos/{assetId}/captions/{lang}` with `AccessKey` header
- And a separate `VideoMediaServiceTest` (Mockito unit test) verifies: `getThumbnailUrl` returns the adapter URL for a valid video; `VideoNotFoundException` for missing video; `VideoValidationException` for DELETED video; `VideoProviderException` when adapter throws `UnsupportedOperationException` for both methods

## Tasks / Subtasks

- [x] Task 1: Add default methods to `VideoProviderAdapter` interface (AC: 1)
  - [x] Open `src/main/java/com/softropic/skillars/infrastructure/video/VideoProviderAdapter.java`
  - [x] Add after the existing `restoreAsset` default: `default String getThumbnailUrl(String providerAssetId) { throw new UnsupportedOperationException("thumbnails not supported"); }`
  - [x] Add: `default void addCaptionTrack(String providerAssetId, String language, String captionFileUrl) { throw new UnsupportedOperationException("captions not supported"); }`
  - [x] No imports needed — method signatures use only `java.lang` types

- [x] Task 2: Implement `getThumbnailUrl` in `BunnyVideoProviderAdapter` (AC: 2)
  - [x] Open `src/main/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapter.java`
  - [x] Add `@Override public String getThumbnailUrl(String providerAssetId) { return "https://" + cdnHostname + "/" + providerAssetId + "/thumbnail.jpg"; }`
  - [x] No HTTP call, no try/catch — purely string construction using existing `cdnHostname` field

- [x] Task 3: Implement `addCaptionTrack` in `BunnyVideoProviderAdapter` (AC: 3)
  - [x] Add `@Override public void addCaptionTrack(String providerAssetId, String language, String captionFileUrl)` method
  - [x] Use `buildHeaders()` + set `ContentType = APPLICATION_JSON` (same pattern as `initializeUpload`)
  - [x] POST to `apiBaseUrl + "/library/" + libraryId + "/videos/" + providerAssetId + "/captions/" + language`
  - [x] Body: `Map.of("captionFileUrl", captionFileUrl)`
  - [x] Catch `RestClientException` and throw `new VideoProviderException("addCaptionTrack", e)`

- [x] Task 4: Create `VideoMediaService` in `platform.video.service` (AC: 4)
  - [x] Create `src/main/java/com/softropic/skillars/platform/video/service/VideoMediaService.java`
  - [x] `@Slf4j @Service @RequiredArgsConstructor` — inject `VideoRepository`, `VideoProviderAdapter`
  - [x] `getThumbnailUrl(UUID videoId)`: MDC + @Observed, look up video, DELETED guard, adapter call wrapped in try/catch for UnsupportedOperationException → VideoProviderException
  - [x] `addCaptionTrack(UUID videoId, String language, String captionFileUrl)`: same existence + DELETED guard, then adapter call wrapped in try/catch for UnsupportedOperationException → VideoProviderException
  - [x] Both methods: MDC `operation` + `videoId`, try/finally cleanup

- [x] Task 5: Add media enhancement tests to `BunnyVideoProviderAdapterTest` (AC: 5)
  - [x] Open `src/test/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapterTest.java`
  - [x] Add `getThumbnailUrl_returnsDeterministicUrl()` — no stub needed, assert `"https://test.b-cdn.net/vid-thumb/thumbnail.jpg"`
  - [x] Add `addCaptionTrack_firesCorrectPost()` — stub `POST /library/123/videos/vid-001/captions/en` with `AccessKey` header, call adapter, verify with `postRequestedFor`

- [x] Task 6: Create `VideoMediaServiceTest` unit test (AC: 5)
  - [x] Create `src/test/java/com/softropic/skillars/platform/video/service/VideoMediaServiceTest.java`
  - [x] `@ExtendWith(MockitoExtension.class)` — mock `VideoRepository`, `VideoProviderAdapter`; use `@InjectMocks VideoMediaService`
  - [x] Tests: `getThumbnailUrl_returnsUrl`; `getThumbnailUrl_videoNotFound_throws`; `getThumbnailUrl_deletedVideo_throws`; `getThumbnailUrl_unsupportedProvider_wrapsAsVideoProviderException`; `addCaptionTrack_unsupportedProvider_wrapsAsVideoProviderException`; `addCaptionTrack_deletedVideo_throws`

## Dev Notes

### Architecture Compliance

- `VideoMediaService` → `platform.video.service` ✓ (domain service; touches VideoRepository + delegates to VideoProviderAdapter)
- `getThumbnailUrl` / `addCaptionTrack` additions to `VideoProviderAdapter` → `infrastructure.video` ✓ (transport-layer interface, no platform imports)
- `BunnyVideoProviderAdapter` overrides → `infrastructure.video` ✓ (provider implementation, no domain imports)
- `VideoMediaService` is NOT a `@RestController` — the module exposes only the service layer; consuming apps own their REST endpoints (per `package-info.java` contract)
- NO new REST resource is required or expected from this story

### Critical: VideoProviderAdapter — No Import Required

`getThumbnailUrl` returns `String` and `addCaptionTrack` is `void` — both use only `java.lang` types. No imports needed in the interface file:

```java
// In VideoProviderAdapter.java — add after restoreAsset:
default String getThumbnailUrl(String providerAssetId) {
    throw new UnsupportedOperationException("thumbnails not supported");
}

default void addCaptionTrack(String providerAssetId, String language, String captionFileUrl) {
    throw new UnsupportedOperationException("captions not supported");
}
```

### Critical: BunnyVideoProviderAdapter — getThumbnailUrl Is String Concatenation Only

No HTTP call, no exception path. Uses the existing `cdnHostname` field injected via constructor:

```java
@Override
public String getThumbnailUrl(String providerAssetId) {
    return "https://" + cdnHostname + "/" + providerAssetId + "/thumbnail.jpg";
}
```

### Critical: BunnyVideoProviderAdapter — addCaptionTrack Pattern

Follows exact same pattern as `initializeUpload` for POST with JSON body:

```java
@Override
public void addCaptionTrack(String providerAssetId, String language, String captionFileUrl) {
    HttpHeaders headers = buildHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<Map<String, String>> entity = new HttpEntity<>(
        Map.of("captionFileUrl", captionFileUrl), headers
    );
    try {
        restTemplate.postForEntity(
            apiBaseUrl + "/library/" + libraryId + "/videos/" + providerAssetId + "/captions/" + language,
            entity,
            Void.class
        );
    } catch (RestClientException e) {
        throw new VideoProviderException("addCaptionTrack", e);
    }
}
```

Import needed: `java.util.Map` is already imported. `MediaType`, `HttpEntity`, `HttpHeaders` are already imported.

### Critical: VideoMediaService — Full Implementation

```java
package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoMediaService {

    private final VideoRepository videoRepository;
    private final VideoProviderAdapter videoProviderAdapter;

    @Observed(name = "video.media.getThumbnailUrl")
    public String getThumbnailUrl(UUID videoId) {
        MDC.put("operation", "media.getThumbnailUrl");
        MDC.put("videoId", videoId.toString());
        try {
            Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));
            if (video.getOperationalState() == OperationalState.DELETED) {
                throw new VideoValidationException("Cannot get thumbnail for a deleted video");
            }
            try {
                return videoProviderAdapter.getThumbnailUrl(video.getProviderAssetId());
            } catch (UnsupportedOperationException e) {
                throw new VideoProviderException("getThumbnailUrl: not supported by active provider", e);
            }
        } finally {
            MDC.remove("operation");
            MDC.remove("videoId");
        }
    }

    @Observed(name = "video.media.addCaptionTrack")
    public void addCaptionTrack(UUID videoId, String language, String captionFileUrl) {
        MDC.put("operation", "media.addCaptionTrack");
        MDC.put("videoId", videoId.toString());
        try {
            Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));
            if (video.getOperationalState() == OperationalState.DELETED) {
                throw new VideoValidationException("Cannot add caption track to a deleted video");
            }
            try {
                videoProviderAdapter.addCaptionTrack(video.getProviderAssetId(), language, captionFileUrl);
            } catch (UnsupportedOperationException e) {
                throw new VideoProviderException("addCaptionTrack: not supported by active provider", e);
            }
        } finally {
            MDC.remove("operation");
            MDC.remove("videoId");
        }
    }
}
```

### Critical: VideoProviderException Constructor — Verify Signature

Before calling `new VideoProviderException("...", e)`, confirm the two-arg constructor accepts `(String message, Throwable cause)`. Open `platform.video.contract.exception.VideoProviderException` and verify — existing calls in `BunnyVideoProviderAdapter` use `new VideoProviderException("initializeUpload", e)` confirming this pattern.

### BunnyVideoProviderAdapterTest — New Test Cases

Append to the existing `@WireMockTest` class (already has `adapter` in `@BeforeEach` with `API_KEY="test-api-key"`, `LIBRARY_ID="123"`, `CDN_HOSTNAME="test.b-cdn.net"`):

```java
@Test
void getThumbnailUrl_returnsDeterministicUrl() {
    // No WireMock stub — no HTTP call
    String url = adapter.getThumbnailUrl("vid-thumb");
    assertThat(url).isEqualTo("https://test.b-cdn.net/vid-thumb/thumbnail.jpg");
}

@Test
void addCaptionTrack_firesPostToCorrectEndpoint() {
    stubFor(post(urlEqualTo("/library/123/videos/vid-001/captions/en"))
        .withHeader("AccessKey", equalTo(API_KEY))
        .willReturn(okJson("{\"success\":true}")));

    adapter.addCaptionTrack("vid-001", "en", "https://example.com/subtitles.srt");

    verify(postRequestedFor(urlEqualTo("/library/123/videos/vid-001/captions/en"))
        .withHeader("AccessKey", equalTo(API_KEY)));
}
```

No new imports needed — `post`, `postRequestedFor`, `urlEqualTo`, `equalTo`, `okJson`, `verify`, `stubFor` are already statically imported in the test class.

### VideoMediaServiceTest — Full Unit Test Structure

```java
package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VideoMediaServiceTest {

    @Mock VideoRepository videoRepository;
    @Mock VideoProviderAdapter videoProviderAdapter;
    @InjectMocks VideoMediaService videoMediaService;

    @Test
    void getThumbnailUrl_returnsAdapterUrl() {
        UUID videoId = UUID.randomUUID();
        Video video = activeReadyVideo(videoId, "asset-001");
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        when(videoProviderAdapter.getThumbnailUrl("asset-001")).thenReturn("https://cdn/asset-001/thumbnail.jpg");

        String result = videoMediaService.getThumbnailUrl(videoId);

        assertThat(result).isEqualTo("https://cdn/asset-001/thumbnail.jpg");
    }

    @Test
    void getThumbnailUrl_videoNotFound_throwsVideoNotFoundException() {
        UUID videoId = UUID.randomUUID();
        when(videoRepository.findById(videoId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> videoMediaService.getThumbnailUrl(videoId))
            .isInstanceOf(VideoNotFoundException.class);
    }

    @Test
    void getThumbnailUrl_deletedVideo_throwsVideoValidationException() {
        UUID videoId = UUID.randomUUID();
        Video video = deletedVideo(videoId, "asset-del");
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));

        assertThatThrownBy(() -> videoMediaService.getThumbnailUrl(videoId))
            .isInstanceOf(VideoValidationException.class);
    }

    @Test
    void getThumbnailUrl_unsupportedProvider_wrapsAsVideoProviderException() {
        UUID videoId = UUID.randomUUID();
        Video video = activeReadyVideo(videoId, "asset-001");
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        when(videoProviderAdapter.getThumbnailUrl(any())).thenThrow(new UnsupportedOperationException("thumbnails not supported"));

        assertThatThrownBy(() -> videoMediaService.getThumbnailUrl(videoId))
            .isInstanceOf(VideoProviderException.class);
    }

    @Test
    void addCaptionTrack_deletedVideo_throwsVideoValidationException() {
        UUID videoId = UUID.randomUUID();
        Video video = deletedVideo(videoId, "asset-del");
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));

        assertThatThrownBy(() -> videoMediaService.addCaptionTrack(videoId, "en", "https://example.com/cap.srt"))
            .isInstanceOf(VideoValidationException.class);
    }

    @Test
    void addCaptionTrack_unsupportedProvider_wrapsAsVideoProviderException() {
        UUID videoId = UUID.randomUUID();
        Video video = activeReadyVideo(videoId, "asset-001");
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        doThrow(new UnsupportedOperationException("captions not supported"))
            .when(videoProviderAdapter).addCaptionTrack(any(), any(), any());

        assertThatThrownBy(() -> videoMediaService.addCaptionTrack(videoId, "en", "https://example.com/cap.srt"))
            .isInstanceOf(VideoProviderException.class);
    }

    // --- helpers ---

    private Video activeReadyVideo(UUID id, String providerAssetId) {
        Video v = new Video();
        v.setId(id);
        v.setProviderAssetId(providerAssetId);
        v.setOperationalState(OperationalState.READY);
        v.setAccessState(AccessState.ACTIVE);
        return v;
    }

    private Video deletedVideo(UUID id, String providerAssetId) {
        Video v = new Video();
        v.setId(id);
        v.setProviderAssetId(providerAssetId);
        v.setOperationalState(OperationalState.DELETED);
        v.setAccessState(AccessState.ACTIVE);
        return v;
    }
}
```

### Existing Files — Change Summary

| Component | Path | Action | What Changes |
|---|---|---|---|
| `VideoProviderAdapter` | `infrastructure.video` | UPDATE | Add 2 default methods: `getThumbnailUrl`, `addCaptionTrack` |
| `BunnyVideoProviderAdapter` | `infrastructure.video` | UPDATE | Override `getThumbnailUrl` (string concat) + `addCaptionTrack` (POST to Bunny) |
| `VideoMediaService` | `platform.video.service` | NEW | Domain service: existence + DELETED guard, adapter delegation, UnsupportedOp wrapping |
| `BunnyVideoProviderAdapterTest` | `infrastructure.video` (test) | UPDATE | Add 2 test methods for the new adapter methods |
| `VideoMediaServiceTest` | `platform.video.service` (test) | NEW | 6 Mockito unit tests |

### No New Flyway Migration Needed

This story adds Java service and interface methods only — no schema changes.

### Do NOT Create a REST Resource

The epics explicitly state: "End-user REST controllers are the consuming app's responsibility (out of scope)." `VideoMediaService` is the API surface for consuming apps. Do not create `VideoMediaResource.java`.

### Do NOT Add @Observed to VideoApiAdvice

`VideoApiAdvice` is already complete from Story 5.2. The exceptions thrown by `VideoMediaService` (`VideoNotFoundException`, `VideoValidationException`, `VideoProviderException`) are all already handled by the existing `@ExceptionHandler` methods.

### Import Reference

```java
// VideoMediaService imports
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import com.softropic.skillars.platform.video.contract.exception.VideoValidationException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import java.util.UUID;

// BunnyVideoProviderAdapter — addCaptionTrack needs no new imports
// Map, HttpHeaders, HttpEntity, MediaType, RestClientException, VideoProviderException all already imported
```

### Project Structure Notes

- All new/modified files follow `com.softropic.skillars.platform.video.{layer}` hierarchy
- `VideoMediaService` is business domain code → `platform.video.service` ✓
- The `VideoProviderAdapter` additions and `BunnyVideoProviderAdapter` overrides remain in `infrastructure.video` ✓ — these are transport-layer adapter methods with zero business rules
- `VideoMediaService` is the domain boundary that applies the DELETED guard before calling the provider adapter

### References

- Story 5.3 ACs: `_bmad-output/planning-artifacts/video-module/epics.md` §"Story 5.3: Optional Media Enhancements"
- FR-36: Optional Media Enhancements (SHOULD) — thumbnail generation, subtitle tracks
- FR-21: Optional Adapter Operations — `default` methods throwing `UnsupportedOperationException`
- `VideoProviderAdapter.java`: `src/main/java/com/softropic/skillars/infrastructure/video/VideoProviderAdapter.java`
- `BunnyVideoProviderAdapter.java`: `src/main/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapter.java`
- `AdminVideoService.java`: reference for `@Slf4j @Service @RequiredArgsConstructor` + MDC try/finally pattern
- `BunnyVideoProviderAdapterTest.java`: `src/test/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapterTest.java` — `@WireMockTest` setup, existing `stubFor`/`verify` patterns
- `project-context.md` rules: REST controllers suffixed `Resource` and placed in `api` package; `@PreAuthorize` required on every resource method — N/A for this story (no REST resource)
- Story 5.2 learnings: `@Observed` on all service methods; MDC try/finally cleanup; `VideoMetrics` not needed here (no latency metrics required by these SHOULD features)

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

- All 6 tasks completed. Two new default methods added to `VideoProviderAdapter` (backward-compatible). `BunnyVideoProviderAdapter` overrides `getThumbnailUrl` (deterministic string concat, no HTTP) and `addCaptionTrack` (POST to Bunny captions API, catches `RestClientException` → `VideoProviderException`). New `VideoMediaService` created with existence + DELETED guard + `UnsupportedOperationException` wrapping for both operations. 24 tests total: 18 in `BunnyVideoProviderAdapterTest` (16 existing + 2 new) and 6 new in `VideoMediaServiceTest`. No new Flyway migration, no REST resource created (by design).

### File List

- src/main/java/com/softropic/skillars/infrastructure/video/VideoProviderAdapter.java
- src/main/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapter.java
- src/main/java/com/softropic/skillars/platform/video/service/VideoMediaService.java
- src/test/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapterTest.java
- src/test/java/com/softropic/skillars/platform/video/service/VideoMediaServiceTest.java

## Change Log

- 2026-06-02: Implemented Story Video-5.3 — added `getThumbnailUrl` and `addCaptionTrack` default methods to `VideoProviderAdapter`; overrode both in `BunnyVideoProviderAdapter`; created `VideoMediaService` domain service with existence/DELETED guards and UnsupportedOperation wrapping; added 2 tests to `BunnyVideoProviderAdapterTest` and created `VideoMediaServiceTest` with 6 unit tests. All 24 tests pass.
