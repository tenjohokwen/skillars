package com.softropic.skillars.platform.filestorage.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Service
public class StorageKeyGenerator {

    // Key format: {entity}/{entityId}/{year}/{month}/{uuid}.{extension}
    public static final int KEY_ENTITY_INDEX = 0;
    public static final int KEY_ENTITY_ID_INDEX = 1;
    public static final int KEY_MIN_SEGMENTS = 2;

    public record StorageKeyParts(String entity, String entityId) {}

    public String generate(String entity, String entityId, String extension) {
        String sanitizedEntity = entity.replaceAll("[^a-zA-Z0-9_-]", "_");
        String sanitizedEntityId = entityId.replaceAll("[^a-zA-Z0-9_-]", "_");
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        String uuid = UUID.randomUUID().toString();
        return String.format("%s/%s/%04d/%02d/%s.%s",
            sanitizedEntity, sanitizedEntityId, now.getYear(), now.getMonthValue(), uuid, extension);
    }

    public Optional<StorageKeyParts> parse(String key) {
        if (key == null) return Optional.empty();
        String[] parts = key.split("/");
        if (parts.length < KEY_MIN_SEGMENTS) return Optional.empty();
        return Optional.of(new StorageKeyParts(parts[KEY_ENTITY_INDEX], parts[KEY_ENTITY_ID_INDEX]));
    }
}
