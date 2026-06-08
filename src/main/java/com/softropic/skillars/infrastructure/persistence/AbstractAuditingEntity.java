package com.softropic.skillars.infrastructure.persistence;


import com.softropic.skillars.infrastructure.security.SessionIdAuditEntityListener;

import org.hibernate.envers.Audited;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;


/**
 * Base abstract class for entities which will hold definitions for created, last modified by and created,
 * last modified by date.
 */
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Audited
@MappedSuperclass
@EntityListeners({RequestIdAuditEntityListener.class, SessionIdAuditEntityListener.class, AuditingEntityListener.class})
public abstract class AbstractAuditingEntity extends BaseEntity {

    @CreatedBy
    //@NotNull//(groups = {Audit.class})
    @Column(name = "created_by", /*nullable = false,*/ length = 50, updatable = false)
    protected String createdBy;

    @CreatedDate
    //@NotNull//(groups = {Audit.class}) //hibernate calls BeanValidationEventListener before insert
    @Column(name = "created_date", /*nullable = false,*/ updatable = false)
    protected Instant createdDate;

    @LastModifiedBy
    @Column(name = "last_modified_by", length = 50 /*, nullable = false,*/)
    protected String lastModifiedBy;

    @LastModifiedDate
    @Column(name = "last_modified_date")
    protected Instant lastModifiedDate;

    @Column(name = "request_id")
    protected String requestId;

    @Column(name = "session_id", columnDefinition = "text")
    private String sessionId;

    @Builder.Default
    @NotNull//(groups = {Audit.class})
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    protected EntityStatus status = EntityStatus.INACTIVE;


    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(final String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(final Instant createdDate) {
        this.createdDate = createdDate;
    }

    public String getLastModifiedBy() {
        return lastModifiedBy;
    }

    public void setLastModifiedBy(final String lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

    public Instant getLastModifiedDate() {
        return lastModifiedDate;
    }

    public void setLastModifiedDate(final Instant lastModifiedDate) {
        this.lastModifiedDate = lastModifiedDate;
    }

    public void setRequestId(final String requestId) {
        this.requestId = requestId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public EntityStatus getStatus() {
        return status;
    }

    public void setStatus(final EntityStatus status) {
        this.status = status;
    }

}
