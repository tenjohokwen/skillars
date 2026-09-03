package com.softropic.skillars.platform.outbox.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-91 AC1 + code review decision D2 — {@link OutboxChunkProcessor} is now a plain
 * loop over {@link OutboxRowProcessor}, so this covers the loop contract: stop when nothing is due,
 * bound at {@code CHUNK_SIZE}, and route a row failure to the separate-transaction bookkeeping
 * instead of letting it abort the chunk.
 */
class OutboxChunkProcessorTest {

    private OutboxRowProcessor rowProcessor;
    private OutboxChunkProcessor processor;

    @BeforeEach
    void setUp() {
        rowProcessor = mock(OutboxRowProcessor.class);
        processor = new OutboxChunkProcessor(rowProcessor);
    }

    @Test
    void processChunk_stopsAsSoonAsNothingIsDue() {
        when(rowProcessor.claimAndHandle()).thenReturn(OutboxRowProcessor.Outcome.NONE);

        OutboxChunkProcessor.ChunkResult result = processor.processChunk();

        verify(rowProcessor, times(1)).claimAndHandle();
        assertThat(result.claimed()).isZero();
        assertThat(result.drainedSomething()).isFalse();
    }

    @Test
    void processChunk_processesAtMostChunkSizeRowsInOnePass() {
        when(rowProcessor.claimAndHandle()).thenReturn(OutboxRowProcessor.Outcome.PROCESSED);

        OutboxChunkProcessor.ChunkResult result = processor.processChunk();

        verify(rowProcessor, times(OutboxChunkProcessor.CHUNK_SIZE)).claimAndHandle();
        assertThat(result.processed()).isEqualTo(OutboxChunkProcessor.CHUNK_SIZE);
        assertThat(result.failed()).isZero();
    }

    @Test
    void processChunk_aFailedRowIsBookkeptSeparatelyAndTheChunkContinues() {
        RuntimeException cause = new RuntimeException("gateway 503");
        when(rowProcessor.claimAndHandle())
            .thenThrow(new OutboxRowProcessor.OutboxRowFailure(7L, "MONEY", cause))
            .thenReturn(OutboxRowProcessor.Outcome.PROCESSED)
            .thenReturn(OutboxRowProcessor.Outcome.NONE);

        OutboxChunkProcessor.ChunkResult result = processor.processChunk();

        // The sibling row still ran — the whole point of decision D2.
        verify(rowProcessor).recordFailure(eq(7L), eq(cause));
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.processed()).isEqualTo(1);
        assertThat(result.claimed()).isEqualTo(2);
    }

    @Test
    void processChunk_aChunkOfPureFailuresStillRecordsEveryOne() {
        when(rowProcessor.claimAndHandle())
            .thenThrow(new OutboxRowProcessor.OutboxRowFailure(1L, "MONEY", new RuntimeException("a")))
            .thenThrow(new OutboxRowProcessor.OutboxRowFailure(2L, "MONEY", new RuntimeException("b")))
            .thenReturn(OutboxRowProcessor.Outcome.NONE);

        OutboxChunkProcessor.ChunkResult result = processor.processChunk();

        verify(rowProcessor).recordFailure(eq(1L), any());
        verify(rowProcessor).recordFailure(eq(2L), any());
        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.processed()).isZero();
        assertThat(result.drainedSomething()).isTrue();
        verify(rowProcessor, never()).recordFailure(eq(3L), any());
    }
}
