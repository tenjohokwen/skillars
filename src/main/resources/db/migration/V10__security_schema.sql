-- V26: Security Module Schema
-- Defines tables for User, Authority, PersistentToken, SecKey, Secret, and LoginInfo
-- Uses IF NOT EXISTS for idempotency

CREATE TABLE IF NOT EXISTS authority (
    id                 BIGINT PRIMARY KEY,
    name               VARCHAR(50) UNIQUE NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by         VARCHAR(50),
    created_date       TIMESTAMP,
    last_modified_by   VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id         VARCHAR(255),
    session_id         TEXT
);

CREATE TABLE IF NOT EXISTS "user" (
    id                 BIGINT PRIMARY KEY,
    login              TEXT UNIQUE NOT NULL,
    login_id_type      VARCHAR(20) NOT NULL,
    password_hash      TEXT NOT NULL,
    activated          BOOLEAN NOT NULL DEFAULT FALSE,
    locked             BOOLEAN NOT NULL DEFAULT FALSE,
    activation_key     TEXT,
    activation_date    TIMESTAMP,
    reset_key          TEXT,
    reset_expiration   TIMESTAMP,
    account_expiration TIMESTAMP,
    otp_enabled        BOOLEAN NOT NULL DEFAULT FALSE,
    first_name         TEXT NOT NULL,
    last_name          TEXT NOT NULL,
    title              TEXT,
    gender             VARCHAR(20) NOT NULL,
    dob                DATE NOT NULL,
    lang_key           TEXT,
    phone              VARCHAR(50) UNIQUE,
    iso2_country       VARCHAR(2) NOT NULL,
    phone_type         VARCHAR(20),
    email              TEXT UNIQUE NOT NULL,
    national_id        VARCHAR(255),
    status             VARCHAR(20) NOT NULL DEFAULT 'INACTIVE',
    created_by         VARCHAR(50),
    created_date       TIMESTAMP,
    last_modified_by   VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id         VARCHAR(255),
    session_id         TEXT
);

CREATE TABLE IF NOT EXISTS user_authority (
    user_id            BIGINT NOT NULL REFERENCES "user"(id),
    authority_id       BIGINT NOT NULL REFERENCES authority(id),
    PRIMARY KEY (user_id, authority_id)
);

CREATE TABLE IF NOT EXISTS user_addresses (
    user_id            BIGINT NOT NULL REFERENCES "user"(id),
    name               VARCHAR(50) NOT NULL,
    company_name       VARCHAR(250),
    address_line1      VARCHAR(250) NOT NULL,
    address_line2      VARCHAR(250),
    address_line3      VARCHAR(250),
    city               VARCHAR(50) NOT NULL,
    state_prov         VARCHAR(50) NOT NULL,
    postal_code        VARCHAR(25) NOT NULL,
    country            VARCHAR(25) NOT NULL,
    PRIMARY KEY (user_id, name)
);

CREATE TABLE IF NOT EXISTS persistent_token (
    id                 BIGINT PRIMARY KEY,
    version            UUID UNIQUE NOT NULL,
    token_value        VARCHAR(255) NOT NULL,
    token_date         DATE,
    expiry_time        TIME,
    ip_address         VARCHAR(39),
    user_agent         VARCHAR(255),
    is_blacklisted     BOOLEAN NOT NULL DEFAULT FALSE,
    user_id            BIGINT REFERENCES "user"(id),
    status             VARCHAR(20) NOT NULL DEFAULT 'INACTIVE',
    created_by         VARCHAR(50),
    created_date       TIMESTAMP,
    last_modified_by   VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id         VARCHAR(255),
    session_id         TEXT
);

CREATE TABLE IF NOT EXISTS sec_key (
    id                 BIGINT PRIMARY KEY,
    version            VARCHAR(255) NOT NULL,
    bus_id             VARCHAR(255) NOT NULL,
    encr_perm_key      TEXT NOT NULL,
    seq                VARCHAR(10) NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'INACTIVE',
    created_by         VARCHAR(50),
    created_date       TIMESTAMP,
    last_modified_by   VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id         VARCHAR(255),
    session_id         TEXT,
    UNIQUE (version, bus_id)
);

CREATE TABLE IF NOT EXISTS sec (
    id                 BIGINT PRIMARY KEY,
    version            VARCHAR(255) NOT NULL,
    bus_id             VARCHAR(255) NOT NULL,
    value              TEXT NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'INACTIVE',
    created_by         VARCHAR(50),
    created_date       TIMESTAMP,
    last_modified_by   VARCHAR(50),
    last_modified_date TIMESTAMP,
    request_id         VARCHAR(255),
    session_id         TEXT,
    UNIQUE (version, bus_id)
);

CREATE TABLE IF NOT EXISTS login_info (
    id                 BIGINT PRIMARY KEY,
    creation_date      TIMESTAMP NOT NULL,
    verification_date  TIMESTAMP,
    termination_date   TIMESTAMP,
    expiration_date    TIMESTAMP NOT NULL,
    token              TEXT NOT NULL,
    otp                TEXT NOT NULL,
    login_id           TEXT NOT NULL,
    client_id          TEXT NOT NULL,
    ip_address         TEXT,
    request_id         TEXT NOT NULL,
    sqid_seed          TEXT NOT NULL,
    send_id            TEXT NOT NULL,
    session_id         TEXT NOT NULL
);
