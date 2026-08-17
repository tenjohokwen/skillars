package com.softropic.skillars.platform.booking.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Boundary coverage for BookingRepository.findOverlappingBookings' half-open interval logic
// (Story 3.11 review patch) — bookings.coach_id/parent_id/player_id carry no FK constraints, so
// this seeds Booking rows directly with no coach/player/parent fixture data required. All seeded
// rows use REQUESTED status, which is outside the DB-level excl_bkg_coach_slot_overlap exclusion
// constraint's scope (see V87), so overlapping rows here don't trip that constraint — this test
// is purely about the JPQL query's own boundary correctness.
class BookingRepositoryIT extends AbstractIntegrationTest {

    @Autowired private BookingRepository bookingRepository;

    private static final List<String> STATUSES = List.of("REQUESTED");

    private UUID coachId;
    private Instant existingStart;
    private Instant existingEnd;

    @AfterEach
    void tearDown() {
        if (coachId != null) {
            bookingRepository.findAllByCoachId(coachId).forEach(b -> bookingRepository.deleteById(b.getId()));
        }
    }

    @Test
    void adjacentBookingImmediatelyBefore_doesNotOverlap() {
        seedExisting();
        // Query range ends exactly when the existing booking starts — half-open interval, no overlap.
        List<Booking> result = bookingRepository.findOverlappingBookings(
            coachId, existingStart.minusSeconds(3600), existingStart, STATUSES, null);

        assertThat(result).isEmpty();
    }

    @Test
    void adjacentBookingImmediatelyAfter_doesNotOverlap() {
        seedExisting();
        // Query range starts exactly when the existing booking ends — half-open interval, no overlap.
        List<Booking> result = bookingRepository.findOverlappingBookings(
            coachId, existingEnd, existingEnd.plusSeconds(3600), STATUSES, null);

        assertThat(result).isEmpty();
    }

    @Test
    void fullyNestedBooking_overlaps() {
        seedExisting();
        List<Booking> result = bookingRepository.findOverlappingBookings(
            coachId, existingStart.plusSeconds(600), existingEnd.minusSeconds(600), STATUSES, null);

        assertThat(result).hasSize(1);
    }

    @Test
    void partialOverlapAtStart_overlaps() {
        seedExisting();
        List<Booking> result = bookingRepository.findOverlappingBookings(
            coachId, existingStart.minusSeconds(1800), existingStart.plusSeconds(1800), STATUSES, null);

        assertThat(result).hasSize(1);
    }

    @Test
    void exactBoundaryMatch_overlaps() {
        seedExisting();
        List<Booking> result = bookingRepository.findOverlappingBookings(
            coachId, existingStart, existingEnd, STATUSES, null);

        assertThat(result).hasSize(1);
    }

    @Test
    void excludeBookingId_omitsItselfFromResults() {
        Booking existing = seedExisting();
        List<Booking> result = bookingRepository.findOverlappingBookings(
            coachId, existingStart, existingEnd, STATUSES, existing.getId());

        assertThat(result).isEmpty();
    }

    @Test
    void differentCoach_doesNotOverlap() {
        seedExisting();
        List<Booking> result = bookingRepository.findOverlappingBookings(
            UUID.randomUUID(), existingStart, existingEnd, STATUSES, null);

        assertThat(result).isEmpty();
    }

    @Test
    void statusNotInFilterList_doesNotOverlap() {
        seedExisting();
        List<Booking> result = bookingRepository.findOverlappingBookings(
            coachId, existingStart, existingEnd, List.of("DECLINED"), null);

        assertThat(result).isEmpty();
    }

    // AC2 (skillars-deferred-27): Booking.parentId/playerId/coachId are annotated updatable = false
    // (Booking.java:31-38) but no test proved Hibernate actually excludes them from the generated
    // UPDATE statement, only that no current code path mutates them. updatable = false is silently
    // ignored, not exception-throwing, so the assertion is "value unchanged after mutate+flush+reload".
    @Test
    void updatableFalseColumns_mutationIsIgnoredAfterFlushAndReload() {
        Booking existing = seedExisting();
        UUID bookingId = existing.getId();
        Long originalParentId = existing.getParentId();
        Long originalPlayerId = existing.getPlayerId();
        UUID originalCoachId = existing.getCoachId();

        Booking managed = bookingRepository.findById(bookingId).orElseThrow();
        managed.setParentId(originalParentId + 1);
        managed.setPlayerId(originalPlayerId + 1);
        managed.setCoachId(UUID.randomUUID());
        // status carries no updatable = false guard — mutating it alongside the three immutable
        // columns, in the same flush, characterizes that only those three are guarded rather than
        // the whole entity happening to be immutable. "DECLINED", not "ACCEPTED": ACCEPTED would move
        // this row into excl_bkg_coach_slot_overlap's scope (V87), which this class's header comment
        // states REQUESTED status deliberately stays outside of.
        managed.setStatus("DECLINED");
        bookingRepository.saveAndFlush(managed);

        // This second findById is a genuine DB round-trip, not a persistence-context hit: this class has
        // no @Transactional (nor does AbstractIntegrationTest), so each repository call opens and closes
        // its own transaction and its own persistence context. OSIV is disabled (application.yaml) and no
        // Hibernate L2 cache is configured, so nothing can serve a stale read.
        Optional<Booking> reloaded = bookingRepository.findById(bookingId);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getParentId()).isEqualTo(originalParentId);
        assertThat(reloaded.get().getPlayerId()).isEqualTo(originalPlayerId);
        assertThat(reloaded.get().getCoachId()).isEqualTo(originalCoachId);
        assertThat(reloaded.get().getStatus()).isEqualTo("DECLINED");
    }

    private Booking seedExisting() {
        coachId = UUID.randomUUID();
        existingStart = Instant.now().plusSeconds(86_400);
        existingEnd = existingStart.plusSeconds(3600);

        Booking booking = new Booking();
        booking.setParentId(1L);
        booking.setPlayerId(1L);
        booking.setCoachId(coachId);
        booking.setRequestedStartTime(existingStart);
        booking.setRequestedEndTime(existingEnd);
        booking.setCanonicalTimezone("Europe/Berlin");
        booking.setStatus("REQUESTED");
        return bookingRepository.save(booking);
    }
}
