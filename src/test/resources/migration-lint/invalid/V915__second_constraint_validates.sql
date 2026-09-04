-- Fixture (skillars-deferred-92 AC11.1): the NOT VALID rule used to ask only whether NOT VALID
-- appeared SOMEWHERE in the statement, so constraint `b` below validated under ACCESS EXCLUSIVE
-- while the statement passed clean. The CHECK body's own commas are why a naive split on ',' does
-- not work and balanced-paren scanning is required.
SET lock_timeout = '5s';
ALTER TABLE main.widget
    ADD CONSTRAINT chk_widget_size CHECK (size IN (1,2,3)) NOT VALID,
    ADD CONSTRAINT chk_widget_label CHECK (label <> '');
