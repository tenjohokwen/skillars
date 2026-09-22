package com.softropic.skillars.platform.security.repo;

import com.softropic.skillars.infrastructure.persistence.BaseEntity;
import com.softropic.skillars.platform.security.contract.AgeTier;
import com.softropic.skillars.platform.security.contract.PlayerPosition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "player_profiles", schema = "main")
public class PlayerProfile extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PlayerPosition position;

    @Enumerated(EnumType.STRING)
    @Column(name = "age_tier", nullable = false, length = 15)
    private AgeTier ageTier;

    @Column(name = "parent_id")
    private Long parentId;

    /** Set instead of {@link #parentId} for adult (18+) players who self-registered — exactly one of the two is ever set. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "independent_account_allowed", nullable = false)
    private boolean independentAccountAllowed = true;

    @Column(name = "consent_accepted_at")
    private Instant consentAcceptedAt;

    @Column(name = "consent_policy_version", length = 10)
    private String consentPolicyVersion;

    /**
     * skillars-deferred-127 code review (2026-09-21): a sticky tombstone set by
     * {@code GdprErasureService.deletePlayerDevelopmentData} (under this row's own pessimistic lock)
     * and checked by {@code RadarCompositeCalculationService.recalculateComposite} (immediately after
     * it re-acquires/refreshes that same lock, before reading any aggregates) — closing the residual
     * resurrection race the shared lock alone does not: {@code RadarAssessmentService.submitAssessment}
     * writes {@code radar_assessment_entries} without taking this lock at all, so a coach submission
     * that commits during an in-flight erasure can leave rows the erasure's own {@code DELETE} never
     * saw (not yet committed at the time it ran). A single check inside {@code recalculateComposite}
     * covers both the live {@code AFTER_COMMIT} path and the {@code RadarCompositeDlqProcessor} retry
     * path, since every route to an upsert goes through that one method. {@code null} means "not
     * erased" — never reset back to {@code null} once set, since a {@code player_profiles} row is
     * never "un-erased".
     */
    @Column(name = "development_data_erased_at")
    private Instant developmentDataErasedAt;
}
