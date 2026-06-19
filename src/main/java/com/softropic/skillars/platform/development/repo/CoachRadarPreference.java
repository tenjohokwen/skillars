package com.softropic.skillars.platform.development.repo;

import io.hypersistence.utils.hibernate.type.array.ListArrayType;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(schema = "development", name = "coach_radar_preferences")
@Getter
@Setter
@NoArgsConstructor
public class CoachRadarPreference {

    @EmbeddedId
    private CoachRadarPreferenceId id;

    @Type(ListArrayType.class)
    @Column(name = "selected_skills", columnDefinition = "varchar[]", nullable = false)
    private List<String> selectedSkills = new ArrayList<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
