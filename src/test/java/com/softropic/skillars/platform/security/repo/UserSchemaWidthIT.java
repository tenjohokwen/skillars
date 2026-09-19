package com.softropic.skillars.platform.security.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-124 AC3. {@code main."user".skillars_role}/{@code verification_status} were
 * declared {@code varchar(20)} in {@code V138__baseline_schema.sql}, but every already-booted
 * environment silently carries {@code varchar(255)} (the same auto-DDL mechanism
 * {@code V145__user_aud_role_verification_status.sql}'s header documents for {@code main.user_aud}).
 * {@code V149__widen_user_skillars_role_verification_status.sql} reconciles the Flyway-declared width
 * to match. Modeled on {@code EnvelopeEntitySchemaIT}'s {@code information_schema.columns} pattern —
 * the established in-repo precedent for pinning a column's physical shape, not
 * {@code MigrationConventionLintTest} (a static-text lint that never opens a connection) or
 * {@code RescheduleResourceIT} (does not query {@code information_schema}).
 *
 * <p><strong>Honest scope, stated per this story's own Task 6.</strong> CI only ever builds a
 * Flyway-only database (Testcontainers Postgres, migrations applied from a clean schema, {@code
 * ddl-auto: none}). This test asserts the width {@code V149} produces on such a database — it proves
 * the Flyway-built width now matches what every already-Hibernate-patched environment independently
 * carries (per {@code V145}'s empirical finding), not that a fresh database now literally matches an
 * already-patched one side by side (this suite has no access to a pre-existing, already-patched
 * database to compare against).
 */
class UserSchemaWidthIT extends AbstractIntegrationTest {

    @Test
    void userTable_skillarsRoleAndVerificationStatus_areWidenedToVarchar255() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
            "select column_name, character_maximum_length from information_schema.columns "
                + "where table_schema = 'main' and table_name = 'user' "
                + "and column_name in ('skillars_role', 'verification_status') "
                + "order by column_name");

        assertThat(columns).hasSize(2);
        assertThat(columns.get(0).get("column_name")).isEqualTo("skillars_role");
        assertThat(columns.get(0).get("character_maximum_length"))
            .as("V149 must widen skillars_role to varchar(255), matching every already-booted "
                + "environment's auto-DDL-applied width")
            .isEqualTo(255);
        assertThat(columns.get(1).get("column_name")).isEqualTo("verification_status");
        assertThat(columns.get(1).get("character_maximum_length"))
            .as("V149 must widen verification_status to varchar(255), matching every already-booted "
                + "environment's auto-DDL-applied width")
            .isEqualTo(255);
    }

    /**
     * skillars-deferred-124 AC3: the expected finding, confirmed by direct inspection of
     * {@code V138__baseline_schema.sql} before writing this test — no {@code CHECK} constraint on
     * either column exists in any environment (Hibernate's only possible action against an
     * already-existing column is {@code alter column ... set data type}, which carries no
     * {@code CHECK} clause; only column-creation DDL, as {@code V145} document for the different
     * {@code user_aud} case, produces one). Adding one here would create a new divergence in the
     * opposite direction, which is exactly what this AC exists to avoid — this test pins "none exists"
     * as a regression guard against a future migration adding one by mistake.
     *
     * <p>skillars-deferred-124 code review 2026-09-19 (Patch), noted honestly: this specific test
     * cannot fail for any reason related to {@code V149}'s own diff — {@code V149} does not touch
     * {@code CHECK} constraints at all, so this passes identically whether {@code V149} exists,
     * regressed, or was never written. Its value is purely forward-looking (catching a future
     * migration that adds a {@code CHECK} here by mistake), not a validation of this story's own
     * change — that validation is {@link #userTable_skillarsRoleAndVerificationStatus_areWidenedToVarchar255}
     * above.
     */
    @Test
    void userTable_skillarsRoleAndVerificationStatus_haveNoCheckConstraint() {
        List<Map<String, Object>> checkConstraints = jdbcTemplate.queryForList(
            "select conname, pg_get_constraintdef(oid) as definition from pg_constraint "
                + "where conrelid = 'main.\"user\"'::regclass and contype = 'c'");

        assertThat(checkConstraints)
            .as("no CHECK constraint should exist on main.\"user\" for skillars_role/verification_status "
                + "— adding one on a Flyway-only database while already-booted environments have none "
                + "would be a new divergence in the opposite direction")
            .filteredOn(row -> String.valueOf(row.get("definition")).contains("skillars_role")
                || String.valueOf(row.get("definition")).contains("verification_status"))
            .isEmpty();
    }
}
