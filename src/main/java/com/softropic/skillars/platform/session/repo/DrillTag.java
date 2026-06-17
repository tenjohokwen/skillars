package com.softropic.skillars.platform.session.repo;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(schema = "session", name = "drill_tags")
@Getter
@Setter
@NoArgsConstructor
public class DrillTag {

    @EmbeddedId
    private DrillTagId id;

    private Instant createdAt;

    public DrillTag(DrillTagId id) {
        this.id = id;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
