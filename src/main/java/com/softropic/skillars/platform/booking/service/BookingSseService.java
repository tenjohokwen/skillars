package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.BookingStatusChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class BookingSseService {

    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID bookingId, String currentStatus) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        emitters.computeIfAbsent(bookingId, id -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(bookingId, emitter));
        emitter.onTimeout(() -> {
            tryHeartbeat(emitter);
            removeEmitter(bookingId, emitter);
        });
        emitter.onError(e -> removeEmitter(bookingId, emitter));

        try {
            emitter.send(SseEmitter.event().name("status").data(currentStatus));
        } catch (IOException e) {
            log.warn("Failed to send initial status to SSE subscriber for booking {}", bookingId, e);
            removeEmitter(bookingId, emitter);
        }

        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStatusChanged(BookingStatusChangedEvent event) {
        List<SseEmitter> list = emitters.get(event.bookingId());
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("status").data(event.newStatus()));
            } catch (IOException e) {
                log.warn("Failed to push status update to SSE client for booking {}, removing emitter",
                    event.bookingId());
                removeEmitter(event.bookingId(), emitter);
            }
        }
    }

    private void removeEmitter(UUID bookingId, SseEmitter emitter) {
        emitters.compute(bookingId, (id, list) -> {
            if (list == null) return null;
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }

    private void tryHeartbeat(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("heartbeat").data(""));
        } catch (IOException e) {
            log.debug("Heartbeat send failed (client likely already disconnected)", e);
        }
    }
}
