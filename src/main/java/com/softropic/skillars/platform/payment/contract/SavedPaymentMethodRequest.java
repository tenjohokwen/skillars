package com.softropic.skillars.platform.payment.contract;

import jakarta.validation.constraints.NotBlank;

public record SavedPaymentMethodRequest(@NotBlank String paymentMethodId) {}
