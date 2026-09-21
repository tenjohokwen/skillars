package com.softropic.skillars.platform.development.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * skillars-deferred-123 AC3: mirrors {@code VideoDeletionOutboxProcessorIT}'s two claimed_at
 * repository-level tests for the Radar side of the shared claim/reset shape — no prior IT exercised
 * {@code RadarCompositeDlqRepository.claimPendingBatch}/{@code resetStaleClaimed} against a real
 * database.
 *
 * <p><strong>Caveat (skillars-deferred-126 code review, 2026-09-21).</strong> This test class and the
 * database it runs against (Testcontainers Postgres) share ONE host clock — nothing here can exercise
 * genuine cross-instance clock skew, only the SQL predicate's own comparison-boundary correctness. See
 * {@code resetStaleClaimed_boundary_justUnderWindow_notReclaimed}'s own Javadoc for the full history.
 */
class RadarCompositeDlqRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private RadarCompositeDlqRepository dlqRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void tearDown() {
        dlqRepository.deleteAll();
    }

    private RadarCompositeDlqEntry seedEntry() {
        RadarCompositeDlqEntry row = new RadarCompositeDlqEntry();
        row.setPlayerId(9080_000_001L);
        row.setSkillCodes(List.of("PAC"));
        row.setStatus("PENDING");
        row.setAttempts(0);
        return dlqRepository.save(row);
    }

    /**
     * skillars-deferred-123 code review 2026-09-18 (Patch): no prior test called
     * {@code claimPendingBatch} through its real native query on the Radar side (both existing tests
     * above hand-stamp {@code claimed_at} via plain {@code save()}), so deleting {@code claimed_at =
     * :now} from that query's {@code SET} clause would leave the suite green while production's
     * {@code findClaimedBatch} fetched zero rows forever. This exercises the actual claim → fetch
     * round trip.
     *
     * <p><strong>skillars-deferred-126 AC1.</strong> {@code claimed_at} is now stamped from the
     * database's own {@code now()}, not the {@code Instant} passed for the {@code next_retry_at <=
     * :now} eligibility predicate — the sanity bound below confirms the stamped value reads back
     * close to real wall-clock time (not an exact-match assertion, since the test JVM and the test
     * database may themselves be skewed in CI).
     */
    @Test
    void claimPendingBatch_stampsClaimedAtAndClaimedBySoFindClaimedBatchReturnsIt() {
        RadarCompositeDlqEntry row = seedEntry();
        row.setNextRetryAt(Instant.now().minusSeconds(5));
        dlqRepository.save(row);

        Instant runClaimedAt = Instant.now();
        UUID runId = UUID.randomUUID();
        int claimed = dlqRepository.claimPendingBatch(runClaimedAt, runId, 50);
        assertThat(claimed).isEqualTo(1);

        // skillars-deferred-124 AC4: findClaimedBatch now keys on claimed_by (runId), not
        // claimed_at-exact-equality — see VideoDeletionOutboxProcessorIT's identical sibling test.
        List<RadarCompositeDlqEntry> batch = dlqRepository.findClaimedBatch(runId, 50);
        assertThat(batch).extracting(RadarCompositeDlqEntry::getId).containsExactly(row.getId());
        assertThat(batch.get(0).getClaimedAt()).isNotNull();
        assertThat(batch.get(0).getClaimedBy()).isEqualTo(runId);
        // skillars-deferred-126 AC1: the stamp is the DATABASE's now(), read back — sanity-bound
        // against real wall-clock time rather than asserted exactly equal to runClaimedAt.
        assertThat(batch.get(0).getClaimedAt()).isCloseTo(Instant.now(), within(30, ChronoUnit.SECONDS));
    }

    /**
     * skillars-deferred-123 code review 2026-09-18 (Patch): the sibling test below only ever asserted
     * {@code isZero()} — a predicate that matches nothing under every circumstance also satisfies that
     * assertion. This pins the actual positive recovery case resetStaleClaimed exists for: a genuinely
     * stale claim IS reclaimed, and claimed_at is nulled back out per the field's own invariant.
     */
    @Test
    void resetStaleClaimed_reclaimsGenuinelyStaleClaim() {
        RadarCompositeDlqEntry row = seedEntry();
        row.setStatus("CLAIMED");
        row.setClaimedAt(Instant.now().minus(30, ChronoUnit.MINUTES));
        row.setClaimedBy(UUID.randomUUID());
        dlqRepository.save(row);

        int reset = dlqRepository.resetStaleClaimed(Duration.ofMinutes(10).toSeconds());

        assertThat(reset).as("a genuinely stale claim must be reclaimed").isEqualTo(1);
        RadarCompositeDlqEntry recovered = dlqRepository.findById(row.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo("PENDING");
        assertThat(recovered.getClaimedAt()).isNull();
        // skillars-deferred-124 AC4 Task 7: claimed_by must also clear on this transition.
        assertThat(recovered.getClaimedBy()).isNull();
    }

    /** Mirrors VideoDeletionOutboxProcessorIT#resetStaleClaimed_doesNotReclaimRecentlyClaimedButLongBacklogedRow(). */
    @Test
    void resetStaleClaimed_doesNotReclaimRecentlyClaimedButLongBacklogedRow() {
        RadarCompositeDlqEntry row = seedEntry();
        row.setStatus("CLAIMED");
        row.setNextRetryAt(Instant.now().minus(2, ChronoUnit.HOURS));
        row.setClaimedAt(Instant.now());
        dlqRepository.save(row);

        int reset = dlqRepository.resetStaleClaimed(Duration.ofMinutes(10).toSeconds());

        assertThat(reset).as("a genuinely-just-claimed row must not be reclaimed").isZero();
        RadarCompositeDlqEntry stillClaimed = dlqRepository.findById(row.getId()).orElseThrow();
        assertThat(stillClaimed.getStatus()).isEqualTo("CLAIMED");
    }

    /**
     * skillars-deferred-126 code review (Decision 1, 2026-09-21) — replaces the original
     * {@code resetStaleClaimed_comparesAgainstDatabaseClockNotJvmClock}. That test claimed to prove
     * the new predicate compares against the DATABASE's clock, not the JVM's, but seeded {@code
     * claimed_at} 30 minutes stale against a 10-minute window — the test JVM and the Testcontainers
     * Postgres instance share ONE host clock in this suite, so the OLD app-clock predicate
     * ({@code claimed_at < Instant.now().minus(window)}) and the NEW database-clock predicate
     * ({@code claimed_at < now() - make_interval(...)}) match the exact same row and return the exact
     * same {@code reset == 1} — no clock is ever actually skewed, so the one behavior AC1 exists for
     * was untested. Proving genuine cross-clock immunity would require inducing REAL skew (e.g. a
     * second connection with a shifted session clock), which this fix does not attempt.
     *
     * <p>What these two tests instead pin is the rewritten predicate's exact comparison BOUNDARY —
     * still a genuine regression test (a flipped comparison operator or an off-by-one in the
     * {@code make_interval} arithmetic is caught here), just not a skew-immunity proof. See this
     * class's own Javadoc caveat and {@code VideoDeletionOutboxProcessorIT}'s identical sibling pair.
     *
     * <p><strong>Determinism (found while implementing this fix).</strong> An earlier version of these
     * two tests seeded {@code claimed_at} via one statement and then called {@code resetStaleClaimed}
     * as a SEPARATE statement/transaction, with only a 1-second (later widened to 30-second) margin
     * between the two boundary values. That was empirically flaky in this same Testcontainers
     * environment — a fresh Postgres container's clock can visibly jump by MINUTES shortly after
     * startup as its own time-sync catches up to the host, and that jump can land between the two
     * statements. Both statements are now wrapped in ONE explicit transaction
     * ({@link #transactionTemplate}): {@code now()} is Postgres's {@code transaction_timestamp()}, not
     * a per-statement clock read, so the seeding {@code UPDATE} and {@code resetStaleClaimed}'s own
     * {@code @Transactional} (which joins this already-open transaction rather than starting a new
     * one) see the IDENTICAL {@code now()} value — eliminating the race entirely rather than papering
     * over it with a bigger margin. This restores the original 1-second margin safely.
     */
    @Test
    void resetStaleClaimed_boundary_justUnderWindow_notReclaimed() {
        RadarCompositeDlqEntry row = seedEntry();
        row.setStatus("CLAIMED");
        row.setClaimedBy(UUID.randomUUID());
        dlqRepository.save(row);

        int reset = transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE development.radar_composite_dlq SET claimed_at = now() - interval '9 minutes 59 seconds' WHERE id = ?",
                row.getId());
            return dlqRepository.resetStaleClaimed(Duration.ofMinutes(10).toSeconds());
        });

        assertThat(reset).as("a claim one second inside the stale window must NOT be reclaimed yet").isZero();
    }

    @Test
    void resetStaleClaimed_boundary_justOverWindow_reclaimed() {
        RadarCompositeDlqEntry row = seedEntry();
        row.setStatus("CLAIMED");
        row.setClaimedBy(UUID.randomUUID());
        dlqRepository.save(row);

        int reset = transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE development.radar_composite_dlq SET claimed_at = now() - interval '10 minutes 1 second' WHERE id = ?",
                row.getId());
            return dlqRepository.resetStaleClaimed(Duration.ofMinutes(10).toSeconds());
        });

        assertThat(reset).as("a claim one second past the stale window must be reclaimed").isEqualTo(1);
        RadarCompositeDlqEntry recovered = dlqRepository.findById(row.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo("PENDING");
        assertThat(recovered.getClaimedAt()).isNull();
        assertThat(recovered.getClaimedBy()).isNull();
    }

    /** Mirrors VideoDeletionOutboxProcessorIT#findClaimedBatch_doesNotReturnRowsClaimedByADifferentRun(). */
    @Test
    void findClaimedBatch_doesNotReturnRowsClaimedByADifferentRun() {
        RadarCompositeDlqEntry rowA = seedEntry();
        RadarCompositeDlqEntry rowB = seedEntry();
        UUID runAId = UUID.randomUUID();
        UUID runBId = UUID.randomUUID();

        rowA.setStatus("CLAIMED");
        rowA.setClaimedAt(Instant.now().minusSeconds(120));
        rowA.setClaimedBy(runAId);
        dlqRepository.save(rowA);

        rowB.setStatus("CLAIMED");
        rowB.setClaimedAt(Instant.now());
        rowB.setClaimedBy(runBId);
        dlqRepository.save(rowB);

        List<RadarCompositeDlqEntry> runBBatch = dlqRepository.findClaimedBatch(runBId, 50);

        assertThat(runBBatch).extracting(RadarCompositeDlqEntry::getId).containsExactly(rowB.getId());
    }
}
