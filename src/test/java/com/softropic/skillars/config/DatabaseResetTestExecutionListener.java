package com.softropic.skillars.config;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.notification.service.AlertRuleCache;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resets the database and Redis before every test method, so a test sees exactly the data it
 * declared plus the platform reference data, and nothing any earlier class left behind.
 *
 * <h2>Why this replaces the hand-written cleanup</h2>
 *
 * The suite used to isolate tests with per-class {@code @AfterEach} blocks that hand-deleted
 * rows, plus {@code DbCleaner} and {@code TestDataCleaner}. Every one of those was a
 * hand-maintained, silently-incomplete list. They were already failing before this story:
 * {@code TenantServiceIT}, {@code TenantAuditIT}, {@code RotatedKeyCleanupJobIT} and
 * {@code ApiKeyConcurrentRotationIT} error on {@code DELETE FROM main.revinfo} because
 * {@code user_aud} rows still reference the revision. Consolidating ~90 classes onto one shared
 * context widened the blast radius from one context group to the whole run and turned that from
 * 17 errors into 52.
 *
 * <h2>Ordering — the critical detail</h2>
 *
 * {@code @Sql} scripts are executed by {@code SqlScriptsTestExecutionListener} at order 5000
 * during {@code beforeTestMethod}, which runs <strong>before</strong> JUnit's {@code @BeforeEach}
 * callbacks. A {@code @BeforeEach} truncate would therefore wipe the {@code @Sql}-seeded data.
 * This listener runs at {@link #ORDER}, comfortably below 5000, so the sequence is:
 *
 * <pre>  reset (this, 3000)  ->  @Sql scripts (5000)  ->  @BeforeEach  ->  test</pre>
 *
 * {@code @TestExecutionListeners} does not contribute to {@code MergedContextConfiguration}, so
 * registering this adds no Spring contexts.
 *
 * <h2>What is excluded from the truncate, and why each exclusion is load-bearing</h2>
 *
 * <ul>
 *   <li><strong>{@code flyway_schema_history}</strong> — truncating it makes the next context's
 *       Flyway run re-apply all 91 migrations against a populated schema.</li>
 *   <li><strong>{@code main.shedlock}</strong> — truncating it would <em>silently disable every
 *       {@code @SchedulerLock} job for the rest of the JVM</em>. {@code JdbcTemplateLockProvider}
 *       caches the lock names it has inserted and thereafter issues only
 *       {@code UPDATE … WHERE name = ? AND lock_until <= now()}. Delete the row and that UPDATE
 *       matches nothing, {@code lock()} returns empty, and the run is skipped — logged merely as
 *       "held by another instance". Story deferred-15's review hit exactly this and recorded that
 *       deleting the row is <em>worse than nothing</em>. Reset by backdating instead.</li>
 *   <li><strong>{@code qrtz_*}</strong> — Quartz runs with {@code job-store-type: jdbc} and
 *       {@code isClustered: true}, so a cluster check-in thread writes to these concurrently.
 *       Truncating under a live clustered scheduler is a data race, not a clean reset.</li>
 * </ul>
 *
 * <h2>Reference data</h2>
 *
 * Excluding infrastructure tables is <strong>not sufficient</strong>. 33 migrations contain
 * {@code INSERT INTO}, seeding {@code main.platform_config} (30 of them), {@code session.drills},
 * {@code main.authority} and {@code development.skill_definitions}. Flyway will not replay them
 * after a truncate — the {@code flyway_schema_history} row is intact, so later runs are no-op
 * validation passes. The first truncated test method would destroy that data for the rest of the
 * JVM, and "add the missing seed to the class" is the wrong remedy: re-seeding migration data
 * into ~130 classes is never right.
 *
 * <p>So the very first reset, before anything has been truncated, snapshots every non-empty table
 * into a {@code _refdata} schema with {@code CREATE TABLE … AS SELECT *}, and every later reset
 * replays it with {@code INSERT INTO … SELECT * FROM …}. At that instant the database contains
 * exactly the Flyway-seeded reference data and nothing else, so <strong>the snapshot defines
 * itself</strong> — a new seeding migration is picked up automatically with no list to maintain
 * and nothing to drift. Doing it inside PostgreSQL also avoids mapping jsonb, arrays and enums
 * through Java types.
 */
public class DatabaseResetTestExecutionListener extends AbstractTestExecutionListener {

    /** Must stay below SqlScriptsTestExecutionListener's 5000. See the class javadoc. */
    public static final int ORDER = 3000;

    private static final String SNAPSHOT_SCHEMA = "_refdata";

    /** Application schemas. Discovered from information_schema; this only bounds the search. */
    private static final String APP_SCHEMA_PREDICATE =
        "table_schema NOT IN ('pg_catalog','information_schema','" + SNAPSHOT_SCHEMA + "')";

    private static volatile boolean snapshotTaken = false;
    private static final List<String[]> REFERENCE_TABLES = new ArrayList<>();
    private static final Object LOCK = new Object();

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        ApplicationContext ctx = testContext.getApplicationContext();
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

        // skillars-deferred-131 AC4: quiesce BEFORE the reset transaction opens below. See
        // quiesceAsyncExecutors's own javadoc for the deadlock this closes.
        quiesceAsyncExecutors(ctx);

        // Code review 2026-09-23: measured from AFTER quiescing, not before. Quiescing can legitimately
        // wait up to its own 10s bound; folding that into "database reset" cost (AC5.6, baselined at a
        // ~100ms CI mean) would make the reported metric mostly measure unrelated async-pool drain time
        // instead of the truncate/restore work it exists to track.
        long startNanos = System.nanoTime();

        // Everything touching the database MUST run inside an explicit transaction.
        // application.yaml:73 sets hikari auto-commit: false (it is required so Hibernate can
        // group statements into one transaction). A bare JdbcTemplate call outside a transaction
        // therefore executes on a connection that is never committed, and the work is rolled back
        // when the connection returns to the pool -- silently, with no error. The first version of
        // this listener did exactly that and was a complete no-op; it only surfaced because the
        // CREATE SCHEMA for the snapshot vanished before the CREATE TABLE that followed it.
        // This is also why every hand-written cleaner in this codebase wraps itself in
        // transactionTemplate.execute(...).
        TransactionTemplate tx = ctx.getBean(TransactionTemplate.class);
        tx.execute(status -> {
            snapshotReferenceDataOnce(jdbc);
            truncateApplicationTables(jdbc);
            restoreReferenceData(jdbc);
            backdateShedLock(jdbc);
            return null;
        });

        flushRedis(ctx);
        evictInProcessCaches(ctx);
        resetStatefulStubBeans(ctx);
        recordCost(System.nanoTime() - startNanos);
    }

    /**
     * skillars-deferred-131 AC4. Master CI run {@code 35891248593} (post-skillars-deferred-130-merge,
     * {@code test} job) failed with a genuine PostgreSQL {@code deadlock detected} inside
     * {@link #truncateApplicationTables}, while resetting the database ahead of
     * {@code RadarAssessmentResourceIT.getMyEntries_returnsOwnEntriesOnly} — two backends deadlocked
     * on a {@code ShareLock} while deleting from {@code main.player_profiles}. A rerun of the same
     * commit passed clean; all ~14 prior master CI runs on this branch were green. First occurrence,
     * not a known recurring flake.
     *
     * <h2>Investigation finding (Task 1)</h2>
     *
     * <p>{@code pom.xml}'s {@code maven-failsafe-plugin} configuration documents "all ~135 IT classes
     * share ONE forked JVM" (no {@code forkCount}/{@code reuseForks}), and no
     * {@code junit-platform.properties} configures JUnit 5 parallel execution — so test METHODS never
     * run concurrently with each other. The second concurrent Postgres backend has to be a second
     * connection from the SAME JVM: an async component still in flight from a preceding test class's
     * already-committed transaction. Two {@code @Async("reportExecutor")} +
     * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} listeners share this pool and both
     * touch {@code player_profiles}-adjacent state — {@code RadarCompositeCalculationService
     * .onRadarEntrySubmitted} (takes a {@code player_profiles} row lock via
     * {@code PlayerProfileRepository.findByIdForUpdate}, then writes
     * {@code development.player_radar_composites}/{@code player_radar_baselines}) and
     * {@code ReportGenerationService}'s own equivalent listener (reads {@code PlayerProfileRepository}
     * while generating a report). {@link #truncateApplicationTables}'s single transaction deletes from
     * every application table in one pass with no fixed cross-table ordering (unlike the async
     * writer's own fixed table order); if that transaction has already deleted rows in one of the
     * async writer's target tables (locking them for the remainder of its own transaction) and then
     * reaches {@code main.player_profiles} while the async writer still holds that row's lock from an
     * earlier statement, each side ends up waiting on a lock the other holds — a genuine cycle, not
     * merely a long wait. This is the structural mechanism a code-level reading of both listeners
     * supports; the race itself was not reproduced locally (first occurrence in ~14 prior green runs,
     * non-deterministic by nature) — this closes the identified mechanism and reduces risk, it does not
     * claim to exhaustively rule out any other.
     *
     * <h2>Fix (Task 2)</h2>
     *
     * <p>A {@code pg_advisory_xact_lock} around the reset was considered and rejected: it only
     * serializes transactions that ALSO take that same advisory lock, and neither async listener would
     * — it would leave the actual {@code ShareLock} cycle on {@code player_profiles} exactly as
     * reachable as before. Quiescing removes the second concurrent actor directly: bounded-poll both
     * {@code reportExecutor}'s active-task count and its queue down to zero before the reset
     * transaction below opens, so no async writer from a preceding test's committed transaction can
     * still be in flight when {@link #truncateApplicationTables}'s {@code DELETE FROM
     * main.player_profiles} runs. A bounded retry-on-{@code 40P01} around the reset was also
     * considered (the reset is idempotent test infrastructure, so retry carries none of the
     * "retry-and-hope" downside applicable to production business logic) but quiescing was chosen
     * instead because it closes the mechanism rather than merely reducing the odds of colliding with
     * it.
     *
     * <p>Code review 2026-09-23: {@code reportExecutor} alone quiesced only the specific pair that
     * produced the observed CI failure. {@link #truncateApplicationTables}'s single {@code DO} block
     * deletes from every non-empty application table with no fixed cross-table order, so the same
     * ShareLock-cycle mechanism is reachable against <em>any</em> {@code @Async} pool still writing to
     * an application table when the reset runs — {@code taskExecutor} alone backs
     * {@code TimelineEventListener} and {@code SluCalculationService}, both of which write rows FK'd to
     * {@code main.player_profiles} the same way {@code reportExecutor}'s two listeners do. Draining
     * every {@link ThreadPoolTaskExecutor} bean in the context (not just a hardcoded name) closes the
     * mechanism generally and stays correct if a future module adds another pool.
     *
     * <h2>skillars-deferred-132 AC1 Fix 6: empirical reproduction attempt</h2>
     *
     * <p>This fix shipped against a structural, not empirical, diagnosis (see above). This story
     * attempted a real reproduction: this method's own quiesce was temporarily short-circuited (an
     * early return behind a system property, reverted before this story's PR) to reopen the pre-fix
     * race window, temporary entry/exit diagnostic logging (thread name + timestamp, also since
     * reverted) was added around {@code RadarCompositeCalculationService.onRadarEntrySubmitted} and
     * {@code ReportGenerationService.onReportGenerated}, and the full {@code platform.development.**}
     * package (231 tests) was run repeatedly against real Testcontainers Postgres with the quiesce
     * disabled — 9 valid consecutive local runs (a 10th run failed on an unrelated compilation error
     * from concurrent, unrelated file edits mid-run, discarded rather than counted), each confirmed via
     * the diagnostic log to have genuinely dispatched both async listeners. <strong>Not reproduced</strong>
     * in any of the 9 runs — no {@code deadlock detected} error, no async task still logged as in-flight
     * at the moment of a later test class's reset. This is an explicit, temporary, disclosed exception to
     * this project's "no local {@code mvn verify} — GitHub CI is the sole full-verification gate"
     * convention, justified because a race that reproduced roughly 1-in-15 on master (first occurrence
     * in ~14 prior green runs) is not practical to chase through CI-only runs.
     *
     * <p>This keeps the mechanism's status exactly as it was: <strong>closed by structural reasoning,
     * not exhaustively proven</strong> — now with a documented, bounded reproduction attempt behind that
     * statement instead of none at all, rather than a claim that the race is impossible. One known,
     * accepted residual in the shipped fix itself, unrelated to whether the race reproduces: a
     * per-executor {@link ConditionTimeoutException} here is caught and logged, then the reset proceeds
     * anyway after its own 10s wait — so the race window is not fully closed for an in-flight async task
     * that runs longer than 10s.
     *
     * <p>A per-executor {@link ConditionTimeoutException} is caught and logged rather than left to
     * propagate: letting it escape {@code beforeTestMethod} would skip the reset transaction below
     * entirely for this test method, leaving stale data in place for both this test and every
     * subsequent one for the rest of the JVM — strictly worse than proceeding with a residual (small,
     * now-bounded-to-one-pool) deadlock risk for this single invocation.
     */
    private void quiesceAsyncExecutors(ApplicationContext ctx) {
        for (ThreadPoolTaskExecutor executor : ctx.getBeansOfType(ThreadPoolTaskExecutor.class).values()) {
            try {
                Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(25))
                    .until(() -> executor.getActiveCount() == 0
                        && executor.getThreadPoolExecutor().getQueue().isEmpty());
            } catch (ConditionTimeoutException e) {
                System.err.printf(
                    "[deferred-131] async executor did not quiesce within 10s (activeCount=%d, "
                        + "queueSize=%d) — proceeding with the reset anyway%n",
                    executor.getActiveCount(), executor.getThreadPoolExecutor().getQueue().size());
            }
        }
    }

    /**
     * AC4.3's real leakage risk — and it is <em>not</em> Mockito.
     *
     * <p>{@code @MockitoBean} defaults to {@code MockReset.AFTER} and the default
     * {@code MockitoResetTestExecutionListener} already clears those after every test method, so
     * writing {@code Mockito.reset(...)} for them would be redundant ceremony. The genuine risk is
     * the non-Mockito test doubles registered as {@code @Primary} beans in {@code TestConfig},
     * which get no automatic treatment at all.
     *
     * <p>{@code TestMailManager} keeps a {@code Map<String, Envelope> sentMails} of every message
     * sent. Context fragmentation used to bound that map to one context group's lifetime; now a
     * single context serves ~81 classes for the whole run, so it would accumulate across all ~905
     * test methods and a test awaiting {@code getEnvelope(helpCode)} could match an envelope left
     * by an earlier class. It already exposed {@code clear()}; nothing called it.
     *
     * <p>Audited and found stateless, so deliberately not reset here: {@code StubPaymentGateway}
     * (no fields) and the {@code @Primary} {@code RestTemplate}.
     */
    private void resetStatefulStubBeans(ApplicationContext ctx) {
        com.softropic.skillars.utils.TestMailManager mailManager =
            ctx.getBeanProvider(com.softropic.skillars.utils.TestMailManager.class).getIfAvailable();
        if (mailManager != null) {
            mailManager.clear();
        }
    }

    // AC5.6: the truncate takes an ACCESS EXCLUSIVE lock and runs on every test method, so its
    // cost has to be measured rather than assumed. Reported once at JVM exit.
    private static final java.util.concurrent.atomic.AtomicLong RESET_COUNT =
        new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong RESET_NANOS =
        new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicBoolean HOOK_REGISTERED =
        new java.util.concurrent.atomic.AtomicBoolean();

    private static void recordCost(long nanos) {
        long count = RESET_COUNT.incrementAndGet();
        long total = RESET_NANOS.addAndGet(nanos);
        // Report periodically as well as at exit: Failsafe kills the forked JVM without always
        // running shutdown hooks, so a shutdown-only report is frequently lost.
        if (count % 25 == 0) {
            long ms = total / 1_000_000;
            System.out.printf("[deferred-19] database reset: %d invocations, %d ms total, "
                + "%.1f ms mean%n", count, ms, (double) ms / count);
        }
        if (HOOK_REGISTERED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                long n = RESET_COUNT.get();
                if (n == 0) {
                    return;
                }
                long totalMs = RESET_NANOS.get() / 1_000_000;
                System.out.printf(
                    "[deferred-19] database reset: %d invocations, %d ms total, %.1f ms mean%n",
                    n, totalMs, (double) totalMs / n);
            }, "db-reset-cost-reporter"));
        }
    }

    /** Tables that must survive the truncate. See the class javadoc for why each one matters. */
    private static boolean isInfrastructureTable(String schema, String table) {
        return table.equals("flyway_schema_history")
            || (schema.equals("main") && table.equals("shedlock"))
            || table.startsWith("qrtz_");
    }

    /**
     * Cached: the schema shape is fixed for the JVM once Flyway has run, and this would otherwise
     * be an information_schema round-trip on every one of ~905 test methods.
     */
    private static volatile List<String[]> cachedTables;

    private List<String[]> applicationTables(JdbcTemplate jdbc) {
        List<String[]> cached = cachedTables;
        if (cached != null) {
            return cached;
        }
        synchronized (LOCK) {
            if (cachedTables == null) {
                cachedTables = queryApplicationTables(jdbc);
            }
            return cachedTables;
        }
    }

    private List<String[]> queryApplicationTables(JdbcTemplate jdbc) {
        List<String[]> all = jdbc.query(
            "SELECT table_schema, table_name FROM information_schema.tables "
                + "WHERE table_type = 'BASE TABLE' AND " + APP_SCHEMA_PREDICATE,
            (rs, i) -> new String[] {rs.getString(1), rs.getString(2)});
        List<String[]> result = new ArrayList<>();
        for (String[] t : all) {
            if (!isInfrastructureTable(t[0], t[1])) {
                result.add(t);
            }
        }
        return result;
    }

    /**
     * The tables Flyway migrations seed, derived by scanning the migration scripts themselves.
     *
     * <p><strong>Why not "whatever is non-empty at the first reset".</strong> That was the first
     * implementation and it is wrong: the snapshot is taken inside {@code beforeTestMethod}, by
     * which point the application context has already started and written its own rows. It
     * captured {@code main.sec} — populated by the security key material created at startup, not
     * by any migration — and then faithfully restored that row before every test, so
     * {@code secData.sql}'s fixed-primary-key insert collided with it:
     *
     * <pre>  duplicate key value violates unique constraint "sec_pkey"
     *   Detail: Key (id)=(659287191260154475) already exists.</pre>
     *
     * <p>Reading the migrations instead distinguishes the two precisely: only data a migration
     * inserts is reference data. It also keeps the list self-maintaining — a new seeding
     * migration is picked up with nothing to update — which is what AC5.1a asks for, without the
     * drift-detection check its hand-maintained fallback would need.
     */
    private static Set<String> flywaySeededTables() {
        Set<String> tables = new LinkedHashSet<>();
        try {
            org.springframework.core.io.support.PathMatchingResourcePatternResolver resolver =
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
            for (org.springframework.core.io.Resource r
                     : resolver.getResources("classpath*:db/migration/*.sql")) {
                String sql;
                try (java.io.InputStream in = r.getInputStream()) {
                    sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                java.util.regex.Matcher m = INSERT_TARGET.matcher(sql);
                while (m.find()) {
                    tables.add(m.group(1).toLowerCase() + "." + m.group(2).replace("\"", "").toLowerCase());
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot scan db/migration for seeded tables", e);
        }
        return tables;
    }

    private static final java.util.regex.Pattern INSERT_TARGET = java.util.regex.Pattern.compile(
        "INSERT\\s+INTO\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*(\"?[A-Za-z_][A-Za-z0-9_]*\"?)",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Snapshots the Flyway-seeded reference data, exactly once per JVM, before the first truncate.
     *
     * <p>Snapshotting inside PostgreSQL with {@code CREATE TABLE … AS SELECT *} avoids mapping
     * jsonb, arrays and enums through Java types on the way back in.
     */
    private void snapshotReferenceDataOnce(JdbcTemplate jdbc) {
        if (snapshotTaken) {
            return;
        }
        synchronized (LOCK) {
            if (snapshotTaken) {
                return;
            }
            Set<String> seeded = flywaySeededTables();
            jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + SNAPSHOT_SCHEMA);
            for (String[] t : applicationTables(jdbc)) {
                if (!seeded.contains(t[0].toLowerCase() + "." + t[1].toLowerCase())) {
                    continue;
                }
                String qualified = quote(t[0]) + "." + quote(t[1]);
                String snap = quote(SNAPSHOT_SCHEMA) + "." + quote(t[0] + "__" + t[1]);
                jdbc.execute("DROP TABLE IF EXISTS " + snap);
                jdbc.execute("CREATE TABLE " + snap + " AS SELECT * FROM " + qualified);
                REFERENCE_TABLES.add(new String[] {qualified, snap});
            }
            snapshotTaken = true;
        }
    }

    /**
     * Clears every application table, skipping the ones that are already empty.
     *
     * <h3>Why this is not a TRUNCATE any more (AC5.6)</h3>
     *
     * The first implementation issued one
     * {@code TRUNCATE <~100 tables> RESTART IDENTITY CASCADE}. Measured cost:
     *
     * <pre>  CI (Linux):            814 invocations,  81 s total,   99.7 ms mean
     *   local (Docker Desktop): 814 invocations, 828 s total, ~1018 ms mean</pre>
     *
     * AC5.6 says to switch to deleting only from non-empty tables if the cost is material, having
     * measured first. On CI it is not material. Locally it is ~14 minutes of a 41-minute run,
     * because every statement crosses Docker Desktop's VM boundary, and TRUNCATE does real file
     * work and takes an ACCESS EXCLUSIVE lock on every table named — even the empty ones, which
     * after the first reset is nearly all of them.
     *
     * <p>This version runs one server-side {@code DO} block instead: a single round trip that
     * tests each table with {@code EXISTS (SELECT 1 …)} and only issues a {@code DELETE} for the
     * few that actually hold rows. An {@code EXISTS} against an empty table stops at the first
     * page; a {@code DELETE} it never issues costs nothing.
     *
     * <h3>Two details that matter</h3>
     *
     * <p><strong>{@code session_replication_role = 'replica'}</strong> suspends FK triggers for
     * the duration, so deletion order between tables is irrelevant — the property TRUNCATE got
     * from naming every table in one statement. It is restored to {@code 'origin'} in the same
     * block. This is the same mechanism {@code BasePaymentIT} already used to delete from the
     * append-only {@code parent_credit_ledger}, so it is an established pattern here rather than
     * a new privilege requirement (the test container runs as superuser {@code postgres}).
     *
     * <p><strong>Sequences are no longer restarted.</strong> TRUNCATE carried
     * {@code RESTART IDENTITY}; DELETE has no equivalent. This codebase's fixtures use explicit
     * hard-coded ids (see the fixture-id registry in {@code docs/testing/test-data-isolation.md}),
     * so nothing should depend on a sequence restarting at 1 — but that is an assumption this
     * change introduces, and it is the first thing to suspect if a test starts failing on an
     * unexpected generated id.
     */
    private void truncateApplicationTables(JdbcTemplate jdbc) {
        List<String[]> tables = applicationTables(jdbc);
        if (tables.isEmpty()) {
            return;
        }
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < tables.size(); i++) {
            if (i > 0) {
                values.append(',');
            }
            values.append('(').append(literal(tables.get(i)[0])).append(',')
                  .append(literal(tables.get(i)[1])).append(')');
        }
        jdbc.execute(
            "DO $$\n"
            + "DECLARE r record; has_rows boolean;\n"
            + "BEGIN\n"
            + "  SET CONSTRAINTS ALL DEFERRED;\n"
            + "  PERFORM set_config('session_replication_role', 'replica', true);\n"
            + "  FOR r IN SELECT * FROM (VALUES " + values + ") AS t(s, n) LOOP\n"
            + "    EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.%I)', r.s, r.n) INTO has_rows;\n"
            + "    IF has_rows THEN\n"
            + "      EXECUTE format('DELETE FROM %I.%I', r.s, r.n);\n"
            + "    END IF;\n"
            + "  END LOOP;\n"
            + "  PERFORM set_config('session_replication_role', 'origin', true);\n"
            + "END $$;");
    }

    /** Single-quoted SQL string literal, for embedding in the DO block's VALUES list. */
    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private void restoreReferenceData(JdbcTemplate jdbc) {
        for (String[] pair : REFERENCE_TABLES) {
            jdbc.execute("INSERT INTO " + pair[0] + " SELECT * FROM " + pair[1]);
        }
    }

    /**
     * Backdate, never delete — deleting the row makes JdbcTemplateLockProvider's UPDATE match
     * nothing and silently skips every later job. See the class javadoc.
     */
    private void backdateShedLock(JdbcTemplate jdbc) {
        jdbc.update("UPDATE main.shedlock SET lock_until = now() - interval '1 minute'");
    }

    private void flushRedis(ApplicationContext ctx) {
        RedisConnectionFactory factory =
            ctx.getBeanProvider(RedisConnectionFactory.class).getIfAvailable();
        if (factory == null) {
            return;
        }
        try (RedisConnection connection = factory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    /**
     * AC5.1b. {@code ConfigService} and {@code AlertRuleCache} hold database rows in-process on a
     * scheduled refresh. Under context fragmentation this was self-healing, because contexts were
     * short-lived and the caches were rebuilt constantly. Under one long-lived context the
     * truncate would otherwise leave both holding rows that no longer exist — and with scheduling
     * disabled under the test profile (AC5a) nothing would ever refresh them.
     *
     * <p>Both refresh entry points are already public, so this needs no production API change.
     */
    private void evictInProcessCaches(ApplicationContext ctx) {
        ConfigService configService = ctx.getBeanProvider(ConfigService.class).getIfAvailable();
        if (configService != null) {
            configService.scheduledRefresh();
        }
        AlertRuleCache alertRuleCache = ctx.getBeanProvider(AlertRuleCache.class).getIfAvailable();
        if (alertRuleCache != null) {
            alertRuleCache.refresh();
        }
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
