package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.SavedPaymentMethodResponse;
import com.softropic.skillars.platform.payment.contract.SessionPackPurchaseResponse;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackTier;
import com.softropic.skillars.platform.payment.repo.SessionPackTierRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.stripe.model.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionPackPaymentServiceTest {

    @Mock SessionPackTierRepository sessionPackTierRepository;
    @Mock SessionPackPurchaseRepository sessionPackPurchaseRepository;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock PlayerProfileRepository playerProfileRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock StripeClient stripeClient;
    @Mock PessimisticLockRetryer lockRetryer;

    @InjectMocks SessionPackPaymentService sessionPackPaymentService;

    @BeforeEach
    void setUpLockRetryer() {
        lenient().when(lockRetryer.withBoundedRetry(any()))
            .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
    }

    private static final Long PARENT_ID = 8001L;
    private static final Long PLAYER_ID = 8002L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final UUID TIER_ID = UUID.randomUUID();

    @Test
    void purchasePack_playerNotFound_throwsResourceNotFound() {
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, null))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * Deferred-81 AC4: replaces the old findByIdAndParentId-based
     * purchasePack_playerNotOwnedByParent_throwsResourceNotFound — a parent-owned player wrongly
     * claimed by a different parentId now fails the XOR ownership check with
     * OperationNotAllowedException (mirroring BookingService.createBookingRequest's own wrong-owner
     * behavior) rather than the old 404, which used to mask "exists but not yours" as "not found".
     */
    @Test
    void purchasePack_playerOwnedByDifferentParent_throwsOperationNotAllowed() {
        PlayerProfile player = new PlayerProfile();
        player.setId(PLAYER_ID);
        player.setParentId(9999L);

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, null))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("does not own this player");
    }

    /**
     * Deferred-81 AC4: a self-registered (no-parent) player — parentId null, userId set — must be
     * able to purchase their own session pack, mirroring BookingService.createBookingRequest's own
     * self-booking XOR branch.
     */
    @Test
    void purchasePack_selfRegisteredPlayer_succeeds() {
        Long selfPlayerUserId = 8003L;
        PlayerProfile player = new PlayerProfile();
        player.setId(PLAYER_ID);
        player.setUserId(selfPlayerUserId);

        SessionPackTier tier = makeActiveTier();

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.of(tier));
        when(stripeCustomerRepository.findById(selfPlayerUserId)).thenReturn(Optional.empty());
        when(paymentGateway.createStripeCustomer(selfPlayerUserId)).thenReturn("cus_test");
        when(stripeCustomerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGateway.chargeAndCapture(TIER_ID, selfPlayerUserId, COACH_ID, tier.getTotalPrice()))
            .thenReturn("pi_test");
        when(sessionPackPurchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SessionPackPurchaseResponse response =
            sessionPackPaymentService.purchasePack(selfPlayerUserId, TIER_ID, PLAYER_ID, "pm_test");

        assertThat(response).isNotNull();
        assertThat(response.remainingSessions()).isEqualTo(tier.getSessionCount());
        verify(paymentGateway).chargeAndCapture(TIER_ID, selfPlayerUserId, COACH_ID, tier.getTotalPrice());
    }

    /** Deferred-81 AC4: someone else calling with a self-registered player's id must still be refused. */
    @Test
    void purchasePack_selfRegisteredPlayerWrongCaller_throwsNotOwned() {
        PlayerProfile player = new PlayerProfile();
        player.setId(PLAYER_ID);
        player.setUserId(8003L);

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, null))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("does not own this profile");
    }

    private SessionPackTier makeActiveTier() {
        SessionPackTier tier = new SessionPackTier();
        tier.setPackTierId(TIER_ID);
        tier.setCoachId(COACH_ID);
        tier.setLabel("Starter Pack");
        tier.setSessionCount(5);
        tier.setTotalPrice(new BigDecimal("100.00"));
        tier.setPricePerSession(new BigDecimal("20.00"));
        tier.setActive(true);
        return tier;
    }

    @Test
    void getPacksForParent_computesAllFourStatuses() {
        Instant now = Instant.now();
        SessionPackPurchase active = purchase(3, now.plus(10, ChronoUnit.DAYS), null);
        SessionPackPurchase exhausted = purchase(0, now.plus(10, ChronoUnit.DAYS), null);
        SessionPackPurchase paused = purchase(3, now.plus(10, ChronoUnit.DAYS), now.plus(5, ChronoUnit.DAYS));
        SessionPackPurchase expired = purchase(3, now.minus(1, ChronoUnit.DAYS), null);

        when(sessionPackPurchaseRepository.findByParentIdOrderByCreatedAtDesc(PARENT_ID))
            .thenReturn(List.of(active, exhausted, paused, expired));
        when(sessionPackTierRepository.findAllById(any())).thenReturn(List.of());

        List<SessionPackPurchaseResponse> responses = sessionPackPaymentService.getPacksForParent(PARENT_ID, null);

        assertThat(responses).hasSize(4);
        assertThat(statusOf(responses, active.getPurchaseId())).isEqualTo("ACTIVE");
        assertThat(statusOf(responses, exhausted.getPurchaseId())).isEqualTo("EXHAUSTED");
        assertThat(statusOf(responses, paused.getPurchaseId())).isEqualTo("PAUSED");
        assertThat(statusOf(responses, expired.getPurchaseId())).isEqualTo("EXPIRED");
    }

    @Test
    void getPacksForParent_filtersByCoachId() {
        Instant now = Instant.now();
        SessionPackPurchase forCoach = purchase(3, now.plus(10, ChronoUnit.DAYS), null);
        forCoach.setCoachId(COACH_ID);

        when(sessionPackPurchaseRepository.findByParentIdAndCoachIdOrderByCreatedAtDesc(PARENT_ID, COACH_ID))
            .thenReturn(List.of(forCoach));
        when(sessionPackTierRepository.findAllById(any())).thenReturn(List.of());

        List<SessionPackPurchaseResponse> responses = sessionPackPaymentService.getPacksForParent(PARENT_ID, COACH_ID);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).purchaseId()).isEqualTo(forCoach.getPurchaseId());
        verify(sessionPackPurchaseRepository, never()).findByParentIdOrderByCreatedAtDesc(any());
    }

    // ─── getSavedPaymentMethod (Deferred-11 AC 4) ─────────────────────────────────

    @Test
    void getSavedPaymentMethod_noStripeCustomerRow_returnsHasCardFalse() {
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.empty());

        SavedPaymentMethodResponse response = sessionPackPaymentService.getSavedPaymentMethod(PARENT_ID);

        assertThat(response).isEqualTo(new SavedPaymentMethodResponse(false, null, null, null, null));
    }

    @Test
    void getSavedPaymentMethod_rowWithNullPaymentMethodId_returnsHasCardFalse() {
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_ID);
        customer.setStripeCustomerId("cus_test");
        customer.setStripePaymentMethodId(null);
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.of(customer));

        SavedPaymentMethodResponse response = sessionPackPaymentService.getSavedPaymentMethod(PARENT_ID);

        assertThat(response).isEqualTo(new SavedPaymentMethodResponse(false, null, null, null, null));
    }

    @Test
    void getSavedPaymentMethod_rowWithPaymentMethodId_returnsHasCardTrueWithDetails() throws Exception {
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_ID);
        customer.setStripeCustomerId("cus_test");
        customer.setStripePaymentMethodId("pm_test_123");
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.of(customer));

        PaymentMethod.Card card = new PaymentMethod.Card();
        card.setBrand("visa");
        card.setLast4("4242");
        card.setExpMonth(12L);
        card.setExpYear(2030L);
        PaymentMethod paymentMethod = new PaymentMethod();
        paymentMethod.setCard(card);
        when(stripeClient.retrievePaymentMethod("pm_test_123")).thenReturn(paymentMethod);

        SavedPaymentMethodResponse response = sessionPackPaymentService.getSavedPaymentMethod(PARENT_ID);

        assertThat(response).isEqualTo(new SavedPaymentMethodResponse(true, "visa", "4242", 12L, 2030L));
    }

    @Test
    void getSavedPaymentMethod_stripeErrorsOnRetrieve_returnsHasCardTrueWithNullDetails() throws Exception {
        // AC 4: a parent must never be told they have no card when they do — a transient Stripe
        // failure on retrieve must not downgrade hasCard to false.
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_ID);
        customer.setStripeCustomerId("cus_test");
        customer.setStripePaymentMethodId("pm_test_123");
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.of(customer));
        when(stripeClient.retrievePaymentMethod("pm_test_123"))
            .thenThrow(new com.stripe.exception.ApiException("Stripe unavailable", "req_1", null, 500, null));

        SavedPaymentMethodResponse response = sessionPackPaymentService.getSavedPaymentMethod(PARENT_ID);

        assertThat(response).isEqualTo(new SavedPaymentMethodResponse(true, null, null, null, null));
    }

    private String statusOf(List<SessionPackPurchaseResponse> responses, UUID purchaseId) {
        return responses.stream()
            .filter(r -> r.purchaseId().equals(purchaseId))
            .findFirst()
            .orElseThrow()
            .status();
    }

    private SessionPackPurchase purchase(int remainingSessions, Instant expiresAt, Instant pausedUntil) {
        SessionPackPurchase p = new SessionPackPurchase();
        p.setPurchaseId(UUID.randomUUID());
        p.setParentId(PARENT_ID);
        p.setPlayerId(PLAYER_ID);
        p.setCoachId(COACH_ID);
        p.setPackTierId(TIER_ID);
        p.setPricePerSession(new BigDecimal("25.00"));
        p.setRemainingSessions(remainingSessions);
        p.setExpiresAt(expiresAt);
        p.setPausedUntil(pausedUntil);
        return p;
    }
}
