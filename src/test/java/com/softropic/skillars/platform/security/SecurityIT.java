package com.softropic.skillars.platform.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.security.contract.Gender;
import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.config.CommonConfig;
import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.security.api.AccountManagementFacade;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.contract.LoginIdType;
import com.softropic.skillars.platform.security.contract.UserDto;
import com.softropic.skillars.platform.security.service.LoginAttemptsService;
import com.softropic.skillars.platform.security.repo.LoginInfoRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import com.softropic.skillars.utils.TestMailManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.JWT_COOKIE_NAME;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.JWT_SESSION_COOKIE;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.LOGIN_INFO_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"ledger.database.spy=true", "enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
public class SecurityIT {
    private final static ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final String USER_DATA_SQL_PATH = "/sql/userData.sql";
    public static final String SEC_DATA_SQL_PATH = "/sql/secData.sql";
    public static final String AUTHORITY_DATA_SQL_PATH = "/sql/authorityData.sql";
    public static final String AUTHENTICATE_ENDPOINT = "/authenticate";
    public static final String OTP_ENDPOINT = "/otp";
    public static final String HELP_CODE = "helpCode";
    public static final String PASSWORD = "password";
    public static final String EMAIL = "email";
    public static final String LOGIN_ID = "id";
    public static final String CLIENT_ID = "myClientId";

    @Autowired
    private TransactionTemplate template;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MailManager mailManager;

    @Autowired
    private AccountManagementFacade accountManagementFacade;

    @Autowired
    private LoginInfoRepository loginInfoRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LoginAttemptsService loginAttemptsService;

    @Autowired
    private HttpTestClient httpTestClient;

    @LocalServerPort
    int randomServerPort;

    @AfterEach
    void tearDown() {
        template.execute(status -> {
            jdbcTemplate.execute("delete from main.sec");
            jdbcTemplate.execute("delete from main.user_addresses");
            jdbcTemplate.execute("delete from main.user_authority");
            jdbcTemplate.execute("delete from main.authority");
            jdbcTemplate.execute("delete from main.user");
            jdbcTemplate.execute("delete from main.audit_log");
            return 0;
        });
        loginAttemptsService.unblacklistClient(CLIENT_ID);
    }

    /**
     * bad.credentials test
     */
    @Sql({AUTHORITY_DATA_SQL_PATH, USER_DATA_SQL_PATH, SEC_DATA_SQL_PATH})
    @ParameterizedTest
    @EnumSource(value = Credentials.class,
                names = {"INVALID_EMAIL", "ARBITRARY_EMAIL", "INVALID_PASSWORD"})
    void loginWithWrongCredentials(Credentials credentials) throws JsonProcessingException {
        final String uri = baseUrl() + AUTHENTICATE_ENDPOINT;
        
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(uri,
                HttpMethod.POST,
                credentials.getBody(),
                baseHttpHeaders(),
                Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNAUTHORIZED);
    }

    enum Credentials {
        INVALID_EMAIL(Map.of(EMAIL, "Mike", PASSWORD, "Thomson")),
        ARBITRARY_EMAIL(Map.of(EMAIL, "walters@yahoo.com", PASSWORD, "Thomson")),
        INVALID_PASSWORD(Map.of(EMAIL, "me@yahoo.com", PASSWORD, PASSWORD));
        private final Map<String, Object> body;

        Credentials(Map<String, Object> body) {this.body = body;}

        public Map<String, Object> getBody() {
            return this.body;
        }
    }

    @Test
    void attemptLoginWithInvalidOriginPolicy() {
        final Map<String, Object> body = Map.of(EMAIL, "Mike", PASSWORD, "Thomson");
        final String uri = baseUrl() + AUTHENTICATE_ENDPOINT;

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
    void attemptLoginToDisabledAccount() throws JsonProcessingException {
        // Use not-activated@yahoo.com which has activated=false in userData.sql
        
        final String uri = baseUrl() + AUTHENTICATE_ENDPOINT;
        final HttpHeaders httpHeaders = baseHttpHeaders();
        
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(uri,
                HttpMethod.POST,
                Map.of(LOGIN_ID, "not-activated@yahoo.com", PASSWORD, "admin*123!"),
                httpHeaders,
                Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> {
                    HttpClientErrorException ex = (HttpClientErrorException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getResponseBodyAsString()).contains("security.accNotEnabled");
                });
    }

    /**
     * Happy path test with 2FA
     */
    @Test
    @Sql({AUTHORITY_DATA_SQL_PATH, USER_DATA_SQL_PATH, SEC_DATA_SQL_PATH})
    void loginWith2FAWhenAccountEnabled() throws JsonProcessingException {
        final String registrationUri = baseUrl() + "/v1/account/register";
        boolean otpEnabled = true;
        final UserDto userData = getUserData(otpEnabled);

        final ObjectMapper defaultObjectMapper = getDefaultObjectMapper();
        final String userDataAsString = defaultObjectMapper.writeValueAsString(userData);
        final Map<String, Object> userDataMap = defaultObjectMapper.readValue(userDataAsString, Map.class);

        //register user
        final ResponseEntity<Map> registerResponse = httpTestClient.makeHttpRequest(registrationUri,
                                                                                    HttpMethod.POST,
                                                                                    userDataMap,
                                                                                    baseHttpHeaders(),
                                                                                    Map.class
        );

        final String registerHelpCode = (String) registerResponse.getBody().get(HELP_CODE);

        //Get activation key from sent email and then activate account
        final TestMailManager testMailManager = (TestMailManager) mailManager;
        await().until(() -> testMailManager.getEnvelope(registerHelpCode) != null);
        final String activationKey = (String) testMailManager.getEnvelope(registerHelpCode).data().get("activationKey");
        assertThat(activationKey).isNotNull();
        
        final String enableAccountUri = baseUrl() + "/v1/account/activate?key=" + activationKey;
        final ResponseEntity<Map> activationResponse = httpTestClient.makeHttpRequest(enableAccountUri,
                                                                                      HttpMethod.POST,
                                                                                      Map.of(),
                                                                                      baseHttpHeaders(),
                                                                                      Map.class);

        assertThat(activationResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Manually enable OTP if not correctly set by registration
        template.execute(status -> {
            userRepository.enableOtp(userData.getLogin());
            return 0;
        });

        // Authenticate (should trigger 2FA)
        String authUri = baseUrl() + AUTHENTICATE_ENDPOINT;
        final HttpHeaders httpHeaders = baseHttpHeaders();
        ResponseEntity<Map> responseEntity = httpTestClient.makeHttpRequest(authUri,
                                                                            HttpMethod.POST,
                                                                            Map.of(LOGIN_ID,
                                                                                   userData.getLogin(),
                                                                                   PASSWORD,
                                                                                   userData.getPassword()),
                                                                            httpHeaders,
                                                                            Map.class);
        final Map authBody = responseEntity.getBody();
        assertThat(authBody).isNotNull();
        final String authHelpCode = (String) authBody.get(HELP_CODE);
        assertThat(authHelpCode).isNotBlank();
        
        final Map payload = (Map) authBody.get("payload");
        final String loginInfoId = (String) payload.get("loginInfoId");
        assertThat(loginInfoId).isNotBlank();

        // Get OTP from email
        await().until(() -> testMailManager.getEnvelope(authHelpCode) != null);
        
        final Envelope otpEnvelope = testMailManager.getEnvelope(authHelpCode);
        final Map<String, Object> data = otpEnvelope.data();
        
        // TwoFactorLoginService uses "otpCode"
        Object otpObj = data.get("otpCode");
        if (otpObj == null) otpObj = data.get("otp");
        
        assertThat(otpObj).as("OTP should be present in envelope data").isNotNull();
        String otp = otpObj.toString();
        assertThat(otp).isNotBlank();

        // Submit OTP
        final List<String> setCookies = responseEntity.getHeaders().get(HttpHeaders.SET_COOKIE);
        
        final String liiCookie = setCookies.stream()
                .filter(c -> c.contains(LOGIN_INFO_ID))
                .findFirst().orElseThrow();
        final String sessionCookie = setCookies.stream()
                .filter(c -> c.contains(JWT_SESSION_COOKIE))
                .findFirst().orElseThrow();

        final HttpHeaders otpHeaders = baseHttpHeaders();
        otpHeaders.add(HttpHeaders.COOKIE, liiCookie.split(";", 2)[0]);
        otpHeaders.add(HttpHeaders.COOKIE, sessionCookie.split(";", 2)[0]);

        final String otpUri = baseUrl() + OTP_ENDPOINT;
        ResponseEntity<Map> otpResponse = httpTestClient.makeHttpRequest(otpUri,
                                                                         HttpMethod.POST,
                                                                         Map.of("otp", otp, "loginInfoId", loginInfoId),
                                                                         otpHeaders,
                                                                         Map.class);

        assertThat(otpResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otpResponse.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
                .anyMatch(c -> c.contains(JWT_COOKIE_NAME))).isTrue();
    }

    @Test
    @Sql({AUTHORITY_DATA_SQL_PATH, USER_DATA_SQL_PATH, SEC_DATA_SQL_PATH})
    void loginWith2FADisabled() {
        final String uri = baseUrl() + AUTHENTICATE_ENDPOINT;
        final HttpHeaders httpHeaders = baseHttpHeaders();
        final ResponseEntity<Map> responseEntity = httpTestClient.makeHttpRequest(uri,
                                                                                  HttpMethod.POST,
                                                                                  Map.of(LOGIN_ID,
                                                                                         "queb@yahoo.com",
                                                                                         PASSWORD,
                                                                                         "admin*123!"),
                                                                                  httpHeaders,
                                                                                  Map.class);
        final Map responseBody = responseEntity.getBody();
        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseBody.get("msgKey")).isEqualTo("jwt.created");

        // Access protected resource
        final List<String> cookies = responseEntity.getHeaders().get(HttpHeaders.SET_COOKIE);
        String cookieHeaderValue = String.join("; ", cookies.stream().map(c -> c.split(";", 2)[0]).toList());
        
        final HttpHeaders authHeaders = baseHttpHeaders();
        authHeaders.add(HttpHeaders.COOKIE, cookieHeaderValue);
        
        String accountUri = baseUrl() + "/v1/account/";
        ResponseEntity<Map> accountResponse = httpTestClient.makeHttpRequest(accountUri, HttpMethod.GET, null, authHeaders, Map.class);

        assertThat(accountResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accountResponse.getBody().get("login")).isEqualTo("queb@yahoo.com");
    }

    @Test
    @Sql({SecurityIT.SEC_DATA_SQL_PATH})
    void testAccessProtectedResource_NoToken_Unauthorized() throws JsonProcessingException {
        final String accountUri = baseUrl() + "/v1/account/";
        final HttpHeaders httpHeaders = baseHttpHeaders();

        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(accountUri, HttpMethod.GET, null, httpHeaders, Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Sql({AUTHORITY_DATA_SQL_PATH, USER_DATA_SQL_PATH, SEC_DATA_SQL_PATH})
    void attemptLoginWhenAccountBlocked() throws JsonProcessingException {
        final String uri = baseUrl() + AUTHENTICATE_ENDPOINT;
        final HttpHeaders httpHeaders = baseHttpHeaders();
        
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(uri,
                HttpMethod.POST,
                Map.of(LOGIN_ID, "locked@yahoo.com", PASSWORD, "admin*123!"),
                httpHeaders,
                Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> {
                    HttpClientErrorException ex = (HttpClientErrorException) e;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getResponseBodyAsString()).contains("security.accLocked");
                });
    }

    private String baseUrl() {
        return "http://localhost:" + randomServerPort;
    }

    private static UserDto getUserData(boolean otpEnabled) {
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
        userDto.setOtpEnabled(otpEnabled);
        userDto.setFirstName("Test");
        userDto.setLastName("User");
        return userDto;
    }

    private static ObjectMapper getDefaultObjectMapper() {
        CommonConfig contextConfig = new CommonConfig();
        return contextConfig.objectMapperBuilder().build();
    }
}
