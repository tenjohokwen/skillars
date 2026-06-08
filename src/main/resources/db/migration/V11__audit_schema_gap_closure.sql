-- V27: Audit Schema Gap Closure
-- Creates missing Envers audit (_aud) tables for all audited entities
-- Matches field definitions from JPA entities as of 2026-04-22

-- 1. Security Audit Tables
CREATE TABLE IF NOT EXISTS authority_aud (
    id                 BIGINT NOT NULL,
    rev                INTEGER NOT NULL REFERENCES main.revinfo(rev),
    revtype            SMALLINT,
    name               VARCHAR(50),
    status             VARCHAR(20),
    created_by         VARCHAR(50),
    created_date       TIMESTAMP,
    last_modified_by   VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id         VARCHAR(255),
    session_id         TEXT,
    PRIMARY KEY (id, rev)
);

CREATE TABLE IF NOT EXISTS user_aud (
    id                 BIGINT NOT NULL,
    rev                INTEGER NOT NULL REFERENCES main.revinfo(rev),
    revtype            SMALLINT,
    login              TEXT,
    login_id_type      VARCHAR(20),
    password_hash      TEXT,
    activated          BOOLEAN,
    locked             BOOLEAN,
    activation_key     TEXT,
    activation_date    TIMESTAMP,
    reset_key          TEXT,
    reset_expiration   TIMESTAMP,
    account_expiration TIMESTAMP,
    otp_enabled        BOOLEAN,
    first_name         TEXT,
    last_name          TEXT,
    title              TEXT,
    gender             VARCHAR(20),
    dob                DATE,
    lang_key           TEXT,
    phone              VARCHAR(50),
    iso2_country       VARCHAR(2),
    phone_type         VARCHAR(20),
    email              TEXT,
    national_id        VARCHAR(255),
    status             VARCHAR(20),
    created_by         VARCHAR(50),
    created_date       TIMESTAMP,
    last_modified_by   VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id         VARCHAR(255),
    session_id         TEXT,
    PRIMARY KEY (id, rev)
);

CREATE TABLE IF NOT EXISTS persistent_token_aud (
    id                 BIGINT NOT NULL,
    rev                INTEGER NOT NULL REFERENCES main.revinfo(rev),
    revtype            SMALLINT,
    version            UUID,
    token_value        VARCHAR(255),
    token_date         DATE,
    expiry_time        TIME,
    ip_address         VARCHAR(39),
    user_agent         VARCHAR(255),
    is_blacklisted     BOOLEAN,
    user_id            BIGINT,
    status             VARCHAR(20),
    created_by         VARCHAR(50),
    created_date       TIMESTAMP,
    last_modified_by   VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id         VARCHAR(255),
    session_id         TEXT,
    PRIMARY KEY (id, rev)
);

CREATE TABLE IF NOT EXISTS sec_key_aud (
    id                 BIGINT NOT NULL,
    rev                INTEGER NOT NULL REFERENCES main.revinfo(rev),
    revtype            SMALLINT,
    version            VARCHAR(255),
    bus_id             VARCHAR(255),
    encr_perm_key      TEXT,
    seq                VARCHAR(10),
    status             VARCHAR(20),
    created_by         VARCHAR(50),
    created_date       TIMESTAMP,
    last_modified_by   VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id         VARCHAR(255),
    session_id         TEXT,
    PRIMARY KEY (id, rev)
);

CREATE TABLE IF NOT EXISTS sec_aud (
    id                 BIGINT NOT NULL,
    rev                INTEGER NOT NULL REFERENCES main.revinfo(rev),
    revtype            SMALLINT,
    version            VARCHAR(255),
    bus_id             VARCHAR(255),
    value              TEXT,
    status             VARCHAR(20),
    created_by         VARCHAR(50),
    created_date       TIMESTAMP,
    last_modified_by   VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id         VARCHAR(255),
    session_id         TEXT,
    PRIMARY KEY (id, rev)
);

CREATE TABLE IF NOT EXISTS main.alert_rule_aud (
    id                 BIGINT NOT NULL,
    rev                INTEGER NOT NULL REFERENCES main.revinfo(rev),
    revtype            SMALLINT,
    metric_name        VARCHAR(255),
    threshold          NUMERIC(10, 4),
    window_seconds     INTEGER,
    notification_channel VARCHAR(255),
    enabled            BOOLEAN,
    description        VARCHAR(500),
    status             VARCHAR(20),
    created_by         VARCHAR(50),
    created_date       TIMESTAMP,
    last_modified_by   VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id         VARCHAR(255),
    session_id         TEXT,
    PRIMARY KEY (id, rev)
);
