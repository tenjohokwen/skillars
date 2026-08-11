package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachPricing;
import com.softropic.skillars.platform.marketplace.repo.CoachPricingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionDurationResolverTest {

    private static final UUID COACH_ID = UUID.randomUUID();
    private static final String KEY = "booking.session.defaultDurationMinutes";

    @Mock
    private CoachPricingRepository coachPricingRepository;

    @Mock
    private ConfigService configService;

    @InjectMocks
    private SessionDurationResolver resolver;

    private CoachPricing pricing;

    @BeforeEach
    void setUp() {
        pricing = new CoachPricing();
        pricing.setCoachId(COACH_ID);
    }

    @Test
    void resolve_coachOverridePresent_winsOverPlatformDefault() {
        pricing.setSessionDurationMinutes(90);
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(pricing));

        assertThat(resolver.resolve(COACH_ID)).isEqualTo(Duration.ofMinutes(90));
        verify(configService, never()).getBoundedLong(eq(KEY), anyLong(), anyLong(), anyLong());
    }

    @Test
    void resolve_nullOverride_fallsBackToPlatformDefault() {
        pricing.setSessionDurationMinutes(null);
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(pricing));
        when(configService.getBoundedLong(KEY, 60L, 15L, 240L)).thenReturn(45L);

        assertThat(resolver.resolve(COACH_ID)).isEqualTo(Duration.ofMinutes(45));
    }

    /**
     * Reachable today, not defensive: createBookingRequest accepts PENDING_REVIEW coach profiles
     * and Step 3 of the profile builder is where a coach_pricing row is first written, so a coach
     * can be bookable before any pricing row exists.
     */
    @Test
    void resolve_noPricingRowAtAll_fallsBackToPlatformDefault() {
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.empty());
        when(configService.getBoundedLong(KEY, 60L, 15L, 240L)).thenReturn(60L);

        assertThat(resolver.resolve(COACH_ID)).isEqualTo(Duration.ofMinutes(60));
    }

    /**
     * getBoundedLong, not getLong: a 0 or negative duration would make
     * AvailabilityService.computeAvailableSlots loop forever. The bounds are asserted here so
     * switching to getLong(key) or widening the range fails a test rather than a production loop.
     */
    @Test
    void resolve_requestsTheBoundedLookupWithTheSlicingSafeRange() {
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.empty());
        when(configService.getBoundedLong(KEY, 60L, 15L, 240L)).thenReturn(60L);

        resolver.resolve(COACH_ID);

        verify(configService).getBoundedLong(KEY, 60L, 15L, 240L);
    }

    @Test
    void resolve_outOfRangeConfigValue_yieldsTheSixtyMinuteDefault() {
        // ConfigService.getBoundedLong itself clamps to the default and WARNs; this pins that the
        // resolver passes 60 as that default rather than propagating an absurd value.
        when(coachPricingRepository.findByCoachId(COACH_ID)).thenReturn(Optional.empty());
        when(configService.getBoundedLong(KEY, 60L, 15L, 240L)).thenAnswer(inv -> inv.getArgument(1));

        assertThat(resolver.resolve(COACH_ID)).isEqualTo(Duration.ofMinutes(60));
    }
}
