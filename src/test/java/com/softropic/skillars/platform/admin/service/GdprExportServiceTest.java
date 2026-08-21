package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.SkillarsRole;
import com.softropic.skillars.platform.security.repo.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Story deferred-52 AC3: buildBookings() must deduplicate by booking id, not by default Java object
 * reference identity — Booking has no equals()/hashCode() override, so distinct persistence-context
 * reads of the same row are never equal under the old .stream().distinct() logic.
 */
@ExtendWith(MockitoExtension.class)
class GdprExportServiceTest {

    @Mock BookingRepository bookingRepository;
    @Mock CoachProfileRepository coachProfileRepository;

    @InjectMocks
    GdprExportService service;

    @Test
    void buildBookings_selfRegisteredPlayer_sameBookingFromParentAndPlayerQuery_dedupedToOne() {
        Long userId = 42L;
        UUID bookingId = UUID.randomUUID();

        Booking fromParentQuery = new Booking();
        fromParentQuery.setId(bookingId);
        Booking fromPlayerQuery = new Booking();
        fromPlayerQuery.setId(bookingId);

        when(bookingRepository.findAllByParentIdOrderByRequestedStartTimeAsc(userId))
            .thenReturn(List.of(fromParentQuery));
        when(bookingRepository.findAllByPlayerId(userId))
            .thenReturn(List.of(fromPlayerQuery));
        lenient().when(coachProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());

        User user = new User();
        user.setSkillarsRole(SkillarsRole.PLAYER);

        List<Booking> result = service.buildBookings(user, userId);

        assertThat(result).extracting(Booking::getId).containsExactly(bookingId);
    }

    @Test
    void buildBookings_noOverlap_allBookingsPreserved() {
        Long userId = 42L;
        UUID parentBookingId = UUID.randomUUID();
        UUID playerBookingId = UUID.randomUUID();
        UUID coachBookingId = UUID.randomUUID();

        Booking parentBooking = new Booking();
        parentBooking.setId(parentBookingId);
        Booking playerBooking = new Booking();
        playerBooking.setId(playerBookingId);
        Booking coachBooking = new Booking();
        coachBooking.setId(coachBookingId);

        when(bookingRepository.findAllByParentIdOrderByRequestedStartTimeAsc(userId))
            .thenReturn(List.of(parentBooking));
        when(bookingRepository.findAllByPlayerId(userId))
            .thenReturn(List.of(playerBooking));

        CoachProfile coachProfile = new CoachProfile();
        UUID coachProfileId = UUID.randomUUID();
        coachProfile.setId(coachProfileId);
        when(coachProfileRepository.findByUserId(userId)).thenReturn(Optional.of(coachProfile));
        when(bookingRepository.findAllByCoachId(coachProfileId)).thenReturn(List.of(coachBooking));

        User user = new User();
        user.setSkillarsRole(SkillarsRole.PLAYER);

        List<Booking> result = service.buildBookings(user, userId);

        assertThat(result).extracting(Booking::getId)
            .containsExactlyInAnyOrder(parentBookingId, playerBookingId, coachBookingId);
    }

    @Test
    void buildBookings_coachProfileBookingsSameIdAsParentBookings_dedupedToOne() {
        Long userId = 42L;
        UUID bookingId = UUID.randomUUID();

        Booking fromParentQuery = new Booking();
        fromParentQuery.setId(bookingId);
        Booking fromCoachQuery = new Booking();
        fromCoachQuery.setId(bookingId);

        when(bookingRepository.findAllByParentIdOrderByRequestedStartTimeAsc(userId))
            .thenReturn(List.of(fromParentQuery));

        CoachProfile coachProfile = new CoachProfile();
        UUID coachProfileId = UUID.randomUUID();
        coachProfile.setId(coachProfileId);
        when(coachProfileRepository.findByUserId(userId)).thenReturn(Optional.of(coachProfile));
        when(bookingRepository.findAllByCoachId(coachProfileId)).thenReturn(List.of(fromCoachQuery));

        User user = new User();
        user.setSkillarsRole(SkillarsRole.PARENT);

        List<Booking> result = service.buildBookings(user, userId);

        assertThat(result).extracting(Booking::getId).containsExactly(bookingId);
    }
}
