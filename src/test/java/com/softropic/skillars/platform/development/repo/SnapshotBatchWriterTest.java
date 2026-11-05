package com.softropic.skillars.platform.development.repo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.softropic.skillars.platform.security.service.DevelopmentDataErasureGuard;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class SnapshotBatchWriterTest {

    private static final short ISO_YEAR = 2026;
    private static final short ISO_WEEK = 35;

    @Mock private SluWeeklySnapshotRepository snapshotRepository;
    @Mock private DevelopmentDataErasureGuard erasureGuard;

    private SnapshotBatchWriter writer;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        writer = new SnapshotBatchWriter(snapshotRepository, erasureGuard);
    }

    private PlayerSkillStat stat(UUID sessionId, String skillCode, String slu) {
        PlayerSkillStat s = new PlayerSkillStat();
        s.setPlayerId(42L);
        s.setSessionId(sessionId);
        s.setCoachId(UUID.randomUUID());
        s.setSkillCode(skillCode);
        s.setSluValue(new BigDecimal(slu));
        return s;
    }

    @Test
    void writeAll_nullSessionIdStat_isSkippedAndRepositoryNotCalledForIt() {
        UUID goodSession = UUID.randomUUID();
        PlayerSkillStat withSession = stat(goodSession, "PAC", "5.0000");
        PlayerSkillStat withoutSession = stat(null, "SHO", "3.0000");

        writer.writeAll(List.of(withoutSession, withSession), ISO_YEAR, ISO_WEEK);

        // Exactly one upsert — for the good stat. The null-session stat is skipped entirely (its
        // NOT NULL session_id would fail), so there is no second interaction of any shape.
        verify(snapshotRepository).upsertAddIdempotent(
            eq(goodSession), eq(42L), eq("PAC"), eq(ISO_YEAR), eq(ISO_WEEK), eq(new BigDecimal("5.0000")));
        verifyNoMoreInteractions(snapshotRepository);
    }

    @Test
    void writeAll_allStatsHaveSessionId_eachIsUpserted() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();

        writer.writeAll(List.of(stat(s1, "PAC", "1.0000"), stat(s2, "SHO", "2.0000")), ISO_YEAR, ISO_WEEK);

        verify(snapshotRepository).upsertAddIdempotent(eq(s1), eq(42L), eq("PAC"), eq(ISO_YEAR), eq(ISO_WEEK), eq(new BigDecimal("1.0000")));
        verify(snapshotRepository).upsertAddIdempotent(eq(s2), eq(42L), eq("SHO"), eq(ISO_YEAR), eq(ISO_WEEK), eq(new BigDecimal("2.0000")));
    }

    // ── skillars-deferred-171 AC3: an erased player's deltas are dropped, another player's still applied ──

    @Test
    void writeAllDeltas_erasedPlayer_isDroppedAndTheOtherPlayerIsStillApplied() {
        UUID session = UUID.randomUUID();
        // lenient: the writer also checks player 7, which this stub deliberately does not cover.
        org.mockito.Mockito.lenient().when(erasureGuard.isErasedUnderLock(eq(42L), anyString())).thenReturn(true);

        writer.writeAllDeltas(List.of(
            new SnapshotBatchWriter.SluDelta(session, 42L, "PAC", new BigDecimal("1.0000")),
            new SnapshotBatchWriter.SluDelta(session, 7L, "SHO", new BigDecimal("2.0000"))), ISO_YEAR, ISO_WEEK);

        verify(snapshotRepository).upsertAddIdempotent(eq(session), eq(7L), eq("SHO"), eq(ISO_YEAR), eq(ISO_WEEK),
            eq(new BigDecimal("2.0000")));
        verifyNoMoreInteractions(snapshotRepository);
    }

    @Test
    void writeAllDeltas_checksEachDistinctPlayerOnceInAscendingOrder() {
        UUID session = UUID.randomUUID();

        writer.writeAllDeltas(List.of(
            new SnapshotBatchWriter.SluDelta(session, 42L, "PAC", BigDecimal.ONE),
            new SnapshotBatchWriter.SluDelta(session, 7L, "SHO", BigDecimal.ONE),
            new SnapshotBatchWriter.SluDelta(session, 42L, "DRI", BigDecimal.ONE)), ISO_YEAR, ISO_WEEK);

        InOrder order = inOrder(erasureGuard);
        order.verify(erasureGuard).isErasedUnderLock(eq(7L), anyString());
        order.verify(erasureGuard).isErasedUnderLock(eq(42L), anyString());
        order.verifyNoMoreInteractions();
    }
}
