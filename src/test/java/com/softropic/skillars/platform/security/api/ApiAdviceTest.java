package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.infrastructure.message.ErrorDto;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * Pure unit coverage for {@link ApiAdvice#integrityViolationHandler} — no Spring context
 * (CI context-count ceiling, skillars-deferred-90 scope discipline).
 *
 * <p>skillars-deferred-90 AC1: the handler previously called {@code Map.of(...).getOrDefault(name, ...)}
 * and {@code Set.of(...).contains(name)} with an unguarded {@code name}, which NPEs on a {@code null}
 * key. Hibernate's {@code PostgreSQLDialect} only templates a constraint name for SQLSTATE
 * 23502/23503/23505/23514; an exclusion-constraint violation (23P01 — the V87
 * {@code excl_bkg_coach_slot_overlap} double-booking backstop) and a RESTRICT violation (23001)
 * both arrive with {@code null}. AC9: the same slice proves the {@code uq_pot_one_active_per_user}
 * unique-index mapping on the non-null-name path.
 */
class ApiAdviceTest {

    private ApiAdvice apiAdvice;

    @BeforeEach
    void setUp() {
        apiAdvice = new ApiAdvice(mock(MessageSource.class), mock(ApplicationEventPublisher.class));
    }

    @AfterEach
    void tearDown() {
        // dogfood AC8: never leave the request-metadata ThreadLocal populated for the next test.
        RequestMetadataProvider.cleanup();
    }

    private static DataIntegrityViolationException dive(String message, String sqlState, String constraintName) {
        org.hibernate.exception.ConstraintViolationException cve =
            new org.hibernate.exception.ConstraintViolationException(
                message, new SQLException(message, sqlState), constraintName);
        return new DataIntegrityViolationException("could not execute statement", cve);
    }

    @Test
    void integrityViolationHandler_nullConstraintNameAndNon23P01State_returns400GenericDataError_noNpe() {
        // 23001 (RESTRICT) is explicitly null-mapped by the dialect: a genuinely unmappable null.
        DataIntegrityViolationException ex =
            dive("ERROR: update or delete on table violates foreign key constraint", "23001", null);

        ResponseEntity<ErrorDto>[] holder = new ResponseEntity[1];
        assertThatCode(() -> holder[0] = apiAdvice.integrityViolationHandler(ex)).doesNotThrowAnyException();

        assertThat(holder[0].getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(holder[0].getBody().getErrorMsg().errorKey()).isEqualTo("generic.dataError");
    }

    @Test
    void integrityViolationHandler_exclusionConstraintViolation_returns409SlotUnavailable() {
        DataIntegrityViolationException ex = dive(
            "ERROR: conflicting key value violates exclusion constraint \"excl_bkg_coach_slot_overlap\"",
            "23P01", null);

        ResponseEntity<ErrorDto> response = apiAdvice.integrityViolationHandler(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getErrorMsg().errorKey()).isEqualTo("booking.slotUnavailable");
    }

    @Test
    void integrityViolationHandler_23P01WithUnparseableMessage_stillMapsToSlotUnavailable() {
        // The one exclusion constraint in the schema is V87; recover the 23P01 even if the driver
        // message format drifts, rather than leaking an unmapped 500.
        DataIntegrityViolationException ex = dive("ERROR: exclusion violation on bookings", "23P01", null);

        ResponseEntity<ErrorDto> response = apiAdvice.integrityViolationHandler(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getErrorMsg().errorKey()).isEqualTo("booking.slotUnavailable");
    }

    @Test
    void integrityViolationHandler_oneActiveOtpPerUserUniqueIndex_returns409OtpResendInProgress() {
        // AC9: prove the uq_pot_one_active_per_user -> 409 security.otpResendInProgress mapping at
        // the ApiAdvice slice (the endpoint can never sequentially collide + rate limiting masks it).
        DataIntegrityViolationException ex = dive(
            "ERROR: duplicate key value violates unique constraint \"uq_pot_one_active_per_user\"",
            "23505", "uq_pot_one_active_per_user");

        ResponseEntity<ErrorDto> response = apiAdvice.integrityViolationHandler(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getErrorMsg().errorKey()).isEqualTo("security.otpResendInProgress");
    }

    @Test
    void integrityViolationHandler_mappedNonConflictConstraint_returns400WithMappedKey() {
        DataIntegrityViolationException ex = dive(
            "ERROR: duplicate key value violates unique constraint \"user_email_key\"",
            "23505", "user_email_key");

        ResponseEntity<ErrorDto> response = apiAdvice.integrityViolationHandler(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getErrorMsg().errorKey()).isEqualTo("user.email.duplicate");
    }

    @Test
    void integrityViolationHandler_nonHibernateCause_returns400GenericDataError() {
        DataIntegrityViolationException ex =
            new DataIntegrityViolationException("bad data", new IllegalStateException("boom"));

        ResponseEntity<ErrorDto> response = apiAdvice.integrityViolationHandler(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getErrorMsg().errorKey()).isEqualTo("generic.dataError");
    }
}
