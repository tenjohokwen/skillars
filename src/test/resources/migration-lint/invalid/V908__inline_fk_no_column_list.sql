-- Fixture (skillars-deferred-91 code review): the referenced column list is OPTIONAL too — omitting
-- it defaults to the referenced table's primary key. The original INLINE_FK regex required a
-- trailing '(' after the table name and missed this spelling.
ALTER TABLE main.widget
    ADD COLUMN owner_id BIGINT REFERENCES main."user";
