package com.softropic.skillars.infrastructure.arachnid;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "infrastructure.arachnid")
public class ArachnidProperties {
    private String apiKey = "";
    private String apiBaseUrl = "https://api.arachnid.projectvic.org";
    private int timeoutSeconds = 30;
    /** S-18: how long an uploaded image the scanner could not judge waits before the sweeper tries it again. */
    private long retryDelayMs = 300_000L;
    /** S-18: cadence of the sweeper that retries images still awaiting a verdict. */
    private long sweepDelayMs = 60_000L;
    private int sweepBatchSize = 20;
    /** S-18: after this many attempts with no verdict, an admin is told the scanner is not working (once per image). */
    private int alertAfterAttempts = 5;
}
