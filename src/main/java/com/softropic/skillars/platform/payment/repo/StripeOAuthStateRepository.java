package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

public interface StripeOAuthStateRepository extends JpaRepository<StripeOAuthState, String> {

    /**
     * Single-use consumption: one atomic DELETE that matches only an unexpired state minted for this coach.
     * Of two concurrent callbacks carrying the same {@code state} exactly one sees a count of 1.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM StripeOAuthState s WHERE s.nonceHash = :nonceHash AND s.coachId = :coachId "
        + "AND s.expiresAt > :now")
    int consume(@Param("nonceHash") String nonceHash, @Param("coachId") UUID coachId, @Param("now") Instant now);

    /** Drops every expired state and the coach's earlier ones: one outstanding onboarding link per coach. */
    @Modifying
    @Transactional
    @Query("DELETE FROM StripeOAuthState s WHERE s.coachId = :coachId OR s.expiresAt <= :now")
    int purgeForCoachAndExpired(@Param("coachId") UUID coachId, @Param("now") Instant now);
}
