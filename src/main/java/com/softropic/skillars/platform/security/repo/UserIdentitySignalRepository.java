package com.softropic.skillars.platform.security.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserIdentitySignalRepository extends JpaRepository<UserIdentitySignal, Long> {

    /**
     * Records that {@code userId} was seen with this hashed identifier. One row per (user, kind, hash); a repeat
     * sighting only refreshes {@code last_seen}, and at most once a day so a busy user does not rewrite the row on
     * every login.
     */
    @Modifying
    @Query(value = """
        INSERT INTO main.user_identity_signals (user_id, kind, value_hash)
        VALUES (:userId, :kind, :valueHash)
        ON CONFLICT (user_id, kind, value_hash)
        DO UPDATE SET last_seen = now() WHERE main.user_identity_signals.last_seen < now() - interval '1 day'
        """, nativeQuery = true)
    int upsert(@Param("userId") Long userId, @Param("kind") String kind, @Param("valueHash") String valueHash);

    /**
     * The kinds on which the two users share at least one identical hashed identifier. Served by
     * {@code idx_uis_kind_hash} and the (user_id, kind, value_hash) unique index.
     */
    @Query(value = """
        SELECT DISTINCT a.kind
        FROM main.user_identity_signals a
        JOIN main.user_identity_signals b ON b.kind = a.kind AND b.value_hash = a.value_hash
        WHERE a.user_id = :userA AND b.user_id = :userB
        ORDER BY a.kind
        """, nativeQuery = true)
    List<String> findSharedKinds(@Param("userA") Long userA, @Param("userB") Long userB);

    /** GDPR user erasure: every identifier recorded for the user. */
    @Modifying
    @Query("DELETE FROM UserIdentitySignal s WHERE s.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
