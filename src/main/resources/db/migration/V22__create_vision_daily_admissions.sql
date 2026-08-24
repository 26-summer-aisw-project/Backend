CREATE TABLE vision_daily_admissions (
    admission_date DATE PRIMARY KEY,
    reserved_count INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT vision_daily_admissions_reserved_count_check CHECK (reserved_count >= 0)
);
