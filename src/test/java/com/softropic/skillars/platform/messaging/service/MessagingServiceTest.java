package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import com.softropic.skillars.infrastructure.security.RateLimitingService;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.messaging.contract.MessagingErrorCode;
import com.softropic.skillars.platform.messaging.contract.ModerationService;
import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;
import com.softropic.skillars.platform.messaging.repo.Conversation;
import com.softropic.skillars.platform.messaging.repo.ConversationRepository;
import com.softropic.skillars.platform.messaging.repo.Message;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import com.softropic.skillars.platform.security.contract.MessagingPolicy;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import com.softropic.skillars.platform.security.service.AgePolicyService;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessagingServiceTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private PlayerProfileRepository playerProfileRepository;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private ModerationService moderationService;
    @Mock private MessagingEmitterRegistry messagingEmitterRegistry;
    @Mock private ConversationCreationHelper conversationCreationHelper;
    @Mock private AgePolicyService agePolicyService;
    @Mock private com.softropic.skillars.platform.marketplace.service.PlayerProfileService playerProfileService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private EntityManager entityManager;
    @Mock private ModerationResultApplier moderationResultApplier;
    @Mock private RateLimitingService rateLimitingService;
    @Mock private UserRepository userRepository;

    private MessagingService messagingService;

    private static final Long CONVERSATION_ID = 1L;
    private static final Long MESSAGE_ID = 2L;
    private static final Long SENDER_ID = 100L;

    @BeforeEach
    void setUp() {
        messagingService = new MessagingService(
            conversationRepository, messageRepository, bookingRepository,
            playerProfileRepository, coachProfileRepository, moderationService,
            messagingEmitterRegistry, conversationCreationHelper, agePolicyService,
            playerProfileService, transactionTemplate, entityManager,
            moderationResultApplier, new ContactDetailSanitizer(), rateLimitingService, userRepository
        );
    }

    private Message makeMessage(Long conversationId, Long senderId) {
        Message message = new Message();
        message.setId(MESSAGE_ID);
        message.setConversationId(conversationId);
        message.setSenderId(senderId);
        return message;
    }

    private static Conversation conv(Long id, UUID coachId, Long playerId) {
        Conversation c = new Conversation();
        c.setId(id);
        c.setCoachId(coachId);
        c.setPlayerId(playerId);
        return c;
    }

    private static PlayerProfile player(Long id, String name) {
        PlayerProfile p = new PlayerProfile();
        p.setId(id);
        p.setName(name);
        return p;
    }

    // skillars-deferred-90 AC13: getConversations must issue a FIXED number of queries regardless of
    // how many conversations the caller has — never the per-row findLastApproved / countUnread /
    // findMessagingPolicy it used to.
    @Test
    void getConversations_coach_batchesEveryPerRowLookup() {
        Long coachUserId = 500L;
        UUID coachId = UUID.randomUUID();
        CoachProfile coach = new CoachProfile();
        coach.setId(coachId);
        coach.setDisplayName("Coach Carter");
        when(coachProfileRepository.findByUserId(coachUserId)).thenReturn(Optional.of(coach));
        when(conversationRepository.findActiveByCoachId(coachId))
            .thenReturn(List.of(conv(1L, coachId, 10L), conv(2L, coachId, 11L), conv(3L, coachId, 12L)));

        when(agePolicyService.findMessagingPoliciesByPlayerIds(any())).thenReturn(Map.of(
            10L, MessagingPolicy.unrestricted(), 11L, MessagingPolicy.unrestricted(), 12L, MessagingPolicy.unrestricted()));
        // skillars-deferred-91 code review D12: names now come from the shared batch method rather
        // than an inline playerProfileRepository.findAllById copy (AC19's actual instruction).
        when(playerProfileService.getPlayerNamesByPlayerIds(any()))
            .thenReturn(Map.of(10L, "Ann A", 11L, "Bob B", 12L, "Cy C"));
        when(coachProfileRepository.findAllById(any())).thenReturn(List.of(coach));
        when(messageRepository.findLatestApprovedPerConversation(any())).thenReturn(List.of());
        when(messageRepository.countUnreadPerConversation(any(), eq(coachUserId), eq("COACH"))).thenReturn(List.of());

        assertThat(messagingService.getConversations(coachUserId, "COACH")).hasSize(3);

        verify(messageRepository, times(1)).findLatestApprovedPerConversation(any());
        verify(messageRepository, never()).findLastApproved(any(), any());
        verify(messageRepository, times(1)).countUnreadPerConversation(any(), any(), any());
        verify(messageRepository, never()).countUnread(anyLong(), anyLong(), any());
        verify(agePolicyService, never()).findMessagingPolicy(any());
        verify(playerProfileService, times(1)).getPlayerNamesByPlayerIds(any());
        verify(playerProfileRepository, never()).findAllById(any());
        verify(playerProfileRepository, never()).findById(any());
        verify(coachProfileRepository, times(1)).findAllById(any());
        verify(coachProfileRepository, never()).findById(any());
    }

    @Test
    void getConversations_parent_batchesFilterAndSummaryLookups() {
        Long parentUserId = 600L;
        UUID coachId = UUID.randomUUID();
        when(conversationRepository.findActiveByParentId(parentUserId))
            .thenReturn(List.of(conv(1L, coachId, 20L), conv(2L, coachId, 21L)));

        // parentManaged() → parentHasAccess() true → both conversations survive the filter
        when(agePolicyService.findMessagingPoliciesByPlayerIds(any())).thenReturn(Map.of(
            20L, MessagingPolicy.parentManaged(), 21L, MessagingPolicy.parentManaged()));
        when(playerProfileService.getPlayerNamesByPlayerIds(any())).thenReturn(Map.of(20L, "Dee", 21L, "Eve"));
        CoachProfile coach = new CoachProfile();
        coach.setId(coachId);
        coach.setDisplayName("Coach K");
        when(coachProfileRepository.findAllById(any())).thenReturn(List.of(coach));
        when(messageRepository.findLatestApprovedPerConversation(any())).thenReturn(List.of());
        when(messageRepository.countUnreadPerConversation(any(), eq(parentUserId), eq("PARENT"))).thenReturn(List.of());

        assertThat(messagingService.getConversations(parentUserId, "PARENT")).hasSize(2);

        // The pre-filter policy lookup and toSummary's are BOTH batched — never per-row.
        verify(agePolicyService, never()).findMessagingPolicy(any());
        verify(messageRepository, times(1)).findLatestApprovedPerConversation(any());
        verify(messageRepository, never()).findLastApproved(any(), any());
        verify(messageRepository, times(1)).countUnreadPerConversation(any(), any(), any());
        verify(playerProfileRepository, never()).findById(any());
        verify(coachProfileRepository, never()).findById(any());
    }

    // Deferred-16 D2 / Deferred-33 AC1: the conversation-membership check must run BEFORE the row
    // lock is taken, so a stranger cannot pin an arbitrary message row for the length of the
    // transaction on their way to a 403. Proven by the locked read never being reached.
    @Test
    void softDeleteMessage_wrongConversation_isRejectedBeforeTakingTheRowLock() {
        Message message = makeMessage(999L, SENDER_ID);
        when(messageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(message));

        assertThatThrownBy(() -> messagingService.softDeleteMessage(CONVERSATION_ID, MESSAGE_ID, SENDER_ID))
            .isInstanceOf(OperationNotAllowedException.class);

        verify(messageRepository, never()).findByIdForUpdate(any());
    }

    // Deferred-16 D2 / Deferred-33 AC1: same reasoning as above, for the sender-ownership check.
    @Test
    void softDeleteMessage_wrongSender_isRejectedBeforeTakingTheRowLock() {
        Message message = makeMessage(CONVERSATION_ID, SENDER_ID);
        when(messageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(message));

        assertThatThrownBy(() -> messagingService.softDeleteMessage(CONVERSATION_ID, MESSAGE_ID, 999_999L))
            .isInstanceOf(OperationNotAllowedException.class);

        verify(messageRepository, never()).findByIdForUpdate(any());
    }

    // ---- S-04: contact details in minors' conversations, suspension, locks, rate limit ----

    private static final String PHONE_MESSAGE = "call me on 0791 112 3456";
    private static final Long COACH_USER_ID = 300L;
    private static final Long PLAYER_ID = 40L;
    private static final Long PARENT_USER_ID = 600L;
    private final UUID coachId = UUID.randomUUID();

    private CoachProfile coachProfile(CoachProfileStatus status) {
        CoachProfile coach = new CoachProfile();
        coach.setId(coachId);
        coach.setUserId(COACH_USER_ID);
        coach.setStatus(status);
        return coach;
    }

    private void arrangeSend(MessagingPolicy policy, CoachProfileStatus coachStatus) {
        Conversation c = conv(CONVERSATION_ID, coachId, PLAYER_ID);
        c.setParentId(PARENT_USER_ID);
        c.setStatus(com.softropic.skillars.platform.messaging.contract.ConversationStatus.ACTIVE);
        when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(c));
        lenient().when(coachProfileRepository.findByUserId(COACH_USER_ID))
            .thenReturn(Optional.of(coachProfile(coachStatus)));
        lenient().when(agePolicyService.getMessagingPolicy(PLAYER_ID)).thenReturn(policy);
        Message saved = new Message();
        saved.setId(0L);
        saved.setSenderId(COACH_USER_ID);
        saved.setSenderRole(com.softropic.skillars.platform.messaging.contract.SenderRole.COACH);
        saved.setModerationStatus(com.softropic.skillars.platform.messaging.contract.MessageModerationStatus.UNDER_REVIEW);
        lenient().when(messageRepository.findById(0L)).thenReturn(Optional.of(saved));
    }

    @Test
    void sendMessage_coachSharesPhoneInSupervisedConversation_isHeldWithoutCallingGemini() {
        arrangeSend(MessagingPolicy.supervised(), CoachProfileStatus.ACTIVE);

        messagingService.sendMessage(CONVERSATION_ID, PHONE_MESSAGE, COACH_USER_ID, "COACH");

        verify(moderationResultApplier).applyResult(0L, ModerationVerdict.UNCERTAIN);
        verify(moderationService, never()).moderate(anyLong(), any());
    }

    @Test
    void sendMessage_coachSharesPhoneInParentManagedConversation_isHeld() {
        arrangeSend(MessagingPolicy.parentManaged(), CoachProfileStatus.ACTIVE);

        messagingService.sendMessage(CONVERSATION_ID, "my whatsapp: coach_john", COACH_USER_ID, "COACH");

        verify(moderationResultApplier).applyResult(0L, ModerationVerdict.UNCERTAIN);
        verify(moderationService, never()).moderate(anyLong(), any());
    }

    @Test
    void sendMessage_cleanMessageInSupervisedConversation_goesToNormalModeration() {
        arrangeSend(MessagingPolicy.supervised(), CoachProfileStatus.ACTIVE);

        messagingService.sendMessage(CONVERSATION_ID, "See you at 5 pm, score was 3-2", COACH_USER_ID, "COACH");

        verify(moderationService).moderate(0L, "See you at 5 pm, score was 3-2");
        verify(moderationResultApplier, never()).applyResult(anyLong(), any());
    }

    @Test
    void sendMessage_phoneInAdultConversation_isNotHeldByTheDeterministicCheck() {
        arrangeSend(MessagingPolicy.unrestricted(), CoachProfileStatus.ACTIVE);

        messagingService.sendMessage(CONVERSATION_ID, PHONE_MESSAGE, COACH_USER_ID, "COACH");

        verify(moderationService).moderate(0L, PHONE_MESSAGE);
        verify(moderationResultApplier, never()).applyResult(anyLong(), any());
    }

    @Test
    void sendMessage_parentSharesOwnNumber_isNotHeldByTheDeterministicCheck() {
        arrangeSend(MessagingPolicy.supervised(), CoachProfileStatus.ACTIVE);
        Message saved = new Message();
        saved.setId(0L);
        saved.setSenderId(PARENT_USER_ID);
        saved.setSenderRole(com.softropic.skillars.platform.messaging.contract.SenderRole.PARENT);
        saved.setModerationStatus(com.softropic.skillars.platform.messaging.contract.MessageModerationStatus.APPROVED);
        when(messageRepository.findById(0L)).thenReturn(Optional.of(saved));

        messagingService.sendMessage(CONVERSATION_ID, PHONE_MESSAGE, PARENT_USER_ID, "PARENT");

        verify(moderationService).moderate(0L, PHONE_MESSAGE);
    }

    @Test
    void sendMessage_suspendedCoach_isRefusedBeforeAnythingIsSaved() {
        arrangeSend(MessagingPolicy.supervised(), CoachProfileStatus.SUSPENDED);

        assertThatThrownBy(() -> messagingService.sendMessage(CONVERSATION_ID, "hello", COACH_USER_ID, "COACH"))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(MessagingErrorCode.COACH_SUSPENDED));

        verify(transactionTemplate, never()).execute(any());
        verify(moderationService, never()).moderate(anyLong(), any());
    }

    @Test
    void getMessages_suspendedCoach_isRefused() {
        Conversation c = conv(CONVERSATION_ID, coachId, PLAYER_ID);
        when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(c));
        when(coachProfileRepository.findByUserId(COACH_USER_ID))
            .thenReturn(Optional.of(coachProfile(CoachProfileStatus.SUSPENDED)));

        assertThatThrownBy(() -> messagingService.getMessages(CONVERSATION_ID, COACH_USER_ID, "COACH",
                org.springframework.data.domain.PageRequest.of(0, 20)))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(MessagingErrorCode.COACH_SUSPENDED));

        verify(messageRepository, never()).findByConversationIdAndNotDeleted(any(), any());
    }

    @Test
    void getMessages_parentOfConversationWithSuspendedCoach_stillReadsHistory() {
        Conversation c = conv(CONVERSATION_ID, coachId, PLAYER_ID);
        c.setParentId(PARENT_USER_ID);
        when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(c));
        when(messageRepository.findByConversationIdAndNotDeleted(any(), any()))
            .thenReturn(org.springframework.data.domain.Page.empty());

        assertThat(messagingService.getMessages(CONVERSATION_ID, PARENT_USER_ID, "PARENT",
            org.springframework.data.domain.PageRequest.of(0, 20))).isEmpty();
    }

    @Test
    void getMessages_lockedUser_isRefused() {
        Conversation c = conv(CONVERSATION_ID, coachId, PLAYER_ID);
        c.setParentId(PARENT_USER_ID);
        when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(c));
        when(userRepository.existsByIdAndLockedTrue(PARENT_USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> messagingService.getMessages(CONVERSATION_ID, PARENT_USER_ID, "PARENT",
                org.springframework.data.domain.PageRequest.of(0, 20)))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(MessagingErrorCode.ACCOUNT_LOCKED));
    }

    @Test
    void initiateConversation_suspendedCoachCaller_isRefused() {
        when(coachProfileRepository.findById(coachId))
            .thenReturn(Optional.of(coachProfile(CoachProfileStatus.SUSPENDED)));

        assertThatThrownBy(() -> messagingService.initiateConversation(coachId, PLAYER_ID, COACH_USER_ID, "COACH"))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(MessagingErrorCode.COACH_SUSPENDED));

        verify(conversationCreationHelper, never()).tryCreate(any(), any(), any());
    }

    @Test
    void initiateConversation_parentWithSuspendedCoachAndNoExistingThread_isRefused() {
        when(coachProfileRepository.findById(coachId))
            .thenReturn(Optional.of(coachProfile(CoachProfileStatus.SUSPENDED)));
        when(conversationRepository.findByCoachIdAndPlayerId(coachId, PLAYER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> messagingService.initiateConversation(coachId, PLAYER_ID, PARENT_USER_ID, "PARENT"))
            .isInstanceOf(OperationNotAllowedException.class);

        verify(conversationCreationHelper, never()).tryCreate(any(), any(), any());
    }

    @Test
    void sendMessage_overTheSenderRateLimit_isRefusedBeforeAnythingIsSaved() {
        ReflectionTestUtils.setField(messagingService, "rateLimitingEnabled", true);
        arrangeSend(MessagingPolicy.supervised(), CoachProfileStatus.ACTIVE);
        when(rateLimitingService.tryConsume(eq(String.valueOf(COACH_USER_ID)), eq("messaging.send.sender"),
            anyLong(), anyLong(), any())).thenReturn(false);

        assertThatThrownBy(() -> messagingService.sendMessage(CONVERSATION_ID, "hello", COACH_USER_ID, "COACH"))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(MessagingErrorCode.RATE_LIMITED));

        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void sendMessage_overTheConversationRateLimit_isRefused() {
        ReflectionTestUtils.setField(messagingService, "rateLimitingEnabled", true);
        arrangeSend(MessagingPolicy.supervised(), CoachProfileStatus.ACTIVE);
        when(rateLimitingService.tryConsume(any(), eq("messaging.send.sender"), anyLong(), anyLong(), any()))
            .thenReturn(true);
        when(rateLimitingService.tryConsume(eq(String.valueOf(CONVERSATION_ID)), eq("messaging.send.conversation"),
            anyLong(), anyLong(), any())).thenReturn(false);

        assertThatThrownBy(() -> messagingService.sendMessage(CONVERSATION_ID, "hello", COACH_USER_ID, "COACH"))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(MessagingErrorCode.RATE_LIMITED));
    }
}
