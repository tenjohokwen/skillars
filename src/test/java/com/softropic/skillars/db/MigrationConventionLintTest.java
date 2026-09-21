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
 * <h2>The fixture set still exercises two baselines, even though the real tree's have converged</h2>
 *
 * Before skillars-deferred-112, the real migration tree grandfathered the skillars-deferred-92 rules
 * below {@code V127} specifically, because {@code V122}–{@code V127} were already applied and Flyway
 * checksums whole files — they could not be edited to carry the markers those rules demand.
 * skillars-deferred-112's squash deleted that whole pre-baseline band (formerly {@code V02}–{@code
 * V137}) rather than editing it, so in the real tree {@link MigrationLint#GRANDFATHER_BASELINE} and
 * {@link MigrationLint#DEFERRED_92_BASELINE} now sit at the same value, {@code 139} — there is no
 * longer a gap between them to bridge. The two-baseline <em>mechanism</em> is still real — the two
 * constants gate mechanically distinct rule bands and could diverge again in the future — so the
 * fixtures deliberately keep exercising it at two different values ({@link #FIXTURE_DEFERRED_92_BASELINE} = {@code 808},
 * unchanged by skillars-deferred-112): {@code V800}–{@code V808} predate the deferred-92 rules,
 * {@code V809}+ are bound by them, independent of whatever the real tree's constants happen to be.
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

    /**
     * skillars-deferred-125 AC4: a separate, fixture-level boundary for
     * {@link MigrationLint.Rule#SESSION_SCOPED_LOCK_TIMEOUT} — mirrors {@link
     * #FIXTURE_DEFERRED_92_BASELINE}'s own reasoning (a distinct numbering space from the real
     * migration directory's {@link MigrationLint#SESSION_SCOPED_LOCK_TIMEOUT_BASELINE} = 150), set
     * high enough that every existing {@code valid/} fixture using a plain {@code SET lock_timeout}
     * (V809–V820, {@code R__repeatable_drop_optout}) stays exempt rather than newly flagged.
     */
    private static final int FIXTURE_SESSION_SCOPED_LOCK_TIMEOUT_BASELINE = 840;

    private static List<MigrationLint.Violation> lintFixtures(String dir, int baseline) throws IOException {
        return lintFixtures(dir, baseline, FIXTURE_DEFERRED_92_BASELINE, MigrationLint.ALL_KNOWN_AT_HEAD);
    }

    private static List<MigrationLint.Violation> lintFixtures(
            String dir, int baseline, int deferred92Baseline,
            java.util.function.Predicate<Path> knownAtHead) throws IOException {
        return lintFixtures(dir, baseline, deferred92Baseline,
            FIXTURE_SESSION_SCOPED_LOCK_TIMEOUT_BASELINE, knownAtHead);
    }

    private static List<MigrationLint.Violation> lintFixtures(
            String dir, int baseline, int deferred92Baseline, int sessionScopedLockTimeoutBaseline,
            java.util.function.Predicate<Path> knownAtHead) throws IOException {
        return MigrationLint.lint(FIXTURES.resolve(dir), baseline, deferred92Baseline,
            sessionScopedLockTimeoutBaseline, knownAtHead, FIXTURE_SOURCES);
    }

    @Test
    @DisplayName("real migrations above the V139 grandfather baseline have zero violations")
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
        // applies. An R__ file has no version at all, so baseline never governs it — including for
        // the skillars-deferred-92 rules (code review: repeatables re-run on every checksum change,
        // making them at least as much a rolling-deploy hazard as a versioned migration, so those
        // four rules bind them unconditionally, same as REPEATABLE_HAZARD always has).
        // UNPARSEABLE_VERSION is baseline-independent for a different reason: the version cannot be
        // read, so "above the baseline?" is unanswerable (code review, 3-layer run) — grandfathering
        // it would reinstate the silent skip.
        List<MigrationLint.Violation> violations = lintFixtures("invalid", 100_000, 100_000,
            MigrationLint.ALL_KNOWN_AT_HEAD);

        assertThat(violations)
            .as("only a baseline-independent rule, or one sourced from an R__ file (which has no "
                + "version for any baseline to govern), may fire below the baseline, got: %s", violations)
            .allMatch(v -> v.rule() == MigrationLint.Rule.UNPARSEABLE_VERSION
                        || v.file().startsWith("R__"));
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

        // --- skillars-deferred-92 code review (COLUMN-optional / value / marker evasions) ----

        assertThat(violations)
            .as("code review: COLUMN is optional on a DROP clause — 'DROP col' is 'DROP COLUMN col'")
            .anyMatch(v -> v.file().equals("V917__drop_column_no_column_keyword.sql")
                        && v.rule() == MigrationLint.Rule.DROP_WITHOUT_IF_EXISTS);

        assertThat(violations)
            .as("code review: COLUMN is also optional on an ADD clause, for MISSING_LOCK_TIMEOUT too")
            .anyMatch(v -> v.file().equals("V918__missing_lock_timeout_no_column_keyword.sql")
                        && v.rule() == MigrationLint.Rule.MISSING_LOCK_TIMEOUT);

        assertThat(violations)
            .as("code review: SET lock_timeout = 0 is Postgres's own 'wait forever' and must not satisfy the rule")
            .anyMatch(v -> v.file().equals("V919__lock_timeout_zero.sql")
                        && v.rule() == MigrationLint.Rule.MISSING_LOCK_TIMEOUT);

        assertThat(violations)
            .as("code review: TRUNCATE is an unbatched full-table write UPDATE/DELETE patterns never recognised")
            .anyMatch(v -> v.file().equals("V920__truncate_unbatched.sql")
                        && v.rule() == MigrationLint.Rule.UNBATCHED_DML);

        assertThat(violations)
            .as("code review: no explicit column list still supplies id positionally")
            .anyMatch(v -> v.file().equals("V921__platform_config_no_column_list.sql")
                        && v.rule() == MigrationLint.Rule.PLATFORM_CONFIG_EXPLICIT_ID);

        assertThat(violations)
            .as("code review: a quoted schema/table spelling evaded the original pattern")
            .anyMatch(v -> v.file().equals("V922__platform_config_quoted_schema.sql")
                        && v.rule() == MigrationLint.Rule.PLATFORM_CONFIG_EXPLICIT_ID);

        assertThat(violations)
            .as("code review: a multi-clause DROP used to have only the FIRST clause reference-scanned")
            .anyMatch(v -> v.file().equals("V923__multi_clause_drop_second_offender.sql")
                        && v.rule() == MigrationLint.Rule.DROP_WITHOUT_PRIOR_RELEASE_PREP
                        && v.detail().contains("obsolete_reading"));

        assertThat(violations)
            .as("code review: a migration in a subdirectory of the Flyway location used to be invisible "
                + "(Files.list is non-recursive; Flyway's own scan is)")
            .anyMatch(v -> v.file().equals("V924__nested_migration_unlinted.sql")
                        && v.rule() == MigrationLint.Rule.DROP_WITHOUT_PRIOR_RELEASE_PREP);

        assertThat(violations)
            .as("code review: opt-out text inside a STRING LITERAL must not be honoured as a real marker")
            .anyMatch(v -> v.file().equals("V925__marker_in_string_literal_not_honoured.sql")
                        && v.rule() == MigrationLint.Rule.UNBATCHED_DML);

        assertThat(violations)
            .as("code review: a '--' inside a string literal must not swallow the rest of the statement "
                + "(comment-stripping is now literal-aware) — this firing at all is the proof")
            .anyMatch(v -> v.file().equals("V926__comment_stripping_string_literal_awareness.sql")
                        && v.rule() == MigrationLint.Rule.DROP_WITHOUT_PRIOR_RELEASE_PREP);

        assertThat(violations)
            .as("code review: a subquery's own WHERE must not satisfy the OUTER statement's bounding check")
            .anyMatch(v -> v.file().equals("V927__subquery_where_not_top_level.sql")
                        && v.rule() == MigrationLint.Rule.UNBATCHED_DML);

        assertThat(violations)
            .as("code review: a leading CTE must not hide the DELETE it feeds from UNBATCHED_DML")
            .anyMatch(v -> v.file().equals("V928__cte_led_delete_unbatched.sql")
                        && v.rule() == MigrationLint.Rule.UNBATCHED_DML);

        assertThat(violations)
            .as("code review: repeatables are now bound by the skillars-deferred-92 rules too, "
                + "unconditionally (no version to gate a baseline on)")
            .anyMatch(v -> v.file().equals("R__bad_repeatable.sql")
                        && v.rule() == MigrationLint.Rule.MISSING_LOCK_TIMEOUT);
    }

    /**
     * skillars-deferred-92 code review: every fixture test above passes an explicit
     * {@code deferred92Baseline} ({@link #FIXTURE_DEFERRED_92_BASELINE}), so none of them exercise
     * the PRODUCTION {@link MigrationLint#DEFERRED_92_BASELINE} constant itself — changing it to any
     * larger value would silently disable all four skillars-deferred-92 rules for the real migration
     * tree without a single test failing. This test calls the two-argument {@code lint(dir, baseline)}
     * overload, which is the one that resolves {@code deferred92Baseline} from the constant rather
     * than from an argument, against a migration one version above the real constant's value.
     */
    @Test
    @DisplayName("code review: the production DEFERRED_92_BASELINE constant is itself load-bearing")
    void productionDeferred92Baseline_isLoadBearing() throws IOException {
        Path tmp = Files.createTempDirectory("migration-lint-real-baseline");
        try {
            String justAboveBaseline = "V" + (MigrationLint.DEFERRED_92_BASELINE + 1)
                + "__real_baseline_probe.sql";
            Files.writeString(tmp.resolve(justAboveBaseline),
                "-- header\nALTER TABLE main.widget ADD COLUMN evasion_probe VARCHAR(10);\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(tmp, 0);

            assertThat(violations)
                .as("a migration one version above the real DEFERRED_92_BASELINE must trigger "
                    + "MISSING_LOCK_TIMEOUT when resolved from the constant itself, not from a "
                    + "test-supplied override: %s", violations)
                .anyMatch(v -> v.rule() == MigrationLint.Rule.MISSING_LOCK_TIMEOUT);
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * A decimal minor version in the skillars-deferred-92 baseline band (major component equal to
     * the baseline) must still be bound by the rules — code review: comparing only the major
     * component let {@code V808.1} compare {@code 808 <= 808} and evade every rule, even though it is
     * a later migration than {@code V808} and was never applied before the rules existed.
     */
    @Test
    @DisplayName("code review: a decimal minor version in the baseline band still binds the deferred-92 rules")
    void decimalMinorVersion_stillBindsDeferred92Rules() throws IOException {
        Path tmp = Files.createTempDirectory("migration-lint-decimal-version");
        try {
            Files.writeString(tmp.resolve("V808.1__decimal_version_evasion.sql"),
                "-- header\nALTER TABLE main.widget ADD COLUMN evasion_probe VARCHAR(10);\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(
                tmp, 100, FIXTURE_DEFERRED_92_BASELINE, MigrationLint.ALL_KNOWN_AT_HEAD, FIXTURE_SOURCES);

            assertThat(violations)
                .as("V808.1 is newer than the V808 baseline and must still trigger MISSING_LOCK_TIMEOUT")
                .anyMatch(v -> v.rule() == MigrationLint.Rule.MISSING_LOCK_TIMEOUT);
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * The AC7 reference scan must be word-boundary aware, not a bare substring match — code review:
     * {@code body.contains("id")} matched inside words like {@code Invalid}, making every
     * {@code id}-class column name unusable and training authors to reach for the blanket opt-out.
     * The source file mentions {@code widget} as a real standalone token (so the table half of the
     * qualified match is satisfied) but {@code id} only as a substring of {@code Invalid} — never as
     * its own token — so a bare-substring scan would (wrongly) still flag it as a live reference.
     */
    @Test
    @DisplayName("code review: the reference scan matches at a word boundary, not as a bare substring")
    void referenceScan_isWordBoundaryAware_notSubstring() throws IOException {
        Path tmpSrc = Files.createTempDirectory("migration-lint-word-boundary-src");
        Path tmpMig = Files.createTempDirectory("migration-lint-word-boundary-mig");
        try {
            Files.writeString(tmpSrc.resolve("NoiseOnly.java"),
                "package fixtures;\ninterface NoiseOnly { boolean isInvalidRequest(String widget); }\n");
            Files.writeString(tmpMig.resolve("V1__drop_generic_id.sql"),
                "-- migration-lint: drop-prepared-in: V0\n"
                    + "SET lock_timeout = '5s';\n"
                    + "ALTER TABLE main.widget DROP COLUMN IF EXISTS id;\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(
                tmpMig, 0, 0, MigrationLint.ALL_KNOWN_AT_HEAD, List.of(tmpSrc));

            assertThat(violations)
                .as("'id' inside 'Invalid' must not count as a live reference to the dropped id column: %s",
                    violations)
                .isEmpty();
        } finally {
            deleteRecursively(tmpSrc);
            deleteRecursively(tmpMig);
        }
    }

    /**
     * skillars-deferred-99 AC7 — the {@code \b} anchoring must hold for <em>underscore-adjacent</em>
     * names: a dropped {@code player_session_id} must not be seen as "referenced" by a file that only
     * mentions {@code player_session_id_new} (a different column), because {@code _} is a word
     * character so there is no boundary between {@code id} and {@code _new}. The control below shows
     * the same file with the bare token does fire, so this proves the anchoring, not just absence.
     */
    @Test
    @DisplayName("AC7: an underscore-adjacent longer name is not a match for the dropped identifier")
    void referenceScan_underscoreAdjacentName_isNotAMatch() throws IOException {
        // Two distinct source dirs — MigrationLint memoises the corpus per root list, so re-reading a
        // mutated dir would return the stale first read (skillars-deferred-99 AC7 corpus cache).
        Path tmpSrcLonger = Files.createTempDirectory("migration-lint-underscore-src-a");
        Path tmpSrcBare = Files.createTempDirectory("migration-lint-underscore-src-b");
        Path tmpMig = Files.createTempDirectory("migration-lint-underscore-mig");
        try {
            Files.writeString(tmpMig.resolve("V1__drop_player_session_id.sql"),
                "-- migration-lint: drop-prepared-in: V0\n"
                    + "SET lock_timeout = '5s';\n"
                    + "ALTER TABLE main.player_session DROP COLUMN IF EXISTS player_session_id;\n");

            // Only the longer, distinct column name — never the dropped token on its own.
            Files.writeString(tmpSrcLonger.resolve("Reader.java"),
                "package fixtures;\n"
                    + "class Reader { String q = \"SELECT player_session_id_new FROM main.player_session\"; }\n");
            assertThat(MigrationLint.lint(tmpMig, 0, 0, MigrationLint.ALL_KNOWN_AT_HEAD, List.of(tmpSrcLonger)))
                .as("player_session_id_new must not count as a live reference to player_session_id")
                .isEmpty();

            // Control: the bare token in the same context must be caught.
            Files.writeString(tmpSrcBare.resolve("Reader.java"),
                "package fixtures;\n"
                    + "class Reader { String q = \"SELECT player_session_id FROM main.player_session\"; }\n");
            assertThat(MigrationLint.lint(tmpMig, 0, 0, MigrationLint.ALL_KNOWN_AT_HEAD, List.of(tmpSrcBare)))
                .as("the bare dropped token in a live reader must still fire")
                .anyMatch(v -> v.rule() == MigrationLint.Rule.DROP_WITHOUT_PRIOR_RELEASE_PREP);
        } finally {
            deleteRecursively(tmpSrcLonger);
            deleteRecursively(tmpSrcBare);
            deleteRecursively(tmpMig);
        }
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

            // skillars-deferred-125 AC4: V911's own plain SET lock_timeout is incidental to what this
            // test actually exercises (the drop-reference scan) — pass a baseline above V911 itself so
            // SESSION_SCOPED_LOCK_TIMEOUT stays out of this assertion's way, mirroring how this test
            // already passes deferred92Baseline=FIXTURE_DEFERRED_92_BASELINE rather than the real one.
            assertThat(MigrationLint.lint(tmp, 100, FIXTURE_DEFERRED_92_BASELINE,
                    911, MigrationLint.ALL_KNOWN_AT_HEAD, FIXTURE_SOURCES))
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

    // ---------------------------------------------------------------------------------------------
    // skillars-deferred-125 AC4 — SESSION_SCOPED_LOCK_TIMEOUT
    // ---------------------------------------------------------------------------------------------

    /**
     * A synthetic migration above {@link MigrationLint#SESSION_SCOPED_LOCK_TIMEOUT_BASELINE} using a
     * plain {@code SET lock_timeout} must trigger the new rule.
     */
    @Test
    @DisplayName("a plain SET lock_timeout above the boundary triggers SESSION_SCOPED_LOCK_TIMEOUT")
    void sessionScopedLockTimeout_aboveBoundary_triggersViolation() throws IOException {
        Path tmp = Files.createTempDirectory("migration-lint-session-scoped-above");
        try {
            Files.writeString(
                tmp.resolve("V" + (MigrationLint.SESSION_SCOPED_LOCK_TIMEOUT_BASELINE + 1) + "__probe.sql"),
                "-- header\nSET lock_timeout = '5s';\n\nALTER TABLE main.widget ADD COLUMN probe VARCHAR(10);\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(tmp, 0, 0,
                MigrationLint.SESSION_SCOPED_LOCK_TIMEOUT_BASELINE, MigrationLint.ALL_KNOWN_AT_HEAD,
                FIXTURE_SOURCES);

            assertThat(violations)
                .as("a plain SET above the boundary must trigger SESSION_SCOPED_LOCK_TIMEOUT: %s", violations)
                .anyMatch(v -> v.rule() == MigrationLint.Rule.SESSION_SCOPED_LOCK_TIMEOUT);
        } finally {
            deleteRecursively(tmp);
        }
    }

    /** The same content, at or below the boundary, must be grandfathered — no violation. */
    @Test
    @DisplayName("the same plain SET lock_timeout at the boundary is grandfathered")
    void sessionScopedLockTimeout_atBoundary_isGrandfathered() throws IOException {
        Path tmp = Files.createTempDirectory("migration-lint-session-scoped-at");
        try {
            Files.writeString(
                tmp.resolve("V" + MigrationLint.SESSION_SCOPED_LOCK_TIMEOUT_BASELINE + "__probe.sql"),
                "-- header\nSET lock_timeout = '5s';\n\nALTER TABLE main.widget ADD COLUMN probe VARCHAR(10);\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(tmp, 0, 0,
                MigrationLint.SESSION_SCOPED_LOCK_TIMEOUT_BASELINE, MigrationLint.ALL_KNOWN_AT_HEAD,
                FIXTURE_SOURCES);

            assertThat(violations)
                .as("a migration AT the boundary must be grandfathered — mirrors every already-shipped "
                    + "V140-V150 migration this rule must not retroactively flag: %s", violations)
                .noneMatch(v -> v.rule() == MigrationLint.Rule.SESSION_SCOPED_LOCK_TIMEOUT);
        } finally {
            deleteRecursively(tmp);
        }
    }

    /** {@code SET LOCAL} above the boundary must not trigger the rule — it is the compliant form. */
    @Test
    @DisplayName("SET LOCAL lock_timeout above the boundary does not trigger SESSION_SCOPED_LOCK_TIMEOUT")
    void sessionScopedLockTimeout_setLocalAboveBoundary_doesNotTrigger() throws IOException {
        Path tmp = Files.createTempDirectory("migration-lint-session-scoped-local");
        try {
            Files.writeString(
                tmp.resolve("V" + (MigrationLint.SESSION_SCOPED_LOCK_TIMEOUT_BASELINE + 1) + "__probe.sql"),
                "-- header\nSET LOCAL lock_timeout = '5s';\n\nALTER TABLE main.widget ADD COLUMN probe VARCHAR(10);\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(tmp, 0, 0,
                MigrationLint.SESSION_SCOPED_LOCK_TIMEOUT_BASELINE, MigrationLint.ALL_KNOWN_AT_HEAD,
                FIXTURE_SOURCES);

            assertThat(violations)
                .as("SET LOCAL must never trigger SESSION_SCOPED_LOCK_TIMEOUT: %s", violations)
                .noneMatch(v -> v.rule() == MigrationLint.Rule.SESSION_SCOPED_LOCK_TIMEOUT);
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * A migration carrying the {@code executeInTransaction=false} sidecar has no enclosing
     * transaction for {@code SET LOCAL} to bind to, so a plain {@code SET} is the ONLY legal form for
     * it — the rule must exempt it entirely rather than flag a shape the sidecar pattern structurally
     * requires (story-review.md finding: an unconditional rule would silently defeat this repo's own
     * "confirmed working" non-transactional backfill pattern).
     */
    @Test
    @DisplayName("a sidecar-exempt (executeInTransaction=false) migration's plain SET does not trigger the rule")
    void sessionScopedLockTimeout_sidecarExemptMigration_doesNotTrigger() throws IOException {
        Path tmp = Files.createTempDirectory("migration-lint-session-scoped-sidecar");
        try {
            String version = "V" + (MigrationLint.SESSION_SCOPED_LOCK_TIMEOUT_BASELINE + 1);
            Files.writeString(tmp.resolve(version + "__probe.sql"),
                "-- header\nSET lock_timeout = '5s';\n\nUPDATE main.widget SET probe = 1 WHERE id = 1;\n");
            Files.writeString(tmp.resolve(version + "__probe.sql.conf"), "executeInTransaction=false\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(tmp, 0, 0,
                MigrationLint.SESSION_SCOPED_LOCK_TIMEOUT_BASELINE, MigrationLint.ALL_KNOWN_AT_HEAD,
                FIXTURE_SOURCES);

            assertThat(violations)
                .as("a sidecar-exempt migration's plain SET is the only legal form and must not be "
                    + "flagged: %s", violations)
                .noneMatch(v -> v.rule() == MigrationLint.Rule.SESSION_SCOPED_LOCK_TIMEOUT);
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * story-review.md Task 4: {@code isLockTimeoutBoundedAt} must stop treating a {@code SET LOCAL} as
     * bounding statements after an explicit mid-file {@code COMMIT} — its real scope ends there, unlike
     * a plain session-scoped {@code SET}, which survives a {@code COMMIT}. Without this fix,
     * {@code MISSING_LOCK_TIMEOUT} would have a false negative on the second {@code ALTER TABLE} below
     * once {@code SET LOCAL} becomes the norm this AC's own rule pushes new migrations toward.
     */
    @Test
    @DisplayName("SET LOCAL does not bound a lock-taking statement after an explicit mid-file COMMIT")
    void missingLockTimeout_setLocalDoesNotSurviveAnExplicitCommit() throws IOException {
        // /bmad-code-review fix (2026-09-21): NOT a sidecar (no .conf) — this is testing the plain
        // COMMIT/ROLLBACK-boundary behavior of an ordinary transactional migration, where SET LOCAL
        // genuinely does take effect. Mixing this with a non-transactional sidecar (as the original
        // version of this test did) conflated two different scopes: in a real sidecar, SET LOCAL never
        // takes effect at all, from the very first statement — see
        // missingLockTimeout_setLocalIsIgnoredInsideANonTransactionalSidecar below for that case.
        Path tmp = Files.createTempDirectory("migration-lint-set-local-commit-boundary");
        try {
            Files.writeString(tmp.resolve("V1000__set_local_commit_boundary.sql"),
                "-- header\n"
                    + "SET LOCAL lock_timeout = '5s';\n"
                    + "ALTER TABLE main.widget ADD COLUMN first_probe VARCHAR(10);\n"
                    + "COMMIT;\n"
                    + "ALTER TABLE main.widget ADD COLUMN second_probe VARCHAR(10);\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(tmp, 0);

            assertThat(violations)
                .as("the first ALTER TABLE, before the COMMIT, is genuinely bounded by the SET LOCAL "
                    + "above it: %s", violations)
                .noneMatch(v -> v.rule() == MigrationLint.Rule.MISSING_LOCK_TIMEOUT
                    && v.detail().contains("first_probe"));
            assertThat(violations)
                .as("the second ALTER TABLE, after the explicit COMMIT, must no longer be considered "
                    + "bounded by the SET LOCAL above it — its real scope ended at that COMMIT: %s",
                    violations)
                .anyMatch(v -> v.rule() == MigrationLint.Rule.MISSING_LOCK_TIMEOUT
                    && v.detail().contains("second_probe"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * The counterpart to the test above: a plain, session-scoped {@code SET} DOES survive an explicit
     * {@code COMMIT} (matching real PostgreSQL session semantics) — the fix must not over-correct into
     * treating every {@code SET}/{@code SET LOCAL} identically around a transaction boundary.
     */
    @Test
    @DisplayName("a plain session-scoped SET still bounds a statement after an explicit mid-file COMMIT")
    void missingLockTimeout_plainSetSurvivesAnExplicitCommit() throws IOException {
        Path tmp = Files.createTempDirectory("migration-lint-plain-set-commit-boundary");
        try {
            Files.writeString(tmp.resolve("V1000__plain_set_commit_boundary.sql"),
                "-- header\n"
                    + "SET lock_timeout = '5s';\n"
                    + "ALTER TABLE main.widget ADD COLUMN first_probe VARCHAR(10);\n"
                    + "COMMIT;\n"
                    + "ALTER TABLE main.widget ADD COLUMN second_probe VARCHAR(10);\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(tmp, 0);

            assertThat(violations)
                .as("a plain SET is session-scoped and genuinely does survive a COMMIT — the second "
                    + "ALTER TABLE must still be considered bounded: %s", violations)
                .noneMatch(v -> v.rule() == MigrationLint.Rule.MISSING_LOCK_TIMEOUT
                    && v.detail().contains("second_probe"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * /bmad-code-review fix (2026-09-21): the original version of the two tests above ran inside a
     * non-transactional sidecar, which silently mixed two different bugs together — this isolates the
     * sidecar case on its own. A plain SET BEFORE a SET LOCAL, then a COMMIT, must still leave the
     * plain SET's bound in effect afterward — the original isLockTimeoutBoundedAt collapsed session and
     * local scope into one flag pair and would have incorrectly cleared it at the COMMIT.
     */
    @Test
    @DisplayName("a plain SET before a SET LOCAL still bounds a statement after the SET LOCAL's COMMIT ends")
    void missingLockTimeout_plainSetUnderASetLocalSurvivesTheLocalsCommit() throws IOException {
        Path tmp = Files.createTempDirectory("migration-lint-plain-under-local-commit-boundary");
        try {
            Files.writeString(tmp.resolve("V1000__plain_under_local_commit_boundary.sql"),
                "-- header\n"
                    + "SET lock_timeout = '5s';\n"
                    + "SET LOCAL lock_timeout = '1s';\n"
                    + "ALTER TABLE main.widget ADD COLUMN first_probe VARCHAR(10);\n"
                    + "COMMIT;\n"
                    + "ALTER TABLE main.widget ADD COLUMN second_probe VARCHAR(10);\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(tmp, 0);

            assertThat(violations)
                .as("the earlier plain SET's session-scoped bound must survive the SET LOCAL's own "
                    + "COMMIT-bounded scope ending — the second ALTER TABLE is still bounded: %s",
                    violations)
                .noneMatch(v -> v.rule() == MigrationLint.Rule.MISSING_LOCK_TIMEOUT
                    && v.detail().contains("second_probe"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * /bmad-code-review fix (2026-09-21): SET LOCAL is a documented Postgres no-op with no enclosing
     * transaction — in a genuine {@code executeInTransaction=false} sidecar migration it never takes
     * effect at all, from the very first statement. The original fix for the COMMIT/ROLLBACK boundary
     * did not know about the sidecar case, so a SET LOCAL there would have silently satisfied
     * MISSING_LOCK_TIMEOUT for a statement that is, in real Postgres, genuinely unbounded.
     */
    @Test
    @DisplayName("SET LOCAL is ignored entirely inside a non-transactional (executeInTransaction=false) sidecar")
    void missingLockTimeout_setLocalIsIgnoredInsideANonTransactionalSidecar() throws IOException {
        Path tmp = Files.createTempDirectory("migration-lint-set-local-sidecar-noop");
        try {
            Files.writeString(tmp.resolve("V1000__set_local_sidecar_noop.sql.conf"),
                "executeInTransaction=false\n");
            Files.writeString(tmp.resolve("V1000__set_local_sidecar_noop.sql"),
                "-- header\n"
                    + "SET LOCAL lock_timeout = '5s';\n"
                    + "ALTER TABLE main.widget ADD COLUMN sidecar_probe VARCHAR(10);\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(tmp, 0);

            assertThat(violations)
                .as("SET LOCAL never took effect in this non-transactional sidecar — the statement is "
                    + "genuinely unbounded and must be flagged, even though it is the very first "
                    + "statement in the file (no COMMIT/ROLLBACK boundary needed to disprove it): %s",
                    violations)
                .anyMatch(v -> v.rule() == MigrationLint.Rule.MISSING_LOCK_TIMEOUT
                    && v.detail().contains("sidecar_probe"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * /bmad-code-review fix (2026-09-21): {@code SET SESSION lock_timeout} is valid PostgreSQL (SESSION
     * is the explicit spelling of the scope a bare SET already defaults to) but evaded the original
     * LOCK_TIMEOUT_DIRECTIVE/SESSION_SCOPED_SET_LOCK_TIMEOUT patterns entirely, which only recognised a
     * bare SET or SET LOCAL.
     */
    @Test
    @DisplayName("SET SESSION lock_timeout bounds a statement and still triggers SESSION_SCOPED_LOCK_TIMEOUT")
    void setSessionLockTimeout_boundsAndIsSessionScoped() throws IOException {
        Path tmp = Files.createTempDirectory("migration-lint-set-session-lock-timeout");
        try {
            Files.writeString(tmp.resolve("V1000__set_session_lock_timeout.sql"),
                "-- header\n"
                    + "SET SESSION lock_timeout = '5s';\n"
                    + "ALTER TABLE main.widget ADD COLUMN session_probe VARCHAR(10);\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(tmp, 0);

            assertThat(violations)
                .as("SET SESSION lock_timeout genuinely bounds the statement — must not trip "
                    + "MISSING_LOCK_TIMEOUT: %s", violations)
                .noneMatch(v -> v.rule() == MigrationLint.Rule.MISSING_LOCK_TIMEOUT);
            assertThat(violations)
                .as("SET SESSION is session-scoped exactly like a bare SET — must still trip "
                    + "SESSION_SCOPED_LOCK_TIMEOUT: %s", violations)
                .anyMatch(v -> v.rule() == MigrationLint.Rule.SESSION_SCOPED_LOCK_TIMEOUT);
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * /bmad-code-review fix (2026-09-21): Postgres's own {@code lock_timeout} default is {@code 0}
     * (wait forever) — {@code SET LOCAL lock_timeout = DEFAULT} is therefore exactly as unbounded as an
     * explicit {@code 0}, but the original digit-only isZeroTimeout check found no leading digit in
     * "DEFAULT" and treated it as bounded.
     */
    @Test
    @DisplayName("SET LOCAL lock_timeout = DEFAULT is unbounded, not a genuine bound")
    void lockTimeoutDefault_isTreatedAsUnbounded() throws IOException {
        Path tmp = Files.createTempDirectory("migration-lint-lock-timeout-default");
        try {
            Files.writeString(tmp.resolve("V1000__lock_timeout_default.sql"),
                "-- header\n"
                    + "SET LOCAL lock_timeout = DEFAULT;\n"
                    + "ALTER TABLE main.widget ADD COLUMN default_probe VARCHAR(10);\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(tmp, 0);

            assertThat(violations)
                .as("DEFAULT resolves to 0 (unbounded) in real Postgres — must trip "
                    + "MISSING_LOCK_TIMEOUT: %s", violations)
                .anyMatch(v -> v.rule() == MigrationLint.Rule.MISSING_LOCK_TIMEOUT
                    && v.detail().contains("default_probe"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * /bmad-code-review fix (2026-09-21): {@code ON COMMIT DROP} (a {@code CREATE TEMP TABLE} clause)
     * contains the bare word "COMMIT" but is not a real transaction boundary — the original
     * TRANSACTION_BOUNDARY pattern had no way to tell the two apart. A SET LOCAL before it must still
     * bound a later statement in the same (never actually ended) transaction.
     */
    @Test
    @DisplayName("ON COMMIT DROP is not mistaken for a real transaction-ending COMMIT")
    void transactionBoundary_onCommitDropIsNotARealBoundary() throws IOException {
        Path tmp = Files.createTempDirectory("migration-lint-on-commit-drop");
        try {
            Files.writeString(tmp.resolve("V1000__on_commit_drop.sql"),
                "-- header\n"
                    + "SET LOCAL lock_timeout = '5s';\n"
                    + "CREATE TEMP TABLE staging (id int) ON COMMIT DROP;\n"
                    + "ALTER TABLE main.widget ADD COLUMN after_temp_probe VARCHAR(10);\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(tmp, 0);

            assertThat(violations)
                .as("'ON COMMIT DROP' must not be read as a real transaction boundary — the later ALTER "
                    + "TABLE is still bounded by the SET LOCAL above it, since no real COMMIT occurred: %s",
                    violations)
                .noneMatch(v -> v.rule() == MigrationLint.Rule.MISSING_LOCK_TIMEOUT
                    && v.detail().contains("after_temp_probe"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * /bmad-code-review fix (2026-09-21): {@code END} is Postgres's own synonym for {@code COMMIT} —
     * the original TRANSACTION_BOUNDARY pattern recognised only the literal {@code COMMIT}/
     * {@code ROLLBACK} keywords.
     */
    @Test
    @DisplayName("END ends a SET LOCAL's scope exactly like COMMIT")
    void transactionBoundary_endIsRecognisedAsACommitSynonym() throws IOException {
        Path tmp = Files.createTempDirectory("migration-lint-end-boundary");
        try {
            Files.writeString(tmp.resolve("V1000__end_boundary.sql"),
                "-- header\n"
                    + "SET LOCAL lock_timeout = '5s';\n"
                    + "ALTER TABLE main.widget ADD COLUMN first_probe VARCHAR(10);\n"
                    + "END;\n"
                    + "ALTER TABLE main.widget ADD COLUMN second_probe VARCHAR(10);\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(tmp, 0);

            assertThat(violations)
                .as("END is Postgres's own COMMIT synonym — the second ALTER TABLE, after it, must no "
                    + "longer be considered bounded by the SET LOCAL above it: %s", violations)
                .anyMatch(v -> v.rule() == MigrationLint.Rule.MISSING_LOCK_TIMEOUT
                    && v.detail().contains("second_probe"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * /bmad-code-review fix (2026-09-21): {@link MigrationLint}'s {@code hasNonTransactionalSidecar}
     * used to be a raw {@code .contains("executeInTransaction=false")} substring search — it missed
     * whitespace/case variants and, worse, matched a commented-out directive. This drives the check via
     * SESSION_SCOPED_LOCK_TIMEOUT (which exempts a genuine sidecar entirely), since it is directly
     * observable from lint output whether the sidecar was detected.
     */
    @Test
    @DisplayName("hasNonTransactionalSidecar tolerates whitespace/case and ignores a commented-out directive")
    void nonTransactionalSidecar_isWhitespaceCaseToleranteAndIgnoresComments() throws IOException {
        Path tmp = Files.createTempDirectory("migration-lint-sidecar-parsing");
        try {
            Files.writeString(tmp.resolve("V1000__sidecar_spaced.sql.conf"),
                "executeInTransaction = FALSE\n");
            Files.writeString(tmp.resolve("V1000__sidecar_spaced.sql"),
                "-- header\nSET lock_timeout = '5s';\nRESET lock_timeout;\n");

            Files.writeString(tmp.resolve("V1001__sidecar_commented_out.sql.conf"),
                "# executeInTransaction=false\n");
            Files.writeString(tmp.resolve("V1001__sidecar_commented_out.sql"),
                "-- header\nSET lock_timeout = '5s';\nRESET lock_timeout;\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(tmp, 0);

            assertThat(violations)
                .as("'executeInTransaction = FALSE' (spaces, different case) must still be recognised as "
                    + "the sidecar — the plain SET in V1000 must be exempt from SESSION_SCOPED_LOCK_TIMEOUT: %s",
                    violations)
                .noneMatch(v -> v.rule() == MigrationLint.Rule.SESSION_SCOPED_LOCK_TIMEOUT
                    && v.file().equals("V1000__sidecar_spaced.sql"));
            assertThat(violations)
                .as("a COMMENTED-OUT '# executeInTransaction=false' must NOT be read as a live sidecar "
                    + "directive — V1001's plain SET must still trip SESSION_SCOPED_LOCK_TIMEOUT: %s",
                    violations)
                .anyMatch(v -> v.rule() == MigrationLint.Rule.SESSION_SCOPED_LOCK_TIMEOUT
                    && v.file().equals("V1001__sidecar_commented_out.sql"));
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * /bmad-code-review fix (2026-09-21, owner decision — option 1 of 3, see
     * optionsAndRecommendations.md): the new counterpart rule to SESSION_SCOPED_LOCK_TIMEOUT's sidecar
     * exemption — closes the leak that exemption would otherwise leave open (a sidecar migration's
     * plain SET has no COMMIT to bound it, so it is GUARANTEED, not merely possible, to carry into
     * every later migration in the deploy unless reset by hand).
     */
    @Test
    @DisplayName("a sidecar migration's plain SET lock_timeout with no later RESET trips SIDECAR_LOCK_TIMEOUT_NOT_RESET")
    void sidecarLockTimeoutNotReset_firesWithNoTrailingReset() throws IOException {
        Path tmp = Files.createTempDirectory("migration-lint-sidecar-no-reset");
        try {
            Files.writeString(tmp.resolve("V1000__sidecar_no_reset.sql.conf"),
                "executeInTransaction=false\n");
            Files.writeString(tmp.resolve("V1000__sidecar_no_reset.sql"),
                "-- header\nSET lock_timeout = '5s';\nALTER TABLE main.widget ADD COLUMN probe VARCHAR(10);\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(tmp, 0);

            assertThat(violations)
                .as("a sidecar's plain SET with no later RESET must trip SIDECAR_LOCK_TIMEOUT_NOT_RESET: %s",
                    violations)
                .anyMatch(v -> v.rule() == MigrationLint.Rule.SIDECAR_LOCK_TIMEOUT_NOT_RESET);
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    @DisplayName("a sidecar migration's plain SET lock_timeout followed by a RESET does not trip the rule")
    void sidecarLockTimeoutNotReset_doesNotFireWithATrailingReset() throws IOException {
        Path tmp = Files.createTempDirectory("migration-lint-sidecar-with-reset");
        try {
            Files.writeString(tmp.resolve("V1000__sidecar_with_reset.sql.conf"),
                "executeInTransaction=false\n");
            Files.writeString(tmp.resolve("V1000__sidecar_with_reset.sql"),
                "-- header\nSET lock_timeout = '5s';\n"
                    + "ALTER TABLE main.widget ADD COLUMN probe VARCHAR(10);\nRESET lock_timeout;\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(tmp, 0);

            assertThat(violations)
                .as("a trailing RESET satisfies the rule: %s", violations)
                .noneMatch(v -> v.rule() == MigrationLint.Rule.SIDECAR_LOCK_TIMEOUT_NOT_RESET);
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    @DisplayName("a sidecar migration's plain SET lock_timeout opted out via allow-session-lock-timeout does not trip the rule")
    void sidecarLockTimeoutNotReset_optOutMarkerSuppresses() throws IOException {
        Path tmp = Files.createTempDirectory("migration-lint-sidecar-optout");
        try {
            Files.writeString(tmp.resolve("V1000__sidecar_optout.sql.conf"),
                "executeInTransaction=false\n");
            Files.writeString(tmp.resolve("V1000__sidecar_optout.sql"),
                "-- header\n"
                    + "-- migration-lint: allow-session-lock-timeout cannot reset, deploy aborts after this step\n"
                    + "SET lock_timeout = '5s';\n"
                    + "ALTER TABLE main.widget ADD COLUMN probe VARCHAR(10);\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(tmp, 0);

            assertThat(violations)
                .as("the opt-out marker suppresses SIDECAR_LOCK_TIMEOUT_NOT_RESET exactly as it "
                    + "suppresses SESSION_SCOPED_LOCK_TIMEOUT: %s", violations)
                .noneMatch(v -> v.rule() == MigrationLint.Rule.SIDECAR_LOCK_TIMEOUT_NOT_RESET);
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * /bmad-code-review fix (2026-09-21): SESSION_SCOPED_LOCK_TIMEOUT (and its sidecar-RESET
     * counterpart) were never invoked for R__ repeatables at all — lintFile returns into lintRepeatable
     * before the versioned per-statement loop that carries those checks ever runs.
     */
    @Test
    @DisplayName("an R__ repeatable's plain SET lock_timeout trips SESSION_SCOPED_LOCK_TIMEOUT")
    void repeatable_plainSetLockTimeout_trips_sessionScopedLockTimeout() throws IOException {
        Path tmp = Files.createTempDirectory("migration-lint-repeatable-session-scoped");
        try {
            Files.writeString(tmp.resolve("R__probe.sql"),
                "-- header\nSET lock_timeout = '5s';\nALTER TABLE main.widget ADD COLUMN probe VARCHAR(10);\n");

            List<MigrationLint.Violation> violations = MigrationLint.lint(tmp, 0);

            assertThat(violations)
                .as("an R__ repeatable's plain SET is exactly as much a cross-migration leak hazard as a "
                    + "versioned migration's — must trip SESSION_SCOPED_LOCK_TIMEOUT: %s", violations)
                .anyMatch(v -> v.rule() == MigrationLint.Rule.SESSION_SCOPED_LOCK_TIMEOUT);
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
