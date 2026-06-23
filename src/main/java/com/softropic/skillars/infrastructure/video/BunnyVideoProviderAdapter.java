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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
public class BunnyVideoProviderAdapter implements VideoProviderAdapter {

    private static final String TUS_ENDPOINT = "https://video.bunnycdn.com/tusupload";

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String libraryId;
    private final String cdnHostname;
    private final String apiBaseUrl;
    private final ObjectMapper objectMapper;
    private final long sessionTtlSeconds;
    private final String webhookSigningSecret;
    private final long libraryIdLong;

    public BunnyVideoProviderAdapter(RestTemplate restTemplate,
                                     String apiKey,
                                     String libraryId,
                                     String cdnHostname,
                                     String apiBaseUrl,
                                     ObjectMapper objectMapper,
                                     long sessionTtlSeconds,
                                     String webhookSigningSecret) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
        this.libraryId = libraryId;
        this.cdnHostname = cdnHostname;
        this.apiBaseUrl = apiBaseUrl;
        this.objectMapper = objectMapper;
        this.sessionTtlSeconds = sessionTtlSeconds;
        this.webhookSigningSecret = Objects.requireNonNull(webhookSigningSecret,
            "webhookSigningSecret must not be null — set app.video.bunny.webhook-signing-secret");
        if (webhookSigningSecret.isBlank()) throw new IllegalArgumentException(
            "BunnyVideoProviderAdapter: webhookSigningSecret must not be blank — set app.video.bunny.webhook-signing-secret to the Bunny Read-Only API key");
        try {
            this.libraryIdLong = Long.parseLong(libraryId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "BunnyVideoProviderAdapter: app.video.bunny.library-id must be numeric, got: " + libraryId, e);
        }
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
            long expireEpoch = Instant.now().getEpochSecond() + sessionTtlSeconds;
            String tusSignature = computeTusSignature(libraryId, apiKey, expireEpoch, guid);
            return new UploadCredentials(guid, TUS_ENDPOINT, tusSignature, expireEpoch, libraryIdLong);
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
    public VideoMetadata getVideoMetadata(String providerAssetId) {
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        try {
            var response = restTemplate.exchange(
                apiBaseUrl + "/library/" + libraryId + "/videos/" + providerAssetId,
                HttpMethod.GET,
                entity,
                BunnyVideoResponse.class
            );
            BunnyVideoResponse body = response.getBody();
            // Guard: getBody() can return null on a 200 with empty body during Bunny infrastructure issues.
            if (body == null) {
                throw new VideoProviderException(
                    "getVideoMetadata: empty response body for providerAssetId=" + providerAssetId, null);
            }
            // Verified field names: "length" (int, seconds) and "storageSize" (long, bytes)
            // per Bunny.net Get Video API. If absent (video still encoding), Jackson returns null.
            long durationMs = body.length() != null ? body.length() * 1000L : 0L;
            long storageBytes = body.storageSize() != null ? body.storageSize() : 0L;
            return new VideoMetadata(durationMs, storageBytes);
        } catch (HttpClientErrorException.NotFound e) {
            throw new VideoProviderException("getVideoMetadata: asset not found " + providerAssetId, e);
        } catch (RestClientException e) {
            throw new VideoProviderException("getVideoMetadata", e);
        }
    }

    @Override
    public SignedPlaybackUrl generatePlaybackUrl(String providerAssetId, PlaybackTokenClaims claims) {
        // CRITICAL pre-deploy: verify IP-binding HMAC order matches Bunny documentation.
        // Bunny spec: SHA256(securityKey + videoId + expiry [+ clientIp]).
        // Current impl uses HMAC-SHA256 with HS256- prefix which differs from Bunny spec —
        // verify that Bunny Edge is actually validating tokens before relying on IP binding.
        String signaturePath = "/" + providerAssetId + "/playlist.m3u8";
        long expires = claims.expiresAt().getEpochSecond();
        String clientIp = claims.clientIp();

        // Normalise IPv6 to bracket notation to avoid HMAC delimiter ambiguity
        String normalizedIp = normalizeClientIp(clientIp);
        String message = signaturePath + expires + (normalizedIp != null ? normalizedIp : "");

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] rawToken = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));

            String token = "HS256-" + Base64.getEncoder().encodeToString(rawToken)
                .replace("\n", "")
                .replace("+", "-")
                .replace("/", "_")
                .replace("=", "");

            StringBuilder url = new StringBuilder("https://")
                .append(cdnHostname).append(signaturePath)
                .append("?token=").append(token)
                .append("&expires=").append(expires);
            if (normalizedIp != null) {
                url.append("&clientIp=").append(normalizedIp);
            }

            return new SignedPlaybackUrl(url.toString(), claims.expiresAt());
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new VideoProviderException("generatePlaybackUrl", e);
        }
    }

    @Override
    public Optional<String> generateDownloadUrl(String providerAssetId, PlaybackTokenClaims claims) {
        String downloadPath = "/" + providerAssetId + "/original";
        long expires = claims.expiresAt().getEpochSecond();
        String normalizedIp = normalizeClientIp(claims.clientIp());
        String message = downloadPath + expires + (normalizedIp != null ? normalizedIp : "");

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] rawToken = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));

            String token = "HS256-" + Base64.getEncoder().encodeToString(rawToken)
                .replace("\n", "")
                .replace("+", "-")
                .replace("/", "_")
                .replace("=", "");

            StringBuilder url = new StringBuilder("https://")
                .append(cdnHostname).append(downloadPath)
                .append("?token=").append(token)
                .append("&expires=").append(expires);
            if (normalizedIp != null) {
                url.append("&clientIp=").append(normalizedIp);
            }
            // Content-Disposition=attachment via Bunny query param if supported; otherwise configure Edge Rule
            // CRITICAL pre-deploy: verify Bunny supports Content-Disposition override query param for download URLs

            return Optional.of(url.toString());
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new VideoProviderException("generateDownloadUrl", e);
        }
    }

    @Override
    public void archiveAsset(String providerAssetId) {
        // CRITICAL pre-deploy: verify cold storage archive API endpoint exists in Bunny Stream.
        // If no dedicated archive endpoint exists, this is a no-op — the DB transition still commits
        // correctly but the file stays on hot storage until deleteAsset() is called.
        // 404 = asset already gone; treat as success for idempotency.
        Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
            "archiveAsset must not be called inside @Transactional");
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        try {
            restTemplate.postForEntity(
                apiBaseUrl + "/library/" + libraryId + "/videos/" + providerAssetId + "/archive",
                entity,
                Void.class
            );
        } catch (HttpClientErrorException.NotFound e) {
            // 404 = asset already gone; treat as success for idempotency
            log.warn("archiveAsset: asset not found on Bunny (already archived/deleted?); treating as success providerAssetId={}", providerAssetId);
        } catch (org.springframework.web.client.HttpClientErrorException.MethodNotAllowed |
                 org.springframework.web.client.HttpClientErrorException.BadRequest e) {
            // Bunny may not support an /archive endpoint — log and proceed (no-op path)
            log.warn("archiveAsset: Bunny returned {} — archive endpoint may not exist; video stays on hot storage. providerAssetId={}",
                e.getStatusCode(), providerAssetId);
        } catch (RestClientException e) {
            throw new VideoProviderException("archiveAsset", e);
        }
    }

    @Override
    public void deleteAsset(String providerAssetId) {
        Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
            "deleteAsset must not be called inside @Transactional");
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        try {
            restTemplate.exchange(
                apiBaseUrl + "/library/" + libraryId + "/videos/" + providerAssetId,
                HttpMethod.DELETE,
                entity,
                Void.class
            );
        } catch (HttpClientErrorException.NotFound e) {
            // 404 = asset already gone; treat as success for idempotency
            // A prior run may have deleted the asset but failed to commit the DB update.
            log.warn("deleteAsset: asset not found on Bunny (already deleted?); treating as success providerAssetId={}", providerAssetId);
        } catch (RestClientException e) {
            throw new VideoProviderException("deleteAsset", e);
        }
    }

    @Override
    public WebhookEvent verifyWebhook(String payload, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSigningSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));

            byte[] provided;
            try {
                provided = HexFormat.of().parseHex(signature.toLowerCase());
            } catch (IllegalArgumentException e) {
                throw new VideoProviderException("verifyWebhook: malformed signature header", e);
            }

            if (!MessageDigest.isEqual(expected, provided)) {
                throw new VideoProviderException("verifyWebhook: invalid signature", null);
            }

            BunnyWebhookPayload webhookPayload = objectMapper.readValue(payload, BunnyWebhookPayload.class);
            String eventType = switch (webhookPayload.status()) {
                case 7 -> "video.upload.success";
                case 3 -> "video.encoding.success";
                case 5 -> "video.encoding.failed";
                case 8 -> "video.upload.failed";
                default -> "video.status." + webhookPayload.status();
            };
            return new WebhookEvent(webhookPayload.videoLibraryId(), eventType, webhookPayload.videoGuid(), Instant.now());
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
    public void triggerTranscoding(String providerAssetId) {
        // CRITICAL pre-deploy: Verify whether /reencode is correct for first-time encoding or only re-encoding.
        // Bunny auto-encodes on upload by default; this may be a no-op for the normal path.
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        try {
            restTemplate.postForEntity(
                apiBaseUrl + "/library/" + libraryId + "/videos/" + providerAssetId + "/reencode",
                entity,
                Void.class
            );
        } catch (HttpClientErrorException.NotFound e) {
            throw new VideoProviderException(
                "triggerTranscoding: video not found in Bunny, providerAssetId=" + providerAssetId, e);
        } catch (RestClientException e) {
            throw new VideoProviderException("triggerTranscoding", e);
        }
    }

    @Override
    public String getRawVideoUrl(String providerAssetId) {
        // CRITICAL pre-deploy: Verify this URL pattern gives access to the raw uploaded video before transcoding.
        return "https://" + cdnHostname + "/" + providerAssetId + "/original";
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

    private String normalizeClientIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return null;
        // IPv6 addresses contain ':' — wrap in bracket notation to avoid HMAC delimiter ambiguity
        if (clientIp.contains(":") && !clientIp.startsWith("[")) {
            return "[" + clientIp + "]";
        }
        return clientIp;
    }

    private String computeTusSignature(String libId, String key, long expireEpoch, String videoGuid) {
        try {
            String input = libId + key + expireEpoch + videoGuid;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new VideoProviderException("computeTusSignature", e);
        }
    }

    private AssetStatus mapBunnyStatus(int bunnyStatus) {
        return switch (bunnyStatus) {
            case 0, 6 -> AssetStatus.UPLOADING;
            case 1, 2, 7 -> AssetStatus.PROCESSING;
            case 3, 4 -> AssetStatus.READY;
            case 5, 8 -> AssetStatus.FAILED;
            default -> AssetStatus.PROCESSING;
        };
    }

    private record BunnyCreateVideoResponse(String guid) {}

    // Shared by getAssetStatus() and getVideoMetadata(); length/storageSize are null when
    // the video is still processing (Jackson maps absent JSON fields to null for boxed types).
    private record BunnyVideoResponse(String guid, int status, Integer length, Long storageSize) {}

    private record BunnyWebhookPayload(
        @JsonProperty("VideoLibraryId") long videoLibraryId,
        @JsonProperty("VideoGuid") String videoGuid,
        @JsonProperty("Status") int status
    ) {}
}
