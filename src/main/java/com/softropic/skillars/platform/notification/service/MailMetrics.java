package com.softropic.skillars.platform.notification.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Story ses-1.3 AC5: a transport-tagged {@code mail.send} outcome metric, alongside the existing
 * {@code mail.send_from_template} {@code @Observed} signal on {@link MailService} (this is a second,
 * distinct signal — it does not replace or duplicate that one). Mirrors the {@code MeterRegistry}
 * -backed pattern already established by {@code platform.video.service.VideoMetrics}/{@code
 * infrastructure.blobstore.service.StorageMetrics} — no new library, no new pattern.
 */
@Component
public class MailMetrics {

    public static final String MAIL_SEND = "mail.send";

    private final MeterRegistry meterRegistry;

    public MailMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordSend(String transport, String outcome, long nanos) {
        Timer.builder(MAIL_SEND)
            .tag("transport", transport)
            .tag("outcome", outcome)
            .register(meterRegistry)
            .record(nanos, TimeUnit.NANOSECONDS);
    }
}
