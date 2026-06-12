package com.softropic.skillars.platform.marketplace.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(schema = "marketplace", name = "coach_pricing")
@Getter
@Setter
@NoArgsConstructor
public class CoachPricing {

    @Id
    @Column(name = "coach_id")
    private UUID coachId;

    @Column(name = "per_session_price", nullable = false)
    private BigDecimal perSessionPrice;

    @Column(nullable = false)
    private String currency = "EUR";
}
