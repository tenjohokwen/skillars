package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.contract.exception.SecError;
import com.softropic.skillars.platform.security.contract.exception.SecException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.JWT_BUS_NAME;
import static com.softropic.skillars.infrastructure.security.SecurityConstants.JWT_VERSION;
import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Creates the JWT signing secret in the database, once, at startup — a local-development
 * convenience only.
 *
 * <h2>Why this exists</h2>
 *
 * {@link JwtConfiguration} and {@link com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService}
 * both fetch a {@code Secret} row keyed by ({@code JWT_VERSION}, {@code JWT_BUS_NAME}) from the
 * database, and there is no default, no Flyway seed, and no admin endpoint that creates it. A fresh
 * database (schema migrations only, no data) therefore fails EVERY request — including
 * unauthenticated ones — with {@code SecException(KEY_NOT_FOUND)}:
 * {@code SecurityAdviceFilter} loads the secret onto the thread ahead of every request, before the
 * controller (or its {@code @PreAuthorize}) ever runs. Until now the only way past this was
 * hand-written SQL replicating {@link com.softropic.skillars.platform.security.repo.Secret}'s own
 * Jasypt encryption — not written down anywhere, and not something a fresh local checkout should
 * have to reverse-engineer.
 *
 * <h2>Safety posture — mirrors {@link AdminBootstrapRunner}</h2>
 *
 * <ul>
 *   <li><strong>Opt-in.</strong> Silently does nothing unless {@code app.bootstrap.jwt-secret.enabled}
 *       is explicitly {@code true}. This MUST stay unset (or false) in every real environment: those
 *       provision the secret once, deliberately, and this runner exists only so a brand-new local
 *       database can boot at all.</li>
 *   <li><strong>Not {@code @Profile}-gated</strong>, for the same reason as {@code AdminBootstrapRunner}
 *       — production boots with no {@code SPRING_PROFILES_ACTIVE} set at all, so a profile guard
 *       would fail-close in precisely the environments that must never enable this. The property
 *       gate is the only gate, by design.</li>
 *   <li><strong>Idempotent.</strong> Skips if a secret already exists for this version/busId. There is
 *       no update path anywhere in {@link SecretService} — {@code Secret}'s own columns are
 *       {@code updatable = false} — so this runner never overwrites one.</li>
 *   <li><strong>Fails startup loudly on anything other than "not found".</strong> Any other
 *       {@link SecException} (e.g. a corrupt/undecryptable row) means the existing row is broken,
 *       not missing — creating a second one under it would leave two active secrets and silently
 *       invalidate tokens signed with whichever one {@code fetchLatestActiveSecretAsBytes} does not
 *       pick. That is worth stopping the boot for.</li>
 *   <li><strong>A lost race at creation time never fails startup.</strong> Two instances booting
 *       against the same fresh database could both pass the existence check before either commits;
 *       the loser's insert then violates the {@code (version, busId)} unique constraint. That failure
 *       means the secret now exists — exactly the state this runner is trying to reach — so it is
 *       logged and swallowed, not thrown.</li>
 * </ul>
 */
@Slf4j
@Component
public class JwtSecretBootstrapRunner implements ApplicationRunner {

    private final SecretService secretService;
    private final boolean enabled;

    public JwtSecretBootstrapRunner(SecretService secretService,
            @Value("${app.bootstrap.jwt-secret.enabled:false}") boolean enabled) {
        this.secretService = secretService;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }

        try {
            secretService.fetchSecret(JWT_VERSION, JWT_BUS_NAME);
            log.info("JWT secret bootstrap skipped — secret already present",
                kv("operation", "jwt_secret_bootstrap"),
                kv("action", "skip_existing"),
                kv("status", "SUCCESS"));
            return;
        } catch (SecException e) {
            if (e.getErrorCode() != SecError.KEY_NOT_FOUND) {
                // A broken row (undecryptable, blank value, ...), not a missing one. Refuse to
                // start rather than silently creating a second active secret alongside it.
                throw e;
            }
        }

        try {
            secretService.createActiveSecret(JWT_VERSION, JWT_BUS_NAME);
            log.info("JWT secret bootstrap created a new active JWT signing secret",
                kv("operation", "jwt_secret_bootstrap"),
                kv("action", "create_secret"),
                kv("status", "SUCCESS"));
        } catch (DataIntegrityViolationException e) {
            log.warn("JWT secret bootstrap could not create the secret — likely lost a race with "
                    + "another instance's bootstrap, which already leaves the secret present",
                kv("operation", "jwt_secret_bootstrap"),
                kv("action", "skip_failed"),
                kv("status", "WARN"), e);
        }
    }
}
