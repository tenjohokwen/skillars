package com.softropic.skillars.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Text-level rolling-deploy migration-safety lint (skillars-deferred-90 AC10, widened by
 * skillars-deferred-91 AC7 and skillars-deferred-92 AC7–AC11, hardened by the skillars-deferred-92
 * code review — see {@code deferred-work.md} for the 24-item list of evasions and false accusations
 * that review found and this revision closes).
 *
 * <p>Scans a directory of Flyway {@code V<n>__*.sql} scripts and reports the mechanical subset of
 * {@code docs/deployment/migration-conventions.md} that can be checked without a database. Only
 * scripts with a version <strong>strictly greater than</strong> {@code baselineVersion} are checked
 * — everything at or below the baseline is grandfathered (applied and immutable).
 *
 * <h2>Two baselines, and why they now share one value</h2>
 *
 * Before skillars-deferred-112, this history was two different bands. Flyway checksums a
 * migration's whole file, comments included, so an applied migration cannot be edited — not even to
 * add an opt-out marker — without breaking validation on every environment that already ran it. The
 * rules skillars-deferred-92 added ({@link Rule#MISSING_LOCK_TIMEOUT}, {@link Rule#UNBATCHED_DML},
 * {@link Rule#DROP_WITHOUT_PRIOR_RELEASE_PREP}, {@link Rule#PLATFORM_CONFIG_EXPLICIT_ID}) would have
 * flagged several already-applied migrations above the original {@code GRANDFATHER_BASELINE}, so
 * they bound from a second, later constant instead — the same reasoning that produced the first
 * baseline, applied a second time rather than quietly rewriting shipped migrations.
 *
 * <p>skillars-deferred-112 closed that gap by deletion, not by edit: the entire pre-baseline history
 * (formerly {@code V02}–{@code V137}) was replaced with a single generated baseline,
 * {@code V138__baseline_schema.sql} (pure DDL) plus {@code V139__baseline_seed_data.sql} (seed rows).
 * Both are grandfathered by design — {@code V138} is machine-generated from {@code pg_dump} and
 * necessarily contains validating {@code ADD CONSTRAINT}s and non-concurrent {@code CREATE INDEX}es
 * that are correct only because the file runs exactly once, against an empty database; linting it
 * would demand hundreds of opt-outs in generated output for zero safety gain. There is no longer a
 * band of already-applied migrations that the newer rules would flag but cannot be edited to exempt
 * — both constants now sit at the same rebaseline boundary, {@code V139}, and every migration from
 * {@code V140} onward is bound by the full rule set with no grandfather exceptions. The two constants
 * are kept distinct rather than collapsed into one (see {@code migration-rebaseline.md}'s follow-up
 * note) because {@link Rule#PLATFORM_CONFIG_EXPLICIT_ID} and friends are still, mechanically, a
 * separate rule band from the original expand/contract rules — they just happen to bind from the
 * same version now.
 *
 * <p><strong>The baseline comparison is version-aware, not major-only</strong> (code review): a
 * decimal minor version in the baseline band, e.g. {@code V139.1} when the baseline is {@code 139},
 * is strictly newer than the bare baseline and is bound by the new rules even though its major
 * component equals the baseline. See {@link #isAboveBaseline}.
 *
 * <h2>Statement scoping (skillars-deferred-92 AC11.2)</h2>
 *
 * {@code -- migration-lint: allow-*} markers used to be matched against the whole file, so one
 * opt-out silenced that rule for <em>every</em> statement in the migration — a documented blind spot.
 * A marker now scopes to the statement that follows it (precisely: to the text between the previous
 * statement terminator and this statement's own, including a trailing same-line comment that follows
 * this statement's own {@code ;} — code review: that comment belongs to the statement it was written
 * against, not the next one), which is where every existing marker in this repository already sits.
 * {@code drop-prepared-in} and {@code allow-drop-reference-scan} are scoped too, though more widely
 * than every other marker: to the window from the end of the <em>previous</em> drop-affecting
 * statement (or the start of the file) through this one, so the header-block convention every
 * fixture in this repository already uses — marker, then an unrelated {@code SET lock_timeout}
 * statement, then the drop — keeps working, while a SECOND, later drop the header marker says
 * nothing about no longer benefits from it (code review: these two were previously matched against
 * the whole file with no reset at all, which is the leak AC11.2 exists to close). See {@link
 * #lintDropOrdering}.
 *
 * <p>A marker is only honoured when it is an actual comment, never when the same text happens to
 * appear inside a string literal (code review: an {@code UPDATE} could otherwise opt itself out of
 * {@code UNBATCHED_DML} via its own data). See {@link #hasMarker}.
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
     * Highest migration version that predates this convention. Since skillars-deferred-112's true
     * squash, this is the generated baseline itself: {@code V138} (pure DDL) and
     * {@code V139} (seed data) are both machine-generated, run exactly once against an empty
     * database, and are correct as generated even though they contain patterns the rules below
     * would otherwise flag. The entire pre-baseline history they replaced (formerly
     * {@code V02}–{@code V137}, including V60/V94/V117/V124 and the other migrations once
     * individually grandfathered here) no longer exists — it was deleted, not edited, and is
     * recoverable from git history at commit {@code 4a3f218d} if ever needed. The guard binds
     * {@code V140+}, with zero grandfather exceptions.
     */
    public static final int GRANDFATHER_BASELINE = 139;

    /**
     * Historically a second, later boundary for the skillars-deferred-92 rules ({@link
     * Rule#MISSING_LOCK_TIMEOUT}, {@link Rule#UNBATCHED_DML}, {@link Rule#DROP_WITHOUT_PRIOR_RELEASE_PREP},
     * {@link Rule#PLATFORM_CONFIG_EXPLICIT_ID}), needed only because those rules arrived after some
     * migrations were already applied above {@link #GRANDFATHER_BASELINE} and could not be edited to
     * add opt-outs. skillars-deferred-112's squash deleted that whole band, so there is no longer a
     * gap between the two boundaries to bridge — both now sit at {@code V139}, the rebaseline
     * boundary. Kept as a distinct constant (not collapsed into {@link #GRANDFATHER_BASELINE}) because
     * the two still gate mechanically distinct rule sets; see {@code migration-rebaseline.md}'s
     * follow-up note for the case to collapse them properly in a future story.
     */
    public static final int DEFERRED_92_BASELINE = 139;

    /**
     * skillars-deferred-125 AC4: a third, later boundary for {@link Rule#SESSION_SCOPED_LOCK_TIMEOUT}
     * — the rule genuinely must not bind the ten already-shipped migrations that use a plain,
     * session-scoped {@code SET lock_timeout} ({@code V140}–{@code V150}, excluding the marker-only
     * {@code V141}; re-verify the actual real-migration tip at implementation/maintenance time — a
     * concurrent story could have added a newer one since), matching the same "cannot rewrite an
     * applied migration" reasoning that produced {@link #GRANDFATHER_BASELINE} and
     * {@link #DEFERRED_92_BASELINE} — restated here on its real grounds (churn/coordination cost
     * across every environment that already ran them, not Flyway checksum immutability alone;
     * {@code skillars-deferred-112}'s own squash-and-delete precedent already rewrote migration
     * history once, so "impossible to edit" is not the honest reason not to touch these ten).
     *
     * <p>Kept as a third, distinct constant rather than folded into {@link #DEFERRED_92_BASELINE}
     * (currently the same value, {@code 139}): this class's own Javadoc records that the first two
     * baselines are kept apart only <em>provisionally</em>, with a follow-up note in
     * {@code migration-rebaseline.md} for the case to collapse them properly in a future story — it
     * does not present "add a new boundary constant" as the established forward direction. A third
     * boundary is still the right call here (this rule's grandfathered band, {@code V140}–{@code
     * V150}, is strictly newer than — and disjoint from — the {@code V139} band the other two
     * baselines share), but is added with that context in mind rather than as if it were this class's
     * stated intent.
     */
    public static final int SESSION_SCOPED_LOCK_TIMEOUT_BASELINE = 150;

    /**
     * Sentinel passed to {@link #lintDropOrdering} for an {@code R__} repeatable, which has no
     * version number to compare a {@code drop-prepared-in} marker's release against (code review:
     * repeatables were previously exempt from all four skillars-deferred-92 rules outright).
     */
    private static final int NO_ORDERING_CHECK = -1;

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
         * ({@code -- migration-lint: drop-prepared-in: V123}), that release must genuinely be
         * <em>before</em> this one, and no live reference to the dropped identifier may remain in
         * {@code src/main}.
         */
        DROP_WITHOUT_PRIOR_RELEASE_PREP,
        /**
         * skillars-deferred-92 AC8: lock-taking DDL with no bounded {@code SET lock_timeout} in
         * effect, so it waits indefinitely — and every later query on the table queues behind the
         * blocked statement.
         */
        MISSING_LOCK_TIMEOUT,
        /** skillars-deferred-92 AC9: an {@code UPDATE}/{@code DELETE}/{@code TRUNCATE} that cannot bound its row count. */
        UNBATCHED_DML,
        /**
         * skillars-deferred-92 AC10.3: an {@code INSERT INTO main.platform_config} that supplies an
         * explicit {@code id}, whether named in a column list or implied by omitting the column list
         * entirely. {@code V128} gave the column an identity; a hand-picked id collides on the
         * primary key, which the {@code ON CONFLICT (key)} clause never sees.
         */
        PLATFORM_CONFIG_EXPLICIT_ID,
        /**
         * skillars-deferred-125 AC4: a plain, session-scoped {@code SET lock_timeout} (not
         * {@code SET LOCAL}) in a migration above {@link #SESSION_SCOPED_LOCK_TIMEOUT_BASELINE}.
         * Flyway runs every pending migration in a deploy over one reused JDBC session (no
         * {@code group}/{@code mixed} option configured), so a session-scoped {@code SET} in one
         * migration silently carries forward into a later migration in the same deploy that never
         * declared its own timeout — {@code SET LOCAL} resets automatically at
         * {@code COMMIT}/{@code ROLLBACK} and closes that leak. Exempt: a migration with the
         * {@code executeInTransaction=false} sidecar present (no enclosing transaction for
         * {@code SET LOCAL} to bind to — see {@code docs/deployment/migration-conventions.md} rule 6)
         * and a statement carrying {@code -- migration-lint: allow-session-lock-timeout <reason>}.
         */
        SESSION_SCOPED_LOCK_TIMEOUT,
        /**
         * skillars-deferred-125 AC4 code-review fix (2026-09-21, owner decision — option 1 of 3, see
         * {@code optionsAndRecommendations.md}): the {@link Rule#SESSION_SCOPED_LOCK_TIMEOUT} sidecar
         * exemption above is correct — {@code SET LOCAL} genuinely cannot work with no enclosing
         * transaction — but leaves the rule's own stated leak not merely possible but <em>guaranteed</em>
         * in exactly this case: a non-transactional migration has no {@code COMMIT} to bound a plain
         * {@code SET} the way {@code SET LOCAL} would. A sidecar migration that sets
         * {@code lock_timeout} must therefore carry a later, hand-rolled {@code RESET lock_timeout} —
         * the manual equivalent of what {@code SET LOCAL} does automatically at {@code COMMIT} — or
         * silence this with {@code -- migration-lint: allow-session-lock-timeout <reason>}.
         */
        SIDECAR_LOCK_TIMEOUT_NOT_RESET
    }

    public record Violation(String file, Rule rule, String detail) {
        @Override
        public String toString() {
            return file + " :: " + rule + " — " + detail;
        }
    }

    /**
     * Flyway versioned migration. The version may be dotted or underscore-separated
     * ({@code V122.1__x.sql}, {@code V122_1__x.sql}); every component is parsed and compared, not
     * just the major one — see {@link #isAboveBaseline}.
     *
     * <p>Code review (3-layer run): this was {@code ^V(\d+)__.*\.sql$}, so a decimal-versioned
     * migration matched nothing and was skipped entirely — an unguarded DROP, a blocking index or a
     * validating constraint in a {@code V122.1__} file merged unlinted. Anything that looks like a
     * versioned migration but cannot be parsed is now reported rather than ignored (see
     * {@link #lintFile}).
     */
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+(?:[._]\\d+)*)__.*\\.sql$");
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
    // CONSTRAINT added by the skillars-deferred-91 code review: V124's own
    // `ALTER TABLE payment.booking_payments DROP CONSTRAINT chk_bp_status` was unlinted and fails
    // hard if the constraint is ever already absent (a re-run, a diverged environment).
    //
    // skillars-deferred-92 code review: `COLUMN` is ALSO optional on a DROP clause
    // (`ALTER TABLE t DROP col_name` is identical to `ALTER TABLE t DROP COLUMN col_name`), so a
    // literal-keyword rule for COLUMN misses that spelling entirely. TABLE/INDEX/CONSTRAINT are NOT
    // optional in PostgreSQL's grammar — only COLUMN is — so this pattern keeps requiring the literal
    // keyword for those three, and {@link #DROP_COLUMN_CLAUSE} separately covers the column case,
    // with or without the keyword.
    private static final Pattern DROP_NO_IF_EXISTS_KEYWORD =
        Pattern.compile("(?is)\\bDROP\\s+(TABLE|INDEX|CONSTRAINT)\\b(?!\\s+IF\\s+EXISTS)");
    private static final Pattern CREATE_INDEX_BLOCKING =
        Pattern.compile("(?is)\\bCREATE\\s+(UNIQUE\\s+)?INDEX\\b(?!\\s+CONCURRENTLY)");
    private static final Pattern ADD_FK_OR_CHECK =
        Pattern.compile("(?is)\\bADD\\s+CONSTRAINT\\b.*?\\b(FOREIGN\\s+KEY|CHECK)\\b");
    private static final Pattern NOT_VALID = Pattern.compile("(?i)\\bNOT\\s+VALID\\b");

    // --- DROP target patterns (COLUMN keyword optional; ONLY keyword skipped) -----------------

    /**
     * A {@code DROP [COLUMN] [IF EXISTS] identifier} clause. {@code COLUMN} is optional in
     * PostgreSQL's grammar for an {@code ALTER TABLE} sub-clause, so this deliberately does NOT
     * require the literal keyword — {@code TABLE}/{@code INDEX}/{@code CONSTRAINT} are excluded by
     * the negative lookaheads so this never fires on those (mandatory-keyword) forms.
     *
     * <p>Group 1: {@code "IF EXISTS"} if present, else {@code null}. Group 2: the identifier.
     *
     * <p>skillars-deferred-92 code review: the previous version of this rule required the literal
     * {@code COLUMN} keyword, so {@code ALTER TABLE t ADD nickname varchar(50)} and
     * {@code ALTER TABLE t DROP obsolete_col} — both legal, both the identical hazard as their
     * {@code COLUMN}-qualified spellings — evaded {@link Rule#DROP_WITHOUT_IF_EXISTS},
     * {@link Rule#DROP_WITHOUT_PRIOR_RELEASE_PREP} and {@link Rule#BARE_DROP_NO_HEADER} outright.
     */
    private static final Pattern DROP_COLUMN_CLAUSE = Pattern.compile(
        "(?is)\\bDROP\\s+(?:COLUMN\\s+)?(?:(IF\\s+EXISTS)\\s+)?(?!TABLE\\b)(?!INDEX\\b)(?!CONSTRAINT\\b)([\\w\"]+)");
    private static final Pattern DROP_TABLE_KEYWORD = Pattern.compile("(?is)\\bDROP\\s+TABLE\\b");
    private static final Pattern DROP_TABLE_PREFIX =
        Pattern.compile("(?is)\\bDROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?");
    /** Skips the optional {@code ONLY} keyword — code review: it was captured as the table name. */
    private static final Pattern ALTER_TABLE_NAME = Pattern.compile(
        "(?is)\\bALTER\\s+TABLE\\s+(?:ONLY\\s+)?(?:IF\\s+EXISTS\\s+)?([\\w.\"]+)");

    // --- skillars-deferred-92 patterns -------------------------------------------------------

    /** {@code -- migration-lint: drop-prepared-in: V123} (AC7). */
    private static final Pattern DROP_PREPARED_IN =
        Pattern.compile("(?i)migration-lint:\\s*drop-prepared-in:\\s*(V?\\d+(?:[._]\\d+)*)");
    /**
     * DDL inside {@code ALTER TABLE} that takes a lock blocking concurrent DML or DDL. {@code COLUMN}
     * is optional throughout PostgreSQL's {@code ALTER TABLE} grammar for {@code ADD}/{@code DROP}/
     * {@code ALTER}/{@code RENAME} — code review: the previous version required the literal keyword
     * and so missed {@code ADD nickname varchar(50)} (no {@code COLUMN}) entirely, both for
     * {@link Rule#MISSING_LOCK_TIMEOUT} and the {@code DROP} rule family above.
     */
    private static final Pattern ACCESS_EXCLUSIVE_ALTER = Pattern.compile(
        "(?is)\\bALTER\\s+TABLE\\b(?:(?!;).)*?\\b(?:"
            + "(?:ADD|DROP)\\s+(?:COLUMN\\s+)?(?!CONSTRAINT\\b|PRIMARY\\s+KEY\\b)[\\w\"]"
            + "|ALTER\\s+(?:COLUMN\\s+)?[\\w\"]"
            + "|RENAME\\s+(?:COLUMN\\s+)?[\\w\"]"
            + "|(?:ADD|DROP)\\s+CONSTRAINT\\b"
            + "|ADD\\s+PRIMARY\\s+KEY\\b"
            + ")");
    /**
     * Top-level {@code ACCESS EXCLUSIVE} DDL that is not an {@code ALTER TABLE} sub-clause.
     * {@code TRUNCATE} added by the code review: it takes {@code ACCESS EXCLUSIVE} and is also an
     * unconditional full-table write that {@link Rule#UNBATCHED_DML}'s {@code UPDATE}/{@code DELETE}
     * pattern never recognised.
     */
    private static final Pattern TOP_LEVEL_ACCESS_EXCLUSIVE =
        Pattern.compile("(?is)\\bDROP\\s+TABLE\\b|\\bDROP\\s+INDEX\\b|\\bTRUNCATE\\b");
    /** Any index build, concurrent or not — all of them take a lock worth bounding (AC8.1). */
    private static final Pattern ANY_CREATE_INDEX =
        Pattern.compile("(?is)\\bCREATE\\s+(UNIQUE\\s+)?INDEX\\b");
    /**
     * A {@code SET}/{@code SET LOCAL lock_timeout = <value>}, or {@code RESET lock_timeout}, scanned
     * in document order so a {@code RESET} — or a later {@code SET} to {@code 0} — correctly
     * un-bounds a wait a prior {@code SET} had bounded. Group 1 (only present for a {@code SET}) is
     * the raw value text.
     *
     * <p>skillars-deferred-92 code review: the previous rule only checked for the literal keyword's
     * <em>presence</em> anywhere earlier in the file, so {@code SET lock_timeout = 0;} — PostgreSQL's
     * own spelling for "wait forever" — satisfied the rule it exists to prevent, and a
     * {@code RESET lock_timeout} after a valid {@code SET} was invisible too.
     */
    // skillars-deferred-125 AC4: group 1 now captures "LOCAL" when present (null for a plain SET,
    // including "SET SESSION ...", which is session-scoped like a bare SET — SESSION is just the
    // explicit spelling of the default scope) — isLockTimeoutBoundedAt needs to know which spelling
    // bounded the statement, since only a plain SET's bound survives a mid-file COMMIT/ROLLBACK; SET
    // LOCAL's does not. Group 2 (was group 1) is still the raw value text.
    //
    // /bmad-code-review fix (2026-09-21): "SET SESSION lock_timeout" (valid Postgres — SESSION is the
    // explicit, rarely-written spelling of the scope SET already defaults to) evaded this pattern
    // entirely, so a genuinely-bounded statement using that spelling was flagged MISSING_LOCK_TIMEOUT.
    private static final Pattern LOCK_TIMEOUT_DIRECTIVE = Pattern.compile(
        "(?i)\\bRESET\\s+lock_timeout\\b|\\bSET\\s+(?:(LOCAL)\\s+|SESSION\\s+)?lock_timeout\\s*(?:=|TO)\\s*('[^']*'|[\\w]+)");
    /**
     * skillars-deferred-125 AC4 — {@link Rule#SESSION_SCOPED_LOCK_TIMEOUT}. A plain {@code SET
     * lock_timeout}, including the explicit {@code SET SESSION lock_timeout} spelling (the negative
     * lookahead excludes only {@code SET LOCAL}) — distinct from {@link #LOCK_TIMEOUT_DIRECTIVE},
     * which matches both spellings plus {@code RESET} for the pre-existing {@code
     * MISSING_LOCK_TIMEOUT} "is a bound genuinely in effect" check.
     */
    private static final Pattern SESSION_SCOPED_SET_LOCK_TIMEOUT = Pattern.compile(
        "(?i)\\bSET\\s+(?!LOCAL\\b)(?:SESSION\\s+)?lock_timeout\\s*(?:=|TO)\\s*('[^']*'|[\\w]+)");
    /**
     * skillars-deferred-125 AC4 code-review fix (2026-09-21) — {@link Rule#SIDECAR_LOCK_TIMEOUT_NOT_RESET}:
     * a bare {@code RESET lock_timeout}, searched for anywhere after a sidecar migration's plain
     * {@code SET lock_timeout}.
     */
    private static final Pattern RESET_LOCK_TIMEOUT = Pattern.compile("(?i)\\bRESET\\s+lock_timeout\\b");
    /**
     * skillars-deferred-125 AC4 code-review fix (2026-09-21) — a Flyway {@code .conf} sidecar's
     * {@code executeInTransaction=false} directive, tolerant of whitespace around {@code =} and case
     * in the boolean literal. Matched against one already-comment-stripped line at a time — see
     * {@link #hasNonTransactionalSidecar}.
     */
    private static final Pattern EXECUTE_IN_TRANSACTION_FALSE =
        Pattern.compile("(?i)executeInTransaction\\s*=\\s*false");
    /**
     * skillars-deferred-125 AC4 Task 4: a top-level transaction-boundary statement. {@code SET LOCAL}'s
     * effect ends here — {@link #isLockTimeoutBoundedAt} must stop treating it as bounding statements
     * after this point in the same file, which the previous version (a pure forward replay with no
     * transaction-boundary concept) did not.
     *
     * <p>/bmad-code-review fix (2026-09-21): also recognises {@code END} and {@code ABORT} — Postgres's
     * own synonyms for {@code COMMIT}/{@code ROLLBACK} — and excludes {@code ON COMMIT} (the {@code
     * CREATE TEMP TABLE ... ON COMMIT DROP/DELETE ROWS/PRESERVE ROWS} clause, not a real transaction
     * boundary). This operates on {@link #stripComments}'d text, which already blanks dollar-quoted
     * bodies entirely — a PL/pgSQL {@code END IF}/{@code END LOOP}/block {@code END} inside a
     * {@code $$...$$} function body never reaches this pattern. The residual risk is a bare, top-level
     * {@code CASE ... END} expression outside any dollar-quoted body, which would be misread as a
     * boundary; no real migration in this repository does that today (verified by grep), and it is
     * accepted rather than built out into a real SQL parser.
     */
    private static final Pattern TRANSACTION_BOUNDARY =
        Pattern.compile("(?i)(?<!\\bON\\s{1,4})\\b(?:COMMIT|ROLLBACK|END|ABORT)\\b");
    /** {@code TRUNCATE [TABLE] ...} at the start of a statement. */
    private static final Pattern TRUNCATE_STATEMENT = Pattern.compile("(?is)^\\s*TRUNCATE\\b");
    /**
     * A statement that is, or begins with a CTE feeding, a data-modifying write. Gates
     * {@link #topLevelIndexOf} against a stray {@code UPDATE}/{@code DELETE} token inside an
     * unrelated statement's string literal — see {@link #lintUnbatchedDml}.
     */
    private static final Pattern DML_STATEMENT_START = Pattern.compile("(?is)^\\s*(?:WITH|UPDATE|DELETE)\\b");
    private static final Pattern DML_KEYWORD = Pattern.compile("(?i)\\b(?:UPDATE|DELETE)\\b");
    private static final Pattern WHERE_KEYWORD = Pattern.compile("(?i)\\bWHERE\\b");
    /** The ENTIRE predicate, not a prefix — {@code WHERE 1=1 AND id = 7} must not match this. */
    private static final Pattern TAUTOLOGICAL_PREDICATE = Pattern.compile("(?i)^(?:TRUE|1\\s*=\\s*1)$");
    private static final Pattern PLATFORM_CONFIG_INSERT_WITH_COLS = Pattern.compile(
        "(?is)\\bINSERT\\s+INTO\\s+(?:\"?main\"?\\s*\\.\\s*)?\"?platform_config\"?\\s*\\(([^)]*)\\)");
    /**
     * {@code INSERT INTO main.platform_config VALUES (...)} with no explicit column list at all —
     * code review: the original pattern required a literal {@code (} right after the table name,
     * i.e. an explicit column list, so an insert that supplies every column positionally (id
     * included, by table order) was invisible. Quoted identifiers ({@code "main"."platform_config"})
     * were also unmatched.
     */
    private static final Pattern PLATFORM_CONFIG_INSERT_NO_COLS = Pattern.compile(
        "(?is)\\bINSERT\\s+INTO\\s+(?:\"?main\"?\\s*\\.\\s*)?\"?platform_config\"?\\s+VALUES\\b");
    /**
     * Marks the start of a dollar-quoted body: a tagged form ({@code $fn$}) or the plain form
     * ({@code $$}). Code review: the previous scanner recognised only the plain form, so a tagged
     * body was torn into fragments at any {@code ;} inside it, producing violations on SQL the
     * migration never executes as written (see {@link #statements}).
     */
    private static final Pattern DOLLAR_QUOTE_TAG = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*\\$|\\$\\$");

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
        return lint(dir, baselineVersion, deferred92Baseline, SESSION_SCOPED_LOCK_TIMEOUT_BASELINE,
            knownAtHead, sourceRoots);
    }

    /**
     * @param sessionScopedLockTimeoutBaseline versions at or below this are additionally
     *     grandfathered from {@link Rule#SESSION_SCOPED_LOCK_TIMEOUT} (skillars-deferred-125 AC4) —
     *     see {@link #SESSION_SCOPED_LOCK_TIMEOUT_BASELINE}'s own Javadoc for why this is a third,
     *     distinct constant rather than reusing {@code deferred92Baseline}
     */
    public static List<Violation> lint(Path dir, int baselineVersion, int deferred92Baseline,
                                       int sessionScopedLockTimeoutBaseline, Predicate<Path> knownAtHead,
                                       List<Path> sourceRoots)
            throws IOException {
        final List<Violation> violations = new ArrayList<>();
        // skillars-deferred-92 code review: Files.list is non-recursive, so a migration in a
        // subdirectory of the Flyway location (Flyway's own classpath scan IS recursive) was never
        // linted at all — the same silent-skip class UNPARSEABLE_VERSION exists to eliminate.
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.getFileName().toString().endsWith(".sql"))
                 .sorted()
                 .forEach(p -> lintFile(p, baselineVersion, deferred92Baseline,
                     sessionScopedLockTimeoutBaseline, knownAtHead, sourceRoots, violations));
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
     *     terminator (plus a trailing same-line comment, if any), comments included
     * @param sql the same statement with comments stripped, literal-aware (see {@link #stripComments})
     * @param offset {@code scope}'s real starting position within the file's raw text — code review:
     *     this used to be recovered with {@code raw.indexOf(st.scope())}, a substring search that
     *     silently returns the position of the FIRST occurrence, so two textually identical
     *     statements collapsed onto the same offset
     */
    private record Statement(String scope, String sql, int offset) {
    }

    /**
     * Splits on {@code ;} at the top level, skipping terminators inside line comments, block
     * comments, single-quoted literals and dollar-quoted bodies (tagged or plain).
     *
     * <p>A trailing same-line comment after a statement's own {@code ;} is folded into THAT
     * statement's scope rather than the next one's (code review: an opt-out written as
     * {@code "…; -- migration-lint: allow-blocking-index reason"} was silently binding to the
     * following statement, because scope used to start right after the {@code ;}).
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
            } else if (c == '$') {
                Matcher tag = DOLLAR_QUOTE_TAG.matcher(raw);
                tag.region(i, raw.length());
                if (tag.lookingAt()) {
                    String delimiter = tag.group();
                    int close = raw.indexOf(delimiter, tag.end());
                    i = close < 0 ? raw.length() : close + delimiter.length();
                } else {
                    i++;
                }
            } else if (c == ';') {
                int end = i + 1;
                int j = end;
                while (j < raw.length() && (raw.charAt(j) == ' ' || raw.charAt(j) == '\t')) {
                    j++;
                }
                if (j + 1 < raw.length() && raw.charAt(j) == '-' && raw.charAt(j + 1) == '-') {
                    int nl = raw.indexOf('\n', j);
                    end = nl < 0 ? raw.length() : nl + 1;
                }
                String scope = raw.substring(start, end);
                out.add(new Statement(scope, stripComments(scope), start));
                start = end;
                i = end;
            } else {
                i++;
            }
        }
        String tail = raw.substring(start);
        if (!stripComments(tail).isBlank()) {
            out.add(new Statement(tail, stripComments(tail), start));
        }
        return out;
    }

    /**
     * Splits an {@code ALTER TABLE} statement's clauses on top-level commas, so each
     * {@code ADD CONSTRAINT} / {@code DROP COLUMN} is evaluated independently (AC11.1, and — code
     * review — AC7's multi-clause {@code DROP} the same way: {@code DROP COLUMN a, DROP COLUMN b}
     * used to have only {@code a} reference-scanned, since the target regex needed a fresh
     * {@code ALTER TABLE} per match).
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

    /**
     * The index of {@code keyword}'s first match at paren-depth 0 in {@code sql}, or {@code -1}.
     * Used to find a statement's real {@code UPDATE}/{@code DELETE}/{@code WHERE} — as opposed to
     * one buried inside a subquery's parentheses, which does not bound (or belong to) the outer
     * statement (code review: {@code UPDATE t SET x = (SELECT v FROM u WHERE u.id = 1)} has no outer
     * {@code WHERE} at all, and the old substring search found the subquery's).
     */
    private static int topLevelIndexOf(String sql, Pattern keyword) {
        int depth = 0;
        Matcher m = keyword.matcher(sql);
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0) {
                m.region(i, sql.length());
                if (m.lookingAt()) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * True if {@code marker} appears, as an actual comment (never inside a string literal — code
     * review: {@code UPDATE t SET note = 'migration-lint: allow-full-table-dml'} used to opt itself
     * out via its own data), anywhere in {@code scope}. Whitespace after the colon is not required
     * (code review: {@code migration-lint:allow-x} and {@code migration-lint:  allow-x} were both
     * previously unrecognised, producing a violation telling the author to add the marker they had
     * already added).
     */
    private static boolean hasMarker(String scope, String marker) {
        return Pattern.compile("(?i)migration-lint:\\s*" + Pattern.quote(marker))
            .matcher(extractComments(scope)).find();
    }

    // --- file linting -------------------------------------------------------------------------

    private static void lintFile(Path file, int baselineVersion, int deferred92Baseline,
                                 int sessionScopedLockTimeoutBaseline, Predicate<Path> knownAtHead,
                                 List<Path> sourceRoots, List<Violation> out) {
        final String name = file.getFileName().toString();
        final Matcher vm = VERSIONED.matcher(name);
        if (!vm.matches()) {
            if (REPEATABLE.matcher(name).matches()) {
                lintRepeatable(file, name, sourceRoots, out);
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

        final List<Integer> versionParts;
        try {
            versionParts = parseVersionParts(vm.group(1));
        } catch (NumberFormatException e) {
            // code review: a Flyway-legal timestamp version (V20260904120000__x.sql) overflows a
            // 32-bit int and used to propagate an uncaught NumberFormatException out of `lint`,
            // aborting the whole run — every OTHER migration in the directory went unchecked and the
            // failure surfaced as a stack trace instead of the UNPARSEABLE_VERSION this exists for.
            out.add(new Violation(name, Rule.UNPARSEABLE_VERSION,
                "the version '" + vm.group(1) + "' cannot be parsed as a 32-bit integer, so it cannot "
                    + "be compared against the baseline — rename to V<major>[.<minor>]__<description>.sql"));
            return;
        }
        final int majorVersion = versionParts.get(0);

        if (!isAboveBaseline(versionParts, baselineVersion)) {
            if (!knownAtHead.test(file)) {
                out.add(new Violation(name, Rule.BACKPORT_BELOW_BASELINE,
                    "a V" + majorVersion + " script at or below the V" + baselineVersion + " grandfather "
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
        final boolean deferred92Applies = isAboveBaseline(versionParts, deferred92Baseline);
        // skillars-deferred-125 AC4: the sidecar check is file-level (a non-transactional migration
        // has no enclosing transaction for SET LOCAL to bind to anywhere in the file), so it is
        // resolved once here rather than per statement. Also feeds lintLockTimeout/
        // isLockTimeoutBoundedAt (code review fix 2026-09-21) — a SET LOCAL is a no-op there too, and
        // must not silently satisfy MISSING_LOCK_TIMEOUT either.
        final boolean nonTransactionalSidecar = hasNonTransactionalSidecar(file);
        final boolean aboveSessionScopedBaseline = isAboveBaseline(versionParts, sessionScopedLockTimeoutBaseline);
        final boolean sessionScopedLockTimeoutApplies = aboveSessionScopedBaseline && !nonTransactionalSidecar;
        // skillars-deferred-125 AC4 code-review fix (2026-09-21, owner decision — option 1): the
        // mirror-image check to sessionScopedLockTimeoutApplies above — runs exactly when a sidecar
        // migration IS exempt from SESSION_SCOPED_LOCK_TIMEOUT, closing the leak that exemption would
        // otherwise leave open. Same baseline, opposite sidecar condition.
        final boolean sidecarLockTimeoutResetApplies = aboveSessionScopedBaseline && nonTransactionalSidecar;

        int dropScopeStart = 0;
        for (Statement st : statements) {
            lintStatement(name, st, out);
            if (deferred92Applies) {
                int stEnd = Math.min(raw.length(), st.offset() + st.scope().length());
                String dropScope = raw.substring(dropScopeStart, stEnd);
                lintStatementDeferred92(name, st, raw, dropScope, sourceRoots, out, majorVersion,
                    nonTransactionalSidecar);
                if (dropsTableOrColumn(st.sql())) {
                    dropScopeStart = stEnd;
                }
            }
            if (sessionScopedLockTimeoutApplies) {
                lintSessionScopedLockTimeout(name, st, out);
            }
            if (sidecarLockTimeoutResetApplies) {
                lintSidecarLockTimeoutReset(name, st, raw, out);
            }
        }

        if (dropsTableOrColumn(stripComments(raw)) && !hasHeaderComment(raw)) {
            out.add(new Violation(name, Rule.BARE_DROP_NO_HEADER,
                "performs a table/column DROP but has no leading header comment block explaining the expand/contract sequencing"));
        }
    }

    /** Splits a dotted/underscore version string into its integer components. */
    private static List<Integer> parseVersionParts(String version) {
        final List<Integer> parts = new ArrayList<>();
        for (String piece : version.split("[._]")) {
            parts.add(Integer.parseInt(piece));
        }
        return parts;
    }

    /**
     * True if {@code versionParts} is strictly newer than the bare-integer {@code baseline}.
     *
     * <p>skillars-deferred-92 code review: comparing only the major component let a decimal minor
     * version in the baseline band evade every rule bound to that baseline — {@code V127.1} against
     * {@code DEFERRED_92_BASELINE = 127} compared {@code 127 <= 127} and was silently grandfathered,
     * even though {@code V127.1} is a later migration than {@code V127} and was never applied before
     * the new rules existed. Any nonzero minor component makes the version newer than a bare baseline.
     */
    private static boolean isAboveBaseline(List<Integer> versionParts, int baseline) {
        int major = versionParts.get(0);
        if (major != baseline) {
            return major > baseline;
        }
        for (int i = 1; i < versionParts.size(); i++) {
            if (versionParts.get(i) != 0) {
                return true;
            }
        }
        return false;
    }

    /** The skillars-deferred-90/-91 rules, now evaluated per statement rather than per file. */
    private static void lintStatement(String name, Statement st, List<Violation> out) {
        if (DROP_NO_IF_EXISTS_KEYWORD.matcher(st.sql()).find()) {
            out.add(new Violation(name, Rule.DROP_WITHOUT_IF_EXISTS,
                "a DROP TABLE/INDEX/CONSTRAINT is missing IF EXISTS"));
        }
        for (String clause : topLevelClauses(st.sql())) {
            Matcher col = DROP_COLUMN_CLAUSE.matcher(clause);
            if (col.find() && col.group(1) == null) {
                out.add(new Violation(name, Rule.DROP_WITHOUT_IF_EXISTS,
                    "a DROP COLUMN is missing IF EXISTS (COLUMN is optional in PostgreSQL — 'DROP "
                        + "col_name' is the identical hazard as 'DROP COLUMN col_name'): " + oneLine(clause)));
            }
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

    /**
     * The rules skillars-deferred-92 added; bind above {@link #DEFERRED_92_BASELINE} only.
     *
     * @param dropScope the text a {@code drop-prepared-in} / {@code allow-drop-reference-scan}
     *     marker for THIS statement may appear in — see {@link #lintDropOrdering}
     */
    private static void lintStatementDeferred92(String name, Statement st, String raw, String dropScope,
                                                List<Path> sourceRoots, List<Violation> out, int version,
                                                boolean nonTransactionalSidecar) {
        lintDropOrdering(name, st, dropScope, sourceRoots, out, version);
        lintLockTimeout(name, st, raw, nonTransactionalSidecar, out);
        lintUnbatchedDml(name, st, out);
        lintPlatformConfigId(name, st, out);
    }

    /** True if {@code sql} contains a {@code DROP TABLE}, or a column drop with or without the optional {@code COLUMN} keyword. */
    private static boolean dropsTableOrColumn(String sql) {
        return DROP_TABLE_KEYWORD.matcher(sql).find() || DROP_COLUMN_CLAUSE.matcher(sql).find();
    }

    /**
     * AC7 — expand/contract <strong>ordering</strong>, which the shape rules never checked. During a
     * rolling deploy the old pods are still reading a column when the new pod's migration drops it;
     * {@code skillars-11-3} D2 shipped exactly that and passed the lint clean.
     *
     * <p>Three parts, and each is what makes the previous one more than decoration: the migration
     * must carry a marker, in ITS OWN statement scope, naming the release that removed the last
     * reader; that release must genuinely be earlier than this migration's own version (code review:
     * the named release used to be echoed into the message and never checked — a migration could
     * name its own version, or one that never shipped, and pass); and no live reference to the
     * dropped identifier may remain in {@code src/main}.
     *
     * <p><strong>What the reference search actually guarantees</strong> (AC7.2 requires this be
     * stated, not implied). Column names like {@code id}, {@code status} or {@code amount} are far
     * too generic to grep for, so a match requires the {@code snake_case} identifier <em>and</em> the
     * owning table's bare name to appear in the same file — both matched at word boundaries (code
     * review: a bare substring match made every {@code id}-class column unusable, since e.g. the word
     * {@code Invalid} contains the substring {@code id}) — plus the camelCase JPA field name where
     * that is distinctive. A dropped column whose reader lives in a file that never mentions the table
     * name — a raw SQL string assembled from fragments, say — will not be found. For those, the
     * marker alone is the guarantee. {@code -- migration-lint: allow-drop-reference-scan <reason>}
     * suppresses the search when its output is noise; the marker and ordering requirements still stand.
     *
     * @param dropScope the window a marker for THIS drop may appear in: from the end of the
     *     <em>previous</em> drop-affecting statement in the file (or the start of the file, for the
     *     first one) through the end of this statement. Deliberately wider than {@link
     *     Statement#scope()} — code review: statement-scoping this marker as narrowly as every other
     *     one would break the header-block convention every fixture in this repository already uses
     *     (marker, then an unrelated {@code SET lock_timeout} statement, then the drop), while a
     *     RESET at the previous drop still closes the original leak the review found: a header marker
     *     no longer covers a SECOND, later drop it says nothing about.
     */
    private static void lintDropOrdering(String name, Statement st, String dropScope,
                                         List<Path> sourceRoots, List<Violation> out, int version) {
        if (!dropsTableOrColumn(st.sql())) {
            return;
        }
        Matcher prepared = DROP_PREPARED_IN.matcher(extractComments(dropScope));
        if (!prepared.find()) {
            out.add(new Violation(name, Rule.DROP_WITHOUT_PRIOR_RELEASE_PREP,
                "a DROP TABLE/COLUMN must name the release in which the last reader was removed — add "
                    + "'-- migration-lint: drop-prepared-in: V<n>' above this statement (and after any "
                    + "earlier DROP in the same file — a marker does not carry across two drops). "
                    + "Dropping in the same release that stops reading is the rolling-deploy hazard the "
                    + "expand/contract standard exists to prevent: old pods are still reading the column "
                    + "when the new pod's migration removes it"));
            return;
        }
        if (version != NO_ORDERING_CHECK) {
            Integer preparedVersion = leadingVersionNumber(prepared.group(1));
            if (preparedVersion != null && preparedVersion >= version) {
                out.add(new Violation(name, Rule.DROP_WITHOUT_PRIOR_RELEASE_PREP,
                    "the marker names " + prepared.group(1) + " as the release that prepared this drop, "
                        + "but that is not strictly before this migration's own V" + version + " — "
                        + "dropping in the same release (or a later one) as the one that stops reading is "
                        + "exactly the ordering hazard this rule exists to catch"));
                return;
            }
        }
        if (hasMarker(dropScope, "allow-drop-reference-scan")) {
            return;
        }

        for (String[] target : droppedIdentifiers(st.sql())) {
            final String table = target[0];
            final String identifier = target[1];
            List<String> offenders = referencesIn(sourceRoots, table, identifier);
            if (!offenders.isEmpty()) {
                out.add(new Violation(name, Rule.DROP_WITHOUT_PRIOR_RELEASE_PREP,
                    "the marker claims this DROP was prepared in " + prepared.group(1) + ", but '"
                        + identifier + "' still appears alongside '" + table + "' in " + offenders
                        + " — a marker that claims preparation while the code still reads the object is "
                        + "the failure worth catching. Remove the reads first, or add "
                        + "'-- migration-lint: allow-drop-reference-scan <reason>' if these are false "
                        + "positives"));
            }
        }
    }

    /** The leading integer of a version token like {@code "V123"}, {@code "123"} or {@code "123.1"}. */
    private static Integer leadingVersionNumber(String token) {
        Matcher m = Pattern.compile("(\\d+)").matcher(token);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /**
     * {@code {table, identifier}} pairs for each {@code DROP TABLE} / column-drop clause in
     * {@code sql}, including every clause of a multi-target statement (code review: the previous
     * version scanned only the first {@code DROP TABLE a, b} name and the first {@code DROP COLUMN}
     * clause of a multi-clause {@code ALTER TABLE}, so a comma-joined second target evaded the scan
     * entirely).
     */
    private static List<String[]> droppedIdentifiers(String sql) {
        final List<String[]> out = new ArrayList<>();

        Matcher dt = DROP_TABLE_PREFIX.matcher(sql);
        if (dt.find()) {
            String rest = sql.substring(dt.end());
            int semi = rest.indexOf(';');
            String namesPart = semi < 0 ? rest : rest.substring(0, semi);
            for (String piece : topLevelClauses(namesPart)) {
                String t = bareName(piece.strip());
                if (!t.isEmpty()) {
                    out.add(new String[] {t, t});
                }
            }
        }

        Matcher at = ALTER_TABLE_NAME.matcher(sql);
        if (at.find()) {
            String table = bareName(at.group(1));
            for (String clause : topLevelClauses(sql)) {
                Matcher col = DROP_COLUMN_CLAUSE.matcher(clause);
                if (col.find()) {
                    out.add(new String[] {table, bareName(col.group(2))});
                }
            }
        }

        return out;
    }

    private static String bareName(String qualified) {
        String s = qualified.replace("\"", "");
        int dot = s.lastIndexOf('.');
        return dot < 0 ? s : s.substring(dot + 1);
    }

    /**
     * skillars-deferred-99 AC7: the source corpus (absolute-path string → file body) for one set of
     * roots, walked and read <strong>once</strong> and memoised for the test JVM. Before this,
     * {@link #referencesIn} did its own {@code Files.walk} + {@code readString} over every
     * {@code .java/.sql/.yaml/.yml/.xml/.properties/.html/.json} file <em>once per dropped
     * identifier</em>, so a migration dropping N identifiers re-walked and re-read the whole source
     * tree N times. The corpus is immutable during a run — nothing edits {@code src/main} mid-test.
     */
    private static final Map<List<Path>, Map<String, String>> SOURCE_CORPUS_CACHE = new ConcurrentHashMap<>();

    private static Map<String, String> sourceCorpus(List<Path> roots) {
        return SOURCE_CORPUS_CACHE.computeIfAbsent(roots, MigrationLint::readSourceCorpus);
    }

    private static Map<String, String> readSourceCorpus(List<Path> roots) {
        final Map<String, String> corpus = new LinkedHashMap<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                     // The migration tree is not source; a later migration naming the column is not a reader.
                     .filter(p -> !p.toString().contains("db" + java.io.File.separator + "migration"))
                     .filter(p -> {
                         String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                         // .html: src/main/resources/mails/*.html is a live location for a template
                         // that reads a dropped column (code review).
                         return n.endsWith(".java") || n.endsWith(".sql") || n.endsWith(".yaml")
                             || n.endsWith(".yml") || n.endsWith(".xml") || n.endsWith(".properties")
                             || n.endsWith(".html") || n.endsWith(".json");
                     })
                     .forEach(p -> {
                         try {
                             corpus.put(p.toString(), Files.readString(p, StandardCharsets.UTF_8));
                         } catch (IOException | RuntimeException e) {
                             // unreadable or non-UTF-8: not evidence of a reference
                         }
                     });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return corpus;
    }

    /**
     * Files mentioning both the owning table and the identifier (snake_case or camelCase), each
     * matched at a word boundary — code review: a bare substring match made every generic,
     * {@code id}-class column name unusable (matching, for example, the substring {@code id} inside
     * {@code Invalid}), which is the opposite of what the doc promises and trains authors to reach
     * for the blanket opt-out reflexively.
     *
     * <p>Resolves against the memoised {@link #sourceCorpus} rather than walking the tree itself
     * (skillars-deferred-99 AC7). Hits are still reported by bare file name, exactly as before.
     */
    private static List<String> referencesIn(List<Path> roots, String table, String identifier) {
        final String camel = toCamelCase(identifier);
        final Pattern tablePattern = wordBoundary(table);
        final Pattern identifierPattern = wordBoundary(identifier);
        final Pattern camelPattern = camel.equals(identifier) ? null : wordBoundary(camel);
        final Set<String> hits = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : sourceCorpus(roots).entrySet()) {
            final String body = entry.getValue();
            if (!tablePattern.matcher(body).find()) {
                continue;
            }
            if (identifierPattern.matcher(body).find()
                || (camelPattern != null && camelPattern.matcher(body).find())) {
                hits.add(Path.of(entry.getKey()).getFileName().toString());
            }
        }
        return new ArrayList<>(hits);
    }

    private static Pattern wordBoundary(String token) {
        return Pattern.compile("\\b" + Pattern.quote(token) + "\\b");
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
     * AC8 — every lock-taking DDL statement needs a bounded wait genuinely in effect at that point in
     * the file.
     *
     * <p>Verified during skillars-deferred-92: only {@code V55} and {@code V57} of 121 migrations set
     * {@code lock_timeout}. Without it a blocked {@code ALTER TABLE} queues behind a long-running
     * reader <em>and every subsequent query on that table queues behind the ALTER</em>, so one slow
     * {@code SELECT} can stall the whole table and exhaust the connection pool. That mechanism, not
     * the {@code ALTER}'s own duration, is why this matters — and it is the concrete cause behind the
     * three separate ledger entries for {@code V60}, {@code V94} and {@code V97}.
     *
     * <p><strong>"Genuinely in effect", stated precisely</strong> (code review closed two evasions
     * here): a {@code SET lock_timeout = 0} — PostgreSQL's own spelling for "wait forever" — used to
     * satisfy this rule, because the old check only asked whether the keyword appeared anywhere
     * earlier in the file, never what value it was set to. A later {@code RESET lock_timeout}
     * similarly used to be invisible. {@link #isLockTimeoutBoundedAt} now scans every {@code SET}/
     * {@code RESET} directive in document order up to this statement and tracks the running state.
     *
     * <p><strong>The locks are not all the same, and saying so matters:</strong>
     * <ul>
     *   <li>{@code ALTER TABLE … ADD/DROP/ALTER/RENAME COLUMN|CONSTRAINT}, {@code ADD PRIMARY KEY},
     *       {@code DROP TABLE}, {@code DROP INDEX}, {@code TRUNCATE} → {@code ACCESS EXCLUSIVE},
     *       which blocks reads too;</li>
     *   <li>{@code CREATE INDEX} (non-{@code CONCURRENTLY}) → {@code SHARE}: blocks writes and other
     *       DDL, but <strong>allows reads</strong>;</li>
     *   <li>{@code CREATE INDEX CONCURRENTLY} → {@code SHARE UPDATE EXCLUSIVE}.</li>
     * </ul>
     * All three are covered, because all three can wait indefinitely for a conflicting lock — but the
     * rule deliberately does not claim they are equally disruptive, and the message it emits does not
     * either. Overstating what a guard covers is the failure this project has recorded three times.
     */
    private static void lintLockTimeout(String name, Statement st, String raw, boolean nonTransactionalSidecar,
                                        List<Violation> out) {
        if (!ACCESS_EXCLUSIVE_ALTER.matcher(st.sql()).find()
            && !TOP_LEVEL_ACCESS_EXCLUSIVE.matcher(st.sql()).find()
            && !ANY_CREATE_INDEX.matcher(st.sql()).find()) {
            return;
        }
        if (hasMarker(st.scope(), "allow-unbounded-lock-wait")) {
            return;
        }
        // A `SET lock_timeout` anywhere earlier in the file covers this statement: it is a session /
        // transaction setting, not a per-statement one, so scoping it per statement would be wrong.
        // Comments MUST be stripped before this search (see stripComments) — otherwise a header that
        // merely *discusses* lock_timeout silences the rule for its own DDL.
        int end = Math.min(raw.length(), st.offset() + st.scope().length());
        String before = stripComments(raw.substring(0, end));
        if (isLockTimeoutBoundedAt(before, nonTransactionalSidecar)) {
            return;
        }
        out.add(new Violation(name, Rule.MISSING_LOCK_TIMEOUT,
            "lock-taking DDL with no bounded 'SET lock_timeout' in effect, so it waits indefinitely — and "
                + "every later query on that table queues behind the blocked statement. Add "
                + "'SET lock_timeout = '<n>s';' near the top of the migration (0 means unbounded and does "
                + "not count), or '-- migration-lint: allow-unbounded-lock-wait <reason>': "
                + oneLine(st.sql())));
    }

    /**
     * Replays every {@code SET}/{@code RESET lock_timeout} in {@code text} to the running state.
     *
     * <p>skillars-deferred-125 AC4 Task 4 (story-review.md): also replays {@code COMMIT}/{@code
     * ROLLBACK} (and, since the {@code /bmad-code-review} fix below, {@code END}/{@code ABORT})
     * transaction boundaries.
     *
     * <p><strong>Session vs. local scope, tracked separately (code review fix 2026-09-21).</strong> A
     * plain, session-scoped {@code SET}'s bound genuinely lasts for the rest of the session regardless
     * of any {@code COMMIT} in between. A {@code SET LOCAL}'s real scope ends at the enclosing
     * {@code COMMIT}/{@code ROLLBACK}, reverting to whatever the session-scoped state was underneath
     * it — NOT to "unbounded" unconditionally. The original version of this method collapsed both into
     * one flag pair, so a plain {@code SET} followed by a {@code SET LOCAL} then a {@code COMMIT}
     * incorrectly lost the still-live plain {@code SET}'s bound. {@code sessionBounded} and
     * {@code localActive}/{@code localBounded} are now tracked independently: a transaction boundary
     * clears only the local override, never the session-scoped value.
     *
     * @param nonTransactionalSidecar true if this file carries the Flyway {@code
     *     executeInTransaction=false} sidecar (see {@link #hasNonTransactionalSidecar}). {@code SET
     *     LOCAL} is a documented Postgres no-op with no enclosing transaction, so in a genuinely
     *     non-transactional migration it is ignored entirely rather than treated as a bound —
     *     otherwise a {@code SET LOCAL lock_timeout} that never actually took effect would silently
     *     satisfy {@link Rule#MISSING_LOCK_TIMEOUT} (code review fix 2026-09-21; this is exactly the
     *     form {@link Rule#SESSION_SCOPED_LOCK_TIMEOUT}'s own sidecar exemption pushes an author
     *     toward avoiding — the plain, session-scoped {@code SET} remains the only form that actually
     *     works in a sidecar migration, and continues to bound normally here).
     */
    private static boolean isLockTimeoutBoundedAt(String text, boolean nonTransactionalSidecar) {
        Matcher lockTimeout = LOCK_TIMEOUT_DIRECTIVE.matcher(text);
        Matcher boundary = TRANSACTION_BOUNDARY.matcher(text);
        boolean sessionBounded = false;
        boolean localActive = false;
        boolean localBounded = false;
        boolean hasNextLockTimeout = lockTimeout.find();
        boolean hasNextBoundary = boundary.find();
        while (hasNextLockTimeout || hasNextBoundary) {
            boolean lockTimeoutIsNext = hasNextLockTimeout
                && (!hasNextBoundary || lockTimeout.start() <= boundary.start());
            if (lockTimeoutIsNext) {
                if (lockTimeout.group().toUpperCase(Locale.ROOT).startsWith("RESET")) {
                    // RESET clears whichever scope currently governs: the LOCAL override if one is
                    // active, else the session-scoped value — mirroring real Postgres RESET semantics.
                    if (localActive) {
                        localActive = false;
                        localBounded = false;
                    } else {
                        sessionBounded = false;
                    }
                } else {
                    boolean isLocal = lockTimeout.group(1) != null;
                    boolean bound = !isZeroTimeout(lockTimeout.group(2));
                    if (isLocal) {
                        if (!nonTransactionalSidecar) {
                            localActive = true;
                            localBounded = bound;
                        }
                        // else: a Postgres no-op with no enclosing transaction — ignored rather than
                        // treated as a bound (see this method's own Javadoc).
                    } else {
                        sessionBounded = bound;
                    }
                }
                hasNextLockTimeout = lockTimeout.find();
            } else {
                // A COMMIT/ROLLBACK/END/ABORT ends any active SET LOCAL's scope — the session-scoped
                // SET's bound (if any) survives it untouched, which is the whole distinction this fix
                // exists to make.
                localActive = false;
                localBounded = false;
                hasNextBoundary = boundary.find();
            }
        }
        return localActive ? localBounded : sessionBounded;
    }

    // /bmad-code-review fix (2026-09-21): "SET LOCAL lock_timeout = DEFAULT" (or a plain SET) was
    // treated as bounded, because the old digit-only check found no leading digit in "DEFAULT" and
    // fell through to "not zero". Postgres's own lock_timeout default is 0 (wait forever) — DEFAULT is
    // therefore exactly as unbounded as an explicit 0.
    private static boolean isZeroTimeout(String rawValue) {
        if (rawValue == null) {
            return false;
        }
        String stripped = rawValue.replace("'", "").strip();
        if (stripped.equalsIgnoreCase("DEFAULT")) {
            return true;
        }
        Matcher digits = Pattern.compile("^(\\d+)").matcher(stripped);
        return digits.find() && Integer.parseInt(digits.group(1)) == 0;
    }

    /**
     * AC9 — a full-table {@code UPDATE}/{@code DELETE}/{@code TRUNCATE} in a migration locks every
     * row it touches for the whole statement.
     *
     * <p><strong>Scope, stated honestly.</strong> AC9 asks for "no {@code WHERE}, <em>or a
     * {@code WHERE} that cannot bound the row count</em>". The second half is not decidable from
     * text — {@code WHERE status = 'X'} may match three rows or three million. This rule therefore
     * catches a missing {@code WHERE} and the tautological forms ({@code WHERE TRUE},
     * {@code WHERE 1=1} — and ONLY when that is the entire predicate; code review: {@code WHERE 1=1
     * AND id = 7} used to be flagged too, because the old pattern matched the tautological prefix and
     * never looked at what followed it), and no more. A present-but-unbounded predicate is still
     * review's problem, which is why that limitation is written here rather than left for a reader to
     * discover.
     *
     * <p>{@code WHERE} and the {@code UPDATE}/{@code DELETE} keyword itself are matched at paren-depth
     * 0 (code review: a subquery's own {@code WHERE} — {@code UPDATE t SET x = (SELECT v FROM u WHERE
     * u.id = 1)}, which has no bounding predicate on {@code t} at all — used to satisfy this rule). A
     * leading CTE is recognised too ({@code WITH x AS (...) DELETE FROM t} — the previous anchor
     * required the statement to start with the keyword itself).
     */
    private static void lintUnbatchedDml(String name, Statement st, List<Violation> out) {
        String sql = st.sql().strip();

        if (TRUNCATE_STATEMENT.matcher(sql).find()) {
            if (!hasMarker(st.scope(), "allow-full-table-dml")) {
                out.add(new Violation(name, Rule.UNBATCHED_DML,
                    "TRUNCATE unconditionally removes every row — there is no WHERE clause it could carry. "
                        + "Use a chunked DELETE instead, or add '-- migration-lint: allow-full-table-dml "
                        + "<reason>' immediately above: " + oneLine(sql)));
            }
            return;
        }

        if (!DML_STATEMENT_START.matcher(sql).find()) {
            return;
        }
        int dmlIdx = topLevelIndexOf(sql, DML_KEYWORD);
        if (dmlIdx < 0) {
            return; // a WITH ... SELECT / WITH ... INSERT — not a write
        }
        String clause = sql.substring(dmlIdx);
        int whereIdx = topLevelIndexOf(clause, WHERE_KEYWORD);
        boolean hasWhere = whereIdx >= 0;
        boolean tautological = false;
        if (hasWhere) {
            String predicate = clause.substring(whereIdx + 5).strip();
            if (predicate.endsWith(";")) {
                predicate = predicate.substring(0, predicate.length() - 1).strip();
            }
            tautological = TAUTOLOGICAL_PREDICATE.matcher(predicate).matches();
        }
        boolean unbounded = !hasWhere || tautological;
        if (!unbounded || hasMarker(st.scope(), "allow-full-table-dml")) {
            return;
        }
        out.add(new Violation(name, Rule.UNBATCHED_DML,
            "an UPDATE/DELETE with no top-level bounding WHERE clause locks every row in the table for the "
                + "whole statement. Batch it, or add '-- migration-lint: allow-full-table-dml <reason>' "
                + "immediately above: " + oneLine(sql)));
    }

    /**
     * AC10.3 — {@code V128} gave {@code main.platform_config.id} an identity, so seeds must omit it.
     * A hand-picked id raises a <em>primary key</em> violation that the {@code ON CONFLICT (key)}
     * clause never sees, failing Flyway on every database that already ran a later migration reusing
     * that id — the hazard {@code V99}'s own header spends six lines describing.
     *
     * <p>Two evasions closed by the code review: an {@code INSERT} with no explicit column list at
     * all supplies {@code id} positionally just the same, and a quoted/schema-qualified spelling
     * ({@code "main"."platform_config"}) evaded the original pattern's literal {@code (} requirement.
     */
    private static void lintPlatformConfigId(String name, Statement st, List<Violation> out) {
        Matcher withCols = PLATFORM_CONFIG_INSERT_WITH_COLS.matcher(st.sql());
        boolean matchedWithCols = false;
        while (withCols.find()) {
            matchedWithCols = true;
            boolean namesId = Stream.of(withCols.group(1).split(","))
                .map(c -> c.strip().replace("\"", "").toLowerCase(Locale.ROOT))
                .anyMatch("id"::equals);
            if (namesId) {
                out.add(new Violation(name, Rule.PLATFORM_CONFIG_EXPLICIT_ID,
                    "INSERT INTO main.platform_config supplies an explicit id in its column list. Since "
                        + "V128 the column has an identity — omit id and let the sequence assign it. A "
                        + "hand-picked id raises a PRIMARY KEY violation that ON CONFLICT (key) does not "
                        + "catch, which fails Flyway on every database that already used that id"));
            }
        }
        if (!matchedWithCols && PLATFORM_CONFIG_INSERT_NO_COLS.matcher(st.sql()).find()) {
            out.add(new Violation(name, Rule.PLATFORM_CONFIG_EXPLICIT_ID,
                "INSERT INTO main.platform_config VALUES (...) with no explicit column list supplies "
                    + "every column positionally, including id. Name the columns explicitly and omit id "
                    + "so the identity default applies"));
        }
    }

    /**
     * skillars-deferred-125 AC4: true when {@code file} carries a Flyway {@code
     * executeInTransaction=false} sidecar ({@code <file's own name>.conf} containing that directive) —
     * the "confirmed working" non-transactional pattern {@code docs/deployment/migration-conventions.md}
     * documents for multi-batch, multi-commit backfills. {@code SET LOCAL} is a Postgres no-op outside
     * a transaction block, so a migration using this sidecar has no enclosing transaction for
     * {@code SET LOCAL} to bind to and must be allowed to keep a plain, session-scoped {@code SET} —
     * {@link Rule#SESSION_SCOPED_LOCK_TIMEOUT} exempts it entirely rather than flagging a form the
     * sidecar pattern structurally requires.
     *
     * <p>/bmad-code-review fix (2026-09-21): the previous version was a raw
     * {@code .contains("executeInTransaction=false")} substring search — it missed
     * {@code executeInTransaction = false} (spaces around {@code =}) and {@code executeInTransaction=FALSE},
     * and it *matched* a commented-out {@code #executeInTransaction=false} line, wrongly exempting a
     * migration whose sidecar had the directive turned off. Flyway {@code .conf} sidecars are
     * Java-properties files, where {@code #}/{@code !} start a comment line — parsed line by line here,
     * skipping comment lines, with a whitespace/case-tolerant match on the directive itself.
     */
    private static boolean hasNonTransactionalSidecar(Path file) {
        Path sidecar = file.resolveSibling(file.getFileName().toString() + ".conf");
        if (!Files.isRegularFile(sidecar)) {
            return false;
        }
        try {
            String content = Files.readString(sidecar, StandardCharsets.UTF_8);
            for (String line : content.split("\n", -1)) {
                String trimmed = line.strip();
                if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue; // Java .properties-style comment line — not a live directive
                }
                if (EXECUTE_IN_TRANSACTION_FALSE.matcher(trimmed).find()) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * skillars-deferred-125 AC4 — see {@link Rule#SESSION_SCOPED_LOCK_TIMEOUT}'s own Javadoc for the
     * cross-migration leak this closes. Statement-scoped like every other {@code migration-lint}
     * marker (AC11.2's convention): {@code -- migration-lint: allow-session-lock-timeout <reason>}
     * immediately above the statement opts it out. Callers are responsible for the file-level
     * {@link #hasNonTransactionalSidecar} exemption and the {@link #SESSION_SCOPED_LOCK_TIMEOUT_BASELINE}
     * check — this method assumes both already passed.
     */
    private static void lintSessionScopedLockTimeout(String name, Statement st, List<Violation> out) {
        if (!SESSION_SCOPED_SET_LOCK_TIMEOUT.matcher(st.sql()).find()) {
            return;
        }
        if (hasMarker(st.scope(), "allow-session-lock-timeout")) {
            return;
        }
        out.add(new Violation(name, Rule.SESSION_SCOPED_LOCK_TIMEOUT,
            "a plain 'SET lock_timeout' is session-scoped and persists past this migration's own "
                + "COMMIT — Flyway runs every pending migration in a deploy over one reused JDBC "
                + "session, so this silently carries forward into a later migration that never "
                + "declared its own timeout. Use 'SET LOCAL lock_timeout' instead (resets "
                + "automatically at COMMIT/ROLLBACK), or add '-- migration-lint: "
                + "allow-session-lock-timeout <reason>' immediately above this statement: "
                + oneLine(st.sql())));
    }

    /**
     * skillars-deferred-125 AC4 code-review fix (2026-09-21, owner decision — option 1, see
     * {@code optionsAndRecommendations.md}): the counterpart check for a sidecar migration exempted
     * from {@link Rule#SESSION_SCOPED_LOCK_TIMEOUT} above. A plain {@code SET lock_timeout} there is
     * genuinely the only form that works (no enclosing transaction for {@code SET LOCAL} to bind to),
     * but with no {@code COMMIT} to bound it automatically, it is GUARANTEED — not merely possible —
     * to carry into every later migration in the deploy unless the migration also issues a later
     * {@code RESET lock_timeout} by hand. Callers are responsible for the file-level {@link
     * #hasNonTransactionalSidecar} check and the {@link #SESSION_SCOPED_LOCK_TIMEOUT_BASELINE} check
     * — this method assumes both already passed (mirrors {@link #lintSessionScopedLockTimeout}).
     */
    private static void lintSidecarLockTimeoutReset(String name, Statement st, String raw, List<Violation> out) {
        if (!SESSION_SCOPED_SET_LOCK_TIMEOUT.matcher(st.sql()).find()) {
            return;
        }
        if (hasMarker(st.scope(), "allow-session-lock-timeout")) {
            return;
        }
        int end = Math.min(raw.length(), st.offset() + st.scope().length());
        String after = stripComments(raw.substring(end));
        if (RESET_LOCK_TIMEOUT.matcher(after).find()) {
            return;
        }
        out.add(new Violation(name, Rule.SIDECAR_LOCK_TIMEOUT_NOT_RESET,
            "a sidecar (executeInTransaction=false) migration's plain 'SET lock_timeout' has no later "
                + "'RESET lock_timeout' anywhere after it in the file — with no COMMIT to bound it the "
                + "way SET LOCAL would in a transactional migration, this setting is guaranteed to carry "
                + "forward into every later migration in the same deploy. Add 'RESET lock_timeout;' "
                + "after this migration's lock-taking statements, or add '-- migration-lint: "
                + "allow-session-lock-timeout <reason>' immediately above this statement: "
                + oneLine(st.sql())));
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
     *
     * <p>skillars-deferred-92 code review: the four rules that story added were never invoked here at
     * all — {@code lintStatementDeferred92} was called only from {@link #lintFile}'s versioned path.
     * A repeatable's unprepared {@code DROP}, unbounded lock wait, unbatched DML or hand-picked
     * {@code platform_config} id is exactly as much a hazard as in a versioned migration, arguably
     * more so since a repeatable's re-run is not staged the way a versioned migration's release is.
     * It is now linted with the same rules, using {@link #NO_ORDERING_CHECK} since a repeatable has
     * no version number for {@link #lintDropOrdering} to compare a {@code drop-prepared-in} release
     * against.
     */
    private static void lintRepeatable(Path file, String name, List<Path> sourceRoots, List<Violation> out) {
        final String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // /bmad-code-review fix (2026-09-21): SESSION_SCOPED_LOCK_TIMEOUT (and its sidecar-RESET
        // counterpart) were never invoked for repeatables at all — this method has its own separate
        // per-statement loop and did not call either. A repeatable's plain SET lock_timeout is exactly
        // as much a cross-migration leak hazard as a versioned migration's, and a repeatable shares the
        // same reused Flyway JDBC session for the rest of the deploy. No baseline gate, matching this
        // method's existing NO_ORDERING_CHECK convention below: a repeatable always re-runs at HEAD, so
        // there is no grandfathered band for it the way a versioned migration has.
        final boolean nonTransactionalSidecar = hasNonTransactionalSidecar(file);

        int dropScopeStart = 0;
        for (Statement st : statements(raw)) {
            int stEnd = Math.min(raw.length(), st.offset() + st.scope().length());
            String dropScope = raw.substring(dropScopeStart, stEnd);
            if (dropsTableOrColumn(st.sql())) {
                dropScopeStart = stEnd;
            }

            // skillars-deferred-91 code review: this branch had no opt-out check, though the method
            // javadoc promises "the allow-* opt-outs still apply" and both sibling branches honour one.
            // A repeatable that legitimately needs an unconditional DROP had no way past the lint.
            if (DROP_NO_IF_EXISTS_KEYWORD.matcher(st.sql()).find()
                && !hasMarker(st.scope(), "allow-unconditional-drop")) {
                out.add(new Violation(name, Rule.REPEATABLE_HAZARD,
                    "an R__ repeatable contains a DROP without IF EXISTS and without a "
                        + "'-- migration-lint: allow-unconditional-drop <reason>' opt-out"));
            }
            for (String clause : topLevelClauses(st.sql())) {
                Matcher col = DROP_COLUMN_CLAUSE.matcher(clause);
                if (col.find() && col.group(1) == null
                    && !hasMarker(st.scope(), "allow-unconditional-drop")) {
                    out.add(new Violation(name, Rule.REPEATABLE_HAZARD,
                        "an R__ repeatable drops a column without IF EXISTS and without a "
                            + "'-- migration-lint: allow-unconditional-drop <reason>' opt-out"));
                }
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

            lintStatementDeferred92(name, st, raw, dropScope, sourceRoots, out, NO_ORDERING_CHECK,
                nonTransactionalSidecar);
            if (nonTransactionalSidecar) {
                lintSidecarLockTimeoutReset(name, st, raw, out);
            } else {
                lintSessionScopedLockTimeout(name, st, out);
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

    /**
     * Removes comments from {@code sql}, literal-aware: a {@code --} or {@code /* … *}{@code /} that
     * appears inside a single-quoted string is left alone.
     *
     * <p>skillars-deferred-92 code review: the previous version applied {@code --[^\n]*} directly to
     * the raw text with no awareness of string literals, so {@code DEFAULT 'a--b'} deleted everything
     * from the {@code --} inside the literal to the end of the line — silently hiding whatever SQL
     * followed (a {@code DROP COLUMN} clause, in the probe that found this) from every rule that reads
     * {@link Statement#sql()}. A doubled quote ({@code ''}) inside a literal is treated as an escaped
     * quote character, not the end of the string.
     *
     * <p>A dollar-quoted body ({@code $$...$$} or {@code $tag$...$tag$}) is blanked out the same way
     * a comment is: its content is a function/procedure BODY, not a statement this migration itself
     * executes, so text inside it (a stray {@code DROP TABLE} in a debug helper, say) must not be
     * seen by the DDL/DML rules — code review: fixing only the statement SPLITTER for a tagged body
     * left the body's own text still exposed to every pattern match, so a {@code DROP} inside a
     * function definition still produced violations "on SQL the migration never executes".
     */
    private static String stripComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'') {
                int j = literalEnd(sql, i);
                out.append(sql, i, j);
                i = j;
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int nl = sql.indexOf('\n', i);
                i = nl < 0 ? sql.length() : nl;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                out.append(' ');
                i = end < 0 ? sql.length() : end + 2;
            } else if (c == '$') {
                int end = dollarQuoteEnd(sql, i);
                if (end >= 0) {
                    out.append(' ');
                    i = end;
                } else {
                    out.append(c);
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * If {@code sql.charAt(dollarIndex) == '$'} starts a dollar-quote tag, the index just past its
     * closing tag; otherwise {@code -1}.
     */
    private static int dollarQuoteEnd(String sql, int dollarIndex) {
        Matcher tag = DOLLAR_QUOTE_TAG.matcher(sql);
        tag.region(dollarIndex, sql.length());
        if (!tag.lookingAt()) {
            return -1;
        }
        String delimiter = tag.group();
        int close = sql.indexOf(delimiter, tag.end());
        return close < 0 ? sql.length() : close + delimiter.length();
    }

    /**
     * The comment-only text of {@code text} — the inverse of {@link #stripComments}, literal-aware on
     * the same terms. Used so a {@code -- migration-lint: allow-*} marker is only honoured when it is
     * an actual comment, never when the identical text happens to appear inside a string literal.
     */
    private static String extractComments(String text) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'') {
                i = literalEnd(text, i);
            } else if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') {
                int nl = text.indexOf('\n', i);
                int end = nl < 0 ? text.length() : nl;
                out.append(text, i, end).append('\n');
                i = end;
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                int stop = end < 0 ? text.length() : end + 2;
                out.append(text, i, stop);
                i = stop;
            } else if (c == '$') {
                int end = dollarQuoteEnd(text, i);
                i = end >= 0 ? end : i + 1;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    /** {@code text.charAt(quoteIndex) == '\''}. Returns the index just past the literal's closing quote. */
    private static int literalEnd(String text, int quoteIndex) {
        int j = quoteIndex + 1;
        while (true) {
            int end = text.indexOf('\'', j);
            if (end < 0) {
                return text.length();
            }
            if (end + 1 < text.length() && text.charAt(end + 1) == '\'') {
                j = end + 2; // an escaped '' quote character — still inside the literal
                continue;
            }
            return end + 1;
        }
    }

    /**
     * True when the first non-blank line of the file is a {@code --} or {@code /* … *}{@code /}
     * comment (code review: a block-comment header did not count, though {@link #stripComments}
     * treats both forms identically everywhere else).
     */
    private static boolean hasHeaderComment(String sql) {
        for (String line : sql.split("\\n")) {
            final String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            return trimmed.startsWith("--") || trimmed.startsWith("/*");
        }
        return false;
    }
}
