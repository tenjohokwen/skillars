package com.softropic.skillars.platform.security.contract;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for the one-time creation of the platform's first administrator.
 *
 * <p>Registered as a bean via {@code @EnableConfigurationProperties(AdminBootstrapProperties.class)}
 * in {@code SecurityConfiguration} — same pattern as {@link SecurityProperties}.
 *
 * <p><strong>Every field defaults to blank, and blank means disabled.</strong>
 * {@code AdminBootstrapRunner} no-ops unless {@code email} and {@code password} are both set, so
 * the mechanism is inert on every environment that does not deliberately opt in. Never give
 * {@code password} a default in {@code application.yaml}, {@code .env.example}, or a compose file:
 * a default admin password is a default admin account.
 */
@ConfigurationProperties(prefix = "app.bootstrap.admin")
@Data
public class AdminBootstrapProperties {

    /**
     * Login and email address of the admin to create. Normalized to lower case by the runner
     * before it is either looked up or stored — {@code AuthService} resolves logins as
     * {@code findOneByLogin(email.toLowerCase())}, so a row stored with any upper-case character
     * could never be logged into.
     */
    private String email = "";

    /**
     * Raw password, bcrypt-encoded by the runner before it touches the database. Never logged,
     * never returned, never stored in plain form. Remove this variable from the environment once
     * the first boot has created the account.
     *
     * <p><strong>{@code @ToString.Exclude} is load-bearing, not decoration.</strong> Lombok's
     * {@code @Data} generates a {@code toString()} over every field, and a
     * {@code @ConfigurationProperties} bean's {@code toString()} is not private: Spring Boot prints
     * it in binding-failure messages, and the {@code /actuator/configprops} endpoint reflects over
     * the same object. Without this the raw password reaches a log line or an HTTP response, which
     * is exactly what this class's own contract forbids.
     */
    @ToString.Exclude
    private String password = "";

    /**
     * Required when the bootstrap is enabled. {@code user.phone} carries a UNIQUE constraint and
     * {@code PhoneNumber.phone} is {@code @NotEmpty}, so there is no safe hardcoded placeholder —
     * one would collide the moment a second admin is bootstrapped on the same database.
     */
    private String phone = "";

    private String firstName = "Platform";

    private String lastName = "Administrator";
}
