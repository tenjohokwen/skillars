package com.softropic.skillars.platform.development.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-123 AC3: mirrors {@code VideoDeletionOutboxProcessorIT}'s two claimed_at
 * repository-level tests for the Radar side of the shared claim/reset shape — no prior IT exercised
 * {@code RadarCompositeDlqRepository.claimPendingBatch}/{@code resetStaleClaimed} against a real
 * database.
 */
class RadarCompositeDlqRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private RadarCompositeDlqRepository dlqRepository;

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

        int reset = dlqRepository.resetStaleClaimed(Instant.now().minus(10, ChronoUnit.MINUTES));

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

        int reset = dlqRepository.resetStaleClaimed(Instant.now().minus(10, ChronoUnit.MINUTES));

        assertThat(reset).as("a genuinely-just-claimed row must not be reclaimed").isZero();
        RadarCompositeDlqEntry stillClaimed = dlqRepository.findById(row.getId()).orElseThrow();
        assertThat(stillClaimed.getStatus()).isEqualTo("CLAIMED");
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
