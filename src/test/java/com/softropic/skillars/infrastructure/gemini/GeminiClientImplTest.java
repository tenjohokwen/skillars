package com.softropic.skillars.infrastructure.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Both transports share one moderation exchange; only URL and credential differ. Hermetic: a
 * {@link MockRestServiceServer} stands in for Google (nothing here ran against the real Vertex AI).
 */
@DisplayName("Gemini client: developer and Vertex transports")
class GeminiClientImplTest {

    private static final String SAFE_BODY = """
        {"candidates":[{"content":{"parts":[{"text":"SAFE"}]}}]}""";
    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private KeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
    }

    @Test
    @DisplayName("developer mode: v1beta URL with x-goog-api-key, unchanged prompt body")
    void developerMode() {
        var endpoint = new DeveloperGeminiEndpoint("https://generativelanguage.googleapis.com", "k-123");
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-x:generateContent"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("x-goog-api-key", "k-123"))
            .andExpect(jsonPath("$.contents[0].parts[0].text").value("hello"))
            .andExpect(jsonPath("$.generationConfig.maxOutputTokens").value(10))
            .andRespond(withSuccess(SAFE_BODY, MediaType.APPLICATION_JSON));

        assertThat(new GeminiClientImpl(restTemplate, endpoint, "gemini-x").evaluate("hello"))
            .isEqualTo(ModerationVerdict.SAFE);
        server.verify();
    }

    @Test
    @DisplayName("vertex mode: regional publisher URL, bearer token from a signed JWT-bearer exchange")
    void vertexMode() throws Exception {
        var tokens = new GoogleAccessTokenProvider(restTemplate, credentials(), fixedClock());
        var endpoint = new VertexGeminiEndpoint("https://europe-west4-aiplatform.googleapis.com", "proj-1",
            "europe-west4", tokens);

        server.expect(requestTo(TOKEN_URI))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(request -> {
                String form = request.getBody().toString();
                assertThat(form).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer");
                String assertion = form.substring(form.indexOf("assertion=") + "assertion=".length());
                var claims = Jwts.parser().verifyWith(keyPair.getPublic()).clock(() -> java.util.Date.from(FIXED))
                    .build().parseSignedClaims(assertion);
                assertThat(claims.getPayload().getIssuer()).isEqualTo("sa@proj-1.iam.gserviceaccount.com");
                assertThat(claims.getPayload().getAudience()).containsExactly(TOKEN_URI);
                assertThat(claims.getPayload().get("scope", String.class)).isEqualTo(GoogleAccessTokenProvider.SCOPE);
                assertThat(claims.getHeader().getKeyId()).isEqualTo("kid-1");
            })
            .andRespond(withSuccess("{\"access_token\":\"ya29.tok\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://europe-west4-aiplatform.googleapis.com/v1/projects/proj-1/locations/"
                + "europe-west4/publishers/google/models/gemini-x:generateContent"))
            .andExpect(header("Authorization", "Bearer ya29.tok"))
            .andExpect(jsonPath("$.contents[0].role").value("user"))
            .andExpect(jsonPath("$.contents[0].parts[0].text").value("hello"))
            .andRespond(withSuccess("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" unsafe \"}]}}]}",
                MediaType.APPLICATION_JSON));

        assertThat(new GeminiClientImpl(restTemplate, endpoint, "gemini-x").evaluate("hello"))
            .isEqualTo(ModerationVerdict.UNSAFE);
        server.verify();
    }

    @Test
    @DisplayName("vertex mode: the access token is cached across calls")
    void tokenCached() throws Exception {
        var tokens = new GoogleAccessTokenProvider(restTemplate, credentials(), fixedClock());
        server.expect(requestTo(TOKEN_URI))
            .andRespond(withSuccess("{\"access_token\":\"t1\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));

        assertThat(tokens.accessToken()).isEqualTo("t1");
        assertThat(tokens.accessToken()).isEqualTo("t1");
        server.verify();
    }

    @Test
    @DisplayName("vertex mode: a failing token exchange surfaces as GeminiException")
    void tokenFailure() throws Exception {
        var tokens = new GoogleAccessTokenProvider(restTemplate, credentials(), fixedClock());
        server.expect(requestTo(TOKEN_URI)).andRespond(withServerError());

        assertThatThrownBy(tokens::accessToken).isInstanceOf(GeminiException.class);
    }

    @Test
    @DisplayName("an incomplete credentials file is rejected up front")
    void incompleteCredentials() {
        JsonNode json = new ObjectMapper().createObjectNode().put("client_email", "a@b");
        assertThatThrownBy(() -> new GoogleAccessTokenProvider(restTemplate, json, fixedClock()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("token_uri");
    }

    @Test
    @DisplayName("an empty response is a GeminiException in either mode")
    void emptyResponse() {
        var endpoint = new DeveloperGeminiEndpoint("https://g", "k");
        server.expect(requestTo("https://g/v1beta/models/m:generateContent"))
            .andRespond(withSuccess("{\"candidates\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> new GeminiClientImpl(restTemplate, endpoint, "m").evaluate("x"))
            .isInstanceOf(GeminiException.class);
    }

    @Test
    @DisplayName("config wiring: developer needs a key, vertex needs project, location and a readable key file")
    void configValidation() {
        var props = new GeminiProperties();
        assertThatThrownBy(() -> GeminiConfig.endpoint(props, restTemplate))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("GEMINI_API_KEY");
        props.setApiKey("k");
        assertThat(GeminiConfig.endpoint(props, restTemplate).mode()).isEqualTo(GeminiMode.DEVELOPER);

        props.setMode(GeminiMode.VERTEX);
        assertThatThrownBy(() -> GeminiConfig.endpoint(props, restTemplate))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("GEMINI_VERTEX_PROJECT");
        props.getVertex().setProjectId("p");
        props.getVertex().setCredentialsPath("/nonexistent/sa.json");
        assertThatThrownBy(() -> GeminiConfig.endpoint(props, restTemplate))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("credentials-path");
        assertThat(props.getVertex().getLocation()).isEqualTo("europe-west4");
    }

    private static final Instant FIXED = Instant.parse("2026-01-01T00:00:00Z");

    private static Clock fixedClock() {
        return Clock.fixed(FIXED, ZoneOffset.UTC);
    }

    private JsonNode credentials() {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
        var node = new ObjectMapper().createObjectNode();
        node.put("client_email", "sa@proj-1.iam.gserviceaccount.com");
        node.put("private_key", pem);
        node.put("private_key_id", "kid-1");
        node.put("token_uri", TOKEN_URI);
        return node;
    }
}
