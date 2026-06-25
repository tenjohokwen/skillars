package com.softropic.skillars.platform.payment.contract;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PurchaseSessionPackRequest(
    @NotNull UUID packTierId,
    String paymentMethodId
) {}
