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
    private final String whatWentWell;
    /** fix/audit3-completion-dispute-integrity: which side of the completion this event carries. */
    private final Scope scope;

    /**
     * fix/audit3-completion-dispute-integrity: a completion has a settlement side (coach payout, session plan
     * locked) and a development side (SLU, timeline, homework). They normally travel together ({@link #FULL}).
     * When the family confirms before the coach's wrap-up, the confirm settles ({@link #SETTLEMENT_ONLY}) and the
     * coach's later wrap-up — with the real attendance, ratings and homework — records the development side
     * ({@link #DEVELOPMENT_ONLY}), so neither runs twice and the development side never runs on placeholder data.
     */
    public enum Scope { FULL, SETTLEMENT_ONLY, DEVELOPMENT_ONLY }

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
        this(source, bookingId, coachId, playerId, parentId, playerAttended, effortRating,
            focusRating, techniqueRating, homeworkDrillIds, whatWeDid, whatsNext, null);
    }

    public BookingCompletedEvent(Object source, UUID bookingId, UUID coachId, Long playerId,
                                  Long parentId, boolean playerAttended, Integer effortRating,
                                  Integer focusRating, Integer techniqueRating,
                                  List<UUID> homeworkDrillIds, String whatWeDid, String whatsNext,
                                  String whatWentWell) {
        this(source, bookingId, coachId, playerId, parentId, playerAttended, effortRating,
            focusRating, techniqueRating, homeworkDrillIds, whatWeDid, whatsNext, whatWentWell, Scope.FULL);
    }

    @SuppressWarnings("java:S107") // an immutable event: one field per fact its listeners need
    public BookingCompletedEvent(Object source, UUID bookingId, UUID coachId, Long playerId,
                                  Long parentId, boolean playerAttended, Integer effortRating,
                                  Integer focusRating, Integer techniqueRating,
                                  List<UUID> homeworkDrillIds, String whatWeDid, String whatsNext,
                                  String whatWentWell, Scope scope) {
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
        this.whatWentWell = whatWentWell;
        this.scope = scope;
    }

    /** Payout and session-plan listeners act on this event. */
    public boolean isSettlement() {
        return scope != Scope.DEVELOPMENT_ONLY;
    }

    /** SLU, timeline and homework listeners act on this event. */
    public boolean isDevelopment() {
        return scope != Scope.SETTLEMENT_ONLY;
    }
}
