ALTER TABLE lost_centers
    ADD COLUMN parent_place TEXT,
    ADD COLUMN detail_location TEXT,
    ADD COLUMN verification_status TEXT NOT NULL DEFAULT 'inactive',
    ADD CONSTRAINT lost_centers_verification_status_check
        CHECK (verification_status IN (
            'inactive',
            'official_verified',
            'official_board_verified',
            'official_local_verified'
        ));
