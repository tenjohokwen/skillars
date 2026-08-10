package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.SecurityIT;
import com.softropic.skillars.platform.security.contract.AdminBootstrapProperties;
import com.softropic.skillars.platform.security.repo.AuthorityRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * End-to-end proof that {@link AdminBootstrapRunner} produces an account that can actually be used
 * (story skillars-uat-1, AC2).
 *
 * <p>{@code AdminBootstrapRunnerTest} pins the fields the runner sets; it cannot prove those fields
 * are the RIGHT ones. That takes the real login path against a real database, because every gate
 * that would reject the account lives there: {@code activated}, {@code verification_status} versus
 * the phone-OTP toggle, the {@code user_authority} join, and the bcrypt hash length constraint. A
 * bootstrap that writes a row nobody can log into is exactly as useless as no bootstrap at all.
 *
 * <p><strong>The runner is constructed here rather than driven by properties.</strong> Adding
 * {@code @TestPropertySource(app.bootstrap.admin.*)} to this class would fork the Spring context
 * and trip {@code IntegrationTestConventionTest}'s pinned property-source count. Constructor
 * injection with the real repositories and the real {@code PasswordEncoder} exercises the identical
 * code path at no context cost — the container-managed bean is present too, and correctly no-ops
 * because the properties are blank under the test profile.
 */
// Seeds the JWT signing keys (sec_key/sec). Without them LoginTokenManager cannot mint a token and
// every login returns 401 security.generic — a SecException, not a credentials failure, which is
// easy to misread as "the bootstrapped account is wrong". Same annotation AuthResourceIT and
// AdminQueueIT carry for the same reason. @Sql does not contribute to MergedContextConfiguration,
// so this forks no Spring context and IntegrationTestConventionTest does not police it.
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class AdminBootstrapIT extends AbstractIntegrationTest {

    private static final String LOGIN_ENDPOINT = "/api/auth/login";
    private static final String ADMIN_QUEUE_URL = "/api/admin/queue";
    private static final String CLIENT_ID = "testClientId";

    private static final String CONFIGURED_EMAIL = "Bootstrap.Admin@Skillars.com";
    private static final String NORMALIZED_LOGIN = "bootstrap.admin@skillars.com";
    private static final String BOOTSTRAP_PASSWORD = "BootstrapPass@123!";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthorityRepository authorityRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private AdminBootstrapRunner runner;

    @BeforeEach
    void setUp() {
        AdminBootstrapProperties properties = new AdminBootstrapProperties();
        // Deliberately mixed case: this is the configuration shape that used to survive the first
        // boot and fail the second one.
        properties.setEmail(CONFIGURED_EMAIL);
        properties.setPassword(BOOTSTRAP_PASSWORD);
        properties.setPhone("+493090000001");
        runner = new AdminBootstrapRunner(properties, userRepository, authorityRepository,
            passwordEncoder, transactionTemplate);
    }

    @Test
    @DisplayName("a bootstrapped admin can log in and reach a HAS_ADMIN_ROLE endpoint")
    void bootstrappedAdminCanAuthenticateAndReachAdminSurface() {
        runner.run(null);

        Optional<User> created = userRepository.findOneByEmail(NORMALIZED_LOGIN);
        assertThat(created)
            .as("stored lower-cased — AuthService looks logins up as findOneByLogin(email.toLowerCase())")
            .isPresent();

        ResponseEntity<Map> login = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            // The operator types the address as configured; login must work regardless of casing.
            Map.of("email", CONFIGURED_EMAIL, "password", BOOTSTRAP_PASSWORD),
            clientHeaders(), Map.class);

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).isNotNull();
        assertThat(login.getBody().get("role"))
            .as("skillars_role must be ADMIN, not AuthService's null fallback")
            .isEqualTo("ADMIN");

        String cookies = extractCookies(login);
        ResponseEntity<Map> queue = httpTestClient.makeHttpRequest(
            baseUrl() + ADMIN_QUEUE_URL,
            HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);

        assertThat(queue.getStatusCode())
            .as("HAS_ADMIN_ROLE must be satisfied — a 403 here means the user_authority join "
                + "or the V92 authority seed is wrong, which is the whole point of the bootstrap")
            .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("running the bootstrap a second time is a no-op and leaves the account usable")
    void secondRunIsANoOp() {
        runner.run(null);
        long afterFirst = countBootstrapAdmins();

        assertThatCode(() -> runner.run(null))
            .as("an exception out of ApplicationRunner.run fails startup — a re-deploy with the "
                + "env vars still set must not brick the box")
            .doesNotThrowAnyException();

        assertThat(countBootstrapAdmins())
            .as("no duplicate row")
            .isEqualTo(afterFirst)
            .isEqualTo(1L);

        ResponseEntity<Map> login = httpTestClient.makeHttpRequest(
            baseUrl() + LOGIN_ENDPOINT,
            HttpMethod.POST,
            Map.of("email", CONFIGURED_EMAIL, "password", BOOTSTRAP_PASSWORD),
            clientHeaders(), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private long countBootstrapAdmins() {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.\"user\" WHERE email = ?", Long.class, NORMALIZED_LOGIN);
        return count == null ? 0L : count;
    }

    private String extractCookies(ResponseEntity<?> response) {
        List<String> setCookies = response.getHeaders().get("Set-Cookie");
        assertThat(setCookies).isNotNull();
        return setCookies.stream().map(c -> c.split(";")[0])
            .reduce((a, b) -> a + "; " + b).orElseThrow();
    }

    private HttpHeaders clientHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(SecurityConstants.API_KEY_HEADER, CLIENT_ID);
        return headers;
    }

    private HttpHeaders authenticatedHeaders(String cookieValue) {
        HttpHeaders headers = clientHeaders();
        headers.add(HttpHeaders.COOKIE, cookieValue);
        return headers;
    }
}
