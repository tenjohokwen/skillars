package com.softropic.skillars.platform.security.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.infrastructure.validation.PhoneNumber;
import com.softropic.skillars.infrastructure.validation.Provider;
import com.softropic.skillars.platform.security.SecurityIT;
import com.softropic.skillars.platform.security.contract.SkillarsRole;
import com.softropic.skillars.platform.security.contract.SkillarsVerificationStatus;

import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-123 AC5. {@code User.skillarsRole}/{@code verificationStatus} are {@code @Audited}
 * (no {@code @NotAudited}) on a class annotated {@code @Audited}, but neither field has a matching
 * column in {@code main.user_aud} per {@code V138__baseline_schema.sql}'s own DDL — a schema/annotation
 * mismatch that, read from the migration file alone, looks like it should throw at insert time.
 *
 * <p><strong>Investigation finding (empirical, not guessed):</strong> it does not throw. At boot,
 * Hibernate/Envers issued {@code alter table ... add column} DDL for both audit columns (with a
 * {@code CHECK} constraint mirroring the enum's current values) — confirmed by raising
 * {@code org.hibernate.SQL} to DEBUG and observing the literal {@code alter table if exists
 * main.user_aud add column skillars_role varchar(255) check (...)} statements at startup, and separately
 * confirming {@code main.flyway_schema_history} contains no migration that adds them. Both columns
 * round-trip real values correctly on every insert.
 *
 * <p><strong>Cause, corrected by the {@code skillars-deferred-123} code review (2026-09-18).</strong>
 * This Javadoc originally attributed that DDL to Hibernate acting "independent of the {@code ddl-auto}
 * setting". It was not. The cause was {@code spring.jpa.generate-ddl: true} in {@code application.yaml},
 * which defeated the adjacent {@code ddl-auto: none} and left every profile — including this test
 * profile, which inherits it — running with effective {@code hibernate.hbm2ddl.auto=update}. That is
 * also why no integration test had ever caught Flyway/entity schema drift: Hibernate patched the schema
 * before any assertion ran. The property has been removed, so this IT now genuinely exercises a
 * Flyway-only schema, and {@code V145__user_aud_role_verification_status.sql} is what supplies these two
 * columns. See the replacement comment in {@code application.yaml} for the decompiled mechanism.
 *
 * <p>This test pins the round-trip as a permanent regression check, not just a one-off investigation.
 */
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class UserEnversAuditGapIT extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistingUserWithSkillarsRoleAndVerificationStatus_auditRowRoundTripsBothFields() {
        final User user = Instancio.create(User.class);
        user.setId(null);
        user.setPersistentTokens(Collections.emptySet());
        user.setLangKey("en");
        user.setEmail("envers-gap-" + System.nanoTime() + "@yahoo.com");
        user.setPassword("sixtysixtysixtysixtysixtysixtysixtysixtysixtysixtysixtysixty");
        user.setLogin("envers-gap-" + System.nanoTime() + "@yahoo.com");
        user.setDateOfBirth(LocalDate.of(1978, 3, 19));
        user.setPhone(new PhoneNumber("01794443151", Provider.MTN, "DE"));
        final Address address = user.getAddresses().stream().findFirst().orElse(null);
        if (address != null) {
            address.setName("abcdAddress");
            user.setAddresses(Set.of(address));
        }
        user.setAuthorities(Set.of());
        user.setSkillarsRole(SkillarsRole.COACH);
        user.setVerificationStatus(SkillarsVerificationStatus.BASIC_VERIFIED);

        final User saved = userRepo.saveAndFlush(user);

        List<Map<String, Object>> auditRows = jdbcTemplate.queryForList(
            "SELECT * FROM main.user_aud WHERE id = ?", saved.getId());

        assertThat(auditRows).as("an audit row must exist for the initial insert revision").hasSize(1);
        Map<String, Object> row = auditRows.get(0);
        assertThat(row).as("skillars_role round-trips into the audit row")
            .containsEntry("skillars_role", "COACH");
        assertThat(row).as("verification_status round-trips into the audit row")
            .containsEntry("verification_status", "BASIC_VERIFIED");
    }
}
