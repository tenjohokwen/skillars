package com.softropic.skillars.infrastructure.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import io.jsonwebtoken.Jwts;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * OAuth2 access tokens for a Google service account, using the JWT-bearer grant (RFC 7523) directly so
 * no Google auth library has to be added: sign a short-lived RS256 assertion with the key from the
 * service-account JSON file and exchange it at the account's {@code token_uri}. Tokens are cached until
 * shortly before expiry. Application Default Credentials / the metadata server are deliberately not
 * supported; the key file is the single, explicit credential.
 */
public class GoogleAccessTokenProvider {

    static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(2);
    private static final Duration ASSERTION_LIFETIME = Duration.ofMinutes(55);

    private final RestTemplate restTemplate;
    private final String clientEmail;
    private final String keyId;
    private final PrivateKey privateKey;
    private final String tokenUri;
    private final Clock clock;

    private String cachedToken;
    private Instant cachedUntil = Instant.MIN;

    public GoogleAccessTokenProvider(RestTemplate restTemplate, JsonNode serviceAccountJson, Clock clock) {
        this.restTemplate = restTemplate;
        this.clientEmail = required(serviceAccountJson, "client_email");
        this.tokenUri = required(serviceAccountJson, "token_uri");
        this.keyId = serviceAccountJson.path("private_key_id").asText(null);
        this.privateKey = parsePrivateKey(required(serviceAccountJson, "private_key"));
        this.clock = clock;
    }

    public synchronized String accessToken() {
        Instant now = clock.instant();
        if (cachedToken != null && now.isBefore(cachedUntil.minus(REFRESH_MARGIN))) {
            return cachedToken;
        }
        var builder = Jwts.builder()
            .issuer(clientEmail)
            .subject(clientEmail)
            .audience().add(tokenUri).and()
            .claim("scope", SCOPE)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(ASSERTION_LIFETIME)));
        if (keyId != null) {
            builder.header().keyId(keyId);
        }
        String assertion = builder.signWith(privateKey, Jwts.SIG.RS256).compact();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.add("assertion", assertion);
        try {
            JsonNode response = restTemplate.postForObject(tokenUri, new HttpEntity<>(form, headers), JsonNode.class);
            String token = response == null ? null : response.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                throw new GeminiException("Google token endpoint returned no access_token", null);
            }
            long expiresIn = response.path("expires_in").asLong(3600);
            cachedToken = token;
            cachedUntil = now.plusSeconds(expiresIn);
            return token;
        } catch (RestClientException e) {
            // The message is the status/transport error, never the assertion or the key.
            throw new GeminiException("Google token exchange failed: " + e.getMessage(), e);
        }
    }

    private static String required(JsonNode json, String field) {
        String value = json.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Service-account credentials file is missing '" + field + "'");
        }
        return value;
    }

    private static PrivateKey parsePrivateKey(String pem) {
        try {
            String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (Exception e) {
            throw new IllegalStateException("Service-account private_key is not a valid PKCS#8 RSA key", e);
        }
    }
}
