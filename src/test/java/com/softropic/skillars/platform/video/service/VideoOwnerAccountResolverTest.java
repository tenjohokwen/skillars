package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** B5: video ownerIds (coach-profile UUID / PlayerProfile id) map to the account behind them. */
class VideoOwnerAccountResolverTest {

    private CoachProfileRepository coachProfileRepository;
    private PlayerProfileRepository playerProfileRepository;
    private UserRepository userRepository;
    private VideoOwnerAccountResolver resolver;

    @BeforeEach
    void setUp() {
        coachProfileRepository = mock(CoachProfileRepository.class);
        playerProfileRepository = mock(PlayerProfileRepository.class);
        userRepository = mock(UserRepository.class);
        resolver = new VideoOwnerAccountResolver(coachProfileRepository, playerProfileRepository, userRepository);
    }

    private User user(long id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setLangKey("de");
        when(userRepository.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    private PlayerProfile player(long id, Long userId, Long parentId) {
        PlayerProfile p = new PlayerProfile();
        p.setId(id);
        p.setUserId(userId);
        p.setParentId(parentId);
        when(playerProfileRepository.findById(id)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void coachVideo_resolvesThroughCoachProfileUserId() {
        UUID coachId = UUID.randomUUID();
        CoachProfile coach = new CoachProfile();
        coach.setUserId(7L);
        when(coachProfileRepository.findById(coachId)).thenReturn(Optional.of(coach));
        user(7L, "coach@example.com");

        assertThat(resolver.resolveOwnerUserId(coachId.toString())).contains(7L);
        assertThat(resolver.resolveNotificationRecipient(coachId.toString()))
            .map(VideoOwnerAccountResolver.OwnerAccount::email).contains("coach@example.com");
    }

    @Test
    void playerVideo_resolvesThroughPlayerProfileUserId_notAsAUserId() {
        player(555L, 9L, 20L);
        user(9L, "kid@example.com");

        assertThat(resolver.resolveOwnerUserId("555")).contains(9L);
        assertThat(resolver.resolveNotificationRecipient("555"))
            .map(VideoOwnerAccountResolver.OwnerAccount::email).contains("kid@example.com");
    }

    @Test
    void ownerAccountWithoutEmail_hasNoNotificationRecipient() {
        player(556L, 10L, null);
        user(10L, null);

        assertThat(resolver.resolveOwnerUserId("556")).contains(10L);
        assertThat(resolver.resolveNotificationRecipient("556")).isEmpty();
    }

    @Test
    void unknownOrMalformedOwner_resolvesToNothing() {
        when(playerProfileRepository.findById(1L)).thenReturn(Optional.empty());

        assertThat(resolver.resolveOwnerUserId("1")).isEmpty();
        assertThat(resolver.resolveOwnerUserId("owner@example.com")).isEmpty();
        assertThat(resolver.resolveOwnerUserId(null)).isEmpty();
        assertThat(resolver.resolveNotificationRecipient("owner@example.com")).isEmpty();
    }
}
