package com.softropic.skillars.infrastructure.blobstore.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.storage")
public class BlobstoreProperties {

    @NotBlank
    private String provider = "s3";

    @NotBlank
    private String bucket;

    @NotBlank
    private String endpointUrl;

    @Min(1)
    private int presignTtlSeconds = 300;

    @Min(1)
    private int uploadTtlSeconds = 600;
    private Replication replication = new Replication();
    private Poller poller = new Poller();
    private Retry retry = new Retry();
    private Deletion deletion = new Deletion();
    private S3 s3 = new S3();
    private Local local = new Local();

    @Getter
    @Setter
    public static class Replication {
        private boolean enabled = false;
        private int maxAttempts = 5;
        private long backoffInitialMs = 1000;
        private double backoffMultiplier = 2.0;
        private Backup backup = new Backup();

        @Getter
        @Setter
        public static class Backup {
            private String endpointUrl;
            private String bucket;
            private String region = "us-east-1";
            private String accessKey;
            private String secretKey;
            private boolean pathStyleAccess = false;
        }
    }

    @Getter
    @Setter
    public static class Poller {
        private long fixedDelayMs = 5000;
        private int batchSize = 10;
    }

    @Getter
    @Setter
    public static class Retry {
        private int maxAttempts = 3;
        private long backoffInitialMs = 1000;
        private double backoffMultiplier = 2.0;
    }

    @Getter
    @Setter
    public static class Deletion {
        private int retentionDays = 30;
    }

    @Getter
    @Setter
    public static class Local {
        private String baseDir = "/tmp/skillars-storage";
    }

    @Getter
    @Setter
    public static class S3 {
        private long requestTimeoutMs = 5000;
        private long connectionTimeoutMs = 3000;
        private String region = "us-east-1";
        private boolean pathStyleAccess = false;
        private String accessKey = "";
        private String secretKey = "";
    }

    @Getter
    @Setter
    public static class Executor {
        @Min(1)
        private int poolSize = 2 * Runtime.getRuntime().availableProcessors();
        @Min(1)
        private int queueCapacity = 100;
    }

    private Executor executor = new Executor();
}
