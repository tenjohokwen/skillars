package com.softropic.skillars.platform.filestorage.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ValidationRequest {
    private String originalFilename;
    private String contentType;
    private String extension;
    private long fileSizeBytes;
    private String checksum;
    private Map<String, String> tags;
}
