package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;

public record CreditBalanceResponse(BigDecimal balance, String currency) {}
