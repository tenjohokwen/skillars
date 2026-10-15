package com.softropic.skillars.platform.booking.contract;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;
import java.util.UUID;

@Getter
public class BookingCompletedEvent extends ApplicationEvent {

    private final UUID bookingId;
    private final UUID coachId;
    private final Long playerId;
    private final Long parentId;
    private final boolean playerAttended;
    private final Integer effortRating;
    private final Integer focusRating;
    private final Integer techniqueRating;
    private final List<UUID> homeworkDrillIds;
    private final String whatWeDid;
    private final String whatsNext;

    public BookingCompletedEvent(Object source, UUID bookingId, UUID coachId, Long playerId,
                                  Long parentId, boolean playerAttended, Integer effortRating,
                                  Integer focusRating, Integer techniqueRating,
                                  List<UUID> homeworkDrillIds) {
        this(source, bookingId, coachId, playerId, parentId, playerAttended, effortRating,
            focusRating, techniqueRating, homeworkDrillIds, null, null);
    }

    public BookingCompletedEvent(Object source, UUID bookingId, UUID coachId, Long playerId,
                                  Long parentId, boolean playerAttended, Integer effortRating,
                                  Integer focusRating, Integer techniqueRating,
                                  List<UUID> homeworkDrillIds, String whatWeDid, String whatsNext) {
        super(source);
        this.bookingId = bookingId;
        this.coachId = coachId;
        this.playerId = playerId;
        this.parentId = parentId;
        this.playerAttended = playerAttended;
        this.effortRating = effortRating;
        this.focusRating = focusRating;
        this.techniqueRating = techniqueRating;
        this.homeworkDrillIds = homeworkDrillIds;
        this.whatWeDid = whatWeDid;
        this.whatsNext = whatsNext;
    }
}
