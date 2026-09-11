package com.softropic.skillars.infrastructure.ses;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.SesV2ClientBuilder;

import java.net.URI;
import java.time.Duration;

/**
 * Wires the SES v2 client, gated purely on {@code app.email.transport=ses} — see story ses-1.1 AC6.
 * {@code app.ses.enabled} no longer exists.
 */
@Configuration
@EnableConfigurationProperties(SesProperties.class)
public class SesConfig {

    /**
     * Exposed as a bean, unlike {@code BlobstoreConfig}'s private equivalent, so that
     * {@link SesPropertiesValidator} can resolve it at startup and fail fast on a credential chain
     * that cannot produce credentials (code review 2026-09-11, D2). No other
     * {@code AwsCredentialsProvider} bean exists in the context — blobstore keeps both of its
     * providers private — so this introduces no by-type injection ambiguity.
     */
    @Bean
    @ConditionalOnProperty(name = "app.email.transport", havingValue = "ses")
    public AwsCredentialsProvider sesCredentialsProvider(SesProperties props) {
        // Mirrors BlobstoreConfig.credentialsProvider (infrastructure/blobstore/config/
        // BlobstoreConfig.java:40-56) exactly — don't reinvent.
        if (props.getAccessKey() != null && !props.getAccessKey().isBlank()
            && props.getSecretKey() != null && !props.getSecretKey().isBlank()) {
            return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey()));
        }
        return DefaultCredentialsProvider.create();
    }

    @Bean
    @ConditionalOnProperty(name = "app.email.transport", havingValue = "ses")
    public SesV2Client sesV2Client(SesProperties props, AwsCredentialsProvider sesCredentialsProvider) {
        SesV2ClientBuilder builder = SesV2Client.builder()
            .region(Region.of(props.getRegion()))
            .credentialsProvider(sesCredentialsProvider)
            .overrideConfiguration(c -> c
                // §6.4 wants zero SDK-level retries *once MailManager owns the call* — its
                // EmailRetryScheduler x RetryTemplate then implement the retry semantics we want.
                // That is Phase 4. Until then nothing consumes SesErrorClassifier's
                // transient/permanent split at all, so disabling SDK retries here would leave the
                // six registration/OTP emails single-attempt, with no resend endpoint behind them
                // — strictly worse than the client this replaced, which carried the SDK default.
                // Keep the default retry strategy and flip to AwsRetryStrategy.doNotRetry() in
                // Phase 4, when the comment above becomes true (code review 2026-09-11, D1).
                //
                // apiCallTimeout bounds the WHOLE call including every retry attempt, so retries
                // cannot extend the worst case beyond 5s in an AFTER_COMMIT listener; the 2-second
                // gap to apiCallAttemptTimeout covers marshalling, signing and credential
                // resolution, which sit outside any single attempt's budget.
                .apiCallTimeout(Duration.ofSeconds(5))
                .apiCallAttemptTimeout(Duration.ofSeconds(3)));

        if (props.getEndpointUrl() != null && !props.getEndpointUrl().isBlank()) {
            // Needed for the WireMock-backed SesEmailEndToEndIT.
            builder.endpointOverride(URI.create(props.getEndpointUrl()));
        }

        return builder.build();
    }
}
