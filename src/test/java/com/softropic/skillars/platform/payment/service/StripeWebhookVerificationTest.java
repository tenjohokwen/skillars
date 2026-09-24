package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.config.PaymentProperties;
import com.softropic.skillars.platform.payment.contract.event.CoachStripeOnboardingCompleteEvent;
import com.softropic.skillars.platform.payment.contract.event.CoachSubscriptionOrphanedEvent;
import com.softropic.skillars.platform.payment.contract.exception.WebhookSignatureException;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccount;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.payment.repo.StripeWebhookEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripeWebhookVerificationTest {

    private static final String WEBHOOK_SECRET = "whsec_test_secret_for_unit_tests_only";
    private static final UUID COACH_ID = UUID.fromString("aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb");
    private static final String STRIPE_ACCOUNT_ID = "acct_test123";

    @Mock CoachStripeAccountRepository coachStripeAccountRepository;
    @Mock StripeWebhookEventRepository webhookEventRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PaymentCoachSubscriptionRepository paymentCoachSubscriptionRepository;
    @Mock PaymentPlayerSubscriptionRepository paymentPlayerSubscriptionRepository;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock SubscriptionService subscriptionService;

    // Story Deferred-76 AC7: a real registry, not a mock — Counter.builder(...).register(mock)
    // returns null, so counter-increment assertions need SimpleMeterRegistry (matches
    // CreditRoutingTest's identical pattern for BookingPaymentPersistenceService's counters).
    MeterRegistry meterRegistry;
    PaymentProperties paymentProperties;
    StripeWebhookService webhookService;

    @BeforeEach
    void setUp() {
        paymentProperties = new PaymentProperties();
        paymentProperties.setWebhookSecret(WEBHOOK_SECRET);
        meterRegistry = new SimpleMeterRegistry();
        webhookService = new StripeWebhookService(
            coachStripeAccountRepository, webhookEventRepository, paymentProperties, eventPublisher,
            paymentCoachSubscriptionRepository, paymentPlayerSubscriptionRepository,
            stripeCustomerRepository, coachProfileRepository, meterRegistry);
        // Wire the self-reference so @Transactional dispatch works in unit tests without a Spring context
        ReflectionTestUtils.setField(webhookService, "self", webhookService);
        // subscriptionService is @Autowired @Lazy field injection, not constructor injection —
        // must be wired manually here, same reason as "self" above.
        ReflectionTestUtils.setField(webhookService, "subscriptionService", subscriptionService);
        // Manually call @PostConstruct since this is a unit test without Spring to inject
        webhookService.initializeCounters();
    }

    @Test
    void processWebhook_validSignature_insertsEventAndProcesses() throws Exception {
        String payload = buildAccountUpdatedPayload(STRIPE_ACCOUNT_ID, true, true);
        String sigHeader = buildStripeSignature(WEBHOOK_SECRET, payload);

        CoachStripeAccount account = buildAccount(COACH_ID, STRIPE_ACCOUNT_ID, "PENDING", false, false);
        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(coachStripeAccountRepository.findByStripeAccountId(STRIPE_ACCOUNT_ID))
            .thenReturn(Optional.of(account));

        webhookService.processWebhook(payload, sigHeader);

        verify(webhookEventRepository).insertIfAbsent(any(), any());
        assertThat(account.getOnboardingStatus()).isEqualTo("COMPLETE");
        assertThat(account.isChargesEnabled()).isTrue();
        assertThat(account.isPayoutsEnabled()).isTrue();

        ArgumentCaptor<CoachStripeOnboardingCompleteEvent> captor =
            ArgumentCaptor.forClass(CoachStripeOnboardingCompleteEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().coachId()).isEqualTo(COACH_ID);
    }

    @Test
    void processWebhook_invalidSignature_throwsWebhookSignatureException() {
        String payload = buildAccountUpdatedPayload(STRIPE_ACCOUNT_ID, true, true);
        String badHeader = "t=1234567890,v1=invalidsignaturehex";

        assertThatThrownBy(() -> webhookService.processWebhook(payload, badHeader))
            .isInstanceOf(WebhookSignatureException.class)
            .extracting("errorCode")
            .isEqualTo("payment.webhookSignatureInvalid");

        verify(webhookEventRepository, never()).insertIfAbsent(any(), any());
        verify(coachStripeAccountRepository, never()).findByStripeAccountId(any());
    }

    @Test
    void processWebhook_duplicateEventId_returnsWithoutProcessing() throws Exception {
        String payload = buildAccountUpdatedPayload(STRIPE_ACCOUNT_ID, true, true);
        String sigHeader = buildStripeSignature(WEBHOOK_SECRET, payload);

        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(0);

        webhookService.processWebhook(payload, sigHeader);

        verify(coachStripeAccountRepository, never()).findByStripeAccountId(any());
    }

    @Test
    void processWebhook_accountUpdated_chargesDisabled_transitionsToRestricted() throws Exception {
        String payload = buildAccountUpdatedPayload(STRIPE_ACCOUNT_ID, false, false);
        String sigHeader = buildStripeSignature(WEBHOOK_SECRET, payload);

        CoachStripeAccount account = buildAccount(COACH_ID, STRIPE_ACCOUNT_ID, "COMPLETE", true, true);
        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(coachStripeAccountRepository.findByStripeAccountId(STRIPE_ACCOUNT_ID))
            .thenReturn(Optional.of(account));

        webhookService.processWebhook(payload, sigHeader);

        assertThat(account.isChargesEnabled()).isFalse();
        assertThat(account.isPayoutsEnabled()).isFalse();
        assertThat(account.getOnboardingStatus()).isEqualTo("RESTRICTED");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void processWebhook_invoicePaymentFailed_incrementsInvoiceFailedCounter() throws Exception {
        String payload = buildInvoicePaymentFailedPayload("sub_test123");
        String sigHeader = buildStripeSignature(WEBHOOK_SECRET, payload);
        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(1);

        webhookService.processWebhook(payload, sigHeader);

        verify(subscriptionService).handleSubscriptionWebhook("invoice.payment_failed", "sub_test123", java.util.Map.of());
        Counter counter = meterRegistry.find("subscription.payment.invoice_failed").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void processWebhook_invoicePaymentFailed_noSubscription_doesNotIncrementCounter() throws Exception {
        String payload = buildInvoicePaymentFailedPayload(null);
        String sigHeader = buildStripeSignature(WEBHOOK_SECRET, payload);
        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(1);

        webhookService.processWebhook(payload, sigHeader);

        verify(subscriptionService, never()).handleSubscriptionWebhook(any(), any(), any());
        // initializeCounters() eagerly pre-registers this counter at 0 (standard Micrometer practice,
        // so it appears on dashboards before its first increment) — it's always registered, just
        // never incremented when there's no subscription to attribute the failure to.
        Counter counter = meterRegistry.find("subscription.payment.invoice_failed").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(0.0);
    }

    // ── skillars-deferred-133 AC3: orphaned live-subscription alerting ─────────

    private static final String STRIPE_CUSTOMER_ID = "cus_test_orphan_001";
    private static final String STRIPE_SUB_ID = "sub_test_orphan_001";
    private static final Long COACH_USER_ID = 9330_000_001L;
    private static final UUID COACH_PROFILE_ID = UUID.fromString("aaaaaaaa-9330-0001-0001-000000000001");

    @Test
    void processWebhook_subscriptionUpdated_activeStatus_noLocalMatch_resolvableCoach_noRecentRow_publishesOrphanedEvent()
        throws Exception {
        String payload = buildSubscriptionUpdatedPayload(STRIPE_SUB_ID, STRIPE_CUSTOMER_ID, "active");
        String sigHeader = buildStripeSignature(WEBHOOK_SECRET, payload);
        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(paymentCoachSubscriptionRepository.findByStripeSubscriptionId(STRIPE_SUB_ID)).thenReturn(Optional.empty());
        when(paymentPlayerSubscriptionRepository.findByStripeSubscriptionId(STRIPE_SUB_ID)).thenReturn(Optional.empty());
        when(stripeCustomerRepository.findByStripeCustomerId(STRIPE_CUSTOMER_ID))
            .thenReturn(List.of(buildStripeCustomer(COACH_USER_ID, STRIPE_CUSTOMER_ID)));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(buildCoachProfile()));
        when(paymentCoachSubscriptionRepository.findByCoachId(COACH_PROFILE_ID)).thenReturn(Optional.empty());

        webhookService.processWebhook(payload, sigHeader);

        ArgumentCaptor<CoachSubscriptionOrphanedEvent> captor =
            ArgumentCaptor.forClass(CoachSubscriptionOrphanedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getCoachProfileId()).isEqualTo(COACH_PROFILE_ID);
        assertThat(captor.getValue().getStripeSubscriptionId()).isEqualTo(STRIPE_SUB_ID);
        verify(subscriptionService, never()).handleSubscriptionWebhook(any(), any(), any());
    }

    @Test
    void processWebhook_subscriptionUpdated_activeStatus_recentPaymentCoachSubscriptionRow_doesNotPublishEvent()
        throws Exception {
        String payload = buildSubscriptionUpdatedPayload(STRIPE_SUB_ID, STRIPE_CUSTOMER_ID, "active");
        String sigHeader = buildStripeSignature(WEBHOOK_SECRET, payload);
        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(paymentCoachSubscriptionRepository.findByStripeSubscriptionId(STRIPE_SUB_ID)).thenReturn(Optional.empty());
        when(paymentPlayerSubscriptionRepository.findByStripeSubscriptionId(STRIPE_SUB_ID)).thenReturn(Optional.empty());
        when(stripeCustomerRepository.findByStripeCustomerId(STRIPE_CUSTOMER_ID))
            .thenReturn(List.of(buildStripeCustomer(COACH_USER_ID, STRIPE_CUSTOMER_ID)));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(buildCoachProfile()));
        PaymentCoachSubscription recentRow = new PaymentCoachSubscription();
        recentRow.setCoachId(COACH_PROFILE_ID);
        recentRow.setUpdatedAt(Instant.now().minus(2, ChronoUnit.MINUTES));
        when(paymentCoachSubscriptionRepository.findByCoachId(COACH_PROFILE_ID)).thenReturn(Optional.of(recentRow));

        webhookService.processWebhook(payload, sigHeader);

        verify(eventPublisher, never()).publishEvent(any(CoachSubscriptionOrphanedEvent.class));
    }

    @Test
    void processWebhook_subscriptionDeleted_noLocalMatch_doesNotAttemptResolutionOrAlert() throws Exception {
        String payload = buildSubscriptionDeletedPayload(STRIPE_SUB_ID, STRIPE_CUSTOMER_ID, "canceled");
        String sigHeader = buildStripeSignature(WEBHOOK_SECRET, payload);
        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(paymentCoachSubscriptionRepository.findByStripeSubscriptionId(STRIPE_SUB_ID)).thenReturn(Optional.empty());
        when(paymentPlayerSubscriptionRepository.findByStripeSubscriptionId(STRIPE_SUB_ID)).thenReturn(Optional.empty());

        webhookService.processWebhook(payload, sigHeader);

        verify(eventPublisher, never()).publishEvent(any(CoachSubscriptionOrphanedEvent.class));
        verify(stripeCustomerRepository, never()).findByStripeCustomerId(any());
    }

    @Test
    void processWebhook_subscriptionUpdated_terminalStatus_noLocalMatch_doesNotAlert() throws Exception {
        String payload = buildSubscriptionUpdatedPayload(STRIPE_SUB_ID, STRIPE_CUSTOMER_ID, "canceled");
        String sigHeader = buildStripeSignature(WEBHOOK_SECRET, payload);
        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(paymentCoachSubscriptionRepository.findByStripeSubscriptionId(STRIPE_SUB_ID)).thenReturn(Optional.empty());
        when(paymentPlayerSubscriptionRepository.findByStripeSubscriptionId(STRIPE_SUB_ID)).thenReturn(Optional.empty());

        webhookService.processWebhook(payload, sigHeader);

        verify(eventPublisher, never()).publishEvent(any(CoachSubscriptionOrphanedEvent.class));
        verify(stripeCustomerRepository, never()).findByStripeCustomerId(any());
    }

    @Test
    void processWebhook_subscriptionUpdated_secondActiveEventForSameUnresolvedSubscription_publishesEventEachTime()
        throws Exception {
        // insertAlert's own (referenceId, type, OPEN) dedup — not this webhook's own state — is what
        // suppresses a duplicate AdminAlert; that is AdminAlertEventListener's own responsibility, not
        // StripeWebhookService's (see this fix's own Test guidance / Javadoc). This webhook always
        // publishes the event for an unresolved live-status update; verified across two independent
        // Stripe event ids since handleEventAtomically's own idempotency dedup is per-event-id.
        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(paymentCoachSubscriptionRepository.findByStripeSubscriptionId(STRIPE_SUB_ID)).thenReturn(Optional.empty());
        when(paymentPlayerSubscriptionRepository.findByStripeSubscriptionId(STRIPE_SUB_ID)).thenReturn(Optional.empty());
        when(stripeCustomerRepository.findByStripeCustomerId(STRIPE_CUSTOMER_ID))
            .thenReturn(List.of(buildStripeCustomer(COACH_USER_ID, STRIPE_CUSTOMER_ID)));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(buildCoachProfile()));
        when(paymentCoachSubscriptionRepository.findByCoachId(COACH_PROFILE_ID)).thenReturn(Optional.empty());

        String firstPayload = buildSubscriptionUpdatedEventPayload(
            "evt_orphan_first", STRIPE_SUB_ID, STRIPE_CUSTOMER_ID, "active");
        webhookService.processWebhook(firstPayload, buildStripeSignature(WEBHOOK_SECRET, firstPayload));
        String secondPayload = buildSubscriptionUpdatedEventPayload(
            "evt_orphan_second", STRIPE_SUB_ID, STRIPE_CUSTOMER_ID, "active");
        webhookService.processWebhook(secondPayload, buildStripeSignature(WEBHOOK_SECRET, secondPayload));

        verify(eventPublisher, org.mockito.Mockito.times(2))
            .publishEvent(any(CoachSubscriptionOrphanedEvent.class));
    }

    // ── skillars-deferred-134 AC2: orphaned invoice.payment_failed alerting ────

    @Test
    void processWebhook_invoicePaymentFailed_noLocalMatch_resolvableCoach_noRecentRow_publishesOrphanedEvent()
        throws Exception {
        String payload = buildInvoicePaymentFailedPayload("evt_test_invoice_orphan_001", STRIPE_SUB_ID, STRIPE_CUSTOMER_ID);
        String sigHeader = buildStripeSignature(WEBHOOK_SECRET, payload);
        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(paymentCoachSubscriptionRepository.findByStripeSubscriptionId(STRIPE_SUB_ID)).thenReturn(Optional.empty());
        when(paymentPlayerSubscriptionRepository.findByStripeSubscriptionId(STRIPE_SUB_ID)).thenReturn(Optional.empty());
        when(stripeCustomerRepository.findByStripeCustomerId(STRIPE_CUSTOMER_ID))
            .thenReturn(List.of(buildStripeCustomer(COACH_USER_ID, STRIPE_CUSTOMER_ID)));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(buildCoachProfile()));
        when(paymentCoachSubscriptionRepository.findByCoachId(COACH_PROFILE_ID)).thenReturn(Optional.empty());

        webhookService.processWebhook(payload, sigHeader);

        ArgumentCaptor<CoachSubscriptionOrphanedEvent> captor =
            ArgumentCaptor.forClass(CoachSubscriptionOrphanedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getCoachProfileId()).isEqualTo(COACH_PROFILE_ID);
        assertThat(captor.getValue().getStripeSubscriptionId()).isEqualTo(STRIPE_SUB_ID);
        // Regression coverage for the untouched PAST_DUE update path: the orphan check does not skip
        // the delegation, unlike handleSubscriptionUpdated's own orphan branch.
        verify(subscriptionService).handleSubscriptionWebhook("invoice.payment_failed", STRIPE_SUB_ID, java.util.Map.of());
    }

    @Test
    void processWebhook_invoicePaymentFailed_matchedCoachSubscription_doesNotPublishEvent() throws Exception {
        String payload = buildInvoicePaymentFailedPayload("evt_test_invoice_matched_001", STRIPE_SUB_ID, STRIPE_CUSTOMER_ID);
        String sigHeader = buildStripeSignature(WEBHOOK_SECRET, payload);
        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(paymentCoachSubscriptionRepository.findByStripeSubscriptionId(STRIPE_SUB_ID))
            .thenReturn(Optional.of(new PaymentCoachSubscription()));

        webhookService.processWebhook(payload, sigHeader);

        verify(eventPublisher, never()).publishEvent(any(CoachSubscriptionOrphanedEvent.class));
        verify(stripeCustomerRepository, never()).findByStripeCustomerId(any());
        // The already-working PAST_DUE update path must still run, matched or not.
        verify(subscriptionService).handleSubscriptionWebhook("invoice.payment_failed", STRIPE_SUB_ID, java.util.Map.of());
    }

    @Test
    void processWebhook_invoicePaymentFailed_orphanedWithinGraceWindow_doesNotPublishEvent() throws Exception {
        String payload = buildInvoicePaymentFailedPayload("evt_test_invoice_grace_001", STRIPE_SUB_ID, STRIPE_CUSTOMER_ID);
        String sigHeader = buildStripeSignature(WEBHOOK_SECRET, payload);
        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(paymentCoachSubscriptionRepository.findByStripeSubscriptionId(STRIPE_SUB_ID)).thenReturn(Optional.empty());
        when(paymentPlayerSubscriptionRepository.findByStripeSubscriptionId(STRIPE_SUB_ID)).thenReturn(Optional.empty());
        when(stripeCustomerRepository.findByStripeCustomerId(STRIPE_CUSTOMER_ID))
            .thenReturn(List.of(buildStripeCustomer(COACH_USER_ID, STRIPE_CUSTOMER_ID)));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(buildCoachProfile()));
        PaymentCoachSubscription recentRow = new PaymentCoachSubscription();
        recentRow.setCoachId(COACH_PROFILE_ID);
        recentRow.setUpdatedAt(Instant.now().minus(2, ChronoUnit.MINUTES));
        when(paymentCoachSubscriptionRepository.findByCoachId(COACH_PROFILE_ID)).thenReturn(Optional.of(recentRow));

        webhookService.processWebhook(payload, sigHeader);

        verify(eventPublisher, never()).publishEvent(any(CoachSubscriptionOrphanedEvent.class));
    }

    @Test
    void processWebhook_invoicePaymentFailed_orphanedResolvesToPlayer_doesNotPublishEvent() throws Exception {
        String payload = buildInvoicePaymentFailedPayload("evt_test_invoice_player_001", STRIPE_SUB_ID, STRIPE_CUSTOMER_ID);
        String sigHeader = buildStripeSignature(WEBHOOK_SECRET, payload);
        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(paymentCoachSubscriptionRepository.findByStripeSubscriptionId(STRIPE_SUB_ID)).thenReturn(Optional.empty());
        when(paymentPlayerSubscriptionRepository.findByStripeSubscriptionId(STRIPE_SUB_ID)).thenReturn(Optional.empty());
        when(stripeCustomerRepository.findByStripeCustomerId(STRIPE_CUSTOMER_ID))
            .thenReturn(List.of(buildStripeCustomer(COACH_USER_ID, STRIPE_CUSTOMER_ID)));
        // Resolves to a customer, but not one with a CoachProfile -- a player -- coach-only scoping.
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.empty());

        webhookService.processWebhook(payload, sigHeader);

        verify(eventPublisher, never()).publishEvent(any(CoachSubscriptionOrphanedEvent.class));
        verify(paymentCoachSubscriptionRepository, never()).findByCoachId(any());
    }

    /**
     * insertAlert's own {@code (referenceId, type, OPEN)} dedup — not this webhook's own state — is
     * what suppresses a duplicate {@code AdminAlert} for two independent orphaned events resolving to
     * the same coach; that is {@code AdminAlertEventListener}'s own responsibility (skillars-deferred-134
     * AC1), proven with a real DB read against a genuine concurrent race by
     * {@code AdminAlertEventListenerConcurrencyIT} — both this webhook's orphan path and {@code
     * handleSubscriptionUpdated}'s publish the exact same {@code CoachSubscriptionOrphanedEvent} /
     * {@code SUBSCRIPTION_ORPHANED} type through that same {@code insertAlert} call, so that proof
     * covers this trigger path too. This webhook always publishes for an unresolved orphan, mirroring
     * {@code processWebhook_subscriptionUpdated_secondActiveEventForSameUnresolvedSubscription_publishesEventEachTime}'s
     * identical mock-level shape.
     */
    @Test
    void processWebhook_invoicePaymentFailed_secondOrphanedEventForSameCoach_publishesEventEachTime()
        throws Exception {
        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        when(paymentCoachSubscriptionRepository.findByStripeSubscriptionId(STRIPE_SUB_ID)).thenReturn(Optional.empty());
        when(paymentPlayerSubscriptionRepository.findByStripeSubscriptionId(STRIPE_SUB_ID)).thenReturn(Optional.empty());
        when(stripeCustomerRepository.findByStripeCustomerId(STRIPE_CUSTOMER_ID))
            .thenReturn(List.of(buildStripeCustomer(COACH_USER_ID, STRIPE_CUSTOMER_ID)));
        when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(buildCoachProfile()));
        when(paymentCoachSubscriptionRepository.findByCoachId(COACH_PROFILE_ID)).thenReturn(Optional.empty());

        String firstPayload = buildInvoicePaymentFailedPayload("evt_invoice_orphan_first", STRIPE_SUB_ID, STRIPE_CUSTOMER_ID);
        webhookService.processWebhook(firstPayload, buildStripeSignature(WEBHOOK_SECRET, firstPayload));
        String secondPayload = buildInvoicePaymentFailedPayload("evt_invoice_orphan_second", STRIPE_SUB_ID, STRIPE_CUSTOMER_ID);
        webhookService.processWebhook(secondPayload, buildStripeSignature(WEBHOOK_SECRET, secondPayload));

        verify(eventPublisher, org.mockito.Mockito.times(2))
            .publishEvent(any(CoachSubscriptionOrphanedEvent.class));
    }

    private static StripeCustomer buildStripeCustomer(Long parentId, String stripeCustomerId) {
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(parentId);
        customer.setStripeCustomerId(stripeCustomerId);
        return customer;
    }

    private static CoachProfile buildCoachProfile() {
        CoachProfile profile = new CoachProfile();
        profile.setId(COACH_PROFILE_ID);
        profile.setUserId(COACH_USER_ID);
        return profile;
    }

    private static String buildSubscriptionUpdatedPayload(String subId, String customerId, String status) {
        return buildSubscriptionUpdatedEventPayload("evt_test_sub_updated_001", subId, customerId, status);
    }

    private static String buildSubscriptionUpdatedEventPayload(String eventId, String subId, String customerId,
                                                                 String status) {
        return buildSubscriptionEventPayload(eventId, "customer.subscription.updated", subId, customerId, status);
    }

    private static String buildSubscriptionDeletedPayload(String subId, String customerId, String status) {
        return buildSubscriptionEventPayload("evt_test_sub_deleted_001", "customer.subscription.deleted",
            subId, customerId, status);
    }

    private static String buildSubscriptionEventPayload(String eventId, String eventType, String subId,
                                                          String customerId, String status) {
        return """
            {
              "id": "%s",
              "object": "event",
              "type": "%s",
              "data": {
                "object": {
                  "id": "%s",
                  "object": "subscription",
                  "customer": "%s",
                  "status": "%s"
                }
              }
            }
            """.formatted(eventId, eventType, subId, customerId, status);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String buildStripeSignature(String secret, String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String signedPayload = timestamp + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",v1=" + sig;
    }

    private static String buildAccountUpdatedPayload(String accountId, boolean chargesEnabled, boolean payoutsEnabled) {
        return """
            {
              "id": "evt_test_001",
              "object": "event",
              "type": "account.updated",
              "data": {
                "object": {
                  "id": "%s",
                  "object": "account",
                  "charges_enabled": %b,
                  "payouts_enabled": %b
                }
              }
            }
            """.formatted(accountId, chargesEnabled, payoutsEnabled);
    }

    private static String buildInvoicePaymentFailedPayload(String subscriptionId) {
        return buildInvoicePaymentFailedPayload("evt_test_invoice_001", subscriptionId, null);
    }

    private static String buildInvoicePaymentFailedPayload(String eventId, String subscriptionId, String customerId) {
        String subscriptionField = subscriptionId == null ? "null" : "\"" + subscriptionId + "\"";
        String customerField = customerId == null ? "null" : "\"" + customerId + "\"";
        return """
            {
              "id": "%s",
              "object": "event",
              "type": "invoice.payment_failed",
              "data": {
                "object": {
                  "id": "in_test_001",
                  "object": "invoice",
                  "subscription": %s,
                  "customer": %s
                }
              }
            }
            """.formatted(eventId, subscriptionField, customerField);
    }

    private static CoachStripeAccount buildAccount(UUID coachId, String stripeAccountId,
                                                    String status, boolean charges, boolean payouts) {
        CoachStripeAccount account = new CoachStripeAccount();
        account.setCoachId(coachId);
        account.setStripeAccountId(stripeAccountId);
        account.setOnboardingStatus(status);
        account.setChargesEnabled(charges);
        account.setPayoutsEnabled(payouts);
        return account;
    }
}
