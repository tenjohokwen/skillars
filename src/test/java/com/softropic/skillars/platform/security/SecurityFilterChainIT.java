package com.softropic.skillars.platform.security;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.security.SecurityConstants;

import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.catalina.valves.ValveBase;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.JWT_COOKIE_NAME;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.USER_COOKIE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SecurityFilterChainIT extends AbstractIntegrationTest {

    @Autowired
    private HttpTestClient httpTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private RestTemplate testRestTemplate;

    @LocalServerPort
    int randomServerPort;

    private static final String LOGIN_URL = "/api/auth/login";
    private static final String ACCOUNT_URL = "/v1/account/";

    private HttpHeaders baseHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");
        return headers;
    }

    @Test
    void testPublicEndpointAccessibleWithoutAuth() {
        // /api/auth/login is public: an empty body is rejected by the controller's bean validation
        // (400), not by the security chain (401/403).
        String url = baseUrl() + LOGIN_URL;

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(url, HttpMethod.POST, Map.of(), baseHeaders(), Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> {
                    HttpClientErrorException ex = (HttpClientErrorException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    @Sql(scripts = {"/sql/authorityData.sql", "/sql/userData.sql", "/sql/secData.sql"})
    void testSecuredEndpointRequiresAuth() {
        String url = baseUrl() + ACCOUNT_URL;

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(url, HttpMethod.GET, null, baseHeaders(), Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> {
                    HttpClientErrorException ex = (HttpClientErrorException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    // JWTAuthorizationFilter now emits an ErrorDto body (not a bare sendError) so the
                    // SPA axios interceptor can gate on errorMsg.errorKey. No token -> security.unauthorized.
                    assertThat(ex.getResponseBodyAsString())
                            .contains("\"errorKey\":\"security.unauthorized\"");
                });
    }

    @Test
    @Sql(scripts = {"/sql/authorityData.sql", "/sql/userData.sql", "/sql/secData.sql"})
    void testFullAuthFlowAndFilterInterdependencies() {
        // Ensure me@yahoo.com is ACTIVE and NOT locked. otp_enabled = true on purpose: the flag only
        // drove the removed /authenticate -> /otp flow and must not gate /api/auth/login.
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("update main.user set status = 'ACTIVE', activation_date = '2000-01-01 00:00:00', activated = true, locked = false, otp_enabled = true where login = 'me@yahoo.com'");
            return 0;
        });

        // 1. Authenticate (AuthResource -> AuthService.login -> LoginTokenManager.createLoginToken)
        Map<String, Object> credentials = Map.of("email", "me@yahoo.com", "password", "admin*123!");
        ResponseEntity<Map> authResponse = httpTestClient.makeHttpRequest(baseUrl() + LOGIN_URL, HttpMethod.POST, credentials, baseHeaders(), Map.class);
        assertThat(authResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> cookies = authResponse.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).anyMatch(c -> c.startsWith(JWT_COOKIE_NAME + "="));
        assertThat(cookies).anyMatch(c -> c.startsWith(USER_COOKIE + "="));
        assertThat(cookies).anyMatch(c -> c.startsWith(SecurityConstants.REFRESH_TOKEN_COOKIE + "="));

        // 2. Access Secured Endpoint (triggers JWTAuthorizationFilter)
        String cookieHeader = String.join("; ", cookies.stream().map(c -> c.split(";", 2)[0]).toList());
        HttpHeaders authHeaders = baseHeaders();
        authHeaders.add(HttpHeaders.COOKIE, cookieHeader);

        ResponseEntity<Map> accountResponse = httpTestClient.makeHttpRequest(baseUrl() + ACCOUNT_URL, HttpMethod.GET, null, authHeaders, Map.class);
        assertThat(accountResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accountResponse.getBody().get("login")).isEqualTo("me@yahoo.com");
    }

    /**
     * The legacy password entry points are gone: POST /authenticate (+ POST /otp) and Spring's
     * default form login (POST /login). Valid credentials posted to any of them must not yield a
     * session — no JWT cookie, no JSESSIONID-backed security context — so POST /api/auth/login
     * (and its LoginRateLimiter) is the only way to verify a password.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/authenticate", "/otp", "/login"})
    @Sql(scripts = {"/sql/authorityData.sql", "/sql/userData.sql", "/sql/secData.sql"})
    void removedLegacyLoginEndpoints_doNotAuthenticate(String path) {
        HttpHeaders jsonHeaders = baseHeaders();
        Map<String, Object> jsonBody = Map.of("id", "queb@yahoo.com", "email", "queb@yahoo.com",
                                              "password", "admin*123!", "otp", "123456", "loginInfoId", "x");
        assertNoSessionFrom(() -> testRestTemplate.exchange(baseUrl() + path, HttpMethod.POST,
                new HttpEntity<>(jsonBody, jsonHeaders), String.class));

        HttpHeaders formHeaders = new HttpHeaders();
        formHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        formHeaders.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", "queb@yahoo.com");
        form.add("password", "admin*123!");
        assertNoSessionFrom(() -> testRestTemplate.exchange(baseUrl() + path, HttpMethod.POST,
                new HttpEntity<>(form, formHeaders), String.class));
    }

    private void assertNoSessionFrom(Supplier<ResponseEntity<String>> call) {
        ResponseEntity<String> response;
        try {
            response = call.get();
        } catch (HttpStatusCodeException e) {
            assertThat(e.getStatusCode().is2xxSuccessful()).isFalse();
            assertThat(cookieNames(e.getResponseHeaders())).doesNotContain(JWT_COOKIE_NAME, "JSESSIONID");
            return;
        }
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("a removed login endpoint answered %s", response.getStatusCode())
                .isFalse();
        assertThat(cookieNames(response.getHeaders())).doesNotContain(JWT_COOKIE_NAME, "JSESSIONID");
    }

    /** Names of the cookies a response SETS to a value — a denial may clear (empty) the JWT cookie. */
    private static List<String> cookieNames(HttpHeaders headers) {
        List<String> setCookies = headers == null ? null : headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return List.of();
        }
        return setCookies.stream()
                .map(c -> c.split(";", 2)[0].split("=", 2))
                .filter(nv -> nv.length == 2 && !nv[1].isBlank())
                .map(nv -> nv[0])
                .toList();
    }

    // skillars-deferred-162 AC1.3/AC1.4: the RemoteIpValve Boot actually built from application.yaml is the
    // single forwarded-header mechanism. A copy of the live valve's configuration is driven with synthetic
    // Tomcat requests, so both a trusted-proxy peer and a direct (non-proxy) peer can be simulated; MockMvc
    // and a loopback HTTP call cannot set the TCP peer. The live valve itself is not mutated.

    @Autowired
    private ServletWebServerApplicationContext webServerContext;

    @Test
    void remoteIpValve_trustedProxyPeer_resolvesTheClientFromXForwardedFor() throws Exception {
        assertThat(resolveRemoteAddr("172.18.0.5", "203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void remoteIpValve_trustedProxyPeer_ignoresAClientSuppliedLeftmostEntry() throws Exception {
        // A proxy that appends (nginx's $proxy_add_x_forwarded_for) leaves the client's own value on the
        // left. The valve walks right-to-left and stops at the first untrusted address.
        assertThat(resolveRemoteAddr("127.0.0.1", "1.2.3.4, 203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void remoteIpValve_nonProxyPeer_ignoresForwardedHeaders() throws Exception {
        assertThat(resolveRemoteAddr("198.51.100.20", "1.2.3.4")).isEqualTo("198.51.100.20");
    }

    @Test
    void responses_carryFramingReferrerPermissionsAndHstsHeaders() {
        HttpHeaders headers = baseHeaders();
        headers.add("X-Forwarded-Proto", "https");

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(baseUrl() + ACCOUNT_URL, HttpMethod.GET, null, headers, Map.class))
                .isInstanceOfSatisfying(HttpClientErrorException.class, ex -> {
                    HttpHeaders response = ex.getResponseHeaders();
                    assertThat(response.getFirst("Content-Security-Policy")).isEqualTo("frame-ancestors 'self'");
                    assertThat(response.getFirst("X-Frame-Options")).isEqualToIgnoringCase("SAMEORIGIN");
                    assertThat(response.getFirst("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
                    assertThat(response.getFirst("Permissions-Policy")).contains("camera=()", "microphone=()", "geolocation=()");
                    assertThat(response.getFirst("Strict-Transport-Security"))
                            .isEqualTo("max-age=31536000 ; includeSubDomains");
                });
    }

    @Test
    void forwardedHeaderFilter_isNotRegistered() {
        assertThat(webServerContext.getBeansOfType(FilterRegistrationBean.class).values())
                .extracting(FilterRegistrationBean::getFilter)
                .noneMatch(ForwardedHeaderFilter.class::isInstance);
        assertThat(webServerContext.getBeansOfType(ForwardedHeaderFilter.class)).isEmpty();
    }

    private String resolveRemoteAddr(String peer, String xForwardedFor) throws Exception {
        RemoteIpValve live = Arrays.stream(((TomcatWebServer) webServerContext.getWebServer())
                        .getTomcat().getEngine().getPipeline().getValves())
                .filter(RemoteIpValve.class::isInstance)
                .map(RemoteIpValve.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no RemoteIpValve: forward-headers-strategy native is not active"));
        assertThat(live.getRemoteIpHeader()).isEqualToIgnoringCase("x-forwarded-for");
        assertThat(live.getProtocolHeader()).isEqualToIgnoringCase("x-forwarded-proto");

        RemoteIpValve valve = new RemoteIpValve();
        valve.setRemoteIpHeader(live.getRemoteIpHeader());
        valve.setProtocolHeader(live.getProtocolHeader());
        valve.setInternalProxies(live.getInternalProxies());
        valve.setTrustedProxies(live.getTrustedProxies());
        AtomicReference<String> seen = new AtomicReference<>();
        valve.setNext(new ValveBase() {
            @Override
            public void invoke(Request request, Response response) {
                seen.set(request.getRemoteAddr());
            }
        });

        Request request = new Request(new Connector());
        request.setCoyoteRequest(new org.apache.coyote.Request());
        request.setRemoteAddr(peer);
        request.setRemoteHost(peer);
        request.getCoyoteRequest().getMimeHeaders().addValue("X-Forwarded-For").setString(xForwardedFor);
        request.getCoyoteRequest().getMimeHeaders().addValue("Forwarded").setString("for=1.2.3.4");
        valve.invoke(request, null);
        return seen.get();
    }
}
