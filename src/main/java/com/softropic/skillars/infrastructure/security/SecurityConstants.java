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

    /**
     * Authority names (without ROLE_ prefix)
     */
    public static final String AUTHORITY_USER = "USER";
    public static final String AUTHORITY_ADMIN = "ADMIN";
    public static final String AUTHORITY_LTD_ADMIN = "LTD_ADMIN";

    /**
     * Security expressions for @PreAuthorize annotations
     */
    public static final String HAS_ANY_ROLE = "hasRole('ROLE_ADMIN') or hasRole('ROLE_LTD_ADMIN') or hasRole('ROLE_USER') or hasRole('ROLE_COACH') or hasRole('ROLE_PARENT')";
    public static final String HAS_ADMIN_ROLE = "hasRole('ROLE_ADMIN') or hasRole('ROLE_LTD_ADMIN')";
    public static final String HAS_PARENT_ROLE = "hasRole('ROLE_PARENT')";

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
    public static final String SESSION_REFRESH_COUNTDOWN = "rint"; //Not used at the moment but Clients should actually use this to alert users that their session is about to expire
    public static final String B_COOKIE                  = "bcookie";
    public static final String F_COOKIE = "fcookie"; //fingerprint cookie. Set by browser
    public static final String USER_COOKIE = "user"; //username cookie
    public static final String ADMIN_COOKIE = "admin"; //admin cookie
    public static final String JAVA_SESSION_COOKIE = "JSESSIONID";
    public static final String JWT_SESSION_COOKIE = "ION"; //jwt session id cookie
    public static final String ANONYMOUS_SESSION_COOKIE = "nym";
    public static final String LOCALE_COOKIE = "lang";
    public static final Duration JWT_TTL = Duration.ofMinutes(15);
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
