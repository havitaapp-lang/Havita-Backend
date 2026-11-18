-- =========================================================================
-- V24: Add CANCELLED to visitors status CHECK constraint
-- The original visitors table did not include CANCELLED in the allowed
-- status values. This migration drops and recreates the constraint.
-- =========================================================================

-- Drop existing check constraint (name may vary, we try the standard name)
ALTER TABLE visitors DROP CONSTRAINT IF EXISTS visitors_status_check;

-- Recreate with CANCELLED included
ALTER TABLE visitors
    ADD CONSTRAINT visitors_status_check
    CHECK (status IN ('AUTHORIZED', 'PENDING', 'ENTERED', 'EXITED', 'EXPIRED', 'CANCELLED'));
