ALTER TABLE point_ledger
    ADD COLUMN reference_type TEXT,
    ADD COLUMN reference_id BIGINT,
    DROP CONSTRAINT point_ledger_amount_check,
    ADD CONSTRAINT point_ledger_amount_check CHECK (
        (entry_type = 'DEMO_GRANT' AND amount > 0)
        OR (entry_type = 'SIGNUP_GRANT' AND amount = 10)
        OR (entry_type = 'CANDIDATE_ACCESS_DEBIT' AND amount = -1)
        OR (entry_type = 'CENTER_RETURN_REWARD' AND amount = 5)
        OR (entry_type = 'ADMIN_ADJUSTMENT' AND amount <> 0)
    ),
    ADD CONSTRAINT point_ledger_new_reference_check CHECK (
        (entry_type = 'CANDIDATE_ACCESS_DEBIT'
            AND reference_type = 'LOST_REPORT' AND reference_id IS NOT NULL)
        OR (entry_type = 'CENTER_RETURN_REWARD'
            AND reference_type = 'FOUND_ITEM_RETURN' AND reference_id IS NOT NULL)
        OR (entry_type NOT IN ('CANDIDATE_ACCESS_DEBIT', 'CENTER_RETURN_REWARD')
            AND reference_type IS NULL AND reference_id IS NULL)
    ) NOT VALID;

CREATE UNIQUE INDEX point_ledger_one_signup_grant_per_user_uq
    ON point_ledger (user_id)
    WHERE entry_type = 'SIGNUP_GRANT';

CREATE UNIQUE INDEX point_ledger_one_return_reward_per_return_uq
    ON point_ledger (reference_id)
    WHERE entry_type = 'CENTER_RETURN_REWARD' AND reference_type = 'FOUND_ITEM_RETURN';

ALTER TABLE candidate_accesses
    ADD CONSTRAINT uk_candidate_accesses_receipt_link UNIQUE (id, user_id, report_id);

CREATE TABLE candidate_access_idempotency_receipts (
    idempotency_key UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    report_id BIGINT NOT NULL REFERENCES lost_reports(id) ON DELETE RESTRICT,
    candidate_access_id BIGINT NOT NULL REFERENCES candidate_accesses(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_candidate_access_receipts_link
        FOREIGN KEY (candidate_access_id, user_id, report_id)
        REFERENCES candidate_accesses(id, user_id, report_id) ON DELETE RESTRICT
);

CREATE INDEX candidate_access_receipts_user_report_idx
    ON candidate_access_idempotency_receipts (user_id, report_id);

CREATE INDEX candidate_access_receipts_access_idx
    ON candidate_access_idempotency_receipts (candidate_access_id);

INSERT INTO point_accounts (user_id, balance)
SELECT id, 0
FROM users
WHERE role = 'USER' AND status = 'ACTIVE'
ON CONFLICT (user_id) DO NOTHING;

WITH inserted_grants AS (
    INSERT INTO point_ledger (user_id, entry_type, amount, idempotency_key)
    SELECT id, 'SIGNUP_GRANT', 10, md5('lostory:signup-grant:v28:' || id)::uuid
    FROM users
    WHERE role = 'USER' AND status = 'ACTIVE'
    ON CONFLICT (user_id) WHERE entry_type = 'SIGNUP_GRANT' DO NOTHING
    RETURNING user_id, amount
)
UPDATE point_accounts account
SET balance = account.balance + inserted_grant.amount,
    updated_at = NOW()
FROM inserted_grants inserted_grant
WHERE account.user_id = inserted_grant.user_id;
