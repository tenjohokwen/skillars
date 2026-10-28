package com.softropic.skillars.platform.filestorage.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class StorageKeyGenerator {

    // Key format: {entity}/{entityId}/{year}/{month}/{uuid}.{extension}
    public static final int KEY_ENTITY_INDEX = 0;
    public static final int KEY_ENTITY_ID_INDEX = 1;
    public static final int KEY_MIN_SEGMENTS = 2;

    /**
     * The exact shape {@link #generate} produces. {@code confirmUpload} only accepts keys of this shape, so a
     * client cannot "confirm" (and so claim ownership of) a server-written object such as
     * {@code reports/<uuid>/report.pdf} or {@code gdpr/exports/<id>.zip}, which {@code storeBytes} writes with
     * no {@code file_storage_objects} row.
     */
    private static final Pattern GENERATED_KEY = Pattern.compile(
        "[a-zA-Z0-9_-]+/[a-zA-Z0-9_-]+/\\d{4}/\\d{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-zA-Z0-9]+");

    public record StorageKeyParts(String entity, String entityId) {}

    public static String sanitizeSegment(String segment) {
        return segment.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    public boolean isGeneratedKey(String key) {
        return key != null && GENERATED_KEY.matcher(key).matches();
    }

    public String generate(String entity, String entityId, String extension) {
        String sanitizedEntity = sanitizeSegment(entity);
        String sanitizedEntityId = sanitizeSegment(entityId);
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
