package com.softropic.skillars.platform.video.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class VideoMetrics {

    public static final String UPLOAD_INIT_LATENCY = "video.upload.init.latency";
    public static final String UPLOAD_CONFIRM_LATENCY = "video.upload.confirm.latency";
    public static final String PLAYBACK_AUTHORIZE_LATENCY = "video.playback.authorize.latency";
    public static final String WEBHOOK_PROCESSING_LATENCY = "video.webhook.processing.latency";
    public static final String RECONCILIATION_CYCLE_DURATION = "video.reconciliation.cycle.duration";
    public static final String WEBHOOK_QUEUE_DEPTH = "video.webhook.queue.depth";
    public static final String UPLOAD_SESSION_ACTIVE = "video.upload.session.active";
    public static final String ERROR_COUNT = "video.error.count";

    private final MeterRegistry meterRegistry;
    private final AtomicLong webhookQueueDepth = new AtomicLong(0L);
    private final AtomicLong activeUploadSessions = new AtomicLong(0L);

    public VideoMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void initGauges() {
        Gauge.builder(WEBHOOK_QUEUE_DEPTH, webhookQueueDepth, AtomicLong::get)
            .description("Count of PENDING video webhook events")
            .register(meterRegistry);
        Gauge.builder(UPLOAD_SESSION_ACTIVE, activeUploadSessions, AtomicLong::get)
            .description("Count of PENDING upload sessions")
            .register(meterRegistry);
    }

    public void recordUploadInitLatency(String provider, String status, long nanos) {
        Timer.builder(UPLOAD_INIT_LATENCY)
            .tag("provider", provider)
            .tag("status", status)
            .register(meterRegistry)
            .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordUploadConfirmLatency(String status, long nanos) {
        Timer.builder(UPLOAD_CONFIRM_LATENCY)
            .tag("status", status)
            .register(meterRegistry)
            .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordPlaybackAuthorizeLatency(String status, long nanos) {
        Timer.builder(PLAYBACK_AUTHORIZE_LATENCY)
            .tag("status", status)
            .register(meterRegistry)
            .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordWebhookProcessingLatency(String eventType, String status, long nanos) {
        Timer.builder(WEBHOOK_PROCESSING_LATENCY)
            .tag("event_type", eventType)
            .tag("status", status)
            .register(meterRegistry)
            .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordReconciliationCycleDuration(long nanos) {
        Timer.builder(RECONCILIATION_CYCLE_DURATION)
            .register(meterRegistry)
            .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordError(String operation, String errorCode) {
        Counter.builder(ERROR_COUNT)
            .tag("operation", operation)
            .tag("error_code", errorCode)
            .register(meterRegistry)
            .increment();
    }

    public void updateWebhookQueueDepth(long count) {
        webhookQueueDepth.set(count);
    }

    public void updateActiveUploadSessions(long count) {
        activeUploadSessions.set(count);
    }
}
