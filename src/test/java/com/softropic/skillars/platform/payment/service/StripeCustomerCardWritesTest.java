package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** skillars-deferred-176 AC3 (RS09g, RS09h): card writes lock the row, then check the payer, and skip an erased one. */
@ExtendWith(MockitoExtension.class)
class StripeCustomerCardWritesTest {

    private static final long USER_ID = 42L;

    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock UserRepository userRepository;
    @InjectMocks StripeCustomerCardWrites cardWrites;

    private void row() {
        when(stripeCustomerRepository.findByIdForUpdateWaiting(USER_ID)).thenReturn(Optional.of(new StripeCustomer()));
    }

    private void user(String email) {
        User u = new User();
        u.setEmail(email);
        when(userRepository.findOneById(USER_ID)).thenReturn(Optional.of(u));
    }

    @Test
    void aLivePayer_getsTheCard_withTheRowLockedBeforeTheUserIsRead() {
        row();
        user("me@example.com");
        when(stripeCustomerRepository.updatePaymentMethod(USER_ID, "pm_new")).thenReturn(1);

        assertThat(cardWrites.writeCardIfPayerLive(USER_ID, "pm_new")).isTrue();

        InOrder order = inOrder(stripeCustomerRepository, userRepository);
        order.verify(stripeCustomerRepository).findByIdForUpdateWaiting(USER_ID);
        order.verify(userRepository).findOneById(USER_ID);
        order.verify(stripeCustomerRepository).updatePaymentMethod(USER_ID, "pm_new");
    }

    @Test
    void anErasedPayer_getsNoCard() {
        row();
        user("deleted.42@erased.io");

        assertThat(cardWrites.writeCardIfPayerLive(USER_ID, "pm_new")).isFalse();

        verify(stripeCustomerRepository, never()).updatePaymentMethod(any(), any());
    }

    @Test
    void aGonePayer_orNoRow_isNotLive() {
        when(stripeCustomerRepository.findByIdForUpdateWaiting(USER_ID)).thenReturn(Optional.empty());
        assertThat(cardWrites.lockIfPayerLive(USER_ID)).isFalse();

        row();
        when(userRepository.findOneById(USER_ID)).thenReturn(Optional.empty());
        assertThat(cardWrites.lockIfPayerLive(USER_ID)).isFalse();
    }

    @Test
    void aPayerWithoutAnEmail_isLive() {
        row();
        user(null);

        assertThat(cardWrites.lockIfPayerLive(USER_ID)).isTrue();
    }
}
