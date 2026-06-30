package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.contract.AdminAlertDto;
import com.softropic.skillars.platform.admin.contract.AdminAlertStatus;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import com.softropic.skillars.platform.admin.contract.AdminQueueSummaryDto;
import com.softropic.skillars.platform.admin.repo.AdminAlert;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.messaging.repo.ConversationReportRepository;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import com.softropic.skillars.platform.reviews.repo.ReviewFlagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminQueueService {

    private final AdminAlertRepository adminAlertRepository;
    private final MessageRepository messageRepository;
    private final ConversationReportRepository conversationReportRepository;
    private final ReviewFlagRepository reviewFlagRepository;

    @Transactional(readOnly = true)
    public Page<AdminAlertDto> getAlerts(String typeParam, int page) {
        AdminAlertType type;
        if ("ALL".equalsIgnoreCase(typeParam) || typeParam == null) {
            type = null;
        } else {
            try {
                type = AdminAlertType.valueOf(typeParam.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Unknown alert type: " + typeParam);
            }
        }
        Pageable p = PageRequest.of(Math.max(0, page), 20);
        return adminAlertRepository.findByTypeAndStatus(type, AdminAlertStatus.OPEN, p)
            .map(alert -> new AdminAlertDto(
                alert.getAlertId(),
                alert.getType().name(),
                alert.getReferenceId(),
                alert.getReferenceType().name(),
                alert.getStatus().name(),
                alert.getCreatedAt(),
                buildSummary(alert)));
    }

    private String buildSummary(AdminAlert alert) {
        return switch (alert.getType()) {
            case MESSAGE_REPORT -> {
                try {
                    Long msgId = Long.parseLong(alert.getReferenceId());
                    yield messageRepository.findById(msgId)
                        .map(m -> {
                            String c = m.getContent();
                            return c != null && c.length() > 100 ? c.substring(0, 100) : c;
                        })
                        .orElse("[message not found]");
                } catch (NumberFormatException e) {
                    yield "[invalid referenceId]";
                }
            }
            case CONVERSATION_REPORT -> {
                try {
                    Long convId = Long.parseLong(alert.getReferenceId());
                    List<?> reports = conversationReportRepository.findByConversationId(convId);
                    if (reports.isEmpty()) yield "[no report]";
                    var first = (com.softropic.skillars.platform.messaging.repo.ConversationReport) reports.get(0);
                    yield first.getReason() != null ? first.getReason().name() : "[no report]";
                } catch (NumberFormatException e) {
                    yield "[invalid referenceId]";
                }
            }
            case REVIEW_FLAG -> {
                try {
                    UUID reviewId = UUID.fromString(alert.getReferenceId());
                    long count = reviewFlagRepository.countByReviewId(reviewId);
                    String top = topFlagReason(reviewId);
                    yield count + " flags, top: " + top;
                } catch (IllegalArgumentException e) {
                    yield "[invalid referenceId]";
                }
            }
            case STRIKE_THRESHOLD -> "";
            default -> "";
        };
    }

    private String topFlagReason(UUID reviewId) {
        return reviewFlagRepository.findByReviewIdOrderByCreatedAtAsc(reviewId).stream()
            .collect(java.util.stream.Collectors.groupingBy(
                f -> f.getReason().name(),
                java.util.stream.Collectors.counting()))
            .entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("NONE");
    }

    @Transactional(readOnly = true)
    public AdminQueueSummaryDto getSummary() {
        List<Object[]> rows = adminAlertRepository.countOpenByType();
        Map<AdminAlertType, Long> counts = new EnumMap<>(AdminAlertType.class);
        for (Object[] row : rows) {
            counts.put((AdminAlertType) row[0], (Long) row[1]);
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return new AdminQueueSummaryDto(
            counts.getOrDefault(AdminAlertType.MESSAGE_REPORT, 0L),
            counts.getOrDefault(AdminAlertType.CONVERSATION_REPORT, 0L),
            counts.getOrDefault(AdminAlertType.REVIEW_FLAG, 0L),
            counts.getOrDefault(AdminAlertType.STRIKE_THRESHOLD, 0L),
            counts.getOrDefault(AdminAlertType.DISPUTE_RAISED, 0L),
            total);
    }
}
