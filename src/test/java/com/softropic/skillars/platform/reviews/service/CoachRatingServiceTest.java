package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoachRatingServiceTest {

    @Mock private CoachReviewRepository reviewRepository;
    @Mock private CoachProfileRepository coachProfileRepository;

    @InjectMocks private CoachRatingService coachRatingService;

    @Test
    void recompute_withApprovedReviews_updatesAvgAndCount() {
        UUID coachId = UUID.randomUUID();
        when(reviewRepository.computeAggregates(coachId, ReviewModerationStatus.APPROVED))
            .thenReturn(Collections.singletonList(new Object[]{2L, 4.0}));

        coachRatingService.recompute(coachId);

        verify(coachProfileRepository).updateRatingAggregate(coachId, 4.0, 2);
    }

    @Test
    void recompute_noApprovedReviews_updatesNullAndZero() {
        UUID coachId = UUID.randomUUID();
        when(reviewRepository.computeAggregates(coachId, ReviewModerationStatus.APPROVED))
            .thenReturn(Collections.singletonList(new Object[]{0L, null}));

        coachRatingService.recompute(coachId);

        verify(coachProfileRepository).updateRatingAggregate(coachId, null, 0);
    }

    @Test
    void recompute_roundsToOneDecimal() {
        UUID coachId = UUID.randomUUID();
        when(reviewRepository.computeAggregates(coachId, ReviewModerationStatus.APPROVED))
            .thenReturn(Collections.singletonList(new Object[]{4L, 3.75}));

        coachRatingService.recompute(coachId);

        verify(coachProfileRepository).updateRatingAggregate(coachId, 3.8, 4);
    }
}
