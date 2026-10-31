package com.softropic.skillars.platform.security.infrastructure.jwt;


import org.springframework.stereotype.Service;


/**
 * Puts the JWT signing key in the thread local for the duration of a request.
 * The key comes from configuration ({@link JwtConfiguration}), already in memory, so no database
 * access happens here or per request.
 */
@Service
public class JwtSecretService {
    private final JwtConfiguration jwtConfiguration;

    public JwtSecretService(JwtConfiguration jwtConfiguration) {this.jwtConfiguration = jwtConfiguration;}


    public void addSecretToThread() {
        JwtSecretProvider.setSecret(jwtConfiguration.getSecretKey());
    }

    public void removeSecretFromThread() {
        JwtSecretProvider.removeFromThread();
    }

}
