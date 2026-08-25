ALTER TABLE users
    DROP CONSTRAINT ck_users_status,
    ADD CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'DELETED'));
