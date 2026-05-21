-- Demo seed data. Both users share the password: Password123!
-- (BCrypt hashes below). Replace/remove for production.

INSERT INTO app_user (username, password_hash, roles) VALUES
    ('alice', '$2a$10$2u38v658fHEibA.hamAeqOqRy9AVAQSOzNAL6ALroflrxmdZHzHqu', 'USER'),
    ('bob',   '$2a$10$/zJWSnCq/R43dfr6qVt12eOYwWxhxJFOV0Q/Q7wuA7hZ9qK3msuCa', 'USER');

-- Internal accounts held in this service.
INSERT INTO account (account_number, owner_username, currency, balance, type, version, created_at) VALUES
    ('ACC-ALICE-001', 'alice', 'USD', 1000.0000, 'INTERNAL', 0, NOW(6)),
    ('ACC-ALICE-002', 'alice', 'USD',  250.0000, 'INTERNAL', 0, NOW(6)),
    ('ACC-BOB-001',   'bob',   'USD',  500.0000, 'INTERNAL', 0, NOW(6));

-- An external account standing in for a destination held at the partner bank.
-- Owned by alice so she is authorized to push funds out to it.
INSERT INTO account (account_number, owner_username, currency, balance, type, version, created_at) VALUES
    ('PARTNER-EXT-001', 'alice', 'USD', 0.0000, 'EXTERNAL', 0, NOW(6));
