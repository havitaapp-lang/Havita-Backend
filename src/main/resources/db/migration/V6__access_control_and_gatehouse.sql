-- =========================================================================
-- HAVITA SAAS — V6 ACCESS CONTROL AND GATEHOUSE SCHEMA
-- =========================================================================

-- 1. VISITORS / INVITATIONS TABLE (Control de Accesos y Visitas)
CREATE TABLE IF NOT EXISTS visitors (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    resident_id UUID REFERENCES residents(id) ON DELETE SET NULL,
    name VARCHAR(150) NOT NULL,
    document_id VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING', -- AUTHORIZED, PENDING, ENTERED, EXITED, EXPIRED
    expected_at TIMESTAMP WITH TIME ZONE,
    entered_at TIMESTAMP WITH TIME ZONE,
    exited_at TIMESTAMP WITH TIME ZONE,
    vehicle_plate VARCHAR(20),
    observations TEXT,
    qr_code VARCHAR(255),
    group_id VARCHAR(100),
    group_name VARCHAR(150),
    visit_purpose VARCHAR(150),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_visitors_tenant ON visitors(tenant_id);
CREATE INDEX IF NOT EXISTS idx_visitors_property ON visitors(property_id);
CREATE INDEX IF NOT EXISTS idx_visitors_status ON visitors(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_visitors_document ON visitors(tenant_id, document_id);
CREATE INDEX IF NOT EXISTS idx_visitors_group ON visitors(group_id);

-- 2. PACKAGES TABLE (Recepción y Entrega de Correspondencia y Paquetería)
CREATE TABLE IF NOT EXISTS packages (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    recipient_name VARCHAR(150) NOT NULL,
    tracking_number VARCHAR(100),
    company VARCHAR(100) NOT NULL DEFAULT 'Otro', -- Mercado Libre, Amazon, Servientrega, Coordinadora, DHL, etc.
    status VARCHAR(30) NOT NULL DEFAULT 'RECEIVED', -- RECEIVED, NOTIFIED, DELIVERED
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMP WITH TIME ZONE,
    delivered_to VARCHAR(150),
    security_guard_id UUID REFERENCES users(id) ON DELETE SET NULL,
    observations TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_packages_tenant ON packages(tenant_id);
CREATE INDEX IF NOT EXISTS idx_packages_property ON packages(property_id);
CREATE INDEX IF NOT EXISTS idx_packages_status ON packages(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_packages_received_at ON packages(received_at DESC);
