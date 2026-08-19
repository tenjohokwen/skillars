package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.repo.VideoQuotaRepository;
import com.softropic.skillars.platform.video.repo.VideoQuotaReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// skillars-deferred-40 AC4: targeted coverage of incrementBandwidthUsedBytes's no-op guard and
// successful-increment path — not a full re-test of QuotaService's existing behavior (see
// QuotaServiceConcurrencyIT for reserve/commit/release coverage).
@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    @Mock VideoQuotaRepository videoQuotaRepository;
    @Mock VideoQuotaReservationRepository reservationRepository;
    @Mock QuotaConfigService quotaConfigService;
    @Mock JdbcTemplate jdbcTemplate;

    QuotaService quotaService;

    @BeforeEach
    void setUp() {
        quotaService = new QuotaService(videoQuotaRepository, reservationRepository, quotaConfigService, jdbcTemplate);
    }

    @Test
    void incrementBandwidthUsedBytes_zeroBytes_isNoOp() {
        quotaService.incrementBandwidthUsedBytes("owner-1", 0L);

        verify(jdbcTemplate, never()).update(any(String.class), any(Object[].class));
    }

    @Test
    void incrementBandwidthUsedBytes_negativeBytes_isNoOp() {
        quotaService.incrementBandwidthUsedBytes("owner-1", -1L);

        verify(jdbcTemplate, never()).update(any(String.class), any(Object[].class));
    }

    @Test
    void incrementBandwidthUsedBytes_positiveBytes_issuesAtomicUpdate() {
        quotaService.incrementBandwidthUsedBytes("owner-1", 500L);

        verify(jdbcTemplate).update(
            "UPDATE main.video_quotas SET bandwidth_used_bytes = bandwidth_used_bytes + ? WHERE user_id = ?",
            500L, "owner-1");
    }
}
