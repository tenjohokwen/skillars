package com.softropic.skillars.platform.security.audit.api;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Transient;
import jakarta.validation.constraints.Size;


public class AuditTrail {
    private Instant eventTimestamp; //UTC

    @Size(max = 250)
    private String msg;

    @Size(max = 50)
    private String login;

    private Boolean isAuthenticated;

    private Map<String, Object> relevantProperties;

    @Size(max = 600)
    private String userAgent;

    @Size(max = 64)
    private String ipAddress;

    @Size(max = 50)
    private String clientId;

    @Size(max = 150)
    private String browserCookie;

    @Size(max = 2048)
    private String url;

    @Size(max = 50)
    private String logId;  //To the client/user this is the helpCode. To the app it is a unique id for a log file entry

    //This value is captured automatically (in MDC, for logging and RequestIdListener when writing to the db )
    @Transient
    private String requestId;

    private String sessionId;


    public Instant getEventTimestamp() {
        return eventTimestamp;
    }

    public void setEventTimestamp(Instant eventTimestamp) {
        this.eventTimestamp = eventTimestamp;
    }

    public @Size(max = 250) String getMsg() {
        return msg;
    }

    public void setMsg(@Size(max = 250) String msg) {
        this.msg = msg;
    }

    public @Size(max = 50) String getLogin() {
        return login;
    }

    public void setLogin(@Size(max = 50) String login) {
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

    public @Size(max = 600) String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(@Size(max = 600) String userAgent) {
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

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public String toString() {
        return "{\"AuditTrail\":{"
                + "\"eventTimestamp\":" + eventTimestamp
                + ", \"msg\":\"" + msg + "\""
                + ", \"login\":\"" + login + "\""
                + ", \"isAuthenticated\":\"" + isAuthenticated + "\""
                + ", \"relevantProperties\":" + relevantProperties
                + ", \"userAgent\":\"" + userAgent + "\""
                + ", \"ipAddress\":\"" + ipAddress + "\""
                + ", \"clientId\":\"" + clientId + "\""
                + ", \"browserCookie\":\"" + browserCookie + "\""
                + ", \"url\":\"" + url + "\""
                + ", \"logId\":\"" + logId + "\""
                + ", \"requestId\":\"" + requestId + "\""
                + ", \"sessionId\":\"" + sessionId + "\""
                + "}}";
    }
}
