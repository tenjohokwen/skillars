package com.softropic.skillars.platform.security.repo;

import com.softropic.skillars.infrastructure.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "parent_player_links", schema = "main")
public class ParentPlayerLink extends BaseEntity {

    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "consent_accepted_at", nullable = false)
    private Instant consentAcceptedAt;

    @Column(name = "consent_policy_version", nullable = false, length = 10)
    private String consentPolicyVersion;
}
