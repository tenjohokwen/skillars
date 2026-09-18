package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * skillars-deferred-122 AC9: real-database coverage for the {@code cleanup_failed_at} marker —
 * deliberately not a Mockito unit test, since the point of this AC is that a plain
 * {@code user.setCleanupFailedAt(...)} on a detached entity (the naive mechanism the story
 * explicitly calls out as a no-op) would compile and pass a mock-based test while persisting
 * nothing. This IT queries the column back from a real Postgres row.
 */
class UserCleanupFailedMarkerIT extends AbstractIntegrationTest {

    private static final long STUCK_USER_ID = 9600_000_001L;
    private static final long OK_USER_ID = 9600_000_002L;
    private static final String STUCK_LOGIN = "stuck.cleanup9600@skillars-test.com";
    private static final String OK_LOGIN = "ok.cleanup9600@skillars-test.com";

    @Autowired private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            // Both expired (created 10 days ago) and non-activated — otherwise identical, so only
            // the cleanup_failed_at marker distinguishes them in the query assertion below.
            insertExpiredUnactivatedUser(STUCK_USER_ID, STUCK_LOGIN);
            insertExpiredUnactivatedUser(OK_USER_ID, OK_LOGIN);
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?)", STUCK_USER_ID, OK_USER_ID);
            return null;
        });
    }

    @Test
    void markCleanupFailed_persistsColumnGenuinely_queryableBack() {
        Instant failedAt = Instant.now();

        userRepository.markCleanupFailed(STUCK_LOGIN, failedAt);

        Timestamp stored = jdbcTemplate.queryForObject(
            "SELECT cleanup_failed_at FROM main.\"user\" WHERE login = ?", Timestamp.class, STUCK_LOGIN);
        assertThat(stored).isNotNull();
        assertThat(stored.toInstant()).isCloseTo(failedAt, within(2, ChronoUnit.SECONDS));

        // The other user's row must be untouched.
        Timestamp untouched = jdbcTemplate.queryForObject(
            "SELECT cleanup_failed_at FROM main.\"user\" WHERE login = ?", Timestamp.class, OK_LOGIN);
        assertThat(untouched).isNull();
    }

    /**
     * skillars-deferred-122 AC9 Task: "a subsequent call to findExpiredUsers ... excludes that user
     * via the persisted marker". {@link UserAdminService#findExpiredUsers} is a thin wrapper around
     * this exact repository query (plus an in-run, page-scoped {@code failedLogins} filter this test
     * does not need); calling the repository method directly here proves the persisted-marker
     * exclusion at the query level — the layer the story identifies as the actual fix.
     * <p>
     * Code review 2026-09-18: {@code Pageable.unpaged()}, not a fixed {@code PageRequest.of(0, 100)}
     * window. {@code AbstractIntegrationTest} performs no truncation between ITs, so
     * activated=false/expired/unstamped rows accumulate in the shared Postgres container across the
     * whole suite; a 100-row page ordered by {@code id ASC} can silently push this test's own
     * ~9.6e9-id {@code OK_LOGIN} row out of the window once enough lower-id rows exist from earlier
     * ITs, failing on execution/accumulation order alone rather than on the exclusion behavior this
     * test actually verifies. Fetching unpaged asserts on the row directly instead of through a
     * window that can be evicted by state this test does not own.
     */
    @Test
    void findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNull_excludesMarkedUser() {
        userRepository.markCleanupFailed(STUCK_LOGIN, Instant.now());

        List<User> page = userRepository.findByActivatedFalseAndCreatedDateBeforeAndCleanupFailedAtIsNullOrderByIdAsc(
            Instant.now(), Pageable.unpaged());

        assertThat(page).extracting(User::getLogin)
            .as("the marked user must be excluded from every subsequent run's candidate set")
            .doesNotContain(STUCK_LOGIN);
        assertThat(page).extracting(User::getLogin)
            .as("an otherwise-identical unmarked user must still be selected")
            .contains(OK_LOGIN);
    }

    /**
     * skillars-deferred-122 AC9 code review 2026-09-18: real-database coverage for
     * {@link UserRepository#recordCleanupAttemptFailure} — the attempt-count replacement for the
     * one-shot marker, following the same "query it back, don't just assert the setter was called"
     * bar as {@link #markCleanupFailed_persistsColumnGenuinely_queryableBack} above.
     */
    @Test
    void recordCleanupAttemptFailure_persistsAttemptCountGenuinely_queryableBack() {
        Instant attemptedAt = Instant.now();

        userRepository.recordCleanupAttemptFailure(STUCK_LOGIN, 1, attemptedAt, "simulated failure");

        Integer attempts = jdbcTemplate.queryForObject(
            "SELECT cleanup_failed_attempts FROM main.\"user\" WHERE login = ?", Integer.class, STUCK_LOGIN);
        assertThat(attempts).isEqualTo(1);

        Timestamp lastAttempted = jdbcTemplate.queryForObject(
            "SELECT cleanup_last_attempted_at FROM main.\"user\" WHERE login = ?", Timestamp.class, STUCK_LOGIN);
        assertThat(lastAttempted.toInstant()).isCloseTo(attemptedAt, within(2, ChronoUnit.SECONDS));

        String lastError = jdbcTemplate.queryForObject(
            "SELECT cleanup_last_error FROM main.\"user\" WHERE login = ?", String.class, STUCK_LOGIN);
        assertThat(lastError).isEqualTo("simulated failure");

        // Recording an attempt alone (below CLEANUP_FAILURE_THRESHOLD) must not exclude the user —
        // that is markCleanupFailed's job, called separately once the threshold is crossed.
        Timestamp excludedAt = jdbcTemplate.queryForObject(
            "SELECT cleanup_failed_at FROM main.\"user\" WHERE login = ?", Timestamp.class, STUCK_LOGIN);
        assertThat(excludedAt).isNull();

        // The other user's row must be untouched.
        Integer untouchedAttempts = jdbcTemplate.queryForObject(
            "SELECT cleanup_failed_attempts FROM main.\"user\" WHERE login = ?", Integer.class, OK_LOGIN);
        assertThat(untouchedAttempts).isZero();
    }

    private void insertExpiredUnactivatedUser(long id, String login) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, last_modified_date, " +
            "request_id, session_id, status, dob, email, first_name, gender, lang_key, last_name, " +
            "iso2_country, phone, activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, 'INACTIVE', '1990-01-01', ?, 'Test', " +
            "'OTHER', 'en', 'User', 'DE', ?, false, false, ?, 'EMAIL', 'noop', false, NULL, 'UNVERIFIED')",
            id,
            Timestamp.from(Instant.now().minusSeconds(10L * 86400L)),
            Timestamp.from(Instant.now().minusSeconds(10L * 86400L)),
            login, "96" + (id % 100000000), login);
    }
}
