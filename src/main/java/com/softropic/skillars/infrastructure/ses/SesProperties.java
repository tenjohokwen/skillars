package com.softropic.skillars.infrastructure.ses;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.ses.*}. Registered unconditionally (via {@code SesConfig}'s
 * {@code @EnableConfigurationProperties}), which is exactly why its semantic validation lives in
 * the separate, conditionally-created {@link SesPropertiesValidator} rather than here — see that
 * class's javadoc.
 *
 * <p>{@code enabled} is gone: transport selection is now purely {@code app.email.transport}
 * (story ses-1.1), not a boolean flag on this class.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.ses")
public class SesProperties {

    private String fromAddress;
    private String region = "eu-west-1";
    private String accessKey;
    private String secretKey;
    private String configurationSet;
    private String endpointUrl;
    private int maxSendRatePerSecond = 10;
    private String replyToAddress;
}
