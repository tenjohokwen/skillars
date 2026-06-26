package com.softropic.skillars.platform.messaging.contract;

public interface ModerationService {
    ModerationResult moderate(Long messageId, String content);
}
