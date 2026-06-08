package com.softropic.skillars.infrastructure.security;

import org.apache.commons.lang3.StringUtils;

import java.util.UUID;

/**
 * Used to identifier a client.
 *
 */
public class RequestMetadata {

    private String ipAddress;

    private String userName;

    //"X-Remote-User-Agent")
    private String userAgent;

    //If api key is missing, then it should have an fingerprintCookie (at login time) OR bCookie (after login) and vice versa
    //This is used to fingerprint browsers. See http://clientjs.org/ https://github.com/jackspirou/clientjs (use this as opposed to fingerprintjs that is going commercial
    private String browserCookie;


    // this is the client side generated browser fingerprint cookie. At successful login, its value is copied to the newly created browserCookie
    // The browserCookie cannot be edited by js
    private String fingerprintCookie;

    //honey pot
    private String hcookie;

    //"X-Request-Id")
    private String requestId;

    //"Accept-Language")
    private String acceptLanguage;

    //Language set in the UI by user
    private String chosenLang = "English";

    //apiKey
    private String apiKey;

    private String reqUrl;

    private String sessionId;

    private final String objId;

    private String method;

    private boolean https;

    public RequestMetadata() {
        this.objId = UUID.randomUUID().toString();
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserName() {
        return userName;
    }

    void setIpAddress(final String ipAddress) {
        this.ipAddress = ipAddress;
    }

    void setUserName(final String userName) {
        this.userName = userName;
    }

    public String getUserAgent() {
        return userAgent;
    }

    void setUserAgent(final String userAgent) {
        this.userAgent = userAgent;
    }

    public String getBrowserCookie() {
        return browserCookie;
    }

    void setBrowserCookie(final String browserCookie) {
        this.browserCookie = browserCookie;
    }

    public String getFingerprintCookie() {
        return fingerprintCookie;
    }

    void setFingerprintCookie(final String fingerprintCookie) {
        this.fingerprintCookie = fingerprintCookie;
    }

    public String getHcookie() {
        return hcookie;
    }

    void setHcookie(final String hcookie) {
        this.hcookie = hcookie;
    }

    public String getRequestId() {
        return requestId;
    }

    void setRequestId(final String requestId) {
        this.requestId = requestId;
    }

    public String getAcceptLanguage() {
        return acceptLanguage;
    }

    void setAcceptLanguage(final String acceptLanguage) {
        this.acceptLanguage = acceptLanguage;
    }

    public String getChosenLang() {
        return chosenLang;
    }

    void setChosenLang(final String chosenLang) {
        this.chosenLang = chosenLang;
    }

    public String getApiKey() {
        return apiKey;
    }

    void setApiKey(final String apiKey) {
        this.apiKey = apiKey;
    }

    public String getClientIdentifier() {
        return StringUtils.isNotBlank(apiKey) ? apiKey : browserClientCookie();
    }

    private String browserClientCookie() {
        return StringUtils.isNotBlank(browserCookie) ? browserCookie: fingerprintCookie;
    }

    public boolean isMachineClient() {
        return StringUtils.isNotBlank(apiKey);
    }

    public String getReqUrl() {
        return reqUrl;
    }

    void setReqUrl(final String reqUrl) {
        this.reqUrl = reqUrl;
    }

    public String getSessionId() {
        return sessionId;
    }

    void setSessionId(final String sessionId) {
        this.sessionId = sessionId;
    }

    public String getObjId() {
        return objId;
    }

    public String getMethod() {
        return method;
    }

    void setMethod(String method) {
        this.method = method;
    }

    public boolean isHttps() {
        return https;
    }

    public void setHttps(boolean https) {
        this.https = https;
    }

    @Override
    public String toString() {
        return "{" +
                " ipAddress : " + ipAddress +
                ", userName: " + userName +
                ", userAgent: " + userAgent +
                ", browserCookie: " + browserCookie +
                ", fingerprintCookie: " + fingerprintCookie +
                ", hcookie: " + hcookie +
                ", requestId: " + requestId +
                ", acceptLanguage: " + acceptLanguage +
                ", chosenLang: " + chosenLang +
                ", apiKey: " + apiKey +
                ", reqUrl: " + reqUrl +
                ", sessionId: " + sessionId +
                ", objId: " + objId +
                ", method: " + method +
                ", https: " + https +
                " }";
    }
}
