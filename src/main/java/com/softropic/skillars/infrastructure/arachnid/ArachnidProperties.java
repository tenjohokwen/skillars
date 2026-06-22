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
}
