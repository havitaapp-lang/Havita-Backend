-- =========================================================================
-- HAVITA SAAS — V29 MARKETPLACE MODULE
-- =========================================================================

CREATE TABLE IF NOT EXISTS marketplace_config (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL UNIQUE REFERENCES tenants(id) ON DELETE CASCADE,
    mode VARCHAR(30) NOT NULL DEFAULT 'AUTO_APPROVE',
    listing_duration_days INTEGER NOT NULL DEFAULT 30,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS marketplace_listings (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    created_by_user_id UUID NOT NULL REFERENCES users(id),
    property_unit VARCHAR(200),
    type VARCHAR(20) NOT NULL,
    category VARCHAR(100) NOT NULL,
    title VARCHAR(150) NOT NULL,
    description TEXT NOT NULL,
    price DECIMAL(12, 2),
    price_type VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING_APPROVAL',
    image_keys TEXT,
    contact_phone VARCHAR(50),
    rejection_reason TEXT,
    published_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS marketplace_reports (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    listing_id UUID NOT NULL REFERENCES marketplace_listings(id) ON DELETE CASCADE,
    reported_by_user_id UUID NOT NULL REFERENCES users(id),
    reason VARCHAR(30) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    resolved_by_user_id UUID REFERENCES users(id),
    resolved_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_marketplace_listings_tenant ON marketplace_listings(tenant_id);
CREATE INDEX IF NOT EXISTS idx_marketplace_listings_tenant_status ON marketplace_listings(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_marketplace_listings_user ON marketplace_listings(created_by_user_id);
CREATE INDEX IF NOT EXISTS idx_marketplace_listings_created ON marketplace_listings(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_marketplace_reports_listing ON marketplace_reports(listing_id);
CREATE INDEX IF NOT EXISTS idx_marketplace_reports_tenant ON marketplace_reports(tenant_id);
CREATE INDEX IF NOT EXISTS idx_marketplace_config_tenant ON marketplace_config(tenant_id);
