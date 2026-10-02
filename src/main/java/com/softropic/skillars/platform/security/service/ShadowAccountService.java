package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import com.softropic.skillars.platform.security.contract.AgeTier;
import com.softropic.skillars.platform.security.contract.CreatePlayerProfileRequest;
import com.softropic.skillars.platform.security.contract.CreateSelfPlayerProfileRequest;
import com.softropic.skillars.platform.security.contract.PlayerPosition;
import com.softropic.skillars.platform.security.contract.PlayerProfileResponse;
import com.softropic.skillars.platform.security.contract.exception.PlayerProfileNotFoundException;
import com.softropic.skillars.platform.security.contract.exception.ShadowAccountException;
import com.softropic.skillars.platform.security.contract.exception.UserNotFoundException;
import com.softropic.skillars.platform.security.repo.ParentPlayerLink;
import com.softropic.skillars.platform.security.repo.ParentPlayerLinkRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Transactional
@RequiredArgsConstructor
public class ShadowAccountService {

    private final PlayerProfileRepository playerProfileRepository;
    private final ParentPlayerLinkRepository parentPlayerLinkRepository;
    private final AgePolicyService agePolicyService;
    private final PlayerProfileMapper playerProfileMapper;
    private final ContactDetailSanitizer sanitizer;
    private final UserRepository userRepository;
    private final EntityManager entityManager;
    private final PessimisticLockRetryer lockRetryer;

    public PlayerProfileResponse createPlayerProfile(Long parentId, CreatePlayerProfileRequest req) {
        AgeTier ageTier = agePolicyService.getAgeTier(req.dateOfBirth());
        boolean isMinor = agePolicyService.isMinor(ageTier);
        boolean independentAccountAllowed = agePolicyService.isIndependentAccountAllowed(ageTier);

        if (isMinor && !Boolean.TRUE.equals(req.parentConsent())) {
            throw new ShadowAccountException("security.parentConsentRequired", "Parental consent required for minor");
        }
        if (isMinor && req.consentPolicyVersion() == null) {
            throw new ShadowAccountException("security.consentPolicyVersionRequired", "Consent policy version required for minor");
        }

        PlayerProfile profile = new PlayerProfile();
        profile.setName(sanitizer.sanitize(req.name()).sanitized());
        profile.setDateOfBirth(req.dateOfBirth());
        profile.setPosition(req.position());
        profile.setAgeTier(ageTier);
        profile.setParentId(parentId);
        profile.setIndependentAccountAllowed(independentAccountAllowed);

        if (isMinor) {
            profile.setConsentAcceptedAt(Instant.now());
            profile.setConsentPolicyVersion(req.consentPolicyVersion());
        }

        playerProfileRepository.save(profile);

        ParentPlayerLink link = new ParentPlayerLink();
        link.setParentId(parentId);
        link.setPlayerId(profile.getId());
        link.setConsentAcceptedAt(Instant.now());
        link.setConsentPolicyVersion(req.consentPolicyVersion() != null ? req.consentPolicyVersion() : "1.0");
        try {
            parentPlayerLinkRepository.save(link);
        } catch (DataIntegrityViolationException ex) {
            throw new ShadowAccountException("security.playerAlreadyHasParent", "Player already has a parent");
        }

        return playerProfileMapper.toResponse(profile);
    }

    /**
     * Creates the PlayerProfile for a self-registered adult player, completing the flow started
     * by {@link PlayerRegistrationService}. Unlike {@link #createPlayerProfile}, there's no parent,
     * no consent to collect (the registrant already proved they're an adult at registration), and
     * name/date of birth come from the already-verified User rather than a fresh form submission.
     */
    public PlayerProfileResponse createSelfOwnedPlayerProfile(Long userId, CreateSelfPlayerProfileRequest req) {
        if (playerProfileRepository.existsByUserId(userId)) {
            throw new ShadowAccountException("security.playerProfileAlreadyExists", "Player profile already exists for this account");
        }

        User user = userRepository.findOneById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));

        AgeTier ageTier = agePolicyService.getAgeTier(user.getDateOfBirth());

        PlayerProfile profile = new PlayerProfile();
        profile.setName(sanitizer.sanitize(user.getFirstName() + " " + user.getLastName()).sanitized());
        profile.setDateOfBirth(user.getDateOfBirth());
        profile.setPosition(req.position());
        profile.setAgeTier(ageTier);
        profile.setUserId(userId);
        profile.setIndependentAccountAllowed(true);

        playerProfileRepository.save(profile);

        return playerProfileMapper.toResponse(profile);
    }

    @Transactional(readOnly = true)
    public List<PlayerProfileResponse> listPlayerProfiles(Long parentId) {
        return playerProfileRepository.findByParentIdOrderByIdAsc(parentId)
            .stream()
            .map(playerProfileMapper::toResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public PlayerProfileResponse getPlayerProfile(Long playerId, Long parentId) {
        PlayerProfile profile = playerProfileRepository.findByIdAndParentId(playerId, parentId)
            .orElseThrow(() -> new UserNotFoundException(playerId));
        return playerProfileMapper.toResponse(profile);
    }

    /**
     * 404s if the self-registered player hasn't completed the profile-builder step yet.
     *
     * <p>Review audit item 3: throws {@link PlayerProfileNotFoundException}, matching
     * {@link #updateOwnPosition}. This is the call "My Profile" uses to decide whether to show the
     * player's position row or the "complete your player profile" call-to-action, and the condition
     * is identical — the user account exists, only their own profile row does not. It previously
     * threw {@link UserNotFoundException}, which named the wrong entity for a user who is
     * demonstrably logged in and left the read and write paths reporting the same state differently.
     */
    @Transactional(readOnly = true)
    public PlayerProfileResponse getSelfOwnedPlayerProfile(Long userId) {
        PlayerProfile profile = playerProfileRepository.findByUserId(userId)
            .orElseThrow(() -> new PlayerProfileNotFoundException(userId));
        return playerProfileMapper.toResponse(profile);
    }

    /**
     * AC4: the missing update counterpart to {@link #createSelfOwnedPlayerProfile}, which is
     * one-time-only (rejects a second call via {@code existsByUserId}). "My Profile" needs a route
     * back to this field without going through account creation again.
     *
     * <p>skillars-deferred-139 review D3: throws {@link PlayerProfileNotFoundException} rather than
     * {@link UserNotFoundException} — the user account exists, only the profile row does not, so the
     * frontend can route to the player-profile builder instead of implying the account is missing.
     *
     * <p>skillars-deferred-139 review Patch 3: {@code PlayerProfile} has no {@code @Version}/
     * {@code @DynamicUpdate} ({@link com.softropic.skillars.platform.admin.service.GdprErasureService}
     * documents this on its own {@code entityManager.detach} call), so an unlocked read-then-save here
     * would silently re-write every column with this request's stale pre-lock snapshot — including
     * resurrecting a GDPR erasure tombstone ({@code developmentDataErasedAt}) that committed between
     * this method's read and its save. Mirrors {@code CoachProfileService.saveStep4}'s own
     * lock-then-refresh pattern for the identical reason.
     */
    public PlayerProfileResponse updateOwnPosition(Long userId, PlayerPosition position) {
        PlayerProfile profile = playerProfileRepository.findByUserId(userId)
            .orElseThrow(() -> new PlayerProfileNotFoundException(userId));

        lockRetryer.withBoundedRetry("ShadowAccountService.updateOwnPosition", () -> {
            playerProfileRepository.findByIdForUpdate(profile.getId())
                .orElseThrow(() -> new PlayerProfileNotFoundException(userId));
            entityManager.refresh(profile, LockModeType.PESSIMISTIC_WRITE);
            return null;
        });

        profile.setPosition(position);
        playerProfileRepository.save(profile);
        return playerProfileMapper.toResponse(profile);
    }

    /**
     * AC5: same {@code (playerId, parentId)} parameter shape as {@link #getPlayerProfile} and for the
     * same reason — {@code findByIdAndParentId} enforces family isolation as defense-in-depth
     * alongside the {@code @PreAuthorize} guard at the resource layer.
     *
     * <p>skillars-deferred-139 review Patch 3: locks and refreshes before mutating, for the same
     * lost-update reason documented on {@link #updateOwnPosition} — a single parent editing their own
     * child's {@code position} has no other *routine* writer to race against, but the GDPR-erasure
     * tombstone write is exactly such a writer, and an unlocked full-row save here could silently
     * revert it.
     *
     * <p>Review audit item 3 — this method deliberately keeps {@link UserNotFoundException} rather
     * than adopting {@link PlayerProfileNotFoundException} like {@link #updateOwnPosition} and
     * {@link #getSelfOwnedPlayerProfile} do. The lookup is {@code findByIdAndParentId}, so an empty
     * result conflates two different states — "no such player profile" and "that player is not
     * yours" — and must not be reported as the former. Saying "this player profile does not exist"
     * for another family's child would both leak a negative existence claim and (via the frontend's
     * handling of that key) wrongly offer this parent the create-a-player-profile builder for a
     * child that is not theirs. The resource-layer {@code @PreAuthorize("@playerOwnershipGuard...")}
     * already 403s the cross-family case; this branch is defense-in-depth behind it.
     */
    public PlayerProfileResponse updateChildPosition(Long playerId, Long parentId, PlayerPosition position) {
        PlayerProfile profile = playerProfileRepository.findByIdAndParentId(playerId, parentId)
            .orElseThrow(() -> new UserNotFoundException(playerId));

        lockRetryer.withBoundedRetry("ShadowAccountService.updateChildPosition", () -> {
            playerProfileRepository.findByIdForUpdate(profile.getId())
                .orElseThrow(() -> new UserNotFoundException(playerId));
            entityManager.refresh(profile, LockModeType.PESSIMISTIC_WRITE);
            return null;
        });

        profile.setPosition(position);
        playerProfileRepository.save(profile);
        return playerProfileMapper.toResponse(profile);
    }

    public void linkAdditionalParent(Long requestingParentId, Long playerId) {
        if (parentPlayerLinkRepository.existsByPlayerId(playerId)) {
            throw new ShadowAccountException("security.playerAlreadyHasParent", "Player already has a parent");
        }
        ParentPlayerLink link = new ParentPlayerLink();
        link.setParentId(requestingParentId);
        link.setPlayerId(playerId);
        link.setConsentAcceptedAt(Instant.now());
        link.setConsentPolicyVersion("1.0");
        try {
            parentPlayerLinkRepository.save(link);
        } catch (DataIntegrityViolationException ex) {
            throw new ShadowAccountException("security.playerAlreadyHasParent", "Player already has a parent");
        }
    }

}
