package com.softropic.skillars.platform.admin.config;

import com.softropic.skillars.platform.security.contract.util.Cryptopher;
import com.softropic.skillars.infrastructure.feature.FeatureProperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the platform module.
 *
 * <p>Registers {@link SkillarsPlatformProperties} as a configuration-properties bean
 * bound from the {@code skillars.platform} YAML prefix.
 *
 * <p>Also exposes a {@link Cryptopher} bean named {@code pinCryptopher} that encrypts
 * and decrypts provider PINs (PIN-03, PIN-05). The bean is constructed from
 * {@link SkillarsPlatformProperties#getPinEncryptionSecret()} which is bound from the
 * {@code PLATFORM_PIN_ENCRYPTION_SECRET} environment variable (Phase 41).
 *
 * <p><b>Fail-fast:</b> {@code Cryptopher}'s constructor throws
 * {@code EncryptionException(MISSING_SECRET)} when the secret is blank — the application
 * context will refuse to start. In test contexts the secret MUST be supplied via
 * {@code src/test/resources/application.properties} ({@code skillars.platform.pin-encryption-secret=...}).
 */
@Configuration
@EnableConfigurationProperties({SkillarsPlatformProperties.class, FeatureProperties.class})
public class PlatformConfig {

    /**
     * AES256 encryptor for provider PINs.
     *
     * @param props injected {@link SkillarsPlatformProperties} carrying
     *              {@code getPinEncryptionSecret()} from the YAML binding
     * @return a {@link Cryptopher} initialised with the configured PIN secret
     */
    @Bean
    public Cryptopher pinCryptopher(SkillarsPlatformProperties props) {
        return new Cryptopher(props.getPinEncryptionSecret());
    }
}
