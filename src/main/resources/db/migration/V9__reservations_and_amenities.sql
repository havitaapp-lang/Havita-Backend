-- =========================================================================
-- HAVITA SAAS — V9 RESERVATIONS AND AMENITIES SCHEMA
-- =========================================================================

-- 1. RESERVATIONS TABLE (Reservas de Zonas Comunes / Amenidades)
CREATE TABLE IF NOT EXISTS reservations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    common_zone_id UUID NOT NULL REFERENCES common_zones(id) ON DELETE CASCADE,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    resident_id UUID REFERENCES residents(id) ON DELETE SET NULL,
    created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    reservation_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    guests_count INT DEFAULT 1,
    total_cost NUMERIC(12, 2) DEFAULT 0.0,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING', -- PENDING, CONFIRMED, CANCELLED, REJECTED, COMPLETED
    observations TEXT,
    admin_notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_reservations_tenant ON reservations(tenant_id);
CREATE INDEX IF NOT EXISTS idx_reservations_zone ON reservations(common_zone_id);
CREATE INDEX IF NOT EXISTS idx_reservations_property ON reservations(property_id);
CREATE INDEX IF NOT EXISTS idx_reservations_date ON reservations(tenant_id, reservation_date);
CREATE INDEX IF NOT EXISTS idx_reservations_status ON reservations(tenant_id, status);
