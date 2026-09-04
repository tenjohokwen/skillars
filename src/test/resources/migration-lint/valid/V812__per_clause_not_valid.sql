-- Fixture (skillars-deferred-92 AC11.1): BOTH constraints carry NOT VALID on their own clause, and
-- the CHECK bodies contain commas — the case balanced-paren scanning has to get right.
SET lock_timeout = '5s';
ALTER TABLE main.widget
    ADD CONSTRAINT chk_widget_size CHECK (size IN (1,2,3)) NOT VALID,
    ADD CONSTRAINT chk_widget_label CHECK (label <> '') NOT VALID;
