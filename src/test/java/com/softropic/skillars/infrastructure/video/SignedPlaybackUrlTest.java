package com.softropic.skillars.infrastructure.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SignedPlaybackUrlTest {

    BunnyVideoProviderAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BunnyVideoProviderAdapter(
            mock(RestTemplate.class),
            "test-api-key-for-signing",
            "12345",
            "cdn.example.com",
            "https://api.bunnycdn.com",
            new ObjectMapper(),
            3600L,
            "webhook-secret"
        );
    }

    @Test
    void generatePlaybackUrl_noIpBinding_doesNotIncludeClientIp() {
        Instant expires = Instant.now().plusSeconds(7200);
        PlaybackTokenClaims claims = new PlaybackTokenClaims("viewer-1", expires);

        SignedPlaybackUrl result = adapter.generatePlaybackUrl("asset-abc", claims);

        assertThat(result.url()).contains("cdn.example.com");
        assertThat(result.url()).contains("token=");
        assertThat(result.url()).contains("expires=");
        assertThat(result.url()).doesNotContain("clientIp");
        assertThat(result.expiresAt()).isEqualTo(expires);
    }

    @Test
    void generatePlaybackUrl_ipBindingEnabled_embedsClientIpInUrl() {
        Instant expires = Instant.now().plusSeconds(7200);
        PlaybackTokenClaims claims = new PlaybackTokenClaims("viewer-1", expires, "1.2.3.4");

        SignedPlaybackUrl result = adapter.generatePlaybackUrl("asset-abc", claims);

        assertThat(result.url()).contains("clientIp=1.2.3.4");
        assertThat(result.url()).contains("token=");
        assertThat(result.url()).contains("expires=");
    }

    @Test
    void generatePlaybackUrl_ipBindingDifferentIp_differentToken() {
        Instant expires = Instant.now().plusSeconds(7200);
        PlaybackTokenClaims claimsA = new PlaybackTokenClaims("viewer-1", expires, "1.2.3.4");
        PlaybackTokenClaims claimsB = new PlaybackTokenClaims("viewer-1", expires, "5.6.7.8");

        SignedPlaybackUrl urlA = adapter.generatePlaybackUrl("asset-abc", claimsA);
        SignedPlaybackUrl urlB = adapter.generatePlaybackUrl("asset-abc", claimsB);

        assertThat(urlA.url()).isNotEqualTo(urlB.url());
    }

    @Test
    void generatePlaybackUrl_ipv6Address_usesBracketNotation() {
        Instant expires = Instant.now().plusSeconds(7200);
        PlaybackTokenClaims claims = new PlaybackTokenClaims("viewer-1", expires, "2001:db8::1");

        SignedPlaybackUrl result = adapter.generatePlaybackUrl("asset-abc", claims);

        // IPv6 must be bracket-normalised before HMAC and in the URL to avoid delimiter ambiguity
        assertThat(result.url()).contains("clientIp=[2001:db8::1]");
    }

    @Test
    void generatePlaybackUrl_sameParamsTwice_producesIdenticalToken() {
        Instant expires = Instant.now().plusSeconds(7200);
        PlaybackTokenClaims claims = new PlaybackTokenClaims("viewer-1", expires, null);

        SignedPlaybackUrl a = adapter.generatePlaybackUrl("asset-abc", claims);
        SignedPlaybackUrl b = adapter.generatePlaybackUrl("asset-abc", claims);

        assertThat(a.url()).isEqualTo(b.url());
    }

    @Test
    void generateDownloadUrl_returnsSignedOriginalPath() {
        Instant expires = Instant.now().plusSeconds(7200);
        PlaybackTokenClaims claims = new PlaybackTokenClaims("viewer-1", expires);

        var result = adapter.generateDownloadUrl("asset-abc", claims);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("/asset-abc/original");
        assertThat(result.get()).contains("token=");
    }
}
