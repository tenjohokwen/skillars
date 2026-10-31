package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * S-07: the coach's "press Start, or this session can be refunded" reminder for a session still UPCOMING — published
 * by {@code CoachStartReminderScheduler} inside the transaction that stamps the booking's per-reminder sent-at column,
 * so each reminder is queued once. {@code reminderType} is {@link #START} (at the scheduled start) or {@link #LATE}
 * (at start + 10 minutes). {@code BookingEmailListener} turns it into the {@code COACH_START_REMINDER} email, which
 * also produces the coach's in-app inbox row (category BOOKING: transactional, never opt-out-able).
 */
public class CoachStartReminderEvent extends ApplicationEvent {

    public static final String START = "START";
    public static final String LATE = "LATE";

    private final UUID bookingId;
    private final String coachEmail;
    private final String playerName;
    private final Instant requestedStartTime;
    private final String canonicalTimezone;
    private final String reminderType;

    public CoachStartReminderEvent(Object source, UUID bookingId, String coachEmail, String playerName,
                                   Instant requestedStartTime, String canonicalTimezone, String reminderType) {
        super(source);
        this.bookingId = bookingId;
        this.coachEmail = coachEmail;
        this.playerName = playerName;
        this.requestedStartTime = requestedStartTime;
        this.canonicalTimezone = canonicalTimezone;
        this.reminderType = reminderType;
    }

    public UUID getBookingId() { return bookingId; }
    public String getCoachEmail() { return coachEmail; }
    public String getPlayerName() { return playerName; }
    public Instant getRequestedStartTime() { return requestedStartTime; }
    public String getCanonicalTimezone() { return canonicalTimezone; }
    public String getReminderType() { return reminderType; }
}
