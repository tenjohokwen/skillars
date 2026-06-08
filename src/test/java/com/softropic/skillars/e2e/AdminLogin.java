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

public abstract class AdminLogin {

    public static final String TEST_USER_AGENT = "Java/17.0.11";

    /** Admin fixture: login and password match userData.sql (queb@yahoo.com). */
    private static final String ADMIN_LOGIN    = "queb@yahoo.com";
    private static final String ADMIN_PASSWORD = "admin*123!";


    /**
     * Authenticates as the seeded admin user via POST /authenticate and
     * returns headers containing the JWT cookies from the response.
     *
     */
    public static HttpHeaders loginAsAdmin(String authenticateUrl, RestTemplate restTemplate) {
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");
        loginHeaders.add("user-agent", TEST_USER_AGENT);
        //loginHeaders.set("X-Client-Id", TEST_CLIENT_ID);

        Map<String, String> credentials = Map.of("id", ADMIN_LOGIN, "password", ADMIN_PASSWORD);
        ResponseEntity<Map> loginResponse = restTemplate.exchange(
                authenticateUrl,
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

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.set(HttpHeaders.COOKIE, cookieHeader);
        return adminHeaders;
    }

}
