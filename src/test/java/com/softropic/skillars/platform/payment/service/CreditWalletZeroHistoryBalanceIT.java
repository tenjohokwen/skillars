package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-103 AC11: a parent with no {@code parent_credit_ledger} history must resolve to
 * a <strong>zero</strong> balance, not an empty result / null / exception.
 *
 * <p>The JPQL aggregate {@code sumByParentId} returns {@code Optional.empty()} for such a parent
 * (a {@code SUM} over zero rows is SQL {@code NULL}); {@link CreditWalletService#getBalance} is the
 * component that maps that to {@link BigDecimal#ZERO}. Mutation check: remove the
 * {@code .orElse(BigDecimal.ZERO)} in {@code getBalance} and this test fails.
 */
class CreditWalletZeroHistoryBalanceIT extends BasePaymentIT {

    private static final long PARENT_NO_HISTORY = 74_900L;

    @Autowired
    CreditWalletService creditWalletService;

    @Autowired
    ParentCreditLedgerRepository ledgerRepository;

    @Test
    void getBalance_parentWithNoLedgerRows_returnsZero_notNullOrException() {
        insertTestParent(PARENT_NO_HISTORY, "parent.nohistory@test.com");

        // The raw JPQL path genuinely returns no value for an absent parent...
        Optional<BigDecimal> raw = ledgerRepository.sumByParentId(PARENT_NO_HISTORY);
        assertThat(raw).isEmpty();

        // ...and getBalance is what makes "no history" resolve to zero.
        BigDecimal balance = creditWalletService.getBalance(PARENT_NO_HISTORY);
        assertThat(balance).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
