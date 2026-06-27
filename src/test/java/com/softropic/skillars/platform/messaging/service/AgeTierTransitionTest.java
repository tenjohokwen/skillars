package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.messaging.contract.ConversationSummaryDto;
import com.softropic.skillars.platform.messaging.contract.MessageModerationStatus;
import com.softropic.skillars.platform.messaging.contract.MessagingErrorCode;
import com.softropic.skillars.platform.messaging.contract.ModerationResult;
import com.softropic.skillars.platform.messaging.contract.ModerationService;
import com.softropic.skillars.platform.messaging.contract.SenderRole;
import com.softropic.skillars.platform.messaging.repo.Conversation;
import com.softropic.skillars.platform.messaging.repo.ConversationRepository;
import com.softropic.skillars.platform.messaging.repo.Message;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import com.softropic.skillars.platform.security.contract.MessagingPolicy;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.AgePolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgeTierTransitionTest {

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

    @InjectMocks
    private MessagingService messagingService;

    private static final Long PLAYER_ID = 1001L;
    private static final Long PARENT_ID = 1002L;
    private static final Long COACH_USER_ID = 1003L;
    private static final UUID COACH_PROFILE_ID = UUID.randomUUID();
    private static final Long CONVERSATION_ID = 2001L;

    private Conversation conversation;

    @BeforeEach
    void setUp() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });

        conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setCoachId(COACH_PROFILE_ID);
        conversation.setPlayerId(PLAYER_ID);
        conversation.setParentId(PARENT_ID);
    }

    // ── sendMessage: PLAYER role blocked ──

    @Test
    void sendMessage_playerRole_parentManagedPolicy_throws403PlayerRestricted() {
        when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(agePolicyService.getMessagingPolicy(PLAYER_ID)).thenReturn(MessagingPolicy.parentManaged());

        assertThatThrownBy(() ->
            messagingService.sendMessage(CONVERSATION_ID, "Hello", PLAYER_ID, "PLAYER"))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode().getErrorCode())
                .isEqualTo(MessagingErrorCode.PLAYER_DIRECT_MESSAGING_RESTRICTED.getErrorCode()));
    }

    @Test
    void sendMessage_playerRole_prohibitedPolicy_throws403PlayerRestricted() {
        when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(agePolicyService.getMessagingPolicy(PLAYER_ID)).thenReturn(MessagingPolicy.prohibited());

        assertThatThrownBy(() ->
            messagingService.sendMessage(CONVERSATION_ID, "Hello", PLAYER_ID, "PLAYER"))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode().getErrorCode())
                .isEqualTo(MessagingErrorCode.PLAYER_DIRECT_MESSAGING_RESTRICTED.getErrorCode()));
    }

    @Test
    void sendMessage_playerRole_supervisedPolicy_succeeds() {
        when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(agePolicyService.getMessagingPolicy(PLAYER_ID)).thenReturn(MessagingPolicy.supervised());
        Message savedMsg = stubMessage(SenderRole.PLAYER, 999L);
        when(messageRepository.save(any())).thenReturn(savedMsg);
        when(conversationRepository.save(any())).thenReturn(conversation);
        when(moderationService.moderate(anyLong(), any()))
            .thenReturn(new ModerationResult(MessageModerationStatus.APPROVED, Instant.now()));
        when(messageRepository.findById(999L)).thenReturn(Optional.of(savedMsg));

        messagingService.sendMessage(CONVERSATION_ID, "Hello", PLAYER_ID, "PLAYER");
        // No exception = success
    }

    // ── sendMessage: PARENT role blocked ──

    @Test
    void sendMessage_parentRole_unrestrictedPolicy_throws403ParentRestricted() {
        when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(agePolicyService.getMessagingPolicy(PLAYER_ID)).thenReturn(MessagingPolicy.unrestricted());

        assertThatThrownBy(() ->
            messagingService.sendMessage(CONVERSATION_ID, "Hello", PARENT_ID, "PARENT"))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode().getErrorCode())
                .isEqualTo(MessagingErrorCode.PARENT_MESSAGING_RESTRICTED_FOR_ADULT.getErrorCode()));
    }

    @Test
    void sendMessage_parentRole_supervisedPolicy_succeeds() {
        when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(agePolicyService.getMessagingPolicy(PLAYER_ID)).thenReturn(MessagingPolicy.supervised());
        Message savedMsg = stubMessage(SenderRole.PARENT, 999L);
        when(messageRepository.save(any())).thenReturn(savedMsg);
        when(conversationRepository.save(any())).thenReturn(conversation);
        when(moderationService.moderate(anyLong(), any()))
            .thenReturn(new ModerationResult(MessageModerationStatus.APPROVED, Instant.now()));
        when(messageRepository.findById(999L)).thenReturn(Optional.of(savedMsg));

        messagingService.sendMessage(CONVERSATION_ID, "Hello", PARENT_ID, "PARENT");
        // No exception = success
    }

    // ── getConversations: PLAYER role age visibility ──

    @Test
    void getConversations_playerRole_prohibitedPolicy_returnsEmptyList() {
        when(agePolicyService.getMessagingPolicy(PLAYER_ID)).thenReturn(MessagingPolicy.prohibited());

        List<ConversationSummaryDto> result = messagingService.getConversations(PLAYER_ID, "PLAYER");

        assertThat(result).isEmpty();
    }

    // ── getConversations: PARENT role excludes UNRESTRICTED conversations ──

    @Test
    void getConversations_parentRole_unrestrictedConversationExcluded() {
        when(conversationRepository.findActiveByParentId(PARENT_ID)).thenReturn(List.of(conversation));
        when(agePolicyService.getMessagingPolicy(PLAYER_ID)).thenReturn(MessagingPolicy.unrestricted());

        List<ConversationSummaryDto> result = messagingService.getConversations(PARENT_ID, "PARENT");

        assertThat(result).isEmpty();
    }

    // ── initiateConversation: PARENT age gate (Finding 2) ──

    @Test
    void initiateConversation_parentRole_adultPlayer_throws403OversightNotApplicable() {
        when(bookingRepository.existsByCoachIdAndPlayerIdAndStatusIn(any(), any(), any())).thenReturn(true);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(mock(PlayerProfile.class)));
        when(agePolicyService.getMessagingPolicy(PLAYER_ID)).thenReturn(MessagingPolicy.unrestricted());

        assertThatThrownBy(() ->
            messagingService.initiateConversation(COACH_PROFILE_ID, PLAYER_ID, PARENT_ID, "PARENT"))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode().getErrorCode())
                .isEqualTo(MessagingErrorCode.PARENTAL_OVERSIGHT_NOT_APPLICABLE.getErrorCode()));
    }

    // ── getConversationsForPlayer: ownership and age guards (Finding 10) ──

    @Test
    void getConversationsForPlayer_playerNotOwnedByParent_throws403NotAParty() {
        when(playerProfileRepository.findByIdAndParentId(PLAYER_ID, PARENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            messagingService.getConversationsForPlayer(PLAYER_ID, PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode().getErrorCode())
                .isEqualTo(MessagingErrorCode.NOT_A_PARTY.getErrorCode()));
    }

    @Test
    void getConversationsForPlayer_adultPlayer_throws403OversightNotApplicable() {
        when(playerProfileRepository.findByIdAndParentId(PLAYER_ID, PARENT_ID))
            .thenReturn(Optional.of(mock(PlayerProfile.class)));
        when(agePolicyService.getMessagingPolicy(PLAYER_ID)).thenReturn(MessagingPolicy.unrestricted());

        assertThatThrownBy(() ->
            messagingService.getConversationsForPlayer(PLAYER_ID, PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode().getErrorCode())
                .isEqualTo(MessagingErrorCode.PARENTAL_OVERSIGHT_NOT_APPLICABLE.getErrorCode()));
    }

    // ── getMessagesForPlayerConversation: ownership and conversation guards (Finding 10) ──

    @Test
    void getMessagesForPlayerConversation_playerNotOwnedByParent_throws403NotAParty() {
        when(playerProfileRepository.findByIdAndParentId(PLAYER_ID, PARENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            messagingService.getMessagesForPlayerConversation(PLAYER_ID, CONVERSATION_ID, PARENT_ID, Pageable.unpaged()))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode().getErrorCode())
                .isEqualTo(MessagingErrorCode.NOT_A_PARTY.getErrorCode()));
    }

    @Test
    void getMessagesForPlayerConversation_conversationBelongsToDifferentPlayer_throws403NotAParty() {
        when(playerProfileRepository.findByIdAndParentId(PLAYER_ID, PARENT_ID))
            .thenReturn(Optional.of(mock(PlayerProfile.class)));
        when(agePolicyService.getMessagingPolicy(PLAYER_ID)).thenReturn(MessagingPolicy.supervised());
        Conversation otherConv = new Conversation();
        otherConv.setId(CONVERSATION_ID);
        otherConv.setPlayerId(9999L);
        when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(otherConv));

        assertThatThrownBy(() ->
            messagingService.getMessagesForPlayerConversation(PLAYER_ID, CONVERSATION_ID, PARENT_ID, Pageable.unpaged()))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode().getErrorCode())
                .isEqualTo(MessagingErrorCode.NOT_A_PARTY.getErrorCode()));
    }

    // ── toMessageDto: UNDER_REVIEW content suppression ──

    @Test
    void sendMessage_underReviewModeration_returnsNullContent() {
        CoachProfile coachProfile = mock(CoachProfile.class);
        when(coachProfile.getId()).thenReturn(COACH_PROFILE_ID);
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coachProfile));
        when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
        when(agePolicyService.getMessagingPolicy(PLAYER_ID)).thenReturn(MessagingPolicy.unrestricted());
        Message savedMsg = stubMessage(SenderRole.COACH, 995L);
        savedMsg.setModerationStatus(MessageModerationStatus.UNDER_REVIEW);
        savedMsg.setContent(null);
        when(messageRepository.save(any())).thenReturn(savedMsg);
        when(conversationRepository.save(any())).thenReturn(conversation);
        when(moderationService.moderate(anyLong(), any()))
            .thenReturn(new ModerationResult(MessageModerationStatus.UNDER_REVIEW, null));
        when(messageRepository.findById(995L)).thenReturn(Optional.of(savedMsg));

        com.softropic.skillars.platform.messaging.contract.MessageDto result =
            messagingService.sendMessage(CONVERSATION_ID, "Flagged content", COACH_USER_ID, "COACH");

        assertThat(result.content()).isNull();
        assertThat(result.moderationStatus()).isEqualTo("UNDER_REVIEW");
    }

    private Message stubMessage(SenderRole role, Long id) {
        Message m = new Message();
        m.setId(id);
        m.setSenderRole(role);
        m.setContent("test");
        m.setModerationStatus(MessageModerationStatus.APPROVED);
        m.setCreatedAt(Instant.now());
        return m;
    }
}
