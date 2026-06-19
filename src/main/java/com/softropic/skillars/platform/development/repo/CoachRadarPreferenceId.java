package com.softropic.skillars.platform.development.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode
public class CoachRadarPreferenceId implements Serializable {

    @Column(name = "coach_id")
    private UUID coachId;

    @Column(name = "player_id")
    private Long playerId;   // BIGINT — NOT UUID
}
