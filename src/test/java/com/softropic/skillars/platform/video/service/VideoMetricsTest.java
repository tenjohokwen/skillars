package com.softropic.skillars.platform.video.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class VideoMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private VideoMetrics videoMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        videoMetrics = new VideoMetrics(meterRegistry);
        videoMetrics.initGauges();
    }

    @Test
    void constantsHaveExpectedValues() {
        assertThat(VideoMetrics.UPLOAD_INIT_LATENCY).isEqualTo("video.upload.init.latency");
        assertThat(VideoMetrics.UPLOAD_CONFIRM_LATENCY).isEqualTo("video.upload.confirm.latency");
        assertThat(VideoMetrics.PLAYBACK_AUTHORIZE_LATENCY).isEqualTo("video.playback.authorize.latency");
        assertThat(VideoMetrics.WEBHOOK_PROCESSING_LATENCY).isEqualTo("video.webhook.processing.latency");
        assertThat(VideoMetrics.RECONCILIATION_CYCLE_DURATION).isEqualTo("video.reconciliation.cycle.duration");
        assertThat(VideoMetrics.WEBHOOK_QUEUE_DEPTH).isEqualTo("video.webhook.queue.depth");
        assertThat(VideoMetrics.UPLOAD_SESSION_ACTIVE).isEqualTo("video.upload.session.active");
        assertThat(VideoMetrics.ERROR_COUNT).isEqualTo("video.error.count");
    }

    @Test
    void initGauges_registersWebhookQueueDepthAtZero() {
        Gauge gauge = meterRegistry.find(VideoMetrics.WEBHOOK_QUEUE_DEPTH).gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(0.0);
    }

    @Test
    void initGauges_registersActiveUploadSessionsAtZero() {
        Gauge gauge = meterRegistry.find(VideoMetrics.UPLOAD_SESSION_ACTIVE).gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(0.0);
    }

    @Test
    void updateWebhookQueueDepth_reflectsNewValue() {
        videoMetrics.updateWebhookQueueDepth(42L);
        Gauge gauge = meterRegistry.find(VideoMetrics.WEBHOOK_QUEUE_DEPTH).gauge();
        assertThat(gauge.value()).isEqualTo(42.0);
    }

    @Test
    void updateActiveUploadSessions_reflectsNewValue() {
        videoMetrics.updateActiveUploadSessions(10L);
        Gauge gauge = meterRegistry.find(VideoMetrics.UPLOAD_SESSION_ACTIVE).gauge();
        assertThat(gauge.value()).isEqualTo(10.0);
    }

    @Test
    void recordUploadInitLatency_registersTimerWithProviderAndStatusTags() {
        videoMetrics.recordUploadInitLatency("bunny", "success", 1_000_000L);

        Timer timer = meterRegistry.find(VideoMetrics.UPLOAD_INIT_LATENCY)
            .tag("provider", "bunny").tag("status", "success").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void recordWebhookProcessingLatency_registersTimerWithEventTypeAndStatusTags() {
        videoMetrics.recordWebhookProcessingLatency("video.encoding.success", "success", 500_000L);

        Timer timer = meterRegistry.find(VideoMetrics.WEBHOOK_PROCESSING_LATENCY)
            .tag("event_type", "video.encoding.success").tag("status", "success").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void recordError_incrementsCounterWithBothTags() {
        videoMetrics.recordError("initialize_upload", "QUOTA_EXCEEDED");

        Counter counter = meterRegistry.find(VideoMetrics.ERROR_COUNT)
            .tag("operation", "initialize_upload").tag("error_code", "QUOTA_EXCEEDED").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordUploadConfirmLatency_registersTimerWithStatusTag() {
        videoMetrics.recordUploadConfirmLatency("error", 200_000L);

        Timer timer = meterRegistry.find(VideoMetrics.UPLOAD_CONFIRM_LATENCY)
            .tag("status", "error").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void recordReconciliationCycleDuration_registersTimer() {
        videoMetrics.recordReconciliationCycleDuration(5_000_000_000L);

        Timer timer = meterRegistry.find(VideoMetrics.RECONCILIATION_CYCLE_DURATION).timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isEqualTo(5_000_000_000.0);
    }
}
