package com.softropic.skillars.platform.notification.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-110 AC9, code review 2026-09-14 (patch) — a permanent regression guard
 * originally written for {@code V136__pin_envelope_entity_schema.sql}, restoring the ad hoc
 * {@code TmpEnvelopeSchemaDumpIT} used to derive that migration in the first place.
 *
 * <p>skillars-deferred-112 rebaseline note: {@code V136} (and every other pre-baseline migration,
 * {@code V02}–{@code V137}) was physically deleted and squashed into {@code
 * V138__baseline_schema.sql} — see {@code docs/deployment/migration-rebaseline.md}. A fresh
 * database no longer has a {@code flyway_schema_history} row for version 136 at all, so the test
 * that used to assert that row's existence was removed here rather than updated to reference
 * {@code V138}; {@code V138} is machine-generated and grandfathered from {@code MigrationLint}
 * precisely because it isn't a hand-written migration whose "did it run" needs pinning the way
 * {@code V136} did.
 *
 * <p>What the remaining tests below still guard against: on any database where {@code
 * envelope_entity} does not already exist (every fresh CI Testcontainers Postgres included, since
 * Flyway runs before Hibernate's schema management), {@code V138} is what creates the table, and
 * its two constraints ({@code send_id} uniqueness, the recipients FK) are deliberately named to
 * match Hibernate's own hash-based generated names — {@code
 * AbstractSchemaMigrator.applyUniqueKeys}/{@code applyForeignKeys} look up an existing constraint
 * by name, so an unnamed (PostgreSQL-default-named) constraint here would not match what Hibernate
 * expects to find and would grow a second, redundant constraint on every fresh boot. These tests
 * pin "exactly one of each, under the exact expected name" so that regression can never land
 * silently.
 */
class EnvelopeEntitySchemaIT extends AbstractIntegrationTest {

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

    /**
     * skillars-deferred-111 AC12, code review 2026-09-14 (owner decision) —
     * {@code V137__envelope_entity_recipients_composite_pk.sql}'s composite primary key. Hibernate's
     * {@code @ElementCollection} mapping carries no {@code @Id} for this table, so Hibernate's
     * auto-DDL can never create, duplicate, or drop this constraint — it exists purely because the
     * migration adds it, which is exactly why a permanent regression guard belongs here rather than
     * being left to Hibernate-tracking assertions like the sibling tests in this class.
     */
    @Test
    void envelopeEntityRecipients_hasCompositePrimaryKeyOnEnvelopeEntityIdAndEmail() {
        List<Map<String, Object>> primaryKeys = jdbcTemplate.queryForList(
            "select conname, pg_get_constraintdef(oid) as definition from pg_constraint "
                + "where conrelid = 'main.envelope_entity_recipients'::regclass and contype = 'p'");

        assertThat(primaryKeys)
            .as("exactly one PRIMARY KEY on envelope_entity_recipients")
            .hasSize(1);
        assertThat(primaryKeys.get(0).get("conname")).isEqualTo("envelope_entity_recipients_pkey");
        assertThat((String) primaryKeys.get(0).get("definition"))
            .as("must cover both key columns, in the order the migration declared them")
            .isEqualTo("PRIMARY KEY (envelope_entity_id, email)");
    }

    @Test
    void envelopeEntityRecipients_emailColumn_isNotNull() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
            "select is_nullable from information_schema.columns "
                + "where table_schema = 'main' and table_name = 'envelope_entity_recipients' and column_name = 'email'");

        assertThat(columns).hasSize(1);
        assertThat(columns.get(0).get("is_nullable"))
            .as("a composite PRIMARY KEY requires every key column NOT NULL")
            .isEqualTo("NO");
    }
}
