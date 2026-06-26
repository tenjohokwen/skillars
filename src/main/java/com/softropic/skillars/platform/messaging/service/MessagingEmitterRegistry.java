package com.softropic.skillars.platform.messaging.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class MessagingEmitterRegistry {

    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;
    private final ConcurrentHashMap<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(Long userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onCompletion(() -> emitters.remove(userId, emitter));
        emitter.onTimeout(() -> emitters.remove(userId, emitter));
        emitter.onError(e -> emitters.remove(userId, emitter));
        // Complete the displaced emitter so its servlet async context is released
        SseEmitter old = emitters.put(userId, emitter);
        if (old != null) {
            try { old.complete(); } catch (Exception ignored) {}
        }
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
