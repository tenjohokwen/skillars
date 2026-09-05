-- Fixture (skillars-deferred-92 code review): a quoted schema/table spelling evaded the original
-- pattern, which only matched an unquoted 'main.platform_config'.
INSERT INTO "main"."platform_config" (id, key, value, value_type, description) VALUES
    (999, 'widget.fixture.quoted', 'true', 'STRING', 'fixture')
ON CONFLICT (key) DO NOTHING;
