package com.softropic.skillars.platform.development.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Pure unit tests for {@link RosterAttentionCalculator}. skillars-12-1 AC4/AC5/AC6, Task 5.2. No
 * Spring, no mocks — the calculator is a function of its {@link RosterAttentionCalculator.Signals}.
 */
class RosterAttentionCalculatorTest {

    @Test
    void brandNewPlayer_scoresLowestRecencyUrgency_notMaximum() {
        // AC6: a booking created moments ago -> daysSinceLastActivity ~0 -> lowest urgency, not
        // null-propagated into a maximum score.
        RosterAttentionCalculator.Result r = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(0, 0, 0, 30, false));

        assertThat(r.attentionScore()).isCloseTo(0.0, offset(1e-9));
        assertThat(r.dormant()).isFalse();
    }

    @Test
    void daysSinceLastActivityBeyondThreshold_isDormant() {
        RosterAttentionCalculator.Result r = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(31, 0, 0, 30, false));

        assertThat(r.dormant()).isTrue();
    }

    // skillars-12-1 code review (2026-10-15) Patch: the Math.max(...,0) guard inside compute() was
    // untested — no case exercised a negative daysSinceLastActivity, so the guard could be deleted
    // without failing anything. Now defense-in-depth only (CoachRosterService derives
    // daysSinceLastActivity from past-only activity, so a negative value should be unreachable in
    // production — see the Signals Javadoc), but still exercised directly here in case that
    // upstream invariant is ever violated.
    @Test
    void negativeDaysSinceLastActivity_flooredAtZero_byDefensiveGuard() {
        RosterAttentionCalculator.Result negative = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(-10, 0, 0, 30, false));
        RosterAttentionCalculator.Result zero = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(0, 0, 0, 30, false));

        assertThat(negative.attentionScore()).isEqualTo(zero.attentionScore());
        assertThat(negative.attentionScore()).isCloseTo(0.0, offset(1e-9));
    }

    @Test
    void daysSinceLastActivityAtThreshold_isNotDormant() {
        RosterAttentionCalculator.Result r = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(30, 0, 0, 30, false));

        assertThat(r.dormant()).isFalse();
    }

    // skillars-12-1 code review (2026-10-15) Patch, owner decision: a future-dated roster-qualifying
    // booking (ACCEPTED/PAYMENT_PENDING/CONFIRMED/UPCOMING) suppresses only the dormant badge, not
    // the recency score — CoachRosterService excludes it from daysSinceLastActivity entirely rather
    // than letting it mask a long-silent player's real idle time.
    @Test
    void hasUpcomingBooking_suppressesDormantFlag_butNotRecencyScore() {
        RosterAttentionCalculator.Result withUpcoming = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(60, 0, 0, 30, true));
        RosterAttentionCalculator.Result withoutUpcoming = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(60, 0, 0, 30, false));

        assertThat(withUpcoming.dormant()).isFalse();
        assertThat(withoutUpcoming.dormant()).isTrue();
        assertThat(withUpcoming.attentionScore()).isEqualTo(withoutUpcoming.attentionScore());
    }

    @Test
    void recencyComponent_clampsAtDormancyThreshold() {
        // Far beyond the threshold normalizes to the same 1.0 as exactly-at-threshold-plus-one.
        RosterAttentionCalculator.Result atCeiling = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(31, 0, 0, 30, false));
        RosterAttentionCalculator.Result wayPast = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(9999, 0, 0, 30, false));

        assertThat(atCeiling.attentionScore()).isEqualTo(wayPast.attentionScore());
        assertThat(atCeiling.attentionScore()).isCloseTo(0.4, offset(1e-9));
    }

    @Test
    void neglectedCount_clampsAtCapOfFive() {
        RosterAttentionCalculator.Result atCap = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(0, 5, 0, 30, false));
        RosterAttentionCalculator.Result beyondCap = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(0, 50, 0, 30, false));

        assertThat(atCap.attentionScore()).isEqualTo(beyondCap.attentionScore());
        assertThat(atCap.attentionScore()).isCloseTo(0.35, offset(1e-9));
    }

    // skillars-12-1 code review (2026-10-15) Patch, owner decision: replaced the prior
    // decliningCount/countWithTrendData ratio (which read 0.0 — indistinguishable from "no declining
    // skills" — for an abandoned player whose every skill is INSUFFICIENT_DATA, and let a player with
    // few tracked skills outrank one with many more declining skills) with a capped absolute count,
    // mirroring neglectedCount_clampsAtCapOfFive directly above.
    @Test
    void decliningCount_clampsAtCapOfFive() {
        RosterAttentionCalculator.Result atCap = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(0, 0, 5, 30, false));
        RosterAttentionCalculator.Result beyondCap = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(0, 0, 50, 30, false));

        assertThat(atCap.attentionScore()).isEqualTo(beyondCap.attentionScore());
        assertThat(atCap.attentionScore()).isCloseTo(0.25, offset(1e-9));
    }

    @Test
    void noTrendData_defaultsDecliningTermToZero_notNullPropagated() {
        RosterAttentionCalculator.Result r = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(0, 0, 0, 30, false));

        assertThat(r.attentionScore()).isZero();
    }

    // skillars-12-1 code review (2026-10-15) Patch: the prior ordering test
    // (dormantNeglectedPlayer_ranksAboveActiveCleanPlayer, below) varies recency, neglected count,
    // and declining count simultaneously, so AC5's second clause — "among non-dormant players, more
    // open neglected-skill flags ... must rank higher than fewer/none" — was never isolated. Holds
    // recency and declining count equal, varying only neglectedCount.
    @Test
    void equalRecencyAndDeclining_moreNeglectedFlagsRanksHigher() {
        RosterAttentionCalculator.Result moreNeglected = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(10, 3, 1, 30, false));
        RosterAttentionCalculator.Result fewerNeglected = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(10, 1, 1, 30, false));

        assertThat(moreNeglected.attentionScore()).isGreaterThan(fewerNeglected.attentionScore());
    }

    // Same isolation for the declining-trend clause, holding recency and neglected count equal.
    @Test
    void equalRecencyAndNeglected_moreDecliningSkillsRanksHigher() {
        RosterAttentionCalculator.Result moreDeclining = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(10, 1, 3, 30, false));
        RosterAttentionCalculator.Result fewerDeclining = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(10, 1, 1, 30, false));

        assertThat(moreDeclining.attentionScore()).isGreaterThan(fewerDeclining.attentionScore());
    }

    @Test
    void dormantNeglectedPlayer_ranksAboveActiveCleanPlayer() {
        // AC5: dormant + neglected-heavy must outrank active + clean.
        RosterAttentionCalculator.Result dormantNeglected = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(60, 5, 2, 30, false));
        RosterAttentionCalculator.Result activeClean = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(1, 0, 0, 30, false));

        assertThat(dormantNeglected.attentionScore()).isGreaterThan(activeClean.attentionScore());
    }

    @Test
    void maximalSignals_scoreIsOne() {
        RosterAttentionCalculator.Result r = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(100, 10, 5, 30, false));

        assertThat(r.attentionScore()).isCloseTo(1.0, offset(1e-9));
    }
}
