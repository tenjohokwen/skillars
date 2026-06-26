package com.softropic.skillars.platform.messaging.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.messaging.contract.ConversationRequest;
import com.softropic.skillars.platform.messaging.contract.ConversationSummaryDto;
import com.softropic.skillars.platform.messaging.contract.MessageDto;
import com.softropic.skillars.platform.messaging.contract.MessagingErrorCode;
import com.softropic.skillars.platform.messaging.contract.SendMessageRequest;
import com.softropic.skillars.platform.messaging.service.MessagingService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/messaging")
@RequiredArgsConstructor
@Observed(name = "messaging")
public class MessagingResource {

    private final MessagingService messagingService;
    private final SecurityUtil securityUtil;
    private final CoachProfileRepository coachProfileRepository;
    private final PlayerProfileRepository playerProfileRepository;

    @PostMapping("/conversations")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    public ResponseEntity<ConversationSummaryDto> initiateConversation(
            @RequestBody ConversationRequest request,
            @RequestParam(name = "role", required = false) String roleHint,
            Authentication auth) {
        Long userId = resolveUserId();
        // Verify caller is the coach or the parent who owns the player
        boolean isCoach = coachProfileRepository.findByUserId(userId)
            .map(c -> Objects.equals(c.getId(), request.coachId()))
            .orElse(false);
        boolean isParent = !isCoach &&
            playerProfileRepository.findByIdAndParentId(request.playerId(), userId).isPresent();
        if (!isCoach && !isParent) {
            throw new OperationNotAllowedException(
                "Caller is not the coach or the parent of the player",
                MessagingErrorCode.NOT_A_PARTY);
        }
        String role = resolveRole(auth, roleHint);
        ConversationSummaryDto result = messagingService.initiateConversation(
            request.coachId(), request.playerId(), userId, role);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/conversations")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    public ResponseEntity<List<ConversationSummaryDto>> getConversations(
            @RequestParam(name = "role", required = false) String roleHint,
            Authentication auth) {
        Long userId = resolveUserId();
        String role = resolveRole(auth, roleHint);
        return ResponseEntity.ok(messagingService.getConversations(userId, role));
    }

    @PostMapping("/conversations/{conversationId}/messages")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    public ResponseEntity<MessageDto> sendMessage(
            @PathVariable Long conversationId,
            @RequestBody SendMessageRequest request,
            @RequestParam(name = "role", required = false) String roleHint,
            Authentication auth) {
        Long userId = resolveUserId();
        String role = resolveRole(auth, roleHint);
        MessageDto result = messagingService.sendMessage(conversationId, request.content(), userId, role);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    public ResponseEntity<Page<MessageDto>> getMessages(
            @PathVariable Long conversationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "role", required = false) String roleHint,
            Authentication auth) {
        Long userId = resolveUserId();
        String role = resolveRole(auth, roleHint);
        return ResponseEntity.ok(
            messagingService.getMessages(conversationId, userId, role,
                PageRequest.of(Math.max(0, page), Math.min(size, 100))));
    }

    @GetMapping("/conversations/{conversationId}/events")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    public ResponseEntity<SseEmitter> subscribeToEvents(
            @PathVariable Long conversationId,
            @RequestParam(name = "role", required = false) String roleHint,
            Authentication auth) {
        Long userId = resolveUserId();
        String role = resolveRole(auth, roleHint);
        // Verify caller is a party before registering (throws ResourceNotFoundException / OperationNotAllowedException)
        messagingService.getConversationForSse(conversationId, userId, role);
        SseEmitter emitter = messagingService.registerSse(userId);
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(emitter);
    }

    private Long resolveUserId() {
        Object principal = securityUtil.getCurrentUser();
        if (!(principal instanceof Principal p)) {
            throw new InsufficientAuthenticationException("Unexpected principal type");
        }
        try {
            return Long.parseLong(p.getBusinessId());
        } catch (NumberFormatException e) {
            throw new InsufficientAuthenticationException("Invalid business ID format in principal");
        }
    }

    private String resolveRole(Authentication auth) {
        return resolveRole(auth, null);
    }

    private String resolveRole(Authentication auth, String roleHint) {
        if ("PARENT".equals(roleHint) &&
            auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PARENT"))) {
            return "PARENT";
        }
        if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_COACH"))) return "COACH";
        if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PARENT"))) return "PARENT";
        return "PLAYER";
    }
}
