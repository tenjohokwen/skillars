package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreditStatementEntryDto(
    UUID txId,
    String type,
    BigDecimal amount,
    String description,
    UUID referenceId,
    Instant createdAt,
    BigDecimal runningBalance
) {}
