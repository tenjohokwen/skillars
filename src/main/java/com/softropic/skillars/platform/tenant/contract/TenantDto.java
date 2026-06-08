package com.softropic.skillars.platform.tenant.contract;

public record TenantDto(Long id, String tenantRef, String name, TenantStatus tenantStatus) {}
