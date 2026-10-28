package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.contract.AdminNotificationRecipient;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import com.softropic.skillars.platform.security.service.AgePolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the recipient of an admin-outcome notification from the id the admin action holds. Runs
 * inside the admin action's own transaction (plain primary-key reads), so the address on the event is
 * the one committed alongside the action.
 *
 * <p>ID spaces: a coach is addressed by its {@code CoachProfile} UUID and resolved through
 * {@code CoachProfile.userId}; every other recipient is a {@code main."user"} id. A user who is a
 * <em>minor</em> player with a linked parent ({@code PlayerProfile.parentId}) is never emailed directly —
 * the parent is, matching every other user-facing email in the platform. A missing date of birth with a
 * linked parent is treated as a minor (the safe default).
 */
@Component
@RequiredArgsConstructor
public class AdminNotificationRecipientResolver {

    private static final String DEFAULT_LANG_KEY = "en";

    private final UserRepository userRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final AgePolicyService agePolicyService;

    /** The coach behind a {@code CoachProfile} id. */
    public Optional<AdminNotificationRecipient> forCoach(final UUID coachProfileId) {
        if (coachProfileId == null) {
            return Optional.empty();
        }
        return coachProfileRepository.findById(coachProfileId)
            .map(CoachProfile::getUserId)
            .flatMap(this::contactOf);
    }

    /** A {@code main."user"} id; redirected to the linked parent when the user is a minor player. */
    public Optional<AdminNotificationRecipient> forUser(final Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        final Long target = playerProfileRepository.findByUserId(userId)
            .filter(this::isManagedMinor)
            .map(PlayerProfile::getParentId)
            .orElse(userId);
        return contactOf(target);
    }

    /**
     * The account holder themselves, with no minor-to-parent redirect. Used only where the notice is
     * about something the holder personally requested and can only act on from their own login (a GDPR
     * export sits on the requester's own profile page; a child export's requester is already the parent).
     */
    public Optional<AdminNotificationRecipient> forAccountHolder(final Long userId) {
        return contactOf(userId);
    }

    private boolean isManagedMinor(final PlayerProfile profile) {
        return profile.getParentId() != null
            && (profile.getDateOfBirth() == null || agePolicyService.isMinor(profile.getDateOfBirth()));
    }

    private Optional<AdminNotificationRecipient> contactOf(final Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return userRepository.findById(userId)
            .filter(u -> u.getEmail() != null && !u.getEmail().isBlank())
            .map(u -> new AdminNotificationRecipient(u.getEmail(),
                u.getLangKey() == null || u.getLangKey().isBlank() ? DEFAULT_LANG_KEY : u.getLangKey()));
    }
}
