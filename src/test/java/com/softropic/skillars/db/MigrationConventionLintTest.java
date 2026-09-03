package com.softropic.skillars.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
        List<MigrationLint.Violation> violations =
            MigrationLint.lint(MigrationLint.REAL_MIGRATIONS, MigrationLint.GRANDFATHER_BASELINE);

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
        List<MigrationLint.Violation> violations = MigrationLint.lint(FIXTURES.resolve("invalid"), 0);

        Set<MigrationLint.Rule> triggered = violations.stream()
            .map(MigrationLint.Violation::rule)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(MigrationLint.Rule.class)));

        assertThat(triggered)
            .as("every lint rule must have at least one failing fixture; got %s from %s", triggered, violations)
            .containsExactlyInAnyOrder(MigrationLint.Rule.values());
    }

    @Test
    @DisplayName("a version at or below the baseline is never flagged for a content rule")
    void baselineIsRespected() throws IOException {
        // Same invalid fixtures, but with a baseline above all of them → no content rule applies.
        List<MigrationLint.Violation> violations = MigrationLint.lint(FIXTURES.resolve("invalid"), 100_000);

        // UNPARSEABLE_VERSION is deliberately baseline-independent (code review, 3-layer run): it
        // reports a file whose version the lint cannot read, and "is it above the baseline?" is
        // exactly the question that cannot be answered for such a file. Grandfathering it would
        // reinstate the silent skip the rule exists to prevent. Every CONTENT rule stays gated.
        assertThat(violations)
            .as("only the baseline-independent naming rule may fire below the baseline, got: %s", violations)
            .allMatch(v -> v.rule() == MigrationLint.Rule.UNPARSEABLE_VERSION);
    }
}
