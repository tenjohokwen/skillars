package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CoachVisibilitySuppressionIT extends BasePaymentIT {

    @Autowired ReliabilityStrikeService reliabilityStrikeService;
    @Autowired CoachProfileRepository coachProfileRepository;

    private static final long COACH_USER_ID = 70001L;
    private static final String COACH_EMAIL = "coach.reliability@test.com";

    @AfterEach
    void cleanStrikesAndProfiles() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM marketplace.coach_reliability_strikes");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_profiles WHERE user_id = " + COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", COACH_USER_ID);
            return null;
        });
    }

    @Test
    void threeStrikes_coachStatusBecomesReduced() {
        UUID coachId = insertTestCoach(COACH_USER_ID, COACH_EMAIL, "Reliability Test Coach");

        // Issue 3 strikes — reaches visibilityThreshold
        for (int i = 0; i < 3; i++) {
            reliabilityStrikeService.issue(coachId, UUID.randomUUID(), "COACH_CANCELLATION_UNEXCUSED");
        }

        CoachProfileStatus status = coachProfileRepository.findById(coachId)
            .map(p -> p.getStatus())
            .orElseThrow();
        assertThat(status).isEqualTo(CoachProfileStatus.REDUCED);

        long strikeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE coach_id = ?",
            Long.class, coachId
        );
        assertThat(strikeCount).isEqualTo(3);
    }

    @Test
    void fiveStrikes_coachStatusBecomesPendingReview() {
        UUID coachId = insertTestCoach(COACH_USER_ID, COACH_EMAIL, "Reliability Test Coach");

        for (int i = 0; i < 5; i++) {
            reliabilityStrikeService.issue(coachId, UUID.randomUUID(), "COACH_NO_SHOW");
        }

        CoachProfileStatus status = coachProfileRepository.findById(coachId)
            .map(p -> p.getStatus())
            .orElseThrow();
        assertThat(status).isEqualTo(CoachProfileStatus.PENDING_REVIEW);

        long strikeCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM marketplace.coach_reliability_strikes WHERE coach_id = ?",
            Long.class, coachId
        );
        assertThat(strikeCount).isEqualTo(5);
    }

    @Test
    void strikeRecordHasBookingIdAndAcknowledgedFalse() {
        UUID coachId = insertTestCoach(COACH_USER_ID, COACH_EMAIL, "Reliability Test Coach");
        UUID bookingId = UUID.randomUUID();

        reliabilityStrikeService.issue(coachId, bookingId, "COACH_NO_SHOW");

        Boolean acknowledged = jdbcTemplate.queryForObject(
            "SELECT acknowledged FROM marketplace.coach_reliability_strikes WHERE coach_id = ? LIMIT 1",
            Boolean.class, coachId
        );
        UUID storedBookingId = jdbcTemplate.queryForObject(
            "SELECT booking_id FROM marketplace.coach_reliability_strikes WHERE coach_id = ? LIMIT 1",
            UUID.class, coachId
        );
        assertThat(acknowledged).isFalse();
        assertThat(storedBookingId).isEqualTo(bookingId);
    }
}
