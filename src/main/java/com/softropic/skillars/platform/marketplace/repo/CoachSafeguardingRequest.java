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
 * S-09 (V223): a coach's request to have their safeguarding certificate inspected, i.e. the staff queue. One row
 * per coach. {@link #STATUS_PENDING} until staff decide; {@link #STATUS_REJECTED} is kept only so the coach sees
 * the outcome, and is replaced by their next request or removed by withdraw / erasure. An accepted inspection
 * deletes the row (the badge lives in {@link CoachSafeguardingCheck}).
 *
 * <p>The optional certificate upload is held application-encrypted under {@link #uploadKey} in the private
 * bucket and deleted when staff decide, when the request is replaced or withdrawn, on erasure, and by the
 * sweeper after {@code coach.safeguarding.uploadRetentionDays}. {@code uploadContentType} is the sniffed type of
 * the plaintext; no file name is kept (it could carry the coach's name).
 */
@Entity
@Table(schema = "marketplace", name = "coach_safeguarding_requests")
@Getter
@Setter
@NoArgsConstructor
public class CoachSafeguardingRequest {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_REJECTED = "REJECTED";

    @Id
    @Column(name = "coach_id")
    private UUID coachId;

    @Column(nullable = false)
    private String status;

    @Column(name = "check_type", nullable = false)
    private String checkType;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "upload_key")
    private String uploadKey;

    @Column(name = "upload_stored_at")
    private Instant uploadStoredAt;

    @Column(name = "upload_content_type")
    private String uploadContentType;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Version
    @Column(nullable = false)
    private long version;

    public boolean isPending() {
        return STATUS_PENDING.equals(status);
    }

    public boolean hasUpload() {
        return uploadKey != null;
    }
}
