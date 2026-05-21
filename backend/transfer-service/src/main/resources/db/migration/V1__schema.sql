-- Core schema for the transfer service.
-- Money is stored as DECIMAL(19,4); never FLOAT/DOUBLE (no rounding error).

CREATE TABLE app_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    roles         VARCHAR(100) NOT NULL,
    CONSTRAINT uk_app_user_username UNIQUE (username)
) ENGINE=InnoDB;

CREATE TABLE account (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_number VARCHAR(34)  NOT NULL,
    owner_username VARCHAR(100) NOT NULL,
    currency       VARCHAR(3)   NOT NULL,
    balance        DECIMAL(19,4) NOT NULL DEFAULT 0,
    type           VARCHAR(16)  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_account_number UNIQUE (account_number),
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0)
) ENGINE=InnoDB;

CREATE INDEX idx_account_owner ON account (owner_username);

CREATE TABLE transfer_transaction (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    reference                   VARCHAR(40)  NOT NULL,
    source_account_number       VARCHAR(34)  NOT NULL,
    destination_account_number  VARCHAR(34)  NOT NULL,
    amount                      DECIMAL(19,4) NOT NULL,
    currency                    VARCHAR(3)   NOT NULL,
    narrative                   VARCHAR(140),
    type                        VARCHAR(16)  NOT NULL,
    status                      VARCHAR(16)  NOT NULL,
    failure_reason              VARCHAR(255),
    created_at                  TIMESTAMP(6) NOT NULL,
    completed_at                TIMESTAMP(6),
    CONSTRAINT uk_txn_reference UNIQUE (reference)
) ENGINE=InnoDB;

CREATE INDEX idx_txn_source  ON transfer_transaction (source_account_number);
CREATE INDEX idx_txn_created ON transfer_transaction (created_at);

CREATE TABLE idempotency_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    idempotency_key VARCHAR(100) NOT NULL,
    username        VARCHAR(100) NOT NULL,
    request_hash    VARCHAR(64)     NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    response_status INT,
    response_body   VARCHAR(4000),
    created_at      TIMESTAMP(6) NOT NULL,
    updated_at      TIMESTAMP(6) NOT NULL,
    -- The UNIQUE key is what makes the claim-first protocol work: a concurrent
    -- duplicate collides here instead of executing the transfer twice.
    CONSTRAINT uk_idempotency_key UNIQUE (idempotency_key)
) ENGINE=InnoDB;
