package com.softropic.skillars.infrastructure.ses;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story skillars-deferred-88 AC9 — the four {@code app.ses.enabled} {@code @ConditionalOnProperty}
 * gates are left exactly as they were (M4). This pins the wiring the fail-fast validator assumes:
 * {@code true} -> real client + impl, {@code false}/unset -> {@code NoOpSesEmailService}. Uses an
 * {@link ApplicationContextRunner} (no {@code @SpringBootTest} context — Dev Notes: the context-count
 * ceiling matters).
 */
class SesConditionalWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(UserConfigurations.of(
            SesConfig.class, SesEmailServiceImpl.class, NoOpSesEmailService.class))
        .withPropertyValues("app.ses.region=eu-west-1");

    @Test
    void enabledTrue_wiresSesV2ClientAndRealImpl() {
        runner.withPropertyValues("app.ses.enabled=true").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(SesV2Client.class);
            assertThat(ctx).getBean(SesEmailService.class).isInstanceOf(SesEmailServiceImpl.class);
        });
    }

    @Test
    void enabledFalse_selectsNoOpAndNoClient() {
        runner.withPropertyValues("app.ses.enabled=false").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(SesV2Client.class);
            assertThat(ctx).getBean(SesEmailService.class).isInstanceOf(NoOpSesEmailService.class);
        });
    }

    @Test
    void unset_selectsNoOpAndNoClient() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(SesV2Client.class);
            assertThat(ctx).getBean(SesEmailService.class).isInstanceOf(NoOpSesEmailService.class);
        });
    }
}
