package com.softropic.skillars.platform.notification.repo;

import com.softropic.skillars.platform.notification.contract.EmailDeliveryStatus;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Version;


@Entity
public class EnvelopeEntity implements Serializable {
    @Id
    private UUID id;

    @Version
    private long version;

    @ElementCollection
    private List<RecipientEntity> recipients;

    @Enumerated(EnumType.STRING)
    private EmailTemplate emailTemplate;

    private Instant deadline;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> data;

    private long attempts;

    @Enumerated(EnumType.STRING)
    private EmailDeliveryStatus status;

    @Column(columnDefinition = "text")
    private String error;

    @Column(columnDefinition = "text")
    private boolean retry = false;

    @Column(unique = true)
    private String sendId;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public List<RecipientEntity> getRecipients() {
        return recipients;
    }

    public void setRecipients(List<RecipientEntity> recipients) {
        this.recipients = recipients;
    }

    public EmailTemplate getEmailTemplate() {
        return emailTemplate;
    }

    public void setEmailTemplate(EmailTemplate emailTemplate) {
        this.emailTemplate = emailTemplate;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public void setDeadline(Instant deadline) {
        this.deadline = deadline;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = new HashMap<>(data);
    }

    public long getAttempts() {
        return attempts;
    }

    public void setAttempts(long attempts) {
        this.attempts = attempts;
    }

    public EmailDeliveryStatus getStatus() {
        return status;
    }

    public void setStatus(EmailDeliveryStatus status) {
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public boolean isRetry() {
        return retry;
    }

    public void setRetry(boolean retry) {
        this.retry = retry;
    }

    public String getSendId() {
        return sendId;
    }

    public void setSendId(String sendId) {
        this.sendId = sendId;
    }

    @Override
    public String toString() {
        return "{\"EnvelopeEntity\":{"
                + "\"id\":" + id
                + ", \"version\":\"" + version + "\""
                + ", \"recipients\":" + recipients
                + ", \"emailTemplate\":\"" + emailTemplate + "\""
                + ", \"deadline\":" + deadline
                + ", \"data\":" + data
                + ", \"attempts\":\"" + attempts + "\""
                + ", \"status\":\"" + status + "\""
                + ", \"error\":\"" + error + "\""
                + ", \"retry\":\"" + retry + "\""
                + ", \"sendId\":\"" + sendId + "\""
                + "}}";
    }
}
