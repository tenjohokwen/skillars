package com.softropic.skillars.platform.admin.service;

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
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.PessimisticLockException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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

    @PostConstruct
    void initTemplates() {
        requiresNewTemplate = new TransactionTemplate(txManager);
        requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void erase(UUID requestId, Long userId) {
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
            playerProfileRepository.findByUserId(userId)
                .ifPresentOrElse(
                    pp -> {
                        try {
                            deletePlayerDevelopmentData(pp.getId());
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID requestId) {
        gdprRequestRepository.findById(requestId).ifPresent(r -> {
            r.setStatus("FAILED");
            gdprRequestRepository.save(r);
            log.error("[GDPR_ERASURE_MARKED_FAILED] requestId={}", requestId);
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
     * <p>The deadline clock starts here, at the top of this loop — not at the top of {@link #erase}
     * as a whole. The account anonymisation and message/review deletion work preceding this call
     * includes its own unbounded bulk deletes, but they do not contend {@code player_profiles}, so
     * they are not the lock-holding concern this budget targets.
     *
     * @return {@code true} if any child was skipped for a {@link #CHILD_CONTENDED}/
     *     {@link #CHILD_DELETE_LOCK_TIMEOUT} reason (code review 2026-09-23, Decision 1) — signals
     *     {@link #erase} to mark the request {@code FAILED} rather than {@code COMPLETED}, since that
     *     child's development data survives this run. {@code false} when every child either
     *     succeeded or only vanished ({@code CHILD_VANISHED}, whose data was genuinely already erased
     *     by a concurrent request and does not make this run incomplete).
     */
    private boolean eraseParentChildren(UUID requestId, Long userId) {
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
                deletePlayerDevelopmentData(child.getId());
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
     * <p>Deduplicated per {@code (requestId, reason)} (code review 2026-09-23, Decision 2 — corrected
     * from the original per-{@code requestId}-only dedup): a second skipped child raising the SAME
     * reason (or a later re-drive) does not raise another one, but a DIFFERENT reason on the same
     * request does. Deduping on {@code requestId} alone let an earlier, benign {@code CHILD_VANISHED}
     * alert silently suppress a later, genuinely-actionable {@code CHILD_DELETE_LOCK_TIMEOUT} or
     * {@code DEADLINE_EXCEEDED} for the same PARENT request — defeating the discrimination this
     * method's {@code reason} parameter exists to provide, since the admin queue would render only
     * the already-resolved-looking {@code CHILD_VANISHED} case.
     *
     * <p>Runs in its OWN {@code REQUIRES_NEW} transaction, not {@link #erase}'s own — for the
     * deadline-exceeded case, the caller ({@link #eraseParentChildren}) throws immediately after this
     * returns, which rolls back {@link #erase}'s outer transaction; without its own transaction this
     * alert would be rolled back right along with it and never actually recorded. For the AC4
     * skip-and-continue cases the caller does not throw, but this stays consistent with the
     * deadline path rather than depending on {@code erase()}'s own eventual commit.
     */
    private void raiseErasureAlert(UUID requestId, String reason) {
        requiresNewTemplate.executeWithoutResult(status -> {
            boolean alreadyOpen = adminAlertRepository.findFirstByReferenceIdAndTypeAndReasonAndStatus(
                    requestId.toString(), AdminAlertType.GDPR_ERASURE_DEADLINE, reason, AdminAlertStatus.OPEN)
                .isPresent();
            if (alreadyOpen) {
                return;
            }
            AdminAlert alert = new AdminAlert();
            alert.setType(AdminAlertType.GDPR_ERASURE_DEADLINE);
            alert.setReferenceId(requestId.toString());
            alert.setReferenceType(AdminAlertReferenceType.GDPR_REQUEST);
            alert.setReason(reason);
            adminAlertRepository.save(alert);
        });
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
     * never a {@code main.user.id} — resolved differently by each caller in {@link #erase}: the
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
     * {@code player_profiles} lock as soon as it returns — BEFORE {@link #erase}'s own unrelated
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
     * <p>The config value is read via {@link ConfigService#getBoundedLong(String, long, long, long)}
     * <strong>before</strong> the lock acquisition (mirroring {@code recalculateComposite}'s own
     * identical reasoning) — a cache-expiry read can trigger a real {@code configRepository.findAll()}
     * DB round trip, which should not happen while this transaction already holds the
     * {@code player_profiles} lock. The {@code set_config} statement itself is issued
     * <strong>after</strong> the lock is held (only the value read moves earlier), mirroring
     * {@code recalculateComposite}'s own placement of that statement.
     *
     * <p><strong>skillars-deferred-129 AC1 (H2): the {@code role == PLAYER} branch call site in
     * {@link #erase} now also catches a lock-timeout/contention failure here</strong> — equivalent
     * skip-and-alert semantics to {@link #eraseParentChildren}'s own two catches, rather than letting
     * it propagate and silently convert a previously-successful (if slow) erasure into an unalerted
     * {@code FAILED} on the most common account shape. See {@link DeleteStatementLockTimeoutException}
     * for how the two possible failure causes reaching either call site are distinguished.
     */
    private void deletePlayerDevelopmentData(Long playerId) {
        // Task 5: read before the lock acquisition below, not after — see this method's own Javadoc.
        long lockTimeoutSeconds = configService.getBoundedLong(
            ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.key(), 5L, 2L, 120L);
        requiresNewTemplate.executeWithoutResult(status -> {
            var playerProfile = lockRetryer.withBoundedRetry(() -> playerProfileRepository.findByIdForUpdate(playerId)
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
                performanceReportRepository.findByPlayerIdOrderByGeneratedAtDesc(playerId).forEach(report -> {
                    // Deferred-77 AC2: a PENDING_UPLOAD/UPLOAD_FAILED report may have no storage_key yet.
                    // skillars-deferred-90 AC13: enqueue the key, don't delete from S3 inside this transaction.
                    if (report.getStorageKey() != null) {
                        childBlobKeys.add(report.getStorageKey());
                    }
                });
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
     * this specific type at {@link #erase} and {@link #eraseParentChildren}'s own call sites, rather
     * than inspecting any cause, is what actually distinguishes the two failure modes deterministically.
     */
    private static final class DeleteStatementLockTimeoutException extends RuntimeException {
        DeleteStatementLockTimeoutException(PessimisticLockingFailureException cause) {
            super(cause);
        }
    }
}
