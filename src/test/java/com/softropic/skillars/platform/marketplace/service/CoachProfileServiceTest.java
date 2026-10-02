package com.softropic.skillars.platform.marketplace.service;

import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageObjectNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import com.softropic.skillars.infrastructure.security.AuthorizationException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileSelfResponse;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.repo.CoachAgeGroupRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachMediaItemRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachPricing;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachPublicProfileFactsRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrikeRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachSpecialtyRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachSubscriptionRepository;
import com.softropic.skillars.platform.marketplace.repo.SessionPackRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    @Mock private FileStorageService fileStorageService;

    @InjectMocks
    private CoachProfileService service;

    private static final UUID COACH_ID = UUID.randomUUID();
    private static final Long USER_ID = 42L;

    // skillars-deferred-139 review Patch 4: withBoundedRetry's Supplier is executed for real,
    // mirroring every other lockRetryer stub in this codebase (e.g. PlaybackServiceTest,
    // RescheduleServiceTest) — entityManager.refresh is a no-op mock for the same reason.
    @BeforeEach
    void stubLockRetryer() {
        lenient().when(lockRetryer.withBoundedRetry(anyString(), any()))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
    }

    @Test
    void getCoachSubscriptionTier_missingRow_defaultsToScoutInsteadOfThrowing() {
        when(coachSubscriptionRepository.findByCoachId(COACH_ID)).thenReturn(Optional.empty());

        assertThat(service.getCoachSubscriptionTier(COACH_ID)).isEqualTo(CoachSubscriptionTier.SCOUT);
    }

    // ---- AC1: getOwnProfile ----

    private CoachProfile draftProfile() {
        CoachProfile profile = new CoachProfile();
        profile.setId(COACH_ID);
        profile.setUserId(USER_ID);
        profile.setDisplayName("Coach Name");
        profile.setBio("Bio text");
        profile.setCity("Berlin");
        profile.setDistrict("Mitte");
        profile.setLanguages(List.of("English"));
        profile.setCanonicalTimezone("Europe/Berlin");
        return profile;
    }

    @Test
    void getOwnProfile_draftProfile_returnsAllCollectedFields() {
        CoachProfile profile = draftProfile();
        when(coachProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.empty());
        when(coachSpecialtyRepository.findByCoachId(COACH_ID)).thenReturn(List.of());
        when(coachAgeGroupRepository.findByCoachId(COACH_ID)).thenReturn(List.of());
        when(sessionPackRepository.findByCoachId(COACH_ID)).thenReturn(List.of());
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID))
            .thenReturn(List.of());

        CoachProfileSelfResponse response = service.getOwnProfile(USER_ID);

        assertThat(response.coachId()).isEqualTo(COACH_ID);
        assertThat(response.displayName()).isEqualTo("Coach Name");
        assertThat(response.bio()).isEqualTo("Bio text");
        assertThat(response.city()).isEqualTo("Berlin");
        assertThat(response.district()).isEqualTo("Mitte");
        assertThat(response.languages()).containsExactly("English");
        assertThat(response.canonicalTimezone()).isEqualTo("Europe/Berlin");
    }

    @Test
    void getOwnProfile_noPricingRow_perSessionPriceNull() {
        CoachProfile profile = draftProfile();
        when(coachProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.empty());
        when(coachSpecialtyRepository.findByCoachId(COACH_ID)).thenReturn(List.of());
        when(coachAgeGroupRepository.findByCoachId(COACH_ID)).thenReturn(List.of());
        when(sessionPackRepository.findByCoachId(COACH_ID)).thenReturn(List.of());
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID))
            .thenReturn(List.of());

        CoachProfileSelfResponse response = service.getOwnProfile(USER_ID);

        assertThat(response.perSessionPrice()).isNull();
        assertThat(response.sessionDurationMinutes()).isNull();
    }

    @Test
    void getOwnProfile_withPricingRow_returnsPerSessionPriceAndDuration() {
        CoachProfile profile = draftProfile();
        CoachPricing pricing = new CoachPricing();
        pricing.setPerSessionPrice(BigDecimal.valueOf(50));
        pricing.setSessionDurationMinutes(90);
        when(coachProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(pricing));
        when(coachSpecialtyRepository.findByCoachId(COACH_ID)).thenReturn(List.of());
        when(coachAgeGroupRepository.findByCoachId(COACH_ID)).thenReturn(List.of());
        when(sessionPackRepository.findByCoachId(COACH_ID)).thenReturn(List.of());
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID))
            .thenReturn(List.of());

        CoachProfileSelfResponse response = service.getOwnProfile(USER_ID);

        assertThat(response.perSessionPrice()).isEqualTo(BigDecimal.valueOf(50));
        assertThat(response.sessionDurationMinutes()).isEqualTo(90);
    }

    @Test
    void getOwnProfile_noAvailabilityWindows_emptyList() {
        CoachProfile profile = draftProfile();
        when(coachProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.empty());
        when(coachSpecialtyRepository.findByCoachId(COACH_ID)).thenReturn(List.of());
        when(coachAgeGroupRepository.findByCoachId(COACH_ID)).thenReturn(List.of());
        when(sessionPackRepository.findByCoachId(COACH_ID)).thenReturn(List.of());
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID))
            .thenReturn(List.of());

        CoachProfileSelfResponse response = service.getOwnProfile(USER_ID);

        assertThat(response.availabilityWindows()).isEmpty();
    }

    // ---- AC3: deletePhoto ----

    @Test
    void deletePhoto_withExistingPhoto_clearsFieldAndSoftDeletesStorageObject() {
        CoachProfile profile = draftProfile();
        profile.setPhotoUrl("coach_profile/" + USER_ID + "/2026/01/photo.jpg");
        when(coachProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(profile));

        service.deletePhoto(USER_ID, "coach@example.com");

        verify(fileStorageService).softDelete("coach_profile/" + USER_ID + "/2026/01/photo.jpg", "coach@example.com");
        verify(coachProfileRepository).save(profile);
        assertThat(profile.getPhotoUrl()).isNull();
    }

    @Test
    void deletePhoto_noExistingPhoto_isNoOp() {
        CoachProfile profile = draftProfile();
        profile.setPhotoUrl(null);
        when(coachProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));

        service.deletePhoto(USER_ID, "coach@example.com");

        verify(fileStorageService, never()).softDelete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(coachProfileRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // ---- skillars-deferred-139 review D2: deletePhoto tolerates a missing/foreign-owned storage row ----

    @Test
    void deletePhoto_storageObjectAlreadyGone_stillClearsPhotoUrl() {
        CoachProfile profile = draftProfile();
        profile.setPhotoUrl("coach_profile/" + USER_ID + "/2026/01/photo.jpg");
        when(coachProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(profile));
        org.mockito.Mockito.doThrow(new StorageObjectNotFoundException("coach_profile/" + USER_ID + "/2026/01/photo.jpg"))
            .when(fileStorageService).softDelete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        service.deletePhoto(USER_ID, "coach@example.com");

        verify(coachProfileRepository).save(profile);
        assertThat(profile.getPhotoUrl()).isNull();
    }

    @Test
    void deletePhoto_storageObjectForeignOwned_stillClearsPhotoUrl() {
        CoachProfile profile = draftProfile();
        profile.setPhotoUrl("coach_profile/" + USER_ID + "/2026/01/photo.jpg");
        when(coachProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(profile));
        org.mockito.Mockito.doThrow(new AuthorizationException("User not authorized to delete this file", SecurityError.MISSING_RIGHTS))
            .when(fileStorageService).softDelete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        service.deletePhoto(USER_ID, "coach@example.com");

        verify(coachProfileRepository).save(profile);
        assertThat(profile.getPhotoUrl()).isNull();
    }
}
