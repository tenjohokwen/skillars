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
}
