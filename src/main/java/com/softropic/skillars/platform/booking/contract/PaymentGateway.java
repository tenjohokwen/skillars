package com.softropic.skillars.platform.booking.contract;

import java.math.BigDecimal;

public interface PaymentGateway {
    /** Returns a payment reference ID. */
    String capturePayment(BigDecimal amount, String currency);
}
