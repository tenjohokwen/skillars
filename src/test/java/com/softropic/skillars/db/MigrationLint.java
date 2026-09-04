package com.softropic.skillars.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Text-level rolling-deploy migration-safety lint (skillars-deferred-90 AC10, widened by
 * skillars-deferred-91 AC7 and skillars-deferred-92 AC7–AC11).
 *
 * <p>Scans a directory of Flyway {@code V<n>__*.sql} scripts and reports the mechanical subset of
 * {@code docs/deployment/migration-conventions.md} that can be checked without a database. Only
 * scripts with a version <strong>strictly greater than</strong> {@code baselineVersion} are checked
 * — everything at or below the baseline is grandfathered (applied and immutable).
 *
 * <h2>Two baselines, and why</h2>
 *
 * Flyway checksums a migration's whole file, comments included, so an applied migration cannot be
 * edited — not even to add an opt-out marker — without breaking validation on every environment that
 * already ran it. The rules skillars-deferred-92 adds ({@link Rule#MISSING_LOCK_TIMEOUT},
 * {@link Rule#UNBATCHED_DML}, {@link Rule#DROP_WITHOUT_PRIOR_RELEASE_PREP},
 * {@link Rule#PLATFORM_CONFIG_EXPLICIT_ID}) would flag several of {@code V122}–{@code V127}, which
 * are above {@link #GRANDFATHER_BASELINE} but already applied. They therefore bind from
 * {@link #DEFERRED_92_BASELINE} instead. This is the same reasoning that produced the first baseline,
 * applied a second time rather than quietly rewriting shipped migrations.
 *
 * <h2>Statement scoping (skillars-deferred-92 AC11.2)</h2>
 *
 * {@code -- migration-lint: allow-*} markers used to be matched against the whole file, so one
 * opt-out silenced that rule for <em>every</em> statement in the migration — a documented blind spot.
 * A marker now scopes to the statement that follows it (precisely: to the text between the previous
 * statement terminator and this statement's own), which is where every existing marker in this
 * repository already sits.
 *
 * <p>Intentionally a backstop, not a proof. Each rule's javadoc states what it does <em>not</em>
 * catch; this project has three recorded instances of a guard believed stronger than it was, and an
 * overstated rule would be a fourth.
 *
 * <p>No Spring context, no database — invoked from {@code MigrationConventionLintTest} in the
 * {@code test} phase so it fails the build in milliseconds.
 */
public final class MigrationLint {

    /** The Flyway location whose scripts are grandfathered up to {@link #GRANDFATHER_BASELINE}. */
    public static final Path REAL_MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    /** Source trees {@link Rule#DROP_WITHOUT_PRIOR_RELEASE_PREP} searches for live references. */
    public static final List<Path> REAL_SOURCE_ROOTS = List.of(
        Path.of("src", "main", "java"), Path.of("src", "main", "resources"));

    /**
     * Highest migration version that predates this convention. V60/V89/V94/V97/V98/V117 + the
     * AdminAlertType enum widen are all &le; this and are immutable; the guard binds V122+.
     */
    public static final int GRANDFATHER_BASELINE = 121;

    /**
     * Highest migration version that predates the skillars-deferred-92 rules. {@code V122}–{@code V127}
     * are above {@link #GRANDFATHER_BASELINE} but already applied and therefore checksum-frozen, so
     * they cannot carry the opt-out markers the new rules would demand. Those rules bind {@code V128+}.
     */
    public static final int DEFERRED_92_BASELINE = 127;

    public enum Rule {
        /** {@code DROP TABLE|COLUMN|INDEX|CONSTRAINT} without {@code IF EXISTS}. */
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
        /** A {@code V…​.sql} whose version the lint cannot parse — it would otherwise be skipped silently. */
        UNPARSEABLE_VERSION,
        /**
         * skillars-deferred-92 AC7: expand/contract <em>ordering</em>. A {@code DROP TABLE} or
         * {@code DROP COLUMN} must name the release that removed the last reader
         * ({@code -- migration-lint: drop-prepared-in: V123}), and no live reference to the dropped
         * identifier may remain in {@code src/main}.
         */
        DROP_WITHOUT_PRIOR_RELEASE_PREP,
        /**
         * skillars-deferred-92 AC8: lock-taking DDL with no {@code SET lock_timeout}, so it waits
         * indefinitely — and every later query on the table queues behind the blocked statement.
         */
        MISSING_LOCK_TIMEOUT,
        /** skillars-deferred-92 AC9: an {@code UPDATE}/{@code DELETE} that cannot bound its row count. */
        UNBATCHED_DML,
        /**
         * skillars-deferred-92 AC10.3: an {@code INSERT INTO main.platform_config} that supplies an
         * explicit {@code id}. {@code V128} gave the column an identity; a hand-picked id collides on
         * the primary key, which the {@code ON CONFLICT (key)} clause never sees.
         */
        PLATFORM_CONFIG_EXPLICIT_ID
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

    // --- skillars-deferred-92 patterns -------------------------------------------------------

    /** {@code -- migration-lint: drop-prepared-in: V123} (AC7). */
    private static final Pattern DROP_PREPARED_IN =
        Pattern.compile("(?i)migration-lint:\\s*drop-prepared-in:\\s*(V?\\d+(?:[._]\\d+)*)");
    /** The identifier a {@code DROP TABLE|COLUMN} names, and (for a column) its owning table. */
    private static final Pattern DROP_COLUMN_TARGET = Pattern.compile(
        "(?is)\\bALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?([\\w.\"]+).*?\\bDROP\\s+COLUMN\\s+(?:IF\\s+EXISTS\\s+)?([\\w\"]+)");
    private static final Pattern DROP_TABLE_TARGET =
        Pattern.compile("(?is)\\bDROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?([\\w.\"]+)");
    private static final Pattern SET_LOCK_TIMEOUT =
        Pattern.compile("(?i)\\bSET\\s+(?:LOCAL\\s+)?lock_timeout\\b");
    /** Any index build, concurrent or not — all of them take a lock worth bounding (AC8.1). */
    private static final Pattern ANY_CREATE_INDEX =
        Pattern.compile("(?is)\\bCREATE\\s+(UNIQUE\\s+)?INDEX\\b");
    /** DDL that takes a lock blocking concurrent DML or DDL. Lock levels differ — see the rule. */
    private static final Pattern ACCESS_EXCLUSIVE_DDL = Pattern.compile(
        "(?is)\\bALTER\\s+TABLE\\b(?:(?!;).)*?\\b(ADD|DROP|ALTER)\\s+(COLUMN|CONSTRAINT)\\b"
            + "|\\bDROP\\s+TABLE\\b|\\bDROP\\s+INDEX\\b|\\bALTER\\s+TABLE\\b(?:(?!;).)*?\\bADD\\s+GENERATED\\b");
    private static final Pattern DML_WRITE = Pattern.compile("(?is)^\\s*(UPDATE|DELETE)\\s+");
    private static final Pattern HAS_WHERE = Pattern.compile("(?i)\\bWHERE\\b");
    private static final Pattern TAUTOLOGICAL_WHERE =
        Pattern.compile("(?i)\\bWHERE\\s+(TRUE|1\\s*=\\s*1)\\b");
    private static final Pattern PLATFORM_CONFIG_INSERT = Pattern.compile(
        "(?is)\\bINSERT\\s+INTO\\s+(?:main\\.)?platform_config\\s*\\(([^)]*)\\)");

    private MigrationLint() {
    }

    /** Everything is assumed already present at HEAD — the backport rule cannot fire. */
    public static final Predicate<Path> ALL_KNOWN_AT_HEAD = p -> true;

    public static List<Violation> lint(Path dir, int baselineVersion) throws IOException {
        return lint(dir, baselineVersion, ALL_KNOWN_AT_HEAD);
    }

    public static List<Violation> lint(Path dir, int baselineVersion, Predicate<Path> knownAtHead)
            throws IOException {
        return lint(dir, baselineVersion, DEFERRED_92_BASELINE, knownAtHead, REAL_SOURCE_ROOTS);
    }

    /**
     * @param baselineVersion versions at or below this are grandfathered from every content rule
     * @param deferred92Baseline versions at or below this are additionally grandfathered from the
     *     rules skillars-deferred-92 added — see the class javadoc for why there are two
     * @param knownAtHead returns {@code true} if the file already existed at {@code HEAD}. A
     *     {@code V<n>} script with {@code n <= baselineVersion} for which this returns {@code false}
     *     is a backport past the grandfather baseline ({@link Rule#BACKPORT_BELOW_BASELINE}).
     * @param sourceRoots trees searched for live references to a dropped identifier
     *     ({@link Rule#DROP_WITHOUT_PRIOR_RELEASE_PREP})
     */
    public static List<Violation> lint(Path dir, int baselineVersion, int deferred92Baseline,
                                       Predicate<Path> knownAtHead, List<Path> sourceRoots)
            throws IOException {
        final List<Violation> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                 .sorted()
                 .forEach(p -> lintFile(p, baselineVersion, deferred92Baseline, knownAtHead,
                     sourceRoots, violations));
        }
        return violations;
    }

    // --- statement model ----------------------------------------------------------------------

    /**
     * One SQL statement plus the raw text leading up to it — the comments immediately above a
     * statement are part of its scope, which is what makes a {@code -- migration-lint: allow-*}
     * marker bind to the statement it was written for rather than to the whole file (AC11.2).
     *
     * @param scope raw text from the end of the previous statement through this statement's
     *     terminator, comments included
     * @param sql the same statement with comments stripped
     */
    private record Statement(String scope, String sql) {
    }

    /**
     * Splits on {@code ;} at the top level, skipping terminators inside line comments, block
     * comments, single-quoted literals and dollar-quoted bodies.
     *
     * <p>Limitation, stated rather than implied: nested dollar-quote tags ({@code $tag$}) are matched
     * only by the plain {@code $$} form. No migration in this repository uses a tagged dollar quote,
     * and a mis-split would at worst scope a marker more narrowly than intended — the safe direction.
     */
    private static List<Statement> statements(String raw) {
        final List<Statement> out = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '-' && i + 1 < raw.length() && raw.charAt(i + 1) == '-') {
                int nl = raw.indexOf('\n', i);
                i = nl < 0 ? raw.length() : nl + 1;
            } else if (c == '/' && i + 1 < raw.length() && raw.charAt(i + 1) == '*') {
                int end = raw.indexOf("*/", i + 2);
                i = end < 0 ? raw.length() : end + 2;
            } else if (c == '\'') {
                int end = raw.indexOf('\'', i + 1);
                i = end < 0 ? raw.length() : end + 1;
            } else if (c == '$' && i + 1 < raw.length() && raw.charAt(i + 1) == '$') {
                int end = raw.indexOf("$$", i + 2);
                i = end < 0 ? raw.length() : end + 2;
            } else if (c == ';') {
                String scope = raw.substring(start, i + 1);
                out.add(new Statement(scope, stripComments(scope)));
                start = i + 1;
                i++;
            } else {
                i++;
            }
        }
        String tail = raw.substring(start);
        if (!stripComments(tail).isBlank()) {
            out.add(new Statement(tail, stripComments(tail)));
        }
        return out;
    }

    /**
     * Splits an {@code ALTER TABLE} statement's clauses on top-level commas, so each
     * {@code ADD CONSTRAINT} is evaluated independently (AC11.1).
     *
     * <p>Balanced-paren tracking is what makes this correct: a {@code CHECK} body carries its own
     * commas ({@code CHECK (x IN (1,2,3))}), so a naive split on {@code ,} would tear a constraint in
     * half and both fragments would look clause-less.
     */
    private static List<String> topLevelClauses(String statement) {
        final List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < statement.length(); i++) {
            char c = statement.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(statement.substring(start, i));
                start = i + 1;
            }
        }
        out.add(statement.substring(start));
        return out;
    }

    private static boolean hasMarker(String scope, String marker) {
        return scope.toLowerCase(Locale.ROOT).contains("migration-lint: " + marker);
    }

    // --- file linting -------------------------------------------------------------------------

    private static void lintFile(Path file, int baselineVersion, int deferred92Baseline,
                                 Predicate<Path> knownAtHead, List<Path> sourceRoots,
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
        final List<Statement> statements = statements(raw);
        final boolean deferred92Applies = version > deferred92Baseline;

        for (Statement st : statements) {
            lintStatement(name, st, out);
            if (deferred92Applies) {
                lintStatementDeferred92(name, st, raw, sourceRoots, out);
            }
        }

        if (DROP_TABLE_OR_COLUMN.matcher(stripComments(raw)).find() && !hasHeaderComment(raw)) {
            out.add(new Violation(name, Rule.BARE_DROP_NO_HEADER,
                "performs a table/column DROP but has no leading header comment block explaining the expand/contract sequencing"));
        }
    }

    /** The skillars-deferred-90/-91 rules, now evaluated per statement rather than per file. */
    private static void lintStatement(String name, Statement st, List<Violation> out) {
        if (DROP_NO_IF_EXISTS.matcher(st.sql()).find()) {
            out.add(new Violation(name, Rule.DROP_WITHOUT_IF_EXISTS,
                "a DROP TABLE/COLUMN/INDEX is missing IF EXISTS"));
        }

        if (CREATE_INDEX_BLOCKING.matcher(st.sql()).find()
            && !hasMarker(st.scope(), "allow-blocking-index")) {
            out.add(new Violation(name, Rule.BLOCKING_INDEX,
                "CREATE INDEX without CONCURRENTLY and without a '-- migration-lint: allow-blocking-index <reason>' "
                    + "opt-out immediately above this statement (skillars-deferred-92 AC11.2: an opt-out "
                    + "elsewhere in the file no longer covers it)"));
        }

        if (!hasMarker(st.scope(), "allow-validating-constraint")) {
            // AC11.1: per CLAUSE, not per statement. `ADD CONSTRAINT a CHECK (…) NOT VALID,
            // ADD CONSTRAINT b CHECK (…)` used to pass because NOT VALID appeared *somewhere*.
            for (String clause : topLevelClauses(st.sql())) {
                if (ADD_FK_OR_CHECK.matcher(clause).find() && !NOT_VALID.matcher(clause).find()) {
                    out.add(new Violation(name, Rule.VALIDATING_CONSTRAINT,
                        "ADD CONSTRAINT … FOREIGN KEY|CHECK without NOT VALID on that clause (and no "
                            + "allow-validating-constraint opt-out): " + oneLine(clause)));
                    break;
                }
            }
            for (String clause : topLevelClauses(st.sql())) {
                if (INLINE_FK.matcher(clause).find() && !NOT_VALID.matcher(clause).find()) {
                    out.add(new Violation(name, Rule.INLINE_FK_ADD_COLUMN,
                        "ALTER TABLE … ADD COLUMN … REFERENCES x(y) inline FK without NOT VALID — the "
                            + "'ADD CONSTRAINT' rule does not see it; add the column first, then "
                            + "ADD CONSTRAINT … NOT VALID in a later migration"));
                    break;
                }
            }
        }
    }

    /** The rules skillars-deferred-92 added; bind above {@link #DEFERRED_92_BASELINE} only. */
    private static void lintStatementDeferred92(String name, Statement st, String raw,
                                                List<Path> sourceRoots, List<Violation> out) {
        lintDropOrdering(name, st, raw, sourceRoots, out);
        lintLockTimeout(name, st, raw, out);
        lintUnbatchedDml(name, st, out);
        lintPlatformConfigId(name, st, out);
    }

    /**
     * AC7 — expand/contract <strong>ordering</strong>, which the shape rules never checked. During a
     * rolling deploy the old pods are still reading a column when the new pod's migration drops it;
     * {@code skillars-11-3} D2 shipped exactly that and passed the lint clean.
     *
     * <p>Two halves, and the second is what makes the first more than decoration: the migration must
     * name the release that removed the last reader, <em>and</em> no live reference to the dropped
     * identifier may remain in {@code src/main}.
     *
     * <p><strong>What the reference search actually guarantees</strong> (AC7.2 requires this be
     * stated, not implied). Column names like {@code id}, {@code status} or {@code amount} are far
     * too generic to grep for, so a match requires the {@code snake_case} identifier <em>and</em> the
     * owning table's bare name to appear in the same file, plus the camelCase JPA field name where
     * that is distinctive. A dropped column whose reader lives in a file that never mentions the table
     * name — a raw SQL string assembled from fragments, say — will not be found. For those, the
     * marker alone is the guarantee. {@code -- migration-lint: allow-drop-reference-scan <reason>}
     * suppresses the search when its output is noise; the marker requirement still stands.
     */
    private static void lintDropOrdering(String name, Statement st, String raw,
                                         List<Path> sourceRoots, List<Violation> out) {
        if (!DROP_TABLE_OR_COLUMN.matcher(st.sql()).find()) {
            return;
        }
        Matcher prepared = DROP_PREPARED_IN.matcher(raw);
        if (!prepared.find()) {
            out.add(new Violation(name, Rule.DROP_WITHOUT_PRIOR_RELEASE_PREP,
                "a DROP TABLE/COLUMN must name the release in which the last reader was removed — add "
                    + "'-- migration-lint: drop-prepared-in: V<n>' to the header block. Dropping in the "
                    + "same release that stops reading is the rolling-deploy hazard the expand/contract "
                    + "standard exists to prevent: old pods are still reading the column when the new "
                    + "pod's migration removes it"));
            return;
        }
        if (hasMarker(raw, "allow-drop-reference-scan")) {
            return;
        }

        for (String[] target : droppedIdentifiers(st.sql())) {
            final String table = target[0];
            final String identifier = target[1];
            List<String> offenders = referencesIn(sourceRoots, table, identifier);
            if (!offenders.isEmpty()) {
                out.add(new Violation(name, Rule.DROP_WITHOUT_PRIOR_RELEASE_PREP,
                    "the header claims this DROP was prepared in " + prepared.group(1) + ", but '"
                        + identifier + "' still appears alongside '" + table + "' in " + offenders
                        + " — a marker that claims preparation while the code still reads the object is "
                        + "the failure worth catching. Remove the reads first, or add "
                        + "'-- migration-lint: allow-drop-reference-scan <reason>' if these are false "
                        + "positives"));
            }
        }
    }

    /** {@code {table, identifier}} pairs for each {@code DROP TABLE} / {@code DROP COLUMN}. */
    private static List<String[]> droppedIdentifiers(String sql) {
        final List<String[]> out = new ArrayList<>();
        Matcher col = DROP_COLUMN_TARGET.matcher(sql);
        while (col.find()) {
            out.add(new String[] {bareName(col.group(1)), bareName(col.group(2))});
        }
        Matcher tbl = DROP_TABLE_TARGET.matcher(sql);
        while (tbl.find()) {
            String t = bareName(tbl.group(1));
            out.add(new String[] {t, t});
        }
        return out;
    }

    private static String bareName(String qualified) {
        String s = qualified.replace("\"", "");
        int dot = s.lastIndexOf('.');
        return dot < 0 ? s : s.substring(dot + 1);
    }

    /** Files mentioning both the owning table and the identifier (snake_case or camelCase). */
    private static List<String> referencesIn(List<Path> roots, String table, String identifier) {
        final String camel = toCamelCase(identifier);
        final Set<String> hits = new LinkedHashSet<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                     // The migration tree is not source; a later migration naming the column is not a reader.
                     .filter(p -> !p.toString().contains("db" + java.io.File.separator + "migration"))
                     .filter(p -> {
                         String n = p.getFileName().toString();
                         return n.endsWith(".java") || n.endsWith(".sql") || n.endsWith(".yaml")
                             || n.endsWith(".yml") || n.endsWith(".xml");
                     })
                     .forEach(p -> {
                         final String body;
                         try {
                             body = Files.readString(p, StandardCharsets.UTF_8);
                         } catch (IOException | RuntimeException e) {
                             return; // unreadable or non-UTF-8: not evidence of a reference
                         }
                         if (!body.contains(table)) {
                             return;
                         }
                         if (body.contains(identifier) || (!camel.equals(identifier) && body.contains(camel))) {
                             hits.add(p.getFileName().toString());
                         }
                     });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new ArrayList<>(hits);
    }

    private static String toCamelCase(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }

    /**
     * AC8 — every lock-taking DDL statement needs a bounded wait.
     *
     * <p>Verified during skillars-deferred-92: only {@code V55} and {@code V57} of 121 migrations set
     * {@code lock_timeout}. Without it a blocked {@code ALTER TABLE} queues behind a long-running
     * reader <em>and every subsequent query on that table queues behind the ALTER</em>, so one slow
     * {@code SELECT} can stall the whole table and exhaust the connection pool. That mechanism, not
     * the {@code ALTER}'s own duration, is why this matters — and it is the concrete cause behind the
     * three separate ledger entries for {@code V60}, {@code V94} and {@code V97}.
     *
     * <p><strong>The locks are not all the same, and saying so matters:</strong>
     * <ul>
     *   <li>{@code ALTER TABLE … ADD/DROP COLUMN|CONSTRAINT}, {@code DROP TABLE}, {@code DROP INDEX}
     *       → {@code ACCESS EXCLUSIVE}, which blocks reads too;</li>
     *   <li>{@code CREATE INDEX} (non-{@code CONCURRENTLY}) → {@code SHARE}: blocks writes and other
     *       DDL, but <strong>allows reads</strong>;</li>
     *   <li>{@code CREATE INDEX CONCURRENTLY} → {@code SHARE UPDATE EXCLUSIVE}.</li>
     * </ul>
     * All three are covered, because all three can wait indefinitely for a conflicting lock — but the
     * rule deliberately does not claim they are equally disruptive, and the message it emits does not
     * either. Overstating what a guard covers is the failure this project has recorded three times.
     */
    private static void lintLockTimeout(String name, Statement st, String raw, List<Violation> out) {
        if (!ACCESS_EXCLUSIVE_DDL.matcher(st.sql()).find()
            && !ANY_CREATE_INDEX.matcher(st.sql()).find()) {
            return;
        }
        if (hasMarker(st.scope(), "allow-unbounded-lock-wait")) {
            return;
        }
        // A `SET lock_timeout` anywhere earlier in the file covers this statement: it is a session /
        // transaction setting, not a per-statement one, so scoping it per statement would be wrong.
        //
        // Comments MUST be stripped before this search. Without it, a migration whose header merely
        // *discusses* lock_timeout — "this migration does not set lock_timeout because ..." — silences
        // the rule for its own DDL. That is not hypothetical: the V912 fixture's explanatory header
        // contains the phrase, and the rule passed it clean until this line stripped comments first.
        int here = raw.indexOf(st.scope());
        String before = here < 0 ? raw : raw.substring(0, here + st.scope().length());
        if (SET_LOCK_TIMEOUT.matcher(stripComments(before)).find()) {
            return;
        }
        out.add(new Violation(name, Rule.MISSING_LOCK_TIMEOUT,
            "lock-taking DDL with no 'SET lock_timeout' before it, so it waits indefinitely — and every "
                + "later query on that table queues behind the blocked statement. Add "
                + "'SET lock_timeout = '<n>s';' near the top of the migration, or "
                + "'-- migration-lint: allow-unbounded-lock-wait <reason>': " + oneLine(st.sql())));
    }

    /**
     * AC9 — a full-table {@code UPDATE}/{@code DELETE} in a migration locks every row it touches for
     * the whole statement.
     *
     * <p><strong>Scope, stated honestly.</strong> AC9 asks for "no {@code WHERE}, <em>or a
     * {@code WHERE} that cannot bound the row count</em>". The second half is not decidable from
     * text — {@code WHERE status = 'X'} may match three rows or three million. This rule therefore
     * catches a missing {@code WHERE} and the tautological forms ({@code WHERE TRUE},
     * {@code WHERE 1=1}), and no more. A present-but-unbounded predicate is still review's problem,
     * which is why that limitation is written here rather than left for a reader to discover.
     */
    private static void lintUnbatchedDml(String name, Statement st, List<Violation> out) {
        String sql = st.sql().strip();
        if (!DML_WRITE.matcher(sql).find()) {
            return;
        }
        boolean unbounded = !HAS_WHERE.matcher(sql).find() || TAUTOLOGICAL_WHERE.matcher(sql).find();
        if (!unbounded || hasMarker(st.scope(), "allow-full-table-dml")) {
            return;
        }
        out.add(new Violation(name, Rule.UNBATCHED_DML,
            "an UPDATE/DELETE with no bounding WHERE clause locks every row in the table for the whole "
                + "statement. Batch it, or add '-- migration-lint: allow-full-table-dml <reason>' "
                + "immediately above: " + oneLine(sql)));
    }

    /**
     * AC10.3 — {@code V128} gave {@code main.platform_config.id} an identity, so seeds must omit it.
     * A hand-picked id raises a <em>primary key</em> violation that the {@code ON CONFLICT (key)}
     * clause never sees, failing Flyway on every database that already ran a later migration reusing
     * that id — the hazard {@code V99}'s own header spends six lines describing.
     */
    private static void lintPlatformConfigId(String name, Statement st, List<Violation> out) {
        Matcher m = PLATFORM_CONFIG_INSERT.matcher(st.sql());
        while (m.find()) {
            boolean namesId = Stream.of(m.group(1).split(","))
                .map(c -> c.strip().replace("\"", "").toLowerCase(Locale.ROOT))
                .anyMatch("id"::equals);
            if (namesId) {
                out.add(new Violation(name, Rule.PLATFORM_CONFIG_EXPLICIT_ID,
                    "INSERT INTO main.platform_config supplies an explicit id. Since V128 the column has "
                        + "an identity — omit id and let the sequence assign it. A hand-picked id raises a "
                        + "PRIMARY KEY violation that ON CONFLICT (key) does not catch, which fails Flyway "
                        + "on every database that already used that id"));
            }
        }
    }

    private static String oneLine(String sql) {
        String s = sql.replaceAll("\\s+", " ").strip();
        return s.length() <= 120 ? s : s.substring(0, 117) + "...";
    }

    /**
     * skillars-deferred-91 AC7 blind spot 2: an {@code R__} repeatable migration re-runs on every
     * checksum change, so a {@code DROP} / validating constraint / blocking {@code CREATE INDEX} in
     * one is a rolling-deploy hazard no versioned rule ever sees. The {@code allow-*} opt-outs still
     * apply, and since skillars-deferred-92 AC11.2 they are scoped to the statement they precede.
     */
    private static void lintRepeatable(Path file, String name, List<Violation> out) {
        final String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        for (Statement st : statements(raw)) {
            // skillars-deferred-91 code review: this branch had no opt-out check, though the method
            // javadoc promises "the allow-* opt-outs still apply" and both sibling branches honour one.
            // A repeatable that legitimately needs an unconditional DROP had no way past the lint.
            if (DROP_NO_IF_EXISTS.matcher(st.sql()).find()
                && !hasMarker(st.scope(), "allow-unconditional-drop")) {
                out.add(new Violation(name, Rule.REPEATABLE_HAZARD,
                    "an R__ repeatable contains a DROP without IF EXISTS and without a "
                        + "'-- migration-lint: allow-unconditional-drop <reason>' opt-out"));
            }
            if (CREATE_INDEX_BLOCKING.matcher(st.sql()).find()
                && !hasMarker(st.scope(), "allow-blocking-index")) {
                out.add(new Violation(name, Rule.REPEATABLE_HAZARD,
                    "an R__ repeatable contains a blocking CREATE INDEX (not CONCURRENTLY) with no opt-out"));
            }
            if (!hasMarker(st.scope(), "allow-validating-constraint")) {
                for (String clause : topLevelClauses(st.sql())) {
                    if ((ADD_FK_OR_CHECK.matcher(clause).find() || INLINE_FK.matcher(clause).find())
                        && !NOT_VALID.matcher(clause).find()) {
                        out.add(new Violation(name, Rule.REPEATABLE_HAZARD,
                            "an R__ repeatable adds a validating constraint (no NOT VALID) with no opt-out"));
                        break;
                    }
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
