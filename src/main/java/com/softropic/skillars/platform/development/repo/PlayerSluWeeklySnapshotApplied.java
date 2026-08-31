package com.softropic.skillars.platform.development.repo;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

// IMMUTABLE: append-only — a row here means "this session's SLU delta for this weekly bucket is
// already applied to development.player_slu_weekly_snapshot". Never update or delete rows except for
// GDPR Article 17 erasure (PlayerSluWeeklySnapshotAppliedRepository.deleteAllByPlayerId).
@Entity
@Table(schema = "development", name = "player_slu_weekly_snapshot_applied")
@Getter
@Setter
@NoArgsConstructor
public class PlayerSluWeeklySnapshotApplied {

    @EmbeddedId
    private PlayerSluWeeklySnapshotAppliedId id;

    @Column(name = "applied_at", nullable = false, insertable = true, updatable = false)
    private Instant appliedAt;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    public static class PlayerSluWeeklySnapshotAppliedId implements Serializable {

        @Column(name = "session_id")
        private UUID sessionId;

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
            if (!(o instanceof PlayerSluWeeklySnapshotAppliedId that)) return false;
            return Objects.equals(sessionId, that.sessionId)
                && Objects.equals(playerId, that.playerId)
                && Objects.equals(skillCode, that.skillCode)
                && Objects.equals(isoYear, that.isoYear)
                && Objects.equals(isoWeek, that.isoWeek);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sessionId, playerId, skillCode, isoYear, isoWeek);
        }
    }
}
