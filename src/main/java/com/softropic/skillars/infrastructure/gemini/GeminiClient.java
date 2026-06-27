package com.softropic.skillars.infrastructure.gemini;

import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;

public interface GeminiClient {
    ModerationVerdict evaluate(String content);
}
