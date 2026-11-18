-- =========================================================================
-- HAVITA SAAS — V35 PUSH NOTIFICATIONS SUBSCRIPTIONS SCHEMA
-- =========================================================================

CREATE TABLE IF NOT EXISTS push_subscriptions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint TEXT NOT NULL,
    p256dh TEXT NOT NULL,
    auth VARCHAR(255) NOT NULL,
    device_name VARCHAR(150),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_used_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_push_subscriptions_endpoint UNIQUE (endpoint)
);

CREATE INDEX IF NOT EXISTS idx_push_sub_user_tenant ON push_subscriptions(user_id, tenant_id, active);
CREATE INDEX IF NOT EXISTS idx_push_sub_tenant_active ON push_subscriptions(tenant_id, active);
