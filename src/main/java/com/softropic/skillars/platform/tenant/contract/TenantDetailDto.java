package com.softropic.skillars.platform.tenant.contract;

import java.util.List;

public record TenantDetailDto(
    Long id,
    String tenantRef,
    String name,
    String email,
    String webhookUrl,
    TenantStatus tenantStatus,
    List<ApiKeySummaryDto> keys
) {}
