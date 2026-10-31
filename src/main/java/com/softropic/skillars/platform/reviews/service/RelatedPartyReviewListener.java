package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.platform.payment.contract.event.RelatedPartyBookingFlaggedEvent;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * feat/security-s06-related-party: a booking was just flagged related-party, so a review the payer (or the booked
 * player's own account) already posted for that coach is retroactively hidden and the coach's rating recomputed without
 * it. Synchronous, in the flagging transaction, so the flag, the hidden review and the new aggregate commit together.
 * Idempotent: a review already marked is skipped, and nothing is recomputed when nothing changed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelatedPartyReviewListener {

    private final CoachReviewRepository reviewRepository;
    private final CoachRatingService coachRatingService;

    @EventListener
    public void onBookingFlagged(RelatedPartyBookingFlaggedEvent event) {
        List<Long> authors = new ArrayList<>(2);
        authors.add(event.payerUserId());
        if (event.playerUserId() != null && !event.playerUserId().equals(event.payerUserId())) {
            authors.add(event.playerUserId());
        }
        int hidden = reviewRepository.markRelatedParty(event.coachId(), authors);
        if (hidden > 0) {
            coachRatingService.recompute(event.coachId());
            log.info("[RELATED_PARTY_REVIEW_HIDDEN] coach={} reviews={}", event.coachId(), hidden);
        }
    }
}
