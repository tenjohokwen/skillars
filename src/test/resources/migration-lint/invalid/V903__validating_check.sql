-- Fixture (rule 3): CHECK constraint added without NOT VALID and without opt-out.
ALTER TABLE main.widget
    ADD CONSTRAINT chk_widget_name CHECK (char_length(name) > 0);
