package com.softropic.skillars.platform.config.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigRateLimitBucketTtlSourceTest {

    /**
     * The TTL used to be read inside infrastructure.security.RateLimitingService with hand-copied
     * literals (24, 1, 8760). Pins that the platform-side port implementation reads it with the
     * ConfigBounds entry's own key, default and range.
     */
    @Test
    void bucketTtlHours_readsTheRegistryEntry() {
        ConfigService configService = mock(ConfigService.class);
        ConfigBounds.BoundedKey bound = ConfigBounds.RATE_LIMIT_BUCKET_TTL_HOURS;
        when(configService.getBoundedLong(bound)).thenReturn(48L);

        assertThat(new ConfigRateLimitBucketTtlSource(configService).bucketTtlHours()).isEqualTo(48L);
        assertThat(bound.key()).isEqualTo("security.rate_limiting.bucket_ttl_hours");
        assertThat(bound.defaultValue()).isEqualTo(24L);
        assertThat(bound.min()).isEqualTo(1L);
        assertThat(bound.max()).isEqualTo(8760L);
    }
}
