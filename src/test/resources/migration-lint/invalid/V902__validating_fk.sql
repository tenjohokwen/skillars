-- Fixture (rule 3): FK added without NOT VALID and without an allow-validating-constraint opt-out.
ALTER TABLE main.widget
    ADD CONSTRAINT fk_widget_owner FOREIGN KEY (owner_id) REFERENCES main."user" (id);
