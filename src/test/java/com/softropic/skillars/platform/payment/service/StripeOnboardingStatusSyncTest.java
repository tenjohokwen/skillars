package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.config.PaymentProperties;
import com.softropic.skillars.platform.payment.contract.CoachStripeStatusResponse;
import com.softropic.skillars.platform.payment.contract.event.CoachStripeOnboardingCompleteEvent;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccount;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;
import com.stripe.exception.ApiConnectionException;
import com.stripe.model.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * fix/payment-stripe-connect-webhook-endpoint: the onboarding fallback that retrieves the connected
 * account from Stripe so a missed {@code account.updated} cannot leave a coach stuck in PENDING.
 */
@ExtendWith(MockitoExtension.class)
class StripeOnboardingStatusSyncTest {

    private static final UUID COACH_ID = UUID.fromString("dddddddd-1111-2222-3333-eeeeeeeeeeee");
    private static final String ACCT = "acct_sync_1";
    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");

    @Mock CoachStripeAccountRepository repository;
    @Mock StripeClient stripeClient;
    @Mock ApplicationEventPublisher eventPublisher;

    private MutableClock clock;
    private StripeOnboardingService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        service = new StripeOnboardingService(repository, new PaymentProperties(), stripeClient,
            new CoachStripeAccountStatusApplier(eventPublisher), clock);
        ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void getStripeStatus_pending_fetchesAccountAndCompletes() throws Exception {
        CoachStripeAccount row = row("PENDING");
        when(repository.findById(COACH_ID)).thenReturn(Optional.of(row));
        when(stripeClient.retrieveAccount(ACCT)).thenReturn(account(ACCT, true, true, "active"));

        CoachStripeStatusResponse status = service.getStripeStatus(COACH_ID);

        assertThat(status.onboardingStatus()).isEqualTo("COMPLETE");
        assertThat(status.chargesEnabled()).isTrue();
        assertThat(row.getTransfersCapability()).isEqualTo("active");
        // Conservative snapshot time: the request start minus the skew allowance.
        assertThat(row.getLastEventCreatedAt())
            .isEqualTo(NOW.minus(StripeOnboardingService.FETCH_SNAPSHOT_SKEW_ALLOWANCE));
        verify(eventPublisher).publishEvent(any(CoachStripeOnboardingCompleteEvent.class));
    }

    @Test
    void getStripeStatus_complete_doesNotCallStripe() throws Exception {
        when(repository.findById(COACH_ID)).thenReturn(Optional.of(row("COMPLETE")));

        assertThat(service.getStripeStatus(COACH_ID).onboardingStatus()).isEqualTo("COMPLETE");

        verify(stripeClient, never()).retrieveAccount(any());
    }

    @Test
    void getStripeStatus_notConnected_doesNotCallStripe() throws Exception {
        when(repository.findById(COACH_ID)).thenReturn(Optional.empty());

        assertThat(service.getStripeStatus(COACH_ID).onboardingStatus()).isEqualTo("NOT_CONNECTED");

        verify(stripeClient, never()).retrieveAccount(any());
    }

    @Test
    void getStripeStatus_repeatedPolls_rateLimitedPerCoach() throws Exception {
        when(repository.findById(COACH_ID)).thenReturn(Optional.of(row("PENDING")));
        when(stripeClient.retrieveAccount(ACCT)).thenReturn(account(ACCT, false, false, "inactive"));

        service.getStripeStatus(COACH_ID);
        clock.advance(Duration.ofSeconds(5));
        service.getStripeStatus(COACH_ID);
        verify(stripeClient, times(1)).retrieveAccount(ACCT);

        clock.advance(StripeOnboardingService.STATUS_SYNC_MIN_INTERVAL);
        service.getStripeStatus(COACH_ID);
        verify(stripeClient, times(2)).retrieveAccount(ACCT);
    }

    @Test
    void getStripeStatus_newerWebhookAlreadyApplied_fetchDoesNotOverwrite() throws Exception {
        // An account.updated created after the fetch's snapshot point said RESTRICTED: the fetch
        // (which Stripe may have served from before that change) must not flip it back.
        CoachStripeAccount row = row("RESTRICTED");
        Instant newerEvent = NOW.minusSeconds(5);
        row.setLastEventCreatedAt(newerEvent);
        when(repository.findById(COACH_ID)).thenReturn(Optional.of(row));
        when(stripeClient.retrieveAccount(ACCT)).thenReturn(account(ACCT, true, true, "active"));

        CoachStripeStatusResponse status = service.getStripeStatus(COACH_ID);

        assertThat(status.onboardingStatus()).isEqualTo("RESTRICTED");
        assertThat(row.getLastEventCreatedAt()).isEqualTo(newerEvent);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void getStripeStatus_stripeUnavailable_returnsStoredStatus() throws Exception {
        when(repository.findById(COACH_ID)).thenReturn(Optional.of(row("PENDING")));
        when(stripeClient.retrieveAccount(ACCT)).thenThrow(new ApiConnectionException("down"));

        assertThat(service.getStripeStatus(COACH_ID).onboardingStatus()).isEqualTo("PENDING");
    }

    @Test
    void applyFetchedAccount_rowNowPointsAtDifferentAccount_skipped() {
        CoachStripeAccount row = row("PENDING");
        row.setStripeAccountId("acct_reconnected");
        when(repository.findById(COACH_ID)).thenReturn(Optional.of(row));

        boolean applied = service.applyFetchedAccount(COACH_ID, account(ACCT, true, true, "active"), NOW);

        assertThat(applied).isFalse();
        assertThat(row.getOnboardingStatus()).isEqualTo("PENDING");
    }

    @Test
    void upsert_existingRow_resetsSnapshotHistoryToTheConnectTime() {
        CoachStripeAccount row = row("COMPLETE");
        row.setLastEventCreatedAt(NOW.minusSeconds(1));
        row.setTransfersCapability("active");
        when(repository.findById(COACH_ID)).thenReturn(Optional.of(row));

        service.upsertCoachStripeAccount(COACH_ID, "acct_new");

        assertThat(row.getOnboardingStatus()).isEqualTo("PENDING");
        assertThat(row.getLastEventCreatedAt()).isEqualTo(NOW);
        assertThat(row.getTransfersCapability()).isNull();
        assertThat(row.getStripeAccountId()).isEqualTo("acct_new");
    }

    @Test
    void reconnectAfterDeauthorization_clearsSentinel_andCallbackFetchApplies() throws Exception {
        CoachStripeAccount row = row("RESTRICTED");
        row.setTransfersCapability(CoachStripeAccountStatusApplier.TRANSFERS_DEAUTHORIZED);
        row.setLastEventCreatedAt(NOW.minusSeconds(60));
        when(repository.findById(COACH_ID)).thenReturn(Optional.of(row));
        when(stripeClient.retrieveAccount(ACCT)).thenReturn(account(ACCT, true, true, "active"));

        // What handleOAuthCallback does after the OAuth code exchange.
        service.upsertCoachStripeAccount(COACH_ID, ACCT, NOW);
        boolean applied = service.syncAccountFromStripe(COACH_ID, ACCT, NOW);

        assertThat(applied).isTrue();
        assertThat(row.getOnboardingStatus()).isEqualTo("COMPLETE");
        assertThat(row.getTransfersCapability()).isEqualTo("active");
    }

    @Test
    void getStripeStatus_deauthorized_reportsDisconnected_withoutCallingStripe() throws Exception {
        CoachStripeAccount row = row("RESTRICTED");
        row.setTransfersCapability(CoachStripeAccountStatusApplier.TRANSFERS_DEAUTHORIZED);
        when(repository.findById(COACH_ID)).thenReturn(Optional.of(row));

        CoachStripeStatusResponse status = service.getStripeStatus(COACH_ID);

        assertThat(status.disconnected()).isTrue();
        assertThat(status.onboardingStatus()).isEqualTo("RESTRICTED");
        verify(stripeClient, never()).retrieveAccount(any());
    }

    // ── fix/prod-readiness-gaps: daily re-read of COMPLETE accounts ─────────────────────────────

    @Test
    void resyncCompleteAccounts_restrictedAtStripe_storedAsRestricted() throws Exception {
        CoachStripeAccount row = row("COMPLETE");
        when(repository.findConnectedByStatusAfter(eq("COMPLETE"), eq("deauthorized"), any(UUID.class),
            any(Pageable.class))).thenReturn(List.of(row));
        when(repository.findById(COACH_ID)).thenReturn(Optional.of(row));
        when(stripeClient.retrieveAccount(ACCT)).thenReturn(account(ACCT, false, false, "inactive"));

        int changed = service.resyncCompleteAccounts(200, 25);

        assertThat(changed).isEqualTo(1);
        assertThat(row.getOnboardingStatus()).isEqualTo("RESTRICTED");
        assertThat(row.isChargesEnabled()).isFalse();
    }

    @Test
    void resyncCompleteAccounts_pagesByCoachIdAndStopsAtMaxPages() throws Exception {
        CoachStripeAccount a = row("COMPLETE");
        CoachStripeAccount b = row("COMPLETE");
        b.setCoachId(UUID.fromString("ffffffff-1111-2222-3333-eeeeeeeeeeee"));
        b.setStripeAccountId("acct_sync_2");
        when(repository.findConnectedByStatusAfter(eq("COMPLETE"), eq("deauthorized"), any(UUID.class),
            any(Pageable.class))).thenReturn(List.of(a), List.of(b));
        when(stripeClient.retrieveAccount(any())).thenThrow(new ApiConnectionException("down"));

        int changed = service.resyncCompleteAccounts(1, 2);

        assertThat(changed).isZero();
        ArgumentCaptor<UUID> after = ArgumentCaptor.forClass(UUID.class);
        verify(repository, times(2)).findConnectedByStatusAfter(eq("COMPLETE"), eq("deauthorized"), after.capture(),
            any(Pageable.class));
        assertThat(after.getAllValues()).containsExactly(new UUID(0L, 0L), COACH_ID);
        // A Stripe failure on one account does not stop the run.
        verify(stripeClient).retrieveAccount(ACCT);
        verify(stripeClient).retrieveAccount("acct_sync_2");
    }

    private static CoachStripeAccount row(String status) {
        CoachStripeAccount a = new CoachStripeAccount();
        a.setCoachId(COACH_ID);
        a.setStripeAccountId(ACCT);
        a.setOnboardingStatus(status);
        a.setChargesEnabled("COMPLETE".equals(status));
        a.setPayoutsEnabled("COMPLETE".equals(status));
        return a;
    }

    private static Account account(String id, boolean charges, boolean payouts, String transfers) {
        Account a = new Account();
        a.setId(id);
        a.setChargesEnabled(charges);
        a.setPayoutsEnabled(payouts);
        Account.Capabilities caps = new Account.Capabilities();
        caps.setTransfers(transfers);
        a.setCapabilities(caps);
        return a;
    }

    /** Settable clock: the rate-limit window is checked against it. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
