package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CoachRatingService {

    private final CoachReviewRepository reviewRepository;
    private final CoachProfileRepository coachProfileRepository;

    @Transactional
    public void recompute(UUID coachId) {
        List<Object[]> rows = reviewRepository.computeAggregates(coachId, ReviewModerationStatus.APPROVED);
        Object[] agg = rows.get(0);
        long count = ((Number) agg[0]).longValue();
        Double avg = (Double) agg[1];
        Double rounded = avg == null ? null : Math.round(avg * 10.0) / 10.0;
        coachProfileRepository.updateRatingAggregate(coachId, rounded, (int) count);
        log.debug("Rating recomputed: coachId={}, avgRating={}, reviewCount={}", coachId, rounded, count);
    }
}
