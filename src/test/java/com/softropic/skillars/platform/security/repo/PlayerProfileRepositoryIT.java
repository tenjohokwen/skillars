package com.softropic.skillars.platform.security.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

// Story Deferred-77 AC4: findParentEmailByPlayerId's single-query JOIN replaces the old
// getParentIdByPlayerId + userRepository.findById(parentId) pair, which threw
// IllegalArgumentException for self-registered adult players (parentId == null).
class PlayerProfileRepositoryIT extends AbstractIntegrationTest {

    @Autowired private PlayerProfileRepository playerProfileRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final long PARENT_USER_ID = 9579000001L;
    private static final long ADULT_PLAYER_USER_ID = 9579000002L;
    private static final long PLAYER_WITH_PARENT_ID = 9579000010L;
    private static final long SELF_REGISTERED_ADULT_PLAYER_ID = 9579000020L;

    @Test
    void findParentEmailByPlayerId_playerWithParent_returnsParentEmail() {
        transactionTemplate.execute(status -> {
            insertUser(PARENT_USER_ID, "playerfk.parent@skillars-test.com");
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Parented Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                PLAYER_WITH_PARENT_ID, Date.valueOf(LocalDate.now().minusYears(10)),
                PARENT_USER_ID, Timestamp.from(Instant.now()));
            return null;
        });

        assertThat(playerProfileRepository.findParentEmailByPlayerId(PLAYER_WITH_PARENT_ID))
            .contains("playerfk.parent@skillars-test.com");
    }

    @Test
    void findParentEmailByPlayerId_selfRegisteredAdultNoParent_returnsEmpty() {
        transactionTemplate.execute(status -> {
            insertUser(ADULT_PLAYER_USER_ID, "playerfk.adult@skillars-test.com");
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, user_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Self Registered Adult', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                SELF_REGISTERED_ADULT_PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(20)),
                ADULT_PLAYER_USER_ID, Timestamp.from(Instant.now()));
            return null;
        });

        assertThat(playerProfileRepository.findParentEmailByPlayerId(SELF_REGISTERED_ADULT_PLAYER_ID))
            .isEmpty();
    }

    @Test
    void findParentEmailByPlayerId_playerDoesNotExist_returnsEmpty() {
        assertThat(playerProfileRepository.findParentEmailByPlayerId(999999999L)).isEmpty();
    }

    private void insertUser(long id, String email) {
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
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email,
            "69" + (id % 100000000L),
            email, "x"
        );
    }
}
