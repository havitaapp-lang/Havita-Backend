-- =========================================================================
-- HAVITA SAAS — V19 ADD STORAGE KEY TO DOCUMENTS
-- =========================================================================

ALTER TABLE community_documents ADD COLUMN IF NOT EXISTS storage_key VARCHAR(500);
CREATE INDEX IF NOT EXISTS idx_community_documents_storage_key ON community_documents(storage_key);
