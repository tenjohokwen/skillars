package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.session.contract.SessionBlockData;
import com.softropic.skillars.platform.session.contract.SessionDrillRef;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.Session;
import com.softropic.skillars.platform.session.repo.SessionRepository;
import com.softropic.skillars.platform.session.repo.SessionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.when;

/** skillars-12-8 AC1: drills-trained snapshot gating, ordering, dedupe, cap and best-effort failure. */
@ExtendWith(MockitoExtension.class)
class TimelineDrillSnapshotResolverTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private DrillRepository drillRepository;
    @InjectMocks private TimelineDrillSnapshotResolver resolver;

    private final UUID bookingId = UUID.randomUUID();
    private static final UUID COACH_ID = UUID.randomUUID();

    private static Drill drill(UUID id, String name) {
        Drill d = new Drill();
        d.setId(id);
        d.setName(name);
        d.setLibraryType("PLATFORM");
        return d;
    }

    private static Drill privateDrill(UUID id, String name, UUID ownerCoachId) {
        Drill d = drill(id, name);
        d.setLibraryType("PRIVATE");
        d.setOwnerCoachId(ownerCoachId);
        return d;
    }

    private static Session session(String status, List<SessionBlockData> blocks) {
        Session s = new Session();
        s.setStatus(status);
        s.setBlocks(blocks);
        s.setCoachId(COACH_ID);
        return s;
    }

    @Test
    void noSession_returnsEmpty() {
        when(sessionRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        assertThat(resolver.forBooking(bookingId)).isEmpty();
    }

    @Test
    void cancelledSession_returnsEmpty() {
        when(sessionRepository.findByBookingId(bookingId)).thenReturn(Optional.of(
            session(SessionStatus.CANCELLED, List.of(new SessionBlockData("a", "A", 10, List.of(new SessionDrillRef(UUID.randomUUID(), 1)))))));
        assertThat(resolver.forBooking(bookingId)).isEmpty();
    }

    @Test
    void draftSession_isAccepted_soTheResultDoesNotDependOnTheStatusFlipRace() {
        UUID d1 = UUID.randomUUID();
        when(sessionRepository.findByBookingId(bookingId)).thenReturn(Optional.of(
            session(SessionStatus.DRAFT, List.of(new SessionBlockData("a", "A", 10, List.of(new SessionDrillRef(d1, 1)))))));
        when(drillRepository.findAllById(anyIterable())).thenReturn(List.of(drill(d1, "Rondo")));

        List<Map<String, Object>> out = resolver.forBooking(bookingId);

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsEntry("drillId", d1.toString()).containsEntry("name", "Rondo");
    }

    @Test
    void nullDrillRef_isSkipped_notFatalForTheOtherDrills() {
        UUID d1 = UUID.randomUUID();
        List<SessionDrillRef> refs = new ArrayList<>();
        refs.add(null);
        refs.add(new SessionDrillRef(d1, 1));
        when(sessionRepository.findByBookingId(bookingId)).thenReturn(Optional.of(
            session(SessionStatus.SAVED, List.of(new SessionBlockData("a", "A", 10, refs)))));
        when(drillRepository.findAllById(anyIterable())).thenReturn(List.of(drill(d1, "Rondo")));

        assertThat(resolver.forBooking(bookingId)).extracting(m -> m.get("name")).containsExactly("Rondo");
    }

    @Test
    void ordersByBlockThenRef_dedupesFirstWins() {
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        UUID d3 = UUID.randomUUID();
        when(sessionRepository.findByBookingId(bookingId)).thenReturn(Optional.of(session(SessionStatus.COMPLETED, List.of(
            new SessionBlockData("w", "Warm", 10, List.of(new SessionDrillRef(d2, 2), new SessionDrillRef(d1, 1))),
            new SessionBlockData("m", "Main", 10, List.of(new SessionDrillRef(d1, 1), new SessionDrillRef(d3, 2)))))));
        when(drillRepository.findAllById(anyIterable())).thenReturn(
            List.of(drill(d3, "C"), drill(d1, "A"), drill(d2, "B")));

        assertThat(resolver.forBooking(bookingId)).extracting(m -> m.get("name")).containsExactly("A", "B", "C");
    }

    @Test
    void capsAtTwenty() {
        List<SessionDrillRef> refs = new ArrayList<>();
        List<Drill> drills = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            UUID id = UUID.randomUUID();
            refs.add(new SessionDrillRef(id, i));
            drills.add(drill(id, "D" + i));
        }
        when(sessionRepository.findByBookingId(bookingId)).thenReturn(Optional.of(
            session(SessionStatus.SAVED, List.of(new SessionBlockData("a", "A", 60, refs)))));
        when(drillRepository.findAllById(anyIterable())).thenReturn(drills);

        assertThat(resolver.forBooking(bookingId)).hasSize(TimelineDrillSnapshotResolver.MAX_DRILLS);
    }

    @Test
    void unresolvedDrill_isSkipped() {
        UUID d1 = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        when(sessionRepository.findByBookingId(bookingId)).thenReturn(Optional.of(session(SessionStatus.SAVED,
            List.of(new SessionBlockData("a", "A", 10, List.of(new SessionDrillRef(gone, 1), new SessionDrillRef(d1, 2)))))));
        when(drillRepository.findAllById(anyIterable())).thenReturn(List.of(drill(d1, "Keep")));

        assertThat(resolver.forBooking(bookingId)).extracting(m -> m.get("name")).containsExactly("Keep");
    }

    @Test
    void repositoryFailure_returnsEmptyInsteadOfThrowing() {
        when(sessionRepository.findByBookingId(bookingId)).thenThrow(new IllegalStateException("db down"));
        assertThat(resolver.forBooking(bookingId)).isEmpty();
    }

    // skillars-deferred-162 AC4.3/AC4.4: another coach's PRIVATE drill is never named on the timeline.

    @Test
    void forBooking_skipsAnotherCoachsPrivateDrill_keepsOwnPrivateAndPlatform() {
        UUID own = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        UUID platform = UUID.randomUUID();
        when(sessionRepository.findByBookingId(bookingId)).thenReturn(Optional.of(session(SessionStatus.SAVED,
            List.of(new SessionBlockData("a", "A", 10, List.of(
                new SessionDrillRef(own, 1), new SessionDrillRef(foreign, 2), new SessionDrillRef(platform, 3)))))));
        when(drillRepository.findAllById(anyIterable())).thenReturn(List.of(
            privateDrill(own, "Mine", COACH_ID),
            privateDrill(foreign, "Theirs", UUID.randomUUID()),
            drill(platform, "Shared")));

        assertThat(resolver.forBooking(bookingId)).extracting(m -> m.get("name")).containsExactly("Mine", "Shared");
    }

    @Test
    void snapshot_skipsAnotherCoachsPrivateDrill() {
        UUID foreign = UUID.randomUUID();
        UUID own = UUID.randomUUID();
        when(drillRepository.findAllById(anyIterable())).thenReturn(List.of(
            privateDrill(foreign, "Theirs", UUID.randomUUID()), privateDrill(own, "Mine", COACH_ID)));

        assertThat(resolver.snapshot(List.of(foreign, own), COACH_ID))
            .extracting(m -> m.get("name")).containsExactly("Mine");
    }
}
