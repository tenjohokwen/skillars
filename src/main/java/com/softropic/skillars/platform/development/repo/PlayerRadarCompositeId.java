package com.softropic.skillars.platform.development.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode
public class PlayerRadarCompositeId implements Serializable {

    @Column(name = "player_id")
    private Long playerId;

    @Column(name = "skill_code", length = 10)
    private String skillCode;
}
