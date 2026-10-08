package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.repo.RefreshToken;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import static com.softropic.skillars.infrastructure.security.SecurityConstants.REFRESH_TOKEN_COOKIE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-deferred-149 AC3: real-wiring proof that {@code UserRepository
 * .invalidateSessionsForUser}'s {@code REQUIRES_NEW} propagation is load-bearing, not decorative.
 * A mocked {@code UserRepository} (as in {@code AuthServiceTest}) can only verify the call was
 * made — it cannot observe whether the write actually survives the surrounding transaction's
 * rollback. This class autowires the real {@code AuthService} + real repositories against a real
 * Postgres, mirroring the project's established revert-experiment convention: Completion Notes
 * record this test run once with {@code REQUIRES_NEW} removed (column observed NULL — the no-op
 * this bug would ship as) and once restored (column observed non-NULL and durable).
 */
class AuthServiceSessionInvalidationIT extends AbstractIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SecretService secretService;

    private static final String CLIENT_ID = "testClientId";

    private static final long USER_ID = 9149_100_001L;
    private static final long USER_ID_2 = 9149_100_002L;
    private static final long USER_ID_3 = 9149_100_003L;
    private static final String RAW_PASSWORD = "TestPass@123!";

    @Test
    void refresh_tokenReuseDetected_durablyFlipsSecuritySessionInvalidatedAt() {
        transactionTemplate.execute(status -> {
            insertUser(USER_ID, "sessioninvalidation.theft@skillars-test.com");
            return null;
        });

        String rawToken = UUID.randomUUID().toString();
        RefreshToken usedToken = new RefreshToken();
        usedToken.setUserId(USER_ID);
        usedToken.setTokenHash(sha256Hex(rawToken));
        usedToken.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        usedToken.setUsed(true); // already used, rotatedAt null -> outside the grace-window branch
        refreshTokenRepository.save(usedToken);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(REFRESH_TOKEN_COOKIE, rawToken));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> authService.refresh(request, response))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessageContaining("Token reuse detected");

        Timestamp invalidatedAt = jdbcTemplate.queryForObject(
            "SELECT security_session_invalidated_at FROM main.\"user\" WHERE id = ?",
            Timestamp.class, USER_ID);
        assertThat(invalidatedAt)
            .as("REQUIRES_NEW must survive AuthService.refresh()'s own rollback-on-throw — an "
                + "ambient write here would leave this column NULL forever (AC3's no-op bug)")
            .isNotNull();
    }

    /**
     * skillars-deferred-149 AC3 code review (2026-10-08): the exact vulnerability the review
     * found. An earlier implementation cleared this column in {@code AuthService.login()} — which
     * meant the VICTIM's own innocent re-login (the natural next action after being logged out by
     * theft detection) silently re-armed the attacker's still-live stolen JWT account-wide, not
     * just the victim's own new session. Proven here end-to-end against a real Postgres: trigger
     * theft detection, then perform a genuine successful login for the same account, then assert
     * the revocation epoch is untouched — exactly what a per-JWT (not per-account) revocation
     * check requires to be sound.
     */
    @Test
    void realLoginAfterTheftDetection_doesNotClearInvalidationEpoch() {
        String email = "sessioninvalidation.relogin@skillars-test.com";
        String passwordHash = passwordEncoder.encode(RAW_PASSWORD);
        transactionTemplate.execute(status -> {
            insertUser(USER_ID_2, email, passwordHash);
            return null;
        });

        String rawToken = UUID.randomUUID().toString();
        RefreshToken usedToken = new RefreshToken();
        usedToken.setUserId(USER_ID_2);
        usedToken.setTokenHash(sha256Hex(rawToken));
        usedToken.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        usedToken.setUsed(true);
        refreshTokenRepository.save(usedToken);

        MockHttpServletRequest refreshRequest = new MockHttpServletRequest();
        refreshRequest.setCookies(new Cookie(REFRESH_TOKEN_COOKIE, rawToken));
        MockHttpServletResponse refreshResponse = new MockHttpServletResponse();
        assertThatThrownBy(() -> authService.refresh(refreshRequest, refreshResponse))
            .isInstanceOf(BadCredentialsException.class);

        Timestamp invalidatedAtAfterTheft = jdbcTemplate.queryForObject(
            "SELECT security_session_invalidated_at FROM main.\"user\" WHERE id = ?",
            Timestamp.class, USER_ID_2);
        assertThat(invalidatedAtAfterTheft).as("theft detection must have set the epoch").isNotNull();

        // Seed the JWT signing secret: main.sec is neither Flyway-seeded reference data nor an
        // infrastructure-excluded table (see DatabaseResetTestExecutionListener's own javadoc on
        // exactly this table, citing this exact "collides with the fixed-PK insert" history), so
        // it is truncated by this test's own per-method reset and never re-seeded outside the
        // handful of IT classes that explicitly import E2ESecurityConfig for this reason —
        // unrelated to anything this story's AC3 redesign touches. Without a row here, JWT
        // signing itself fails (JwtConfiguration.getSecretKey), independent of which code path
        // (HTTP or direct call) reaches it.
        secretService.createActiveSecret(SecurityConstants.JWT_VERSION, SecurityConstants.JWT_BUS_NAME);

        // The victim's own legitimate re-login, using their real password. Calls authService
        // .login(...) directly (not through HTTP): populating RequestMetadataProvider directly
        // supplies the one piece of request-scoped state TokenCreatorImpl.getClientId actually
        // needs (the API-key header), without needing a full servlet round trip.
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        loginRequest.addHeader(SecurityConstants.API_KEY_HEADER, CLIENT_ID);
        RequestMetadataProvider.initRequestMetadata(loginRequest);
        try {
            authService.login(email, RAW_PASSWORD, "127.0.0.1", new MockHttpServletResponse());
        } finally {
            RequestMetadataProvider.cleanup();
        }

        Timestamp invalidatedAtAfterLogin = jdbcTemplate.queryForObject(
            "SELECT security_session_invalidated_at FROM main.\"user\" WHERE id = ?",
            Timestamp.class, USER_ID_2);
        assertThat(invalidatedAtAfterLogin)
            .as("login() must NOT clear the revocation epoch — doing so would re-arm the "
                + "attacker's still-live stolen JWT the moment the victim logs back in")
            .isEqualTo(invalidatedAtAfterTheft);
    }

    /**
     * skillars-deferred-149 AC3 code review (2026-10-08): {@code User} has no {@code @Version} and
     * no {@code @DynamicUpdate}, so a plain managed-entity save flushes a static {@code UPDATE}
     * naming every mapped column using whatever in-memory value each field held — including a
     * stale, pre-revocation {@code null} for this one, silently undoing the bulk
     * {@code invalidateSessionsForUser} write. Proven here against a real Postgres: load the user
     * BEFORE the revocation (capturing the stale null in memory), trigger the revocation via the
     * real bulk query, then save the stale entity — the column must survive.
     */
    @Test
    void concurrentManagedEntitySave_doesNotRevertInvalidationEpoch() {
        transactionTemplate.execute(status -> {
            insertUser(USER_ID_3, "sessioninvalidation.staleload@skillars-test.com");
            return null;
        });

        // Captures security_session_invalidated_at = null in memory, as a concurrent request's
        // own load would, before this story's theft-detection write ever runs.
        User staleUser = userRepository.findById(USER_ID_3).orElseThrow();

        userRepository.invalidateSessionsForUser(USER_ID_3, Instant.now());
        Timestamp invalidatedAtAfterRevocation = jdbcTemplate.queryForObject(
            "SELECT security_session_invalidated_at FROM main.\"user\" WHERE id = ?",
            Timestamp.class, USER_ID_3);
        assertThat(invalidatedAtAfterRevocation).isNotNull();

        // A plain managed-entity save of the STALE (pre-revocation) in-memory snapshot — the
        // concurrency hazard. Something harmless (first name) is mutated so this is a genuine
        // dirty-checked flush, not a no-op.
        staleUser.setFirstName("Updated");
        transactionTemplate.execute(status -> {
            userRepository.save(staleUser);
            return null;
        });

        Timestamp invalidatedAtAfterStaleSave = jdbcTemplate.queryForObject(
            "SELECT security_session_invalidated_at FROM main.\"user\" WHERE id = ?",
            Timestamp.class, USER_ID_3);
        assertThat(invalidatedAtAfterStaleSave)
            .as("insertable=false, updatable=false on User.securitySessionInvalidatedAt must make "
                + "Hibernate omit this column from the stale entity's own UPDATE entirely — "
                + "otherwise this concurrent save silently reverts the revocation to NULL")
            .isEqualTo(invalidatedAtAfterRevocation);
    }

    private void insertUser(long id, String email) {
        // A real 60-char bcrypt hash, not a 1-char placeholder: User.password carries
        // @Size(min=60,max=60), enforced by Bean Validation on every managed-entity UPDATE flush
        // (not on the raw jdbcTemplate INSERT below) — a short placeholder only breaks the test
        // that later does a plain userRepository.save(...) on this row.
        insertUser(id, email, passwordEncoder.encode("placeholder-not-used-for-login"));
    }

    private void insertUser(long id, String email, String passwordHash) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', ?, ?, 'Test', 'OTHER', 'en', 'Theft', 'DE', ?, " +
            "true, false, ?, 'EMAIL', ?, false, " +
            "'COACH', 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            Date.valueOf(LocalDate.of(1990, 3, 15)),
            email,
            "915" + (id % 10000000),
            email, passwordHash);
    }

    private static String sha256Hex(String raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
