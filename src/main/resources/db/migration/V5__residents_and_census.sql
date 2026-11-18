-- =========================================================================
-- HAVITA SAAS — V5 RESIDENTS, VEHICLES AND PETS SCHEMA
-- =========================================================================

-- 1. RESIDENTS TABLE (Censo de residentes vinculados a inmuebles y usuarios)
CREATE TABLE IF NOT EXISTS residents (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    name VARCHAR(150) NOT NULL,
    document_type VARCHAR(20) DEFAULT 'CC',
    document_id VARCHAR(50),
    type VARCHAR(30) NOT NULL DEFAULT 'Propietario', -- Propietario / Arrendatario
    phone VARCHAR(50),
    email VARCHAR(150),
    is_main_contact BOOLEAN NOT NULL DEFAULT FALSE,
    emergency_contact_name VARCHAR(150),
    emergency_contact_phone VARCHAR(50),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / INACTIVE
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_residents_tenant ON residents(tenant_id);
CREATE INDEX IF NOT EXISTS idx_residents_property ON residents(property_id);
CREATE INDEX IF NOT EXISTS idx_residents_user ON residents(user_id);
CREATE INDEX IF NOT EXISTS idx_residents_status ON residents(tenant_id, status);

-- 2. VEHICLES TABLE (Vehículos asociados a una propiedad)
CREATE TABLE IF NOT EXISTS vehicles (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    resident_id UUID REFERENCES residents(id) ON DELETE SET NULL,
    plate VARCHAR(20) NOT NULL,
    type VARCHAR(30) NOT NULL DEFAULT 'Automóvil', -- Automóvil, Moto, Bicicleta, etc.
    brand VARCHAR(50),
    model VARCHAR(50),
    color VARCHAR(30),
    parking_spot VARCHAR(50),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tenant_vehicle_plate UNIQUE (tenant_id, plate)
);

CREATE INDEX IF NOT EXISTS idx_vehicles_tenant ON vehicles(tenant_id);
CREATE INDEX IF NOT EXISTS idx_vehicles_property ON vehicles(property_id);
CREATE INDEX IF NOT EXISTS idx_vehicles_plate ON vehicles(LOWER(plate));

-- 3. PETS TABLE (Mascotas registradas en el censo)
CREATE TABLE IF NOT EXISTS pets (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    resident_id UUID REFERENCES residents(id) ON DELETE SET NULL,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(30) NOT NULL DEFAULT 'Perro', -- Perro, Gato, etc.
    breed VARCHAR(50),
    color VARCHAR(30),
    is_dangerous_breed BOOLEAN NOT NULL DEFAULT FALSE,
    vaccination_card_url VARCHAR(500),
    photo_url VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_pets_tenant ON pets(tenant_id);
CREATE INDEX IF NOT EXISTS idx_pets_property ON pets(property_id);
