package com.softropic.skillars.platform.messaging.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class MessagingEmitterRegistry {

    // Sends a ": connected" SSE comment shortly after registration so clients get an immediate
    // acknowledgment that the stream is live. The 200 ms delay ensures Spring's handleReturnValue()
    // has finished calling initialize() on the emitter before the send.
    private static final ScheduledExecutorService CONNECT_FLUSHER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-connect-flush");
            t.setDaemon(true);
            return t;
        });

    private final long sseTimeoutMs;
    private final ConcurrentHashMap<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    public MessagingEmitterRegistry(
            @Value("${platform.messaging.sse-timeout-ms:300000}") long sseTimeoutMs) {
        this.sseTimeoutMs = sseTimeoutMs;
    }

    public SseEmitter register(Long userId) {
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        emitter.onCompletion(() -> emitters.remove(userId, emitter));
        emitter.onTimeout(() -> emitters.remove(userId, emitter));
        emitter.onError(e -> emitters.remove(userId, emitter));
        // Complete the displaced emitter so its servlet async context is released
        SseEmitter old = emitters.put(userId, emitter);
        if (old != null) {
            try { old.complete(); } catch (Exception ignored) {}
        }
        CONNECT_FLUSHER.schedule(() -> {
            try {
                emitter.send(SseEmitter.event().comment("connected"));
            } catch (IOException e) {
                log.warn("SSE connect-flush: IOException for userId {}: {}", userId, e.getMessage());
            } catch (IllegalStateException e) {
                log.warn("SSE connect-flush: IllegalStateException for userId {}: {}", userId, e.getMessage());
            }
        }, 200, TimeUnit.MILLISECONDS);
        return emitter;
    }

    public void emit(Long recipientUserId, Object event) {
        if (recipientUserId == null) return;
        SseEmitter emitter = emitters.get(recipientUserId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name("NEW_MESSAGE").data(event));
        } catch (IOException | IllegalStateException e) {
            log.debug("Failed to emit to userId {}, removing emitter", recipientUserId);
            emitters.remove(recipientUserId, emitter);
        }
    }

    public void emit(Long recipientUserId, String eventType, Object event) {
        if (recipientUserId == null) return;
        SseEmitter emitter = emitters.get(recipientUserId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name(eventType).data(event));
        } catch (IOException | IllegalStateException e) {
            log.debug("Failed to emit {} to userId {}, removing emitter", eventType, recipientUserId);
            emitters.remove(recipientUserId, emitter);
        }
    }

    @Scheduled(fixedDelay = 30_000)
    public void sendHeartbeats() {
        emitters.forEach((userId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data(""));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(userId, emitter);
            }
        });
    }
}
