package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Maps a video {@code ownerId} to the {@code main."user"} account behind it.
 *
 * <p>{@code video.ownerId} is never a user id, login or email: it is the coach-profile UUID for coach
 * uploads and the PlayerProfile id (a Long TSID string) for player uploads (Story 6.6). Anything that
 * needs to email the account must go through here (CSAM suspension resolves the owner itself, in AccountSuspensionEventListener) instead of treating
 * {@code ownerId} as a login/email (which silently matched nobody).
 *
 * <p>Deliberately not {@code @Transactional}: callers run it both inside a business transaction
 * (approval decisions — plain repository reads join it) and with none (the async moderation
 * pipeline — each read gets its own).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoOwnerAccountResolver {

    /** The account's id plus where/how to email it (email may be null for a username-only login). */
    public record OwnerAccount(Long userId, String email, String langKey) {
        public boolean hasEmail() {
            return email != null && !email.isBlank();
        }
    }

    private final CoachProfileRepository coachProfileRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final UserRepository userRepository;

    /** The user id that owns the video space {@code ownerId}, if it still resolves to one (a login-less managed profile resolves to nothing). */
    public Optional<Long> resolveOwnerUserId(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return Optional.empty();
        }
        Optional<UUID> coachId = parseUuid(ownerId);
        if (coachId.isPresent()) {
            return coachProfileRepository.findById(coachId.get()).map(CoachProfile::getUserId);
        }
        try {
            return playerProfileRepository.findById(Long.parseLong(ownerId)).map(PlayerProfile::getUserId);
        } catch (NumberFormatException e) {
            log.warn("[OWNER_ID_FORMAT_UNEXPECTED value={}] ownerId is neither a UUID nor a Long", ownerId);
            return Optional.empty();
        }
    }

    /** The account owning the video space {@code ownerId}. */
    public Optional<OwnerAccount> resolveOwnerAccount(String ownerId) {
        return resolveOwnerUserId(ownerId).flatMap(this::accountForUser);
    }

    /**
     * Who to email about a notice concerning the video space {@code ownerId}: the owner's own account,
     * when it has an email address. Only profiles with a login can upload (VideoResource resolves the
     * uploader through PlayerProfile.userId), so there is no parent fallback here — a parent reached
     * this way would get player-facing copy and a player-only deep link.
     */
    public Optional<OwnerAccount> resolveNotificationRecipient(String ownerId) {
        return resolveOwnerAccount(ownerId).filter(OwnerAccount::hasEmail);
    }

    /** The account for a PlayerProfile id (its own login, not its parent's). */
    public Optional<OwnerAccount> accountForPlayer(Long playerProfileId) {
        if (playerProfileId == null) {
            return Optional.empty();
        }
        return playerProfileRepository.findById(playerProfileId)
            .map(PlayerProfile::getUserId)
            .flatMap(this::accountForUser);
    }

    public Optional<OwnerAccount> accountForUser(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return userRepository.findById(userId).map(VideoOwnerAccountResolver::toAccount);
    }

    private static OwnerAccount toAccount(User user) {
        return new OwnerAccount(user.getId(), user.getEmail(), user.getLangKey());
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
