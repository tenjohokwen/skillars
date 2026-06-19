package com.softropic.skillars.platform.development.repo;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

@Entity
@Table(schema = "development", name = "player_slu_weekly_snapshot")
@Getter
@Setter
@NoArgsConstructor
public class PlayerSluWeeklySnapshot {

    @EmbeddedId
    private PlayerSluSnapshotId id;

    @Column(name = "total_slu", nullable = false, precision = 12, scale = 4)
    private BigDecimal totalSlu;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    public static class PlayerSluSnapshotId implements Serializable {

        @Column(name = "player_id")
        private Long playerId;

        @Column(name = "skill_code", length = 10)
        private String skillCode;

        @Column(name = "iso_year")
        private Short isoYear;

        @Column(name = "iso_week")
        private Short isoWeek;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PlayerSluSnapshotId that)) return false;
            return Objects.equals(playerId, that.playerId)
                && Objects.equals(skillCode, that.skillCode)
                && Objects.equals(isoYear, that.isoYear)
                && Objects.equals(isoWeek, that.isoWeek);
        }

        @Override
        public int hashCode() {
            return Objects.hash(playerId, skillCode, isoYear, isoWeek);
        }
    }
}
