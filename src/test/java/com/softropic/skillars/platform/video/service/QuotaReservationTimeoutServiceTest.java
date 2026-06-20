package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.repo.VideoQuotaReservation;
import com.softropic.skillars.platform.video.repo.VideoQuotaReservationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuotaReservationTimeoutServiceTest {

    @Mock VideoQuotaReservationRepository reservationRepository;

    @InjectMocks QuotaReservationBatchExpirer batchExpirer;

    @Test
    void expireBatch_setsStatusToReleased() {
        VideoQuotaReservation reservation = activeReservation();
        when(reservationRepository.findExpiredReservationsForUpdate(anyInt()))
            .thenReturn(List.of(reservation));
        when(reservationRepository.saveAll(anyList())).thenReturn(List.of(reservation));

        List<VideoQuotaReservation> result = batchExpirer.expireBatch();

        assertThat(result).hasSize(1);
        assertThat(reservation.getStatus()).isEqualTo("RELEASED");
        verify(reservationRepository).saveAll(List.of(reservation));
    }

    @Test
    @SuppressWarnings("unchecked")
    void expireBatch_setsAllRowsToReleased() {
        VideoQuotaReservation r1 = activeReservation();
        VideoQuotaReservation r2 = activeReservation();
        VideoQuotaReservation r3 = activeReservation();
        when(reservationRepository.findExpiredReservationsForUpdate(anyInt()))
            .thenReturn(List.of(r1, r2, r3));
        when(reservationRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        List<VideoQuotaReservation> result = batchExpirer.expireBatch();

        assertThat(result).hasSize(3);
        assertThat(r1.getStatus()).isEqualTo("RELEASED");
        assertThat(r2.getStatus()).isEqualTo("RELEASED");
        assertThat(r3.getStatus()).isEqualTo("RELEASED");

        ArgumentCaptor<List<VideoQuotaReservation>> captor = ArgumentCaptor.forClass(List.class);
        verify(reservationRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(r1, r2, r3);
    }

    @Test
    void expireBatch_emptyList_isNoOp() {
        when(reservationRepository.findExpiredReservationsForUpdate(anyInt()))
            .thenReturn(Collections.emptyList());

        List<VideoQuotaReservation> result = batchExpirer.expireBatch();

        assertThat(result).isEmpty();
        verify(reservationRepository, never()).saveAll(anyList());
    }

    private VideoQuotaReservation activeReservation() {
        VideoQuotaReservation r = new VideoQuotaReservation();
        r.setUserId("test-owner-" + UUID.randomUUID());
        r.setReservedBytes(100L);
        r.setStatus("ACTIVE");
        r.setExpiresAt(Instant.now().minusSeconds(60));
        return r;
    }
}
