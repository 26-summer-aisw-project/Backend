ALTER TABLE found_items
    DROP CONSTRAINT found_items_status_check,
    ADD CONSTRAINT found_items_status_check CHECK (status IN ('ACTIVE', 'EXPIRED', 'RETURNED'));
