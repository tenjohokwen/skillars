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
 * Build-failing guard for {@code docs/deployment/migration-conventions.md} (skillars-deferred-90 AC10,
 * widened by skillars-deferred-91 AC7 and skillars-deferred-92 AC7–AC11).
 *
 * <p>Named {@code *Test}, not {@code *IT}: it runs in the {@code test} phase, ahead of failsafe,
 * with no Spring context and no container. It reads {@code .sql} text only.
 *
 * <p>Fixtures live under {@code src/test/resources/migration-lint/{valid,invalid}/}, deliberately
 * NOT under {@code src/(main|test)/resources/db/migration/} (F19): Flyway is {@code enabled: true}
 * with {@code validateMigrationNaming: true} and {@code locations = classpath:db/migration}, so a
 * fixture placed there would be executed or fail naming validation.
 *
 * <h2>The fixture set has two baselines too</h2>
 *
 * The real migration tree grandfathers the skillars-deferred-92 rules below {@code V127}, because
 * {@code V122}–{@code V127} are already applied and Flyway checksums whole files — they cannot be
 * edited to carry the markers those rules demand. The fixtures mirror that split at
 * {@link #FIXTURE_DEFERRED_92_BASELINE}: {@code V800}–{@code V808} predate the new rules exactly as
 * {@code V122}–{@code V127} do, and {@code V809}+ are bound by them. Mirroring it here means the
 * two-baseline mechanism is itself exercised rather than only described.
 */
@DisplayName("New DB migrations must follow the rolling-deploy safety conventions")
class MigrationConventionLintTest {

    private static final Path FIXTURES = Path.of("src", "test", "resources", "migration-lint");

    /**
     * Fixture stand-in for {@code src/main/java} + {@code src/main/resources}. Pointing
     * {@link MigrationLint.Rule#DROP_WITHOUT_PRIOR_RELEASE_PREP}'s reference scan at a fixture corpus
     * rather than the real source tree keeps the fixture assertions deterministic — otherwise adding
     * an unrelated class that happens to mention {@code widget} would flip a fixture's verdict.
     */
    private static final List<Path> FIXTURE_SOURCES = List.of(FIXTURES.resolve("fixture-src"));

    /** Fixtures at or below this predate the skillars-deferred-92 rules, as V122–V127 do for real. */
    private static final int FIXTURE_DEFERRED_92_BASELINE = 808;

    private static List<MigrationLint.Violation> lintFixtures(String dir, int baseline) throws IOException {
        return lintFixtures(dir, baseline, FIXTURE_DEFERRED_92_BASELINE, MigrationLint.ALL_KNOWN_AT_HEAD);
    }

    private static List<MigrationLint.Violation> lintFixtures(
            String dir, int baseline, int deferred92Baseline,
            java.util.function.Predicate<Path> knownAtHead) throws IOException {
        return MigrationLint.lint(
            FIXTURES.resolve(dir), baseline, deferred92Baseline, knownAtHead, FIXTURE_SOURCES);
    }

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
        List<MigrationLint.Violation> violations = lintFixtures("valid", 0);

        assertThat(violations).as("valid fixtures should lint clean, got: %s", violations).isEmpty();
    }

    @Test
    @DisplayName("the invalid/ fixtures trigger every rule")
    void invalidFixtures_triggerEveryRule() throws IOException {
        // baseline 100: V50__backported_hazard.sql is at/below it (→ BACKPORT_BELOW_BASELINE fires
        // because knownAtHead says it is NOT in HEAD), while V900+ stay above it so every content
        // rule still applies. The R__ and Vx fixtures are version-less and unaffected by the baseline.
        List<MigrationLint.Violation> violations = lintFixtures("invalid", 100,
            FIXTURE_DEFERRED_92_BASELINE,
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
        List<MigrationLint.Violation> violations = lintFixtures("invalid", 100_000, 100_000,
            MigrationLint.ALL_KNOWN_AT_HEAD);

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
        List<MigrationLint.Violation> violations = lintFixtures("invalid", 100);

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

        // --- skillars-deferred-92 ------------------------------------------------------------

        assertThat(violations)
            .as("AC7: a DROP COLUMN with no drop-prepared-in marker — skillars-11-3 D2's exact defect")
            .anyMatch(v -> v.file().equals("V910__drop_column_no_prepared_marker.sql")
                        && v.rule() == MigrationLint.Rule.DROP_WITHOUT_PRIOR_RELEASE_PREP);

        assertThat(violations)
            .as("AC7.2: the marker is load-bearing — it must fail when a reader is still live, or it "
                + "is decoration and the rule overstates what it guarantees")
            .anyMatch(v -> v.file().equals("V911__drop_column_marker_but_live_reference.sql")
                        && v.rule() == MigrationLint.Rule.DROP_WITHOUT_PRIOR_RELEASE_PREP
                        && v.detail().contains("obsolete_reading"));

        assertThat(violations)
            .as("AC8: lock-taking DDL with no SET lock_timeout")
            .anyMatch(v -> v.file().equals("V912__missing_lock_timeout.sql")
                        && v.rule() == MigrationLint.Rule.MISSING_LOCK_TIMEOUT);

        assertThat(violations)
            .as("AC9: an UPDATE with no bounding WHERE")
            .anyMatch(v -> v.file().equals("V913__unbatched_dml.sql")
                        && v.rule() == MigrationLint.Rule.UNBATCHED_DML);

        assertThat(violations)
            .as("AC10.3: a platform_config seed that still hand-picks the primary key")
            .anyMatch(v -> v.file().equals("V914__platform_config_explicit_id.sql")
                        && v.rule() == MigrationLint.Rule.PLATFORM_CONFIG_EXPLICIT_ID);

        assertThat(violations)
            .as("AC11.1: the SECOND ADD CONSTRAINT validates; the old rule passed because NOT VALID "
                + "appeared somewhere in the statement")
            .anyMatch(v -> v.file().equals("V915__second_constraint_validates.sql")
                        && v.rule() == MigrationLint.Rule.VALIDATING_CONSTRAINT
                        && v.detail().contains("chk_widget_label"));

        assertThat(violations)
            .as("AC11.2: an opt-out must not leak to a later statement it says nothing about")
            .anyMatch(v -> v.file().equals("V916__optout_leaks_to_later_statement.sql")
                        && v.rule() == MigrationLint.Rule.BLOCKING_INDEX);
    }

    /**
     * AC11.1 is only meaningful if the balanced-paren clause split does not also break the
     * <em>correct</em> spelling. {@code V812} carries two constraints, each with its own
     * {@code NOT VALID}, and both {@code CHECK} bodies contain commas — the exact shape a naive split
     * on {@code ,} would tear in half, producing two clause fragments that each look constraint-less.
     */
    @Test
    @DisplayName("AC11.1: per-clause evaluation still passes a correctly written multi-constraint statement")
    void perClauseNotValid_doesNotFalselyFlagTheCorrectShape() throws IOException {
        assertThat(lintFixtures("valid", 0))
            .as("V812 declares both constraints NOT VALID with commas inside the CHECK bodies")
            .noneMatch(v -> v.file().equals("V812__per_clause_not_valid.sql"));
    }

    /**
     * AC11.2 the other way round: two blocking indexes, each carrying its own opt-out, must pass.
     * Together with the {@code V916} assertion above this pins the rule from both sides — a marker
     * covers the statement it precedes and only that one.
     */
    @Test
    @DisplayName("AC11.2: a per-statement opt-out covers exactly the statement it precedes")
    void statementScopedOptOut_coversItsOwnStatement() throws IOException {
        assertThat(lintFixtures("valid", 0))
            .as("V813 gives each of its two indexes its own allow-blocking-index marker")
            .noneMatch(v -> v.file().equals("V813__optout_per_statement.sql"));
    }

    /**
     * The AC7 reference scan must be provably load-bearing in both directions. {@code V911} fails
     * <em>because</em> a reader is live; the same file with the identifier renamed to one that appears
     * nowhere in the corpus must pass, otherwise the rule is really just "did you write a marker".
     */
    @Test
    @DisplayName("AC7.2: the reference scan is what fails V911, not merely the marker's presence")
    void dropReferenceScan_isLoadBearing() throws IOException {
        Path tmp = Files.createTempDirectory("migration-lint-dropscan");
        try {
            String withLiveReader = Files.readString(
                FIXTURES.resolve("invalid").resolve("V911__drop_column_marker_but_live_reference.sql"));
            Files.writeString(tmp.resolve("V911__drop_column_marker_but_live_reference.sql"),
                withLiveReader.replace("obsolete_reading", "column_nothing_reads"));

            assertThat(MigrationLint.lint(tmp, 100, FIXTURE_DEFERRED_92_BASELINE,
                    MigrationLint.ALL_KNOWN_AT_HEAD, FIXTURE_SOURCES))
                .as("with no live reader the identical file must pass — so V911's failure is the scan "
                    + "finding a real reference, not the rule firing on every DROP that carries a marker")
                .isEmpty();
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * The repeatable DROP rule must honour an {@code allow-unconditional-drop} opt-out, as
     * {@code lintRepeatable}'s javadoc has always claimed. Guards the fixture against silently
     * passing because no rule applied to it: the same file without its opt-out must fail.
     */
    @Test
    @DisplayName("an R__ repeatable can opt out of the unconditional-DROP rule, and only via the opt-out")
    void repeatableDropOptOut_isHonoured_andIsLoadBearing() throws IOException {
        assertThat(lintFixtures("valid", 0))
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
            deleteRecursively(tmp);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
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
