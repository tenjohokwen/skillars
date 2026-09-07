package com.softropic.skillars.platform.notification.contract;

public class ProviderConfig {
    private String name;
    private String host;
    private String port;
    private String username;
    private String password;
    /**
     * skillars-deferred-99 AC5: this provider speaks implicit TLS (SMTPS) — the server expects a TLS
     * handshake immediately, with no plaintext {@code 220} banner. Defaults from {@code port == 465}
     * when unset; set it explicitly for a non-standard implicit-TLS port.
     */
    private Boolean implicitTls;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean getImplicitTls() {
        return implicitTls;
    }

    public void setImplicitTls(Boolean implicitTls) {
        this.implicitTls = implicitTls;
    }
}
