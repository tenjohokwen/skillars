package com.softropic.skillars.platform.marketplace.repo;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One round trip for the four small "facts about a coach" reads that
 * {@code CoachProfileService.getPublicProfile} used to issue separately.
 *
 * <p>skillars-deferred-91 AC11 / code review decision D9. The measured cost of
 * {@code getPublicProfile} was 8 independent round trips, above AC11's "collapse if &gt; 4"
 * threshold. Four of the eight — specialties, age groups, an availability existence check and a
 * 90-day strike count — are all {@code (text, text)}-shaped once projected, so a single
 * {@code UNION ALL} keyed on {@code coach_id} returns them together with no cartesian product and no
 * {@code MultipleBagFetchException} risk (the hazards the original "left as-is" decision correctly
 * identified for a naive {@code JOIN FETCH} across six collections).
 *
 * <p>Native on purpose: JPQL has no {@code UNION}. The projection is deliberately homogeneous —
 * every branch yields exactly {@code (kind text, value text)} — so PostgreSQL needs no implicit type
 * coercion and the result maps to a trivial two-column interface.
 */
public interface CoachPublicProfileFactsRepository extends Repository<CoachSpecialty, UUID> {

    /** One {@code (kind, value)} row. Counts arrive as text and are parsed by the caller. */
    interface Fact {
        String getKind();

        String getValue();
    }

    String KIND_SPECIALTY = "SPECIALTY";
    String KIND_AGE_GROUP = "AGE_GROUP";
    String KIND_AVAILABILITY_COUNT = "AVAILABILITY_COUNT";
    String KIND_STRIKE_COUNT = "STRIKE_COUNT";

    @Query(value = """
        SELECT 'SPECIALTY' AS kind, s.skill AS value
          FROM marketplace.coach_specialties s
         WHERE s.coach_id = :coachId
        UNION ALL
        SELECT 'AGE_GROUP' AS kind, g.age_tier AS value
          FROM marketplace.coach_age_groups g
         WHERE g.coach_id = :coachId
        UNION ALL
        SELECT 'AVAILABILITY_COUNT' AS kind, CAST(COUNT(*) AS text) AS value
          FROM marketplace.coach_availability_windows w
         WHERE w.coach_id = :coachId
        UNION ALL
        SELECT 'STRIKE_COUNT' AS kind, CAST(COUNT(*) AS text) AS value
          FROM marketplace.coach_reliability_strikes r
         WHERE r.coach_id = :coachId
           AND r.created_at > :since
        """, nativeQuery = true)
    List<Fact> findPublicProfileFacts(@Param("coachId") UUID coachId,
                                      @Param("since") OffsetDateTime since);
}
