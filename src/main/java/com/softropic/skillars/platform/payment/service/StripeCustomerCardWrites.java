package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * skillars-deferred-176 AC3 (RS09g, RS09h): card writes on {@code payment.stripe_customers} that a GDPR erasure cannot
 * race. The erasure NULLs the card column in the same transaction that sets the user's {@code @erased.io} tombstone
 * ({@code GdprErasureService.eraseTransactional}). A card UPDATE that blocks on that row lock re-checks only its own WHERE
 * against the new row version, so a NULL "previous card" matches the erased NULL and the card is written back. Taking
 * the row lock first and only THEN reading the user, in a later statement, sees a committed erasure under READ
 * COMMITTED; if this side holds the lock first, the erasure waits and NULLs the card after it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeCustomerCardWrites {

    private final StripeCustomerRepository stripeCustomerRepository;
    private final UserRepository userRepository;

    /**
     * Locks the payer's {@code stripe_customers} row (blocking), then reads the user. False, having written nothing, when
     * there is no row or the user is gone or erased. The lock is held until the caller's transaction ends.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockIfPayerLive(Long userId) {
        if (stripeCustomerRepository.findByIdForUpdateWaiting(userId).isEmpty()) {
            return false;
        }
        Optional<User> user = userRepository.findOneById(userId);
        boolean live = user.isPresent() && !isErasureTombstone(userId, user.get().getEmail());
        if (!live) {
            log.info("[STRIPE_CUSTOMER_CARD_WRITE_SKIPPED userId={}] account gone or erased", userId);
        }
        return live;
    }

    /**
     * The exact address {@code GdprErasureService} writes for this user. Round-2 V2: not just the {@code @erased.io} domain,
     * which a live user may register under.
     */
    static boolean isErasureTombstone(Long userId, String email) {
        return email != null && email.equals("deleted." + userId + "@erased.io");
    }

    /**
     * skillars-deferred-177 (RS03h): for a caller that writes no card but must not go on for an erased payer (a pack purchase
     * without a named card, a SetupIntent). Reads the account's email {@code FOR SHARE}, so an erasure that has written the
     * tombstone but not committed is waited for and seen. Deliberately not the {@code stripe_customers} row lock
     * ({@link #lockIfPayerLive}): booking settlement holds that across its Stripe charge (RS09i), and no card is written here.
     */
    @Transactional
    public boolean payerLive(Long userId) {
        boolean live = userRepository.findEmailByIdForShare(userId).map(email -> !isErasureTombstone(userId, email)).orElse(false);
        if (!live) {
            log.info("[STRIPE_CUSTOMER_PAYER_NOT_LIVE userId={}] account gone or erased", userId);
        }
        return live;
    }

    /** RS09g: a pack purchase's card on an existing customer row, as a one-column UPDATE rather than a full-row save. */
    @Transactional
    public boolean writeCardIfPayerLive(Long userId, String paymentMethodId) {
        return lockIfPayerLive(userId) && stripeCustomerRepository.updatePaymentMethod(userId, paymentMethodId) == 1;
    }
}
