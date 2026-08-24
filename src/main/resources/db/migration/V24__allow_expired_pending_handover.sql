ALTER TABLE found_items
    DROP CONSTRAINT found_items_storage_detail_check,
    ADD CONSTRAINT found_items_storage_detail_check
        CHECK (
            (status = 'DRAFT'
                AND storage_method IS NULL
                AND storage_description IS NULL
                AND legacy_handover_place_name IS NULL
                AND center_id IS NULL
                AND handover_status = 'NONE'
                AND handed_at IS NULL)
            OR
            (storage_method = 'LEFT_IN_PLACE'
                AND status IN ('ACTIVE', 'EXPIRED', 'RETURNED')
                AND storage_description IS NULL
                AND legacy_handover_place_name IS NULL
                AND center_id IS NULL
                AND handover_status = 'NONE'
                AND handed_at IS NULL)
            OR
            (storage_method = 'MOVED_TO_SAFE_PLACE'
                AND status IN ('ACTIVE', 'EXPIRED', 'RETURNED')
                AND storage_description IS NOT NULL
                AND btrim(storage_description) <> ''
                AND legacy_handover_place_name IS NULL
                AND center_id IS NULL
                AND handover_status = 'NONE'
                AND handed_at IS NULL)
            OR
            (storage_method = 'HANDED_TO_CENTER'
                AND storage_description IS NULL
                AND (
                    (status = 'PENDING_HANDOVER'
                        AND center_id IS NOT NULL
                        AND legacy_handover_place_name IS NULL
                        AND handover_status = 'NONE'
                        AND handed_at IS NULL)
                    OR
                    (status = 'EXPIRED'
                        AND center_id IS NOT NULL
                        AND legacy_handover_place_name IS NULL
                        AND handover_status = 'NONE'
                        AND handed_at IS NULL)
                    OR
                    (status IN ('ACTIVE', 'EXPIRED', 'RETURNED')
                        AND center_id IS NOT NULL
                        AND legacy_handover_place_name IS NULL
                        AND handover_status = 'USER_CONFIRMED'
                        AND handed_at IS NOT NULL
                        AND handed_at BETWEEN created_at AND updated_at)
                    OR
                    (status IN ('ACTIVE', 'EXPIRED', 'RETURNED')
                        AND center_id IS NULL
                        AND legacy_handover_place_name IS NOT NULL
                        AND btrim(legacy_handover_place_name) <> ''
                        AND handover_status = 'LEGACY_UNVERIFIED'
                        AND handed_at IS NULL)
                ))
        );
