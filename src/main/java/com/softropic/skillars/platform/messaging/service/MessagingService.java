package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.messaging.contract.ConversationSummaryDto;
import com.softropic.skillars.platform.messaging.contract.MessageDto;
import com.softropic.skillars.platform.messaging.contract.MessageModerationStatus;
import com.softropic.skillars.platform.messaging.contract.MessagingErrorCode;
import com.softropic.skillars.platform.messaging.contract.ModerationResult;
import com.softropic.skillars.platform.messaging.contract.ModerationService;
import com.softropic.skillars.platform.messaging.contract.SenderRole;
import com.softropic.skillars.platform.messaging.repo.Conversation;
import com.softropic.skillars.platform.messaging.repo.ConversationRepository;
import com.softropic.skillars.platform.messaging.repo.Message;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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

    public ConversationSummaryDto initiateConversation(UUID coachId, Long playerId, Long callerUserId, String role) {
        boolean hasBooking = bookingRepository.existsByCoachIdAndPlayerIdAndStatusIn(coachId, playerId, CONFIRMED_STATES);
        if (!hasBooking) {
            throw new OperationNotAllowedException(
                "No booking relationship between coach and player",
                MessagingErrorCode.NO_BOOKING_RELATIONSHIP);
        }

        var player = playerProfileRepository.findById(playerId)
            .orElseThrow(() -> new ResourceNotFoundException("Player not found", "player"));

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
            conversations = conversationRepository.findActiveByParentId(callerUserId);
        } else {
            conversations = conversationRepository.findActiveByPlayerId(callerUserId);
        }

        return conversations.stream()
            .map(c -> toSummary(c, callerUserId, role))
            .sorted(Comparator.comparing(ConversationSummaryDto::lastMessageAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    public MessageDto sendMessage(Long conversationId, String content, Long senderUserId, String role) {
        if (content == null || content.isBlank() || content.length() > 2000) {
            throw new OperationNotAllowedException(
                "Message content is invalid: must be 1–2000 characters",
                MessagingErrorCode.INVALID_CONTENT);
        }

        var conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation not found", "conversation"));

        verifyIsParty(conv, senderUserId, role);

        var message = new Message();
        message.setConversationId(conversationId);
        message.setSenderId(senderUserId);
        message.setSenderRole(SenderRole.valueOf(role));
        message.setContent(content);
        message.setModerationStatus(MessageModerationStatus.PENDING);
        message.setCreatedAt(Instant.now());
        message = messageRepository.save(message);

        conv.setLastMessageAt(Instant.now());
        conversationRepository.save(conv);

        ModerationResult moderation = moderationService.moderate(message.getId(), content);
        message.setModerationStatus(moderation.moderationStatus());
        message.setDeliveredAt(moderation.deliveredAt());

        Long recipientUserId = resolveRecipient(conv, role);
        if (recipientUserId != null) {
            final Long finalRecipientId = recipientUserId;
            final Long finalMessageId = message.getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    messagingEmitterRegistry.emit(finalRecipientId,
                        Map.of("type", "NEW_MESSAGE", "messageId", finalMessageId, "conversationId", conversationId));
                }
            });
        }

        return toMessageDto(message);
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
            return playerProfileRepository.findById(conv.getPlayerId())
                .map(p -> p.getName())
                .orElse("Unknown Player");
        } else {
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

    private Long resolveRecipient(Conversation conv, String role) {
        if ("COACH".equals(role)) {
            return conv.getParentId();
        } else {
            return coachProfileRepository.findById(conv.getCoachId())
                .map(CoachProfile::getUserId)
                .orElse(null);
        }
    }

    private MessageDto toMessageDto(Message msg) {
        String content = msg.getModerationStatus() == MessageModerationStatus.BLOCKED ? null : msg.getContent();
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
