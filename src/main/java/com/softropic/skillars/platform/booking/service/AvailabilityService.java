package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.AvailabilityBlockResponse;
import com.softropic.skillars.platform.booking.contract.AvailabilityWindowResponse;
import com.softropic.skillars.platform.booking.contract.AvailableSlotResponse;
import com.softropic.skillars.platform.booking.contract.CoachAvailabilityResponse;
import com.softropic.skillars.platform.booking.contract.CreateBlockRequest;
import com.softropic.skillars.platform.booking.contract.CreateWindowRequest;
import com.softropic.skillars.platform.booking.contract.UpdateWindowRequest;
import com.softropic.skillars.platform.booking.repo.CoachAvailabilityBlock;
import com.softropic.skillars.platform.booking.repo.CoachAvailabilityBlockRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindow;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private final CoachAvailabilityWindowRepository windowRepository;
    private final CoachAvailabilityBlockRepository blockRepository;
    private final CoachProfileRepository coachProfileRepository;

    @Transactional(readOnly = true)
    public CoachAvailabilityResponse getAvailabilityCalendar(UUID coachId, LocalDate weekStart) {
        List<CoachAvailabilityWindow> windows = windowRepository.findByCoachId(coachId);

        String timezone = windows.isEmpty() ? "UTC" : windows.get(0).getCanonicalTimezone();
        if (timezone == null || timezone.isBlank()) timezone = "UTC";

        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timezone);
        } catch (DateTimeException e) {
            zoneId = ZoneId.of("UTC");
        }

        Instant weekStartInstant = weekStart.atStartOfDay(zoneId).toInstant();
        Instant weekEndInstant = weekStart.plusDays(7).atStartOfDay(zoneId).toInstant();

        List<CoachAvailabilityBlock> weekBlocks =
            blockRepository.findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore(
                coachId, weekStartInstant, weekEndInstant);

        List<AvailableSlotResponse> computedSlots = new ArrayList<>();
        for (int dayOffset = 0; dayOffset < 7; dayOffset++) {
            LocalDate date = weekStart.plusDays(dayOffset);
            DayOfWeek dow = date.getDayOfWeek();
            int isoDay = dow.getValue();

            List<CoachAvailabilityWindow> dayWindows = windows.stream()
                .filter(w -> w.getDayOfWeek() == (short) isoDay)
                .toList();

            for (CoachAvailabilityWindow window : dayWindows) {
                Instant windowStart = date.atTime(window.getStartTime()).atZone(zoneId).toInstant();
                Instant windowEnd = date.atTime(window.getEndTime()).atZone(zoneId).toInstant();

                List<CoachAvailabilityBlock> overlapping = weekBlocks.stream()
                    .filter(b -> b.getEndDatetime().isAfter(windowStart)
                        && b.getStartDatetime().isBefore(windowEnd))
                    .toList();

                computedSlots.addAll(computeAvailableSlots(windowStart, windowEnd, overlapping));
            }
        }

        List<AvailabilityWindowResponse> windowResponses = windows.stream()
            .map(w -> new AvailabilityWindowResponse(
                w.getId(), w.getDayOfWeek(), w.getStartTime(), w.getEndTime(),
                w.getCanonicalTimezone(), false))
            .toList();

        List<AvailabilityBlockResponse> blockResponses = weekBlocks.stream()
            .map(b -> new AvailabilityBlockResponse(
                b.getId(), b.getStartDatetime(), b.getEndDatetime(), b.getReason()))
            .toList();

        return new CoachAvailabilityResponse(windowResponses, blockResponses, computedSlots);
    }

    @Transactional
    public AvailabilityWindowResponse addWindow(Long userId, CreateWindowRequest req) {
        CoachProfile profile = requireProfile(userId);
        CoachAvailabilityWindow window = new CoachAvailabilityWindow();
        window.setCoachId(profile.getId());
        window.setDayOfWeek((short) req.dayOfWeek().intValue());
        window.setStartTime(req.startTime());
        window.setEndTime(req.endTime());
        window.setCanonicalTimezone(profile.getCanonicalTimezone());
        CoachAvailabilityWindow saved = windowRepository.save(window);
        return toWindowResponse(saved, false);
    }

    @Transactional
    public AvailabilityWindowResponse updateWindow(Long userId, UUID windowId, UpdateWindowRequest req) {
        CoachProfile profile = requireProfile(userId);
        CoachAvailabilityWindow window = windowRepository.findByIdAndCoachId(windowId, profile.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Availability window not found", "coach_availability_window"));

        window.setDayOfWeek((short) req.dayOfWeek().intValue());
        window.setStartTime(req.startTime());
        window.setEndTime(req.endTime());
        CoachAvailabilityWindow saved = windowRepository.save(window);

        // TODO(3.3): wire to BookingRepository once available
        boolean hasConflict = hasBookingConflict(profile.getId(), saved);
        return toWindowResponse(saved, hasConflict);
    }

    @Transactional
    public void deleteWindow(Long userId, UUID windowId) {
        CoachProfile profile = requireProfile(userId);
        CoachAvailabilityWindow window = windowRepository.findByIdAndCoachId(windowId, profile.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Availability window not found", "coach_availability_window"));
        windowRepository.delete(window);
    }

    @Transactional
    public AvailabilityBlockResponse addBlock(Long userId, CreateBlockRequest req) {
        CoachProfile profile = requireProfile(userId);
        CoachAvailabilityBlock block = new CoachAvailabilityBlock();
        block.setCoachId(profile.getId());
        block.setStartDatetime(req.startDatetime());
        block.setEndDatetime(req.endDatetime());
        block.setReason(req.reason());
        CoachAvailabilityBlock saved = blockRepository.save(block);
        return toBlockResponse(saved);
    }

    @Transactional
    public void deleteBlock(Long userId, UUID blockId) {
        CoachProfile profile = requireProfile(userId);
        CoachAvailabilityBlock block = blockRepository.findByIdAndCoachId(blockId, profile.getId())
            .orElseThrow(() -> new OperationNotAllowedException("Block not found or not owned by coach", SecurityError.MISSING_RIGHTS));
        blockRepository.delete(block);
    }

    List<AvailableSlotResponse> computeAvailableSlots(
            Instant windowStart, Instant windowEnd,
            List<CoachAvailabilityBlock> blocks) {
        List<Instant[]> segments = new ArrayList<>();
        segments.add(new Instant[]{windowStart, windowEnd});

        for (CoachAvailabilityBlock block : blocks) {
            List<Instant[]> next = new ArrayList<>();
            for (Instant[] seg : segments) {
                Instant sStart = seg[0];
                Instant sEnd = seg[1];
                Instant bStart = block.getStartDatetime();
                Instant bEnd = block.getEndDatetime();

                if (!bEnd.isAfter(sStart) || !bStart.isBefore(sEnd)) {
                    next.add(seg);
                } else {
                    if (sStart.isBefore(bStart)) {
                        next.add(new Instant[]{sStart, bStart});
                    }
                    if (bEnd.isBefore(sEnd)) {
                        next.add(new Instant[]{bEnd, sEnd});
                    }
                }
            }
            segments = next;
        }

        return segments.stream()
            .map(s -> new AvailableSlotResponse(s[0], s[1]))
            .toList();
    }

    private CoachProfile requireProfile(Long userId) {
        return coachProfileRepository.findByUserId(userId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
    }

    private AvailabilityWindowResponse toWindowResponse(CoachAvailabilityWindow w, boolean hasConflict) {
        return new AvailabilityWindowResponse(
            w.getId(), w.getDayOfWeek(), w.getStartTime(), w.getEndTime(),
            w.getCanonicalTimezone(), hasConflict);
    }

    private AvailabilityBlockResponse toBlockResponse(CoachAvailabilityBlock b) {
        return new AvailabilityBlockResponse(b.getId(), b.getStartDatetime(), b.getEndDatetime(), b.getReason());
    }

    // TODO(3.3): wire to BookingRepository once available
    private boolean hasBookingConflict(UUID coachId, CoachAvailabilityWindow window) {
        return false;
    }
}
