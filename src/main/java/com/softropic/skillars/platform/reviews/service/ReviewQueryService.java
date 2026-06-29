package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.platform.reviews.contract.CoachOwnReviewDto;
import com.softropic.skillars.platform.reviews.contract.CoachOwnReviewListResponse;
import com.softropic.skillars.platform.reviews.contract.ReviewDto;
import com.softropic.skillars.platform.reviews.contract.ReviewListResponse;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import com.softropic.skillars.platform.reviews.repo.CoachReview;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewQueryService {

    private final CoachReviewRepository reviewRepository;

    public ReviewListResponse listApprovedReviews(UUID coachId, int page, String sort) {
        Sort sortSpec = resolvePublicSort(sort);
        Pageable pageable = PageRequest.of(Math.max(0, page), 10, sortSpec);
        Page<CoachReview> result = reviewRepository.findByCoachIdAndModerationStatus(
            coachId, ReviewModerationStatus.APPROVED, pageable);
        List<ReviewDto> dtos = result.getContent().stream()
            .map(r -> new ReviewDto(
                r.getReviewId(),
                r.getAuthorRole().name(),
                r.getRating(),
                r.getBody(),
                r.getCoachResponseBody(),
                r.getCoachResponseAt(),
                r.getCreatedAt(),
                r.getLastModifiedAt()))
            .toList();
        return new ReviewListResponse(
            dtos, result.getNumber(), result.getTotalPages(),
            result.getTotalElements(), result.hasNext());
    }

    public CoachOwnReviewListResponse listCoachOwnReviews(UUID coachId, int page) {
        Pageable pageable = PageRequest.of(Math.max(0, page), 10, Sort.by(Sort.Direction.DESC, "lastModifiedAt"));
        Page<CoachReview> result = reviewRepository.findByCoachId(coachId, pageable);
        List<CoachOwnReviewDto> dtos = result.getContent().stream()
            .map(r -> new CoachOwnReviewDto(
                r.getReviewId(),
                r.getAuthorRole().name(),
                r.getRating(),
                r.getBody(),
                r.getModerationStatus().name(),
                r.getCoachResponseBody(),
                r.getCoachResponseAt(),
                r.getCreatedAt(),
                r.getLastModifiedAt()))
            .toList();
        return new CoachOwnReviewListResponse(
            dtos, result.getNumber(), result.getTotalPages(),
            result.getTotalElements(), result.hasNext());
    }

    private Sort resolvePublicSort(String sort) {
        return switch (sort == null ? "newest" : sort) {
            case "highest" -> Sort.by(Sort.Direction.DESC, "rating");
            case "lowest"  -> Sort.by(Sort.Direction.ASC, "rating");
            default        -> Sort.by(Sort.Direction.DESC, "lastModifiedAt");
        };
    }
}
