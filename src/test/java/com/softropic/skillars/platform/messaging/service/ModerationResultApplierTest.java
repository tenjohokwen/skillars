package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.messaging.contract.MessageModerationStatus;
import com.softropic.skillars.platform.messaging.contract.ModerationResult;
import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;
import com.softropic.skillars.platform.messaging.repo.Conversation;
import com.softropic.skillars.platform.messaging.repo.Message;
import com.softropic.skillars.platform.messaging.repo.ConversationRepository;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import com.softropic.skillars.platform.security.contract.MessagingPolicy;
import com.softropic.skillars.platform.security.service.AgePolicyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModerationResultApplierTest {

    @Mock private MessageRepository messageRepository;
    @Mock private ConversationRepository conversationRepository;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private AgePolicyService agePolicyService;
    @Mock private MessagingEmitterRegistry messagingEmitterRegistry;
    @Mock private ApplicationEventPublisher publisher;

    @InjectMocks
    private ModerationResultApplier applier;

    private static final Long PLAYER_ID = 1001L;
    private static final Long PARENT_ID = 1002L;
    private static final Long COACH_USER_ID = 1003L;
    private static final UUID COACH_UUID = UUID.randomUUID();

    private Message message;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();

        message = new Message();
        message.setId(1L);
        message.setConversationId(10L);
        message.setSenderId(COACH_USER_ID);
        message.setSenderRole(com.softropic.skillars.platform.messaging.contract.SenderRole.COACH);
        message.setContent("test content");
        message.setModerationStatus(MessageModerationStatus.PENDING);
        message.setCreatedAt(Instant.now());

        conversation = new Conversation();
        conversation.setId(10L);
        conversation.setPlayerId(PLAYER_ID);
        conversation.setParentId(PARENT_ID);
        conversation.setCoachId(COACH_UUID);

        when(messageRepository.findById(1L)).thenReturn(Optional.of(message));
        lenient().when(messageRepository.save(any())).thenReturn(message);
        lenient().when(conversationRepository.findById(10L)).thenReturn(Optional.of(conversation));
        // Default policy: SUPERVISED — parent receives SSE for SAFE verdict in basic tests
        lenient().when(agePolicyService.getMessagingPolicy(PLAYER_ID))
            .thenReturn(MessagingPolicy.supervised());
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void applyResult_safeVerdict_setsApprovedAndDeliveredAt() {
        ModerationResult result = applier.applyResult(1L, ModerationVerdict.SAFE);

        assertThat(result.moderationStatus()).isEqualTo(MessageModerationStatus.APPROVED);
        assertThat(result.deliveredAt()).isNotNull();
        assertThat(message.getModerationStatus()).isEqualTo(MessageModerationStatus.APPROVED);
        assertThat(message.getDeliveredAt()).isNotNull();
    }

    @Test
    void applyResult_safeVerdict_coachSender_parentManagedPolicy_sseGoesToParent() {
        when(agePolicyService.getMessagingPolicy(PLAYER_ID)).thenReturn(MessagingPolicy.parentManaged());

        applier.applyResult(1L, ModerationVerdict.SAFE);
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());

        verify(messagingEmitterRegistry).emit(
            org.mockito.ArgumentMatchers.eq(PARENT_ID),
            org.mockito.ArgumentMatchers.eq("NEW_MESSAGE"),
            org.mockito.ArgumentMatchers.argThat(m -> {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> map = (java.util.Map<String, Object>) m;
                return "NEW_MESSAGE".equals(map.get("type"))
                    && Long.valueOf(1L).equals(map.get("messageId"))
                    && Long.valueOf(10L).equals(map.get("conversationId"));
            }));
    }

    @Test
    void applyResult_safeVerdict_coachSender_unrestrictedPolicy_sseGoesToPlayer() {
        when(agePolicyService.getMessagingPolicy(PLAYER_ID)).thenReturn(MessagingPolicy.unrestricted());

        applier.applyResult(1L, ModerationVerdict.SAFE);
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());

        verify(messagingEmitterRegistry).emit(
            org.mockito.ArgumentMatchers.eq(PLAYER_ID),
            org.mockito.ArgumentMatchers.eq("NEW_MESSAGE"),
            any());
    }

    @Test
    void applyResult_unsafeVerdict_setsBlockedAndEmitsSenderSse() {
        ModerationResult result = applier.applyResult(1L, ModerationVerdict.UNSAFE);
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());

        assertThat(result.moderationStatus()).isEqualTo(MessageModerationStatus.BLOCKED);
        assertThat(result.deliveredAt()).isNull();
        assertThat(message.getModerationStatus()).isEqualTo(MessageModerationStatus.BLOCKED);

        verify(messagingEmitterRegistry).emit(
            org.mockito.ArgumentMatchers.eq(COACH_USER_ID),
            org.mockito.ArgumentMatchers.eq("MESSAGE_BLOCKED"),
            org.mockito.ArgumentMatchers.argThat(m -> {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> map = (java.util.Map<String, Object>) m;
                return "MESSAGE_BLOCKED".equals(map.get("type"))
                    && Long.valueOf(1L).equals(map.get("messageId"));
            }));
    }

    @Test
    void applyResult_uncertainVerdict_setsUnderReview_noSse() {
        ModerationResult result = applier.applyResult(1L, ModerationVerdict.UNCERTAIN);
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());

        assertThat(result.moderationStatus()).isEqualTo(MessageModerationStatus.UNDER_REVIEW);
        assertThat(result.deliveredAt()).isNull();
        assertThat(message.getModerationStatus()).isEqualTo(MessageModerationStatus.UNDER_REVIEW);

        verify(messagingEmitterRegistry, never()).emit(any(Long.class), any(String.class), any());
    }

    @Test
    void applyResult_messageNotFound_logsWarnAndReturnsPending() {
        when(messageRepository.findById(1L)).thenReturn(Optional.empty());

        ModerationResult result = applier.applyResult(1L, ModerationVerdict.SAFE);

        assertThat(result.moderationStatus()).isEqualTo(MessageModerationStatus.PENDING);
        assertThat(result.deliveredAt()).isNull();
        verify(messageRepository, never()).save(any());
    }

    @Test
    void applyResult_safeVerdict_nonCoachSender_sseGoesToCoach() {
        message.setSenderId(PLAYER_ID);
        message.setSenderRole(com.softropic.skillars.platform.messaging.contract.SenderRole.PLAYER);

        CoachProfile coachProfile = mock(CoachProfile.class);
        when(coachProfile.getUserId()).thenReturn(COACH_USER_ID);
        when(coachProfileRepository.findById(COACH_UUID)).thenReturn(Optional.of(coachProfile));

        applier.applyResult(1L, ModerationVerdict.SAFE);
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());

        verify(messagingEmitterRegistry).emit(
            org.mockito.ArgumentMatchers.eq(COACH_USER_ID),
            org.mockito.ArgumentMatchers.eq("NEW_MESSAGE"),
            any());
    }
}
