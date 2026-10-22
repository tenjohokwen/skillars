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

    @Column(name = "cancel_reason", length = 50)
    private String cancelReason;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = Instant.now();
        if (status == null) status = "REQUESTED";
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
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
