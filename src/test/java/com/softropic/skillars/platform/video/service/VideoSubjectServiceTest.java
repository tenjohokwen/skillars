package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.booking.contract.BookingSnapshot;
import com.softropic.skillars.platform.booking.contract.PlannableBookingSummary;
import com.softropic.skillars.platform.booking.service.BookingQueryService;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.video.contract.VideoType;
import com.softropic.skillars.platform.video.contract.exception.VideoSubjectInvalidException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** V184: the subject (booking → child) of a coach video is validated server-side. */
@ExtendWith(MockitoExtension.class)
class VideoSubjectServiceTest {

    @Mock BookingQueryService bookingQueryService;
    @Mock PlayerProfileRepository playerProfileRepository;

    private VideoSubjectService service;

    private static final UUID COACH = UUID.randomUUID();
    private static final UUID BOOKING = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new VideoSubjectService(bookingQueryService, playerProfileRepository);
    }

    private static PlayerProfile profile(long id, boolean erased) {
        PlayerProfile p = new PlayerProfile();
        p.setId(id);
        if (erased) p.setDevelopmentDataErasedAt(Instant.now());
        return p;
    }

    @Test
    void ownCompletedBooking_returnsTheBookingsPlayer() {
        when(bookingQueryService.getBookingSnapshot(BOOKING))
            .thenReturn(Optional.of(new BookingSnapshot(BOOKING, COACH, 77L, "COMPLETED")));
        when(playerProfileRepository.findById(77L)).thenReturn(Optional.of(profile(77L, false)));

        assertThat(service.resolveSubjectPlayer(COACH, VideoType.COACH_REVIEW, BOOKING)).isEqualTo(77L);
    }

    @Test
    void disputedSession_isStillAHeldSession() {
        when(bookingQueryService.getBookingSnapshot(BOOKING))
            .thenReturn(Optional.of(new BookingSnapshot(BOOKING, COACH, 77L, "DISPUTED")));
        when(playerProfileRepository.findById(77L)).thenReturn(Optional.of(profile(77L, false)));

        assertThat(service.resolveSubjectPlayer(COACH, VideoType.COACH_REVIEW, BOOKING)).isEqualTo(77L);
    }

    @Test
    void erasedChild_isRefusedBeforeAnyUploadWork() {
        when(bookingQueryService.getBookingSnapshot(BOOKING))
            .thenReturn(Optional.of(new BookingSnapshot(BOOKING, COACH, 77L, "COMPLETED")));
        when(playerProfileRepository.findById(77L)).thenReturn(Optional.of(profile(77L, true)));

        assertThatThrownBy(() -> service.resolveSubjectPlayer(COACH, VideoType.COACH_REVIEW, BOOKING))
            .isInstanceOf(VideoSubjectInvalidException.class);
    }

    @Test
    void coachReviewWithoutBooking_isRefused() {
        assertThatThrownBy(() -> service.resolveSubjectPlayer(COACH, VideoType.COACH_REVIEW, null))
            .isInstanceOf(VideoSubjectInvalidException.class);
        verifyNoInteractions(bookingQueryService);
    }

    @Test
    void drillDemoWithoutBooking_hasNoSubject() {
        assertThat(service.resolveSubjectPlayer(COACH, VideoType.DRILL_DEMO, null)).isNull();
    }

    @Test
    void anotherCoachsBooking_isRefusedLikeAnUnknownOne() {
        when(bookingQueryService.getBookingSnapshot(BOOKING))
            .thenReturn(Optional.of(new BookingSnapshot(BOOKING, UUID.randomUUID(), 77L, "COMPLETED")));

        assertThatThrownBy(() -> service.resolveSubjectPlayer(COACH, VideoType.COACH_REVIEW, BOOKING))
            .isInstanceOf(VideoSubjectInvalidException.class)
            .hasMessageContaining("not valid");
    }

    @Test
    void unknownBooking_isRefused() {
        when(bookingQueryService.getBookingSnapshot(BOOKING)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveSubjectPlayer(COACH, VideoType.DRILL_DEMO, BOOKING))
            .isInstanceOf(VideoSubjectInvalidException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "ACCEPTED", "CONFIRMED", "DECLINED", "CANCELLED_PARENT", "NO_SHOW_PLAYER",
        "REFUND_PENDING", "REFUNDED"})
    void bookingThatNeverBecameAHeldSession_isRefused(String status) {
        when(bookingQueryService.getBookingSnapshot(BOOKING))
            .thenReturn(Optional.of(new BookingSnapshot(BOOKING, COACH, 77L, status)));

        assertThatThrownBy(() -> service.resolveSubjectPlayer(COACH, VideoType.COACH_REVIEW, BOOKING))
            .isInstanceOf(VideoSubjectInvalidException.class);
    }

    @Test
    void homeworkWithBooking_isRefused() {
        assertThatThrownBy(() -> service.resolveSubjectPlayer(COACH, VideoType.HOMEWORK, BOOKING))
            .isInstanceOf(VideoSubjectInvalidException.class);
    }

    @Test
    void picker_listsHeldSessionsMostRecentFirst() {
        Instant now = Instant.now();
        PlannableBookingSummary older = new PlannableBookingSummary(UUID.randomUUID(), 1L, "A",
            now.minusSeconds(86_400 * 3), now, "UTC");
        PlannableBookingSummary newer = new PlannableBookingSummary(UUID.randomUUID(), 2L, "B",
            now.minusSeconds(86_400), now, "UTC");
        when(bookingQueryService.findCoachBookings(eq(COACH), eq(VideoSubjectService.SUBJECT_ELIGIBLE_STATUSES),
            any(), any())).thenReturn(List.of(older, newer));
        when(playerProfileRepository.findAllById(any())).thenReturn(List.of(profile(1L, false), profile(2L, false)));

        assertThat(service.listSubjectBookings(COACH)).containsExactly(newer, older);
    }

    @Test
    void picker_hidesSessionsOfErasedChildren() {
        Instant now = Instant.now();
        PlannableBookingSummary erased = new PlannableBookingSummary(UUID.randomUUID(), 1L, "Erased player",
            now.minusSeconds(86_400), now, "UTC");
        PlannableBookingSummary live = new PlannableBookingSummary(UUID.randomUUID(), 2L, "B",
            now.minusSeconds(86_400 * 2), now, "UTC");
        when(bookingQueryService.findCoachBookings(eq(COACH), any(), any(), any())).thenReturn(List.of(erased, live));
        when(playerProfileRepository.findAllById(any())).thenReturn(List.of(profile(1L, true), profile(2L, false)));

        assertThat(service.listSubjectBookings(COACH)).containsExactly(live);
    }
}
