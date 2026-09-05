-- Fixture (skillars-deferred-92 code review): no valid/ fixture exercised a CORRECT
-- main.platform_config seed (columns named explicitly, id omitted) against PLATFORM_CONFIG_EXPLICIT_ID
-- — so nothing pinned the rule against firing on the seeds AC10 asks every future migration to write.
SET lock_timeout = '5s';
INSERT INTO main.platform_config (key, value, value_type, description) VALUES
    ('widget.fixture.correct_seed', 'true', 'STRING', 'fixture')
ON CONFLICT (key) DO NOTHING;
