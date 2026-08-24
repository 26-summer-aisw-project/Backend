ALTER TABLE lost_centers
    DROP CONSTRAINT lost_centers_verification_status_check,
    ADD CONSTRAINT lost_centers_verification_status_check
        CHECK (verification_status IN (
            'inactive',
            'official_verified',
            'official_board_verified',
            'official_local_verified',
            'admin_verified'
        ));
