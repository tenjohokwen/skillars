package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.platform.messaging.contract.MessageModerationStatus;
import com.softropic.skillars.platform.messaging.contract.ModerationResult;
import com.softropic.skillars.platform.messaging.contract.ModerationService;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Slf4j
public class PassThroughModerationService implements ModerationService {

    private final MessageRepository messageRepository;

    public PassThroughModerationService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Override
    @Transactional
    public ModerationResult moderate(Long messageId, String content) {
        Instant deliveredAt = Instant.now();
        messageRepository.findById(messageId).ifPresent(msg -> {
            msg.setModerationStatus(MessageModerationStatus.APPROVED);
            msg.setDeliveredAt(deliveredAt);
            messageRepository.save(msg);
        });
        return new ModerationResult(MessageModerationStatus.APPROVED, deliveredAt);
    }
}
