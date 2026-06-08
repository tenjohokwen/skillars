package com.softropic.skillars.infrastructure.persistence;



import com.softropic.skillars.infrastructure.security.RequestIdProvider;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;

/**
 * Puts the request id associated with the current thread in entity to be modified.
 */
//TODO move this to security/exposed
public class RequestIdAuditEntityListener {

    @PrePersist
    @PreUpdate
    @PreRemove
    public void recordRequestId(final AbstractAuditingEntity abstractAuditingEntity) {
        abstractAuditingEntity.setRequestId(RequestIdProvider.provideRequestId());
    }
}
