package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.admin.contract.AdminAlertDto;
import com.softropic.skillars.platform.admin.contract.AdminBlockMessageRequest;
import com.softropic.skillars.platform.admin.contract.AdminConversationDetailDto;
import com.softropic.skillars.platform.admin.contract.AdminMessageDetailDto;
import com.softropic.skillars.platform.admin.contract.AdminQueueSummaryDto;
import com.softropic.skillars.platform.admin.service.AdminConversationService;
import com.softropic.skillars.platform.admin.service.AdminMessageService;
import com.softropic.skillars.platform.admin.service.AdminQueueService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Observed(name = "admin.moderation")
public class AdminModerationResource {

    private final AdminQueueService adminQueueService;
    private final AdminMessageService adminMessageService;
    private final AdminConversationService adminConversationService;
    private final SecurityUtil securityUtil;

    @GetMapping("/queue")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.queue")
    public ResponseEntity<Page<AdminAlertDto>> getQueue(
            @RequestParam(defaultValue = "ALL") String type,
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(defaultValue = "0") int page) {
        if (!"OPEN".equalsIgnoreCase(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Only status=OPEN is supported in this version");
        }
        return ResponseEntity.ok(adminQueueService.getAlerts(type, page));
    }

    @GetMapping("/queue/summary")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.queue.summary")
    public ResponseEntity<AdminQueueSummaryDto> getQueueSummary() {
        return ResponseEntity.ok(adminQueueService.getSummary());
    }

    @GetMapping("/messages/{messageId}")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.messages.detail")
    public ResponseEntity<AdminMessageDetailDto> getMessageDetail(@PathVariable Long messageId) {
        return ResponseEntity.ok(adminMessageService.getMessageDetail(messageId));
    }

    @PostMapping("/messages/{messageId}/approve")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.messages.approve")
    public ResponseEntity<Void> approveMessage(@PathVariable Long messageId) {
        adminMessageService.approveMessage(messageId, resolveAdminId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/messages/{messageId}/block")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.messages.block")
    public ResponseEntity<Void> blockMessage(
            @PathVariable Long messageId,
            @Valid @RequestBody AdminBlockMessageRequest request) {
        adminMessageService.blockMessage(messageId, request.reason(), resolveAdminId());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/conversations/{conversationId}")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.conversations.detail")
    public ResponseEntity<AdminConversationDetailDto> getConversationDetail(
            @PathVariable Long conversationId) {
        return ResponseEntity.ok(adminConversationService.getConversationDetail(conversationId));
    }

    @PostMapping("/conversations/{conversationId}/unblock")
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    @Observed(name = "admin.conversations.unblock")
    public ResponseEntity<Void> unblockConversation(@PathVariable Long conversationId) {
        adminConversationService.unblockConversation(conversationId, resolveAdminId());
        return ResponseEntity.ok().build();
    }

    private Long resolveAdminId() {
        Object principal = securityUtil.getCurrentUser();
        if (!(principal instanceof Principal p)) {
            throw new InsufficientAuthenticationException("Unexpected principal type");
        }
        try {
            return Long.parseLong(p.getBusinessId());
        } catch (NumberFormatException e) {
            throw new InsufficientAuthenticationException("Invalid business ID");
        }
    }
}
