package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.repo.VideoQuotaReservation;
import com.softropic.skillars.platform.video.repo.VideoQuotaReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
class QuotaReservationBatchExpirer {

    private final VideoQuotaReservationRepository reservationRepository;
    static final int BATCH_SIZE = 100;

    @Transactional
    List<VideoQuotaReservation> expireBatch() {
        List<VideoQuotaReservation> expired =
            reservationRepository.findExpiredReservationsForUpdate(BATCH_SIZE);
        for (VideoQuotaReservation r : expired) {
            r.setStatus("RELEASED");
            // storage_used_bytes NOT decremented — only COMMITTED reservations increment it
        }
        if (!expired.isEmpty()) {
            reservationRepository.saveAll(expired);
        }
        return expired;
    }
}
