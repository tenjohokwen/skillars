package com.softropic.skillars.platform.video.config;

import com.softropic.skillars.platform.video.contract.QuotaProvider;
import com.softropic.skillars.platform.video.service.QuotaConfigService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@EnableConfigurationProperties(VideoProperties.class)
public class VideoConfig {

    // Bunny's documented minimum TUS AuthorizationExpire is 3600 seconds (1 hour).
    // Below this, all TUS uploads fail with 401 from Bunny — no other error surfaced.
    private static final long BUNNY_MIN_TUS_EXPIRY_MINUTES = 60L;

    private final VideoProperties videoProperties;
    private final QuotaConfigService quotaConfigService;

    public VideoConfig(VideoProperties videoProperties, QuotaConfigService quotaConfigService) {
        this.videoProperties = videoProperties;
        this.quotaConfigService = quotaConfigService;
    }

    @PostConstruct
    void validateTimeoutAlignment() {
        long reservationTimeoutMinutes = quotaConfigService.getReservationTimeoutMinutes();
        long sessionTtlMinutes = videoProperties.getUpload().getSessionTtlMinutes();

        if (sessionTtlMinutes < BUNNY_MIN_TUS_EXPIRY_MINUTES) {
            throw new IllegalStateException(String.format(
                "app.video.upload.session-ttl-minutes (%d) is below Bunny.net's documented minimum " +
                "of %d minutes for TUS AuthorizationExpire. All TUS uploads will receive 401.",
                sessionTtlMinutes, BUNNY_MIN_TUS_EXPIRY_MINUTES));
        }

        // Skip check when value is 0 — indicates config not yet loaded (e.g. test context with an unstubbed mock).
        if (reservationTimeoutMinutes > 0 && reservationTimeoutMinutes < sessionTtlMinutes) {
            throw new IllegalStateException(String.format(
                "platform.video_reservation_timeout_minutes (%d) must be >= " +
                "app.video.upload.session-ttl-minutes (%d). " +
                "If the reaper fires before the TUS upload completes, quota commits silently no-op.",
                reservationTimeoutMinutes, sessionTtlMinutes));
        }
        log.info("Quota timeout alignment validated: bunnyMin={}min, sessionTtl={}min, reservationTimeout={}min",
                 BUNNY_MIN_TUS_EXPIRY_MINUTES, sessionTtlMinutes, reservationTimeoutMinutes);
    }

    @Bean
    public QuotaProviderValidator quotaProviderValidator(ObjectProvider<QuotaProvider> quotaProviderProvider) {
        QuotaProvider quotaProvider = quotaProviderProvider.getIfAvailable();
        if (quotaProvider == null) {
            throw new IllegalStateException(
                "Video module requires a QuotaProvider bean. " +
                "Register an implementation in your application @Configuration. " +
                "To disable quota enforcement, use: @Bean QuotaProvider quotaProvider() { return new NoOpQuotaProvider(); }");
        }
        log.info("QuotaProvider active: {} — consistency guarantee: {}",
            quotaProvider.getClass().getSimpleName(), quotaProvider.getConsistencyGuarantee());
        return new QuotaProviderValidator(quotaProvider);
    }

    public record QuotaProviderValidator(QuotaProvider quotaProvider) {}
}
