package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.security.contract.AgePolicy;
import com.softropic.skillars.platform.security.contract.AgeTier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Period;

@Service
@RequiredArgsConstructor
public class AgePolicyService {

    private static final AgePolicy DEFAULTS = AgePolicy.defaults();

    private final ConfigService configService;

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
}
