package com.softropic.skillars.platform.notification.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.notification.contract.UnsubscribeResultDto;
import com.softropic.skillars.platform.notification.service.NotificationPreferenceService;

import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One-click email unsubscribe (RFC 8058) — the only unauthenticated notification endpoint, listed
 * as an exact path in {@code AppEndpoints.PUBLIC_ENDPOINTS} (nothing else under
 * {@code /api/notifications} is public). The signed token is the credential.
 *
 * <p>POST only: mailbox providers send {@code POST <List-Unsubscribe URL>} with the form body
 * {@code List-Unsubscribe=One-Click}, and the frontend landing page posts the same token after an
 * explicit click. There is deliberately no GET — link scanners and prefetchers issue GETs, and a GET
 * that mutated state would unsubscribe users who never clicked. {@code @RequestParam} reads the
 * token from the query string (one-click) or a form body alike; the one-click marker body itself
 * carries no information and is ignored.
 */
@Observed(name = "notification.unsubscribe")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class UnsubscribeResource {

    public static final String PATH = "/api/notifications/unsubscribe";

    private final NotificationPreferenceService preferenceService;

    @PostMapping("/unsubscribe")
    @PreAuthorize(SecurityConstants.IS_PERMIT_ALL)
    public UnsubscribeResultDto unsubscribe(@RequestParam("token") String token) {
        return new UnsubscribeResultDto(preferenceService.unsubscribe(token));
    }
}
