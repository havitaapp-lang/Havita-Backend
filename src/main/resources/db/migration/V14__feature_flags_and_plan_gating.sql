-- =========================================================================
-- HAVITA SAAS — V14 FEATURE FLAGS AND PLAN GATING SCHEMA
-- =========================================================================

-- 1. FEATURE FLAGS TABLE (Catálogo Global de Funcionalidades y Módulos)
CREATE TABLE IF NOT EXISTS feature_flags (
    id UUID PRIMARY KEY,
    flag_key VARCHAR(100) UNIQUE NOT NULL, -- ej: "ASSEMBLIES_VOTING", "ADVANCED_FINANCE"
    name VARCHAR(150) NOT NULL,
    description TEXT,
    category VARCHAR(50) NOT NULL DEFAULT 'Operación', -- Seguridad, Operación, Finanzas, Comunidad, Avanzado
    enabled_by_plan TEXT NOT NULL, -- Lista separada por comas o JSON: "STARTER,PROFESSIONAL,ENTERPRISE"
    is_global_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_beta BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_feature_flags_key ON feature_flags(flag_key);

-- 2. TENANT FEATURE OVERRIDES TABLE (Sobrescrituras manuales por Tenant)
CREATE TABLE IF NOT EXISTS tenant_feature_overrides (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    feature_flag_id UUID NOT NULL REFERENCES feature_flags(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL,
    reason VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tenant_feature_override UNIQUE (tenant_id, feature_flag_id)
);

CREATE INDEX IF NOT EXISTS idx_feature_overrides_tenant ON tenant_feature_overrides(tenant_id);

-- Semilla de Feature Flags base
INSERT INTO feature_flags (id, flag_key, name, description, category, enabled_by_plan, is_global_enabled, is_beta, created_at, updated_at)
VALUES 
  ('11111111-1111-1111-1111-111111111101', 'GATEHOUSE_ACCESS', 'Control de Accesos y Portería', 'Registro de visitantes y paquetes', 'Seguridad', 'STARTER,PROFESSIONAL,ENTERPRISE', TRUE, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('11111111-1111-1111-1111-111111111102', 'PQRS_MAINTENANCE', 'PQRS y Mantenimiento', 'Gestión de reportes, reclamos y asignación técnica', 'Operación', 'STARTER,PROFESSIONAL,ENTERPRISE', TRUE, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('11111111-1111-1111-1111-111111111103', 'AMENITIES_RESERVATIONS', 'Reservas de Zonas Comunes', 'Agenda y cobro de amenidades', 'Comunidad', 'PROFESSIONAL,ENTERPRISE', TRUE, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('11111111-1111-1111-1111-111111111104', 'ASSEMBLIES_VOTING', 'Asambleas y Votación en Vivo', 'Asambleas, quórum por coeficiente y mociones', 'Comunidad', 'PROFESSIONAL,ENTERPRISE', TRUE, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('11111111-1111-1111-1111-111111111105', 'ADVANCED_FINANCE', 'Finanzas y Pasarelas de Pago', 'Facturación electrónica, PSE y conciliación bancaria', 'Finanzas', 'ENTERPRISE', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (flag_key) DO NOTHING;
