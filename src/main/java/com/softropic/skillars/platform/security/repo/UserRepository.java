package com.softropic.skillars.platform.security.repo;



import com.softropic.skillars.platform.security.contract.Consumer;
import com.softropic.skillars.platform.security.repo.User;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA persistence for the User entity.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findOneByActivationKey(final String activationKey);

    @Query("select u from User u where activated = false and activationKey = ?1")
    Optional<User> findInactivatedByActivationKey(final String activationKey);

    /**
     * skillars-deferred-122 AC6: genuinely server-side paged, replacing a now-deleted
     * {@code findAllByActivatedIsFalseAndCreatedDateBefore(ZonedDateTime)} that took no
     * {@code Pageable} at all (every call materialized the entire expired-user set). AC9 layers the
     * {@code cleanupFailedAt IS NULL} predicate onto this same query rather than a second,
     * overlapping exclusion mechanism — a fixed-cost condition, unlike the in-run
     * {@code failedLogins} Java-side filter this story deliberately keeps unpaged/page-scoped (see
     * {@code UserAdminService.findExpiredUsers}).
     * <p>
     * skillars-deferred-122 implementation-time finding: {@code createdDate} is mapped as
     * {@link Instant} on {@code AbstractAuditingEntity} — the deleted method above (and this one, in
     * an earlier draft) took a {@code ZonedDateTime} cutoff parameter instead, which Hibernate 6's
     * strict parameter-type validation rejects outright ({@code QueryArgumentException: Argument
     * ... of type [java.time.ZonedDateTime] did not match parameter type [java.time.Instant]}) —
     * caught by {@code UserCleanupFailedMarkerIT} (a real-database IT; a Mockito-only test cannot
     * surface a JDBC/Hibernate type-binding mismatch). The deleted method carried the identical latent
     * defect and was never caught because nothing exercised it against a real database. {@code Instant}
     * is the correct parameter type here, matching the entity field it compares against.
     */
    List<User> findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc(
        Instant cutoffDate, Pageable pageable);

    Optional<User> findOneByResetKey(final String resetKey);

    Optional<User> findOneByEmail(final String email);

    //EntityGraphType.FETCH treats all unlisted attributes as LAZY, overriding the @ElementCollection(fetch = EAGER)
    //annotation on the addresses field. DO NOT add "addresses" to "attributePaths". I will lead to a cartesian product
    @EntityGraph(type = EntityGraph.EntityGraphType.FETCH, attributePaths = {"authorities"})
    Optional<User> findOneByLogin(final String login);

    //This should fetch addresses as well since "addresses" should be loaded eagerly
    Optional<User> findOneById(Long userId);

    Optional<User> findOneByEmailOrLogin(final String email, final String login);

    /**
     * Scalar id lookups for callers that must then take the row lock with {@link #findByIdForUpdate}:
     * loading the entity first would make that locked query return the already-managed, pre-lock
     * snapshot (see AdminUserService.suspendCoachProfileIfAny's note on the same hazard).
     */
    @Query("select u.id from User u where u.login = :login")
    Optional<Long> findIdByLogin(@Param("login") String login);

    @Query("select u.id from User u where u.email = :email")
    Optional<Long> findIdByEmail(@Param("email") String email);

    /**
     * The account behind a coach profile, for a video owner id (coach uploads store the
     * CoachProfile UUID as {@code Video.ownerId}). Scalar and native so the security module does not
     * load a marketplace entity.
     */
    @Query(value = "select cp.user_id from marketplace.coach_profiles cp where cp.id = :coachProfileId",
           nativeQuery = true)
    Optional<Long> findUserIdByCoachProfileId(@Param("coachProfileId") java.util.UUID coachProfileId);

    @Override
    void delete(final User user);

    Optional<Consumer> findCustomerById(Long id);

    Optional<Consumer> findCustomerByLogin(String login);

    @Query("update User  set locked = ?1 where login = ?2")
    @Modifying
    void changeAccountLockStatus(boolean locked, String username);

    /**
     * skillars-deferred-122 AC9: an explicit @Modifying @Query update, not a setter call against a
     * detached entity. {@code UserAdminService.removeNotActivatedUsers} runs with
     * {@code Propagation.NOT_SUPPORTED} — there is no ambient persistence context, so the {@code User}
     * objects {@link #findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc}
     * returns are detached the moment that call's own transaction closes. A plain
     * {@code user.setCleanupFailedAt(...)} on such a detached entity dirty-checks and persists
     * nothing.
     * <p>
     * {@code @Transactional} directly on this method (not inherited from a caller) is required, not
     * decorative: its own call site sits in {@code removeNotActivatedUsers}'s catch block, which runs
     * under that method's {@code NOT_SUPPORTED} propagation — there is no ambient transaction for an
     * {@code @Modifying} query to join, and Spring Data does not implicitly wrap a custom repository
     * query method in one. Without this annotation, the call throws
     * {@code TransactionRequiredException} at runtime.
     * <p>
     * skillars-deferred-122 code review 2026-09-18: now called only once
     * {@link #recordCleanupAttemptFailure} has recorded {@code CLEANUP_FAILURE_THRESHOLD} separate-run
     * failures for this login — see {@code UserAdminService.recordCleanupFailure}'s Javadoc. Kept as
     * its own explicit method (not folded into a single conditional update) so the permanent-exclusion
     * write stays a simple, auditable one-column set, independent of the attempt-count arithmetic.
     */
    @Query("update User set cleanupFailedAt = :failedAt where login = :login")
    @Modifying
    @Transactional
    void markCleanupFailed(@Param("login") String login, @Param("failedAt") Instant failedAt);

    /**
     * skillars-deferred-122 AC9 code review 2026-09-18: records one cleanup-delete failure attempt for
     * this login — the attempt-count replacement for the one-shot {@link #markCleanupFailed} marker.
     * Same detached-entity / explicit-{@code @Transactional} rationale as {@link #markCleanupFailed}
     * above applies identically here. {@code attempts} is computed by the caller (from the already-
     * loaded entity's current count + 1), not derived in SQL, so this stays a plain unconditional
     * column set rather than a JPQL {@code CASE WHEN} expression.
     */
    @Query("update User set cleanupFailedAttempts = :attempts, cleanupLastAttemptedAt = :attemptedAt, "
        + "cleanupLastError = :errorMessage where login = :login")
    @Modifying
    @Transactional
    void recordCleanupAttemptFailure(@Param("login") String login, @Param("attempts") int attempts,
        @Param("attemptedAt") Instant attemptedAt, @Param("errorMessage") String errorMessage);

    /**
     * skillars-deferred-149 AC3: flips {@code securitySessionInvalidatedAt} on a refresh-token-reuse
     * ("theft") detection. {@code REQUIRES_NEW}, mirroring {@code
     * RefreshTokenRepository.markAllUsedByUserId}/{@code markUsedByTokenHash} — NOT {@link
     * #changeAccountLockStatus}/{@link #markCleanupFailed}, which are the wrong precedent for this
     * call site. Both call sites ({@code AuthService.refresh()}'s two theft branches) call this and
     * then throw immediately; {@code AuthService} is its own outermost transaction boundary
     * ({@code @Transactional} class-level, {@code AuthResource} not transactional, {@code
     * spring.jpa.open-in-view=false}), so an ambient write here would be silently rolled back by the
     * very throw that follows it, and this AC would ship as a complete no-op.
     */
    @Query("update User set securitySessionInvalidatedAt = :invalidatedAt where id = :userId")
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void invalidateSessionsForUser(@Param("userId") Long userId, @Param("invalidatedAt") Instant invalidatedAt);

    /**
     * Admin console user search (feat/admin-moderation-and-enforcement D7). {@code pattern} is an
     * already lower-cased, LIKE-escaped ({@code !} escape char) {@code %term%} pattern — or plain
     * {@code "%"} to list everyone — matched against email, login and "first last" name. Newest first
     * (ids are time-ordered TSIDs).
     */
    @Query(value = "select u.id as id, u.login as login, u.email as email, u.firstName as firstName, "
        + "u.lastName as lastName, u.skillarsRole as skillarsRole, u.activated as activated, "
        + "u.locked as locked, u.verificationStatus as verificationStatus, u.createdDate as createdDate "
        + "from User u where lower(u.email) like :pattern escape '!' "
        + "or lower(u.login) like :pattern escape '!' "
        + "or lower(concat(u.firstName, ' ', u.lastName)) like :pattern escape '!' "
        + "order by u.id desc",
        countQuery = "select count(u) from User u where lower(u.email) like :pattern escape '!' "
        + "or lower(u.login) like :pattern escape '!' "
        + "or lower(concat(u.firstName, ' ', u.lastName)) like :pattern escape '!'")
    Page<UserAdminSummaryView> searchForAdmin(@Param("pattern") String pattern, Pageable pageable);

    /**
     * Locked load for the admin suspend/reactivate paths (D7), mirroring
     * {@code CoachProfileRepository.findByIdForUpdate}: PESSIMISTIC_WRITE with
     * {@code lock.timeout = 0} (NO_WAIT on Postgres), so contention fails fast with a
     * {@code PessimisticLockingFailureException} that callers wrap in {@code PessimisticLockRetryer}.
     * That JPQL + lock-timeout-hint shape matters: Hibernate reports the NO_WAIT failure as a lock
     * timeout, which does not mark the caller's transaction rollback-only, so the retryer can roll
     * back to its savepoint and try again (a native {@code FOR UPDATE NOWAIT} query failure does mark
     * it, turning every contended call into an {@code UnexpectedRollbackException} at commit). The
     * entity's EAGER {@code addresses} collection is loaded by a separate, unlocked select, not joined
     * into the locking statement. Must run inside the caller's transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    /**
     * Same write as {@link #invalidateSessionsForUser} but joining the caller's transaction
     * ({@code MANDATORY}) instead of opening a {@code REQUIRES_NEW} one. For the admin suspend path,
     * which already holds this user's row lock: a {@code REQUIRES_NEW} UPDATE of the same row would
     * wait on its own suspended outer transaction (self-deadlock). The value is a monotonic epoch and
     * {@code securitySessionInvalidatedAt} is {@code updatable = false} on the entity, so no stale
     * managed {@code save()} can undo it.
     */
    @Query("update User set securitySessionInvalidatedAt = :invalidatedAt where id = :userId")
    @Modifying(flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    int invalidateSessionsForUserInCurrentTransaction(@Param("userId") Long userId,
                                                      @Param("invalidatedAt") Instant invalidatedAt);
}
