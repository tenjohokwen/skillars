package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.storage.BaseStorageIT;
import com.softropic.skillars.platform.development.contract.ReportGeneratedEvent;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Deferred-77 AC2: proves the real @Async @TransactionalEventListener wiring — a PENDING_UPLOAD
 * report becomes READY (with a real storage_key) only after onReportGenerated actually runs against
 * the live DB/S3-compatible backend, and listReports only ever surfaces it once READY. The
 * UPLOAD_FAILED branch is covered at the unit level (ReportGenerationServiceTest) where the S3 call
 * can be made to throw deterministically.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class ReportGenerationServiceIT extends BaseStorageIT {

    @Autowired private ReportGenerationService reportGenerationService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final long PARENT_USER_ID = 9576000001L;
    private static final long PLAYER_ID = 9576000010L;
    private static final UUID COACH_ID = UUID.randomUUID();

    @Test
    void onReportGenerated_realAsyncPipeline_transitionsPendingUploadToReadyAndListable() {
        UUID reportId = UUID.randomUUID();
        Instant generatedAt = Instant.now();

        transactionTemplate.execute(status -> {
            insertParentUser();
            insertPlayerProfile();
            jdbcTemplate.update(
                "INSERT INTO development.performance_reports " +
                "(id, coach_id, player_id, generated_at, next_steps, version, status) " +
                "VALUES (?, ?, ?, ?, 'Keep practicing.', 1, 'PENDING_UPLOAD')",
                reportId, COACH_ID, PLAYER_ID, Timestamp.from(generatedAt));

            eventPublisher.publishEvent(new ReportGeneratedEvent(
                reportId, PLAYER_ID, "Test Coach", "Test Player", generatedAt,
                "%PDF-1.4 test report bytes".getBytes()));
            return null;
        });

        await().atMost(5, SECONDS).until(() -> "READY".equals(reportStatus(reportId)));

        String storageKey = jdbcTemplate.queryForObject(
            "SELECT storage_key FROM development.performance_reports WHERE id = ?", String.class, reportId);
        assertThat(storageKey).isNotNull();

        assertThat(reportGenerationService.listReports(PLAYER_ID))
            .anySatisfy(r -> assertThat(r.id()).isEqualTo(reportId));

        int timelineCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM development.player_timeline_events WHERE reference_id = ?",
            Integer.class, reportId);
        assertThat(timelineCount).isEqualTo(1);
    }

    private String reportStatus(UUID reportId) {
        return jdbcTemplate.queryForObject(
            "SELECT status::text FROM development.performance_reports WHERE id = ?", String.class, reportId);
    }

    private void insertPlayerProfile() {
        jdbcTemplate.update(
            "INSERT INTO main.player_profiles " +
            "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
            "VALUES (?, 'Report Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
            PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
            PARENT_USER_ID, Timestamp.from(Instant.now()));
    }

    private void insertParentUser() {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1990-01-01', ?, 'Test', 'OTHER', 'en', 'User', 'DE', ?, " +
            "true, false, ?, 'EMAIL', ?, false, " +
            "'PARENT', 'BASIC_VERIFIED')",
            PARENT_USER_ID,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            "reportgen.parent@skillars-test.com",
            "69" + (PARENT_USER_ID % 100000000L),
            "reportgen.parent@skillars-test.com", "x"
        );
    }
}
