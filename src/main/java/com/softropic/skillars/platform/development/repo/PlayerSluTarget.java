package com.softropic.skillars.platform.development.repo;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(schema = "development", name = "player_slu_targets")
@Getter
@Setter
@NoArgsConstructor
public class PlayerSluTarget {

    @EmbeddedId
    private PlayerSluTargetId id;

    @Column(name = "weekly_target_slu", nullable = false, precision = 10, scale = 4)
    private BigDecimal weeklyTargetSlu;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    public static class PlayerSluTargetId implements Serializable {

        @Column(name = "coach_id")
        private UUID coachId;

        @Column(name = "player_id")
        private Long playerId;

        @Column(name = "skill_code", length = 10)
        private String skillCode;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PlayerSluTargetId that)) return false;
            return Objects.equals(coachId, that.coachId)
                && Objects.equals(playerId, that.playerId)
                && Objects.equals(skillCode, that.skillCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(coachId, playerId, skillCode);
        }
    }
}
