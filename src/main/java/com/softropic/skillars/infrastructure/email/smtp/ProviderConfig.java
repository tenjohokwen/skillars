package com.softropic.skillars.infrastructure.email.smtp;

/**
 * Story ses-1.2 AC2: moved unchanged from {@code platform.notification.contract.ProviderConfig}.
 */
public class ProviderConfig {

    /**
     * skillars-deferred-99 AC5's own default: a provider with no explicit {@link #implicitTls} is
     * assumed implicit-TLS when its port is this one — the SMTPS convention.
     */
    private static final int IMPLICIT_TLS_PORT = 465;

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

    /**
     * skillars-deferred-111 AC10: extracted from what was {@code SmtpHealthIndicator}'s own private
     * {@code isImplicitTls(ProviderConfig, int)} helper, so the health probe and the actual send
     * path (previously drifted — {@link MailSenderProvider#toMailSender} never called it at all)
     * cannot silently diverge again. Defaults from {@code port == 465} when {@link #implicitTls} is
     * unset.
     */
    public boolean isImplicitTls(int port) {
        return implicitTls != null ? implicitTls : port == IMPLICIT_TLS_PORT;
    }
}
