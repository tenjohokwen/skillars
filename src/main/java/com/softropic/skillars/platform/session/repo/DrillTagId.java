package com.softropic.skillars.platform.session.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class DrillTagId implements Serializable {

    @Column(name = "drill_id")
    private UUID drillId;

    @Column(name = "tag")
    private String tag;

    @Column(name = "coach_id")
    private UUID coachId;

    public DrillTagId() {}

    public DrillTagId(UUID drillId, String tag, UUID coachId) {
        this.drillId = drillId;
        this.tag = tag;
        this.coachId = coachId;
    }

    public UUID getDrillId() { return drillId; }
    public String getTag() { return tag; }
    public UUID getCoachId() { return coachId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DrillTagId that)) return false;
        return Objects.equals(drillId, that.drillId)
            && Objects.equals(tag, that.tag)
            && Objects.equals(coachId, that.coachId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(drillId, tag, coachId);
    }
}
