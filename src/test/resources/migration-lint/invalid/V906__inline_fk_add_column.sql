-- Fixture (AC7 blind spot 3): an inline ADD COLUMN ... REFERENCES foreign key. There is no literal
-- 'ADD CONSTRAINT' text, so the VALIDATING_CONSTRAINT rule never sees this validating FK.
ALTER TABLE main.widget
    ADD COLUMN owner_id BIGINT REFERENCES main."user" (id);
