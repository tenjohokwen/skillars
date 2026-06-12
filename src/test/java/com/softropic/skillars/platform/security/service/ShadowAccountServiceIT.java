package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.security.SecurityIT;
import com.softropic.skillars.platform.security.contract.AgeTier;
import com.softropic.skillars.platform.security.contract.CreatePlayerProfileRequest;
import com.softropic.skillars.platform.security.contract.PlayerPosition;
import com.softropic.skillars.platform.security.contract.PlayerProfileResponse;
import com.softropic.skillars.platform.security.contract.exception.ShadowAccountException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "rate.limiting.enabled=false"
})
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class ShadowAccountServiceIT {

    private static final long PARENT_A_ID = 555000000000000001L;
    private static final long PARENT_B_ID = 555000000000000002L;

    @Autowired
    private ShadowAccountService shadowAccountService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.authority (id, name, status, created_by, created_date) " +
                "VALUES (101, 'ROLE_PARENT', 'ACTIVE', 'system', ?) " +
                "ON CONFLICT (name) DO NOTHING",
                Timestamp.from(Instant.now())
            );
            insertParentUser(PARENT_A_ID, "parent.a@test.com", "6571111001");
            insertParentUser(PARENT_B_ID, "parent.b@test.com", "6571111002");
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.parent_player_links");
            jdbcTemplate.execute("DELETE FROM main.player_profiles");
            jdbcTemplate.execute("DELETE FROM main.phone_otp_tokens");
            jdbcTemplate.execute("DELETE FROM main.email_verification_tokens");
            jdbcTemplate.execute("DELETE FROM main.user_authority");
            jdbcTemplate.execute("DELETE FROM main.\"user\"");
            jdbcTemplate.execute("DELETE FROM main.authority");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void createPlayerProfile_minor_storesConsentAndAgeTier() {
        // 7-year-old → U10 tier, minor requires consent
        LocalDate dob = LocalDate.now().minusYears(7).minusMonths(3);
        CreatePlayerProfileRequest req = new CreatePlayerProfileRequest(
            "Junior Player", dob, PlayerPosition.MIDFIELDER, true, "1.0"
        );

        PlayerProfileResponse response = shadowAccountService.createPlayerProfile(PARENT_A_ID, req);

        assertThat(response.ageTier()).isEqualTo(AgeTier.U10);
        assertThat(response.independentAccountAllowed()).isFalse();
        assertThat(response.name()).isEqualTo("Junior Player");

        // Verify consent stored in DB
        Instant consentAt = jdbcTemplate.queryForObject(
            "SELECT consent_accepted_at FROM main.player_profiles WHERE id = ?",
            Instant.class,
            response.id()
        );
        assertThat(consentAt).isNotNull();
    }

    @Test
    void createPlayerProfile_adult_noConsentRequired() {
        // 26-year-old → ADULT, no parental consent needed
        LocalDate dob = LocalDate.now().minusYears(26);
        CreatePlayerProfileRequest req = new CreatePlayerProfileRequest(
            "Adult Player", dob, PlayerPosition.FORWARD, null, null
        );

        PlayerProfileResponse response = shadowAccountService.createPlayerProfile(PARENT_A_ID, req);

        assertThat(response.ageTier()).isEqualTo(AgeTier.ADULT);
        assertThat(response.independentAccountAllowed()).isTrue();
    }

    @Test
    void createPlayerProfile_u10_independentAccountAllowedFalse() {
        LocalDate dob = LocalDate.now().minusYears(8);
        CreatePlayerProfileRequest req = new CreatePlayerProfileRequest(
            "U10 Player", dob, PlayerPosition.GOALKEEPER, true, "1.0"
        );

        PlayerProfileResponse response = shadowAccountService.createPlayerProfile(PARENT_A_ID, req);

        assertThat(response.ageTier()).isEqualTo(AgeTier.U10);
        assertThat(response.independentAccountAllowed()).isFalse();
    }

    @Test
    void createPlayerProfile_teen_ageTierIsAge13_17() {
        // 15-year-old → AGE_13_17
        LocalDate dob = LocalDate.now().minusYears(15);
        CreatePlayerProfileRequest req = new CreatePlayerProfileRequest(
            "Teen Player", dob, PlayerPosition.DEFENDER, true, "1.0"
        );

        PlayerProfileResponse response = shadowAccountService.createPlayerProfile(PARENT_A_ID, req);

        assertThat(response.ageTier()).isEqualTo(AgeTier.AGE_13_17);
        assertThat(response.independentAccountAllowed()).isTrue();
    }

    @Test
    void createPlayerProfile_minor_missingConsent_throws() {
        // 12-year-old minor, no consent → ShadowAccountException
        LocalDate dob = LocalDate.now().minusYears(12);
        CreatePlayerProfileRequest req = new CreatePlayerProfileRequest(
            "Minor No Consent", dob, PlayerPosition.MIDFIELDER, null, null
        );

        assertThatThrownBy(() -> shadowAccountService.createPlayerProfile(PARENT_A_ID, req))
            .isInstanceOf(ShadowAccountException.class)
            .satisfies(e -> assertThat(((ShadowAccountException) e).getErrorCode())
                .isEqualTo("security.parentConsentRequired"));
    }

    @Test
    void listPlayerProfiles_isolation_returnsOnlyOwnProfiles() {
        LocalDate adultDob = LocalDate.now().minusYears(20);

        shadowAccountService.createPlayerProfile(PARENT_A_ID, new CreatePlayerProfileRequest(
            "Player A1", adultDob, PlayerPosition.FORWARD, null, null
        ));
        shadowAccountService.createPlayerProfile(PARENT_A_ID, new CreatePlayerProfileRequest(
            "Player A2", adultDob, PlayerPosition.DEFENDER, null, null
        ));
        shadowAccountService.createPlayerProfile(PARENT_B_ID, new CreatePlayerProfileRequest(
            "Player B1", adultDob, PlayerPosition.GOALKEEPER, null, null
        ));

        List<PlayerProfileResponse> parentAProfiles = shadowAccountService.listPlayerProfiles(PARENT_A_ID);
        List<PlayerProfileResponse> parentBProfiles = shadowAccountService.listPlayerProfiles(PARENT_B_ID);

        assertThat(parentAProfiles).hasSize(2);
        assertThat(parentAProfiles).allMatch(p -> List.of("Player A1", "Player A2").contains(p.name()));

        assertThat(parentBProfiles).hasSize(1);
        assertThat(parentBProfiles.get(0).name()).isEqualTo("Player B1");
    }

    @Test
    void linkAdditionalParent_existingLink_throws() {
        LocalDate adultDob = LocalDate.now().minusYears(20);
        PlayerProfileResponse profile = shadowAccountService.createPlayerProfile(PARENT_A_ID,
            new CreatePlayerProfileRequest("Shared Player", adultDob, PlayerPosition.FORWARD, null, null)
        );

        // Parent B attempts to claim the same player — must be refused
        assertThatThrownBy(() -> shadowAccountService.linkAdditionalParent(PARENT_B_ID, profile.id()))
            .isInstanceOf(ShadowAccountException.class)
            .satisfies(e -> assertThat(((ShadowAccountException) e).getErrorCode())
                .isEqualTo("security.playerAlreadyHasParent"));
    }

    private void insertParentUser(long id, String email, String phone) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', 'Parent', 'CM', ?, " +
            "true, false, ?, 'EMAIL', '$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i', false, " +
            "'PARENT', 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()),
            Timestamp.from(Instant.now()),
            email,
            phone,
            email
        );
    }
}
