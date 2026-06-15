package com.softropic.skillars.platform.booking.contract;

public class BookingStateTransitionException extends RuntimeException {

    private static final String ERROR_CODE = "booking.invalidTransition";

    public BookingStateTransitionException(BookingStatus from, BookingEvent event) {
        super("Invalid booking transition: " + from + " + " + event);
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
