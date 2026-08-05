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
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
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
    private final PlayerProfileRepository playerProfileRepository;
    private final AgePolicyService agePolicyService;

    @Transactional(readOnly = true)
    public AdminMessageDetailDto getMessageDetail(Long messageId) {
        Message msg = messageRepository.findById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message not found", "message"));

        List<Message> beforeRaw = messageRepository.findBeforePivot(
            msg.getConversationId(), msg.getCreatedAt(), msg.getId(), PageRequest.of(0, 5));
        List<Message> before = new ArrayList<>(beforeRaw);
        Collections.reverse(before);

        List<Message> after = messageRepository.findAfterPivot(
            msg.getConversationId(), msg.getCreatedAt(), msg.getId(), PageRequest.of(0, 5));

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

    // findByIdForUpdate, not findById: approve and block are the other two writers of this row
    // (alongside ModerationResultApplier and MessageModerationSweeper, both of which take the
    // lock). Unlocked, two admins acting concurrently on one UNDER_REVIEW message both pass the
    // status guard below and the last commit wins — leaving admin_action_log with contradicting
    // MESSAGE_APPROVE and MESSAGE_BLOCK rows and an SSE already sent for the losing decision.
    @Transactional
    public void approveMessage(Long messageId, Long adminId) {
        Message message = messageRepository.findByIdForUpdate(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message not found", "message"));

        if (message.getModerationStatus() != MessageModerationStatus.UNDER_REVIEW) {
            return;
        }

        message.setModerationStatus(MessageModerationStatus.APPROVED);
        message.setDeliveredAt(Instant.now());
        messageRepository.save(message);

        messageReportRepository.resolveAllOpenByMessageId(messageId, Instant.now(), adminId, ReportStatus.RESOLVED);

        resolveOpenAlert(messageId, AdminAlertType.MESSAGE_REPORT, adminId);
        resolveOpenAlert(messageId, AdminAlertType.MODERATION_UNRESOLVED, adminId);

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

    // findByIdForUpdate — see approveMessage for why.
    @Transactional
    public void blockMessage(Long messageId, String reason, Long adminId) {
        Message message = messageRepository.findByIdForUpdate(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message not found", "message"));

        if (message.getModerationStatus() != MessageModerationStatus.UNDER_REVIEW) {
            return;
        }

        message.setModerationStatus(MessageModerationStatus.BLOCKED);
        messageRepository.save(message);

        messageReportRepository.resolveAllOpenByMessageId(messageId, Instant.now(), adminId, ReportStatus.RESOLVED);

        resolveOpenAlert(messageId, AdminAlertType.MESSAGE_REPORT, adminId);
        resolveOpenAlert(messageId, AdminAlertType.MODERATION_UNRESOLVED, adminId);

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

    // A reported-and-swept message can carry both alert types at once; resolve each independently
    // rather than replacing the MESSAGE_REPORT-only lookup.
    private void resolveOpenAlert(Long messageId, AdminAlertType type, Long adminId) {
        adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(
            String.valueOf(messageId), type, AdminAlertStatus.OPEN)
            .ifPresent(alert -> {
                alert.setStatus(AdminAlertStatus.RESOLVED);
                alert.setResolvedAt(Instant.now());
                alert.setResolvedBy(adminId);
                adminAlertRepository.save(alert);
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
            // findMessagingPolicy, not getMessagingPolicy: this runs inside the transaction that
            // has already written an admin approve/block decision — throwing here would roll that
            // decision back before it commits, over a display concern.
            var policy = agePolicyService.findMessagingPolicy(conv.getPlayerId());
            if (policy.isEmpty()) {
                log.error("No messaging policy for playerId={} on conversationId={} — "
                        + "skipping NEW_MESSAGE SSE (orphaned player_profiles row?)",
                    conv.getPlayerId(), conversationId);
                return null;
            }
            return AgeMessagingPolicy.from(policy.get()).parentIsBlocked()
                ? resolvePlayerUserId(conv.getPlayerId(), conversationId)
                : conv.getParentId();
        } else {
            return coachProfileRepository.findById(conv.getCoachId())
                .map(CoachProfile::getUserId)
                .orElse(null);
        }
    }

    /**
     * {@code Conversation.playerId} is a player-PROFILE id; {@code MessagingEmitterRegistry} is
     * keyed by USER id. Kept in sync with {@code ModerationResultApplier.resolvePlayerUserId} —
     * see that method for the full rationale.
     */
    private Long resolvePlayerUserId(Long playerProfileId, Long conversationId) {
        Long userId = playerProfileRepository.findById(playerProfileId)
            .map(PlayerProfile::getUserId)
            .orElse(null);
        if (userId == null) {
            log.error("Player profile {} on conversationId={} has no linked user account — "
                    + "skipping NEW_MESSAGE SSE", playerProfileId, conversationId);
        }
        return userId;
    }
}
