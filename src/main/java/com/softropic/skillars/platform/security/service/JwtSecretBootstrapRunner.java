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
 *   <li><strong>Enforced local-only guard (skillars-deferred-91 AC18).</strong> "MUST stay unset"
 *       used to be a javadoc promise only. Now, when the flag is {@code true}, the runner also
 *       requires {@code spring.datasource.url} to target {@code localhost} / {@code 127.0.0.1} /
 *       {@code [::1]} and throws (failing the boot) otherwise. Signal chosen: the datasource host,
 *       not a second co-located "i-understand" flag — a flag pair travels together when settings are
 *       copied between environments, a datasource pointed at a real DB does not. A misconfigured
 *       non-dev deploy that sets the flag {@code true} now fails to start instead of silently
 *       seeding a well-known signing secret.</li>
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

    /**
     * Matches a JDBC URL whose <em>entire</em> host authority is loopback. Covers
     * {@code jdbc:postgresql://localhost/db}, {@code //127.0.0.1:5432/db}, {@code //[::1]:5432/db}
     * and a {@code user:pass@host} authority. Anything else — a hostname, a private IP, an RDS
     * endpoint — is treated as non-local.
     *
     * <p>skillars-deferred-91 code review: this is matched with {@code matches()} against the
     * extracted authority, not {@code find()} over the whole URL. Scanning the whole string let a
     * pgjdbc multi-host failover URL through —
     * {@code jdbc:postgresql://localhost:5432,prod-db.internal:5432/skillars} contains
     * {@code //localhost:} and passed the guard while the driver could connect to production — as
     * would any URL carrying {@code //localhost} inside a query parameter. A comma-separated host
     * list now fails the guard because every host must be loopback for the authority to match.
     */
    private static final java.util.regex.Pattern LOCAL_AUTHORITY = java.util.regex.Pattern.compile(
        "(?:[^@/]*@)?(?:localhost|127\\.0\\.0\\.1|\\[::1\\]|\\[0:0:0:0:0:0:0:1\\])(?::\\d+)?",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Pulls the authority out of {@code …://<authority>/db?params} (everything between // and / or ?). */
    private static final java.util.regex.Pattern JDBC_AUTHORITY = java.util.regex.Pattern.compile(
        "^[^/]*//([^/?#]*)");

    /** True only when the URL has an authority and that whole authority is a loopback host. */
    static boolean targetsLoopback(String jdbcUrl) {
        final java.util.regex.Matcher authority = JDBC_AUTHORITY.matcher(jdbcUrl);
        if (!authority.find()) {
            return false;
        }
        return LOCAL_AUTHORITY.matcher(authority.group(1)).matches();
    }

    private final SecretService secretService;
    private final boolean enabled;
    private final String datasourceUrl;

    public JwtSecretBootstrapRunner(SecretService secretService,
            @Value("${app.bootstrap.jwt-secret.enabled:false}") boolean enabled,
            @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.secretService = secretService;
        this.enabled = enabled;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }

        // skillars-deferred-91 AC18: fail-fast local-only guard. A non-dev deploy that sets the flag
        // true must not boot and silently seed a known secret.
        final String url = datasourceUrl == null ? "" : datasourceUrl;
        if (!targetsLoopback(url)) {
            throw new IllegalStateException(
                "app.bootstrap.jwt-secret.enabled=true is a LOCAL-DEVELOPMENT-ONLY convenience, but "
              + "spring.datasource.url does not target localhost / 127.0.0.1 / [::1]. Refusing to seed "
              + "a well-known JWT signing secret into a non-local database — unset "
              + "app.bootstrap.jwt-secret.enabled for this environment. (datasource host is not loopback)");
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
