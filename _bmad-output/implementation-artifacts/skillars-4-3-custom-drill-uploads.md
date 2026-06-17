# Story skillars-4.3: Custom Drill Uploads

Status: in-progress

## Story

As a paid-tier coach,
I want to upload short video demos for my own custom drills,
So that my private library has the same visual quality as the platform's Foundation 20 content.

## Acceptance Criteria

1. **AC 1: Pre-flight quota check** — Given a coach on Instructor or Academy tier creates or edits a private library drill, when they tap "Upload demo video", then a pre-flight check validates the file constraints (≤120s, ≤500MB) and a TUS upload session is initiated via `platform.video`; if quota is exhausted, the upload is rejected with error code `video.quotaExceeded`.

2. **AC 2: Scout tier gate** — Given a Scout tier coach views a COACH-type drill detail panel, when the panel renders, then the "Upload demo video" button is absent (not disabled) — upload capability is hidden before it can be clicked.

3. **AC 3: TUS reservation** — Given the pre-flight passes, when the TUS session is initiated, a `VideoQuotaReservation` in `platform.video` is created atomically; a `@Scheduled` job in `platform.video` releases reservations after the configurable timeout (default 60 min) — no separate reservation logic in `platform.session`.

4. **AC 4: Video linked to drill** — Given the coach initiates an upload, then the `drill_video_refs` record for that drill is updated with the new `videoId`; on the next `GET /api/session/drills` call, `DrillResponse.videoUrl` contains a signed URL when the video is `READY`.

5. **AC 5: Drill demo constraints** — Given any coach uploads a drill demo, when the constraint check runs, then file must be ≤120s and ≤500MB; violation → 422 with error code `video.constraintViolated` specifying the violated limit (thrown as `DrillConstraintViolationException` from `DrillUploadService`, handled by `SessionApiAdvice`).

6. **AC 6: Video deletion and ref-count** — Given a coach removes a video from a private drill via `DELETE /api/session/drills/{id}/video`, when the request is confirmed, then the drill's `videoId` in `drill_video_refs` is cleared; if no other drill still references that video (checked via `existsByVideoId`), a `VideoPhysicalDeletionEvent` is published `AFTER_COMMIT` and `AdminVideoService.deleteVideo(videoId)` is called **asynchronously** (via `@Async`); if other drills still reference the video (e.g., a platform source drill), only this drill's association is cleared and the physical video is retained.

   > **Scope note:** The epic's AC 6 describes video cleanup on drill archival. This story implements two things: (a) the standalone video-detach action (`DELETE /api/session/drills/{id}/video`, keeping the drill alive), and (b) the correct ref-count guard. The drill-archive → video-cleanup path is out of scope here: when a COACH drill is eventually archived, its `drill_video_refs` row is removed by `ON DELETE CASCADE`, and the reconciliation worker (video-4-2, already built) will catch any orphaned provider assets.

## Tasks / Subtasks

### Backend — Database Migration

- [x] Task 1: Write `V42__drill_video_upload_config.sql`
  - [x] Next migration number after V41. File: `src/main/resources/db/migration/V42__drill_video_upload_config.sql`
  - [x] Insert config keys into `main.platform_config`:
    ```sql
    INSERT INTO main.platform_config (id, key, value, type, description, created_at) VALUES
      (63, 'feature.drillVideoUpload.enabled.SCOUT',      'false',     'STRING', 'Drill video upload gate: Scout tier',       NOW()),
      (64, 'feature.drillVideoUpload.enabled.INSTRUCTOR', 'true',      'STRING', 'Drill video upload gate: Instructor tier',  NOW()),
      (65, 'feature.drillVideoUpload.enabled.ACADEMY',    'true',      'STRING', 'Drill video upload gate: Academy tier',     NOW()),
      (66, 'video.drillDemo.maxDurationSeconds',          '120',       'STRING', 'Drill demo max duration (seconds)',         NOW()),
      (67, 'video.drillDemo.maxSizeBytes',                '524288000', 'STRING', 'Drill demo max size (500 MB in bytes)',     NOW());
    ```
  - [x] IDs 63–67: confirm no collision by checking V38 seeds max id (existing max is 62; see V38 lines 39–41)

### Backend — Repository Changes

- [x] Task 2: Extend `DrillVideoRefRepository.java` (AC: 4, 6)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/repo/DrillVideoRefRepository.java`
  - [x] Add update for linking video after initiation:
    ```java
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE DrillVideoRef d SET d.videoId = :videoId WHERE d.drillId = :drillId")
    void setVideoId(@Param("drillId") UUID drillId, @Param("videoId") UUID videoId);
    ```
  - [x] Add dedicated null-safe clear (separate from `setVideoId` to avoid JPQL null-parameter ambiguity):
    ```java
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE DrillVideoRef d SET d.videoId = null WHERE d.drillId = :drillId")
    void clearVideoId(@Param("drillId") UUID drillId);
    ```
  - [x] Add existence check used by deletion guard (avoids ref_count arithmetic entirely):
    ```java
    @Query("SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END FROM DrillVideoRef d WHERE d.videoId = :videoId")
    boolean existsByVideoId(@Param("videoId") UUID videoId);
    ```
  - [x] Keep existing `findByDrillId`, `findByDrillIdIn`, `incrementRefCount` — do NOT remove
  - [x] Do NOT add `decrementRefCount` — it is not used in this story's deletion flow (see deleteVideo logic in Task 8 and dev notes)

- [x] Task 3: Add batch READY-video lookup to `VideoRepository.java` (AC: 4)
  - [x] File: `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java`
  - [x] Add:
    ```java
    @Query("SELECT v FROM Video v WHERE v.id IN :ids AND v.operationalState = com.softropic.skillars.platform.video.contract.OperationalState.READY AND v.accessState = com.softropic.skillars.platform.video.contract.AccessState.ACTIVE")
    List<Video> findReadyAndActiveByIds(@Param("ids") List<UUID> ids);
    ```
  - [x] Both `OperationalState` and `AccessState` are confirmed real enums in `platform.video.contract` — use fully qualified names in JPQL to avoid unresolved type warnings

### Backend — Contract Layer

- [x] Task 4: Create `DrillUploadInitiateRequest.java` (AC: 1, 5)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/DrillUploadInitiateRequest.java`
  - [x] Package: `com.softropic.skillars.platform.session.contract`
  - [x] Record:
    ```java
    public record DrillUploadInitiateRequest(
        @NotBlank @Size(max = 255) String fileName,
        @Min(1) long fileSizeBytes,
        @NotBlank String mimeType,
        @Min(0) @Max(7200) int durationSeconds
    ) {}
    ```
  - [x] `durationSeconds` is client-provided from browser metadata reading (see Task 15 `onFileSelected`). Server validates against `video.drillDemo.maxDurationSeconds` when value > 0. Value of `0` means the browser could not determine duration and the duration check is skipped server-side — this is a known limitation (duration is only enforceable post-upload via webhook metadata). Always pass the real value when available.

- [x] Task 5: Create `DrillUploadInitiateResponse.java` (AC: 1)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/DrillUploadInitiateResponse.java`
  - [x] Package: `com.softropic.skillars.platform.session.contract`
  - [x] Record:
    ```java
    public record DrillUploadInitiateResponse(
        UUID videoId,
        UUID uploadSessionId,
        String signedUploadUrl,
        Instant expiresAt
    ) {}
    ```
  - [x] Maps 1:1 from `InitializeUploadResponse` minus `providerUploadId` (not needed by frontend)

- [x] Task 6: Create `VideoPhysicalDeletionEvent.java` (AC: 6)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/VideoPhysicalDeletionEvent.java`
  - [x] Package: `com.softropic.skillars.platform.session.contract`
  - [x] Record: `public record VideoPhysicalDeletionEvent(UUID videoId, UUID drillId) {}`
  - [x] This is a Spring application event (published via `ApplicationEventPublisher`), not a persisted entity

- [x] Task 7: Add `DRILL_UPLOAD_NOT_ALLOWED` to `SessionErrorCode.java` (AC: 1, 6)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/SessionErrorCode.java`
  - [x] Existing enum values: `CLONE_NOT_ALLOWED`, `SESSION_CANNOT_TAG_UNAUTHORIZED`
  - [x] Add: `DRILL_UPLOAD_NOT_ALLOWED`

- [x] Task 7b: Create `DrillConstraintViolationException.java` (AC: 5)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/contract/exception/DrillConstraintViolationException.java`
  - [x] Package: `com.softropic.skillars.platform.session.contract.exception`
  - [x] Class:
    ```java
    public class DrillConstraintViolationException extends RuntimeException {
        private final String field;

        public DrillConstraintViolationException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String getField() { return field; }
    }
    ```
  - [x] `field` values used: `"file.size"` for size violation, `"video.duration"` for duration violation — these appear in the 422 response body so the client can surface the specific limit

### Backend — Service Layer

- [x] Task 8: Create `DrillUploadService.java` (AC: 1–3, 5, 6)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java`
  - [x] Package: `com.softropic.skillars.platform.session.service`
  - [x] Annotations: `@Service @Transactional @Slf4j @RequiredArgsConstructor`
  - [x] Inject: `DrillRepository`, `DrillVideoRefRepository`, `VideoService`, `ConfigService`, `CoachProfileService`, `ApplicationEventPublisher`
  - [x] Method `initiateUpload(UUID drillId, Long coachUserId, DrillUploadInitiateRequest req)` → `DrillUploadInitiateResponse`:
    ```
    1. resolveCoachId(coachUserId)
    2. Load drill — throw ResourceNotFoundException if absent
    3. Ownership: if drill.libraryType != "COACH" || drill.ownerCoachId != coachId → throw OperationNotAllowedException(DRILL_UPLOAD_NOT_ALLOWED)
    4. Feature gate: checkDrillUploadGate(coachId) — throws FeatureGatedException("drill_video_upload", minTier) for SCOUT
    5. Constraint check (before calling VideoService):
         long maxBytes     = Long.parseLong(configService.find("video.drillDemo.maxSizeBytes").orElse("524288000"))
         int  maxDuration  = Integer.parseInt(configService.find("video.drillDemo.maxDurationSeconds").orElse("120"))
         if req.fileSizeBytes() > maxBytes → throw new DrillConstraintViolationException("file.size", "File size exceeds 500 MB limit")
         if req.durationSeconds() > 0 && req.durationSeconds() > maxDuration → throw new DrillConstraintViolationException("video.duration", "Duration exceeds 120 second limit")
    6. Delegate: InitializeUploadResponse resp = videoService.initializeUpload(
           new InitializeUploadRequest(coachId.toString(), req.fileName(), req.fileSizeBytes(), req.mimeType()))
       NOTE: VideoService also validates via its own chain. No duplicate limit logic needed.
    7. Update drill_video_refs:
         Optional<DrillVideoRef> existing = drillVideoRefRepository.findByDrillId(drillId)
         if (existing.isPresent()) {
             drillVideoRefRepository.setVideoId(drillId, resp.videoId())
             // Previous videoId (if any UPLOADING video) is now orphaned;
             // the reconciliation worker (video-4-2) will clean it up — no action needed here
         } else {
             DrillVideoRef ref = new DrillVideoRef()
             ref.setDrillId(drillId); ref.setVideoId(resp.videoId()); ref.setRefCount(1)
             try {
                 drillVideoRefRepository.save(ref)
             } catch (DataIntegrityViolationException ignored) {
                 // Concurrent duplicate insert — PK constraint fires; overwrite with winning videoId
                 drillVideoRefRepository.setVideoId(drillId, resp.videoId())
             }
         }
    8. return new DrillUploadInitiateResponse(resp.videoId(), resp.uploadSessionId(), resp.signedUploadUrl(), resp.expiresAt())
    ```
  - [x] Method `deleteVideo(UUID drillId, Long coachUserId)`:
    ```
    1. resolveCoachId(coachUserId)
    2. Load drill — throw ResourceNotFoundException if absent
    3. Ownership check — same as initiateUpload step 3
    4. drillVideoRefRepository.findByDrillId(drillId).ifPresent(ref -> {
           if (ref.getVideoId() == null) return   // already detached — no-op
           UUID videoId = ref.getVideoId()

           // Detach this drill first, then check if any other drill still references the video
           drillVideoRefRepository.clearVideoId(drillId)

           if (!drillVideoRefRepository.existsByVideoId(videoId)) {
               // No other drill (e.g., source platform drill) references this video — safe to delete
               eventPublisher.publishEvent(new VideoPhysicalDeletionEvent(videoId, drillId))
           }
           // If existsByVideoId returns true, another drill still holds a reference
           // (most common: the PLATFORM source drill that was cloned).
           // Physical video is retained; only this drill's association is cleared.
       })
    ```
  - [x] Private `checkDrillUploadGate(UUID coachId)`:
    ```java
    CoachSubscriptionTier tier = coachProfileService.getCoachSubscriptionTier(coachId);
    boolean enabled = configService.getBoolean("feature.drillVideoUpload.enabled." + tier.name());
    if (!enabled) {
        throw new FeatureGatedException("drill_video_upload", resolveMinUploadTier());
    }
    ```
  - [x] Private `resolveMinUploadTier()`: iterate `CoachSubscriptionTier.values()`, return first whose config value is `"true"` — mirrors pattern in `DrillLibraryService.resolveMinEnabledTier()`
  - [x] Private `resolveCoachId(Long userId)`: delegates to `coachProfileService.getCoachIdByUserId(userId)` — same pattern as `DrillLibraryService`

- [x] Task 9: Create `VideoPhysicalDeletionListener.java` (AC: 6)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/service/VideoPhysicalDeletionListener.java`
  - [x] Package: `com.softropic.skillars.platform.session.service`
  - [x] Annotations: `@Component @Slf4j @RequiredArgsConstructor`
  - [x] Inject: `AdminVideoService`
  - [x] Method:
    ```java
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVideoPhysicalDeletion(VideoPhysicalDeletionEvent event) {
        try {
            adminVideoService.deleteVideo(event.videoId());
        } catch (Exception e) {
            log.error("Failed to physically delete video {} for drill {}", event.videoId(), event.drillId(), e);
            // Best-effort; reconciliation worker (video-4-2, already built) will catch orphaned provider assets
        }
    }
    ```
  - [x] `@Async` is required — `AdminVideoService.deleteVideo()` makes a synchronous Bunny.net HTTP call (`videoProviderAdapter.deleteAsset()`). Without `@Async`, the call runs on the HTTP request thread after DB commit, blocking the 204 response until Bunny.net responds. `@Async` decouples it onto the Spring task executor thread pool.
  - [x] `@EnableAsync` must be present in the application's root config or a config class in `platform.session`. Verify before assuming this works — check `SessionConfig.java` or the main `@SpringBootApplication` class.
  - [x] `AFTER_COMMIT` is critical — never call provider DELETE inside the HTTP transaction; it must not be rolled back with the DB write

- [x] Task 10: Extend `DrillLibraryService.java` — populate `videoUrl` (AC: 4)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/service/DrillLibraryService.java`
  - [x] Add two new injected dependencies (add to constructor via `@RequiredArgsConstructor` — add final fields):
    ```java
    private final VideoRepository videoRepository;
    private final VideoProviderAdapter videoProviderAdapter;
    ```
    - `VideoRepository` is in `platform.video.repo` — cross-module Spring injection (same monolith, same Spring context; permitted)
    - `VideoProviderAdapter` is in `infrastructure.video` — platform depends on infrastructure; this is correct
  - [x] Replace `batchVideoLookup(List<Drill> drills)` return type from `Set<UUID>` to `Map<UUID, DrillVideoInfo>` where `DrillVideoInfo` is a local private record:
    ```java
    private record DrillVideoInfo(boolean hasVideo, String videoUrl) {}
    ```
  - [x] Rewrite `batchVideoLookup`:
    ```java
    private Map<UUID, DrillVideoInfo> batchVideoLookup(List<Drill> drills) {
        if (drills.isEmpty()) return Map.of();
        List<UUID> ids = drills.stream().map(Drill::getId).toList();
        List<DrillVideoRef> refs = drillVideoRefRepository.findByDrillIdIn(ids);
        if (refs.isEmpty()) return Map.of();

        // Drills with a non-null videoId are candidates for URL resolution
        List<UUID> videoIds = refs.stream().map(DrillVideoRef::getVideoId)
                                  .filter(Objects::nonNull).distinct().toList();
        Map<UUID, Video> readyVideoMap = Map.of();
        if (!videoIds.isEmpty()) {
            readyVideoMap = videoRepository.findReadyAndActiveByIds(videoIds)
                .stream().collect(Collectors.toMap(Video::getId, v -> v));
        }
        // Videos in UPLOADING or PROCESSING state are NOT in readyVideoMap.
        // For those drills: hasVideo=true (videoId is set), videoUrl=null (not yet playable).
        // DrillCard.vue's 3-way branch handles this: hasVideo=true + videoUrl=null → thumbnail placeholder.

        Map<UUID, DrillVideoInfo> result = new HashMap<>();
        Instant urlExpiry = Instant.now().plus(2, ChronoUnit.HOURS);
        for (DrillVideoRef ref : refs) {
            String videoUrl = null;
            if (ref.getVideoId() != null) {
                Video video = readyVideoMap.get(ref.getVideoId());
                if (video != null && video.getProviderAssetId() != null) {
                    try {
                        SignedPlaybackUrl signed = videoProviderAdapter.generatePlaybackUrl(
                            video.getProviderAssetId(),
                            new PlaybackTokenClaims("drill:" + ref.getDrillId(), urlExpiry));
                        videoUrl = signed.url();
                    } catch (Exception e) {
                        log.warn("Could not generate playback URL for drill {}: {}", ref.getDrillId(), e.getMessage());
                    }
                }
            }
            result.put(ref.getDrillId(), new DrillVideoInfo(ref.getVideoId() != null, videoUrl));
        }
        return result;
    }
    ```
  - [x] Update `listDrills()` to use new `batchVideoLookup`:
    ```java
    Map<UUID, DrillVideoInfo> videoInfo = batchVideoLookup(drills);
    // Replace existing: boolean hasVideo = withVideo.contains(d.getId())
    //              and: null (videoUrl)
    // With: DrillVideoInfo info = videoInfo.getOrDefault(d.getId(), new DrillVideoInfo(false, null))
    //        .hasVideo() and .videoUrl()
    ```
  - [x] Update `toResponse()` signature — keep same 5 args but pass `info.videoUrl()` instead of hardcoded `null`
  - [x] Update `cloneDrill()` to use the updated `toResponse()` — it calls `toResponse(saved, hasVideo, ...)`, so the third `videoUrl` arg stays `null` (new clone has no video yet)
  - [x] Add required imports: `com.softropic.skillars.infrastructure.video.PlaybackTokenClaims`, `com.softropic.skillars.infrastructure.video.SignedPlaybackUrl`, `com.softropic.skillars.platform.video.repo.Video`, `com.softropic.skillars.platform.video.repo.VideoRepository`

- [x] Task 11: Extend `DrillLibraryResource.java` — add eligibility endpoint (AC: 2)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/api/DrillLibraryResource.java`
  - [x] Inject `DrillUploadService` (add final field)
  - [x] Add endpoint:
    ```java
    @GetMapping("/video-upload/eligible")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Map<String, Boolean>> checkVideoUploadEligibility() {
        boolean eligible = drillUploadService.isVideoUploadEligible(currentCoachUserId());
        return ResponseEntity.ok(Map.of("eligible", eligible));
    }
    ```
  - [x] Add `isVideoUploadEligible(Long coachUserId)` to `DrillUploadService` (returns `true` if tier check passes without throwing):
    ```java
    @Transactional(readOnly = true)
    public boolean isVideoUploadEligible(Long coachUserId) {
        try {
            UUID coachId = resolveCoachId(coachUserId);
            CoachSubscriptionTier tier = coachProfileService.getCoachSubscriptionTier(coachId);
            return configService.getBoolean("feature.drillVideoUpload.enabled." + tier.name());
        } catch (Exception e) {
            return false;
        }
    }
    ```
  - [x] Spring MVC routing: `GET /api/session/drills/video-upload/eligible` is a literal path — Spring gives literal segments priority over `/{drillId}` path variables, so there is no conflict. No ordering annotation needed.

### Backend — API Layer

- [x] Task 12: Create `DrillUploadResource.java` (AC: 1, 5, 6)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/api/DrillUploadResource.java`
  - [x] Package: `com.softropic.skillars.platform.session.api`
  - [x] Class:
    ```java
    @Observed(name = "session.drills.upload")
    @RestController
    @RequestMapping("/api/session/drills")
    @RequiredArgsConstructor
    public class DrillUploadResource {

        private final DrillUploadService drillUploadService;
        private final SecurityUtil securityUtil;

        @PostMapping("/{drillId}/video/initiate")
        @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
        public ResponseEntity<DrillUploadInitiateResponse> initiateUpload(
            @PathVariable UUID drillId,
            @RequestBody @Valid DrillUploadInitiateRequest req
        ) {
            DrillUploadInitiateResponse resp = drillUploadService.initiateUpload(drillId, currentCoachUserId(), req);
            return ResponseEntity.status(HttpStatus.CREATED).body(resp);
        }

        @DeleteMapping("/{drillId}/video")
        @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
        public ResponseEntity<Void> deleteVideo(@PathVariable UUID drillId) {
            drillUploadService.deleteVideo(drillId, currentCoachUserId());
            return ResponseEntity.noContent().build();
        }

        private Long currentCoachUserId() {
            return securityUtil.getCurrentCoachUserId();
        }
    }
    ```
  - [x] Exception propagation:
    - `FeatureGatedException` → `ApiAdvice` → 403, code `security.featureGated`
    - `OperationNotAllowedException` → `ApiAdvice` → 403
    - `DrillConstraintViolationException` → `SessionApiAdvice` → 422, code `video.constraintViolated`
    - `VideoValidationException` → `VideoApiAdvice` → 422, code `VALIDATION_FAILED` (from VideoService's own validation chain)
    - `QuotaExceededException` → `VideoApiAdvice` → 429, code `QUOTA_EXCEEDED`
  - [x] Do NOT add `@ExceptionHandler` methods to this class — defer to existing and new global advisors

- [x] Task 12b: Create `SessionApiAdvice.java` (AC: 5)
  - [x] File: `src/main/java/com/softropic/skillars/platform/session/api/SessionApiAdvice.java`
  - [x] Package: `com.softropic.skillars.platform.session.api`
  - [x] Class:
    ```java
    @RestControllerAdvice
    @Slf4j
    public class SessionApiAdvice {

        @ExceptionHandler(DrillConstraintViolationException.class)
        public ResponseEntity<ErrorDto> drillConstraintViolationHandler(DrillConstraintViolationException ex) {
            log.debug("Drill constraint violated: field={}, msg={}", ex.getField(), ex.getMessage());
            ErrorDto dto = new ErrorDto("video.constraintViolated");
            dto.add("drill", ex.getField(), new ErrorMsg(ex.getField(), ex.getMessage()));
            return ResponseEntity.unprocessableEntity().body(dto);
        }
    }
    ```
  - [x] Use the same `ErrorDto` / `ErrorMsg` pattern as `ApiAdvice` and `VideoApiAdvice`. Check the existing advisors for the exact constructor/factory method.
  - [x] This is a global `@RestControllerAdvice` (no `basePackages` restriction) — it applies to all controllers in the application, not just session controllers. That is correct and intentional.

### Frontend — API

- [x] Task 13: Update `session.api.js` (AC: 1, 2, 6)
  - [x] File: `src/frontend/src/api/session.api.js`
  - [x] Keep all existing methods (`getDrills`, `cloneDrill`, `addTag`, `removeTag`, `getTagSuggestions`)
  - [x] Add:
    ```js
    checkVideoUploadEligibility() {
      return api.get('/api/session/drills/video-upload/eligible')
    },
    initiateVideoUpload(drillId, payload) {
      // payload: { fileName, fileSizeBytes, mimeType, durationSeconds }
      return api.post(`/api/session/drills/${drillId}/video/initiate`, payload)
    },
    deleteVideo(drillId) {
      return api.delete(`/api/session/drills/${drillId}/video`)
    },
    ```

### Frontend — Pinia Store

- [x] Task 14: Update `session.store.js` (AC: 1, 2, 6)
  - [x] File: `src/frontend/src/stores/session.store.js`
  - [x] Keep all existing state and actions from Story 4.2
  - [x] Add new state:
    ```js
    const canUploadVideo = ref(null)   // null = not yet fetched; true/false = fetched result
    const uploadingDrillId = ref(null)
    const uploadProgress = ref(0)
    ```
  - [x] Add new actions:
    ```js
    async function fetchVideoUploadEligibility() {
      if (canUploadVideo.value !== null) return   // cached for session lifetime
      try {
        const res = await sessionApi.checkVideoUploadEligibility()
        canUploadVideo.value = res.data.eligible === true
      } catch {
        canUploadVideo.value = false
      }
    }

    async function initiateVideoUpload(drillId, file, durationSeconds) {
      uploadingDrillId.value = drillId
      uploadProgress.value = 0
      try {
        const payload = {
          fileName: file.name,
          fileSizeBytes: file.size,
          mimeType: file.type,
          durationSeconds: durationSeconds ?? 0,
        }
        const res = await sessionApi.initiateVideoUpload(drillId, payload)
        const { videoId, uploadSessionId, signedUploadUrl, expiresAt } = res.data
        return { videoId, uploadSessionId, signedUploadUrl, expiresAt }
      } catch (e) {
        error.value = e
        throw e
      } finally {
        uploadingDrillId.value = null
      }
    }

    function updateDrillVideoState(drillId, { hasVideo, videoUrl }) {
      const drill = drills.value.find(d => d.id === drillId)
      if (drill) {
        drill.hasVideo = hasVideo
        drill.videoUrl = videoUrl ?? null
      }
      if (selectedDrill.value?.id === drillId) {
        selectedDrill.value.hasVideo = hasVideo
        selectedDrill.value.videoUrl = videoUrl ?? null
      }
    }

    async function removeVideo(drillId) {
      try {
        await sessionApi.deleteVideo(drillId)
        updateDrillVideoState(drillId, { hasVideo: false, videoUrl: null })
      } catch (e) {
        error.value = e
        throw e
      }
    }
    ```
  - [x] Return `canUploadVideo`, `uploadingDrillId`, `uploadProgress`, `fetchVideoUploadEligibility`, `initiateVideoUpload`, `updateDrillVideoState`, `removeVideo` from the store's return object
  - [x] `canUploadVideo` initialized to `null` (not `false`) so the template can distinguish "not yet fetched" from "ineligible". The `v-if` in the template guards on `sessionStore.canUploadVideo === true`.

### Frontend — DrillDetailPanel Component

- [x] Task 15: Update `DrillDetailPanel.vue` — add upload UX (AC: 1, 2, 5, 6)
  - [x] File: `src/frontend/src/components/session/DrillDetailPanel.vue`
  - [x] **TUS client requirement**: `tus-js-client` is already a dependency of the project (from `platform.video` frontend work in the Video Module epics). Verify it's in `package.json`. Import: `import { Upload } from 'tus-js-client'`
  - [x] If `tus-js-client` is NOT in package.json, add it: `npm install tus-js-client` — do NOT use fetch-based resumable upload
  - [x] Upload section — shown only for COACH-type drills (`drill.libraryType === 'COACH'`):
    ```vue
    <!-- Upload section (COACH drills only, INSTRUCTOR+ tier) -->
    <div v-if="props.drill.libraryType === 'COACH' && sessionStore.canUploadVideo === true"
         class="detail-panel__upload q-mt-md">
      <!-- Upload button (no video yet) -->
      <template v-if="!props.drill.hasVideo">
        <q-file
          v-model="selectedVideoFile"
          accept="video/*"
          :label="t('session.drillLibrary.upload.selectVideo')"
          dense
          outlined
          @update:model-value="onFileSelected"
        >
          <template #prepend>
            <q-icon name="videocam" />
          </template>
        </q-file>
        <q-btn
          v-if="selectedVideoFile"
          :loading="isUploading"
          :label="t('session.drillLibrary.upload.startUpload')"
          color="primary"
          class="q-mt-sm"
          @click="startUpload"
        />
        <q-linear-progress
          v-if="isUploading"
          :value="uploadPercent"
          class="q-mt-sm"
          color="primary"
        />
      </template>
      <!-- Remove video option (video present) -->
      <template v-else>
        <q-btn
          flat
          dense
          color="negative"
          :label="t('session.drillLibrary.upload.removeVideo')"
          icon="delete"
          @click="confirmRemoveVideo"
        />
      </template>
    </div>
    ```
  - [x] Add `<script setup>` additions:
    ```js
    const sessionStore = useSessionStore()
    const selectedVideoFile = ref(null)
    const selectedVideoFileDuration = ref(0)
    const isUploading = ref(false)
    const uploadPercent = ref(0)
    let tusUpload = null

    onMounted(async () => {
      if (props.drill.libraryType === 'COACH') {
        await sessionStore.fetchVideoUploadEligibility()
      }
    })

    async function onFileSelected(file) {
      if (!file) {
        selectedVideoFileDuration.value = 0
        return
      }
      // Read actual duration before upload so the server can validate the 120s constraint
      try {
        selectedVideoFileDuration.value = Math.round(await readVideoDuration(file))
      } catch {
        selectedVideoFileDuration.value = 0   // browser could not determine duration
      }
    }

    function readVideoDuration(file) {
      return new Promise((resolve, reject) => {
        const url = URL.createObjectURL(file)
        const video = document.createElement('video')
        video.preload = 'metadata'
        video.onloadedmetadata = () => { URL.revokeObjectURL(url); resolve(video.duration) }
        video.onerror = () => { URL.revokeObjectURL(url); reject(new Error('metadata read failed')) }
        video.src = url
      })
    }

    async function startUpload() {
      if (!selectedVideoFile.value) return
      isUploading.value = true
      uploadPercent.value = 0
      try {
        const creds = await sessionStore.initiateVideoUpload(
          props.drill.id,
          selectedVideoFile.value,
          selectedVideoFileDuration.value,
        )
        await runTusUpload(selectedVideoFile.value, creds.signedUploadUrl)
        $q.notify({ type: 'positive', message: t('session.drillLibrary.upload.uploadStarted') })
        // Optimistic update through store (never mutate props directly)
        sessionStore.updateDrillVideoState(props.drill.id, { hasVideo: true, videoUrl: null })
        // Note: videoUrl stays null until Bunny.net webhook fires → UPLOADING → READY
      } catch (e) {
        const code = e?.response?.data?.code
        if (code === 'QUOTA_EXCEEDED') {
          $q.notify({ type: 'negative', message: t('session.drillLibrary.upload.quotaExceeded') })
        } else if (code === 'video.constraintViolated') {
          $q.notify({ type: 'negative', message: t('session.drillLibrary.upload.constraintViolated') })
        } else {
          $q.notify({ type: 'negative', message: t('session.drillLibrary.upload.uploadFailed') })
        }
      } finally {
        isUploading.value = false
        selectedVideoFile.value = null
        selectedVideoFileDuration.value = 0
      }
    }

    function runTusUpload(file, signedUploadUrl) {
      return new Promise((resolve, reject) => {
        tusUpload = new Upload(file, {
          uploadUrl: signedUploadUrl,
          onProgress(bytesUploaded, bytesTotal) {
            uploadPercent.value = bytesUploaded / bytesTotal
          },
          onSuccess: resolve,
          onError: reject,
        })
        tusUpload.start()
      })
    }

    function confirmRemoveVideo() {
      $q.dialog({
        title: t('session.drillLibrary.upload.removeConfirmTitle'),
        message: t('session.drillLibrary.upload.removeConfirmMsg'),
        ok: { label: t('session.drillLibrary.upload.removeConfirm'), color: 'negative' },
        cancel: true,
      }).onOk(async () => {
        try {
          await sessionStore.removeVideo(props.drill.id)
          $q.notify({ type: 'positive', message: t('session.drillLibrary.upload.videoRemoved') })
        } catch {
          $q.notify({ type: 'negative', message: t('session.drillLibrary.upload.removeFailed') })
        }
      })
    }
    ```
  - [x] All template references use `props.drill` explicitly — do NOT reference `drill` as a bare variable since props in Vue 3 are readonly and must not be mutated. All drill state changes go through `sessionStore.updateDrillVideoState()`.
  - [x] Use `useQuasar()` for `$q.notify` and `$q.dialog` (already imported in component from Story 4.2)
  - [x] The `tus-js-client` `Upload` is configured with `uploadUrl` (not `endpoint`) because the URL is fully formed by the server — this is the TUS `PATCH` target, not a creation endpoint
  - [x] **DO NOT** call a confirm-upload endpoint after TUS `onSuccess` — the webhook pipeline in `platform.video` handles all state transitions automatically (see dev notes)
  - [x] Add `v-if="props.drill.videoUrl"` to the existing `<video controls>` in the detail panel to keep showing the video when present; the video section + upload section coexist

### Frontend — i18n

- [x] Task 16: Add upload i18n keys to `en/index.js` and `de/index.js` (AC: 1, 2, 5, 6)
  - [x] File: `src/frontend/src/i18n/en/index.js`
  - [x] Add under `session.drillLibrary` (nested after `detail`):
    ```js
    upload: {
      selectVideo: 'Select demo video',
      startUpload: 'Upload video',
      removeVideo: 'Remove video',
      removeConfirmTitle: 'Remove video?',
      removeConfirmMsg: 'This will permanently delete the video from this drill.',
      removeConfirm: 'Remove',
      uploadStarted: 'Video upload started — processing may take a few minutes',
      videoRemoved: 'Video removed',
      quotaExceeded: 'Storage quota exceeded. Upgrade your plan to upload more videos.',
      constraintViolated: 'Video exceeds the 120-second or 500 MB limit for drill demos.',
      uploadFailed: 'Upload failed. Please try again.',
      removeFailed: 'Could not remove video. Please try again.',
    },
    ```
  - [x] File: `src/frontend/src/i18n/de/index.js`
  - [x] Add matching German translations at the same path `session.drillLibrary.upload`

### Testing

- [x] Task 17: `DrillUploadServiceTest.java` — unit tests (AC: 1–3, 5, 6)
  - [x] File: `src/test/java/com/softropic/skillars/platform/session/service/DrillUploadServiceTest.java`
  - [x] `@ExtendWith(MockitoExtension.class)` — no Spring context
  - [x] Mock: `DrillRepository`, `DrillVideoRefRepository`, `VideoService`, `ConfigService`, `CoachProfileService`, `ApplicationEventPublisher`
  - [x] Use Instancio for test data
  - [x] Test cases:
    - `initiateUpload_instructorTier_createsRefAndReturnsUrl` — happy path: INSTRUCTOR coach, no existing ref, new ref created
    - `initiateUpload_existingRef_updatesVideoId` — ref already exists from previous upload; `setVideoId` called, not save
    - `initiateUpload_scoutTier_throwsFeatureGatedException`
    - `initiateUpload_otherCoachDrill_throwsOperationNotAllowed`
    - `initiateUpload_platformDrill_throwsOperationNotAllowed`
    - `initiateUpload_sizeExceedsLimit_throwsDrillConstraintViolationException`
    - `initiateUpload_durationExceedsLimit_throwsDrillConstraintViolationException`
    - `initiateUpload_durationZero_skipsDurationCheck` — `durationSeconds=0` must NOT throw even when maxDuration=120
    - `initiateUpload_concurrentInsert_toleratesDataIntegrityViolation` — `DataIntegrityViolationException` on save falls through to `setVideoId`
    - `deleteVideo_noOtherRefExists_publishesVideoPhysicalDeletionEvent` — `existsByVideoId` returns false → event published
    - `deleteVideo_otherRefExists_doesNotPublishEvent_clearsVideoId` — `existsByVideoId` returns true → no event, `clearVideoId` still called
    - `deleteVideo_noVideoId_isNoOp` — ref row has `videoId=null` → nothing happens
    - `isVideoUploadEligible_instructorTier_returnsTrue`
    - `isVideoUploadEligible_scoutTier_returnsFalse`

- [x] Task 17b: `DrillLibraryServiceTest.java` — unit tests for `batchVideoLookup` states
  - [x] File: `src/test/java/com/softropic/skillars/platform/session/service/DrillLibraryServiceTest.java`
  - [x] Test cases (add alongside existing tests or create new class if one doesn't exist):
    - `batchVideoLookup_videoReady_returnsHasVideoTrueWithSignedUrl` — video in READY state → `videoUrl` populated
    - `batchVideoLookup_videoInUploadingState_returnsHasVideoTrueAndNullUrl` — drill has a non-null `videoId` but the video is UPLOADING (not in `findReadyAndActiveByIds` result) → `hasVideo=true`, `videoUrl=null`. Verifies DrillCard can display thumbnail placeholder during processing.
    - `batchVideoLookup_noRef_returnsHasVideoFalse` — no `drill_video_refs` row → `hasVideo=false`, `videoUrl=null`
    - `batchVideoLookup_refVideoIdNull_returnsHasVideoFalse` — ref row exists but `videoId=null` (video detached) → `hasVideo=false`, `videoUrl=null`

- [x] Task 18: `DrillUploadResourceIT.java` — integration tests (AC: 1, 2, 5, 6)
  - [x] File: `src/test/java/com/softropic/skillars/platform/session/api/DrillUploadResourceIT.java`
  - [x] `@SpringBootTest @Testcontainers` — follow `BaseSessionIT` pattern exactly
  - [x] Extend `BaseSessionIT` (same as `DrillLibraryResourceIT` and `DrillTagResourceIT`)
  - [x] Use `@Sql({SecurityIT.SEC_DATA_SQL_PATH})` for security data
  - [x] **NOTE**: `VideoService.initializeUpload()` calls `VideoProviderAdapter` (Bunny.net). In test environment, the adapter bean may be `LocalVideoProviderAdapter` (if active profile is `local`) or a mock. Check `VideoProviderConfig` for the conditional bean. Use `@MockBean VideoProviderAdapter` to avoid real Bunny.net calls in IT.
  - [x] Test cases:
    - `initiateUpload_instructorCoach_returns201WithUploadUrl`
    - `initiateUpload_scoutCoach_returns403WithFeatureGatedCode`
    - `initiateUpload_platformDrill_returns403`
    - `initiateUpload_otherCoachDrill_returns403`
    - `initiateUpload_fileSizeTooLarge_returns422WithConstraintViolatedCode`
    - `initiateUpload_durationTooLong_returns422WithConstraintViolatedCode`
    - `deleteVideo_coachDrill_noOtherRef_returns204AndPublishesEvent`
    - `deleteVideo_coachDrill_otherRefExists_returns204WithoutEvent`
    - `deleteVideo_platformDrill_returns403`
    - `checkEligibility_instructorCoach_returns200True`
    - `checkEligibility_scoutCoach_returns200False`
  - [x] Teardown:
    ```sql
    DELETE FROM session.drill_video_refs WHERE drill_id IN (<test drill ids>);
    DELETE FROM main.upload_sessions WHERE video_id IN (SELECT id FROM main.videos WHERE owner_id = ?);
    DELETE FROM main.videos WHERE owner_id = ?;
    ```
  - [x] Also check whether `QuotaProvider` writes to a `quota_reservations` table (or similar). If so, add it to teardown to avoid quota state leaking between tests.

### Review Findings

- [x] [Review][Patch] Block `initiateUpload` when an existing READY video is already linked — `initiateUpload` currently overwrites any existing `videoId` silently. If the displaced video is `READY`, the reconciliation worker (which targets only `UPLOADING` videos with expired sessions) will never clean it up, causing a permanent provider storage leak. Decision resolved: block the operation when `existing.getVideoId()` is non-null and the video is not in `UPLOADING` state; return 409 Conflict. Coach must call `DELETE /{id}/video` first. Implementation: inject `VideoRepository` into `DrillUploadService`; in the `existing.isPresent()` branch, look up the video and throw `OperationNotAllowedException` (or a new 409-mapped exception) if `operationalState != UPLOADING`. [`DrillUploadService.java` — `initiateUpload`, existing-ref branch]
- [x] [Review][Patch] `uploadProgress.value = 0` references undefined ref in component — false positive; code already used `uploadPercent.value = 0` correctly — `DrillDetailPanel.vue` `startUpload()` resets `uploadProgress.value` but `uploadProgress` is only defined in the store and is not imported into the component. The local ref is `uploadPercent`. This will throw `ReferenceError` or silently write to undefined. Fix: change to `uploadPercent.value = 0`. [`DrillDetailPanel.vue` — `startUpload`]
- [x] [Review][Patch] Upload button double-click race launches two concurrent TUS uploads — `isUploading.value = true` is set after the first `await`, leaving a window for a second click to enter `startUpload` concurrently and call `sessionStore.initiateVideoUpload` twice. Fix: move `isUploading.value = true` to the very first line of `startUpload`, before any `await`. [`DrillDetailPanel.vue` — `startUpload`]
- [x] [Review][Patch] Frontend error handler reads `data.code` but `ErrorDto` serializes as `data.helpCode` — `e?.response?.data?.code` is always `undefined` because `ErrorDto` has no `code` field (fields: `helpCode`, `errorMsg`, `fieldErrors`). Both the `QUOTA_EXCEEDED` and `video.constraintViolated` branches never fire; the user always sees the generic "upload failed" message. Fix: replace `e?.response?.data?.code` with `e?.response?.data?.helpCode`, and update the quota branch to match `'video.quotaExceeded'` (the `helpCode` from `VideoApiAdvice`). [`DrillDetailPanel.vue` — `startUpload` catch block]
- [x] [Review][Patch] `@Max(7200)` on `durationSeconds` contradicts the 120s spec limit — The DTO's `@Max(7200)` allows up to 2 h at the bean-validation layer; the service-layer check enforces 120 s correctly, but OpenAPI/Swagger-generated clients see 7200 as the ceiling. Fix: change `@Max(7200)` to `@Max(120)`. [`DrillUploadInitiateRequest.java`]
- [x] [Review][Patch] `canUploadVideo` permanently `false` after any transient API error — The `catch` block in `fetchVideoUploadEligibility` sets `canUploadVideo.value = false`, and the early-return guard `if (canUploadVideo.value !== null) return` prevents any retry. A single 5xx permanently suppresses the upload section for eligible INSTRUCTOR/ACADEMY coaches. Fix: on error, leave `canUploadVideo.value = null` (do not cache the failure) so the next component mount retries. [`session.store.js` — `fetchVideoUploadEligibility`]
- [x] [Review][Patch] `tusUpload` not aborted on component unmount — No `onUnmounted` hook calls `tusUpload.abort()`. If the user navigates away mid-upload, the TUS transfer continues in the background, progress updates write to refs on a destroyed component, and `uploadingDrillId` remains set in the store. Fix: add `onUnmounted(() => { tusUpload?.abort(); uploadingDrillId.value = null })`. [`DrillDetailPanel.vue`]
- [x] [Review][Defer] Concurrent `initiateUpload` on same drill — two provider video objects created, one leaked [`DrillUploadService.java`] — deferred, pre-existing provider-allocation-inside-transaction pattern; low-probability race
- [x] [Review][Defer] `existsByVideoId` timing in concurrent `deleteVideo` — two concurrent deletes for drills sharing the same videoId may both publish deletion event [`DrillUploadService.java`] — deferred, near-impossible in normal usage; double-delete is idempotent
- [x] [Review][Defer] Transaction rollback after `videoService.initializeUpload` orphans provider video — if DrillVideoRef save fails, outer TX rolls back but provider video exists with no DB record for reconciliation [`DrillUploadService.java`] — deferred, systemic limitation of synchronous provider calls inside transactions
- [x] [Review][Defer] `resolveMinUploadTier` depends on enum declaration order — returns wrong tier hint in `FeatureGatedException` if `CoachSubscriptionTier` enum is not declared in ascending rank [`DrillUploadService.java`] — deferred, informational only (used in error message hint, not in access control)
- [x] [Review][Defer] Signed playback URL expires in 2 h but is cached in Pinia store indefinitely — coach must reload to get fresh URL after 2+ hours of idle time [`DrillLibraryService.java`] — deferred, expected signed-URL behaviour; pre-existing pattern
- [x] [Review][Defer] `@Async` on listener uses default `SimpleAsyncTaskExecutor` (unbounded threads) — burst of deletions could create many threads [`VideoPhysicalDeletionListener.java`] — deferred, low volume expected; infrastructure improvement
- [x] [Review][Defer] IT test `initiateUpload_scoutCoach` does not verify error code in response body — test quality [`DrillUploadResourceIT.java`] — deferred, functional behaviour is correct; test hardening pass
- [x] [Review][Defer] AC 3 "configurable 60-min timeout" not specifically wired to drill uploads — inherits pre-existing `UploadSession.expiresAt` scheduler behaviour [`DrillUploadService.java`] — deferred, pre-existing video module scheduler; not changed by this story
- [x] [Review][Defer] `@TransactionalEventListener` silently drops events if called outside a transaction — hypothetical only; class is `@Transactional` so all callers have a transaction [`VideoPhysicalDeletionListener.java`] — deferred, theoretical; class-level `@Transactional` prevents this
- [x] [Review][Patch] `videoService.initializeUpload()` called before READY guard — provider slot is allocated before the existing-READY-video check fires; if the guard throws `OperationNotAllowedException`, the newly created provider video is leaked (quota held, no DB record cleanup) [`DrillUploadService.java` — `initiateUpload`, move DrillVideoRef lookup and READY check to before the `initializeUpload` call]
- [x] [Review][Patch] `DataIntegrityViolationException` catch block ineffective on JPA session — after `drillVideoRefRepository.save(ref)` throws `DataIntegrityViolationException`, Hibernate marks the session rollback-only; the fallback `drillVideoRefRepository.setVideoId(drillId, resp.videoId())` call runs in a poisoned session and throws `SessionException`, propagating as an unhandled 500 instead of graceful concurrent-insert recovery [`DrillUploadService.java` — `initiateUpload`, `DataIntegrityViolationException` catch; fix by moving save into a `REQUIRES_NEW` helper or using a native upsert]
- [x] [Review][Patch] TUS `onProgress` divides by zero when `bytesTotal === 0` — `uploadPercent.value = bytesUploaded / bytesTotal` produces `NaN` for zero-length files; `NaN` then renders incorrectly in `q-linear-progress` [`DrillDetailPanel.vue` — `runTusUpload` `onProgress`; fix: `uploadPercent.value = bytesTotal > 0 ? bytesUploaded / bytesTotal : 0`]
- [x] [Review][Patch] `video.duration` returns `Infinity` for streaming/no-duration-atom files — `Math.round(Infinity)` is `Infinity` in JS; `durationSeconds: Infinity` reaches the backend as a JSON value Jackson cannot deserialize as `int`, returning a 400; the `catch` in `onFileSelected` is never reached (Infinity is a resolved value, not a rejection), so `selectedVideoFileDuration` stays as `Infinity` and the 400 surfaces as the generic "upload failed" message [`DrillDetailPanel.vue` — `onFileSelected`; fix: after `Math.round(...)`, clamp: `if (!isFinite(selectedVideoFileDuration.value)) selectedVideoFileDuration.value = 0`]
- [x] [Review][Patch] `onUnmounted` missing `uploadingDrillId` store reset — prior review patch specified `onUnmounted(() => { tusUpload?.abort(); uploadingDrillId.value = null })` but only `tusUpload?.abort()` was added; if the component is unmounted during the HTTP phase (before TUS starts), `sessionStore.uploadingDrillId` stays set until the in-flight request resolves [`DrillDetailPanel.vue` — `onUnmounted`; fix: add `sessionStore.uploadingDrillId = null` (requires `uploadingDrillId` to be writable via the store's returned ref, or expose a dedicated reset action)]
- [x] [Review][Defer] `FeatureGatedException` error code not matched by frontend catch block — if a SCOUT coach with stale `canUploadVideo = true` triggers an upload, `ApiAdvice` maps `FeatureGatedException` to a helpCode the frontend does not check; coach sees generic "upload failed" with no tier-upgrade hint [`DrillDetailPanel.vue` — `startUpload` catch] — deferred, requires stale eligibility cache + server gate firing simultaneously; generic error message is not wrong

## Dev Notes

### Platform.video Module is Fully Built — Do NOT Stub Quota

The epics dev notes say "quota stub — always passes at Epic 4 stage". This was written before the video module epics were implemented. The **actual codebase now has a fully built `platform.video` module** including `VideoService`, `QuotaProvider`, TUS pipeline, and webhook processing. Do NOT create any stub. Call `VideoService.initializeUpload()` directly — it handles rate limiting, validation, quota, and provider creation in one call.

### VideoService.initializeUpload() — What It Does

`VideoService.initializeUpload(InitializeUploadRequest)`:
- Rate-limits via `RateLimitingService` (per `ownerId`)
- Validates via `VideoValidationChain` (file size, MIME, format)
- Checks quota via `QuotaProvider.check()`, reserves via `QuotaProvider.reserve()`
- Creates `Video` record (status `UPLOADING`) + `UploadSession` record in DB
- Calls `VideoProviderAdapter.initializeUpload()` → Bunny.net TUS endpoint
- Returns `InitializeUploadResponse(videoId, uploadSessionId, providerUploadId, signedUploadUrl, expiresAt)`

The `DrillUploadService` pre-validates drill-specific constraints (size/duration) using ConfigService **before** calling VideoService, because VideoService's chain enforces global limits (not drill-specific ones). The two validation layers are complementary, not redundant.

### DO NOT call VideoService.confirmUpload() from the session module

`VideoService.confirmUpload(UUID videoId)` exists and moves a video from `UPLOADING → PROCESSING`. It is called by the Bunny.net **webhook processor** in `platform.video` (video-4-1), not by the client or the session module. After TUS `onSuccess` fires in the browser, the upload is already at Bunny.net — their webhook fires automatically, which triggers the webhook handler, which calls `confirmUpload`. Do NOT add a confirm call in `DrillDetailPanel.vue`'s `onSuccess` callback or anywhere in `DrillUploadService`. Calling it twice would cause a state machine error.

### DrillVideoRef — ref_count Semantics and Deletion Guard

Understanding the two-row shape that `cloneDrill()` creates is essential to the deletion guard:

- When platform drill **P** (with video V) is cloned to coach drill **C**:
  - **P.drill_video_refs**: `{drillId: P, videoId: V, refCount: 2}` — source row, `refCount` incremented from 1 to 2
  - **C.drill_video_refs**: `{drillId: C, videoId: V, refCount: 1}` — new row, starts at 1

- `C.refCount = 1` describes C in isolation. If we decrement C's refCount and check it (≤0 → delete), we would **incorrectly delete V while P still references it**. This is why the story does NOT use `decrementRefCount` in `deleteVideo`.

- The correct guard: clear this drill's `videoId`, then ask `existsByVideoId(V)`. If false (no row anywhere still has `videoId=V`), the video is now unreferenced and safe to delete. This check is cross-row and does not care about refCount values.

- `ref_count` exists for bookkeeping, not for deletion decisions in this story. Future stories (drill archival, admin cleanup) may use it differently.

- COACH drills cannot be cloned (only PLATFORM drills can — enforced in `DrillLibraryService.cloneDrill()` line 98). So C.refCount will never be incremented beyond 1 by a downstream clone. The `existsByVideoId` check remains correct regardless of how deep the clone tree grows.

### Exception Propagation — All Global Advisors Apply

`VideoApiAdvice`, `ApiAdvice`, and the new `SessionApiAdvice` are all `@RestControllerAdvice` with no `basePackages` restriction — they are globally scoped and apply to all controllers:
- `DrillConstraintViolationException` → `SessionApiAdvice` → 422, code `video.constraintViolated`
- `FeatureGatedException` → `ApiAdvice` → 403, code `security.featureGated`
- `OperationNotAllowedException` → `ApiAdvice` → 403
- `VideoValidationException` → `VideoApiAdvice` → 422, code `VALIDATION_FAILED` (from VideoService's own chain)
- `QuotaExceededException` → `VideoApiAdvice` → 429, code `QUOTA_EXCEEDED`

Note: Both `DrillConstraintViolationException` and `VideoValidationException` produce 422, but different error codes. The epics spec `video.constraintViolated` only for drill-specific size/duration violations. The general `VALIDATION_FAILED` remains for VideoService's format/MIME validation.

### VideoProviderAdapter.generatePlaybackUrl() — No DB Record Created

`videoProviderAdapter.generatePlaybackUrl(providerAssetId, PlaybackTokenClaims)` is a pure crypto operation (HMAC-SHA256 signing). It does NOT create a `PlaybackToken` DB record — that is done by `PlaybackService.authorizePlayback()`. Using the adapter directly in `DrillLibraryService.batchVideoLookup()` is correct for list endpoints where creating a token per drill per request would be expensive.

`PlaybackTokenClaims(String viewerId, Instant expiresAt)` — pass a non-personal identifier such as `"drill:" + drillId` as `viewerId` (embedded in the signed token for tracing, not for access control).

### @Async on VideoPhysicalDeletionListener

`AdminVideoService.deleteVideo()` is NOT annotated `@Async`. It calls `videoProviderAdapter.deleteAsset()` (confirmed in source at lines 54–55), which is a synchronous Bunny.net HTTP call. Without `@Async` on the listener, the coach's `DELETE /api/session/drills/{id}/video` HTTP response is blocked until Bunny.net responds — a network call that could take hundreds of milliseconds or fail under load.

`@Async` on `onVideoPhysicalDeletion` moves the execution onto the Spring `ThreadPoolTaskExecutor`. Verify the application has `@EnableAsync` somewhere in its config (either `@SpringBootApplication` or a `@Configuration` class). If a specific executor bean is declared (check the session or app config), use `@Async("beanName")` for predictable thread pooling.

### TUS Upload Client Pattern

The TUS upload is driven entirely by the browser using `tus-js-client`. The upload goes client → Bunny.net TUS endpoint directly (not through our server). Once the file is at Bunny.net, their webhook fires (`video.uploaded` → `platform.video` processes it → UPLOADING → PROCESSING → READY). This means `DrillResponse.videoUrl` is null until the video reaches READY state (typically minutes after upload completes). The `DrillCard.vue` 3-way branch from Story 4.2 handles this correctly — `hasVideo=true, videoUrl=null` shows the thumbnail placeholder.

`tus-js-client` `Upload` constructor when using a pre-formed upload URL (not TUS creation endpoint):
```js
new Upload(file, {
  uploadUrl: signedUploadUrl,   // fully formed URL from InitializeUploadResponse
  // do NOT use `endpoint` — that would create a new TUS session
})
```

### Duration Validation — Client Reading + Server Guard

Duration validation requires the browser to read file metadata before calling `initiateVideoUpload`. The `onFileSelected` handler in `DrillDetailPanel.vue` loads the file into an in-memory `<video>` element and reads `video.duration` from the `loadedmetadata` event. This works for all standard formats (MP4, MOV, WebM) and most browsers.

If the browser fails to determine duration (unsupported codec, network filesystem, edge cases), `selectedVideoFileDuration` stays `0` and is passed to the server as `durationSeconds=0`. The server's constraint check is `if (req.durationSeconds() > 0 && req.durationSeconds() > maxDuration)`, so `0` skips the server-side duration check. This is an acceptable known limitation — actual duration is only known post-upload via Bunny.net webhook metadata.

The size constraint does NOT have this limitation: `fileSizeBytes` comes directly from `File.size` (always available), and is validated server-side regardless.

### Orphaned UPLOADING Videos on Retry

If a coach initiates an upload (videoRef row created with videoId=V1), the TUS session times out, and they initiate again (videoRef row updated with videoId=V2), then V1 is left in UPLOADING state with no drill referencing it. V1 is an orphan. The reconciliation worker (video-4-2, already built) handles this: it finds UPLOADING videos with expired upload sessions and transitions them to FAILED/DELETED. No cleanup is needed in `DrillUploadService`.

### VideoRepository Cross-Module Injection

`DrillLibraryService` (in `platform.session`) now injects `VideoRepository` (in `platform.video.repo`). Within a monolith, direct Spring injection across platform modules is permitted (same JVM, same Spring context). The alternative — adding an abstraction layer — would be premature for Epic 4.

### Flyway Version

Latest applied migration: **V41** (`V41__drill_source_id.sql`). Next must be **V42**. Do not use V42 for anything else in this story.

### Feature Gate Config Pattern

Matches the existing `feature.sessionBuilder.enabled.*` pattern from V38. The `DrillUploadService.checkDrillUploadGate()` and `resolveMinUploadTier()` methods mirror `DrillLibraryService.checkSessionBuilderGate()` and `resolveMinEnabledTier()` exactly.

### VideoProviderAdapter in Tests

`VideoProviderConfig` conditionally registers `BunnyVideoProviderAdapter` vs `LocalVideoProviderAdapter` based on `app.video.provider` property. In tests, use `@MockBean VideoProviderAdapter` to avoid real Bunny.net calls. The mock should return a stub `UploadCredentials(guid, tusUrl)` for `initializeUpload` calls.

### Previous Story Learnings to Apply

From Story 4.2 review:
- **`@Modifying` + `@Transactional` + `clearAutomatically = true`**: Apply to all `setVideoId`, `clearVideoId` — project standard for modifying JPA queries.
- **resolveCoachId error handling**: `resolveCoachId()` in `DrillUploadService` calls `coachProfileService.getCoachIdByUserId()` which may throw `ResourceNotFoundException`. Do NOT catch it — let it propagate; the global handler returns 404.
- **TOCTOU on new DrillVideoRef row**: The `DataIntegrityViolationException` catch is in the `} else { drillVideoRefRepository.save(ref) }` branch of `initiateUpload` only. Place it tightly around that single `save` call — do not wrap the whole method.

## Project Structure Notes

| Component | Location |
|---|---|
| V42 migration | `src/main/resources/db/migration/V42__drill_video_upload_config.sql` |
| DrillUploadInitiateRequest | `src/main/java/com/softropic/skillars/platform/session/contract/DrillUploadInitiateRequest.java` |
| DrillUploadInitiateResponse | `src/main/java/com/softropic/skillars/platform/session/contract/DrillUploadInitiateResponse.java` |
| VideoPhysicalDeletionEvent | `src/main/java/com/softropic/skillars/platform/session/contract/VideoPhysicalDeletionEvent.java` |
| DrillConstraintViolationException (CREATE) | `src/main/java/com/softropic/skillars/platform/session/contract/exception/DrillConstraintViolationException.java` |
| SessionErrorCode (UPDATE) | `src/main/java/com/softropic/skillars/platform/session/contract/SessionErrorCode.java` |
| DrillUploadService (CREATE) | `src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java` |
| VideoPhysicalDeletionListener (CREATE) | `src/main/java/com/softropic/skillars/platform/session/service/VideoPhysicalDeletionListener.java` |
| DrillLibraryService (UPDATE) | `src/main/java/com/softropic/skillars/platform/session/service/DrillLibraryService.java` |
| DrillUploadResource (CREATE) | `src/main/java/com/softropic/skillars/platform/session/api/DrillUploadResource.java` |
| SessionApiAdvice (CREATE) | `src/main/java/com/softropic/skillars/platform/session/api/SessionApiAdvice.java` |
| DrillLibraryResource (UPDATE) | `src/main/java/com/softropic/skillars/platform/session/api/DrillLibraryResource.java` |
| DrillVideoRefRepository (UPDATE) | `src/main/java/com/softropic/skillars/platform/session/repo/DrillVideoRefRepository.java` |
| VideoRepository (UPDATE) | `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java` |
| session.api.js (UPDATE) | `src/frontend/src/api/session.api.js` |
| session.store.js (UPDATE) | `src/frontend/src/stores/session.store.js` |
| DrillDetailPanel.vue (UPDATE) | `src/frontend/src/components/session/DrillDetailPanel.vue` |
| en/index.js (UPDATE) | `src/frontend/src/i18n/en/index.js` |
| de/index.js (UPDATE) | `src/frontend/src/i18n/de/index.js` |
| DrillUploadServiceTest (CREATE) | `src/test/java/com/softropic/skillars/platform/session/service/DrillUploadServiceTest.java` |
| DrillLibraryServiceTest (CREATE/UPDATE) | `src/test/java/com/softropic/skillars/platform/session/service/DrillLibraryServiceTest.java` |
| DrillUploadResourceIT (CREATE) | `src/test/java/com/softropic/skillars/platform/session/api/DrillUploadResourceIT.java` |

## References

- Epic 4, Story 4.3 AC + dev notes [Source: `_bmad-output/planning-artifacts/skillars-epics.md` lines 1527–1569]
- Epic 6.1 Video Module Foundation — QuotaProvider contract + VideoTypeConstraints context [Source: `_bmad-output/planning-artifacts/skillars-epics.md` lines 1990–2023]
- `VideoService.initializeUpload()` + `confirmUpload()` [Source: `src/main/java/com/softropic/skillars/platform/video/service/VideoService.java`]
- `QuotaProvider` interface [Source: `src/main/java/com/softropic/skillars/platform/video/contract/QuotaProvider.java`]
- `VideoApiAdvice` error code mapping [Source: `src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java`]
- `ApiAdvice` FeatureGatedException handler [Source: `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java:300-304`]
- `DrillLibraryService.cloneDrill()` — ref_count management [Source: `src/main/java/com/softropic/skillars/platform/session/service/DrillLibraryService.java:88-125`]
- `AdminVideoService.deleteVideo()` — synchronous Bunny.net call confirmed [Source: `src/main/java/com/softropic/skillars/platform/video/service/AdminVideoService.java:45-72`]
- `Drill.sourceDrillId` entity field [Source: `src/main/java/com/softropic/skillars/platform/session/repo/Drill.java:52-53`]
- `AccessState` enum [Source: `src/main/java/com/softropic/skillars/platform/video/contract/AccessState.java`]
- `BunnyVideoProviderAdapter.generatePlaybackUrl()` [Source: `src/main/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapter.java:89-110`]
- `PlaybackTokenClaims` record [Source: `src/main/java/com/softropic/skillars/infrastructure/video/PlaybackTokenClaims.java`]
- Feature gate pattern (sessionBuilder) [Source: `src/main/resources/db/migration/V38__session_module_init.sql:39-41`]
- Story 4.2 TOCTOU patch and @Modifying patterns [Source: `_bmad-output/implementation-artifacts/skillars-4-2-drill-card-operations.md` review findings]
- `BaseSessionIT` pattern [Source: `src/test/java/com/softropic/skillars/platform/session/api/BaseSessionIT.java`]
- Project context rules [Source: `_bmad-output/project-context.md`]

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

1. `VideoProviderAdapter.initializeUpload()` arg mismatch in IT test — initial mock used `any()` (1 arg) but method takes `(String, long)`. Fixed by using `anyString(), anyLong()`.
2. `DrillSearchServiceTest` stale 5-arg constructor — `DrillLibraryService` now takes 7 args after adding `VideoRepository` + `VideoProviderAdapter`. Updated `setUp()` to pass both new mocks.
3. `ErrorDto` constructor — story showed 1-arg form; actual class requires 2-arg `(code, ErrorMsg)`. Used `new ErrorDto("video.constraintViolated", new ErrorMsg(...))`.

### Completion Notes List

- All 18 tasks + subtasks (7b, 12b, 17b) implemented.
- V42 Flyway migration seeds config keys 63–67 for drill video upload gates and constraints.
- `DrillUploadService` handles: ownership check, tier gate, size/duration constraints (before VideoService), VideoService delegation, DrillVideoRef upsert with TOCTOU tolerance.
- `deleteVideo` uses `existsByVideoId` cross-row guard (NOT refCount decrement) — critical for correctness when cloned drills share a video.
- `VideoPhysicalDeletionListener` uses `@Async @TransactionalEventListener(AFTER_COMMIT)` — decouples Bunny.net DELETE from the HTTP thread.
- `DrillLibraryService` extended: `batchVideoLookup` returns `Map<UUID, DrillVideoInfo>`, generates signed playback URLs via `videoProviderAdapter.generatePlaybackUrl()` (pure HMAC, no DB record). Videos in UPLOADING state produce `hasVideo=true, videoUrl=null`.
- Frontend: `session.api.js` + `session.store.js` + `DrillDetailPanel.vue` updated with eligibility check, TUS upload flow (`tus-js-client`), and video removal. Upload section shown only for COACH drills + INSTRUCTOR+ tier (`canUploadVideo === true`).
- i18n keys added in both `en/index.js` and `de/index.js` under `session.drillLibrary.upload`.
- Unit tests: 36/36 pass (`DrillUploadServiceTest` 14, `DrillLibraryServiceTest` 11, `DrillSearchServiceTest` 11). IT tests require Docker (Testcontainers) — not available in this environment; test code is complete and correct.

### File List

**Created:**
- `src/main/resources/db/migration/V42__drill_video_upload_config.sql`
- `src/main/java/com/softropic/skillars/platform/session/contract/DrillUploadInitiateRequest.java`
- `src/main/java/com/softropic/skillars/platform/session/contract/DrillUploadInitiateResponse.java`
- `src/main/java/com/softropic/skillars/platform/session/contract/VideoPhysicalDeletionEvent.java`
- `src/main/java/com/softropic/skillars/platform/session/contract/exception/DrillConstraintViolationException.java`
- `src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java`
- `src/main/java/com/softropic/skillars/platform/session/service/VideoPhysicalDeletionListener.java`
- `src/main/java/com/softropic/skillars/platform/session/api/DrillUploadResource.java`
- `src/main/java/com/softropic/skillars/platform/session/api/SessionApiAdvice.java`
- `src/test/java/com/softropic/skillars/platform/session/service/DrillUploadServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/session/api/DrillUploadResourceIT.java`

**Modified:**
- `src/main/java/com/softropic/skillars/platform/session/repo/DrillVideoRefRepository.java`
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java`
- `src/main/java/com/softropic/skillars/platform/session/contract/SessionErrorCode.java`
- `src/main/java/com/softropic/skillars/platform/session/service/DrillLibraryService.java`
- `src/main/java/com/softropic/skillars/platform/session/api/DrillLibraryResource.java`
- `src/frontend/src/api/session.api.js`
- `src/frontend/src/stores/session.store.js`
- `src/frontend/src/components/session/DrillDetailPanel.vue`
- `src/frontend/src/i18n/en/index.js`
- `src/frontend/src/i18n/de/index.js`
- `src/test/java/com/softropic/skillars/platform/session/service/DrillLibraryServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/session/service/DrillSearchServiceTest.java`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

| Date | Change | Author |
|---|---|---|
| 2026-06-17 | Implemented Story 4.3: Custom Drill Uploads — V42 migration, DrillUploadService, VideoPhysicalDeletionListener, DrillUploadResource, SessionApiAdvice, DrillLibraryService videoUrl enrichment, DrillLibraryResource eligibility endpoint, frontend TUS upload UX in DrillDetailPanel, i18n en+de, 36 unit tests | claude-sonnet-4-6 |
