-- Story Deferred-40 AC3: distinct-coach-count for the skills-radar confidence indicator.
-- entry_count (total assessment rows, all coaches) is kept as-is for ReportGenerationService;
-- distinct_coach_count is an additive column purpose-built for the confidence dot, so a single
-- prolific coach logging many assessments no longer misrepresents multi-coach agreement.

ALTER TABLE development.player_radar_composites ADD COLUMN distinct_coach_count INT NOT NULL DEFAULT 0;

UPDATE development.player_radar_composites prc
SET distinct_coach_count = counts.distinct_coaches
FROM (
    SELECT player_id, skill_code, COUNT(DISTINCT coach_id) AS distinct_coaches
    FROM development.radar_assessment_entries
    GROUP BY player_id, skill_code
) counts
WHERE prc.player_id = counts.player_id
  AND prc.skill_code = counts.skill_code;
