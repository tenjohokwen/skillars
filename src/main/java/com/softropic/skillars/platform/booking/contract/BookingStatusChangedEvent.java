package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class BookingStatusChangedEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final String newStatus;

    public BookingStatusChangedEvent(Object source, UUID bookingId, String newStatus) {
        super(source);
        this.bookingId = bookingId;
        this.newStatus = newStatus;
    }

    public UUID bookingId() {
        return bookingId;
    }

    public String newStatus() {
        return newStatus;
    }
}
