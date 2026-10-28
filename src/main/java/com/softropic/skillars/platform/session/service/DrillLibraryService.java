package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import com.softropic.skillars.infrastructure.video.PlaybackTokenClaims;
import com.softropic.skillars.infrastructure.video.SignedPlaybackUrl;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import org.springframework.dao.DataIntegrityViolationException;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.exception.FeatureGatedException;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.session.contract.DrillMetadata;
import com.softropic.skillars.platform.session.contract.DrillResponse;
import com.softropic.skillars.platform.session.contract.DrillUpsertRequest;
import com.softropic.skillars.platform.session.contract.InvalidParamException;
import com.softropic.skillars.platform.session.contract.SessionErrorCode;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.DrillTag;
import com.softropic.skillars.platform.session.repo.DrillTagId;
import com.softropic.skillars.platform.session.repo.DrillTagRepository;
import com.softropic.skillars.platform.session.repo.DrillVideoRef;
import com.softropic.skillars.platform.session.repo.DrillVideoRefRepository;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class DrillLibraryService {

    private static final String FEATURE_GATE_FULLY_DISABLED = "feature.gate.fully_disabled";

    private final DrillRepository drillRepository;
    private final DrillVideoRefRepository drillVideoRefRepository;
    private final DrillTagRepository drillTagRepository;
    private final ConfigService configService;
    private final CoachProfileService coachProfileService;
    private final VideoRepository videoRepository;
    private final VideoProviderAdapter videoProviderAdapter;
    private final MeterRegistry meterRegistry;
    private final ContactDetailSanitizer contactDetailSanitizer;
    private final PessimisticLockRetryer lockRetryer;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<DrillResponse> listDrills(
        String library,
        String q,
        String skill,
        String difficultyTier,
        List<String> equipment,
        Boolean weakFootBias,
        Long coachUserId
    ) {
        List<Drill> drills;
        if ("PLATFORM".equals(library)) {
            drills = drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE");
        } else {
            UUID coachId = resolveCoachId(coachUserId);
            drills = drillRepository.findByOwnerCoachIdAndStatus(coachId, "ACTIVE");
        }

        drills = applyFilters(drills, q, skill, difficultyTier, equipment, weakFootBias);

        Map<UUID, DrillVideoInfo> videoInfo = batchVideoLookup(drills);
        List<UUID> drillIds = drills.stream().map(Drill::getId).toList();

        if ("PRIVATE".equals(library)) {
            UUID coachId = resolveCoachId(coachUserId);
            Map<UUID, List<String>> tagsByDrill = buildTagMap(drillIds, coachId);
            return drills.stream()
                .map(d -> {
                    DrillVideoInfo info = videoInfo.getOrDefault(d.getId(), new DrillVideoInfo(false, null));
                    return toResponse(d, info.hasVideo(), tagsByDrill.getOrDefault(d.getId(), List.of()), null, null, info.videoUrl());
                })
                .toList();
        } else {
            // Resolve coachId for clone provenance; coaches without a profile can still browse PLATFORM drills
            UUID coachId = null;
            try {
                coachId = resolveCoachId(coachUserId);
            } catch (ResourceNotFoundException ignored) {}
            Map<UUID, UUID> cloneMap = buildCloneMap(drillIds, coachId);
            return drills.stream()
                .map(d -> {
                    DrillVideoInfo info = videoInfo.getOrDefault(d.getId(), new DrillVideoInfo(false, null));
                    return toResponse(d, info.hasVideo(), List.of(),
                        cloneMap.containsKey(d.getId()), cloneMap.get(d.getId()), info.videoUrl());
                })
                .toList();
        }
    }

    public DrillResponse cloneDrill(UUID sourceDrillId, Long coachUserId) {
        UUID coachId = resolveCoachId(coachUserId);

        Drill source = drillRepository.findById(sourceDrillId)
            .orElseThrow(() -> new ResourceNotFoundException("Drill not found", "drill"));

        if ("ARCHIVED".equals(source.getStatus())) {
            throw new ResourceNotFoundException("Drill not found", "drill");
        }

        if (!"PLATFORM".equals(source.getLibraryType())) {
            throw new OperationNotAllowedException("Only PLATFORM drills may be cloned", SessionErrorCode.CLONE_NOT_ALLOWED);
        }

        Drill clone = new Drill();
        clone.setName(source.getName());
        clone.setDescription(source.getDescription());
        clone.setLibraryType("PRIVATE");
        clone.setOwnerCoachId(coachId);
        clone.setStatus("ACTIVE");
        clone.setMetadata(source.getMetadata());
        clone.setSourceDrillId(source.getId());
        Drill saved = drillRepository.save(clone);

        boolean hasVideo = false;
        Optional<DrillVideoRef> sourceRefOpt = drillVideoRefRepository.findByDrillId(sourceDrillId);
        if (sourceRefOpt.isPresent()) {
            DrillVideoRef cloneRef = new DrillVideoRef();
            cloneRef.setDrillId(saved.getId());
            cloneRef.setVideoId(sourceRefOpt.get().getVideoId());
            cloneRef.setRefCount(1);
            drillVideoRefRepository.save(cloneRef);
            drillVideoRefRepository.incrementRefCount(sourceDrillId);
            hasVideo = sourceRefOpt.get().getVideoId() != null;
        }

        return toResponse(saved, hasVideo, List.of(), null, null, null);
    }

    // ─── feat/coach-workflow-gaps D1: coach-authored PRIVATE drills ──────────────────────────────

    /**
     * Creates a PRIVATE drill from scratch, owned by the calling coach. Same ownership shape as
     * {@link #cloneDrill} (library_type PRIVATE, owner = caller, ACTIVE) without a source drill. Name
     * uniqueness among the coach's ACTIVE private drills is enforced solely by the
     * {@code idx_drills_coach_name_unique_active} index (V173): {@code saveAndFlush} surfaces a collision
     * inside this call, where {@code ApiAdvice} maps it to 409 {@code session.drillNameTaken} — a
     * read-then-insert pre-check would only add a race window, not close one.
     */
    public DrillResponse createDrill(DrillUpsertRequest req, Long coachUserId) {
        UUID coachId = resolveCoachId(coachUserId);

        Drill drill = new Drill();
        drill.setLibraryType("PRIVATE");
        drill.setOwnerCoachId(coachId);
        drill.setStatus("ACTIVE");
        applyUpsert(drill, req, null);
        Drill saved = drillRepository.saveAndFlush(drill);

        return toResponse(saved, false, List.of(), null, null, null);
    }

    /**
     * Full replace of a PRIVATE drill's editable fields. The ownership check runs against the row this
     * transaction holds {@code FOR UPDATE} (refreshed after the lock), so it cannot be invalidated by a
     * concurrent delete/edit between check and write; {@code Drill.version} is bumped by the save.
     * Session plans and templates store drill ids, not copies, so the edit is visible wherever the drill is
     * used — the same semantics a PLATFORM drill already has.
     */
    public DrillResponse updateDrill(UUID drillId, DrillUpsertRequest req, Long coachUserId) {
        UUID coachId = resolveCoachId(coachUserId);
        Drill drill = lockOwnedPrivateDrill(drillId, coachId, "DrillLibraryService.updateDrill");
        if (!"ACTIVE".equals(drill.getStatus())) {
            throw new ResourceNotFoundException("Drill not found", "drill");
        }

        applyUpsert(drill, req, drill.getMetadata());
        Drill saved = drillRepository.saveAndFlush(drill);

        DrillVideoInfo info = batchVideoLookup(List.of(saved))
            .getOrDefault(saved.getId(), new DrillVideoInfo(false, null));
        List<String> tags = buildTagMap(List.of(saved.getId()), coachId).getOrDefault(saved.getId(), List.of());
        return toResponse(saved, info.hasVideo(), tags, null, null, info.videoUrl());
    }

    /**
     * Deletes a PRIVATE drill as a soft delete ({@code status = ARCHIVED}). Session plans, templates and
     * homework assignments reference drills by id ({@link DrillUsagePolicy} lets a plan keep a since-archived
     * drill of the coach's own), so a hard delete would orphan them. The archived drill drops out of the
     * library, suggestions and homework pickers (all ACTIVE-only), and V173 frees its name and clone slot.
     *
     * <p>A drill with a linked demo video is refused ({@code DRILL_HAS_VIDEO}): the coach removes the video
     * first (DELETE .../video), which runs the existing ref-count / physical-deletion path, so archiving never
     * strands a video ref. Idempotent: deleting an archived drill is a no-op.
     */
    public void deleteDrill(UUID drillId, Long coachUserId) {
        UUID coachId = resolveCoachId(coachUserId);
        Drill drill = lockOwnedPrivateDrill(drillId, coachId, "DrillLibraryService.deleteDrill");
        if ("ARCHIVED".equals(drill.getStatus())) {
            return;
        }
        // A linked demo video must be removed first (DELETE .../video runs the ref-count and
        // physical-deletion path). Archiving with the ref still set would strand it: the archived
        // drill disappears from every UI, so its ref — and, for a clone, the shared video's
        // refcount — could never be released. Checked under the drill row lock, which the upload
        // path (DrillUploadService.initiateUpload) also takes, so an upload cannot slip in between.
        boolean hasVideo = drillVideoRefRepository.findByDrillId(drillId)
            .map(ref -> ref.getVideoId() != null)
            .orElse(false);
        if (hasVideo) {
            throw new OperationNotAllowedException("Remove the drill's video before deleting it",
                SessionErrorCode.DRILL_HAS_VIDEO);
        }
        drill.setStatus("ARCHIVED");
        drillRepository.save(drill);
    }

    /**
     * Locks the drill row (NOWAIT + bounded retry, as {@code DrillUploadService} does) and re-checks
     * ownership on the locked, refreshed state. The retried supplier is read-only, per
     * {@code PessimisticLockRetryer}'s contract.
     */
    private Drill lockOwnedPrivateDrill(UUID drillId, UUID coachId, String lockName) {
        // Ownership is checked BEFORE the lock (so a probe never row-locks another coach's or a
        // PLATFORM drill) and again on the locked, refreshed state (so the decision is made against
        // the row this transaction then mutates). Not-owned answers 404 exactly like unknown — the
        // response must never confirm that another coach's private drill exists (DrillUsagePolicy).
        Drill drill = drillRepository.findById(drillId)
            .filter(d -> isOwnedPrivate(d, coachId))
            .orElseThrow(() -> new ResourceNotFoundException("Drill not found", "drill"));
        lockRetryer.withBoundedRetry(lockName, () -> {
            drillRepository.findByIdForUpdate(drillId)
                .orElseThrow(() -> new ResourceNotFoundException("Drill not found", "drill"));
            entityManager.refresh(drill, LockModeType.PESSIMISTIC_WRITE);
            return null;
        });
        if (!isOwnedPrivate(drill, coachId)) {
            throw new ResourceNotFoundException("Drill not found", "drill");
        }
        return drill;
    }

    private static boolean isOwnedPrivate(Drill d, UUID coachId) {
        return "PRIVATE".equals(d.getLibraryType()) && coachId.equals(d.getOwnerCoachId());
    }

    /** Copies the request onto the entity, sanitizing coach free text (story 2.4) and normalising lists. */
    private void applyUpsert(Drill drill, DrillUpsertRequest req, DrillMetadata existing) {
        String name = sanitize(req.name()).trim();
        if (name.isEmpty()) {
            throw new InvalidParamException("name");
        }
        drill.setName(name);
        String description = req.description() == null ? null : sanitize(req.description()).trim();
        drill.setDescription(description == null || description.isEmpty() ? null : description);

        List<String> primary = distinctTrimmed(req.primarySkills());
        if (primary.isEmpty()) {
            throw new InvalidParamException("primarySkills");
        }
        List<String> secondary = distinctTrimmed(req.secondarySkills()).stream()
            .filter(s -> !primary.contains(s)).toList();

        Map<String, Integer> weighting = resolveWeighting(req.skillWeighting(), primary, secondary, existing);
        List<String> coachingPoints = distinctTrimmed(req.coachingPoints()).stream().map(this::sanitize).toList();
        String groupSize = req.recommendedGroupSize() == null || req.recommendedGroupSize().isBlank()
            ? null : req.recommendedGroupSize().trim();

        drill.setMetadata(new DrillMetadata(
            primary,
            secondary,
            weighting,
            req.repDensity(),
            req.intensity(),
            req.pressureLevel(),
            req.cognitiveLoad(),
            req.matchRealism(),
            req.weakFootBias(),
            req.difficultyTier(),
            distinctTrimmed(req.equipmentRequired()),
            groupSize,
            coachingPoints,
            existing != null ? existing.setupDiagram() : null
        ));
    }

    /**
     * An explicit weighting must cover exactly the drill's skills and sum to 100 (what the SLU and suggestion
     * maths assume). Absent: keep the existing one if it still covers exactly the same skills, else derive —
     * primary skills share 70 (100 when there are no secondary skills), secondary skills share the rest, any
     * integer remainder going to the first skill of its group.
     */
    static Map<String, Integer> resolveWeighting(Map<String, Integer> requested, List<String> primary,
                                                 List<String> secondary, DrillMetadata existing) {
        Set<String> skills = new LinkedHashSet<>(primary);
        skills.addAll(secondary);
        if (requested != null && !requested.isEmpty()) {
            // A null weight would be persisted and later unboxed by SluFormula (NPE) — reject it.
            if (requested.values().stream().anyMatch(Objects::isNull)) {
                throw new InvalidParamException("skillWeighting");
            }
            int sum = requested.values().stream().mapToInt(Integer::intValue).sum();
            if (!requested.keySet().equals(skills) || sum != 100) {
                throw new InvalidParamException("skillWeighting");
            }
            return new LinkedHashMap<>(requested);
        }
        if (existing != null && existing.skillWeighting() != null
                && existing.skillWeighting().keySet().equals(skills)) {
            return new LinkedHashMap<>(existing.skillWeighting());
        }
        Map<String, Integer> derived = new LinkedHashMap<>();
        int primaryShare = secondary.isEmpty() ? 100 : 70;
        spread(derived, primary, primaryShare);
        spread(derived, secondary, 100 - primaryShare);
        return derived;
    }

    private static void spread(Map<String, Integer> target, List<String> skills, int total) {
        if (skills.isEmpty()) return;
        int each = total / skills.size();
        int remainder = total - each * skills.size();
        for (int i = 0; i < skills.size(); i++) {
            target.put(skills.get(i), each + (i == 0 ? remainder : 0));
        }
    }

    private static List<String> distinctTrimmed(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(v -> !v.isEmpty())
            .distinct()
            .toList();
    }

    private String sanitize(String value) {
        return contactDetailSanitizer.sanitize(value).sanitized();
    }

    public void addTag(UUID drillId, String tag, Long coachUserId) {
        UUID coachId = resolveCoachId(coachUserId);
        Drill drill = drillRepository.findById(drillId)
            .orElseThrow(() -> new ResourceNotFoundException("Drill not found", "drill"));

        if (!"PRIVATE".equals(drill.getLibraryType()) || !coachId.equals(drill.getOwnerCoachId())) {
            throw new OperationNotAllowedException("Cannot tag this drill", SessionErrorCode.SESSION_CANNOT_TAG_UNAUTHORIZED);
        }

        DrillTagId tagId = new DrillTagId(drillId, tag, coachId);
        if (!drillTagRepository.existsById(tagId)) {
            try {
                drillTagRepository.save(new DrillTag(tagId));
            } catch (DataIntegrityViolationException ignored) {
                // concurrent duplicate insert — PK constraint fires; treat as idempotent
            }
        }
    }

    public void removeTag(UUID drillId, String tag, Long coachUserId) {
        UUID coachId = resolveCoachId(coachUserId);
        Drill drill = drillRepository.findById(drillId)
            .orElseThrow(() -> new ResourceNotFoundException("Drill not found", "drill"));

        if (!"PRIVATE".equals(drill.getLibraryType()) || !coachId.equals(drill.getOwnerCoachId())) {
            throw new OperationNotAllowedException("Cannot tag this drill", SessionErrorCode.SESSION_CANNOT_TAG_UNAUTHORIZED);
        }

        drillTagRepository.deleteByIdDrillIdAndIdTagAndIdCoachId(drillId, tag, coachId);
    }

    @Transactional(readOnly = true)
    public List<String> getSuggestedTags(Long coachUserId) {
        UUID coachId = resolveCoachId(coachUserId);
        return drillTagRepository.findDistinctTagsByCoachId(coachId);
    }

    public void checkSessionBuilderGate(Long coachUserId) {
        UUID coachId = resolveCoachId(coachUserId);
        CoachSubscriptionTier tier = coachProfileService.getCoachSubscriptionTier(coachId);
        boolean enabled = configService.getBoolean("feature.sessionBuilder.enabled." + tier.name());
        if (!enabled) {
            throw new FeatureGatedException("session_builder", resolveMinEnabledTier());
        }
    }

    private List<Drill> applyFilters(List<Drill> drills, String q, String skill, String difficultyTier,
                                     List<String> equipment, Boolean weakFootBias) {
        return drills.stream().filter(d -> {
            if (q != null && !q.isBlank()) {
                String lower = q.toLowerCase();
                boolean nameMatch = d.getName() != null && d.getName().toLowerCase().contains(lower);
                boolean descMatch = d.getDescription() != null && d.getDescription().toLowerCase().contains(lower);
                boolean cpMatch = d.getMetadata() != null
                    && d.getMetadata().coachingPoints() != null
                    && d.getMetadata().coachingPoints().stream().anyMatch(cp -> cp.toLowerCase().contains(lower));
                if (!nameMatch && !descMatch && !cpMatch) return false;
            }
            if (skill != null && !skill.isBlank() && d.getMetadata() != null) {
                boolean primary = d.getMetadata().primarySkills() != null && d.getMetadata().primarySkills().contains(skill);
                boolean secondary = d.getMetadata().secondarySkills() != null && d.getMetadata().secondarySkills().contains(skill);
                if (!primary && !secondary) return false;
            }
            if (difficultyTier != null && !difficultyTier.isBlank() && d.getMetadata() != null) {
                if (!difficultyTier.equals(d.getMetadata().difficultyTier())) return false;
            }
            if (equipment != null && !equipment.isEmpty() && d.getMetadata() != null) {
                if (d.getMetadata().equipmentRequired() == null) return false;
                boolean anyMatch = equipment.stream().anyMatch(e -> d.getMetadata().equipmentRequired().contains(e));
                if (!anyMatch) return false;
            }
            if (weakFootBias != null && d.getMetadata() != null) {
                if (d.getMetadata().weakFootBias() != weakFootBias) return false;
            }
            return true;
        }).toList();
    }

    private Map<UUID, List<String>> buildTagMap(List<UUID> drillIds, UUID coachId) {
        if (drillIds.isEmpty()) return Map.of();
        List<com.softropic.skillars.platform.session.repo.DrillTag> tags =
            drillTagRepository.findByIdDrillIdInAndIdCoachId(drillIds, coachId);
        Map<UUID, List<String>> map = new HashMap<>();
        for (com.softropic.skillars.platform.session.repo.DrillTag t : tags) {
            map.computeIfAbsent(t.getId().getDrillId(), k -> new ArrayList<>()).add(t.getId().getTag());
        }
        return map;
    }

    private Map<UUID, UUID> buildCloneMap(List<UUID> drillIds, UUID coachId) {
        if (drillIds.isEmpty() || coachId == null) return Map.of();
        List<DrillRepository.CloneProjection> rows = drillRepository.findClonesBySourceIdsAndCoach(drillIds, coachId);
        Map<UUID, UUID> map = new HashMap<>();
        for (DrillRepository.CloneProjection row : rows) {
            map.put(row.getSourceId(), row.getCloneId());
        }
        return map;
    }

    private UUID resolveCoachId(Long userId) {
        return coachProfileService.getCoachIdByUserId(userId);
    }

    private String resolveMinEnabledTier() {
        for (CoachSubscriptionTier t : CoachSubscriptionTier.values()) {
            if (configService.find("feature.sessionBuilder.enabled." + t.name())
                    .map("true"::equalsIgnoreCase).orElse(false)) {
                return t.name();
            }
        }
        log.warn("No CoachSubscriptionTier has feature.sessionBuilder.enabled.* set to true — "
                + "session_builder is unreachable for every coach regardless of subscription");
        Counter.builder(FEATURE_GATE_FULLY_DISABLED)
            .tag("feature", "sessionBuilder")
            .register(meterRegistry)
            .increment();
        return null;
    }

    private record DrillVideoInfo(boolean hasVideo, String videoUrl) {}

    private Map<UUID, DrillVideoInfo> batchVideoLookup(List<Drill> drills) {
        if (drills.isEmpty()) return Map.of();
        List<UUID> ids = drills.stream().map(Drill::getId).toList();
        List<DrillVideoRef> refs = drillVideoRefRepository.findByDrillIdIn(ids);
        if (refs.isEmpty()) return Map.of();

        List<UUID> videoIds = refs.stream().map(DrillVideoRef::getVideoId)
                                  .filter(Objects::nonNull).distinct().toList();
        Map<UUID, Video> readyVideoMap = Map.of();
        if (!videoIds.isEmpty()) {
            readyVideoMap = videoRepository.findReadyAndActiveByIds(videoIds)
                .stream().collect(Collectors.toMap(Video::getId, v -> v));
        }

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

    DrillResponse toResponse(Drill drill, boolean hasVideo, List<String> tags, Boolean isClonedByMe, UUID cloneId, String videoUrl) {
        return toResponse(drill, hasVideo, tags, isClonedByMe, cloneId, videoUrl, false);
    }

    DrillResponse toResponse(Drill drill, boolean hasVideo, List<String> tags, Boolean isClonedByMe, UUID cloneId, String videoUrl, boolean addressesNeglectedSkill) {
        return new DrillResponse(
            drill.getId(),
            drill.getName(),
            drill.getDescription(),
            drill.getLibraryType(),
            drill.getOwnerCoachId(),
            drill.getStatus(),
            drill.getMetadata(),
            hasVideo,
            videoUrl,
            drill.getTransKey(),
            drill.getCreatedAt(),
            tags,
            isClonedByMe,
            cloneId,
            addressesNeglectedSkill
        );
    }
}
