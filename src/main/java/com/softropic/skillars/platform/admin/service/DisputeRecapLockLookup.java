package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.repo.DisputeRepository;
import com.softropic.skillars.platform.booking.contract.BookingDisputeLookup;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** skillars-deferred-162 AC6.1: admin's implementation of booking's dispute port. */
@Component
@RequiredArgsConstructor
public class DisputeRecapLockLookup implements BookingDisputeLookup {

    private final DisputeRepository disputeRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean isRecapLockedByDispute(UUID bookingId) {
        return disputeRepository.existsRecapLockingDispute(bookingId);
    }
}
