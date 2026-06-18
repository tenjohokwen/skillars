package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.BookingSnapshot;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingQueryService {

    private final BookingRepository bookingRepository;

    @Transactional(readOnly = true)
    public Optional<BookingSnapshot> getBookingSnapshot(UUID bookingId) {
        return bookingRepository.findById(bookingId)
            .map(b -> new BookingSnapshot(b.getId(), b.getCoachId(), b.getPlayerId(), b.getStatus()));
    }
}
