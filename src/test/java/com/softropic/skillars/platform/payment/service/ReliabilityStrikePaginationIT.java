package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrike;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.domain.Sort.Direction.DESC;

/**
 * skillars-deferred-103 AC2: {@code GET /coaches/me/strikes} is now paginated.
 * {@link ReliabilityStrikeService#getCoachStrikes(Long, org.springframework.data.domain.Pageable)}
 * must return a {@link Page} (bounded), newest-first, with correct {@code totalElements} / {@code hasNext}.
 */
class ReliabilityStrikePaginationIT extends BasePaymentIT {

    private static final long COACH_USER_ID = 71_500L;

    @Autowired
    ReliabilityStrikeService reliabilityStrikeService;

    @Test
    void getCoachStrikes_returnsBoundedNewestFirstPage_withCorrectTotalsAndHasNext() {
        UUID coachId = insertTestCoach(COACH_USER_ID, "coach.pagination@test.com", "Pagination Coach");

        int total = 25;
        transactionTemplate.execute(status -> {
            for (int i = 0; i < total; i++) {
                // Stagger created_at so ordering is unambiguous — i=0 oldest, i=24 newest.
                seedStrike(coachId, Instant.now().minus(total - i, ChronoUnit.MINUTES));
            }
            return null;
        });

        Page<CoachReliabilityStrike> page0 = reliabilityStrikeService.getCoachStrikes(
            COACH_USER_ID, PageRequest.of(0, 20, Sort.by(DESC, "createdAt")));

        assertThat(page0.getTotalElements()).isEqualTo(total);
        assertThat(page0.getContent()).hasSize(20);
        assertThat(page0.hasNext()).isTrue();
        assertThat(page0.getContent())
            .extracting(CoachReliabilityStrike::getCreatedAt)
            .isSortedAccordingTo((a, b) -> b.compareTo(a));

        Page<CoachReliabilityStrike> page1 = reliabilityStrikeService.getCoachStrikes(
            COACH_USER_ID, PageRequest.of(1, 20, Sort.by(DESC, "createdAt")));

        assertThat(page1.getContent()).hasSize(5);
        assertThat(page1.hasNext()).isFalse();
        // Newest-first across both pages: the last row of page 0 is newer than the first of page 1.
        assertThat(page0.getContent().get(19).getCreatedAt())
            .isAfterOrEqualTo(page1.getContent().get(0).getCreatedAt());
    }

    private void seedStrike(UUID coachId, Instant createdAt) {
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_reliability_strikes "
                + "(id, coach_id, booking_id, reason, acknowledged, created_at) "
                + "VALUES (?, ?, ?, 'COACH_NO_SHOW', false, ?)",
            UUID.randomUUID(), coachId, UUID.randomUUID(), Timestamp.from(createdAt));
    }
}
