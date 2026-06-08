package com.softropic.skillars.platform.filestorage.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class StorageKeyGeneratorTest {

    private final StorageKeyGenerator generator = new StorageKeyGenerator();

    @Test
    void generate_returnsKeyInExpectedFormat() {
        String key = generator.generate("documents", "user-42", "pdf");

        LocalDate now = LocalDate.now();
        String expectedPrefix = String.format("documents/user-42/%04d/%02d/", now.getYear(), now.getMonthValue());

        assertThat(key).startsWith(expectedPrefix);
        assertThat(key).endsWith(".pdf");
    }

    @Test
    void generate_keyContainsUuidSegment() {
        String key = generator.generate("images", "tenant-1", "png");
        String[] parts = key.split("/");
        assertThat(parts).hasSize(5);
        String uuidWithExt = parts[4];
        String uuid = uuidWithExt.substring(0, uuidWithExt.lastIndexOf('.'));
        assertThat(uuid).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void generate_twoCallsWithSameArgsDifferentKeys() {
        String key1 = generator.generate("docs", "entity-1", "txt");
        String key2 = generator.generate("docs", "entity-1", "txt");
        assertThat(key1).isNotEqualTo(key2);
    }
}
