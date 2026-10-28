package com.softropic.skillars.platform.booking.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * {@code totalAmount} is accepted for wire compatibility but IGNORED (fix/booking-integrity, B2): the
 * batch total is computed server-side by {@code BookingBatchService.createBatch} from the coach's
 * pricing. It is optional; a client must never be trusted with the amount.
 */
public record CreateBatchRequest(
    @NotNull UUID coachId,
    @NotNull Long playerId,
    @NotEmpty @Size(min = 1, max = 10) List<@Valid BatchSlot> slots,
    @DecimalMin("0.00") BigDecimal totalAmount,
    String availabilitySignature
) {}
