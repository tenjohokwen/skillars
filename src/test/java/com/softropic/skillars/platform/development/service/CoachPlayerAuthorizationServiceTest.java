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

import java.time.Instant;
import java.util.List;
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
    @Mock private CoachAccessWindow coachAccessWindow;

    private CoachPlayerAuthorizationService service;

    private static final Long COACH_USER_ID = 100L;
    private static final Long PLAYER_ID = 200L;
    private static final UUID COACH_PROFILE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CoachPlayerAuthorizationService(
            bookingRepository, coachProfileRepository, securityUtil, playerProfileRepository, coachAccessWindow);
    }

    @Test
    void requireCoachPlayerRelationshipForRead_statusInReadSet_succeeds() {
        when(coachProfileRepository.findByUserId(COACH_USER_ID))
            .thenReturn(Optional.of(coachProfile()));
        stubReadClock(true);

        service.requireCoachPlayerRelationshipForRead(COACH_USER_ID, PLAYER_ID);

        verify(bookingRepository).findLatestEndTimeByCoachIdAndPlayerIdAndStatusIn(
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
        stubReadClock(true);

        service.requireCoachPlayerRelationshipForRead(COACH_USER_ID, PLAYER_ID);

        // What this establishes: the read check passes the 12-value READ list, which carries the
        // roster-only statuses the narrower write-path list does not.
        assertThat(CoachPlayerAuthorizationService.READ_RELATIONSHIP_STATUSES)
            .contains("PAYMENT_PENDING", "IN_PROGRESS", "PAUSED", "COMPLETED_PENDING_CONFIRMATION",
                      "NO_SHOW_PLAYER", "NO_SHOW_COACH", "DISPUTED", "REFUND_PENDING");
        verify(bookingRepository).findLatestEndTimeByCoachIdAndPlayerIdAndStatusIn(
            COACH_PROFILE_ID, PLAYER_ID, CoachPlayerAuthorizationService.READ_RELATIONSHIP_STATUSES);
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
        stubReadClock(true);

        service.requireCoachPlayerRelationshipIfCoach(PLAYER_ID);

        verify(bookingRepository).findLatestEndTimeByCoachIdAndPlayerIdAndStatusIn(
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
        stubReadClock(false);

        assertThatThrownBy(() -> service.requireCoachPlayerRelationshipIfCoach(PLAYER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    private static CoachProfile coachProfile() {
        CoachProfile coach = new CoachProfile();
        coach.setId(COACH_PROFILE_ID);
        coach.setUserId(COACH_USER_ID);
        return coach;
    }

    // ── skillars-12-10: window ──────────────────────────────────────────────────────────────────

    private static final Instant LATEST_END = Instant.parse("2026-10-01T10:00:00Z");
    private static final List<String> WRITE_STATUSES = List.of("ACCEPTED", "CONFIRMED", "COMPLETED", "UPCOMING");
    private static final String NO_RELATIONSHIP = "Coach has no booking relationship with this player";
    private static final String WINDOW_CLOSED = "Coach's access window for this player has closed";

    /**
     * true -> a READ-status booking exists and the window is open; false -> no relationship at all. The
     * isOpen stub is strict (code review 2026-10-23), so every success path also proves the window was
     * consulted: an unused stub fails the test.
     */
    private void stubReadClock(boolean related) {
        stubClock(CoachPlayerAuthorizationService.READ_RELATIONSHIP_STATUSES, related);
    }

    private void stubClock(List<String> statuses, boolean related) {
        when(bookingRepository.findLatestEndTimeByCoachIdAndPlayerIdAndStatusIn(COACH_PROFILE_ID, PLAYER_ID, statuses))
            .thenReturn(related ? LATEST_END : null);
        if (related) {
            when(coachAccessWindow.isOpen(LATEST_END)).thenReturn(true);
        }
    }

    @Test
    void requireCoachPlayerRelationshipForRead_noRelationship_throwsNoRelationship_withoutConsultingWindow() {
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coachProfile()));
        stubReadClock(false);

        assertThatThrownBy(() -> service.requireCoachPlayerRelationshipForRead(COACH_USER_ID, PLAYER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining(NO_RELATIONSHIP);
        verify(coachAccessWindow, never()).isOpen(any());
    }

    @Test
    void requireCoachPlayerRelationshipForRead_relatedButWindowClosed_throws() {
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coachProfile()));
        when(bookingRepository.findLatestEndTimeByCoachIdAndPlayerIdAndStatusIn(
                COACH_PROFILE_ID, PLAYER_ID, CoachPlayerAuthorizationService.READ_RELATIONSHIP_STATUSES))
            .thenReturn(LATEST_END);
        when(coachAccessWindow.isOpen(LATEST_END)).thenReturn(false);

        assertThatThrownBy(() -> service.requireCoachPlayerRelationshipForRead(COACH_USER_ID, PLAYER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining(WINDOW_CLOSED);
    }

    // Code review 2026-10-23 (owner decision): the write window is measured over the NARROW write list, so the
    // stub is keyed on exactly that list -- a regression back to READ_RELATIONSHIP_STATUSES misses it, the
    // repository returns null and the test fails with NO_RELATIONSHIP instead.
    @Test
    void requireCoachPlayerRelationship_write_withNarrowRelationshipButClosedWindow_throws() {
        when(bookingRepository.findLatestEndTimeByCoachIdAndPlayerIdAndStatusIn(
                COACH_PROFILE_ID, PLAYER_ID, WRITE_STATUSES))
            .thenReturn(LATEST_END);
        when(coachAccessWindow.isOpen(LATEST_END)).thenReturn(false);

        assertThatThrownBy(() -> service.requireCoachPlayerRelationship(COACH_PROFILE_ID, PLAYER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining(WINDOW_CLOSED);
    }

    @Test
    void requireCoachPlayerRelationship_write_withOpenWindow_succeeds() {
        stubClock(WRITE_STATUSES, true);

        service.requireCoachPlayerRelationship(COACH_PROFILE_ID, PLAYER_ID);

        verify(bookingRepository, never()).findLatestEndTimeByCoachIdAndPlayerIdAndStatusIn(
            COACH_PROFILE_ID, PLAYER_ID, CoachPlayerAuthorizationService.READ_RELATIONSHIP_STATUSES);
        verify(bookingRepository, never()).existsByCoachIdAndPlayerIdAndStatusIn(any(), any(), any());
    }

    // The case the decision exists for: only read-only statuses (DISPUTED/NO_SHOW_COACH/PAYMENT_PENDING...) are
    // recent, so the write-status clock is empty and the write is refused even though reads would pass.
    @Test
    void requireCoachPlayerRelationship_write_onlyReadOnlyStatusesRecent_throwsNoRelationship() {
        stubClock(WRITE_STATUSES, false);

        assertThatThrownBy(() -> service.requireCoachPlayerRelationship(COACH_PROFILE_ID, PLAYER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining(NO_RELATIONSHIP);
    }
}
