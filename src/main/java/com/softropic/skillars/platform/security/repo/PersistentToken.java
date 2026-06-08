package com.softropic.skillars.platform.security.repo;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.softropic.skillars.infrastructure.persistence.AbstractAuditingEntity;

import org.hibernate.envers.Audited;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Used to find out how many sessions a user has at a given time. It can be used to put constraints on user.
 *
 */
@Audited
@Entity
@Table(name = "persistent_token")
public class PersistentToken extends AbstractAuditingEntity {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("d MMMM yyyy");

    private static final int MAX_USER_AGENT_LEN = 255;

    @Column(unique = true, nullable = false)
    private UUID version;

    @NotNull
    @Column(name = "token_value", nullable = false)
    private String tokenValue;

    @Column(name = "token_date")
    private LocalDate tokenDate;

    @Column(name = "expiry_time")
    private LocalTime expiryTime;

    //an IPV6 address max length is 39 characters
    @Size(max = 39)
    @Column(name = "ip_address", length = 39)
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "is_blacklisted")
    boolean isBlacklisted;

    @JsonBackReference
    @ManyToOne(fetch = FetchType.LAZY)
    private User user;

    public UUID getVersion() {
        return version;
    }

    public void setVersion(final UUID version) {
        this.version = version;
    }

    public String getTokenValue() {
        return tokenValue;
    }

    public void setTokenValue(final String tokenValue) {
        this.tokenValue = tokenValue;
    }

    public LocalDate getTokenDate() {
        return tokenDate;
    }

    public void setTokenDate(final LocalDate tokenDate) {
        this.tokenDate = tokenDate;
    }

    public String getFormattedTokenDate() {
        return DATE_TIME_FORMATTER.format(this.tokenDate);
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(final String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(final String userAgent) {
        if (userAgent.length() >= MAX_USER_AGENT_LEN) {
            this.userAgent = userAgent.substring(0, MAX_USER_AGENT_LEN - 1);
        } else {
            this.userAgent = userAgent;
        }
    }

    public User getUser() {
        return user;
    }

    public void setUser(final User user) {
        this.user = user;
    }

    public LocalTime getExpiryTime() {
        return expiryTime;
    }

    public void setExpiryTime(final LocalTime expiryTime) {
        this.expiryTime = expiryTime;
    }

    public boolean isBlacklisted() {
        return isBlacklisted;
    }

    public void setBlacklisted(final boolean blacklisted) {
        isBlacklisted = blacklisted;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        final PersistentToken that = (PersistentToken) obj;

        return tokenValue.equals(that.tokenValue);
    }

    @Override
    public int hashCode() {
        return tokenValue.hashCode();
    }

    @Override
    public String toString() {
        return "{\"PersistentToken\":"
                + super.toString()
                + ", \"version\":" + version
                + ", \"tokenValue\":\"" + tokenValue + "\""
                + ", \"tokenDate\":" + tokenDate
                + ", \"expiryTime\":" + expiryTime
                + ", \"ipAddress\":\"" + ipAddress + "\""
                + ", \"userAgent\":\"" + userAgent + "\""
                + ", \"isBlacklisted\":\"" + isBlacklisted + "\""
                + ", \"user\":" + user
                + "}";
    }
}
