package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.BookingStateTransitionException;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.softropic.skillars.platform.booking.contract.BookingEvent.ACCEPT;
import static com.softropic.skillars.platform.booking.contract.BookingEvent.CANCEL_COACH;
import static com.softropic.skillars.platform.booking.contract.BookingEvent.CANCEL_PARENT;
import static com.softropic.skillars.platform.booking.contract.BookingEvent.COMPLETE;
import static com.softropic.skillars.platform.booking.contract.BookingEvent.COMPLETE_PENDING;
import static com.softropic.skillars.platform.booking.contract.BookingEvent.DECLINE;
import static com.softropic.skillars.platform.booking.contract.BookingEvent.DISPUTE;
import static com.softropic.skillars.platform.booking.contract.BookingEvent.INITIATE_PAYMENT;
import static com.softropic.skillars.platform.booking.contract.BookingEvent.PAYMENT_CAPTURED;
import static com.softropic.skillars.platform.booking.contract.BookingEvent.PAYMENT_FAILED;
import static com.softropic.skillars.platform.booking.contract.BookingEvent.QUICK_COMPLETE;
import static com.softropic.skillars.platform.booking.contract.BookingEvent.REFUND_PROCESSED;
import static com.softropic.skillars.platform.booking.contract.BookingEvent.SETTLE_COMPLETE;
import static com.softropic.skillars.platform.booking.contract.BookingEvent.SETTLE_REFUND;
import static com.softropic.skillars.platform.booking.contract.BookingEvent.START;
import static com.softropic.skillars.platform.booking.contract.BookingStatus.ACCEPTED;
import static com.softropic.skillars.platform.booking.contract.BookingStatus.CANCELLED_COACH;
import static com.softropic.skillars.platform.booking.contract.BookingStatus.CANCELLED_PARENT;
import static com.softropic.skillars.platform.booking.contract.BookingStatus.COMPLETED;
import static com.softropic.skillars.platform.booking.contract.BookingStatus.COMPLETED_PENDING_CONFIRMATION;
import static com.softropic.skillars.platform.booking.contract.BookingStatus.CONFIRMED;
import static com.softropic.skillars.platform.booking.contract.BookingStatus.DECLINED;
import static com.softropic.skillars.platform.booking.contract.BookingStatus.DISPUTED;
import static com.softropic.skillars.platform.booking.contract.BookingStatus.IN_PROGRESS;
import static com.softropic.skillars.platform.booking.contract.BookingStatus.PAYMENT_PENDING;
import static com.softropic.skillars.platform.booking.contract.BookingStatus.REFUND_PENDING;
import static com.softropic.skillars.platform.booking.contract.BookingStatus.REFUNDED;
import static com.softropic.skillars.platform.booking.contract.BookingStatus.REQUESTED;
import static com.softropic.skillars.platform.booking.contract.BookingStatus.UPCOMING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingStateMachineTest {

    private BookingStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new BookingStateMachine();
    }

    static Stream<Arguments> validTransitions() {
        return Stream.of(
            Arguments.of(REQUESTED, ACCEPT, ACCEPTED),
            Arguments.of(REQUESTED, DECLINE, DECLINED),
            Arguments.of(ACCEPTED, INITIATE_PAYMENT, PAYMENT_PENDING),
            Arguments.of(ACCEPTED, CANCEL_COACH, CANCELLED_COACH),
            Arguments.of(ACCEPTED, CANCEL_PARENT, CANCELLED_PARENT),
            Arguments.of(PAYMENT_PENDING, PAYMENT_CAPTURED, CONFIRMED),
            Arguments.of(PAYMENT_PENDING, PAYMENT_FAILED, REFUND_PENDING),
            Arguments.of(CONFIRMED, CANCEL_COACH, CANCELLED_COACH),
            Arguments.of(CONFIRMED, CANCEL_PARENT, CANCELLED_PARENT),
            Arguments.of(UPCOMING, START, IN_PROGRESS),
            Arguments.of(UPCOMING, BookingEvent.NO_SHOW_PLAYER, BookingStatus.NO_SHOW_PLAYER),
            Arguments.of(UPCOMING, BookingEvent.NO_SHOW_COACH, BookingStatus.NO_SHOW_COACH),
            Arguments.of(UPCOMING, CANCEL_COACH, CANCELLED_COACH),
            Arguments.of(UPCOMING, CANCEL_PARENT, CANCELLED_PARENT),
            Arguments.of(IN_PROGRESS, COMPLETE_PENDING, COMPLETED_PENDING_CONFIRMATION),
            Arguments.of(IN_PROGRESS, DISPUTE, DISPUTED),
            Arguments.of(COMPLETED_PENDING_CONFIRMATION, COMPLETE, COMPLETED),
            Arguments.of(COMPLETED_PENDING_CONFIRMATION, QUICK_COMPLETE, COMPLETED),
            Arguments.of(COMPLETED_PENDING_CONFIRMATION, DISPUTE, DISPUTED),
            Arguments.of(COMPLETED, DISPUTE, DISPUTED),
            Arguments.of(DISPUTED, SETTLE_REFUND, REFUND_PENDING),
            Arguments.of(DISPUTED, SETTLE_COMPLETE, COMPLETED),
            Arguments.of(REFUND_PENDING, REFUND_PROCESSED, REFUNDED)
        );
    }

    @ParameterizedTest
    @MethodSource("validTransitions")
    void targetStatus_validTransition_returnsExpectedStatus(BookingStatus from, BookingEvent event, BookingStatus expected) {
        BookingStatus result = stateMachine.targetStatus(from, event);
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("validTransitions")
    void validate_validTransition_doesNotThrow(BookingStatus from, BookingEvent event, BookingStatus ignored) {
        // Should not throw
        stateMachine.validate(from, event);
    }

    @Test
    void targetStatus_invalidTransition_throwsBookingStateTransitionException() {
        assertThatThrownBy(() -> stateMachine.targetStatus(COMPLETED, ACCEPT))
            .isInstanceOf(BookingStateTransitionException.class)
            .satisfies(ex -> assertThat(((BookingStateTransitionException) ex).getErrorCode())
                .isEqualTo("booking.invalidTransition"));
    }

    @Test
    void validate_invalidTransition_throwsBookingStateTransitionException() {
        assertThatThrownBy(() -> stateMachine.validate(REQUESTED, DISPUTE))
            .isInstanceOf(BookingStateTransitionException.class);
    }

    @Test
    void terminalState_REFUNDED_allEventsThrow() {
        for (BookingEvent event : BookingEvent.values()) {
            assertThatThrownBy(() -> stateMachine.validate(REFUNDED, event))
                .isInstanceOf(BookingStateTransitionException.class);
        }
    }

    @Test
    void terminalState_DECLINED_allEventsThrow() {
        for (BookingEvent event : BookingEvent.values()) {
            assertThatThrownBy(() -> stateMachine.validate(DECLINED, event))
                .isInstanceOf(BookingStateTransitionException.class);
        }
    }

    @Test
    void completedPendingConfirmation_COMPLETE_reachesCompleted() {
        BookingStatus result = stateMachine.targetStatus(COMPLETED_PENDING_CONFIRMATION, COMPLETE);
        assertThat(result).isEqualTo(COMPLETED);
    }

    @Test
    void completedPendingConfirmation_QUICK_COMPLETE_reachesCompleted() {
        BookingStatus result = stateMachine.targetStatus(COMPLETED_PENDING_CONFIRMATION, QUICK_COMPLETE);
        assertThat(result).isEqualTo(COMPLETED);
    }
}
