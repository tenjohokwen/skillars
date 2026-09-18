package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.contract.SecurityProperties;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;

import static net.logstash.logback.argument.StructuredArguments.kv;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
     * <p>
     * skillars-deferred-122 AC7: a fixed <em>total-attempts</em> ceiling, not a fixed
     * <em>batch-count</em> ceiling — {@code MAX_BATCHES_PER_RUN = 100} used to be multiplied by
     * {@link SecurityProperties#getUserCleanupBatchSize()} (default 100) to form the real 10,000
     * worst-case delete-attempt bound this class's own arithmetic below is sized from, but a smaller
     * configured batch size silently shrank that bound too (e.g. batch size 10 → only 1,000 attempts
     * per run). {@code removeNotActivatedUsers} now derives its own {@code maxBatches} from this
     * constant divided by the configured batch size each run, keeping the 10,000-attempt ceiling
     * invariant to whatever batch size an operator configures. A configured batch size above this
     * constant yields {@code maxBatches == 1} (best-effort above that size — an operator setting a
     * batch size that large should size it deliberately).
     */
    private static final int MAX_DELETE_ATTEMPTS_PER_RUN = 10_000;

    /**
     * skillars-deferred-122 AC9 code review 2026-09-18: see {@link #recordCleanupFailure}'s Javadoc
     * for the full rationale — the number of separate scheduled-run failures after which a user is
     * permanently excluded via the persisted {@code cleanup_failed_at} marker. 3 mirrors this
     * codebase's other bounded-retry defaults being in the low single digits (e.g.
     * {@code PessimisticLockRetryer}'s attempt count) — enough to absorb an isolated transient failure
     * (a lock timeout, a deadlock, a connection reset spanning one unlucky run) without either
     * excluding a recoverable user on one bad night or leaving a truly undeletable user occupying
     * sweep capacity indefinitely.
     */
    private static final int CLEANUP_FAILURE_THRESHOLD = 3;

    /** Defensive cap on the persisted {@code cleanup_last_error} text — an exception message should
     * never be unbounded, and this is an operator-diagnostic field, not a full stack trace. */
    private static final int MAX_CLEANUP_ERROR_MESSAGE_LENGTH = 2000;

    private final UserRepository userRepository;
    private final SecurityProperties securityProperties;

    /**
     * skillars-deferred-122 AC8: self-reference so {@link #deleteUserInTransaction}'s
     * {@code REQUIRES_NEW} is applied via the Spring AOP proxy, mirroring
     * {@code VideoSubscriptionLifecycleListener.self} exactly. Field-injected (not a constructor
     * parameter) since this class's other two dependencies are {@code final}-injected via
     * {@code @RequiredArgsConstructor}, and a constructor-injected self-reference would create an
     * unsatisfiable circular-dependency-at-construction-time requirement.
     */
    @Autowired @Lazy
    private UserAdminService self;

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
     * undeletable set reaches {@code batchSize} — {@link #MAX_DELETE_ATTEMPTS_PER_RUN} would then
     * stop the spin without ever restoring progress. {@code failedLogins} is excluded from each
     * batch's selection after a login's first failure in this run, so deletable users behind a stuck
     * one still get processed in the same run; a login that keeps failing this run is retried fresh
     * next run unless skillars-deferred-122 AC9's persisted {@code cleanup_failed_at} marker (stamped
     * below) has since excluded it — see {@link #findExpiredUsers}.
     * </p>
     * <p>
     * skillars-deferred-122 AC7: {@code batches < maxBatches} below, not the old fixed
     * {@code MAX_BATCHES_PER_RUN} — see {@link #MAX_DELETE_ATTEMPTS_PER_RUN}'s Javadoc for why the
     * batch-count ceiling is now derived from the configured batch size each run instead of hardcoded.
     * </p>
     */
    @Scheduled(cron = "0 0 1 * * ?")
    @Timed
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @SchedulerLock(name = "UserAdminService_removeNotActivatedUsers",
                   lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void removeNotActivatedUsers() {
        // skillars-deferred-122 implementation-time finding: Instant, not ZonedDateTime — User's
        // createdDate is Instant-typed (AbstractAuditingEntity), and Hibernate 6's strict
        // parameter-type validation rejects binding a ZonedDateTime against it (see
        // UserRepository.findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc's
        // own Javadoc for the full story — a real, pre-existing latent bug this story's own IT
        // coverage surfaced and fixed).
        Instant cutoffDate = Instant.now().minus(
            securityProperties.getAccountActivationExpirationDays(), ChronoUnit.DAYS);
        int batchSize = securityProperties.getUserCleanupBatchSize();
        // skillars-deferred-122 AC7 / code review 2026-09-18: the real fix for an unvalidated
        // 0/negative configured batch size is SecurityProperties.userCleanupBatchSize's @Min(1) +
        // @Validated, which now fails application startup before this method can ever run with a bad
        // value. This Math.max(1, ...) stays as a cheap defensive backstop for any SecurityProperties
        // instance constructed outside Spring's validated binding (e.g. `new SecurityProperties()` in
        // a unit test) — it does not, by itself, make batchSize/PageRequest.of below safe; only the
        // @Min(1) binding validation does that for the real, Spring-managed instance production uses.
        int effectiveBatchSize = Math.max(1, batchSize);
        int maxBatches = Math.max(1, MAX_DELETE_ATTEMPTS_PER_RUN / effectiveBatchSize);
        int totalDeleted = 0;
        Set<String> failedLogins = new HashSet<>();

        boolean hasMore = true;
        int batches = 0;
        // skillars-deferred-122 code review 2026-09-18 (Patch: silent early exit): true once this run
        // stops with a genuine, unprocessed backlog still in the DB — either the maxBatches cap was
        // hit, or every row on some page had already failed earlier this run (see below). Read once
        // more, defensively, for the ERROR log below — do not infer "backlog remains" purely from
        // `batches >= maxBatches`, which the "stuck page" exit path below can reach without ever
        // being true.
        boolean backlogRemains = false;
        while (hasMore && batches < maxBatches) {
            batches++;
            // skillars-deferred-122 code review 2026-09-18: the RAW page (before the in-run
            // failedLogins filter) decides whether a backlog remains — see findExpiredUsers's Javadoc
            // for why the filtered view cannot be used for that decision.
            List<User> rawPage = fetchExpiredUsersPage(cutoffDate, batchSize);
            if (rawPage.isEmpty()) {
                hasMore = false;
                continue;
            }
            List<User> users = rawPage.stream().filter(u -> !failedLogins.contains(u.getLogin())).toList();
            if (users.isEmpty()) {
                // Every row on this page already failed earlier in this run, and none of them has
                // yet crossed AC9's cross-run failure threshold (below), so the DB-level
                // cleanupFailedAt IS NULL predicate still returns the identical page every time —
                // PageRequest.of(0, batchSize) never advances past page 0. Further iterations cannot
                // make progress this run; stop now and report the real backlog instead of silently
                // exiting with `hasMore = false` the way an empty *filtered* list once did (code
                // review 2026-09-18 — that coincidence read as "backlog drained" when it was not).
                hasMore = false;
                backlogRemains = true;
                continue;
            }
            for (User user : users) {
                try {
                    self.deleteUserInTransaction(user.getLogin());
                    totalDeleted++;
                } catch (Exception e) {
                    failedLogins.add(user.getLogin());
                    log.error("Failed to delete non-activated user",
                        kv("operation", "user_admin"),
                        kv("action", "delete_inactive"),
                        kv("status", "ERROR"),
                        e);
                    recordCleanupFailure(user, e);
                    // Continue processing other users even if one fails
                }
            }
        }

        // skillars-deferred-120 code review (2026-09-17, Patch #2): `hasMore` alone is not proof a
        // backlog remains — a run that drains exactly on the maxBatches-th batch (e.g. exactly
        // batchSize * maxBatches eligible users, no failures) exits this loop with `hasMore` still
        // `true` even though nothing is left, because the empty-result check that would have cleared
        // it never got a chance to run. One extra read-only query — cheap, and only paid when the cap
        // was actually reached — distinguishes a real stuck backlog from that coincidence before
        // alerting an operator about one that does not exist.
        //
        // skillars-deferred-122 code review 2026-09-18: this re-check must ignore failedLogins (the
        // raw page, not findExpiredUsers's filtered view) — every login already in failedLogins by
        // now would otherwise filter the very backlog this check exists to detect back out to empty,
        // hiding it exactly like the loop-exit bug above.
        if (batches >= maxBatches && !backlogRemains) {
            backlogRemains = !fetchExpiredUsersPage(cutoffDate, batchSize).isEmpty();
        }
        if (backlogRemains) {
            // skillars-deferred-120 AC3 Finding 1: hit the safety cap without draining the backlog —
            // most likely a deterministic per-user delete failure re-returning the same batch every
            // iteration. The remaining backlog is picked up by tomorrow's run; this only prevents an
            // unbounded spin today.
            log.error("Stopped non-activated-user cleanup after {} batches ({} deleted) — {}; "
                + "remaining backlog will be retried on the next scheduled run",
                batches, totalDeleted,
                batches >= maxBatches
                    ? "hit the maxBatches safety cap (~" + (maxBatches * effectiveBatchSize) + " attempts this run)"
                    : "every remaining candidate already failed earlier this run",
                kv("operation", "user_admin"),
                kv("action", "delete_inactive"),
                kv("status", "CAPPED"));
        }
    }

    /**
     * skillars-deferred-122 AC9 code review 2026-09-18 (Resolved: option 2 — attempt-count threshold,
     * not a one-shot marker): a single blanket {@code catch (Exception e)} in {@link
     * #removeNotActivatedUsers} cannot distinguish a deterministically-undeletable user (AC9's stated
     * scope — an uncovered FK, a trigger, a constraint) from a transient failure (a lock timeout,
     * deadlock, or connection reset) — the one-shot marker this replaced stamped {@code
     * cleanupFailedAt} on the very first failure of either kind, permanently excluding a user for a
     * single bad night with no retry and no TTL. Follows this repo's established idiom for
     * repeatedly-failing work — {@code OutboxReplicationJob} ({@code attemptCount} +
     * {@code lastAttemptedAt} + {@code errorMessage}), {@code VideoWebhookEvent.attemptCount}, {@code
     * Video.moderationRetryCount} — none of which uses a one-shot flag.
     * <p>
     * {@code user.getCleanupFailedAttempts()} is read off the already-loaded entity from this run's
     * own {@link #fetchExpiredUsersPage} call, not a fresh query — safe because this job is
     * {@code @SchedulerLock}-serialized to one node at a time and this is the only writer of these
     * columns, so no concurrent writer can have changed the count since this run's own page read.
     * {@link #CLEANUP_FAILURE_THRESHOLD} consecutive <em>separate-run</em> failures (never in-run
     * retries — {@code failedLogins} already excludes an already-failed login from the rest of this
     * run) before the permanent {@code cleanupFailedAt} exclusion marker is stamped; below that count,
     * the attempt is recorded (for operator visibility and to detect the deterministic case
     * accumulating) but the user stays eligible for tomorrow's run.
     */
    private void recordCleanupFailure(User user, Exception cause) {
        int attempts = user.getCleanupFailedAttempts() + 1;
        Instant attemptedAt = Instant.now();
        String errorMessage = String.valueOf(cause.getMessage());
        if (errorMessage.length() > MAX_CLEANUP_ERROR_MESSAGE_LENGTH) {
            errorMessage = errorMessage.substring(0, MAX_CLEANUP_ERROR_MESSAGE_LENGTH);
        }
        try {
            userRepository.recordCleanupAttemptFailure(user.getLogin(), attempts, attemptedAt, errorMessage);
            if (attempts >= CLEANUP_FAILURE_THRESHOLD) {
                userRepository.markCleanupFailed(user.getLogin(), attemptedAt);
            }
        } catch (Exception markException) {
            // skillars-deferred-122 AC9: a failure recording the attempt/marker must not abort the
            // sweep — its own try/catch, matching the pre-existing marker-stamp isolation this
            // replaces.
            log.error("Failed to persist cleanup failure attempt marker",
                kv("operation", "user_admin"),
                kv("action", "mark_cleanup_failed"),
                kv("status", "ERROR"),
                markException);
        }
    }

    /**
     * Finds expired non-activated users, excluding any login that has already failed deletion once
     * in this run (see {@link #removeNotActivatedUsers}'s Decision-3 note) or that
     * skillars-deferred-122 AC9's persisted {@code cleanup_failed_at} marker excludes from every run
     * until an operator clears it.
     * <p>
     * skillars-deferred-122 AC6: genuinely server-side paged via
     * {@code findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc}, replacing
     * the previous {@code findAllByActivatedIsFalseAndCreatedDateBefore} call that took no
     * {@code Pageable} at all and materialized the entire expired-user set on every call (the
     * method's own former Javadoc claim of "uses pagination" was false). {@code excludeLogins}
     * deliberately stays an in-run, Java-side filter over the returned page only — not pushed
     * server-side as a growing {@code NOT IN} bind list, which would add real query-plan-cache churn
     * across a set that can reach {@code batchSize x maxBatches} entries. AC9's persisted marker
     * carries the <em>cross-run</em> exclusion instead; this in-memory set is only the
     * belt-and-braces for the (small) window before a stamp has committed.
     * </p>
     * <p>
     * skillars-deferred-122 AC8: this method's own {@code @Transactional(readOnly = true)} was
     * equally inert as {@link #deleteUserInTransaction}'s pre-fix annotation, for the identical
     * reason (a plain self-invoked, non-public method under Spring's default proxy-mode
     * {@code publicMethodsOnly} AOP). Dropped rather than "fixed" via {@code self} + {@code public}:
     * unlike {@code deleteUserInTransaction}, this method's transactionality carries no correctness
     * requirement of its own — {@code userRepository}'s query call already gets
     * {@code SimpleJpaRepository}'s own per-call transaction — so a misleading, always-inert
     * annotation is worse than none.
     * </p>
     * <p>
     * skillars-deferred-122 code review 2026-09-18 (Patch: silent early exit): {@link
     * #removeNotActivatedUsers}'s loop-continuation decision must NOT be made from this method's
     * {@code excludeLogins}-filtered result. If every row on the raw page has already failed earlier
     * this run, this method correctly returns an empty list (nothing left to *attempt* this
     * iteration) — but the raw page itself is not empty, and reading that emptiness as "backlog
     * drained" was exactly the bug: the DB-level {@code cleanupFailedAt IS NULL} predicate still
     * returns those same rows (AC9's cross-run marker only stamps after
     * {@link #CLEANUP_FAILURE_THRESHOLD} separate-run failures, not on the first), so a real backlog
     * silently went unreported. {@link #removeNotActivatedUsers} now calls
     * {@link #fetchExpiredUsersPage} directly for that decision and uses this method's
     * {@code excludeLogins} filtering only to decide which users to attempt this iteration.
     * </p>
     */
    protected List<User> findExpiredUsers(Instant cutoffDate, int batchSize, Set<String> excludeLogins) {
        return fetchExpiredUsersPage(cutoffDate, batchSize)
                .stream()
                .filter(u -> !excludeLogins.contains(u.getLogin()))
                .toList();
    }

    /**
     * The raw, unfiltered expired-user page — no {@code excludeLogins} filtering. See
     * {@link #findExpiredUsers}'s Javadoc for why {@link #removeNotActivatedUsers} needs this
     * distinct, unfiltered view for its loop-continuation and backlog-remains decisions.
     */
    private List<User> fetchExpiredUsersPage(Instant cutoffDate, int batchSize) {
        return userRepository.findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc(
            cutoffDate, PageRequest.of(0, batchSize));
    }

    /**
     * Deletes a user, re-checking {@code !activated} on a fresh re-fetch immediately before
     * deleting.
     * <p>
     * skillars-deferred-122 AC8: {@code public} (was {@code protected}) and invoked via the
     * {@link #self} proxy reference from {@link #removeNotActivatedUsers}, mirroring
     * {@code VideoSubscriptionLifecycleListener}'s established {@code self} pattern exactly —
     * <strong>both</strong> changes are required. A self-proxy field alone does not fix
     * self-invocation: {@code DataSourceConfig}'s bare {@code @EnableTransactionManagement} (no
     * {@code mode}/{@code proxyTargetClass} override) uses Spring's default {@code PROXY} mode, whose
     * {@code AnnotationTransactionAttributeSource} carries {@code publicMethodsOnly = true} — a
     * non-public method returns a {@code null} transaction attribute (no advice at all) regardless of
     * whether the call arrives through the proxy. {@code REQUIRES_NEW} now genuinely applies: each
     * user's delete runs in its own transaction, isolated from the batch loop and from sibling
     * deletes.
     * </p>
     * <p>
     * Scope honesty: even with {@code REQUIRES_NEW} genuinely applied, the
     * {@code findOneByLogin}-then-{@code delete} below is a plain {@code SELECT} then {@code DELETE}
     * at READ COMMITTED with no row lock — this fix <em>narrows</em> the residual TOCTOU window
     * sharply (from a whole batch's worth of processing time down to the gap between two statements
     * in this one small method), it does not <em>close</em> it. A fully-closing fix (a conditional
     * {@code DELETE ... WHERE login = ? AND activated = false}, or a locked re-read) is reasonable
     * future hardening, not required here.
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
    public void deleteUserInTransaction(String login) {
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
