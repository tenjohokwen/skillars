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
import com.softropic.skillars.platform.booking.repo.CoachAvailabilityBlockRepository;
import com.softropic.skillars.platform.booking.repo.SessionCompletionDataRepository;
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
import java.util.concurrent.atomic.AtomicReference;
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
    private final SessionCompletionDataRepository sessionCompletionDataRepository;
    private final CoachAvailabilityBlockRepository coachAvailabilityBlockRepository;
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

    // skillars-deferred-137 AC1: bounds deletePlayerDevelopmentData's own cumulative statement-group
    // lock wait — see that method's own Javadoc for the "(12 + M) x lockTimeoutSeconds, not a
    // method-level ceiling" accounting this closes, and deletePlayerDevelopmentDataInDedicatedPool's
    // own Javadoc for the statement-grouping mechanism itself. Ports
    // RadarCompositeCalculationService.recalculateComposite's own CUMULATIVE_LOCK_WAIT_BUDGET — same
    // shape (Duration.ofSeconds(the sibling per-statement bound's own .max())), same reasoning (a
    // caller-configured lockTimeoutSeconds can be much smaller than this ceiling; this is the outer
    // bound on how much cumulative wait a single deletePlayerDevelopmentData call can ever rack up
    // regardless of how many statement groups it runs).
    //
    // Deliberately a plain instance field, not Radar's `private static final Duration` — for the
    // IDENTICAL reason gdprEraseLockBudget above already is one: this project's own established test
    // seam for a normally-fixed duration constant on a singleton Spring bean is
    // ReflectionTestUtils.setField(bean, "fieldName", ...), which needs a genuine instance field to
    // target. A real-Postgres IT proving the cumulative bail-out fires would otherwise need to hold a
    // contended lock for most of this constant's own 120s value to spend the budget down for real —
    // Radar's own identical mechanism has ZERO test coverage for its cumulative-exhaustion path
    // anywhere in this codebase today (verified by grep before choosing this shape), which this
    // divergence is specifically meant to avoid repeating here. `volatile` for the same cross-thread
    // visibility reason documented on gdprEraseLockBudget above.
    private volatile Duration cumulativeLockWaitBudget =
        Duration.ofSeconds(ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.max());

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
    // skillars-deferred-137 AC1: distinct from CHILD_DELETE_LOCK_TIMEOUT above — that reason means one
    // statement tripped its own per-statement lock_timeout; this one means enough statements (or slow
    // enough ones) ran that the CUMULATIVE budget below was exhausted before a later statement group
    // even started, which is a materially different signal for an operator triaging the alert queue
    // (single hot statement vs. broad, sustained contention across this child's whole delete sequence).
    private static final String CHILD_CUMULATIVE_LOCK_BUDGET_EXCEEDED = "CHILD_CUMULATIVE_LOCK_BUDGET_EXCEEDED";
    // skillars-deferred-157 AC1: the two `booking`-schema statements in
    // deletePlayerDevelopmentDataInDedicatedPool (session_completion_data, bookings) get their own pair
    // so an operator triaging the alert investigates booking.*, not development.*. Same lock_timeout vs.
    // genuine-deadlock split as CHILD_DELETE_LOCK_TIMEOUT/CHILD_CONTENDED, distinguished by
    // BookingSchemaLockException's `contended` flag. Still CHILD_*: these mean "one of N child player
    // profiles", exactly like their siblings.
    //
    // NOT "only booking data survived": the booking statements share ONE requiresNewTemplate
    // transaction with the development-schema groups, the outbox enqueue and the tombstone, so a trip
    // here rolls back ALL of that child's development-data deletion for this run.
    private static final String CHILD_BOOKING_LOCK_TIMEOUT = "CHILD_BOOKING_LOCK_TIMEOUT";
    private static final String CHILD_BOOKING_CONTENDED = "CHILD_BOOKING_CONTENDED";
    // skillars-deferred-133 AC1: markFailed's own catch-all reason — covers erase()'s pre-transaction
    // assertConnectionPoolNotSaturated throw and any other exception not one of the typed reasons
    // above (a GdprRequest/User "not found" RuntimeException, or anything unforeseen).
    private static final String UNCLASSIFIED_FAILURE = "UNCLASSIFIED_FAILURE";
    // (code review 2026-10-17, skillars-deferred-154 AC2): the COACH branch's own session-recap
    // anonymisation now has the same two-way classification the PLAYER branch has carried since
    // skillars-deferred-129 — a lock_timeout trip on the unindexed coach_id scan vs. a genuine
    // deadlock — distinguished by catch TYPE at the call site, not by cause inspection. Named for the
    // coach branch rather than reusing the CHILD_* reasons above, which mean "one of N child player
    // profiles" and would misdescribe this statement in the admin alert queue.
    //
    // skillars-deferred-156 AC7: split from one shared pair into three per-group pairs (four since
    // skillars-deferred-157 AC4 added AVAILABILITY_BLOCK below, five since skillars-12-6 AC5 added
    // BOOKING_PLAN_NOTE), taking the
    // LABELING half (only) of the PLAYER branch's statement-group pattern
    // (deletePlayerDevelopmentDataInDedicatedPool). Deliberately observability-only, and
    // deliberately NOT a full mirror of that precedent:
    //   * No durability. All five groups below share ONE requiresNewTemplate transaction, so a
    //     failure on a later group still rolls back an earlier group's already-succeeded scrub.
    //     That matches the PLAYER branch, whose groups get zero durability from grouping either
    //     (see its own Javadoc) — grouping buys labels there too, not independent commits.
    //   * No per-group timeout budgeting. The PLAYER branch additionally threads
    //     nextGroupLockTimeoutSecondsOrThrow/spendDownLockBudget cumulative-budget arithmetic
    //     through its groups; this branch does not, because its statements are a fixed,
    //     small set with no N-child multiplier to budget against. One transaction-scoped
    //     lock_timeout (set once below) therefore bounds all five groups — calling
    //     setStatementLockTimeout per group would be a no-op, since set_config's is_local=true
    //     makes the bound transaction-scoped rather than statement-scoped.
    // (code review of skillars-deferred-156) What this buys, precisely: knowing WHICH of the five
    // groups tripped. Nothing more. Do not read the group split as granting per-group commits or
    // per-group time bounds — it grants neither.
    private static final String COACH_RECAP_SCD_LOCK_TIMEOUT = "COACH_RECAP_SCD_LOCK_TIMEOUT";
    private static final String COACH_RECAP_SCD_CONTENDED = "COACH_RECAP_SCD_CONTENDED";
    private static final String COACH_RECAP_TIMELINE_LOCK_TIMEOUT = "COACH_RECAP_TIMELINE_LOCK_TIMEOUT";
    private static final String COACH_RECAP_TIMELINE_CONTENDED = "COACH_RECAP_TIMELINE_CONTENDED";
    // skillars-deferred-156 AC7: group 3 bundles performance_reports' scrub together with the
    // blob-deletion outbox enqueue — BlobDeletionOutboxSupport's own Javadoc requires enqueue to run
    // inside the SAME transaction as the write that nulls storage_key, or a later retry finds no
    // key to delete and the S3 blob is orphaned permanently. Under normal DML-only contention only
    // the anonymizeByCoachId UPDATE in this group takes row locks that can conflict:
    // findStorageKeysByCoachId is a plain SELECT (no FOR UPDATE) and the outbox enqueue is plain
    // INSERTs with no unique index beyond the PK. (skillars-deferred-157 AC9) That is not absolute —
    // the SELECT still takes ACCESS SHARE, which conflicts with a concurrent ACCESS EXCLUSIVE lock
    // (ALTER TABLE, VACUUM FULL, REINDEX, …) and so can trip lock_timeout. The grouping is correct
    // either way: the SELECT must share the UPDATE's transaction for BlobDeletionOutboxSupport's
    // reasons above, and a trip in it is correctly labelled REPORT.
    /**
     * (code review of skillars-deferred-156) Which statement group the coach-recap scrub is currently
     * executing, so the shared catch block can pick the matching reason-code pair. An enum rather than
     * the three magic strings this replaced: the switch over it is compiler-checked exhaustive, so a
     * new group cannot be silently absorbed into another group's codes, and a typo is a build error
     * instead of a mislabelled admin alert. {@code AVAILABILITY_BLOCK} (skillars-deferred-157 AC4) and
     * {@code BOOKING_PLAN_NOTE} (skillars-12-6 AC5) are not session recaps — the name is kept to minimise diff; read it as "coach-scrub group".
     */
    private enum CoachRecapGroup { SCD, TIMELINE, REPORT, AVAILABILITY_BLOCK, BOOKING_PLAN_NOTE }

    private static final String COACH_RECAP_REPORT_LOCK_TIMEOUT = "COACH_RECAP_REPORT_LOCK_TIMEOUT";
    private static final String COACH_RECAP_REPORT_CONTENDED = "COACH_RECAP_REPORT_CONTENDED";
    // skillars-deferred-157 AC4: group 4 nulls booking.coach_availability_blocks.reason — free text
    // the coach typed when blocking out time, not session narrative, hence no COACH_RECAP_ prefix.
    private static final String COACH_AVAILABILITY_BLOCK_LOCK_TIMEOUT = "COACH_AVAILABILITY_BLOCK_LOCK_TIMEOUT";
    private static final String COACH_AVAILABILITY_BLOCK_CONTENDED = "COACH_AVAILABILITY_BLOCK_CONTENDED";
    // skillars-12-6 AC5: group 5 nulls booking.bookings.coach_plan_note — the coach's private plan
    // note. Shares the one transaction: a trip here also rolls back the other four groups' scrubs
    // (labels only, no per-group durability — the documented design above).
    private static final String COACH_PLAN_NOTE_LOCK_TIMEOUT = "COACH_PLAN_NOTE_LOCK_TIMEOUT";
    private static final String COACH_PLAN_NOTE_CONTENDED = "COACH_PLAN_NOTE_CONTENDED";

    // skillars-deferred-155 code review, Decision 2(a): performance_reports.next_steps is NOT NULL, so
    // the coach-erasure scrub overwrites it with this placeholder rather than nulling it — same
    // convention as the "Deleted"/"User" writes on main.user above. Well inside the column's 500 chars.
    private static final String ERASED_REPORT_NEXT_STEPS = "[Removed — coach account erased]";
    // Parent-requested child erasure (V172): the placeholder a scrubbed child profile's name becomes.
    static final String ERASED_PLAYER_NAME = "Erased player";

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
     *
     * <p><b>What this erases, by branch</b> (skillars-deferred-155 AC9 — this inventory was previously
     * only in the inline comments further down, which AC9 and its own Task 3.3 both asked to have
     * summarised here):
     * <ul>
     *   <li><b>Always:</b> {@code main.user} identity fields (anonymised to
     *       {@code deleted.<id>@erased.io} / "Deleted User"), messages, review bodies, refresh tokens,
     *       expired GDPR requests, and the S3 blobs of previously COMPLETED export requests.</li>
     *   <li><b>COACH:</b> {@code marketplace.coach_profiles} free text, plus — all inside one
     *       {@code REQUIRES_NEW} block under a {@code lock_timeout} bound — three copies of the coach's
     *       own authored narrative: {@code booking.session_completion_data} recap columns
     *       (skillars-deferred-154 AC2), {@code development.player_timeline_events.metadata}'s
     *       independent second copy of the same recap, and {@code development.performance_reports},
     *       whose {@code next_steps} is replaced and whose PDF blob is enqueued for deletion. That last
     *       one SCRUBS and keeps the row rather than deleting it: the table is a joint coach+player
     *       artifact, so a delete would destroy the player's own record (skillars-deferred-155 code
     *       review, Decision 2(a)). Separately, the same block also nulls
     *       {@code booking.coach_availability_blocks.reason} — free text the coach typed when blocking
     *       out time, not narrative — keeping the rows themselves (skillars-deferred-157 AC3).</li>
     *   <li><b>PLAYER / PARENT:</b> the player's development data wholesale via
     *       {@link #deletePlayerDevelopmentData}, including {@code player_timeline_events} row deletes
     *       and {@code performance_reports} deletes — deletion is correct there because the data
     *       subject owns those rows.</li>
     * </ul>
     *
     * <p>A contended scrub in either the COACH or the PLAYER branch is skipped, alerted, and marks the
     * request {@code FAILED} rather than {@code COMPLETED}, so the daily
     * {@code GdprErasureRetryScheduler} re-drives it — see the {@code skipped} flag below. Reporting
     * {@code COMPLETED} while data survives would be an Article 17 correctness regression.
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

        // skillars-deferred-155 code review, Decision 1(a): declared HERE, above the COACH block — not
        // just above the PLAYER branch further down, which is where it used to live. The coach-side
        // scrub's own catch has to be able to set it too. Before this fix the flag was declared after
        // that block, so a contended coach scrub was alerted but the request was still written
        // COMPLETED at the terminal status below, and retryFailedErasures selects only
        // status = "FAILED" — meaning the skip was never re-driven and the coach's recap text, timeline
        // metadata and report rows survived indefinitely while the request machine-readably claimed
        // success. Same Article 17 reasoning this method already documents for the CHILD case.
        AtomicBoolean skipped = new AtomicBoolean(false);

        // Anonymise coach_profiles (if coach)
        coachProfileRepository.findByUserId(userId).ifPresent(cp -> {
            cp.setBio(null);
            cp.setCity(null);
            cp.setDistrict(null);
            coachProfileRepository.save(cp);

            // skillars-deferred-154 AC2: booking.session_completion_data.{what_we_did, whats_next}
            // are coach-authored free text, but anonymizeByPlayerId (used by the PLAYER branch
            // below) is keyed on playerId — a coach's own erasure never touched this table, so their
            // authored recap text survived their own erasure indefinitely. (skillars-deferred-165
            // AC12: the voice_note_text column is unmapped by JPA now, but legacy rows still hold
            // coach-authored text in it, so anonymizeByCoachId keeps nulling it via native SQL until
            // the column is dropped.)
            //
            // skillars-deferred-155 AC9: two more copies of the same underlying fact, scrubbed in
            // the same unit. (1) development.player_timeline_events.metadata carries a SECOND,
            // independent copy of the recap (written by TimelineEventListener at session-completion
            // time, not wrap-up time) that anonymizeByCoachId above never touches — closing the gap
            // skillars-deferred-154's own code review recorded as "its own story, not a review
            // patch". (2) development.performance_reports now also carries the coach's verbatim
            // recap history (skillars-deferred-155 AC1/AC2's own new "Session Notes" section) and
            // was purged only by the PLAYER erasure branch (deletePlayerDevelopmentData) — never the
            // coach's.
            //
            // (skillars-deferred-155 code review, Decision 2(a)) The report half SCRUBS and keeps the
            // row; it deliberately does NOT mirror the PLAYER branch's findStorageKeys→delete→enqueue
            // sequence, even though that is where it was first copied from. performance_reports is a
            // joint artifact (coach_id NOT NULL *and* player_id NOT NULL), so a delete keyed on coachId
            // destroys the player's own development record because a third party asked for erasure.
            // Deleting is correct on the PLAYER branch only because the data subject owns the row
            // there. Scrub-and-keep also matches the two statements above it. The blob is still
            // enqueued for deletion — the PDF embeds the coach's verbatim recap text and cannot be
            // scrubbed in place — so what survives is a row with a placeholder next_steps and no
            // storage_key, which keeps the player's report history and its PERFORMANCE_REPORT timeline
            // entries internally consistent.
            //
            // (code review 2026-10-17, two findings) This statement runs in its OWN REQUIRES_NEW
            // transaction rather than inline in eraseTransactional's, for two independent reasons:
            //
            // 1. setStatementLockTimeout issues set_config('lock_timeout', ?, true) — is_local = true
            //    is TRANSACTION-scoped, NOT per-statement (see that method's own Javadoc: "issues the
            //    transaction-scoped lock_timeout bound ahead of one statement group", and
            //    deletePlayerDevelopmentData's: "transaction-local and covers every statement through
            //    commit, this one included"). Set inline, the 5s bound would have silently governed
            //    every remaining write in eraseTransactional — messageRepository.deleteAllBySenderId,
            //    resolveOpenAlertsForDeletedMessages, the two coachReviewRepository writes,
            //    markAllUsedByUserId, deleteExpiredByUserId, the terminal gdprRequestRepository.save,
            //    and the commit-time flush of the main."user" and coach_profiles UPDATEs above — none
            //    of which previously carried any lock_timeout at all on a COACH erasure. The PLAYER
            //    branch's identical call site is safe only because it already sits inside
            //    requiresNewTemplate, which is what confines the setting there; this call site did not.
            // 2. session_completion_data carries no index on coach_id (only idx_scd_booking_id), so
            //    this UPDATE is a sequential scan taking a row lock on every matching row — contention
            //    is exactly why it needs the bound. But with no catch, a lock_timeout trip (55P03)
            //    propagated straight out of eraseTransactional and rolled back the ENTIRE erasure —
            //    user anonymisation, coach-profile anonymisation, the message/review deletes, the
            //    PROCESSING status write — landing in GdprEventListener's generic catch, which only
            //    markFailed + log.errors: no reason code, no AdminAlert, i.e. an unalerted FAILED.
            //    Mirrors the PLAYER branch's own skip-and-alert instead: the recap anonymisation is
            //    skipped and alerted, and the rest of the erasure still commits. The UPDATE is
            //    idempotent and one-way, so the daily GdprErasureRetryScheduler simply re-runs it.
            //
            // raiseErasureAlert must stay OUTSIDE the requiresNewTemplate boundary — see its own Javadoc
            // (skillars-deferred-135 AC1) for why catching inside it defeats the isolation entirely.
            long coachRecapLockTimeoutSeconds = configService.getBoundedLong(
                ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.key(), 5L, 2L, 120L);
            // skillars-deferred-156 AC7: identifies which of the five groups below was executing
            // when a PessimisticLockingFailureException propagated out of the transaction, so the
            // catch block can pick that group's own reason-code pair. All five groups still run
            // inside ONE requiresNewTemplate transaction (see the constants' own Javadoc above) —
            // Postgres aborts the whole transaction on the first statement error regardless, so
            // there is never a question of "did group 1 already commit when group 2 failed."
            AtomicReference<CoachRecapGroup> coachRecapCurrentGroup = new AtomicReference<>();
            try {
                requiresNewTemplate.executeWithoutResult(status -> {
                    // (code review of skillars-deferred-156) Label FIRST, then set the bound ONCE for
                    // the whole transaction rather than once per group: set_config's is_local=true
                    // bound is transaction-scoped, so the per-group calls this replaced were provable
                    // no-ops (same value, already in effect). Keeping the set("SCD") ahead of it
                    // preserves the property that even a failure inside set_config itself attributes
                    // to a real group rather than leaving the label null.
                    coachRecapCurrentGroup.set(CoachRecapGroup.SCD);
                    setStatementLockTimeout(coachRecapLockTimeoutSeconds);
                    // skillars-12-7 code review (Decision 1(a)): the tombstone BookingCompletionService
                    // .recapEditability checks. It must commit with the scrub below, not with the outer
                    // user.setLocked(true) above, which only becomes visible at the outer commit -- a
                    // recap edit landing between the two commits would otherwise write the text back.
                    // Same SCD group: it is the first statement of the recap scrub.
                    coachProfileRepository.markRecapDataErased(cp.getId());
                    sessionCompletionDataRepository.anonymizeByCoachId(cp.getId());

                    coachRecapCurrentGroup.set(CoachRecapGroup.TIMELINE);
                    playerTimelineRepository.anonymizeMetadataByCoachId(cp.getId());

                    coachRecapCurrentGroup.set(CoachRecapGroup.REPORT);
                    List<String> coachReportBlobKeys = performanceReportRepository.findStorageKeysByCoachId(cp.getId());
                    performanceReportRepository.anonymizeByCoachId(cp.getId(), ERASED_REPORT_NEXT_STEPS);
                    if (!coachReportBlobKeys.isEmpty()) {
                        blobDeletionOutboxSupport.enqueue(coachReportBlobKeys);
                    }

                    // skillars-deferred-157 AC4: inside this block rather than inline, so it inherits
                    // the lock_timeout bound and its own alert label. coach_id holds the
                    // coach_profiles.id (AvailabilityService.addBlock). No lock-order cycle with
                    // AvailabilityService's writers: they lock coach_profiles FOR UPDATE first, and since
                    // skillars-12-7 so does this transaction (markRecapDataErased, its first statement)
                    // -- same order. The outer save(cp) above is flushed only at the outer commit, so
                    // that row lock is not held by the outer transaction while this one waits on it.
                    coachRecapCurrentGroup.set(CoachRecapGroup.AVAILABILITY_BLOCK);
                    coachAvailabilityBlockRepository.anonymizeReasonByCoachId(cp.getId());

                    // skillars-12-6 AC5: the coach's private per-booking plan note. Bulk UPDATE bumps
                    // version (see the repository method) so a concurrently loaded Booking cannot
                    // resurrect the erased text.
                    coachRecapCurrentGroup.set(CoachRecapGroup.BOOKING_PLAN_NOTE);
                    bookingRepository.anonymizeCoachPlanNoteByCoachId(cp.getId());
                });
            } catch (PessimisticLockingFailureException e) {
                // CannotAcquireLockException (SQLSTATE 40P01, a genuine deadlock) is a SUBCLASS of
                // PessimisticLockingFailureException and is not a lock_timeout trip — classify the two
                // apart by catch type, exactly as the PLAYER branch does, so an operator triaging the
                // alert queue can tell one hot statement from a real deadlock. The group identifies
                // WHICH statement; the catch type identifies WHY.
                boolean contended = e instanceof CannotAcquireLockException;
                // (code review of skillars-deferred-156) The null-coalesce is load-bearing, not
                // defensive noise: a switch over a null selector throws NPE, and `default` does NOT
                // catch null (`case null` is not available on this project's Java 17 toolchain). The
                // label is null only if the template raises PessimisticLockingFailureException before
                // the lambda body runs at all — transaction acquisition from the dedicated pool.
                // Without this guard that NPE would be thrown from INSIDE this catch block, skipping
                // raiseErasureAlert and skipped.set(true) below, i.e. producing exactly the
                // unalerted-silent-failure outcome this block exists to prevent. Attributed to SCD
                // because nothing in any group ran; SCD is the first thing that would have.
                CoachRecapGroup group = coachRecapCurrentGroup.get();
                String reason = switch (group == null ? CoachRecapGroup.SCD : group) {
                    case SCD -> contended ? COACH_RECAP_SCD_CONTENDED : COACH_RECAP_SCD_LOCK_TIMEOUT;
                    case TIMELINE -> contended ? COACH_RECAP_TIMELINE_CONTENDED : COACH_RECAP_TIMELINE_LOCK_TIMEOUT;
                    case REPORT -> contended ? COACH_RECAP_REPORT_CONTENDED : COACH_RECAP_REPORT_LOCK_TIMEOUT;
                    case AVAILABILITY_BLOCK -> contended
                        ? COACH_AVAILABILITY_BLOCK_CONTENDED : COACH_AVAILABILITY_BLOCK_LOCK_TIMEOUT;
                    case BOOKING_PLAN_NOTE -> contended
                        ? COACH_PLAN_NOTE_CONTENDED : COACH_PLAN_NOTE_LOCK_TIMEOUT;
                };
                log.warn("[GDPR_ERASURE] COACH-branch coachId={} session-recap/report/availability-block/plan-note "
                        + "scrub failed in group={} (reason={}) — skipping it; the rest of the erasure still "
                        + "commits, the request is reported FAILED, and the daily retry scheduler re-runs it. "
                        + "requestId={} userId={}",
                    cp.getId(), coachRecapCurrentGroup.get(), reason, requestId, userId);
                raiseErasureAlert(requestId, reason);
                // skillars-deferred-155 code review, Decision 1(a): gate the terminal status. Without
                // this the request is written COMPLETED below even though the coach's recap text,
                // timeline metadata and report rows all survive, and retryFailedErasures — which
                // selects only status = "FAILED" — never re-drives the block. Every statement in the
                // lambda above is idempotent, so the retry is safe to re-run wholesale.
                skipped.set(true);
            }
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
        // (code review 2026-09-23, Decision 1) true only for CHILD_CONTENDED/CHILD_DELETE_LOCK_TIMEOUT/
        // CHILD_CUMULATIVE_LOCK_BUDGET_EXCEEDED/CHILD_BOOKING_LOCK_TIMEOUT/CHILD_BOOKING_CONTENDED skips
        // and the COACH block's COACH_RECAP_*/COACH_AVAILABILITY_BLOCK_* skips — never for
        // CHILD_VANISHED, whose data genuinely was already erased by a concurrent request. Gates the
        // terminal status below: a skip here means data survives this run, so the request must not be
        // reported COMPLETED. An AtomicBoolean, not a plain local, because the PLAYER branch's catch
        // runs inside the ifPresentOrElse lambda below.
        // (skillars-deferred-155 code review, Decision 1(a)) Now declared above the COACH block rather
        // than here, so that branch's catch can gate the terminal status too — see its comment there.
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
                        } catch (CumulativeLockBudgetExhaustedException e) {
                            // skillars-deferred-137 AC1: distinct from the catch above — this fires
                            // when enough statements (or slow enough ones) ran to exhaust the
                            // cumulative budget, not because any single statement tripped its own
                            // per-statement lock_timeout. Same skip-and-alert semantics otherwise.
                            log.warn("[GDPR_ERASURE] PLAYER-branch playerId={} development-data deletion "
                                    + "exceeded its cumulative lock-wait budget partway through (single-"
                                    + "profile branch, not a skip-one-of-N-siblings case) — skipping, "
                                    + "requestId={} userId={}", pp.getId(), requestId, userId, e);
                            raiseErasureAlert(requestId, CHILD_CUMULATIVE_LOCK_BUDGET_EXCEEDED);
                            skipped.set(true);
                        } catch (BookingSchemaLockException e) {
                            // skillars-deferred-157 AC2: one of the two booking-schema statements
                            // tripped — labelled CHILD_BOOKING_* so triage looks at booking.*, with the
                            // same skip-and-alert semantics as the catches above.
                            String reason = e.contended ? CHILD_BOOKING_CONTENDED : CHILD_BOOKING_LOCK_TIMEOUT;
                            log.warn("[GDPR_ERASURE] PLAYER-branch playerId={} booking.* anonymisation "
                                    + "(session_completion_data/bookings) failed (reason={}) — the whole "
                                    + "development-data deletion for this player was rolled back with it "
                                    + "(one shared transaction), skipping, requestId={} userId={}",
                                pp.getId(), reason, requestId, userId, e);
                            raiseErasureAlert(requestId, reason);
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
        // child's development data was skipped for a CHILD_CONTENDED/CHILD_DELETE_LOCK_TIMEOUT/
        // CHILD_CUMULATIVE_LOCK_BUDGET_EXCEEDED/CHILD_BOOKING_LOCK_TIMEOUT/CHILD_BOOKING_CONTENDED
        // reason, or the COACH block skipped for a COACH_RECAP_*/COACH_AVAILABILITY_BLOCK_* reason:
        // that data survives (for a child, with developmentDataErasedAt still NULL), so COMPLETED
        // would be an Article 17 correctness regression (machine-readably claiming "done" when it is not). The
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
     * Parent-requested erasure of ONE parent-owned (shadow) child profile (V172 {@code player_id}
     * scope). Same outer shape as {@link #erase}: the connection-pool pre-check and the dedicated GDPR
     * pool routing both apply to {@link #eraseChildTransactional}'s own REQUIRES_NEW acquisition.
     */
    public void eraseChild(UUID requestId, Long parentUserId, Long playerId) {
        assertConnectionPoolNotSaturated(requestId, "eraseChild");
        RoutingDataSourceContext.set(DataSourceConfig.GDPR_ERASURE_DATASOURCE_KEY);
        try {
            self.eraseChildTransactional(requestId, parentUserId, playerId);
        } finally {
            RoutingDataSourceContext.clear();
        }
    }

    /**
     * What a child erasure erases — exactly the per-child hook the PARENT branch already runs for each
     * child ({@link #deletePlayerDevelopmentData}: timeline, SLU, radar, performance reports + their
     * blobs, homework completions, the child's {@code session_completion_data} recap and the free text
     * on its bookings), plus, because the parent account (and so the profile row) survives here, the
     * profile's own identity ({@code scrubProfileIdentity}: name, date-of-birth precision), committed
     * atomically with the {@code developmentDataErasedAt} tombstone that also hides the profile from
     * the parent's child list. Deliberately NOT touched: the booking rows themselves and any payment /
     * credit ledger rows (the parent's financial record), messages (authored by the parent or coach,
     * never by a shadow child, which has no account) and videos (owned by an uploader account, not
     * linked to a player profile).
     *
     * <p>Failure handling mirrors the PLAYER branch: a contended/timed-out scrub is alerted and the
     * request is written FAILED so {@link #retryFailedErasures} re-drives it (scope-aware — see there).
     * Ownership is re-checked here, against the row about to be erased, not only at request time.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void eraseChildTransactional(UUID requestId, Long parentUserId, Long playerId) {
        GdprRequest request = gdprRequestRepository.findById(requestId)
            .orElseThrow(() -> new RuntimeException("GdprRequest not found: " + requestId));
        request.setStatus("PROCESSING");
        gdprRequestRepository.save(request);

        boolean skipped = false;
        PlayerProfile child = playerProfileRepository.findById(playerId).orElse(null);
        if (child == null) {
            log.warn("[GDPR_CHILD_ERASURE] playerId={} not found — nothing to erase, requestId={}",
                playerId, requestId);
        } else if (child.getParentId() == null || !child.getParentId().equals(parentUserId)) {
            // Ownership changed between request and processing (should be impossible — a profile's
            // parent_id is never reassigned — but never erase a profile the requester does not own).
            throw new IllegalStateException("GDPR child erasure requestId=" + requestId
                + ": playerId=" + playerId + " is not owned by parentUserId=" + parentUserId);
        } else {
            long lockTimeoutSeconds = configService.getBoundedLong(
                ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.key(), 5L, 2L, 120L);
            // Detach BEFORE the inner REQUIRES_NEW transaction locks and rewrites the same row: this
            // outer persistence context must not hold (and later flush) a stale pre-scrub snapshot.
            entityManager.detach(child);
            try {
                deletePlayerDevelopmentData(playerId, lockTimeoutSeconds, true);
            } catch (ResourceNotFoundException e) {
                log.warn("[GDPR_CHILD_ERASURE] playerId={} vanished before its lock could be acquired — "
                    + "treating as already erased, requestId={}", playerId, requestId);
                raiseErasureAlert(requestId, CHILD_VANISHED);
            } catch (DeleteStatementLockTimeoutException | PessimisticLockingFailureException e) {
                String reason = e instanceof DeleteStatementLockTimeoutException
                    ? CHILD_DELETE_LOCK_TIMEOUT : CHILD_CONTENDED;
                log.warn("[GDPR_CHILD_ERASURE] playerId={} erasure skipped (reason={}), requestId={}",
                    playerId, reason, requestId);
                raiseErasureAlert(requestId, reason);
                skipped = true;
            } catch (CumulativeLockBudgetExhaustedException e) {
                log.warn("[GDPR_CHILD_ERASURE] playerId={} exceeded its cumulative lock-wait budget, "
                    + "requestId={}", playerId, requestId, e);
                raiseErasureAlert(requestId, CHILD_CUMULATIVE_LOCK_BUDGET_EXCEEDED);
                skipped = true;
            } catch (BookingSchemaLockException e) {
                String reason = e.contended ? CHILD_BOOKING_CONTENDED : CHILD_BOOKING_LOCK_TIMEOUT;
                log.warn("[GDPR_CHILD_ERASURE] playerId={} booking.* anonymisation failed (reason={}), "
                    + "requestId={}", playerId, reason, requestId, e);
                raiseErasureAlert(requestId, reason);
                skipped = true;
            }
        }

        blobDeletionOutboxSupport.requestDrainAfterCommit();

        request.setStatus(skipped ? "FAILED" : "COMPLETED");
        request.setCompletedAt(Instant.now());
        if (skipped) {
            request.setFailedAt(Instant.now());
        }
        gdprRequestRepository.save(request);
        log.info("[GDPR_CHILD_ERASURE_COMPLETED] requestId={} parentUserId={} playerId={} skipped={}",
            requestId, parentUserId, playerId, skipped);
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
                // V172: a child-scoped row (playerId set) must be re-driven as a CHILD erasure — userId
                // is the parent there, and erase(requestId, userId) would erase the parent's account.
                Long playerId = candidate.getPlayerId();
                // story review: the try now wraps the dedup check and the retry-count increment too,
                // not just erase() — an exception from either previously propagated out of this whole
                // sweep, aborting every remaining candidate instead of just this one.
                try {
                    boolean activeDuplicate = playerId != null
                        ? gdprRequestRepository.existsByPlayerIdAndRequestTypeAndStatusIn(
                            playerId, "ERASURE", List.of("PENDING", "PROCESSING"))
                        : gdprRequestRepository.existsByUserIdAndPlayerIdIsNullAndRequestTypeAndStatusIn(
                            userId, "ERASURE", List.of("PENDING", "PROCESSING"));
                    if (activeDuplicate) {
                        skippedDedup++;
                        continue;
                    }
                    self.incrementRetryCount(requestId);
                    if (playerId != null) {
                        eraseChild(requestId, userId, playerId);
                    } else {
                        erase(requestId, userId);
                    }
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
     *     {@link #CHILD_DELETE_LOCK_TIMEOUT}/{@link #CHILD_CUMULATIVE_LOCK_BUDGET_EXCEEDED}/
     *     {@link #CHILD_BOOKING_LOCK_TIMEOUT}/{@link #CHILD_BOOKING_CONTENDED} reason (code review
     *     2026-09-23, Decision 1) — signals
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
            } catch (CumulativeLockBudgetExhaustedException e) {
                // skillars-deferred-137 AC1: distinct from the catch above — this child exhausted its
                // cumulative lock-wait budget across enough (or slow enough) statement groups, not
                // because any single statement tripped its own per-statement lock_timeout.
                skipped++;
                anyUnrecoverableSkip = true;
                log.warn("[GDPR_ERASURE] PARENT-branch child playerId={} exceeded its cumulative "
                        + "lock-wait budget partway through its own delete sequence — skipping "
                        + "(retryable on a later re-drive), requestId={} userId={}",
                    child.getId(), requestId, userId, e);
                raiseErasureAlert(requestId, CHILD_CUMULATIVE_LOCK_BUDGET_EXCEEDED);
            } catch (BookingSchemaLockException e) {
                // skillars-deferred-157 AC2: see the PLAYER branch's identical catch.
                skipped++;
                anyUnrecoverableSkip = true;
                String reason = e.contended ? CHILD_BOOKING_CONTENDED : CHILD_BOOKING_LOCK_TIMEOUT;
                log.warn("[GDPR_ERASURE] PARENT-branch child playerId={} booking.* anonymisation "
                        + "(session_completion_data/bookings) failed (reason={}) — the child's whole "
                        + "development-data deletion was rolled back with it (one shared transaction), "
                        + "skipping (retryable on a later re-drive), requestId={} userId={}",
                    child.getId(), reason, requestId, userId, e);
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
     * queue UI. The complete set of reasons (skillars-deferred-157 AC2 — keep in step with the
     * comment in {@code AdminQueueService.buildSummary}):
     * <ul>
     *   <li>{@code DEADLINE_EXCEEDED} — {@link #eraseParentChildren}'s loop budget exhausted;</li>
     *   <li>{@link #CHILD_VANISHED}, {@link #CHILD_CONTENDED}, {@link #CHILD_DELETE_LOCK_TIMEOUT}
     *       (skillars-deferred-129 AC1), {@link #CHILD_CUMULATIVE_LOCK_BUDGET_EXCEEDED}
     *       (skillars-deferred-137 AC1) — a child player's {@code development.*} deletion;</li>
     *   <li>{@link #CHILD_BOOKING_LOCK_TIMEOUT}, {@link #CHILD_BOOKING_CONTENDED}
     *       (skillars-deferred-157 AC1) — the same deletion's {@code booking.*} statements;</li>
     *   <li>{@code COACH_RECAP_SCD_*}, {@code COACH_RECAP_TIMELINE_*}, {@code COACH_RECAP_REPORT_*}
     *       (skillars-deferred-156 AC7) {@code COACH_AVAILABILITY_BLOCK_*} (skillars-deferred-157
     *       AC4) and {@code COACH_PLAN_NOTE_*} (skillars-12-6 AC5), each a {@code _LOCK_TIMEOUT}/{@code _CONTENDED} pair — the coach-erasure scrub;</li>
     *   <li>{@link #UNCLASSIFIED_FAILURE} (skillars-deferred-133 AC1) — {@link #markFailed}'s
     *       catch-all.</li>
     * </ul>
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
     * <p><strong>skillars-deferred-129 AC1: each statement below is bounded by a
     * {@code lock_timeout}, not the method's total wait.</strong> Postgres {@code lock_timeout} is
     * per-STATEMENT — mirroring {@code RadarCompositeCalculationService.recalculateComposite}'s own
     * documented trap (see that method's Javadoc) — so a {@code set_config} call bounds each of this
     * method's ~12 independently-timeout-able statements individually. There are 12 fixed delete/scan
     * statements below (the nine {@code deleteAllByPlayerId}/{@code deleteByPlayerId} calls, the
     * {@code performance_reports} scan, its own delete, and the {@code homework_completions} delete),
     * plus one additional {@code INSERT} per non-null {@code storage_key} the blob-enqueue below
     * issues (zero or more, via {@link BlobDeletionOutboxSupport#enqueue} — no dedup, so a repeated
     * key across reports would count more than once, though that is not expected in practice), so
     * <strong>before skillars-deferred-137 AC1</strong> this method's real worst case was
     * {@code (12 + M) ×} the configured per-statement seconds — {@code M} being the count of that
     * child's {@code performance_reports} rows with a non-null {@code storage_key} (not its total
     * report count — a {@code PENDING_UPLOAD}/{@code UPLOAD_FAILED} report has none) — with no
     * method-level ceiling at all.
     *
     * <p><strong>skillars-deferred-137 AC1 closes that gap:</strong> the 12+M statements are batched
     * into four logical groups (see {@link #deletePlayerDevelopmentDataInDedicatedPool}'s own Javadoc
     * for the exact grouping and the per-group budget-shrink mechanism, ported from
     * {@code RadarCompositeCalculationService.recalculateComposite}'s own cumulative spend-down), so
     * this method's real worst case is now bounded by {@link #cumulativeLockWaitBudget} regardless of
     * how large {@code M} grows — a group whose shrunk timeout would fall below the per-statement
     * floor instead throws {@link CumulativeLockBudgetExhaustedException}, rolling back this whole
     * REQUIRES_NEW call (see that method's own Javadoc for why a mid-child bail-out has no
     * partial-commit risk here) rather than letting the wait keep growing unbounded. This does NOT
     * make {@link #gdprEraseLockBudget} (skillars-deferred-128 AC2, a DIFFERENT budget scoped to
     * {@link #eraseParentChildren}'s own PARENT loop, sampled only BETWEEN children) redundant — that
     * budget still cannot interrupt a single child's own in-progress call, which is exactly the gap
     * {@link #cumulativeLockWaitBudget} closes at the single-child level. The lock acquisition above
     * and the final tombstone {@code save()} below cannot themselves BLOCK on this {@code lock_timeout}
     * (corrected 2026-09-23: they are not exempt from it — {@code set_config(…, true)} is
     * transaction-local and covers every statement through commit, this one included) — the lock
     * acquisition is already bounded by {@code findByIdForUpdate}'s own {@code NOWAIT} + {@link
     * PessimisticLockRetryer}, and the tombstone write cannot contend an external lock since this
     * transaction already holds the row exclusively.
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
        deletePlayerDevelopmentData(playerId, lockTimeoutSeconds, false);
    }

    /**
     * @param scrubProfileIdentity {@code true} only for a parent-requested erasure of a single child
     *     profile ({@link #eraseChildTransactional}): the profile row itself survives (bookings and the
     *     parent's financial history still reference it), so its identifying fields are scrubbed in the
     *     SAME inner transaction as the tombstone — atomically, so a profile can never read as erased
     *     (hidden from the parent's child list) while still carrying the child's name. {@code false}
     *     keeps the PLAYER/PARENT branches' pre-existing behaviour unchanged.
     */
    private void deletePlayerDevelopmentData(Long playerId, long lockTimeoutSeconds, boolean scrubProfileIdentity) {
        // skillars-deferred-132 AC1 Fix 4: this method's own REQUIRES_NEW acquisition (via
        // requiresNewTemplate below) is the second of the two acquisitions this fix bounds — see
        // erase()'s own Javadoc for the shared mechanism and the tradeoffs it accepts.
        assertConnectionPoolNotSaturated(playerId, "deletePlayerDevelopmentData");
        // skillars-deferred-136 AC1: see erase()'s identical comment — same routing mechanism, this
        // method's own REQUIRES_NEW acquisition.
        RoutingDataSourceContext.set(DataSourceConfig.GDPR_ERASURE_DATASOURCE_KEY);
        try {
            deletePlayerDevelopmentDataInDedicatedPool(playerId, lockTimeoutSeconds, scrubProfileIdentity);
        } finally {
            RoutingDataSourceContext.clear();
        }
    }

    /**
     * skillars-deferred-137 AC1: the ~12 fixed statements plus the variable-length blob-enqueue are
     * grouped into four logical steps (mirroring this method's own pre-existing statement ORDER
     * exactly, just batched) rather than bounded per-individual-statement like
     * {@code RadarCompositeCalculationService.recalculateComposite}'s own per-SKILL grain — this
     * method's 12 statements have no natural per-skill-style grouping, so a coarser, table-domain
     * grouping (timeline/SLU, radar, performance-report/homework, blob-enqueue) keeps the fix legible
     * without a budget check between every single {@code deleteAllByPlayerId} call. Each group re-issues
     * {@code set_config} with a timeout shrunk to {@code remainingBudget / (statements in that group)},
     * so that group's own worst case ({@code statementCount * thisGroupTimeout}) can never exceed the
     * remaining budget — the same invariant Radar's own per-skill halving establishes for its pairs,
     * generalized to N statements per group. {@link #cumulativeLockWaitBudget}'s own Javadoc explains
     * why this is a plain field, not a {@code static final} constant like Radar's.
     *
     * <p>A bail-out here (via {@link CumulativeLockBudgetExhaustedException}) rolls back the ENTIRE
     * {@code requiresNewTemplate} transaction this method runs in — including every earlier group's
     * already-executed statements in THIS SAME call — because they all share the one REQUIRES_NEW
     * transaction below; there is no partial-commit risk to reason about (unlike a naive reading of
     * "mid-child bail-out" might suggest), and the method's own re-drivability Javadoc continues to
     * hold unchanged: a bailed-out child is retried from scratch on the next re-drive, exactly like a
     * single-statement {@link DeleteStatementLockTimeoutException} trip already causes today.
     */
    private void deletePlayerDevelopmentDataInDedicatedPool(Long playerId, long lockTimeoutSeconds,
                                                            boolean scrubProfileIdentity) {
        requiresNewTemplate.executeWithoutResult(status -> {
            var playerProfile = lockRetryer.withBoundedRetry("GdprErasureService.deletePlayerDevelopmentData",
                () -> playerProfileRepository.findByIdForUpdate(playerId)
                    .orElseThrow(() -> new ResourceNotFoundException("Player not found: " + playerId, "player_profile")));
            entityManager.refresh(playerProfile, LockModeType.PESSIMISTIC_WRITE);

            // skillars-deferred-137 AC1: remaining cumulative lock-wait budget for this child's whole
            // statement-group sequence below — see cumulativeLockWaitBudget's own Javadoc.
            Duration remainingLockBudget = cumulativeLockWaitBudget;

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

                // Group 1 (6 statements): timeline + SLU deletes, in this method's own pre-existing order.
                long group1Timeout = nextGroupLockTimeoutSecondsOrThrow(
                    remainingLockBudget, 6, lockTimeoutSeconds, playerId, "timeline/SLU deletes");
                Instant group1StartedAt = Instant.now();
                setStatementLockTimeout(group1Timeout);
                playerTimelineRepository.deleteByPlayerId(playerId);
                sluRepository.deleteAllByPlayerId(playerId);
                sluWeeklySnapshotRepository.deleteAllByPlayerId(playerId);
                playerSluWeeklySnapshotAppliedRepository.deleteAllByPlayerId(playerId);
                sluTargetRepository.deleteAllByPlayerId(playerId);
                neglectedSkillFlagRepository.deleteAllByPlayerId(playerId);
                remainingLockBudget = spendDownLockBudget(remainingLockBudget, group1StartedAt);

                // Group 2 (3 statements): radar deletes.
                long group2Timeout = nextGroupLockTimeoutSecondsOrThrow(
                    remainingLockBudget, 3, lockTimeoutSeconds, playerId, "radar deletes");
                Instant group2StartedAt = Instant.now();
                setStatementLockTimeout(group2Timeout);
                playerRadarBaselineRepository.deleteAllByPlayerId(playerId);
                playerRadarCompositeRepository.deleteAllByPlayerId(playerId);
                radarAssessmentRepository.deleteAllByPlayerId(playerId);
                remainingLockBudget = spendDownLockBudget(remainingLockBudget, group2StartedAt);

                // Group 3 (3 statements): performance-report scan + delete, homework-completion delete.
                // Deferred-77 AC2: a PENDING_UPLOAD/UPLOAD_FAILED report may have no storage_key yet —
                // the query's own WHERE clause excludes those. skillars-deferred-90 AC13: enqueue the
                // key, don't delete from S3 inside this transaction. skillars-deferred-132 AC2 Fix 8:
                // a projection, not full-entity hydration — only the storage_key was ever read.
                long group3Timeout = nextGroupLockTimeoutSecondsOrThrow(
                    remainingLockBudget, 3, lockTimeoutSeconds, playerId, "performance-report/homework deletes");
                Instant group3StartedAt = Instant.now();
                setStatementLockTimeout(group3Timeout);
                childBlobKeys.addAll(performanceReportRepository.findStorageKeysByPlayerId(playerId));
                performanceReportRepository.deleteAllByPlayerId(playerId);
                homeworkCompletionRepository.deleteAllByPlayerId(playerId);
                remainingLockBudget = spendDownLockBudget(remainingLockBudget, group3StartedAt);

                // skillars-deferred-153 AC2: session_completion_data lives in the `booking` schema,
                // not `development` — this method's own cumulative-lock-budget arithmetic above is
                // sized to its pre-existing, development-schema-only statement count (12 + M), and
                // folding this UPDATE into one of those groups would require re-deriving that
                // arithmetic for a table this method otherwise has nothing to do with. Deliberately
                // kept as its own independently-bounded step instead: same per-statement
                // lock_timeout convention (setStatementLockTimeout), but using the full
                // (unshrunk) lockTimeoutSeconds and NOT counted against remainingLockBudget/
                // cumulativeLockWaitBudget — a CumulativeLockBudgetExhaustedException from an
                // earlier group already distinguishes "development data genuinely survives this
                // run" from this statement's own, separate (and much smaller) contention risk.
                //
                // skillars-deferred-157 AC1: wrapped in their own inner try so a trip here is labelled
                // CHILD_BOOKING_* rather than the development-schema CHILD_* reasons. The wrapper is
                // not a PessimisticLockingFailureException, so it passes untouched through this
                // method's own outer catches below.
                //
                // skillars-deferred-158 AC5: only the two lock failures are labelled CHILD_BOOKING_*,
                // deliberately. A non-lock DataAccessException (e.g. QueryTimeoutException) is not a
                // PessimisticLockingFailureException, so nothing here or in the outer catches matches it
                // and it propagates out of erase(); the request is then routed to markFailed (via
                // GdprEventListener / retryFailedErasures), which reports UNCLASSIFIED_FAILURE. That is the
                // honest label for an unpredicted failure; widening this catch would send an operator to
                // booking-lock contention for, say, a bad-SQL or connection error. A commit-time lock trip
                // would instead surface as CHILD_CONTENDED, but is close to unreachable (both statements
                // are immediate bulk UPDATEs and the tombstone is flushed explicitly). Two caveats when
                // reading the resulting alert: on the PARENT branch rollback is per child (earlier children
                // committed in their own REQUIRES_NEW transactions), and alert dedup ignores the reason, so
                // an already-OPEN alert for this request suppresses UNCLASSIFIED_FAILURE.
                try {
                    setStatementLockTimeout(lockTimeoutSeconds);
                    sessionCompletionDataRepository.anonymizeByPlayerId(playerId);

                    // skillars-deferred-156 AC1: booking.bookings is the same `booking` schema as
                    // session_completion_data above, for the same reason — independently-bounded, not
                    // folded into this method's own development-schema cumulative-lock-budget groups.
                    setStatementLockTimeout(lockTimeoutSeconds);
                    bookingRepository.anonymizeNotesAndPlayerRequestByPlayerId(playerId);
                } catch (CannotAcquireLockException e) {
                    // Subclass of PessimisticLockingFailureException — must be caught first.
                    throw new BookingSchemaLockException(e, true);
                } catch (PessimisticLockingFailureException e) {
                    throw new BookingSchemaLockException(e, false);
                }

                // Group 4 (M statements, M = childBlobKeys.size()): the blob-deletion outbox INSERTs.
                // Skipped entirely when M is zero — nothing to bound, and dividing by zero statements
                // would be nonsensical. (H1 fix) enqueue INSIDE this transaction, atomically with the
                // deletes above. Do NOT call requestDrainAfterCommit() here — that stays a single call
                // in erase()'s own transaction, since it only needs to fire once per erase() invocation,
                // not once per child.
                if (!childBlobKeys.isEmpty()) {
                    long group4Timeout = nextGroupLockTimeoutSecondsOrThrow(remainingLockBudget,
                        childBlobKeys.size(), lockTimeoutSeconds, playerId, "blob-deletion outbox enqueue");
                    setStatementLockTimeout(group4Timeout);
                }
                blobDeletionOutboxSupport.enqueue(childBlobKeys);

                // Sticky tombstone — see this method's own Javadoc. Never reset back to null: a
                // player_profiles row is never "un-erased". Inherits whichever group's set_config value
                // is currently in effect (group 4's if M > 0, else group 3's) — this statement cannot
                // itself contend an external lock (the transaction already holds player_profiles
                // exclusively), so which value is in effect is immaterial to its own outcome.
                if (scrubProfileIdentity) {
                    // Parent-requested child erasure: name replaced; date of birth coarsened to
                    // 31 Dec of the birth year rather than nulled (NOT NULL) or reset to EPOCH (which
                    // would read as an adult to AgePolicyService.isMinor) — the latest date in the
                    // year can only make the profile read the same age or younger, never older.
                    playerProfile.setName(ERASED_PLAYER_NAME);
                    if (playerProfile.getDateOfBirth() != null) {
                        playerProfile.setDateOfBirth(
                            LocalDate.of(playerProfile.getDateOfBirth().getYear(), 12, 31));
                    }
                }
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

    /** skillars-deferred-137 AC1: issues the transaction-scoped {@code lock_timeout} bound ahead of one statement group — extracted since AC1 now calls this up to four times per {@code deletePlayerDevelopmentData} invocation instead of once. */
    private void setStatementLockTimeout(long seconds) {
        entityManager.createNativeQuery("SELECT set_config('lock_timeout', ?1, true)")
            .setParameter(1, seconds + "s")
            .getSingleResult();
    }

    /**
     * skillars-deferred-137 AC1: shrinks the next statement group's own {@code lock_timeout} to
     * whatever cumulative budget remains, so that group's own worst case ({@code statementsInGroup *}
     * the returned value) can never push the running total past {@link #cumulativeLockWaitBudget} —
     * mirrors {@code RadarCompositeCalculationService.recalculateComposite}'s identical per-skill
     * shrink, generalized from a fixed pair to an arbitrary group size. Throws rather than returning a
     * near-zero, contention-indistinguishable value once the shrunk timeout would fall below the floor
     * {@link ConfigBounds#GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS} already enforces as its own
     * {@code min} — mirroring Radar's own {@code IllegalStateException} bail-out, adapted to this
     * method's own typed-exception classification shape (see {@link CumulativeLockBudgetExhaustedException}).
     */
    private long nextGroupLockTimeoutSecondsOrThrow(Duration remainingBudget, int statementsInGroup,
            long lockTimeoutSeconds, Long playerId, String groupName) {
        long shrunk = Math.min(lockTimeoutSeconds, remainingBudget.toSeconds() / statementsInGroup);
        if (shrunk < ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.min()) {
            throw new CumulativeLockBudgetExhaustedException(
                "deletePlayerDevelopmentData exceeded its cumulative lock-wait budget ("
                    + cumulativeLockWaitBudget + ") for playerId=" + playerId + " before group=" + groupName
                    + " — remaining statements deferred to a later re-drive");
        }
        return shrunk;
    }

    /**
     * skillars-deferred-137 AC1: spends the cumulative budget down by a statement group's ACTUAL
     * elapsed wall-clock time, not its worst-case timeout — mirrors
     * {@code RadarCompositeCalculationService.recalculateComposite}'s identical bookkeeping, including
     * its zero-floor guard against elapsed exceeding the remaining budget.
     *
     * <p><strong>story review 2026-09-26: also floors a NEGATIVE {@code elapsed} at zero</strong> —
     * {@code Instant.now()} is wall-clock, not monotonic, so a backward clock step (NTP correction, VM
     * time adjustment) between {@code groupStartedAt} and this call can make {@code Duration.between}
     * return a negative duration. Without this guard, {@code remainingBudget.minus(elapsed)} would
     * ADD to the budget instead of spending it down, silently defeating the cumulative ceiling this AC
     * exists to enforce. Radar's own identical mechanism has this same gap unguarded — not fixed here,
     * out of this story's own file scope, but worth a follow-up given the shared origin.
     *
     * <p>Package-private, not {@code private}: a pure static helper with no Spring/mocking
     * dependencies, so {@code GdprErasureServiceTest} calls it directly rather than needing a
     * reflection or Clock-injection seam to exercise the clock-regression branch deterministically.
     */
    static Duration spendDownLockBudget(Duration remainingBudget, Instant groupStartedAt) {
        Duration elapsed = Duration.between(groupStartedAt, Instant.now());
        if (elapsed.isNegative()) {
            elapsed = Duration.ZERO;
        }
        return elapsed.compareTo(remainingBudget) >= 0 ? Duration.ZERO : remainingBudget.minus(elapsed);
    }

    /**
     * skillars-deferred-137 AC1: thrown by {@link #nextGroupLockTimeoutSecondsOrThrow} when the
     * cumulative lock-wait budget is exhausted before a statement group has even been attempted — a
     * DIFFERENT condition from {@link DeleteStatementLockTimeoutException} (one statement tripping its
     * own per-statement {@code lock_timeout}): this can fire even when every individual statement so
     * far succeeded well within its own per-statement timeout, simply because enough of them ran (or
     * ran slowly enough under real contention) to exhaust the budget. Given its own alert reason
     * ({@link #CHILD_CUMULATIVE_LOCK_BUDGET_EXCEEDED}) rather than folded into
     * {@link #CHILD_DELETE_LOCK_TIMEOUT} — distinguishing "one contended statement" from "cumulative
     * contention across many statements" is meaningful operator-triage information, and this class
     * (not a cause-inspection) is what lets the two call sites tell them apart deterministically,
     * exactly like {@link DeleteStatementLockTimeoutException}'s own Javadoc explains for its pair.
     *
     * <p>Uncaught by both catch clauses in {@link #deletePlayerDevelopmentDataInDedicatedPool} (it is
     * neither a {@link CannotAcquireLockException} nor a {@link PessimisticLockingFailureException}), so
     * it propagates out of that method's {@code requiresNewTemplate} transaction as-is, rolling back
     * every statement this child's call has executed so far — see that method's own Javadoc for why
     * this has no partial-commit risk to reason about.
     */
    private static final class CumulativeLockBudgetExhaustedException extends RuntimeException {
        CumulativeLockBudgetExhaustedException(String message) {
            super(message);
        }
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

    /**
     * skillars-deferred-157 AC1: thrown only by the inner try around the two {@code booking}-schema
     * statements in {@link #deletePlayerDevelopmentDataInDedicatedPool}, so the call sites can report
     * {@link #CHILD_BOOKING_LOCK_TIMEOUT}/{@link #CHILD_BOOKING_CONTENDED} instead of the
     * development-schema reasons. {@code contended} is {@code true} for a genuine deadlock
     * ({@link CannotAcquireLockException}), {@code false} for a {@code lock_timeout} trip.
     *
     * <p>Deliberately NOT a {@link PessimisticLockingFailureException}: it must pass untouched through
     * that method's own outer {@code CannotAcquireLockException}/{@code PessimisticLockingFailureException}
     * catches (which would otherwise rethrow or rewrap it as a development-schema failure) and through
     * the {@code requiresNewTemplate} boundary, which rolls the whole inner transaction back on any
     * {@code RuntimeException}.
     */
    private static final class BookingSchemaLockException extends RuntimeException {
        private final boolean contended;

        BookingSchemaLockException(PessimisticLockingFailureException cause, boolean contended) {
            super(cause);
            this.contended = contended;
        }
    }
}
