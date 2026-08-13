CREATE TABLE point_accounts (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE RESTRICT,
    balance INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT point_accounts_balance_check CHECK (balance >= 0)
);
