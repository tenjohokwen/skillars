package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.contract.exception.SecError;
import com.softropic.skillars.platform.security.contract.exception.SecException;
import com.softropic.skillars.platform.security.repo.Secret;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.JWT_BUS_NAME;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.JWT_VERSION;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JwtSecretBootstrapRunner}.
 *
 * <p>Deliberately a plain unit test, not an {@code *IT} — same rationale as
 * {@link AdminBootstrapRunnerTest}: the runner is property-driven via a constructor-injected
 * {@code boolean}, so every enable/disable/idempotency/race case is reachable here for free,
 * without forking a Spring context via {@code @TestPropertySource}.
 */
class JwtSecretBootstrapRunnerTest {

    /** A loopback JDBC URL — the only kind under which the runner is allowed to act (AC18). */
    private static final String LOCAL_DB_URL = "jdbc:postgresql://localhost/skillars?TimeZone=UTC";

    private SecretService secretService;

    @BeforeEach
    void setUp() {
        secretService = mock(SecretService.class);
    }

    private JwtSecretBootstrapRunner runner(boolean enabled) {
        return new JwtSecretBootstrapRunner(secretService, enabled, LOCAL_DB_URL);
    }

    @Test
    @DisplayName("disabled by default — no interaction with SecretService at all")
    void disabledByDefault() {
        runner(false).run(null);

        verifyNoInteractions(secretService);
    }

    @Test
    @DisplayName("enabled with no existing secret creates one")
    void enabledWithNoExistingSecretCreatesOne() {
        when(secretService.fetchSecret(JWT_VERSION, JWT_BUS_NAME))
            .thenThrow(new SecException("not found", SecError.KEY_NOT_FOUND));

        runner(true).run(null);

        verify(secretService).createActiveSecret(eq(JWT_VERSION), eq(JWT_BUS_NAME));
    }

    @Test
    @DisplayName("enabled with an existing secret is a no-op — never overwrites")
    void enabledWithExistingSecretSkipsCreation() {
        when(secretService.fetchSecret(JWT_VERSION, JWT_BUS_NAME)).thenReturn(mock(Secret.class));

        runner(true).run(null);

        verify(secretService, never()).createActiveSecret(anyString(), anyString());
    }

    @Test
    @DisplayName("a broken (not merely missing) secret row fails startup loudly")
    void brokenSecretRowFailsStartup() {
        when(secretService.fetchSecret(JWT_VERSION, JWT_BUS_NAME))
            .thenThrow(new SecException("could not decrypt", SecError.DECR_ERROR));

        JwtSecretBootstrapRunner runner = runner(true);

        assertThatThrownBy(() -> runner.run(null)).isInstanceOf(SecException.class);
        verify(secretService, never()).createActiveSecret(anyString(), anyString());
    }

    @Test
    @DisplayName("losing a creation race to another instance's bootstrap never fails startup")
    void lostCreationRaceDoesNotFailStartup() {
        when(secretService.fetchSecret(JWT_VERSION, JWT_BUS_NAME))
            .thenThrow(new SecException("not found", SecError.KEY_NOT_FOUND));
        when(secretService.createActiveSecret(JWT_VERSION, JWT_BUS_NAME))
            .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        JwtSecretBootstrapRunner runner = runner(true);

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a creation failure unrelated to a race (e.g. a real DB error) fails startup loudly")
    void unrelatedCreationFailureFailsStartup() {
        when(secretService.fetchSecret(JWT_VERSION, JWT_BUS_NAME))
            .thenThrow(new SecException("not found", SecError.KEY_NOT_FOUND));
        when(secretService.createActiveSecret(JWT_VERSION, JWT_BUS_NAME))
            .thenThrow(new IllegalStateException("connection pool exhausted"));

        JwtSecretBootstrapRunner runner = runner(true);

        assertThatThrownBy(() -> runner.run(null)).isInstanceOf(IllegalStateException.class);
    }

    // --- skillars-deferred-91 AC18: enforced local-only guard ---

    @Test
    @DisplayName("enabled against a NON-local datasource fails startup and never touches SecretService")
    void enabledAgainstRemoteDatasourceFailsStartup() {
        JwtSecretBootstrapRunner runner = new JwtSecretBootstrapRunner(
            secretService, true, "jdbc:postgresql://prod-db.internal:5432/skillars");

        assertThatThrownBy(() -> runner.run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LOCAL-DEVELOPMENT-ONLY");
        verifyNoInteractions(secretService);
    }

    @Test
    @DisplayName("disabled against a non-local datasource stays a silent no-op (guard only fires when enabled)")
    void disabledAgainstRemoteDatasourceIsSilent() {
        JwtSecretBootstrapRunner runner = new JwtSecretBootstrapRunner(
            secretService, false, "jdbc:postgresql://prod-db.internal:5432/skillars");

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
        verifyNoInteractions(secretService);
    }

    @Test
    @DisplayName("127.0.0.1 and [::1] loopback hosts are accepted")
    void loopbackIpHostsAreLocal() {
        when(secretService.fetchSecret(JWT_VERSION, JWT_BUS_NAME))
            .thenThrow(new SecException("not found", SecError.KEY_NOT_FOUND));

        assertThatCode(() -> new JwtSecretBootstrapRunner(
            secretService, true, "jdbc:postgresql://127.0.0.1:5432/skillars").run(null))
            .doesNotThrowAnyException();
        assertThatCode(() -> new JwtSecretBootstrapRunner(
            secretService, true, "jdbc:postgresql://[::1]:5432/skillars").run(null))
            .doesNotThrowAnyException();
    }

    /**
     * skillars-deferred-91 code review: the guard used {@code find()} over the WHOLE JDBC URL, so any
     * string containing {@code //localhost} passed — including a pgjdbc multi-host failover URL whose
     * driver may connect to the production host, and a URL carrying {@code //localhost} in a query
     * parameter. It now matches the extracted authority with {@code matches()}.
     */
    @Test
    @DisplayName("a multi-host failover URL listing localhost first is NOT treated as local")
    void multiHostFailoverUrlIsNotLocal() {
        JwtSecretBootstrapRunner runner = new JwtSecretBootstrapRunner(
            secretService, true, "jdbc:postgresql://localhost:5432,prod-db.internal:5432/skillars");

        assertThatThrownBy(() -> runner.run(null))
            .as("the driver may connect to prod-db.internal; a leading localhost must not whitelist it")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LOCAL-DEVELOPMENT-ONLY");
        verifyNoInteractions(secretService);
    }

    @Test
    @DisplayName("a remote host with '//localhost' buried in a query parameter is NOT treated as local")
    void localhostInsideAQueryParameterIsNotLocal() {
        JwtSecretBootstrapRunner runner = new JwtSecretBootstrapRunner(
            secretService, true,
            "jdbc:postgresql://prod-db.internal:5432/skillars?options=-c%20search_path=//localhost");

        assertThatThrownBy(() -> runner.run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LOCAL-DEVELOPMENT-ONLY");
        verifyNoInteractions(secretService);
    }

    @Test
    @DisplayName("a host that merely starts with 'localhost' is NOT treated as local")
    void hostnamePrefixedWithLocalhostIsNotLocal() {
        JwtSecretBootstrapRunner runner = new JwtSecretBootstrapRunner(
            secretService, true, "jdbc:postgresql://localhost.evil.example.com:5432/skillars");

        assertThatThrownBy(() -> runner.run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LOCAL-DEVELOPMENT-ONLY");
        verifyNoInteractions(secretService);
    }
}
