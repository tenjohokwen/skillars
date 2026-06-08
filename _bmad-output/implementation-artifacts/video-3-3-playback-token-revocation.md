# Story Video-3.3: Playback Token Revocation

Status: review

## Story

As a consuming application,
I want to revoke all active Playback Tokens for a viewer on security events, and validate individual tokens against the revocation state before authorizing playback,
So that previously issued tokens are immediately rejected and the viewer cannot use stale credentials to stream video content.

## Acceptance Criteria

**AC-1: Revoke all active tokens for a viewer**
- Given a `viewerId` whose account has experienced a security event (password change, account suspension, explicit logout, or security incident)
- When `PlaybackService.revokeTokensForViewer(viewerId)` is called
- Then `revokedAt = now` is set on all `PlaybackToken` records for that `viewerId` where `revokedAt IS NULL AND expiresAt > NOW()`
- And returns the count of revoked tokens
- And already-expired tokens (`expiresAt <= NOW()`) are NOT updated — only active non-revoked tokens are touched

**AC-2: Validate token — revoked token rejected**
- Given a `tokenId` (the `jti` claim UUID from a previously issued JWT)
- When `PlaybackService.validateToken(tokenId)` is called
- Then if `PlaybackToken.revokedAt IS NOT NULL`, throws `PlaybackDeniedException(token.getVideoId(), token.getViewerId())` (HTTP 403) with reason `PLAYBACK_DENIED`

**AC-3: Validate token — expired token rejected**
- Given a `tokenId` for a token whose `expiresAt < NOW()`
- When `PlaybackService.validateToken(tokenId)` is called
- Then throws `PlaybackDeniedException(token.getVideoId(), token.getViewerId())` (HTTP 403)

**AC-4: Validate token — valid token returns metadata**
- Given a `tokenId` for a non-revoked, non-expired token
- When `PlaybackService.validateToken(tokenId)` is called
- Then returns the `PlaybackToken` entity for the caller's use

**AC-5: validateToken — unknown tokenId**
- Given a `tokenId` that does not exist in the database
- When `PlaybackService.validateToken(tokenId)` is called
- Then throws `PlaybackDeniedException` (HTTP 403) — unknown tokens are denied, not 404

**AC-6: authorizePlayback — revocation window blocks new token issuance**
- Given `authorizePlayback(videoId, viewerId)` is called for a viewer who has a recently revoked token (within `app.video.playback.revocation-window-hours`, default 24 hours)
- When the playback eligibility check passes (video is READY + ACTIVE)
- Then before issuing a new token, the service checks if `viewerId` has any `PlaybackToken` with `revokedAt IS NOT NULL AND revokedAt > NOW() - revocationWindowHours`
- And if a recent revocation exists, throws `PlaybackDeniedException(videoId, viewerId)` (2-arg constructor, HTTP 403) with reason `PLAYBACK_DENIED`
- And no new `PlaybackToken` record is created
- And the consuming app must wait out the revocation window (or configure it to zero to disable) before the viewer can receive new tokens

**AC-7: authorizePlayback — zero revocation window disables the block**
- Given `app.video.playback.revocation-window-hours = 0`
- When `authorizePlayback(videoId, viewerId)` is called for a viewer with a recent revocation
- Then the revocation window check is skipped entirely and a new token is issued normally

**AC-8: Unit tests**
- `revokeTokensForViewer()` marks only active (non-expired, non-already-revoked) tokens — already-expired tokens unchanged
- `validateToken()` rejects a revoked token with `PlaybackDeniedException`; rejects an expired token; accepts a valid active token; rejects unknown tokenId
- `authorizePlayback()` for a viewer with a recent revocation returns `PlaybackDeniedException` rather than issuing a new token
- `authorizePlayback()` succeeds when revocation window has elapsed

## Tasks / Subtasks

- [x] Task 1: Add custom queries to `PlaybackTokenRepository` (AC: 1, 6)
  - [x] Add `@Modifying @Query` bulk-update for revoking active tokens by `viewerId`
  - [x] Add `boolean` query to check for recent revocation by `viewerId` + window start `Instant`
  - [x] See Dev Notes for exact JPQL

- [x] Task 2: Add `revokeTokensForViewer(viewerId)` to `PlaybackService` (AC: 1)
  - [x] Call the bulk-update repository method with `Instant.now()` as both `revokedAt` and `now`
  - [x] Return the count from the repository (int)
  - [x] Wrap in MDC context: `viewerId`, `operation=revoke_tokens`

- [x] Task 3: Add `validateToken(tokenId)` to `PlaybackService` (AC: 2, 3, 4, 5)
  - [x] Load `PlaybackToken` by ID — if not found, throw `PlaybackDeniedException(null, null)` → use the `(UUID videoId, String viewerId)` 2-arg constructor safely (pass UUIDs from token or zero UUID if absent)
  - [x] Check `revokedAt != null` first → throw 2-arg `PlaybackDeniedException`
  - [x] Check `expiresAt.isBefore(Instant.now())` → throw 2-arg `PlaybackDeniedException`
  - [x] Return the `PlaybackToken` entity
  - [x] No `@Transactional` needed — read-only

- [x] Task 4: Update `authorizePlayback(videoId, viewerId)` with revocation window check (AC: 6, 7)
  - [x] Insert check AFTER eligibility check, BEFORE TTL computation and token issuance
  - [x] Only check when `revocationWindowHours > 0` (zero disables)
  - [x] Use `playbackTokenRepository.hasRecentRevocation(viewerId, Instant.now().minus(revocationWindowHours, ChronoUnit.HOURS))`
  - [x] On match: throw `new PlaybackDeniedException(videoId, viewerId)` — 2-arg constructor (no state context)
  - [x] See Dev Notes for exact insertion point in the method

- [x] Task 5: Write `PlaybackRevocationIT` extending `BaseVideoIT` (AC: 1, 2, 3, 4, 5, 6, 7, 8)
  - [x] See Dev Notes for complete test structure

## Dev Notes

### EXISTING CODE — DO NOT RECREATE OR REPLACE

| Component | Current State | This Story |
|---|---|---|
| `PlaybackToken.java` | `@Getter @Setter @NoArgsConstructor @Entity` — already has `revokedAt` nullable field | **No changes needed** — entity is complete |
| `VideoProperties.Playback` | Already has `revocationWindowHours = 24` field | **No changes needed** — property exists |
| `PlaybackDeniedException` | Has 2-arg `(UUID videoId, String viewerId)` AND 4-arg `(…, OperationalState, AccessState)` constructors | Use **2-arg constructor** for revocation/validation denials |
| `PlaybackService.authorizePlayback()` | Complete — eligibility check, TTL cap, provider call, token persist, JWT sign | **UPDATE only** — insert revocation window check in one place |

### `PlaybackTokenRepository` — New Queries

Add to the existing bare interface (`extends JpaRepository<PlaybackToken, UUID>`):

```java
package com.softropic.skillars.platform.video.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface PlaybackTokenRepository extends JpaRepository<PlaybackToken, UUID> {

    @Modifying
    @Query("UPDATE PlaybackToken t SET t.revokedAt = :revokedAt " +
           "WHERE t.viewerId = :viewerId AND t.revokedAt IS NULL AND t.expiresAt > :now")
    int revokeActiveTokensForViewer(@Param("viewerId") String viewerId,
                                    @Param("revokedAt") Instant revokedAt,
                                    @Param("now") Instant now);

    @Query("SELECT COUNT(t) > 0 FROM PlaybackToken t " +
           "WHERE t.viewerId = :viewerId AND t.revokedAt IS NOT NULL AND t.revokedAt > :windowStart")
    boolean hasRecentRevocation(@Param("viewerId") String viewerId,
                                @Param("windowStart") Instant windowStart);
}
```

**Critical:** `@Modifying` requires `@Transactional` on the calling method (or at the repository layer via Spring's default). The `revokeTokensForViewer` service method must be `@Transactional`.

### `PlaybackService` — `revokeTokensForViewer` Method

Add to the existing `PlaybackService` class:

```java
@Transactional
public int revokeTokensForViewer(String viewerId) {
    MDC.put("viewerId", viewerId);
    MDC.put("operation", "revoke_tokens");
    try {
        Instant now = Instant.now();
        int count = playbackTokenRepository.revokeActiveTokensForViewer(viewerId, now, now);
        log.info("Revoked {} active playback token(s) for viewer", count);
        return count;
    } finally {
        MDC.remove("viewerId");
        MDC.remove("operation");
    }
}
```

### `PlaybackService` — `validateToken` Method

```java
public PlaybackToken validateToken(UUID tokenId) {
    MDC.put("operation", "validate_token");
    try {
        PlaybackToken token = playbackTokenRepository.findById(tokenId)
            .orElseThrow(() -> new PlaybackDeniedException(
                tokenId,  // treat tokenId as a placeholder videoId for the log context
                "unknown"));
        if (token.getRevokedAt() != null) {
            throw new PlaybackDeniedException(token.getVideoId(), token.getViewerId());
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new PlaybackDeniedException(token.getVideoId(), token.getViewerId());
        }
        return token;
    } finally {
        MDC.remove("operation");
    }
}
```

**Note on unknown token:** The 2-arg `PlaybackDeniedException(UUID videoId, String viewerId)` is designed for this — pass the `tokenId` as `videoId` and `"unknown"` as `viewerId` so the log context captures the requested tokenId for audit purposes. No 404 is returned — unknown tokens are always a security concern and must be denied uniformly.

**No `@Transactional`** — this is a read-only method. Spring transactions are not needed for `findById`.

### `PlaybackService` — Update `authorizePlayback` with Revocation Window Check

Insert the following block **after** the eligibility check and **before** the TTL computation (between lines 54 and 56 in the current file):

```java
// Revocation window check: block new tokens if viewer has a recent revocation
int windowHours = properties.getPlayback().getRevocationWindowHours();
if (windowHours > 0) {
    Instant windowStart = Instant.now().minus(windowHours, ChronoUnit.HOURS);
    if (playbackTokenRepository.hasRecentRevocation(viewerId, windowStart)) {
        throw new PlaybackDeniedException(videoId, viewerId);
    }
}
```

**Exact insertion point** in `authorizePlayback` (current file `PlaybackService.java:50–58`):

```java
// EXISTING — eligibility check (keep as-is)
if (!(video.getOperationalState() == OperationalState.READY
        && video.getAccessState() == AccessState.ACTIVE)) {
    throw new PlaybackDeniedException(videoId, viewerId,
        video.getOperationalState(), video.getAccessState());
}

// NEW — revocation window check (insert here)
int windowHours = properties.getPlayback().getRevocationWindowHours();
if (windowHours > 0) {
    Instant windowStart = Instant.now().minus(windowHours, ChronoUnit.HOURS);
    if (playbackTokenRepository.hasRecentRevocation(viewerId, windowStart)) {
        throw new PlaybackDeniedException(videoId, viewerId);
    }
}

// EXISTING — TTL computation (keep as-is)
int ttlMinutes = Math.min(
    properties.getPlayback().getTokenTtlMinutes(),
    properties.getPlayback().getTokenMaxTtlMinutes());
```

Also add `import java.time.temporal.ChronoUnit;` if not already in the imports — check current file; it already imports `java.time.temporal.ChronoUnit` (**it does** — visible in the current `PlaybackService.java`). No new import needed.

### `PlaybackRevocationIT` — Integration Test

```java
package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.PlaybackTokenClaims;
import com.softropic.skillars.infrastructure.video.SignedPlaybackUrl;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.contract.exception.PlaybackDeniedException;
import com.softropic.skillars.platform.video.repo.PlaybackToken;
import com.softropic.skillars.platform.video.repo.PlaybackTokenRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = "app.video.playback.revocation-window-hours=24")
class PlaybackRevocationIT extends BaseVideoIT {

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    @Autowired
    PlaybackService playbackService;

    @Autowired
    VideoRepository videoRepository;

    @Autowired
    PlaybackTokenRepository playbackTokenRepository;

    @BeforeEach
    void setUp() {
        playbackTokenRepository.deleteAll();  // FK constraint: tokens first
        videoRepository.deleteAll();
        when(videoProviderAdapter.generatePlaybackUrl(anyString(), any(PlaybackTokenClaims.class)))
            .thenReturn(new SignedPlaybackUrl("https://bunny-cdn/asset/playlist.m3u8?token=test",
                Instant.now().plusSeconds(900)));
    }

    // --- revokeTokensForViewer ---

    @Test
    void revokeTokensForViewer_revokesActiveNonExpiredTokens() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);

        // Issue two tokens for viewer-A
        playbackService.authorizePlayback(video.getId(), "viewer-A");
        playbackService.authorizePlayback(video.getId(), "viewer-A");

        int count = playbackService.revokeTokensForViewer("viewer-A");

        assertThat(count).isEqualTo(2);
        playbackTokenRepository.findAll().forEach(t ->
            assertThat(t.getRevokedAt()).isNotNull());
    }

    @Test
    void revokeTokensForViewer_doesNotRevokeAlreadyExpiredTokens() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);
        // Seed an already-expired token directly
        PlaybackToken expired = new PlaybackToken();
        expired.setVideoId(video.getId());
        expired.setViewerId("viewer-B");
        expired.setExpiresAt(Instant.now().minusSeconds(3600));  // expired 1h ago
        playbackTokenRepository.save(expired);

        int count = playbackService.revokeTokensForViewer("viewer-B");

        assertThat(count).isEqualTo(0);  // expired token not touched
        PlaybackToken check = playbackTokenRepository.findAll().get(0);
        assertThat(check.getRevokedAt()).isNull();  // still null — we didn't set it
    }

    @Test
    void revokeTokensForViewer_idempotent_alreadyRevokedNotDoubleUpdated() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);
        playbackService.authorizePlayback(video.getId(), "viewer-C");

        int first = playbackService.revokeTokensForViewer("viewer-C");
        int second = playbackService.revokeTokensForViewer("viewer-C");  // already revoked

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);  // second call finds no active tokens
    }

    // --- validateToken ---

    @Test
    void validateToken_validToken_returnsMetadata() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);
        var response = playbackService.authorizePlayback(video.getId(), "viewer-D");

        // Extract tokenId from the persisted token
        PlaybackToken saved = playbackTokenRepository.findAll().get(0);
        PlaybackToken result = playbackService.validateToken(saved.getId());

        assertThat(result.getViewerId()).isEqualTo("viewer-D");
        assertThat(result.getVideoId()).isEqualTo(video.getId());
        assertThat(result.getRevokedAt()).isNull();
    }

    @Test
    void validateToken_revokedToken_throwsPlaybackDenied() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);
        playbackService.authorizePlayback(video.getId(), "viewer-E");
        playbackService.revokeTokensForViewer("viewer-E");

        PlaybackToken revoked = playbackTokenRepository.findAll().get(0);
        assertThatThrownBy(() -> playbackService.validateToken(revoked.getId()))
            .isInstanceOf(PlaybackDeniedException.class);
    }

    @Test
    void validateToken_expiredToken_throwsPlaybackDenied() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);
        PlaybackToken expired = new PlaybackToken();
        expired.setVideoId(video.getId());
        expired.setViewerId("viewer-F");
        expired.setExpiresAt(Instant.now().minusSeconds(1));
        PlaybackToken saved = playbackTokenRepository.save(expired);

        assertThatThrownBy(() -> playbackService.validateToken(saved.getId()))
            .isInstanceOf(PlaybackDeniedException.class);
    }

    @Test
    void validateToken_unknownTokenId_throwsPlaybackDenied() {
        assertThatThrownBy(() -> playbackService.validateToken(UUID.randomUUID()))
            .isInstanceOf(PlaybackDeniedException.class);
    }

    // --- authorizePlayback + revocation window ---

    @Test
    void authorizePlayback_viewerWithRecentRevocation_throwsPlaybackDenied() {
        Video video = seedVideo(OperationalState.READY, AccessState.ACTIVE);

        // Issue and revoke tokens for viewer-G
        playbackService.authorizePlayback(video.getId(), "viewer-G");
        playbackService.revokeTokensForViewer("viewer-G");

        // Immediately try again — within 24-hour window
        assertThatThrownBy(() -> playbackService.authorizePlayback(video.getId(), "viewer-G"))
            .isInstanceOf(PlaybackDeniedException.class);
    }

    @Test
    @TestPropertySource(properties = "app.video.playback.revocation-window-hours=0")
    void authorizePlayback_zeroRevocationWindow_allowsNewTokenAfterRevocation() {
        // NOTE: @TestPropertySource on method level may not work in Spring Boot IT.
        // If it doesn't override, manually seed the revocation and rely on window=0 via
        // a separate test application config. Alternative approach: refactor to test the
        // window==0 branch directly via unit test of PlaybackService.
        // See Dev Notes for guidance.
    }

    // Helper: seed a video directly in the target states
    private Video seedVideo(OperationalState opState, AccessState accessState) {
        Video video = new Video();
        video.setOwnerId("owner-revocation");
        video.setProvider("bunny");
        video.setProviderAssetId("bunny-asset-revoke-123");
        video.setTitle("test-revocation.mp4");
        video.setOperationalState(opState);
        video.setAccessState(accessState);
        video.setVisibility(Visibility.PRIVATE);
        return videoRepository.save(video);
    }
}
```

**Note on zero-window test:** `@TestPropertySource` on method level does NOT override Spring Boot's application context — use a separate `@SpringBootTest` nested class or a dedicated profile. The simplest approach: test the revocation window == 0 branch as a unit test by creating a `PlaybackService` with a mock repository and `VideoProperties` configured to `revocationWindowHours = 0`. This avoids the context-reload overhead.

### `@TestPropertySource` on `PlaybackRevocationIT` class level
The class-level `@TestPropertySource(properties = "app.video.playback.revocation-window-hours=24")` sets the default. The `application-test.yaml` already has `revocationWindowHours` unset (inheriting the Java default of 24) — so no YAML change is needed. Confirm by checking `src/test/resources/application-test.yaml`.

### FK Delete Order — CRITICAL

Always delete in this order in `@BeforeEach`:
```java
playbackTokenRepository.deleteAll();  // child — must come FIRST
videoRepository.deleteAll();          // parent
```
Reversing this causes a FK constraint violation (`playback_tokens.video_id → videos.id`).

This matches the existing `PlaybackServiceIT` pattern.

### Existing `PlaybackServiceIT` — Do NOT Modify

`PlaybackServiceIT.java` at `src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceIT.java` tests `authorizePlayback()`. Story 3.3 adds a **new** test class `PlaybackRevocationIT` — do NOT modify the existing tests.

**Regression check after this story:** Run both `PlaybackServiceIT` AND `PlaybackRevocationIT` to confirm the `authorizePlayback()` update (revocation window check insertion) didn't break the happy path.

### Package & File Structure

```
src/main/java/com/softropic/skillars/platform/video/
├── repo/
│   └── PlaybackTokenRepository.java    ← UPDATE: add 2 custom queries
└── service/
    └── PlaybackService.java            ← UPDATE: add 2 methods + window check in authorizePlayback

src/test/java/com/softropic/skillars/platform/video/
└── service/
    └── PlaybackRevocationIT.java       ← NEW: extends BaseVideoIT
```

### Architecture Compliance

- `PlaybackService` → `platform.video.service` ✓
- `PlaybackTokenRepository` → `platform.video.repo` ✓
- No new DTOs, records, or exceptions needed — existing `PlaybackDeniedException` (2-arg) covers all denial cases ✓
- `revokeTokensForViewer` is `@Transactional` (required by `@Modifying` query) ✓
- `validateToken` is NOT `@Transactional` (read-only, no JPA writes) ✓
- 2-arg `PlaybackDeniedException` used for revocation/validation cases (no state context) ✓
- 4-arg `PlaybackDeniedException` remains unchanged — still used by eligibility check in `authorizePlayback` ✓
- MDC context added to new service methods per convention ✓
- Raw signing secrets never logged (NFR-10) ✓

### Project Context Rules (Must Follow)

- `@Modifying` JPQL updates require `@Transactional` on caller — `revokeTokensForViewer` has it ✓
- `AssertJ` (`assertThat` / `assertThatThrownBy`) for ALL assertions — never JUnit `assertEquals` ✓
- `@MockitoBean VideoProviderAdapter` for Spring IT mock injection ✓
- Integration tests extend `BaseVideoIT` ✓
- `Instant` for all timestamps ✓
- No `@Autowired` on fields — use `@Autowired` on field injection in IT tests (project pattern from `PlaybackServiceIT`) ✓

### References

- `PlaybackService.java`: `src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java` — READ before modifying; insert revocation window check at lines 54–58
- `PlaybackTokenRepository.java`: `src/main/java/com/softropic/skillars/platform/video/repo/PlaybackTokenRepository.java` — ADD 2 queries
- `PlaybackToken.java`: `src/main/java/com/softropic/skillars/platform/video/repo/PlaybackToken.java` — read-only reference; entity complete with `revokedAt`, `expiresAt`; `@Getter @Setter` in place
- `VideoProperties.java`: `src/main/java/com/softropic/skillars/platform/video/config/VideoProperties.java` — `Playback.revocationWindowHours` already exists (default 24); no changes needed
- `PlaybackDeniedException.java`: `src/main/java/com/softropic/skillars/platform/video/contract/exception/PlaybackDeniedException.java` — 2-arg constructor `(UUID videoId, String viewerId)` is the one to use for revocation denials
- `PlaybackServiceIT.java`: `src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceIT.java` — reference for test patterns; do NOT modify
- `BaseVideoIT.java`: `src/test/java/com/softropic/skillars/platform/video/BaseVideoIT.java` — base class for all video integration tests
- `application-test.yaml`: `src/test/resources/application-test.yaml` — signing-secret already configured; no new keys needed
- Epic 3 Story 3.3 AC: `_bmad-output/planning-artifacts/video-module/epics.md` §"Story 3.3: Playback Token Revocation"
- Project context rules: `_bmad-output/project-context.md`
- OQ-4 (open question from addendum): Redis bloom filter as revocation accelerator — out of scope for this story; DB `revokedAt` column is the mandated baseline per FR-18

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

- ✅ Task 1: Added `revokeActiveTokensForViewer` (@Modifying JPQL bulk-update) and `hasRecentRevocation` (boolean JPQL) to `PlaybackTokenRepository`.
- ✅ Task 2: Added `revokeTokensForViewer(viewerId)` to `PlaybackService` — @Transactional, MDC context, returns revoked count.
- ✅ Task 3: Added `validateToken(UUID tokenId)` to `PlaybackService` — denies revoked, expired, and unknown tokens via 2-arg `PlaybackDeniedException`; not @Transactional (read-only).
- ✅ Task 4: Inserted revocation window check in `authorizePlayback()` after eligibility check; skipped entirely when `revocationWindowHours == 0`.
- ✅ Task 5: `PlaybackRevocationIT` (9 integration tests) + `PlaybackRevocationWindowUnitTest` (1 Mockito unit test for zero-window branch). All 14 playback tests pass; no regressions in `PlaybackServiceIT`.

### File List

- `src/main/java/com/softropic/skillars/platform/video/repo/PlaybackTokenRepository.java` (modified)
- `src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java` (modified)
- `src/test/java/com/softropic/skillars/platform/video/service/PlaybackRevocationIT.java` (new)
- `src/test/java/com/softropic/skillars/platform/video/service/PlaybackRevocationWindowUnitTest.java` (new)

## Change Log

- 2026-06-01: Story created (Status: ready-for-dev)
- 2026-06-01: Story implemented (Status: review) — PlaybackTokenRepository queries, revokeTokensForViewer, validateToken, authorizePlayback revocation window check, PlaybackRevocationIT (9 tests), PlaybackRevocationWindowUnitTest (1 test)
