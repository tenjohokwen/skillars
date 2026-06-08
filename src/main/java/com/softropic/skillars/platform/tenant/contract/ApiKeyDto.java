package com.softropic.skillars.platform.tenant.contract;

public record ApiKeyDto(
    Long id,
    String keyPrefix,
    ApiKeyEnvironment environment,
    String rawKey   // NON-NULL only on creation/rotation — never stored, shown once
) {}
