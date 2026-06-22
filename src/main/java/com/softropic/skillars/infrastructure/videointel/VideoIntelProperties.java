package com.softropic.skillars.infrastructure.videointel;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "infrastructure.videointel")
public class VideoIntelProperties {
    private String projectId = "";
    private String credentialsPath = "";    // path to GCP service account JSON
    private double flagThreshold = 0.7;     // LIKELY or VERY_LIKELY maps to confidence > 0.7
    private int timeoutSeconds = 300;       // VideoIntel can take minutes for long videos
}
