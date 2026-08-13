ALTER TABLE users
    ADD COLUMN display_name VARCHAR(50),
    ADD COLUMN role VARCHAR(16),
    ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE users
SET display_name = 'User',
    updated_at = created_at;

UPDATE users AS u
SET role = CASE
    WHEN EXISTS (
        SELECT 1
        FROM user_roles AS ur
        WHERE ur.user_id = u.id AND ur.role = 'ADMIN'
    ) THEN 'ADMIN'
    ELSE 'USER'
END;

ALTER TABLE users
    DROP CONSTRAINT ck_users_status,
    DROP CONSTRAINT uk_users_email,
    DROP CONSTRAINT ck_users_email_canonical,
    ALTER COLUMN email TYPE TEXT,
    ALTER COLUMN password_hash TYPE TEXT,
    ALTER COLUMN display_name SET DEFAULT 'User',
    ALTER COLUMN display_name SET NOT NULL,
    ALTER COLUMN role SET DEFAULT 'USER',
    ALTER COLUMN role SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT NOW(),
    ALTER COLUMN updated_at SET NOT NULL;

UPDATE users
SET status = 'BLOCKED'
WHERE status = 'INACTIVE';

ALTER TABLE users
    ADD CONSTRAINT ck_users_email_canonical CHECK (
        email = btrim(email)
        AND email !~ '(^[[:space:]])|([[:space:]]$)'
        AND char_length(email) BETWEEN 3 AND 320
    ),
    ADD CONSTRAINT ck_users_display_name_not_blank CHECK (btrim(display_name) <> ''),
    ADD CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'BLOCKED')),
    ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'));

CREATE UNIQUE INDEX users_email_ci_uq ON users (lower(email));

DROP TABLE user_roles;
