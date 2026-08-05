package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.messaging.contract.MessageHeldForReviewEvent;
import com.softropic.skillars.platform.messaging.contract.MessageModerationStatus;
import com.softropic.skillars.platform.messaging.contract.ModerationResult;
import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;
import com.softropic.skillars.platform.messaging.repo.ConversationRepository;
import com.softropic.skillars.platform.messaging.repo.Message;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.AgePolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ModerationResultApplier {

    /** Alert reason for a message held by an UNCERTAIN verdict (or a Gemini call that threw). */
    static final String HELD_REASON_UNCERTAIN = "MODERATION_UNCERTAIN";

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final AgePolicyService agePolicyService;
    private final MessagingEmitterRegistry emitterRegistry;
    private final ApplicationEventPublisher publisher;

    // findByIdForUpdate, not findById: this verdict must lose to any decision already recorded
    // against the row. The Gemini call that produced `verdict` runs outside any transaction
    // (MessagingService.sendMessage is NOT_SUPPORTED) and can take seconds — ample room for an
    // admin to approve/block the message, for MessageModerationSweeper to sweep it, or for a
    // duplicate moderation call to land first (which this also makes idempotent for free). This
    // is the FIRST read of the row in this transaction (applyResult is the transaction boundary
    // here, unlike ReviewModerationService which is invoked from an AFTER_COMMIT listener and
    // needs REQUIRES_NEW to escape a stale EntityManager), so the locked query returns fresh DB
    // state and needs no entityManager.refresh.
    @Transactional
    public ModerationResult applyResult(Long messageId, ModerationVerdict verdict) {
        Message message = messageRepository.findByIdForUpdate(messageId).orElse(null);
        if (message == null) {
            log.warn("ModerationResultApplier: message not found: messageId={}", messageId);
            return new ModerationResult(MessageModerationStatus.PENDING, null);
        }

        // The guard is PENDING and undeleted, in one branch. A PENDING message can be soft-deleted
        // (softDeleteMessage blocks only UNDER_REVIEW/BLOCKED) and toMessageDto does not hide PENDING
        // content, so the sender can see and delete it while this verdict is still in flight.
        // Writing onto a deleted row would have MessageModerationSweeper re-select it forever, raise
        // an admin alert with a content preview for a message the sender removed, and let an approve
        // emit a NEW_MESSAGE SSE for content no read path returns.
        if (message.getModerationStatus() != MessageModerationStatus.PENDING || message.getDeletedAt() != null) {
            log.warn("ModerationResultApplier: message {} already resolved as {} (deleted={}) — "
                    + "discarding moderation verdict {}",
                messageId, message.getModerationStatus(), message.getDeletedAt() != null, verdict);
            return new ModerationResult(message.getModerationStatus(), message.getDeliveredAt());
        }

        MessageModerationStatus status;
        Instant deliveredAt;

        switch (verdict) {
            case SAFE -> {
                status = MessageModerationStatus.APPROVED;
                deliveredAt = Instant.now();
            }
            case UNSAFE -> {
                status = MessageModerationStatus.BLOCKED;
                deliveredAt = null;
            }
            default -> {
                status = MessageModerationStatus.UNDER_REVIEW;
                deliveredAt = null;
            }
        }

        message.setModerationStatus(status);
        message.setDeliveredAt(deliveredAt);
        messageRepository.save(message);

        final Long msgId = message.getId();
        final Long convId = message.getConversationId();
        final Long senderId = message.getSenderId();
        final String senderRoleName = message.getSenderRole().name();

        if (verdict == ModerationVerdict.SAFE) {
            Long recipientId = resolveRecipient(convId, senderRoleName);
            if (recipientId != null) {
                final Long finalRecipientId = recipientId;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        emitterRegistry.emit(finalRecipientId, "NEW_MESSAGE",
                            Map.of("type", "NEW_MESSAGE", "messageId", msgId, "conversationId", convId));
                    }
                });
            }
        } else if (verdict == ModerationVerdict.UNSAFE) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emitterRegistry.emit(senderId, "MESSAGE_BLOCKED",
                        Map.of("type", "MESSAGE_BLOCKED", "messageId", msgId, "conversationId", convId));
                }
            });
        } else {
            // UNDER_REVIEW (UNCERTAIN verdict or Gemini failure — both funnel through the same
            // arm, so the reason names the verdict, not the cause): the message is invisible to
            // sender and recipient alike and to the admin queue unless this fires. Published
            // inside the guarded branch, so a message that turned out to be already resolved or
            // deleted raises no alert.
            //
            // The listener runs REQUIRES_NEW, so its insert commits in its own transaction inside
            // this call and cannot roll back the verdict written above; catching here is what
            // keeps a failed alert from failing the send. An alert we could not raise is far less
            // bad than a message bounced back to PENDING, which is the stranding AC2 exists to fix.
            try {
                publisher.publishEvent(new MessageHeldForReviewEvent(msgId, convId, HELD_REASON_UNCERTAIN));
            } catch (Exception e) {
                log.error("Failed to raise MODERATION_UNRESOLVED alert for message {} — "
                        + "the message is UNDER_REVIEW but invisible to the admin queue", msgId, e);
            }
        }

        return new ModerationResult(status, deliveredAt);
    }

    private Long resolveRecipient(Long conversationId, String senderRole) {
        var conv = conversationRepository.findById(conversationId).orElse(null);
        if (conv == null) return null;

        if ("COACH".equals(senderRole)) {
            // findMessagingPolicy, not getMessagingPolicy: this runs inside a transaction that has
            // already decided a moderation verdict. Throwing here would roll back that decision
            // before it commits and strand the message in PENDING — the exact failure AC2 exists
            // to clean up. The caller (applyResult) already treats a null recipient as "no SSE".
            var policy = agePolicyService.findMessagingPolicy(conv.getPlayerId());
            if (policy.isEmpty()) {
                log.error("No messaging policy for playerId={} on conversationId={} — "
                        + "skipping NEW_MESSAGE SSE (orphaned player_profiles row?)",
                    conv.getPlayerId(), conversationId);
                return null;
            }
            return AgeMessagingPolicy.from(policy.get()).parentIsBlocked()
                ? resolvePlayerUserId(conv.getPlayerId(), conversationId)
                : conv.getParentId();
        } else {
            return coachProfileRepository.findById(conv.getCoachId())
                .map(CoachProfile::getUserId)
                .orElse(null);
        }
    }

    /**
     * {@code Conversation.playerId} is a player-PROFILE id, but {@link MessagingEmitterRegistry} is
     * keyed by USER id (see {@code MessagingService.registerSse}). Returning the profile id here —
     * as this did before code review — silently dropped every live notification to an adult player,
     * the one branch where the player rather than the parent is the recipient. The sibling arm
     * returns {@code conv.getParentId()} and the coach arm {@code CoachProfile::getUserId}, both
     * user ids, so the mismatch was invisible at the call site.
     * <p>Duplicated verbatim in {@code AdminMessageService.resolveRecipient}; the two are kept in
     * sync deliberately rather than extracted, for the circular-dependency reason recorded on
     * {@code MessagingReportService.verifyIsParty}.
     */
    private Long resolvePlayerUserId(Long playerProfileId, Long conversationId) {
        Long userId = playerProfileRepository.findById(playerProfileId)
            .map(PlayerProfile::getUserId)
            .orElse(null);
        if (userId == null) {
            log.error("Player profile {} on conversationId={} has no linked user account — "
                    + "skipping NEW_MESSAGE SSE", playerProfileId, conversationId);
        }
        return userId;
    }
}
