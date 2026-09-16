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
    // skillars-deferred-114 code review (LOW): extracted to a constant for the same reason as
    // MAIL_SEND above — a typo in an inline string literal would silently create a second,
    // never-incremented metric name instead of failing to compile.
    public static final String MAIL_ADMIN_ALERT_OUTCOME_UNKNOWN = "mail.admin_alert.outcome_unknown";

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

    /**
     * skillars-deferred-114 AC2: {@code VideoModerationEmailListener.sendAdminAlertSync}'s
     * {@code persisted == null} branch already logs a rich diagnostic WARN — this makes a real
     * occurrence Grafana-queryable too, without waiting for someone to grep logs for
     * {@code [VIDEO_MODERATION_ADMIN_ALERT]}. Diagnostics only, by owner decision — see that
     * method's own javadoc for why no structural fix (bounded retry / forced flush / re-read) is
     * attempted here.
     */
    public void recordAdminAlertOutcomeUnknown() {
        meterRegistry.counter(MAIL_ADMIN_ALERT_OUTCOME_UNKNOWN).increment();
    }
}
