package com.softropic.skillars.utils;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * skillars-deferred-131 AC1 Fix 3 (code review 2026-09-23): {@code seedCompleteBuilderSteps} was
 * copy-pasted verbatim into {@code ManualStrikeIT}, {@code ReinstateIT} and
 * {@code AdminCoachEnforcementConcurrencyIT} — all three needed minimal complete builder-step data
 * (specialty, age group, pricing, one availability window) so
 * {@code CoachProfileService.validateReadyForActivation} passes for their fixture coach. Extracted
 * here so the three copies can't drift.
 */
public final class CoachProfileTestFixtures {

    private CoachProfileTestFixtures() {
    }

    public static void seedCompleteBuilderSteps(JdbcTemplate jdbcTemplate, UUID coachId) {
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_specialties (id, coach_id, skill) VALUES (?, ?, 'Dribbling')",
            UUID.randomUUID(), coachId);
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_age_groups (id, coach_id, age_tier) VALUES (?, ?, 'ADULT')",
            UUID.randomUUID(), coachId);
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 50.00, 'EUR')",
            coachId);
        jdbcTemplate.update(
            "INSERT INTO marketplace.coach_availability_windows " +
            "(id, coach_id, day_of_week, start_time, end_time, canonical_timezone) " +
            "VALUES (?, ?, 1, '09:00', '11:00', 'Europe/Berlin')",
            UUID.randomUUID(), coachId);
    }
}
