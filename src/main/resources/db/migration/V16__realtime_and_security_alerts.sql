-- =========================================================================
-- HAVITA SAAS — V16 REALTIME, WEBSOCKETS AND SECURITY ALERTS SCHEMA
-- =========================================================================

-- 1. REALTIME NOTIFICATIONS (Avisos push in-app a residentes/usuarios)
CREATE TABLE IF NOT EXISTS realtime_notifications (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    property_id UUID REFERENCES properties(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    message TEXT NOT NULL,
    type VARCHAR(50) NOT NULL, -- VISIT_ARRIVAL, PACKAGE_RECEIVED, RESERVATION_STATUS, GENERAL_ANNOUNCEMENT, PANIC_ALERT
    data_payload TEXT,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_notifications_tenant_user ON realtime_notifications(tenant_id, user_id);
CREATE INDEX IF NOT EXISTS idx_notifications_tenant_property ON realtime_notifications(tenant_id, property_id);
CREATE INDEX IF NOT EXISTS idx_notifications_is_read ON realtime_notifications(is_read);

-- 2. CONCIERGE CHAT MESSAGES (Chat bidireccional entre portería y apartamento)
CREATE TABLE IF NOT EXISTS concierge_chat_messages (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sender_name VARCHAR(150) NOT NULL,
    sender_role VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    message_type VARCHAR(30) NOT NULL DEFAULT 'TEXT', -- TEXT, IMAGE, AUDIO
    attachment_url VARCHAR(500),
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chat_tenant_property ON concierge_chat_messages(tenant_id, property_id);
CREATE INDEX IF NOT EXISTS idx_chat_created_at ON concierge_chat_messages(created_at);

-- 3. SECURITY PANIC ALERTS (Botón de pánico y eventos críticos SOS)
CREATE TABLE IF NOT EXISTS security_panic_alerts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    resident_id UUID REFERENCES residents(id) ON DELETE SET NULL,
    alert_type VARCHAR(50) NOT NULL, -- MEDICAL, FIRE, INTRUSION, SUSPICIOUS_ACTIVITY, GENERAL
    notes TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, ACKNOWLEDGED, RESOLVED, FALSE_ALARM
    acknowledged_by UUID REFERENCES users(id) ON DELETE SET NULL,
    resolved_by UUID REFERENCES users(id) ON DELETE SET NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    resolution_notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_panic_tenant_status ON security_panic_alerts(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_panic_property ON security_panic_alerts(property_id);
