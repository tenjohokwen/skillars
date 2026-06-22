package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.event.VideoStatusChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class VideoSseService {

    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    // HIDDEN is terminal here because VALID_TRANSITIONS makes it terminal in Story 6.3 (Set.of() = no exits).
    // Story 6.6 DEPENDENCY: when parent approval drives HIDDEN → TRANSCODING, the emitter for HIDDEN
    // videos will already be closed. Story 6.6 must NOT rely on SSE for the HIDDEN → TRANSCODING transition.
    private static final Set<OperationalState> TERMINAL_STATES = Set.of(
        OperationalState.READY, OperationalState.LOCKED,
        OperationalState.HIDDEN, OperationalState.FAILED, OperationalState.DELETED);

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID videoId, String currentStatus) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.computeIfAbsent(videoId, id -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(videoId, emitter));
        emitter.onTimeout(() -> removeEmitter(videoId, emitter));
        emitter.onError(e -> removeEmitter(videoId, emitter));
        try {
            emitter.send(SseEmitter.event().name("status").data(currentStatus));
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send initial status to SSE subscriber for video {}", videoId, e);
            removeEmitter(videoId, emitter);
        }
        return emitter;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStatusChanged(VideoStatusChangedEvent event) {
        boolean terminal = TERMINAL_STATES.contains(event.newState());
        // For terminal events: atomically remove the list before iterating so that any concurrent
        // subscribe() call after this point creates a fresh entry and is not silently removed.
        CopyOnWriteArrayList<SseEmitter> list = terminal
            ? emitters.remove(event.videoId())
            : emitters.get(event.videoId());
        if (list == null || list.isEmpty()) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("status").data(event.newState().name()));
                if (terminal) {
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException e) {
                log.warn("Failed to push status update for video {}, removing emitter", event.videoId());
                removeEmitter(event.videoId(), emitter);
            }
        }
    }

    private void removeEmitter(UUID videoId, SseEmitter emitter) {
        emitters.compute(videoId, (id, list) -> {
            if (list == null) return null;
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
}
