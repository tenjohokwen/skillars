package com.softropic.skillars.infrastructure.security;



import com.softropic.skillars.infrastructure.persistence.AbstractAuditingEntity;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;

public class SessionIdAuditEntityListener {
    @PrePersist
    @PreUpdate
    @PreRemove
    public void recordSessionId(final AbstractAuditingEntity abstractAuditingEntity) {
        abstractAuditingEntity.setSessionId(RequestMetadataProvider.getClientInfo().getSessionId());
    }

}
