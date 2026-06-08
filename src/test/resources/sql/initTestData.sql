--/*-----------------------------------------------------------------------------------------------------------------------------------
--*   Step 1 Start the app
--*-----------------------------------------------------------------------------------------------------------------------------------*/
-- mvn spring-boot:run -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5009" -Dspring-boot.run.profiles=local


--/*-----------------------------------------------------------------------------------------------------------------------------------
--*   Step 2 Run the SQL statements
--*-----------------------------------------------------------------------------------------------------------------------------------*/

insert into main.sec (id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, status, bus_id, value, version) values ('659287191260154475',	'SYSTEM_ACCOUNT',	'2024-12-24 06:51:55.357352',	'SYSTEM_ACCOUNT',	'2024-12-24 06:51:55.357352',	'bed78f34-3e09-4fa8-81db-32326a528cca',	null,	'ACTIVE',	'jot',	'loiI8oT2C1tWecrNXPDjN8fveYEU8rD6nb1k1NbVy92rwdd4/KO+aHhXh3A5zjsT5eSFL/xI+9Rqyj4RI6QCiFywn5nZLIwHGPNEY0F9lnDnGGmVjv/9rO5fgGt83+cxNDyGoCePaVEpBd7xHxyDdfpAoLxQs8mhKGqcEsh09Q+26qEiEm/a9bgDSbSQ0sX00VHBLd35OLmvN+ydjEluYxBTa6KzGb2CQ6Ttg4ZaELmbZOWpEjQ1Z7BbbYiXmWyaY+2HnkyhONoGbUpvVKl1c4e9IlQzeUYkekbUbADIm2LNK9Nhfv5/L5esvFrdVOUcUpLk/y8UT9f5xOMLFJ4Ct6s0eTKvNqYkSz2DFRI8Ip4p/ns6gA4V/1MUf9GeqPUWLiOa28Vw15+R8ycUMqb8NZHOP1oj9RunhSwA7EY84bZL3+yePc3n1b8ne8xzaYVEdK1WBu3J6s2AoBaOL/JLWfu8MuxXI+ub', 'v1');


--insert user data
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
(5418719445932238328,'ROLE_USER','ACTIVE','system','2016-04-26 20:41:25','system','2016-04-26 20:41:25',''),
(3318719445932238111,'ROLE_LTD_ADMIN','ACTIVE','system','2016-04-26 20:41:25','system','2016-04-26 20:41:25',''),
(1238719445932238123,'ROLE_0','ACTIVE','system','2016-04-26 20:41:25','system','2016-04-26 20:41:25',''),
(2228719445932238222,'ROLE_1','ACTIVE','system','2016-04-26 20:41:25','system','2016-04-26 20:41:25',''),
(3338719445932238333,'ROLE_2','ACTIVE','system','2016-04-26 20:41:25','system','2016-04-26 20:41:25','');

-- activated user: loginId: me@yahoo.com p/w: admin*123!
INSERT INTO main."user" (id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, phone_type, title, activated, activation_date, activation_key, locked, login, login_id_type, password_hash, reset_expiration, reset_key, otp_enabled) VALUES (586920556720583008, 'CUQXWCV', '2078-01-19 04:01:29.327261', 'JEHNCBEJL', '2004-07-18 12:55:21.553817', '65acab56-627d-4f49-9c0a-492d108f2799', '3754926784768', 'INACTIVE', '1978-03-19', 'me@yahoo.com', 'RZZ', 'FEMALE', 'en', 'AGZM', 'DE', '0248888736', 'MOBILE', 'QBHNOQUCFL', true, '2006-05-03 18:50:36.681611', 'TDSVYCJULU', false, 'me@yahoo.com', 'EMAIL', '$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i', null, 'HOVPXEWVX', 't');
INSERT INTO main."user_addresses" (user_id, address_line1, address_line2, address_line3, city, company_name, country, name, postal_code, state_prov) values (586920556720583008, 'IUHDLBKX', 'CUBUEWNZ', 'DULUDMIBXZ', 'DQUYPTHOW', 'TAYWNMKYLD', 'NHTTKXZWZ', 'abcdAddress', 'NKDY', 'BCVZN');
INSERT INTO main."user_authority" (user_id, authority_id) VALUES (586920556720583008, 5418719445932238328);

-- admin user
INSERT INTO main."user" (id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, phone_type, title, activated, activation_date, activation_key, locked, login, login_id_type, password_hash, reset_expiration, reset_key, otp_enabled) VALUES (675373350208068096, 'anonymousUser', '2025-02-06 16:12:34.516705', 'anonymousUser', '2025-02-06 16:12:35.198266', 'd503b412-b576-48c2-8ead-ec9e10d42880', NULL, 'ACTIVE', '1990-02-20', 'queb@yahoo.com', 'VAYM', 'MALE', 'en', 'FXFUOUQBUO', 'DE', '01724527687', 'MOBILE', NULL, true, NULL, NULL, false, 'queb@yahoo.com', 'EMAIL', '$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i', NULL, NULL, false);
INSERT INTO main."user" (id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, phone_type, title, activated, activation_date, activation_key, locked, login, login_id_type, password_hash, reset_expiration, reset_key, otp_enabled) VALUES
(675373350208067011, 'anonymousUser', '2025-02-06 16:12:34.516705', 'anonymousUser', '2025-02-06 16:12:35.198266', 'd503b412-b576-48c2-8ead-ec9e10d42880', NULL, 'ACTIVE', '1990-02-20', 'gulliver@yahoo.com', 'Gulli', 'MALE', 'en', 'Travels', 'DE', '695472452', 'MOBILE', NULL, true, NULL, NULL, false, 'gulliver@yahoo.com', 'EMAIL', '$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i', NULL, NULL, false);
INSERT INTO main."user" (id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, phone_type, title, activated, activation_date, activation_key, locked, login, login_id_type, password_hash, reset_expiration, reset_key, otp_enabled) VALUES
(675373350208022222, 'anonymousUser', '2025-02-06 16:12:34.516705', 'anonymousUser', '2025-02-06 16:12:35.198266', 'd503b412-b576-48c2-8ead-ec9e10d42880', NULL, 'ACTIVE', '1990-02-20', 'vatican@yahoo.com', 'Vati', 'MALE', 'en', 'Can', 'DE', '675827687', 'MOBILE', NULL, true, NULL, NULL, false, 'vatican@yahoo.com', 'EMAIL', '$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i', NULL, NULL, false);
INSERT INTO main."user" (id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, phone_type, title, activated, activation_date, activation_key, locked, login, login_id_type, password_hash, reset_expiration, reset_key, otp_enabled) VALUES
(675373350208033333, 'anonymousUser', '2025-02-06 16:12:34.516705', 'anonymousUser', '2025-02-06 16:12:35.198266', 'd503b412-b576-48c2-8ead-ec9e10d42880', NULL, 'ACTIVE', '1990-02-20', 'guarantee@yahoo.com', 'Guarantee', 'MALE', 'en', 'Exp', 'DE', '671237651', 'MOBILE', NULL, true, NULL, NULL, false, 'guarantee@yahoo.com', 'EMAIL', '$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i', NULL, NULL, false);
INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208068096, 5418719445932238328); --ROLE_USER
INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208068096, 6747751741842104908); --ROLE_ADMIN, highest role. Sysadmin
INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208068096, 3318719445932238111); --ROLE_LTD_ADMIN, admin with limited role. All operator agents should have this role
INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208067011, 1238719445932238123); --ROLE_0, gulliver
INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208067011, 3318719445932238111); --ROLE_LTD_ADMIN, admin with limited role. All operator agents should have this role
INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208033333, 2228719445932238222); --ROLE_1, guarantee
INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208033333, 3318719445932238111); --ROLE_LTD_ADMIN, admin with limited role. All operator agents should have this role
INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208022222, 3338719445932238333); --ROLE_2, vatican
INSERT INTO main.user_authority (user_id, authority_id) VALUES (675373350208022222, 3318719445932238111); --ROLE_LTD_ADMIN, admin with limited role. All operator agents should have this role

-- not activated user loginId: not-activated@yahoo.com p/w: admin*123!
INSERT INTO main."user" (id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, phone_type, title, activated, activation_date, activation_key, locked, login, login_id_type, password_hash, reset_expiration, reset_key, otp_enabled) VALUES (31620716521543010, 'admin', '2078-01-19 04:01:29.327261', 'JEHN', '2004-07-18 12:55:21.553817', '22ecwb12-127d-1f19-9c0a-492d1736f5894', 'pqs2wt', 'INACTIVE', '1978-03-19', 'not-activated@yahoo.com', 'Doh', 'FEMALE', 'en', 'Foo', 'DE', '0249283736', 'MOBILE', 'QBHNOQUCFL', false, null, 'TDSVYCJULU', false, 'not-activated@yahoo.com', 'EMAIL', '$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i', null, 'HOVPXEWVX', 't');

-- locked user loginId: locked@yahoo.com p/w: admin*123!
INSERT INTO main."user" (id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, phone_type, title, activated, activation_date, activation_key, locked, login, login_id_type, password_hash, reset_expiration, reset_key, otp_enabled) VALUES (12780121221323583, 'admin', '2078-01-19 04:01:29.327261', 'JEHN', '2004-07-18 12:55:21.553817', 'B67BBF24-A0FB-4020-9E98-BCF67C405EC7', 'wqi3lke', 'INACTIVE', '1978-03-19', 'locked@yahoo.com', 'Mike', 'MALE', 'en', 'Weib', 'DE', '0237591736', 'MOBILE', 'QBHNOQUCFL', true, '2006-05-03 18:50:36.681611', 'activationKey123', true, 'locked@yahoo.com', 'EMAIL', '$2a$10$Sdo/qTAcMcYaIAV6XXw3dejlsDwL93g6zb.uPUwFohPpC8q3bEg5i', null, 'HOVPXEWVX', 't');
