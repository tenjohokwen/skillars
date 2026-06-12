package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.contract.AgePolicy;
import com.softropic.skillars.platform.security.contract.AgeTier;
import com.softropic.skillars.platform.security.contract.MessagingPolicy;
import com.softropic.skillars.platform.security.contract.exception.UserNotFoundException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Period;

@Service
@RequiredArgsConstructor
public class AgePolicyService {

    private static final AgePolicy DEFAULTS = AgePolicy.defaults();

    private final ConfigService configService;
    private final PlayerProfileRepository playerProfileRepository;

    public AgeTier getAgeTier(LocalDate dateOfBirth) {
        int age = Period.between(dateOfBirth, LocalDate.now()).getYears();
        int u10Max = configService.find("security.age-policy.u10-max-age")
            .map(Integer::parseInt).orElse(DEFAULTS.u10MaxAge());
        int youngTeenMax = configService.find("security.age-policy.young-teen-max-age")
            .map(Integer::parseInt).orElse(DEFAULTS.youngTeenMaxAge());
        int teenMax = configService.find("security.age-policy.teen-max-age")
            .map(Integer::parseInt).orElse(DEFAULTS.teenMaxAge());
        if (age <= u10Max) return AgeTier.U10;
        if (age <= youngTeenMax) return AgeTier.AGE_10_12;
        if (age <= teenMax) return AgeTier.AGE_13_17;
        return AgeTier.ADULT;
    }

    /** ADULT tier is the 18+ bracket by default config; derived so callers share the same clock tick as getAgeTier(). */
    public boolean isMinor(AgeTier ageTier) {
        return ageTier != AgeTier.ADULT;
    }

    public boolean isMinor(LocalDate dateOfBirth) {
        return isMinor(getAgeTier(dateOfBirth));
    }

    public boolean isIndependentAccountAllowed(AgeTier ageTier) {
        return ageTier != AgeTier.U10;
    }

    public MessagingPolicy getMessagingPolicy(Long playerId) {
        PlayerProfile player = playerProfileRepository.findById(playerId)
            .orElseThrow(() -> new UserNotFoundException(playerId));
        AgeTier tier = getAgeTier(player.getDateOfBirth());
        return switch (tier) {
            case U10       -> MessagingPolicy.prohibited();
            case AGE_10_12 -> MessagingPolicy.parentManaged();
            case AGE_13_17 -> MessagingPolicy.supervised();
            case ADULT     -> MessagingPolicy.unrestricted();
        };
    }
}
