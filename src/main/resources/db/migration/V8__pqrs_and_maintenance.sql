-- =========================================================================
-- HAVITA SAAS — V8 PQRS AND MAINTENANCE SCHEMA
-- =========================================================================

-- 1. PQRS TABLE (Peticiones, Quejas, Reclamos, Sugerencias y Mantenimiento)
CREATE TABLE IF NOT EXISTS pqrs_requests (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    resident_id UUID REFERENCES residents(id) ON DELETE SET NULL,
    created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    assigned_to_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    category VARCHAR(50) NOT NULL DEFAULT 'Otro', -- Plomería, Electricidad, Ascensor, Seguridad, Limpieza, Zonas comunes, Otro
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'NEW', -- NEW, IN_PROGRESS, RESOLVED, CLOSED
    priority VARCHAR(30) NOT NULL DEFAULT 'Media', -- Baja, Media, Alta, Crítica
    response_notes TEXT,
    resolved_at TIMESTAMP WITH TIME ZONE,
    closed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_pqrs_tenant ON pqrs_requests(tenant_id);
CREATE INDEX IF NOT EXISTS idx_pqrs_property ON pqrs_requests(property_id);
CREATE INDEX IF NOT EXISTS idx_pqrs_status ON pqrs_requests(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_pqrs_priority ON pqrs_requests(tenant_id, priority);
CREATE INDEX IF NOT EXISTS idx_pqrs_category ON pqrs_requests(tenant_id, category);

-- 2. PQRS COMMENTS / AUDIT TIMELINE TABLE (Trazabilidad de respuestas y notas)
CREATE TABLE IF NOT EXISTS pqrs_timeline_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    pqrs_id UUID NOT NULL REFERENCES pqrs_requests(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    author_name VARCHAR(150) NOT NULL,
    author_role VARCHAR(50) NOT NULL,
    comment TEXT NOT NULL,
    status_changed_to VARCHAR(30),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_pqrs_timeline_request ON pqrs_timeline_events(pqrs_id);
