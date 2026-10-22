-- skillars-12-7 AC10 (D2): the coach's optional "what went well" session-recap field, captured at
-- wrap-up (or added later through the recap-edit endpoint) alongside what_we_did / whats_next on this
-- row. Additive, nullable ADD COLUMN per the expand/contract standard (migration-conventions.md
-- rule 1); no backfill is possible, matching V159's identical reasoning for the other two recap columns.
SET LOCAL lock_timeout = '5s';

ALTER TABLE booking.session_completion_data
    ADD COLUMN IF NOT EXISTS what_went_well varchar(500);
