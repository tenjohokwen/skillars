package com.softropic.skillars.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build-failing guard for {@code docs/deployment/migration-conventions.md} (skillars-deferred-90 AC10).
 *
 * <p>Named {@code *Test}, not {@code *IT}: it runs in the {@code test} phase, ahead of failsafe,
 * with no Spring context and no container. It reads {@code .sql} text only.
 *
 * <p>Fixtures live under {@code src/test/resources/migration-lint/{valid,invalid}/}, deliberately
 * NOT under {@code src/(main|test)/resources/db/migration/} (F19): Flyway is {@code enabled: true}
 * with {@code validateMigrationNaming: true} and {@code locations = classpath:db/migration}, so a
 * fixture placed there would be executed or fail naming validation.
 */
@DisplayName("New DB migrations must follow the rolling-deploy safety conventions")
class MigrationConventionLintTest {

    private static final Path FIXTURES = Path.of("src", "test", "resources", "migration-lint");

    @Test
    @DisplayName("real migrations above the V121 grandfather baseline have zero violations")
    void realMigrations_aboveBaseline_areClean() throws IOException {
        List<MigrationLint.Violation> violations = MigrationLint.lint(
            MigrationLint.REAL_MIGRATIONS, MigrationLint.GRANDFATHER_BASELINE, MigrationLint::gitKnownAtHead);

        assertThat(violations)
            .as("New migration(s) break docs/deployment/migration-conventions.md. "
                + "Fix the migration, or add a '-- migration-lint: allow-*' opt-out with a reason. Violations: %s",
                violations)
            .isEmpty();
    }

    @Test
    @DisplayName("the valid/ fixtures produce no violations")
    void validFixtures_areClean() throws IOException {
        List<MigrationLint.Violation> violations = MigrationLint.lint(FIXTURES.resolve("valid"), 0);

        assertThat(violations).as("valid fixtures should lint clean, got: %s", violations).isEmpty();
    }

    @Test
    @DisplayName("the invalid/ fixtures trigger every rule")
    void invalidFixtures_triggerEveryRule() throws IOException {
        // baseline 100: V50__backported_hazard.sql is at/below it (→ BACKPORT_BELOW_BASELINE fires
        // because knownAtHead says it is NOT in HEAD), while V900+ stay above it so every content
        // rule still applies. The R__ and Vx fixtures are version-less and unaffected by the baseline.
        List<MigrationLint.Violation> violations = MigrationLint.lint(
            FIXTURES.resolve("invalid"), 100,
            p -> !p.getFileName().toString().equals("V50__backported_hazard.sql"));

        Set<MigrationLint.Rule> triggered = violations.stream()
            .map(MigrationLint.Violation::rule)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(MigrationLint.Rule.class)));

        assertThat(triggered)
            .as("every lint rule must have at least one failing fixture; got %s from %s", triggered, violations)
            .containsExactlyInAnyOrder(MigrationLint.Rule.values());
    }

    @Test
    @DisplayName("a versioned content rule is never flagged at or below the baseline")
    void baselineIsRespected() throws IOException {
        // Same invalid fixtures, baseline above every versioned one → no versioned CONTENT rule
        // applies. Two rules are baseline-independent by nature and may still fire:
        //  - UNPARSEABLE_VERSION: the version cannot be read, so "above the baseline?" is unanswerable
        //    (code review, 3-layer run) — grandfathering it would reinstate the silent skip.
        //  - REPEATABLE_HAZARD: an R__ file has no version at all, so the baseline does not apply.
        List<MigrationLint.Violation> violations = MigrationLint.lint(FIXTURES.resolve("invalid"), 100_000);

        assertThat(violations)
            .as("only the baseline-independent rules may fire below the baseline, got: %s", violations)
            .allMatch(v -> v.rule() == MigrationLint.Rule.UNPARSEABLE_VERSION
                        || v.rule() == MigrationLint.Rule.REPEATABLE_HAZARD);
    }

    /**
     * skillars-deferred-91 code review: the rule-coverage test above only asserts that every rule
     * fires <em>somewhere</em>, so a newly-widened rule whose fixture is inert would still pass. These
     * assertions pin each new spelling to the specific fixture that must trigger it.
     */
    @Test
    @DisplayName("the widened rules trigger on each specific new fixture")
    void widenedRules_triggerOnTheirOwnFixtures() throws IOException {
        List<MigrationLint.Violation> violations = MigrationLint.lint(FIXTURES.resolve("invalid"), 100);

        assertThat(violations)
            .as("COLUMN is optional in PostgreSQL: 'ADD owner_id BIGINT REFERENCES …' is the same hazard")
            .anyMatch(v -> v.file().equals("V907__inline_fk_no_column_keyword.sql")
                        && v.rule() == MigrationLint.Rule.INLINE_FK_ADD_COLUMN);

        assertThat(violations)
            .as("the referenced column list is optional: 'REFERENCES main.\"user\"' defaults to its PK")
            .anyMatch(v -> v.file().equals("V908__inline_fk_no_column_list.sql")
                        && v.rule() == MigrationLint.Rule.INLINE_FK_ADD_COLUMN);

        assertThat(violations)
            .as("DROP CONSTRAINT without IF EXISTS is as unguarded as DROP TABLE/COLUMN/INDEX")
            .anyMatch(v -> v.file().equals("V909__drop_constraint_no_if_exists.sql")
                        && v.rule() == MigrationLint.Rule.DROP_WITHOUT_IF_EXISTS);
    }

    /**
     * The repeatable DROP rule must honour an {@code allow-unconditional-drop} opt-out, as
     * {@code lintRepeatable}'s javadoc has always claimed. Guards the fixture against silently
     * passing because no rule applied to it: the same file without its opt-out must fail.
     */
    @Test
    @DisplayName("an R__ repeatable can opt out of the unconditional-DROP rule, and only via the opt-out")
    void repeatableDropOptOut_isHonoured_andIsLoadBearing() throws IOException {
        assertThat(MigrationLint.lint(FIXTURES.resolve("valid"), 0))
            .as("R__repeatable_drop_optout.sql carries the opt-out and must lint clean")
            .isEmpty();

        Path tmp = Files.createTempDirectory("migration-lint-optout");
        try {
            String withOptOut = Files.readString(
                FIXTURES.resolve("valid").resolve("R__repeatable_drop_optout.sql"));
            String withoutOptOut = withOptOut
                .replace("migration-lint: allow-unconditional-drop", "note: opt-out deliberately removed");
            Files.writeString(tmp.resolve("R__repeatable_drop_optout.sql"), withoutOptOut);

            assertThat(MigrationLint.lint(tmp, 0))
                .as("without the opt-out the very same file must fail — otherwise the fixture proves nothing")
                .anyMatch(v -> v.rule() == MigrationLint.Rule.REPEATABLE_HAZARD
                            && v.detail().contains("allow-unconditional-drop"));
        } finally {
            try (var paths = Files.walk(tmp)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(f -> {
                    try {
                        Files.deleteIfExists(f);
                    } catch (IOException ignored) {
                        // best effort temp cleanup
                    }
                });
            }
        }
    }
}
