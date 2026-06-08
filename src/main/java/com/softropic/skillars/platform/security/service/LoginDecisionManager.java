package com.softropic.skillars.platform.security.service;

public interface LoginDecisionManager<T> {
    /**
     * Makes the decision whether the login is allowed
     * @param key value used as an identifier.
     * @return true if allowed to log in or else false.
     */
    boolean isAllowed(T key);

    /**
     * Blacklist client.
     * Should occur when a rogue client is identified
     * @param key value used as an identifier.
     */
    void blacklistClient(T key);

    /**
     * Unblocks client
     * @param key value used as an identifier.
     */
    void unblockClient(T key);
}
