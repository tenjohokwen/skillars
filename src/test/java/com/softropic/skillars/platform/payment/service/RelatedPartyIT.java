package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
import com.softropic.skillars.platform.reviews.contract.AuthorReviewDto;
import com.softropic.skillars.platform.reviews.contract.ReviewListResponse;
import com.softropic.skillars.platform.reviews.contract.SubmitReviewResponse;
import com.softropic.skillars.platform.reviews.service.ReviewQueryService;
import com.softropic.skillars.platform.reviews.service.ReviewSubmissionService;
import com.softropic.skillars.platform.security.service.UserIdentitySignalService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * feat/security-s06-related-party (V219), against a real database: a coach and a second account of their own are
 * linked by a shared identifier; the booking is flagged, its payout is held 60 days, it stops counting toward ending
 * the new-coach hold, and the second account's review is stored but neither shown nor counted. A coach who is an
 * unrelated parent of another coach's trainee is untouched.
 */
class RelatedPartyIT extends BasePaymentIT {

    private static final AtomicLong IDS = new AtomicLong(97_100_000L);

    @Autowired RelatedPartyService relatedPartyService;
    @Autowired UserIdentitySignalService signalService;
    @Autowired CoachPayoutOutboxSupport coachPayoutOutboxSupport;
    @Autowired CoachPayoutRepository coachPayoutRepository;
    @Autowired ReviewSubmissionService reviewSubmissionService;
    @Autowired ReviewQueryService reviewQueryService;

    private long coachUserId;
    private long payerId;
    private long playerId;
    private UUID coachId;

    @BeforeEach
    void seed() {
        long base = IDS.getAndAdd(10);
        coachUserId = base;
        payerId = base + 1;
        playerId = base + 2;
        coachId = insertTestCoach(coachUserId, "rp.coach." + base + "@test.com", "RP Coach " + base);
        insertTestParent(payerId, "rp.payer." + base + "@test.com");
        insertTestPlayer(playerId, payerId);
    }

    @AfterEach
    void cleanup() {
        RequestMetadataProvider.cleanup();
    }

    // ── fixtures ──

    private UUID booking(long forPayer, long forPlayer, UUID forCoach, int ageDays) {
        UUID bookingId = UUID.randomUUID();
        Instant end = Instant.now().minus(ageDays, ChronoUnit.DAYS);
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, "
                    + "requested_end_time, status, canonical_timezone, version, created_at, updated_at, status_changed_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED', 'UTC', 0, now(), ?, ?)",
                bookingId, forPayer, forPlayer, forCoach, Timestamp.from(end.minus(1, ChronoUnit.HOURS)),
                Timestamp.from(end), Timestamp.from(end), Timestamp.from(end));
            jdbcTemplate.update(
                "INSERT INTO payment.booking_payments (booking_id, credit_debited, stripe_charged, status, captured_at, "
                    + "commission_rate, stripe_payment_intent_id) VALUES (?, 0, 50.00, 'CAPTURED', now(), 0.10, ?)",
                bookingId, "pi_rp_" + bookingId);
            return null;
        });
        return bookingId;
    }

    private boolean evaluate(UUID bookingId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(s -> relatedPartyService.evaluate(bookingId, "confirmation")));
    }

    private boolean flagged(UUID bookingId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.related_party_bookings WHERE booking_id = ?", Integer.class, bookingId) == 1;
    }

    private void setEmail(long userId, String email) {
        transactionTemplate.execute(s -> jdbcTemplate.update("UPDATE main.\"user\" SET email = ? WHERE id = ?", email, userId));
    }

    private void enqueue(UUID bookingId) {
        transactionTemplate.execute(s -> {
            coachPayoutOutboxSupport.enqueuePayout(bookingId, coachId);
            return null;
        });
    }

    private Instant releaseAfter(UUID bookingId) {
        return coachPayoutRepository.findById(bookingId).orElseThrow().getReleaseAfter();
    }

    // ── detection ──

    @Test
    void sharedCardFingerprint_flagsTheBooking() {
        transactionTemplate.execute(s -> {
            signalService.recordCardFingerprint(coachUserId, "fp_shared_card_" + coachUserId);
            signalService.recordCardFingerprint(payerId, "fp_shared_card_" + coachUserId);
            return null;
        });
        UUID bookingId = booking(payerId, playerId, coachId, 10);

        assertThat(evaluate(bookingId)).isTrue();

        assertThat(flagged(bookingId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT matched_kinds FROM payment.related_party_bookings WHERE booking_id = ?", String.class, bookingId))
            .isEqualTo("CARD");
    }

    @Test
    void normalisedEmailAndPhoneVariants_flagTheBooking() {
        setEmail(coachUserId, "jo.hn" + coachUserId + "@gmail.com");
        setEmail(payerId, "john" + coachUserId + "+kids@googlemail.com");
        UUID bookingId = booking(payerId, playerId, coachId, 10);

        assertThat(evaluate(bookingId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT matched_kinds FROM payment.related_party_bookings WHERE booking_id = ?", String.class, bookingId))
            .isEqualTo("EMAIL");
    }

    @Test
    void sharedBrowserFingerprint_flagsTheBooking() {
        recordBrowserFor(coachUserId, "browser-fingerprint-" + coachUserId);
        recordBrowserFor(payerId, "browser-fingerprint-" + coachUserId);
        UUID bookingId = booking(payerId, playerId, coachId, 10);

        assertThat(evaluate(bookingId)).isTrue();
    }

    private void recordBrowserFor(long userId, String fingerprint) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SecurityConstants.F_COOKIE, fingerprint));
        RequestMetadataProvider.initRequestMetadata(request);
        transactionTemplate.execute(s -> {
            signalService.recordCurrentBrowser(userId);
            return null;
        });
        RequestMetadataProvider.cleanup();
    }

    @Test
    void payerIsTheCoachsOwnUser_isFlagged() {
        long playerOfCoach = IDS.getAndIncrement();
        insertTestPlayer(playerOfCoach, coachUserId);
        UUID bookingId = booking(coachUserId, playerOfCoach, coachId, 10);

        assertThat(evaluate(bookingId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT matched_kinds FROM payment.related_party_bookings WHERE booking_id = ?", String.class, bookingId))
            .isEqualTo("SELF");
    }

    @Test
    void coachWhoIsAnUnrelatedParentOfAnotherCoachsTrainee_isNotAffected() {
        long otherCoachUser = IDS.getAndAdd(10);
        UUID otherCoach = insertTestCoach(otherCoachUser, "rp.other." + otherCoachUser + "@test.com", "RP Other");
        // The first coach, acting as a parent, books the other coach for their own child.
        long childOfCoach = IDS.getAndIncrement();
        insertTestPlayer(childOfCoach, coachUserId);
        UUID bookingId = booking(coachUserId, childOfCoach, otherCoach, 10);
        // ...and an unrelated parent books the first coach.
        UUID plainBooking = booking(payerId, playerId, coachId, 10);

        assertThat(evaluate(bookingId)).isFalse();
        assertThat(evaluate(plainBooking)).isFalse();

        enqueue(bookingId);
        assertThat(releaseAfter(bookingId)).isBefore(Instant.now().plus(Duration.ofDays(9)));
    }

    @Test
    void onlyHashesAreStored() {
        setEmail(coachUserId, "plain" + coachUserId + "@example.org");
        UUID bookingId = booking(payerId, playerId, coachId, 10);
        evaluate(bookingId);

        List<String> hashes = jdbcTemplate.queryForList(
            "SELECT value_hash FROM main.user_identity_signals WHERE user_id = ? AND kind = 'EMAIL'",
            String.class, coachUserId);
        assertThat(hashes).hasSize(1);
        assertThat(hashes.get(0)).matches("[0-9a-f]{64}").doesNotContain("plain");
    }

    // ── consequences ──

    @Test
    void flaggedBooking_isFlaggedOnce_andEvaluatingAgainIsIdempotent() {
        setEmail(coachUserId, "same" + coachUserId + "@example.org");
        setEmail(payerId, "Same" + coachUserId + "+kids@Example.org");
        UUID bookingId = booking(payerId, playerId, coachId, 10);

        assertThat(evaluate(bookingId)).isTrue();
        assertThat(evaluate(bookingId)).isTrue();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.related_party_bookings WHERE booking_id = ?", Integer.class, bookingId))
            .isEqualTo(1);
    }

    @Test
    void flaggedBooking_payoutIsHeldSixtyDays_andNeverCountsTowardTheNewCoachRelease() {
        setEmail(coachUserId, "hold" + coachUserId + "@example.org");
        setEmail(payerId, "hold" + coachUserId + "+kids@example.org");
        UUID bookingId = booking(payerId, playerId, coachId, 10);

        enqueue(bookingId);

        assertThat(flagged(bookingId)).isTrue();
        assertThat(releaseAfter(bookingId)).isAfter(Instant.now().plus(Duration.ofDays(59)));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT payout_hold_until > now() + interval '59 days' FROM payment.related_party_bookings "
                + "WHERE booking_id = ?", Boolean.class, bookingId)).isTrue();
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "UPDATE payment.coach_payouts SET status = 'RELEASED', released_at = now() WHERE booking_id = ?", bookingId));
        assertThat(coachPayoutRepository.countQualifyingReleasedByCoach(coachId, BigDecimal.ZERO)).isZero();
    }

    @Test
    void genuineReleasedPayout_stillCountsTowardTheNewCoachRelease() {
        UUID bookingId = booking(payerId, playerId, coachId, 10);
        enqueue(bookingId);
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "UPDATE payment.coach_payouts SET status = 'RELEASED', released_at = now() WHERE booking_id = ?", bookingId));

        assertThat(coachPayoutRepository.countQualifyingReleasedByCoach(coachId, BigDecimal.ZERO)).isEqualTo(1);
    }

    @Test
    void flagRaisedAfterThePayoutWasEnqueued_extendsTheStoredHoldTheHandlerEnforces() {
        UUID bookingId = booking(payerId, playerId, coachId, 10);
        enqueue(bookingId);
        assertThat(releaseAfter(bookingId)).isBefore(Instant.now().plus(Duration.ofDays(9)));

        setEmail(coachUserId, "late" + coachUserId + "@example.org");
        setEmail(payerId, "late" + coachUserId + "+kids@example.org");
        assertThat(evaluate(bookingId)).isTrue();

        assertThat(relatedPartyService.findPayoutHoldUntil(bookingId)).hasValueSatisfying(
            until -> assertThat(until).isAfter(Instant.now().plus(Duration.ofDays(59))));
    }

    // ── reviews ──

    private UUID insertApprovedReview(long author, int rating) {
        UUID reviewId = UUID.randomUUID();
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO reviews.coach_reviews (review_id, coach_id, author_id, author_role, rating, body, "
                    + "moderation_status) VALUES (?, ?, ?, 'PARENT', ?, 'great', 'APPROVED')",
                reviewId, coachId, author, rating);
            jdbcTemplate.update(
                "UPDATE marketplace.coach_profiles SET average_rating = ?, review_count = 1 WHERE id = ?",
                new BigDecimal(rating), coachId);
            return null;
        });
        return reviewId;
    }

    @Test
    void reviewAlreadyPosted_isRetroactivelyHidden_andTheAggregateRecomputed() {
        insertApprovedReview(payerId, 5);
        setEmail(coachUserId, "rev" + coachUserId + "@example.org");
        setEmail(payerId, "rev" + coachUserId + "+kids@example.org");
        UUID bookingId = booking(payerId, playerId, coachId, 10);

        assertThat(evaluate(bookingId)).isTrue();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT related_party FROM reviews.coach_reviews WHERE coach_id = ? AND author_id = ?",
            Boolean.class, coachId, payerId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT review_count FROM marketplace.coach_profiles WHERE id = ?", Integer.class, coachId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT average_rating FROM marketplace.coach_profiles WHERE id = ?", BigDecimal.class, coachId)).isNull();
        ReviewListResponse listed = reviewQueryService.listApprovedReviews(coachId, 0, "newest");
        assertThat(listed.reviews()).isEmpty();
        assertThat(reviewQueryService.listCoachOwnReviews(coachId, 0).reviews()).isEmpty();
        AuthorReviewDto mine = reviewQueryService.getAuthorReview(payerId, coachId);
        assertThat(mine.relatedPartyHidden()).isTrue();
        assertThat(mine.rating()).isEqualTo(5);
    }

    @Test
    void reviewSubmittedAfterTheFlag_isStoredHidden_uncounted_andCannotBeResubmitted() {
        setEmail(coachUserId, "post" + coachUserId + "@example.org");
        setEmail(payerId, "post" + coachUserId + "+kids@example.org");
        UUID bookingId = booking(payerId, playerId, coachId, 10);
        assertThat(evaluate(bookingId)).isTrue();

        SubmitReviewResponse response = reviewSubmissionService.submitReview(coachId, payerId, 5, "Brilliant");

        assertThat(response.reviewId()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT related_party FROM reviews.coach_reviews WHERE review_id = ?", Boolean.class, response.reviewId()))
            .isTrue();
        assertThat(reviewQueryService.getAuthorReview(payerId, coachId).relatedPartyHidden()).isTrue();
        assertThat(reviewQueryService.listApprovedReviews(coachId, 0, "newest").reviews()).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> reviewSubmissionService.submitReview(coachId, payerId, 5, "Again"))
            .hasMessageContaining("already submitted");
    }

    @Test
    void unrelatedParentsReview_isPublishedAndCounted_alongsideAHiddenRelatedOne() {
        long honestParent = IDS.getAndIncrement();
        insertTestParent(honestParent, "rp.honest." + honestParent + "@test.com");
        long honestPlayer = IDS.getAndIncrement();
        insertTestPlayer(honestPlayer, honestParent);
        booking(honestParent, honestPlayer, coachId, 10);
        UUID honestReview = reviewSubmissionService.submitReview(coachId, honestParent, 4, "Good").reviewId();
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "UPDATE reviews.coach_reviews SET moderation_status = 'APPROVED' WHERE review_id = ?", honestReview));

        setEmail(coachUserId, "mix" + coachUserId + "@example.org");
        setEmail(payerId, "mix" + coachUserId + "+kids@example.org");
        UUID related = booking(payerId, playerId, coachId, 10);
        evaluate(related);
        UUID relatedReview = reviewSubmissionService.submitReview(coachId, payerId, 1, "Hmm").reviewId();
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "UPDATE reviews.coach_reviews SET moderation_status = 'APPROVED' WHERE review_id = ?", relatedReview));

        ReviewListResponse listed = reviewQueryService.listApprovedReviews(coachId, 0, "newest");
        assertThat(listed.reviews()).hasSize(1);
        assertThat(listed.reviews().get(0).rating()).isEqualTo(4);
        assertThat(reviewQueryService.getAuthorReview(honestParent, coachId).relatedPartyHidden()).isFalse();
    }

    // ── GDPR ──

    @Test
    void erasure_deletesEverySignalOfTheUser() {
        setEmail(coachUserId, "gdpr" + coachUserId + "@example.org");
        UUID bookingId = booking(payerId, playerId, coachId, 10);
        evaluate(bookingId);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.user_identity_signals WHERE user_id = ?", Integer.class, coachUserId))
            .isPositive();

        transactionTemplate.execute(s -> signalService.eraseForUser(coachUserId));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.user_identity_signals WHERE user_id = ?", Integer.class, coachUserId)).isZero();
    }
}
