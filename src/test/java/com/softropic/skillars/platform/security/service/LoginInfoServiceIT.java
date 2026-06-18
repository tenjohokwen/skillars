package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.util.TestClockProvider;
import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.security.contract.LoginData;
import com.softropic.skillars.platform.security.repo.LoginInfo;
import com.softropic.skillars.platform.security.contract.exception.SecException;
import com.softropic.skillars.infrastructure.security.TestRequestMetadataProvider;
import com.softropic.skillars.platform.security.repo.LoginInfoRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.softropic.skillars.infrastructure.security.SecurityError.CLIENT_INFO_MISMATCH;
import static com.softropic.skillars.infrastructure.security.SecurityError.INVALID_LOGIN_INFO_ID;
import static com.softropic.skillars.infrastructure.security.SecurityError.OTP_ALREADY_USED;
import static com.softropic.skillars.infrastructure.security.SecurityError.OTP_EXPIRED;
import static com.softropic.skillars.infrastructure.security.SecurityError.OTP_MISMATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rate.limiting.enabled=false"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
@Transactional
class LoginInfoServiceIT {

    // Minimal valid values that satisfy LoginInfo column constraints
    private static final String CLIENT_ID  = "myTestClient";
    private static final String SESSION_ID = "test-session-id";
    private static final String REQUEST_ID = "test-request-id-000000000"; // >= 20 chars
    private static final String OTP        = "123456";
    private static final String SQID_SEED  = "a".repeat(62);             // exactly 62 chars
    private static final String TOKEN      = "x".repeat(100);
    private static final String LOGIN_ID   = "me@yahoo.com";
    private static final String SEND_ID    = "send-001";

    @Autowired
    private LoginInfoService loginInfoService;

    @Autowired
    private LoginInfoRepository loginInfoRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        TestClockProvider.setSystemClock();
        TestRequestMetadataProvider.setApiKey(CLIENT_ID);
        TestRequestMetadataProvider.setSessionId(SESSION_ID);
        TestRequestMetadataProvider.setRequestId(REQUEST_ID);
        TestRequestMetadataProvider.setIpAddress("127.0.0.1");
    }

    @AfterEach
    void tearDown() {
        TestClockProvider.setSystemClock();
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // fetchValidLoginData — happy path
    // -------------------------------------------------------------------------

    @Test
    void fetchValidLoginData_validInput_returnsLoginData() {
        LoginInfo saved = loginInfoService.saveLoginInfo(TOKEN, OTP, LOGIN_ID, SQID_SEED, SEND_ID);

        LoginData result = loginInfoService.fetchValidLoginData(saved.getId(), OTP);

        assertThat(result).isNotNull();
        assertThat(result.getOtp()).isEqualTo(OTP);
        assertThat(result.getLoginId()).isEqualTo(LOGIN_ID);
        assertThat(result.getClientId()).isEqualTo(CLIENT_ID);
    }

    // -------------------------------------------------------------------------
    // fetchValidLoginData — failure scenarios
    // -------------------------------------------------------------------------

    @Test
    void fetchValidLoginData_unknownId_throwsInvalidLoginInfoId() {
        assertThatThrownBy(() -> loginInfoService.fetchValidLoginData(Long.MAX_VALUE, OTP))
                .isInstanceOf(SecException.class)
                .extracting(e -> ((SecException) e).getErrorCode())
                .isEqualTo(INVALID_LOGIN_INFO_ID);
    }

    @Test
    void fetchValidLoginData_wrongOtp_throwsOtpMismatch() {
        LoginInfo saved = loginInfoService.saveLoginInfo(TOKEN, OTP, LOGIN_ID, SQID_SEED, SEND_ID);

        assertThatThrownBy(() -> loginInfoService.fetchValidLoginData(saved.getId(), "wrong-otp"))
                .isInstanceOf(SecException.class)
                .extracting(e -> ((SecException) e).getErrorCode())
                .isEqualTo(OTP_MISMATCH);
    }

    @Test
    void fetchValidLoginData_expiredOtp_throwsOtpExpired() {
        LoginInfo saved = loginInfoService.saveLoginInfo(TOKEN, OTP, LOGIN_ID, SQID_SEED, SEND_ID);

        // Advance the clock past the 30-minute OTP TTL
        TestClockProvider.setClock(Clock.fixed(
                Instant.now().plusSeconds(31 * 60),
                ZoneOffset.UTC));

        assertThatThrownBy(() -> loginInfoService.fetchValidLoginData(saved.getId(), OTP))
                .isInstanceOf(SecException.class)
                .extracting(e -> ((SecException) e).getErrorCode())
                .isEqualTo(OTP_EXPIRED);
    }

    @Test
    void fetchValidLoginData_differentClientId_throwsClientInfoMismatch() {
        LoginInfo saved = loginInfoService.saveLoginInfo(TOKEN, OTP, LOGIN_ID, SQID_SEED, SEND_ID);

        // Simulate request arriving from a different client
        TestRequestMetadataProvider.setApiKey("different-client-id");

        assertThatThrownBy(() -> loginInfoService.fetchValidLoginData(saved.getId(), OTP))
                .isInstanceOf(SecException.class)
                .extracting(e -> ((SecException) e).getErrorCode())
                .isEqualTo(CLIENT_INFO_MISMATCH);
    }

    @Test
    void fetchValidLoginData_differentSessionId_throwsClientInfoMismatch() {
        LoginInfo saved = loginInfoService.saveLoginInfo(TOKEN, OTP, LOGIN_ID, SQID_SEED, SEND_ID);

        TestRequestMetadataProvider.setSessionId("different-session-id");

        assertThatThrownBy(() -> loginInfoService.fetchValidLoginData(saved.getId(), OTP))
                .isInstanceOf(SecException.class)
                .extracting(e -> ((SecException) e).getErrorCode())
                .isEqualTo(CLIENT_INFO_MISMATCH);
    }

    @Test
    void fetchValidLoginData_alreadyConsumedOtp_throwsOtpAlreadyUsed() {
        LoginInfo saved = loginInfoService.saveLoginInfo(TOKEN, OTP, LOGIN_ID, SQID_SEED, SEND_ID);
        loginInfoService.markLoginInfoAsConsumed(saved.getId());
        loginInfoRepository.flush();

        assertThatThrownBy(() -> loginInfoService.fetchValidLoginData(saved.getId(), OTP))
                .isInstanceOf(SecException.class)
                .extracting(e -> ((SecException) e).getErrorCode())
                .isEqualTo(OTP_ALREADY_USED);
    }

    // -------------------------------------------------------------------------
    // markLoginInfoAsConsumed
    // -------------------------------------------------------------------------

    @Test
    void markLoginInfoAsConsumed_existingId_returnsTrueAndSetsVerificationDate() {
        LoginInfo saved = loginInfoService.saveLoginInfo(TOKEN, OTP, LOGIN_ID, SQID_SEED, SEND_ID);

        boolean result = loginInfoService.markLoginInfoAsConsumed(saved.getId());

        assertThat(result).isTrue();
        LoginInfo updated = loginInfoRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated.getVerificationDate()).isNotNull();
    }

    @Test
    void markLoginInfoAsConsumed_unknownId_returnsFalse() {
        boolean result = loginInfoService.markLoginInfoAsConsumed(Long.MAX_VALUE);

        assertThat(result).isFalse();
    }
}
