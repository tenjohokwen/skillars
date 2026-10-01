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
 *       requires {@code spring.datasource.url}'s host to resolve to a loopback, link-local, or
 *       private (RFC 1918) address and throws (failing the boot) otherwise — see
 *       {@link #targetsLoopback(String)} for why this resolves the host rather than
 *       string-matching it, and for the tradeoff that widening deliberately accepts. Signal
 *       chosen: the datasource host, not a second co-located "i-understand" flag — a flag pair
 *       travels together when settings are copied between environments, a datasource pointed at
 *       a real DB does not. A misconfigured non-dev deploy that sets the flag {@code true} now
 *       fails to start instead of silently seeding a well-known signing secret.</li>
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

    /** Pulls the authority out of {@code …://<authority>/db?params} (everything between // and / or ?). */
    private static final java.util.regex.Pattern JDBC_AUTHORITY = java.util.regex.Pattern.compile(
        "^[^/]*//([^/?#]*)");

    /**
     * True when the JDBC URL has a single-host authority and that host <em>resolves</em> to an
     * address that cannot be a real, internet-routable database endpoint — loopback
     * ({@code 127.0.0.0/8}, {@code ::1}), link-local ({@code 169.254.0.0/16}), or private RFC
     * 1918 ({@code 10/8}, {@code 172.16/12}, {@code 192.168/16}).
     *
     * <p>The original implementation only string-matched the authority against the literal hosts
     * {@code localhost}/{@code 127.0.0.1}/{@code [::1]}. That is too narrow for this project's own
     * all-Docker local setup: {@code docker-compose.yml} always points {@code spring.datasource.url}
     * at the literal hostname {@code postgres} (the Compose service name, identical in the local
     * override and the base file), which never matches those literals even though it resolves to
     * an address on the project's own private bridge network. Resolving the host and classifying
     * the resulting address, instead of string-matching the hostname, recognizes that topology
     * without hardcoding this project's own service names.
     *
     * <p><strong>Known limitation, accepted deliberately:</strong> a real production database that
     * happens to sit on a private VPC subnet (a common, legitimate cloud topology) also resolves to
     * an RFC 1918 address and would pass this check. This guard's primary defense is still the
     * {@code enabled} property gate (constructor-injected, default {@code false}, {@code true} only
     * under the {@code dev} profile) — this address check is defense-in-depth for "the flag got
     * enabled somewhere it shouldn't have," not a guarantee against every possible
     * misconfiguration.
     *
     * <p>DNS resolution failure is treated as NOT local — a guard whose entire purpose is refusing
     * to act unless it can prove locality must not fail open just because it could not resolve the
     * host at all.
     *
     * <p>skillars-deferred-91 code review: a multi-host (comma-separated, pgjdbc failover syntax)
     * authority is still rejected outright, unchanged from the original implementation — resolving
     * only the first host would let a real second host ride along, the exact bypass that guard
     * closed.
     */
    static boolean targetsLoopback(String jdbcUrl) {
        final java.util.regex.Matcher authorityMatcher = JDBC_AUTHORITY.matcher(jdbcUrl);
        if (!authorityMatcher.find()) {
            return false;
        }
        String authority = authorityMatcher.group(1);
        int at = authority.lastIndexOf('@');
        String hostsPart = at >= 0 ? authority.substring(at + 1) : authority;
        if (hostsPart.isBlank() || hostsPart.contains(",")) {
            return false;
        }

        String host = extractHost(hostsPart);
        if (host.isBlank()) {
            return false;
        }

        try {
            java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return false;
            }
            for (java.net.InetAddress address : addresses) {
                if (!isNonRoutable(address)) {
                    return false;
                }
            }
            return true;
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    /** Strips a trailing {@code :port} and, for a bracketed IPv6 literal, the brackets themselves. */
    private static String extractHost(String hostAndMaybePort) {
        String h = hostAndMaybePort.trim();
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            return close >= 0 ? h.substring(1, close) : h;
        }
        int colon = h.indexOf(':');
        return colon >= 0 ? h.substring(0, colon) : h;
    }

    private static boolean isNonRoutable(java.net.InetAddress address) {
        return address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress();
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
