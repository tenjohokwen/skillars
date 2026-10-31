package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.CoachSubscriptionChangeRepository;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.PlayerSubscriptionChangeRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.security.repo.ParentPlayerLink;
import com.softropic.skillars.platform.security.repo.ParentPlayerLinkRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * fix/payment-subscription-payment-method-sync: (1) saving a new card moves the user's live
 * subscriptions onto it and retries a past-due invoice; (2) UNPAID/PAUSED get the same grace
 * treatment as PAST_DUE (clock, then cancel at Stripe and clear the link).
 * fix/payment-subscription-resume-and-async-sync: (3) a PAUSED subscription is resumed; (4) the sync
 * runs from the outbox — transient failures throw for a retry, declines/permanent errors do not.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionPaymentMethodSyncTest {

    @Mock PaymentCoachSubscriptionRepository paymentCoachSubscriptionRepository;
    @Mock PaymentPlayerSubscriptionRepository paymentPlayerSubscriptionRepository;
    @Mock CoachSubscriptionChangeRepository coachSubscriptionChangeRepository;
    @Mock PlayerSubscriptionChangeRepository playerSubscriptionChangeRepository;
    @Mock CoachSubscriptionRepository coachSubscriptionRepository;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock ConfigService configService;
    @Mock StripeClient stripeClient;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ParentPlayerLinkRepository parentPlayerLinkRepository;
    @Mock TransactionTemplate transactionTemplate;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock PessimisticLockRetryer lockRetryer;
    @Mock StripeWebhookService stripeWebhookService;
    @Mock PlayerProfileRepository playerProfileRepository;
    @Mock PaymentMethodSyncOutboxSupport paymentMethodSyncOutboxSupport;

    @InjectMocks SubscriptionService service;

    private static final long USER_ID = 7100L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final String PM = "pm_new";

    private PaymentCoachSubscription coachSub;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "self", service);
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(lockRetryer.withBoundedRetry(anyString(), any()))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(coachProfileRepository.findByIdForUpdate(any()))
            .thenReturn(Optional.of(Instancio.create(CoachProfile.class)));
        when(configService.getBoundedLong("subscription.pastDue.gracePeriodDays", 0L, 365L)).thenReturn(7L);

        CoachProfile profile = Instancio.of(CoachProfile.class).set(field(CoachProfile::getId), COACH_ID).create();
        when(coachProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        coachSub = new PaymentCoachSubscription();
        coachSub.setCoachId(COACH_ID);
        coachSub.setTier("INSTRUCTOR");
        coachSub.setStatus("ACTIVE");
        coachSub.setStripeSubscriptionId("sub_coach");
        when(paymentCoachSubscriptionRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(coachSub));
        when(paymentCoachSubscriptionRepository.findByCoachIdForUpdate(COACH_ID)).thenReturn(Optional.of(coachSub));
        when(paymentCoachSubscriptionRepository.findByStripeSubscriptionIdForUpdate("sub_coach"))
            .thenAnswer(inv -> "sub_coach".equals(coachSub.getStripeSubscriptionId())
                ? Optional.of(coachSub) : Optional.empty());
        savedCard = new StripeCustomer();
        savedCard.setParentId(USER_ID);
        savedCard.setStripeCustomerId("cus_7100");
        savedCard.setStripePaymentMethodId(PM);
        when(stripeCustomerRepository.findById(USER_ID)).thenReturn(Optional.of(savedCard));
    }

    private StripeCustomer savedCard;

    // ─── Item 1: payment-method sync ──────────────────────────────────────────────

    @Test
    void activeCoachSubscription_getsNewDefaultPaymentMethod_noInvoiceRetry() throws StripeException {
        service.syncPaymentMethodToSubscriptions(USER_ID, PM);

        verify(stripeClient).updateSubscriptionDefaultPaymentMethod("sub_coach", PM);
        verify(stripeClient, never()).payLatestOpenInvoice(anyString(), anyString(), anyString());
    }

    @Test
    void pastDueSubscription_retriesOpenInvoiceWithNewCard() throws StripeException {
        coachSub.setStatus("PAST_DUE");

        service.syncPaymentMethodToSubscriptions(USER_ID, PM);

        verify(stripeClient).updateSubscriptionDefaultPaymentMethod("sub_coach", PM);
        verify(stripeClient).payLatestOpenInvoice(eq("sub_coach"), eq(PM), startsWith("inv-retry-"));
    }

    @Test
    void parentAndSelfRegisteredPlayerSubscriptions_allSynced_unpaidRetried() throws StripeException {
        ParentPlayerLink link = new ParentPlayerLink();
        link.setPlayerId(81L);
        when(parentPlayerLinkRepository.findByParentId(USER_ID)).thenReturn(List.of(link));
        PlayerProfile own = new PlayerProfile();
        own.setId(82L);
        when(playerProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(own));
        when(paymentPlayerSubscriptionRepository.findByPlayerId(81L)).thenReturn(Optional.of(playerSub(81L, "UNPAID", "sub_p81")));
        when(paymentPlayerSubscriptionRepository.findByPlayerId(82L)).thenReturn(Optional.of(playerSub(82L, "ACTIVE", "sub_p82")));

        service.syncPaymentMethodToSubscriptions(USER_ID, PM);

        verify(stripeClient).updateSubscriptionDefaultPaymentMethod("sub_p81", PM);
        verify(stripeClient).payLatestOpenInvoice(eq("sub_p81"), eq(PM), anyString());
        verify(stripeClient).updateSubscriptionDefaultPaymentMethod("sub_p82", PM);
        verify(stripeClient, never()).payLatestOpenInvoice(eq("sub_p82"), anyString(), anyString());
    }

    @Test
    void cancelledOrUnlinkedSubscriptions_skipped() throws StripeException {
        coachSub.setStatus("CANCELLED");
        coachSub.setStripeSubscriptionId(null);

        service.syncPaymentMethodToSubscriptions(USER_ID, PM);

        verify(stripeClient, never()).updateSubscriptionDefaultPaymentMethod(anyString(), anyString());
    }

    @Test
    void declinedRetry_isNotRetried() throws StripeException {
        coachSub.setStatus("PAST_DUE");
        when(stripeClient.payLatestOpenInvoice(anyString(), anyString(), anyString()))
            .thenThrow(new CardException("declined", "req", "card_declined", null, null, null, 402, null));

        assertThatCode(() -> service.syncPaymentMethodToSubscriptions(USER_ID, PM)).doesNotThrowAnyException();
    }

    @Test
    void permanentStripeRejection_isNotRetried() throws StripeException {
        when(stripeClient.updateSubscriptionDefaultPaymentMethod(anyString(), anyString()))
            .thenThrow(new InvalidRequestException("No such PaymentMethod", "payment_method", "req",
                "resource_missing", 400, null));

        assertThatCode(() -> service.syncPaymentMethodToSubscriptions(USER_ID, PM)).doesNotThrowAnyException();
    }

    // fix/payment-subscription-resume-and-async-sync: the sync runs on the outbox, so a transient Stripe
    // failure must throw (the row is retried) — after every other subscription has still been attempted.
    @Test
    void transientStripeFailure_throwsForOutboxRetry_afterAttemptingEverySubscription() throws StripeException {
        ParentPlayerLink link = new ParentPlayerLink();
        link.setPlayerId(81L);
        when(parentPlayerLinkRepository.findByParentId(USER_ID)).thenReturn(List.of(link));
        when(paymentPlayerSubscriptionRepository.findByPlayerId(81L)).thenReturn(Optional.of(playerSub(81L, "ACTIVE", "sub_p81")));
        when(stripeClient.updateSubscriptionDefaultPaymentMethod("sub_coach", PM))
            .thenThrow(new ApiConnectionException("down"));

        assertThatThrownBy(() -> service.syncPaymentMethodToSubscriptions(USER_ID, PM))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("COACH:sub_coach");
        verify(stripeClient).updateSubscriptionDefaultPaymentMethod("sub_p81", PM);
    }

    @Test
    void supersededCard_isSkipped() throws StripeException {
        savedCard.setStripePaymentMethodId("pm_newer");

        service.syncPaymentMethodToSubscriptions(USER_ID, PM);

        verify(stripeClient, never()).updateSubscriptionDefaultPaymentMethod(anyString(), anyString());
    }

    // ─── fix/payment-subscription-resume-and-async-sync: PAUSED resumes ─────────

    @Test
    void pausedSubscription_getsNewCardThenIsResumed_noInvoiceRetry() throws StripeException {
        coachSub.setStatus("PAUSED");

        service.syncPaymentMethodToSubscriptions(USER_ID, PM);

        var order = inOrder(stripeClient);
        order.verify(stripeClient).updateSubscriptionDefaultPaymentMethod("sub_coach", PM);
        order.verify(stripeClient).resumePausedSubscription(eq("sub_coach"), startsWith("sub-resume-sub_coach-"));
        verify(stripeClient, never()).payLatestOpenInvoice(anyString(), anyString(), anyString());
    }

    @Test
    void activeOrPastDueSubscription_isNotResumed() throws StripeException {
        service.syncPaymentMethodToSubscriptions(USER_ID, PM);
        coachSub.setStatus("PAST_DUE");
        service.syncPaymentMethodToSubscriptions(USER_ID, PM);

        verify(stripeClient, never()).resumePausedSubscription(anyString(), anyString());
    }

    @Test
    void pausedResumeDeclined_isNotRetried_transientResumeFailureIs() throws StripeException {
        coachSub.setStatus("PAUSED");
        when(stripeClient.resumePausedSubscription(anyString(), anyString()))
            .thenThrow(new CardException("declined", "req", "card_declined", null, null, null, 402, null));
        assertThatCode(() -> service.syncPaymentMethodToSubscriptions(USER_ID, PM)).doesNotThrowAnyException();

        doThrow(new ApiConnectionException("down")).when(stripeClient).resumePausedSubscription(anyString(), anyString());
        assertThatThrownBy(() -> service.syncPaymentMethodToSubscriptions(USER_ID, PM))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void savePaymentMethod_storesCardAndEnqueuesSync_noStripeCall() throws StripeException {
        savedCard.setStripePaymentMethodId("pm_old");

        service.savePaymentMethodAndScheduleSync(USER_ID, PM);

        assertThat(savedCard.getStripePaymentMethodId()).isEqualTo(PM);
        verify(stripeCustomerRepository).save(savedCard);
        verify(paymentMethodSyncOutboxSupport).enqueueSync(USER_ID, PM);
        verify(stripeClient, never()).updateSubscriptionDefaultPaymentMethod(anyString(), anyString());
    }

    @Test
    void savePaymentMethod_malformedId_rejectedWith400_nothingStoredOrEnqueued() {
        assertThatThrownBy(() -> service.savePaymentMethodAndScheduleSync(USER_ID, "card_123"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("payment.invalidPaymentMethodId");

        verify(stripeCustomerRepository, never()).save(any());
        verify(paymentMethodSyncOutboxSupport, never()).enqueueSync(any(), anyString());
    }

    // ─── Item 2: UNPAID/PAUSED grace ──────────────────────────────────────────────

    @Test
    void unpaidPastGrace_cancelledAtStripeAndLinkCleared() throws StripeException {
        coachSub.setStatus("UNPAID");
        coachSub.setPastDueSince(Instant.now().minus(30, ChronoUnit.DAYS));
        when(paymentCoachSubscriptionRepository.findByStatusInAndPastDueSinceBefore(any(), any()))
            .thenReturn(List.of(coachSub));

        service.checkPastDueGracePeriod();

        verify(paymentCoachSubscriptionRepository)
            .findByStatusInAndPastDueSinceBefore(eq(List.of("PAST_DUE", "UNPAID", "PAUSED")), any());
        verify(stripeClient).cancelSubscriptionImmediately("sub_coach");
        assertThat(coachSub.getStatus()).isEqualTo("CANCELLED");
        assertThat(coachSub.getStripeSubscriptionId()).isNull();
        assertThat(coachSub.getTier()).isEqualTo("SCOUT");
    }

    @Test
    void pausedPastGrace_cancelledAtStripe() throws StripeException {
        coachSub.setStatus("PAUSED");
        coachSub.setPastDueSince(Instant.now().minus(30, ChronoUnit.DAYS));
        when(paymentCoachSubscriptionRepository.findByStatusInAndPastDueSinceBefore(any(), any()))
            .thenReturn(List.of(coachSub));

        service.checkPastDueGracePeriod();

        verify(stripeClient).cancelSubscriptionImmediately("sub_coach");
        assertThat(coachSub.getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void delinquentRowWithoutClock_getsClockStartedNotCancelled() throws StripeException {
        coachSub.setStatus("UNPAID");
        when(paymentCoachSubscriptionRepository.findByStatusInAndPastDueSinceIsNull(any()))
            .thenReturn(List.of(coachSub));

        service.checkPastDueGracePeriod();

        assertThat(coachSub.getPastDueSince()).isNotNull();
        assertThat(coachSub.getStatus()).isEqualTo("UNPAID");
        verify(stripeClient, never()).cancelSubscriptionImmediately(anyString());
    }

    @Test
    void webhookStatusUnpaid_startsGraceClock_activeClearsIt() {
        service.handleSubscriptionWebhook("customer.subscription.updated", "sub_coach", Map.of("status", "unpaid"));
        assertThat(coachSub.getPastDueSince()).isNotNull();

        service.handleSubscriptionWebhook("customer.subscription.updated", "sub_coach", Map.of("status", "active"));
        assertThat(coachSub.getPastDueSince()).isNull();
    }

    private static PaymentPlayerSubscription playerSub(long playerId, String status, String stripeSubId) {
        PaymentPlayerSubscription sub = new PaymentPlayerSubscription();
        sub.setPlayerId(playerId);
        sub.setTier("PRO");
        sub.setBillingInterval("YEARLY");
        sub.setStatus(status);
        sub.setStripeSubscriptionId(stripeSubId);
        return sub;
    }
}
