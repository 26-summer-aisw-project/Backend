ALTER TABLE item_features
    DROP CONSTRAINT uk_item_features_item_kind_source_ordinal;

CREATE INDEX item_features_matching_precedence_idx
    ON item_features (item_id, kind, source, visibility, ordinal, id);
