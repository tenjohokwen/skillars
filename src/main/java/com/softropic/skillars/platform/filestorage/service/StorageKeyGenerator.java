package com.softropic.skillars.platform.filestorage.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class StorageKeyGenerator {

    public String generate(String entity, String entityId, String extension) {
        String sanitizedEntity = entity.replaceAll("[^a-zA-Z0-9_-]", "_");
        String sanitizedEntityId = entityId.replaceAll("[^a-zA-Z0-9_-]", "_");
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        String uuid = UUID.randomUUID().toString();
        return String.format("%s/%s/%04d/%02d/%s.%s",
            sanitizedEntity, sanitizedEntityId, now.getYear(), now.getMonthValue(), uuid, extension);
    }
}
