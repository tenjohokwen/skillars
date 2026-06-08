package com.softropic.skillars.platform.security.service;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.base.Ticker;


import com.softropic.skillars.infrastructure.security.RequestMetadata;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;


/**
 * Records failed logins and uses this information to determine if a login can be allowed or not.
 * On a general note, successful login undoes the failed ones. There are however things to take into consideration:
 *  <ul>
     <li>It should be possible to use a blocked client to log a different user. So use a combination of clientId and username</li>
     <li>The client id (browser cookie or apikey) could be faked or stolen, leading to unique ids per request. So also take Ips into account.</li>
     <li>Several people could share an ip address. So avoid blocking just by ip. Use ip and loginId</li>
     <li>Ip addresses can be faked. So keep a higher count of failed logins for a given user to delay this</li>
    </ul>
 *
 * Levels of locking
 * <ol>
 *     <li>By clientId and user</li>
 *     <li>By Ip and user</li>
 *     <li>By user</li>
 * </ol>
 */
//TODO document all changes that need to be made once for the application to be multi-node ready. This cache cannot be used for multi node apps
@Service("loginAttemptService")
public class LoginAttemptsService implements LoginDecisionManager<RequestMetadata>,
                                             LoginAttemptConsumer<RequestMetadata> {

    /*
    The client (browser/api machine client) is blocked when login failure occurs 3 times from same client within a time frame
     */
    private static final int MAX_FAILED_CLIENT_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptsService.class);

    private static final String LOG_TAG = "LOGIN ATTEMPT NOTICE: ";

    /*
    The ip address is blocked when login failure occurs {@code MAX_FAILED_CLIENT_ATTEMPTS} times within a time frame
     */
    private static final int MAX_FAILED_IP_ATTEMPTS = MAX_FAILED_CLIENT_ATTEMPTS;

    /*
     A rogue client will be able to make {@code MAX_FAILED_CLIENT_ATTEMPTS + 2} attempts when it fakes ip addresses and clientIds
     */
    private static final int MAX_FAILED_USER_ATTEMPTS = MAX_FAILED_CLIENT_ATTEMPTS + 2;

    /**
     * Separator used to build composite cache keys. Must not appear unescaped in any field value.
     */
    private static final String KEY_SEPARATOR = "|";
    private static final String KEY_SEPARATOR_ESCAPED = "%7C";

    private final LoadingCache<String, Integer> blacklistedClients;

    //only the userName (loginId) is used as key here
    private final LoadingCache<String, Integer> attemptsByUserCache;

    //ip|userName key
    private final LoadingCache<String, Integer> attemptsByIpUserCache;

    //clientId|userName key
    private final LoadingCache<String, Integer> attemptsByClientUserCache;

    private final ClientIdAccessDecisionManager clientIdAccessDecisionVoter;


    @Autowired
    public LoginAttemptsService(final ClientIdAccessDecisionManager clientIdAccessDecisionMgr) {
        this(clientIdAccessDecisionMgr, Ticker.systemTicker());
    }

    // Constructor for testing with a controllable ticker
    public LoginAttemptsService(final ClientIdAccessDecisionManager clientIdAccessDecisionVoter, final Ticker ticker) {
        attemptsByUserCache      = buildCache(ticker, 4,  TimeUnit.HOURS);
        attemptsByIpUserCache    = buildCache(ticker, 4,  TimeUnit.HOURS);
        attemptsByClientUserCache = buildCache(ticker, 4, TimeUnit.HOURS);
        blacklistedClients       = buildCache(ticker, 30, TimeUnit.DAYS);
        this.clientIdAccessDecisionVoter = clientIdAccessDecisionVoter;
    }

    @Override
    public void loginSucceeded(final RequestMetadata metadata) {
        deRecordAttempts(metadata);
    }

    @Override
    public void loginFailed(final RequestMetadata metadata) {
        //username will be blank if userName validation fails
        if(StringUtils.isNotBlank(metadata.getUserName())) {
            recordAttempts(attemptsByUserCache, metadata.getUserName());
            recordAttempts(attemptsByIpUserCache, getIpUserKey(metadata));
            recordAttempts(attemptsByClientUserCache, getClientIdUserKey(metadata));
        }
    }

    @Override
    public boolean isAllowed(final RequestMetadata metadata) {
        if(StringUtils.isBlank(metadata.getUserName())) {
            // Nothing is recorded for blank usernames (see loginFailed).
            // Upstream validation should reject blank usernames before this point.
            return true;
        }
        try {
            return  isBelowClientAttemptLimit(metadata)
                    &&
                    isIpAndUserAllowed(metadata)
                    &&
                    isBelowUserAttemptLimit(metadata)
                    &&
                    isClientIdAllowed() //Explicitly invoke this since call does not go down to controller level. AccessDecisionVoters are called by interceptors. (interceptors are called before controllers)
                    &&
                    clientNotBlacklisted(metadata.getClientIdentifier());
        } catch (ExecutionException e) {
            log.error("Unexpected cache error during login decision, failing open",
                kv("operation", "login_attempts"),
                kv("status", "CACHE_ERROR"),
                e);
            return true;
        }
    }

    public void resetLoginRecording() {
        attemptsByClientUserCache.invalidateAll();
        attemptsByIpUserCache.invalidateAll();
        attemptsByUserCache.invalidateAll();
        blacklistedClients.invalidateAll();
    }

    private boolean isClientIdAllowed() {
        final boolean clientIdAllowed = clientIdAccessDecisionVoter.isClientIdAllowed();
        if(!clientIdAllowed) {
            log.error("Client id is already blacklisted",
                kv("operation", "login_attempts"),
                kv("status", "CLIENT_BLACKLISTED"));
        }
        return clientIdAllowed;
    }

    private boolean isBelowUserAttemptLimit(RequestMetadata metadata) throws ExecutionException {
        final String userName = metadata.getUserName();
        final boolean isBelowUserAttemptLimit = attemptsByUserCache.get(userName) < MAX_FAILED_USER_ATTEMPTS;
        if(!isBelowUserAttemptLimit) {
            log.error("User exceeded permitted login attempts for current window",
                kv("operation", "login_attempts"),
                kv("status", "USER_ATTEMPTS_EXCEEDED"));
        }
        return isBelowUserAttemptLimit;
    }

    private boolean isBelowClientAttemptLimit(RequestMetadata metadata) throws ExecutionException {
        final boolean isBelowMaxClientAttempts = attemptsByClientUserCache.get(getClientIdUserKey(metadata)) < MAX_FAILED_CLIENT_ATTEMPTS;
        if(!isBelowMaxClientAttempts) {
            log.error("Client login attempts exceed amount permitted within window",
                kv("operation", "login_attempts"),
                kv("status", "CLIENT_ATTEMPTS_EXCEEDED"));
        }
        return isBelowMaxClientAttempts;
    }

    private boolean clientNotBlacklisted(final String clientId) throws ExecutionException {
        if(StringUtils.isNotBlank(clientId)) {
            final boolean isNotBlacklisted = blacklistedClients.get(clientId) == 0;
            if(!isNotBlacklisted) { //is blacklisted
                log.error("Client is already blacklisted",
                    kv("operation", "login_attempts"),
                    kv("status", "CLIENT_BLACKLISTED"));
            }
            return isNotBlacklisted;
        }
        return true;
    }

    /**
     * Uses client identifier to blacklist client.
     * Note: blacklisting IS enforced in isAllowed() via clientNotBlacklisted().
     * What's missing: persistence. Blacklisted clients are only held in-memory and expire
     * after the cache window. For permanent blacklisting, persist to DB here.
     * @param metadata provides the client data.
     */
    @Override
    public void blacklistClient(final RequestMetadata metadata) {
        //TODO could create a table in db and add a service here to populate blacklisted clients and the reasons
        //Blacklisted clients will not have access to the system anymore regardless of the user they use
        log.warn("Blacklisting client",
            kv("operation", "login_attempts"),
            kv("status", "CLIENT_BLACKLISTING"));
        //The user is probably not known so just the client is blacklisted.
        //The getClientIdentifier here may not exist e.g for a browser (or else many users will share the same key)
        if(StringUtils.isNotBlank(metadata.getClientIdentifier())) {
            recordAttempts(blacklistedClients, metadata.getClientIdentifier());
        }
    }

    public void unblacklistClient(final String clientId) {
        blacklistedClients.invalidate(clientId);
    }

    @Override
    public void unblockClient(final RequestMetadata metadata) {
        deRecordAttempts(metadata);
    }

    /**
     * Removes all login-attempt locks for the given username across all cache layers.
     * Intended for admin use when a legitimate user is locked out and cannot wait for the cache window to expire.
     * <p>
     * Because composite keys are structured as {@code escapedField|escapedUsername}, all entries
     * belonging to the username can be identified by their suffix and removed atomically.
     * </p>
     *
     * @param username the login name of the user to unlock
     */
    public void unlockUser(final String username) {
        final String userKeySuffix = KEY_SEPARATOR + escapeField(username);
        attemptsByUserCache.invalidate(username);
        attemptsByClientUserCache.asMap().keySet().removeIf(key -> key.endsWith(userKeySuffix));
        attemptsByIpUserCache.asMap().keySet().removeIf(key -> key.endsWith(userKeySuffix));
        log.info("Login-attempt locks cleared",
            kv("operation", "login_attempts"),
            kv("action", "unlock"),
            kv("status", "SUCCESS"));
    }

    private String getClientIdUserKey(final RequestMetadata metadata) {
        return escapeField(StringUtils.defaultString(metadata.getClientIdentifier())) + KEY_SEPARATOR + escapeField(metadata.getUserName());
    }

    private String getIpUserKey(final RequestMetadata metadata) {
        String ipAddress = StringUtils.defaultString(metadata.getIpAddress());
        return escapeField(ipAddress) + KEY_SEPARATOR + escapeField(metadata.getUserName());
    }

    /**
     * Escapes the key separator character in a field value to prevent composite key collisions.
     */
    private String escapeField(final String value) {
        return value.replace(KEY_SEPARATOR, KEY_SEPARATOR_ESCAPED);
    }

    /**
     * Atomically increments the attempt count for the given identifier.
     */
    private void recordAttempts(final LoadingCache<String, Integer> cache, final String identifier) {
        cache.asMap().merge(identifier, 1, Integer::sum);
    }

    /**
     * Removes all previously failed attempts of the client-user combination.
     * Since this count also incremented the other caches, it decrements them on removal.
     * The user-level cache is always fully cleared on success, covering the case where the
     * user previously failed from a different client or IP than the one used for this success.
     * @param metadata holds the client info needed for decrementing the cache counts.
     */
    private void deRecordAttempts(final RequestMetadata metadata) {
        int attempts;
        final String clientIdUserKey = getClientIdUserKey(metadata);
        try {
            attempts = attemptsByClientUserCache.get(clientIdUserKey);
            attemptsByClientUserCache.invalidate(clientIdUserKey);
            if(attempts > 0) {
                //The failed attempts were equally recorded in the other 2 caches. Reduce them.
                // NB This is not accurate but the best way to go about this
                // NB The user may have had failed logins using the same client but different ips
                // NB The net effect is that the reduction occurs. In most cases the user will use the same ip though
                // NB This will not cause much/any harm even if user previously logged from different ips
                countDownAttempts(attemptsByIpUserCache, getIpUserKey(metadata), attempts);
                countDownAttempts(attemptsByUserCache, metadata.getUserName(), attempts);
            }
        } catch (ExecutionException e) {
            // key not found in cache — nothing to clear
        }
        // Always clear the user-level lock regardless of which client was used during failures.
        // This covers the case where the successful login comes from a different client than the failed attempts.
        attemptsByUserCache.invalidate(metadata.getUserName());
    }

    /**
     * Atomically decrements the attempt count for the given identifier.
     * Removes the entry if the count reaches zero or below.
     */
    private void countDownAttempts(final LoadingCache<String, Integer> cache, final String identifier, final int count) {
        cache.asMap().computeIfPresent(identifier, (k, v) -> {
            int result = v - count;
            return result > 0 ? result : null; // returning null removes the entry
        });
    }

    private boolean isIpAndUserAllowed(final RequestMetadata metadata) {
        //TODO also verify that ip is in whitelist
        try {
            final boolean isAttemptsByIpBelowLimit = attemptsByIpUserCache.get(getIpUserKey(metadata)) < MAX_FAILED_IP_ATTEMPTS;
            if(!isAttemptsByIpBelowLimit) {
                log.error("Current IP address has exceeded login attempt limits for given window",
                    kv("operation", "login_attempts"),
                    kv("status", "IP_ATTEMPTS_EXCEEDED"));
            }
            return isAttemptsByIpBelowLimit;
        } catch (ExecutionException e) {
            log.error("Unexpected cache error during IP check, failing open",
                kv("operation", "login_attempts"),
                kv("status", "CACHE_ERROR"),
                e);
            return true;
        }
    }

    private LoadingCache<String, Integer> buildCache(Ticker ticker, long duration, TimeUnit unit) {
        return CacheBuilder.newBuilder()
                           .ticker(ticker)
                           .expireAfterWrite(duration, unit)
                           .build(new CacheLoader<>() {
                    @SuppressWarnings("NullableProblems")
                    @Override
                    public Integer load(final String key) {
                        return 0;
                    }
                });
    }

}
