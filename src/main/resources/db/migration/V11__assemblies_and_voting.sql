-- =========================================================================
-- HAVITA SAAS — V11 ASSEMBLIES AND VOTING SCHEMA
-- =========================================================================

-- 1. ASSEMBLIES TABLE (Asambleas Generales)
CREATE TABLE IF NOT EXISTS assemblies (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    type VARCHAR(30) NOT NULL DEFAULT 'ORDINARIA', -- ORDINARIA, EXTRAORDINARIA
    modality VARCHAR(30) NOT NULL DEFAULT 'MIXTA', -- PRESENCIAL, VIRTUAL, MIXTA
    assembly_date DATE NOT NULL,
    start_time TIME NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'SCHEDULED', -- SCHEDULED, IN_PROGRESS, ENDED
    total_properties INT NOT NULL DEFAULT 0,
    registered_attendees INT NOT NULL DEFAULT 0,
    quorum_percentage NUMERIC(6, 2) NOT NULL DEFAULT 0.0,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_assemblies_tenant ON assemblies(tenant_id);
CREATE INDEX IF NOT EXISTS idx_assemblies_date ON assemblies(tenant_id, assembly_date);

-- 2. ASSEMBLY ATTENDEES TABLE (Registro de Asistencia y Quórum)
CREATE TABLE IF NOT EXISTS assembly_attendees (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    assembly_id UUID NOT NULL REFERENCES assemblies(id) ON DELETE CASCADE,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    resident_id UUID REFERENCES residents(id) ON DELETE SET NULL,
    coefficient NUMERIC(8, 6) NOT NULL DEFAULT 0.0,
    is_power_of_attorney BOOLEAN NOT NULL DEFAULT FALSE, -- Si asiste por poder
    representative_name VARCHAR(150),
    checked_in_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_assembly_property UNIQUE (assembly_id, property_id)
);

CREATE INDEX IF NOT EXISTS idx_attendees_assembly ON assembly_attendees(assembly_id);

-- 3. POLLS TABLE (Votaciones de la Asamblea)
CREATE TABLE IF NOT EXISTS polls (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    assembly_id UUID NOT NULL REFERENCES assemblies(id) ON DELETE CASCADE,
    question VARCHAR(300) NOT NULL,
    type VARCHAR(30) NOT NULL DEFAULT 'SINGLE_CHOICE', -- SINGLE_CHOICE, MULTIPLE_CHOICE
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT', -- DRAFT, SCHEDULED, LIVE, CLOSED
    show_results_to_residents BOOLEAN NOT NULL DEFAULT TRUE,
    duration_minutes INT,
    opened_at TIMESTAMP WITH TIME ZONE,
    closed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_polls_assembly ON polls(assembly_id);
CREATE INDEX IF NOT EXISTS idx_polls_status ON polls(tenant_id, status);

-- 4. POLL OPTIONS TABLE (Opciones de votación)
CREATE TABLE IF NOT EXISTS poll_options (
    id UUID PRIMARY KEY,
    poll_id UUID NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    text VARCHAR(200) NOT NULL,
    option_order INT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_poll_options_poll ON poll_options(poll_id);

-- 5. POLL VOTES TABLE (Registro inmutable de votos ponderados por coeficiente)
CREATE TABLE IF NOT EXISTS poll_votes (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    poll_id UUID NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    poll_option_id UUID NOT NULL REFERENCES poll_options(id) ON DELETE CASCADE,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    resident_id UUID REFERENCES residents(id) ON DELETE SET NULL,
    coefficient NUMERIC(8, 6) NOT NULL DEFAULT 0.0,
    voted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_property_poll_option UNIQUE (poll_id, property_id, poll_option_id)
);

CREATE INDEX IF NOT EXISTS idx_poll_votes_poll ON poll_votes(poll_id);
CREATE INDEX IF NOT EXISTS idx_poll_votes_option ON poll_votes(poll_option_id);
