package com.softropic.skillars.platform.session.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.util.UUID;

@Entity
@Table(schema = "session", name = "drill_video_refs")
@Getter
@Setter
@NoArgsConstructor
public class DrillVideoRef implements Persistable<UUID> {

    @Id
    @Column(name = "drill_id")
    private UUID drillId;

    @Column(name = "video_id")
    private UUID videoId;

    @Column(name = "ref_count", nullable = false)
    private int refCount = 1;

    @Transient
    @Setter(AccessLevel.NONE)
    private boolean isNew = true;

    @Override
    public UUID getId() {
        return drillId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        isNew = false;
    }
}
