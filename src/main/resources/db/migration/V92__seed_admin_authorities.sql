-- V92: Seed ROLE_ADMIN and ROLE_LTD_ADMIN into authority
--
-- Both names are referenced by SecurityConstants.HAS_ADMIN_ROLE, which gates the entire admin
-- surface (moderation queue, disputes, coach enforcement, config, GDPR tools, admin finance,
-- admin video, alert rules) -- but no migration had ever seeded them. Before this, the only way
-- to obtain an admin was hand-written SQL against a running database, and that was written down
-- nowhere. See story skillars-uat-1 (AC1); the first admin USER is created by AdminBootstrapRunner,
-- not here, because User ids come from @Tsid and the password must be a real bcrypt hash.
--
-- Ids continue the hand-assigned sequence: 100 ROLE_COACH / 101 ROLE_PARENT (V21), 102 ROLE_PLAYER (V84).
-- ON CONFLICT (name) mirrors V84: authority.name is UNIQUE (V10), and a UAT database may already
-- carry a hand-inserted row at a different id from someone working around the missing seed.

INSERT INTO main.authority (id, name, status, created_by, created_date)
VALUES
    (103, 'ROLE_ADMIN', 'ACTIVE', 'system', NOW()),
    (104, 'ROLE_LTD_ADMIN', 'ACTIVE', 'system', NOW())
ON CONFLICT (name) DO NOTHING;
