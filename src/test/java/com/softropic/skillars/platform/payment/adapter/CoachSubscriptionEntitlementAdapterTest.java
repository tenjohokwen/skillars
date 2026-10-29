package com.softropic.skillars.platform.payment.adapter;

import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// fix/payment-subscription-entitlements item 3.
class CoachSubscriptionEntitlementAdapterTest {

    private final PaymentCoachSubscriptionRepository repository = mock(PaymentCoachSubscriptionRepository.class);
    private final CoachSubscriptionEntitlementAdapter adapter = new CoachSubscriptionEntitlementAdapter(repository);
    private final UUID coachId = UUID.randomUUID();

    @ParameterizedTest
    @CsvSource({"ACTIVE,true", "TRIALLING,true", "PAST_DUE,true",
        "CANCELLED,false", "UNPAID,false", "INCOMPLETE,false", "PAUSED,false"})
    void entitlementFollowsPaymentStatus(String status, boolean entitled) {
        PaymentCoachSubscription sub = new PaymentCoachSubscription();
        sub.setStatus(status);
        when(repository.findByCoachId(coachId)).thenReturn(Optional.of(sub));

        assertThat(adapter.isPaidTierEntitled(coachId)).isEqualTo(entitled);
    }

    @Test
    void noPaymentRow_keepsProjectedTier() {
        when(repository.findByCoachId(coachId)).thenReturn(Optional.empty());

        assertThat(adapter.isPaidTierEntitled(coachId)).isTrue();
    }
}
