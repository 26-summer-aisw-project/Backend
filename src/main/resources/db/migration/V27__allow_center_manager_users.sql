ALTER TABLE users
    DROP CONSTRAINT ck_users_role,
    ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN', 'CENTER_MANAGER'));
