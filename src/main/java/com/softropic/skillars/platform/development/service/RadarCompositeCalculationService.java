package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.development.contract.AssessmentType;
import com.softropic.skillars.platform.development.contract.RadarEntrySubmittedEvent;
import com.softropic.skillars.platform.development.repo.PlayerRadarBaselineRepository;
import com.softropic.skillars.platform.development.repo.PlayerRadarCompositeRepository;
import com.softropic.skillars.platform.development.repo.RadarAssessmentRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class RadarCompositeCalculationService {

    private static final BigDecimal WEIGHT_OBJECTIVE  = new BigDecimal("0.50");
    private static final BigDecimal WEIGHT_MATCH_OBS  = new BigDecimal("0.30");
    private static final BigDecimal WEIGHT_COACH_EVAL = new BigDecimal("0.20");

    private final RadarAssessmentRepository radarRepository;
    private final PlayerRadarCompositeRepository compositeRepository;
    private final PlayerRadarBaselineRepository baselineRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final PessimisticLockRetryer lockRetryer;
    private final EntityManager entityManager;
    private final RadarCompositeDlqService dlqService;
    private final ConfigService configService;

    /**
     * skillars-deferred-126 code review (Decision 2, 2026-09-21): {@code lock_timeout} is per
     * STATEMENT, so at the configured ceiling ({@link ConfigBounds#RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS}'s
     * {@code max}, 120s) the two statements per skill ({@code upsertComposite} /
     * {@code insertBaselineIfAbsent}) could each independently wait up to 120s — an N-skill call's
     * cumulative worst case is {@code 2 * N * lockTimeoutSeconds}, unbounded by the per-statement
     * timeout alone. A two-skill row at the ceiling (480s) would exceed both {@code
     * RadarCompositeDlqProcessor}'s own {@code MAX_RUN_DURATION} per-row margin and the {@code
     * STALE_CLAIM_WINDOW} buffer AC1 relies on to prevent a duplicate {@code recalculateComposite} —
     * the exact failure mode AC1 exists to close, reopened by AC2's own per-statement-only bound.
     *
     * <p>Set equal to the per-statement ceiling itself: bounding this WHOLE call's cumulative
     * lock-wait to no more than one single statement's own worst case keeps a multi-skill row inside
     * the same margin a single-skill row already respects. See {@link #recalculateComposite}'s own
     * per-skill loop for how this budget is spent down and enforced.
     */
    private static final Duration CUMULATIVE_LOCK_WAIT_BUDGET =
        Duration.ofSeconds(ConfigBounds.RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS.max());

    // recalculateComposite is @Transactional; calling it as `this.recalculateComposite(...)` from
    // onRadarEntrySubmitted below would bypass the Spring proxy and silently drop that annotation
    // (same pitfall documented on BookingService.acceptAndInitiatePayment). Mirrors
    // TimelineEventListener's identical @Lazy @Autowired self field for the same reason.
    @Autowired
    @Lazy
    private RadarCompositeCalculationService self;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    // skillars-deferred-92 code review (D2): shares reportExecutor with report generation. The
    // recalculation below takes a pessimistic player-row lock for a read-then-upsert, so it has no
    // fixed upper bound either and does not belong on the shared 2s-budget pool.
    @Async("reportExecutor")
    public void onRadarEntrySubmitted(RadarEntrySubmittedEvent event) {
        Long playerId    = event.playerId();
        Long parentId    = event.parentId();
        Set<String> skills = event.skillCodes();

        try {
            self.recalculateComposite(playerId, parentId, skills);
        } catch (Exception e) {
            log.error("Composite recalculation failed for player={} skills={} — composite is now stale, queued to DLQ",
                playerId, skills, e);
            dlqService.emitFailedCompositeCalculation(playerId, parentId, skills, e);
        }
    }

    /**
     * Called both from {@link #onRadarEntrySubmitted} (via {@link #self}) and from
     * {@link RadarCompositeDlqProcessor} (scheduled retry poller, a separate bean) — always through
     * this bean's Spring proxy, never a direct same-instance call.
     *
     * <p><strong>skillars-deferred-126 AC2 (2026-09-21).</strong> The per-skill loop's two native
     * {@code INSERT ... ON CONFLICT} upserts ({@code compositeRepository.upsertComposite},
     * {@code baselineRepository.insertBaselineIfAbsent}) used to have no lock/statement timeout at
     * all — unlike {@code findByIdForUpdate} above, which deliberately does (NOWAIT +
     * {@link PessimisticLockRetryer}). A concrete conflict source: {@code GdprErasureService.erase}
     * (its own {@code Propagation.REQUIRES_NEW} transaction) calls {@code
     * deletePlayerDevelopmentData}, which deletes {@code player_radar_baselines} then {@code
     * player_radar_composites} for the same player, in the <strong>opposite</strong> table order this
     * method writes them in (composites then baselines). That is a genuine lock-ordering deadlock
     * shape (Postgres {@code 40P01}), not merely an unbounded wait ({@code 55P03}).
     *
     * <p><strong>The direct table-order collision is CLOSED (skillars-deferred-127 AC1, 2026-09-21):
     * </strong> {@code deletePlayerDevelopmentData} now takes the SAME {@code player_profiles}
     * pessimistic lock this method takes below (same {@code findByIdForUpdate} + {@link
     * PessimisticLockRetryer} pattern) before touching either table, fully serializing the two paths
     * — once one holds the {@code player_profiles} lock, the other cannot even begin touching {@code
     * player_radar_composites}/{@code player_radar_baselines}, so they can no longer race on those two
     * tables' row locks at all. This closes the direct resurrection risk (a recalculation
     * re-inserting composites/baselines from a pre-erasure snapshot after erasure already deleted
     * them) and the lock-ordering deadlock shape above, as a structural consequence of the shared
     * lock, not a separate mechanism. The lock-ordering discussion, the {@code lock_timeout} vs
     * {@code deadlock_timeout} distinction and the exception-class findings immediately below remain
     * accurate — they describe how a wait/deadlock on these tables is bounded/detected in general, not
     * only for the now-closed erasure conflict specifically.
     *
     * <p><strong>A residual race through the SOURCE table is separately closed by a tombstone
     * (code review, 2026-09-21):</strong> the shared lock above only serializes {@code erase} against
     * THIS method — it does nothing for {@code RadarAssessmentService.submitAssessment}, which writes
     * {@code radar_assessment_entries} (the data this method reads) without taking this lock at all.
     * A coach's {@code submitAssessment} transaction can commit new assessment rows for player P
     * <em>after</em> {@code deletePlayerDevelopmentData}'s own delete of {@code
     * radar_assessment_entries} already ran and (under READ COMMITTED) could not see them, then this
     * method's {@code AFTER_COMMIT}-triggered or DLQ-retried run would read those surviving rows and
     * re-create composites/baselines for an already-erased player. See the {@code
     * developmentDataErasedAt} check immediately after this method's own lock acquisition below —
     * checked under the identical lock, so whichever of {@code erase}/this method runs second always
     * sees the other's fully-committed state.
     *
     * <strong>/bmad-code-review fix (2026-09-21): these are NOT both bounded by the same mechanism.</strong>
     * A genuine deadlock was ALREADY bounded — by Postgres's own {@code deadlock_timeout} (default
     * 1s), independent of anything this AC adds; {@code lock_timeout} only makes an ordinary WAITER
     * abort itself rather than block forever, it cannot break a circular wait, which is Postgres's own
     * deadlock detector's job, not this timeout's. What {@code lock_timeout} newly bounds is only the
     * unbounded-WAIT case ({@code 55P03}) — the two are documented together here only because both
     * failure modes happen to surface through this method's write-order hazard and both translate to
     * the same Spring exception class (see below), not because {@code lock_timeout} bounds both. The
     * value set below must stay ABOVE Postgres's own {@code deadlock_timeout} regardless, so the
     * deadlock detector gets a chance to fire before this timeout would otherwise misreport a real
     * deadlock as an ordinary lock timeout; see {@link ConfigBounds#RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS}'s
     * own Javadoc for the bound's sizing. If the erasure transaction is itself slow, this method's
     * upsert could otherwise hang indefinitely — {@code MAX_RUN_DURATION} only bounds the DLQ-retry
     * path between loop iterations and does not apply at all to this method's {@code AFTER_COMMIT}-
     * triggered live path ({@link #onRadarEntrySubmitted}), which has no self-terminating backstop of
     * its own.
     *
     * <p>Both failure modes translate to the SAME Spring exception class — {@link
     * org.springframework.dao.PessimisticLockingFailureException} — empirically confirmed against
     * this project's pinned Spring Boot / Hibernate version by {@code
     * RadarCompositeCalculationServiceConcurrencyIT}, not assumed: the plain lock-timeout wait
     * ({@code 55P03}, via the JPA-native-query path) surfaces with cause {@code
     * org.hibernate.PessimisticLockException}; the genuine lock-ordering deadlock ({@code 40P01}, via
     * the plain-JdbcTemplate path exercised against a real held competing lock/transaction) surfaces
     * with cause {@code org.postgresql.util.PSQLException} ("ERROR: deadlock detected") — Spring's
     * exception translation maps both SQLStates to the same top-level {@code
     * PessimisticLockingFailureException}, distinguishable only by inspecting the cause, not by
     * catching a different exception type per failure mode. Deliberately left to propagate, NOT
     * retried in place: both existing callers already provide
     * eventual-consistency recovery on any thrown exception ({@link #onRadarEntrySubmitted}'s own
     * {@code catch} routes to {@code dlqService.emitFailedCompositeCalculation}, retried by {@link
     * RadarCompositeDlqProcessor} on its own schedule; that processor's own loop-level failure
     * handling does the same for the DLQ-retry path). The goal is only to make a stuck upsert
     * <strong>fail fast and become visible</strong>, not to make it silently succeed after a wait.
     *
     * <p><strong>Blast radius.</strong> The lock-timeout bound is set via a session-scoped {@code
     * SELECT set_config('lock_timeout', ..., true)} call (the transaction-scoped equivalent of
     * {@code SET LOCAL} — Postgres's {@code SET}/{@code SET LOCAL} accept no bind parameters at all,
     * so a literal {@code SET LOCAL lock_timeout = ?} is not legal SQL; {@code set_config}'s
     * arguments are genuine function parameters and so bind normally). It binds to whatever
     * transaction is active when it runs — issued once per skill inside the per-skill loop (see
     * {@link #CUMULATIVE_LOCK_WAIT_BUDGET}), not once for the whole call, so its value can shrink as
     * that budget is spent down; each re-issue simply overwrites the previous value for the rest of
     * this same transaction. This method is plain {@code @Transactional} ({@code
     * Propagation.REQUIRED}), so a <strong>future</strong> transactional caller that itself opens a
     * transaction before calling this method would inherit whatever {@code lock_timeout} this method
     * last set for the rest of its own transaction too — silently widening this bound's intended blast
     * radius. Both current callers are safe today: {@link #onRadarEntrySubmitted} is {@code @Async}
     * and opens no transaction of its own; {@code RadarCompositeDlqProcessor.processRow} is not
     * {@code @Transactional} either. Do not change this method's propagation to {@code REQUIRES_NEW}
     * to "solve" this preemptively — the constraint is documentation-cheap and today's callers are
     * already safe.
     */
    @Transactional
    public void recalculateComposite(Long playerId, Long parentId, Set<String> skills) {
        // skillars-deferred-126 AC2: read the lock-timeout tunable BEFORE taking the pessimistic
        // lock below. ConfigService is TTL-cached, but a cache-expiry read triggers a real DB round
        // trip (configRepository.findAll()) — doing this after findByIdForUpdate/entityManager.refresh
        // would put that round trip inside the transaction already holding the player_profiles
        // pessimistic lock, for no reason. The 4-arg getBoundedLong never throws on a missing key —
        // see ConfigBounds.RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS's own Javadoc for why that overload
        // (not the 3-arg, throwing one) is required here.
        //
        // /bmad-code-review fix (2026-09-21): min/max passed as the same LITERAL numbers
        // ConfigBounds.RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS declares (2L, 120L), not `.min()`/`.max()`
        // accessor calls — matching this codebase's own documented ConfigBounds convention ("each call
        // site still passes its [min, max] literally... Mockito verify(...) pins the exact numbers" —
        // see ConfigBounds's own class Javadoc). Reading the bound through its own accessors defeats
        // that convention: a unit test asserting via `verify(...).getBoundedLong(key, default, 2L,
        // 120L)` would still pass if ConfigBounds's stored min/max silently drifted from what this call
        // site's own literals are supposed to be cross-checked against.
        long lockTimeoutSeconds = configService.getBoundedLong(
            ConfigBounds.RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS.key(), 5L, 2L, 120L);

        // Deferred-77 AC10 Phase 1: two concurrent submissions for the same player both used to
        // read aggregates before either upserted, so the later commit silently clobbered the
        // earlier one's result (last-writer-wins, not a merge). Locking the player row for the
        // duration of read+upsert serializes concurrent recalculations for the same player.
        var playerProfile = lockRetryer.withBoundedRetry("RadarCompositeCalculationService.recalculateComposite",
            () -> playerProfileRepository.findByIdForUpdate(playerId)
                .orElseThrow(() -> new ResourceNotFoundException("Player not found: " + playerId, "player_profile")));
        entityManager.refresh(playerProfile, LockModeType.PESSIMISTIC_WRITE);

        // skillars-deferred-127 code review (2026-09-21): the shared player_profiles lock alone does
        // not close the resurrection race against GdprErasureService.erase — RadarAssessmentService.
        // submitAssessment writes radar_assessment_entries without taking this lock at all, so a
        // coach submission that commits during an in-flight erasure can leave rows the erasure's own
        // delete never saw. GdprErasureService.deletePlayerDevelopmentData stamps this tombstone
        // under the identical lock this method just re-acquired/refreshed above — checking it here,
        // immediately after that refresh and before reading any aggregates, means: if the erasure ran
        // first and committed, this refresh sees the now-committed tombstone and this method skips
        // entirely; if this method ran first, the erasure blocks behind this call's own lock hold and
        // cannot set the tombstone until this call's transaction has already committed. Covers both
        // the live AFTER_COMMIT path (via onRadarEntrySubmitted) and the RadarCompositeDlqProcessor
        // retry path, since every route to an upsert passes through this one method.
        if (playerProfile.getDevelopmentDataErasedAt() != null) {
            log.info("Skipping composite recalculation for player={} — development data was erased at {}",
                playerId, playerProfile.getDevelopmentDataErasedAt());
            return;
        }

        List<Object[]> aggregates = radarRepository.findAggregatesByPlayerAndSkills(playerId, parentId, skills);
        List<Object[]> distinctCoachCounts =
            radarRepository.findDistinctCoachCountsByPlayerAndSkills(playerId, parentId, skills);

        Map<String, Integer> distinctCoachCountBySkill = new HashMap<>();
        for (Object[] row : distinctCoachCounts) {
            distinctCoachCountBySkill.put((String) row[0], ((Number) row[1]).intValue());
        }

        Map<String, Map<AssessmentType, double[]>> bySkill = new HashMap<>();
        for (Object[] row : aggregates) {
            String skill = (String) row[0];
            AssessmentType type = AssessmentType.valueOf((String) row[1]);
            double avg   = ((Number) row[2]).doubleValue();
            long count   = ((Number) row[3]).longValue();
            bySkill.computeIfAbsent(skill, k -> new HashMap<>())
                .put(type, new double[]{avg, count});
        }

        // skillars-deferred-126 code review (Decision 2, 2026-09-21): remaining cumulative lock-wait
        // budget for this call's whole per-skill loop — see CUMULATIVE_LOCK_WAIT_BUDGET's own Javadoc.
        Duration remainingLockBudget = CUMULATIVE_LOCK_WAIT_BUDGET;

        for (Map.Entry<String, Map<AssessmentType, double[]>> skillEntry : bySkill.entrySet()) {
            String skill = skillEntry.getKey();
            Map<AssessmentType, double[]> types = skillEntry.getValue();

            // Shrink this skill's OWN statement-pair timeout to whatever budget remains, so its
            // worst-case wait (2 * thisSkillLockTimeoutSeconds) can never push the running total past
            // CUMULATIVE_LOCK_WAIT_BUDGET, no matter how many skills came before it in this same call.
            long thisSkillLockTimeoutSeconds = Math.min(lockTimeoutSeconds, remainingLockBudget.toSeconds() / 2);
            if (thisSkillLockTimeoutSeconds < 2L) {
                // Budget already exhausted by earlier skills in THIS call — stop rather than let a
                // shrunk-below-the-deadlock_timeout-floor value misreport a genuine deadlock as an
                // ordinary lock timeout (ConfigBounds.RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS's own min,
                // 2L, exists precisely to keep every set_config call above that floor). Remaining
                // skills are recovered by the existing DLQ retry path exactly like any other failure —
                // recalculateComposite re-derives ALL requested skills from the aggregates tables on
                // retry, so re-running already-completed skills is idempotent, not harmful duplicate
                // work.
                throw new IllegalStateException("recalculateComposite exceeded its cumulative "
                    + "lock-wait budget (" + CUMULATIVE_LOCK_WAIT_BUDGET + ") for player=" + playerId
                    + " before skill=" + skill + " — remaining skills deferred to DLQ retry");
            }
            // skillars-deferred-126 AC2 (bound revised by its own code review, 2026-09-21): issued
            // once per skill (not once before the loop), so its value can shrink as the cumulative
            // budget above is spent down — see this method's own "Blast radius" Javadoc paragraph.
            entityManager.createNativeQuery("SELECT set_config('lock_timeout', ?1, true)")
                .setParameter(1, thisSkillLockTimeoutSeconds + "s")
                .getSingleResult();
            Instant skillStartedAt = Instant.now();

            double composite = 0.0;
            int totalCount   = 0;

            if (types.containsKey(AssessmentType.OBJECTIVE)) {
                composite  += types.get(AssessmentType.OBJECTIVE)[0] * WEIGHT_OBJECTIVE.doubleValue();
                totalCount += toSafeIntCount(types.get(AssessmentType.OBJECTIVE)[1]);
            }
            if (types.containsKey(AssessmentType.MATCH_OBSERVATION)) {
                composite  += types.get(AssessmentType.MATCH_OBSERVATION)[0] * WEIGHT_MATCH_OBS.doubleValue();
                totalCount += toSafeIntCount(types.get(AssessmentType.MATCH_OBSERVATION)[1]);
            }
            if (types.containsKey(AssessmentType.COACH_EVALUATION)) {
                composite  += types.get(AssessmentType.COACH_EVALUATION)[0] * WEIGHT_COACH_EVAL.doubleValue();
                totalCount += toSafeIntCount(types.get(AssessmentType.COACH_EVALUATION)[1]);
            }

            BigDecimal compositeScore = BigDecimal.valueOf(composite)
                .setScale(2, java.math.RoundingMode.HALF_UP);
            int distinctCoachCount = distinctCoachCountBySkill.getOrDefault(skill, 0);
            compositeRepository.upsertComposite(playerId, skill, compositeScore, totalCount, distinctCoachCount);
            baselineRepository.insertBaselineIfAbsent(playerId, skill, compositeScore);
            log.debug("Composite updated: player={} skill={} score={} entries={} distinctCoaches={}",
                playerId, skill, compositeScore, totalCount, distinctCoachCount);

            // Spend down the cumulative budget by this skill's ACTUAL elapsed time, not its worst-case
            // timeout — the common case (no contention) barely dents the budget; only genuine lock
            // waiting consumes it. Floored at zero: a slightly-stale clock or GC pause between
            // skillStartedAt and here must not underflow this into a negative Duration.
            Duration elapsed = Duration.between(skillStartedAt, Instant.now());
            remainingLockBudget = elapsed.compareTo(remainingLockBudget) >= 0
                ? Duration.ZERO
                : remainingLockBudget.minus(elapsed);
        }
    }

    /**
     * Recovers the exact integral count a native-query {@code long} was cast to {@code double} for
     * aggregation, narrowing it back to {@code int} with an overflow guard instead of silently wrapping.
     */
    private static int toSafeIntCount(double count) {
        return Math.toIntExact(Math.round(count));
    }
}
