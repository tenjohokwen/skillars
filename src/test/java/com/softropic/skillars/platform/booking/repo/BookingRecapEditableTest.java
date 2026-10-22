package com.softropic.skillars.platform.booking.repo;

import com.softropic.skillars.platform.config.service.ConfigBounds;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** skillars-12-7 AC3: the pure window predicate, its boundary, and the key-coupling to the sweeper timeout. */
class BookingRecapEditableTest {

    private static final Instant END = Instant.parse("2026-03-01T12:00:00Z");

    private Booking completed() {
        Booking b = new Booking();
        b.setStatus("COMPLETED");
        b.setRequestedEndTime(END);
        return b;
    }

    @Test
    void insideWindow_isEditable_andExactlyAtTheBoundaryIsClosed() {
        Booking b = completed();
        assertThat(b.isRecapEditable(END.plusSeconds(86_400L * 30 - 1), 30)).isTrue();
        assertThat(b.isRecapEditable(END.plusSeconds(86_400L * 30), 30)).isFalse();
        assertThat(b.recapEditableUntil(30)).isEqualTo(END.plusSeconds(86_400L * 30));
    }

    @Test
    void nonCompletedStatus_isNeverEditable() {
        Booking b = completed();
        b.setStatus("COMPLETED_PENDING_CONFIRMATION");
        assertThat(b.isRecapEditable(END.plusSeconds(60), 30)).isFalse();
    }

    @Test
    void nullEndTime_isClosed_unlikeThePlanNotePredicate() {
        Booking b = completed();
        b.setRequestedEndTime(null);
        assertThat(b.isRecapEditable(END, 30)).isFalse();
        assertThat(b.recapEditableUntil(30)).isNull();
    }

    /** A window shorter than the sweeper timeout would make swept sessions born un-editable and un-nudged. */
    @Test
    void defaultWindow_exceedsDefaultStuckCompletionTimeout_andFloorExceedsIt() {
        long stuckDefaultDays = ConfigBounds.BOOKING_STUCK_COMPLETION_TIMEOUT_HOURS.defaultValue() / 24;
        assertThat(ConfigBounds.BOOKING_RECAP_EDIT_WINDOW_DAYS.defaultValue()).isGreaterThan(stuckDefaultDays);
        assertThat(ConfigBounds.BOOKING_RECAP_EDIT_WINDOW_DAYS.min()).isGreaterThan(stuckDefaultDays);
    }
}
