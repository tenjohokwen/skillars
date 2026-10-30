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
    BigDecimal creditUsed,
    // fix/audit3-wallet-cashout-integrity: when a PENDING_RELEASE payout's transfer is due (it reflects the
    // longer new-coach hold); null once released.
    Instant releaseAfter
) {}
