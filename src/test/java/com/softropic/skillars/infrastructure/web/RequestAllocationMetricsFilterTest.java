package com.softropic.skillars.infrastructure.web;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestAllocationMetricsFilterTest {

    private static final int ALLOCATION = 4 * 1024 * 1024;

    /** Keeps the allocation reachable so the JIT cannot optimise it away. */
    private byte[] retained;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @SuppressWarnings("unchecked")
    private RequestAllocationMetricsFilter filter(MeterRegistry meterRegistry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(meterRegistry);
        return new RequestAllocationMetricsFilter(provider);
    }

    private FilterChain matchingRoute(String route) {
        return (request, response) -> {
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, route);
            retained = new byte[ALLOCATION];
        };
    }

    @Test
    void recordsTheHeapARequestAllocated_taggedByMethodAndRoutePattern() throws Exception {
        filter(registry).doFilter(new MockHttpServletRequest("GET", "/api/bookings/42"),
            new MockHttpServletResponse(), matchingRoute("/api/bookings/{id}"));

        DistributionSummary summary = registry.get(RequestAllocationMetricsFilter.METRIC)
            .tags("method", "GET", "uri", "/api/bookings/{id}").summary();
        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.totalAmount()).isGreaterThanOrEqualTo(ALLOCATION);
        assertThat(retained).hasSize(ALLOCATION);
    }

    @Test
    void anUnmatchedRequest_isBucketedSoRawPathsCannotExplodeCardinality() throws Exception {
        filter(registry).doFilter(new MockHttpServletRequest("GET", "/no/such/path/8f3a"),
            new MockHttpServletResponse(), (request, response) -> { });

        assertThat(registry.get(RequestAllocationMetricsFilter.METRIC).tag("uri", "UNKNOWN").summary().count())
            .isEqualTo(1);
    }

    @Test
    void aRequestThatThrows_isStillRecorded() {
        FilterChain failing = (request, response) -> {
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/boom");
            throw new IOException("boom");
        };

        assertThatThrownBy(() -> filter(registry).doFilter(new MockHttpServletRequest("POST", "/api/boom"),
            new MockHttpServletResponse(), failing)).isInstanceOf(IOException.class);

        assertThat(registry.get(RequestAllocationMetricsFilter.METRIC).tag("uri", "/api/boom").summary().count())
            .isEqualTo(1);
    }

    @Test
    void withNoRegistry_theRequestStillRuns() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(null).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
