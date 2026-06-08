package com.softropic.skillars.infrastructure.storage.service;

import com.softropic.skillars.infrastructure.blobstore.service.StorageMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class StorageMetricsTest {

    private MeterRegistry meterRegistry;
    private StorageMetrics storageMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        storageMetrics = new StorageMetrics(meterRegistry);
        storageMetrics.initGauge();
    }

    @Test
    void constantsHaveExpectedValues() {
        assertThat(StorageMetrics.UPLOAD_LATENCY).isEqualTo("storage.upload.latency");
        assertThat(StorageMetrics.DOWNLOAD_LATENCY).isEqualTo("storage.download.latency");
        assertThat(StorageMetrics.DELETE_LATENCY).isEqualTo("storage.delete.latency");
        assertThat(StorageMetrics.REPLICATION_QUEUE_DEPTH).isEqualTo("storage.replication.queue.depth");
        assertThat(StorageMetrics.FILE_SIZE_BYTES).isEqualTo("storage.file.size.bytes");
        assertThat(StorageMetrics.ERROR_COUNT).isEqualTo("storage.error.count");
    }

    @Test
    void initGauge_registersReplicationQueueDepthGaugeAtZero() {
        Gauge gauge = meterRegistry.find(StorageMetrics.REPLICATION_QUEUE_DEPTH).gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(0.0);
    }

    @Test
    void updateQueueDepth_reflectsNewValueInGauge() {
        storageMetrics.updateQueueDepth(42L);

        Gauge gauge = meterRegistry.find(StorageMetrics.REPLICATION_QUEUE_DEPTH).gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(42.0);
    }

    @Test
    void recordLatency_registersTimerWithProviderAndStatusTags() {
        storageMetrics.recordLatency(StorageMetrics.UPLOAD_LATENCY, "s3", "success", 1_000_000L);

        Timer timer = meterRegistry.find(StorageMetrics.UPLOAD_LATENCY)
            .tag("provider", "s3")
            .tag("status", "success")
            .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isEqualTo(1_000_000.0);
    }

    @Test
    void recordFileSizeBytes_registersDistributionSummary() {
        storageMetrics.recordFileSizeBytes(2048L);

        DistributionSummary summary = meterRegistry.find(StorageMetrics.FILE_SIZE_BYTES).summary();
        assertThat(summary).isNotNull();
        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.totalAmount()).isEqualTo(2048.0);
    }

    @Test
    void recordError_incrementsCounterWithOperationAndErrorCodeTags() {
        storageMetrics.recordError("confirm_upload", "QUOTA_EXCEEDED");

        Counter counter = meterRegistry.find(StorageMetrics.ERROR_COUNT)
            .tag("operation", "confirm_upload")
            .tag("error_code", "QUOTA_EXCEEDED")
            .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordLatency_differentTagsProduceSeparateTimers() {
        storageMetrics.recordLatency(StorageMetrics.DOWNLOAD_LATENCY, "s3", "success", 500_000L);
        storageMetrics.recordLatency(StorageMetrics.DOWNLOAD_LATENCY, "s3", "error", 200_000L);

        Timer successTimer = meterRegistry.find(StorageMetrics.DOWNLOAD_LATENCY)
            .tag("status", "success").timer();
        Timer errorTimer = meterRegistry.find(StorageMetrics.DOWNLOAD_LATENCY)
            .tag("status", "error").timer();

        assertThat(successTimer).isNotNull();
        assertThat(errorTimer).isNotNull();
        assertThat(successTimer.count()).isEqualTo(1);
        assertThat(errorTimer.count()).isEqualTo(1);
    }
}
