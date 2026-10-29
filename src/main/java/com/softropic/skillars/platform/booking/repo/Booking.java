package com.softropic.skillars.platform.booking.repo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "booking", name = "bookings")
@Getter
@Setter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "parent_id", nullable = false, updatable = false)
    private Long parentId;

    @Column(name = "player_id", nullable = false, updatable = false)
    private Long playerId;

    @Column(name = "coach_id", nullable = false, updatable = false)
    private UUID coachId;

    @Column(name = "requested_start_time", nullable = false)
    private Instant requestedStartTime;

    @Column(name = "requested_end_time", nullable = false)
    private Instant requestedEndTime;

    // fix/booking-cancel-policy-loopholes: the start time the booking was confirmed with, captured
    // the first time a reschedule is accepted (null = never rescheduled; requestedStartTime is it).
    // The parent-cancel 24h refund test is anchored on the earlier of this and requestedStartTime, so
    // rescheduling a <24h booking further out cannot launder it into a full-refund cancel.
    @Column(name = "original_start_time")
    private Instant originalStartTime;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "canonical_timezone", nullable = false, length = 50)
    private String canonicalTimezone;

    @Column(length = 500)
    private String notes;

    // skillars-deferred-155 AC4: editable any time the booking is CONFIRMED/UPCOMING, surfaced on
    // the coach's Session Prep Card — deliberately NOT the same column as `notes` above, which is
    // write-once at creation and surfaced on a different coach-facing screen (the request queue).
    @Column(name = "player_request", length = 500)
    private String playerRequest;

    // skillars-12-6 AC1/AC4: the COACH's own private plan note for this session. @JsonIgnore is a
    // security control, not a nicety: GdprExportService hands this entity straight to Jackson for
    // PARENT/PLAYER/COACH exports, so without it a parent's export zip would carry the coach's
    // private note. Never add this field to BookingResponse either.
    @JsonIgnore
    @Column(name = "coach_plan_note", length = 500)
    private String coachPlanNote;

    @Version
    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "primary_reminder_sent_at")
    private Instant primaryReminderSentAt;

    @Column(name = "secondary_reminder_sent_at")
    private Instant secondaryReminderSentAt;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "session_pack_purchase_id", updatable = false)
    private UUID sessionPackPurchaseId;

    /**
     * fix/payment-agreed-price-lock (V192): the coach's per-session price when this (non-pack) request
     * was made — what accept/settle charges, so a coach re-pricing before accepting cannot change it.
     * NULL for pack bookings (the pack's locked price applies), rows written before V192, and requests
     * made while the coach had no pricing row; readers then fall back to the current coach_pricing row.
     */
    @Column(name = "agreed_price", precision = 10, scale = 2, updatable = false)
    private BigDecimal agreedPrice;

    /**
     * fix/booking-request-consent (V206): true for a booking the COACH created for the family ("repeat
     * next week"). It stays REQUESTED until the parent confirms it ({@code BookingService
     * .confirmProposedBooking} — accept + charge) or declines it; the coach cannot accept it
     * ({@code BookingService.acceptAndInitiatePayment} enforces both directions).
     */
    @Column(name = "proposed_by_coach", nullable = false, updatable = false)
    private boolean proposedByCoach;

    @Column(name = "cancel_reason", length = 50)
    private String cancelReason;

    // skillars-12-7 code review (Decision 2): set by BookingCompletionService.startSession. LIVE and QUICK
    // both pass through COMPLETED_PENDING_CONFIRMATION, so this is the only record of which mode an
    // abandoned wrap-up must be resumed in (null = quick-complete, or a row from before V167).
    @Column(name = "live_started_at")
    private Instant liveStartedAt;

    /**
     * fix/booking-integrity (B2, V178): when the booking entered its CURRENT status. Unlike
     * {@code updatedAt}, later writes that do not change the status (a recap edit, a reminder stamp,
     * GDPR note anonymisation, a plan note) leave it alone — so for a terminal status it is the moment
     * the booking settled, which is what the dispute window and review maturity are measured from.
     * Maintained by {@link #setStatus} only (no setter of its own); NULL on rows written before V178,
     * which is why readers use {@link #getStatusAnchor()}.
     */
    @Setter(lombok.AccessLevel.NONE)
    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    /**
     * Assigning a different status stamps {@link #statusChangedAt}; re-assigning the same value does
     * not. Hibernate uses field access here, so loading or refreshing a row never goes through this.
     */
    public void setStatus(String status) {
        if (!java.util.Objects.equals(this.status, status)) {
            this.statusChangedAt = Instant.now();
        }
        this.status = status;
    }

    /**
     * The instant the booking reached its current status — {@code statusChangedAt}, or
     * {@code updatedAt} for a row last transitioned before V178 added the column (the best available
     * approximation; the pre-V178 behaviour). {@code BookingRepository.findQualifyingCompletedBookings}
     * applies the same COALESCE in JPQL.
     */
    @JsonIgnore
    public Instant getStatusAnchor() {
        return statusChangedAt != null ? statusChangedAt : updatedAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = Instant.now();
        if (status == null) status = "REQUESTED";
        if (statusChangedAt == null) statusChangedAt = updatedAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * skillars-12-7 AC3: the pure half of "the coach may still add/correct this session's recap" --
     * status {@code COMPLETED} and {@code now} strictly before {@code requestedEndTime + windowDays}.
     * A null {@code requestedEndTime} is treated as CLOSED (unlike {@link #isPrepNoteEditable}, which
     * treats it as open): with no end there is no anchor to measure the window from. The erasure
     * checks live in {@code BookingCompletionService.recapEditability}, which both the read and the
     * write path call, so the two can never disagree.
     */
    public boolean isRecapEditable(Instant now, long windowDays) {
        return BookingStatus.COMPLETED.name().equals(status) && recapEditableUntil(windowDays) != null
            && now.isBefore(recapEditableUntil(windowDays));
    }

    /** End of the recap edit window, or null when there is no {@code requestedEndTime} to anchor on. */
    public Instant recapEditableUntil(long windowDays) {
        return requestedEndTime == null ? null : requestedEndTime.plus(java.time.Duration.ofDays(windowDays));
    }

    /**
     * skillars-12-6 AC2: single definition of "the coach may still write the plan note" — status
     * CONFIRMED/UPCOMING and {@code now} strictly before {@code requestedEndTime}. Called by both
     * {@code BookingService.setCoachPlanNote} (the write guard) and the development module's
     * {@code SessionPrepService} (the {@code coachPlanNoteEditable} flag) with the same
     * {@code ClockProvider}-derived instant, so the two can never disagree. {@code @JsonIgnore}d via
     * the method name not being a bean getter ("is" + non-boolean-returning would be, but this takes
     * an argument, so Jackson ignores it).
     */
    public boolean isPrepNoteEditable(Instant now) {
        boolean statusOk = BookingStatus.CONFIRMED.name().equals(status)
            || BookingStatus.UPCOMING.name().equals(status);
        return statusOk && (requestedEndTime == null || now.isBefore(requestedEndTime));
    }
}
