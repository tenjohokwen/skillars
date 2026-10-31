package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.config.PaymentProperties;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccount;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;
import com.softropic.skillars.platform.payment.repo.StripeOAuthState;
import com.softropic.skillars.platform.payment.repo.StripeOAuthStateRepository;
import com.softropic.skillars.platform.security.contract.event.AccountChangeEvent;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * fix/security-payout-oauth-session-revocation: the single-use OAuth {@code state} (S-01) and the
 * cooling-off + notice when a payout destination is replaced (S-02).
 */
@ExtendWith(MockitoExtension.class)
class StripeOnboardingSecurityTest {

    private static final UUID COACH_ID = UUID.fromString("dddddddd-1111-2222-3333-eeeeeeeeeeee");
    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");

    @Mock CoachStripeAccountRepository accountRepository;
    @Mock StripeOAuthStateRepository stateRepository;
    @Mock ConfigService configService;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock UserRepository userRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock StripeClient stripeClient;

    private StripeOnboardingService service;

    @BeforeEach
    void setUp() {
        service = new StripeOnboardingService(accountRepository, new PaymentProperties(), stripeClient,
            new CoachStripeAccountStatusApplier(eventPublisher), Clock.fixed(NOW, ZoneOffset.UTC),
            stateRepository, configService, coachProfileRepository, userRepository, eventPublisher);
    }

    // ---- S-01 -------------------------------------------------------------------------------

    @AfterEach
    void resetStripeClientId() {
        com.stripe.Stripe.clientId = null;
    }

    @Test
    void generateOnboardingUrl_mintsAnUnguessableNonceStoredOnlyAsAHash() throws Exception {
        com.stripe.Stripe.clientId = "ca_test";

        String url = service.generateOnboardingUrl(COACH_ID);

        ArgumentCaptor<StripeOAuthState> saved = ArgumentCaptor.forClass(StripeOAuthState.class);
        verify(stateRepository).purgeForCoachAndExpired(COACH_ID, NOW);
        verify(stateRepository).save(saved.capture());
        StripeOAuthState row = saved.getValue();
        assertThat(row.getCoachId()).isEqualTo(COACH_ID);
        assertThat(row.getExpiresAt()).isEqualTo(NOW.plus(StripeOnboardingService.OAUTH_STATE_TTL));
        assertThat(url).doesNotContain(COACH_ID.toString());
        assertThat(url).contains("state=");
        String nonce = url.substring(url.indexOf("state=") + 6).split("&")[0];
        assertThat(nonce).hasSizeGreaterThanOrEqualTo(43);
        assertThat(row.getNonceHash()).hasSize(64).isNotEqualTo(nonce);
    }

    @Test
    void consumeOAuthState_validUnexpiredStateForThisCoach_isAccepted() {
        when(stateRepository.consume(anyString(), eq(COACH_ID), eq(NOW))).thenReturn(1);

        assertThat(service.consumeOAuthState(COACH_ID, "nonce")).isTrue();
    }

    @Test
    void consumeOAuthState_replayedWrongCoachOrExpired_isRejected() {
        // The atomic DELETE matches nothing when the state is used up, minted for another coach, or expired.
        when(stateRepository.consume(anyString(), any(), any())).thenReturn(0);

        assertThat(service.consumeOAuthState(COACH_ID, "nonce")).isFalse();
    }

    @Test
    void consumeOAuthState_missingOrBlank_isRejectedWithoutTouchingTheStore() {
        assertThat(service.consumeOAuthState(COACH_ID, null)).isFalse();
        assertThat(service.consumeOAuthState(COACH_ID, " ")).isFalse();
        verify(stateRepository, never()).consume(any(), any(), any());
    }

    @Test
    void consumeOAuthState_looksUpTheHashNotTheRawNonce() {
        when(stateRepository.consume(anyString(), any(), any())).thenReturn(1);

        service.consumeOAuthState(COACH_ID, "nonce");

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(stateRepository).consume(hash.capture(), eq(COACH_ID), eq(NOW));
        assertThat(hash.getValue()).hasSize(64).isNotEqualTo("nonce");
    }

    // ---- S-02 -------------------------------------------------------------------------------

    @Test
    void replacingAnExistingAccount_holdsPayoutsAndNotifiesTheCoach() {
        CoachStripeAccount existing = account("acct_old");
        when(accountRepository.findById(COACH_ID)).thenReturn(Optional.of(existing));
        when(configService.getBoundedLong(ConfigBounds.PAYOUT_ACCOUNT_CHANGE_HOLD_HOURS)).thenReturn(72L);
        stubCoachUser();

        service.upsertCoachStripeAccount(COACH_ID, "acct_new", NOW);

        assertThat(existing.getStripeAccountId()).isEqualTo("acct_new");
        assertThat(existing.getPayoutHoldUntil()).isEqualTo(NOW.plus(Duration.ofHours(72)));
        ArgumentCaptor<AccountChangeEvent> event = ArgumentCaptor.forClass(AccountChangeEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getAction()).isEqualTo(AccountChangeEvent.Action.PAYOUT_ACCOUNT_CHANGED);
        assertThat(event.getValue().getUserInfo().email()).isEqualTo("coach@example.com");
    }

    @Test
    void reconnectingTheSameAccount_setsNoHoldAndSendsNoNotice() {
        CoachStripeAccount existing = account("acct_same");
        when(accountRepository.findById(COACH_ID)).thenReturn(Optional.of(existing));

        service.upsertCoachStripeAccount(COACH_ID, "acct_same", NOW);

        assertThat(existing.getPayoutHoldUntil()).isNull();
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void firstTimeConnection_isNeverDelayedAndSendsNoNotice() {
        when(accountRepository.findById(COACH_ID)).thenReturn(Optional.empty());

        service.upsertCoachStripeAccount(COACH_ID, "acct_first", NOW);

        ArgumentCaptor<CoachStripeAccount> saved = ArgumentCaptor.forClass(CoachStripeAccount.class);
        verify(accountRepository).save(saved.capture());
        assertThat(saved.getValue().getPayoutHoldUntil()).isNull();
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void zeroConfiguredHold_stillNotifiesButSetsNoHold() {
        CoachStripeAccount existing = account("acct_old");
        when(accountRepository.findById(COACH_ID)).thenReturn(Optional.of(existing));
        when(configService.getBoundedLong(ConfigBounds.PAYOUT_ACCOUNT_CHANGE_HOLD_HOURS)).thenReturn(0L);
        stubCoachUser();

        service.upsertCoachStripeAccount(COACH_ID, "acct_new", NOW);

        assertThat(existing.getPayoutHoldUntil()).isNull();
        verify(eventPublisher).publishEvent(any(AccountChangeEvent.class));
    }

    private void stubCoachUser() {
        CoachProfile profile = new CoachProfile();
        profile.setId(COACH_ID);
        profile.setUserId(7L);
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(profile));
        User user = new User();
        user.setEmail("coach@example.com");
        user.setFirstName("Co");
        user.setLastName("Ach");
        when(userRepository.findOneById(7L)).thenReturn(Optional.of(user));
    }

    private static CoachStripeAccount account(String stripeId) {
        CoachStripeAccount a = new CoachStripeAccount();
        a.setCoachId(COACH_ID);
        a.setStripeAccountId(stripeId);
        a.setOnboardingStatus("COMPLETE");
        return a;
    }
}
