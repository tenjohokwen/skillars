package com.softropic.skillars.platform.booking.repo;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fix/booking-integrity (B2): the dispute window and review maturity are anchored on when the booking
 * entered its current status ({@code statusChangedAt}, V178), not {@code updatedAt}, which every later
 * write bumps.
 */
class BookingStatusAnchorTest {

    @Test
    void setStatus_changingTheValue_stampsStatusChangedAt() {
        Booking b = new Booking();
        b.setStatus("COMPLETED_PENDING_CONFIRMATION");
        Instant first = b.getStatusChangedAt();
        assertThat(first).isNotNull();

        setField(b, "statusChangedAt", Instant.parse("2020-01-01T00:00:00Z"));
        b.setStatus("COMPLETED");

        assertThat(b.getStatusChangedAt()).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
    }

    @Test
    void setStatus_sameValue_leavesStatusChangedAtAlone() {
        Booking b = new Booking();
        b.setStatus("COMPLETED");
        Instant settled = Instant.parse("2020-01-01T00:00:00Z");
        setField(b, "statusChangedAt", settled);

        b.setStatus("COMPLETED");
        b.setNotes("a later, unrelated edit");

        assertThat(b.getStatusChangedAt()).isEqualTo(settled);
        assertThat(b.getStatusAnchor()).isEqualTo(settled);
    }

    @Test
    void statusAnchor_preV178RowWithoutStatusChangedAt_fallsBackToUpdatedAt() {
        Booking b = new Booking();
        Instant updated = Instant.parse("2021-06-01T00:00:00Z");
        setField(b, "status", "COMPLETED"); // as Hibernate hydrates it: field access, no setter
        setField(b, "updatedAt", updated);

        assertThat(b.getStatusChangedAt()).isNull();
        assertThat(b.getStatusAnchor()).isEqualTo(updated);
    }

    @Test
    void statusAnchor_prefersStatusChangedAtOverALaterUpdatedAt() {
        Booking b = new Booking();
        Instant settled = Instant.parse("2021-06-01T00:00:00Z");
        setField(b, "statusChangedAt", settled);
        setField(b, "updatedAt", Instant.parse("2021-07-01T00:00:00Z"));

        assertThat(b.getStatusAnchor()).isEqualTo(settled);
    }

    private static void setField(Booking b, String name, Object value) {
        try {
            Field f = Booking.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(b, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
