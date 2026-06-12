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

    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    @Column(name = "independent_account_allowed", nullable = false)
    private boolean independentAccountAllowed = true;

    @Column(name = "consent_accepted_at")
    private Instant consentAcceptedAt;

    @Column(name = "consent_policy_version", length = 10)
    private String consentPolicyVersion;
}
