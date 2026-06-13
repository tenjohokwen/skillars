package com.softropic.skillars.platform.marketplace.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CoachCapabilityService {

    static final String BADGE_WINDOW_DAYS_KEY = "marketplace.capability.badge.activity.window.days";
    static final long BADGE_WINDOW_DAYS_DEFAULT = 90L;

    private final CoachSubscriptionRepository coachSubscriptionRepository;
    private final ConfigService configService;

    // Returns active capability badge names for a single coach.
    // All activity-based badges return empty at this stage (Epic 2.3 scaffold).
    // Wire each badge when its source module ships (Epic 4+). The activity window
    // is configurable via ConfigService key: marketplace.capability.badge.activity.window.days
    @Transactional(readOnly = true)
    public List<String> getActiveBadges(UUID coachId) {
        return List.of();
    }

    // Batch variant for search result pages — avoids N+1 per card.
    @Transactional(readOnly = true)
    public Map<UUID, List<String>> getActiveBadgesBatch(List<UUID> coachIds) {
        return coachIds.stream().collect(Collectors.toMap(id -> id, id -> List.of()));
    }

    long getBadgeWindowDays() {
        return configService.find(BADGE_WINDOW_DAYS_KEY)
            .map(Long::parseLong)
            .orElse(BADGE_WINDOW_DAYS_DEFAULT);
    }
}
