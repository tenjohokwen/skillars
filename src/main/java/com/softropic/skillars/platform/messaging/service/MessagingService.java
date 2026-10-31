package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import com.softropic.skillars.infrastructure.security.RateLimitingService;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.messaging.contract.ConversationStatus;
import com.softropic.skillars.platform.messaging.contract.ConversationSummaryDto;
import com.softropic.skillars.platform.messaging.contract.MessageDto;
import com.softropic.skillars.platform.messaging.contract.MessageModerationStatus;
import com.softropic.skillars.platform.messaging.contract.MessagingErrorCode;
import com.softropic.skillars.platform.messaging.contract.ModerationService;
import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;
import com.softropic.skillars.platform.messaging.contract.SenderRole;
import com.softropic.skillars.platform.messaging.repo.Conversation;
import com.softropic.skillars.platform.messaging.repo.ConversationRepository;
import com.softropic.skillars.platform.messaging.repo.Message;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import com.softropic.skillars.platform.security.contract.AgeVerificationErrorCode;
import com.softropic.skillars.platform.security.contract.exception.AgeVerificationException;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import com.softropic.skillars.platform.security.service.AgePolicyService;
import com.softropic.skillars.platform.security.service.AgeVerificationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.softropic.skillars.platform.security.contract.MessagingPolicy;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
    /** skillars-deferred-91 AC19: shared "id -> player name" batch read, instead of a local copy. */
    private final com.softropic.skillars.platform.marketplace.service.PlayerProfileService playerProfileService;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;
    /** S-04: deterministic pre-Gemini check for minors' conversations; applies the UNCERTAIN (held) verdict. */
    private final ModerationResultApplier moderationResultApplier;
    private final ContactDetailSanitizer contactDetailSanitizer;
    private final RateLimitingService rateLimitingService;
    private final UserRepository userRepository;
    /** S-27: age gate for chats with a self-registered player. */
    private final AgeVerificationService ageVerificationService;

    /** Same switch RateLimitingAspect honours, so ITs that send many messages can turn limiting off. */
    @Value("${rate.limiting.enabled:true}")
    private boolean rateLimitingEnabled;

    /** S-04: sends per sender / per conversation per minute. A chat burst beyond this is spam or scripted. */
    static final int SEND_LIMIT_PER_SENDER_PER_MINUTE = 20;
    static final int SEND_LIMIT_PER_CONVERSATION_PER_MINUTE = 40;

    public ConversationSummaryDto initiateConversation(UUID coachId, Long playerId, Long callerUserId, String role) {
        assertAccountNotLocked(callerUserId);
        if (coachIsSuspended(coachId)) {
            // A suspended coach (admin suspension or CSAM safety hold) opens nothing. A family may still
            // be handed an EXISTING thread with that coach (history stays readable), but not a new one.
            if ("COACH".equals(role)
                    || conversationRepository.findByCoachIdAndPlayerId(coachId, playerId).isEmpty()) {
                throw new OperationNotAllowedException(
                    "This coach cannot currently take part in conversations",
                    MessagingErrorCode.COACH_SUSPENDED);
            }
        }
        boolean hasBooking = bookingRepository.existsByCoachIdAndPlayerIdAndStatusIn(coachId, playerId, CONFIRMED_STATES);
        if (!hasBooking) {
            throw new OperationNotAllowedException(
                "No booking relationship between coach and player",
                MessagingErrorCode.NO_BOOKING_RELATIONSHIP);
        }

        // AC19: direct — needs the full PlayerProfile entity (not just id -> name), and an
        // existence check that throws.
        var player = playerProfileRepository.findById(playerId)
            .orElseThrow(() -> new ResourceNotFoundException("Player not found", "player"));

        requireSelfRegisteredPlayerMayChat(player, coachId, role);

        if ("PARENT".equals(role)) {
            AgeMessagingPolicy agePolicy = AgeMessagingPolicy.from(agePolicyService.getMessagingPolicy(playerId));
            if (!agePolicy.parentHasAccess()) {
                throw new OperationNotAllowedException(
                    "Parent cannot initiate conversations for adult players",
                    MessagingErrorCode.PARENTAL_OVERSIGHT_NOT_APPLICABLE);
            }
        } else if ("PLAYER".equals(role)) {
            // Under-13 tiers: the player can neither send into nor even see the thread (visibleToPlayer
            // is false), so letting them create one would only produce a conversation that is
            // invisible to its creator. Same code sendMessage uses for the same rule.
            AgeMessagingPolicy agePolicy = AgeMessagingPolicy.from(agePolicyService.getMessagingPolicy(playerId));
            if (agePolicy.playerIsBlocked()) {
                throw new OperationNotAllowedException(
                    "Player direct messaging is restricted for this age tier",
                    MessagingErrorCode.PLAYER_DIRECT_MESSAGING_RESTRICTED);
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
            // S-04: a suspended coach sees no threads (the per-conversation reads refuse them too).
            conversations = coach.getStatus() == CoachProfileStatus.SUSPENDED
                ? List.of()
                : conversationRepository.findActiveByCoachId(coach.getId());
        } else if ("PARENT".equals(role)) {
            List<Conversation> all = conversationRepository.findActiveByParentId(callerUserId);
            // skillars-deferred-90 AC13: batch the age-policy lookup for the whole list ONCE, before
            // the filter (was one findMessagingPolicy per row here, then again per row in toSummary).
            // Read path: an orphaned player_profiles row must cost this one conversation, not the
            // whole list — an absent policy degrades locally instead of 404-ing the caller.
            Map<Long, MessagingPolicy> parentPolicyByPlayer = agePolicyService.findMessagingPoliciesByPlayerIds(
                all.stream().map(Conversation::getPlayerId).collect(Collectors.toSet()));
            conversations = all.stream()
                .filter(c -> {
                    MessagingPolicy policy = parentPolicyByPlayer.get(c.getPlayerId());
                    if (policy == null) {
                        log.error("getConversations: no player profile for playerId={} (conversationId={}) "
                                + "— excluding from parent's list", c.getPlayerId(), c.getId());
                        return false;
                    }
                    return AgeMessagingPolicy.from(policy).parentHasAccess();
                })
                .toList();
        } else if ("PLAYER".equals(role)) {
            // PLAYER: resolve the caller's own player-profile id once — Conversation.playerId and
            // the age-policy lookup both key on the player-profile id, not the caller's user id.
            // A caller with no player profile (or an unresolvable policy) gets an empty list, not
            // a 404 for the whole endpoint.
            Optional<PlayerProfile> callerProfile = playerProfileRepository.findByUserId(callerUserId);
            if (callerProfile.isEmpty()) {
                log.error("getConversations: no player profile for callerUserId={} — returning empty list", callerUserId);
                conversations = List.of();
            } else {
                Long playerId = callerProfile.get().getId();
                Optional<MessagingPolicy> policy = agePolicyService.findMessagingPolicy(playerId);
                if (policy.isEmpty()) {
                    log.error("getConversations: no messaging policy resolvable for playerId={} "
                            + "(callerUserId={}) — returning empty list", playerId, callerUserId);
                    conversations = List.of();
                } else {
                    conversations = AgeMessagingPolicy.from(policy.get()).visibleToPlayer()
                        ? conversationRepository.findActiveByPlayerId(playerId)
                        : List.of();
                }
            }
        } else {
            // See verifyIsParty for why this is OperationNotAllowedException and not
            // IllegalArgumentException.
            throw new OperationNotAllowedException(
                "Caller does not hold a recognised messaging role",
                MessagingErrorCode.NOT_A_PARTY);
        }

        if (conversations.isEmpty()) {
            return List.of();
        }
        SummaryContext ctx = buildSummaryContext(conversations, callerUserId, role);
        return conversations.stream()
            .map(c -> toSummary(c, callerUserId, role, ctx))
            .sorted(Comparator.comparing(ConversationSummaryDto::lastMessageAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MessageDto sendMessage(Long conversationId, String content, Long senderUserId, String role) {
        if (content == null || content.isBlank()
                || content.codePointCount(0, content.length()) > 2000) {
            throw new OperationNotAllowedException(
                "Message content is invalid: must be 1–2000 characters",
                MessagingErrorCode.INVALID_CONTENT);
        }

        var conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation not found", "conversation"));

        verifyIsParty(conv, senderUserId, role);

        if (conv.getStatus() == ConversationStatus.BLOCKED) {
            throw new OperationNotAllowedException(
                "Conversation is blocked — no new messages can be sent",
                MessagingErrorCode.CONVERSATION_BLOCKED);
        }

        // S-27: an existing thread with a self-registered player who is not (yet) age-verified is frozen too —
        // this also covers players registered before the gate existed.
        playerProfileRepository.findById(conv.getPlayerId())
            .ifPresent(p -> requireSelfRegisteredPlayerMayChat(p, conv.getCoachId(), role));

        // After verifyIsParty (a non-party cannot burn another user's budget) and before any write or
        // Gemini call. Two buckets: one sender cannot flood, and a conversation cannot be flooded by
        // several accounts.
        enforceSendRateLimit(senderUserId, conversationId);

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
            // Re-check inside the transaction: a concurrent reportConversation() may have committed BLOCKED
            // between the outer check (line ~135, runs outside any transaction) and here
            if (c.getStatus() == ConversationStatus.BLOCKED) {
                throw new OperationNotAllowedException(
                    "Conversation is blocked — no new messages can be sent",
                    MessagingErrorCode.CONVERSATION_BLOCKED);
            }
            Instant now = Instant.now();
            var message = new Message();
            message.setConversationId(conversationId);
            message.setSenderId(senderUserId);
            message.setSenderRole(SenderRole.valueOf(role));
            message.setContent(content);
            message.setModerationStatus(MessageModerationStatus.PENDING);
            message.setCreatedAt(now);
            Message saved = messageRepository.save(message);
            c.setLastMessageAt(now);
            conversationRepository.save(c);
            savedMessageId[0] = saved.getId();
            return null;
        });
        long messageId = savedMessageId[0];

        // S-04: in a conversation involving a minor (any tier where a parent has oversight or is the
        // primary participant), a message from the coach or the player that carries contact details is
        // held for a human BEFORE the model is consulted — the check is deterministic, so it cannot be
        // talked around, and it spends no Gemini call. Held = the existing UNDER_REVIEW state (invisible
        // to the recipient, admin alert raised), the same outcome as an UNCERTAIN verdict, so the sender
        // sees the usual "held for review" UX. A PARENT sender is exempt: sharing a number with the
        // coach is the parent's own decision, and they are the child's guardian.
        if (agePolicy.parentHasAccess() && !"PARENT".equals(role)
                && contactDetailSanitizer.sanitize(content).wasModified()) {
            log.warn("Holding message with contact details in a minor's conversation: messageId={}, "
                + "conversationId={}, senderRole={}", messageId, conversationId, role);
            moderationResultApplier.applyResult(messageId, ModerationVerdict.UNCERTAIN);
        } else {
            moderationService.moderate(messageId, content);
        }
        Message finalMessage = messageRepository.findById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message not found", "message"));
        return toMessageDto(finalMessage, senderUserId);
    }

    /**
     * S-27: a self-registered adult player (profile owned by a user, not a parent) may start or take part in a chat
     * only once age-verified. With verification unavailable the fallback applies instead: they and the coach may
     * message each other only after at least one COMPLETED session between them. Parent-managed players are not
     * affected — a parent is in the loop. The coach-side message names no cause beyond "not verified".
     */
    private void requireSelfRegisteredPlayerMayChat(PlayerProfile player, UUID coachId, String role) {
        if (player.getUserId() == null || ageVerificationService.isVerified(player.getUserId())) {
            return;
        }
        boolean playerSide = "PLAYER".equals(role);
        if (ageVerificationService.isEnabled()) {
            throw playerSide
                ? new AgeVerificationException(AgeVerificationErrorCode.AGE_VERIFICATION_REQUIRED,
                    "Age verification is required before chatting")
                : new AgeVerificationException(AgeVerificationErrorCode.PLAYER_NOT_AGE_VERIFIED,
                    "This player has not completed age verification yet");
        }
        if (!bookingRepository.existsByCoachIdAndPlayerIdAndStatusIn(coachId, player.getId(), List.of("COMPLETED"))) {
            throw new AgeVerificationException(
                playerSide ? AgeVerificationErrorCode.COMPLETED_SESSION_REQUIRED
                           : AgeVerificationErrorCode.PLAYER_NOT_AGE_VERIFIED,
                "Chat opens after a first completed session with this coach");
        }
    }

    public Page<MessageDto> getMessages(Long conversationId, Long callerUserId, String role, Pageable pageable) {
        var conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation not found", "conversation"));

        verifyIsParty(conv, callerUserId, role);

        Page<Message> page = messageRepository.findByConversationIdAndNotDeleted(conversationId, pageable);

        // Mark read: update role's lastReadAt timestamp — but never past a message from someone else
        // that is still PENDING. Its content is hidden from this caller (toMessageDto), so it has not
        // been read; once approved it must still count as unread (countUnread: createdAt > lastReadAt).
        Instant readUpTo = Instant.now();
        Instant oldestHiddenPending = messageRepository.findOldestPendingCreatedAtFromOthers(conversationId, callerUserId);
        if (oldestHiddenPending != null && oldestHiddenPending.minusNanos(1_000).isBefore(readUpTo)) {
            readUpTo = oldestHiddenPending.minusNanos(1_000); // 1 µs: Postgres timestamp precision
        }
        updateLastRead(conv, role, readUpTo);
        conversationRepository.save(conv);

        return page.map(m -> toMessageDto(m, callerUserId));
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
        return page.map(m -> toMessageDto(m, parentUserId));
    }

    @Transactional
    public void softDeleteMessage(Long conversationId, Long messageId, Long callerUserId) {
        // Unlocked read + authorization checks FIRST, locked re-read second. Deliberately in this
        // order: taking a row lock before authorising the caller lets any authenticated user pin an
        // arbitrary message row for the duration of the transaction before receiving their 403 —
        // Deferred-16 D2. Mirrors BookingService.cancelBookingAsParent, which was fixed for the
        // identical reason (its own code comment names this exact finding). One extra SELECT is cheap.
        Message unlocked = messageRepository.findById(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message not found", "message"));
        if (!unlocked.getConversationId().equals(conversationId)) {
            throw new OperationNotAllowedException(
                "Message does not belong to this conversation", MessagingErrorCode.NOT_A_PARTY);
        }
        if (!unlocked.getSenderId().equals(callerUserId)) {
            throw new OperationNotAllowedException(
                "Only the original sender may delete this message", MessagingErrorCode.NOT_A_PARTY);
        }

        // Locked read: the deletedAt check below must run under the row lock so a concurrent
        // double-delete loses cleanly (409) instead of both callers observing null and both
        // committing a 204. Do not add @Version to Message — see Dev Notes: ModerationResultApplier,
        // AdminMessageService and MessageModerationSweeper all save() this row, and optimistic
        // locking would turn their benign interleavings into OptimisticLockingFailureExceptions.
        Message message = messageRepository.findByIdForUpdate(messageId)
            .orElseThrow(() -> new ResourceNotFoundException("Message not found", "message"));
        // The explicit refresh is required, not defensive: findByIdForUpdate is a JPQL query and the
        // same row is already managed from the unlocked findById above, so Hibernate takes the DB
        // lock but returns the existing instance without overwriting its in-memory state. Reading
        // getDeletedAt() off message without this would re-check the same stale (pre-lock) value and
        // a concurrent double-delete could never be caught. Mirrors BookingService.createBookingRequest's
        // identical entityManager.refresh use for the same Hibernate identity-map gotcha.
        entityManager.refresh(message, LockModeType.PESSIMISTIC_WRITE);
        if (message.getModerationStatus() == MessageModerationStatus.UNDER_REVIEW
                || message.getModerationStatus() == MessageModerationStatus.BLOCKED) {
            throw new OperationNotAllowedException(
                "Message under active moderation cannot be deleted",
                MessagingErrorCode.MODERATION_PENDING);
        }
        if (message.getDeletedAt() != null) {
            throw new OperationNotAllowedException(
                "Message is already deleted", MessagingErrorCode.ALREADY_DELETED);
        }
        message.setDeletedAt(Instant.now());
        messageRepository.save(message);
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

    private void enforceSendRateLimit(Long senderUserId, Long conversationId) {
        if (!rateLimitingEnabled) {
            return;
        }
        if (!rateLimitingService.tryConsume(String.valueOf(senderUserId), "messaging.send.sender",
                SEND_LIMIT_PER_SENDER_PER_MINUTE, 1, TimeUnit.MINUTES)
                || !rateLimitingService.tryConsume(String.valueOf(conversationId), "messaging.send.conversation",
                SEND_LIMIT_PER_CONVERSATION_PER_MINUTE, 1, TimeUnit.MINUTES)) {
            log.warn("Message send rate limit exceeded: senderUserId={}, conversationId={}",
                senderUserId, conversationId);
            throw new OperationNotAllowedException(
                "You are sending messages too quickly. Please wait a moment and try again.",
                MessagingErrorCode.RATE_LIMITED);
        }
    }

    /** A locked account (admin suspension locks the user, GDPR erasure too) takes no part in messaging. */
    private void assertAccountNotLocked(Long callerUserId) {
        if (callerUserId != null && userRepository.existsByIdAndLockedTrue(callerUserId)) {
            throw new OperationNotAllowedException(
                "Account is locked", MessagingErrorCode.ACCOUNT_LOCKED);
        }
    }

    private boolean coachIsSuspended(UUID coachId) {
        return coachId != null && coachProfileRepository.findById(coachId)
            .map(c -> c.getStatus() == CoachProfileStatus.SUSPENDED)
            .orElse(false);
    }

    private void verifyIsParty(Conversation conv, Long callerUserId, String role) {
        assertAccountNotLocked(callerUserId);
        // skillars-deferred-91 code review D12: single-identity authorization lookups, NOT a batch
        // "id -> name" read — PlayerProfileService.getPlayerNamesByPlayerIds does not apply here.
        // Each branch resolves the CALLER's own profile by userId to test conversation membership.
        boolean isParty = switch (role) {
            case "COACH" -> {
                var coach = coachProfileRepository.findByUserId(callerUserId);
                // S-04: a suspended coach (admin suspension or safety hold both set SUSPENDED) can neither
                // read nor write. Only the COACH arm: parents and players keep read access to the history.
                if (coach.isPresent() && coach.get().getStatus() == CoachProfileStatus.SUSPENDED) {
                    throw new OperationNotAllowedException(
                        "This coach account is suspended", MessagingErrorCode.COACH_SUSPENDED);
                }
                yield coach.map(c -> Objects.equals(c.getId(), conv.getCoachId())).orElse(false);
            }
            case "PARENT" -> Objects.equals(conv.getParentId(), callerUserId);
            case "PLAYER" -> playerProfileRepository.findByUserId(callerUserId)
                .map(p -> Objects.equals(p.getId(), conv.getPlayerId()))
                .orElse(false);
            // Was IllegalArgumentException, which no @RestControllerAdvice handles — so an
            // unrecognised role produced a 500 with a stack trace instead of the 403 the
            // resolver's own fallback produces (MessagingResource.resolveRole). Latent today,
            // since resolveRole guarantees one of three values, but the guard and the throw
            // live in different classes with no shared enum, so the invariant is convention
            // only. MessagingApiAdvice maps this to 403 messaging.notAParty.
            default -> throw new OperationNotAllowedException(
                "Caller does not hold a recognised messaging role",
                MessagingErrorCode.NOT_A_PARTY);
        };
        if (!isParty) {
            throw new OperationNotAllowedException(
                "Caller is not a party to this conversation",
                MessagingErrorCode.NOT_A_PARTY);
        }
    }

    /**
     * skillars-deferred-90 AC13: everything {@link #toSummary} needs for a whole conversation list,
     * fetched in a fixed number of queries instead of ~4 per conversation.
     */
    private record SummaryContext(
        Map<Long, MessagingPolicy> policyByPlayerId,
        Map<Long, String> playerNameByPlayerId,
        Map<UUID, String> coachNameByCoachId,
        Map<Long, Message> lastApprovedByConversationId,
        Map<Long, Long> unreadByConversationId) {

        String playerName(Long playerId) {
            return playerNameByPlayerId.getOrDefault(playerId, "Unknown Player");
        }

        String coachName(UUID coachId) {
            return coachNameByCoachId.getOrDefault(coachId, "Unknown Coach");
        }
    }

    private SummaryContext buildSummaryContext(List<Conversation> conversations, Long callerUserId, String role) {
        // Code review of skillars-deferred-90: was `resolveLastReadAt(conversations.get(0), role)`,
        // i.e. a getter called purely for its throw. Behaviourally identical (resolveLastReadAt is a
        // pure switch on role) but it would silently stop validating if that method ever gained a
        // non-throwing default, so the intent is now named.
        validateRole(role);

        Set<Long> playerIds = conversations.stream().map(Conversation::getPlayerId).collect(Collectors.toSet());
        Set<UUID> coachIds = conversations.stream().map(Conversation::getCoachId).collect(Collectors.toSet());
        Set<Long> conversationIds = conversations.stream().map(Conversation::getId).collect(Collectors.toSet());

        Map<Long, MessagingPolicy> policyByPlayerId = agePolicyService.findMessagingPoliciesByPlayerIds(playerIds);
        // Null-tolerant on purpose: Collectors.toMap throws NPE on a null VALUE, where the per-row
        // code this replaced degraded to "Unknown Player"/"Unknown Coach" via .orElse(...). Both
        // name columns are NOT NULL today so it is not currently reachable, but a batched read path
        // should not be the thing that turns a bad row into a 500 for the whole conversation list.
        // (Code review, 3-layer run.)
        // skillars-deferred-91 AC19 (completed in the code review, decision D12): routed through the
        // shared method as the AC asked. The blocker was real — getPlayerNamesByPlayerIds used
        // Collectors.toMap, which NPEs on a null name value — so that method was made null-tolerant
        // (skipping null names, not storing them) rather than this call site keeping its own copy.
        Map<Long, String> playerNameByPlayerId = playerProfileService.getPlayerNamesByPlayerIds(playerIds);
        Map<UUID, String> coachNameByCoachId = new java.util.HashMap<>();
        coachProfileRepository.findAllById(coachIds).forEach(cp -> {
            if (cp.getDisplayName() != null) {
                coachNameByCoachId.putIfAbsent(cp.getId(), cp.getDisplayName());
            }
        });
        Map<Long, Message> lastApprovedByConversationId = messageRepository
            .findLatestApprovedPerConversation(conversationIds).stream()
            .collect(Collectors.toMap(Message::getConversationId, m -> m, (a, b) -> a));
        Map<Long, Long> unreadByConversationId = messageRepository
            .countUnreadPerConversation(conversationIds, callerUserId, role).stream()
            .collect(Collectors.toMap(r -> ((Number) r[0]).longValue(), r -> ((Number) r[1]).longValue()));

        return new SummaryContext(policyByPlayerId, playerNameByPlayerId, coachNameByCoachId,
            lastApprovedByConversationId, unreadByConversationId);
    }

    private ConversationSummaryDto toSummary(Conversation conv, Long callerUserId, String role) {
        return toSummary(conv, callerUserId, role, buildSummaryContext(List.of(conv), callerUserId, role));
    }

    private ConversationSummaryDto toSummary(Conversation conv, Long callerUserId, String role, SummaryContext ctx) {
        String otherPartyName = resolveOtherPartyName(conv, role, ctx);

        String lastMessagePreview = null;
        Message lastApproved = ctx.lastApprovedByConversationId().get(conv.getId());
        if (lastApproved != null) {
            String msgContent = lastApproved.getContent();
            lastMessagePreview = msgContent.length() > 60 ? msgContent.substring(0, 60) : msgContent;
        }

        long unreadCount = ctx.unreadByConversationId().getOrDefault(conv.getId(), 0L);

        MessagingPolicy policy = ctx.policyByPlayerId().get(conv.getPlayerId());
        AgeMessagingPolicy agePolicy = policy == null ? null : AgeMessagingPolicy.from(policy);

        return new ConversationSummaryDto(
            conv.getId(),
            otherPartyName,
            null,
            lastMessagePreview,
            conv.getLastMessageAt(),
            unreadCount,
            conv.getStatus().name(),
            canSend(conv, role, agePolicy),
            "PARENT".equals(role) && agePolicy == AgeMessagingPolicy.SUPERVISED
        );
    }

    /**
     * UI hint mirroring the sendMessage gates (BLOCKED, then the story 8.2 age-tier table). An
     * unresolvable policy (orphaned player_profiles row) degrades to "cannot send" — sendMessage
     * itself would 404 on getMessagingPolicy for the same row.
     */
    private static boolean canSend(Conversation conv, String role, AgeMessagingPolicy agePolicy) {
        if (conv.getStatus() == ConversationStatus.BLOCKED || agePolicy == null) {
            return false;
        }
        if ("PLAYER".equals(role)) {
            return !agePolicy.playerIsBlocked();
        }
        if ("PARENT".equals(role)) {
            return !agePolicy.parentIsBlocked();
        }
        return true;
    }

    private String resolveOtherPartyName(Conversation conv, String role, SummaryContext ctx) {
        if ("COACH".equals(role)) {
            String playerFirstName = firstName(ctx.playerName(conv.getPlayerId()));
            MessagingPolicy policy = ctx.policyByPlayerId().get(conv.getPlayerId());
            if (policy == null) {
                log.error("resolveOtherPartyName: no player profile for playerId={} (conversationId={}) "
                        + "— falling back to Unknown Player label", conv.getPlayerId(), conv.getId());
                return "Unknown Player";
            }
            AgeMessagingPolicy agePolicy = AgeMessagingPolicy.from(policy);
            return switch (agePolicy) {
                case PROHIBITED, PARENT_MANAGED -> "Parent of " + playerFirstName;
                case SUPERVISED -> playerFirstName + " & parent";
                case UNRESTRICTED -> playerFirstName;
            };
        } else if ("PARENT".equals(role)) {
            MessagingPolicy policy = ctx.policyByPlayerId().get(conv.getPlayerId());
            if (policy == null) {
                log.error("resolveOtherPartyName: no player profile for playerId={} (conversationId={}) "
                        + "— falling back to Unknown Player label", conv.getPlayerId(), conv.getId());
                return "Unknown Player";
            }
            AgeMessagingPolicy agePolicy = AgeMessagingPolicy.from(policy);
            return switch (agePolicy) {
                case PROHIBITED, PARENT_MANAGED -> ctx.coachName(conv.getCoachId());
                case SUPERVISED -> firstName(ctx.playerName(conv.getPlayerId()))
                    + "'s conversation with " + ctx.coachName(conv.getCoachId());
                case UNRESTRICTED ->
                    // initiateConversation() gates this path; safe fallback if reached unexpectedly
                    firstName(ctx.playerName(conv.getPlayerId()));
            };
        } else {
            // PLAYER role: show coach name
            return ctx.coachName(conv.getCoachId());
        }
    }

    private static String firstName(String fullName) {
        return fullName.contains(" ") ? fullName.substring(0, fullName.indexOf(' ')) : fullName;
    }

    /**
     * Rejects a caller whose role is not a recognised messaging role. Same contract (and same
     * exception) as the per-row {@link #resolveLastReadAt} call this replaced in the batched
     * summary path — see verifyIsParty for why it is OperationNotAllowedException.
     */
    private void validateRole(String role) {
        if (!"COACH".equals(role) && !"PARENT".equals(role) && !"PLAYER".equals(role)) {
            throw new OperationNotAllowedException(
                "Caller does not hold a recognised messaging role",
                MessagingErrorCode.NOT_A_PARTY);
        }
    }

    private Instant resolveLastReadAt(Conversation conv, String role) {
        return switch (role) {
            case "COACH" -> conv.getCoachLastReadAt();
            case "PARENT" -> conv.getParentLastReadAt();
            case "PLAYER" -> conv.getPlayerLastReadAt();
            // See verifyIsParty for why this is OperationNotAllowedException and not
            // IllegalArgumentException.
            default -> throw new OperationNotAllowedException(
                "Caller does not hold a recognised messaging role",
                MessagingErrorCode.NOT_A_PARTY);
        };
    }

    private void updateLastRead(Conversation conv, String role, Instant now) {
        switch (role) {
            case "COACH" -> conv.setCoachLastReadAt(now);
            case "PARENT" -> conv.setParentLastReadAt(now);
            case "PLAYER" -> conv.setPlayerLastReadAt(now);
            // See verifyIsParty for why this is OperationNotAllowedException and not
            // IllegalArgumentException.
            default -> throw new OperationNotAllowedException(
                "Caller does not hold a recognised messaging role",
                MessagingErrorCode.NOT_A_PARTY);
        }
    }

    /**
     * Content visibility by moderation status and viewer. BLOCKED and UNDER_REVIEW are hidden from
     * everyone. PENDING (moderation verdict not landed yet, and up to
     * {@code MessageModerationSweeper}'s grace window if it never does) is shown ONLY to its own
     * sender: no moderation pass has seen it, and a recipient may be a minor. The recipient still
     * gets the row, with null content and status PENDING, so the thread order is stable and the
     * client can render a "held for review" placeholder. The SSE path needs no equivalent: it only
     * ever pushes ids, and NEW_MESSAGE fires only after an APPROVED verdict commits.
     */
    static MessageDto toMessageDto(Message msg, Long viewerUserId) {
        MessageModerationStatus status = msg.getModerationStatus();
        boolean contentHidden = status == MessageModerationStatus.BLOCKED
            || status == MessageModerationStatus.UNDER_REVIEW
            || (status == MessageModerationStatus.PENDING && !Objects.equals(msg.getSenderId(), viewerUserId));
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
