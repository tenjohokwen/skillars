package com.softropic.skillars.platform.payment.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// AC3 (skillars-deferred-57): no format-guard CHECK constraint existed on
// payment.stripe_customers.stripe_customer_id — V100/V101 add one. This IT proves the constraint
// actually rejects a non-"cus_"-prefixed value and allows a valid one.
class StripeCustomerRepositoryIT extends AbstractIntegrationTest {

    @Autowired private StripeCustomerRepository stripeCustomerRepository;

    @Test
    void validCusPrefixedId_saves() {
        StripeCustomer sc = new StripeCustomer();
        sc.setParentId(9_640_000_001L);
        sc.setStripeCustomerId("cus_valid_test_id");
        sc.setCreatedAt(Instant.now());

        try {
            assertThatCode(() -> stripeCustomerRepository.saveAndFlush(sc)).doesNotThrowAnyException();
        } finally {
            if (stripeCustomerRepository.existsById(9_640_000_001L)) {
                stripeCustomerRepository.deleteById(9_640_000_001L);
            }
        }
    }

    @Test
    void nonCusPrefixedId_throwsDataIntegrity() {
        StripeCustomer sc = new StripeCustomer();
        sc.setParentId(9_640_000_002L);
        sc.setStripeCustomerId("acct_wrong_prefix");
        sc.setCreatedAt(Instant.now());

        assertThatThrownBy(() -> stripeCustomerRepository.saveAndFlush(sc))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── skillars-deferred-174 AC2/AC4: targeted card-column writes ─────────────────────────────────────────────

    private static final long PAYER = 9_640_000_003L;

    private void savePayer(String card) {
        StripeCustomer sc = new StripeCustomer();
        sc.setParentId(PAYER);
        sc.setStripeCustomerId("cus_payer_174");
        sc.setStripePaymentMethodId(card);
        stripeCustomerRepository.saveAndFlush(sc);
    }

    @org.junit.jupiter.api.AfterEach
    void removePayer() {
        if (stripeCustomerRepository.existsById(PAYER)) {
            stripeCustomerRepository.deleteById(PAYER);
        }
    }

    private String cardOf() {
        return stripeCustomerRepository.findById(PAYER).orElseThrow().getStripePaymentMethodId();
    }

    /** AC2: a card saved between the gateway's read and its post-capture write survives (a full-row save undid it). */
    @Test
    void lastIntentUpdate_keepsACardSavedSinceTheGatewayReadTheRow() {
        savePayer("pm_charged");
        StripeCustomer readByTheGateway = stripeCustomerRepository.findById(PAYER).orElseThrow();
        stripeCustomerRepository.revertPaymentMethod(PAYER, "pm_saved_meanwhile", "pm_charged"); // any concurrent card write

        stripeCustomerRepository.updateLastPaymentIntentId(readByTheGateway.getParentId(), "pi_captured");

        StripeCustomer row = stripeCustomerRepository.findById(PAYER).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(row.getStripePaymentMethodId()).isEqualTo("pm_saved_meanwhile");
        org.assertj.core.api.Assertions.assertThat(row.getLastPaymentIntentId()).isEqualTo("pi_captured");
    }

    /** The defect AC2 fixes, shown on the old write path: saving the entity read before the change brings the old card back. */
    @Test
    void fullRowSaveOfAStaleEntity_wouldUndoTheCardSavedMeanwhile() {
        savePayer("pm_charged");
        StripeCustomer readByTheGateway = stripeCustomerRepository.findById(PAYER).orElseThrow();
        stripeCustomerRepository.revertPaymentMethod(PAYER, "pm_saved_meanwhile", "pm_charged");

        readByTheGateway.setLastPaymentIntentId("pi_captured");
        stripeCustomerRepository.save(readByTheGateway);

        org.assertj.core.api.Assertions.assertThat(cardOf()).isEqualTo("pm_charged");
    }

    /** AC4: the success-path re-apply accepts the attempted or the previous card, and keeps any other. */
    @Test
    void reapply_acceptsTheAttemptedOrThePreviousCard_andKeepsAThirdOne() {
        java.util.function.IntSupplier reapply = () -> transactionTemplate.execute(
            s -> stripeCustomerRepository.reapplyPaymentMethod(PAYER, "pm_old", "pm_new"));

        savePayer("pm_new"); // normal success: the purchase wrote it before the charge
        org.assertj.core.api.Assertions.assertThat(reapply.getAsInt()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(cardOf()).isEqualTo("pm_new");
        removePayer();

        savePayer("pm_old"); // the resolver reverted it meanwhile
        org.assertj.core.api.Assertions.assertThat(reapply.getAsInt()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(cardOf()).isEqualTo("pm_new");
        removePayer();

        savePayer("pm_third"); // the payer saved another card during the purchase
        org.assertj.core.api.Assertions.assertThat(reapply.getAsInt()).isZero();
        org.assertj.core.api.Assertions.assertThat(cardOf()).isEqualTo("pm_third");
    }

    @Test
    void reapply_withNoPreviousCard_matchesAnEmptyColumn() {
        savePayer(null);

        int updated = transactionTemplate.execute(s -> stripeCustomerRepository.reapplyPaymentMethod(PAYER, null, "pm_new"));

        org.assertj.core.api.Assertions.assertThat(updated).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(cardOf()).isEqualTo("pm_new");
    }
}
