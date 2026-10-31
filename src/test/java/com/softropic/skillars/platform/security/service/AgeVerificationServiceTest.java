package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.api.dto.AgeVerificationStartResponse;
import com.softropic.skillars.platform.security.api.dto.AgeVerificationStatusResponse;
import com.softropic.skillars.platform.security.contract.AgeTier;
import com.softropic.skillars.platform.security.contract.AgeVerificationErrorCode;
import com.softropic.skillars.platform.security.contract.AgeVerificationGateway;
import com.softropic.skillars.platform.security.contract.AgeVerificationStatus;
import com.softropic.skillars.platform.security.contract.SkillarsRole;
import com.softropic.skillars.platform.security.contract.event.AgeVerificationConcludedEvent;
import com.softropic.skillars.platform.security.contract.exception.AgeVerificationException;
import com.softropic.skillars.platform.security.repo.AgeVerification;
import com.softropic.skillars.platform.security.repo.AgeVerificationRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S-27: state machine, gates and cost controls of {@link AgeVerificationService}. */
@ExtendWith(MockitoExtension.class)
class AgeVerificationServiceTest {

    private static final long USER_ID = 77L;
    private static final String SESSION = "vs_123";

    @Mock AgeVerificationRepository verificationRepository;
    @Mock UserRepository userRepository;
    @Mock PlayerProfileRepository playerProfileRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock AgePolicyService agePolicyService;
    @Mock ConfigService configService;
    @Mock AgeVerificationGateway gateway;
    @Mock ApplicationEventPublisher publisher;
    @Mock PessimisticLockRetryer lockRetryer;

    private AgeVerificationService service;
    private User user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new AgeVerificationService(verificationRepository, userRepository, playerProfileRepository,
            refreshTokenRepository, agePolicyService, configService, gateway, publisher, lockRetryer);
        user = new User();
        user.setId(USER_ID);
        user.setSkillarsRole(SkillarsRole.PLAYER);
        user.setDateOfBirth(LocalDate.of(1990, 1, 1));
        lenient().when(userRepository.findOneById(USER_ID)).thenReturn(Optional.of(user));
        lenient().when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        lenient().when(lockRetryer.withBoundedRetry(anyString(), any())).thenAnswer(
            inv -> ((Supplier<Object>) inv.getArgument(1)).get());
        lenient().when(configService.getBoundedInt(ConfigBounds.IDENTITY_AGE_VERIFICATION_MAX_ATTEMPTS_PER_DAY))
            .thenReturn(3);
    }

    private void enable(boolean flag, boolean configured) {
        lenient().when(configService.getBoolean(AgeVerificationService.ENABLED_KEY, false)).thenReturn(flag);
        lenient().when(gateway.isConfigured()).thenReturn(configured);
    }

    private AgeVerification row(AgeVerificationStatus status) {
        AgeVerification v = new AgeVerification();
        v.setUserId(USER_ID);
        v.setStripeSessionId(SESSION);
        v.setStatus(status);
        v.setCreatedAt(Instant.now());
        v.setUpdatedAt(Instant.now());
        return v;
    }

    // ── enablement and the booking gate ─────────────────────────────────────────────────────────────────────

    @Test
    void enabled_needsTheFlagAndAConfiguredProvider() {
        enable(true, true);
        assertThat(service.isEnabled()).isTrue();
        enable(true, false);
        assertThat(service.isEnabled()).isFalse();
        enable(false, true);
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void requireVerifiedToBook_featureOff_fallbackAllowsBooking() {
        enable(false, true);

        service.requireVerifiedToBook(USER_ID);

        verify(verificationRepository, never()).existsByUserIdAndStatus(anyLong(), any());
    }

    @Test
    void requireVerifiedToBook_featureOn_unverified_isRefusedWithTheRequiredCode() {
        enable(true, true);
        when(verificationRepository.existsByUserIdAndStatus(USER_ID, AgeVerificationStatus.VERIFIED)).thenReturn(false);

        assertThatThrownBy(() -> service.requireVerifiedToBook(USER_ID))
            .isInstanceOf(AgeVerificationException.class)
            .satisfies(e -> assertThat(((AgeVerificationException) e).getErrorCode())
                .isEqualTo(AgeVerificationErrorCode.AGE_VERIFICATION_REQUIRED));
    }

    @Test
    void requireVerifiedToBook_featureOn_verified_passes() {
        enable(true, true);
        when(verificationRepository.existsByUserIdAndStatus(USER_ID, AgeVerificationStatus.VERIFIED)).thenReturn(true);

        service.requireVerifiedToBook(USER_ID);
    }

    // ── status ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void status_mapsTheLatestRowToTheWireState() {
        enable(true, true);
        when(verificationRepository.existsByUserIdAndStatus(USER_ID, AgeVerificationStatus.VERIFIED)).thenReturn(false);
        when(verificationRepository.countByUserIdAndCreatedAtAfter(eq(USER_ID), any())).thenReturn(1L);
        when(verificationRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID))
            .thenReturn(Optional.of(row(AgeVerificationStatus.REQUIRES_INPUT)));

        AgeVerificationStatusResponse status = service.getStatus(USER_ID);

        assertThat(status.state()).isEqualTo("FAILED");
        assertThat(status.applicable()).isTrue();
        assertThat(status.enabled()).isTrue();
        assertThat(status.attemptsRemainingToday()).isEqualTo(2);
    }

    // ── start: availability, cost controls, idempotency ─────────────────────────────────────────────────────

    @Test
    void start_featureOff_isUnavailable_andNeverCallsTheProvider() {
        enable(false, true);

        assertThatThrownBy(() -> service.start(USER_ID))
            .isInstanceOf(AgeVerificationException.class)
            .satisfies(e -> assertThat(((AgeVerificationException) e).getErrorCode())
                .isEqualTo(AgeVerificationErrorCode.UNAVAILABLE));
        verify(gateway, never()).createSession(anyLong(), anyString(), anyString());
    }

    @Test
    void start_dailyCapReached_isRefusedBeforeAnyProviderCall() {
        enable(true, true);
        when(verificationRepository.countByUserIdAndCreatedAtAfter(eq(USER_ID), any())).thenReturn(3L);
        when(verificationRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID))
            .thenReturn(Optional.of(row(AgeVerificationStatus.REQUIRES_INPUT)));

        assertThatThrownBy(() -> service.start(USER_ID))
            .isInstanceOf(AgeVerificationException.class)
            .satisfies(e -> assertThat(((AgeVerificationException) e).getErrorCode())
                .isEqualTo(AgeVerificationErrorCode.ATTEMPTS_EXCEEDED));
        verify(gateway, never()).createSession(anyLong(), anyString(), anyString());
    }

    @Test
    void start_createsASessionUnderAnAttemptKeyedIdempotencyKey_andStoresOnlyTheOutcomeRow() {
        enable(true, true);
        when(verificationRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(Optional.empty());
        when(verificationRepository.countByUserIdAndCreatedAtAfter(eq(USER_ID), any())).thenReturn(0L);
        when(verificationRepository.countByUserId(USER_ID)).thenReturn(4L);
        when(gateway.createSession(USER_ID, "age-verification-77-4", AgeVerificationService.RETURN_PATH))
            .thenReturn(new AgeVerificationGateway.Session(SESSION, "https://verify.stripe.com/s/1"));

        AgeVerificationStartResponse res = service.start(USER_ID);

        assertThat(res.url()).isEqualTo("https://verify.stripe.com/s/1");
        assertThat(res.state()).isEqualTo("PENDING");
        ArgumentCaptor<AgeVerification> saved = ArgumentCaptor.forClass(AgeVerification.class);
        verify(verificationRepository).save(saved.capture());
        assertThat(saved.getValue().getStripeSessionId()).isEqualTo(SESSION);
        assertThat(saved.getValue().getStatus()).isEqualTo(AgeVerificationStatus.CREATED);
    }

    @Test
    void start_anOpenFreshSession_isResumedWithoutPayingForAnotherOne() {
        enable(true, true);
        when(verificationRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID))
            .thenReturn(Optional.of(row(AgeVerificationStatus.CREATED)));
        when(gateway.resumeUrl(SESSION)).thenReturn(Optional.of("https://verify.stripe.com/s/resume"));

        AgeVerificationStartResponse res = service.start(USER_ID);

        assertThat(res.url()).isEqualTo("https://verify.stripe.com/s/resume");
        verify(gateway, never()).createSession(anyLong(), anyString(), anyString());
        verify(verificationRepository, never()).save(any());
    }

    @Test
    void start_aSessionStripeIsStillChecking_returnsPendingWithoutAUrl() {
        enable(true, true);
        when(verificationRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID))
            .thenReturn(Optional.of(row(AgeVerificationStatus.PROCESSING)));

        AgeVerificationStartResponse res = service.start(USER_ID);

        assertThat(res.state()).isEqualTo("PENDING");
        assertThat(res.url()).isNull();
        verify(gateway, never()).createSession(anyLong(), anyString(), anyString());
    }

    @Test
    void start_alreadyVerified_createsNothing() {
        enable(true, true);
        when(verificationRepository.existsByUserIdAndStatus(USER_ID, AgeVerificationStatus.VERIFIED)).thenReturn(true);

        assertThat(service.start(USER_ID).state()).isEqualTo("VERIFIED");
        verify(gateway, never()).createSession(anyLong(), anyString(), anyString());
    }

    // ── applyOutcome ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void verifiedAdult_withADifferentDeclaredDob_correctsTheStoredDob_andRedactsTheSession() {
        AgeVerification row = row(AgeVerificationStatus.PROCESSING);
        when(verificationRepository.findByStripeSessionId(SESSION)).thenReturn(Optional.of(row));
        LocalDate verified = LocalDate.of(1985, 5, 5);
        when(agePolicyService.getAgeTier(verified)).thenReturn(AgeTier.ADULT);
        when(agePolicyService.isMinor(AgeTier.ADULT)).thenReturn(false);
        PlayerProfile profile = new PlayerProfile();
        when(playerProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));

        service.applyOutcome(SESSION, AgeVerificationStatus.VERIFIED, verified);

        assertThat(row.getStatus()).isEqualTo(AgeVerificationStatus.VERIFIED);
        assertThat(row.getVerifiedTier()).isEqualTo(AgeTier.ADULT);
        assertThat(row.getVerifiedAt()).isNotNull();
        assertThat(user.getDateOfBirth()).isEqualTo(verified);
        assertThat(profile.getDateOfBirth()).isEqualTo(verified);
        assertThat(profile.getAgeTier()).isEqualTo(AgeTier.ADULT);
        assertThat(user.isLocked()).isFalse();
        verify(publisher).publishEvent(new AgeVerificationConcludedEvent(
            SESSION, AgeVerificationConcludedEvent.Action.REDACT));
    }

    @Test
    void verifiedAdult_withTheSameDob_leavesTheStoredDobAlone() {
        AgeVerification row = row(AgeVerificationStatus.CREATED);
        when(verificationRepository.findByStripeSessionId(SESSION)).thenReturn(Optional.of(row));
        LocalDate same = user.getDateOfBirth();
        when(agePolicyService.getAgeTier(same)).thenReturn(AgeTier.ADULT);
        when(agePolicyService.isMinor(AgeTier.ADULT)).thenReturn(false);

        service.applyOutcome(SESSION, AgeVerificationStatus.VERIFIED, same);

        assertThat(row.getStatus()).isEqualTo(AgeVerificationStatus.VERIFIED);
        verify(userRepository, never()).save(any());
        verify(playerProfileRepository, never()).save(any());
    }

    @Test
    void verifiedUnderage_locksTheAccount_revokesSessions_andKeepsTheDeclaredDob() {
        AgeVerification row = row(AgeVerificationStatus.PROCESSING);
        when(verificationRepository.findByStripeSessionId(SESSION)).thenReturn(Optional.of(row));
        LocalDate verified = LocalDate.now().minusYears(15);
        when(agePolicyService.getAgeTier(verified)).thenReturn(AgeTier.AGE_13_17);
        when(agePolicyService.isMinor(AgeTier.AGE_13_17)).thenReturn(true);

        service.applyOutcome(SESSION, AgeVerificationStatus.VERIFIED, verified);

        assertThat(row.getStatus()).isEqualTo(AgeVerificationStatus.UNDERAGE);
        assertThat(row.getVerifiedTier()).isEqualTo(AgeTier.AGE_13_17);
        assertThat(user.isLocked()).isTrue();
        verify(userRepository).invalidateSessionsForUserInCurrentTransaction(eq(USER_ID), any());
        verify(refreshTokenRepository).markAllUsedByUserId(USER_ID);
        verify(playerProfileRepository, never()).save(any());
    }

    @Test
    void verifiedWithoutADateOfBirth_failsSoTheWebhookIsRetried() {
        when(verificationRepository.findByStripeSessionId(SESSION))
            .thenReturn(Optional.of(row(AgeVerificationStatus.PROCESSING)));

        assertThatThrownBy(() -> service.applyOutcome(SESSION, AgeVerificationStatus.VERIFIED, null))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aTerminalRowIsNeverChangedByAReplayedOrLateEvent() {
        AgeVerification row = row(AgeVerificationStatus.VERIFIED);
        when(verificationRepository.findByStripeSessionId(SESSION)).thenReturn(Optional.of(row));

        service.applyOutcome(SESSION, AgeVerificationStatus.REQUIRES_INPUT, null);
        service.applyOutcome(SESSION, AgeVerificationStatus.VERIFIED, LocalDate.of(2010, 1, 1));

        assertThat(row.getStatus()).isEqualTo(AgeVerificationStatus.VERIFIED);
        verify(verificationRepository, never()).save(any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void anUnknownSession_isIgnored() {
        when(verificationRepository.findByStripeSessionId("vs_other")).thenReturn(Optional.empty());

        service.applyOutcome("vs_other", AgeVerificationStatus.VERIFIED, LocalDate.of(1990, 1, 1));

        verify(verificationRepository, never()).save(any());
    }

    @Test
    void requiresInput_marksFailed_andAsksForTheSessionToBeCancelled() {
        AgeVerification row = row(AgeVerificationStatus.PROCESSING);
        when(verificationRepository.findByStripeSessionId(SESSION)).thenReturn(Optional.of(row));

        service.applyOutcome(SESSION, AgeVerificationStatus.REQUIRES_INPUT, null);

        assertThat(row.getStatus()).isEqualTo(AgeVerificationStatus.REQUIRES_INPUT);
        verify(publisher).publishEvent(new AgeVerificationConcludedEvent(
            SESSION, AgeVerificationConcludedEvent.Action.CANCEL));
    }

    @Test
    void ourOwnCancelEchoingBack_doesNotOverwriteAFailedStatus() {
        AgeVerification row = row(AgeVerificationStatus.REQUIRES_INPUT);
        when(verificationRepository.findByStripeSessionId(SESSION)).thenReturn(Optional.of(row));

        service.applyOutcome(SESSION, AgeVerificationStatus.CANCELED, null);

        assertThat(row.getStatus()).isEqualTo(AgeVerificationStatus.REQUIRES_INPUT);
    }

    @Test
    void aPlayerCancelling_marksTheOpenSessionCanceled() {
        AgeVerification row = row(AgeVerificationStatus.CREATED);
        when(verificationRepository.findByStripeSessionId(SESSION)).thenReturn(Optional.of(row));

        service.applyOutcome(SESSION, AgeVerificationStatus.CANCELED, null);

        assertThat(row.getStatus()).isEqualTo(AgeVerificationStatus.CANCELED);
    }

    // ── GDPR ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void eraseForUser_deletesTheRows_andAsksTheProviderToRedactEverySession() {
        when(verificationRepository.findAllByUserId(USER_ID)).thenReturn(List.of(row(AgeVerificationStatus.VERIFIED)));

        service.eraseForUser(USER_ID);

        verify(publisher).publishEvent(new AgeVerificationConcludedEvent(
            SESSION, AgeVerificationConcludedEvent.Action.REDACT));
        verify(verificationRepository).deleteAllByUserId(USER_ID);
    }
}
