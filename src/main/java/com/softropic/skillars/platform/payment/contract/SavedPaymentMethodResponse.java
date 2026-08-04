package com.softropic.skillars.platform.payment.contract;

public record SavedPaymentMethodResponse(boolean hasCard, String brand, String last4, Long expMonth, Long expYear) {}
