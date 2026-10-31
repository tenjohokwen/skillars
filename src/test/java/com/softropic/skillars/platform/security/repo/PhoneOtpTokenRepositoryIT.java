package com.softropic.skillars.platform.security.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** S-14: the persisted wrong-guess counter invalidates the token at the cap and survives the caller's rollback. */
class PhoneOtpTokenRepositoryIT extends AbstractIntegrationTest {

    @Autowired private PhoneOtpTokenRepository repository;

    @Test
    void recordFailedGuess_countsEachGuess_andInvalidatesTheTokenAtTheCap() {
        PhoneOtpToken token = transactionTemplate.execute(status -> {
            PhoneOtpToken t = new PhoneOtpToken();
            t.setUserId(9216_000_001L);
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
        assertThat(repository.findFirstByUserIdAndUsedFalseOrderByExpiresAtDesc(9216_000_001L)).isEmpty();
    }
}
