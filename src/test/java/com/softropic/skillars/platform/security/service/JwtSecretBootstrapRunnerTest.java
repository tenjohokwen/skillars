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

    private SecretService secretService;

    @BeforeEach
    void setUp() {
        secretService = mock(SecretService.class);
    }

    @Test
    @DisplayName("disabled by default — no interaction with SecretService at all")
    void disabledByDefault() {
        new JwtSecretBootstrapRunner(secretService, false).run(null);

        verifyNoInteractions(secretService);
    }

    @Test
    @DisplayName("enabled with no existing secret creates one")
    void enabledWithNoExistingSecretCreatesOne() {
        when(secretService.fetchSecret(JWT_VERSION, JWT_BUS_NAME))
            .thenThrow(new SecException("not found", SecError.KEY_NOT_FOUND));

        new JwtSecretBootstrapRunner(secretService, true).run(null);

        verify(secretService).createActiveSecret(eq(JWT_VERSION), eq(JWT_BUS_NAME));
    }

    @Test
    @DisplayName("enabled with an existing secret is a no-op — never overwrites")
    void enabledWithExistingSecretSkipsCreation() {
        when(secretService.fetchSecret(JWT_VERSION, JWT_BUS_NAME)).thenReturn(mock(Secret.class));

        new JwtSecretBootstrapRunner(secretService, true).run(null);

        verify(secretService, never()).createActiveSecret(anyString(), anyString());
    }

    @Test
    @DisplayName("a broken (not merely missing) secret row fails startup loudly")
    void brokenSecretRowFailsStartup() {
        when(secretService.fetchSecret(JWT_VERSION, JWT_BUS_NAME))
            .thenThrow(new SecException("could not decrypt", SecError.DECR_ERROR));

        JwtSecretBootstrapRunner runner = new JwtSecretBootstrapRunner(secretService, true);

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

        JwtSecretBootstrapRunner runner = new JwtSecretBootstrapRunner(secretService, true);

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a creation failure unrelated to a race (e.g. a real DB error) fails startup loudly")
    void unrelatedCreationFailureFailsStartup() {
        when(secretService.fetchSecret(JWT_VERSION, JWT_BUS_NAME))
            .thenThrow(new SecException("not found", SecError.KEY_NOT_FOUND));
        when(secretService.createActiveSecret(JWT_VERSION, JWT_BUS_NAME))
            .thenThrow(new IllegalStateException("connection pool exhausted"));

        JwtSecretBootstrapRunner runner = new JwtSecretBootstrapRunner(secretService, true);

        assertThatThrownBy(() -> runner.run(null)).isInstanceOf(IllegalStateException.class);
    }
}
