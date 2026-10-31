package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.config.service.ConfigBounds;

import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.repo.ParentPlayerLink;
import com.softropic.skillars.platform.security.repo.ParentPlayerLinkRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.exception.PlaybackDeniedException;
import com.softropic.skillars.platform.video.contract.exception.VideoDeletionNotAuthorisedException;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.repo.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.User;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoAccessGuardTest {

    @Mock VideoService videoService;
    @Mock SecurityUtil securityUtil;
    @Mock ParentPlayerLinkRepository parentPlayerLinkRepository;
    @Mock BookingRepository bookingRepository;
    @Mock CoachProfileService coachProfileService;
    @Mock ConfigService configService;
    @Mock VideoAccessCache videoAccessCache;
    @Mock PlayerProfileRepository playerProfileRepository;
    @Mock Principal mockPrincipal;
    @Mock VideoSharingPolicy videoSharingPolicy;

    @InjectMocks VideoAccessGuard guard;

    private static final UUID VIDEO_ID = UUID.randomUUID();
    private static final Long PLAYER_LONG_ID = 42L;
    private static final String PLAYER_OWNER_ID = "42";     // Long as string → player video
    // The owning player's USER id — player video ownerIds are PlayerProfile ids, never user ids.
    private static final Long PLAYER_USER_ID = 4242L;
    private static final UUID COACH_UUID = UUID.randomUUID();
    private static final String COACH_OWNER_ID = COACH_UUID.toString(); // UUID string → coach video
    private static final Long PARENT_LONG_ID = 99L;
    private static final Long COACH_USER_ID = 55L;

    @BeforeEach
    void setUp() {
        lenient().when(configService.getBoundedLong(ConfigBounds.VIDEO_ACCESS_COACH_WINDOW_DAYS))
            .thenReturn(90L);
        lenient().when(videoSharingPolicy.maySharePastOwner(any())).thenReturn(true);
    }

    // S-18 (decision B): until a real scanner has passed a video, only its owner (and an admin) may play it.
    @Test
    void canPlay_unscannedVideo_ownerStillAllowed() {
        Video video = videoWithState(OperationalState.READY, PLAYER_OWNER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.isAdmin()).thenReturn(false);
        stubPlayerCurrentUser(PLAYER_USER_ID);
        when(playerProfileRepository.existsByIdAndUserId(PLAYER_LONG_ID, PLAYER_USER_ID)).thenReturn(true);
        lenient().when(videoSharingPolicy.maySharePastOwner(video)).thenReturn(false);

        assertThat(guard.canPlay(null, VIDEO_ID)).isTrue();
    }

    @Test
    void canPlay_unscannedVideo_adminStillAllowed() {
        Video video = videoWithState(OperationalState.READY, PLAYER_OWNER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.isAdmin()).thenReturn(true);
        lenient().when(videoSharingPolicy.maySharePastOwner(video)).thenReturn(false);

        assertThat(guard.canPlay(null, VIDEO_ID)).isTrue();
    }

    @Test
    void canPlay_unscannedVideo_linkedParentDeniedBeforeAnyRelationshipCheck() {
        Video video = videoWithState(OperationalState.READY, PLAYER_OWNER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.isAdmin()).thenReturn(false);
        stubPlayerCurrentUser(PARENT_LONG_ID);
        when(playerProfileRepository.existsByIdAndUserId(PLAYER_LONG_ID, PARENT_LONG_ID)).thenReturn(false);
        when(videoSharingPolicy.maySharePastOwner(video)).thenReturn(false);
        lenient().when(securityUtil.getCurrentUserName()).thenReturn("parent@example.com");

        assertThatThrownBy(() -> guard.canPlay(null, VIDEO_ID)).isInstanceOf(PlaybackDeniedException.class);
        org.mockito.Mockito.verifyNoInteractions(parentPlayerLinkRepository, bookingRepository);
    }

    // --- canPlay --- //

    @Test
    void canPlay_purgedVideo_throwsVideoNotFoundException() {
        Video video = videoWithState(OperationalState.PURGED, PLAYER_OWNER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);

        assertThatThrownBy(() -> guard.canPlay(null, VIDEO_ID))
            .isInstanceOf(VideoNotFoundException.class);
    }

    @Test
    void canPlay_adminUser_allowsAnyVideo() {
        Video video = videoWithState(OperationalState.READY, PLAYER_OWNER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.isAdmin()).thenReturn(true);

        assertThat(guard.canPlay(null, VIDEO_ID)).isTrue();
    }

    @Test
    void canPlay_playerOwnsVideo_allowed() {
        Video video = videoWithState(OperationalState.READY, PLAYER_OWNER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.isAdmin()).thenReturn(false);
        stubPlayerCurrentUser(PLAYER_USER_ID);
        when(playerProfileRepository.existsByIdAndUserId(PLAYER_LONG_ID, PLAYER_USER_ID)).thenReturn(true);

        assertThat(guard.canPlay(null, VIDEO_ID)).isTrue();
    }

    @Test
    void canDelete_callerWhoseUserIdEqualsOwnerProfileIdButDoesNotOwnIt_denied() {
        // Regression: the old check compared the caller's user id string to the PlayerProfile id.
        Video video = videoWithState(OperationalState.READY, PLAYER_OWNER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.isAdmin()).thenReturn(false);
        stubPlayerCurrentUser(PLAYER_LONG_ID);
        when(playerProfileRepository.existsByIdAndUserId(PLAYER_LONG_ID, PLAYER_LONG_ID)).thenReturn(false);
        when(parentPlayerLinkRepository.findAllByPlayerId(PLAYER_LONG_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> guard.canDelete(null, VIDEO_ID))
            .isInstanceOf(VideoDeletionNotAuthorisedException.class);
    }

    @Test
    void canPlay_coachOwnsCoachVideo_allowed() {
        Video video = videoWithState(OperationalState.READY, COACH_OWNER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.isAdmin()).thenReturn(false);
        // Coach video (UUID ownerId): isOwner() tries the coach path first (not player path), so no getCurrentUser() stub needed
        when(securityUtil.getCurrentCoachUserId()).thenReturn(COACH_USER_ID);
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_UUID);

        assertThat(guard.canPlay(null, VIDEO_ID)).isTrue();
    }

    @Test
    void canPlay_parentOfPlayer_allowed() {
        Video video = videoWithState(OperationalState.READY, PLAYER_OWNER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.isAdmin()).thenReturn(false);
        stubPlayerCurrentUser(PARENT_LONG_ID); // PARENT, not the owner
        ParentPlayerLink link = new ParentPlayerLink();
        link.setParentId(PARENT_LONG_ID);
        link.setPlayerId(PLAYER_LONG_ID);
        when(parentPlayerLinkRepository.findAllByPlayerId(PLAYER_LONG_ID)).thenReturn(List.of(link));

        assertThat(guard.canPlay(null, VIDEO_ID)).isTrue();
    }

    @Test
    void canPlay_coachWithActiveBooking_allowed() {
        Video video = videoWithState(OperationalState.READY, PLAYER_OWNER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.isAdmin()).thenReturn(false);
        stubPlayerCurrentUser(PARENT_LONG_ID); // not owner, not parent
        when(parentPlayerLinkRepository.findAllByPlayerId(PLAYER_LONG_ID)).thenReturn(List.of());
        when(securityUtil.getCurrentCoachUserId()).thenReturn(COACH_USER_ID);
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_UUID);
        when(bookingRepository.existsRecentCompletedBooking(eq(COACH_UUID), eq(PLAYER_LONG_ID), any(Instant.class)))
            .thenReturn(true);

        assertThat(guard.canPlay(null, VIDEO_ID)).isTrue();

        // skillars-deferred-107 AC2: the coach access window is read through the range-bounded
        // accessor — a stored 0/negative would otherwise deny every coach with a recent booking.
        // Revert VideoAccessGuard to getLong(key, 90L) and this verify fails.
        verify(configService).getBoundedLong(ConfigBounds.VIDEO_ACCESS_COACH_WINDOW_DAYS);
    }

    @Test
    void canPlay_unrelatedUser_throwsPlaybackDeniedException() {
        Video video = videoWithState(OperationalState.READY, PLAYER_OWNER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.isAdmin()).thenReturn(false);
        stubPlayerCurrentUser(999L); // unrelated user
        when(parentPlayerLinkRepository.findAllByPlayerId(PLAYER_LONG_ID)).thenReturn(List.of());
        when(securityUtil.getCurrentCoachUserId()).thenThrow(new RuntimeException("not a coach"));
        when(securityUtil.getCurrentUserName()).thenReturn("nobody@example.com");

        assertThatThrownBy(() -> guard.canPlay(null, VIDEO_ID))
            .isInstanceOf(PlaybackDeniedException.class);
    }

    // --- canDelete --- //

    @Test
    void canDelete_purgedVideo_throwsVideoNotFoundException() {
        Video video = videoWithState(OperationalState.PURGED, PLAYER_OWNER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);

        assertThatThrownBy(() -> guard.canDelete(null, VIDEO_ID))
            .isInstanceOf(VideoNotFoundException.class);
    }

    @Test
    void canDelete_adminUser_allowed() {
        Video video = videoWithState(OperationalState.READY, PLAYER_OWNER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.isAdmin()).thenReturn(true);

        assertThat(guard.canDelete(null, VIDEO_ID)).isTrue();
    }

    @Test
    void canDelete_playerOwner_allowed() {
        Video video = videoWithState(OperationalState.READY, PLAYER_OWNER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.isAdmin()).thenReturn(false);
        stubPlayerCurrentUser(PLAYER_USER_ID);
        when(playerProfileRepository.existsByIdAndUserId(PLAYER_LONG_ID, PLAYER_USER_ID)).thenReturn(true);

        assertThat(guard.canDelete(null, VIDEO_ID)).isTrue();
    }

    @Test
    void canDelete_coachOwner_allowed() {
        Video video = videoWithState(OperationalState.READY, COACH_OWNER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.isAdmin()).thenReturn(false);
        // Coach video: isOwner() takes the non-player path directly
        when(securityUtil.getCurrentCoachUserId()).thenReturn(COACH_USER_ID);
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_UUID);

        assertThat(guard.canDelete(null, VIDEO_ID)).isTrue();
    }

    @Test
    void canDelete_parentOfPlayer_allowed() {
        Video video = videoWithState(OperationalState.READY, PLAYER_OWNER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.isAdmin()).thenReturn(false);
        stubPlayerCurrentUser(PARENT_LONG_ID);
        ParentPlayerLink link = new ParentPlayerLink();
        link.setParentId(PARENT_LONG_ID);
        link.setPlayerId(PLAYER_LONG_ID);
        when(parentPlayerLinkRepository.findAllByPlayerId(PLAYER_LONG_ID)).thenReturn(List.of(link));

        assertThat(guard.canDelete(null, VIDEO_ID)).isTrue();
    }

    @Test
    void canDelete_coachTriesToDeletePlayerVideo_throwsDeletionNotAuthorised() {
        // Coaches can play player videos but NOT delete them
        Video video = videoWithState(OperationalState.READY, PLAYER_OWNER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.isAdmin()).thenReturn(false);
        stubPlayerCurrentUser(PARENT_LONG_ID); // not owner, not parent
        when(parentPlayerLinkRepository.findAllByPlayerId(PLAYER_LONG_ID)).thenReturn(List.of());
        when(securityUtil.getCurrentUserName()).thenReturn("coach@example.com");

        assertThatThrownBy(() -> guard.canDelete(null, VIDEO_ID))
            .isInstanceOf(VideoDeletionNotAuthorisedException.class);
    }

    // --- V184: subject of a coach video (the child it shows) --- //

    private static final Long SUBJECT_PLAYER_ID = 4_200L;

    @Test
    void canPlay_parentOfCoachVideoSubject_allowed_butCannotDelete() {
        Video video = coachVideoAbout(SUBJECT_PLAYER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.isAdmin()).thenReturn(false);
        stubPlayerCurrentUser(PARENT_LONG_ID);
        ParentPlayerLink link = new ParentPlayerLink();
        link.setParentId(PARENT_LONG_ID);
        link.setPlayerId(SUBJECT_PLAYER_ID);
        when(parentPlayerLinkRepository.findAllByPlayerId(SUBJECT_PLAYER_ID)).thenReturn(List.of(link));

        assertThat(guard.canPlay(null, VIDEO_ID)).isTrue();

        // Play, never delete: the coach owns the video.
        when(securityUtil.getCurrentUserName()).thenReturn("parent@example.com");
        assertThatThrownBy(() -> guard.canDelete(null, VIDEO_ID))
            .isInstanceOf(VideoDeletionNotAuthorisedException.class);
    }

    @Test
    void canPlay_selfRegisteredSubjectPlayer_allowed() {
        Video video = coachVideoAbout(SUBJECT_PLAYER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.isAdmin()).thenReturn(false);
        stubPlayerCurrentUser(PLAYER_USER_ID);
        when(parentPlayerLinkRepository.findAllByPlayerId(SUBJECT_PLAYER_ID)).thenReturn(List.of());
        when(playerProfileRepository.existsByIdAndUserId(SUBJECT_PLAYER_ID, PLAYER_USER_ID)).thenReturn(true);

        assertThat(guard.canPlay(null, VIDEO_ID)).isTrue();
    }

    @Test
    void canPlay_coachVideoSubject_unrelatedParent_denied() {
        Video video = coachVideoAbout(SUBJECT_PLAYER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.isAdmin()).thenReturn(false);
        stubPlayerCurrentUser(999L);
        when(parentPlayerLinkRepository.findAllByPlayerId(SUBJECT_PLAYER_ID)).thenReturn(List.of());
        when(playerProfileRepository.existsByIdAndUserId(SUBJECT_PLAYER_ID, 999L)).thenReturn(false);
        when(securityUtil.getCurrentUserName()).thenReturn("nobody@example.com");

        assertThatThrownBy(() -> guard.canPlay(null, VIDEO_ID))
            .isInstanceOf(PlaybackDeniedException.class);
    }

    @Test
    void canPlay_coachVideoWithoutSubject_parentDenied() {
        // Existing (pre-V184) coach videos stay unlinked: no parent can play them.
        Video video = videoWithState(OperationalState.READY, COACH_OWNER_ID);
        when(videoService.findById(VIDEO_ID)).thenReturn(video);
        when(securityUtil.isAdmin()).thenReturn(false);
        when(securityUtil.getCurrentUserName()).thenReturn("parent@example.com");

        assertThatThrownBy(() -> guard.canPlay(null, VIDEO_ID))
            .isInstanceOf(PlaybackDeniedException.class);
    }

    private Video coachVideoAbout(Long subjectPlayerId) {
        Video v = videoWithState(OperationalState.READY, COACH_OWNER_ID);
        v.setSubjectBookingId(UUID.randomUUID());
        v.setSubjectPlayerId(subjectPlayerId);
        return v;
    }

    // --- helpers --- //

    private Video videoWithState(OperationalState state, String ownerId) {
        Video v = new Video();
        v.setOperationalState(state);
        v.setOwnerId(ownerId);
        return v;
    }

    private void stubPlayerCurrentUser(Long businessId) {
        when(mockPrincipal.getBusinessId()).thenReturn(String.valueOf(businessId));
        when(securityUtil.getCurrentUser()).thenReturn(mockPrincipal);
    }
}
