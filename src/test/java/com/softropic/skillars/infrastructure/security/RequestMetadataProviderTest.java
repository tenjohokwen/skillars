package com.softropic.skillars.infrastructure.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-162 AC1.2/AC1.4(a): the client IP is the container-resolved remote address, never a
 * raw forwarded header. Tomcat's RemoteIpValve (covered by SecurityFilterChainIT) is what turns a trusted
 * proxy's X-Forwarded-For into getRemoteAddr(); by the time this provider runs, a header a client sent
 * directly must have no effect.
 */
class RequestMetadataProviderTest {

    @AfterEach
    void tearDown() {
        RequestMetadataProvider.cleanup();
    }

    @Test
    void ipAddress_isTheRemoteAddress_evenWhenForwardedHeadersArePresent() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/coach/verify-email");
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("Forwarded", "for=1.2.3.4");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        RequestMetadataProvider.initRequestMetadata(request);

        assertThat(RequestMetadataProvider.getClientInfo().getIpAddress()).isEqualTo("203.0.113.9");
    }

    @Test
    void ipAddress_withoutForwardedHeaders_isTheRemoteAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/anything");
        request.setRemoteAddr("198.51.100.4");

        RequestMetadataProvider.initRequestMetadata(request);

        assertThat(RequestMetadataProvider.getClientInfo().getIpAddress()).isEqualTo("198.51.100.4");
    }
}
