package com.softropic.skillars.platform.tenant;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.skillars.platform.tenant.service.TenantService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS-03: Integration test proving TenantContext is cleared from the servlet thread
 * after an exception-path request.
 *
 * <p>Strategy — two-request probe over RANDOM_PORT (see 40-RESEARCH.md Architecture
 * Patterns OPS-03):
 * <ol>
 *   <li>Create tenant T1, send a valid-key request that triggers an exception inside
 *       the dispatcher (malformed JSON body → HttpMessageNotReadableException, or
 *       wrong HTTP method → HttpRequestMethodNotSupportedException). The exception
 *       propagates through {@code chain.doFilter} and the filter's {@code finally}
 *       block fires, which calls {@code TenantContext.clear()}.</li>
 *   <li>Create tenant T2 and send a second request with T2's key. If T1's
 *       SecurityContext or TenantContext had leaked, the second request would be
 *       rejected with 401 "Missing X-Api-Key header" (filter saw stale state) or
 *       the chain would short-circuit on a different tenant's identity. The
 *       assertion NOT equal to 401 proves no leak.</li>
 * </ol>
 *
 * <p>The test thread cannot read the servlet thread's ThreadLocal directly, so
 * "no leak" is observed indirectly through the outcome of request 2. This is the
 * same pattern as {@code TenantFilterChainIT#tenantContext_clearedAfterRequest_noLeakBetweenRequests}
 * but extended to exercise the exception path specifically.
 */
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"ledger.database.spy=true", "enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
class TenantContextExceptionIT {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private RestTemplate restTemplate;

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        // Load the JWT secret row required by SecurityAdviceFilter.addSecretToThread().
        // Same row used by TenantFilterChainIT — lifted verbatim.
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute(
                "INSERT INTO main.sec (id, created_by, created_date, last_modified_by, last_modified_date, " +
                "request_id, session_id, status, bus_id, value, version) " +
                "VALUES ('659287191260154475','SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'SYSTEM_ACCOUNT','2024-12-24 06:51:55.357352'," +
                "'bed78f34-3e09-4fa8-81db-32326a528cca', null, 'ACTIVE', 'jot'," +
                "'loiI8oT2C1tWecrNXPDjN8fveYEU8rD6nb1k1NbVy92rwdd4/KO+aHhXh3A5zjsT5eSFL/xI+9Rqyj4RI6QCiFywn5nZLIwHGPNEY0F9lnDnGGmVjv/9rO5fgGt83+cxNDyGoCePaVEpBd7xHxyDdfpAoLxQs8mhKGqcEsh09Q+26qEiEm/a9bgDSbSQ0sX00VHBLd35OLmvN+ydjEluYxBTa6KzGb2CQ6Ttg4ZaELmbZOWpEjQ1Z7BbbYiXmWyaY+2HnkyhONoGbUpvVKl1c4e9IlQzeUYkekbUbADIm2LNK9Nhfv5/L5esvFrdVOUcUpLk/y8UT9f5xOMLFJ4Ct6s0eTKvNqYkSz2DFRI8Ip4p/ns6gA4V/1MUf9GeqPUWLiOa28Vw15+R8ycUMqb8NZHOP1oj9RunhSwA7EY84bZL3+yePc3n1b8ne8xzaYVEdK1WBu3J6s2AoBaOL/JLWfu8MuxXI+ub', 'v1') " +
                "ON CONFLICT DO NOTHING");
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("delete from main.tenant_api_key");
            jdbcTemplate.execute("delete from main.tenant");
            jdbcTemplate.execute("delete from main.sec");
            return null;
        });
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // -------------------------------------------------------------------------
    // OPS-03 Test 1:
    // Request 1 = valid key + malformed JSON body → HttpMessageNotReadableException
    //             → filter's finally block fires → TenantContext.clear()
    // Request 2 = different tenant's valid key → must NOT be rejected with 401
    //             (a 401 would prove stale context leaked from request 1)
    // -------------------------------------------------------------------------
    @Test
    void tenantContext_clearedAfterExceptionPath_validKeyFollowedByMalformedBody() {
        TenantService.TenantCreationResult t1 =
            tenantService.createTenant("ExPath Corp 1", ApiKeyEnvironment.PROD);
        String rawKey1 = t1.rawKey();

        HttpHeaders headers1 = new HttpHeaders();
        headers1.setContentType(MediaType.APPLICATION_JSON);
        headers1.set("X-Api-Key", rawKey1);

        // Malformed JSON body — Spring dispatcher throws HttpMessageNotReadableException.
        // The exception propagates through chain.doFilter, so the filter's finally
        // block runs and TenantContext is cleared on the servlet thread.
        HttpEntity<String> req1 = new HttpEntity<>("NOT_VALID_JSON{", headers1);

        try {
            ResponseEntity<Object> resp1 = restTemplate.postForEntity(
                url("/v1/ping"), req1, Object.class);
            // Any non-401 here proves the filter accepted the key; the route may
            // respond differently depending on content-negotiation.
            assertThat(resp1.getStatusCode().value()).isNotEqualTo(401);
        } catch (HttpClientErrorException e) {
            // Expected path: 400 Bad Request from HttpMessageNotReadableException.
            // Anything except 401 is fine — 401 would mean the filter rejected the key.
            assertThat(e.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        } catch (HttpServerErrorException e) {
            assertThat(e.getStatusCode().value()).isNotEqualTo(401);
        }

        // --- Probe: second request on a different tenant's key ---
        TenantService.TenantCreationResult t2 =
            tenantService.createTenant("ExPath Corp 2", ApiKeyEnvironment.PROD);
        String rawKey2 = t2.rawKey();

        HttpHeaders headers2 = new HttpHeaders();
        headers2.setContentType(MediaType.APPLICATION_JSON);
        headers2.set("X-Api-Key", rawKey2);

        HttpEntity<Map<String, Object>> req2 = new HttpEntity<>(
            Map.of(
                "msisdn", "670000001",
                "amount", "500",
                "idempotencyKey", UUID.randomUUID().toString()),
            headers2);

        try {
            ResponseEntity<Object> resp2 = restTemplate.postForEntity(
                url("/v1/ping"), req2, Object.class);
            assertThat(resp2.getStatusCode().value())
                .as("OPS-03: request 2 must NOT see leaked context from request 1 — expected != 401")
                .isNotEqualTo(401);
        } catch (HttpClientErrorException e) {
            // Any 4xx except 401 is acceptable — downstream validation may reject
            // the body on business grounds, but the filter chain must have passed.
            assertThat(e.getStatusCode())
                .as("OPS-03: filter must accept request 2's key after request 1's exception path")
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(e.getResponseBodyAsString())
                .doesNotContain("Missing X-Api-Key header");
        } catch (HttpServerErrorException e) {
            // 5xx from downstream is acceptable — not a filter-chain rejection.
            assertThat(e.getStatusCode().value()).isNotEqualTo(401);
        }
    }

    // -------------------------------------------------------------------------
    // OPS-03 Test 2:
    // Request 1 = valid key + wrong HTTP method → HttpRequestMethodNotSupportedException
    //             → filter's finally block still fires → TenantContext.clear()
    // Request 2 = different tenant's valid key → must NOT be rejected with 401
    // -------------------------------------------------------------------------
    @Test
    void tenantContext_clearedAfterExceptionPath_validKeyFollowedByUnsupportedMethod() {
        TenantService.TenantCreationResult t1 =
            tenantService.createTenant("ExPath MethodCorp 1", ApiKeyEnvironment.PROD);
        String rawKey1 = t1.rawKey();

        HttpHeaders headers1 = new HttpHeaders();
        headers1.set("X-Api-Key", rawKey1);

        HttpEntity<Void> req1 = new HttpEntity<>(headers1);

        try {
            restTemplate.exchange(url("/v1/ping"), HttpMethod.DELETE, req1, Object.class);
        } catch (HttpClientErrorException e) {
            // Expected: 405 Method Not Allowed OR 404 — anything except 401.
            assertThat(e.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        } catch (HttpServerErrorException e) {
            assertThat(e.getStatusCode().value()).isNotEqualTo(401);
        }

        // --- Probe: second request on a different tenant's key ---
        TenantService.TenantCreationResult t2 =
            tenantService.createTenant("ExPath MethodCorp 2", ApiKeyEnvironment.PROD);
        String rawKey2 = t2.rawKey();

        HttpHeaders headers2 = new HttpHeaders();
        headers2.setContentType(MediaType.APPLICATION_JSON);
        headers2.set("X-Api-Key", rawKey2);

        HttpEntity<Map<String, Object>> req2 = new HttpEntity<>(
            Map.of(
                "msisdn", "670000002",
                "amount", "500",
                "idempotencyKey", UUID.randomUUID().toString()),
            headers2);

        try {
            ResponseEntity<Object> resp2 = restTemplate.postForEntity(
                url("/v1/ping"), req2, Object.class);
            assertThat(resp2.getStatusCode().value()).isNotEqualTo(401);
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode())
                .as("OPS-03: filter must accept request 2's key after 405-path request 1")
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(e.getResponseBodyAsString())
                .doesNotContain("Missing X-Api-Key header");
        } catch (HttpServerErrorException e) {
            assertThat(e.getStatusCode().value()).isNotEqualTo(401);
        }
    }
}
