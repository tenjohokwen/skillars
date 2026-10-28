package com.softropic.skillars.platform.security.service;


import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.infrastructure.security.AuthorizationException;


import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface LoginTokenManager {
    /**
     * Create the token that will be used to identify the user
     * @param res Provides means of sending the created token to the client
     * @param principal holds the user data
     */
    void createLoginToken(HttpServletResponse res, Principal principal);

    /**
     * This method re-creates the login token.
     * This is very similar to #createLoginToken but in this case the user has previously logged in
     * The user is logged in but the old token is discarded.
     * The old token can be discarded for example if the db refresh interval has expired
     * @param res Provides means of sending the refreshed token to the client
     * @param principal holds the user data that is used to create the token
     */
    void renewLoginToken(HttpServletResponse res, Principal principal);

    /**
     * Sets session cookie for anonymous user
     * @param response
     * @return
     */
    String generateAnonymousSession(HttpServletResponse response);


    /**
     * Resets the Ttl of the token. It should not refresh the dbRefreshToken
     * @param req holds the incoming token
     * @param res Provides means of sending the created token to the client
     */
    void extendTtlOfToken(final HttpServletRequest req, final HttpServletResponse res);

    /**
     * Removes the login token by setting a removal request on the response
     * @param response Provides means of sending the request to remove the token to the client
     */
    void deleteLoginToken(final HttpServletResponse response);

    /**
     * Extracts user data from the login token and returns a new Principal object
     * @param request holds token information that is
     * @return the user data
     */
    Principal extractPrincipal(final HttpServletRequest request);

    /**
     * Finds out if the token has been manipulated.
     * Should detect when for example a token from a different client is used.
     * @param request Holds the incoming token
     * @return true if fixed, else false
     */
    boolean isTokenFixed(final HttpServletRequest request);

    /**
     * Checks if db refresh token has expired.
     * When expired, the principal has to be refetched from the db.
     * This gives the possibility of locking out a user.  i.e. When the call is made to the db, if user is locked then user cannot continue session
     * @param request holds the login token
     * @return true if expired else false
     */
    boolean hasDbRefreshTokenExpired(final HttpServletRequest request);

    /**
     * Extracts the username from the incoming token without throwing an error.
     * @param request holds the login token that is used to extract the username
     * @return user name else null
     */
    String extractUserNameSilently(final HttpServletRequest request);

    /**
     * Ensures the client id is available in the incoming request
     */
    void ensureClientHasPreLoginId();

    /**
     * Ensures login id set if user is already logged in
     */
    void ensureClientHasPostLoginId();

    /**
     * Ensures that the auth token is present in the incoming request
     * @param request  holds the auth token
     * @throws AuthorizationException if token is missing
     */
    void ensureAuthTokenPresent(HttpServletRequest request) throws AuthorizationException;

    Optional<String> extractSessionIdSilently(final HttpServletRequest request);

    /**
     * skillars-deferred-149 AC3 code review (2026-10-08): epoch-millis of when the incoming JWT's
     * session was last freshly authenticated (login or a successful DB-reauth) —
     * preserved unchanged across fast-path extensions, unlike the JWT's own {@code iat}. Null for
     * a token minted before this claim existed. See {@code SecurityConstants.SESSION_ISSUED_AT}'s
     * own javadoc for the full mechanism this feeds.
     * @param request holds the incoming token
     * @return the claim value, or null if absent
     */
    Long extractSessionIssuedAt(final HttpServletRequest request);
}
