package com.softropic.skillars.platform.booking.contract;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * skillars-12-7 AC4: published after a coach edits a completed session's recap. Ids only on purpose --
 * the timeline listener re-reads the current {@code session_completion_data} row, so two quick edits
 * applied out of order on the async executor still converge on the source of truth.
 */
@Getter
public class SessionRecapUpdatedEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final UUID coachId;
    private final Long playerId;

    public SessionRecapUpdatedEvent(Object source, UUID bookingId, UUID coachId, Long playerId) {
        super(source);
        this.bookingId = bookingId;
        this.coachId = coachId;
        this.playerId = playerId;
    }
}
