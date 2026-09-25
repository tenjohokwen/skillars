package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.admin.contract.AdminAlertStatus;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.param.SubscriptionListParams;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-135 AC2: proves {@link SubscriptionService#reconcileStripeSubscriptions()} against
 * a real Testcontainers Postgres, exercising the real event/listener chain
 * ({@code CoachSubscriptionOrphanedEvent} → {@code AdminAlertEventListener.onCoachSubscriptionOrphaned})
 * rather than a mocked interaction count.
 *
 * <h2>Why {@code @MockitoBean StripeClient}, not real WireMock HTTP stubs</h2>
 *
 * {@link com.softropic.skillars.platform.payment.BasePaymentIT}'s own Javadoc documents this as an
 * established, deliberate alternative to raw WireMock stubbing ("use {@code @MockitoBean StripeClient}
 * to mock at the SDK-wrapper layer without HTTP stubs"). Every production call this sweep makes to the
 * Stripe SDK funnels through {@link StripeClient#listSubscriptionsByStatus}, so mocking at that one
 * seam exercises the exact same boundary a raw HTTP stub would, without hand-rolling Stripe's own
 * pagination-cursor JSON shape (a real risk of testing WireMock's JSON-matching fidelity instead of
 * this sweep's own logic). The pinned SDK's actual {@code Subscription.list}/{@code
 * SubscriptionListParams}/{@code autoPagingIterable} call shape was independently confirmed via
 * {@code javap} against the pinned {@code stripe-java-28.4.0.jar} before writing {@link
 * StripeClient#listSubscriptionsByStatus} — see that method's own Javadoc.
 */
class SubscriptionServiceStripeReconciliationIT extends BasePaymentIT {

    @Autowired SubscriptionService subscriptionService;
    @Autowired StripeCustomerRepository stripeCustomerRepository;
    @Autowired PaymentCoachSubscriptionRepository paymentCoachSubscriptionRepository;
    @Autowired AdminAlertRepository adminAlertRepository;
    @Autowired StripeWebhookService stripeWebhookService;

    @MockitoBean StripeClient stripeClient;

    /** Every test stubs all 3 statuses (only some populated) so the sweep's own per-status loop is exercised fully. */
    private void stubStatuses(List<Subscription> active, List<Subscription> trialing, List<Subscription> pastDue)
            throws StripeException {
        when(stripeClient.listSubscriptionsByStatus(eq(SubscriptionListParams.Status.ACTIVE))).thenReturn(active);
        when(stripeClient.listSubscriptionsByStatus(eq(SubscriptionListParams.Status.TRIALING))).thenReturn(trialing);
        when(stripeClient.listSubscriptionsByStatus(eq(SubscriptionListParams.Status.PAST_DUE))).thenReturn(pastDue);
    }

    private Subscription liveSub(String id, String customerId) {
        Subscription sub = new Subscription();
        sub.setId(id);
        sub.setCustomer(customerId);
        sub.setStatus("active");
        return sub;
    }

    @Test
    void reconcileStripeSubscriptions_liveSubscriptionWithNoLocalRow_raisesOrphanAlert() throws Exception {
        long userId = 96603001L;
        insertTestParent(userId, "reconcile1@skillars-test.com");
        UUID coachId = insertTestCoach(userId, "reconcile1coach@skillars-test.com", "Reconcile Coach 1");
        stripeCustomerRepository.save(newStripeCustomer(userId, "cus_reconcile1"));

        stubStatuses(List.of(liveSub("sub_reconcile1", "cus_reconcile1")), List.of(), List.of());

        subscriptionService.reconcileStripeSubscriptions();

        assertThat(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(
                coachId.toString(), AdminAlertType.SUBSCRIPTION_ORPHANED, AdminAlertStatus.OPEN))
            .as("a live Stripe subscription with no matching payment.coach_subscriptions row must raise an alert")
            .isPresent();
    }

    @Test
    void reconcileStripeSubscriptions_liveSubscriptionAlreadyTrackedLocally_noAlert() throws Exception {
        long userId = 96603002L;
        insertTestParent(userId, "reconcile2@skillars-test.com");
        UUID coachId = insertTestCoach(userId, "reconcile2coach@skillars-test.com", "Reconcile Coach 2");
        stripeCustomerRepository.save(newStripeCustomer(userId, "cus_reconcile2"));

        PaymentCoachSubscription tracked = new PaymentCoachSubscription();
        tracked.setCoachId(coachId);
        tracked.setStripeSubscriptionId("sub_reconcile2");
        paymentCoachSubscriptionRepository.save(tracked);

        stubStatuses(List.of(liveSub("sub_reconcile2", "cus_reconcile2")), List.of(), List.of());

        subscriptionService.reconcileStripeSubscriptions();

        assertThat(adminAlertRepository.countOpenByReferenceId(coachId.toString()))
            .as("a Stripe subscription already matched by stripeSubscriptionId must not raise an alert")
            .isZero();
    }

    @Test
    void reconcileStripeSubscriptions_recentlyTouchedLocalRowForSameCoach_gracePeriodSuppresses() throws Exception {
        long userId = 96603003L;
        insertTestParent(userId, "reconcile3@skillars-test.com");
        UUID coachId = insertTestCoach(userId, "reconcile3coach@skillars-test.com", "Reconcile Coach 3");
        stripeCustomerRepository.save(newStripeCustomer(userId, "cus_reconcile3"));

        // A DIFFERENT stripeSubscriptionId than the one the sweep will see below -- so
        // findByStripeSubscriptionId misses, forcing resolution through resolveCoachAndAlertIfOrphaned,
        // which then finds THIS row via findByCoachId with a just-persisted (recent) updatedAt.
        PaymentCoachSubscription recentlyTouched = new PaymentCoachSubscription();
        recentlyTouched.setCoachId(coachId);
        recentlyTouched.setStripeSubscriptionId("sub_reconcile3_old");
        paymentCoachSubscriptionRepository.save(recentlyTouched);

        stubStatuses(List.of(liveSub("sub_reconcile3_new", "cus_reconcile3")), List.of(), List.of());

        subscriptionService.reconcileStripeSubscriptions();

        assertThat(adminAlertRepository.countOpenByReferenceId(coachId.toString()))
            .as("a coach with a just-touched payment.coach_subscriptions row must be treated as still settling")
            .isZero();
    }

    @Test
    void reconcileStripeSubscriptions_multipleOrphansInSameStatusPage_allProcessed() throws Exception {
        long userIdA = 96603004L;
        long userIdB = 96603005L;
        insertTestParent(userIdA, "reconcile4a@skillars-test.com");
        insertTestParent(userIdB, "reconcile4b@skillars-test.com");
        UUID coachIdA = insertTestCoach(userIdA, "reconcile4acoach@skillars-test.com", "Reconcile Coach 4A");
        UUID coachIdB = insertTestCoach(userIdB, "reconcile4bcoach@skillars-test.com", "Reconcile Coach 4B");
        stripeCustomerRepository.save(newStripeCustomer(userIdA, "cus_reconcile4a"));
        stripeCustomerRepository.save(newStripeCustomer(userIdB, "cus_reconcile4b"));

        stubStatuses(List.of(
            liveSub("sub_reconcile4a", "cus_reconcile4a"),
            liveSub("sub_reconcile4b", "cus_reconcile4b")), List.of(), List.of());

        subscriptionService.reconcileStripeSubscriptions();

        assertThat(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(
                coachIdA.toString(), AdminAlertType.SUBSCRIPTION_ORPHANED, AdminAlertStatus.OPEN)).isPresent();
        assertThat(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(
                coachIdB.toString(), AdminAlertType.SUBSCRIPTION_ORPHANED, AdminAlertStatus.OPEN)).isPresent();
    }

    /**
     * Exercises AC1's own fixed dedup mechanism from the SWEEP's side, not just the webhook's — mirrors
     * {@code StripeWebhookVerificationTest}'s own webhook-side coverage of the identical dedup.
     */
    @Test
    void reconcileStripeSubscriptions_coachAlreadyAlertedByWebhookPath_noSecondAlert() throws Exception {
        long userId = 96603006L;
        insertTestParent(userId, "reconcile5@skillars-test.com");
        UUID coachId = insertTestCoach(userId, "reconcile5coach@skillars-test.com", "Reconcile Coach 5");
        stripeCustomerRepository.save(newStripeCustomer(userId, "cus_reconcile5"));

        // Simulate the webhook path having already raised this exact alert moments earlier, via the
        // real shared resolution chain -- not a hand-inserted row -- so this genuinely exercises AC1's
        // dedup mechanism rather than assuming its shape.
        stripeWebhookService.resolveCoachAndAlertIfOrphaned("cus_reconcile5", "sub_reconcile5_webhook");
        assertThat(adminAlertRepository.countOpenByReferenceId(coachId.toString())).isEqualTo(1L);

        stubStatuses(List.of(liveSub("sub_reconcile5_sweep", "cus_reconcile5")), List.of(), List.of());

        subscriptionService.reconcileStripeSubscriptions();

        assertThat(adminAlertRepository.countOpenByReferenceId(coachId.toString()))
            .as("the sweep finding the same underlying drift must not raise a second, redundant alert")
            .isEqualTo(1L);
    }

    private StripeCustomer newStripeCustomer(long parentId, String stripeCustomerId) {
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(parentId);
        customer.setStripeCustomerId(stripeCustomerId);
        customer.setCreatedAt(Instant.now());
        return customer;
    }
}
