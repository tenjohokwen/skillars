package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.admin.repo.GdprRequest;
import com.softropic.skillars.platform.admin.repo.GdprRequestRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-136 AC2: {@link GdprErasureService#retryFailedErasures()} — the four scenarios
 * this AC's own test plan calls out. Each test seeds a bare {@code main."user"} row directly (COACH
 * role — no {@code player_profiles} needed, see {@code GdprErasureDataSourceRoutingIT}'s identical
 * rationale) and a {@code GdprRequest} row with the exact {@code failed_at}/{@code retry_count}/
 * {@code status} shape the scenario needs, rather than driving a real failure through {@code erase()}
 * first — this AC's own re-drive mechanism is what's under test, not how a row originally became
 * FAILED (that is AC1/skillars-deferred-133/135's own already-covered territory).
 */
class GdprErasureRetryIT extends AbstractIntegrationTest {

    @Autowired private GdprErasureService gdprErasureService;
    @Autowired private GdprRequestRepository gdprRequestRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final AtomicLong USER_ID_SEQ = new AtomicLong(9214_000_001L);

    private long seedCoachUser() {
        long userId = USER_ID_SEQ.getAndIncrement();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, "
                    + "last_modified_date, request_id, session_id, status, dob, email, first_name, "
                    + "gender, lang_key, last_name, iso2_country, phone, activated, locked, login, "
                    + "login_id_type, password_hash, otp_enabled, skillars_role, verification_status) "
                    + "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, 'ACTIVE', '1985-06-01', "
                    // User.password carries a bean-validation @Size(min=60, max=60) (bcrypt hash
                    // shape) enforced on UPDATE too — eraseTransactional's own userRepository.save(user)
                    // trips it on anything shorter, so this placeholder must be exactly 60 chars.
                    + "?, 'Test', 'OTHER', 'en', 'User', 'DE', ?, true, false, ?, 'EMAIL', "
                    + "'$2a$10$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx', false, "
                    + "'COACH', 'BASIC_VERIFIED') ON CONFLICT (id) DO NOTHING",
                userId, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                "gdpr.retry." + userId + "@skillars-test.com", "921" + (userId % 10000000),
                "gdpr.retry." + userId + "@skillars-test.com");
            return null;
        });
        return userId;
    }

    private UUID seedFailedRequest(long userId, Instant failedAt, int retryCount) {
        return transactionTemplate.execute(status -> {
            GdprRequest request = new GdprRequest(userId, "ERASURE", "FAILED");
            request.setFailedAt(failedAt);
            request.setRetryCount(retryCount);
            return gdprRequestRepository.save(request).getId();
        });
    }

    @Test
    @Timeout(30)
    void pastGraceWindowUnderCap_redrivesAndTransitionsOutOfFailed() {
        long userId = seedCoachUser();
        UUID requestId = seedFailedRequest(userId, Instant.now().minus(Duration.ofHours(2)), 0);

        gdprErasureService.retryFailedErasures();

        GdprRequest reread = gdprRequestRepository.findById(requestId).orElseThrow();
        assertThat(reread.getStatus()).isEqualTo("COMPLETED");
        assertThat(reread.getRetryCount()).isEqualTo(1);
    }

    @Test
    @Timeout(30)
    void withinGraceWindow_leftAlone() {
        long userId = seedCoachUser();
        UUID requestId = seedFailedRequest(userId, Instant.now().minus(Duration.ofMinutes(5)), 0);

        gdprErasureService.retryFailedErasures();

        GdprRequest reread = gdprRequestRepository.findById(requestId).orElseThrow();
        assertThat(reread.getStatus()).as("still within the 1h grace window — must not be touched")
            .isEqualTo("FAILED");
        assertThat(reread.getRetryCount()).isZero();
    }

    @Test
    @Timeout(30)
    void atRetryCap_leftFailedAndNotRedriven() {
        long userId = seedCoachUser();
        UUID requestId = seedFailedRequest(userId, Instant.now().minus(Duration.ofHours(2)),
            GdprErasureService.MAX_ERASURE_RETRY_ATTEMPTS);

        gdprErasureService.retryFailedErasures();

        GdprRequest reread = gdprRequestRepository.findById(requestId).orElseThrow();
        assertThat(reread.getStatus()).as("at the retry cap — must not be re-driven again")
            .isEqualTo("FAILED");
        assertThat(reread.getRetryCount()).isEqualTo(GdprErasureService.MAX_ERASURE_RETRY_ATTEMPTS);
    }

    @Test
    @Timeout(30)
    void concurrentPendingRowForSameUser_dedupGuardSkips() {
        long userId = seedCoachUser();
        UUID failedRequestId = seedFailedRequest(userId, Instant.now().minus(Duration.ofHours(2)), 0);
        transactionTemplate.execute(status ->
            gdprRequestRepository.save(new GdprRequest(userId, "ERASURE", "PENDING")).getId());

        gdprErasureService.retryFailedErasures();

        GdprRequest reread = gdprRequestRepository.findById(failedRequestId).orElseThrow();
        assertThat(reread.getStatus())
            .as("a concurrent PENDING row for the same user must skip this candidate, not race it")
            .isEqualTo("FAILED");
        assertThat(reread.getRetryCount()).isZero();
    }
}
