package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.UUID;

public class BookingReminderEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final String parentEmail;
    private final String coachEmail;
    private final String coachDisplayName;
    private final Instant requestedStartTime;
    private final String canonicalTimezone;
    private final String reminderType;

    private BookingReminderEvent(Builder builder) {
        super(builder.source);
        this.bookingId = builder.bookingId;
        this.parentEmail = builder.parentEmail;
        this.coachEmail = builder.coachEmail;
        this.coachDisplayName = builder.coachDisplayName;
        this.requestedStartTime = builder.requestedStartTime;
        this.canonicalTimezone = builder.canonicalTimezone;
        this.reminderType = builder.reminderType;
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getBookingId() { return bookingId; }
    public String getParentEmail() { return parentEmail; }
    public String getCoachEmail() { return coachEmail; }
    public String getCoachDisplayName() { return coachDisplayName; }
    public Instant getRequestedStartTime() { return requestedStartTime; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
    public String getReminderType() { return reminderType; }

    public static final class Builder {
        private Object source;
        private UUID bookingId;
        private String parentEmail;
        private String coachEmail;
        private String coachDisplayName;
        private Instant requestedStartTime;
        private String canonicalTimezone;
        private String reminderType;

        private Builder() { }

        public Builder source(Object source) { this.source = source; return this; }
        public Builder bookingId(UUID bookingId) { this.bookingId = bookingId; return this; }
        public Builder parentEmail(String parentEmail) { this.parentEmail = parentEmail; return this; }
        public Builder coachEmail(String coachEmail) { this.coachEmail = coachEmail; return this; }
        public Builder coachDisplayName(String coachDisplayName) { this.coachDisplayName = coachDisplayName; return this; }
        public Builder requestedStartTime(Instant requestedStartTime) { this.requestedStartTime = requestedStartTime; return this; }
        public Builder canonicalTimezone(String canonicalTimezone) { this.canonicalTimezone = canonicalTimezone; return this; }
        public Builder reminderType(String reminderType) { this.reminderType = reminderType; return this; }

        public BookingReminderEvent build() {
            return new BookingReminderEvent(this);
        }
    }
}
