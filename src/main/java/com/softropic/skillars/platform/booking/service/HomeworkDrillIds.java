package com.softropic.skillars.platform.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

/**
 * skillars-deferred-159 AC1: the single reader of {@code session_completion_data.homework_drill_ids},
 * shared by the parent-confirm path ({@link BookingCompletionService#confirmCompletion}) and the
 * Quick Complete timeout ({@link QuickCompleteTimeoutService}) so the two cannot drift again.
 */
@Slf4j
final class HomeworkDrillIds {

    private HomeworkDrillIds() {
    }

    /** Null/blank → empty, silently (the homework-less case). Unparseable → empty + WARN. */
    @SuppressWarnings("unchecked")
    static List<UUID> parse(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> ids = objectMapper.readValue(json, List.class);
            return ids.stream().map(UUID::fromString).toList();
        } catch (Exception e) {
            log.warn("Failed to deserialize homeworkDrillIds from JSON '{}': {}", json, e.getMessage());
            return List.of();
        }
    }
}
