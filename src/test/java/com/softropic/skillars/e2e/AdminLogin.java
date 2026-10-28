package com.softropic.skillars.e2e;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test login helper: authenticates through the SPA's own entry point, {@code POST /api/auth/login}
 * (the only password login the application exposes), and returns a {@code Cookie} header carrying
 * every cookie the response set (JWT cookies, {@code rtkn}, {@code skp}).
 *
 * <p>The JWT embeds a fingerprint of the {@code fcookie} cookie and the {@code user-agent} header,
 * and {@code JWTAuthorizationFilter} re-checks both on every request — callers must send the same
 * {@link #TEST_USER_AGENT} and {@code fcookie=fingerprintCookie} on their follow-up requests.
 */
public abstract class AdminLogin {

    public static final String TEST_USER_AGENT = "Java/17.0.11";
    public static final String LOGIN_PATH      = "/api/auth/login";

    /** Admin fixture: login and password match userData.sql (queb@yahoo.com). */
    private static final String ADMIN_LOGIN    = "queb@yahoo.com";
    private static final String ADMIN_PASSWORD = "admin*123!";

    /** Logs in as the seeded admin (userData.sql). {@code baseUrl} is e.g. {@code http://localhost:<port>}. */
    public static HttpHeaders loginAsAdmin(String baseUrl, RestTemplate restTemplate) {
        return login(baseUrl, ADMIN_LOGIN, ADMIN_PASSWORD, restTemplate);
    }

    /** Logs in as any seeded user via {@code POST /api/auth/login} and returns its cookies as a Cookie header. */
    public static HttpHeaders login(String baseUrl, String email, String password, RestTemplate restTemplate) {
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");
        loginHeaders.add("user-agent", TEST_USER_AGENT);

        Map<String, String> credentials = Map.of("email", email, "password", password);
        ResponseEntity<Map> loginResponse = restTemplate.exchange(
                baseUrl + LOGIN_PATH,
                HttpMethod.POST,
                new HttpEntity<>(credentials, loginHeaders),
                Map.class);

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Extract Set-Cookie headers and forward them as Cookie header on subsequent requests
        List<String> setCookies = loginResponse.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull().isNotEmpty();

        String cookieHeader = String.join("; ",
                                          setCookies.stream()
                                                    .map(c -> c.split(";", 2)[0])  // keep only name=value, drop path/httponly etc.
                                                    .toList());

        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.set(HttpHeaders.COOKIE, cookieHeader);
        return authHeaders;
    }

}
