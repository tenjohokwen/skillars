package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BatchBookingAcceptedEvent;
import com.softropic.skillars.platform.booking.contract.BatchBookingCreatedResponse;
import com.softropic.skillars.platform.booking.contract.BatchBookingRequestedEvent;
import com.softropic.skillars.platform.booking.contract.BatchRuleViolationException;
import com.softropic.skillars.platform.booking.contract.BatchSlot;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.CreateBatchRequest;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingBatch;
import com.softropic.skillars.platform.booking.repo.BookingBatchRepository;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.SessionPackPurchasedRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingBatchService {

    private final BookingBatchRepository batchRepository;
    private final BookingRepository bookingRepository;
    private final SessionPackService sessionPackService;
    private final SessionPackPurchasedRepository sessionPackPurchasedRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ConfigService configService;
    private final BookingService bookingService;

    private static final Set<String> POST_ACCEPTANCE_STATUSES = Set.of(
        "ACCEPTED", "CONFIRMED", "UPCOMING", "IN_PROGRESS",
        "COMPLETED_PENDING_CONFIRMATION", "COMPLETED"
    );

    public int getMaxBatchSize() {
        return (int) configService.getLong("booking.batch.maxSize");
    }

    @Transactional
    public BatchBookingCreatedResponse createBatch(Long parentId, CreateBatchRequest req) {
        int maxSize = (int) configService.getLong("booking.batch.maxSize");
        if (req.slots().size() > maxSize) {
            throw new BatchRuleViolationException("booking.batchSizeExceeded");
        }

        PlayerProfile player = playerProfileRepository.findById(req.playerId())
            .orElseThrow(() -> new ResourceNotFoundException("Player not found", "player_profile"));
        if (!Objects.equals(player.getParentId(), parentId)) {
            throw new OperationNotAllowedException("Parent does not own this player", SecurityError.MISSING_RIGHTS);
        }

        CoachProfile coach = coachProfileRepository.findById(req.coachId())
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        if (coach.getStatus() != CoachProfileStatus.ACTIVE) {
            throw new OperationNotAllowedException("Coach profile is not active", SecurityError.MISSING_RIGHTS);
        }

        for (BatchSlot slot : req.slots()) {
            if (!slot.requestedStartTime().isAfter(Instant.now())) {
                throw new OperationNotAllowedException("Requested start time must be in the future", SecurityError.MISSING_RIGHTS);
            }
            if (!slot.requestedEndTime().isAfter(slot.requestedStartTime())) {
                throw new OperationNotAllowedException("Requested end time must be after start time", SecurityError.MISSING_RIGHTS);
            }
        }

        long distinctStartTimes = req.slots().stream()
            .map(BatchSlot::requestedStartTime)
            .distinct()
            .count();
        if (distinctStartTimes != req.slots().size()) {
            throw new BatchRuleViolationException("booking.duplicateSlotStartTime");
        }

        sessionPackPurchasedRepository.findActivePacksForDeduction(req.playerId(), req.coachId());
        if (!sessionPackService.hasCredits(req.playerId(), req.coachId())) {
            throw new OperationNotAllowedException("No effective session credits available for this coach", SecurityError.MISSING_RIGHTS);
        }

        BookingBatch batch = new BookingBatch();
        batch.setParentId(parentId);
        batch.setCoachId(req.coachId());
        batch.setRequestedCount(req.slots().size());
        batch.setTotalAmount(req.totalAmount() != null ? req.totalAmount() : BigDecimal.ZERO);
        batchRepository.save(batch);

        String canonicalTimezone = coach.getCanonicalTimezone();
        List<Instant> sessionDates = new ArrayList<>();

        for (BatchSlot slot : req.slots()) {
            Booking booking = new Booking();
            booking.setStatus("REQUESTED");
            booking.setParentId(parentId);
            booking.setPlayerId(req.playerId());
            booking.setCoachId(req.coachId());
            booking.setRequestedStartTime(slot.requestedStartTime());
            booking.setRequestedEndTime(slot.requestedEndTime());
            booking.setCanonicalTimezone(canonicalTimezone);
            booking.setBatchId(batch.getId());
            bookingRepository.save(booking);
            sessionDates.add(slot.requestedStartTime());
        }

        String coachEmail = resolveEmail(coach.getUserId());
        String parentName = resolveParentName(parentId);

        eventPublisher.publishEvent(new BatchBookingRequestedEvent(
            this, batch.getId(), coachEmail, parentName,
            req.slots().size(), sessionDates, canonicalTimezone
        ));

        log.info("Batch created: batchId={} parentId={} coachId={} slots={}",
            batch.getId(), parentId, req.coachId(), req.slots().size());

        return new BatchBookingCreatedResponse(batch.getId(), req.slots().size());
    }

    @Transactional
    public void acceptAll(UUID batchId, Long coachUserId) {
        BookingBatch batch = batchRepository.findById(batchId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking batch not found", "booking_batch"));

        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        if (!batch.getCoachId().equals(coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this booking batch", SecurityError.MISSING_RIGHTS);
        }

        if (!"PENDING".equals(batch.getStatus())) {
            throw new OperationNotAllowedException("Batch has already been processed", SecurityError.MISSING_RIGHTS);
        }

        List<Booking> requestedBookings = bookingRepository.findByBatchIdAndStatus(batchId, "REQUESTED");
        List<UUID> acceptedIds = new ArrayList<>();

        for (Booking b : requestedBookings) {
            try {
                bookingService.transition(b.getId(), BookingEvent.ACCEPT, new TransitionContext(ActorRole.COACH, coachUserId));
                acceptedIds.add(b.getId());
            } catch (Exception e) {
                log.warn("Failed to accept booking {} in batch {}: {}", b.getId(), batchId, e.getMessage());
            }
        }

        if (acceptedIds.isEmpty()) {
            log.warn("No bookings were accepted in batch {}", batchId);
            return;
        }

        String newBatchStatus = acceptedIds.size() == requestedBookings.size()
            ? "FULLY_ACCEPTED" : "PARTIALLY_ACCEPTED";
        batch.setStatus(newBatchStatus);
        batchRepository.save(batch);

        String parentEmail = resolveEmail(batch.getParentId());
        String parentName = resolveParentName(batch.getParentId());

        eventPublisher.publishEvent(new BatchBookingAcceptedEvent(
            this, batchId, acceptedIds, batch.getParentId(), batch.getCoachId(),
            batch.getTotalAmount(), resolveEmail(coach.getUserId()), parentEmail,
            coach.getDisplayName(), parentName, acceptedIds.size()
        ));

        log.info("Batch accepted: batchId={} acceptedCount={}", batchId, acceptedIds.size());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateBatchStatusFromBooking(UUID batchId) {
        List<Booking> allBookings = bookingRepository.findByBatchId(batchId);
        if (allBookings.isEmpty()) return;

        long requestedCount = allBookings.stream()
            .filter(b -> "REQUESTED".equals(b.getStatus()))
            .count();

        if (requestedCount > 0) {
            return;
        }

        long acceptedCount = allBookings.stream()
            .filter(b -> POST_ACCEPTANCE_STATUSES.contains(b.getStatus()))
            .count();
        long totalCount = allBookings.size();

        String newStatus;
        if (acceptedCount == totalCount) {
            newStatus = "FULLY_ACCEPTED";
        } else if (acceptedCount == 0) {
            newStatus = "DECLINED";
        } else {
            newStatus = "PARTIALLY_ACCEPTED";
        }

        batchRepository.findById(batchId).ifPresent(batch -> {
            batch.setStatus(newStatus);
            batchRepository.save(batch);
            log.info("Batch status updated: batchId={} newStatus={}", batchId, newStatus);
        });
    }

    private String resolveEmail(Long userId) {
        return userRepository.findById(userId).map(u -> u.getEmail()).orElse("");
    }

    private String resolveParentName(Long parentId) {
        return userRepository.findById(parentId)
            .map(u -> u.getFirstName() + " " + u.getLastName())
            .orElse("Unknown Parent");
    }
}
