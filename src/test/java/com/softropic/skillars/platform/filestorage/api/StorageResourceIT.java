package com.softropic.skillars.platform.filestorage.api;

import com.softropic.skillars.config.E2ESecurityConfig;
import com.softropic.skillars.e2e.AdminLogin;
import com.softropic.skillars.infrastructure.persistence.BaseEntity;
import com.softropic.skillars.infrastructure.storage.BaseStorageIT;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObject;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJobRepository;
import com.softropic.skillars.platform.filestorage.repo.StorageAccessEventRepository;
import com.softropic.skillars.platform.filestorage.config.FileStorageProperties;
import com.softropic.skillars.platform.filestorage.contract.ConfirmUploadRequest;
import com.softropic.skillars.platform.filestorage.contract.ConfirmUploadResponse;
import com.softropic.skillars.platform.filestorage.contract.SignDownloadResponse;
import com.softropic.skillars.platform.filestorage.contract.SignUploadRequest;
import com.softropic.skillars.platform.filestorage.contract.SignUploadResponse;
import com.softropic.skillars.platform.security.service.LoginAttemptsService;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import com.softropic.skillars.platform.security.WithMockPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;

@Import(E2ESecurityConfig.class)
class StorageResourceIT extends BaseStorageIT {

    @LocalServerPort
    int port;

    @Autowired
    RestTemplate restTemplate;

    @Autowired
    FileStorageObjectRepository fileStorageObjectRepository;

    @Autowired
    OutboxReplicationJobRepository outboxReplicationJobRepository;

    @Autowired
    StorageAccessEventRepository storageAccessEventRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    LoginAttemptsService loginAttemptsService;

    @Autowired
    FileStorageProperties fileStorageProperties;

    private RestTemplate noRetryRestTemplate;
    private HttpHeaders userCookies;

    @BeforeEach
    void setUp() {
        storageAccessEventRepository.deleteAll();
        outboxReplicationJobRepository.deleteAll();
        fileStorageObjectRepository.deleteAll();

        loginAttemptsService.resetLoginRecording();

        transactionTemplate.execute(status -> {
            jdbcTemplate.execute(
                "INSERT INTO main.authority (id, name, status, created_by, created_date, last_modified_by, last_modified_date, request_id) " +
                "VALUES (6747751741842104908, 'ROLE_ADMIN', 'ACTIVE', 'system', '2016-04-26 20:41:25', 'system', '2016-04-26 20:41:25', '') " +
                "ON CONFLICT DO NOTHING");
            jdbcTemplate.execute(
                "INSERT INTO main.authority (id, name, status, created_by, created_date, last_modified_by, last_modified_date, request_id) " +
                "VALUES (5418719445932238328, 'ROLE_USER', 'ACTIVE', 'system', '2016-04-26 20:41:25', 'system', '2016-04-26 20:41:25', '') " +
                "ON CONFLICT DO NOTHING");
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

        noRetryRestTemplate = new RestTemplateBuilder()
            .requestFactory(SimpleClientHttpRequestFactory.class)
            .build();

        String authUrl = "http://localhost:" + port + "/authenticate";
        userCookies = AdminLogin.loginAsAdmin(authUrl, noRetryRestTemplate);
    }

    @Test
    void fullUploadFlow_signConfirmDownload_succeeds() {
        byte[] content = new byte[]{1, 2, 3};
        SignUploadResponse signResponse = signUploadHttp("application/pdf", "pdf", content.length);

        putToS3(signResponse.uploadUrl(), content, "application/pdf");

        ConfirmUploadResponse confirmResponse = confirmUploadHttp(signResponse.key(), "application/pdf", content.length);
        assertThat(confirmResponse.key()).isEqualTo(signResponse.key());

        ResponseEntity<SignDownloadResponse> downloadSignResponse = signDownloadHttp(signResponse.key());
        assertThat(downloadSignResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String downloadUrl = downloadSignResponse.getBody().downloadUrl();
        assertThat(downloadUrl).isNotBlank();

        ResponseEntity<byte[]> getResponse = noRetryRestTemplate.getForEntity(
            URI.create(downloadUrl), byte[].class);
        assertThat(getResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(getResponse.getBody()).isEqualTo(content);
    }

    @Test
    void deleteFlow_softDeleteMakesFileInaccessible() {
        byte[] content = new byte[]{1, 2, 3};
        SignUploadResponse signResponse = signUploadHttp("application/pdf", "pdf", content.length);
        putToS3(signResponse.uploadUrl(), content, "application/pdf");
        confirmUploadHttp(signResponse.key(), "application/pdf", content.length);

        ResponseEntity<Void> deleteResponse = deleteHttp(signResponse.key());
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThatThrownBy(() -> signDownloadHttp(signResponse.key()))
            .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    @WithMockPrincipal(businessId = "675373350208068096")
    void quotaRejection_returns429() {
        String ownerId = "675373350208068096";

        FileStorageObject existingFile = Instancio.of(FileStorageObject.class)
            .ignore(field(BaseEntity.class, "id"))
            .set(field(FileStorageObject.class, "ownerId"), ownerId)
            .set(field(FileStorageObject.class, "sizeBytes"), fileStorageProperties.getQuota().getDefaultBytes())
            .set(field(FileStorageObject.class, "provider"), "s3")
            .set(field(FileStorageObject.class, "tags"), null)
            .create();
        fileStorageObjectRepository.save(existingFile);

        SignUploadRequest body = new SignUploadRequest(
            "documents", "42", "application/pdf", "pdf", 1L, null, null);

        HttpHeaders headers = new HttpHeaders();
        headers.addAll(userCookies);
        headers.add("user-agent", AdminLogin.TEST_USER_AGENT);
        headers.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");
        headers.setContentType(MediaType.APPLICATION_JSON);

        assertThatThrownBy(() -> restTemplate.postForEntity(
            "http://localhost:" + port + "/api/storage/sign/upload",
            new HttpEntity<>(body, headers), Void.class))
            .isInstanceOf(HttpClientErrorException.class)
            .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value()).isEqualTo(429));
    }

    @Test
    void unauthenticated_signUpload_returns401() {
        SignUploadRequest body = new SignUploadRequest(
            "documents", "42", "application/pdf", "pdf", 1024L, null, null);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        assertThatThrownBy(() -> restTemplate.postForEntity(
            "http://localhost:" + port + "/api/storage/sign/upload",
            new HttpEntity<>(body, headers), Void.class))
            .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    @Test
    void unauthenticated_signDownload_returns401() {
        assertThatThrownBy(() -> restTemplate.getForEntity(
            "http://localhost:" + port + "/api/storage/sign/download/some/key",
            Void.class))
            .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    private SignUploadResponse signUploadHttp(String contentType, String extension, long sizeBytes) {
        SignUploadRequest body = new SignUploadRequest(
            "documents", "42", contentType, extension, sizeBytes, null, null);

        HttpHeaders headers = new HttpHeaders();
        headers.addAll(userCookies);
        headers.add("user-agent", AdminLogin.TEST_USER_AGENT);
        headers.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<SignUploadResponse> response = restTemplate.postForEntity(
            "http://localhost:" + port + "/api/storage/sign/upload",
            new HttpEntity<>(body, headers), SignUploadResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void putToS3(String uploadUrl, byte[] content, String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        ResponseEntity<Void> response = noRetryRestTemplate.exchange(
            URI.create(uploadUrl), HttpMethod.PUT,
            new HttpEntity<>(content, headers), Void.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private ConfirmUploadResponse confirmUploadHttp(String key, String contentType, long sizeBytes) {
        ConfirmUploadRequest body = new ConfirmUploadRequest(contentType, sizeBytes, null, null, null);

        HttpHeaders headers = new HttpHeaders();
        headers.addAll(userCookies);
        headers.add("user-agent", AdminLogin.TEST_USER_AGENT);
        headers.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<ConfirmUploadResponse> response = restTemplate.postForEntity(
            "http://localhost:" + port + "/api/storage/confirm/" + key,
            new HttpEntity<>(body, headers), ConfirmUploadResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<SignDownloadResponse> signDownloadHttp(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(userCookies);
        headers.add("user-agent", AdminLogin.TEST_USER_AGENT);
        headers.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");

        return restTemplate.exchange(
            "http://localhost:" + port + "/api/storage/sign/download/" + key,
            HttpMethod.GET, new HttpEntity<>(headers), SignDownloadResponse.class);
    }

    private ResponseEntity<Void> deleteHttp(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(userCookies);
        headers.add("user-agent", AdminLogin.TEST_USER_AGENT);
        headers.add(HttpHeaders.COOKIE, "fcookie=fingerprintCookie");

        return restTemplate.exchange(
            "http://localhost:" + port + "/api/storage/" + key,
            HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
    }
}
