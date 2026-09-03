package com.softropic.skillars.infrastructure.security;

import java.time.Duration;

/**
 * Consolidated security constants for the application.
 * Contains role definitions, security expressions, JWT/token constants, and cookie names.
 */
public final class SecurityConstants {

    // ==================== ROLE AND AUTHORITY CONSTANTS ====================

    /**
     * Standard role names (with ROLE_ prefix for Spring Security)
     */
    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_LTD_ADMIN = "ROLE_LTD_ADMIN";
    public static final String ROLE_COACH = "ROLE_COACH";
    public static final String ROLE_PARENT = "ROLE_PARENT";
    public static final String ROLE_PLAYER = "ROLE_PLAYER";

    /**
     * Authority names (without ROLE_ prefix)
     */
    public static final String AUTHORITY_USER = "USER";
    public static final String AUTHORITY_ADMIN = "ADMIN";
    public static final String AUTHORITY_LTD_ADMIN = "LTD_ADMIN";

    /**
     * Security expressions for @PreAuthorize annotations
     */
    public static final String HAS_ANY_ROLE = "hasRole('ROLE_ADMIN') or hasRole('ROLE_LTD_ADMIN') or hasRole('ROLE_USER') or hasRole('ROLE_COACH') or hasRole('ROLE_PARENT') or hasRole('ROLE_PLAYER')";
    public static final String HAS_ADMIN_ROLE = "hasRole('ROLE_ADMIN') or hasRole('ROLE_LTD_ADMIN')";
    public static final String HAS_COACH_ROLE = "hasRole('ROLE_COACH')";
    public static final String HAS_PARENT_ROLE = "hasRole('ROLE_PARENT')";
    public static final String HAS_PLAYER_ROLE = "hasRole('ROLE_PLAYER')";
    public static final String HAS_PARENT_OR_PLAYER_ROLE = "hasRole('ROLE_PARENT') or hasRole('ROLE_PLAYER')";
    public static final String HAS_PARENT_PLAYER_OR_COACH_ROLE =
        "hasRole('ROLE_PARENT') or hasRole('ROLE_PLAYER') or hasRole('ROLE_COACH')";

    /** Use on endpoints that require any authenticated user regardless of role. */
    public static final String IS_AUTHENTICATED = "isAuthenticated()";

    /** Use on public endpoints that are explicitly accessible without authentication. */
    public static final String IS_PERMIT_ALL = "permitAll()";

    // ==================== JWT AND AUTHENTICATION CONSTANTS ====================

    public static final String TOKEN_PREFIX = "Bearer ";
    public static final String HEADER_STRING = "Authorization";
    public static final String API_KEY_HEADER = "X-Client-Id";
    public static final String EMAIL = "email";
    public static final String GENDER = "gender";
    public static final String DISPLAY_NAME = "displayName";
    public static final String PASSWORD_KEY = "password";
    public static final String JWT_COOKIE_NAME = "potc";
    public static final String JWT_BUS_NAME = "jot";
    public static final String JWT_VERSION               = "v1";
    /**
     * Name of the {@code rint} cookie ("refresh instant").
     * <p>
     * {@code JwtManagerImpl.createLoginCookies} writes this cookie on every authenticated response
     * (login, refresh and TTL extension) as the JWT's <b>absolute expiry time in epoch
     * milliseconds</b> ({@code ClockProvider now + JWT_TTL}). Because the sliding window re-issues
     * the JWT with a fresh full TTL on every request, the value <b>advances</b> on each response.
     * <p>
     * The frontend ({@code src/frontend/src/plugins/sessionManager.js}) reads it as
     * {@code timeUntilExpiry = rint - Date.now()} and shows the session warning when that drops
     * below a client-side constant ({@code WARNING_THRESHOLD}, 5 min). No copy of {@code JWT_TTL}
     * is needed on the client; the contract is time-based, so a backend TTL change needs no
     * frontend change. It is also multi-tab safe — an idle tab sees a sibling's advanced value and
     * does not force a logout — and survives timer suspension across a laptop sleep, because the
     * deadline is a stored instant rather than an accumulating countdown.
     * <p>
     * It is <b>not</b> immune to client clock drift. {@code rint - Date.now()} subtracts a client
     * instant from a server instant, so the result carries the wall-clock offset between the two
     * machines; the client's legacy elapsed-time fallback is the skew-immune path. The frontend
     * therefore sanity-checks the computed remaining time and degrades to that fallback when it
     * falls outside a plausible band.
     * <p>
     * {@code HttpOnly=false} so JS can read it. {@code maxAge} is {@code JWT_TTL + 60s} so the
     * client can still read "expired at T" for a brief grace window after the JWT is gone.
     */
    public static final String SESSION_REFRESH_COUNTDOWN = "rint";
    public static final String B_COOKIE                  = "bcookie";
    public static final String F_COOKIE = "fcookie"; //fingerprint cookie. Set by browser
    public static final String USER_COOKIE = "user"; //username cookie
    /**
     * skillars-deferred-90 AC2: written into {@link #USER_COOKIE} in place of a blank display name
     * so a valid session is never represented by an empty cookie value. An empty value is read as
     * "authenticated" by a naive presence check and as "session dead" by a liveness check — both
     * wrong. Frontend readers (see {@code utils/sessionCookies.js}) must treat this value, and a
     * blank value, as "no display name" and fall back to their generic greeting.
     */
    public static final String BLANK_DISPLAY_NAME_SENTINEL = "__blank__";
    public static final String ADMIN_COOKIE = "admin"; //admin cookie
    public static final String JAVA_SESSION_COOKIE = "JSESSIONID";
    public static final String JWT_SESSION_COOKIE = "ION"; //jwt session id cookie
    public static final String ANONYMOUS_SESSION_COOKIE = "nym";
    public static final String LOCALE_COOKIE = "lang";
    public static final Duration JWT_TTL = Duration.ofMinutes(15);
    public static final String REFRESH_TOKEN_COOKIE    = "rtkn";
    public static final String SKILLARS_PROFILE_COOKIE = "skp";
    public static final Duration REFRESH_TOKEN_TTL     = Duration.ofDays(7);
    public static final String ROLES = "roles";
    public static final String LOGIN_ID = "id";
    public static final String LOGIN_CODE = "loginCode";
    public static final String OTP_ENABLED = "otpEnabled";


    /*
    This token is used to determine the interval at which a db call can be made.
    The db call is necessary to make sure that once logged in the user's access can still be revoked.
    Without this token there will be no check in the db to verify if the user's rights have been revoked
    Else the user will remain logged in forever so long as he makes requests to the server within the ttl of JWT
    */
    public static final String DB_REFRESH_TOKEN = "dbRToken";

    public static final Duration DB_REFRESH_TOKEN_INTERVAL = Duration.ofMinutes(5);
    public static final String BUS_ID = "busId";
    public static final Duration OTP_TTL = Duration.ofMinutes(30);
    public static final String LOGIN_INFO_ID = "lii";
    public static final String PHONE         = "phone";
    public static final String USERNAME = "username";
    public static final String SESSION_ID = "sessionId";
    public static final String CLIENT_ID = "clientId";
    public static final String USER_AGENT_HASH = "userAgent";
    public static final String OPF_SEED = "opfSeed";

    private SecurityConstants() {
        // Prevent instantiation
    }
}
