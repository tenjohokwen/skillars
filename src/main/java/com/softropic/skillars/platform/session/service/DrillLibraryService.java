package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.video.PlaybackTokenClaims;
import com.softropic.skillars.infrastructure.video.SignedPlaybackUrl;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import org.springframework.dao.DataIntegrityViolationException;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.exception.FeatureGatedException;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.session.contract.DrillResponse;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
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

    private final DrillRepository drillRepository;
    private final DrillVideoRefRepository drillVideoRefRepository;
    private final DrillTagRepository drillTagRepository;
    private final ConfigService configService;
    private final CoachProfileService coachProfileService;
    private final VideoRepository videoRepository;
    private final VideoProviderAdapter videoProviderAdapter;

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
        clone.setLibraryType("COACH");
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

    public void addTag(UUID drillId, String tag, Long coachUserId) {
        UUID coachId = resolveCoachId(coachUserId);
        Drill drill = drillRepository.findById(drillId)
            .orElseThrow(() -> new ResourceNotFoundException("Drill not found", "drill"));

        if (!"COACH".equals(drill.getLibraryType()) || !coachId.equals(drill.getOwnerCoachId())) {
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

        if (!"COACH".equals(drill.getLibraryType()) || !coachId.equals(drill.getOwnerCoachId())) {
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
        return "NONE";
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
