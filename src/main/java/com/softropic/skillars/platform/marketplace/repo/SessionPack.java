package com.softropic.skillars.platform.marketplace.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(schema = "marketplace", name = "session_packs")
@Getter
@Setter
@NoArgsConstructor
public class SessionPack {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "coach_id", nullable = false)
    private UUID coachId;

    @Column(name = "session_count", nullable = false)
    private int sessionCount;

    @Column(name = "total_price", nullable = false)
    private BigDecimal totalPrice;

    private String label;
}
