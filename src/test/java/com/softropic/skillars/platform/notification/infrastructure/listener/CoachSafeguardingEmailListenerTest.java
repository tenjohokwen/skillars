package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.infrastructure.web.FrontendLinkBuilder;
import com.softropic.skillars.platform.marketplace.contract.CoachSafeguardingExpiringEvent;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CoachSafeguardingEmailListenerTest {

    @Mock NotificationOutboxSupport notificationOutboxSupport;

    private CoachSafeguardingEmailListener listener() {
        return new CoachSafeguardingEmailListener(notificationOutboxSupport,
            new FrontendLinkBuilder("https://app.test"));
    }

    @Test
    void handlerIsABeforeCommitListener_soTheOutboxRowCommitsWithTheStamp() throws Exception {
        Method m = CoachSafeguardingEmailListener.class.getMethod("onSafeguardingExpiring",
            CoachSafeguardingExpiringEvent.class);
        TransactionalEventListener annotation = m.getAnnotation(TransactionalEventListener.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.BEFORE_COMMIT);
    }

    @Test
    void enqueuesAStringTypedPayload_withTheDateInTheCoachsZone() {
        // 23:30 UTC on 31 Oct is already 1 Nov in Berlin.
        CoachSafeguardingExpiringEvent event = new CoachSafeguardingExpiringEvent(UUID.randomUUID(), "Coach Kim",
            "kim@example.com", "de", "Europe/Berlin", Instant.parse("2031-10-31T23:30:00Z"));

        listener().onSafeguardingExpiring(event);

        ArgumentCaptor<Recipient> recipient = ArgumentCaptor.forClass(Recipient.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(notificationOutboxSupport).enqueueEmail(eq(EmailTemplate.COACH_SAFEGUARDING_EXPIRING),
            recipient.capture(), data.capture(), anyString());
        assertThat(recipient.getValue().getEmail()).isEqualTo("kim@example.com");
        assertThat(recipient.getValue().getLangKey()).isEqualTo("de");
        assertThat(data.getValue()).containsEntry("expiresAt", "2031-11-01")
            .containsEntry("coachDisplayName", "Coach Kim")
            .containsEntry("safeguardingUrl", "https://app.test/#/coach/safeguarding");
        assertThat(data.getValue().values()).allMatch(v -> v instanceof String);
    }

    @Test
    void blankRecipient_isSkipped() {
        listener().onSafeguardingExpiring(new CoachSafeguardingExpiringEvent(UUID.randomUUID(), "Coach",
            " ", "en", "UTC", Instant.now()));
        verify(notificationOutboxSupport, never()).enqueueEmail(any(), any(), anyMap(), anyString());
    }
}
