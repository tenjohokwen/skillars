package com.softropic.skillars.platform.video.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.video")
public class VideoProperties {

    private String provider = "bunny";
    private Upload upload = new Upload();
    private Playback playback = new Playback();
    private Reconciliation reconciliation = new Reconciliation();
    private Webhook webhook = new Webhook();
    private Bunny bunny = new Bunny();

    @Getter
    @Setter
    public static class Upload {
        private long maxBytes = 5368709120L;
        private List<String> allowedMimeTypes = List.of(
            "video/mp4", "video/quicktime", "video/webm", "video/x-msvideo"
        );
        private List<String> allowedFormats = List.of("MP4", "MOV", "WebM", "AVI");
        private int sessionTtlMinutes = 60;
        private long expirySchedulerDelayMs = 30000L;
        private RateLimit rateLimit = new RateLimit();

        @Getter
        @Setter
        public static class RateLimit {
            private int requestsPerMinute = 10;
        }
    }

    @Getter
    @Setter
    public static class Playback {
        private int tokenTtlMinutes = 15;
        private int tokenMaxTtlMinutes = 120;
        private int revocationWindowHours = 24;
        private String signingSecret = "";  // Must be Base64-encoded; decodes to ≥32 bytes for HS256
    }

    @Getter
    @Setter
    public static class Reconciliation {
        private long fixedDelayMs = 60000L;
        private int batchSize = 10;
    }

    @Getter
    @Setter
    public static class Webhook {
        private int maxAttempts = 3;
        private long processorDelayMs = 5000L;
    }

    @Getter
    @Setter
    public static class Bunny {
        private String apiKey = "${APP_VIDEO_BUNNY_API_KEY:}";
        private String libraryId = "${APP_VIDEO_BUNNY_LIBRARY_ID:}";
        private String cdnHostname = "${APP_VIDEO_BUNNY_CDN_HOSTNAME:}";
        private String apiBaseUrl = "https://video.bunnycdn.com";

        @Override
        public String toString() {
            return "Bunny{libraryId='" + libraryId + "', cdnHostname='" + cdnHostname + "', apiKey='[MASKED]'}";
        }
    }
}
