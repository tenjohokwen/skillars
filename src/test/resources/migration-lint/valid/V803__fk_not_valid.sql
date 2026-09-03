-- Fixture: FK added NOT VALID; a later migration will VALIDATE it (rule 3).
ALTER TABLE main.widget
    ADD CONSTRAINT fk_widget_owner FOREIGN KEY (owner_id) REFERENCES main."user" (id) NOT VALID;
ALTER TABLE main.widget
    ADD CONSTRAINT chk_widget_name_len CHECK (char_length(name) > 0) NOT VALID;
