package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.messaging.contract.ConversationSummaryDto;
import com.softropic.skillars.platform.messaging.contract.MessageDto;
import com.softropic.skillars.platform.messaging.contract.MessageModerationStatus;
import com.softropic.skillars.platform.messaging.contract.MessagingErrorCode;
import com.softropic.skillars.platform.messaging.contract.ModerationService;
import com.softropic.skillars.platform.messaging.contract.SenderRole;
import com.softropic.skillars.platform.messaging.repo.Conversation;
import com.softropic.skillars.platform.messaging.repo.ConversationRepository;
import com.softropic.skillars.platform.messaging.repo.Message;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.AgePolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class MessagingService {

    private static final List<String> CONFIRMED_STATES =
        List.of("CONFIRMED", "UPCOMING", "IN_PROGRESS", "COMPLETED");

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final BookingRepository bookingRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final ModerationService moderationService;
    private final MessagingEmitterRegistry messagingEmitterRegistry;
    private final ConversationCreationHelper conversationCreationHelper;
    private final AgePolicyService agePolicyService;
    private final TransactionTemplate transactionTemplate;

    public ConversationSummaryDto initiateConversation(UUID coachId, Long playerId, Long callerUserId, String role) {
        boolean hasBooking = bookingRepository.existsByCoachIdAndPlayerIdAndStatusIn(coachId, playerId, CONFIRMED_STATES);
        if (!hasBooking) {
            throw new OperationNotAllowedException(
                "No booking relationship between coach and player",
                MessagingErrorCode.NO_BOOKING_RELATIONSHIP);
        }

        var player = playerProfileRepository.findById(playerId)
            .orElseThrow(() -> new ResourceNotFoundException("Player not found", "player"));

        if ("PARENT".equals(role)) {
            AgeMessagingPolicy agePolicy = AgeMessagingPolicy.from(agePolicyService.getMessagingPolicy(playerId));
            if (!agePolicy.parentHasAccess()) {
                throw new OperationNotAllowedException(
                    "Parent cannot initiate conversations for adult players",
                    MessagingErrorCode.PARENTAL_OVERSIGHT_NOT_APPLICABLE);
            }
        }

        Conversation conversation;
        try {
            conversation = conversationCreationHelper.tryCreate(coachId, playerId, player.getParentId());
        } catch (DataIntegrityViolationException e) {
            // REQUIRES_NEW tx was rolled back cleanly; re-query in this transaction
            conversation = conversationRepository.findByCoachIdAndPlayerId(coachId, playerId)
                .orElseThrow(() -> e);
        }

        return toSummary(conversation, callerUserId, role);
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryDto> getConversations(Long callerUserId, String role) {
        List<Conversation> conversations;
        if ("COACH".equals(role)) {
            var coach = coachProfileRepository.findByUserId(callerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach"));
            conversations = conversationRepository.findActiveByCoachId(coach.getId());
        } else if ("PARENT".equals(role)) {
            List<Conversation> all = conversationRepository.findActiveByParentId(callerUserId);
            // N+1 note: getMessagingPolicy is called once here to filter, then again in resolveOtherPartyName —
            // 2 lookups per surviving conversation. Acceptable for MVP; reduce in a later optimisation story.
            conversations = all.stream()
                .filter(c -> AgeMessagingPolicy.from(
                    agePolicyService.getMessagingPolicy(c.getPlayerId())).parentHasAccess())
                .toList();
        } else {
            // PLAYER: all conversations belong to the same player (callerUserId), one policy lookup suffices
            AgeMessagingPolicy callerPolicy = AgeMessagingPolicy.from(
                agePolicyService.getMessagingPolicy(callerUserId));
            conversations = callerPolicy.visibleToPlayer()
                ? conversationRepository.findActiveByPlayerId(callerUserId)
                : List.of();
        }

        return conversations.stream()
            .map(c -> toSummary(c, callerUserId, role))
            .sorted(Comparator.comparing(ConversationSummaryDto::lastMessageAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MessageDto sendMessage(Long conversationId, String content, Long senderUserId, String role) {
        if (content == null || content.isBlank() || content.length() > 2000) {
            throw new OperationNotAllowedException(
                "Message content is invalid: must be 1–2000 characters",
                MessagingErrorCode.INVALID_CONTENT);
        }

        var conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation not found", "conversation"));

        verifyIsParty(conv, senderUserId, role);

        AgeMessagingPolicy agePolicy = AgeMessagingPolicy.from(
            agePolicyService.getMessagingPolicy(conv.getPlayerId()));
        if ("PLAYER".equals(role) && agePolicy.playerIsBlocked()) {
            throw new OperationNotAllowedException(
                "Player direct messaging is restricted for this age tier",
                MessagingErrorCode.PLAYER_DIRECT_MESSAGING_RESTRICTED);
        }
        if ("PARENT".equals(role) && agePolicy.parentIsBlocked()) {
            throw new OperationNotAllowedException(
                "Parent cannot send messages in adult player conversations",
                MessagingErrorCode.PARENT_MESSAGING_RESTRICTED_FOR_ADULT);
        }

        // Save message PENDING — commits immediately so ModerationResultApplier can see it
        final long[] savedMessageId = {0L};
        transactionTemplate.execute(status -> {
            Conversation c = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found", "conversation"));
            var message = new Message();
            message.setConversationId(conversationId);
            message.setSenderId(senderUserId);
            message.setSenderRole(SenderRole.valueOf(role));
            message.setContent(content);
            message.setModerationStatus(MessageModerationStatus.PENDING);
            message.setCreatedAt(Instant.now());
            Message saved = messageRepository.save(message);
            c.setLastMessageAt(Instant.now());
            conversationRepository.save(c);
            savedMessageId[0] = saved.getId();
            return null;
        });
        long messageId = savedMessageId[0];

        moderationService.moderate(messageId, content);
        Message finalMessage = messageRepository.findById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message not found", "message"));
        return toMessageDto(finalMessage);
    }

    public Page<MessageDto> getMessages(Long conversationId, Long callerUserId, String role, Pageable pageable) {
        var conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation not found", "conversation"));

        verifyIsParty(conv, callerUserId, role);

        Page<Message> page = messageRepository.findByConversationIdAndNotDeleted(conversationId, pageable);

        // Mark read: update role's lastReadAt timestamp
        updateLastRead(conv, role);
        conversationRepository.save(conv);

        return page.map(this::toMessageDto);
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryDto> getConversationsForPlayer(Long playerId, Long parentUserId) {
        // 1. Ownership guard — always 403, never 404
        playerProfileRepository.findByIdAndParentId(playerId, parentUserId)
            .orElseThrow(() -> new OperationNotAllowedException(
                "Parent does not own this player", MessagingErrorCode.NOT_A_PARTY));

        // 2. Age check — adult player conversations not surfaced to parents
        AgeMessagingPolicy agePolicy = AgeMessagingPolicy.from(
            agePolicyService.getMessagingPolicy(playerId));
        if (!agePolicy.parentHasAccess()) {
            throw new OperationNotAllowedException(
                "Parental oversight is not applicable for adult players",
                MessagingErrorCode.PARENTAL_OVERSIGHT_NOT_APPLICABLE);
        }

        // 3. Return ALL conversations including BLOCKED — oversight view must not hide safety-flagged history
        List<Conversation> conversations = conversationRepository.findAllByPlayerId(playerId);
        return conversations.stream()
            .map(c -> toSummary(c, parentUserId, "PARENT"))
            .sorted(Comparator.comparing(ConversationSummaryDto::lastMessageAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    @Transactional(readOnly = true)
    public Page<MessageDto> getMessagesForPlayerConversation(
            Long playerId, Long conversationId, Long parentUserId, Pageable pageable) {
        // 1. Ownership guard — always 403, never 404
        playerProfileRepository.findByIdAndParentId(playerId, parentUserId)
            .orElseThrow(() -> new OperationNotAllowedException(
                "Parent does not own this player", MessagingErrorCode.NOT_A_PARTY));

        // 2. Age check
        AgeMessagingPolicy agePolicy = AgeMessagingPolicy.from(
            agePolicyService.getMessagingPolicy(playerId));
        if (!agePolicy.parentHasAccess()) {
            throw new OperationNotAllowedException(
                "Parental oversight is not applicable for adult players",
                MessagingErrorCode.PARENTAL_OVERSIGHT_NOT_APPLICABLE);
        }

        // 3. Load conversation and verify it belongs to this player
        var conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new OperationNotAllowedException(
                "Conversation not found or access denied", MessagingErrorCode.NOT_A_PARTY));
        if (!conv.getPlayerId().equals(playerId)) {
            throw new OperationNotAllowedException(
                "Conversation does not belong to this player", MessagingErrorCode.NOT_A_PARTY);
        }

        // 4. Return messages — do NOT update lastReadAt (AC6: oversight view is read-only for tracking)
        Page<Message> page = messageRepository.findByConversationIdAndNotDeleted(conversationId, pageable);
        return page.map(this::toMessageDto);
    }

    public SseEmitter registerSse(Long callerUserId) {
        return messagingEmitterRegistry.register(callerUserId);
    }

    @Transactional(readOnly = true)
    public Conversation getConversationForSse(Long conversationId, Long callerUserId, String role) {
        var conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation not found", "conversation"));
        verifyIsParty(conv, callerUserId, role);
        return conv;
    }

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

    private ConversationSummaryDto toSummary(Conversation conv, Long callerUserId, String role) {
        String otherPartyName = resolveOtherPartyName(conv, role);

        String lastMessagePreview = null;
        Page<Message> lastApproved = messageRepository.findLastApproved(conv.getId(), PageRequest.of(0, 1));
        if (lastApproved.hasContent()) {
            String msgContent = lastApproved.getContent().get(0).getContent();
            lastMessagePreview = msgContent.length() > 60 ? msgContent.substring(0, 60) : msgContent;
        }

        Instant rawLastReadAt = resolveLastReadAt(conv, role);
        // Null means never read: treat all messages as unread by using epoch as sentinel
        Instant since = rawLastReadAt != null ? rawLastReadAt : Instant.EPOCH;
        long unreadCount = messageRepository.countUnread(conv.getId(), callerUserId, since);

        return new ConversationSummaryDto(
            conv.getId(),
            otherPartyName,
            null,
            lastMessagePreview,
            conv.getLastMessageAt(),
            unreadCount
        );
    }

    private String resolveOtherPartyName(Conversation conv, String role) {
        if ("COACH".equals(role)) {
            String playerName = playerProfileRepository.findById(conv.getPlayerId())
                .map(p -> p.getName()).orElse("Unknown Player");
            String playerFirstName = playerName.contains(" ")
                ? playerName.substring(0, playerName.indexOf(' '))
                : playerName;
            AgeMessagingPolicy agePolicy = AgeMessagingPolicy.from(
                agePolicyService.getMessagingPolicy(conv.getPlayerId()));
            return switch (agePolicy) {
                case PROHIBITED, PARENT_MANAGED -> "Parent of " + playerFirstName;
                case SUPERVISED -> playerFirstName + " & parent";
                case UNRESTRICTED -> playerFirstName;
            };
        } else if ("PARENT".equals(role)) {
            AgeMessagingPolicy agePolicy = AgeMessagingPolicy.from(
                agePolicyService.getMessagingPolicy(conv.getPlayerId()));
            return switch (agePolicy) {
                case PROHIBITED, PARENT_MANAGED ->
                    coachProfileRepository.findById(conv.getCoachId())
                        .map(CoachProfile::getDisplayName).orElse("Unknown Coach");
                case SUPERVISED -> {
                    String playerName = playerProfileRepository.findById(conv.getPlayerId())
                        .map(p -> p.getName()).orElse("Unknown Player");
                    String playerFirstName = playerName.contains(" ")
                        ? playerName.substring(0, playerName.indexOf(' '))
                        : playerName;
                    String coachName = coachProfileRepository.findById(conv.getCoachId())
                        .map(CoachProfile::getDisplayName).orElse("Unknown Coach");
                    yield playerFirstName + "'s conversation with " + coachName;
                }
                case UNRESTRICTED -> {
                    // initiateConversation() gates this path; safe fallback if reached unexpectedly
                    String playerName = playerProfileRepository.findById(conv.getPlayerId())
                        .map(p -> p.getName()).orElse("Unknown Player");
                    yield playerName.contains(" ")
                        ? playerName.substring(0, playerName.indexOf(' '))
                        : playerName;
                }
            };
        } else {
            // PLAYER role: show coach name
            return coachProfileRepository.findById(conv.getCoachId())
                .map(CoachProfile::getDisplayName)
                .orElse("Unknown Coach");
        }
    }

    private Instant resolveLastReadAt(Conversation conv, String role) {
        return switch (role) {
            case "COACH" -> conv.getCoachLastReadAt();
            case "PARENT" -> conv.getParentLastReadAt();
            default -> conv.getPlayerLastReadAt();
        };
    }

    private void updateLastRead(Conversation conv, String role) {
        Instant now = Instant.now();
        switch (role) {
            case "COACH" -> conv.setCoachLastReadAt(now);
            case "PARENT" -> conv.setParentLastReadAt(now);
            default -> conv.setPlayerLastReadAt(now);
        }
    }

    private MessageDto toMessageDto(Message msg) {
        boolean contentHidden = msg.getModerationStatus() == MessageModerationStatus.BLOCKED
            || msg.getModerationStatus() == MessageModerationStatus.UNDER_REVIEW;
        String content = contentHidden ? null : msg.getContent();
        return new MessageDto(
            msg.getId(),
            msg.getSenderId(),
            msg.getSenderRole().name(),
            content,
            msg.getModerationStatus().name(),
            msg.getDeliveredAt(),
            msg.getCreatedAt()
        );
    }
}
