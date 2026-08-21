ALTER TABLE found_items
    RENAME COLUMN found_location_text TO found_location_detail;

ALTER TABLE found_items
    RENAME CONSTRAINT found_items_location_not_blank_check
    TO found_items_location_detail_not_blank_check;

ALTER TABLE found_items
    ALTER COLUMN found_location_detail DROP NOT NULL;

ALTER TABLE found_items
    ADD COLUMN found_latitude DECIMAL(10, 7),
    ADD COLUMN found_longitude DECIMAL(10, 7),
    ADD COLUMN found_address VARCHAR(255),
    ADD CONSTRAINT found_items_latitude_range_check
        CHECK (found_latitude IS NULL OR found_latitude BETWEEN -90 AND 90),
    ADD CONSTRAINT found_items_longitude_range_check
        CHECK (found_longitude IS NULL OR found_longitude BETWEEN -180 AND 180);

CREATE INDEX idx_found_items_location
    ON found_items (found_latitude, found_longitude);