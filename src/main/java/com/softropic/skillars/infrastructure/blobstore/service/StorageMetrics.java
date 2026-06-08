package com.softropic.skillars.infrastructure.blobstore.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class StorageMetrics {

    public static final String UPLOAD_LATENCY = "storage.upload.latency";
    public static final String DOWNLOAD_LATENCY = "storage.download.latency";
    public static final String DELETE_LATENCY = "storage.delete.latency";
    public static final String REPLICATION_QUEUE_DEPTH = "storage.replication.queue.depth";
    public static final String FILE_SIZE_BYTES = "storage.file.size.bytes";
    public static final String ERROR_COUNT = "storage.error.count";

    private final MeterRegistry meterRegistry;
    private final AtomicLong replicationQueueDepth = new AtomicLong(0L);

    public StorageMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void initGauge() {
        Gauge.builder(REPLICATION_QUEUE_DEPTH, replicationQueueDepth, AtomicLong::get)
            .description("Count of PENDING outbox replication jobs")
            .register(meterRegistry);
    }

    public void recordLatency(String metricName, String provider, String status, long nanos) {
        Timer.builder(metricName)
            .tag("provider", provider)
            .tag("status", status)
            .register(meterRegistry)
            .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordFileSizeBytes(long sizeBytes) {
        DistributionSummary.builder(FILE_SIZE_BYTES)
            .baseUnit("bytes")
            .register(meterRegistry)
            .record((double) sizeBytes);
    }

    public void recordError(String operation, String errorCode) {
        Counter.builder(ERROR_COUNT)
            .tag("operation", operation)
            .tag("error_code", errorCode)
            .register(meterRegistry)
            .increment();
    }

    public void updateQueueDepth(long count) {
        replicationQueueDepth.set(count);
    }
}
