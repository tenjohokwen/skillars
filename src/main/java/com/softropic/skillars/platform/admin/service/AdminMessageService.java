package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.admin.contract.AdminActionType;
import com.softropic.skillars.platform.admin.contract.AdminAlertStatus;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import com.softropic.skillars.platform.admin.contract.AdminMessageContextDto;
import com.softropic.skillars.platform.admin.contract.AdminMessageDetailDto;
import com.softropic.skillars.platform.admin.contract.AdminMessageReportDto;
import com.softropic.skillars.platform.admin.repo.AdminActionLog;
import com.softropic.skillars.platform.admin.repo.AdminActionLogRepository;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.messaging.contract.MessageModerationStatus;
import com.softropic.skillars.platform.messaging.contract.ReportStatus;
import com.softropic.skillars.platform.messaging.repo.ConversationRepository;
import com.softropic.skillars.platform.messaging.repo.Message;
import com.softropic.skillars.platform.messaging.repo.MessageReport;
import com.softropic.skillars.platform.messaging.repo.MessageReportRepository;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import com.softropic.skillars.platform.messaging.service.AgeMessagingPolicy;
import com.softropic.skillars.platform.messaging.service.MessagingEmitterRegistry;
import com.softropic.skillars.platform.security.service.AgePolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminMessageService {

    private final MessageRepository messageRepository;
    private final MessageReportRepository messageReportRepository;
    private final ConversationRepository conversationRepository;
    private final AdminAlertRepository adminAlertRepository;
    private final AdminActionLogRepository adminActionLogRepository;
    private final MessagingEmitterRegistry emitterRegistry;
    private final CoachProfileRepository coachProfileRepository;
    private final AgePolicyService agePolicyService;

    @Transactional(readOnly = true)
    public AdminMessageDetailDto getMessageDetail(Long messageId) {
        Message msg = messageRepository.findById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message not found", "message"));

        List<Message> beforeRaw = messageRepository.findBeforePivot(
            msg.getConversationId(), msg.getCreatedAt(), PageRequest.of(0, 5));
        List<Message> before = new ArrayList<>(beforeRaw);
        Collections.reverse(before);

        List<Message> after = messageRepository.findAfterPivot(
            msg.getConversationId(), msg.getCreatedAt(), PageRequest.of(0, 5));

        List<AdminMessageContextDto> contextBefore = before.stream()
            .map(m -> toContextDto(m, false))
            .toList();
        List<AdminMessageContextDto> contextAfter = after.stream()
            .map(m -> toContextDto(m, false))
            .toList();

        List<AdminMessageReportDto> reports = messageReportRepository.findByMessageId(messageId).stream()
            .map(r -> new AdminMessageReportDto(r.getReason().name(), r.getDetails(), r.getCreatedAt()))
            .toList();

        return new AdminMessageDetailDto(
            msg.getId(),
            msg.getConversationId(),
            msg.getSenderId(),
            msg.getSenderRole().name(),
            msg.getContent(),
            msg.getModerationStatus().name(),
            msg.getDeliveredAt(),
            msg.getCreatedAt(),
            contextBefore,
            contextAfter,
            reports);
    }

    @Transactional
    public void approveMessage(Long messageId, Long adminId) {
        Message message = messageRepository.findById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message not found", "message"));

        if (message.getModerationStatus() != MessageModerationStatus.UNDER_REVIEW) {
            return;
        }

        message.setModerationStatus(MessageModerationStatus.APPROVED);
        message.setDeliveredAt(Instant.now());
        messageRepository.save(message);

        messageReportRepository.resolveAllOpenByMessageId(messageId, Instant.now(), adminId, ReportStatus.RESOLVED);

        adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(
            String.valueOf(messageId), AdminAlertType.MESSAGE_REPORT, AdminAlertStatus.OPEN)
            .ifPresent(alert -> {
                alert.setStatus(AdminAlertStatus.RESOLVED);
                alert.setResolvedAt(Instant.now());
                alert.setResolvedBy(adminId);
                adminAlertRepository.save(alert);
            });

        AdminActionLog logEntry = new AdminActionLog();
        logEntry.setAdminId(adminId);
        logEntry.setActionType(AdminActionType.MESSAGE_APPROVE);
        logEntry.setReferenceId(String.valueOf(messageId));
        adminActionLogRepository.save(logEntry);

        final Long msgId = message.getId();
        final Long convId = message.getConversationId();
        final Long recipientId = resolveRecipient(convId, message.getSenderRole().name());
        if (recipientId != null) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emitterRegistry.emit(recipientId, "NEW_MESSAGE",
                        Map.of("type", "NEW_MESSAGE", "messageId", msgId, "conversationId", convId));
                }
            });
        }
    }

    @Transactional
    public void blockMessage(Long messageId, String reason, Long adminId) {
        Message message = messageRepository.findById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message not found", "message"));

        if (message.getModerationStatus() != MessageModerationStatus.UNDER_REVIEW) {
            return;
        }

        message.setModerationStatus(MessageModerationStatus.BLOCKED);
        messageRepository.save(message);

        messageReportRepository.resolveAllOpenByMessageId(messageId, Instant.now(), adminId, ReportStatus.RESOLVED);

        adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(
            String.valueOf(messageId), AdminAlertType.MESSAGE_REPORT, AdminAlertStatus.OPEN)
            .ifPresent(alert -> {
                alert.setStatus(AdminAlertStatus.RESOLVED);
                alert.setResolvedAt(Instant.now());
                alert.setResolvedBy(adminId);
                adminAlertRepository.save(alert);
            });

        AdminActionLog logEntry = new AdminActionLog();
        logEntry.setAdminId(adminId);
        logEntry.setActionType(AdminActionType.MESSAGE_BLOCK);
        logEntry.setReferenceId(String.valueOf(messageId));
        logEntry.setReason(reason);
        adminActionLogRepository.save(logEntry);

        final Long msgId = message.getId();
        final Long convId = message.getConversationId();
        final Long senderId = message.getSenderId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                emitterRegistry.emit(senderId, "MESSAGE_BLOCKED",
                    Map.of("type", "MESSAGE_BLOCKED", "messageId", msgId, "conversationId", convId));
            }
        });
    }

    private AdminMessageContextDto toContextDto(Message m, boolean adminConversationView) {
        String content;
        if (adminConversationView) {
            content = m.getDeletedAt() != null ? null : m.getContent();
        } else {
            content = m.getModerationStatus() == MessageModerationStatus.BLOCKED ? null : m.getContent();
        }
        return new AdminMessageContextDto(
            m.getId(),
            m.getSenderRole().name(),
            content,
            m.getModerationStatus().name(),
            m.getCreatedAt());
    }

    private Long resolveRecipient(Long conversationId, String senderRole) {
        var conv = conversationRepository.findById(conversationId).orElse(null);
        if (conv == null) return null;
        if ("COACH".equals(senderRole)) {
            AgeMessagingPolicy agePolicy = AgeMessagingPolicy.from(
                agePolicyService.getMessagingPolicy(conv.getPlayerId()));
            return agePolicy.parentIsBlocked() ? conv.getPlayerId() : conv.getParentId();
        } else {
            return coachProfileRepository.findById(conv.getCoachId())
                .map(CoachProfile::getUserId)
                .orElse(null);
        }
    }
}
