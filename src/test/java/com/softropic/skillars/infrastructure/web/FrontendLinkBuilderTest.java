package com.softropic.skillars.infrastructure.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class FrontendLinkBuilderTest {

    private final FrontendLinkBuilder links = new FrontendLinkBuilder("https://skillars.com/");

    @Test
    void base_stripsTrailingSlash() {
        assertThat(links.base()).isEqualTo("https://skillars.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"parent/bookings", "/parent/bookings", "#/parent/bookings", "/#/parent/bookings"})
    void route_alwaysProducesExactlyOneHashPrefix(String path) {
        assertThat(links.route(path)).isEqualTo("https://skillars.com/#/parent/bookings");
    }

    @Test
    void resolve_relativePath_isAHashRoute() {
        assertThat(links.resolve("/coach/payment-settings"))
            .isEqualTo("https://skillars.com/#/coach/payment-settings");
    }

    @Test
    void resolve_absoluteUrl_isUsedVerbatim() {
        assertThat(links.resolve("http://localhost:9000/#/coach/payment-settings"))
            .isEqualTo("http://localhost:9000/#/coach/payment-settings");
    }
}
