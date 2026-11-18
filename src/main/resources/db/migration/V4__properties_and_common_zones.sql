-- =========================================================================
-- HAVITA SAAS — V4 PROPERTIES, STRUCTURES AND COMMON ZONES SCHEMA
-- =========================================================================

-- 1. STRUCTURES TABLE (Torres, Bloques, Manzanas, Sectores, Etapas)
CREATE TABLE IF NOT EXISTS structures (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(50) NOT NULL DEFAULT 'Torre',
    total_levels INT NOT NULL DEFAULT 1,
    description VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tenant_structure_name UNIQUE (tenant_id, name)
);

CREATE INDEX IF NOT EXISTS idx_structures_tenant ON structures(tenant_id);

-- 2. PROPERTIES TABLE (Unidades Privadas: Apartamentos, Casas, Locales, etc.)
CREATE TABLE IF NOT EXISTS properties (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    structure_id UUID REFERENCES structures(id) ON DELETE SET NULL,
    number VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    path VARCHAR(200),
    type VARCHAR(50) NOT NULL DEFAULT 'Apartamento',
    status VARCHAR(30) NOT NULL DEFAULT 'VACANT',
    level VARCHAR(20),
    area NUMERIC(10, 2),
    coefficient NUMERIC(8, 6) DEFAULT 0.0,
    bedrooms INT DEFAULT 0,
    bathrooms INT DEFAULT 0,
    vehicles_count INT DEFAULT 0,
    pets_count INT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tenant_property_number UNIQUE (tenant_id, number)
);

CREATE INDEX IF NOT EXISTS idx_properties_tenant ON properties(tenant_id);
CREATE INDEX IF NOT EXISTS idx_properties_structure ON properties(structure_id);
CREATE INDEX IF NOT EXISTS idx_properties_status ON properties(tenant_id, status);

-- 3. COMMON ZONES TABLE (Zonas Comunes: Piscina, Salón Social, Gimnasio, etc.)
CREATE TABLE IF NOT EXISTS common_zones (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    capacity INT NOT NULL DEFAULT 10,
    reservation_required BOOLEAN NOT NULL DEFAULT TRUE,
    cost_per_hour NUMERIC(12, 2) DEFAULT 0.0,
    rules TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tenant_zone_name UNIQUE (tenant_id, name)
);

CREATE INDEX IF NOT EXISTS idx_common_zones_tenant ON common_zones(tenant_id);
