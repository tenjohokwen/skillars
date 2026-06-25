package com.softropic.skillars.platform.payment.contract;

import java.util.List;

public record TierInfoResponse(String tier, List<String> features, String monthlyPrice, String annualPrice) {}
