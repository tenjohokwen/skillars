package com.softropic.skillars.platform.security.service;


import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.infrastructure.security.AuthorizationException;

import org.springframework.security.core.Authentication;

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
     * Resets the time-to-live, db refresh interval and expiration of the fetched token
     * @param res Provides means of sending the created token to the client
     * @param token Token whose TTl, dbrefresh and expiration will be reset
     */
    Authentication refreshLoginToken(HttpServletResponse res, String token);

    /**
     * Creates the login token and embeds the seed in claims
     * @param principal holds the user data that is used to create the token
     * @param seed is stored in the claims
     * @return the created token
     */
    String generateToken(final Principal principal, String seed);

    /**
     * Sets session cookie for anonymous user
     * @param response
     * @return
     */
    String generateAnonymousSession(HttpServletResponse response);


    /** TODO //maybe this should not be removed? Just let it expire or else you may be revealing secrets?
     * Removes the 2FA cookie from the client side.
     * @param response used to remove the 2FA cookie on the client side
     */
    void removeTwoFactorCookie(HttpServletResponse response);

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
     * Sets the login info id cookie
     * @param res Provides means of sending the request to set the login id on the client sent
     * @param value to be set as login id
     */
    void addLoginInfoIdCookie(HttpServletResponse res, String value);

    /**
     * Gets the login info id from the incoming request.
     * @param req holds the login token
     * @return the fetched login token or null
     */
    String extractLoginInfoIdCookie(HttpServletRequest req);

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
}
