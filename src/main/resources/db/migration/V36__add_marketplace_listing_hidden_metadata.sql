-- =========================================================================
-- HAVITA SAAS — V36 ADD MARKETPLACE LISTING HIDDEN METADATA
-- =========================================================================

ALTER TABLE marketplace_listings
    ADD COLUMN IF NOT EXISTS hidden_by_admin BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS hidden_reason TEXT,
    ADD COLUMN IF NOT EXISTS hidden_at TIMESTAMP WITH TIME ZONE;
