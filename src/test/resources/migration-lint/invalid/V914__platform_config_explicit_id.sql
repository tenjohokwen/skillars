-- Fixture (skillars-deferred-92 AC10.3): a seed that still hand-picks the primary key. Since V128
-- the column has an identity; an explicit id raises a PK violation ON CONFLICT (key) never sees.
INSERT INTO main.platform_config (id, key, value, value_type, description) VALUES
    (999, 'widget.fixture.flag', 'true', 'STRING', 'fixture')
ON CONFLICT (key) DO NOTHING;
