package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.platform.booking.contract.SessionPackExpiryWarningEvent;
import com.softropic.skillars.platform.notification.contract.Envelope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SessionPackEmailListenerTest {

    @Mock
    private ApplicationEventPublisher publisher;

    @Captor
    private ArgumentCaptor<Envelope> envelopeCaptor;

    @Test
    void sendId_hasNoCollisionAcross10kEmails() {
        SessionPackEmailListener listener = new SessionPackEmailListener(publisher);

        for (int i = 0; i < 10_000; i++) {
            SessionPackExpiryWarningEvent event = new SessionPackExpiryWarningEvent(
                    this, UUID.randomUUID(), 1L, "parent@example.com", UUID.randomUUID(),
                    "coach@example.com", "Coach", 3, Instant.now().plusSeconds(3600), "3", "UTC"
            );
            listener.onExpiryWarning(event);
        }

        verify(publisher, times(10_000)).publishEvent(envelopeCaptor.capture());
        Set<String> sendIds = new HashSet<>();
        for (Envelope envelope : envelopeCaptor.getAllValues()) {
            sendIds.add(envelope.sendId());
        }
        assertThat(sendIds).hasSize(10_000);
    }
}
