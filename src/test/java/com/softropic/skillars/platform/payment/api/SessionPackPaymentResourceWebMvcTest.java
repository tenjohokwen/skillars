package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.platform.booking.contract.PauseConflictResponse;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.config.PaymentProperties;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.SavedPaymentMethodResponse;
import com.softropic.skillars.platform.payment.contract.SessionPackPurchaseResponse;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.payment.service.PackSessionService;
import com.softropic.skillars.platform.payment.service.SessionPackPaymentService;
import com.softropic.skillars.platform.security.infrastructure.jwt.JwtSecretService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.service.VideoMetrics;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deferred-11 AC 1 / AC 4: publishable-key config endpoint and saved-payment-method endpoint.
 */
@WebMvcTest({SessionPackPaymentResource.class, PaymentApiAdvice.class})
@Import(SessionPackPaymentResourceWebMvcTest.TestSecurityConfig.class)
class SessionPackPaymentResourceWebMvcTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(
                    (req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .build();
        }
    }

    private static final Long PARENT_USER_ID = 42L;

    @Autowired MockMvc mockMvc;

    @MockitoBean SessionPackPaymentService sessionPackPaymentService;
    @MockitoBean PackSessionService packSessionService;
    @MockitoBean StripeCustomerRepository stripeCustomerRepository;
    @MockitoBean PaymentGateway paymentGateway;
    @MockitoBean com.softropic.skillars.platform.payment.service.StripeCustomerEmailSyncOutboxSupport stripeCustomerEmailSync;
    @MockitoBean CoachProfileRepository coachProfileRepository;
    @MockitoBean SecurityUtil securityUtil;
    @MockitoBean PaymentProperties paymentProperties;
    @MockitoBean JwtSecretService jwtSecretService;
    @MockitoBean VideoMetrics videoMetrics;
    @MockitoBean com.softropic.skillars.platform.payment.service.CoachSoldPackQueryService coachSoldPackQueryService;
    @MockitoBean com.softropic.skillars.platform.payment.service.SubscriptionService subscriptionService;

    // ─── GET /api/payment/coaches/me/session-packs (feat/coach-workflow-gaps A20) ────────────

    @Test
    @WithMockUser(roles = "COACH")
    void getMySoldPacks_coach_returnsCallersPacksWithClampedPageSize() throws Exception {
        java.util.UUID coachId = java.util.UUID.randomUUID();
        com.softropic.skillars.platform.marketplace.repo.CoachProfile coach =
            new com.softropic.skillars.platform.marketplace.repo.CoachProfile();
        coach.setId(coachId);
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        when(coachProfileRepository.findByUserId(PARENT_USER_ID)).thenReturn(java.util.Optional.of(coach));
        com.softropic.skillars.platform.payment.contract.CoachSoldPackDto dto =
            new com.softropic.skillars.platform.payment.contract.CoachSoldPackDto(
                java.util.UUID.randomUUID(), "Pat Parent", 7L, "Kid", "5 pack", 5, 2,
                java.time.Instant.parse("2026-11-01T00:00:00Z"), null, java.time.Instant.parse("2026-09-01T00:00:00Z"),
                "ACTIVE", true, true, null);
        when(coachSoldPackQueryService.getSoldPacks(org.mockito.ArgumentMatchers.eq(coachId), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(dto)));

        mockMvc.perform(get("/api/payment/coaches/me/session-packs").param("size", "500"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].buyerName").value("Pat Parent"))
            .andExpect(jsonPath("$.content[0].extendable").value(true));

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> pageable =
            org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        org.mockito.Mockito.verify(coachSoldPackQueryService).getSoldPacks(org.mockito.ArgumentMatchers.eq(coachId), pageable.capture());
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void getMySoldPacks_parent_returns403() throws Exception {
        mockMvc.perform(get("/api/payment/coaches/me/session-packs"))
            .andExpect(status().isForbidden());
    }

    // ─── GET /api/payment/stripe/config ────────────────────────────────────────

    @Test
    @WithMockUser(roles = "PARENT")
    void getStripeConfig_authenticated_returns200WithPublishableKey() throws Exception {
        when(paymentProperties.getPublishableKey()).thenReturn("pk_test_abc123");

        mockMvc.perform(get("/api/payment/stripe/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.publishableKey").value("pk_test_abc123"));
    }

    @Test
    @WithAnonymousUser
    void getStripeConfig_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/payment/stripe/config"))
            .andExpect(status().isUnauthorized());
    }

    // ─── GET /api/payment/payment-method ───────────────────────────────────────

    @Test
    @WithMockUser(roles = "PARENT")
    void getSavedPaymentMethod_noCard_returns200WithHasCardFalse() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        when(sessionPackPaymentService.getSavedPaymentMethod(PARENT_USER_ID))
            .thenReturn(new SavedPaymentMethodResponse(false, null, null, null, null));

        mockMvc.perform(get("/api/payment/payment-method"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasCard").value(false))
            .andExpect(jsonPath("$.brand").doesNotExist())
            .andExpect(jsonPath("$.last4").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void getSavedPaymentMethod_hasCard_returns200WithCardDetails() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        when(sessionPackPaymentService.getSavedPaymentMethod(PARENT_USER_ID))
            .thenReturn(new SavedPaymentMethodResponse(true, "visa", "4242", 12L, 2030L));

        mockMvc.perform(get("/api/payment/payment-method"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasCard").value(true))
            .andExpect(jsonPath("$.brand").value("visa"))
            .andExpect(jsonPath("$.last4").value("4242"))
            .andExpect(jsonPath("$.expMonth").value(12))
            .andExpect(jsonPath("$.expYear").value(2030));
    }

    @Test
    @WithAnonymousUser
    void getSavedPaymentMethod_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/payment/payment-method"))
            .andExpect(status().isUnauthorized());
    }

    // ─── UAT.5 AC2: a self-registered PLAYER can save a card and pay per-session ────────────────
    //
    // Mutation check: reverting the @PreAuthorize widening on any one of these three endpoints
    // back to HAS_PARENT_ROLE turns each of these into a 403, failing the test.

    @Test
    @WithMockUser(roles = "PLAYER")
    void createSetupIntent_playerRole_returns200() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_USER_ID);
        customer.setStripeCustomerId("cus_player_test");
        when(stripeCustomerRepository.findById(PARENT_USER_ID)).thenReturn(Optional.of(customer));
        when(paymentGateway.createSetupIntent("cus_player_test")).thenReturn("seti_player_secret");

        mockMvc.perform(post("/api/payment/setup-intent").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clientSecret").value("seti_player_secret"));
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void savePaymentMethod_playerRole_returns204() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_USER_ID);
        customer.setStripeCustomerId("cus_player_test");
        when(stripeCustomerRepository.findById(PARENT_USER_ID)).thenReturn(Optional.of(customer));
        when(paymentGateway.paymentMethodBelongsTo("cus_player_test", "pm_player_test")).thenReturn(true);

        mockMvc.perform(post("/api/payment/save-payment-method")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethodId\":\"pm_player_test\"}"))
            .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void getSavedPaymentMethod_playerRole_returns200() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        when(sessionPackPaymentService.getSavedPaymentMethod(PARENT_USER_ID))
            .thenReturn(new SavedPaymentMethodResponse(false, null, null, null, null));

        mockMvc.perform(get("/api/payment/payment-method"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasCard").value(false));
    }

    // ─── UAT.6 AC1: a coach can save a card via the same generic endpoints ──────────────────────
    //
    // Mutation check: reverting the @PreAuthorize widening on any one of these three endpoints
    // back to HAS_PARENT_OR_PLAYER_ROLE turns each of these into a 403, failing the test.

    @Test
    @WithMockUser(roles = "COACH")
    void createSetupIntent_coachRole_returns200() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_USER_ID);
        customer.setStripeCustomerId("cus_coach_test");
        when(stripeCustomerRepository.findById(PARENT_USER_ID)).thenReturn(Optional.of(customer));
        when(paymentGateway.createSetupIntent("cus_coach_test")).thenReturn("seti_coach_secret");

        mockMvc.perform(post("/api/payment/setup-intent").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clientSecret").value("seti_coach_secret"));
    }

    // skillars-deferred-177 (RS03h): no SetupIntent for a payer erased while the request ran.
    @Test
    @WithMockUser(roles = "PARENT")
    void setupIntent_existingCustomerErasedMeanwhile_422_noSetupIntent() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_USER_ID);
        customer.setStripeCustomerId("cus_erased");
        when(stripeCustomerRepository.findById(PARENT_USER_ID)).thenReturn(Optional.of(customer));
        org.mockito.Mockito.doThrow(new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
            "payment.accountErased")).when(sessionPackPaymentService).requirePayerLive(PARENT_USER_ID);

        mockMvc.perform(post("/api/payment/setup-intent").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorMsg.errorKey").value("payment.accountErased"));

        org.mockito.Mockito.verify(paymentGateway, org.mockito.Mockito.never()).createSetupIntent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void setupIntent_losingTheCreationRace_stillChecksThePayer_422_noSetupIntent() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        when(stripeCustomerRepository.findById(PARENT_USER_ID)).thenReturn(Optional.empty());
        when(paymentGateway.createStripeCustomer(PARENT_USER_ID)).thenReturn("cus_loser");
        when(stripeCustomerRepository.insertIfAbsent(PARENT_USER_ID, "cus_loser", null)).thenReturn(0); // a concurrent row won
        org.mockito.Mockito.doThrow(new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
            "payment.accountErased")).when(sessionPackPaymentService).requirePayerLive(PARENT_USER_ID);

        mockMvc.perform(post("/api/payment/setup-intent").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnprocessableEntity());

        org.mockito.Mockito.verify(paymentGateway, org.mockito.Mockito.never()).createSetupIntent(org.mockito.ArgumentMatchers.any());
        // review round 3: the loser's own Stripe customer is named by no row, so it is erased at Stripe
        org.mockito.Mockito.verify(sessionPackPaymentService).discardUnrecordedCustomer("cus_loser");
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void setupIntent_firstCustomerCreatedWhileErased_422_noSetupIntent() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        when(stripeCustomerRepository.findById(PARENT_USER_ID)).thenReturn(Optional.empty());
        when(stripeCustomerEmailSync.currentEmail(PARENT_USER_ID)).thenReturn("before@example.com");
        when(paymentGateway.createStripeCustomer(PARENT_USER_ID)).thenReturn("cus_new");
        when(stripeCustomerRepository.insertIfAbsent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(stripeCustomerEmailSync.syncIfEmailChangedSince(PARENT_USER_ID, "before@example.com")).thenReturn(true);

        mockMvc.perform(post("/api/payment/setup-intent").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnprocessableEntity());

        org.mockito.Mockito.verify(paymentGateway, org.mockito.Mockito.never()).createSetupIntent(org.mockito.ArgumentMatchers.any());
    }

    // skillars-deferred-176 AC2 (RS03e): the email is read before the customer is created and compared after its row is saved.
    @Test
    @WithMockUser(roles = "PARENT")
    void setupIntent_firstCustomer_checksForAnEmailChangeMadeDuringCreation() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        when(stripeCustomerRepository.findById(PARENT_USER_ID)).thenReturn(Optional.empty());
        when(stripeCustomerEmailSync.currentEmail(PARENT_USER_ID)).thenReturn("before@example.com");
        when(paymentGateway.createStripeCustomer(PARENT_USER_ID)).thenReturn("cus_new");
        when(stripeCustomerRepository.insertIfAbsent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(paymentGateway.createSetupIntent("cus_new")).thenReturn("seti_new");

        mockMvc.perform(post("/api/payment/setup-intent").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(stripeCustomerEmailSync, paymentGateway, stripeCustomerRepository);
        order.verify(stripeCustomerEmailSync).currentEmail(PARENT_USER_ID);
        order.verify(paymentGateway).createStripeCustomer(PARENT_USER_ID);
        order.verify(stripeCustomerRepository).insertIfAbsent(PARENT_USER_ID, "cus_new", null);
        order.verify(stripeCustomerEmailSync).syncIfEmailChangedSince(PARENT_USER_ID, "before@example.com");
    }

    @Test
    @WithMockUser(roles = "COACH")
    void savePaymentMethod_coachRole_returns204() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_USER_ID);
        customer.setStripeCustomerId("cus_coach_test");
        when(stripeCustomerRepository.findById(PARENT_USER_ID)).thenReturn(Optional.of(customer));
        when(paymentGateway.paymentMethodBelongsTo("cus_coach_test", "pm_coach_test")).thenReturn(true);

        mockMvc.perform(post("/api/payment/save-payment-method")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethodId\":\"pm_coach_test\"}"))
            .andExpect(status().isNoContent());
    }

    // fix/payment-subscription-resume-and-async-sync: the save hands the subscription sync to the durable
    // outbox (SubscriptionService.savePaymentMethodAndScheduleSync) instead of calling Stripe in the
    // request — see SubscriptionPaymentMethodSyncTest / SubscriptionLifecycleIT.
    @Test
    @WithMockUser(roles = "COACH")
    void savePaymentMethod_schedulesSubscriptionSync_noInlineStripeSync() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_USER_ID);
        customer.setStripeCustomerId("cus_coach_test");
        when(stripeCustomerRepository.findById(PARENT_USER_ID)).thenReturn(Optional.of(customer));
        when(paymentGateway.paymentMethodBelongsTo("cus_coach_test", "pm_sync_test")).thenReturn(true);

        mockMvc.perform(post("/api/payment/save-payment-method")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethodId\":\"pm_sync_test\"}"))
            .andExpect(status().isNoContent());

        org.mockito.Mockito.verify(subscriptionService).savePaymentMethodAndScheduleSync(PARENT_USER_ID, "pm_sync_test");
        org.mockito.Mockito.verify(subscriptionService, org.mockito.Mockito.never())
            .syncPaymentMethodToSubscriptions(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(paymentGateway, org.mockito.Mockito.never())
            .createStripeCustomer(org.mockito.ArgumentMatchers.any());
    }

    // skillars-deferred-174 AC1 (RS09b): only a card Stripe reports attached to the caller's own customer is saved.
    @Test
    @WithMockUser(roles = "COACH")
    void savePaymentMethod_noStripeCustomer_returns400_createsNothing() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        when(stripeCustomerRepository.findById(PARENT_USER_ID)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/payment/save-payment-method")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethodId\":\"pm_first\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(r -> org.assertj.core.api.Assertions.assertThat(
                ((org.springframework.web.server.ResponseStatusException) r.getResolvedException()).getReason())
                .isEqualTo("payment.paymentMethodNotOwned"));

        org.mockito.Mockito.verify(paymentGateway, org.mockito.Mockito.never())
            .createStripeCustomer(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(stripeCustomerRepository, org.mockito.Mockito.never())
            .save(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(subscriptionService, sessionPackPaymentService);
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void savePaymentMethod_aCardOfAnotherCustomerOrUnattached_returns400_noSaveNoFingerprint() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_USER_ID);
        customer.setStripeCustomerId("cus_mine");
        when(stripeCustomerRepository.findById(PARENT_USER_ID)).thenReturn(Optional.of(customer));
        when(paymentGateway.paymentMethodBelongsTo("cus_mine", "pm_foreign")).thenReturn(false);

        mockMvc.perform(post("/api/payment/save-payment-method")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethodId\":\"pm_foreign\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(r -> org.assertj.core.api.Assertions.assertThat(
                ((org.springframework.web.server.ResponseStatusException) r.getResolvedException()).getReason())
                .isEqualTo("payment.paymentMethodNotOwned"));

        org.mockito.Mockito.verifyNoInteractions(subscriptionService, sessionPackPaymentService);
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void savePaymentMethod_stripeDownDuringTheOwnershipCheck_returns422_savesNothing() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_USER_ID);
        customer.setStripeCustomerId("cus_mine");
        when(stripeCustomerRepository.findById(PARENT_USER_ID)).thenReturn(Optional.of(customer));
        when(paymentGateway.paymentMethodBelongsTo("cus_mine", "pm_mine"))
            .thenThrow(new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException("payment.lifecycleFailure"));

        mockMvc.perform(post("/api/payment/save-payment-method")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethodId\":\"pm_mine\"}"))
            .andExpect(status().isUnprocessableEntity());

        org.mockito.Mockito.verifyNoInteractions(subscriptionService, sessionPackPaymentService);
    }

    @Test
    @WithMockUser(roles = "COACH")
    void getSavedPaymentMethod_coachRole_returns200() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        when(sessionPackPaymentService.getSavedPaymentMethod(PARENT_USER_ID))
            .thenReturn(new SavedPaymentMethodResponse(false, null, null, null, null));

        mockMvc.perform(get("/api/payment/payment-method"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasCard").value(false));
    }

    // ─── Deferred-81 AC4: self-registered PLAYER can now purchase/manage/browse session packs ───
    //
    // Reverses the prior UAT.5-era decision (packs were explicitly out of scope for a self-booking
    // player) — the project owner decided during this story's creation to add pack-purchase parity
    // for self-booking players. Mutation check: reverting the @PreAuthorize widening on any one of
    // these four endpoints back to HAS_PARENT_ROLE turns each of these into a 403, failing the test.

    @Test
    @WithMockUser(roles = "PLAYER")
    void purchaseSessionPack_playerRole_returns201() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        java.util.UUID packTierId = java.util.UUID.randomUUID();
        when(sessionPackPaymentService.purchasePack(eq(PARENT_USER_ID), eq(packTierId), eq(1L), any(),
                org.mockito.ArgumentMatchers.isNull()))
            .thenReturn(new SessionPackPurchaseResponse(
                java.util.UUID.randomUUID(), packTierId, "Starter", 5, 5, new BigDecimal("20.00"),
                Instant.now().plusSeconds(3600), "pi_test", 1L, java.util.UUID.randomUUID(), null, "ACTIVE", null, null, 90));

        mockMvc.perform(post("/api/payment/session-packs/purchase")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"packTierId\":\"" + packTierId + "\",\"playerId\":1}"))
            .andExpect(status().isCreated());
    }

    /** fix/payment-session-pack-integrity: the client's per-attempt Idempotency-Key reaches the service. */
    @Test
    @WithMockUser(roles = "PARENT")
    void purchaseSessionPack_forwardsTheIdempotencyKeyHeader() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        java.util.UUID packTierId = java.util.UUID.randomUUID();

        mockMvc.perform(post("/api/payment/session-packs/purchase")
                .header("Idempotency-Key", "3f2b8c1e-attempt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"packTierId\":\"" + packTierId + "\",\"playerId\":1}"))
            .andExpect(status().isCreated());

        org.mockito.Mockito.verify(sessionPackPaymentService).purchasePack(
            eq(PARENT_USER_ID), eq(packTierId), eq(1L), any(), eq("3f2b8c1e-attempt"));
    }

    /** skillars-deferred-170 AC3: a pack of a coach who cannot be paid answers 422 with the code the page translates. */
    @Test
    @WithMockUser(roles = "PARENT")
    void purchaseSessionPack_coachNotPaymentReady_returns422WithItsErrorKey() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        java.util.UUID packTierId = java.util.UUID.randomUUID();
        when(sessionPackPaymentService.purchasePack(eq(PARENT_USER_ID), eq(packTierId), eq(1L), any(), any()))
            .thenThrow(new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
                "payment.coachStripeNotConfigured"));

        mockMvc.perform(post("/api/payment/session-packs/purchase")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"packTierId\":\"" + packTierId + "\",\"playerId\":1}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorMsg.errorKey").value("payment.coachStripeNotConfigured"));
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void getMySessionPacks_playerRole_returns200() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        when(sessionPackPaymentService.getPacksForParent(PARENT_USER_ID, null)).thenReturn(List.of());

        mockMvc.perform(get("/api/payment/session-packs"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void pauseSessionPack_playerRole_returns200() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);
        java.util.UUID purchaseId = java.util.UUID.randomUUID();
        when(packSessionService.pausePack(eq(PARENT_USER_ID), eq(purchaseId), any()))
            .thenReturn(new PauseConflictResponse(true, List.of(), Instant.now().plusSeconds(3600)));

        mockMvc.perform(post("/api/payment/session-packs/" + purchaseId + "/pause")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pauseStartDate\":\"" + Instant.now().plusSeconds(60) + "\",\"pauseDurationDays\":7}"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void getActiveCoachTier_playerRole_returns404WhenNoActiveTier() throws Exception {
        java.util.UUID coachId = java.util.UUID.randomUUID();
        when(sessionPackPaymentService.getActiveCoachTier(coachId)).thenReturn(null);

        mockMvc.perform(get("/api/payment/coaches/" + coachId + "/session-pack-tiers"))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "PLAYER")
    void getActiveCoachTier_playerRole_returns200WithTierWhenActive() throws Exception {
        java.util.UUID coachId = java.util.UUID.randomUUID();
        java.util.UUID tierId = java.util.UUID.randomUUID();
        com.softropic.skillars.platform.payment.contract.SessionPackTierResponse tier =
            new com.softropic.skillars.platform.payment.contract.SessionPackTierResponse(
                tierId, coachId, "Basic", 5, BigDecimal.valueOf(49.99), BigDecimal.valueOf(9.99), true, Instant.now(), 180L);
        when(sessionPackPaymentService.getActiveCoachTier(coachId)).thenReturn(tier);

        mockMvc.perform(get("/api/payment/coaches/" + coachId + "/session-pack-tiers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.label").value("Basic"))
            .andExpect(jsonPath("$.sessionCount").value(5))
            .andExpect(jsonPath("$.validityDays").value(180));
    }
    // ─── S-10: input bounds / payment method id format ─────────────────────────

    @Test
    @WithMockUser(roles = "COACH")
    void createTier_sessionCountOverCap_returns400() throws Exception {
        mockMvc.perform(post("/api/payment/coaches/me/session-pack-tiers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Huge\",\"sessionCount\":101,\"totalPrice\":100.00}"))
            .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(sessionPackPaymentService);
    }

    @Test
    @WithMockUser(roles = "COACH")
    void createTier_priceOverColumnCapacity_returns400() throws Exception {
        mockMvc.perform(post("/api/payment/coaches/me/session-pack-tiers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Huge\",\"sessionCount\":5,\"totalPrice\":99999999999.00}"))
            .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(sessionPackPaymentService);
    }

    @Test
    @WithMockUser(roles = "COACH")
    void createTier_threeDecimalPrice_returns400() throws Exception {
        mockMvc.perform(post("/api/payment/coaches/me/session-pack-tiers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Odd\",\"sessionCount\":5,\"totalPrice\":10.005}"))
            .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(sessionPackPaymentService);
    }

    @Test
    @WithMockUser(roles = "COACH")
    void createTier_fractionalSessionCount_returns400InsteadOfTruncating() throws Exception {
        // skillars-deferred-170 AC4: Jackson's default ACCEPT_FLOAT_AS_INT used to bind 2.5 as 2.
        mockMvc.perform(post("/api/payment/coaches/me/session-pack-tiers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Half\",\"sessionCount\":2.5,\"totalPrice\":95.00}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorMsg.errorKey").value("validation.badRequest"));
        org.mockito.Mockito.verifyNoInteractions(sessionPackPaymentService);
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void savePaymentMethod_malformedId_returns400WithoutTouchingStripeOrService() throws Exception {
        when(securityUtil.getCurrentCoachUserId()).thenReturn(PARENT_USER_ID);

        mockMvc.perform(post("/api/payment/save-payment-method")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethodId\":\"card_123; DROP\"}"))
            .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(subscriptionService, paymentGateway);
    }

    @Test
    @WithMockUser(roles = "PARENT")
    void purchaseSessionPack_malformedPaymentMethodId_returns400() throws Exception {
        mockMvc.perform(post("/api/payment/session-packs/purchase")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"packTierId\":\"" + java.util.UUID.randomUUID() + "\",\"playerId\":7,\"paymentMethodId\":\"tok_x\"}"))
            .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(sessionPackPaymentService);
    }
}
