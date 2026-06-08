package com.softropic.skillars.infrastructure.client;

/**
 * Configuration for the Http Client, accessing services.
 */
public class TcpConfiguration {

    private int readTimeout = 15000;
    private int connectionTimeout = 15000;
    private int connectionRequestTimeout = 2000;
    private int maxConnections = 50;
    private int maxConnectionsPerRoute = 25;
    private boolean checkCertificate = true;

    public int getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public int getConnectionRequestTimeout() {
        return connectionRequestTimeout;
    }

    public void setConnectionRequestTimeout(int connectionRequestTimeout) {
        this.connectionRequestTimeout = connectionRequestTimeout;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getMaxConnectionsPerRoute() {
        return maxConnectionsPerRoute;
    }

    public void setMaxConnectionsPerRoute(int maxConnectionsPerRoute) {
        this.maxConnectionsPerRoute = maxConnectionsPerRoute;
    }

    public boolean isCheckCertificate() {
        return checkCertificate;
    }

    public void setCheckCertificate(boolean checkCertificate) {
        this.checkCertificate = checkCertificate;
    }
}
