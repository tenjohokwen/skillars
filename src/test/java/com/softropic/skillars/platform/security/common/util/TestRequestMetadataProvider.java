package com.softropic.skillars.infrastructure.security;

public class TestRequestMetadataProvider {

    private TestRequestMetadataProvider() {}

    public static RequestMetadata getClientInfo() {
        return RequestMetadataProvider.getClientInfo();
    }

    public static String getIpAddress() {
        return RequestMetadataProvider.getClientInfo().getIpAddress();
    }

    public static RequestMetadata setIpAddress(final String ipAddress) {
        final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
        clientInfo.setIpAddress(ipAddress);
        return clientInfo;
    }

    public static String getUserName() {
        return RequestMetadataProvider.getClientInfo().getUserName();
    }

    public static RequestMetadata setUserName(final String userName) {
        final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
        clientInfo.setUserName(userName);
        return clientInfo;
    }

    public static String getUserAgent() {
        return RequestMetadataProvider.getClientInfo().getUserAgent();
    }

    public static RequestMetadata setUserAgent(final String userAgent) {
        final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
        clientInfo.setUserAgent(userAgent);
        return clientInfo;
    }

    public static String getBrowserCookie() {
        return RequestMetadataProvider.getClientInfo().getBrowserCookie();
    }

    public static RequestMetadata setBrowserCookie(final String browserCookie) {
        final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
        clientInfo.setBrowserCookie(browserCookie);
        return clientInfo;
    }

    public static String getFingerprintCookie() {
        return RequestMetadataProvider.getClientInfo().getFingerprintCookie();
    }

    public static RequestMetadata setFingerprintCookie(final String fingerprintCookie) {
        final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
        clientInfo.setFingerprintCookie(fingerprintCookie);
        return clientInfo;
    }

    public static String getHcookie() {
        return RequestMetadataProvider.getClientInfo().getHcookie();
    }

    public static RequestMetadata setHcookie(final String hcookie) {
        final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
        clientInfo.setHcookie(hcookie);
        return clientInfo;
    }

    public static String getRequestId() {
        return RequestMetadataProvider.getClientInfo().getRequestId();
    }

    public static RequestMetadata setRequestId(final String requestId) {
        final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
        clientInfo.setRequestId(requestId);
        return clientInfo;
    }

    public static String getAcceptLanguage() {
        return RequestMetadataProvider.getClientInfo().getAcceptLanguage();
    }

    public static RequestMetadata setAcceptLanguage(final String acceptLanguage) {
        final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
        clientInfo.setAcceptLanguage(acceptLanguage);
        return clientInfo;
    }

    public static String getChosenLang() {
        return RequestMetadataProvider.getClientInfo().getChosenLang();
    }

    public static RequestMetadata setChosenLang(final String chosenLang) {
        final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
        clientInfo.setChosenLang(chosenLang);
        return clientInfo;
    }

    public static String getApiKey() {
        return RequestMetadataProvider.getClientInfo().getApiKey();
    }

    public static RequestMetadata setApiKey(final String apiKey) {
        final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
        clientInfo.setApiKey(apiKey);
        return clientInfo;
    }

    public static String getClientIdentifier() {
        return RequestMetadataProvider.getClientInfo().getClientIdentifier();
    }

    public static boolean isMachineClient() {
        return RequestMetadataProvider.getClientInfo().isMachineClient();
    }

    public static String getReqUrl() {
        return RequestMetadataProvider.getClientInfo().getReqUrl();
    }

    public static RequestMetadata setReqUrl(final String reqUrl) {
        final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
        clientInfo.setReqUrl(reqUrl);
        return clientInfo;
    }

    public static String getSessionId() {
        return RequestMetadataProvider.getClientInfo().getSessionId();
    }

    public static RequestMetadata setSessionId(final String sessionId) {
        final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
        clientInfo.setSessionId(sessionId);
        return clientInfo;
    }

    public static String getObjId() {
        return RequestMetadataProvider.getClientInfo().getObjId();
    }

}
