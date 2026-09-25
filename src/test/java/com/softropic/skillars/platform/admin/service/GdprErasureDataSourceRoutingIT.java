package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.infrastructure.config.DataSourceConfig;
import com.softropic.skillars.infrastructure.config.RoutingDataSource;
import com.softropic.skillars.infrastructure.config.RoutingDataSourceContext;
import com.softropic.skillars.platform.admin.repo.GdprRequest;
import com.softropic.skillars.platform.admin.repo.GdprRequestRepository;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-deferred-136 AC1: proves the dedicated GDPR-erasure {@code DataSource} ({@link
 * RoutingDataSource}, wired by {@link DataSourceConfig}/{@code TestConfig}) is actually wired and
 * used, and independently bounded, rather than a silently-skipped no-op — the two things this AC's
 * own test plan calls out explicitly.
 *
 * <p>See {@link RoutingDataSource}'s own Javadoc for why routing, not a second
 * {@code PlatformTransactionManager} sharing the app's one {@code EntityManagerFactory}, is the
 * mechanism: this story's own Dev Agent Record records the empirical spike (a
 * {@code JpaTransactionManager} built against a deliberately unreachable {@code DataSource} but the
 * app's real, shared {@code EntityManagerFactory} still executed a JPA query successfully) that
 * disproved the originally-sketched design before this one was built.
 */
class GdprErasureDataSourceRoutingIT extends AbstractIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private GdprErasureService gdprErasureService;
    @Autowired private GdprRequestRepository gdprRequestRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private final List<Connection> heldConnections = new ArrayList<>();

    @AfterEach
    void releaseHeldConnections() {
        RoutingDataSourceContext.clear();
        for (Connection c : heldConnections) {
            try {
                c.close();
            } catch (Exception ignored) {
                // best-effort cleanup; a leaked connection here would only affect this pool's own
                // idle count, not correctness of a later test
            }
        }
        heldConnections.clear();
    }

    /**
     * Direct proof, independent of {@code GdprErasureService}: a connection acquired through the
     * app's single {@code DataSource} bean while {@link RoutingDataSourceContext} names the GDPR key
     * is a genuinely NEW physical connection out of the dedicated pool's own {@code HikariPoolMXBean}
     * counters, not the primary pool's.
     */
    @Test
    @Timeout(15)
    void routingKeySet_connectionComesFromDedicatedPool_notPrimary() throws Exception {
        RoutingDataSource routing = (RoutingDataSource) dataSource;
        HikariDataSource primary = (HikariDataSource) routing.getDefaultTarget();
        HikariDataSource gdprErasure =
            (HikariDataSource) routing.getNamedTarget(DataSourceConfig.GDPR_ERASURE_DATASOURCE_KEY);

        // Active (checked-out, not-yet-closed), not total: HikariCP never shrinks totalConnections
        // back down after a close() returns a physical connection to its own idle set for reuse, so
        // total alone cannot discriminate "this specific acquisition" from pool warm-up noise left by
        // another test. Active-connection count can, since this test holds its acquired connection
        // open (released in @AfterEach) rather than closing it before asserting.
        int primaryActiveBefore = primary.getHikariPoolMXBean().getActiveConnections();
        int gdprActiveBefore = gdprErasure.getHikariPoolMXBean().getActiveConnections();

        RoutingDataSourceContext.set(DataSourceConfig.GDPR_ERASURE_DATASOURCE_KEY);
        Connection routed = dataSource.getConnection();
        heldConnections.add(routed);

        assertThat(gdprErasure.getHikariPoolMXBean().getActiveConnections())
            .as("routing the acquisition through the GDPR key must check out a connection from the "
                + "DEDICATED pool specifically")
            .isEqualTo(gdprActiveBefore + 1);
        assertThat(primary.getHikariPoolMXBean().getActiveConnections())
            .as("the primary pool must be untouched by a GDPR-routed acquisition")
            .isEqualTo(primaryActiveBefore);
    }

    /**
     * Saturation test per this AC's own test plan: exhaust the DEDICATED pool specifically (its
     * {@code maximumPoolSize} is 3 in the test wiring, {@code connectionTimeout} 10s — see
     * {@code TestConfig.containerHikariDataSource}), then drive a real {@link
     * GdprErasureService#erase} call and confirm it fails fast, nowhere near the primary pool's 30s.
     * {@code erase()}'s routing key covers the WHOLE {@code eraseTransactional} call (see {@code
     * GdprErasureService.erase}'s own comment), so the failure surfaces at that method's own
     * connection acquisition — no player_profiles/PlayerProfile fixture is needed to exercise this, a
     * bare {@code User} + {@code GdprRequest} row is enough.
     *
     * <p><strong>What this actually proves (story review — corrected):</strong> the failure observed
     * here is {@code erase()}'s own {@code assertConnectionPoolNotSaturated} same-thread pre-check
     * firing near-instantly (it reads {@code idle<=0 && total>=max} directly off the dedicated pool's
     * live {@link com.zaxxer.hikari.HikariPoolMXBean}, which this test has already made true by holding
     * all 3 of its connections) — NOT the real Hikari-level 10s {@code connectionTimeout} being waited
     * out. The pre-check exists specifically to avoid ever reaching that real timeout on the request
     * thread, so a real erase() call against an already-exhausted dedicated pool will essentially never
     * exercise the 10s wait; the assertion below reflects that (near-instant, not near-10s).
     */
    @Test
    @Timeout(30)
    void erase_dedicatedPoolExhausted_failsFastAtDedicatedTimeout_notPrimarysLongerOne() throws Exception {
        RoutingDataSource routing = (RoutingDataSource) dataSource;
        HikariDataSource gdprErasure =
            (HikariDataSource) routing.getNamedTarget(DataSourceConfig.GDPR_ERASURE_DATASOURCE_KEY);
        int maxPoolSize = gdprErasure.getMaximumPoolSize();
        for (int i = 0; i < maxPoolSize; i++) {
            heldConnections.add(gdprErasure.getConnection());
        }

        long userId = 9213_000_001L;
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, "
                    + "last_modified_date, request_id, session_id, status, dob, email, first_name, "
                    + "gender, lang_key, last_name, iso2_country, phone, activated, locked, login, "
                    + "login_id_type, password_hash, otp_enabled, skillars_role, verification_status) "
                    + "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, 'ACTIVE', '1985-06-01', "
                    // User.password carries a bean-validation @Size(min=60, max=60) (bcrypt hash
                    // shape) — a shorter placeholder trips it if eraseTransactional's own
                    // userRepository.save(user) is ever reached (see GdprErasureRetryIT's identical fix).
                    + "?, 'Test', 'OTHER', 'en', 'User', 'DE', '9213000001', true, false, ?, 'EMAIL', "
                    + "'$2a$10$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx', false, "
                    + "'COACH', 'BASIC_VERIFIED') ON CONFLICT (id) DO NOTHING",
                userId, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                "gdpr.routing.9213@skillars-test.com", "gdpr.routing.9213@skillars-test.com");
            return null;
        });
        UUID requestId = transactionTemplate.execute(status ->
            gdprRequestRepository.save(new GdprRequest(userId, "ERASURE", "PROCESSING")).getId());

        Instant start = Instant.now();
        assertThatThrownBy(() -> gdprErasureService.erase(requestId, userId))
            .as("eraseTransactional's own connection acquisition must fail, not silently succeed "
                + "against the exhausted dedicated pool nor block on the primary pool instead");
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(elapsed)
            .as("must fail near-instantly via assertConnectionPoolNotSaturated's own pre-check "
                + "against the exhausted dedicated pool, nowhere near the dedicated pool's own 10s "
                + "connectionTimeout (let alone the primary pool's 30s) -- proves this acquisition was "
                + "pre-check-rejected before ever attempting a real Hikari-level wait")
            .isLessThan(Duration.ofSeconds(5));
    }
}
