package com.softropic.skillars.platform.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.infrastructure.web.FrontendLinkBuilder;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.repo.NotificationRecipientLookup;
import com.softropic.skillars.platform.notification.repo.NotificationRecipientLookup.ResolvedRecipient;
import com.softropic.skillars.platform.notification.repo.UserNotificationRepository;
import com.softropic.skillars.platform.notification.repo.UserNotificationWriter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Notification centre (UI-gap chunk C9): how an email event becomes an inbox row. */
class InAppNotificationServiceTest {

    private static final String BASE = "https://app.example.com";

    private UserNotificationRepository repository;
    private UserNotificationWriter writer;
    private NotificationRecipientLookup lookup;
    private InAppNotificationService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserNotificationRepository.class);
        writer = mock(UserNotificationWriter.class);
        lookup = mock(NotificationRecipientLookup.class);
        // Savepoint handling itself is proven against PostgreSQL in NotificationCenterIT; here it
        // just runs the work.
        JdbcSavepoints savepoints = mock(JdbcSavepoints.class);
        when(savepoints.run(any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
        service = new InAppNotificationService(repository, writer, lookup, savepoints,
            new FrontendLinkBuilder(BASE), new ObjectMapper());
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void emailOnlyTemplate_touchesNothing() {
        service.recordForEmail(EmailTemplate.PARENT_OTP, "p@x.com", Map.of("otp", "123456"), "s1");

        verifyNoInteractions(lookup, writer);
    }

    @Test
    void addressWithNoPlatformUser_isSkipped() {
        when(lookup.findByEmail(eq("nobody@x.com"), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Optional.empty());

        service.recordForEmail(EmailTemplate.BOOKING_CONFIRMED, "nobody@x.com", Map.of(), "s1");

        verify(writer, never()).insertIfAbsent(anyLong(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void onlyWhitelistedStringParamsArePersisted() throws Exception {
        when(lookup.findByEmail(eq("p@x.com"), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Optional.of(new ResolvedRecipient(7L, false)));
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", "Coach Carter");
        data.put("requestedStartTime", "2026-11-01T10:00:00Z");
        data.put("notes", "free text the parent typed");
        data.put("bookingsUrl", BASE + "/#/parent/bookings");
        data.put("sessionDates", List.of("a", "b"));
        data.put("playerName", "x".repeat(500));

        service.recordForEmail(EmailTemplate.BOOKING_CONFIRMED, "p@x.com", data, "send-1");

        ArgumentCaptor<String> params = ArgumentCaptor.forClass(String.class);
        verify(writer).insertIfAbsent(eq(7L), eq("BOOKING"), eq("BOOKING_CONFIRMED"), params.capture(),
            eq("/parent/bookings"), eq("send-1"));
        @SuppressWarnings("unchecked")
        Map<String, String> persisted = new ObjectMapper().readValue(params.getValue(), Map.class);
        assertThat(persisted).containsOnlyKeys("coachDisplayName", "requestedStartTime", "playerName");
        assertThat(persisted.get("playerName")).hasSize(200);
    }

    @Test
    void dualAudienceTemplate_pickslinkByRecipientRole() {
        when(lookup.findByEmail(eq("coach@x.com"), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Optional.of(new ResolvedRecipient(1L, true)));
        when(lookup.findByEmail(eq("parent@x.com"), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Optional.of(new ResolvedRecipient(2L, false)));

        service.recordForEmail(EmailTemplate.BOOKING_REMINDER, "coach@x.com", Map.of(), "s-coach");
        service.recordForEmail(EmailTemplate.BOOKING_REMINDER, "parent@x.com", Map.of(), "s-parent");

        verify(writer).insertIfAbsent(eq(1L), eq("REMINDER"), eq("BOOKING_REMINDER"), anyString(),
            eq("/coach/booking-requests"), eq("s-coach"));
        verify(writer).insertIfAbsent(eq(2L), eq("REMINDER"), eq("BOOKING_REMINDER"), anyString(),
            eq("/parent/bookings"), eq("s-parent"));
    }

    @Test
    void reportDeepLink_isDerivedFromTheFrontendUrlInTheEmail() {
        when(lookup.findByEmail(eq("p@x.com"), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Optional.of(new ResolvedRecipient(3L, false)));

        service.recordForEmail(EmailTemplate.PERFORMANCE_REPORT_SHARED, "p@x.com",
            Map.of("reportsPageUrl", BASE + "/#/parent/players/55/development"), "s");

        verify(writer).insertIfAbsent(eq(3L), eq("DEVELOPMENT"), eq("PERFORMANCE_REPORT_SHARED"), anyString(),
            eq("/parent/players/55/development"), eq("s"));
    }

    @Test
    void foreignOrUnsafeReportUrl_fallsBackToTheCatalogLink() {
        when(lookup.findByEmail(eq("p@x.com"), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Optional.of(new ResolvedRecipient(3L, false)));

        service.recordForEmail(EmailTemplate.PERFORMANCE_REPORT_SHARED, "p@x.com",
            Map.of("reportsPageUrl", "https://evil.example.com/#/parent/players/55/development"), "s1");
        service.recordForEmail(EmailTemplate.PERFORMANCE_REPORT_SHARED, "p@x.com",
            Map.of("reportsPageUrl", BASE + "/#//evil.example.com"), "s2");

        verify(writer).insertIfAbsent(eq(3L), anyString(), anyString(), anyString(), eq("/dashboard"), eq("s1"));
        verify(writer).insertIfAbsent(eq(3L), anyString(), anyString(), anyString(), eq("/dashboard"), eq("s2"));
    }

    @Test
    void numericParamsAreKeptAsStrings() throws Exception {
        when(lookup.findByEmail(eq("p@x.com"), org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn(Optional.of(new ResolvedRecipient(7L, false)));
        Map<String, Object> data = new HashMap<>();
        data.put("creditsRemaining", 3);
        data.put("coachDisplayName", "C");

        service.recordForEmail(EmailTemplate.SESSION_PACK_EXPIRY_WARNING, "p@x.com", data, "s");

        ArgumentCaptor<String> params = ArgumentCaptor.forClass(String.class);
        verify(writer).insertIfAbsent(eq(7L), anyString(), anyString(), params.capture(), any(), eq("s"));
        assertThat(params.getValue()).contains("\"creditsRemaining\":\"3\"");
    }

    @Test
    void aFailingInboxWriteNeverPropagatesToTheBusinessCaller() {
        when(lookup.findByEmail(eq("p@x.com"), org.mockito.ArgumentMatchers.anyBoolean()))
            .thenThrow(new org.springframework.dao.QueryTimeoutException("db slow"));

        org.assertj.core.api.Assertions.assertThatCode(() ->
                service.recordForEmail(EmailTemplate.BOOKING_CONFIRMED, "p@x.com", Map.of(), "s"))
            .doesNotThrowAnyException();
    }

    @Test
    void withoutAnActiveTransactionNothingIsWritten() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);

        service.recordForEmail(EmailTemplate.BOOKING_CONFIRMED, "p@x.com", Map.of(), "s");

        verifyNoInteractions(lookup, writer);
    }

    @Test
    void pageSizeIsClamped() {
        when(repository.findByRecipientUserIdOrderByCreatedAtDescIdDesc(eq(9L), any()))
            .thenReturn(org.springframework.data.domain.Page.empty());

        service.list(9L, -3, 10_000);

        ArgumentCaptor<org.springframework.data.domain.Pageable> pageable =
            ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(repository).findByRecipientUserIdOrderByCreatedAtDescIdDesc(eq(9L), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(InAppNotificationService.MAX_PAGE_SIZE);
    }
}
