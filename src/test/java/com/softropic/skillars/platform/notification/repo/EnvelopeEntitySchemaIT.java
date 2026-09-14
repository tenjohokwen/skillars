package com.softropic.skillars.platform.notification.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-110 AC9, code review 2026-09-14 (patch) — a permanent regression guard for
 * {@code V136__pin_envelope_entity_schema.sql}, restoring the ad hoc {@code TmpEnvelopeSchemaDumpIT}
 * used to derive that migration in the first place.
 *
 * <p>What this actually guards against: {@code V136} is NOT a no-op — on any database where
 * {@code envelope_entity} does not already exist (every fresh CI Testcontainers Postgres included,
 * since Flyway runs before Hibernate's schema management), this migration is what creates the
 * table, and its two constraints ({@code send_id} uniqueness, the recipients FK) are deliberately
 * named to match Hibernate's own hash-based generated names — {@code
 * AbstractSchemaMigrator.applyUniqueKeys}/{@code applyForeignKeys} look up an existing constraint
 * by name, so an unnamed (PostgreSQL-default-named) constraint here would not match what Hibernate
 * expects to find and would grow a second, redundant constraint on every fresh boot. This test pins
 * "exactly one of each, under the exact expected name" so that regression can never land silently.
 */
class EnvelopeEntitySchemaIT extends AbstractIntegrationTest {

    @Test
    void v136Migration_ranSuccessfully_notSkipped() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select success from main.flyway_schema_history where version = '136'");

        assertThat(rows)
            .as("V136 must have a row in flyway_schema_history — it is expected to run on every "
                + "database, not be skipped as already-applied-elsewhere")
            .hasSize(1);
        assertThat(rows.get(0).get("success")).isEqualTo(true);
    }

    @Test
    void envelopeEntity_hasExactlyOneUniqueConstraint_underHibernatesExpectedName() {
        List<Map<String, Object>> uniqueConstraints = jdbcTemplate.queryForList(
            "select conname from pg_constraint "
                + "where conrelid = 'main.envelope_entity'::regclass and contype = 'u'");

        assertThat(uniqueConstraints)
            .as("exactly one UNIQUE constraint on envelope_entity — a second, differently-named one "
                + "would mean Hibernate's applyUniqueKeys did not recognise V136's constraint and "
                + "added a redundant duplicate on this fresh database")
            .hasSize(1);
        assertThat(uniqueConstraints.get(0).get("conname")).isEqualTo("uk428hhm4tjgrg8cy2092q025po");
    }

    @Test
    void envelopeEntity_hasExactlyOneCheckConstraintPerEnumColumn() {
        List<Map<String, Object>> checkConstraints = jdbcTemplate.queryForList(
            "select conname from pg_constraint "
                + "where conrelid = 'main.envelope_entity'::regclass and contype = 'c' order by conname");

        assertThat(checkConstraints)
            .extracting(row -> row.get("conname"))
            .as("exactly one CHECK per enum-backed column — proves V136's CHECK constraints exist "
                + "on a fresh database (Hibernate's ALTER-only path for an existing table never adds "
                + "a CHECK, only its CREATE TABLE path does — so a fresh database that lost these "
                + "would silently and permanently diverge from every existing one)")
            .containsExactly("envelope_entity_email_template_check", "envelope_entity_status_check");
    }

    @Test
    void envelopeEntityRecipients_hasExactlyOneForeignKey_underHibernatesExpectedName() {
        List<Map<String, Object>> foreignKeys = jdbcTemplate.queryForList(
            "select conname from pg_constraint "
                + "where conrelid = 'main.envelope_entity_recipients'::regclass and contype = 'f'");

        assertThat(foreignKeys)
            .as("exactly one FK on envelope_entity_recipients — a second, differently-named one would "
                + "mean Hibernate's applyForeignKeys did not recognise V136's constraint and added a "
                + "redundant duplicate on this fresh database")
            .hasSize(1);
        assertThat(foreignKeys.get(0).get("conname")).isEqualTo("fk89qpyuf6j5fgg7aorxxh8mqyn");
    }
}
