package com.softropic.skillars.platform.marketplace.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * S-09 (V223): the record behind a coach's public "safeguarding check on record" badge. Written only by a staff
 * inspection ({@code CoachSafeguardingService.markInspected}); a coach can never set it. Data minimisation
 * (GDPR Art. 10): this is the WHOLE record — who, when, until when, by which staff user and a generic type
 * code. Nothing from the certificate itself is stored here or anywhere else once the upload is deleted.
 *
 * <p>The badge is "valid" iff {@code expiresAt} is in the future; expiry is a read-time comparison, so nothing
 * has to run for a badge to lapse.
 */
@Entity
@Table(schema = "marketplace", name = "coach_safeguarding_checks")
@Getter
@Setter
@NoArgsConstructor
public class CoachSafeguardingCheck {

    @Id
    @Column(name = "coach_id")
    private UUID coachId;

    /** Generic jurisdiction/type code, e.g. {@code DE_EFZ} (erweitertes Fuehrungszeugnis). */
    @Column(name = "check_type", nullable = false)
    private String checkType;

    @Column(name = "inspected_at", nullable = false)
    private Instant inspectedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** {@code main."user"} id of the staff member who inspected. Never exposed on a public DTO. */
    @Column(name = "inspected_by", nullable = false)
    private Long inspectedBy;

    /** When the ~30-day expiry reminder went out; reset by a renewal. */
    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    @Version
    @Column(nullable = false)
    private long version;
}
