package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import com.softropic.skillars.platform.security.contract.AgeTier;
import com.softropic.skillars.platform.security.contract.CreatePlayerProfileRequest;
import com.softropic.skillars.platform.security.contract.PlayerProfileResponse;
import com.softropic.skillars.platform.security.contract.exception.ShadowAccountException;
import com.softropic.skillars.platform.security.contract.exception.UserNotFoundException;
import com.softropic.skillars.platform.security.repo.ParentPlayerLink;
import com.softropic.skillars.platform.security.repo.ParentPlayerLinkRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
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

    @Transactional(readOnly = true)
    public List<PlayerProfileResponse> listPlayerProfiles(Long parentId) {
        return playerProfileRepository.findByParentId(parentId)
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
