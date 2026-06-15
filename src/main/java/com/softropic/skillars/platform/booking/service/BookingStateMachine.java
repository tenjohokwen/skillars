package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.BookingStateTransitionException;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class BookingStateMachine {

    private static final Map<BookingStatus, Map<BookingEvent, BookingStatus>> TRANSITIONS = buildTransitions();

    private static Map<BookingStatus, Map<BookingEvent, BookingStatus>> buildTransitions() {
        Map<BookingStatus, Map<BookingEvent, BookingStatus>> t = new HashMap<>();

        t.put(BookingStatus.REQUESTED, Map.of(
            BookingEvent.ACCEPT, BookingStatus.ACCEPTED,
            BookingEvent.DECLINE, BookingStatus.DECLINED
        ));
        t.put(BookingStatus.ACCEPTED, Map.of(
            BookingEvent.INITIATE_PAYMENT, BookingStatus.PAYMENT_PENDING,
            BookingEvent.CANCEL_COACH, BookingStatus.CANCELLED_COACH,
            BookingEvent.CANCEL_PARENT, BookingStatus.CANCELLED_PARENT
        ));
        t.put(BookingStatus.PAYMENT_PENDING, Map.of(
            BookingEvent.PAYMENT_CAPTURED, BookingStatus.CONFIRMED,
            BookingEvent.PAYMENT_FAILED, BookingStatus.REFUND_PENDING
        ));
        t.put(BookingStatus.CONFIRMED, Map.of(
            BookingEvent.SCHEDULE_UPCOMING, BookingStatus.UPCOMING,
            BookingEvent.CANCEL_COACH, BookingStatus.CANCELLED_COACH,
            BookingEvent.CANCEL_PARENT, BookingStatus.CANCELLED_PARENT
        ));
        t.put(BookingStatus.UPCOMING, Map.of(
            BookingEvent.START, BookingStatus.IN_PROGRESS,
            BookingEvent.NO_SHOW_PLAYER, BookingStatus.NO_SHOW_PLAYER,
            BookingEvent.NO_SHOW_COACH, BookingStatus.NO_SHOW_COACH,
            BookingEvent.CANCEL_COACH, BookingStatus.CANCELLED_COACH,
            BookingEvent.CANCEL_PARENT, BookingStatus.CANCELLED_PARENT
        ));
        t.put(BookingStatus.IN_PROGRESS, Map.of(
            BookingEvent.COMPLETE_PENDING, BookingStatus.COMPLETED_PENDING_CONFIRMATION,
            BookingEvent.DISPUTE, BookingStatus.DISPUTED
        ));
        t.put(BookingStatus.COMPLETED_PENDING_CONFIRMATION, Map.of(
            BookingEvent.COMPLETE, BookingStatus.COMPLETED,
            BookingEvent.QUICK_COMPLETE, BookingStatus.COMPLETED,
            BookingEvent.DISPUTE, BookingStatus.DISPUTED
        ));
        t.put(BookingStatus.COMPLETED, Map.of(
            BookingEvent.DISPUTE, BookingStatus.DISPUTED
        ));
        t.put(BookingStatus.DISPUTED, Map.of(
            BookingEvent.SETTLE_REFUND, BookingStatus.REFUND_PENDING,
            BookingEvent.SETTLE_COMPLETE, BookingStatus.COMPLETED
        ));
        t.put(BookingStatus.REFUND_PENDING, Map.of(
            BookingEvent.REFUND_PROCESSED, BookingStatus.REFUNDED
        ));

        return Map.copyOf(t);
    }

    public void validate(BookingStatus from, BookingEvent event) {
        Map<BookingEvent, BookingStatus> allowed = TRANSITIONS.getOrDefault(from, Map.of());
        if (!allowed.containsKey(event)) {
            throw new BookingStateTransitionException(from, event);
        }
    }

    public BookingStatus targetStatus(BookingStatus from, BookingEvent event) {
        Map<BookingEvent, BookingStatus> allowed = TRANSITIONS.getOrDefault(from, Map.of());
        BookingStatus target = allowed.get(event);
        if (target == null) {
            throw new BookingStateTransitionException(from, event);
        }
        return target;
    }
}
