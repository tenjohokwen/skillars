package com.softropic.skillars.platform.booking.contract;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.UUID;

@Getter
public class QuickCompleteConfirmationRequiredEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final Long parentId;
    private final String parentEmail;
    private final String coachDisplayName;
    private final Instant sessionStartTime;
    private final String canonicalTimezone;
    private final String playerName;

    public QuickCompleteConfirmationRequiredEvent(Object source, UUID bookingId, Long parentId,
                                                   String parentEmail, String coachDisplayName,
                                                   Instant sessionStartTime, String canonicalTimezone,
                                                   String playerName) {
        super(source);
        this.bookingId = bookingId;
        this.parentId = parentId;
        this.parentEmail = parentEmail;
        this.coachDisplayName = coachDisplayName;
        this.sessionStartTime = sessionStartTime;
        this.canonicalTimezone = canonicalTimezone;
        this.playerName = playerName;
    }
}
