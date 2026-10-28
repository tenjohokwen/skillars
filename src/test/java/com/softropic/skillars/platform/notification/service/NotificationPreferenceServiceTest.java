package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.NotificationCategory;
import com.softropic.skillars.platform.notification.contract.NotificationPreferenceDto;
import com.softropic.skillars.platform.notification.repo.NotificationEmailPreferenceRepository;
import com.softropic.skillars.platform.notification.repo.NotificationRecipientLookup;
import com.softropic.skillars.platform.notification.repo.NotificationRecipientLookup.ResolvedRecipient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Notification centre (UI-gap chunk C9): opt-out rules and the send-time decision. */
class NotificationPreferenceServiceTest {

    private NotificationEmailPreferenceRepository repository;
    private NotificationRecipientLookup lookup;
    private UnsubscribeTokenService tokens;
    private NotificationPreferenceService service;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationEmailPreferenceRepository.class);
        lookup = mock(NotificationRecipientLookup.class);
        tokens = UnsubscribeTokenServiceTest.service("unit-test-registration-verification-secret-0123456789");
        service = new NotificationPreferenceService(repository, lookup, tokens, new JdbcSavepoints(null));
    }

    @Test
    void preferencesListOnlyOptOutableCategories_defaultingToEnabled() {
        when(repository.findByUserId(5L)).thenReturn(Map.of("DEVELOPMENT", false));

        assertThat(service.getPreferences(5L)).containsExactly(
            new NotificationPreferenceDto(NotificationCategory.REMINDER, true),
            new NotificationPreferenceDto(NotificationCategory.DEVELOPMENT, false));
    }

    @Test
    void transactionalCategoryCannotBeTurnedOff() {
        assertThatThrownBy(() -> service.setPreference(5L, NotificationCategory.BOOKING, false))
            .isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).upsert(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void transactionalEmail_isNeverSuppressedAndNeverLooksUpTheUser() {
        NotificationPreferenceService.EmailDecision decision =
            service.emailDecision(EmailTemplate.BOOKING_CONFIRMED, "p@x.com");

        assertThat(decision.suppressed()).isFalse();
        assertThat(decision.unsubscribeToken()).isNull();
        verifyNoInteractions(lookup, repository);
    }

    @Test
    void optedOutUser_isSuppressed() {
        when(lookup.findByEmail("p@x.com", false)).thenReturn(Optional.of(new ResolvedRecipient(5L, false)));
        when(repository.findEmailEnabled(5L, "REMINDER")).thenReturn(Optional.of(false));

        assertThat(service.emailDecision(EmailTemplate.BOOKING_REMINDER, "p@x.com").suppressed()).isTrue();
    }

    @Test
    void optedInUser_getsAVerifiableTokenForTheirOwnCategory() {
        when(lookup.findByEmail("p@x.com", false)).thenReturn(Optional.of(new ResolvedRecipient(5L, false)));
        when(repository.findEmailEnabled(5L, "DEVELOPMENT")).thenReturn(Optional.empty());

        NotificationPreferenceService.EmailDecision decision =
            service.emailDecision(EmailTemplate.PERFORMANCE_REPORT_SHARED, "p@x.com");

        assertThat(decision.suppressed()).isFalse();
        assertThat(tokens.verify(decision.unsubscribeToken()))
            .contains(new UnsubscribeTokenService.Claims(5L, NotificationCategory.DEVELOPMENT));
    }

    @Test
    void unknownAddress_isSentWithoutAnUnsubscribeLink() {
        when(lookup.findByEmail("alias@x.com", false)).thenReturn(Optional.empty());

        NotificationPreferenceService.EmailDecision decision =
            service.emailDecision(EmailTemplate.BOOKING_REMINDER, "alias@x.com");

        assertThat(decision.suppressed()).isFalse();
        assertThat(decision.unsubscribeToken()).isNull();
    }

    @Test
    void unsubscribe_rejectsAnInvalidToken() {
        assertThatThrownBy(() -> service.unsubscribe("v1.bogus.token"))
            .isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).upsert(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void unsubscribe_validTokenForADeletedUser_isRejectedLikeABadToken() {
        String token = tokens.mint(77L, NotificationCategory.REMINDER);
        when(repository.upsert(77L, "REMINDER", false)).thenReturn(0);

        assertThatThrownBy(() -> service.unsubscribe(token)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void unsubscribe_validToken_turnsTheCategoryOff() {
        String token = tokens.mint(77L, NotificationCategory.REMINDER);
        when(repository.upsert(77L, "REMINDER", false)).thenReturn(1);

        assertThat(service.unsubscribe(token)).isEqualTo(NotificationCategory.REMINDER);
    }
}
