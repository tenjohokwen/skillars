package com.softropic.skillars.platform.admin.contract;

import java.util.List;

public record AdminConversationDetailDto(
    Long conversationId,
    String status,
    List<AdminMessageContextDto> messages,
    List<AdminConversationReportDto> reports) {}
