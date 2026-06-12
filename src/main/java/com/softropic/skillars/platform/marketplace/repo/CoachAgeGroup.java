package com.softropic.skillars.platform.marketplace.repo;

import com.softropic.skillars.platform.security.contract.AgeTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(schema = "marketplace", name = "coach_age_groups")
@Getter
@Setter
@NoArgsConstructor
public class CoachAgeGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "coach_id", nullable = false)
    private UUID coachId;

    @Enumerated(EnumType.STRING)
    @Column(name = "age_tier", nullable = false)
    private AgeTier ageTier;
}
