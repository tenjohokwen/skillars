package com.softropic.skillars.platform.security.repo;



import com.softropic.skillars.infrastructure.persistence.BaseEntity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Holds the OTP for 2FA
 * Info like client login history, which client a user typically uses
 * If a fraudster tries to do illegal things and his clientId is saved, this table could be used to find out who that was.
 * A successful login with the given clientId means it could be the same fraudster
 */
@Entity
public class LoginInfo extends BaseEntity {
    @NotNull
    @Column(name = "creation_date", nullable = false, updatable = false)
    private Instant creationDate;

    @Column(name = "verification_date")
    private Instant verificationDate;

    @Column(name = "termination_date")
    private Instant terminationDate;

    @Column(name = "expiration_date", nullable = false, updatable = false)
    private Instant expirationDate;

    @Size(min = 2, max = 850)
    @Column(name = "token", length = 850, nullable = false, updatable = false, columnDefinition = "text")
    private String token;

    @Size(min = 2, max = 20)
    @Column(name = "otp", length = 20, nullable = false, updatable = false, columnDefinition = "text")
    private String otp;

    @Size(min = 5, max = 50)
    @Column(length = 50, nullable = false, columnDefinition = "text")
    private String loginId;

    @Size(min = 5, max = 100)
    @Column(name = "client_id", length = 100, nullable = false, updatable = false, columnDefinition = "text")
    private String clientId;

    @Size(max = 39)
    @Column(name = "ip_address", length = 39, columnDefinition = "text")
    private String ipAddress; //this could be used to identify a fraudster. An ip address could be blocked if it causes security threats

    @Size(min = 20, max = 60)
    @Column(name = "request_id", length = 60, nullable = false, updatable = false, columnDefinition = "text")
    private String requestId;

    @Size(min = 62, max = 62)
    @Column(name = "sqid_seed", length = 62, nullable = false, updatable = false, columnDefinition = "text")
    private String sqidSeed;

    @Size(min = 3, max = 36)
    @Column(name = "send_id", length = 36, nullable = false, updatable = false, columnDefinition = "text")
    private String sendId;

    @Size(max = 50)
    @Column(name = "session_id", nullable = false, updatable = false, columnDefinition = "text")
    private String sessionId;

    public Instant getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(Instant creationDate) {
        this.creationDate = creationDate;
    }

    public Instant getVerificationDate() {
        return verificationDate;
    }

    public void setVerificationDate(Instant verificationDate) {
        this.verificationDate = verificationDate;
    }

    public Instant getTerminationDate() {
        return terminationDate;
    }

    public void setTerminationDate(Instant terminationDate) {
        this.terminationDate = terminationDate;
    }

    public Instant getExpirationDate() {
        return expirationDate;
    }

    public void setExpirationDate(Instant expirationDate) {
        this.expirationDate = expirationDate;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getOtp() {
        return otp;
    }

    public void setOtp(String otp) {
        this.otp = otp;
    }

    public String getLoginId() {
        return loginId;
    }

    public void setLoginId(String loginId) {
        this.loginId = loginId;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getSqidSeed() {
        return sqidSeed;
    }

    public void setSqidSeed(String sqidSeed) {
        this.sqidSeed = sqidSeed;
    }

    public String getSendId() {
        return sendId;
    }

    public void setSendId(String sendId) {
        this.sendId = sendId;
    }

    public @Size(max = 50) String getSessionId() {
        return sessionId;
    }

    public void setSessionId(@Size(max = 50) String sessionId) {
        this.sessionId = sessionId;
    }
}
