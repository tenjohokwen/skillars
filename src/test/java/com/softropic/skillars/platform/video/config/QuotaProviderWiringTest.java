package com.softropic.skillars.platform.video.config;

import com.softropic.skillars.platform.video.contract.QuotaProvider;
import com.softropic.skillars.platform.video.service.NoOpQuotaProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class QuotaProviderWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(VideoConfig.class)
        .withPropertyValues(
            "app.video.provider=bunny",
            "app.video.bunny.api-key=test-key",
            "app.video.bunny.library-id=123",
            "app.video.bunny.cdn-hostname=test.b-cdn.net",
            "app.video.upload.max-bytes=5368709120",
            "app.video.upload.allowed-mime-types=video/mp4",
            "app.video.upload.allowed-formats=MP4",
            "app.video.upload.session-ttl-minutes=60",
            "app.video.upload.rate-limit.requests-per-minute=10",
            "app.video.playback.token-ttl-minutes=15",
            "app.video.playback.token-max-ttl-minutes=120",
            "app.video.playback.revocation-window-hours=24",
            "app.video.reconciliation.fixed-delay-ms=60000",
            "app.video.reconciliation.batch-size=10"
        );

    @Test
    void failsAtStartupWhenNoQuotaProviderBeanRegistered() {
        contextRunner.run(context ->
            assertThat(context).hasFailed()
                .getFailure()
                .hasMessageContaining("QuotaProvider")
        );
    }

    @Test
    void startsWhenNoOpQuotaProviderRegistered() {
        contextRunner
            .withBean(QuotaProvider.class, NoOpQuotaProvider::new)
            .run(context -> assertThat(context).hasNotFailed());
    }
}
