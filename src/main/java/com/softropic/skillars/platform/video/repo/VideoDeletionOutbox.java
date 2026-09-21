package com.softropic.skillars.platform.video.repo;

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

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "video_deletion_outbox", schema = "main")
public class VideoDeletionOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "video_id", nullable = false)
    private UUID videoId;

    @Column(name = "bunny_video_id", length = 255)
    private String bunnyVideoId;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    /**
     * skillars-deferred-126 AC1: deliberately left app-clock-stamped (row insertion, backoff
     * computation) — unlike {@code claimedAt}, this fix does not make this column DB-time. A
     * skew-sensitive misfire here only shifts <em>when</em> a row becomes eligible for the next
     * attempt by the skew amount; it is not a double-processing hazard the way {@code claimedAt}'s
     * staleness comparison was, which is why that fix stayed scoped narrower than every timestamp on
     * this entity.
     */
    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "triggered_by", nullable = false, length = 32)
    private String triggeredBy;

    /**
     * skillars-deferred-123 AC3: stamped by {@code claimPendingBatch}, and cleared back to
     * {@code null} on every transition out of {@code CLAIMED} (completion or either failure
     * outcome). Lets {@code resetStaleClaimed} key staleness on how long a row has actually been
     * claimed rather than {@code nextRetryAt}'s eligibility time, and lets {@code findClaimedBatch}
     * scope its fetch to this run's own claim.
     *
     * <p><strong>skillars-deferred-126 AC1.</strong> The stamp itself is now the <em>database's</em>
     * own claim instant ({@code now()}), not the claiming JVM's own wall clock — matching
     * {@code ShedLockConfig}'s {@code usingDbTime()} choice, and for the identical reason: two
     * instances' app clocks can skew relative to each other, but every instance sees the same
     * database clock. {@code resetStaleClaimed}'s staleness comparison against this column is now
     * computed inside the SQL itself, so it is immune to that skew too. This does NOT extend to
     * {@code nextRetryAt} (see that field), which remains app-clock-stamped at every site that
     * writes it — only the {@code claimed_at} stamp and the staleness comparison against it were in
     * scope for this fix.
     */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    /**
     * skillars-deferred-124 AC4: the run-identity token, replacing {@code claimed_at}-exact-equality
     * for "which run owns this row right now." Stamped by {@code claimPendingBatch} with a fresh
     * {@code UUID.randomUUID()} generated once per tick, alongside (not instead of) {@code claimedAt}
     * — {@code claimedAt} keeps flowing to {@code resetStaleClaimed}'s time-based staleness check, a
     * genuinely different concern from run identity. {@code findClaimedBatch}, {@code releaseClaimed},
     * {@code completeClaimed} and {@code failClaimed} all key their identity predicate on this column
     * instead. Cleared back to {@code null} on every transition out of {@code CLAIMED} — completion or
     * either failure outcome, and {@code resetStaleClaimed} too — mirroring {@code claimedAt}'s own
     * identical invariant above; a stale {@code claimed_by} on a non-{@code CLAIMED} row is never read
     * as an identity match (every predicate is also gated on {@code status = 'CLAIMED'}), but leaving
     * it uncleared would defeat the column's forensic purpose.
     *
     * <p><strong>skillars-deferred-124 code review 2026-09-19 Decision, accepted risk.</strong>
     * Replacing {@code claimed_at}-equality outright (rather than adding {@code claimed_by} alongside
     * it as a second, additional predicate) opens a rolling-deploy lost-update window: a pre-this-story
     * instance running against the migrated schema does not know this column exists, so its own
     * claim/release/reset writes leave it stale, which a post-this-story instance's predicate could
     * then wrongly match. Accepted, not fixed, per {@code skillars-deferred-117}'s owner decision —
     * no production deploy of this application has ever happened. See
     * {@code docs/deployment/migration-conventions.md} rule 7's own sub-point for the expand/contract
     * step this needs before a first production deploy of this table changes that premise.
     */
    @Column(name = "claimed_by")
    private UUID claimedBy;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (nextRetryAt == null) nextRetryAt = now;
    }
}
