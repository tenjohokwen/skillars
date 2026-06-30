package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.admin.contract.AdminActionType;
import com.softropic.skillars.platform.admin.contract.AdminAlertStatus;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import com.softropic.skillars.platform.admin.contract.AdminConversationDetailDto;
import com.softropic.skillars.platform.admin.contract.AdminConversationReportDto;
import com.softropic.skillars.platform.admin.contract.AdminMessageContextDto;
import com.softropic.skillars.platform.admin.repo.AdminActionLog;
import com.softropic.skillars.platform.admin.repo.AdminActionLogRepository;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.messaging.contract.ConversationStatus;
import com.softropic.skillars.platform.messaging.contract.ReportStatus;
import org.springframework.data.domain.PageRequest;
import com.softropic.skillars.platform.messaging.repo.ConversationReport;
import com.softropic.skillars.platform.messaging.repo.ConversationReportRepository;
import com.softropic.skillars.platform.messaging.repo.ConversationRepository;
import com.softropic.skillars.platform.messaging.repo.Message;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ConversationReportRepository conversationReportRepository;
    private final AdminAlertRepository adminAlertRepository;
    private final AdminActionLogRepository adminActionLogRepository;

    @Transactional(readOnly = true)
    public AdminConversationDetailDto getConversationDetail(Long conversationId) {
        var conversation = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation not found", "conversation"));

        List<Message> messages = messageRepository.findAllForAdmin(conversationId, PageRequest.of(0, 500));
        List<AdminMessageContextDto> messageDtos = messages.stream()
            .map(m -> new AdminMessageContextDto(
                m.getId(),
                m.getSenderRole().name(),
                m.getDeletedAt() != null ? null : m.getContent(),
                m.getModerationStatus().name(),
                m.getCreatedAt()))
            .toList();

        List<AdminConversationReportDto> reportDtos = conversationReportRepository
            .findByConversationId(conversationId).stream()
            .map(r -> new AdminConversationReportDto(
                r.getReason() != null ? r.getReason().name() : null,
                r.getDetails(),
                r.getCreatedAt()))
            .toList();

        return new AdminConversationDetailDto(
            conversation.getId(),
            conversation.getStatus().name(),
            messageDtos,
            reportDtos);
    }

    @Transactional
    public void unblockConversation(Long conversationId, Long adminId) {
        var conversation = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation not found", "conversation"));

        if (ConversationStatus.ACTIVE == conversation.getStatus()) {
            return;
        }

        conversation.setStatus(ConversationStatus.ACTIVE);
        conversationRepository.save(conversation);

        conversationReportRepository.resolveAllOpenByConversationId(
            conversationId, Instant.now(), adminId, ReportStatus.RESOLVED);

        adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(
            String.valueOf(conversationId), AdminAlertType.CONVERSATION_REPORT, AdminAlertStatus.OPEN)
            .ifPresent(alert -> {
                alert.setStatus(AdminAlertStatus.RESOLVED);
                alert.setResolvedAt(Instant.now());
                alert.setResolvedBy(adminId);
                adminAlertRepository.save(alert);
            });

        AdminActionLog logEntry = new AdminActionLog();
        logEntry.setAdminId(adminId);
        logEntry.setActionType(AdminActionType.CONVERSATION_UNBLOCK);
        logEntry.setReferenceId(String.valueOf(conversationId));
        adminActionLogRepository.save(logEntry);
    }
}
