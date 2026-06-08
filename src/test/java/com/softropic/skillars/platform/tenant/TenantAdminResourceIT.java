package com.softropic.skillars.platform.tenant;

import com.softropic.skillars.e2e.AdminLogin;
import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.security.service.LoginAttemptsService;
import com.softropic.skillars.platform.tenant.contract.ApiKeyDto;
import com.softropic.skillars.platform.tenant.service.ApiKeyService;
import com.softropic.skillars.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.skillars.platform.tenant.service.TenantService;
import com.softropic.skillars.platform.tenant.service.TenantQueryService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"ledger.database.spy=true", "enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class TenantAdminResourceIT {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private TenantQueryService tenantQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private LoginAttemptsService loginAttemptsService;

    private RestTemplate noRetryRestTemplate;
    private RestTemplate patchRestTemplate;

    @LocalServerPort
    int port;

    /** JWT cookies obtained from POST /authenticate — set once per test in setUp(). */
    private HttpHeaders adminCookies;

    @BeforeEach
    void setUp() {
        cleanDb();
        // Reset the in-memory login-attempt cache so stale failures from a prior test
        // cannot cause LockedException during this test's authentication.
        //loginAttemptsService.unlockUser("queb@yahoo.com");
        loginAttemptsService.resetLoginRecording();

        noRetryRestTemplate = new RestTemplateBuilder()
                .requestFactory(SimpleClientHttpRequestFactory.class)
                .build();

        patchRestTemplate = new RestTemplateBuilder()
                .requestFactory(HttpComponentsClientHttpRequestFactory.class)
                .rootUri("http://localhost:" + port)
                .build();

        // Load the JWT secret required by SecurityAdviceFilter.addSecretToThread().
        // Without this, any request to /v1/** fails with SecException KEY_NOT_FOUND.
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute(
                "INSERT INTO main.sec (id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, session_id, status, bus_id, value, version) " +
                "VALUES ('659287191260154475','SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'bed78f34-3e09-4fa8-81db-32326a528cca', null, 'ACTIVE', 'jot'," +
                "'loiI8oT2C1tWecrNXPDjN8fveYEU8rD6nb1k1NbVy92rwdd4/KO+aHhXh3A5zjsT5eSFL/xI+9Rqyj4RI6QCiFywn5nZLIwHGPNEY0F9lnDnGGmVjv/9rO5fgGt83+cxNDyGoCePaVEpBd7xHxyDdfpAoLxQs8mhKGqcEsh09Q+26qEiEm/a9bgDSbSQ0sX00VHBLd35OLmvN+ydjEluYxBTa6KzGb2CQ6Ttg4ZaELmbZOWpEjQ1Z7BbbYiXmWyaY+2HnkyhONoGbUpvVKl1c4e9IlQzeUYkekbUbADIm2LNK9Nhfv5/L5esvFrdVOUcUpLk/y8UT9f5xOMLFJ4Ct6s0eTKvNqYkSz2DFRI8Ip4p/ns6gA4V/1MUf9GeqPUWLiOa28Vw15+R8ycUMqb8NZHOP1oj9RunhSwA7EY84bZL3+yePc3n1b8ne8xzaYVEdK1WBu3J6s2AoBaOL/JLWfu8MuxXI+ub', 'v1') " +
                "ON CONFLICT DO NOTHING");
            // Roles required for login → user lookup
            jdbcTemplate.execute(
                    "INSERT INTO main.authority (id, name, status, created_by, created_date, last_modified_by, last_modified_date, request_id) " +
                            "VALUES (6747751741842104908, 'ROLE_ADMIN', 'ACTIVE', 'system', '2016-04-26 20:41:25', 'system', '2016-04-26 20:41:25', '') " +
                            "ON CONFLICT DO NOTHING");
            jdbcTemplate.execute(
                    "INSERT INTO main.authority (id, name, status, created_by, created_date, last_modified_by, last_modified_date, request_id) " +
                            "VALUES (5418719445932238328, 'ROLE_USER', 'ACTIVE', 'system', '2016-04-26 20:41:25', 'system', '2016-04-26 20:41:25', '') " +
                            "ON CONFLICT DO NOTHING");

            // Admin user — password hash = admin*123!
            jdbcTemplate.execute(
                    "INSERT INTO main.\"user\" " +
                            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
                            " status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, phone_type, " +
                            " title, activated, activation_date, activation_key, locked, login, login_id_type, " +
                            " password_hash, reset_expiration, reset_key, otp_enabled) " +
                            "VALUES " +
                            "(675373350208068096, 'anonymousUser', '2025-02-06 16:12:34.516705', 'anonymousUser', '2025-02-06 16:12:35.198266', " +
                            " 'd503b412-b576-48c2-8ead-ec9e10d42880', NULL, 'ACTIVE', '1990-02-20', 'queb@yahoo.com', " +
                            " 'VAYM', 'MALE', 'en', 'FXFUOUQBUO', 'DE', '01724527687', 'MOBILE', NULL, " +
                            " true, NULL, NULL, false, 'queb@yahoo.com', 'EMAIL', " +
                            " '$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i', NULL, NULL, false) " +
                            "ON CONFLICT DO NOTHING");
            jdbcTemplate.execute(
                    "INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208068096, 5418719445932238328) " +
                            "ON CONFLICT DO NOTHING");
            jdbcTemplate.execute(
                    "INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208068096, 6747751741842104908) " +
                            "ON CONFLICT DO NOTHING");
            return null;
        });

        // ----------------------------------------------------------------
        // Authenticate as the admin user to obtain real JWT cookies
        // ----------------------------------------------------------------
        final String authUrl = "http://localhost:" + port + "/authenticate";
        adminCookies = AdminLogin.loginAsAdmin(authUrl, noRetryRestTemplate);
    }

    @AfterEach
    void tearDown() {
        cleanDb();
    }

    private void cleanDb() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("delete from main.tenant_api_key");
            jdbcTemplate.execute("delete from main.tenant");
            jdbcTemplate.execute("delete from main.persistent_token");
            jdbcTemplate.execute("delete from main.user_addresses");
            jdbcTemplate.execute("delete from main.user_authority");
            jdbcTemplate.execute("delete from main.audit_log");
            jdbcTemplate.execute("delete from main.user");
            jdbcTemplate.execute("delete from main.authority");
            jdbcTemplate.execute("delete from main.sec");
            return null;
        });
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders(adminCookies);
        headers.add("user-agent", AdminLogin.TEST_USER_AGENT); //user agent has to match the agent used to login or else authorizer sees it as token theft
        headers.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }

    // -------------------------------------------------------------------------
    // Test 1: rotate returns 200 with new non-null rawKey different from original
    // -------------------------------------------------------------------------
    @Test
    void rotateKey_returns200_withNewRawKey() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Rotate HTTP Corp", ApiKeyEnvironment.PROD);
        Long tenantId = result.tenant().getId();
        Long keyId = result.key().getId();
        String originalRawKey = result.rawKey();

        HttpEntity<Void> request = new HttpEntity<>(jsonHeaders());

        ResponseEntity<ApiKeyDto> response = noRetryRestTemplate.exchange(
            url("/v1/admin/tenants/" + tenantId + "/keys/" + keyId + "/rotate"),
            HttpMethod.POST, request, ApiKeyDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiKeyDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.rawKey()).isNotNull();
        assertThat(dto.rawKey()).isNotBlank();
        assertThat(dto.rawKey()).isNotEqualTo(originalRawKey);
        assertThat(dto.id()).isNotEqualTo(keyId);  // rotate creates a new DB row

        // Old key must still authenticate during grace period
        assertThat(apiKeyService.authenticate(originalRawKey)).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Test 2: revoke returns 204 and key is subsequently rejected
    // -------------------------------------------------------------------------
    @Test
    void revokeKey_returns204_andKeyIsUnusable() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Revoke HTTP Corp", ApiKeyEnvironment.PROD);
        Long tenantId = result.tenant().getId();
        Long keyId = result.key().getId();
        String rawKey = result.rawKey();

        HttpEntity<Void> request = new HttpEntity<>(jsonHeaders());

        ResponseEntity<Void> response = noRetryRestTemplate.exchange(
            url("/v1/admin/tenants/" + tenantId + "/keys/" + keyId),
            HttpMethod.DELETE, request, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();

        // Revoked key must be rejected by authenticate()
        assertThatThrownBy(() -> apiKeyService.authenticate(rawKey))
            .isInstanceOf(BadCredentialsException.class);
    }

    // -------------------------------------------------------------------------
    // Test 3: unknown keyId returns 404
    // -------------------------------------------------------------------------
    @Test
    void rotateKey_unknownKeyId_returns404() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Unknown Key Corp", ApiKeyEnvironment.PROD);
        Long tenantId = result.tenant().getId();
        String rawKey = result.rawKey();

        HttpEntity<Void> request = new HttpEntity<>(jsonHeaders());

        assertThatThrownBy(() ->
            noRetryRestTemplate.exchange(
                url("/v1/admin/tenants/" + tenantId + "/keys/999999/rotate"),
                HttpMethod.POST, request, Object.class))
            .isInstanceOf(HttpClientErrorException.NotFound.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // -------------------------------------------------------------------------
    // Test 4 (TENT-05): GET /v1/admin/tenants returns paginated list
    // -------------------------------------------------------------------------
    @Test
    void listTenants_returnsPage() {
        tenantService.createTenant("List Corp Alpha", ApiKeyEnvironment.PROD);
        tenantService.createTenant("List Corp Beta", ApiKeyEnvironment.PROD);

        HttpEntity<Void> request = new HttpEntity<>(jsonHeaders());

        ResponseEntity<String> response = noRetryRestTemplate.exchange(
            url("/v1/admin/tenants"),
            HttpMethod.GET, request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("List Corp Alpha");
        assertThat(body).contains("List Corp Beta");
        assertThat(body).contains("\"totalElements\":2");
    }

    // -------------------------------------------------------------------------
    // Test 5 (TENT-05): GET /v1/admin/tenants?status=ACTIVE filters by status
    // -------------------------------------------------------------------------
    @Test
    void listTenants_filteredByStatus() {
        TenantService.TenantCreationResult activeResult =
            tenantService.createTenant("Active Corp", ApiKeyEnvironment.PROD);
        TenantService.TenantCreationResult suspendedResult =
            tenantService.createTenant("Suspended Corp", ApiKeyEnvironment.PROD);
        tenantService.suspend(suspendedResult.tenant().getTenantRef());

        HttpEntity<Void> request = new HttpEntity<>(jsonHeaders());

        ResponseEntity<String> response = noRetryRestTemplate.exchange(
            url("/v1/admin/tenants?status=ACTIVE"),
            HttpMethod.GET, request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("Active Corp");
        assertThat(body).doesNotContain("Suspended Corp");
        assertThat(body).contains("\"totalElements\":1");
    }

    // -------------------------------------------------------------------------
    // Test 6 (TENT-06): GET /v1/admin/tenants/{tenantRef} returns detail without webhookSecret
    // -------------------------------------------------------------------------
    @Test
    void getTenantDetail_returnsDetailWithoutSecret() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Detail Corp", ApiKeyEnvironment.PROD);
        String tenantRef = result.tenant().getTenantRef();

        HttpEntity<Void> request = new HttpEntity<>(jsonHeaders());

        ResponseEntity<String> response = noRetryRestTemplate.exchange(
            url("/v1/admin/tenants/" + tenantRef),
            HttpMethod.GET, request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains(tenantRef);
        assertThat(body).contains("Detail Corp");
        assertThat(body).contains("keys");
        assertThat(body).doesNotContain("webhookSecret");
    }

    // -------------------------------------------------------------------------
    // Test 7 (WSEC-03): GET /v1/admin/tenants/{tenantRef}/webhook-secret returns plaintext secret
    // -------------------------------------------------------------------------
    @Test
    void getWebhookSecret_returnsPlaintextSecret() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Secret Corp", ApiKeyEnvironment.PROD);
        String tenantRef = result.tenant().getTenantRef();

        HttpEntity<Void> request = new HttpEntity<>(jsonHeaders());

        ResponseEntity<String> response = noRetryRestTemplate.exchange(
            url("/v1/admin/tenants/" + tenantRef + "/webhook-secret"),
            HttpMethod.GET, request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("webhookSecret");
    }

    // -------------------------------------------------------------------------
    // Test 8 (TENT-10): PATCH /{tenantRef}/name returns 204 and persists change
    // -------------------------------------------------------------------------
    @Test
    void updateName_returns204() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Original Corp", ApiKeyEnvironment.PROD);
        String tenantRef = result.tenant().getTenantRef();

        HttpEntity<String> request = new HttpEntity<>("{\"name\":\"Updated Corp\"}", jsonHeaders());

        ResponseEntity<Void> response = patchRestTemplate.exchange(
            "/v1/admin/tenants/" + tenantRef + "/name",
            HttpMethod.PATCH, request, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Verify name change persisted via GET
        HttpEntity<Void> getRequest = new HttpEntity<>(adminCookies);
        ResponseEntity<String> getResponse = noRetryRestTemplate.exchange(
            url("/v1/admin/tenants/" + tenantRef),
            HttpMethod.GET, getRequest, String.class);
        assertThat(getResponse.getBody()).contains("Updated Corp");
    }

    // -------------------------------------------------------------------------
    // Test 9 (TENT-02): PATCH /{tenantRef}/email returns 204 and persists change
    // -------------------------------------------------------------------------
    @Test
    void updateEmail_returns204() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Email Corp", ApiKeyEnvironment.PROD);
        String tenantRef = result.tenant().getTenantRef();

        HttpEntity<String> request = new HttpEntity<>("{\"email\":\"newemail@example.com\"}", jsonHeaders());

        ResponseEntity<Void> response = patchRestTemplate.exchange(
            "/v1/admin/tenants/" + tenantRef + "/email",
            HttpMethod.PATCH, request, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Verify email change persisted via GET
        HttpEntity<Void> getRequest = new HttpEntity<>(adminCookies);
        ResponseEntity<String> getResponse = noRetryRestTemplate.exchange(
            url("/v1/admin/tenants/" + tenantRef),
            HttpMethod.GET, getRequest, String.class);
        assertThat(getResponse.getBody()).contains("newemail@example.com");
    }

    // -------------------------------------------------------------------------
    // Test 10 (TENT-03): PATCH /{tenantRef}/webhook-url returns 204 and persists change
    // -------------------------------------------------------------------------
    @Test
    void updateWebhookUrl_returns204() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Webhook Corp", ApiKeyEnvironment.PROD);
        String tenantRef = result.tenant().getTenantRef();

        HttpEntity<String> request = new HttpEntity<>("{\"webhookUrl\":\"https://hooks.example.com/new\"}", jsonHeaders());

        ResponseEntity<Void> response = patchRestTemplate.exchange(
            "/v1/admin/tenants/" + tenantRef + "/webhook-url",
            HttpMethod.PATCH, request, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Verify webhookUrl change persisted via GET
        HttpEntity<Void> getRequest = new HttpEntity<>(adminCookies);
        ResponseEntity<String> getResponse = noRetryRestTemplate.exchange(
            url("/v1/admin/tenants/" + tenantRef),
            HttpMethod.GET, getRequest, String.class);
        assertThat(getResponse.getBody()).contains("https://hooks.example.com/new");
    }

    // -------------------------------------------------------------------------
    // Test 11 (TENT-04): POST /{tenantRef}/suspend returns 204 and revokes all keys
    // -------------------------------------------------------------------------
    @Test
    void suspend_revokesAllKeys() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Suspend Corp", ApiKeyEnvironment.PROD);
        String tenantRef = result.tenant().getTenantRef();
        Long tenantId = result.tenant().getId();

        HttpEntity<Void> request = new HttpEntity<>(jsonHeaders());

        ResponseEntity<Void> response = noRetryRestTemplate.exchange(
            url("/v1/admin/tenants/" + tenantRef + "/suspend"),
            HttpMethod.POST, request, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Verify tenant is SUSPENDED via GET
        ResponseEntity<String> getResponse = noRetryRestTemplate.exchange(
            url("/v1/admin/tenants/" + tenantRef),
            HttpMethod.GET, request, String.class);
        assertThat(getResponse.getBody()).contains("SUSPENDED");

        // Verify all keys are REVOKED
        Integer nonRevokedCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.tenant_api_key WHERE tenant_id = ? AND key_status != 'REVOKED'",
            Integer.class, tenantId);
        assertThat(nonRevokedCount).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // Test 12 (TENT-07): POST /{tenantRef}/reactivate returns 200 with rawKey
    // -------------------------------------------------------------------------
    @Test
    void reactivate_returnsRawKey() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Reactivate Corp", ApiKeyEnvironment.PROD);
        String tenantRef = result.tenant().getTenantRef();

        // Suspend first
        tenantService.suspend(tenantRef);

        HttpEntity<Void> request = new HttpEntity<>(jsonHeaders());

        ResponseEntity<ApiKeyDto> response = noRetryRestTemplate.exchange(
            url("/v1/admin/tenants/" + tenantRef + "/reactivate"),
            HttpMethod.POST, request, ApiKeyDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiKeyDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.rawKey()).isNotNull();
        assertThat(dto.rawKey()).isNotBlank();

        // Verify tenant is ACTIVE via GET
        ResponseEntity<String> getResponse = noRetryRestTemplate.exchange(
            url("/v1/admin/tenants/" + tenantRef),
            HttpMethod.GET, request, String.class);
        assertThat(getResponse.getBody()).contains("ACTIVE");
    }

    // -------------------------------------------------------------------------
    // Test 13 (TENT-08): POST /{tenantRef}/webhook-secret returns 204 and changes secret
    // -------------------------------------------------------------------------
    @Test
    void regenerateWebhookSecret_returns204() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Secret Regen Corp", ApiKeyEnvironment.PROD);
        String tenantRef = result.tenant().getTenantRef();

        HttpEntity<Void> request = new HttpEntity<>(jsonHeaders());

        // Capture original secret
        ResponseEntity<String> originalSecretResponse = noRetryRestTemplate.exchange(
            url("/v1/admin/tenants/" + tenantRef + "/webhook-secret"),
            HttpMethod.GET, request, String.class);
        String originalBody = originalSecretResponse.getBody();

        // Regenerate
        ResponseEntity<Void> response = noRetryRestTemplate.exchange(
            url("/v1/admin/tenants/" + tenantRef + "/webhook-secret"),
            HttpMethod.POST, request, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Verify new secret differs
        ResponseEntity<String> newSecretResponse = noRetryRestTemplate.exchange(
            url("/v1/admin/tenants/" + tenantRef + "/webhook-secret"),
            HttpMethod.GET, request, String.class);
        assertThat(newSecretResponse.getBody()).isNotEqualTo(originalBody);
    }

    // -------------------------------------------------------------------------
    // Test 14 (UI-01): POST /{tenantRef}/keys/generate returns 200 with rawKey
    // -------------------------------------------------------------------------
    @Test
    void generateKey_returns200_withNewRawKey() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Generate Key Corp", ApiKeyEnvironment.PROD);
        String tenantRef = result.tenant().getTenantRef();
        // Revoke the initial PROD key so there is no active key for PROD
        apiKeyService.revoke(result.key().getId());

        HttpEntity<Void> request = new HttpEntity<>(jsonHeaders());

        ResponseEntity<ApiKeyDto> response = noRetryRestTemplate.exchange(
            url("/v1/admin/tenants/" + tenantRef + "/keys/generate?env=PROD"),
            HttpMethod.POST, request, ApiKeyDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiKeyDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.rawKey()).isNotNull();
        assertThat(dto.rawKey()).isNotBlank();
        assertThat(dto.environment()).isEqualTo(ApiKeyEnvironment.PROD);
    }

    // -------------------------------------------------------------------------
    // Test 15 (UI-01): POST /{tenantRef}/keys/generate with existing active key returns 409
    // -------------------------------------------------------------------------
    @Test
    void generateKey_duplicateActiveKey_returns409() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Duplicate Key Corp", ApiKeyEnvironment.PROD);
        String tenantRef = result.tenant().getTenantRef();
        // PROD key is still ACTIVE — second generate should fail

        HttpEntity<Void> request = new HttpEntity<>(jsonHeaders());

        assertThatThrownBy(() ->
            noRetryRestTemplate.exchange(
                url("/v1/admin/tenants/" + tenantRef + "/keys/generate?env=PROD"),
                HttpMethod.POST, request, Object.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
    }

    // -------------------------------------------------------------------------
    // Test 16: POST /{tenantRef}/reactivate on already-ACTIVE tenant returns 409
    // -------------------------------------------------------------------------
    @Test
    void reactivate_alreadyActive_returns409() {
        TenantService.TenantCreationResult result =
            tenantService.createTenant("Already Active Corp", ApiKeyEnvironment.PROD);
        String tenantRef = result.tenant().getTenantRef();

        HttpEntity<Void> request = new HttpEntity<>(jsonHeaders());

        // Tenant is ACTIVE with a PROD key — reactivate should throw 409
        assertThatThrownBy(() ->
            noRetryRestTemplate.exchange(
                url("/v1/admin/tenants/" + tenantRef + "/reactivate"),
                HttpMethod.POST, request, Object.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
    }
}
