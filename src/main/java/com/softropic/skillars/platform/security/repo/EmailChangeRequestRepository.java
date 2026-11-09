package com.softropic.skillars.platform.security.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EmailChangeRequestRepository extends JpaRepository<EmailChangeRequest, Long> {

    Optional<EmailChangeRequest> findByConfirmTokenHash(String confirmTokenHash);

    Optional<EmailChangeRequest> findByUndoTokenHash(String undoTokenHash);

    /**
     * Ends the user's open (unconfirmed) request, if any: its confirm link stops working. Returns the rows
     * touched (0 or 1, see V232's {@code ux_ecr_one_open_per_user}). Does not clear the persistence context:
     * the password change and reset call it with their managed {@code User} still to be written.
     */
    @Modifying(flushAutomatically = true)
    @Query("update EmailChangeRequest r set r.supersededAt = :now, r.version = r.version + 1 "
        + "where r.userId = :userId and r.confirmedAt is null and r.supersededAt is null")
    int supersedeOpen(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * The end of the user's open undo window (a confirmed, not undone change whose undo link still works), the
     * latest if several. Drives R1 (one change per window) and R3 (erasure hold).
     */
    @Query("select max(r.undoExpiresAt) from EmailChangeRequest r where r.userId = :userId "
        + "and r.confirmedAt is not null and r.undoneAt is null and r.undoExpiresAt > :now")
    Optional<Instant> findOpenUndoWindowEnd(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * Confirmed, not undone changes whose undo window is still open and whose old address is {@code address}
     * (lowercased by the caller): the address is reserved for that request's user.
     */
    @Query("select r from EmailChangeRequest r where lower(r.oldEmail) = :address "
        + "and r.confirmedAt is not null and r.undoneAt is null and r.undoExpiresAt > :now")
    List<EmailChangeRequest> findReservations(@Param("address") String address, @Param("now") Instant now);

    /** Kills every undo link of the user except {@code keepId}'s: their windows end now (R1, at undo). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EmailChangeRequest r set r.undoExpiresAt = :now, r.version = r.version + 1 "
        + "where r.userId = :userId and r.id <> :keepId and r.undoneAt is null and r.undoExpiresAt > :now")
    int expireOtherUndoLinks(@Param("userId") Long userId, @Param("keepId") Long keepId,
                             @Param("now") Instant now);

    /**
     * Confirms an open request atomically: 1 if this call confirmed it, 0 if it was no longer open (a second
     * click, a concurrent supersede, or expiry). The conditional UPDATE is the double-click guard.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EmailChangeRequest r set r.confirmedAt = :now, r.undoTokenHash = :undoTokenHash, "
        + "r.undoExpiresAt = :undoExpiresAt, r.version = r.version + 1 "
        + "where r.id = :id and r.confirmedAt is null and r.supersededAt is null and r.confirmExpiresAt > :now")
    int claimConfirm(@Param("id") Long id, @Param("undoTokenHash") String undoTokenHash,
                     @Param("undoExpiresAt") Instant undoExpiresAt, @Param("now") Instant now);

    /** Undoes a confirmed change atomically: 1 if this call undid it, 0 if it was undone or expired meanwhile. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EmailChangeRequest r set r.undoneAt = :now, r.version = r.version + 1 "
        + "where r.id = :id and r.undoneAt is null and r.undoExpiresAt > :now")
    int claimUndo(@Param("id") Long id, @Param("now") Instant now);

    /** Ends one open request (a confirm refused at use time). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EmailChangeRequest r set r.supersededAt = :now, r.version = r.version + 1 "
        + "where r.id = :id and r.confirmedAt is null and r.supersededAt is null")
    int supersede(@Param("id") Long id, @Param("now") Instant now);

    /**
     * GDPR erasure (R2): the rows hold the person's real addresses and keep one reserved. Does not clear the
     * persistence context: the erasure transaction keeps writing its managed entities afterwards.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from EmailChangeRequest r where r.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
