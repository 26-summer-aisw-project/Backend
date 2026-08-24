ALTER TABLE found_items
    DROP CONSTRAINT found_items_storage_detail_check,
    DROP CONSTRAINT found_items_status_check,
    ALTER COLUMN name DROP NOT NULL,
    ALTER COLUMN category DROP NOT NULL,
    ALTER COLUMN description DROP NOT NULL,
    ALTER COLUMN found_at DROP NOT NULL,
    ALTER COLUMN storage_method DROP NOT NULL,
    ALTER COLUMN expired_at DROP NOT NULL,
    ALTER COLUMN expired_at DROP DEFAULT,
    ADD COLUMN draft_expires_at TIMESTAMPTZ,
    ADD COLUMN center_id BIGINT REFERENCES lost_centers(id) ON DELETE RESTRICT,
    ADD COLUMN handed_at TIMESTAMPTZ,
    ADD COLUMN analysis_generation INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN vision_status TEXT NOT NULL DEFAULT 'FAILED',
    ADD COLUMN handover_status TEXT NOT NULL DEFAULT 'NONE';

ALTER TABLE found_items
    RENAME COLUMN handover_place_name TO legacy_handover_place_name;

UPDATE found_items
SET handover_status = 'LEGACY_UNVERIFIED'
WHERE storage_method = 'HANDED_TO_CENTER';

ALTER TABLE found_items
    ADD CONSTRAINT found_items_status_check
        CHECK (status IN ('DRAFT', 'PENDING_HANDOVER', 'ACTIVE', 'EXPIRED', 'RETURNED')),
    ADD CONSTRAINT found_items_vision_status_check
        CHECK (vision_status IN ('PENDING', 'READY', 'FAILED')),
    ADD CONSTRAINT found_items_handover_status_check
        CHECK (handover_status IN ('NONE', 'USER_CONFIRMED', 'LEGACY_UNVERIFIED')),
    ADD CONSTRAINT found_items_analysis_generation_check
        CHECK (analysis_generation >= 0),
    ADD CONSTRAINT found_items_lifecycle_fields_check
        CHECK (
            (status = 'DRAFT'
                AND draft_expires_at IS NOT NULL
                AND draft_expires_at > created_at
                AND expired_at IS NULL)
            OR
            (status <> 'DRAFT' AND draft_expires_at IS NULL AND expired_at IS NOT NULL
                AND name IS NOT NULL AND category IS NOT NULL AND description IS NOT NULL
                AND found_at IS NOT NULL AND storage_method IS NOT NULL
                AND (status IN ('EXPIRED', 'RETURNED') OR expired_at > updated_at))
        ),
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

CREATE FUNCTION reject_legacy_terminal_handover_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
        AND OLD.storage_method = 'HANDED_TO_CENTER'
        AND OLD.handover_status = 'LEGACY_UNVERIFIED'
        AND NEW.handover_status = 'USER_CONFIRMED' THEN
        RAISE EXCEPTION 'legacy handover confirmation cannot be fabricated';
    END IF;
    IF OLD.storage_method = 'HANDED_TO_CENTER'
        AND OLD.handover_status = 'LEGACY_UNVERIFIED'
        AND OLD.status IN ('EXPIRED', 'RETURNED') THEN
        RAISE EXCEPTION 'legacy terminal handover rows are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER found_items_legacy_terminal_immutable
BEFORE UPDATE OR DELETE ON found_items
FOR EACH ROW
EXECUTE FUNCTION reject_legacy_terminal_handover_mutation();

ALTER TABLE found_item_images
    RENAME COLUMN storage_path TO legacy_storage_path;

ALTER TABLE found_item_images
    RENAME CONSTRAINT found_item_images_storage_path_not_blank_check
    TO found_item_images_legacy_storage_path_not_blank_check;

ALTER TABLE found_item_images
    ALTER COLUMN stored_filename DROP NOT NULL,
    ALTER COLUMN legacy_storage_path DROP NOT NULL,
    ADD COLUMN object_key VARCHAR(500),
    ADD COLUMN is_current BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN analysis_generation INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN upload_operation_id UUID UNIQUE,
    ADD COLUMN object_deleted_at TIMESTAMPTZ,
    ADD CONSTRAINT found_item_images_analysis_generation_check CHECK (analysis_generation >= 0),
    ADD CONSTRAINT found_item_images_storage_check CHECK (
        (legacy_storage_path IS NOT NULL
            AND object_key IS NULL
            AND upload_operation_id IS NULL
            AND object_deleted_at IS NULL
            AND is_current = FALSE)
        OR
        (legacy_storage_path IS NULL
            AND object_key IS NOT NULL
            AND btrim(object_key) <> ''
            AND upload_operation_id IS NOT NULL
            AND (is_current = FALSE OR object_deleted_at IS NULL))
    ),
    ADD CONSTRAINT uk_found_item_images_id_item UNIQUE (id, found_item_id);

CREATE UNIQUE INDEX found_item_images_one_current_per_item_uq
    ON found_item_images (found_item_id)
    WHERE is_current;

CREATE TABLE object_deletion_outbox (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    object_key VARCHAR(500) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    reason VARCHAR(64) NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    lease_owner VARCHAR(255),
    lease_until TIMESTAMPTZ,
    last_error_code VARCHAR(100),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT object_deletion_outbox_object_key_not_blank_check CHECK (btrim(object_key) <> ''),
    CONSTRAINT object_deletion_outbox_idempotency_key_not_blank_check CHECK (btrim(idempotency_key) <> ''),
    CONSTRAINT object_deletion_outbox_reason_not_blank_check CHECK (btrim(reason) <> ''),
    CONSTRAINT object_deletion_outbox_last_error_code_check
        CHECK (last_error_code IS NULL OR btrim(last_error_code) <> ''),
    CONSTRAINT object_deletion_outbox_status_check CHECK (status IN ('PENDING', 'PROCESSING', 'DONE')),
    CONSTRAINT object_deletion_outbox_attempt_count_check CHECK (attempt_count >= 0),
    CONSTRAINT object_deletion_outbox_lease_check CHECK (
        (status = 'PROCESSING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_owner IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT object_deletion_outbox_completion_check CHECK (
        (status = 'DONE' AND completed_at IS NOT NULL)
        OR (status <> 'DONE' AND completed_at IS NULL)
    )
);

CREATE INDEX object_deletion_outbox_due_idx
    ON object_deletion_outbox (next_attempt_at, id)
    WHERE status = 'PENDING';

CREATE TABLE found_item_vision_jobs (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    found_item_id BIGINT NOT NULL REFERENCES found_items(id) ON DELETE CASCADE,
    image_id BIGINT NOT NULL,
    analysis_generation INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    lease_owner VARCHAR(255),
    lease_until TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_found_item_vision_jobs_image_item FOREIGN KEY (image_id, found_item_id)
        REFERENCES found_item_images(id, found_item_id) ON DELETE CASCADE,
    CONSTRAINT uk_found_item_vision_jobs_item_image_generation
        UNIQUE (found_item_id, image_id, analysis_generation),
    CONSTRAINT found_item_vision_jobs_analysis_generation_check CHECK (analysis_generation >= 0),
    CONSTRAINT found_item_vision_jobs_status_check
        CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED', 'SUPERSEDED')),
    CONSTRAINT found_item_vision_jobs_attempt_count_check CHECK (attempt_count >= 0),
    CONSTRAINT found_item_vision_jobs_state_check CHECK (
        (status = 'PENDING'
            AND lease_owner IS NULL AND lease_until IS NULL AND completed_at IS NULL)
        OR
        (status = 'PROCESSING'
            AND lease_owner IS NOT NULL AND btrim(lease_owner) <> ''
            AND lease_until IS NOT NULL AND completed_at IS NULL)
        OR
        (status IN ('READY', 'FAILED', 'SUPERSEDED')
            AND lease_owner IS NULL AND lease_until IS NULL AND completed_at IS NOT NULL)
    )
);

CREATE INDEX found_item_vision_jobs_due_idx
    ON found_item_vision_jobs (next_attempt_at, id)
    WHERE status = 'PENDING';

ALTER TABLE item_features
    DROP CONSTRAINT item_features_kind_check,
    ADD CONSTRAINT item_features_kind_check
        CHECK (kind IN ('COLOR', 'BRAND', 'LABEL', 'PUBLIC_DESCRIPTION', 'OCR_TEXT'));

INSERT INTO item_features (item_id, kind, feature_value, ordinal, source, visibility, confidence, created_at)
SELECT fi.id, 'PUBLIC_DESCRIPTION', fi.description, 1, 'FINDER', 'CANDIDATE_VIEW', NULL, fi.created_at
FROM found_items fi
WHERE fi.description IS NOT NULL
  AND btrim(fi.description) <> ''
  AND NOT EXISTS (
      SELECT 1
      FROM item_features existing
      WHERE existing.item_id = fi.id
        AND existing.kind = 'PUBLIC_DESCRIPTION'
  );

ALTER TABLE lost_reports
    ADD COLUMN effective_search_radius_meters INTEGER,
    ADD COLUMN radius_policy_version VARCHAR(64),
    ADD COLUMN center_guidance JSONB,
    ADD COLUMN candidates_stale BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN last_matched_at TIMESTAMPTZ,
    ADD COLUMN matching_policy_version VARCHAR(64);

UPDATE lost_reports report
SET effective_search_radius_meters = LEAST(3000, GREATEST(500, report.search_radius)),
    radius_policy_version = 'legacy-v19',
    center_guidance = '[]'::jsonb,
    candidates_stale = TRUE,
    last_matched_at = NULL,
    matching_policy_version = 'legacy-unmatched';

ALTER TABLE lost_reports
    ALTER COLUMN effective_search_radius_meters SET NOT NULL,
    ALTER COLUMN radius_policy_version SET NOT NULL,
    ALTER COLUMN center_guidance SET NOT NULL,
    ALTER COLUMN center_guidance SET DEFAULT '[]'::jsonb,
    ALTER COLUMN matching_policy_version SET NOT NULL,
    ADD CONSTRAINT lost_reports_effective_search_radius_check
        CHECK (effective_search_radius_meters BETWEEN 500 AND 3000),
    ADD CONSTRAINT lost_reports_radius_policy_version_not_blank_check
        CHECK (btrim(radius_policy_version) <> ''),
    ADD CONSTRAINT lost_reports_center_guidance_check
        CHECK (jsonb_typeof(center_guidance) = 'array'),
    ADD CONSTRAINT lost_reports_matching_policy_version_not_blank_check
        CHECK (btrim(matching_policy_version) <> '');
