package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionDto(
    UUID bookingId,
    String playerName,
    Instant sessionDate,
    BigDecimal grossAmount,
    BigDecimal commissionAmount,
    BigDecimal netAmount,
    String status,
    BigDecimal creditUsed
) {}
