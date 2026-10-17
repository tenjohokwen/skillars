package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.contract.util.AuthoritiesConstants;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-153 AC5/AC6: new unit coverage — {@link CoachPlayerAuthorizationIT} already
 * covers this class at the integration level (including a dual-role case), so this closes a
 * unit-test gap, not a greenfield one.
 */
@ExtendWith(MockitoExtension.class)
class CoachPlayerAuthorizationServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private SecurityUtil securityUtil;
    @Mock private PlayerProfileRepository playerProfileRepository;

    private CoachPlayerAuthorizationService service;

    private static final Long COACH_USER_ID = 100L;
    private static final Long PLAYER_ID = 200L;
    private static final UUID COACH_PROFILE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CoachPlayerAuthorizationService(
            bookingRepository, coachProfileRepository, securityUtil, playerProfileRepository);
    }

    @Test
    void requireCoachPlayerRelationshipForRead_statusInReadSet_succeeds() {
        when(coachProfileRepository.findByUserId(COACH_USER_ID))
            .thenReturn(Optional.of(coachProfile()));
        when(bookingRepository.existsByCoachIdAndPlayerIdAndStatusIn(
                COACH_PROFILE_ID, PLAYER_ID, CoachPlayerAuthorizationService.READ_RELATIONSHIP_STATUSES))
            .thenReturn(true);

        service.requireCoachPlayerRelationshipForRead(COACH_USER_ID, PLAYER_ID);

        verify(bookingRepository).existsByCoachIdAndPlayerIdAndStatusIn(
            COACH_PROFILE_ID, PLAYER_ID, CoachPlayerAuthorizationService.READ_RELATIONSHIP_STATUSES);
    }

    // skillars-deferred-153 AC5: this is the entire point of the widening — a status that's a
    // roster-only member (e.g. PAYMENT_PENDING) must pass the read check even though it is NOT in
    // the narrower ACTIVE_RELATIONSHIP_STATUSES the write-path check still uses.
    @Test
    void requireCoachPlayerRelationshipForRead_roster_onlyStatusNotInActiveSet_stillSucceeds() {
        when(coachProfileRepository.findByUserId(COACH_USER_ID))
            .thenReturn(Optional.of(coachProfile()));
        // skillars-deferred-153 code review (2026-10-17): the matcher is the EXACT
        // READ_RELATIONSHIP_STATUSES list, as the sibling tests above/below already use. It was
        // any(), and the inline comment claimed that "prov[ed] the widened list is what was actually
        // passed" — it did not: with any(), a regression narrowing the check back to
        // ACTIVE_RELATIONSHIP_STATUSES would have matched the stub identically and kept this green.
        // With eq() on the exact list, a narrowed call misses the stub, existsBy... returns Mockito's
        // default false, and the method throws.
        when(bookingRepository.existsByCoachIdAndPlayerIdAndStatusIn(
                eq(COACH_PROFILE_ID), eq(PLAYER_ID),
                eq(CoachPlayerAuthorizationService.READ_RELATIONSHIP_STATUSES)))
            .thenReturn(true);

        service.requireCoachPlayerRelationshipForRead(COACH_USER_ID, PLAYER_ID);

        // What this establishes: the read check passes the 12-value READ list, which carries the
        // roster-only statuses the narrower write-path list does not.
        assertThat(CoachPlayerAuthorizationService.READ_RELATIONSHIP_STATUSES)
            .contains("PAYMENT_PENDING", "IN_PROGRESS", "PAUSED", "COMPLETED_PENDING_CONFIRMATION",
                      "NO_SHOW_PLAYER", "NO_SHOW_COACH", "DISPUTED", "REFUND_PENDING");
        verify(bookingRepository).existsByCoachIdAndPlayerIdAndStatusIn(
            COACH_PROFILE_ID, PLAYER_ID, CoachPlayerAuthorizationService.READ_RELATIONSHIP_STATUSES);
    }

    @Test
    void requireCoachPlayerRelationshipForRead_noRelationship_throws() {
        when(coachProfileRepository.findByUserId(COACH_USER_ID))
            .thenReturn(Optional.of(coachProfile()));
        when(bookingRepository.existsByCoachIdAndPlayerIdAndStatusIn(
                COACH_PROFILE_ID, PLAYER_ID, CoachPlayerAuthorizationService.READ_RELATIONSHIP_STATUSES))
            .thenReturn(false);

        assertThatThrownBy(() -> service.requireCoachPlayerRelationshipForRead(COACH_USER_ID, PLAYER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    // ── requireCoachPlayerRelationshipIfCoach — AC6 consolidation ──

    @Test
    void requireCoachPlayerRelationshipIfCoach_nonCoach_skipsEntirely() {
        when(securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)).thenReturn(false);

        service.requireCoachPlayerRelationshipIfCoach(PLAYER_ID);

        verify(coachProfileRepository, never()).findByUserId(any());
    }

    @Test
    void requireCoachPlayerRelationshipIfCoach_coachIsPlayersParent_skipsRelationshipCheck() {
        when(securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)).thenReturn(true);
        when(securityUtil.requireCurrentUserId()).thenReturn(COACH_USER_ID);
        when(playerProfileRepository.existsByIdAndParentId(PLAYER_ID, COACH_USER_ID)).thenReturn(true);

        service.requireCoachPlayerRelationshipIfCoach(PLAYER_ID);

        verify(coachProfileRepository, never()).findByUserId(any());
    }

    // skillars-deferred-153 AC6: the new self-owner arm — PlayerOwnershipGuard's own
    // existsByIdAndUserId, missing from all five pre-consolidation copies.
    @Test
    void requireCoachPlayerRelationshipIfCoach_coachIsPlayersSelfOwner_skipsRelationshipCheck() {
        when(securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)).thenReturn(true);
        when(securityUtil.requireCurrentUserId()).thenReturn(COACH_USER_ID);
        when(playerProfileRepository.existsByIdAndParentId(PLAYER_ID, COACH_USER_ID)).thenReturn(false);
        when(playerProfileRepository.existsByIdAndUserId(PLAYER_ID, COACH_USER_ID)).thenReturn(true);

        service.requireCoachPlayerRelationshipIfCoach(PLAYER_ID);

        verify(coachProfileRepository, never()).findByUserId(any());
    }

    @Test
    void requireCoachPlayerRelationshipIfCoach_coachWithRelationship_succeeds() {
        when(securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)).thenReturn(true);
        when(securityUtil.requireCurrentUserId()).thenReturn(COACH_USER_ID);
        when(playerProfileRepository.existsByIdAndParentId(PLAYER_ID, COACH_USER_ID)).thenReturn(false);
        when(playerProfileRepository.existsByIdAndUserId(PLAYER_ID, COACH_USER_ID)).thenReturn(false);
        when(securityUtil.getCurrentCoachUserId()).thenReturn(COACH_USER_ID);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coachProfile()));
        when(bookingRepository.existsByCoachIdAndPlayerIdAndStatusIn(
                COACH_PROFILE_ID, PLAYER_ID, CoachPlayerAuthorizationService.READ_RELATIONSHIP_STATUSES))
            .thenReturn(true);

        service.requireCoachPlayerRelationshipIfCoach(PLAYER_ID);

        verify(bookingRepository).existsByCoachIdAndPlayerIdAndStatusIn(
            COACH_PROFILE_ID, PLAYER_ID, CoachPlayerAuthorizationService.READ_RELATIONSHIP_STATUSES);
    }

    @Test
    void requireCoachPlayerRelationshipIfCoach_coachWithNoRelationship_throws() {
        when(securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)).thenReturn(true);
        when(securityUtil.requireCurrentUserId()).thenReturn(COACH_USER_ID);
        when(playerProfileRepository.existsByIdAndParentId(PLAYER_ID, COACH_USER_ID)).thenReturn(false);
        when(playerProfileRepository.existsByIdAndUserId(PLAYER_ID, COACH_USER_ID)).thenReturn(false);
        when(securityUtil.getCurrentCoachUserId()).thenReturn(COACH_USER_ID);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coachProfile()));
        when(bookingRepository.existsByCoachIdAndPlayerIdAndStatusIn(
                COACH_PROFILE_ID, PLAYER_ID, CoachPlayerAuthorizationService.READ_RELATIONSHIP_STATUSES))
            .thenReturn(false);

        assertThatThrownBy(() -> service.requireCoachPlayerRelationshipIfCoach(PLAYER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    private static CoachProfile coachProfile() {
        CoachProfile coach = new CoachProfile();
        coach.setId(COACH_PROFILE_ID);
        coach.setUserId(COACH_USER_ID);
        return coach;
    }
}
