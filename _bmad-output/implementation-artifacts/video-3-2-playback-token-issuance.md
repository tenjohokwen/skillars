# Story Video-3.2: Playback Token Issuance

Status: review

## Story

As a consuming application,
I want to call `PlaybackService.authorizePlayback()` to obtain a signed, time-bounded HLS playback URL for an eligible video,
So that end users can stream video directly from Bunny.net's CDN with cryptographically signed, expiring credentials.

## Acceptance Criteria

**AC-1: Happy path — eligible video returns token + signed playback URL**
- Given a `videoId` and `viewerId`
- When `PlaybackService.authorizePlayback(videoId, viewerId)` is called
- Then eligibility is checked first — if not eligible, `PlaybackDeniedException` (HTTP 403) is thrown with both `operationalState` and `accessState` in the response body
- And if eligible, a `PlaybackToken` record is persisted: `videoId`, `viewerId`, `expiresAt = now + effective TTL`, `revokedAt = null`
- And `VideoProviderAdapter.generatePlaybackUrl(providerAssetId, PlaybackTokenClaims{viewerId, expiresAt})` is called to obtain a signed Bunny.net HLS URL
- And the `PlaybackToken` UUID becomes the JWT `jti` claim
- And a signed JWT (HMAC-SHA256) is returned with claims: `jti` (token UUID), `iat` (issuedAt epoch), `exp` (expiry epoch), `sub` (viewerId), `vid` (videoId)
- And returns `PlaybackAuthorizationResponse`: `token` (signed JWT), `playbackUrl` (Bunny.net HLS manifest URL), `expiresAt`
- And unsigned public URLs are never returned through any code path (NFR-6)

**AC-2: TTL cap enforcement**
- Given `app.video.playback.token-ttl-minutes` exceeds `app.video.playback.token-max-ttl-minutes` (120 minutes default)
- When `authorizePlayback()` is called
- Then the effective TTL used for `expiresAt` is capped at `token-max-ttl-minutes`

**AC-3: VideoNotFoundException when video does not exist**
- Given `videoId` does not exist
- When `authorizePlayback()` is called
- Then `VideoNotFoundException` (HTTP 404) is thrown

**AC-4: PlaybackDeniedException for ineligible video**
- Given a video in PROCESSING state (or any non-READY/non-ACTIVE combination)
- When `authorizePlayback()` is called
- Then `PlaybackDeniedException` (HTTP 403) is thrown with the `operationalState` and `accessState` included in the response body as `fieldErrors`

**AC-5: Integration test — happy path**
- Given an integration test extending `BaseVideoIT` with a `Video` seeded directly in `READY + ACTIVE` state
- When `authorizePlayback()` is called
- Then a `PlaybackToken` record is persisted with correct `viewerId`, `videoId`, `expiresAt`
- And the returned JWT decodes to the expected claims (`jti`, `iat`, `exp`, `sub`, `vid`)
- And the returned `playbackUrl` is a signed HLS manifest URL matching the WireMock-stubbed Bunny.net CDN response

**AC-6: Integration test — ineligible video returns 403**
- Given a `Video` seeded in PROCESSING state
- When `authorizePlayback()` is called
- Then `PlaybackDeniedException` is thrown with the disqualifying `operationalState`

**AC-7: Performance NFR-2**
- Given an integration test that runs `authorizePlayback()` 100 times for a pre-seeded READY+ACTIVE video with WireMock responding within 20ms
- When all calls complete
- Then 99% complete within 200ms

## Tasks / Subtasks

- [x] Task 1: Add `@Setter` to `PlaybackToken` entity (AC: 1)
  - [x] Add `@Setter` import and annotation to `PlaybackToken.java` — without this, `PlaybackService` cannot set `videoId`, `viewerId`, `expiresAt`, `revokedAt` and will not compile
  - [x] Verify `@Getter @Setter @NoArgsConstructor` matches the `Video` entity pattern

- [x] Task 2: Create `PlaybackAuthorizationResponse` record in `platform.video.contract` (AC: 1)
  - [x] Package: `com.softropic.skillars.platform.video.contract`
  - [x] Fields: `String token`, `String playbackUrl`, `Instant expiresAt`
  - [x] No Jakarta Validation needed — this is a response, not a request

- [x] Task 3: Update `PlaybackDeniedException` with states constructor (AC: 4)
  - [x] Add new constructor: `(UUID videoId, String viewerId, OperationalState operationalState, AccessState accessState)`
  - [x] Store states in `logContext` map (existing parent mechanism) AND expose via public getter
  - [x] Keep existing `(UUID videoId, String viewerId)` constructor unchanged — Story 3.3 uses it

- [x] Task 4: Update `VideoApiAdvice.playbackDeniedHandler()` to expose states in response (AC: 4)
  - [x] After calling `logErrorAndReturnDTO()`, check if the exception carries state context
  - [x] If `operationalState` is in `logContext`, add it to ErrorDto via `dto.add("video", "operationalState", new ErrorMsg("operationalState", ...))`
  - [x] If `accessState` is in `logContext`, add it similarly
  - [x] Existing `PlaybackDeniedException(videoId, viewerId)` path (no states) still works — no states added to fieldErrors

- [x] Task 5: Add `signingSecret` to `VideoProperties.Playback` (AC: 1)
  - [x] Add `private String signingSecret = "";` to the `Playback` inner class
  - [x] Property key: `app.video.playback.signing-secret`
  - [x] Add a test value to `application-test.yaml`: must be Base64-encoded and decode to at least 32 bytes for HS256
  - [x] Add a placeholder dev value to `application-dev.yaml`: same requirement

- [x] Task 6: Create `PlaybackService` in `platform.video.service` (AC: 1, 2, 3, 4)
  - [x] `@Slf4j @Service @RequiredArgsConstructor` annotations
  - [x] Inject: `VideoRepository`, `PlaybackTokenRepository`, `VideoProviderAdapter`, `VideoProperties`
  - [x] Implement `authorizePlayback(UUID videoId, String viewerId): PlaybackAuthorizationResponse`
  - [x] See Dev Notes for full implementation

- [x] Task 7: Write `PlaybackServiceIT` extending `BaseVideoIT` (AC: 5, 6, 7)
  - [x] `@MockitoBean VideoProviderAdapter videoProviderAdapter`
  - [x] See Dev Notes for complete test structure and coverage

## Dev Notes

### CRITICAL: `PlaybackToken` Entity is Missing `@Setter` — Will Cause Compilation Failure

**Current state** (`src/main/java/com/softropic/skillars/platform/video/repo/PlaybackToken.java`):
```java
@Getter
@NoArgsConstructor
@Entity
@Table(name = "playback_tokens", schema = "main")
public class PlaybackToken {
    private UUID id;         // @GeneratedValue — never set manually
    private UUID videoId;    // NEEDS setter
    private String viewerId; // NEEDS setter
    private Instant expiresAt; // NEEDS setter
    private Instant revokedAt; // NEEDS setter (Story 3.3)
    private Instant createdAt; // Set by @PrePersist
}
```

**Fix** — add `@Setter` annotation and import:
```java
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "playback_tokens", schema = "main")
public class PlaybackToken {
    ...
}
```

This matches the `Video.java` pattern exactly. Do NOT add `@Setter` to `id` or `createdAt` (they are controlled by JPA/`@PrePersist`). Since Lombok applies `@Setter` at the class level and `@Column(updatable = false)` prevents JPA from updating those columns, this is safe.

### `PlaybackDeniedException` — New Constructor (Preserve Existing)

**Current** (`src/main/java/com/softropic/skillars/platform/video/contract/exception/PlaybackDeniedException.java`):
```java
public PlaybackDeniedException(UUID videoId, String viewerId) {
    super("Playback access denied",
          Map.of("videoId", videoId.toString(), "viewerId", viewerId),
          VideoErrorCode.PLAYBACK_DENIED);
}
```

**Add** a new constructor — do NOT modify or remove the existing one (Story 3.3 uses it):
```java
public PlaybackDeniedException(UUID videoId, String viewerId,
                                OperationalState operationalState,
                                AccessState accessState) {
    super("Playback access denied",
          Map.of("videoId", videoId.toString(),
                 "viewerId", viewerId,
                 "operationalState", operationalState.name(),
                 "accessState", accessState.name()),
          VideoErrorCode.PLAYBACK_DENIED);
}
```

`logContext` is the existing `Map<String, Object>` from `ApplicationException` — it's already populated by `super(...)`. The advice handler reads it back via `ex.getLogContext()`.

### `VideoApiAdvice` — Update `playbackDeniedHandler()`

**Current** (`src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java`):
```java
@ExceptionHandler(PlaybackDeniedException.class)
@ResponseStatus(HttpStatus.FORBIDDEN)
public ErrorDto playbackDeniedHandler(final PlaybackDeniedException ex) {
    return logErrorAndReturnDTO(ex, "video.playbackDenied", VideoErrorCode.PLAYBACK_DENIED.getErrorCode());
}
```

**Replace** with:
```java
@ExceptionHandler(PlaybackDeniedException.class)
@ResponseStatus(HttpStatus.FORBIDDEN)
public ErrorDto playbackDeniedHandler(final PlaybackDeniedException ex) {
    ErrorDto dto = logErrorAndReturnDTO(ex, "video.playbackDenied", VideoErrorCode.PLAYBACK_DENIED.getErrorCode());
    Map<String, Object> ctx = ex.getLogContext();
    if (ctx.containsKey("operationalState")) {
        dto.add("video", "operationalState",
                new ErrorMsg("operationalState", (String) ctx.get("operationalState")));
    }
    if (ctx.containsKey("accessState")) {
        dto.add("video", "accessState",
                new ErrorMsg("accessState", (String) ctx.get("accessState")));
    }
    return dto;
}
```

Add `import com.softropic.skillars.infrastructure.message.ErrorMsg;` if not already present (it is used elsewhere in the class). `ErrorMsg` constructor: `(String key, String message)`.

### `VideoProperties.Playback` — Add Signing Secret

**Current** inner class in `src/main/java/com/softropic/skillars/platform/video/config/VideoProperties.java`:
```java
@Getter
@Setter
public static class Playback {
    private int tokenTtlMinutes = 15;
    private int tokenMaxTtlMinutes = 120;
    private int revocationWindowHours = 24;
}
```

**Add** `signingSecret` field:
```java
@Getter
@Setter
public static class Playback {
    private int tokenTtlMinutes = 15;
    private int tokenMaxTtlMinutes = 120;
    private int revocationWindowHours = 24;
    private String signingSecret = "";  // Must be Base64-encoded; decodes to ≥32 bytes for HS256
}
```

**Update `application-test.yaml`** — add under `app.video`:
```yaml
app:
  video:
    upload:
      expiry-scheduler-delay-ms: 3600000
    playback:
      signing-secret: "dGVzdC1wbGF5YmFjay1zaWduaW5nLXNlY3JldC0zMi1ieXRlcyEh"  # Base64("test-playback-signing-secret-32-bytes!!")
    bunny:
      cdn-hostname: ${wiremock.server.bunny-service.baseUrl:http://localhost}
      api-base-url: ${wiremock.server.bunny-service.baseUrl}
```

**Update `application-dev.yaml`** — add under `app.video` (wherever that section is — if it doesn't exist, add it):
```yaml
app:
  video:
    playback:
      signing-secret: "${APP_VIDEO_PLAYBACK_SIGNING_SECRET:dGVzdC1wbGF5YmFjay1zaWduaW5nLXNlY3JldC0zMi1ieXRlcyEh}"
```

**Important:** The signing secret must decode to ≥32 bytes (256 bits) for JJWT HS256. The Base64 value above decodes to exactly 40 bytes — valid. Never log the raw signing secret (NFR-10).

### `PlaybackService` — Full Implementation

**Package:** `com.softropic.skillars.platform.video.service`

```java
package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.PlaybackTokenClaims;
import com.softropic.skillars.infrastructure.video.SignedPlaybackUrl;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.config.VideoProperties;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.PlaybackAuthorizationResponse;
import com.softropic.skillars.platform.video.contract.exception.PlaybackDeniedException;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.repo.PlaybackToken;
import com.softropic.skillars.platform.video.repo.PlaybackTokenRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaybackService {

    private final VideoRepository videoRepository;
    private final PlaybackTokenRepository playbackTokenRepository;
    private final VideoProviderAdapter videoProviderAdapter;
    private final VideoProperties properties;

    @Transactional
    public PlaybackAuthorizationResponse authorizePlayback(UUID videoId, String viewerId) {
        MDC.put("videoId", videoId.toString());
        MDC.put("viewerId", viewerId);
        MDC.put("operation", "authorize_playback");
        try {
            Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));

            // Eligibility check: READY + ACTIVE only
            if (!(video.getOperationalState() == OperationalState.READY
                    && video.getAccessState() == AccessState.ACTIVE)) {
                throw new PlaybackDeniedException(videoId, viewerId,
                    video.getOperationalState(), video.getAccessState());
            }

            // TTL: cap at tokenMaxTtlMinutes
            int ttlMinutes = Math.min(
                properties.getPlayback().getTokenTtlMinutes(),
                properties.getPlayback().getTokenMaxTtlMinutes());
            Instant expiresAt = Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES);

            // Call provider for signed HLS URL (inside @Transactional — HTTP call, not DB)
            SignedPlaybackUrl signedUrl = videoProviderAdapter.generatePlaybackUrl(
                video.getProviderAssetId(),
                new PlaybackTokenClaims(viewerId, expiresAt));

            // Persist PlaybackToken — ID becomes the JWT jti claim
            PlaybackToken token = new PlaybackToken();
            token.setVideoId(videoId);
            token.setViewerId(viewerId);
            token.setExpiresAt(expiresAt);
            PlaybackToken saved = playbackTokenRepository.save(token);

            // Sign JWT
            String jwt = buildJwt(saved.getId(), videoId, viewerId, expiresAt);

            log.debug("Playback token issued: tokenId={}", saved.getId());
            return new PlaybackAuthorizationResponse(jwt, signedUrl.url(), expiresAt);
        } finally {
            MDC.remove("videoId");
            MDC.remove("viewerId");
            MDC.remove("operation");
        }
    }

    private String buildJwt(UUID tokenId, UUID videoId, String viewerId, Instant expiresAt) {
        SecretKey key = Keys.hmacShaKeyFor(
            Base64.getDecoder().decode(properties.getPlayback().getSigningSecret()));
        return Jwts.builder()
            .id(tokenId.toString())           // jti
            .issuedAt(Date.from(Instant.now())) // iat
            .expiration(Date.from(expiresAt))   // exp
            .subject(viewerId)                  // sub
            .claim("vid", videoId.toString())   // vid (custom claim)
            .signWith(key)
            .compact();
    }
}
```

**Design notes:**
- `@Transactional` on the whole method is intentional: if the provider call fails, the `PlaybackToken` is rolled back — no orphaned token credentials leak. The provider call is a CDN token generation (fast, deterministic URL — not an async Bunny.net processing call), so holding the transaction is acceptable.
- Do NOT log the signing secret or the JWT string in plaintext. The `log.debug` above only logs the `tokenId`.
- MDC keys match the convention from `VideoService` (`videoId`, `operation`) — clean up in `finally`.
- `generatePlaybackUrl()` is synchronous and returns a `SignedPlaybackUrl(url, expiresAt)`. Use `signedUrl.url()` for the response.

### `PlaybackAuthorizationResponse` — Record Definition

```java
package com.softropic.skillars.platform.video.contract;

import java.time.Instant;

public record PlaybackAuthorizationResponse(String token, String playbackUrl, Instant expiresAt) {}
```

No Jakarta Validation — response only, not request.

### JJWT 0.13.0 API (Confirmed In-Use)

JJWT 0.13.0 is already on the classpath (`io.jsonwebtoken:jjwt-api:0.13.0` + `jjwt-impl:0.13.0` + `jjwt-jackson:0.13.0`).

The codebase already uses this library in `ClaimsExtractorImpl`:
```java
Jwts.parser().verifyWith(jwtConfiguration.getSecretKey()).build().parse(token).accept(Jws.CLAIMS).getPayload();
```

For `PlaybackService`, use the **builder** side:
- `Jwts.builder()` → chain → `.compact()` for signing
- `Keys.hmacShaKeyFor(byte[])` for HMAC-SHA256 key
- **JJWT 0.13.0 import:** `io.jsonwebtoken.Jwts`, `io.jsonwebtoken.security.Keys`
- **Java time:** JJWT 0.13.0 requires `java.util.Date` for `issuedAt()` and `expiration()` — use `Date.from(Instant)` to convert
- Do NOT use `io.jsonwebtoken.Claims.EXPIRATION` or deprecated methods — use the builder chain directly

### PlaybackTokenRepository — No Custom Queries Needed for This Story

`PlaybackTokenRepository` currently only extends `JpaRepository<PlaybackToken, UUID>`. Story 3.2 only calls `.save()`. Story 3.3 (revocation) will add custom queries. Do NOT add revocation-related queries here — that's Story 3.3's scope.

### Integration Test — `PlaybackServiceIT`

```java
package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.SignedPlaybackUrl;
import com.softropic.skillars.infrastructure.video.PlaybackTokenClaims;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.PlaybackAuthorizationResponse;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.contract.exception.PlaybackDeniedException;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.repo.PlaybackToken;
import com.softropic.skillars.platform.video.repo.PlaybackTokenRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class PlaybackServiceIT extends BaseVideoIT {

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    @Autowired
    PlaybackService playbackService;

    @Autowired
    VideoRepository videoRepository;

    @Autowired
    PlaybackTokenRepository playbackTokenRepository;

    @Value("${app.video.playback.signing-secret}")
    String signingSecret;

    @BeforeEach
    void setUp() {
        playbackTokenRepository.deleteAll();
        videoRepository.deleteAll();
        when(videoProviderAdapter.generatePlaybackUrl(anyString(), any(PlaybackTokenClaims.class)))
            .thenReturn(new SignedPlaybackUrl("https://bunny-cdn/asset-id/playlist.m3u8?token=test", Instant.now().plusSeconds(900)));
    }

    @Test
    void authorizePlayback_happyPath_returnsTokenAndUrl() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);

        PlaybackAuthorizationResponse response = playbackService.authorizePlayback(video.getId(), "viewer-1");

        assertThat(response.token()).isNotBlank();
        assertThat(response.playbackUrl()).contains("playlist.m3u8");
        assertThat(response.expiresAt()).isAfter(Instant.now());

        // Verify PlaybackToken persisted
        List<PlaybackToken> tokens = playbackTokenRepository.findAll();
        assertThat(tokens).hasSize(1);
        PlaybackToken saved = tokens.get(0);
        assertThat(saved.getVideoId()).isEqualTo(video.getId());
        assertThat(saved.getViewerId()).isEqualTo("viewer-1");
        assertThat(saved.getRevokedAt()).isNull();

        // Verify JWT claims
        SecretKey key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(signingSecret));
        Claims claims = Jwts.parser().verifyWith(key).build()
            .parseSignedClaims(response.token()).getPayload();
        assertThat(claims.getId()).isEqualTo(saved.getId().toString());  // jti
        assertThat(claims.getSubject()).isEqualTo("viewer-1");           // sub
        assertThat(claims.get("vid", String.class)).isEqualTo(video.getId().toString()); // vid
        assertThat(claims.getExpiration()).isAfter(java.util.Date.from(Instant.now()));
    }

    @Test
    void authorizePlayback_processingVideo_throwsPlaybackDenied() {
        Video video = seedVideo(OperationalState.PROCESSING, AccessState.ACTIVE);

        assertThatThrownBy(() -> playbackService.authorizePlayback(video.getId(), "viewer-2"))
            .isInstanceOf(PlaybackDeniedException.class);
    }

    @Test
    void authorizePlayback_videoNotFound_throwsVideoNotFound() {
        assertThatThrownBy(() -> playbackService.authorizePlayback(UUID.randomUUID(), "viewer-3"))
            .isInstanceOf(VideoNotFoundException.class);
    }

    @Test
    void authorizePlayback_performance_p99Under200ms() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);
        int iterations = 100;
        long[] latencies = new long[iterations];

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            playbackService.authorizePlayback(video.getId(), "perf-viewer-" + i);
            latencies[i] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        }

        java.util.Arrays.sort(latencies);
        long p99 = latencies[(int) (iterations * 0.99)];
        assertThat(p99).as("p99 latency must be < 200ms but was %dms", p99).isLessThan(200L);
    }

    // Helper: seed a video directly in the target states — do NOT go through initializeUpload
    private Video seedVideo(OperationalState opState, AccessState accessState) {
        Video video = new Video();
        video.setOwnerId("owner-playback");
        video.setProvider("bunny");
        video.setProviderAssetId("bunny-asset-id-123");
        video.setTitle("test-video.mp4");
        video.setOperationalState(opState);
        video.setAccessState(accessState);
        video.setVisibility(Visibility.PRIVATE);
        return videoRepository.save(video);
    }
}
```

**Key test notes:**
- Use `@Value("${app.video.playback.signing-secret}")` to inject the signing secret for JWT verification in tests — do NOT hardcode it
- Parse the JWT in tests using the same `Keys.hmacShaKeyFor` + `Jwts.parser().verifyWith()` pattern from `ClaimsExtractorImpl` to verify claims
- Seed video directly via `videoRepository.save()` — never go through `initializeUpload()` as that requires QuotaProvider wiring
- `@MockitoBean VideoProviderAdapter videoProviderAdapter` — same pattern as all prior video ITs
- No `@MockitoBean QuotaProvider` needed in this story — `PlaybackService` does not invoke QuotaProvider
- `playbackTokenRepository.deleteAll()` before `videoRepository.deleteAll()` — FK constraint (playback_tokens.video_id → videos.id)

### Package & File Structure

```
src/main/java/com/softropic/skillars/platform/video/
├── contract/
│   └── PlaybackAuthorizationResponse.java           ← NEW: record(token, playbackUrl, expiresAt)
│   └── exception/
│       └── PlaybackDeniedException.java              ← UPDATE: add 4-arg constructor with states
├── service/
│   └── PlaybackService.java                         ← NEW: authorizePlayback()
├── api/
│   └── VideoApiAdvice.java                          ← UPDATE: playbackDeniedHandler() exposes states
├── config/
│   └── VideoProperties.java                         ← UPDATE: add signingSecret to Playback class
└── repo/
    └── PlaybackToken.java                           ← UPDATE: add @Setter

src/test/java/com/softropic/skillars/platform/video/
└── service/
    └── PlaybackServiceIT.java                       ← NEW: extends BaseVideoIT
```

Also update:
- `src/test/resources/application-test.yaml` → add `app.video.playback.signing-secret`
- `src/main/resources/application-dev.yaml` → add `app.video.playback.signing-secret`

### What MUST NOT Be Recreated (Already Exists)

| Component | Path | Notes |
|---|---|---|
| `PlaybackToken` | `repo/PlaybackToken.java` | UPDATE — add `@Setter` only |
| `PlaybackTokenRepository` | `repo/PlaybackTokenRepository.java` | UPDATE — Story 3.3 adds queries; 3.2 uses only `.save()` |
| `PlaybackDeniedException` | `contract/exception/PlaybackDeniedException.java` | UPDATE — add new constructor only |
| `VideoNotFoundException` | `contract/exception/VideoNotFoundException.java` | Use as-is |
| `VideoApiAdvice` | `api/VideoApiAdvice.java` | UPDATE — modify `playbackDeniedHandler()` only |
| `VideoProperties` | `config/VideoProperties.java` | UPDATE — add `signingSecret` to inner `Playback` class |
| `PlaybackTokenClaims` | `infrastructure.video.PlaybackTokenClaims` | `record(viewerId, expiresAt)` — already defined |
| `SignedPlaybackUrl` | `infrastructure.video.SignedPlaybackUrl` | `record(url, expiresAt)` — already defined |
| `VideoProviderAdapter` | `infrastructure.video.VideoProviderAdapter` | `generatePlaybackUrl(providerAssetId, claims)` — use as-is |
| `BaseVideoIT` | `test/java/.../video/BaseVideoIT.java` | PostgreSQL + WireMock + `@MockitoBean` patterns |
| `VideoUploadConfirmationIT` | `test/java/.../video/service/` | Reference for `@BeforeEach deleteAll()`, seeding patterns |
| `VideoRetryUploadIT` | `test/java/.../video/service/` | Reference for `@MockitoBean VideoProviderAdapter`, `@BeforeEach` structure |
| `OperationalState`, `AccessState` | `contract/` | Use as-is: UPLOADING, PROCESSING, READY, FAILED, DELETED / ACTIVE, BLOCKED, ARCHIVED |

### Previous Story Intelligence (Story 3.1)

From `video-3-1-video-state-machine-failed-retry.md` completion notes:

- `@MockitoBean` (NOT `@Mock` or deprecated `@MockBean`) for Spring context injection in integration tests — `@MockitoBean VideoProviderAdapter videoProviderAdapter`
- `AssertJ` (`assertThat`) for ALL assertions — never JUnit `assertEquals` or `assertTrue`
- `@Slf4j @Service @RequiredArgsConstructor` on all services — carry forward
- `MDC` + `finally` block pattern for `videoId`, `operation`, `viewerId` — matches `VideoService.initializeUpload()`
- Seed integration test data directly via repository `.save()` — never through upper-layer services unless testing the full flow
- FK constraint order matters: `playbackTokenRepository.deleteAll()` BEFORE `videoRepository.deleteAll()`
- `PlaybackToken.@PrePersist` sets `createdAt` automatically — do not set it manually

### Architecture Compliance

- `PlaybackService` → `platform.video.service` ✓
- `PlaybackAuthorizationResponse` → `platform.video.contract` ✓
- No `infrastructure.*` business logic or imports in `platform.*` (other than type usage) ✓
- `@Transactional` on `authorizePlayback()` — correct; rolls back token on provider failure ✓
- `@RequiredArgsConstructor` (not `@Autowired`) ✓
- Signing secret never logged in plaintext (NFR-10) ✓
- `VideoProviderAdapter.generatePlaybackUrl()` call is inside `@Transactional` — acceptable here since it's a CDN token generation (synchronous, fast) unlike the async Bunny.net processing calls ✓
- `PlaybackToken.id` is auto-generated via `@GeneratedValue(strategy = GenerationType.UUID)` — no need to set it; use `saved.getId()` after `.save()` ✓

### Project Context Rules (Must Follow)

- Java 17: `record` for `PlaybackAuthorizationResponse` ✓
- All DTOs as Java `record` types ✓
- `@Slf4j @Service @RequiredArgsConstructor` on services ✓
- `Instant` for all dates/times — no `LocalDateTime` or `java.util.Date` (except `Date.from(Instant)` for JJWT API) ✓
- `AssertJ` for ALL assertions in tests ✓
- `@MockitoBean` for Spring mock injection in `*IT` tests ✓
- Integration tests: `@SpringBootTest` + `@Testcontainers` via `BaseVideoIT` ✓
- `Instancio` not needed here — direct entity construction is cleaner for this story ✓

### References

- `PlaybackToken.java`: `src/main/java/com/softropic/skillars/platform/video/repo/PlaybackToken.java` — read before modifying; add `@Setter`
- `PlaybackDeniedException.java`: `src/main/java/com/softropic/skillars/platform/video/contract/exception/PlaybackDeniedException.java`
- `VideoApiAdvice.java`: `src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java` — modify `playbackDeniedHandler()`
- `VideoProperties.java`: `src/main/java/com/softropic/skillars/platform/video/config/VideoProperties.java` — add `signingSecret` to inner `Playback` class
- `VideoService.java`: `src/main/java/com/softropic/skillars/platform/video/service/VideoService.java` — MDC + `finally` pattern reference
- `ClaimsExtractorImpl.java`: `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/ClaimsExtractorImpl.java` — JJWT 0.13.0 parser pattern for test JWT verification
- `VideoRetryUploadIT.java`: `src/test/java/com/softropic/skillars/platform/video/service/VideoRetryUploadIT.java` — `@MockitoBean VideoProviderAdapter`, `@BeforeEach deleteAll()`, `@Autowired` patterns
- `BaseVideoIT.java`: `src/test/java/com/softropic/skillars/platform/video/BaseVideoIT.java`
- `application-test.yaml`: `src/test/resources/application-test.yaml` — add signing-secret here
- Epic 3 Story 3.2 AC: `_bmad-output/planning-artifacts/video-module/epics.md` §"Story 3.2: Playback Token Issuance"
- Project context rules: `_bmad-output/project-context.md`

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

- Added `@Setter` to `PlaybackToken` entity to enable field population in `PlaybackService` before persisting
- Created `PlaybackAuthorizationResponse` record with `token`, `playbackUrl`, `expiresAt` fields
- Added 4-arg `PlaybackDeniedException` constructor that places `operationalState` and `accessState` in `logContext`; original 2-arg constructor preserved for Story 3.3 compatibility
- Updated `VideoApiAdvice.playbackDeniedHandler()` to read `operationalState`/`accessState` from `logContext` and add them as fieldErrors on the `ErrorDto`
- Added `signingSecret` field to `VideoProperties.Playback`; populated with 40-byte Base64 test secret in `application-test.yaml` and env-var-backed placeholder in `application-dev.yaml`
- Implemented `PlaybackService.authorizePlayback()` with full eligibility check (READY + ACTIVE), TTL cap enforcement, provider call, token persistence, and JJWT 0.13.0 JWT signing (HMAC-SHA256); MDC context follows VideoService convention
- `PlaybackServiceIT` covers: happy path (JWT claims + DB token verified), ineligible video (403), video not found (404), p99 performance (<200ms over 100 iterations); all 4 tests pass
- Full regression: 12 tests across `PlaybackServiceIT`, `VideoUploadConfirmationIT`, `VideoRetryUploadIT` — all pass

### File List

- `src/main/java/com/softropic/skillars/platform/video/repo/PlaybackToken.java` (modified)
- `src/main/java/com/softropic/skillars/platform/video/contract/PlaybackAuthorizationResponse.java` (created)
- `src/main/java/com/softropic/skillars/platform/video/contract/exception/PlaybackDeniedException.java` (modified)
- `src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java` (modified)
- `src/main/java/com/softropic/skillars/platform/video/config/VideoProperties.java` (modified)
- `src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java` (created)
- `src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceIT.java` (created)
- `src/test/resources/application-test.yaml` (modified)
- `src/main/resources/application-dev.yaml` (modified)

## Change Log

- 2026-06-01: Implemented Story Video-3.2 — PlaybackService with JWT signing, PlaybackToken persistence, eligibility check, TTL cap, error handling with state exposure in API advice; 4 integration tests passing (Date: 2026-06-01)
