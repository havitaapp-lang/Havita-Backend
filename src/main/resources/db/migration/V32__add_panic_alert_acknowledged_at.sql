-- V32: Agregar acknowledged_at a security_panic_alerts e índice por resident
ALTER TABLE security_panic_alerts
    ADD COLUMN IF NOT EXISTS acknowledged_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_panic_resident ON security_panic_alerts(resident_id);
CREATE INDEX IF NOT EXISTS idx_panic_created_at ON security_panic_alerts(tenant_id, created_at DESC);
