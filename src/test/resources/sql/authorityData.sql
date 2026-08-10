INSERT INTO main.authority
(id,
name,
status,
created_by,
created_date,
last_modified_by,
last_modified_date,
request_id
)
VALUES
(6747751741842104908,'ROLE_ADMIN','ACTIVE','system','2016-04-26 20:41:25','system','2016-04-26 20:41:25',''),
(5418719445932238328,'ROLE_USER','ACTIVE','system','2016-04-26 20:41:25','system','2016-04-26 20:41:25','')
-- authority.name is UNIQUE (V10), and migrations now seed some of these names themselves:
-- V21 ROLE_COACH/ROLE_PARENT, V84 ROLE_PLAYER, V92 ROLE_ADMIN/ROLE_LTD_ADMIN. Without this clause
-- the ROLE_ADMIN row above is a duplicate-key error that fails every @Sql-seeded test in the suite.
-- The ids here stay for the rows this fixture genuinely creates; where a migration got there first
-- its id wins, which is why the user_authority inserts in userData.sql/initTestData.sql resolve the
-- authority BY NAME rather than by these literals. Do not reintroduce literal authority ids.
ON CONFLICT (name) DO NOTHING;