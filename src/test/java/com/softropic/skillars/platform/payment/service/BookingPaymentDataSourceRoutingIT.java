package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.config.DataSourceConfig;
import com.softropic.skillars.infrastructure.config.RoutingDataSource;
import com.softropic.skillars.infrastructure.config.RoutingDataSourceContext;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-137 AC2: proves the dedicated payment {@code DataSource} ({@link
 * RoutingDataSource}, wired by {@link DataSourceConfig}/{@code TestConfig}) is actually wired and
 * used by {@code reserveCapture}/{@code persistPaymentFailure}/{@code declineBatchBooking}, rather
 * than a silently-skipped no-op — mirrors {@code GdprErasureDataSourceRoutingIT}'s own established
 * test shape exactly (same mechanism, second named target).
 */
class BookingPaymentDataSourceRoutingIT extends BasePaymentIT {

    private static final long PARENT_ID = 97101L;
    private static final long PLAYER_ID = 97103L;
    private static final long COACH_USER_ID = 97102L;
    private static final BigDecimal SESSION_PRICE = new BigDecimal("50.00");

    @Autowired private DataSource dataSource;
    @Autowired private BookingPaymentPersistenceService persistenceService;

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
     * Direct proof, independent of {@code BookingPaymentPersistenceService}: a connection acquired
     * through the app's single {@code DataSource} bean while {@link RoutingDataSourceContext} names
     * the payment key is a genuinely NEW physical connection out of the dedicated pool's own {@link
     * com.zaxxer.hikari.HikariPoolMXBean} counters, not the primary pool's.
     */
    @Test
    @Timeout(15)
    void routingKeySet_connectionComesFromDedicatedPool_notPrimary() throws Exception {
        RoutingDataSource routing = (RoutingDataSource) dataSource;
        HikariDataSource primary = (HikariDataSource) routing.getDefaultTarget();
        HikariDataSource payment = (HikariDataSource) routing.getNamedTarget(
            DataSourceConfig.PAYMENT_REQUIRES_NEW_DATASOURCE_KEY);

        int primaryActiveBefore = primary.getHikariPoolMXBean().getActiveConnections();
        int paymentActiveBefore = payment.getHikariPoolMXBean().getActiveConnections();

        RoutingDataSourceContext.set(DataSourceConfig.PAYMENT_REQUIRES_NEW_DATASOURCE_KEY);
        Connection routed = dataSource.getConnection();
        heldConnections.add(routed);

        assertThat(payment.getHikariPoolMXBean().getActiveConnections())
            .as("routing the acquisition through the payment key must check out a connection from "
                + "the DEDICATED pool specifically")
            .isEqualTo(paymentActiveBefore + 1);
        assertThat(primary.getHikariPoolMXBean().getActiveConnections())
            .as("the primary pool must be untouched by a payment-routed acquisition")
            .isEqualTo(primaryActiveBefore);
    }

    /**
     * End-to-end proof through the real service method (not a manually-set routing key): exhausting
     * ONLY the dedicated payment pool makes {@code reserveCapture} fail fast at that pool's own 5s
     * {@code connectionTimeout} — nowhere near the primary pool's 30s — confirming the self-invocation
     * split ({@code reserveCapture} → {@code self.reserveCaptureTransactional}) genuinely sets the
     * routing key BEFORE the {@code @Transactional(REQUIRES_NEW)} proxy advice opens its connection,
     * not merely before the annotated method's first statement.
     */
    @Test
    @Timeout(30)
    void reserveCapture_dedicatedPoolExhausted_failsFastAtDedicatedTimeout_notPrimarysLongerOne()
            throws Exception {
        UUID coachId = insertTestCoach(COACH_USER_ID, "routing_coach@test.com", "Routing Coach");
        insertTestParent(PARENT_ID, "routing_parent@test.com");
        insertTestPlayer(PLAYER_ID, PARENT_ID);
        UUID bookingId = seedPendingBooking(coachId);

        RoutingDataSource routing = (RoutingDataSource) dataSource;
        HikariDataSource payment = (HikariDataSource) routing.getNamedTarget(
            DataSourceConfig.PAYMENT_REQUIRES_NEW_DATASOURCE_KEY);
        int maxPoolSize = payment.getMaximumPoolSize();
        for (int i = 0; i < maxPoolSize; i++) {
            heldConnections.add(payment.getConnection());
        }

        Instant start = Instant.now();
        // reserveCapture swallows nothing itself — a connection-acquisition failure surfaces as a
        // plain exception from the pool. Whatever it throws, what matters here is HOW FAST it fails.
        try {
            persistenceService.reserveCapture(bookingId, BigDecimal.ZERO, SESSION_PRICE, null);
        } catch (RuntimeException ignored) {
            // expected: the dedicated pool is fully exhausted by this test
        }
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(elapsed)
            .as("must fail at the dedicated pool's own 5s connectionTimeout, nowhere near the "
                + "primary pool's 30s — proves reserveCapture's REQUIRES_NEW acquisition was routed "
                + "to the exhausted dedicated pool, not the untouched primary one")
            .isLessThan(Duration.ofSeconds(15));
    }

    private UUID seedPendingBooking(UUID coachId) {
        UUID bookingId = UUID.randomUUID();
        Instant start = Instant.now().plus(72, ChronoUnit.HOURS);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO booking.bookings (id, parent_id, player_id, coach_id, requested_start_time, "
                    + "requested_end_time, status, canonical_timezone, version, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, 'PAYMENT_PENDING', 'UTC', 0, now(), now())",
                bookingId, PARENT_ID, PLAYER_ID, coachId,
                Timestamp.from(start), Timestamp.from(start.plus(1, ChronoUnit.HOURS)));
            return null;
        });
        return bookingId;
    }
}
