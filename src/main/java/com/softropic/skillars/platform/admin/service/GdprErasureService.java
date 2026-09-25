package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.infrastructure.config.DataSourceConfig;
import com.softropic.skillars.infrastructure.config.RoutingDataSource;
import com.softropic.skillars.infrastructure.config.RoutingDataSourceContext;
import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.admin.contract.AdminAlertReferenceType;
import com.softropic.skillars.platform.admin.contract.AdminAlertStatus;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import com.softropic.skillars.platform.admin.repo.AdminAlert;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.admin.repo.GdprRequest;
import com.softropic.skillars.platform.admin.repo.GdprRequestRepository;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.session.repo.HomeworkCompletionRepository;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlagRepository;
import com.softropic.skillars.platform.development.repo.PerformanceReportRepository;
import com.softropic.skillars.platform.development.repo.PlayerRadarBaselineRepository;
import com.softropic.skillars.platform.development.repo.PlayerRadarCompositeRepository;
import com.softropic.skillars.platform.development.repo.PlayerSluWeeklySnapshotAppliedRepository;
import com.softropic.skillars.platform.development.repo.PlayerTimelineRepository;
import com.softropic.skillars.platform.development.repo.RadarAssessmentRepository;
import com.softropic.skillars.platform.development.repo.SluRepository;
import com.softropic.skillars.platform.development.repo.SluTargetRepository;
import com.softropic.skillars.platform.development.repo.SluWeeklySnapshotRepository;
import com.softropic.skillars.platform.filestorage.service.BlobDeletionOutboxSupport;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import com.softropic.skillars.platform.security.contract.AccountRole;
import com.softropic.skillars.platform.security.contract.SkillarsRole;
import com.softropic.skillars.platform.security.contract.event.AccountDeletionRequestedEvent;
import com.softropic.skillars.platform.security.contract.event.UserErasedEvent;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.PessimisticLockException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GdprErasureService {

    private final GdprRequestRepository gdprRequestRepository;
    private final AdminAlertRepository adminAlertRepository;
    private final UserRepository userRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final BookingRepository bookingRepository;
    private final MessageRepository messageRepository;
    private final CoachReviewRepository coachReviewRepository;
    private final SluRepository sluRepository;
    private final SluWeeklySnapshotRepository sluWeeklySnapshotRepository;
    private final PlayerSluWeeklySnapshotAppliedRepository playerSluWeeklySnapshotAppliedRepository;
    private final SluTargetRepository sluTargetRepository;
    private final RadarAssessmentRepository radarAssessmentRepository;
    private final NeglectedSkillFlagRepository neglectedSkillFlagRepository;
    private final PlayerRadarBaselineRepository playerRadarBaselineRepository;
    private final PlayerRadarCompositeRepository playerRadarCompositeRepository;
    private final PerformanceReportRepository performanceReportRepository;
    private final PlayerTimelineRepository playerTimelineRepository;
    private final HomeworkCompletionRepository homeworkCompletionRepository;
    private final BlobDeletionOutboxSupport blobDeletionOutboxSupport;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PessimisticLockRetryer lockRetryer;
    private final EntityManager entityManager;
    private final PlatformTransactionManager txManager;
    private final ConfigService configService;
    private final DataSource dataSource;

    /**
     * Self-reference so {@link #erase}'s pre-transaction connection-pool check (skillars-deferred-132
     * AC1 Fix 4) genuinely runs BEFORE {@link #eraseTransactional}'s {@code @Transactional(REQUIRES_NEW)}
     * proxy advice opens (and blocks acquiring) its own connection — a check placed inside a
     * declaratively-{@code @Transactional} method's own body runs too late, since the Spring AOP proxy
     * already acquired the connection before the method body starts executing. Mirrors
     * {@code SubscriptionService.self}'s identical pattern.
     */
    @Autowired
    @Lazy
    private GdprErasureService self;

    // skillars-deferred-128 AC1: lets deletePlayerDevelopmentData commit (and release its
    // player_profiles lock) independently of erase()'s own outer transaction — mirrors
    // ModerationSlaMonitorService.java:36,44,52-56 exactly. A plain @Transactional on a
    // private/self-invoked method would silently not apply here (Spring AOP proxy limitation), so
    // this is built programmatically instead.
    private TransactionTemplate requiresNewTemplate;

    // skillars-deferred-128 AC2: bounds how many children eraseParentChildren's loop can ATTEMPT
    // processing for within one PARENT's run — the deadline is sampled only before each child's own
    // processing begins, not preemptively during it, so the real worst-case total wall-clock time is
    // this budget PLUS one additional child's own full processing time (including its own ~3.2s
    // worst-case PessimisticLockRetryer budget), not a hard ceiling on the loop's own duration (story
    // review, 2026-09-22). erase() runs on the request thread — see eraseParentChildren's own
    // Javadoc. ~10s ≈ 3x a single child's own ~3.2s worst-case retry budget, i.e. tolerating about 3
    // genuinely-contended children before tripping — a defensible ceiling for a synchronous
    // admin-triggered HTTP call, not derived from Hikari's unrelated connection-acquisition timeout.
    //
    // Deliberately a plain instance field, not `static final` — this project's own established test
    // seam for a normally-fixed duration constant on a singleton Spring bean is
    // ReflectionTestUtils.setField(bean, "fieldName", ...) against the live bean instance (see
    // GdprErasureIT's deadline-exceeded tests), which needs a genuine instance field to target.
    // `volatile` (story review, 2026-09-22): the test seam above writes this field from the test
    // thread while eraseParentChildren reads it from a separate executor thread in this file's own
    // concurrency tests — without `volatile` that read is not guaranteed to observe the write.
    private volatile Duration gdprEraseLockBudget = Duration.ofSeconds(10);

    // skillars-deferred-129 AC1 (M6): the pre-existing CHILD_CONTENDED reason (PessimisticLockRetryer's
    // own retry budget exhausted acquiring the player_profiles lock) vs. this AC's new
    // CHILD_DELETE_LOCK_TIMEOUT reason (a downstream delete/scan/enqueue statement tripping the new
    // per-statement lock_timeout bound) — distinguished by catch TYPE at each call site, see
    // DeleteStatementLockTimeoutException's own Javadoc for why cause-inspection alone cannot.
    private static final String CHILD_CONTENDED = "CHILD_CONTENDED";
    private static final String CHILD_DELETE_LOCK_TIMEOUT = "CHILD_DELETE_LOCK_TIMEOUT";
    // (code review 2026-09-23): extracted for consistency with the two constants above — this one
    // predates skillars-deferred-129 and was left as a bare literal at both its call sites.
    private static final String CHILD_VANISHED = "CHILD_VANISHED";
    // skillars-deferred-133 AC1: markFailed's own catch-all reason — covers erase()'s pre-transaction
    // assertConnectionPoolNotSaturated throw and any other exception not one of the three typed
    // reasons above (a GdprRequest/User "not found" RuntimeException, or anything unforeseen).
    private static final String UNCLASSIFIED_FAILURE = "UNCLASSIFIED_FAILURE";

    // skillars-deferred-136 AC2: retryFailedErasures's own sweep sizing — mirrors
    // EmailRetryScheduler.MAX_RETRY_ATTEMPTS's shape, not its value. GDPR erasure failures are
    // expected to be rare and each retry is a heavier full-erasure attempt (unlike a single email
    // send), so a lower cap than email's 6 is deliberate: three failed full attempts is a stronger
    // signal that this needs a human, not transient contention. One hour's grace mirrors the
    // scheduler's own once-daily cadence (see GdprErasureRetryScheduler) — long enough that a
    // transient lock-contention failure has almost certainly cleared, short enough that a real
    // failure still gets its first automatic re-drive well within the same day.
    static final int MAX_ERASURE_RETRY_ATTEMPTS = 3;
    static final Duration ERASURE_RETRY_GRACE_WINDOW = Duration.ofHours(1);
    private static final int RETRY_SWEEP_PAGE_SIZE = 50;

    @PostConstruct
    void initTemplates() {
        requiresNewTemplate = new TransactionTemplate(txManager);
        requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * skillars-deferred-132 AC1 Fix 4. Not itself {@code @Transactional} — deliberately, since the
     * check below must run BEFORE {@link #eraseTransactional}'s own {@code @Transactional(REQUIRES_NEW)}
     * proxy advice opens (and, under sustained pool exhaustion, could block up to the full 30s Hikari
     * {@code connection-timeout} acquiring) its connection. {@code erase()} runs on the HTTP request
     * thread via a non-{@code @Async} {@code AFTER_COMMIT} listener while the request's own connection
     * is still briefly held (see {@link #eraseTransactional}'s own Javadoc) — this bounds THAT
     * acquisition's own risk, on top of {@link #deletePlayerDevelopmentData}'s identical guard for its
     * nested {@code REQUIRES_NEW} acquisition below.
     *
     * <p><strong>Chosen mechanism (this story deliberately left the choice open — recorded here):</strong>
     * a same-thread, read-only {@link HikariPoolMXBean} pre-check, not a dedicated secondary
     * {@code EntityManagerFactory}/{@code DataSource} (option (a) — re-costed during story review as
     * "take over JPA bootstrapping for the whole app", since no {@code @EnableJpaRepositories} exists
     * anywhere in this codebase) and not a cross-thread {@code Future} (ruled out — can create a
     * genuine two-transaction deadlock and breaks the typed exception discrimination this file's own
     * {@link DeleteStatementLockTimeoutException} depends on). This fails fast on the caller's own
     * thread with zero new infrastructure and zero shared-mutable-pool-config risk, at the cost of a
     * real TOCTOU gap: the pool's state can change between this check and the real acquisition
     * immediately after, in either direction — accepted, since closing that gap fully would require one
     * of the two costlier mechanisms above.
     */
    public void erase(UUID requestId, Long userId) {
        assertConnectionPoolNotSaturated(requestId, "erase");
        // skillars-deferred-136 AC1: routes eraseTransactional's own REQUIRES_NEW connection
        // acquisition to the dedicated GDPR pool instead of the primary one — see RoutingDataSource's
        // own Javadoc for why this, not a second PlatformTransactionManager, is the mechanism. Set
        // BEFORE the call (transaction begin is synchronous on this thread) and always cleared,
        // success or failure, so this thread never carries the key into unrelated later work.
        RoutingDataSourceContext.set(DataSourceConfig.GDPR_ERASURE_DATASOURCE_KEY);
        try {
            self.eraseTransactional(requestId, userId);
        } finally {
            RoutingDataSourceContext.clear();
        }
    }

    /**
     * skillars-deferred-132 AC1 Fix 4: renamed from {@code erase} — the connection-pool pre-check now
     * lives in the new outer {@link #erase} wrapper above, which this method's own
     * {@code @Transactional(REQUIRES_NEW)} advice would otherwise run BEFORE any of this method's own
     * code could execute. Business logic below is unchanged from before this rename.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void eraseTransactional(UUID requestId, Long userId) {
        GdprRequest request = gdprRequestRepository.findById(requestId)
            .orElseThrow(() -> new RuntimeException("GdprRequest not found: " + requestId));
        request.setStatus("PROCESSING");
        gdprRequestRepository.save(request);

        User user = userRepository.findOneById(userId)
            .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        SkillarsRole role = user.getSkillarsRole();

        // Anonymise main.user — domain uses 2-char TLD to satisfy @Email(regexp = "[a-z]{2,3}") on the entity
        user.setLogin("deleted." + userId + "@erased.io");
        user.setEmail("deleted." + userId + "@erased.io");
        user.setFirstName("Deleted");
        user.setLastName("User");
        user.setPhone(null);
        user.setDateOfBirth(LocalDate.EPOCH); // dob is NOT NULL in DB; EPOCH is a neutral placeholder
        user.setActivationKey(null);
        user.setResetKey(null);
        user.setActivated(false);
        user.setLocked(true);
        user.getPersistentTokens().clear();
        userRepository.save(user);

        // Anonymise coach_profiles (if coach)
        coachProfileRepository.findByUserId(userId).ifPresent(cp -> {
            cp.setBio(null);
            cp.setCity(null);
            cp.setDistrict(null);
            coachProfileRepository.save(cp);
        });

        // Hard-delete messages (all rows including soft-deleted — Article 17)
        messageRepository.deleteAllBySenderId(userId);
        // Close any admin alert left pointing at a message we just erased — otherwise it sits OPEN
        // forever, since every admin action on it now 404s and there is no dismiss endpoint.
        adminAlertRepository.resolveOpenAlertsForDeletedMessages();

        // Hard-delete non-APPROVED reviews; anonymise APPROVED
        coachReviewRepository.deleteNonApprovedByAuthorId(userId, ReviewModerationStatus.APPROVED);
        coachReviewRepository.anonymiseApprovedReviews(userId);

        // skillars-deferred-90 AC13: collect every S3 storage key that needs deleting into a durable
        // outbox row instead of issuing N blocking deleteObject calls inside this transaction. The
        // AFTER_COMMIT drain in the generic platform.outbox deletes them off this request path and
        // retries failures on the next drain.
        //
        // skillars-deferred-128 AC1 (H1): this list is now ONLY for the GDPR-export-zip keys below —
        // a PARENT/PLAYER child's own performance_reports keys are enqueued INSIDE
        // deletePlayerDevelopmentData's own inner transaction instead (see that method's Javadoc for
        // why), so the two are kept in visibly separate lists rather than one shared one.
        List<String> exportBlobKeysToDelete = new ArrayList<>();

        // Delete player development data
        // skillars-deferred-127 AC1: a player_profiles.id (TSID) is NOT the same as main.user.id, so
        // the PLAYER branch must resolve its own profile via findByUserId first — passing userId
        // straight to deletePlayerDevelopmentData (which now locks its argument as a player_profiles.id)
        // would either find no row (a genuine ResourceNotFoundException, since a TSID essentially never
        // equals a user id) or, worse, coincidentally lock and touch an unrelated profile. A PLAYER-role
        // account with no profile row yet has nothing to erase here — orElse-skip, not orElseThrow; a
        // missing profile is a legitimate "nothing to erase" case on a GDPR path, not an error.
        // (code review 2026-09-23, Decision 1) true only for CHILD_CONTENDED/CHILD_DELETE_LOCK_TIMEOUT
        // skips — never for CHILD_VANISHED, whose data genuinely was already erased by a concurrent
        // request. Gates the terminal status below: a skip here means development data survives
        // this run, so the request must not be reported COMPLETED. An AtomicBoolean, not a plain
        // local, because the PLAYER branch's catch runs inside the ifPresentOrElse lambda below.
        AtomicBoolean skipped = new AtomicBoolean(false);
        if (role == SkillarsRole.PLAYER) {
            // skillars-deferred-132 AC2 Fix 9: read once here, before the lock acquisition inside
            // deletePlayerDevelopmentData, mirroring eraseParentChildren's own hoisted read below. This
            // branch already calls deletePlayerDevelopmentData exactly once, so this is a no-op in
            // practice — only the PARENT loop's N-children repetition is actually fixed — but keeps
            // deletePlayerDevelopmentData's signature (lockTimeoutSeconds as a parameter, not read
            // internally) consistent across both callers.
            long playerBranchLockTimeoutSeconds = configService.getBoundedLong(
                ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.key(), 5L, 2L, 120L);
            playerProfileRepository.findByUserId(userId)
                .ifPresentOrElse(
                    pp -> {
                        try {
                            deletePlayerDevelopmentData(pp.getId(), playerBranchLockTimeoutSeconds);
                            // (story review, 2026-09-22): mirrors the PARENT branch's own detach (M9) —
                            // pp is this outer transaction's managed instance, while the tombstone write
                            // now commits in deletePlayerDevelopmentData's INNER transaction/
                            // EntityManager. PlayerProfile has no @Version and is not @DynamicUpdate, so
                            // any future edit to pp in this outer transaction would silently rewrite the
                            // stale pre-tombstone snapshot, resetting the one-way tombstone to NULL.
                            entityManager.detach(pp);
                        } catch (ResourceNotFoundException e) {
                            // (H2 fix, story review 2026-09-22): equivalent to eraseParentChildren's own
                            // CHILD_VANISHED catch — the row vanished between findByUserId above and the
                            // lock attempt inside deletePlayerDevelopmentData (most plausibly a
                            // concurrent, since-finished GDPR request for the same userId). No detach
                            // needed: nothing in this transaction touched pp, so it is not stale.
                            log.warn("[GDPR_ERASURE] PLAYER-branch playerId={} vanished before its lock "
                                    + "could be acquired (already erased, presumably by a concurrent "
                                    + "request) — skipping development-data deletion, requestId={} "
                                    + "userId={}", pp.getId(), requestId, userId);
                            raiseErasureAlert(requestId, CHILD_VANISHED);
                            // no entityManager.detach(pp) needed here — the row already vanished
                            // before deletePlayerDevelopmentData's lock attempt, so pp was never
                            // going to observe a tombstone from this call regardless.
                        } catch (DeleteStatementLockTimeoutException | PessimisticLockingFailureException e) {
                            // (H2 fix, story review 2026-09-22): without this catch, a contended lock
                            // here would throw straight out of erase(), routing to markFailed — which
                            // only log.errors, no AdminAlert, no auto-retry — and silently convert what
                            // was previously a successful (if slow) erasure into an unalerted FAILED, on
                            // the most common account shape (a self-registered player). Skip the
                            // development-data deletion, alert, and let erase() continue: the account
                            // still gets anonymised/locked and its refresh tokens revoked. No detach
                            // needed: deletePlayerDevelopmentData's inner transaction rolled back, so no
                            // tombstone was committed and pp is not stale.
                            //
                            // (Task 8) the two catch types, not a cause inspection, are what
                            // distinguish the reason — see DeleteStatementLockTimeoutException's own
                            // Javadoc for why cause-class inspection alone cannot.
                            String reason = e instanceof DeleteStatementLockTimeoutException
                                ? CHILD_DELETE_LOCK_TIMEOUT : CHILD_CONTENDED;
                            log.warn("[GDPR_ERASURE] PLAYER-branch playerId={} development-data deletion "
                                    + "failed (reason={}, single-profile branch, not a skip-one-of-N-"
                                    + "siblings case) — skipping, requestId={} userId={}",
                                pp.getId(), reason, requestId, userId);
                            raiseErasureAlert(requestId, reason);
                            // (Decision 1): unlike CHILD_VANISHED above, this player's development
                            // data genuinely survives this run — the request must not read COMPLETED.
                            skipped.set(true);
                        }
                    },
                    // code review 2026-09-21: distinguishable from the intended "never built a
                    // profile" case in the logs — a silent no-op here would look identical to a
                    // successfully-completed erasure even if the absence ever had some other cause.
                    () -> log.warn("[GDPR_ERASURE] No player_profiles row found for PLAYER-role "
                        + "userId={} — skipping development-data deletion", userId));
        } else if (role == SkillarsRole.PARENT) {
            if (eraseParentChildren(requestId, userId)) {
                skipped.set(true);
            }
        }

        // Revoke all refresh tokens so existing sessions are rejected on the next request
        refreshTokenRepository.markAllUsedByUserId(userId);

        // Delete old GDPR requests (>30 days)
        gdprRequestRepository.deleteExpiredByUserId(userId, Instant.now().minus(30, ChronoUnit.DAYS));

        // S3 files from previously COMPLETED export requests — enqueued, not deleted inline.
        gdprRequestRepository.findByUserIdAndRequestTypeAndStatus(userId, "EXPORT", "COMPLETED")
            .forEach(completedExport ->
                exportBlobKeysToDelete.add("gdpr/exports/" + completedExport.getId() + ".zip"));

        // Persist the pending-deletion rows inside THIS transaction (so a post-commit S3 failure is
        // re-drivable) and ask for the drain to run once, after this transaction commits.
        blobDeletionOutboxSupport.enqueue(exportBlobKeysToDelete);
        blobDeletionOutboxSupport.requestDrainAfterCommit();

        // Mark erasure complete — (code review 2026-09-23, Decision 1) FAILED, not COMPLETED, if any
        // child's development data was skipped for a CHILD_CONTENDED/CHILD_DELETE_LOCK_TIMEOUT
        // reason: that data survives with developmentDataErasedAt still NULL, so COMPLETED would be
        // an Article 17 correctness regression (machine-readably claiming "done" when it is not). The
        // OPEN AdminAlert this file already raises for that reason is preserved either way; only the
        // request's own terminal status changes. CHILD_VANISHED never sets `skipped` — that data was
        // genuinely already erased by a concurrent request, so COMPLETED there remains correct.
        request.setStatus(skipped.get() ? "FAILED" : "COMPLETED");
        request.setCompletedAt(Instant.now());
        gdprRequestRepository.save(request);

        // Both events are published within this TX and fire AFTER_COMMIT together
        eventPublisher.publishEvent(new UserErasedEvent(userId));

        // ADMIN role has no AccountRole equivalent and no video/player cascade — skip AccountDeletionRequestedEvent
        if (role != SkillarsRole.ADMIN) {
            String eventUserId;
            AccountRole accountRole;
            List<Long> linkedPlayerIds;
            if (role == SkillarsRole.COACH) {
                eventUserId = coachProfileRepository.findByUserId(userId)
                    .map(cp -> cp.getId().toString())
                    .orElse(String.valueOf(userId));
                accountRole = AccountRole.COACH;
                linkedPlayerIds = List.of();
            } else if (role == SkillarsRole.PARENT) {
                eventUserId = String.valueOf(userId);
                accountRole = AccountRole.PARENT;
                linkedPlayerIds = playerProfileRepository.findByParentIdOrderByIdAsc(userId).stream()
                    .map(PlayerProfile::getId)
                    .collect(Collectors.toList());
            } else {
                eventUserId = String.valueOf(userId);
                accountRole = AccountRole.PLAYER;
                linkedPlayerIds = List.of();
            }
            eventPublisher.publishEvent(new AccountDeletionRequestedEvent(eventUserId, accountRole, linkedPlayerIds));
        }

        log.info("[GDPR_ERASURE_COMPLETED] requestId={} userId={} role={}", requestId, userId, role);
    }

    /**
     * skillars-deferred-133 AC1: the alert below runs unconditionally, regardless of whether {@link
     * #markFailedStatusUpdate} found a row to update — {@code eraseTransactional}'s own {@code
     * orElseThrow(() -> new RuntimeException("GdprRequest not found: " + requestId))} fires exactly when
     * the same {@code findById(requestId)} returns empty, so a guard-scoped alert would never run for the
     * one path this fix was explicitly written to cover.
     *
     * <p><strong>skillars-deferred-135 AC1: split into two SEQUENTIAL {@code REQUIRES_NEW} transactions,
     * not one.</strong> Before this fix, {@code markFailed} was itself a single {@code
     * @Transactional(REQUIRES_NEW)} method that wrote the {@code FAILED} status AND called {@link
     * #insertErasureAlertIfAbsent} directly, with the catch for a duplicate-alert race sitting INSIDE
     * that one transaction — which does not work: once {@code saveAndFlush}'s flush throws, Hibernate
     * marks the whole transaction (status update included) rollback-only regardless of the catch, so the
     * proxy's own commit-time logic throws {@code UnexpectedRollbackException} back at this method's own
     * caller, silently discarding the {@code FAILED} status write along with the alert (the identical
     * failure mode {@link AdminAlertEventListener#insertAlert}'s own pre-134 shape had — see its inline
     * comment for the general mechanism this mirrors). Splitting into {@link #markFailedStatusUpdate}
     * (its own committed-and-released {@code REQUIRES_NEW} transaction) followed by {@link
     * #raiseErasureAlert} (which now owns the catch-outside-the-boundary fix, see its own Javadoc) means
     * the status write is durable BEFORE the alert-raise even begins — a losing alert race can no longer
     * roll back the status update, because by the time it could race, that transaction has already
     * committed and released its connection.
     *
     * <p><strong>Accepted tradeoff: two sequential connection acquisitions, not one.</strong> The
     * original single-transaction design was chosen specifically to avoid opening a SECOND,
     * CONCURRENTLY-HELD connection from the same pool on this path — the headline trigger for
     * skillars-deferred-132 AC1 Fix 4's own {@link #assertConnectionPoolNotSaturated} pre-check, since
     * {@code markFailed} is reached (via {@link GdprEventListener}'s own catch block) precisely when
     * {@code erase()}'s pre-check just reported the pool saturated. That specific concern — two
     * connections held AT THE SAME TIME — still does not apply here: {@link #markFailedStatusUpdate}'s
     * own transaction fully commits and releases its connection before {@link #raiseErasureAlert}'s own
     * transaction ever requests one, so at most one connection is ever held at once. What DOES change is
     * the total number of sequential acquisition attempts (one, before this fix; two, after) — each one
     * individually still bounded by Hikari's own {@code connection-timeout}. This is a genuine, disclosed
     * tension between the pool-conservation property and the catch-outside-boundary correctness fix — both
     * could not be preserved exactly as originally shaped — resolved in favor of correctness, since a
     * silently-discarded {@code FAILED} status write is a worse outcome than one extra bounded
     * connection-acquisition attempt on an already-degraded pool.
     */
    public void markFailed(UUID requestId) {
        self.markFailedStatusUpdate(requestId);
        raiseErasureAlert(requestId, UNCLASSIFIED_FAILURE);
    }

    /**
     * skillars-deferred-135 AC1: extracted from {@link #markFailed} so the status write commits (and
     * releases its connection) independently of — and before — {@link #raiseErasureAlert}'s own separate
     * {@code REQUIRES_NEW} transaction. See {@link #markFailed}'s own Javadoc for the full reasoning.
     * {@code public}, not {@code private} — self-invoked via {@link #self} so the {@code
     * @Transactional} proxy advice actually applies (mirrors {@link #eraseTransactional}'s identical
     * self-invocation pattern).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailedStatusUpdate(UUID requestId) {
        gdprRequestRepository.findById(requestId).ifPresent(r -> {
            r.setStatus("FAILED");
            // skillars-deferred-136 AC2: stamps the grace-window clock GdprErasureRetryScheduler's
            // sweep reads — every path to FAILED goes through this one method, so this is the single
            // place that needs to set it.
            r.setFailedAt(Instant.now());
            gdprRequestRepository.save(r);
        });
        log.error("[GDPR_ERASURE_MARKED_FAILED] requestId={}", requestId);
    }

    /**
     * skillars-deferred-136 AC2: scheduled re-drive for {@code FAILED} {@code GdprRequest} rows —
     * {@code markFailed} (skillars-deferred-135 AC1) reliably alerts an admin, but nothing before this
     * automatically retried. Called from {@link
     * com.softropic.skillars.platform.admin.service.GdprErasureRetryScheduler}'s thin
     * {@code @Scheduled}/{@code @SchedulerLock} wrapper.
     *
     * <p>Re-queries page 0 in a loop, filtering out ids this SAME sweep invocation has already
     * considered, rather than advancing an offset: a successful {@code erase()} or a re-failure's own
     * {@code markFailedStatusUpdate} always changes the row enough to drop it out of the filter on the
     * next read (leaves {@code FAILED} entirely, or stamps a fresh {@code failedAt} never before this
     * sweep's own {@code graceDeadline} snapshot) — but a dedup-guard-SKIPPED candidate is left
     * completely unchanged, so it would re-match the identical filter forever. An offset-based {@code
     * page.next()} would silently skip rows shifted earlier by successful-redrive shrinkage; blindly
     * re-querying page 0 without tracking already-seen ids would spin forever on a persistently-skipped
     * row. Tracking {@code consideredThisSweep} closes both: bounded by the total FAILED-row count,
     * terminates even when every remaining candidate is skipped.
     *
     * <p><strong>Dedup guard:</strong> skips any candidate whose {@code userId} currently has a
     * PENDING/PROCESSING {@code ERASURE} row — mirrors {@code GdprRequestService.requestErasure}'s own
     * precondition. A persistently-skipped row (its user's PENDING/PROCESSING row never clears) simply
     * waits for the NEXT scheduled sweep, which re-evaluates it fresh — not an infinite retry within one
     * invocation.
     *
     * <p><strong>Known, accepted residual (story review): a narrow TOCTOU window still exists</strong>
     * between this check and the {@link #erase} call immediately below it — a manual resubmit created by
     * the user in that exact window is not caught, only a resubmit that already exists AT the check.
     * Low-severity and disclosed rather than closed: the worst outcome is duplicate erasure work on the
     * same user (both {@code erase()} is independently idempotent, per {@link
     * #deletePlayerDevelopmentData}'s own Javadoc), not data corruption — not worth a DB-level guard or a
     * second re-check for this narrow a window.
     *
     * <p><strong>Reuses {@code erase()}'s own entry point and safety mechanisms</strong> (AC1's
     * dedicated pool/pre-check, the per-child deadline budget) rather than bypassing them — and {@code
     * erase()}'s own failure path ({@code catch (Exception e) { markFailed(...) }}), mirroring {@link
     * GdprEventListener#onErasureRequested}'s identical shape, so a re-drive that fails again gets the
     * exact same alerting a first-time failure would.
     */
    public void retryFailedErasures() {
        Instant graceDeadline = Instant.now().minus(ERASURE_RETRY_GRACE_WINDOW);
        int redriven = 0;
        int skippedDedup = 0;
        Set<UUID> consideredThisSweep = new HashSet<>();
        Pageable pageable = PageRequest.of(0, RETRY_SWEEP_PAGE_SIZE);
        boolean sawUnconsideredCandidate;
        do {
            sawUnconsideredCandidate = false;
            Page<GdprRequest> page = gdprRequestRepository
                .findByRequestTypeAndStatusAndFailedAtBeforeAndRetryCountLessThan(
                    "ERASURE", "FAILED", graceDeadline, MAX_ERASURE_RETRY_ATTEMPTS, pageable);
            for (GdprRequest candidate : page) {
                UUID requestId = candidate.getId();
                if (!consideredThisSweep.add(requestId)) {
                    continue;
                }
                sawUnconsideredCandidate = true;
                Long userId = candidate.getUserId();
                // story review: the try now wraps the dedup check and the retry-count increment too,
                // not just erase() — an exception from either previously propagated out of this whole
                // sweep, aborting every remaining candidate instead of just this one.
                try {
                    if (gdprRequestRepository.existsByUserIdAndRequestTypeAndStatusIn(
                            userId, "ERASURE", List.of("PENDING", "PROCESSING"))) {
                        skippedDedup++;
                        continue;
                    }
                    self.incrementRetryCount(requestId);
                    erase(requestId, userId);
                    redriven++;
                } catch (Exception e) {
                    markFailed(requestId);
                    log.error("[GDPR_ERASURE_RETRY_FAILED] requestId={} userId={}", requestId, userId, e);
                }
            }
        } while (sawUnconsideredCandidate);
        log.info("[GDPR_ERASURE_RETRY_SWEEP] redriven={} skippedDedup={}", redriven, skippedDedup);
    }

    /**
     * {@code public}, not {@code private} — self-invoked via {@link #self} so the {@code
     * @Transactional} proxy advice actually applies (mirrors {@link #markFailedStatusUpdate}'s
     * identical self-invocation pattern). A separate, short {@code REQUIRES_NEW} transaction rather
     * than folded into {@code erase()}'s own: the count must persist even if the redrive attempt that
     * follows fails immediately, and incrementing it as part of {@code eraseTransactional}'s own
     * transaction would rollback the count together with everything else on that failure — defeating
     * the retry cap.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementRetryCount(UUID requestId) {
        gdprRequestRepository.findById(requestId).ifPresent(r -> {
            r.setRetryCount(r.getRetryCount() + 1);
            gdprRequestRepository.save(r);
        });
    }

    /**
     * skillars-deferred-128 AC1: bounds how many children this loop can ATTEMPT processing for
     * within one PARENT's run (the deadline is sampled only before each child's own processing
     * begins — see {@link #gdprEraseLockBudget}'s own Javadoc for the resulting worst-case-time
     * caveat), and lets a vanished or genuinely-contended child be skipped without failing every
     * other sibling.
     *
     * <p><strong>Runs on the request thread.</strong> {@link GdprEventListener#onErasureRequested} is
     * a plain (non-{@code @Async}) {@code @TransactionalEventListener(phase = AFTER_COMMIT)} fired
     * from {@code GdprRequestService.requestErasure}'s own {@code @Transactional} HTTP-request-scoped
     * method — so {@link #gdprEraseLockBudget} is added directly to that HTTP response time, which
     * is exactly why it is derived from request-latency tolerance (see the constant's own Javadoc),
     * not from an unrelated connection-pool setting.
     *
     * <p>The deadline clock starts here, at the top of this loop — not at the top of {@link #eraseTransactional}
     * as a whole. The account anonymisation and message/review deletion work preceding this call
     * includes its own unbounded bulk deletes, but they do not contend {@code player_profiles}, so
     * they are not the lock-holding concern this budget targets.
     *
     * @return {@code true} if any child was skipped for a {@link #CHILD_CONTENDED}/
     *     {@link #CHILD_DELETE_LOCK_TIMEOUT} reason (code review 2026-09-23, Decision 1) — signals
     *     {@link #eraseTransactional} to mark the request {@code FAILED} rather than {@code COMPLETED}, since that
     *     child's development data survives this run. {@code false} when every child either
     *     succeeded or only vanished ({@code CHILD_VANISHED}, whose data was genuinely already erased
     *     by a concurrent request and does not make this run incomplete).
     */
    private boolean eraseParentChildren(UUID requestId, Long userId) {
        // skillars-deferred-132 AC2 Fix 9: read once per eraseParentChildren call, unconditionally —
        // before `children` is even computed, not after any emptiness check — so this key is read
        // exactly once per erase() call for the PARENT branch regardless of child count, including the
        // 0-children case. Passed down as a parameter into deletePlayerDevelopmentData instead of that
        // method reading it once per child, closing this file's own D5 ledger residual (a cache-expiry
        // read triggering configRepository.findAll() once per child, all on the outer transaction's
        // connection, which already holds the main."user" lock for this parent).
        long lockTimeoutSeconds = configService.getBoundedLong(
            ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.key(), 5L, 2L, 120L);
        // (story review, 2026-09-22, Decision 3): filter out children already tombstoned by an
        // earlier, since-failed run. developmentDataErasedAt is a one-way sticky tombstone (see
        // deletePlayerDevelopmentData's own Javadoc) — without this filter, a re-drive of a
        // deadline-truncated PARENT erasure restarts from child 0 and re-spends the whole budget
        // re-contending player_profiles for children that are already done, with no forward-progress
        // guarantee. Filtering makes deletePlayerDevelopmentData's own "re-driving makes genuine
        // forward progress" tradeoff true rather than assumed.
        List<PlayerProfile> children = playerProfileRepository.findByParentIdOrderByIdAsc(userId).stream()
            .filter(pp -> pp.getDevelopmentDataErasedAt() == null)
            .collect(Collectors.toList());
        // (story review, 2026-09-22): System.nanoTime()-based elapsed-time budget, not Instant.now()
        // — this diff's other half (AC5) exists specifically to remove wall-clock-based
        // transaction/timing coupling; Instant.now() is wall-clock and non-monotonic, so an NTP step
        // during a run could make this deadline unreachable (clock stepped backward) or trip it
        // spuriously (clock stepped forward). System.nanoTime() is immune to both.
        long deadlineNanos = System.nanoTime() + gdprEraseLockBudget.toNanos();
        int processed = 0;
        int skipped = 0;
        boolean anyUnrecoverableSkip = false;
        for (PlayerProfile child : children) {
            // AC2's deadline check is deliberately distinct from AC4's two catches below: exceeding
            // the deadline STOPS the whole request (throw, alert, propagate to markFailed); a
            // vanished/contended child only skips that one child and CONTINUES the loop.
            if (System.nanoTime() - deadlineNanos >= 0) {
                log.error("[GDPR_ERASURE_DEADLINE_EXCEEDED] requestId={} userId={} processed={}/{} "
                        + "children (skipped={}) within budget={}",
                    requestId, userId, processed, children.size(), skipped, gdprEraseLockBudget);
                raiseErasureAlert(requestId, "DEADLINE_EXCEEDED");
                throw new IllegalStateException("GDPR erasure exceeded its per-erase lock budget ("
                    + gdprEraseLockBudget + ") for userId=" + userId + " after processing "
                    + processed + "/" + children.size() + " children (skipped=" + skipped + ")");
            }
            try {
                // (M6, story review 2026-09-22): the ResourceNotFoundException catch below is still
                // precise ONLY by construction — its sole thrower inside deletePlayerDevelopmentData
                // reachable this way is the findByIdForUpdate orElseThrow. The catch below covering
                // BOTH DeleteStatementLockTimeoutException and PessimisticLockingFailureException is
                // no longer single-cause after this AC: it now also catches a NEW thrower — a
                // downstream delete/scan/enqueue statement tripping the new per-statement lock_timeout
                // bound, wrapped in DeleteStatementLockTimeoutException so it is distinguishable from
                // the original PessimisticLockRetryer-budget-exhaustion cause by TYPE, not by
                // inspecting a cause (see that exception's own Javadoc for why cause-inspection alone
                // cannot discriminate the two — both share SQLSTATE 55P03). If
                // deletePlayerDevelopmentData's shape ever changes again (e.g. a repository call
                // deeper in the delete chain starts throwing either exception for an unrelated
                // reason), that new throw must NOT be silently swallowed here without first
                // re-verifying these assumptions still hold.
                deletePlayerDevelopmentData(child.getId(), lockTimeoutSeconds);
                processed++;
            } catch (ResourceNotFoundException e) {
                // skillars-deferred-128 AC4: the child's player_profiles row vanished between
                // findByParentIdOrderByIdAsc's read and this call's lock attempt — most plausibly a
                // second, concurrent GDPR request that already finished it. Benign and resolved:
                // treat as already-erased and continue with the remaining siblings.
                skipped++;
                log.warn("[GDPR_ERASURE] PARENT-branch child playerId={} vanished before its lock "
                        + "could be acquired (already erased, presumably by a concurrent request) — "
                        + "skipping, requestId={} userId={}", child.getId(), requestId, userId);
                raiseErasureAlert(requestId, CHILD_VANISHED);
            } catch (DeleteStatementLockTimeoutException | PessimisticLockingFailureException e) {
                // skillars-deferred-128 AC4 (M11, widened scope); reason now discriminated per AC1's
                // own M6 fix (skillars-deferred-129) — by catch TYPE, not cause inspection, see
                // DeleteStatementLockTimeoutException's own Javadoc.
                skipped++;
                // (Decision 1): unlike CHILD_VANISHED above, this child's development data genuinely
                // survives this run — the request must not read COMPLETED.
                anyUnrecoverableSkip = true;
                String reason = e instanceof DeleteStatementLockTimeoutException
                    ? CHILD_DELETE_LOCK_TIMEOUT : CHILD_CONTENDED;
                if (CHILD_DELETE_LOCK_TIMEOUT.equals(reason)) {
                    log.warn("[GDPR_ERASURE] PARENT-branch child playerId={} had a downstream delete/"
                            + "scan/enqueue statement trip the per-statement lock_timeout bound "
                            + "(distinct from a contended player_profiles lock acquisition) — "
                            + "skipping (retryable on a later re-drive), requestId={} userId={}",
                        child.getId(), requestId, userId);
                } else {
                    log.warn("[GDPR_ERASURE] PARENT-branch child playerId={} lock was genuinely contended "
                            + "and its retry budget was exhausted — skipping (retryable on a later "
                            + "re-drive), requestId={} userId={}", child.getId(), requestId, userId);
                }
                raiseErasureAlert(requestId, reason);
            } finally {
                // skillars-deferred-128 AC1 (M9): deletePlayerDevelopmentData's tombstone write now
                // commits in an INNER transaction/EntityManager, while this outer persistence context
                // still holds the managed instance findByParentIdOrderByIdAsc returned above (and
                // erase() re-reads the same parentId again afterward for
                // AccountDeletionRequestedEvent's linkedPlayerIds). Detaching here means that later
                // re-read hits the DB fresh instead of Hibernate's first-level cache silently
                // returning this same stale (pre-tombstone) instance.
                entityManager.detach(child);
            }
        }
        return anyUnrecoverableSkip;
    }

    /**
     * skillars-deferred-128 AC2 (Task 4, owner decision, widened during story review to cover AC4's
     * own skip-and-continue outcomes too — Decision 1): a deadline-exceeded, vanished-child, or
     * contended-child outcome all end up silent to admins otherwise — a {@code FAILED} request with
     * no auto-retry (deadline), or a skipped child inside an otherwise {@code COMPLETED} request
     * (AC4) — visible only as a {@code log.warn}/{@code log.error}. Raises one targeted
     * {@code GDPR_ERASURE_DEADLINE} {@link AdminAlert} per distinct {@code reason}, reusing the one
     * alert type/reference-type pair V152 already shipped rather than adding new ones for this
     * narrower follow-up; {@code reason} (mirroring {@code MODERATION_UNRESOLVED}'s own established
     * pattern) is what lets {@code AdminQueueService.buildSummary} distinguish the cases in the
     * queue UI — {@code DEADLINE_EXCEEDED}, {@code CHILD_VANISHED}, {@code CHILD_CONTENDED}, and (as
     * of skillars-deferred-129 AC1) {@link #CHILD_DELETE_LOCK_TIMEOUT}.
     *
     * <p><strong>Corrected by skillars-deferred-133 AC1 — dedup is reason-BLIND again</strong> (any
     * {@code OPEN} {@code GDPR_ERASURE_DEADLINE} alert for this {@code requestId}), not per-{@code
     * (requestId, reason)} as the 2026-09-23 code review (Decision 2) briefly made it. That reason-aware
     * dedup was incompatible with {@code admin_alerts_unique_open_per_ref}
     * ({@code V138__baseline_schema.sql}), a unique index on {@code (reference_id, type)} only —
     * {@code reason} is not part of it — so a PARENT erasure raising {@code CHILD_VANISHED} for one
     * child and then {@code CHILD_DELETE_LOCK_TIMEOUT}/{@code CHILD_CONTENDED} for another passed the
     * reason-aware check for the second alert and then violated this reason-blind DB constraint,
     * converting a designed skip-and-continue into a full rollback. See
     * {@link #insertErasureAlertIfAbsent}'s own Javadoc for the fix (a reason-blind check plus a
     * {@code DataIntegrityViolationException} catch for the remaining race window).
     *
     * <p>Runs in its OWN {@code REQUIRES_NEW} transaction, not {@link #eraseTransactional}'s own — for the
     * deadline-exceeded case, the caller ({@link #eraseParentChildren}) throws immediately after this
     * returns, which rolls back {@link #eraseTransactional}'s outer transaction; without its own transaction this
     * alert would be rolled back right along with it and never actually recorded. For the AC4
     * skip-and-continue cases the caller does not throw, but this stays consistent with the
     * deadline path rather than depending on {@code erase()}'s own eventual commit.
     *
     * <p><strong>skillars-deferred-135 AC1: the catch sits OUTSIDE {@code
     * requiresNewTemplate.executeWithoutResult(...)}, not inside it</strong> (mirrors {@link
     * AdminAlertEventListener#insertAlert}'s own shipped fix exactly — read that method's inline comment
     * for the full mechanism). Before this fix, the catch lived inside {@link
     * #insertErasureAlertIfAbsent} itself, which ran INSIDE this callback — once {@code saveAndFlush}'s
     * flush threw, Hibernate marked this {@code REQUIRES_NEW} transaction rollback-only regardless of
     * that catch, so {@code TransactionTemplate}'s own {@code commit()} threw {@code
     * UnexpectedRollbackException} right back out, defeating the isolation this method exists to provide.
     * Letting the exception propagate OUT of the callback instead makes {@code TransactionTemplate} roll
     * back (not commit) this transaction and re-throw the ORIGINAL {@code DataIntegrityViolationException}
     * unchanged, caught here — outside the boundary — where it can be safely suppressed.
     *
     * <p>Also the shared entry point for {@link #markFailed}'s own alert-raise as of
     * skillars-deferred-135 (previously a direct call to {@link #insertErasureAlertIfAbsent}) — see that
     * method's own Javadoc for why the direct-call shape was retired.
     */
    private void raiseErasureAlert(UUID requestId, String reason) {
        try {
            requiresNewTemplate.executeWithoutResult(status -> insertErasureAlertIfAbsent(requestId, reason));
        } catch (DataIntegrityViolationException e) {
            // A genuinely concurrent caller won the (referenceId, type) OPEN slot first, between
            // insertErasureAlertIfAbsent's own isPresent() check and its flush. See this method's own
            // Javadoc and insertErasureAlertIfAbsent's own Javadoc for why the catch must sit here, not
            // inside insertErasureAlertIfAbsent.
            log.debug("[GDPR_ERASURE_ALERT_DUPLICATE_SUPPRESSED] requestId={} reason={}", requestId, reason);
        }
    }

    /**
     * skillars-deferred-133 AC1: extracted from {@link #raiseErasureAlert} so {@link #markFailed} can
     * share the identical mechanism (skillars-deferred-135: now via {@link #raiseErasureAlert} itself,
     * not a direct call — see that method's own Javadoc for why the direct-call shape was retired).
     * Deliberately non-transactional itself — the caller supplies the transactional context.
     *
     * <p><strong>Dedup is reason-BLIND</strong> (any {@code OPEN} {@code GDPR_ERASURE_DEADLINE} alert
     * for this {@code requestId}, regardless of {@code reason}) — corrected back from the 2026-09-23
     * code review's reason-aware dedup ({@code findFirstByReferenceIdAndTypeAndReasonAndStatus}),
     * which is incompatible with {@code admin_alerts_unique_open_per_ref}
     * ({@code V138__baseline_schema.sql}): that unique index is on {@code (reference_id, type)} only —
     * {@code reason} is not part of it — so a PARENT erasure raising two different reasons for two
     * different children (e.g. {@code CHILD_VANISHED} then {@code CHILD_DELETE_LOCK_TIMEOUT}) passed
     * the reason-aware check for the second alert and then violated this reason-blind DB constraint,
     * converting a designed skip-and-continue into an uncaught {@link DataIntegrityViolationException}
     * that rolled back the whole {@code eraseParentChildren} call. Each {@code raiseErasureAlert} call
     * runs in its OWN {@code REQUIRES_NEW} transaction that commits before the next child's turn in
     * {@code eraseParentChildren}'s own sequential loop, so the reason-blind {@code alreadyOpen} check
     * alone (not the {@code catch} in {@link #raiseErasureAlert}) is what actually closes this specific,
     * sequential-not-concurrent pre-existing race — the first child's alert is already durably committed
     * by the time the second child's check runs.
     *
     * <p><strong>A genuinely different, narrower case: two truly concurrent callers racing for the same
     * {@code (requestId, reason)} slot</strong> (found and corrected during implementation, not assumed —
     * {@code AdminAlert.alertId} is {@code GenerationType.UUID}, an in-memory/before-execution id
     * strategy, so Hibernate does not need to flush on a plain {@code save()}; empirically confirmed via
     * a throwaway Testcontainers test that a plain {@code save()} of a duplicate row does NOT throw
     * synchronously — the real {@code DataIntegrityViolationException} only surfaces when the
     * persistence context is flushed. {@code saveAndFlush} below (not plain {@code save}) forces the
     * INSERT to execute — and any constraint violation to surface — synchronously, inside THIS method's
     * own execution, while it is still running inside whatever {@code REQUIRES_NEW} transaction the
     * caller opened. {@code AdminAlertEventListener.insertAlert}'s own catch has this same latent gap for
     * a genuine concurrent race until it was closed by skillars-deferred-134's own fix — the identical
     * fix this AC ports here.
     *
     * <p><strong>skillars-deferred-135 AC1: the catch for a losing race lives in the CALLER ({@link
     * #raiseErasureAlert}), not here.</strong> Catching {@link DataIntegrityViolationException} INSIDE
     * this method — i.e. inside whatever {@code REQUIRES_NEW} transaction the caller has already opened
     * — would leave that transaction marked rollback-only by Hibernate regardless of the catch (per the
     * JPA spec, once a flush throws), so the caller's own commit would still throw {@code
     * UnexpectedRollbackException} right back out, defeating the isolation entirely — the exact bug this
     * AC fixes. Only {@link #raiseErasureAlert}, sitting OUTSIDE that transaction's own commit, can catch
     * this safely; this method lets the exception propagate uncaught.
     */
    private void insertErasureAlertIfAbsent(UUID requestId, String reason) {
        boolean alreadyOpen = adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(
                requestId.toString(), AdminAlertType.GDPR_ERASURE_DEADLINE, AdminAlertStatus.OPEN)
            .isPresent();
        if (alreadyOpen) {
            return;
        }
        AdminAlert alert = new AdminAlert();
        alert.setType(AdminAlertType.GDPR_ERASURE_DEADLINE);
        alert.setReferenceId(requestId.toString());
        alert.setReferenceType(AdminAlertReferenceType.GDPR_REQUEST);
        alert.setReason(reason);
        adminAlertRepository.saveAndFlush(alert);
    }

    /**
     * skillars-deferred-132 AC1 Fix 4. A same-thread, read-only pre-check that fails fast — with a
     * {@link PessimisticLockingFailureException} the existing call sites already know how to catch,
     * classify, and alert on — instead of letting a {@code REQUIRES_NEW} acquisition attempt genuinely
     * block on an exhausted {@code HikariDataSource} pool for up to its configured
     * {@code connection-timeout} (30s in this project's own {@code application.yaml}).
     *
     * <p>Saturated is defined as: at least one other thread is already queued waiting for a connection
     * ({@code threadsAwaitingConnection > 0} — direct evidence of contention), OR the pool has grown to
     * its configured maximum with zero idle connections (this call's OWN attempt would have to wait).
     * Either condition is read directly off the live {@link HikariPoolMXBean} — no polling, no
     * artificial delay.
     *
     * <p><strong>skillars-deferred-136 AC1: checks the DEDICATED GDPR-erasure pool, not the primary
     * one.</strong> Both call sites of this method precede an acquisition that {@link
     * RoutingDataSourceContext} now routes to {@code DataSourceConfig.GDPR_ERASURE_DATASOURCE_KEY}'s
     * target (see {@link #erase} and {@link #deletePlayerDevelopmentData}'s own comments) — checking the
     * primary pool's saturation here would no longer describe the pool the real acquisition is actually
     * about to draw from. {@code dataSource} is the app's single, routing {@code DataSource} bean; this
     * resolves the DEDICATED target off it specifically.
     *
     * <p><strong>{@code dataSource} may not resolve to a {@link HikariDataSource} instance</strong> —
     * {@code DataSourceConfig.dataSourceSpyPostProcessor} wraps the primary {@code dataSource} bean (the
     * whole {@link RoutingDataSource}, not its individual targets) in a {@code ProxyDataSourceBuilder}-
     * created proxy when {@code log.database.spy=true}. This check is a no-op (proceeds exactly as before
     * this fix) whenever it cannot resolve a real {@link HikariPoolMXBean} — a missed check under that
     * specific opt-in debug flag, not a correctness bug in normal operation.
     *
     * <p><strong>Known, accepted residual: a genuine TOCTOU gap.</strong> The pool's state can change
     * between this read and the real acquisition attempt immediately after it, in either direction — a
     * healthy-looking pool can still block, and a momentarily-saturated one can free up before the real
     * attempt. This is the accepted cost of a same-thread, zero-new-infrastructure mechanism (see
     * {@link #erase}'s own Javadoc for the two costlier alternatives this story ruled out/re-costed).
     */
    private void assertConnectionPoolNotSaturated(Object contextId, String callSite) {
        DataSource gdprPoolTarget = dataSource instanceof RoutingDataSource routing
            ? routing.getNamedTarget(DataSourceConfig.GDPR_ERASURE_DATASOURCE_KEY)
            : dataSource;
        if (!(gdprPoolTarget instanceof HikariDataSource hikariDataSource)) {
            return;
        }
        HikariPoolMXBean pool = hikariDataSource.getHikariPoolMXBean();
        if (pool == null) {
            return;
        }
        int idle = pool.getIdleConnections();
        int active = pool.getActiveConnections();
        int total = pool.getTotalConnections();
        int waiting = pool.getThreadsAwaitingConnection();
        int max = hikariDataSource.getMaximumPoolSize();
        boolean saturated = waiting > 0 || (idle <= 0 && total >= max);
        if (!saturated) {
            return;
        }
        log.error("[GDPR_ERASURE_POOL_SATURATED] callSite={} contextId={} active={} idle={} total={} "
                + "max={} waiting={} — refusing to attempt a REQUIRES_NEW connection acquisition that "
                + "could otherwise block this request thread for up to the pool's connection-timeout",
            callSite, contextId, active, idle, total, max, waiting);
        throw new PessimisticLockingFailureException(
            "GDPR erasure's " + callSite + " REQUIRES_NEW connection acquisition was refused: the "
                + "shared HikariCP pool appears saturated (active=" + active + " idle=" + idle
                + " total=" + total + " max=" + max + " waiting=" + waiting + ")");
    }

    /**
     * skillars-deferred-127 AC1: takes the SAME {@code player_profiles} pessimistic lock
     * {@link com.softropic.skillars.platform.development.service.RadarCompositeCalculationService#recalculateComposite}
     * already uses (same {@code findByIdForUpdate} + {@link PessimisticLockRetryer#withBoundedRetry}
     * pattern), before deleting anything below. This fully serializes GDPR erasure against a
     * concurrent radar-composite recalculation for the same player — once one path holds this lock,
     * the other cannot even begin touching {@code player_radar_composites}/{@code player_radar_baselines},
     * closing both the resurrection race (a recalculation re-inserting composites/baselines from a
     * pre-erasure snapshot after this method already deleted them) and the lock-ordering deadlock
     * hazard (the two paths write/delete those same two tables in opposite order) as a structural
     * consequence of the shared lock, not a separate mechanism.
     *
     * <p>{@code playerId} here is always an already-resolved {@code player_profiles.id} (a TSID) —
     * never a {@code main.user.id} — resolved differently by each caller in {@link #eraseTransactional}: the
     * PARENT branch already has it from {@code findByParentIdOrderByIdAsc} (via
     * {@link #eraseParentChildren}); the PLAYER branch resolves it via
     * {@code playerProfileRepository.findByUserId} first and only calls this method when a profile
     * row exists. A not-found here therefore means the row vanished between that resolution and lock
     * acquisition — a real error at THIS exact point (not the normal "no profile" case the PLAYER
     * branch already handles one level up) — so {@code orElseThrow} is correct here; it is the PARENT
     * loop's own reaction to that anomaly (skip-and-continue, see {@link #eraseParentChildren}), not
     * this method suppressing its own not-found signal, that implements skillars-deferred-128 AC4.
     *
     * <p><strong>skillars-deferred-128 AC1: this method now commits independently, in its own
     * {@code REQUIRES_NEW} transaction</strong> (via {@link #requiresNewTemplate}), releasing its
     * {@code player_profiles} lock as soon as it returns — BEFORE {@link #eraseTransactional}'s own unrelated
     * downstream steps (refresh-token revoke, {@code gdprRequest} cleanup) run in {@code erase()}'s
     * own transaction. Before this AC, the lock was held for {@code erase()}'s entire remaining
     * transaction (widening an RI {@code FOR KEY SHARE} exposure window on 7 FK'd tables for
     * concurrent inserts); after it, each child's lock is held only for this method's own
     * lock+deletes+enqueue+tombstone, sequentially, one child at a time.
     *
     * <p><strong>Accepted tradeoff (a): atomicity.</strong> A later failure in {@code erase()} (e.g.
     * in {@code gdprRequestRepository.deleteExpiredByUserId}, or {@link #eraseParentChildren}'s own
     * deadline throw) can no longer roll back an already-committed child's development-data deletion
     * — previously the whole {@code erase()} was one atomic unit; now a GDPR erasure that fails
     * partway through can leave one or more children's development data durably deleted while the
     * account-level anonymisation, refresh-token revocation, etc. did not complete. This is not a
     * data-integrity bug (a partially-erased player is not "less erased" than intended — Article 17
     * only requires eventual full erasure, not atomicity of it): the request still routes to
     * {@link #markFailed} and is re-drivable, and — because the per-child blob-deletion enqueue below
     * now travels atomically with the deletes that made its keys unrecoverable elsewhere (see (b)) —
     * re-driving makes genuine forward progress with no data left unrecoverable.
     *
     * <p><strong>(b) the per-child blob-deletion enqueue moved INSIDE this same inner transaction.</strong>
     * {@code performance_reports} rows are the ONLY record of their S3 {@code storage_key}s. If the
     * enqueue for a child's keys stayed on {@code erase()}'s outer path (alongside the
     * export-zip-key enqueue), any failure between this inner transaction's commit and
     * {@code erase()}'s own outer commit — including {@link #eraseParentChildren}'s own deadline
     * throw — would roll back the outbox rows while these deletes remain durable, permanently
     * orphaning those S3 blobs (a real Article 17 violation, not merely "a partially-erased player").
     * Enqueuing here, atomically with the deletes, closes that gap.
     *
     * <p><strong>Accepted tradeoff (c): the sticky tombstone can now commit before the whole erasure
     * is known to succeed.</strong> {@code developmentDataErasedAt} is one-way (never reset to null)
     * and {@code recalculateComposite} hard-skips every future recalculation once it is set. Before
     * this AC the tombstone committed atomically with the whole erasure; now it commits as soon as
     * THIS child's inner transaction commits — before {@code erase()}'s own outer transaction is known
     * to succeed. If {@code erase()} then fails, the outcome is a live, non-anonymised player whose
     * radar composites silently and permanently stop updating, with no reset path by design.
     * <strong>Deliberately accepted, not fixed</strong> — moving the tombstone write to
     * {@code erase()}'s outer transaction would need its own analysis of
     * {@code RadarCompositeCalculationService}'s lock-ordering argument and is out of scope here;
     * revisit only if this scenario is ever actually observed in production.
     *
     * <p><strong>Accepted tradeoff (d): blast radius of the already-accepted contended-lock
     * failure.</strong> A concurrent {@code recalculateComposite} can hold the shared lock for up to
     * {@code RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS} (120s) vs. this method's own ~3.2s retry budget.
     * Before this AC, that failure rolled the whole {@code erase()} back to a clean, re-drivable
     * state; after it, the failure aborts with any earlier children already durably deleted — and per
     * skillars-deferred-128 AC4, {@link #eraseParentChildren} now also skips-and-continues past this
     * specific exception rather than aborting the whole PARENT request.
     *
     * <p>Note on the lock re-acquisition itself: {@code entityManager.refresh(playerProfile,
     * PESSIMISTIC_WRITE)} below only works correctly if the shared-EntityManager proxy resolves to
     * THIS (inner) transaction's EntityManager — the same one {@code findByIdForUpdate} used just
     * above it. It does: {@code JpaTransactionManager} suspends the outer {@code EntityManagerHolder}
     * and binds a fresh one on {@code REQUIRES_NEW} — confirmed by this method's own {@code GdprErasureIT}
     * coverage, not merely assumed.
     *
     * <p><strong>skillars-deferred-129 AC1: each statement below is now bounded by a
     * {@code lock_timeout}, not the method's total wait.</strong> Postgres {@code lock_timeout} is
     * per-STATEMENT — mirroring {@code RadarCompositeCalculationService.recalculateComposite}'s own
     * documented trap (see that method's Javadoc) — so a single {@code set_config} call before the
     * delete block bounds each of this method's ~12 independently-timeout-able statements
     * individually, not the method's cumulative wait. There are 12 fixed delete/scan statements below
     * (the nine {@code deleteAllByPlayerId}/{@code deleteByPlayerId} calls, the
     * {@code performance_reports} scan, its own delete, and the {@code homework_completions} delete),
     * plus one additional {@code INSERT} per non-null {@code storage_key} the blob-enqueue below
     * issues (zero or more, via {@link BlobDeletionOutboxSupport#enqueue} — no dedup, so a repeated
     * key across reports would count more than once, though that is not expected in practice) — so
     * this method's real worst case is {@code (12 + M) ×} the configured seconds, where {@code M} is
     * the count of that child's {@code performance_reports} rows with a non-null {@code storage_key}
     * (not its total report count — a {@code PENDING_UPLOAD}/{@code UPLOAD_FAILED} report has none),
     * <strong>not</strong> a method-level ceiling. This does NOT make {@link #gdprEraseLockBudget}
     * (skillars-deferred-128 AC2) a hard ceiling on its own — it converts a previously-unbounded hang
     * into a bounded (if, under simultaneous multi-statement contention, possibly larger than that
     * nominal budget) one. The lock acquisition above and the final tombstone {@code save()} below
     * cannot themselves BLOCK on this {@code lock_timeout} (corrected 2026-09-23: they are not exempt
     * from it — {@code set_config(…, true)} is transaction-local and covers every statement through
     * commit, this one included) — the lock acquisition is already bounded by {@code
     * findByIdForUpdate}'s own {@code NOWAIT} + {@link PessimisticLockRetryer}, and the tombstone
     * write cannot contend an external lock since this transaction already holds the row exclusively.
     *
     * <p>{@code lockTimeoutSeconds} is a caller-supplied parameter, not read by this method itself
     * (skillars-deferred-132 AC2 Fix 9 — previously read here, once per call, via
     * {@link ConfigService#getBoundedLong(String, long, long, long)}). Both callers
     * ({@link #eraseTransactional}'s PLAYER branch, {@link #eraseParentChildren}'s loop) read it
     * exactly once per {@code erase()} call and pass it down, closing a repeated-read hazard the PARENT
     * loop had: a cache-expiry read triggering a real {@code configRepository.findAll()} DB round trip
     * once per child, all on the outer transaction's own connection, which already holds the
     * {@code main."user"} lock for that parent. Both callers still read it <strong>before</strong> this
     * method's own lock acquisition (mirroring {@code recalculateComposite}'s own identical reasoning
     * for the equivalent placement) — a cache-expiry read should not happen while a transaction already
     * holds the {@code player_profiles} lock. The {@code set_config} statement itself is issued
     * <strong>after</strong> the lock is held, mirroring {@code recalculateComposite}'s own placement
     * of that statement.
     *
     * <p><strong>skillars-deferred-129 AC1 (H2): the {@code role == PLAYER} branch call site in
     * {@link #eraseTransactional} now also catches a lock-timeout/contention failure here</strong> — equivalent
     * skip-and-alert semantics to {@link #eraseParentChildren}'s own two catches, rather than letting
     * it propagate and silently convert a previously-successful (if slow) erasure into an unalerted
     * {@code FAILED} on the most common account shape. See {@link DeleteStatementLockTimeoutException}
     * for how the two possible failure causes reaching either call site are distinguished.
     */
    private void deletePlayerDevelopmentData(Long playerId, long lockTimeoutSeconds) {
        // skillars-deferred-132 AC1 Fix 4: this method's own REQUIRES_NEW acquisition (via
        // requiresNewTemplate below) is the second of the two acquisitions this fix bounds — see
        // erase()'s own Javadoc for the shared mechanism and the tradeoffs it accepts.
        assertConnectionPoolNotSaturated(playerId, "deletePlayerDevelopmentData");
        // skillars-deferred-136 AC1: see erase()'s identical comment — same routing mechanism, this
        // method's own REQUIRES_NEW acquisition.
        RoutingDataSourceContext.set(DataSourceConfig.GDPR_ERASURE_DATASOURCE_KEY);
        try {
            deletePlayerDevelopmentDataInDedicatedPool(playerId, lockTimeoutSeconds);
        } finally {
            RoutingDataSourceContext.clear();
        }
    }

    private void deletePlayerDevelopmentDataInDedicatedPool(Long playerId, long lockTimeoutSeconds) {
        requiresNewTemplate.executeWithoutResult(status -> {
            var playerProfile = lockRetryer.withBoundedRetry("GdprErasureService.deletePlayerDevelopmentData",
                () -> playerProfileRepository.findByIdForUpdate(playerId)
                    .orElseThrow(() -> new ResourceNotFoundException("Player not found: " + playerId, "player_profile")));
            entityManager.refresh(playerProfile, LockModeType.PESSIMISTIC_WRITE);

            // Task 6: issued once, after the lock is held and before the first delete call — mirrors
            // recalculateComposite's own placement of this exact statement.
            entityManager.createNativeQuery("SELECT set_config('lock_timeout', ?1, true)")
                .setParameter(1, lockTimeoutSeconds + "s")
                .getSingleResult();

            // (Task 8, corrected empirically — see DeleteStatementLockTimeoutException's own Javadoc)
            // wraps every statement below that can trip the lock_timeout bound just set, so a
            // PessimisticLockingFailureException from HERE is distinguishable at the call sites from
            // one thrown by lockRetryer.withBoundedRetry above (the lock ACQUISITION itself) — cause-
            // class inspection alone cannot do this, since both failure modes share SQLSTATE 55P03 and
            // so the SAME cause class.
            try {
                // (H1 fix) this child's OWN local list — not erase()'s outer exportBlobKeysToDelete —
                // enqueued inside this same transaction below, atomically with the deletes that make
                // these keys unrecoverable elsewhere.
                List<String> childBlobKeys = new ArrayList<>();

                playerTimelineRepository.deleteByPlayerId(playerId);
                sluRepository.deleteAllByPlayerId(playerId);
                sluWeeklySnapshotRepository.deleteAllByPlayerId(playerId);
                playerSluWeeklySnapshotAppliedRepository.deleteAllByPlayerId(playerId);
                sluTargetRepository.deleteAllByPlayerId(playerId);
                neglectedSkillFlagRepository.deleteAllByPlayerId(playerId);
                playerRadarBaselineRepository.deleteAllByPlayerId(playerId);
                playerRadarCompositeRepository.deleteAllByPlayerId(playerId);
                radarAssessmentRepository.deleteAllByPlayerId(playerId);
                // Deferred-77 AC2: a PENDING_UPLOAD/UPLOAD_FAILED report may have no storage_key yet —
                // the query's own WHERE clause excludes those. skillars-deferred-90 AC13: enqueue the
                // key, don't delete from S3 inside this transaction. skillars-deferred-132 AC2 Fix 8:
                // a projection, not full-entity hydration — only the storage_key was ever read.
                childBlobKeys.addAll(performanceReportRepository.findStorageKeysByPlayerId(playerId));
                performanceReportRepository.deleteAllByPlayerId(playerId);
                homeworkCompletionRepository.deleteAllByPlayerId(playerId);

                // (H1 fix) enqueue INSIDE this transaction, atomically with the deletes above. Do NOT
                // call requestDrainAfterCommit() here — that stays a single call in erase()'s own
                // transaction, since it only needs to fire once per erase() invocation, not once per
                // child.
                blobDeletionOutboxSupport.enqueue(childBlobKeys);

                // Sticky tombstone — see this method's own Javadoc. Never reset back to null: a
                // player_profiles row is never "un-erased".
                playerProfile.setDevelopmentDataErasedAt(Instant.now());
                playerProfileRepository.save(playerProfile);
                // (code review 2026-09-23): without this, the tombstone UPDATE above is only flushed
                // at this REQUIRES_NEW transaction's commit-time — OUTSIDE this try block — so a
                // lock_timeout trip on THIS specific statement would surface as a plain
                // PessimisticLockingFailureException from the commit, uncaught here, and
                // misclassify at the call sites as CHILD_CONTENDED instead of
                // CHILD_DELETE_LOCK_TIMEOUT. The outbox INSERTs above don't need this: their IDENTITY
                // key generation already forces an immediate INSERT.
                entityManager.flush();
            } catch (CannotAcquireLockException e) {
                // (code review 2026-09-23): a GENUINE deadlock (SQLSTATE 40P01) — Hibernate's
                // LockAcquisitionException, which Spring's HibernateJpaDialect maps to this specific
                // subclass of PessimisticLockingFailureException — is not a lock_timeout trip and must
                // not be reported as one. Rethrow uncaught so it reaches the call sites' own
                // PessimisticLockingFailureException catch and is classified CHILD_CONTENDED, which is
                // the closer of the two existing reasons for a genuine lock conflict.
                throw e;
            } catch (PessimisticLockingFailureException e) {
                throw new DeleteStatementLockTimeoutException(e);
            }
        });
    }

    /**
     * skillars-deferred-129 AC1 (Task 8, corrected by this method's own {@code GdprErasureIT}
     * coverage): a plain {@code instanceof} check on {@link PessimisticLockingFailureException#getCause()}
     * cannot distinguish a downstream delete/scan/enqueue statement's own {@code lock_timeout} trip
     * from a {@link PessimisticLockRetryer}-budget-exhaustion failure acquiring the
     * {@code player_profiles} lock itself — <strong>both surface with the SAME cause class</strong>
     * ({@link PessimisticLockException org.hibernate.PessimisticLockException}), because Postgres
     * reports a {@code NOWAIT} lock-acquisition failure and a {@code lock_timeout} statement
     * expiry under the IDENTICAL SQLSTATE ({@code 55P03}) — empirically discovered when this AC's own
     * {@code erase_parentUser_contendedChild_skipsOthersProcessed_marksRequestFailed} (renamed
     * 2026-09-23; unchanged at the time of this empirical discovery) regressed from
     * {@code CHILD_CONTENDED} to {@code CHILD_DELETE_LOCK_TIMEOUT} under the cause-inspection-only
     * version of this classification, correcting this AC's original assumption (mirrored from
     * {@code RadarCompositeCalculationService}'s own precedent, which only ever needed to distinguish
     * a lock-TIMEOUT wait from a genuine DEADLOCK — two different SQLSTATEs — never a timeout from a
     * NOWAIT acquisition failure, which share one).
     *
     * <p>This marker is thrown ONLY for a {@link PessimisticLockingFailureException} caught AFTER the
     * {@code player_profiles} lock is already held (i.e. from the delete/scan/enqueue block, never
     * from {@code lockRetryer.withBoundedRetry}'s own lock-acquisition attempt above it) — catching
     * this specific type at {@link #eraseTransactional} and {@link #eraseParentChildren}'s own call sites, rather
     * than inspecting any cause, is what actually distinguishes the two failure modes deterministically.
     */
    private static final class DeleteStatementLockTimeoutException extends RuntimeException {
        DeleteStatementLockTimeoutException(PessimisticLockingFailureException cause) {
            super(cause);
        }
    }
}
