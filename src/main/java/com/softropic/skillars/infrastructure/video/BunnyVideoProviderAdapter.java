package com.softropic.skillars.infrastructure.video;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.video.contract.exception.VideoProviderException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

@Slf4j
public class BunnyVideoProviderAdapter implements VideoProviderAdapter {

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String libraryId;
    private final String cdnHostname;
    private final String apiBaseUrl;
    private final ObjectMapper objectMapper;

    public BunnyVideoProviderAdapter(RestTemplate restTemplate,
                                     String apiKey,
                                     String libraryId,
                                     String cdnHostname,
                                     String apiBaseUrl,
                                     ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
        this.libraryId = libraryId;
        this.cdnHostname = cdnHostname;
        this.apiBaseUrl = apiBaseUrl;
        this.objectMapper = objectMapper;
    }

    @Override
    public UploadCredentials initializeUpload(String fileName, long fileSizeBytes) {
        HttpHeaders headers = buildHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(Map.of("title", fileName), headers);
        try {
            var response = restTemplate.postForEntity(
                apiBaseUrl + "/library/" + libraryId + "/videos",
                entity,
                BunnyCreateVideoResponse.class
            );
            String guid = response.getBody().guid();
            return new UploadCredentials(guid, apiBaseUrl + "/tusupload");
        } catch (RestClientException e) {
            throw new VideoProviderException("initializeUpload", e);
        }
    }

    @Override
    public AssetStatus getAssetStatus(String providerAssetId) {
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        try {
            var response = restTemplate.exchange(
                apiBaseUrl + "/library/" + libraryId + "/videos/" + providerAssetId,
                HttpMethod.GET,
                entity,
                BunnyVideoResponse.class
            );
            return mapBunnyStatus(response.getBody().status());
        } catch (HttpClientErrorException.NotFound e) {
            return AssetStatus.DELETED;
        } catch (RestClientException e) {
            throw new VideoProviderException("getAssetStatus", e);
        }
    }

    @Override
    public SignedPlaybackUrl generatePlaybackUrl(String providerAssetId, PlaybackTokenClaims claims) {
        String signaturePath = "/" + providerAssetId + "/playlist.m3u8";
        long expires = claims.expiresAt().getEpochSecond();
        String message = signaturePath + expires;

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] rawToken = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));

            String token = "HS256-" + Base64.getEncoder().encodeToString(rawToken)
                .replace("\n", "")
                .replace("+", "-")
                .replace("/", "_")
                .replace("=", "");

            String url = "https://" + cdnHostname + signaturePath + "?token=" + token + "&expires=" + expires;
            return new SignedPlaybackUrl(url, claims.expiresAt());
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new VideoProviderException("generatePlaybackUrl", e);
        }
    }

    @Override
    public void deleteAsset(String providerAssetId) {
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        try {
            restTemplate.exchange(
                apiBaseUrl + "/library/" + libraryId + "/videos/" + providerAssetId,
                HttpMethod.DELETE,
                entity,
                Void.class
            );
        } catch (RestClientException e) {
            throw new VideoProviderException("deleteAsset", e);
        }
    }

    @Override
    public WebhookEvent verifyWebhook(String payload, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));

            byte[] provided = HexFormat.of().parseHex(signature.toLowerCase());

            if (!MessageDigest.isEqual(expected, provided)) {
                throw new VideoProviderException("verifyWebhook: invalid signature", null);
            }

            BunnyWebhookPayload webhookPayload = objectMapper.readValue(payload, BunnyWebhookPayload.class);
            return new WebhookEvent(
                webhookPayload.eventType(),
                webhookPayload.videoGuid(),
                Instant.ofEpochSecond(webhookPayload.timestamp())
            );
        } catch (VideoProviderException e) {
            throw e;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new VideoProviderException("verifyWebhook", e);
        } catch (JsonProcessingException e) {
            throw new VideoProviderException("verifyWebhook: payload parse error", e);
        }
    }

    @Override
    public String getThumbnailUrl(String providerAssetId) {
        return "https://" + cdnHostname + "/" + providerAssetId + "/thumbnail.jpg";
    }

    @Override
    public void addCaptionTrack(String providerAssetId, String language, String captionFileUrl) {
        HttpHeaders headers = buildHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(
            Map.of("captionFileUrl", captionFileUrl), headers
        );
        try {
            restTemplate.postForEntity(
                apiBaseUrl + "/library/" + libraryId + "/videos/" + providerAssetId + "/captions/" + language,
                entity,
                Void.class
            );
        } catch (RestClientException e) {
            throw new VideoProviderException("addCaptionTrack", e);
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("AccessKey", apiKey);
        return headers;
    }

    private AssetStatus mapBunnyStatus(int bunnyStatus) {
        return switch (bunnyStatus) {
            case 0, 1 -> AssetStatus.UPLOADING;
            case 2, 3, 7 -> AssetStatus.PROCESSING;
            case 4, 8 -> AssetStatus.READY;
            case 5, 6 -> AssetStatus.FAILED;
            default -> AssetStatus.PROCESSING;
        };
    }

    private record BunnyCreateVideoResponse(String guid) {}

    private record BunnyVideoResponse(String guid, int status) {}

    private record BunnyWebhookPayload(
        @JsonProperty("EventType") String eventType,
        @JsonProperty("VideoGuid") String videoGuid,
        @JsonProperty("Timestamp") long timestamp
    ) {}
}
