package com.softropic.skillars.platform.booking.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreateBatchRequest(
    @NotNull UUID coachId,
    @NotNull Long playerId,
    @NotEmpty @Size(min = 1, max = 10) List<@Valid BatchSlot> slots,
    @NotNull @DecimalMin("0.00") BigDecimal totalAmount
) {}
