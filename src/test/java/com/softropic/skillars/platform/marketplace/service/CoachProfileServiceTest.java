package com.softropic.skillars.platform.marketplace.service;

import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageObjectNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import com.softropic.skillars.infrastructure.security.AuthorizationException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileSelfResponse;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.contract.MarketplaceException;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep1Request;
import com.softropic.skillars.platform.marketplace.contract.ProfileBuilderStep4Request;
import com.softropic.skillars.platform.marketplace.repo.CoachAgeGroupRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindow;
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
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
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
        lenient().when(contactDetailSanitizer.sanitize(anyString()))
            .thenAnswer(inv -> new com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer.SanitizerResult(
                inv.getArgument(0), false));
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

    // ---- skillars-deferred-140 AC1.1: saveStep4 stamps the profile zone onto every window ----

    @Test
    void saveStep4_whenWindowHasPerWindowTimezone_overwritesWithProfileTimezone() {
        CoachProfile profile = draftProfile(); // canonicalTimezone = "Europe/Berlin"
        when(coachProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(new CoachPricing()));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(profile));
        when(coachAvailabilityWindowRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        ProfileBuilderStep4Request req = new ProfileBuilderStep4Request(List.of(
            new ProfileBuilderStep4Request.AvailabilityWindowRequest(
                (short) 1, LocalTime.of(9, 0), LocalTime.of(17, 0), "America/New_York")));

        service.saveStep4(USER_ID, req);

        ArgumentCaptor<List<CoachAvailabilityWindow>> captor = ArgumentCaptor.forClass(List.class);
        verify(coachAvailabilityWindowRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(CoachAvailabilityWindow::getCanonicalTimezone)
            .containsExactly("Europe/Berlin");
    }

    // ---- skillars-deferred-140 AC1.2: saveStep1 re-stamps existing windows on zone change ----

    @Test
    void saveStep1_whenTimezoneChanges_updatesExistingWindowsCanonicalTimezone() {
        CoachProfile profile = draftProfile(); // id=COACH_ID, canonicalTimezone = "Europe/Berlin"
        CoachAvailabilityWindow window1 = new CoachAvailabilityWindow();
        window1.setId(UUID.randomUUID());
        window1.setCoachId(COACH_ID);
        window1.setCanonicalTimezone("Europe/Berlin");
        CoachAvailabilityWindow window2 = new CoachAvailabilityWindow();
        window2.setId(UUID.randomUUID());
        window2.setCoachId(COACH_ID);
        window2.setCanonicalTimezone("Europe/Berlin");

        when(coachProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(profile));
        when(coachAvailabilityWindowRepository.findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(COACH_ID))
            .thenReturn(List.of(window1, window2));
        when(coachAvailabilityWindowRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(coachProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProfileBuilderStep1Request req = new ProfileBuilderStep1Request(
            "Coach Name", "Bio text", "Madrid", "Centro", List.of("English", "Spanish"), "Europe/Madrid");

        service.saveStep1(USER_ID, req);

        assertThat(window1.getCanonicalTimezone()).isEqualTo("Europe/Madrid");
        assertThat(window2.getCanonicalTimezone()).isEqualTo("Europe/Madrid");
        verify(coachAvailabilityWindowRepository).saveAll(List.of(window1, window2));
    }

    /**
     * skillars-deferred-140 code review: this previously asserted {@code findByIdForUpdate} was
     * NEVER called on the unchanged-timezone path. That gating was the defect — the "did the zone
     * change?" decision was made from an UNLOCKED read, so two concurrent saveStep1 calls could
     * leave the profile zone and the window zones divergent (the second reads the pre-relocation
     * value, submits that same value, computes no-change, takes no lock, and writes it after the
     * first has re-stamped every window; CoachProfile has no {@code @Version} to catch it). The lock
     * is now unconditional for an existing profile, so what this test pins is narrower but still the
     * point of the AC: an unchanged zone must not TOUCH the windows.
     */
    @Test
    void saveStep1_whenTimezoneUnchanged_locksButDoesNotTouchWindows() {
        CoachProfile profile = draftProfile(); // canonicalTimezone = "Europe/Berlin"
        when(coachProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(coachProfileRepository.findByIdForUpdate(profile.getId())).thenReturn(Optional.of(profile));
        when(coachProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProfileBuilderStep1Request req = new ProfileBuilderStep1Request(
            "Coach Name", "Bio text", "Berlin", "Mitte", List.of("English"), "Europe/Berlin");

        service.saveStep1(USER_ID, req);

        verify(coachAvailabilityWindowRepository, never())
            .findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(any());
        verify(coachAvailabilityWindowRepository, never()).saveAll(any());
        // The lock itself is taken regardless, so the change-detection read happens under it.
        verify(coachProfileRepository).findByIdForUpdate(profile.getId());
    }

    /**
     * skillars-deferred-140 code review: the decision to re-stamp must be made from the value read
     * AFTER {@code entityManager.refresh} under the lock, not from the earlier unlocked
     * {@code findByUserId} snapshot. Simulated here by having refresh mutate the entity the way a
     * concurrently-committed relocation would, then asserting the re-stamp fires off the refreshed
     * value: the unlocked snapshot says "Europe/Berlin -> Europe/Berlin, nothing changed", while the
     * refreshed truth is "Asia/Tokyo -> Europe/Berlin, changed".
     */
    @Test
    void saveStep1_whenConcurrentWriteChangedZoneBeforeLock_reStampsFromRefreshedValue() {
        CoachProfile profile = draftProfile(); // canonicalTimezone = "Europe/Berlin"
        when(coachProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(coachProfileRepository.findByIdForUpdate(profile.getId())).thenReturn(Optional.of(profile));
        when(coachProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> {
            profile.setCanonicalTimezone("Asia/Tokyo");
            return null;
        }).when(entityManager).refresh(eq(profile), any(LockModeType.class));

        CoachAvailabilityWindow window = new CoachAvailabilityWindow();
        window.setCoachId(profile.getId());
        window.setCanonicalTimezone("Asia/Tokyo");
        when(coachAvailabilityWindowRepository
            .findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(profile.getId()))
            .thenReturn(List.of(window));

        ProfileBuilderStep1Request req = new ProfileBuilderStep1Request(
            "Coach Name", "Bio text", "Berlin", "Mitte", List.of("English"), "Europe/Berlin");

        service.saveStep1(USER_ID, req);

        verify(coachAvailabilityWindowRepository).saveAll(any());
        assertThat(window.getCanonicalTimezone())
            .as("re-stamp must key off the post-refresh zone, not the stale unlocked read")
            .isEqualTo("Europe/Berlin");
    }

    @Test
    void saveStep1_newProfile_doesNotAttemptWindowReStampOrLock() {
        when(coachProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(coachProfileRepository.save(any())).thenAnswer(inv -> {
            CoachProfile p = inv.getArgument(0);
            p.setId(COACH_ID);
            return p;
        });

        ProfileBuilderStep1Request req = new ProfileBuilderStep1Request(
            "Coach Name", "Bio text", "Berlin", "Mitte", List.of("English"), "Europe/Berlin");

        service.saveStep1(USER_ID, req);

        verify(coachProfileRepository, never()).findByIdForUpdate(any());
        verify(coachAvailabilityWindowRepository, never()).saveAll(any());
    }

    // ---- skillars-deferred-140 AC2: city/timezone plausibility validation ----

    @Test
    void saveStep1_whenCityAndTimezoneContradict_rejectsWithValidationError() {
        ProfileBuilderStep1Request req = new ProfileBuilderStep1Request(
            "Coach Name", "Bio text", "Paris", "Centre", List.of("French"), "America/New_York");

        assertThatThrownBy(() -> service.saveStep1(USER_ID, req))
            .isInstanceOf(MarketplaceException.class)
            .satisfies(e -> assertThat(((MarketplaceException) e).getErrorCode())
                .isEqualTo("marketplace.cityTimezoneMismatch"));

        verify(coachProfileRepository, never()).findByUserId(any());
        verify(coachProfileRepository, never()).save(any());
    }

    @Test
    void saveStep1_whenCityAndTimezoneSameRegion_succeeds() {
        when(coachProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(coachProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProfileBuilderStep1Request req = new ProfileBuilderStep1Request(
            "Coach Name", "Bio text", "Paris", "Centre", List.of("French"), "Europe/London");

        assertThatCode(() -> service.saveStep1(USER_ID, req)).doesNotThrowAnyException();
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
