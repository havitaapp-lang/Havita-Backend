-- =========================================================================
-- HAVITA SAAS — V25 VISITOR SCHEDULE SETTINGS
-- =========================================================================

CREATE TABLE IF NOT EXISTS visitor_settings (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL UNIQUE REFERENCES tenants(id) ON DELETE CASCADE,
    mode        VARCHAR(20) NOT NULL DEFAULT 'HOURS_24',
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS visitor_day_schedules (
    id                  UUID PRIMARY KEY,
    visitor_settings_id UUID NOT NULL REFERENCES visitor_settings(id) ON DELETE CASCADE,
    day_of_week         VARCHAR(10) NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    start_time          TIME,
    end_time            TIME,
    UNIQUE (visitor_settings_id, day_of_week)
);

CREATE INDEX IF NOT EXISTS idx_visitor_settings_tenant ON visitor_settings(tenant_id);
CREATE INDEX IF NOT EXISTS idx_visitor_day_schedules_settings ON visitor_day_schedules(visitor_settings_id);
