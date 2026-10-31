package com.softropic.skillars.platform.security.infrastructure.jwt;


import javax.crypto.SecretKey;

public class JwtSecretProvider {
    private static final ThreadLocal<SecretKey> contextHolder = new ThreadLocal<>(); // NOPMD

    private JwtSecretProvider() {}

    public static byte[] getKey() {
        final SecretKey key = contextHolder.get();
        if(key == null) {
            return new byte[0];
        }
        return key.getEncoded();
    }

    public static void setSecret(SecretKey key) {
        contextHolder.set(key);
    }

    public static void removeFromThread() {
        contextHolder.remove();
    }


}
