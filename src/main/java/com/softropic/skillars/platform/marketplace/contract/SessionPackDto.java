package com.softropic.skillars.platform.marketplace.contract;

import java.math.BigDecimal;

public record SessionPackDto(int sessionCount, BigDecimal totalPrice, String currency, String label) {}
