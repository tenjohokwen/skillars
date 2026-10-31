package com.softropic.skillars.platform.filestorage.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "platform.filestorage")
public class FileStorageProperties {
    private Validation validation = new Validation();
    private Quota quota = new Quota();

    @Getter
    @Setter
    public static class Validation {
        private List<String> allowedMimeTypes = List.of(
            "image/jpeg", "image/png", "image/gif", "image/webp",
            "application/pdf", "text/plain"
        );
        private List<String> allowedExtensions = List.of(
            "jpg", "jpeg", "png", "gif", "webp", "pdf", "txt"
        );
        private long maxSizeBytes = 104857600L;
        /** Upload {@code entity} values {@code signUpload} accepts: the key's first segment is built from it. */
        private List<String> allowedEntities = List.of("coach_profile", "coach_branding", "coach_gallery");
    }

    @Getter
    @Setter
    public static class Quota {
        private long defaultBytes = 10737418240L;
    }
}
