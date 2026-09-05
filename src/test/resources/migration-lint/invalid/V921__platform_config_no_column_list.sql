-- Fixture (skillars-deferred-92 code review): no explicit column list still supplies every column
-- positionally, id included — the old pattern required a literal '(' right after the table name.
INSERT INTO main.platform_config VALUES (999, 'widget.fixture.novalues', 'true', 'STRING', 'fixture')
ON CONFLICT (key) DO NOTHING;
