package com.softropic.skillars.platform.marketplace.repo;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CoachMediaItemTest {

    @Test
    void onCreate_setsUploadedAt_whenNull() {
        CoachMediaItem item = new CoachMediaItem();
        item.onCreate();
        assertThat(item.getUploadedAt()).isNotNull();
    }

    @Test
    void onCreate_preservesUploadedAt_whenAlreadySet() {
        OffsetDateTime fixed = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        CoachMediaItem item = new CoachMediaItem();
        item.setUploadedAt(fixed);
        item.onCreate();
        assertThat(item.getUploadedAt()).isEqualTo(fixed);
    }
}
