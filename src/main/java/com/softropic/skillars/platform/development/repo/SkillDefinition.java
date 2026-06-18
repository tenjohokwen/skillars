package com.softropic.skillars.platform.development.repo;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(schema = "development", name = "skill_definitions")
@Getter
@Setter
@NoArgsConstructor
public class SkillDefinition {

    @Id
    @Column(length = 10)
    private String code;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "display_order", nullable = false)
    private Short displayOrder;

    @Column(nullable = false)
    private Boolean active = true;
}
