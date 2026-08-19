ALTER TABLE found_items
    ADD COLUMN found_location GEOGRAPHY(Point, 4326);

UPDATE found_items
SET found_location = ST_SetSRID(
        ST_MakePoint(found_longitude, found_latitude),
        4326
    )::geography
WHERE found_latitude IS NOT NULL
  AND found_longitude IS NOT NULL;

ALTER TABLE found_items
    DROP CONSTRAINT found_items_latitude_range_check,
    DROP CONSTRAINT found_items_longitude_range_check,
    DROP COLUMN found_latitude,
    DROP COLUMN found_longitude;

CREATE INDEX idx_found_items_found_location
    ON found_items USING GIST (found_location);
