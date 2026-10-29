package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.CashOutReconciliation;
import com.softropic.skillars.platform.payment.repo.CashOutReconciliationRepository;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.service.PlayerOwnershipGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * fix/player-wallet-cashout: a self-registered adult player (own login, own profile, no parent) cashes out the
 * cashable refund credit in their own wallet under the parent's rules. The wallet is keyed by the payer's user
 * id, and a self-booking player's bookings carry their own user id as the payer, so refunds credit the
 * player's id — the same id the cash-out debits and the {@code stripe_customers} row it locks and refunds from.
 * Also pins {@link PlayerOwnershipGuard#isWalletOwner} against the real {@code player_profiles} table.
 */
class PlayerWalletCashOutIT extends BasePaymentIT {

    private static final long PLAYER_USER_ID = 98411L;
    private static final long PLAYER_PROFILE_ID = 98412L;
    private static final long PARENT_ID = 98413L;
    private static final long CHILD_PROFILE_ID = 98414L;

    @Autowired CashOutService cashOutService;
    @Autowired CashOutReconciliationRepository reconciliationRepository;
    @Autowired CreditWalletService creditWalletService;
    @Autowired PlayerOwnershipGuard playerOwnershipGuard;

    @BeforeEach
    void seed() {
        insertTestParent(PLAYER_USER_ID, "wallet_selfplayer@test.com");
        insertTestParent(PARENT_ID, "wallet_parent_of_child@test.com");
        insertTestPlayer(CHILD_PROFILE_ID, PARENT_ID);
        transactionTemplate.execute(s -> {
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles "
                    + "(id, name, date_of_birth, position, age_tier, user_id, independent_account_allowed, created_at, created_by) "
                    + "VALUES (?, 'Self Registered Adult', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system') "
                    + "ON CONFLICT (id) DO NOTHING",
                PLAYER_PROFILE_ID, Date.valueOf(LocalDate.now().minusYears(20)), PLAYER_USER_ID,
                Timestamp.from(Instant.now()));
            jdbcTemplate.update("INSERT INTO payment.stripe_customers (parent_id, stripe_customer_id, last_payment_intent_id) "
                + "VALUES (?, 'cus_selfplayer', 'pi_selfplayer') ON CONFLICT (parent_id) DO NOTHING", PLAYER_USER_ID);
            // 80.00 cashable refund credit + 20.00 spend-only (an expired-pack refund), both keyed by the
            // player's own user id — where RefundEnqueueListener credits a self-booking player's refunds.
            creditWalletService.writeLedgerEntry(PLAYER_USER_ID, new BigDecimal("80.00"),
                CreditLedgerEntryType.BOOKING_REFUND, UUID.randomUUID(), "IT seed cashable refund");
            creditWalletService.writeLedgerEntry(PLAYER_USER_ID, new BigDecimal("20.00"),
                CreditLedgerEntryType.BOOKING_REFUND, UUID.randomUUID(), "IT seed spend-only refund",
                null, null, new BigDecimal("20.00"));
            return null;
        });
    }

    private static Authentication as(long userId, String role) {
        Principal principal = new Principal.Builder()
            .username("u" + userId + "@test.com")
            .password("password")
            .enabled(true)
            .businessId(String.valueOf(userId))
            .otpEnabled(false)
            .build();
        return new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority(role)));
    }

    @Test
    void selfRegisteredPlayer_cashesOutCashableCredit_withTheParentFee_ontoTheirOwnCard() {
        cashOutService.processCashOut(PLAYER_USER_ID, new BigDecimal("40.00"));

        List<CashOutReconciliation> rows = reconciliationRepository.findAll().stream()
            .filter(r -> r.getParentId().equals(PLAYER_USER_ID)).toList();
        assertThat(rows).hasSize(1);
        CashOutReconciliation r = rows.get(0);
        assertThat(r.getStatus()).isEqualTo(CashOutReconciliation.COMPLETED);
        assertThat(r.getPaymentIntentId()).isEqualTo("pi_selfplayer");
        // 2.5% + €0.25 (payment.stripe.feeRate / feeFixed seed values): 40.00 → fee 1.25, net 38.75.
        assertThat(r.getFeeAmount()).isEqualByComparingTo("1.25");
        assertThat(r.getNetAmount()).isEqualByComparingTo("38.75");

        CreditWalletService.WalletBalance after = creditWalletService.getWalletBalance(PLAYER_USER_ID);
        assertThat(after.total()).isEqualByComparingTo("60.00");
        assertThat(after.spendOnly()).as("spend-only credit is untouched").isEqualByComparingTo("20.00");
        assertThat(after.cashable()).isEqualByComparingTo("40.00");
    }

    @Test
    void selfRegisteredPlayer_cannotCashOutSpendOnlyCredit() {
        assertThatThrownBy(() -> cashOutService.processCashOut(PLAYER_USER_ID, new BigDecimal("90.00")))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.cashOutExceedsCashable");

        assertThat(creditWalletService.getBalance(PLAYER_USER_ID)).isEqualByComparingTo("100.00");
    }

    @Test
    void walletGuard_admitsTheSelfRegisteredPlayer_andRefusesAPlayerLoginWithoutAParentlessProfile() {
        assertThat(playerOwnershipGuard.isWalletOwner(as(PLAYER_USER_ID, "ROLE_PLAYER"))).isTrue();
        // The parent's user id carries no self-owned profile (their child's profile has parent_id set), so the
        // same id presented as a PLAYER is refused; as a PARENT it is the parent's own wallet.
        assertThat(playerOwnershipGuard.isWalletOwner(as(PARENT_ID, "ROLE_PLAYER"))).isFalse();
        assertThat(playerOwnershipGuard.isWalletOwner(as(PARENT_ID, "ROLE_PARENT"))).isTrue();
    }
}
