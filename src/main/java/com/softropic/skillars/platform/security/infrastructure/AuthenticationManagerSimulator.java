package com.softropic.skillars.platform.security.infrastructure;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * This class is meant to simulate authentication thereby mitigating time attacks.
 */
public class AuthenticationManagerSimulator implements AuthenticationManager {

    private final PasswordEncoder passwordEncoder;
    private static final String SIMULATOR_PASSWORD = "simpassword";

    public AuthenticationManagerSimulator(final PasswordEncoder passwordEncoder) {
        super();
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public Authentication authenticate(final Authentication authentication) {
        final String encodedSimPass = passwordEncoder.encode(SIMULATOR_PASSWORD);
        passwordEncoder.matches("randomPassword", encodedSimPass);
        return null;
    }
}
