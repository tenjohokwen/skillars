package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.UUID;

public class BookingConfirmedEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final Long parentId;
    private final String parentEmail;
    private final String coachDisplayName;
    private final Instant requestedStartTime;
    private final String canonicalTimezone;

    private BookingConfirmedEvent(Builder builder) {
        super(builder.source);
        this.bookingId = builder.bookingId;
        this.parentId = builder.parentId;
        this.parentEmail = builder.parentEmail;
        this.coachDisplayName = builder.coachDisplayName;
        this.requestedStartTime = builder.requestedStartTime;
        this.canonicalTimezone = builder.canonicalTimezone;
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getBookingId() { return bookingId; }
    public Long getParentId() { return parentId; }
    public String getParentEmail() { return parentEmail; }
    public String getCoachDisplayName() { return coachDisplayName; }
    public Instant getRequestedStartTime() { return requestedStartTime; }
    public String getCanonicalTimezone() { return canonicalTimezone; }

    public static final class Builder {
        private Object source;
        private UUID bookingId;
        private Long parentId;
        private String parentEmail;
        private String coachDisplayName;
        private Instant requestedStartTime;
        private String canonicalTimezone;

        private Builder() { }

        public Builder source(Object source) { this.source = source; return this; }
        public Builder bookingId(UUID bookingId) { this.bookingId = bookingId; return this; }
        public Builder parentId(Long parentId) { this.parentId = parentId; return this; }
        public Builder parentEmail(String parentEmail) { this.parentEmail = parentEmail; return this; }
        public Builder coachDisplayName(String coachDisplayName) { this.coachDisplayName = coachDisplayName; return this; }
        public Builder requestedStartTime(Instant requestedStartTime) { this.requestedStartTime = requestedStartTime; return this; }
        public Builder canonicalTimezone(String canonicalTimezone) { this.canonicalTimezone = canonicalTimezone; return this; }

        public BookingConfirmedEvent build() {
            return new BookingConfirmedEvent(this);
        }
    }
}
