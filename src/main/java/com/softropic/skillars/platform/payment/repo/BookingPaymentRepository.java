package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BookingPaymentRepository extends JpaRepository<BookingPayment, UUID> {
}
