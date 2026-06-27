package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.messaging.contract.ConversationReportedEvent;
import com.softropic.skillars.platform.messaging.contract.MessageReportReason;
import com.softropic.skillars.platform.messaging.contract.MessageReportedEvent;
import com.softropic.skillars.platform.messaging.contract.MessageModerationStatus;
import com.softropic.skillars.platform.messaging.contract.MessagingErrorCode;
import com.softropic.skillars.platform.messaging.contract.ConversationStatus;
import com.softropic.skillars.platform.messaging.contract.ReportResponse;
import com.softropic.skillars.platform.messaging.contract.ReportStatus;
import com.softropic.skillars.platform.messaging.repo.Conversation;
import com.softropic.skillars.platform.messaging.repo.ConversationReport;
import com.softropic.skillars.platform.messaging.repo.ConversationReportRepository;
import com.softropic.skillars.platform.messaging.repo.ConversationRepository;
import com.softropic.skillars.platform.messaging.repo.MessageReport;
import com.softropic.skillars.platform.messaging.repo.MessageReportRepository;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class MessagingReportService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageReportRepository messageReportRepository;
    private final ConversationReportRepository conversationReportRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ReportResponse reportMessage(Long conversationId, Long messageId, Long reporterUserId,
                                        String role, MessageReportReason reason, String details) {
        var conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation not found", "conversation"));

        verifyIsParty(conv, reporterUserId, role);

        var message = messageRepository.findById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message not found", "message"));
        if (!message.getConversationId().equals(conversationId)) {
            throw new OperationNotAllowedException(
                "Message does not belong to this conversation", MessagingErrorCode.NOT_A_PARTY);
        }

        if (messageReportRepository.existsByMessageIdAndReportedBy(messageId, reporterUserId)) {
            throw new OperationNotAllowedException(
                "Message has already been reported by this user", MessagingErrorCode.ALREADY_REPORTED);
        }

        if (message.getModerationStatus() != MessageModerationStatus.BLOCKED) {
            message.setModerationStatus(MessageModerationStatus.UNDER_REVIEW);
            messageRepository.save(message);
        }

        var report = new MessageReport();
        report.setMessageId(messageId);
        report.setReportedBy(reporterUserId);
        report.setReason(reason);
        report.setDetails(details);
        report.setStatus(ReportStatus.OPEN);
        report.setCreatedAt(Instant.now());

        MessageReport saved;
        try {
            saved = messageReportRepository.save(report);
        } catch (DataIntegrityViolationException e) {
            throw new OperationNotAllowedException(
                "Message has already been reported by this user", MessagingErrorCode.ALREADY_REPORTED);
        }

        eventPublisher.publishEvent(
            new MessageReportedEvent(saved.getId(), messageId, conversationId, reporterUserId, reason.name()));

        return new ReportResponse(saved.getId());
    }

    public ReportResponse reportConversation(Long conversationId, Long reporterUserId, String role,
                                              MessageReportReason reason, String details) {
        var conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation not found", "conversation"));

        verifyIsParty(conv, reporterUserId, role);

        if (conversationReportRepository.existsByConversationIdAndReportedBy(conversationId, reporterUserId)) {
            throw new OperationNotAllowedException(
                "Conversation has already been reported by this user", MessagingErrorCode.ALREADY_REPORTED);
        }

        conv.setStatus(ConversationStatus.BLOCKED);
        conversationRepository.save(conv);

        var report = new ConversationReport();
        report.setConversationId(conversationId);
        report.setReportedBy(reporterUserId);
        report.setReason(reason);
        report.setDetails(details);
        report.setStatus(ReportStatus.OPEN);
        report.setCreatedAt(Instant.now());

        ConversationReport saved;
        try {
            saved = conversationReportRepository.save(report);
        } catch (DataIntegrityViolationException e) {
            throw new OperationNotAllowedException(
                "Conversation has already been reported by this user", MessagingErrorCode.ALREADY_REPORTED);
        }

        eventPublisher.publishEvent(
            new ConversationReportedEvent(saved.getId(), conversationId, reporterUserId, reason.name()));

        return new ReportResponse(saved.getId());
    }

    // Duplicates MessagingService.verifyIsParty() — injecting MessagingService would create a circular dep
    private void verifyIsParty(Conversation conv, Long callerUserId, String role) {
        boolean isParty = switch (role) {
            case "COACH" -> {
                var coach = coachProfileRepository.findByUserId(callerUserId);
                yield coach.map(c -> Objects.equals(c.getId(), conv.getCoachId())).orElse(false);
            }
            case "PARENT" -> Objects.equals(conv.getParentId(), callerUserId);
            default -> Objects.equals(conv.getPlayerId(), callerUserId);
        };
        if (!isParty) {
            throw new OperationNotAllowedException(
                "Caller is not a party to this conversation",
                MessagingErrorCode.NOT_A_PARTY);
        }
    }
}
