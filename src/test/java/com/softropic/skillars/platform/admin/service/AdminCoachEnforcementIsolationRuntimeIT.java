package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/**
 * skillars-deferred-123 AC6. {@code AdminCoachEnforcementServiceIsolationTest} only proves the
 * {@code @Transactional(isolation = REPEATABLE_READ)} annotation is present via reflection — it cannot
 * catch Spring's {@code validateExistingTransaction = false} default silently discarding the requested
 * isolation when the method is invoked from inside an ambient transaction (the documented hazard on
 * both methods). This test observes the *effective* isolation level the real transaction actually got,
 * read from inside the real service call via a {@link CoachProfileRepository} spy — no production-code
 * change, no hand-written SQL reproducing either method's own query shape.
 */
class AdminCoachEnforcementIsolationRuntimeIT extends AbstractIntegrationTest {

    @Autowired private AdminCoachEnforcementService service;
    @MockitoSpyBean private CoachProfileRepository coachProfileRepository;
    @Autowired private DataSource dataSource;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID coachId;
    private long userId;

    private UUID seedCoach() {
        // skillars-deferred-123 code review 2026-09-18 (Patch): plain `%` preserves the dividend's
        // sign, and nanoTime()'s origin is unspecified (may be negative per its own Javadoc), so a
        // bare `% 900_000L` could push userId outside the intended band. Math.floorMod always returns
        // a value in [0, 900_000).
        userId = 9081_000_000L + Math.floorMod(System.nanoTime(), 900_000L);
        UUID[] createdId = new UUID[1];
        // JdbcTemplate calls with no active Spring transaction acquire a fresh, autocommit-false
        // (per this project's hikari.auto-commit: false) connection from the pool and never commit it
        // before it's returned — wrap both the raw user insert and the repository save in the same
        // transaction, mirroring ManualStrikeIT's setUp.
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.\"user\" (id, created_by, created_date, last_modified_by, last_modified_date, request_id, status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, activated, locked, login, login_id_type, password_hash, otp_enabled, skillars_role, verification_status) " +
                "VALUES (?, 'system', ?, 'system', ?, 'test-req', 'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', 'Coach', 'DE', ?, true, false, ?, 'EMAIL', 'noop', false, 'COACH', 'BASIC_VERIFIED')",
                userId, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                "isolation-probe-" + userId + "@skillars-test.com", String.valueOf(userId),
                "isolation-probe-" + userId + "@skillars-test.com");

            CoachProfile coach = new CoachProfile();
            coach.setUserId(userId);
            coach.setDisplayName("Isolation Probe Coach");
            coach.setCanonicalTimezone("Europe/Berlin");
            coach.setStatus(CoachProfileStatus.ACTIVE);
            createdId[0] = coachProfileRepository.save(coach).getId();
            return null;
        });
        return createdId[0];
    }

    /**
     * {@code invocation.callRealMethod()} does not work here: {@code CoachProfileRepository} is a
     * Spring Data JDK dynamic proxy (interface, no concrete bytecode to invoke "the real method" on),
     * which Mockito rejects with "Cannot call abstract real method on java object" — and {@code
     * @MockitoSpyBean}'s wrapped delegate isn't exposed as a plain {@code getSpiedInstance()} either
     * (null for an interface-typed spy). Its {@code defaultAnswer} already IS the correct delegation
     * (whatever mechanism Spring wired it with) — reuse that directly instead of re-deriving it.
     */
    @SuppressWarnings("unchecked")
    private org.mockito.stubbing.Answer<Object> delegatingAnswer() {
        return (org.mockito.stubbing.Answer<Object>) mockingDetails(coachProfileRepository)
            .getMockCreationSettings().getDefaultAnswer();
    }

    @AfterEach
    void tearDown() {
        if (coachId != null) {
            transactionTemplate.execute(status -> {
                coachProfileRepository.deleteById(coachId);
                coachProfileRepository.flush();
                jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", userId);
                return null;
            });
        }
    }

    @Test
    void getEnforcementProfile_topLevelCall_effectiveIsolationIsRepeatableRead() {
        coachId = seedCoach();
        AtomicInteger observedIsolation = new AtomicInteger(-1);
        doAnswer(invocation -> {
            observedIsolation.set(DataSourceUtils.getConnection(dataSource).getTransactionIsolation());
            return delegatingAnswer().answer(invocation);
        }).when(coachProfileRepository).findById(coachId);

        service.getEnforcementProfile(coachId);

        assertThat(observedIsolation.get())
            .as("a fresh top-level call must genuinely run at REPEATABLE READ, not just declare it")
            .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
    }

    @Test
    void getCoachesUnderEnforcement_topLevelCall_effectiveIsolationIsRepeatableRead() {
        coachId = seedCoach();
        AtomicInteger observedIsolation = new AtomicInteger(-1);
        doAnswer(invocation -> {
            observedIsolation.set(DataSourceUtils.getConnection(dataSource).getTransactionIsolation());
            return delegatingAnswer().answer(invocation);
        }).when(coachProfileRepository).findByStatusInOrderByStatusChangedAtAsc(anyList(), any());

        service.getCoachesUnderEnforcement("ALL", 0);

        assertThat(observedIsolation.get())
            .as("a fresh top-level call must genuinely run at REPEATABLE READ, not just declare it")
            .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
    }

    /**
     * CHARACTERIZATION TEST, not a correctness assertion: it pins the documented hazard as it is
     * observed today, so a future fix that makes this method genuinely honor {@code REPEATABLE_READ}
     * from inside an ambient transaction (e.g. {@code validateExistingTransaction = true}) is expected
     * to flip this assertion, not read as a regression. Spring's {@code validateExistingTransaction =
     * false} default silently drops the requested {@code REPEATABLE_READ} when the method is invoked
     * from inside an ambient transaction that already opened at the database's default isolation
     * (READ COMMITTED on Postgres) — no exception, no warning, the method just runs at the wrong
     * isolation. This is the exact hazard both methods' own Javadoc names.
     */
    @Test
    void getEnforcementProfile_calledFromInsideAmbientTransaction_isolationSilentlyNotRepeatableRead() {
        coachId = seedCoach();
        AtomicInteger observedIsolation = new AtomicInteger(-1);
        doAnswer(invocation -> {
            observedIsolation.set(DataSourceUtils.getConnection(dataSource).getTransactionIsolation());
            return delegatingAnswer().answer(invocation);
        }).when(coachProfileRepository).findById(coachId);

        transactionTemplate.execute(status -> {
            service.getEnforcementProfile(coachId);
            return null;
        });

        // skillars-deferred-123 code review 2026-09-18 (Patch): guard the negative assertion below —
        // without this, a spy that never fires (e.g. a refactor that stops calling findById) would
        // leave observedIsolation at its -1 sentinel, which also satisfies isNotEqualTo(REPEATABLE_READ)
        // and the test would report green having observed nothing.
        assertThat(observedIsolation.get())
            .as("the spy must have actually observed a real transaction isolation level")
            .isNotEqualTo(-1);
        assertThat(observedIsolation.get())
            .as("validateExistingTransaction=false silently keeps the ambient transaction's own "
                + "isolation instead of honoring the REPEATABLE_READ the method declares")
            .isNotEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
    }
}
