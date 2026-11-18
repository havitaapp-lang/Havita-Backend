-- =========================================================================
-- HAVITA SAAS — V21 MAKE MEMBERSHIP TENANT NULLABLE
-- Allows global roles (like SUPER_ADMIN) that are not bound to a specific tenant
-- =========================================================================

ALTER TABLE memberships ALTER COLUMN tenant_id DROP NOT NULL;
