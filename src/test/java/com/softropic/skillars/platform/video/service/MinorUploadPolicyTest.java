package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.service.AgePolicyService;
import com.softropic.skillars.platform.video.contract.event.VideoModerationAdminAlertEvent;
import com.softropic.skillars.platform.video.contract.exception.MinorUploadRefusedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADR "Minors never upload" (2026-10-28): refusal at upload initiation, fail-closed. */
@ExtendWith(MockitoExtension.class)
class MinorUploadPolicyTest {

    @Mock AgePolicyService agePolicyService;
    @Mock ApplicationEventPublisher publisher;
    @Mock com.softropic.skillars.infrastructure.security.RateLimitingService rateLimitingService;

    private MinorUploadPolicy policy;

    @BeforeEach
    void setUp() {
        org.springframework.transaction.support.TransactionTemplate tx =
            new org.springframework.transaction.support.TransactionTemplate() {
                @Override
                public void executeWithoutResult(
                        java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action) {
                    action.accept(null);
                }
            };
        policy = new MinorUploadPolicy(agePolicyService, publisher, tx, rateLimitingService);
    }

    private static PlayerProfile selfRegistered(LocalDate dob) {
        PlayerProfile p = new PlayerProfile();
        p.setId(42L);
        p.setUserId(4242L);
        p.setDateOfBirth(dob);
        return p;
    }

    @Test
    void selfRegisteredAdult_mayUpload_noAlert() {
        PlayerProfile adult = selfRegistered(LocalDate.now().minusYears(25));
        when(agePolicyService.isMinor(adult.getDateOfBirth())).thenReturn(false);

        assertThatCode(() -> policy.assertMayUpload(adult)).doesNotThrowAnyException();
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void minor_refusedWithUrgentAdminAlert() {
        PlayerProfile minor = selfRegistered(LocalDate.now().minusYears(15));
        when(agePolicyService.isMinor(minor.getDateOfBirth())).thenReturn(true);
        when(rateLimitingService.tryConsume(eq("42"), eq(MinorUploadPolicy.ALERT_RATE_KEY), anyLong(), anyLong(), any()))
            .thenReturn(true);

        assertThatThrownBy(() -> policy.assertMayUpload(minor))
            .isInstanceOf(MinorUploadRefusedException.class);

        ArgumentCaptor<VideoModerationAdminAlertEvent> alert = ArgumentCaptor.forClass(VideoModerationAdminAlertEvent.class);
        verify(publisher).publishEvent(alert.capture());
        assertThat(alert.getValue().urgent()).isTrue();
        assertThat(alert.getValue().ownerId()).isEqualTo("42");
        assertThat(alert.getValue().body()).contains("reason=MINOR");
    }

    @Test
    void repeatedRefusals_stillRefused_butAlertRateLimitedPerOwner() {
        PlayerProfile minor = selfRegistered(LocalDate.now().minusYears(15));
        when(agePolicyService.isMinor(minor.getDateOfBirth())).thenReturn(true);
        when(rateLimitingService.tryConsume(eq("42"), eq(MinorUploadPolicy.ALERT_RATE_KEY), anyLong(), anyLong(), any()))
            .thenReturn(false);

        assertThatThrownBy(() -> policy.assertMayUpload(minor))
            .isInstanceOf(MinorUploadRefusedException.class);
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void parentManagedProfile_refusedEvenWithAdultDob_withoutConsultingAge() {
        PlayerProfile managed = selfRegistered(LocalDate.now().minusYears(30));
        managed.setUserId(null);
        managed.setParentId(7L);

        assertThat(policy.refusalReason(managed)).isEqualTo(MinorUploadPolicy.REASON_MANAGED);
        verify(agePolicyService, never()).isMinor(any(LocalDate.class));
    }

    @Test
    void missingDob_andMissingProfile_failClosed() {
        assertThat(policy.refusalReason(selfRegistered(null))).isEqualTo(MinorUploadPolicy.REASON_NO_DOB);
        assertThat(policy.refusalReason(null)).isEqualTo(MinorUploadPolicy.REASON_NO_PROFILE);
    }

    @Test
    void alertFailure_doesNotTurnTheRefusalIntoAnotherError() {
        PlayerProfile minor = selfRegistered(LocalDate.now().minusYears(12));
        when(agePolicyService.isMinor(minor.getDateOfBirth())).thenReturn(true);
        when(rateLimitingService.tryConsume(anyString(), anyString(), anyLong(), anyLong(), any())).thenReturn(true);
        doThrow(new IllegalStateException("mail down")).when(publisher).publishEvent(any(Object.class));

        assertThatThrownBy(() -> policy.assertMayUpload(minor))
            .isInstanceOf(MinorUploadRefusedException.class);
    }
}
