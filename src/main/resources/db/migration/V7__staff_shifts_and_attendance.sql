-- =========================================================================
-- HAVITA SAAS — V7 STAFF, SHIFTS AND ATTENDANCE SCHEMA
-- =========================================================================

-- 1. STAFF MEMBERS TABLE (Personal operativo, administrativo y de seguridad)
CREATE TABLE IF NOT EXISTS staff_members (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    document_type VARCHAR(20) NOT NULL DEFAULT 'CC',
    document_number VARCHAR(50) NOT NULL,
    phone VARCHAR(50),
    email VARCHAR(150),
    position VARCHAR(100) NOT NULL, -- Ej: Guarda de seguridad, Conserje, Aseador
    area VARCHAR(50) NOT NULL DEFAULT 'Portería', -- Seguridad, Portería, Administración, Aseo, etc.
    contract_type VARCHAR(50) NOT NULL DEFAULT 'Empleado', -- Empleado, Contratista, Proveedor
    company VARCHAR(150),
    start_date DATE NOT NULL DEFAULT CURRENT_DATE,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, INACTIVE
    assigned_gate VARCHAR(100), -- Portería principal, Portería vehicular, etc.
    avatar_url VARCHAR(500),
    observations TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tenant_staff_document UNIQUE (tenant_id, document_number)
);

CREATE INDEX IF NOT EXISTS idx_staff_tenant ON staff_members(tenant_id);
CREATE INDEX IF NOT EXISTS idx_staff_area ON staff_members(tenant_id, area);
CREATE INDEX IF NOT EXISTS idx_staff_status ON staff_members(tenant_id, status);

-- 2. SHIFTS TABLE (Programación de turnos operativos y de seguridad)
CREATE TABLE IF NOT EXISTS shifts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    staff_member_id UUID NOT NULL REFERENCES staff_members(id) ON DELETE CASCADE,
    shift_date DATE NOT NULL,
    shift_name VARCHAR(100) NOT NULL, -- Ej: Turno Mañana, Turno Noche 12H
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    gate VARCHAR(100),
    shift_type VARCHAR(30) NOT NULL DEFAULT 'Normal', -- Normal, Especial, Relevo, Refuerzo
    status VARCHAR(30) NOT NULL DEFAULT 'SCHEDULED', -- SCHEDULED, IN_PROGRESS, COMPLETED, ABSENT, CANCELLED
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_shifts_tenant ON shifts(tenant_id);
CREATE INDEX IF NOT EXISTS idx_shifts_staff ON shifts(staff_member_id);
CREATE INDEX IF NOT EXISTS idx_shifts_date ON shifts(tenant_id, shift_date);
CREATE INDEX IF NOT EXISTS idx_shifts_status ON shifts(tenant_id, status);

-- 3. ATTENDANCE RECORDS TABLE (Reloj control de marcaciones de asistencia)
CREATE TABLE IF NOT EXISTS attendance_records (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    staff_member_id UUID NOT NULL REFERENCES staff_members(id) ON DELETE CASCADE,
    shift_id UUID REFERENCES shifts(id) ON DELETE SET NULL,
    record_date DATE NOT NULL,
    scheduled_check_in TIME,
    check_in TIMESTAMP WITH TIME ZONE,
    scheduled_check_out TIME,
    check_out TIMESTAMP WITH TIME ZONE,
    gate VARCHAR(100),
    status VARCHAR(30) NOT NULL DEFAULT 'PRESENT', -- PRESENT, LATE, ABSENT, INCOMPLETE
    observations TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_attendance_tenant ON attendance_records(tenant_id);
CREATE INDEX IF NOT EXISTS idx_attendance_staff ON attendance_records(staff_member_id);
CREATE INDEX IF NOT EXISTS idx_attendance_date ON attendance_records(tenant_id, record_date);
CREATE INDEX IF NOT EXISTS idx_attendance_shift ON attendance_records(shift_id);
