-- Fixture: the online-safe shape — ADD COLUMN with no inline REFERENCES, then ADD CONSTRAINT
-- ... NOT VALID separately (validated in a later migration).
ALTER TABLE main.widget ADD COLUMN owner_id BIGINT;
ALTER TABLE main.widget
    ADD CONSTRAINT fk_widget_owner FOREIGN KEY (owner_id) REFERENCES main."user" (id) NOT VALID;
