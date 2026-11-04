package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.SessionPackLimits;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackTier;
import com.softropic.skillars.platform.payment.repo.SessionPackTierRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-168: the profile builder sets the coach's offer through
 * {@link SessionPackPaymentService#replaceActiveOffer} / {@link SessionPackPaymentService#withdrawActiveOffer}.
 * An unchanged pack must not churn tier rows; a changed one replaces the active row; none withdraws it.
 */
@ExtendWith(MockitoExtension.class)
class SessionPackOfferTest {

    @Mock SessionPackTierRepository sessionPackTierRepository;
    @Mock SessionPackPurchaseRepository sessionPackPurchaseRepository;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock PlayerProfileRepository playerProfileRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock ConfigService configService;

    @InjectMocks SessionPackPaymentService service;

    private static final UUID COACH_ID = UUID.randomUUID();

    private static SessionPackTier tier(int count, String price, String label) {
        SessionPackTier tier = new SessionPackTier();
        tier.setCoachId(COACH_ID);
        tier.setSessionCount(count);
        tier.setTotalPrice(new BigDecimal(price));
        tier.setLabel(label);
        tier.setActive(true);
        return tier;
    }

    private void stubSave() {
        lenient().when(sessionPackTierRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void unchangedPack_writesNothing_evenWhenThePriceScaleDiffers() {
        when(sessionPackTierRepository.findByCoachIdAndIsActiveTrue(COACH_ID))
            .thenReturn(Optional.of(tier(10, "380.00", "Season 10")));

        service.replaceActiveOffer(COACH_ID, 10, new BigDecimal("380"), "Season 10");

        verify(sessionPackTierRepository, never()).save(any());
        verify(sessionPackTierRepository, never()).saveAndFlush(any());
    }

    @Test
    void blankLabel_storedAsTheDefault_andThenCountsAsUnchanged() {
        when(sessionPackTierRepository.findByCoachIdAndIsActiveTrue(COACH_ID))
            .thenReturn(Optional.of(tier(5, "180.00", SessionPackLimits.DEFAULT_LABEL)));

        service.replaceActiveOffer(COACH_ID, 5, new BigDecimal("180"), "  ");
        service.replaceActiveOffer(COACH_ID, 5, new BigDecimal("180"), null);

        verify(sessionPackTierRepository, never()).save(any());
    }

    @Test
    void changedPack_deactivatesTheOldRowAndInsertsTheNewOne() {
        SessionPackTier old = tier(5, "180.00", "Starter");
        when(sessionPackTierRepository.findByCoachIdAndIsActiveTrue(COACH_ID)).thenReturn(Optional.of(old));
        when(sessionPackTierRepository.findAllByCoachIdAndIsActiveTrue(COACH_ID)).thenReturn(List.of(old));
        stubSave();

        service.replaceActiveOffer(COACH_ID, 10, new BigDecimal("380"), "Season 10");

        assertThat(old.isActive()).isFalse();
        verify(sessionPackTierRepository).saveAndFlush(old);
        ArgumentCaptor<SessionPackTier> inserted = ArgumentCaptor.forClass(SessionPackTier.class);
        verify(sessionPackTierRepository).save(inserted.capture());
        assertThat(inserted.getValue().getSessionCount()).isEqualTo(10);
        assertThat(inserted.getValue().getTotalPrice()).isEqualByComparingTo("380");
        assertThat(inserted.getValue().getPricePerSession()).isEqualByComparingTo("38.00");
        assertThat(inserted.getValue().getLabel()).isEqualTo("Season 10");
        assertThat(inserted.getValue().isActive()).isTrue();
    }

    @Test
    void firstPack_withoutLabel_createsATierWithTheDefaultLabel() {
        when(sessionPackTierRepository.findByCoachIdAndIsActiveTrue(COACH_ID)).thenReturn(Optional.empty());
        when(sessionPackTierRepository.findAllByCoachIdAndIsActiveTrue(COACH_ID)).thenReturn(List.of());
        stubSave();

        service.replaceActiveOffer(COACH_ID, 5, new BigDecimal("180"), null);

        ArgumentCaptor<SessionPackTier> inserted = ArgumentCaptor.forClass(SessionPackTier.class);
        verify(sessionPackTierRepository).save(inserted.capture());
        assertThat(inserted.getValue().getLabel()).isEqualTo(SessionPackLimits.DEFAULT_LABEL);
    }

    @Test
    void labelChangeOnly_isAChange() {
        SessionPackTier old = tier(5, "180.00", "Starter");
        when(sessionPackTierRepository.findByCoachIdAndIsActiveTrue(COACH_ID)).thenReturn(Optional.of(old));
        when(sessionPackTierRepository.findAllByCoachIdAndIsActiveTrue(COACH_ID)).thenReturn(List.of(old));
        stubSave();

        service.replaceActiveOffer(COACH_ID, 5, new BigDecimal("180"), "Starter plus");

        verify(sessionPackTierRepository).save(any());
    }

    @Test
    void withdraw_deactivatesTheActiveRow_andDeletesNothing() {
        SessionPackTier old = tier(5, "180.00", "Starter");
        when(sessionPackTierRepository.findAllByCoachIdAndIsActiveTrue(COACH_ID)).thenReturn(List.of(old));

        service.withdrawActiveOffer(COACH_ID);

        assertThat(old.isActive()).isFalse();
        verify(sessionPackTierRepository).saveAndFlush(old);
        verify(sessionPackTierRepository, never()).delete(any());
    }

    @Test
    void withdraw_withNoActiveOffer_writesNothing() {
        when(sessionPackTierRepository.findAllByCoachIdAndIsActiveTrue(COACH_ID)).thenReturn(List.of());

        service.withdrawActiveOffer(COACH_ID);

        verify(sessionPackTierRepository, never()).saveAndFlush(any());
    }
}
