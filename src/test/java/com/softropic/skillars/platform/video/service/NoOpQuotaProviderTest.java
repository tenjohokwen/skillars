package com.softropic.skillars.platform.video.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class NoOpQuotaProviderTest {

    private final NoOpQuotaProvider provider = new NoOpQuotaProvider();

    @Test
    void checkAlwaysReturnsTrue() {
        assertThat(provider.check("owner-1", 1024L)).isTrue();
        assertThat(provider.check("owner-2", Long.MAX_VALUE)).isTrue();
        assertThat(provider.check("owner-3", 0L)).isTrue();
    }

    @Test
    void reserveReturnsNonNull() {
        String handle = provider.reserve("owner-1", 2048L);
        assertThat(handle).isNotNull();
    }

    @Test
    void commitCompletesWithoutException() {
        assertThatNoException().isThrownBy(() -> provider.commit("some-handle"));
    }

    @Test
    void releaseCompletesWithoutExceptionForAnyHandle() {
        assertThatNoException().isThrownBy(() -> provider.release("some-handle"));
        assertThatNoException().isThrownBy(() -> provider.release("noop-reservation-handle"));
        assertThatNoException().isThrownBy(() -> provider.release("arbitrary-string"));
    }
}
