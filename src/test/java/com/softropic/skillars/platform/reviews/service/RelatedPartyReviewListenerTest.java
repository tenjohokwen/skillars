package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.platform.payment.contract.event.RelatedPartyBookingFlaggedEvent;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RelatedPartyReviewListenerTest {

    private final UUID coachId = UUID.randomUUID();

    @Mock CoachReviewRepository reviewRepository;
    @Mock CoachRatingService coachRatingService;
    @InjectMocks RelatedPartyReviewListener listener;

    @Test
    void hidesPayerAndPlayerAuthoredReviews_thenRecomputesTheAggregate() {
        when(reviewRepository.markRelatedParty(coachId, List.of(5L, 6L))).thenReturn(1);

        listener.onBookingFlagged(new RelatedPartyBookingFlaggedEvent(UUID.randomUUID(), coachId, 5L, 6L));

        verify(coachRatingService).recompute(coachId);
    }

    @Test
    void nothingToHide_recomputesNothing_andAPlayerWithoutAccountIsNotAnAuthor() {
        when(reviewRepository.markRelatedParty(coachId, List.of(5L))).thenReturn(0);

        listener.onBookingFlagged(new RelatedPartyBookingFlaggedEvent(UUID.randomUUID(), coachId, 5L, null));

        verify(coachRatingService, never()).recompute(coachId);
    }
}
