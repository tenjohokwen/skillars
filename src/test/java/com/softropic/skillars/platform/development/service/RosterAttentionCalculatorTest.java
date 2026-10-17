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

    // skillars-deferred-152 AC5: the `dormant` boolean still uses the raw dormantAfterDays
    // comparison (>=), so d == dormantAfterDays is still dormant.
    //
    // skillars-deferred-153 AC8: this used to also assert the recencyNorm SATURATED (0.4) at
    // exactly dormantAfterDays — that coupling is exactly what this AC removes. The recency term
    // now divides by the fixed RECENCY_NORMALIZATION_DAYS (90), not dormantAfterDays, so d=30 is
    // dormant (30 >= 30) but NOT recency-saturated (30/90 x 0.4 ~ 0.133, not 0.4) — these two facts
    // deliberately disagree now, which is the point: dormant and "how urgent does the score read"
    // are no longer the same question.
    @Test
    void daysSinceLastActivityAtThreshold_isDormant_butRecencyScoreNoLongerSaturated() {
        RosterAttentionCalculator.Result atThreshold = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(30, 0, 0, 30, false));

        assertThat(atThreshold.dormant()).isTrue();
        assertThat(atThreshold.attentionScore()).isCloseTo(30.0 / 90.0 * 0.4, offset(1e-9));
        assertThat(atThreshold.attentionScore()).isNotCloseTo(0.4, offset(1e-9));
    }

    // skillars-deferred-152 code review (2026-10-17): pins the OTHER side of the boundary the
    // `>` -> `>=` fix moved. `>=` and `>` differ only AT d == dormantAfterDays, so without this
    // case nothing stopped a future change from sliding the threshold a day early -- the existing
    // inputs were 0, 30, 31, 60, 100, 9999 and -10, none of them one day below the threshold.
    @Test
    void daysSinceLastActivityOneDayBelowThreshold_isNotDormant() {
        RosterAttentionCalculator.Result justBelow = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(29, 0, 0, 30, false));

        assertThat(justBelow.dormant()).isFalse();
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

    // skillars-deferred-153 AC8: renamed and rewritten — the recency component now clamps at the
    // FIXED RECENCY_NORMALIZATION_DAYS (90), not at the operator-configurable dormantAfterDays (30)
    // this test used to pin. d=31 (one past the OLD ceiling) no longer equals d=9999 — it reads
    // well under saturation now (31/90 x 0.4 ~ 0.138); only d=90 and beyond saturate to 0.4.
    @Test
    void recencyComponent_clampsAtFixedNinetyDayHorizon_notAtDormancyThreshold() {
        RosterAttentionCalculator.Result atNewCeiling = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(90, 0, 0, 30, false));
        RosterAttentionCalculator.Result wayPast = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(9999, 0, 0, 30, false));
        RosterAttentionCalculator.Result justPastOldCeiling = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(31, 0, 0, 30, false));

        assertThat(atNewCeiling.attentionScore()).isEqualTo(wayPast.attentionScore());
        assertThat(atNewCeiling.attentionScore()).isCloseTo(0.4, offset(1e-9));
        assertThat(justPastOldCeiling.attentionScore()).isNotEqualTo(wayPast.attentionScore());
        assertThat(justPastOldCeiling.attentionScore()).isCloseTo(31.0 / 90.0 * 0.4, offset(1e-9));
    }

    // skillars-deferred-153 AC8: the actual decoupling — retuning dormantAfterDays to its own
    // legal extremes (min=1, max=3650) must no longer change the recency contribution to
    // attentionScore for an identical daysSinceLastActivity, even though it still flips the
    // `dormant` badge (which stays coupled to dormantAfterDays, by design).
    @Test
    void recencyScore_decoupledFromDormantAfterDays_atItsLegalExtremes() {
        RosterAttentionCalculator.Result minDormantAfterDays = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(45, 0, 0, 1, false));
        RosterAttentionCalculator.Result maxDormantAfterDays = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(45, 0, 0, 3650, false));

        assertThat(minDormantAfterDays.dormant()).isTrue();   // 45 >= 1
        assertThat(maxDormantAfterDays.dormant()).isFalse();  // 45 < 3650
        // Despite the opposite dormant verdicts, the recency-driven score is identical — no longer
        // reshaped by dormantAfterDays at all.
        assertThat(minDormantAfterDays.attentionScore()).isEqualTo(maxDormantAfterDays.attentionScore());
        assertThat(minDormantAfterDays.attentionScore()).isCloseTo(45.0 / 90.0 * 0.4, offset(1e-9));
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
