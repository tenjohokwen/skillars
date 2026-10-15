package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.development.contract.SessionPrepResponse;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlagRepository;
import com.softropic.skillars.platform.development.repo.PlayerTimelineEvent;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.session.repo.Session;
import com.softropic.skillars.platform.session.repo.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Backs {@code GET /api/development/bookings/{bookingId}/prep} (skillars-12-3 AC3/AC4). Authorized
 * by booking ownership — the already-resolved coachId is handed in by
 * {@code SessionPrepResource}, mirroring {@link CoachRosterResource}'s resource-resolves-UUID
 * layering rather than resolving inside this service.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SessionPrepService {

    // skillars-12-3 Task 3.2: mirrors only the status clause of
    // SluCalculationService.onBookingCompleted's status gate (SAVED/COMPLETED sessions have
    // meaningfully committed block content) — not that method's other five independent gates.
    private static final List<String> FOCUS_THREAD_SESSION_STATUSES = List.of("SAVED", "COMPLETED");

    private final BookingRepository bookingRepository;
    private final SessionRepository sessionRepository;
    private final NeglectedSkillFlagRepository neglectedSkillFlagRepository;
    private final CoachPlayerLastSessionResolver coachPlayerLastSessionResolver;

    public SessionPrepResponse getPrep(UUID bookingId, UUID coachId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found", "booking"));

        if (!Objects.equals(booking.getCoachId(), coachId)) {
            throw new OperationNotAllowedException("Coach does not own this booking", SecurityError.MISSING_RIGHTS);
        }

        Long playerId = booking.getPlayerId();

        CoachPlayerLastSessionResolver.LastCoachSessionSignal signal =
            coachPlayerLastSessionResolver.resolve(playerId, coachId);

        String lastWhatWeDid = null;
        String lastWhatsNext = null;
        PlayerTimelineEvent recapSourceEvent = signal.recapSourceEventOrNull();
        if (recapSourceEvent != null) {
            Map<String, Object> metadata = recapSourceEvent.getMetadata();
            if (metadata != null) {
                Object whatWeDid = metadata.get(TimelineEventListener.METADATA_KEY_WHAT_WE_DID);
                Object whatsNext = metadata.get(TimelineEventListener.METADATA_KEY_WHATS_NEXT);
                lastWhatWeDid = whatWeDid != null ? whatWeDid.toString() : null;
                lastWhatsNext = whatsNext != null ? whatsNext.toString() : null;
            }
        }

        List<Session> recentSessions = sessionRepository
            .findByPlayerIdAndCoachIdAndStatusInOrderByCreatedAtDesc(
                playerId, coachId, FOCUS_THREAD_SESSION_STATUSES,
                PageRequest.of(0, SessionRepository.SESSION_WINDOW));
        List<List<String>> developmentFocusHistory = recentSessions.stream()
            .map(Session::getDevelopmentFocus)
            .toList();
        List<String> focusThreadSkillCodes =
            SessionFocusThreadCalculator.rank(developmentFocusHistory, SessionFocusThreadCalculator.MAX_FOCUS_CODES);

        int openNeglectedSkillCount = neglectedSkillFlagRepository.findByPlayerIdAndResolvedAtIsNull(playerId).size();

        return new SessionPrepResponse(
            playerId,
            signal.lastSessionDate(),
            lastWhatWeDid,
            lastWhatsNext,
            focusThreadSkillCodes,
            openNeglectedSkillCount);
    }
}
