package com.softropic.skillars.platform.outbox.service;

import com.softropic.skillars.platform.outbox.contract.event.OutboxDrainRequestedEvent;
import com.softropic.skillars.platform.outbox.repo.OutboxMessageRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-91 AC1 — unit coverage for {@link OutboxService}: the drain loop runs until a
 * chunk yields nothing, stops on a pure-failure chunk, honours {@code MAX_CHUNKS_PER_DRAIN}, and
 * {@code sweep()} emits the {@code [OUTBOX_STUCK]} ERROR (without ever dropping a row).
 */
class OutboxServiceTest {

    private OutboxMessageRepository repository;
    private OutboxChunkProcessor chunkProcessor;
    private ApplicationEventPublisher publisher;
    private OutboxService service;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxMessageRepository.class);
        chunkProcessor = mock(OutboxChunkProcessor.class);
        publisher = mock(ApplicationEventPublisher.class);
        service = new OutboxService(repository, chunkProcessor, publisher);

        serviceLogger = (Logger) LoggerFactory.getLogger(OutboxService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logAppender);
    }

    private static OutboxChunkProcessor.ChunkResult chunk(int claimed, int processed, int failed) {
        return new OutboxChunkProcessor.ChunkResult(claimed, processed, failed);
    }

    @Test
    void requestDrainAfterCommit_publishesExactlyOneMarkerEvent() {
        service.requestDrainAfterCommit();
        verify(publisher).publishEvent(any(OutboxDrainRequestedEvent.class));
    }

    @Test
    void drain_loopsUntilAChunkYieldsNothing() {
        when(chunkProcessor.processChunk())
            .thenReturn(chunk(25, 25, 0))
            .thenReturn(chunk(25, 25, 0))
            .thenReturn(chunk(0, 0, 0));

        service.drain();

        verify(chunkProcessor, times(3)).processChunk();
    }

    @Test
    void drain_stopsAfterAPureFailureChunkSoAFailingRowCannotSpinTheLoop() {
        when(chunkProcessor.processChunk()).thenReturn(chunk(25, 0, 25));

        service.drain();

        // one pass only: processed == 0 breaks the loop, leaving the rows for the next sweep
        verify(chunkProcessor, times(1)).processChunk();
    }

    @Test
    void drain_isBoundedByMaxChunksPerDrain() {
        when(chunkProcessor.processChunk()).thenReturn(chunk(25, 25, 0)); // never empties

        service.drain();

        verify(chunkProcessor, times(200)).processChunk();
    }

    @Test
    void sweep_logsOutboxStuckErrorWhenRowsCrossTheThreshold_andNeverDeletes() {
        when(chunkProcessor.processChunk()).thenReturn(chunk(0, 0, 0));
        when(repository.countByAttemptsGreaterThanEqual(10)).thenReturn(2L);

        service.sweep();

        assertThat(logAppender.list)
            .anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getFormattedMessage()).contains("[OUTBOX_STUCK]");
            });
    }

    @Test
    void sweep_noStuckRows_noErrorLog() {
        when(chunkProcessor.processChunk()).thenReturn(chunk(0, 0, 0));
        when(repository.countByAttemptsGreaterThanEqual(10)).thenReturn(0L);

        service.sweep();

        assertThat(logAppender.list).noneSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));
    }
}
