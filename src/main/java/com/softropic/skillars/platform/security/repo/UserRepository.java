package com.softropic.skillars.platform.security.repo;



import com.softropic.skillars.platform.security.contract.Consumer;
import com.softropic.skillars.platform.security.repo.User;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    void delete(final User user);

    Optional<Consumer> findCustomerById(Long id);

    Optional<Consumer> findCustomerByLogin(String login);

    @Query("update User  set locked = ?1 where login = ?2")
    @Modifying
    void changeAccountLockStatus(boolean locked, String username);

    @Query("update User  set otpEnabled = true where login = ?1")
    @Modifying
    void enableOtp(String username);

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
}
