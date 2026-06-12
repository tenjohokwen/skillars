INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
VALUES
    (112, 'security.age-policy.u10-max-age',        '9',  'LONG', 'Max age (inclusive) for U10 tier',       NOW()),
    (113, 'security.age-policy.young-teen-max-age', '12', 'LONG', 'Max age (inclusive) for AGE_10_12 tier', NOW()),
    (114, 'security.age-policy.teen-max-age',       '17', 'LONG', 'Max age (inclusive) for AGE_13_17 tier', NOW())
ON CONFLICT (key) DO NOTHING;
