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
    private static final String WEBHOOK_SIGNING_SECRET = "test-webhook-secret";
    private static final long SESSION_TTL_SECONDS = 3600L;

    private BunnyVideoProviderAdapter adapter;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wireMock) {
        adapter = new BunnyVideoProviderAdapter(
            new RestTemplate(),
            API_KEY,
            LIBRARY_ID,
            CDN_HOSTNAME,
            wireMock.getHttpBaseUrl(),
            new ObjectMapper(),
            SESSION_TTL_SECONDS,
            WEBHOOK_SIGNING_SECRET
        );
    }

    @Test
    void initializeUpload_success() {
        stubFor(post(urlEqualTo("/library/123/videos"))
            .withHeader("AccessKey", equalTo(API_KEY))
            .willReturn(okJson("{\"guid\":\"abc-guid\",\"status\":0}")));

        UploadCredentials credentials = adapter.initializeUpload("test.mp4", 1024L);

        assertThat(credentials.providerUploadId()).isEqualTo("abc-guid");
        assertThat(credentials.signedUploadUrl()).isEqualTo("https://video.bunnycdn.com/tusupload");
        assertThat(credentials.tusLibraryId()).isEqualTo(123L);
        // TUS signature = SHA-256(libraryId + apiKey + expireEpoch + videoGuid), always 64 hex chars
        assertThat(credentials.tusAuthorizationSignature()).hasSize(64).matches("[0-9a-f]+");
        // Expire must be at least SESSION_TTL_SECONDS from now
        assertThat(credentials.tusAuthorizationExpire())
            .isGreaterThanOrEqualTo(Instant.now().getEpochSecond() + SESSION_TTL_SECONDS - 5);
    }

    @ParameterizedTest(name = "Bunny status {0} maps to {1}")
    @CsvSource({
        "0, UPLOADING",
        "1, PROCESSING",
        "2, PROCESSING",
        "3, READY",
        "4, READY",
        "5, FAILED",
        "6, UPLOADING",
        "7, PROCESSING",
        "8, FAILED",
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
    void getVideoMetadata_success_returnsDurationAndStorage() {
        stubFor(get(urlEqualTo("/library/123/videos/vid-meta"))
            .withHeader("AccessKey", equalTo(API_KEY))
            .willReturn(okJson("{\"guid\":\"vid-meta\",\"status\":3,\"length\":120,\"storageSize\":10485760}")));

        VideoMetadata meta = adapter.getVideoMetadata("vid-meta");

        assertThat(meta.durationMs()).isEqualTo(120_000L); // 120s * 1000
        assertThat(meta.storageBytes()).isEqualTo(10_485_760L);
    }

    @Test
    void getVideoMetadata_nullFields_returnsZeros() {
        // Video still encoding — length and storageSize absent (Jackson maps to null for boxed types)
        stubFor(get(urlEqualTo("/library/123/videos/vid-encoding"))
            .willReturn(okJson("{\"guid\":\"vid-encoding\",\"status\":2}")));

        VideoMetadata meta = adapter.getVideoMetadata("vid-encoding");

        assertThat(meta.durationMs()).isZero();
        assertThat(meta.storageBytes()).isZero();
    }

    @Test
    void getVideoMetadata_returns404_throwsVideoProviderException() {
        stubFor(get(urlEqualTo("/library/123/videos/missing"))
            .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> adapter.getVideoMetadata("missing"))
            .isInstanceOf(VideoProviderException.class);
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
    void verifyWebhook_validHmac_status7_returnsUploadSuccess() throws Exception {
        // Actual Bunny webhook payload format: three fixed fields
        String payload = "{\"VideoLibraryId\":123,\"VideoGuid\":\"abc-guid\",\"Status\":7}";
        String signature = computeHmac(WEBHOOK_SIGNING_SECRET, payload);

        WebhookEvent event = adapter.verifyWebhook(payload, signature);

        assertThat(event.videoLibraryId()).isEqualTo(123L);
        assertThat(event.eventType()).isEqualTo("video.upload.success");
        assertThat(event.providerAssetId()).isEqualTo("abc-guid");
        // verifyWebhook() uses Instant.now() as timestamp — assert it is recent
        assertThat(event.timestamp()).isAfter(Instant.now().minusSeconds(5));
    }

    @Test
    void verifyWebhook_validHmac_status3_returnsEncodingSuccess() throws Exception {
        String payload = "{\"VideoLibraryId\":123,\"VideoGuid\":\"abc-guid\",\"Status\":3}";
        String signature = computeHmac(WEBHOOK_SIGNING_SECRET, payload);

        WebhookEvent event = adapter.verifyWebhook(payload, signature);

        assertThat(event.eventType()).isEqualTo("video.encoding.success");
    }

    @Test
    void verifyWebhook_validHmac_status5_returnsEncodingFailed() throws Exception {
        String payload = "{\"VideoLibraryId\":123,\"VideoGuid\":\"abc-guid\",\"Status\":5}";
        String signature = computeHmac(WEBHOOK_SIGNING_SECRET, payload);

        WebhookEvent event = adapter.verifyWebhook(payload, signature);

        assertThat(event.eventType()).isEqualTo("video.encoding.failed");
    }

    @Test
    void verifyWebhook_validHmac_status8_returnsUploadFailed() throws Exception {
        String payload = "{\"VideoLibraryId\":123,\"VideoGuid\":\"abc-guid\",\"Status\":8}";
        String signature = computeHmac(WEBHOOK_SIGNING_SECRET, payload);

        WebhookEvent event = adapter.verifyWebhook(payload, signature);

        assertThat(event.eventType()).isEqualTo("video.upload.failed");
    }

    @Test
    void verifyWebhook_validHmac_informationalStatus_returnsStatusEvent() throws Exception {
        // Informational statuses (e.g., Status=1 = video queued) produce a generic event type
        String payload = "{\"VideoLibraryId\":123,\"VideoGuid\":\"abc-guid\",\"Status\":1}";
        String signature = computeHmac(WEBHOOK_SIGNING_SECRET, payload);

        WebhookEvent event = adapter.verifyWebhook(payload, signature);

        assertThat(event.eventType()).startsWith("video.status.");
    }

    @Test
    void verifyWebhook_invalidHmac_throwsVideoProviderException() {
        String payload = "{\"VideoLibraryId\":123,\"VideoGuid\":\"abc-guid\",\"Status\":3}";
        assertThatThrownBy(() -> adapter.verifyWebhook(payload, "0".repeat(64)))
            .isInstanceOf(VideoProviderException.class);
    }

    @Test
    void verifyWebhook_malformedSignatureHeader_throwsVideoProviderException() {
        String payload = "{\"VideoLibraryId\":123,\"VideoGuid\":\"abc-guid\",\"Status\":3}";
        assertThatThrownBy(() -> adapter.verifyWebhook(payload, "not-hex!!!"))
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
