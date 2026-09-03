package com.softropic.skillars.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern DROP_NO_IF_EXISTS =
        Pattern.compile("(?is)\\bDROP\\s+(TABLE|COLUMN|INDEX)\\b(?!\\s+IF\\s+EXISTS)");
    private static final Pattern CREATE_INDEX_BLOCKING =
        Pattern.compile("(?is)\\bCREATE\\s+(UNIQUE\\s+)?INDEX\\b(?!\\s+CONCURRENTLY)");
    private static final Pattern ADD_FK_OR_CHECK =
        Pattern.compile("(?is)\\bADD\\s+CONSTRAINT\\b.*?\\b(FOREIGN\\s+KEY|CHECK)\\b");
    private static final Pattern NOT_VALID = Pattern.compile("(?i)\\bNOT\\s+VALID\\b");
    private static final Pattern DROP_TABLE_OR_COLUMN = Pattern.compile("(?is)\\bDROP\\s+(TABLE|COLUMN)\\b");

    private MigrationLint() {
    }

    public static List<Violation> lint(Path dir, int baselineVersion) throws IOException {
        final List<Violation> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                 .sorted()
                 .forEach(p -> lintFile(p, baselineVersion, violations));
        }
        return violations;
    }

    private static void lintFile(Path file, int baselineVersion, List<Violation> out) {
        final String name = file.getFileName().toString();
        final Matcher vm = VERSIONED.matcher(name);
        if (!vm.matches()) {
            if (LOOKS_VERSIONED.matcher(name).matches()) {
                // Looks like a versioned migration but the version is unparseable: fail loudly
                // rather than silently skipping a file the guard was supposed to check.
                out.add(new Violation(name, Rule.UNPARSEABLE_VERSION,
                    "looks like a versioned migration but its version could not be parsed — "
                        + "the lint would silently skip it; rename to V<major>[.<minor>]__<description>.sql"));
            }
            return; // R__ repeatable, or a non-migration file — outside the guard's scope
        }
        final int version = Integer.parseInt(vm.group(1));
        if (version <= baselineVersion) {
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

        if (DROP_TABLE_OR_COLUMN.matcher(stripped).find() && !hasHeaderComment(raw)) {
            out.add(new Violation(name, Rule.BARE_DROP_NO_HEADER,
                "performs a table/column DROP but has no leading header comment block explaining the expand/contract sequencing"));
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
