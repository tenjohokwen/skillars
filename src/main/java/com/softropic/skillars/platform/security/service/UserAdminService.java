package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.contract.SecurityProperties;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static net.logstash.logback.argument.StructuredArguments.kv;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.micrometer.core.annotation.Timed;
import io.micrometer.observation.annotation.Observed;

/**
 * Service for user administration operations.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class UserAdminService {

    /**
     * skillars-deferred-120 AC3 Finding 1: safety stop so {@link #removeNotActivatedUsers}'s
     * {@code while (hasMore)} loop cannot spin forever. {@link #findExpiredUsers} returns the same
     * batch-sized set on every call as long as the underlying rows are unchanged, and per-user
     * {@link #deleteUserInTransaction} failures are caught and swallowed to keep the sweep going —
     * so if every user in a batch fails to delete for a deterministic reason (an uncovered FK, a
     * trigger, a constraint), the identical batch would be returned and reprocessed forever without
     * this cap. Mirrors {@code SluSnapshotAppliedRetentionService}'s existing
     * {@code MAX_BATCHES_PER_RUN} precedent for the same purpose.
     */
    private static final int MAX_BATCHES_PER_RUN = 100;

    private final UserRepository userRepository;
    private final SecurityProperties securityProperties;

    /**
     * Deletes a user by login.
     * Requires ADMIN, LTD_ADMIN, or USER role.
     *
     * @param login the user login to delete
     */
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    public void deleteUserInformation(final String login) {
        userRepository.findOneByLogin(login).ifPresent(u -> {
            userRepository.delete(u);
        });
    }

    /**
     * Locks a user account.
     * Requires ADMIN or LTD_ADMIN role.
     * Delegates to User domain entity for locking logic.
     *
     * @param login the user login to lock
     * @return the locked user if found, empty otherwise
     */
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public Optional<User> lockUserAccount(String login) {
        return userRepository.findOneByLogin(login)
                .map(u -> {
                    u.lock(); // Use domain method
                    return u;
                });
    }

    /**
     * Removes users that have not been activated after the configured expiration period.
     * This is a scheduled task that runs daily at 01:00 AM.
     * <p>
     * Fixed transaction boundaries (Phase 4.2):
     * 1. Uses pagination to limit fetched amount (configurable batch size)
     * 2. Each deletion happens in its own transaction (reduces lock time)
     * 3. Processes in batches to avoid memory issues with large datasets
     * 4. Continues processing until no more expired users are found
     * </p>
     * <p>
     * skillars-deferred-120 AC3 Finding 1 sizing basis: {@link #MAX_BATCHES_PER_RUN} (100) caps the
     * previously open-ended loop so {@code lockAtMostFor} has a real, bounded worst case to be sized
     * from rather than an open-ended "generous" guess. At the configured default batch size (100,
     * {@link SecurityProperties#getUserCleanupBatchSize()}), 100 batches is a defensible safety
     * ceiling (10,000 delete attempts) — {@link #deleteUserInTransaction} is a DB-only delete with no
     * external I/O (see that method's own Javadoc for what actually makes each delete transactional);
     * even a pessimistic 200ms/attempt (covering a constraint-violation rollback, the pathological
     * path this cap exists to bound) gives 10,000 &times; 0.2s = 2,000s (~33 min). {@code PT1H} gives
     * real margin above that. {@code lockAtLeastFor} is deliberately NOT the {@code PT2M} used by the
     * 5-minute-{@code fixedDelay} siblings — this job runs once daily (cron), so — mirroring
     * {@code MessageRetentionScheduler}'s identical daily-cron reasoning — {@code PT1M} is sized
     * purely as a defensive floor against a pathological fast-fail-and-immediately-refire edge case,
     * not to protect a tight fixed-delay window.
     * </p>
     * <p>
     * skillars-deferred-120 AC3 Finding 1/2: stacking {@code @SchedulerLock} with the existing
     * {@code @Transactional(propagation = NOT_SUPPORTED)} is safe here (unlike the booking
     * schedulers this codebase moved away from that combination for) — this method has no
     * batch-wide shared transaction for one item's exception to roll back; each delete is already
     * isolated from the others via {@link #deleteUserInTransaction} (see that method's Javadoc for
     * the actual isolation mechanism, corrected by code review 2026-09-17 Patch #1).
     * </p>
     * <p>
     * skillars-deferred-120 code review (2026-09-17, Decision 3): {@link #findExpiredUsers} always
     * re-runs the same unpaged, unfiltered query (a separate, deferred inefficiency —
     * see {@code deferred-work.md}), so without {@code failedLogins} below, one deterministically
     * undeletable user occupying a batch slot would occupy the <em>same</em> slot on every
     * subsequent iteration, permanently starving every deletable user behind it once the
     * undeletable set reaches {@code batchSize} — {@link #MAX_BATCHES_PER_RUN} would then stop the
     * spin without ever restoring progress. {@code failedLogins} is excluded from each batch's
     * selection after a login's first failure in this run, so deletable users behind a stuck one
     * still get processed in the same run; a login that keeps failing is simply retried fresh on
     * tomorrow's run (this set is not persisted).
     * </p>
     */
    @Scheduled(cron = "0 0 1 * * ?")
    @Timed
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @SchedulerLock(name = "UserAdminService_removeNotActivatedUsers",
                   lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void removeNotActivatedUsers() {
        ZonedDateTime cutoffDate = ZonedDateTime.now()
                .minusDays(securityProperties.getAccountActivationExpirationDays());
        int batchSize = securityProperties.getUserCleanupBatchSize();
        int totalDeleted = 0;
        Set<String> failedLogins = new HashSet<>();

        boolean hasMore = true;
        int batches = 0;
        while (hasMore && batches < MAX_BATCHES_PER_RUN) {
            batches++;
            List<User> users = findExpiredUsers(cutoffDate, batchSize, failedLogins);

            if (users.isEmpty()) {
                hasMore = false;
            } else {
                for (User user : users) {
                    try {
                        deleteUserInTransaction(user.getLogin());
                        totalDeleted++;
                    } catch (Exception e) {
                        failedLogins.add(user.getLogin());
                        log.error("Failed to delete non-activated user",
                            kv("operation", "user_admin"),
                            kv("action", "delete_inactive"),
                            kv("status", "ERROR"),
                            e);
                        // Continue processing other users even if one fails
                    }
                }
            }
        }

        // skillars-deferred-120 code review (2026-09-17, Patch #2): `hasMore` alone is not proof a
        // backlog remains — a run that drains exactly on the MAX_BATCHES_PER_RUN-th batch (e.g.
        // exactly batchSize * MAX_BATCHES_PER_RUN eligible users, no failures) exits this loop with
        // `hasMore` still `true` even though nothing is left, because the empty-result check that
        // would have cleared it never got a chance to run. One extra read-only query — cheap, and
        // only paid when the cap was actually reached — distinguishes a real stuck backlog from that
        // coincidence before alerting an operator about one that does not exist.
        if (batches >= MAX_BATCHES_PER_RUN
                && !findExpiredUsers(cutoffDate, batchSize, failedLogins).isEmpty()) {
            // skillars-deferred-120 AC3 Finding 1: hit the safety cap without draining the backlog —
            // most likely a deterministic per-user delete failure re-returning the same batch every
            // iteration. The remaining backlog is picked up by tomorrow's run; this only prevents an
            // unbounded spin today.
            log.error("Stopped non-activated-user cleanup after {} batches ({} deleted) — hit the "
                + "MAX_BATCHES_PER_RUN safety cap; remaining backlog will be retried on the next "
                + "scheduled run",
                batches, totalDeleted,
                kv("operation", "user_admin"),
                kv("action", "delete_inactive"),
                kv("status", "CAPPED"));
        }
    }

    /**
     * Finds expired non-activated users, excluding any login that has already failed deletion once
     * in this run (see {@link #removeNotActivatedUsers}'s Decision-3 note — without this exclusion, a
     * single deterministically-undeletable user would occupy every batch and starve deletable users
     * behind it, since the underlying query is unpaged and re-scans the same full expired set on
     * every call).
     */
    @Transactional(readOnly = true)
    protected List<User> findExpiredUsers(ZonedDateTime cutoffDate, int batchSize, Set<String> excludeLogins) {
        Pageable pageable = PageRequest.of(0, batchSize);
        return userRepository.findAllByActivatedIsFalseAndCreatedDateBefore(cutoffDate)
                .stream()
                .filter(u -> !excludeLogins.contains(u.getLogin()))
                .limit(batchSize)
                .toList();
    }

    /**
     * Deletes a user, re-checking {@code !activated} on a fresh re-fetch immediately before
     * deleting.
     * <p>
     * skillars-deferred-120 code review (2026-09-17, Patch #1): the {@code @Transactional(propagation
     * = REQUIRES_NEW)} below does <strong>not</strong> apply — this method is {@code protected} and
     * reached by a plain self-call from {@link #removeNotActivatedUsers} (no {@code self}-proxy
     * pattern, unlike {@code VideoSubscriptionLifecycleListener}), so Spring's AOP proxy is bypassed
     * and the annotation is inert. What actually isolates each delete is that
     * {@code userRepository.findOneByLogin}/{@code delete} each go through the
     * {@code UserRepository} proxy, whose {@code SimpleJpaRepository} superclass carries its own
     * class-level {@code @Transactional} — each repository call still gets its own transaction, just
     * not for the reason this method's own annotation implies. Left in place (harmless, and matches
     * the method's real isolation semantics even though it does nothing) rather than removed, since
     * a bare {@code protected void} with no annotation would look like an oversight to the next
     * reader. Do not rely on this annotation's propagation semantics for anything beyond what
     * {@code SimpleJpaRepository}'s own transactionality already provides.
     * </p>
     * <p>
     * skillars-deferred-118 AC2: re-checks {@code !activated} on the fresh re-fetch before deleting.
     * {@link #findExpiredUsers} reads a batch snapshot; a user can complete email verification (any
     * of {@code ParentRegistrationService}/{@code PlayerRegistrationService}/
     * {@code CoachRegistrationService}/{@code UserRegistrationService}) in the window between that
     * batch select and this specific user's turn in the delete loop. Without this guard, the
     * re-fetch here reads the current, now-activated row and deletes it anyway — a legitimate,
     * freshly-activated account destroyed by a stale batch read. The guard must live here, not in
     * {@link #findExpiredUsers}: the race window is between the batch select and each individual
     * delete call, not before the batch read, and this method already re-fetches a live row for
     * exactly this reason.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void deleteUserInTransaction(String login) {
        userRepository.findOneByLogin(login).ifPresent(user -> {
            if (!user.isActivated()) {
                userRepository.delete(user);
            } else {
                log.debug("Skipping non-activated-user cleanup — user activated since the sweep's "
                    + "batch select: login={}", login);
            }
        });
    }
}
