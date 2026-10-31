package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.RelatedPartyQuery;
import com.softropic.skillars.platform.payment.contract.event.RelatedPartyBookingFlaggedEvent;
import com.softropic.skillars.platform.payment.repo.RelatedPartyBookingRepository;
import com.softropic.skillars.platform.security.contract.IdentitySignalKind;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import com.softropic.skillars.platform.security.service.UserIdentitySignalService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * feat/security-s06-related-party: decides whether a booking is a "related-party" booking — its payer is linked to the
 * coach it is booked with, so a coach colluding with a second account of their own can farm reviews, completed-session
 * history and the new-coach payout release.
 *
 * <p><b>Detection.</b> Only the payer (the booking's {@code parentId}) and the coach OF THAT BOOKING are compared, so a
 * coach who is legitimately a parent of a child trained by someone else is untouched. They are linked when they share
 * a hashed identifier of any {@link IdentitySignalKind}: normalised email, normalised phone, Stripe card fingerprint
 * or browser fingerprint ({@link UserIdentitySignalService}). The payer being the coach's own user is a link too
 * (self-booking is refused at creation; this covers rows that predate the guard). A user holds exactly one role, so
 * "parent and coach in one" is not the vector — a second account is, and these signals link it.
 *
 * <p><b>Consequences</b> (all automatic; the booking itself is never blocked, nobody is alerted): the payout is held
 * {@link ConfigBounds#PAYOUT_RELATED_PARTY_HOLD_DAYS} days ({@link #holdPayoutUntil}, enforced again at release by
 * {@code CoachPayoutTransferHandler}); the payout no longer counts toward ending the new-coach hold
 * ({@code CoachPayoutRepository.countQualifyingReleasedByCoach}); and the payer's review of the coach is stored
 * hidden and left out of every aggregate (reviews module, via {@link RelatedPartyBookingFlaggedEvent}).
 *
 * <p><b>Evaluated</b> when the booking is confirmed (the card fingerprint is known by then) and again just before the
 * payout is scheduled. Flagging is idempotent ({@code booking_id} PK, insert-if-absent); a flag raised later
 * retroactively hides an already-posted review. Cheap and deterministic: three point reads and one indexed join.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelatedPartyService implements RelatedPartyQuery {

    static final String METRIC_FLAGGED = "skillars.payment.related_party.flagged";
    static final String TRIGGER_CONFIRMATION = "confirmation";
    static final String TRIGGER_PAYOUT = "payout";
    static final String KIND_SELF = "SELF";

    private final RelatedPartyBookingRepository relatedPartyBookingRepository;
    private final BookingRepository bookingRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final UserRepository userRepository;
    private final UserIdentitySignalService signalService;
    private final ConfigService configService;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    /**
     * Evaluates the booking and flags it when its payer is linked to its coach. Idempotent.
     *
     * <p>{@code noRollbackFor}: the confirmation listener swallows a failure here, and a runtime exception crossing this
     * proxy would otherwise mark the shared confirmation transaction rollback-only and undo a paid booking.
     *
     * @return whether the booking is (now or already) flagged
     */
    @Transactional(noRollbackFor = RuntimeException.class)
    public boolean evaluate(UUID bookingId, String trigger) {
        if (relatedPartyBookingRepository.existsById(bookingId)) {
            return true;
        }
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null || booking.getParentId() == null || booking.getCoachId() == null) {
            return false;
        }
        Long payerUserId = booking.getParentId();
        Long coachUserId = coachProfileRepository.findById(booking.getCoachId())
            .map(CoachProfile::getUserId).orElse(null);
        if (coachUserId == null) {
            return false;
        }
        Set<String> matched;
        if (payerUserId.equals(coachUserId)) {
            matched = Set.of(KIND_SELF);
        } else {
            // The contact details are read fresh (an email / phone can change); browser and card signals were
            // recorded when seen.
            userRepository.findOneById(payerUserId).ifPresent(signalService::recordContact);
            userRepository.findOneById(coachUserId).ifPresent(signalService::recordContact);
            matched = signalService.sharedKinds(payerUserId, coachUserId).stream()
                .map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
            if (matched.isEmpty()) {
                return false;
            }
        }
        long holdDays = configService.getBoundedLong(ConfigBounds.PAYOUT_RELATED_PARTY_HOLD_DAYS);
        Instant holdUntil = Instant.now().plus(Duration.ofDays(holdDays));
        Long playerUserId = booking.getPlayerId() == null ? null
            : playerProfileRepository.findById(booking.getPlayerId()).map(p -> p.getUserId()).orElse(null);
        int inserted = relatedPartyBookingRepository.insertIfAbsent(bookingId, booking.getCoachId(), payerUserId,
            playerUserId, String.join(",", matched), holdUntil);
        if (inserted == 1) {
            meterRegistry.counter(METRIC_FLAGGED, "trigger", trigger).increment();
            log.warn("[RELATED_PARTY_FLAGGED] booking={} coach={} trigger={} signals={} holdDays={}",
                bookingId, booking.getCoachId(), trigger, matched, holdDays);
            eventPublisher.publishEvent(new RelatedPartyBookingFlaggedEvent(
                bookingId, booking.getCoachId(), payerUserId, playerUserId));
        }
        return true;
    }

    /**
     * Re-evaluates the booking before its payout is scheduled and, when flagged, returns the instant before which the
     * payout must not be released ({@code payment.payout.relatedParty.holdDays} from now; the stored hold only ever
     * grows). Null when not related-party, or when the evaluation itself failed — a payout is never blocked by an
     * error here, which is logged for ops.
     */
    @Transactional
    public Instant holdPayoutUntil(UUID bookingId) {
        try {
            if (!evaluate(bookingId, TRIGGER_PAYOUT)) {
                return null;
            }
            long holdDays = configService.getBoundedLong(ConfigBounds.PAYOUT_RELATED_PARTY_HOLD_DAYS);
            Instant until = Instant.now().plus(Duration.ofDays(holdDays));
            relatedPartyBookingRepository.extendHold(bookingId, until);
            return until;
        } catch (RuntimeException e) {
            log.error("[RELATED_PARTY_EVAL_FAILED] booking={} trigger={} — payout scheduled without the check",
                bookingId, TRIGGER_PAYOUT, e);
            return null;
        }
    }

    /** The stored release gate of a flagged booking; empty when the booking is not flagged. Read at release time. */
    @Transactional(readOnly = true)
    public Optional<Instant> findPayoutHoldUntil(UUID bookingId) {
        return relatedPartyBookingRepository.findPayoutHoldUntil(bookingId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isRelatedPartyAuthor(UUID coachId, Long authorUserId) {
        return coachId != null && authorUserId != null
            && relatedPartyBookingRepository.existsForCoachAndUser(coachId, authorUserId);
    }
}
