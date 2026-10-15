package com.softropic.skillars.platform.development.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link SessionFocusThreadCalculator}. skillars-12-3 Task 3.7. No Spring, no
 * mocks — matching {@code RosterAttentionCalculatorTest}'s real precedent.
 */
class SessionFocusThreadCalculatorTest {

    @Test
    void emptyInput_returnsEmptyList() {
        List<String> result = SessionFocusThreadCalculator.rank(List.of(), SessionFocusThreadCalculator.MAX_FOCUS_CODES);

        assertThat(result).isEmpty();
    }

    @Test
    void clearSingleCodeWinner_ranksFirst() {
        List<List<String>> history = List.of(
            List.of("PAC", "DRI"),
            List.of("PAC"),
            List.of("PAC", "SHO")
        );

        List<String> result = SessionFocusThreadCalculator.rank(history, 3);

        assertThat(result.get(0)).isEqualTo("PAC");
    }

    // Two codes tied on frequency, broken by recency: the code appearing at the lowest session
    // index (most recent) wins.
    @Test
    void tieBrokenByRecency() {
        List<List<String>> history = List.of(
            List.of("DRI"),   // index 0 (most recent)
            List.of("PAC"),   // index 1
            List.of("PAC"),   // index 2
            List.of("DRI")    // index 3
        );
        // PAC: count=2, mostRecentIndex=1. DRI: count=2, mostRecentIndex=0.

        List<String> result = SessionFocusThreadCalculator.rank(history, 3);

        assertThat(result).containsExactly("DRI", "PAC");
    }

    // Two codes co-occurring in the same session share both count and recency — the final
    // alphabetical tiebreak must still produce a reproducible order.
    @Test
    void finalDeterministicTiebreak_alphabetical() {
        List<List<String>> history = List.of(
            List.of("SHO", "DRI")
        );

        List<String> result = SessionFocusThreadCalculator.rank(history, 3);

        assertThat(result).containsExactly("DRI", "SHO");
    }

    @Test
    void maxCodesCap_actuallyCaps() {
        List<List<String>> history = List.of(
            List.of("PAC", "DRI", "SHO", "DEF")
        );

        // skillars-12-3 code review (2026-10-15) Patch: was a literal 2 over a 4-code input, which
        // proves nothing about MAX_FOCUS_CODES's real production value (3) — changing the constant
        // to any value >= 2 would have left this test green. Exercise the actual constant instead.
        List<String> result = SessionFocusThreadCalculator.rank(history, SessionFocusThreadCalculator.MAX_FOCUS_CODES);

        assertThat(result).hasSize(SessionFocusThreadCalculator.MAX_FOCUS_CODES);
    }

    // skillars-12-3 code review (2026-10-15) Patch: a session's developmentFocus list has no
    // server-side uniqueness constraint — a duplicated code within one session must still count as
    // one occurrence, not inflate that session's weight relative to codes genuinely spread across
    // multiple distinct sessions.
    @Test
    void duplicateCodeWithinSingleSession_countsOnce() {
        List<List<String>> history = List.of(
            List.of("PAC", "PAC", "PAC"), // one session, PAC repeated — must count as 1, not 3
            List.of("DRI"),
            List.of("DRI")
        );

        List<String> result = SessionFocusThreadCalculator.rank(history, 3);

        assertThat(result).containsExactly("DRI", "PAC"); // DRI (count=2) outranks PAC (count=1)
    }
}
