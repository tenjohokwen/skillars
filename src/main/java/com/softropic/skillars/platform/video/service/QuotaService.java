package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.contract.ConsistencyGuarantee;
import com.softropic.skillars.platform.video.contract.QuotaProvider;
import com.softropic.skillars.platform.video.contract.VideoType;
import com.softropic.skillars.platform.video.contract.exception.QuotaExceededException;
import io.micrometer.observation.annotation.Observed;
import com.softropic.skillars.platform.video.repo.VideoQuota;
import com.softropic.skillars.platform.video.repo.VideoQuotaRepository;
import com.softropic.skillars.platform.video.repo.VideoQuotaReservation;
import com.softropic.skillars.platform.video.repo.VideoQuotaReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService implements QuotaProvider {

    private final VideoQuotaRepository videoQuotaRepository;
    private final VideoQuotaReservationRepository reservationRepository;
    private final QuotaConfigService quotaConfigService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public boolean check(String ownerId, long requestedBytes) {
        // ADVISORY ONLY — no lock held. A concurrent reserve() may drain quota between this
        // check and the caller's subsequent reserve() call. reserve() is the authoritative gate.
        ensureQuotaRowExists(ownerId);
        long storageQuota = quotaConfigService.getStorageQuotaBytes(ownerId);
        if (storageQuota == 0) {
            return false;  // Scout tier: no video storage
        }
        VideoQuota quota = videoQuotaRepository.findById(ownerId)
            .orElseThrow(() -> new IllegalStateException("video_quotas row missing after init for: " + ownerId));
        long activeReservedBytes = reservationRepository.sumActiveReservedBytes(ownerId);
        return quota.getStorageUsedBytes() + activeReservedBytes + requestedBytes <= storageQuota;
    }

    @Override
    @Observed(name = "video.quota.reserve")
    @Transactional
    // 2-arg callers (DrillUploadService) go through the Spring proxy so @Observed fires here;
    // the self-call to 3-arg bypasses the proxy, so @Observed on 3-arg alone would miss this path.
    public String reserve(String ownerId, long bytes) {
        return reserve(ownerId, bytes, null);
    }

    @Override
    @Observed(name = "video.quota.reserve")
    @Transactional
    public String reserve(String ownerId, long bytes, VideoType videoType) {
        // 1. Lazy init
        ensureQuotaRowExists(ownerId);
        // 2. SELECT FOR UPDATE — serialises concurrent reservations for this ownerId
        VideoQuota quota = videoQuotaRepository.findByIdForUpdate(ownerId)
            .orElseThrow(() -> new IllegalStateException("video_quotas row missing after init for: " + ownerId));
        // 3. Validate timeout config before proceeding
        long timeoutMinutes = quotaConfigService.getReservationTimeoutMinutes();
        if (timeoutMinutes <= 0) {
            throw new IllegalStateException(
                "platform.video_reservation_timeout_minutes must be positive, got: " + timeoutMinutes);
        }
        // 4. Check against tier quota including in-flight ACTIVE reservations (serialised by the FOR UPDATE above)
        long storageQuota = quotaConfigService.getStorageQuotaBytes(ownerId);
        long activeReservedBytes = reservationRepository.sumActiveReservedBytes(ownerId);
        if (storageQuota == 0 || quota.getStorageUsedBytes() + activeReservedBytes + bytes > storageQuota) {
            throw new QuotaExceededException(ownerId, storageQuota, bytes);
        }
        // 5. Insert reservation with videoType populated (null = no type constraint)
        VideoQuotaReservation reservation = new VideoQuotaReservation();
        reservation.setUserId(ownerId);
        reservation.setReservedBytes(bytes);
        reservation.setStatus("ACTIVE");
        reservation.setVideoType(videoType);
        reservation.setExpiresAt(Instant.now().plus(timeoutMinutes, ChronoUnit.MINUTES));
        VideoQuotaReservation saved = reservationRepository.save(reservation);
        log.debug("Quota reserved: ownerId={} bytes={} type={} reservationId={}",
                  ownerId, bytes, videoType, saved.getId());
        return saved.getId().toString();
    }

    @Override
    @Transactional
    public void commit(String reservationHandle) {
        try {
            UUID.fromString(reservationHandle);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid reservation handle (not a UUID): " + reservationHandle, e);
        }
        // Single atomic CTE: status transition ACTIVE→COMMITTED + storage increment in one statement.
        // WHERE status='ACTIVE' is the idempotency and concurrency guard:
        //   - Second commit() call finds 0 rows → no double-increment.
        //   - Concurrent commit() calls race on the UPDATE; exactly one wins.
        int updated = jdbcTemplate.update("""
            WITH committed AS (
                UPDATE main.video_quota_reservations
                SET    status = 'COMMITTED'
                WHERE  id = ?::uuid AND status = 'ACTIVE'
                RETURNING user_id, reserved_bytes
            )
            UPDATE main.video_quotas q
            SET    storage_used_bytes = storage_used_bytes + c.reserved_bytes
            FROM   committed c
            WHERE  q.user_id = c.user_id
            """, reservationHandle);
        if (updated == 0) {
            log.debug("commit() no-op: reservation {} already COMMITTED or not found", reservationHandle);
            return;
        }
        log.debug("Quota committed atomically: reservation={}", reservationHandle);
    }

    @Override
    @Transactional
    public void release(String reservationHandle) {
        try {
            UUID.fromString(reservationHandle);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid reservation handle (not a UUID): " + reservationHandle, e);
        }
        // Single atomic UPDATE — only transitions ACTIVE→RELEASED; silently ignores COMMITTED and RELEASED
        // (idempotency). Eliminates the TOCTOU window where a concurrent commit() CTE could run between
        // findById and save, causing release() to overwrite COMMITTED back to RELEASED.
        int updated = jdbcTemplate.update(
            "UPDATE main.video_quota_reservations SET status = 'RELEASED' WHERE id = ?::uuid AND status = 'ACTIVE'",
            reservationHandle);
        if (updated > 0) {
            log.debug("Quota reservation released: handle={}", reservationHandle);
        }
        // storage_used_bytes is NOT decremented on release — only COMMITTED increments it
    }

    @Transactional
    public void decrementStorageBytes(String ownerId, long bytes) {
        if (bytes <= 0) return;
        jdbcTemplate.update(
            "UPDATE main.video_quotas SET storage_used_bytes = GREATEST(0, storage_used_bytes - ?) WHERE user_id = ?",
            bytes, ownerId);
        log.debug("Quota decremented: ownerId={} bytes={}", ownerId, bytes);
    }

    @Override
    public ConsistencyGuarantee getConsistencyGuarantee() {
        return ConsistencyGuarantee.STRICT;
    }

    private void ensureQuotaRowExists(String ownerId) {
        jdbcTemplate.update(
            "INSERT INTO main.video_quotas (user_id, storage_used_bytes, bandwidth_used_bytes, bandwidth_period_start) " +
            "VALUES (?, 0, 0, NOW()) ON CONFLICT (user_id) DO NOTHING",
            ownerId);
    }
}
