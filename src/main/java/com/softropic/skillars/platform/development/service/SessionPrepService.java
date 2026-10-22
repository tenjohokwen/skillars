package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.development.contract.SessionPrepResponse;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlag;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlagRepository;
import com.softropic.skillars.platform.development.repo.PlayerTimelineEvent;
import com.softropic.skillars.platform.development.repo.PlayerTimelineRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.session.repo.Session;
import com.softropic.skillars.platform.session.repo.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    private final PlayerTimelineRepository playerTimelineRepository;
    private final ContactDetailSanitizer contactDetailSanitizer;

    // Matches booking.bookings.coach_plan_note varchar(500): a suggestion longer than this could never be
    // stored in the plan note anyway (skillars-12-6 AC12).
    private static final int PLAN_NOTE_MAX_LENGTH = 500;

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

        // skillars-12-6 AC11: names, not just a count. De-duplicated and alphabetical so the card is
        // deterministic; the count is derived from the same list so the two can never disagree.
        List<String> openNeglectedSkillCodes = neglectedSkillFlagRepository
            .findByPlayerIdAndResolvedAtIsNull(playerId).stream()
            .map(NeglectedSkillFlag::getSkillCode)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();

        // One ClockProvider instant for both the recency value and the editability flag (AC2/AC8) —
        // never bare Instant.now().
        Instant now = Instant.now(ClockProvider.getClock());

        String suggestedWhatsNext = null;
        Instant suggestedWhatsNextSessionDate = null;
        PlayerTimelineEvent suggestion = playerTimelineRepository
            .findLatestWhatsNextSuggestion(playerId, coachId, bookingId).orElse(null);
        if (suggestion != null && suggestion.getMetadata() != null) {
            Object raw = suggestion.getMetadata().get(TimelineEventListener.METADATA_KEY_WHATS_NEXT);
            if (raw != null) {
                // The recap whatsNext is stored raw while the plan-note write runs the sanitizer
                // (which can lengthen text) — sanitise + truncate here so "already contains" and the
                // 500-char checks on the client compare like with like.
                String sanitized = StringUtils.truncate(
                    contactDetailSanitizer.sanitize(raw.toString()).sanitized(), PLAN_NOTE_MAX_LENGTH);
                if (sanitized != null && !sanitized.isBlank()) {
                    suggestedWhatsNext = sanitized;
                    suggestedWhatsNextSessionDate = suggestion.getOccurredAt();
                }
            }
        }

        return new SessionPrepResponse(
            playerId,
            signal.lastSessionDate(),
            lastWhatWeDid,
            lastWhatsNext,
            focusThreadSkillCodes,
            openNeglectedSkillCodes.size(),
            booking.getPlayerRequest(),
            booking.getCoachPlanNote(),
            booking.isPrepNoteEditable(now),
            SessionRecency.daysSinceOrNull(signal.lastSessionDate(), now),
            openNeglectedSkillCodes,
            suggestedWhatsNext,
            suggestedWhatsNextSessionDate);
    }
}
