package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StripeCustomerRepository extends JpaRepository<StripeCustomer, Long> {
}
