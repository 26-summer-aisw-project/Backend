CREATE OR REPLACE FUNCTION reject_legacy_terminal_handover_mutation()
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
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;
