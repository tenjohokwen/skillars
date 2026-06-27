package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.messaging.contract.MessageModerationStatus;
import com.softropic.skillars.platform.messaging.contract.ModerationResult;
import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;
import com.softropic.skillars.platform.messaging.repo.ConversationRepository;
import com.softropic.skillars.platform.messaging.repo.Message;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import com.softropic.skillars.platform.security.service.AgePolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ModerationResultApplier {

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final AgePolicyService agePolicyService;
    private final MessagingEmitterRegistry emitterRegistry;
    private final ApplicationEventPublisher publisher;

    @Transactional
    public ModerationResult applyResult(Long messageId, ModerationVerdict verdict) {
        Message message = messageRepository.findById(messageId).orElse(null);
        if (message == null) {
            log.warn("ModerationResultApplier: message not found: messageId={}", messageId);
            return new ModerationResult(MessageModerationStatus.PENDING, null);
        }

        MessageModerationStatus status;
        Instant deliveredAt;

        switch (verdict) {
            case SAFE -> {
                status = MessageModerationStatus.APPROVED;
                deliveredAt = Instant.now();
            }
            case UNSAFE -> {
                status = MessageModerationStatus.BLOCKED;
                deliveredAt = null;
            }
            default -> {
                status = MessageModerationStatus.UNDER_REVIEW;
                deliveredAt = null;
            }
        }

        message.setModerationStatus(status);
        message.setDeliveredAt(deliveredAt);
        messageRepository.save(message);

        final Long msgId = message.getId();
        final Long convId = message.getConversationId();
        final Long senderId = message.getSenderId();
        final String senderRoleName = message.getSenderRole().name();

        if (verdict == ModerationVerdict.SAFE) {
            Long recipientId = resolveRecipient(convId, senderRoleName);
            if (recipientId != null) {
                final Long finalRecipientId = recipientId;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        emitterRegistry.emit(finalRecipientId, "NEW_MESSAGE",
                            Map.of("type", "NEW_MESSAGE", "messageId", msgId, "conversationId", convId));
                    }
                });
            }
        } else if (verdict == ModerationVerdict.UNSAFE) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emitterRegistry.emit(senderId, "MESSAGE_BLOCKED",
                        Map.of("type", "MESSAGE_BLOCKED", "messageId", msgId, "conversationId", convId));
                }
            });
        }

        return new ModerationResult(status, deliveredAt);
    }

    private Long resolveRecipient(Long conversationId, String senderRole) {
        var conv = conversationRepository.findById(conversationId).orElse(null);
        if (conv == null) return null;

        if ("COACH".equals(senderRole)) {
            AgeMessagingPolicy agePolicy = AgeMessagingPolicy.from(
                agePolicyService.getMessagingPolicy(conv.getPlayerId()));
            if (agePolicy.parentIsBlocked()) {
                return conv.getPlayerId();
            }
            return conv.getParentId();
        } else {
            return coachProfileRepository.findById(conv.getCoachId())
                .map(CoachProfile::getUserId)
                .orElse(null);
        }
    }
}
