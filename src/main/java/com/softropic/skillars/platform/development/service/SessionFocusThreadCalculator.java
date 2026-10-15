package com.softropic.skillars.platform.development.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, stateless ranking of a coach-player pair's development-focus history into the top N most
 * frequently worked-on skill codes, for the Session Prep Card's "focus thread". No Spring/DB
 * dependency — mirrors {@link RosterAttentionCalculator}'s own precedent exactly. skillars-12-3
 * Task 3.1.
 */
final class SessionFocusThreadCalculator {

    public static final int MAX_FOCUS_CODES = 3;

    private SessionFocusThreadCalculator() {
    }

    /**
     * @param developmentFocusHistoryMostRecentFirst each session's {@code developmentFocus} codes,
     *                                                ordered most-recent session first (index 0)
     * @param maxCodes                                the cap on the returned list's size
     * @return codes ranked by frequency descending, ties broken by recency (the lowest session
     *     index the code appears in wins) and then alphabetically for a fully reproducible order —
     *     every code sharing a session's index also shares that session's recency, so the frequency
     *     tiebreak alone is not always decisive. Never errors; returns an empty list for empty input.
     */
    static List<String> rank(List<List<String>> developmentFocusHistoryMostRecentFirst, int maxCodes) {
        Map<String, Integer> countByCode = new HashMap<>();
        Map<String, Integer> mostRecentIndexByCode = new HashMap<>();

        for (int index = 0; index < developmentFocusHistoryMostRecentFirst.size(); index++) {
            // skillars-12-3 code review (2026-10-15) Patch: dedupe within a single session before
            // counting — a session's developmentFocus list has no server-side uniqueness constraint
            // (unreachable via DevelopmentFocusSelector.vue's own indexOf-based toggle, but reachable
            // by any direct API client), and a duplicated entry must not count as two sessions' worth
            // of frequency for one actual session.
            Set<String> uniqueCodesInSession = new LinkedHashSet<>(developmentFocusHistoryMostRecentFirst.get(index));
            for (String code : uniqueCodesInSession) {
                countByCode.merge(code, 1, Integer::sum);
                mostRecentIndexByCode.merge(code, index, Math::min);
            }
        }

        List<String> codes = new ArrayList<>(countByCode.keySet());
        codes.sort(
            Comparator.<String>comparingInt(countByCode::get).reversed()
                .thenComparingInt(mostRecentIndexByCode::get)
                .thenComparing(Comparator.naturalOrder())
        );

        return codes.stream().limit(maxCodes).toList();
    }
}
