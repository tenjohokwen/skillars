package com.softropic.skillars.platform.session.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(schema = "session", name = "drill_video_refs")
@Getter
@Setter
@NoArgsConstructor
public class DrillVideoRef {

    @Id
    @Column(name = "drill_id")
    private UUID drillId;

    @Column(name = "video_id")
    private UUID videoId;

    @Column(name = "ref_count", nullable = false)
    private int refCount = 1;
}
