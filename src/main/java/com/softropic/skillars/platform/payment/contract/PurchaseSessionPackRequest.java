package com.softropic.skillars.platform.payment.contract;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record PurchaseSessionPackRequest(
    @NotNull UUID packTierId,
    @NotNull Long playerId,
    // Optional (a saved card is used when absent), but when sent it must look like a Stripe payment method id.
    @Pattern(regexp = StripeIdFormat.PAYMENT_METHOD_ID_REGEX, message = "paymentMethodId is not a Stripe payment method id")
    String paymentMethodId
) {}
