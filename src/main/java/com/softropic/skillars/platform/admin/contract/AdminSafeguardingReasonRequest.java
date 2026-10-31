package com.softropic.skillars.platform.admin.contract;

import jakarta.validation.constraints.Size;

/** Optional free-text note for a reject / revoke, written to the admin audit log only (never shown to the coach). */
public record AdminSafeguardingReasonRequest(@Size(max = 300) String reason) {
}
