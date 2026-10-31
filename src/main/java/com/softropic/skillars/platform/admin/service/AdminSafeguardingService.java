package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.contract.AdminActionType;
import com.softropic.skillars.platform.admin.contract.AdminSafeguardingRecordDto;
import com.softropic.skillars.platform.admin.contract.AdminSafeguardingRequestDto;
import com.softropic.skillars.platform.admin.repo.AdminActionLog;
import com.softropic.skillars.platform.admin.repo.AdminActionLogRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachSafeguardingCheck;
import com.softropic.skillars.platform.marketplace.repo.CoachSafeguardingRequest;
import com.softropic.skillars.platform.marketplace.service.CoachSafeguardingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * S-09: the staff side of the optional coach safeguarding check. Every state change and every view of an upload is
 * audited in the same transaction as the action ({@code AdminActionLog}), so an action cannot succeed unaudited. The
 * audit reason carries the check type, validity and whether an upload was on file - never anything from the
 * certificate. The work itself is {@link CoachSafeguardingService}'s.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSafeguardingService {

    private static final int PAGE_SIZE = 20;

    private final CoachSafeguardingService safeguardingService;
    private final CoachProfileRepository coachProfileRepository;
    private final AdminActionLogRepository adminActionLogRepository;

    @Transactional(readOnly = true)
    public Page<AdminSafeguardingRequestDto> getPendingRequests(int page) {
        Page<CoachSafeguardingRequest> requests = safeguardingService.pendingRequests(PageRequest.of(page, PAGE_SIZE));
        Map<UUID, String> names = namesOf(requests.map(CoachSafeguardingRequest::getCoachId).getContent());
        return requests.map(r -> new AdminSafeguardingRequestDto(
            r.getCoachId(), names.getOrDefault(r.getCoachId(), ""), r.getCheckType(), r.getRequestedAt(),
            r.hasUpload(), r.getUploadStoredAt(), safeguardingService.hasValidCheck(r.getCoachId())));
    }

    @Transactional(readOnly = true)
    public Page<AdminSafeguardingRecordDto> getRecords(int page) {
        Page<CoachSafeguardingCheck> records = safeguardingService.validRecords(PageRequest.of(page, PAGE_SIZE));
        Map<UUID, String> names = namesOf(records.map(CoachSafeguardingCheck::getCoachId).getContent());
        return records.map(c -> toRecordDto(c, names.getOrDefault(c.getCoachId(), "")));
    }

    /** The pending request's decrypted upload for the staff viewer; the view is audited before the bytes leave. */
    @Transactional
    public CoachSafeguardingService.Document viewDocument(UUID coachId, Long adminId) {
        CoachSafeguardingService.Document document = safeguardingService.loadDocument(coachId);
        audit(AdminActionType.SAFEGUARDING_DOC_VIEWED, coachId, adminId, document.contentType());
        return document;
    }

    @Transactional
    public AdminSafeguardingRecordDto inspect(UUID coachId, Long adminId) {
        CoachSafeguardingService.Inspection inspection = safeguardingService.markInspected(coachId, adminId);
        CoachSafeguardingCheck check = inspection.check();
        audit(AdminActionType.SAFEGUARDING_INSPECTED, coachId, adminId,
            check.getCheckType() + " until " + check.getExpiresAt().atZone(ZoneOffset.UTC).toLocalDate()
                + (inspection.hadUpload() ? " (upload deleted)" : " (no upload)"));
        log.info("Safeguarding check inspected: coachId={} adminId={}", coachId, adminId);
        return toRecordDto(check, nameOf(coachId));
    }

    @Transactional
    public void reject(UUID coachId, String reason, Long adminId) {
        CoachSafeguardingRequest request = safeguardingService.reject(coachId);
        audit(AdminActionType.SAFEGUARDING_REJECTED, coachId, adminId,
            request.getCheckType() + (reason == null || reason.isBlank() ? "" : ": " + reason.trim()));
        log.info("Safeguarding request rejected: coachId={} adminId={}", coachId, adminId);
    }

    @Transactional
    public void revoke(UUID coachId, String reason, Long adminId) {
        CoachSafeguardingCheck check = safeguardingService.revoke(coachId);
        audit(AdminActionType.SAFEGUARDING_REVOKED, coachId, adminId,
            check.getCheckType() + (reason == null || reason.isBlank() ? "" : ": " + reason.trim()));
        log.info("Safeguarding check revoked: coachId={} adminId={}", coachId, adminId);
    }

    private void audit(AdminActionType type, UUID coachId, Long adminId, String reason) {
        AdminActionLog actionLog = new AdminActionLog();
        actionLog.setAdminId(adminId);
        actionLog.setActionType(type);
        actionLog.setReferenceId(coachId.toString());
        actionLog.setReason(reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason);
        adminActionLogRepository.save(actionLog);
    }

    private Map<UUID, String> namesOf(java.util.List<UUID> coachIds) {
        return coachProfileRepository.findAllById(coachIds).stream()
            .collect(Collectors.toMap(CoachProfile::getId, CoachProfile::getDisplayName, (a, b) -> a));
    }

    private String nameOf(UUID coachId) {
        return coachProfileRepository.findById(coachId).map(CoachProfile::getDisplayName).orElse("");
    }

    private static AdminSafeguardingRecordDto toRecordDto(CoachSafeguardingCheck c, String name) {
        return new AdminSafeguardingRecordDto(c.getCoachId(), name, c.getCheckType(), c.getInspectedAt(),
            c.getExpiresAt(), c.getInspectedBy());
    }
}
