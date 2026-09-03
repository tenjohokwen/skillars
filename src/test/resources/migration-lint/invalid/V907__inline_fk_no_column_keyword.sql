-- Fixture (skillars-deferred-91 code review): PostgreSQL makes the COLUMN keyword OPTIONAL, so this
-- is the same validating-FK hazard as V906 with no literal 'ADD COLUMN' text. The original
-- INLINE_FK regex required 'ADD\s+COLUMN' and missed it entirely.
ALTER TABLE main.widget
    ADD owner_id BIGINT REFERENCES main."user" (id);
