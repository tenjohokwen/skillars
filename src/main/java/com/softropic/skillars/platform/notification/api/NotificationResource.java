package com.softropic.skillars.platform.notification.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.notification.contract.NotificationCategory;
import com.softropic.skillars.platform.notification.contract.NotificationDto;
import com.softropic.skillars.platform.notification.contract.NotificationPreferenceDto;
import com.softropic.skillars.platform.notification.contract.UnreadCountDto;
import com.softropic.skillars.platform.notification.contract.UpdateNotificationPreferenceRequest;
import com.softropic.skillars.platform.notification.service.InAppNotificationService;
import com.softropic.skillars.platform.notification.service.NotificationPreferenceService;
import com.softropic.skillars.platform.security.service.SecurityUtil;

import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The current user's notification centre: in-app inbox and email preferences (UI-gap chunk C9).
 * Every endpoint acts on the authenticated caller only — there is no user id in any path or body,
 * and every write is scoped by that id inside its own SQL statement.
 */
@Observed(name = "notification.center")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationResource {

    private final InAppNotificationService inAppNotificationService;
    private final NotificationPreferenceService preferenceService;
    private final SecurityUtil securityUtil;

    @GetMapping
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    public Page<NotificationDto> list(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return inAppNotificationService.list(currentUserId(), page, size);
    }

    @GetMapping("/unread-count")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    public UnreadCountDto unreadCount() {
        return new UnreadCountDto(inAppNotificationService.unreadCount(currentUserId()));
    }

    /** Idempotent; an unknown id or another user's id is a silent no-op (no existence oracle). */
    @PostMapping("/{id}/read")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    public ResponseEntity<Void> markRead(@PathVariable UUID id) {
        inAppNotificationService.markRead(currentUserId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    public ResponseEntity<Void> markAllRead() {
        inAppNotificationService.markAllRead(currentUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preferences")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    public List<NotificationPreferenceDto> preferences() {
        return preferenceService.getPreferences(currentUserId());
    }

    @PutMapping("/preferences/{category}")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    public NotificationPreferenceDto updatePreference(@PathVariable NotificationCategory category,
                                                      @Valid @RequestBody UpdateNotificationPreferenceRequest request) {
        return preferenceService.setPreference(currentUserId(), category, request.emailEnabled());
    }

    private long currentUserId() {
        return securityUtil.requireCurrentUserId();
    }
}
