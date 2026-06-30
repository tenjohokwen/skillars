package com.softropic.skillars.platform.admin.contract;

import java.util.UUID;

public record GdprExportStatusResponse(UUID requestId, String status) {}
