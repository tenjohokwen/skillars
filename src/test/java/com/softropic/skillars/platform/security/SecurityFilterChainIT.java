package com.softropic.skillars.platform.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.service.LoginAttemptsService;
import com.softropic.skillars.utils.TestMailManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.Map;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.JWT_COOKIE_NAME;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.JWT_SESSION_COOKIE;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.LOGIN_INFO_ID;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.USER_COOKIE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"ledger.database.spy=true", "enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
public class SecurityFilterChainIT {

    @Autowired
    private HttpTestClient httpTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MailManager mailManager;

    @Autowired
    private LoginAttemptsService loginAttemptsService;

    @LocalServerPort
    int randomServerPort;

    @BeforeEach
    void setUp() {
        loginAttemptsService.resetLoginRecording();
    }

    private static final String AUTHENTICATE_URL = "/authenticate";
    private static final String ACCOUNT_URL = "/v1/account/";
    private static final String OTP_URL = "/otp";

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("delete from main.persistent_token");
            jdbcTemplate.execute("delete from main.user_addresses");
            jdbcTemplate.execute("delete from main.user_authority");
            jdbcTemplate.execute("delete from main.audit_log");
            jdbcTemplate.execute("delete from main.user");
            jdbcTemplate.execute("delete from main.authority");
            jdbcTemplate.execute("delete from main.sec");
            return 0;
        });
    }

    private String baseUrl() {
        return "http://localhost:" + randomServerPort;
    }

    private HttpHeaders baseHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");
        //headers.add(SecurityConstants.API_KEY_HEADER, "myClientId");
        return headers;
    }

    @Test
    void testPublicEndpointAccessibleWithoutAuth() {
        // /authenticate is public
        String url = baseUrl() + AUTHENTICATE_URL;
        
        // We don't provide credentials, but we shouldn't get a 403 Forbidden from Spring Security's filter chain
        // Instead we get a 401 Unauthorized because JWTAuthenticationFilter expects valid body or creds
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(url, HttpMethod.POST, Map.of(), baseHeaders(), Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> {
                    HttpClientErrorException ex = (HttpClientErrorException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
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
                });
    }

    @Test
    @Sql(scripts = {"/sql/authorityData.sql", "/sql/userData.sql", "/sql/secData.sql"})
    void testFullAuthFlowAndFilterInterdependencies() throws JsonProcessingException {
        // Ensure me@yahoo.com is ACTIVE and NOT locked
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("update main.user set status = 'ACTIVE', activation_date = '2000-01-01 00:00:00', activated = true, locked = false, otp_enabled = false where login = 'me@yahoo.com'");
            return 0;
        });

        // 1. Authenticate (triggers JWTAuthenticationFilter)
        String authUrl = baseUrl() + AUTHENTICATE_URL;
        Map<String, Object> credentials = Map.of("id", "me@yahoo.com", "password", "admin*123!");
        
        ResponseEntity<Map> authResponse = httpTestClient.makeHttpRequest(authUrl, HttpMethod.POST, credentials, baseHeaders(), Map.class);
        assertThat(authResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        // Verify filters added cookies
        List<String> cookies = authResponse.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).anyMatch(c -> c.contains(JWT_COOKIE_NAME));
        assertThat(cookies).anyMatch(c -> c.contains(JWT_SESSION_COOKIE));
        assertThat(cookies).anyMatch(c -> c.contains(USER_COOKIE));

        // 2. Access Secured Endpoint (triggers JWTAuthorizationFilter)
        String cookieHeader = String.join("; ", cookies.stream().map(c -> c.split(";", 2)[0]).toList());
        HttpHeaders authHeaders = baseHeaders();
        authHeaders.add(HttpHeaders.COOKIE, cookieHeader);
        
        ResponseEntity<Map> accountResponse = httpTestClient.makeHttpRequest(baseUrl() + ACCOUNT_URL, HttpMethod.GET, null, authHeaders, Map.class);
        assertThat(accountResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accountResponse.getBody().get("login")).isEqualTo("me@yahoo.com");
    }

    @Test
    @Sql(scripts = {"/sql/authorityData.sql", "/sql/userData.sql", "/sql/secData.sql"})
    void test2FAFilterBlocksAccessUntilVerified() throws JsonProcessingException {
        // Ensure me@yahoo.com is ACTIVE, NOT locked, and has OTP enabled
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("update main.user set status = 'ACTIVE', activation_date = '2000-01-01 00:00:00', activated = true, locked = false, otp_enabled = true where login = 'me@yahoo.com'");
            return 0;
        });

        String authUrl = baseUrl() + AUTHENTICATE_URL;
        Map<String, Object> credentials = Map.of("id", "me@yahoo.com", "password", "admin*123!");
        
        ResponseEntity<Map> authResponse = httpTestClient.makeHttpRequest(authUrl, HttpMethod.POST, credentials, baseHeaders(), Map.class);
        assertThat(authResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        Map body = authResponse.getBody();
        String helpCode = (String) body.get("helpCode");
        Map payload = (Map) body.get("payload");
        String loginInfoId = (String) payload.get("loginInfoId");
        
        // Access secured endpoint with 2FA pending
        List<String> cookies = authResponse.getHeaders().get(HttpHeaders.SET_COOKIE);
        String cookieHeader = String.join("; ", cookies.stream().map(c -> c.split(";", 2)[0]).toList());
        HttpHeaders partialAuthHeaders = baseHeaders();
        partialAuthHeaders.add(HttpHeaders.COOKIE, cookieHeader);
        
        // jwt cookie not in headers since this is the 1st part of the 2FA flow. So user cannot access endpoint.
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(baseUrl() + ACCOUNT_URL, HttpMethod.GET, null, partialAuthHeaders, Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNAUTHORIZED);

        // Verify OTP
        TestMailManager testMailManager = (TestMailManager) mailManager;
        await().until(() -> testMailManager.getEnvelope(helpCode) != null);
        
        final Envelope otpEnvelope = testMailManager.getEnvelope(helpCode);
        Object otpObj = otpEnvelope.data().get("otpCode");
        if (otpObj == null) otpObj = otpEnvelope.data().get("otp");
        String otp = otpObj.toString();
        
        Map<String, Object> otpBody = Map.of("otp", otp, "loginInfoId", loginInfoId);
        
        // We need BOTH the LOGIN_INFO_ID cookie AND the JWT_SESSION_COOKIE
        final String liiCookie = cookies.stream().filter(c -> c.contains(LOGIN_INFO_ID)).findFirst().orElseThrow();
        final String sessionCookie = cookies.stream().filter(c -> c.contains(JWT_SESSION_COOKIE)).findFirst().orElseThrow();
        
        HttpHeaders otpHeaders = baseHeaders();
        otpHeaders.add(HttpHeaders.COOKIE, liiCookie.split(";", 2)[0]);
        otpHeaders.add(HttpHeaders.COOKIE, sessionCookie.split(";", 2)[0]);

        ResponseEntity<Map> otpResponse = httpTestClient.makeHttpRequest(baseUrl() + OTP_URL, 
                HttpMethod.POST, 
                otpBody, 
                otpHeaders, 
                Map.class);
        
        assertThat(otpResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        // Final access check
        List<String> finalCookies = otpResponse.getHeaders().get(HttpHeaders.SET_COOKIE);
        String finalCookieHeader = String.join("; ", finalCookies.stream().map(c -> c.split(";", 2)[0]).toList());
        HttpHeaders finalHeaders = baseHeaders();
        finalHeaders.add(HttpHeaders.COOKIE, finalCookieHeader);
        
        ResponseEntity<Map> finalAccountResponse = httpTestClient.makeHttpRequest(baseUrl() + ACCOUNT_URL, HttpMethod.GET, null, finalHeaders, Map.class);
        assertThat(finalAccountResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
