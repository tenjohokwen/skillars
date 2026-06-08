package com.softropic.skillars.infrastructure.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WireMockTest
class BunnyVideoProviderAdapterTest {

    private static final String API_KEY = "test-api-key";
    private static final String LIBRARY_ID = "123";
    private static final String CDN_HOSTNAME = "test.b-cdn.net";

    private BunnyVideoProviderAdapter adapter;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wireMock) {
        adapter = new BunnyVideoProviderAdapter(
            new RestTemplate(),
            API_KEY,
            LIBRARY_ID,
            CDN_HOSTNAME,
            wireMock.getHttpBaseUrl(),
            new ObjectMapper()
        );
    }

    @Test
    void initializeUpload_success() {
        stubFor(post(urlEqualTo("/library/123/videos"))
            .withHeader("AccessKey", equalTo(API_KEY))
            .willReturn(okJson("{\"guid\":\"abc-guid\",\"status\":0}")));

        UploadCredentials credentials = adapter.initializeUpload("test.mp4", 1024L);

        assertThat(credentials.providerUploadId()).isEqualTo("abc-guid");
        assertThat(credentials.signedUploadUrl()).contains("/tusupload");
    }

    @ParameterizedTest(name = "Bunny status {0} maps to {1}")
    @CsvSource({
        "0, UPLOADING",
        "1, UPLOADING",
        "2, PROCESSING",
        "3, PROCESSING",
        "7, PROCESSING",
        "4, READY",
        "8, READY",
        "5, FAILED",
        "6, FAILED",
        "99, PROCESSING"
    })
    void getAssetStatus_mapsStatusCorrectly(int bunnyStatus, AssetStatus expected) {
        stubFor(get(urlEqualTo("/library/123/videos/vid-001"))
            .willReturn(okJson("{\"guid\":\"vid-001\",\"status\":" + bunnyStatus + "}")));

        assertThat(adapter.getAssetStatus("vid-001")).isEqualTo(expected);
    }

    @Test
    void getAssetStatus_returns404_mapsToDeleted() {
        stubFor(get(urlEqualTo("/library/123/videos/deleted-vid"))
            .willReturn(aResponse().withStatus(404)));

        assertThat(adapter.getAssetStatus("deleted-vid")).isEqualTo(AssetStatus.DELETED);
    }

    @Test
    void generatePlaybackUrl_returnsCorrectlySignedUrl() {
        Instant expiresAt = Instant.ofEpochSecond(1800000000L);
        PlaybackTokenClaims claims = new PlaybackTokenClaims("viewer-1", expiresAt);

        SignedPlaybackUrl result = adapter.generatePlaybackUrl("vid-xyz", claims);

        assertThat(result.url()).startsWith("https://test.b-cdn.net/vid-xyz/playlist.m3u8");
        assertThat(result.url()).contains("token=HS256-");
        assertThat(result.url()).contains("expires=1800000000");
        assertThat(result.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void deleteAsset_firesDeleteRequest() {
        stubFor(delete(urlEqualTo("/library/123/videos/vid-del"))
            .withHeader("AccessKey", equalTo(API_KEY))
            .willReturn(okJson("{\"success\":true,\"message\":null,\"statusCode\":200}")));

        adapter.deleteAsset("vid-del");

        verify(deleteRequestedFor(urlEqualTo("/library/123/videos/vid-del"))
            .withHeader("AccessKey", equalTo(API_KEY)));
    }

    @Test
    void verifyWebhook_validHmac_returnsWebhookEvent() throws Exception {
        String payload = "{\"EventType\":\"video.upload.success\",\"VideoGuid\":\"abc-guid\",\"Timestamp\":1717000000}";
        String signature = computeHmac(API_KEY, payload);

        WebhookEvent event = adapter.verifyWebhook(payload, signature);

        assertThat(event.eventType()).isEqualTo("video.upload.success");
        assertThat(event.providerAssetId()).isEqualTo("abc-guid");
        assertThat(event.timestamp()).isEqualTo(Instant.ofEpochSecond(1717000000));
    }

    @Test
    void verifyWebhook_invalidHmac_throwsVideoProviderException() {
        String payload = "{\"EventType\":\"video.upload.success\",\"VideoGuid\":\"abc-guid\",\"Timestamp\":1717000000}";
        String wrongSignature = "0".repeat(64);

        assertThatThrownBy(() -> adapter.verifyWebhook(payload, wrongSignature))
            .isInstanceOf(VideoProviderException.class);
    }

    @Test
    void getThumbnailUrl_returnsDeterministicUrl() {
        String url = adapter.getThumbnailUrl("vid-thumb");
        assertThat(url).isEqualTo("https://test.b-cdn.net/vid-thumb/thumbnail.jpg");
    }

    @Test
    void addCaptionTrack_firesPostToCorrectEndpoint() {
        stubFor(post(urlEqualTo("/library/123/videos/vid-001/captions/en"))
            .withHeader("AccessKey", equalTo(API_KEY))
            .willReturn(okJson("{\"success\":true}")));

        adapter.addCaptionTrack("vid-001", "en", "https://example.com/subtitles.srt");

        verify(postRequestedFor(urlEqualTo("/library/123/videos/vid-001/captions/en"))
            .withHeader("AccessKey", equalTo(API_KEY)));
    }

    private String computeHmac(String key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hmac = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hmac);
    }
}
