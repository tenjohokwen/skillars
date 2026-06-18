package com.softropic.skillars.platform.session.api;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.HttpClientErrorException;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "rate.limiting.enabled=false",
    "allowed.clients=testClientId"
})
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class HomeworkResourceIT extends BaseSessionIT {

    @MockitoBean
    VideoProviderAdapter videoProviderAdapter;

    private static final long PARENT_ID       = 9540000001L;
    private static final long WRONG_PARENT_ID = 9540000002L;
    private static final long PLAYER_ID       = 9540000010L;
    private static final long COACH_USER_ID   = 9540000020L;

    private static final String PARENT_EMAIL       = "parent.homework@skillars-test.com";
    private static final String WRONG_PARENT_EMAIL = "wrongparent.homework@skillars-test.com";

    private UUID coachProfileId;
    private UUID drillId;
    private UUID assignmentId;
    private UUID packId;

    @BeforeEach
    void setUp() {
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        transactionTemplate.execute(status -> {
            insertAuthority(9540, "ROLE_PARENT");
            insertAuthority(9541, "ROLE_COACH");

            // Parent user
            insertUser(PARENT_ID, PARENT_EMAIL, passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                PARENT_ID
            );

            // Wrong parent
            insertUser(WRONG_PARENT_ID, WRONG_PARENT_EMAIL, passwordHash, "PARENT");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_PARENT')) ON CONFLICT DO NOTHING",
                WRONG_PARENT_ID
            );

            // Player owned by PARENT
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Homework Player', ?, 'MIDFIELDER', 'AGE_10_12', ?, false, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(10)),
                PARENT_ID, Timestamp.from(Instant.now())
            );

            // Coach
            insertUser(COACH_USER_ID, "coach.homework@skillars-test.com", passwordHash, "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.user_authority (user_id, authority_id) " +
                "VALUES (?, (SELECT id FROM main.authority WHERE name = 'ROLE_COACH')) ON CONFLICT DO NOTHING",
                COACH_USER_ID
            );
            coachProfileId = insertCoachProfile(COACH_USER_ID);
            insertSubscription(coachProfileId, "INSTRUCTOR");

            // Active session pack
            packId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO booking.session_packs_purchased " +
                "(id, parent_id, player_id, coach_id, session_count, credits_remaining, status, purchased_at, expires_at) " +
                "VALUES (?, ?, ?, ?, 5, 5, 'ACTIVE', ?, ?)",
                packId, PARENT_ID, PLAYER_ID, coachProfileId,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now().plus(180, ChronoUnit.DAYS))
            );

            // Drill
            drillId = UUID.randomUUID();
            insertDrill(drillId, "Homework Drill", "COACH", coachProfileId, "ACTIVE");

            // Homework assignment
            assignmentId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO session.homework_assignments " +
                "(id, booking_id, player_id, coach_id, drill_id, pack_id, assigned_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                assignmentId, UUID.randomUUID(), PLAYER_ID, coachProfileId, drillId, packId,
                Timestamp.from(Instant.now())
            );

            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM session.homework_completions WHERE player_id = ?", PLAYER_ID);
            jdbcTemplate.update("DELETE FROM session.homework_assignments WHERE player_id = ?", PLAYER_ID);
            jdbcTemplate.update("DELETE FROM booking.session_packs_purchased WHERE parent_id = ?", PARENT_ID);
            jdbcTemplate.update("DELETE FROM session.drills WHERE id = ?", drillId);
            jdbcTemplate.update("DELETE FROM main.player_profiles WHERE id = ?", PLAYER_ID);
            jdbcTemplate.update("DELETE FROM marketplace.coach_subscriptions WHERE coach_id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachProfileId);
            jdbcTemplate.update("DELETE FROM main.user_authority WHERE user_id IN (?, ?, ?)",
                                PARENT_ID,
                                WRONG_PARENT_ID,
                                COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id IN (?, ?, ?)",
                                PARENT_ID,
                                WRONG_PARENT_ID,
                                COACH_USER_ID);
            jdbcTemplate.update("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void getLockerRoomDrills_parentOwner_returns200WithDrills() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/session/players/" + PLAYER_ID + "/homework",
            HttpMethod.GET, null, authenticatedHeaders(cookies), List.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().hasSize(1);
    }

    @Test
    void getLockerRoomDrills_wrongParent_returns403() {
        String cookies = loginAndGetCookies(WRONG_PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/session/players/" + PLAYER_ID + "/homework",
            HttpMethod.GET, null, authenticatedHeaders(cookies), List.class
        )).isInstanceOf(HttpClientErrorException.class)
          .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void getLockerRoomDrills_noAssignments_returns200EmptyList() {
        transactionTemplate.execute(status ->
            jdbcTemplate.update("DELETE FROM session.homework_assignments WHERE player_id = ?", PLAYER_ID));
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/session/players/" + PLAYER_ID + "/homework",
            HttpMethod.GET, null, authenticatedHeaders(cookies), List.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void getLockerRoomDrills_packExhausted_returns200EmptyList() {
        transactionTemplate.execute(status ->
            jdbcTemplate.update("UPDATE booking.session_packs_purchased SET status = 'EXHAUSTED', credits_remaining = 0 WHERE id = ?", packId));
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<List> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/session/players/" + PLAYER_ID + "/homework",
            HttpMethod.GET, null, authenticatedHeaders(cookies), List.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void markComplete_validAssignment_returns200() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(
            baseUrl() + "/api/session/homework/" + assignmentId + "/complete",
            HttpMethod.POST, null, authenticatedHeaders(cookies), Void.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        boolean exists = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM session.homework_completions WHERE assignment_id = ?)",
            Boolean.class, assignmentId
        ));
        assertThat(exists).isTrue();
    }

    @Test
    void markComplete_twice_returns200Idempotent() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        String url = baseUrl() + "/api/session/homework/" + assignmentId + "/complete";
        httpTestClient.makeHttpRequest(url, HttpMethod.POST, null, authenticatedHeaders(cookies), Void.class);
        ResponseEntity<Void> response = httpTestClient.makeHttpRequest(url, HttpMethod.POST, null, authenticatedHeaders(cookies), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM session.homework_completions WHERE assignment_id = ?",
            Integer.class, assignmentId
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    void markComplete_wrongParent_returns404() {
        String cookies = loginAndGetCookies(WRONG_PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/session/homework/" + assignmentId + "/complete",
            HttpMethod.POST, null, authenticatedHeaders(cookies), Void.class
        )).isInstanceOf(HttpClientErrorException.class)
          .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void markComplete_unknownAssignment_returns404() {
        String cookies = loginAndGetCookies(PARENT_EMAIL);
        assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
            baseUrl() + "/api/session/homework/" + UUID.randomUUID() + "/complete",
            HttpMethod.POST, null, authenticatedHeaders(cookies), Void.class
        )).isInstanceOf(HttpClientErrorException.class)
          .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
