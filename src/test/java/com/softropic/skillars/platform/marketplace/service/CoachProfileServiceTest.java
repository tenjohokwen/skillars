package com.softropic.skillars.platform.marketplace.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.repo.CoachAgeGroupRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachMediaItemRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachPublicProfileFactsRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrikeRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachSpecialtyRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachSubscriptionRepository;
import com.softropic.skillars.platform.marketplace.repo.SessionPackRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-131 AC1 Fix 3 (belt-and-braces): {@link CoachProfileService#getCoachSubscriptionTier}
 * must default to {@code SCOUT} on a missing row rather than throw, matching every other
 * missing-subscription case in this codebase.
 */
@ExtendWith(MockitoExtension.class)
class CoachProfileServiceTest {

    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private CoachSpecialtyRepository coachSpecialtyRepository;
    @Mock private CoachAgeGroupRepository coachAgeGroupRepository;
    @Mock private CoachPricingRepository coachPricingRepository;
    @Mock private SessionPackRepository sessionPackRepository;
    @Mock private CoachAvailabilityWindowRepository coachAvailabilityWindowRepository;
    @Mock private CoachSubscriptionRepository coachSubscriptionRepository;
    @Mock private ContactDetailSanitizer contactDetailSanitizer;
    @Mock private CoachMediaItemRepository coachMediaItemRepository;
    @Mock private CoachPublicProfileFactsRepository coachPublicProfileFactsRepository;
    @Mock private CoachCapabilityService coachCapabilityService;
    @Mock private CoachReliabilityStrikeRepository coachReliabilityStrikeRepository;
    @Mock private EntityManager entityManager;
    @Mock private PessimisticLockRetryer lockRetryer;

    @InjectMocks
    private CoachProfileService service;

    private static final UUID COACH_ID = UUID.randomUUID();

    @Test
    void getCoachSubscriptionTier_missingRow_defaultsToScoutInsteadOfThrowing() {
        when(coachSubscriptionRepository.findByCoachId(COACH_ID)).thenReturn(Optional.empty());

        assertThat(service.getCoachSubscriptionTier(COACH_ID)).isEqualTo(CoachSubscriptionTier.SCOUT);
    }
}
