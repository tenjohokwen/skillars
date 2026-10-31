package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.security.contract.AgeVerificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S-27 (V222): the verification outcome against the real schema — a verified adult corrects the stored date of birth
 * on the user and the self-owned profile, a verified minor is locked out, replays change nothing, and GDPR erasure
 * removes the rows.
 */
class AgeVerificationServiceIT extends AbstractIntegrationTest {

    private static final long USER_ID = 888000000000000101L;
    private static final long PROFILE_ID = 888000000000000102L;
    private static final long MINOR_USER_ID = 888000000000000103L;

    @Autowired private AgeVerificationService service;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private String session;

    @BeforeEach
    void setUp() {
        session = "vs_it_" + UUID.randomUUID();
        transactionTemplate.execute(status -> {
            insertPlayerUser(USER_ID, "agever.adult@test.com", "6572220101", "1990-01-01");
            insertPlayerUser(MINOR_USER_ID, "agever.minor@test.com", "6572220102", "1990-01-01");
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles "
                    + "(id, name, date_of_birth, position, age_tier, user_id, independent_account_allowed, created_at, created_by) "
                    + "VALUES (?, 'Self Player', DATE '1990-01-01', 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                PROFILE_ID, USER_ID, Timestamp.from(Instant.now()));
            return null;
        });
    }

    private void insertSession(long userId, String sessionId, String status) {
        transactionTemplate.execute(s -> jdbcTemplate.update(
            "INSERT INTO main.age_verifications (id, user_id, stripe_session_id, status) VALUES (?, ?, ?, ?)",
            Math.abs(UUID.randomUUID().getMostSignificantBits()), userId, sessionId, status));
    }

    private String statusOf(String sessionId) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM main.age_verifications WHERE stripe_session_id = ?", String.class, sessionId);
    }

    @Test
    void verifiedAdult_correctsTheDeclaredDob_onUserAndProfile_andMarksTheUserVerified() {
        insertSession(USER_ID, session, "PROCESSING");
        LocalDate verified = LocalDate.of(1985, 5, 5);

        service.applyOutcome(session, AgeVerificationStatus.VERIFIED, verified);

        assertThat(statusOf(session)).isEqualTo("VERIFIED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT verified_tier FROM main.age_verifications WHERE stripe_session_id = ?", String.class, session))
            .isEqualTo("ADULT");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT dob FROM main.\"user\" WHERE id = ?", LocalDate.class, USER_ID)).isEqualTo(verified);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT date_of_birth FROM main.player_profiles WHERE id = ?", LocalDate.class, PROFILE_ID))
            .isEqualTo(verified);
        assertThat(service.isVerified(USER_ID)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT locked FROM main.\"user\" WHERE id = ?", Boolean.class, USER_ID)).isFalse();
    }

    @Test
    void verifiedMinor_locksTheAccount_andIsNotVerified() {
        insertSession(MINOR_USER_ID, session, "PROCESSING");

        service.applyOutcome(session, AgeVerificationStatus.VERIFIED, LocalDate.now().minusYears(15));

        assertThat(statusOf(session)).isEqualTo("UNDERAGE");
        assertThat(service.isVerified(MINOR_USER_ID)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT locked FROM main.\"user\" WHERE id = ?", Boolean.class, MINOR_USER_ID)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT security_session_invalidated_at IS NOT NULL FROM main.\"user\" WHERE id = ?",
            Boolean.class, MINOR_USER_ID)).isTrue();
    }

    @Test
    void aReplayedOrLateEvent_doesNotChangeAConcludedSession() {
        insertSession(USER_ID, session, "PROCESSING");
        service.applyOutcome(session, AgeVerificationStatus.VERIFIED, LocalDate.of(1990, 1, 1));

        service.applyOutcome(session, AgeVerificationStatus.REQUIRES_INPUT, null);
        service.applyOutcome(session, AgeVerificationStatus.VERIFIED, LocalDate.now().minusYears(10));

        assertThat(statusOf(session)).isEqualTo("VERIFIED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT dob FROM main.\"user\" WHERE id = ?", LocalDate.class, USER_ID))
            .isEqualTo(LocalDate.of(1990, 1, 1));
    }

    @Test
    void theStatusCheckConstraint_rejectsAnUnknownStatus() {
        assertThatThrownBy(() -> insertSession(USER_ID, session, "WHATEVER"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void erasure_deletesTheUsersRows() {
        insertSession(USER_ID, session, "REQUIRES_INPUT");

        transactionTemplate.executeWithoutResult(s -> service.eraseForUser(USER_ID));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM main.age_verifications WHERE user_id = ?", Integer.class, USER_ID)).isZero();
    }

    private void insertPlayerUser(long id, String email, String phone, String dob) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" "
                + "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, "
                + "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, "
                + "activated, locked, login, login_id_type, password_hash, otp_enabled, "
                + "skillars_role, verification_status) "
                + "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, "
                + "'ACTIVE', ?, ?, 'Self', 'OTHER', 'en', 'Player', 'CM', ?, "
                + "true, false, ?, 'EMAIL', '$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i', false, "
                + "'PLAYER', 'BASIC_VERIFIED')",
            id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), Date.valueOf(dob), email, phone, email);
    }
}
