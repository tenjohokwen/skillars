package com.softropic.skillars.platform.booking.repo;

import com.softropic.skillars.config.AbstractIntegrationTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

// Boundary coverage for BookingRepository.findOverlappingBookings' half-open interval logic
// (Story 3.11 review patch) — bookings.coach_id/parent_id/player_id carry no FK constraints, so
// this seeds Booking rows directly with no coach/player/parent fixture data required. The
// findOverlappingBookings tests below all seed REQUESTED status, which is outside the DB-level
// excl_bkg_coach_slot_overlap exclusion constraint's scope (see V87), so overlapping rows there
// don't trip that constraint -- those tests are purely about the JPQL query's own boundary
// correctness. skillars-deferred-150 AC3 added a sibling test for
// findQualifyingCompletedBookings, which seeds COMPLETED rows instead (that query filters on
// status = 'COMPLETED', so REQUESTED rows would never match) -- not every test in this class
// uses REQUESTED.
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

    // skillars-deferred-90 AC1: pins the premise that a V87 excl_bkg_coach_slot_overlap breach
    // reaches the app as a Hibernate ConstraintViolationException with a NULL constraint name and
    // SQLSTATE 23P01 (PostgreSQLDialect's templated extractor only names 23502/23503/23505/23514).
    // ApiAdvice.integrityViolationHandler recovers this to a clean 409 booking.slotUnavailable;
    // if the driver/Hibernate ever starts populating the name this test flips and we can simplify.
    @Test
    void overlappingAcceptedBookings_tripExclusionConstraint_withNullNameAnd23P01State() {
        coachId = UUID.randomUUID();
        Instant start = Instant.now().plusSeconds(172_800);
        Instant end = start.plusSeconds(3600);

        Booking first = new Booking();
        first.setParentId(1L);
        first.setPlayerId(1L);
        first.setCoachId(coachId);
        first.setRequestedStartTime(start);
        first.setRequestedEndTime(end);
        first.setCanonicalTimezone("Europe/Berlin");
        first.setStatus("ACCEPTED");
        bookingRepository.save(first);

        Booking overlapping = new Booking();
        overlapping.setParentId(2L);
        overlapping.setPlayerId(2L);
        overlapping.setCoachId(coachId);
        overlapping.setRequestedStartTime(start.plusSeconds(1800));
        overlapping.setRequestedEndTime(end.plusSeconds(1800));
        overlapping.setCanonicalTimezone("Europe/Berlin");
        overlapping.setStatus("ACCEPTED");

        Throwable thrown = catchThrowable(() -> bookingRepository.save(overlapping));

        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        Throwable cause = thrown.getCause();
        assertThat(cause).isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
        org.hibernate.exception.ConstraintViolationException cve =
            (org.hibernate.exception.ConstraintViolationException) cause;
        assertThat(cve.getConstraintName()).as("PostgreSQLDialect does not template a 23P01 name").isNull();
        assertThat(cve.getSQLState()).isEqualTo("23P01");
        assertThat(cve.getSQLException().getMessage()).contains("excl_bkg_coach_slot_overlap");
    }

    // skillars-deferred-150 AC3: findQualifyingCompletedBookings now carries a defensive
    // ORDER BY b.playerId + HQL-literal `limit 50`. ORDER BY is not optional alongside the bound —
    // an unordered SELECT DISTINCT truncated by a bare LIMIT could silently drop the caller's one
    // actually-owned playerId. This pins the ordering+cap combination deterministically picks the
    // lowest 50 distinct playerIds, not an arbitrary Postgres-dependent subset, by seeding more
    // distinct qualifying playerIds than the cap and asserting the exact boundary.
    //
    // Code review 2026-10-09 (Patch): also seeds four NEGATIVE-fixture rows with playerIds
    // (-4..-1) that sort BELOW every matching row (1..55) but each violates exactly one WHERE
    // filter (wrong coachId, wrong status, too-recent, too-old). Without these, the test cannot
    // tell "the query filters correctly, then orders+caps" apart from a hypothetical "orders+caps
    // first, filters after" bug -- the earlier version had no row that would surface such a bug,
    // since every seeded row matched every filter.
    @Test
    void findQualifyingCompletedBookings_ordersAndCapsDeterministically() {
        coachId = UUID.randomUUID();
        Long authorId = 1L;
        Instant now = Instant.now();
        Instant sinceAfter = now.minusSeconds(365 * 86_400L);
        Instant maturedBefore = now.plusSeconds(3600);

        // 55 distinct qualifying playerIds (1..55), one booking each, all owned by the same
        // author (parentId = authorId) against the same coach, all COMPLETED and within window.
        for (long playerId = 1; playerId <= 55; playerId++) {
            Booking booking = new Booking();
            booking.setParentId(authorId);
            booking.setPlayerId(playerId);
            booking.setCoachId(coachId);
            booking.setRequestedStartTime(now.minusSeconds(7200));
            booking.setRequestedEndTime(now.minusSeconds(3600));
            booking.setCanonicalTimezone("Europe/Berlin");
            booking.setStatus("COMPLETED");
            bookingRepository.save(booking);
        }

        // Negative fixtures: each would sort ahead of every matching row (lowest playerIds of
        // all, -4..-1) if the query's WHERE filter did not actually exclude it.
        Booking wrongCoach = new Booking();
        wrongCoach.setParentId(authorId);
        wrongCoach.setPlayerId(-4L);
        wrongCoach.setCoachId(UUID.randomUUID()); // not this test's coachId
        wrongCoach.setRequestedStartTime(now.minusSeconds(7200));
        wrongCoach.setRequestedEndTime(now.minusSeconds(3600));
        wrongCoach.setCanonicalTimezone("Europe/Berlin");
        wrongCoach.setStatus("COMPLETED");
        bookingRepository.save(wrongCoach);

        Booking wrongStatus = new Booking();
        wrongStatus.setParentId(authorId);
        wrongStatus.setPlayerId(-3L);
        wrongStatus.setCoachId(coachId);
        wrongStatus.setRequestedStartTime(now.minusSeconds(7200));
        wrongStatus.setRequestedEndTime(now.minusSeconds(3600));
        wrongStatus.setCanonicalTimezone("Europe/Berlin");
        wrongStatus.setStatus("REQUESTED"); // not COMPLETED
        bookingRepository.save(wrongStatus);

        Booking tooRecent = new Booking();
        tooRecent.setParentId(authorId);
        tooRecent.setPlayerId(-2L);
        tooRecent.setCoachId(coachId);
        tooRecent.setRequestedStartTime(now.plusSeconds(3600));
        tooRecent.setRequestedEndTime(now.plusSeconds(7200));
        tooRecent.setCanonicalTimezone("Europe/Berlin");
        tooRecent.setStatus("COMPLETED");
        bookingRepository.save(tooRecent);
        // onCreate() stamps updatedAt = now() regardless of requestedStartTime; push it past
        // maturedBefore (now+3600) directly (GenerationType.UUID assigns the id client-side at
        // save(), so it's already available here) so this row fails the "<= maturedBefore"
        // matured check. This project's bare-jdbcTemplate-writes-never-commit pitfall applies
        // here as everywhere else (Hikari autocommit=false) -- wrap it.
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE booking.bookings SET updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now.plusSeconds(7200)), tooRecent.getId());
            return null;
        });

        Booking tooOld = new Booking();
        tooOld.setParentId(authorId);
        tooOld.setPlayerId(-1L);
        tooOld.setCoachId(coachId);
        tooOld.setRequestedStartTime(sinceAfter.minusSeconds(7200));
        tooOld.setRequestedEndTime(sinceAfter.minusSeconds(3600));
        tooOld.setCanonicalTimezone("Europe/Berlin");
        tooOld.setStatus("COMPLETED");
        bookingRepository.save(tooOld);
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE booking.bookings SET updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(sinceAfter.minusSeconds(3600)), tooOld.getId());
            return null;
        });

        List<BookingReviewEligibilityProjection> result =
            bookingRepository.findQualifyingCompletedBookings(coachId, authorId, maturedBefore, sinceAfter);

        List<Long> playerIds = result.stream().map(BookingReviewEligibilityProjection::getPlayerId).toList();
        assertThat(playerIds).hasSize(50);
        // ORDER BY b.playerId ascending + limit 50 must deterministically select the 50 lowest
        // distinct MATCHING playerIds (1..50) -- none of the four negative fixtures (-4..-1),
        // despite sorting ahead of every one of them.
        List<Long> expected = java.util.stream.LongStream.rangeClosed(1, 50).boxed().toList();
        assertThat(playerIds).isEqualTo(expected);
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
