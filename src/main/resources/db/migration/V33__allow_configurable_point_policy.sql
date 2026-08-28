ALTER TABLE point_ledger
    DROP CONSTRAINT point_ledger_amount_check,
    ADD CONSTRAINT point_ledger_amount_check CHECK (
        (entry_type = 'DEMO_GRANT' AND amount > 0)
        OR (entry_type = 'SIGNUP_GRANT' AND amount > 0)
        OR (entry_type = 'CANDIDATE_ACCESS_DEBIT' AND amount < 0)
        OR (entry_type = 'CENTER_RETURN_REWARD' AND amount > 0)
        OR (entry_type = 'ADMIN_ADJUSTMENT' AND amount <> 0)
    );

CREATE TABLE IF NOT EXISTS point_ledger_v28_debit_compatibility (
    ledger_id BIGINT PRIMARY KEY,
    original_amount INTEGER NOT NULL CHECK (original_amount < 0 AND original_amount <> -1)
);

ALTER TABLE point_ledger DROP CONSTRAINT point_ledger_new_reference_check;

UPDATE point_ledger ledger
SET amount = compatibility.original_amount
FROM point_ledger_v28_debit_compatibility compatibility
WHERE ledger.id = compatibility.ledger_id
    AND ledger.entry_type = 'CANDIDATE_ACCESS_DEBIT';

ALTER TABLE point_ledger
    ADD CONSTRAINT point_ledger_new_reference_check CHECK (
        (entry_type = 'CANDIDATE_ACCESS_DEBIT'
            AND reference_type = 'LOST_REPORT' AND reference_id IS NOT NULL)
        OR (entry_type = 'CENTER_RETURN_REWARD'
            AND reference_type = 'FOUND_ITEM_RETURN' AND reference_id IS NOT NULL)
        OR (entry_type NOT IN ('CANDIDATE_ACCESS_DEBIT', 'CENTER_RETURN_REWARD')
            AND reference_type IS NULL AND reference_id IS NULL)
    ) NOT VALID;

DROP TABLE IF EXISTS point_ledger_v28_debit_compatibility;
