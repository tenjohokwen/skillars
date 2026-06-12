package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.repo.LoginAttemptRepository;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthCleanupService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final LoginAttemptRepository loginAttemptRepository;

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void purgeExpiredRefreshTokens() {
        refreshTokenRepository.deleteExpiredTokens();
        log.debug("Purged expired refresh tokens");
    }

    // Retain 24 hours of attempts so rate-limit windows (15 min) have margin;
    // keeps the table small rather than growing unboundedly.
    @Scheduled(cron = "0 */30 * * * *")
    @Transactional
    public void purgeOldLoginAttempts() {
        loginAttemptRepository.deleteByAttemptedAtBefore(Instant.now().minus(24, ChronoUnit.HOURS));
        log.debug("Purged stale login attempts");
    }
}
