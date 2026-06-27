package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.infrastructure.gemini.GeminiClient;
import com.softropic.skillars.infrastructure.gemini.GeminiException;
import com.softropic.skillars.platform.messaging.contract.ModerationFailureEvent;
import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;
import com.softropic.skillars.platform.messaging.repo.Message;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeminiModerationServiceTest {

    @Mock private GeminiClient geminiClient;
    @Mock private ModerationResultApplier moderationResultApplier;
    @Mock private MessageRepository messageRepository;
    @Mock private ApplicationEventPublisher publisher;

    @InjectMocks
    private GeminiModerationService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "promptTemplate", "Test prompt:\n");
        ReflectionTestUtils.setField(service, "maxInputChars", 100);
    }

    @Test
    void geminiClient_returnsSafe_callsApplyResultWithSafe() {
        when(geminiClient.evaluate(any())).thenReturn(ModerationVerdict.SAFE);

        service.moderate(1L, "hello");

        verify(moderationResultApplier).applyResult(1L, ModerationVerdict.SAFE);
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void geminiClient_returnsUnsafe_callsApplyResultWithUnsafe() {
        when(geminiClient.evaluate(any())).thenReturn(ModerationVerdict.UNSAFE);

        service.moderate(1L, "hello");

        verify(moderationResultApplier).applyResult(1L, ModerationVerdict.UNSAFE);
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void geminiClient_returnsUncertain_callsApplyResultWithUncertain() {
        when(geminiClient.evaluate(any())).thenReturn(ModerationVerdict.UNCERTAIN);

        service.moderate(1L, "hello");

        verify(moderationResultApplier).applyResult(1L, ModerationVerdict.UNCERTAIN);
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void geminiClient_throwsException_callsApplyResultWithUncertain_publishesFailureEvent() {
        when(geminiClient.evaluate(any())).thenThrow(new GeminiException("timeout", null));
        Message mockMessage = new Message();
        mockMessage.setId(1L);
        mockMessage.setConversationId(99L);
        when(messageRepository.findById(1L)).thenReturn(Optional.of(mockMessage));

        service.moderate(1L, "hello");

        verify(moderationResultApplier).applyResult(1L, ModerationVerdict.UNCERTAIN);
        ArgumentCaptor<ModerationFailureEvent> captor = ArgumentCaptor.forClass(ModerationFailureEvent.class);
        verify(publisher).publishEvent(captor.capture());
        ModerationFailureEvent event = captor.getValue();
        assertThat(event.messageId()).isEqualTo(1L);
        assertThat(event.conversationId()).isEqualTo(99L);
        assertThat(event.failureReason()).isEqualTo("timeout");
    }

    @Test
    void contentExceedsMaxInputChars_truncatedBeforeSending() {
        when(geminiClient.evaluate(any())).thenReturn(ModerationVerdict.SAFE);
        String longContent = "x".repeat(200);

        service.moderate(1L, longContent);

        verify(geminiClient).evaluate(argThat(s -> {
            String expectedEnd = "x".repeat(100);
            return s.startsWith("Test prompt:\n") && s.endsWith(expectedEnd) && s.length() == "Test prompt:\n".length() + 100;
        }));
    }

    @Test
    void contentWithinMaxInputChars_notTruncated() {
        when(geminiClient.evaluate(any())).thenReturn(ModerationVerdict.SAFE);
        String shortContent = "short message";

        service.moderate(1L, shortContent);

        verify(geminiClient).evaluate("Test prompt:\n" + shortContent);
    }
}
