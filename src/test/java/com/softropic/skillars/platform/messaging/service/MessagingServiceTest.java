package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.messaging.contract.ModerationService;
import com.softropic.skillars.platform.messaging.repo.Conversation;
import com.softropic.skillars.platform.messaging.repo.ConversationRepository;
import com.softropic.skillars.platform.messaging.repo.Message;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import com.softropic.skillars.platform.security.contract.MessagingPolicy;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.AgePolicyService;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private EntityManager entityManager;

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
            transactionTemplate, entityManager
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
        when(playerProfileRepository.findAllById(any()))
            .thenReturn(List.of(player(10L, "Ann A"), player(11L, "Bob B"), player(12L, "Cy C")));
        when(coachProfileRepository.findAllById(any())).thenReturn(List.of(coach));
        when(messageRepository.findLatestApprovedPerConversation(any())).thenReturn(List.of());
        when(messageRepository.countUnreadPerConversation(any(), eq(coachUserId), eq("COACH"))).thenReturn(List.of());

        assertThat(messagingService.getConversations(coachUserId, "COACH")).hasSize(3);

        verify(messageRepository, times(1)).findLatestApprovedPerConversation(any());
        verify(messageRepository, never()).findLastApproved(any(), any());
        verify(messageRepository, times(1)).countUnreadPerConversation(any(), any(), any());
        verify(messageRepository, never()).countUnread(anyLong(), anyLong(), any());
        verify(agePolicyService, never()).findMessagingPolicy(any());
        verify(playerProfileRepository, times(1)).findAllById(any());
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
        when(playerProfileRepository.findAllById(any())).thenReturn(List.of(player(20L, "Dee"), player(21L, "Eve")));
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
}
