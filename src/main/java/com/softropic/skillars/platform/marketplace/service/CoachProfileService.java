package com.softropic.skillars.platform.marketplace.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import com.softropic.skillars.platform.marketplace.contract.CoachMediaItemDto;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileDto;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileNotFoundException;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.contract.MarketplaceException;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStatusResponse;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep1Request;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep2Request;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep3Request;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep4Request;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep5Request;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStepResponse;
import com.softropic.skillars.platform.marketplace.contract.SessionPackDto;
import com.softropic.skillars.platform.marketplace.repo.CoachAgeGroup;
import com.softropic.skillars.platform.marketplace.repo.CoachAgeGroupRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindow;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachMediaItem;
import com.softropic.skillars.platform.marketplace.repo.CoachMediaItemRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachPricing;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrikeRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachSpecialty;
import com.softropic.skillars.platform.marketplace.repo.CoachSpecialtyRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachSubscription;
import com.softropic.skillars.platform.marketplace.repo.CoachSubscriptionRepository;
import com.softropic.skillars.platform.marketplace.repo.SessionPack;
import com.softropic.skillars.platform.marketplace.repo.SessionPackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CoachProfileService {

    private final CoachProfileRepository coachProfileRepository;
    private final CoachSpecialtyRepository coachSpecialtyRepository;
    private final CoachAgeGroupRepository coachAgeGroupRepository;
    private final CoachPricingRepository coachPricingRepository;
    private final SessionPackRepository sessionPackRepository;
    private final CoachAvailabilityWindowRepository coachAvailabilityWindowRepository;
    private final CoachSubscriptionRepository coachSubscriptionRepository;
    private final ContactDetailSanitizer contactDetailSanitizer;
    private final CoachMediaItemRepository coachMediaItemRepository;
    private final CoachCapabilityService coachCapabilityService;
    private final CoachReliabilityStrikeRepository coachReliabilityStrikeRepository;

    @Transactional
    public CoachProfile getOrCreateDraft(Long userId) {
        return coachProfileRepository.findByUserId(userId).orElseGet(() -> {
            CoachProfile draft = new CoachProfile();
            draft.setUserId(userId);
            draft.setDisplayName("");
            draft.setCanonicalTimezone("UTC");
            draft.setStatus(CoachProfileStatus.DRAFT);
            return coachProfileRepository.save(draft);
        });
    }

    @Transactional
    public ProfileBuilderStepResponse saveStep1(Long userId, ProfileBuilderStep1Request req) {
        CoachProfile profile = coachProfileRepository.findByUserId(userId).orElseGet(() -> {
            CoachProfile p = new CoachProfile();
            p.setUserId(userId);
            p.setStatus(CoachProfileStatus.DRAFT);
            return p;
        });

        profile.setDisplayName(req.displayName());
        profile.setBio(req.bio() != null ? contactDetailSanitizer.sanitize(req.bio()).sanitized() : null);
        profile.setCity(req.city());
        profile.setDistrict(req.district());
        profile.setLanguages(req.languages());
        profile.setCanonicalTimezone(req.canonicalTimezone());

        CoachProfile saved = coachProfileRepository.save(profile);
        return new ProfileBuilderStepResponse(saved.getId(), 1, 2);
    }

    @Transactional
    public ProfileBuilderStepResponse saveStep2(Long userId, ProfileBuilderStep2Request req) {
        CoachProfile profile = requireProfile(userId);
        if (profile.getDisplayName() == null || profile.getDisplayName().isBlank()) {
            throw new MarketplaceException("marketplace.stepOutOfOrder", "Complete Step 1 before submitting Step 2");
        }

        coachSpecialtyRepository.deleteByCoachId(profile.getId());
        List<CoachSpecialty> specialties = req.specialties().stream().map(skill -> {
            CoachSpecialty s = new CoachSpecialty();
            s.setCoachId(profile.getId());
            s.setSkill(skill);
            return s;
        }).toList();
        coachSpecialtyRepository.saveAll(specialties);

        coachAgeGroupRepository.deleteByCoachId(profile.getId());
        List<CoachAgeGroup> ageGroups = req.ageGroups().stream().map(tier -> {
            CoachAgeGroup ag = new CoachAgeGroup();
            ag.setCoachId(profile.getId());
            ag.setAgeTier(tier);
            return ag;
        }).toList();
        coachAgeGroupRepository.saveAll(ageGroups);

        return new ProfileBuilderStepResponse(profile.getId(), 2, 3);
    }

    @Transactional
    public ProfileBuilderStepResponse saveStep3(Long userId, ProfileBuilderStep3Request req) {
        CoachProfile profile = requireProfile(userId);
        if (coachSpecialtyRepository.findByCoachId(profile.getId()).isEmpty()
                || coachAgeGroupRepository.findByCoachId(profile.getId()).isEmpty()) {
            throw new MarketplaceException("marketplace.stepOutOfOrder", "Complete Step 2 before submitting Step 3");
        }

        CoachPricing pricing = coachPricingRepository.findByCoachId(profile.getId())
            .orElseGet(() -> {
                CoachPricing p = new CoachPricing();
                p.setCoachId(profile.getId());
                return p;
            });
        pricing.setPerSessionPrice(req.perSessionPrice());
        pricing.setCurrency("EUR");
        coachPricingRepository.save(pricing);

        if (req.sessionPacks() != null) {
            sessionPackRepository.deleteByCoachId(profile.getId());
            List<SessionPack> packs = req.sessionPacks().stream().map(sp -> {
                SessionPack pack = new SessionPack();
                pack.setCoachId(profile.getId());
                pack.setSessionCount(sp.sessionCount());
                pack.setTotalPrice(sp.totalPrice());
                pack.setLabel(sp.label());
                return pack;
            }).toList();
            sessionPackRepository.saveAll(packs);
        }

        return new ProfileBuilderStepResponse(profile.getId(), 3, 4);
    }

    @Transactional
    public ProfileBuilderStepResponse saveStep4(Long userId, ProfileBuilderStep4Request req) {
        CoachProfile profile = requireProfile(userId);
        if (coachPricingRepository.findByCoachId(profile.getId()).isEmpty()) {
            throw new MarketplaceException("marketplace.stepOutOfOrder", "Complete Step 3 before submitting Step 4");
        }
        validateAvailabilityWindows(req.windows());

        coachAvailabilityWindowRepository.deleteByCoachId(profile.getId());
        List<CoachAvailabilityWindow> windows = req.windows().stream().map(w -> {
            CoachAvailabilityWindow win = new CoachAvailabilityWindow();
            win.setCoachId(profile.getId());
            win.setDayOfWeek(w.dayOfWeek());
            win.setStartTime(w.startTime());
            win.setEndTime(w.endTime());
            win.setCanonicalTimezone(w.canonicalTimezone());
            return win;
        }).toList();
        coachAvailabilityWindowRepository.saveAll(windows);

        return new ProfileBuilderStepResponse(profile.getId(), 4, 5);
    }

    @Transactional
    public ProfileBuilderStepResponse saveStep5(Long userId, ProfileBuilderStep5Request req) {
        CoachProfile profile = requireProfile(userId);
        if (coachAvailabilityWindowRepository.findByCoachId(profile.getId()).isEmpty()) {
            throw new MarketplaceException("marketplace.stepOutOfOrder", "Complete Step 4 before submitting Step 5");
        }

        if (req.photoUrl() != null) {
            String expectedPrefix = "coach_profile/" + userId + "/";
            if (!req.photoUrl().startsWith(expectedPrefix)) {
                throw new MarketplaceException("marketplace.invalidPhotoUrl", "Invalid photo key");
            }
            profile.setPhotoUrl(req.photoUrl());
            coachProfileRepository.save(profile);
        }

        return new ProfileBuilderStepResponse(profile.getId(), 5, 5);
    }

    @Transactional
    public ProfileBuilderStatusResponse publishProfile(Long userId) {
        CoachProfile profile = requireProfile(userId);

        if (profile.getStatus() != CoachProfileStatus.DRAFT) {
            throw new MarketplaceException("marketplace.alreadyPublished", "Profile is already published");
        }

        validateAllStepsComplete(profile);

        profile.setStatus(CoachProfileStatus.ACTIVE);
        coachProfileRepository.save(profile);

        CoachSubscription subscription = new CoachSubscription();
        subscription.setCoachId(profile.getId());
        subscription.setTier(CoachSubscriptionTier.SCOUT);
        coachSubscriptionRepository.save(subscription);

        return new ProfileBuilderStatusResponse(profile.getId(), CoachProfileStatus.ACTIVE, 5, true);
    }

    public ProfileBuilderStatusResponse getBuilderStatus(Long userId) {
        return coachProfileRepository.findByUserId(userId)
            .map(profile -> {
                int lastStep = resolveLastCompletedStep(profile);
                boolean complete = profile.getStatus() == CoachProfileStatus.ACTIVE;
                return new ProfileBuilderStatusResponse(profile.getId(), profile.getStatus(), lastStep, complete);
            })
            .orElse(new ProfileBuilderStatusResponse(null, null, 0, false));
    }

    private static final int STRIKE_WINDOW_DAYS = 90;

    @Transactional(readOnly = true)
    public CoachProfileDto getPublicProfile(UUID coachId) {
        CoachProfile profile = coachProfileRepository.findById(coachId)
            .filter(p -> p.getStatus() == CoachProfileStatus.ACTIVE)
            .orElseThrow(() -> new CoachProfileNotFoundException(coachId));

        List<String> specialties = coachSpecialtyRepository.findByCoachId(profile.getId())
            .stream().map(CoachSpecialty::getSkill).toList();

        List<String> ageGroups = coachAgeGroupRepository.findByCoachId(profile.getId())
            .stream().map(ag -> ag.getAgeTier().name()).toList();

        CoachPricing pricing = coachPricingRepository.findByCoachId(profile.getId()).orElse(null);

        List<SessionPackDto> sessionPacks = sessionPackRepository.findByCoachId(profile.getId())
            .stream()
            .map(sp -> new SessionPackDto(sp.getSessionCount(), sp.getTotalPrice(), "EUR", sp.getLabel()))
            .toList();

        boolean available = !coachAvailabilityWindowRepository.findByCoachId(profile.getId()).isEmpty();

        OffsetDateTime since = OffsetDateTime.now().minusDays(STRIKE_WINDOW_DAYS);
        int strikeCount = (int) coachReliabilityStrikeRepository
            .countByCoachIdAndCreatedAtAfter(profile.getId(), since);

        List<CoachMediaItemDto> mediaGallery = coachMediaItemRepository
            .findByCoachIdOrderByDisplayOrderAsc(profile.getId())
            .stream().limit(6)
            .map(m -> new CoachMediaItemDto(m.getId(), m.getFileUrl(), m.getMediaType(), m.getDisplayOrder()))
            .toList();

        List<String> capabilityBadges = coachCapabilityService.getActiveBadges(profile.getId());

        return new CoachProfileDto(
            profile.getId(),
            profile.getDisplayName(),
            profile.getPhotoUrl(),
            profile.getVerificationTier(),
            capabilityBadges,
            0.0,
            0,
            profile.getBio(),
            profile.getLanguages(),
            profile.getCity(),
            profile.getDistrict(),
            specialties,
            ageGroups,
            pricing != null ? pricing.getPerSessionPrice() : null,
            "EUR",
            sessionPacks,
            available,
            strikeCount,
            mediaGallery
        );
    }

    @Transactional(readOnly = true)
    public CoachSubscriptionTier getCoachSubscriptionTier(UUID coachId) {
        return coachSubscriptionRepository.findByCoachId(coachId)
            .map(CoachSubscription::getTier)
            .orElseThrow(() -> new ResourceNotFoundException("CoachSubscription not found", "coach_subscription"));
    }

    @Transactional(readOnly = true)
    public UUID getCoachIdByUserId(Long userId) {
        return coachProfileRepository.findByUserId(userId)
            .map(CoachProfile::getId)
            .orElseThrow(() -> new ResourceNotFoundException("CoachProfile not found", "coach_profile"));
    }

    private CoachProfile requireProfile(Long userId) {
        return coachProfileRepository.findByUserId(userId)
            .orElseThrow(() -> new MarketplaceException("marketplace.profileNotFound",
                "Coach profile not found for userId=" + userId));
    }

    private void validateAllStepsComplete(CoachProfile profile) {
        if (profile.getDisplayName() == null || profile.getDisplayName().isBlank()) {
            throw new MarketplaceException("marketplace.incompleteProfile", "Step 1 not complete: display name missing");
        }
        if (coachSpecialtyRepository.findByCoachId(profile.getId()).isEmpty()) {
            throw new MarketplaceException("marketplace.incompleteProfile", "Step 2 not complete: no specialties");
        }
        if (coachAgeGroupRepository.findByCoachId(profile.getId()).isEmpty()) {
            throw new MarketplaceException("marketplace.incompleteProfile", "Step 2 not complete: no age groups");
        }
        if (coachPricingRepository.findByCoachId(profile.getId()).isEmpty()) {
            throw new MarketplaceException("marketplace.incompleteProfile", "Step 3 not complete: pricing not set");
        }
        if (coachAvailabilityWindowRepository.findByCoachId(profile.getId()).isEmpty()) {
            throw new MarketplaceException("marketplace.incompleteProfile", "Step 4 not complete: no availability windows");
        }
    }

    private int resolveLastCompletedStep(CoachProfile profile) {
        if (profile.getDisplayName() == null || profile.getDisplayName().isBlank()) return 0;
        if (coachSpecialtyRepository.findByCoachId(profile.getId()).isEmpty()) return 1;
        if (coachAgeGroupRepository.findByCoachId(profile.getId()).isEmpty()) return 1;
        if (coachPricingRepository.findByCoachId(profile.getId()).isEmpty()) return 2;
        if (coachAvailabilityWindowRepository.findByCoachId(profile.getId()).isEmpty()) return 3;
        if (profile.getStatus() == CoachProfileStatus.ACTIVE) return 5;
        return 4;
    }

    private void validateAvailabilityWindows(List<ProfileBuilderStep4Request.AvailabilityWindowRequest> windows) {
        for (ProfileBuilderStep4Request.AvailabilityWindowRequest w : windows) {
            if (!w.endTime().isAfter(w.startTime())) {
                throw new MarketplaceException("marketplace.invalidTimeRange",
                    "End time must be after start time for day " + w.dayOfWeek());
            }
        }

        Map<Short, List<ProfileBuilderStep4Request.AvailabilityWindowRequest>> byDay =
            windows.stream().collect(Collectors.groupingBy(ProfileBuilderStep4Request.AvailabilityWindowRequest::dayOfWeek));

        for (List<ProfileBuilderStep4Request.AvailabilityWindowRequest> dayWindows : byDay.values()) {
            List<ProfileBuilderStep4Request.AvailabilityWindowRequest> sorted = dayWindows.stream()
                .sorted(Comparator.comparing(ProfileBuilderStep4Request.AvailabilityWindowRequest::startTime))
                .toList();
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i).startTime().isBefore(sorted.get(i - 1).endTime())) {
                    throw new MarketplaceException("marketplace.overlappingAvailability",
                        "Overlapping availability windows for day " + sorted.get(i).dayOfWeek());
                }
            }
        }
    }
}
