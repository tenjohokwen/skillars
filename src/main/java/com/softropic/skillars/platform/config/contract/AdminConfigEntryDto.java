package com.softropic.skillars.platform.config.contract;

import java.time.Instant;

/**
 * One row of the admin config editor (FR-ADM-006): a {@code platform_config} value plus the
 * validation metadata {@code ConfigService.updateConfig} enforces on write, so the UI can show the
 * allowed range before the admin submits instead of only after a 400.
 *
 * @param value       the stored value, or — for an unseeded {@code HAS_CODE_DEFAULT} key
 *                    ({@code stored = false}) — {@code null}; the code default then applies
 * @param valueType   {@code STRING} or {@code LONG}; {@code LONG} for an unseeded key (that is the
 *                    type {@code updateConfig} creates it with)
 * @param min         inclusive lower bound when the key is registered in {@code ConfigBounds}, else {@code null}
 * @param max         inclusive upper bound when the key is registered in {@code ConfigBounds}, else {@code null}
 * @param note        what an out-of-range value does, from {@code ConfigBounds}; {@code null} if unbounded
 * @param failFast    whether an out-of-range stored value blocks application startup
 * @param stored      whether a {@code platform_config} row exists for the key
 * @param codeDefault the value the code falls back to when the key is absent ({@code HAS_CODE_DEFAULT}
 *                    keys only), else {@code null}
 */
public record AdminConfigEntryDto(
    String key,
    String value,
    String valueType,
    String description,
    Instant updatedAt,
    Long min,
    Long max,
    String note,
    boolean failFast,
    boolean stored,
    Long codeDefault
) {}
