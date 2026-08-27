package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.contract.CoachContributionDto;
import com.softropic.skillars.platform.development.repo.SluRepository;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SluContributionService {

    private final SluRepository sluRepository;
    private final CoachProfileService coachProfileService;

    /**
     * Returns per-skill, per-coach SLU contribution percentages for the given time window.
     * Only includes skills where at least one coach has contributed SLU.
     */
    public List<CoachContributionDto> getCoachContributions(Long playerId, Instant since) {
        List<Object[]> rows = sluRepository.findCoachContributionsByPlayerId(playerId, since);

        if (rows.isEmpty()) {
            return List.of();
        }

        // Convert coach_id once per distinct raw value — guards both the batch-lookup pass below and
        // the DTO-building pass, instead of re-validating/re-parsing the same UUID twice per row.
        Map<Object, UUID> coachIdByRawValue = new HashMap<>();
        for (Object[] row : rows) {
            Object raw = row[0];
            if (raw == null || coachIdByRawValue.containsKey(raw)) continue;
            if (!(raw instanceof UUID uuid)) {
                throw new BadSqlGrammarException(
                    "coach_id from query is not a valid UUID", "findCoachContributionsByPlayerId", null);
            }
            coachIdByRawValue.put(raw, uuid);
        }

        // Batch coach name lookup — avoids N+1
        Set<UUID> coachIds = Set.copyOf(coachIdByRawValue.values());
        Map<UUID, String> displayNames;
        try {
            displayNames = coachProfileService.getDisplayNamesByIds(coachIds);
        } catch (RuntimeException e) {
            log.error("Failed to fetch coach display names for contribution calculation", e);
            displayNames = new HashMap<>();
        }

        // Group by skill_code, compute total SLU per skill, then % per coach
        Map<String, BigDecimal> skillTotals = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null || row[1] == null || row[2] == null) continue;
            String skillCode = (String) row[1];
            BigDecimal contribution = row[2] instanceof BigDecimal bd
                ? bd : BigDecimal.valueOf(((Number) row[2]).doubleValue());
            skillTotals.merge(skillCode, contribution, BigDecimal::add);
        }

        List<CoachContributionDto> result = new ArrayList<>();
        for (Object[] row : rows) {
            if (row[0] == null || row[1] == null || row[2] == null) continue;
            UUID coachId = coachIdByRawValue.get(row[0]);
            String skillCode = (String) row[1];
            BigDecimal coachSlu = row[2] instanceof BigDecimal bd
                ? bd : BigDecimal.valueOf(((Number) row[2]).doubleValue());
            BigDecimal total = skillTotals.get(skillCode);
            BigDecimal pct = total.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : coachSlu.multiply(BigDecimal.valueOf(100))
                           .divide(total, 1, RoundingMode.HALF_UP);
            String name = displayNames.getOrDefault(coachId, "Unknown");
            result.add(new CoachContributionDto(name, skillCode, pct));
        }
        return result;
    }
}
