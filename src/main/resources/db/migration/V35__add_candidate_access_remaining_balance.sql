ALTER TABLE candidate_accesses
    ADD COLUMN remaining_balance INTEGER,
    ADD CONSTRAINT candidate_accesses_remaining_balance_nonnegative
        CHECK (remaining_balance IS NULL OR remaining_balance >= 0);
