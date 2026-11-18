-- =========================================================================
-- HAVITA SAAS — V3 TENANT BRANDING AND SETTINGS
-- =========================================================================

-- Add branding and configuration columns to tenants
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS logo_url VARCHAR(500);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS primary_color VARCHAR(7) DEFAULT '#3B82F6';
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS secondary_color VARCHAR(7) DEFAULT '#1E40AF';
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS plan_id UUID;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS plan_name VARCHAR(100) DEFAULT 'Starter';
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS trial_ends_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS suspended_reason VARCHAR(255);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS storage_used_mb INT NOT NULL DEFAULT 0;

-- Add computed stats columns to organizations
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS logo_url VARCHAR(500);
