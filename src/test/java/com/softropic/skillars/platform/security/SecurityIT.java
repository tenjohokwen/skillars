package com.softropic.skillars.platform.security;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.security.contract.Gender;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.config.CommonConfig;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.contract.LoginIdType;
import com.softropic.skillars.platform.security.contract.UserDto;
import com.softropic.skillars.utils.TestMailManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.JWT_COOKIE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Sql({SecurityIT.SEC_DATA_SQL_PATH})
public class SecurityIT extends AbstractIntegrationTest {
    public static final String USER_DATA_SQL_PATH = "/sql/userData.sql";
    public static final String SEC_DATA_SQL_PATH = "/sql/secData.sql";
    public static final String AUTHORITY_DATA_SQL_PATH = "/sql/authorityData.sql";
    public static final String LOGIN_ENDPOINT = "/api/auth/login";
    public static final String HELP_CODE = "helpCode";
    public static final String PASSWORD = "password";
    public static final String EMAIL = "email";
    public static final String CLIENT_ID = "myClientId";

    @Autowired
    private TransactionTemplate template;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MailManager mailManager;

    @Autowired
    private HttpTestClient httpTestClient;

    @LocalServerPort
    int randomServerPort;

    @AfterEach
    void tearDown() {
        template.execute(status -> {
            jdbcTemplate.execute("delete from main.sec");
            jdbcTemplate.execute("delete from main.refresh_tokens");
            jdbcTemplate.execute("delete from main.user_addresses");
            jdbcTemplate.execute("delete from main.user_authority");
            jdbcTemplate.execute("delete from main.authority");
            jdbcTemplate.execute("delete from main.user");
            jdbcTemplate.execute("delete from main.audit_log");
            jdbcTemplate.execute("delete from main.login_attempts");
            return 0;
        });
    }

    /**
     * bad.credentials test
     */
    @Sql({AUTHORITY_DATA_SQL_PATH, USER_DATA_SQL_PATH, SEC_DATA_SQL_PATH})
    @ParameterizedTest
    @EnumSource(Credentials.class)
    void loginWithWrongCredentials(Credentials credentials) {
        final String uri = baseUrl() + LOGIN_ENDPOINT;

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(uri,
                HttpMethod.POST,
                credentials.getBody(),
                baseHttpHeaders(),
                Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .hasFieldOrPropertyWithValue("statusCode", credentials.expectedStatus);
    }

    enum Credentials {
        // Not an email at all: rejected by LoginRequest's bean validation before any lookup.
        INVALID_EMAIL(Map.of(EMAIL, "Mike", PASSWORD, "Thomson1"), HttpStatus.BAD_REQUEST),
        ARBITRARY_EMAIL(Map.of(EMAIL, "walters@yahoo.com", PASSWORD, "Thomson1"), HttpStatus.UNAUTHORIZED),
        INVALID_PASSWORD(Map.of(EMAIL, "me@yahoo.com", PASSWORD, PASSWORD), HttpStatus.UNAUTHORIZED);
        private final Map<String, Object> body;
        private final HttpStatus expectedStatus;

        Credentials(Map<String, Object> body, HttpStatus expectedStatus) {
            this.body = body;
            this.expectedStatus = expectedStatus;
        }

        public Map<String, Object> getBody() {
            return this.body;
        }
    }

    @Test
    void attemptLoginWithInvalidOriginPolicy() {
        final Map<String, Object> body = Map.of(EMAIL, "mike@yahoo.com", PASSWORD, "Thomson1");
        final String uri = baseUrl() + LOGIN_ENDPOINT;

        final HttpHeaders httpHeaders = baseHttpHeaders();
        httpHeaders.add(HttpHeaders.ORIGIN, "https:localhost:" + randomServerPort);

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(uri,
                HttpMethod.POST,
                body,
                httpHeaders,
                String.class))
                .isInstanceOf(HttpClientErrorException.class)
                .hasMessageContaining("Invalid CORS request");
    }

    private HttpHeaders baseHttpHeaders() {
        final HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON.toString());
        httpHeaders.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON.toString());
        httpHeaders.add(SecurityConstants.API_KEY_HEADER, CLIENT_ID);
        return httpHeaders;
    }

    /**
     * DisabledException test
     */
    @Test
    @Sql({AUTHORITY_DATA_SQL_PATH, USER_DATA_SQL_PATH, SEC_DATA_SQL_PATH})
    void attemptLoginToDisabledAccount() {
        // Use not-activated@yahoo.com which has activated=false in userData.sql
        final String uri = baseUrl() + LOGIN_ENDPOINT;

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(uri,
                HttpMethod.POST,
                Map.of(EMAIL, "not-activated@yahoo.com", PASSWORD, "admin*123!"),
                baseHttpHeaders(),
                Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> {
                    HttpClientErrorException ex = (HttpClientErrorException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getResponseBodyAsString()).contains("security.accNotEnabled");
                });
    }

    /**
     * Register, activate via the emailed key, then log in through POST /api/auth/login. The account
     * is registered with otpEnabled=true: that flag drove the removed POST /authenticate -> /otp
     * email-2FA flow and must not block (or alter) the one remaining login path.
     */
    @Test
    @Sql({AUTHORITY_DATA_SQL_PATH, USER_DATA_SQL_PATH, SEC_DATA_SQL_PATH})
    void registeredAndActivatedAccount_logsInDirectly() throws JsonProcessingException {
        final String registrationUri = baseUrl() + "/v1/account/register";
        final UserDto userData = getUserData();

        final ObjectMapper defaultObjectMapper = getDefaultObjectMapper();
        final String userDataAsString = defaultObjectMapper.writeValueAsString(userData);
        final Map<String, Object> userDataMap = defaultObjectMapper.readValue(userDataAsString, Map.class);

        final ResponseEntity<Map> registerResponse = httpTestClient.makeHttpRequest(registrationUri,
                                                                                    HttpMethod.POST,
                                                                                    userDataMap,
                                                                                    baseHttpHeaders(),
                                                                                    Map.class);
        final String registerHelpCode = (String) registerResponse.getBody().get(HELP_CODE);

        final TestMailManager testMailManager = (TestMailManager) mailManager;
        await().until(() -> testMailManager.getEnvelope(registerHelpCode) != null);
        final String activationKey = (String) testMailManager.getEnvelope(registerHelpCode).data().get("activationKey");
        assertThat(activationKey).isNotNull();

        final ResponseEntity<Map> activationResponse = httpTestClient.makeHttpRequest(
                baseUrl() + "/v1/account/activate?key=" + activationKey,
                HttpMethod.POST,
                Map.of(),
                baseHttpHeaders(),
                Map.class);
        assertThat(activationResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        final ResponseEntity<Map> loginResponse = httpTestClient.makeHttpRequest(baseUrl() + LOGIN_ENDPOINT,
                HttpMethod.POST,
                Map.of(EMAIL, userData.getLogin(), PASSWORD, userData.getPassword()),
                baseHttpHeaders(),
                Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getHeaders().get(HttpHeaders.SET_COOKIE))
                .anyMatch(c -> c.startsWith(JWT_COOKIE_NAME + "="))
                .anyMatch(c -> c.startsWith(SecurityConstants.SKILLARS_PROFILE_COOKIE + "="));
    }

    @Test
    @Sql({AUTHORITY_DATA_SQL_PATH, USER_DATA_SQL_PATH, SEC_DATA_SQL_PATH})
    void loginThenAccessProtectedResource() {
        final ResponseEntity<Map> responseEntity = httpTestClient.makeHttpRequest(baseUrl() + LOGIN_ENDPOINT,
                                                                                  HttpMethod.POST,
                                                                                  Map.of(EMAIL,
                                                                                         "queb@yahoo.com",
                                                                                         PASSWORD,
                                                                                         "admin*123!"),
                                                                                  baseHttpHeaders(),
                                                                                  Map.class);
        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Access protected resource
        final List<String> cookies = responseEntity.getHeaders().get(HttpHeaders.SET_COOKIE);
        String cookieHeaderValue = String.join("; ", cookies.stream().map(c -> c.split(";", 2)[0]).toList());

        final HttpHeaders authHeaders = baseHttpHeaders();
        authHeaders.add(HttpHeaders.COOKIE, cookieHeaderValue);

        ResponseEntity<Map> accountResponse = httpTestClient.makeHttpRequest(baseUrl() + "/v1/account/", HttpMethod.GET, null, authHeaders, Map.class);

        assertThat(accountResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accountResponse.getBody().get("login")).isEqualTo("queb@yahoo.com");
    }

    @Test
    @Sql({SecurityIT.SEC_DATA_SQL_PATH})
    void testAccessProtectedResource_NoToken_Unauthorized() {
        final String accountUri = baseUrl() + "/v1/account/";
        final HttpHeaders httpHeaders = baseHttpHeaders();

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(accountUri, HttpMethod.GET, null, httpHeaders, Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Sql({AUTHORITY_DATA_SQL_PATH, USER_DATA_SQL_PATH, SEC_DATA_SQL_PATH})
    void attemptLoginWhenAccountBlocked() {
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(baseUrl() + LOGIN_ENDPOINT,
                HttpMethod.POST,
                Map.of(EMAIL, "locked@yahoo.com", PASSWORD, "admin*123!"),
                baseHttpHeaders(),
                Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> {
                    HttpClientErrorException ex = (HttpClientErrorException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getResponseBodyAsString()).contains("security.accLocked");
                });
    }

    private static UserDto getUserData() {
        final UserDto userDto = new UserDto();
        userDto.setEmail("figu@yahoo.com");
        userDto.setLogin("figu@yahoo.com");
        userDto.setLoginIdType(LoginIdType.EMAIL);
        userDto.setPhone("657123456");
        userDto.setActivated(false);
        userDto.setLangKey("en");
        userDto.setGender(Gender.MALE);
        userDto.setDob(LocalDate.of(1990, 2, 20));
        userDto.setPassword("admin*123!");
        userDto.setOtpEnabled(true);
        userDto.setFirstName("Test");
        userDto.setLastName("User");
        return userDto;
    }

    private static ObjectMapper getDefaultObjectMapper() {
        CommonConfig contextConfig = new CommonConfig();
        return contextConfig.objectMapperBuilder().build();
    }
}
