package com.softropic.skillars.platform.security.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** S-14: the persisted wrong-guess counter invalidates the token at the cap and survives the caller's rollback. */
class PhoneOtpTokenRepositoryIT extends AbstractIntegrationTest {

    private static final long USER_ID = 9216_000_001L;

    @Autowired private PhoneOtpTokenRepository repository;

    @Test
    void recordFailedGuess_countsEachGuess_andInvalidatesTheTokenAtTheCap() {
        PhoneOtpToken token = transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" " +
                "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
                "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
                "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
                "skillars_role, verification_status) " +
                "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
                "'ACTIVE', ?, ?, 'Test', 'OTHER', 'en', 'Otp', 'DE', ?, " +
                "true, false, ?, 'EMAIL', ?, false, " +
                "'COACH', 'EMAIL_VERIFIED')",
                USER_ID, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                Date.valueOf(LocalDate.of(1990, 3, 15)), "otp.cap@skillars-test.com",
                "9159216001", "otp.cap@skillars-test.com", "x".repeat(60));
            PhoneOtpToken t = new PhoneOtpToken();
            t.setUserId(USER_ID);
            t.setOtpHash("0".repeat(64));
            t.setExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
            t.setUsed(false);
            return repository.saveAndFlush(t);
        });

        for (int i = 1; i < PhoneOtpToken.MAX_FAILED_ATTEMPTS; i++) {
            repository.recordFailedGuess(token.getId(), PhoneOtpToken.MAX_FAILED_ATTEMPTS);
            PhoneOtpToken reloaded = repository.findById(token.getId()).orElseThrow();
            assertThat(reloaded.getFailedAttempts()).isEqualTo(i);
            assertThat(reloaded.isUsed()).isFalse();
        }

        repository.recordFailedGuess(token.getId(), PhoneOtpToken.MAX_FAILED_ATTEMPTS);

        PhoneOtpToken capped = repository.findById(token.getId()).orElseThrow();
        assertThat(capped.getFailedAttempts()).isEqualTo(PhoneOtpToken.MAX_FAILED_ATTEMPTS);
        assertThat(capped.isUsed()).isTrue();
        assertThat(repository.findFirstByUserIdAndUsedFalseOrderByExpiresAtDesc(USER_ID)).isEmpty();
    }
}
