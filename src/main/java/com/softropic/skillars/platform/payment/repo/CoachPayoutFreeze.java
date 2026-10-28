package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * feat/coach-payouts-pack-and-credit (V186, DECISIONS.md item 2): a coach whose payouts are frozen
 * because of a CSAM safety hold. Active while {@link #liftedAt} is null; an admin reinstatement lifts
 * it. One row per coach — a later hold after a lift re-activates the same row. Written only through
 * {@link CoachPayoutFreezeRepository}'s statements ({@code CoachPayoutFreezeService}).
 */
@Entity
@Table(schema = "payment", name = "coach_payout_freezes")
@Getter
@Setter
@NoArgsConstructor
public class CoachPayoutFreeze {

    @Id
    @Column(name = "coach_id", nullable = false, updatable = false)
    private UUID coachId;

    @Column(name = "reason", nullable = false, length = 200)
    private String reason;

    @Column(name = "frozen_at", nullable = false)
    private Instant frozenAt;

    @Column(name = "lifted_at")
    private Instant liftedAt;

    @Column(name = "lifted_by")
    private Long liftedBy;
}
