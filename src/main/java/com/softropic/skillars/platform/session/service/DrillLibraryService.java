package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.exception.FeatureGatedException;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.session.contract.DrillResponse;
import com.softropic.skillars.platform.session.contract.SessionErrorCode;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.DrillVideoRef;
import com.softropic.skillars.platform.session.repo.DrillVideoRefRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
    private final ConfigService configService;
    private final CoachProfileService coachProfileService;

    @Transactional(readOnly = true)
    public List<DrillResponse> listPlatformDrills() {
        List<Drill> drills = drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE");
        Set<UUID> withVideo = batchVideoLookup(drills);
        return drills.stream().map(d -> toResponse(d, withVideo.contains(d.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<DrillResponse> listPrivateDrills(Long coachUserId) {
        UUID coachId = resolveCoachId(coachUserId);
        List<Drill> drills = drillRepository.findByOwnerCoachIdAndStatus(coachId, "ACTIVE");
        Set<UUID> withVideo = batchVideoLookup(drills);
        return drills.stream().map(d -> toResponse(d, withVideo.contains(d.getId()))).toList();
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

        return toResponse(saved, hasVideo);
    }

    public void checkSessionBuilderGate(Long coachUserId) {
        UUID coachId = resolveCoachId(coachUserId);
        CoachSubscriptionTier tier = coachProfileService.getCoachSubscriptionTier(coachId);
        boolean enabled = configService.getBoolean("feature.sessionBuilder.enabled." + tier.name());
        if (!enabled) {
            throw new FeatureGatedException("session_builder", resolveMinEnabledTier());
        }
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

    private Set<UUID> batchVideoLookup(List<Drill> drills) {
        if (drills.isEmpty()) {
            return Set.of();
        }
        List<UUID> ids = drills.stream().map(Drill::getId).toList();
        return drillVideoRefRepository.findByDrillIdIn(ids)
            .stream()
            .map(DrillVideoRef::getDrillId)
            .collect(Collectors.toSet());
    }

    private DrillResponse toResponse(Drill drill, boolean hasVideo) {
        return new DrillResponse(
            drill.getId(),
            drill.getName(),
            drill.getDescription(),
            drill.getLibraryType(),
            drill.getOwnerCoachId(),
            drill.getStatus(),
            drill.getMetadata(),
            hasVideo,
            drill.getTransKey(),
            drill.getCreatedAt()
        );
    }
}
