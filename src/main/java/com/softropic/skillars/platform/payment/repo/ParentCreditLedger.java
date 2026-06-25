package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "payment", name = "parent_credit_ledger")
@Getter
@NoArgsConstructor
public class ParentCreditLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "tx_id", updatable = false, nullable = false)
    private UUID txId;

    @Setter
    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    @Setter
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Setter
    @Column(nullable = false, length = 32)
    private String type;

    @Setter
    @Column(name = "reference_id")
    private UUID referenceId;

    @Setter
    @Column(length = 500)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
