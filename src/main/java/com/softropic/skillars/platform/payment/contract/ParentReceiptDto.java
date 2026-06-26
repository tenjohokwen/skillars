package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ParentReceiptDto(
    UUID bookingId,
    Instant sessionDate,
    String playerFirstName,
    String coachName,
    BigDecimal stripeCharged,
    BigDecimal creditUsed,
    BigDecimal totalCharged
) {}
