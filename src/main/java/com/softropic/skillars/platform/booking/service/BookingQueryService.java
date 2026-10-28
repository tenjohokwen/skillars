package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.BookingSnapshot;
import com.softropic.skillars.platform.booking.contract.PlannableBookingSummary;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookingQueryService {

    private final BookingRepository bookingRepository;
    private final PlayerProfileRepository playerProfileRepository;

    @Transactional(readOnly = true)
    public Optional<BookingSnapshot> getBookingSnapshot(UUID bookingId) {
        return bookingRepository.findById(bookingId)
            .map(b -> new BookingSnapshot(b.getId(), b.getCoachId(), b.getPlayerId(), b.getStatus()));
    }

    /**
     * feat/coach-workflow-gaps A19: the coach's bookings in {@code statuses} starting in {@code [from, to)},
     * earliest first, with player names resolved in ONE batched lookup (no per-row findById).
     */
    @Transactional(readOnly = true)
    public List<PlannableBookingSummary> findCoachBookings(UUID coachId, List<String> statuses,
                                                           Instant from, Instant to) {
        List<Booking> bookings = bookingRepository.findByCoachIdAndStatusInAndTimeBetween(coachId, statuses, from, to);
        if (bookings.isEmpty()) return List.of();
        Set<Long> playerIds = bookings.stream().map(Booking::getPlayerId).collect(Collectors.toSet());
        Map<Long, String> names = playerProfileRepository.findAllById(playerIds).stream()
            .collect(Collectors.toMap(PlayerProfile::getId, p -> p.getName() != null ? p.getName() : ""));
        return bookings.stream()
            .map(b -> new PlannableBookingSummary(b.getId(), b.getPlayerId(), names.get(b.getPlayerId()),
                b.getRequestedStartTime(), b.getRequestedEndTime(), b.getCanonicalTimezone()))
            .toList();
    }
}
