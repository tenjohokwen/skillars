package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReceiptDto(
    UUID bookingId,
    Instant sessionDate,
    String playerFirstName,
    String coachName,
    String platformName,
    BigDecimal grossAmount,
    BigDecimal commissionDeducted,
    BigDecimal netReceived
) {}
