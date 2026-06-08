package com.softropic.skillars.infrastructure.security;

import com.softropic.skillars.infrastructure.security.AuthorizationException;
import com.softropic.skillars.infrastructure.security.RequestMetadata;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.infrastructure.security.RateLimited;
import com.softropic.skillars.infrastructure.security.RateLimitingAspect;
import com.softropic.skillars.infrastructure.security.RateLimitingService;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.test.annotation.DirtiesContext;

import java.util.concurrent.TimeUnit;

import static com.softropic.skillars.infrastructure.security.SecurityError.TOO_MANY_REQUESTS;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = RateLimitingAspectIT.TestService.class)
@Import(RateLimitingAspectIT.AspectConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class RateLimitingAspectIT {

    @TestConfiguration
    @EnableAspectJAutoProxy
    static class AspectConfig {
        @Bean
        public RateLimitingService rateLimitingService() {
            return new RateLimitingService();
        }

        @Bean
        public RateLimitingAspect rateLimitingAspect(RateLimitingService rateLimitingService) {
            return new RateLimitingAspect(rateLimitingService);
        }
    }

    @Component
    static class TestService {
        @RateLimited(key = "test_method", capacity = 2, duration = 1, unit = TimeUnit.MINUTES)
        public void limitedMethod() {
            // Logic here
        }
    }

    @Autowired
    private TestService testService;

    @Test
    void testRateLimiting_Enforced() {
        RequestMetadata mockMetadata = mock(RequestMetadata.class);
        when(mockMetadata.getIpAddress()).thenReturn("1.2.3.4");

        try (MockedStatic<RequestMetadataProvider> mockedStatic = mockStatic(RequestMetadataProvider.class)) {
            mockedStatic.when(RequestMetadataProvider::getClientInfo).thenReturn(mockMetadata);

            // First two calls should succeed
            assertThatCode(() -> testService.limitedMethod()).doesNotThrowAnyException();
            assertThatCode(() -> testService.limitedMethod()).doesNotThrowAnyException();

            // Third call should fail
            assertThatThrownBy(() -> testService.limitedMethod())
                    .isInstanceOf(AuthorizationException.class)
                    .extracting(e -> ((AuthorizationException) e).getErrorCode())
                    .isEqualTo(TOO_MANY_REQUESTS);
        }
    }

    @Test
    void testRateLimiting_DifferentIps() {
        RequestMetadata metadataIp1 = mock(RequestMetadata.class);
        when(metadataIp1.getIpAddress()).thenReturn("1.1.1.1");

        RequestMetadata metadataIp2 = mock(RequestMetadata.class);
        when(metadataIp2.getIpAddress()).thenReturn("2.2.2.2");

        try (MockedStatic<RequestMetadataProvider> mockedStatic = mockStatic(RequestMetadataProvider.class)) {
            // Setup for IP 1
            mockedStatic.when(RequestMetadataProvider::getClientInfo).thenReturn(metadataIp1);
            testService.limitedMethod();
            testService.limitedMethod();
            
            assertThatThrownBy(() -> testService.limitedMethod())
                    .isInstanceOf(AuthorizationException.class);

            // Switch to IP 2 - should be allowed
            mockedStatic.when(RequestMetadataProvider::getClientInfo).thenReturn(metadataIp2);
            assertThatCode(() -> testService.limitedMethod()).doesNotThrowAnyException();
        }
    }
}
