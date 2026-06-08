package com.softropic.skillars.platform.video.contract;

/**
 * Consistency guarantees provided by {@link QuotaProvider} implementations.
 */
public enum ConsistencyGuarantee {
    /**
     * Strict consistency: All operations are consistent across all nodes.
     * Guaranteed via strong locking (e.g., SELECT FOR UPDATE).
     */
    STRICT,

    /**
     * Eventual consistency: Quota might be temporarily out-of-sync
     * (e.g., asynchronous updates, read-replicas).
     */
    EVENTUAL
}
