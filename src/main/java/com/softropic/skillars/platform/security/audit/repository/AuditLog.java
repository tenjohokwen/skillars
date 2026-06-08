package com.softropic.skillars.platform.security.audit.repository;



import com.softropic.skillars.infrastructure.persistence.AbstractAuditingEntity;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.validation.constraints.Size;


@Entity
public class AuditLog extends AbstractAuditingEntity {

    @Column(name = "event_timestamp", nullable = false, updatable = false)
    private Instant eventTimestamp; //UTC

    @Size(max = 250)
    @Column(name = "msg", nullable = false, length = 250, columnDefinition = "text") //updatable = false,
    private String msg;

    @Size(max = 50)
    @Column(name = "login", updatable = false, length = 50, columnDefinition = "text")
    private String login;

    @Column(name = "is_authenticated", nullable = false)
    private Boolean isAuthenticated;


    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", name = "relevant_properties", updatable = false)
    private Map<String, Object> relevantProperties;

    @Size(max = 400)
    @Column(name = "user_agent", updatable = false, length = 400, columnDefinition = "text")
    private String userAgent;

    @Size(max = 64)
    @Column(name = "ip_address",  updatable = false, length = 64, columnDefinition = "text")
    private String ipAddress;

    @Size(max = 50)
    @Column(name = "client_id", updatable = false, length = 50, columnDefinition = "text")
    private String clientId;

    @Size(max = 150)
    @Column(name = "browser_cookie", updatable = false, length = 150, columnDefinition = "text")
    private String browserCookie;

    @Size(max = 2048)
    @Column(name = "url", nullable = false, updatable = false, length = 2048, columnDefinition = "text")
    private String url;

    @Size(max = 50)
    @Column(name = "log_id", updatable = false, length = 50, columnDefinition = "text")
    private String logId;  //To the client/user this is the helpCode. To the app it is a unique id for a log file entry

    //private String sessionId; //see AbstractAuditingEntity

    public Instant getEventTimestamp() {
        return eventTimestamp;
    }

    public void setEventTimestamp(Instant eventTimestamp) {
        this.eventTimestamp = eventTimestamp;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public Boolean getAuthenticated() {
        return isAuthenticated;
    }

    public void setAuthenticated(Boolean authenticated) {
        isAuthenticated = authenticated;
    }

    public Map<String, Object> getRelevantProperties() {
        return relevantProperties;
    }

    public void setRelevantProperties(Map<String, Object> relevantProperties) {
        this.relevantProperties = relevantProperties;
    }

    public @Size(max = 400) String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(@Size(max = 400) String userAgent) {
        this.userAgent = userAgent;
    }

    public @Size(max = 64) String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(@Size(max = 64) String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public @Size(max = 50) String getClientId() {
        return clientId;
    }

    public void setClientId(@Size(max = 50) String clientId) {
        this.clientId = clientId;
    }

    public @Size(max = 150) String getBrowserCookie() {
        return browserCookie;
    }

    public void setBrowserCookie(@Size(max = 150) String browserCookie) {
        this.browserCookie = browserCookie;
    }

    public @Size(max = 2048) String getUrl() {
        return url;
    }

    public void setUrl(@Size(max = 2048) String url) {
        this.url = url;
    }

    public @Size(max = 50) String getLogId() {
        return logId;
    }

    public void setLogId(@Size(max = 50) String logId) {
        this.logId = logId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {return true;}

        if (!(o instanceof AuditLog auditLog)) {return false;}

        return new EqualsBuilder().appendSuper(super.equals(o))
                                  .append(logId, auditLog.logId)
                                  .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37).appendSuper(super.hashCode()).append(logId).toHashCode();
    }
}
