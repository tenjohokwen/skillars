package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.platform.messaging.contract.ModerationResult;
import com.softropic.skillars.platform.messaging.contract.ModerationService;
import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("passThrough")
@RequiredArgsConstructor
@Slf4j
public class PassThroughModerationService implements ModerationService {

    private final ModerationResultApplier moderationResultApplier;

    @Override
    @Transactional
    public ModerationResult moderate(Long messageId, String content) {
        return moderationResultApplier.applyResult(messageId, ModerationVerdict.SAFE);
    }
}
