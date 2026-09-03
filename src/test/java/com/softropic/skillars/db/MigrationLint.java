package com.softropic.skillars.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Text-level rolling-deploy migration-safety lint (skillars-deferred-90 AC10).
 *
 * <p>Scans a directory of Flyway {@code V<n>__*.sql} scripts and reports the mechanical subset of
 * {@code docs/deployment/migration-conventions.md} that can be checked without a database. Only
 * scripts with a version <strong>strictly greater than</strong> {@code baselineVersion} are checked
 * — everything at or below the baseline is grandfathered (applied and immutable).
 *
 * <p>Intentionally a backstop, not a proof — see the "What the guard cannot catch" section of the
 * conventions doc (backported lower-version scripts, {@code R__} repeatables, inline FK columns).
 *
 * <p>No Spring context, no database — invoked from {@code MigrationConventionLintTest} in the
 * {@code test} phase so it fails the build in milliseconds.
 */
public final class MigrationLint {

    /** The Flyway location whose scripts are grandfathered up to {@link #GRANDFATHER_BASELINE}. */
    public static final Path REAL_MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    /**
     * Highest migration version that predates this convention. V60/V89/V94/V97/V98/V117 + the
     * AdminAlertType enum widen are all &le; this and are immutable; the guard binds V122+.
     */
    public static final int GRANDFATHER_BASELINE = 121;

    public enum Rule {
        /** {@code DROP TABLE|COLUMN|INDEX} without {@code IF EXISTS}. */
        DROP_WITHOUT_IF_EXISTS,
        /** {@code ADD CONSTRAINT … FOREIGN KEY|CHECK} without {@code NOT VALID} and no opt-out. */
        VALIDATING_CONSTRAINT,
        /** {@code CREATE INDEX} (not {@code CONCURRENTLY}) with no {@code allow-blocking-index} opt-out. */
        BLOCKING_INDEX,
        /** A migration that performs a table/column {@code DROP} with no leading header comment block. */
        BARE_DROP_NO_HEADER,
        /**
         * skillars-deferred-91 AC7 blind spot 1: a {@code V<n>__*.sql} at or below the grandfather
         * baseline that is <em>new in this commit</em> (absent from {@code HEAD}). Backporting a
         * script past the baseline is how a {@code DROP} / validating constraint / blocking index
         * evades every content rule above.
         */
        BACKPORT_BELOW_BASELINE,
        /**
         * skillars-deferred-91 AC7 blind spot 2: an {@code R__} repeatable migration containing a
         * {@code DROP}, a validating constraint, or a blocking {@code CREATE INDEX}. Repeatables
         * re-run on every checksum change, so a blocking DDL in one is a rolling-deploy hazard the
         * versioned-only rules never see.
         */
        REPEATABLE_HAZARD,
        /**
         * skillars-deferred-91 AC7 blind spot 3: an inline
         * {@code ALTER TABLE ... ADD COLUMN ... REFERENCES x(y)} foreign key (no literal
         * {@code ADD CONSTRAINT} text, so {@link Rule#VALIDATING_CONSTRAINT} misses it) added
         * without {@code NOT VALID}.
         */
        INLINE_FK_ADD_COLUMN,
        /** A {@code V…\u200b.sql} whose version the lint cannot parse — it would otherwise be skipped silently. */
        UNPARSEABLE_VERSION
    }

    public record Violation(String file, Rule rule, String detail) {
        @Override
        public String toString() {
            return file + " :: " + rule + " — " + detail;
        }
    }

    /**
     * Flyway versioned migration. The version may be dotted or underscore-separated
     * ({@code V122.1__x.sql}, {@code V122_1__x.sql}); only the major component gates the baseline.
     *
     * <p>Code review (3-layer run): this was {@code ^V(\d+)__.*\.sql$}, so a decimal-versioned
     * migration matched nothing and was skipped entirely — an unguarded DROP, a blocking index or a
     * validating constraint in a {@code V122.1__} file merged unlinted. Anything that looks like a
     * versioned migration but cannot be parsed is now reported rather than ignored (see
     * {@link #lintFile}).
     */
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)(?:[._]\\d+)*__.*\\.sql$");
    /** Anything starting {@code V…} and ending {@code .sql} — used to catch names VERSIONED cannot parse. */
    private static final Pattern LOOKS_VERSIONED = Pattern.compile("^V.*\\.sql$");
    /** Flyway repeatable migration ({@code R__description.sql}). */
    private static final Pattern REPEATABLE = Pattern.compile("^R__.*\\.sql$");
    /**
     * An inline {@code ADD COLUMN … REFERENCES tbl(col)} foreign key — no literal {@code ADD
     * CONSTRAINT}, so {@link #ADD_FK_OR_CHECK} does not see it.
     */
    // skillars-deferred-91 code review: both `COLUMN` and the referenced column list are OPTIONAL in
    // PostgreSQL, so the original `ADD\s+COLUMN … REFERENCES tbl\s*\(` missed two spellings of the
    // identical hazard — `ALTER TABLE t ADD owner_id BIGINT REFERENCES u (id)` and
    // `ALTER TABLE t ADD COLUMN owner_id BIGINT REFERENCES u` (defaults to the referenced PK).
    // Both add a VALIDATING foreign key under ACCESS EXCLUSIVE with a full scan of both tables.
    private static final Pattern INLINE_FK =
        Pattern.compile("(?is)\\bADD\\s+(?:COLUMN\\s+)?(?!CONSTRAINT\\b)[\\w\"]+\\b(?:(?!;).)*?\\bREFERENCES\\s+[\\w.\"]+");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    // CONSTRAINT added by the skillars-deferred-91 code review: V124's own
    // `ALTER TABLE payment.booking_payments DROP CONSTRAINT chk_bp_status` was unlinted and fails
    // hard if the constraint is ever already absent (a re-run, a diverged environment).
    private static final Pattern DROP_NO_IF_EXISTS =
        Pattern.compile("(?is)\\bDROP\\s+(TABLE|COLUMN|INDEX|CONSTRAINT)\\b(?!\\s+IF\\s+EXISTS)");
    private static final Pattern CREATE_INDEX_BLOCKING =
        Pattern.compile("(?is)\\bCREATE\\s+(UNIQUE\\s+)?INDEX\\b(?!\\s+CONCURRENTLY)");
    private static final Pattern ADD_FK_OR_CHECK =
        Pattern.compile("(?is)\\bADD\\s+CONSTRAINT\\b.*?\\b(FOREIGN\\s+KEY|CHECK)\\b");
    private static final Pattern NOT_VALID = Pattern.compile("(?i)\\bNOT\\s+VALID\\b");
    private static final Pattern DROP_TABLE_OR_COLUMN = Pattern.compile("(?is)\\bDROP\\s+(TABLE|COLUMN)\\b");

    private MigrationLint() {
    }

    /** Everything is assumed already present at HEAD — the backport rule cannot fire. */
    public static final Predicate<Path> ALL_KNOWN_AT_HEAD = p -> true;

    public static List<Violation> lint(Path dir, int baselineVersion) throws IOException {
        return lint(dir, baselineVersion, ALL_KNOWN_AT_HEAD);
    }

    /**
     * @param knownAtHead returns {@code true} if the file already existed at {@code HEAD}. A
     *     {@code V<n>} script with {@code n <= baselineVersion} for which this returns {@code false}
     *     is a backport past the grandfather baseline ({@link Rule#BACKPORT_BELOW_BASELINE}).
     */
    public static List<Violation> lint(Path dir, int baselineVersion, Predicate<Path> knownAtHead)
            throws IOException {
        final List<Violation> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                 .sorted()
                 .forEach(p -> lintFile(p, baselineVersion, knownAtHead, violations));
        }
        return violations;
    }

    private static void lintFile(Path file, int baselineVersion, Predicate<Path> knownAtHead,
                                 List<Violation> out) {
        final String name = file.getFileName().toString();
        final Matcher vm = VERSIONED.matcher(name);
        if (!vm.matches()) {
            if (REPEATABLE.matcher(name).matches()) {
                lintRepeatable(file, name, out);
                return;
            }
            if (LOOKS_VERSIONED.matcher(name).matches()) {
                // Looks like a versioned migration but the version is unparseable: fail loudly
                // rather than silently skipping a file the guard was supposed to check.
                out.add(new Violation(name, Rule.UNPARSEABLE_VERSION,
                    "looks like a versioned migration but its version could not be parsed — "
                        + "the lint would silently skip it; rename to V<major>[.<minor>]__<description>.sql"));
            }
            return; // a non-migration file — outside the guard's scope
        }
        final int version = Integer.parseInt(vm.group(1));
        if (version <= baselineVersion) {
            if (!knownAtHead.test(file)) {
                out.add(new Violation(name, Rule.BACKPORT_BELOW_BASELINE,
                    "a V" + version + " script at or below the V" + baselineVersion + " grandfather "
                        + "baseline is new in this commit — a backport past the baseline evades every "
                        + "content rule; renumber it above the latest migration"));
            }
            return; // grandfathered
        }

        final String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        final String stripped = stripComments(raw);
        final String lowerRaw = raw.toLowerCase();

        if (DROP_NO_IF_EXISTS.matcher(stripped).find()) {
            out.add(new Violation(name, Rule.DROP_WITHOUT_IF_EXISTS,
                "a DROP TABLE/COLUMN/INDEX is missing IF EXISTS"));
        }

        if (CREATE_INDEX_BLOCKING.matcher(stripped).find()
            && !lowerRaw.contains("migration-lint: allow-blocking-index")) {
            out.add(new Violation(name, Rule.BLOCKING_INDEX,
                "CREATE INDEX without CONCURRENTLY and without a '-- migration-lint: allow-blocking-index <reason>' opt-out"));
        }

        if (!lowerRaw.contains("migration-lint: allow-validating-constraint")) {
            for (String statement : stripped.split(";")) {
                if (ADD_FK_OR_CHECK.matcher(statement).find() && !NOT_VALID.matcher(statement).find()) {
                    out.add(new Violation(name, Rule.VALIDATING_CONSTRAINT,
                        "ADD CONSTRAINT … FOREIGN KEY|CHECK without NOT VALID (and no allow-validating-constraint opt-out)"));
                    break;
                }
            }
        }

        if (!lowerRaw.contains("migration-lint: allow-validating-constraint")) {
            for (String statement : stripped.split(";")) {
                if (INLINE_FK.matcher(statement).find() && !NOT_VALID.matcher(statement).find()) {
                    out.add(new Violation(name, Rule.INLINE_FK_ADD_COLUMN,
                        "ALTER TABLE … ADD COLUMN … REFERENCES x(y) inline FK without NOT VALID — the "
                            + "'ADD CONSTRAINT' rule does not see it; add the column first, then "
                            + "ADD CONSTRAINT … NOT VALID in a later migration"));
                    break;
                }
            }
        }

        if (DROP_TABLE_OR_COLUMN.matcher(stripped).find() && !hasHeaderComment(raw)) {
            out.add(new Violation(name, Rule.BARE_DROP_NO_HEADER,
                "performs a table/column DROP but has no leading header comment block explaining the expand/contract sequencing"));
        }
    }

    /**
     * skillars-deferred-91 AC7 blind spot 2: an {@code R__} repeatable migration re-runs on every
     * checksum change, so a {@code DROP} / validating constraint / blocking {@code CREATE INDEX} in
     * one is a rolling-deploy hazard no versioned rule ever sees. The {@code allow-*} opt-outs still
     * apply.
     */
    private static void lintRepeatable(Path file, String name, List<Violation> out) {
        final String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        final String stripped = stripComments(raw);
        final String lowerRaw = raw.toLowerCase();

        // skillars-deferred-91 code review: this branch had no opt-out check, though the method
        // javadoc promises "the allow-* opt-outs still apply" and both sibling branches honour one.
        // A repeatable that legitimately needs an unconditional DROP had no way past the lint.
        if (DROP_NO_IF_EXISTS.matcher(stripped).find()
            && !lowerRaw.contains("migration-lint: allow-unconditional-drop")) {
            out.add(new Violation(name, Rule.REPEATABLE_HAZARD,
                "an R__ repeatable contains a DROP without IF EXISTS and without a "
                    + "'-- migration-lint: allow-unconditional-drop <reason>' opt-out"));
        }
        if (CREATE_INDEX_BLOCKING.matcher(stripped).find()
            && !lowerRaw.contains("migration-lint: allow-blocking-index")) {
            out.add(new Violation(name, Rule.REPEATABLE_HAZARD,
                "an R__ repeatable contains a blocking CREATE INDEX (not CONCURRENTLY) with no opt-out"));
        }
        if (!lowerRaw.contains("migration-lint: allow-validating-constraint")) {
            for (String statement : stripped.split(";")) {
                if ((ADD_FK_OR_CHECK.matcher(statement).find() || INLINE_FK.matcher(statement).find())
                    && !NOT_VALID.matcher(statement).find()) {
                    out.add(new Violation(name, Rule.REPEATABLE_HAZARD,
                        "an R__ repeatable adds a validating constraint (no NOT VALID) with no opt-out"));
                    break;
                }
            }
        }
    }

    /**
     * Best-effort "does {@code file} exist in the {@code HEAD} tree?" via {@code git cat-file}.
     *
     * <p><strong>Scope</strong> (skillars-deferred-91 code review). This is a <em>pre-commit,
     * working-tree</em> guard, not a CI gate. In CI the checked-out commit already contains every
     * migration file, so {@code cat-file} answers 0 for all of them and {@code BACKPORT_BELOW_BASELINE}
     * cannot fire there. It catches a below-baseline migration while the author still has it staged
     * or untracked locally. Catching a <em>committed</em> backport would need a merge-base diff
     * ({@code git log --diff-filter=A} against the base branch), which is not reliable on the
     * shallow clones CI uses — see {@code docs/deployment/migration-conventions.md}.
     *
     * <p>The exit-code handling used to read {@code exitValue() == 0 || exitValue() == 128} as
     * "known". {@code git cat-file -e HEAD:&lt;absent-path&gt;} exits <strong>128</strong> ("fatal:
     * path … does not exist in 'HEAD'"), which is precisely the case the rule targets — so the rule
     * was inert even locally. "Cannot answer" is now decided up front by asking git whether we are
     * in a work tree at all, and only that case assumes known.
     */
    public static boolean gitKnownAtHead(Path file) {
        if (!insideGitWorkTree()) {
            // No git, or not a work tree: we genuinely cannot answer, so do not accuse anyone.
            return true;
        }
        try {
            Path repoRelative = Path.of("").toAbsolutePath().relativize(file.toAbsolutePath());
            Process p = new ProcessBuilder("git", "cat-file", "-e",
                    "HEAD:" + repoRelative.toString().replace('\\', '/'))
                .redirectErrorStream(true)
                .start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return true;
            }
            // exit 0 => the path exists at HEAD (pre-existing). Anything else — 1, or the 128 git
            // uses for "path does not exist in HEAD" — means this file is new in the working tree.
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return true;
        }
    }

    /** {@code git rev-parse --is-inside-work-tree} — the only reliable "can git answer at all?" probe. */
    private static boolean insideGitWorkTree() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--is-inside-work-tree")
                .redirectErrorStream(true)
                .start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static String stripComments(String sql) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(sql).replaceAll(" ")).replaceAll("");
    }

    /** True when the first non-blank line of the file is a {@code --} comment. */
    private static boolean hasHeaderComment(String sql) {
        for (String line : sql.split("\\n")) {
            final String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            return trimmed.startsWith("--");
        }
        return false;
    }
}
