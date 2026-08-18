package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.messaging.contract.ModerationService;
import com.softropic.skillars.platform.messaging.repo.ConversationRepository;
import com.softropic.skillars.platform.messaging.repo.Message;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.AgePolicyService;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
