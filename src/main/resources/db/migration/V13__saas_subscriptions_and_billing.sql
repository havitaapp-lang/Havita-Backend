-- =========================================================================
-- HAVITA SAAS — V13 SAAS SUBSCRIPTIONS, PLANS AND BILLING SCHEMA
-- =========================================================================

-- 1. SAAS PLANS TABLE (Planes de Suscripción de la Plataforma)
CREATE TABLE IF NOT EXISTS saas_plans (
    id UUID PRIMARY KEY,
    tier VARCHAR(50) NOT NULL, -- STARTER, PROFESSIONAL, ENTERPRISE
    name VARCHAR(100) NOT NULL,
    tagline VARCHAR(255),
    base_price_cop NUMERIC(12, 2) NOT NULL DEFAULT 0.0,
    price_per_unit_cop NUMERIC(12, 2) NOT NULL DEFAULT 0.0,
    periodicity VARCHAR(30) NOT NULL DEFAULT 'Mensual', -- Mensual, Anual
    max_properties INT NOT NULL DEFAULT 100,
    max_admins INT NOT NULL DEFAULT 2,
    storage_gb INT NOT NULL DEFAULT 5,
    popular BOOLEAN NOT NULL DEFAULT FALSE,
    features TEXT, -- JSON o delimitado por comas
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, INACTIVE
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_saas_plans_tier ON saas_plans(tier);
CREATE INDEX IF NOT EXISTS idx_saas_plans_status ON saas_plans(status);

-- 2. SAAS SUBSCRIPTIONS TABLE (Suscripciones B2B de los Tenants)
CREATE TABLE IF NOT EXISTS saas_subscriptions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    plan_id UUID NOT NULL REFERENCES saas_plans(id) ON DELETE RESTRICT,
    price_cop NUMERIC(12, 2) NOT NULL DEFAULT 0.0,
    billing_cycle VARCHAR(30) NOT NULL DEFAULT 'MONTHLY', -- MONTHLY, ANNUAL
    status VARCHAR(30) NOT NULL DEFAULT 'TRIAL', -- TRIAL, ACTIVE, PAST_DUE, SUSPENDED, CANCELLED, EXPIRED
    start_date DATE NOT NULL DEFAULT CURRENT_DATE,
    expires_at DATE NOT NULL,
    payment_method VARCHAR(50), -- PSE, CARD, BANK_TRANSFER
    auto_renew BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tenant_subscription UNIQUE (tenant_id)
);

CREATE INDEX IF NOT EXISTS idx_saas_subs_tenant ON saas_subscriptions(tenant_id);
CREATE INDEX IF NOT EXISTS idx_saas_subs_status ON saas_subscriptions(status);

-- 3. SAAS INVOICES TABLE (Facturas B2B emitidas a los Tenants)
CREATE TABLE IF NOT EXISTS saas_invoices (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    subscription_id UUID REFERENCES saas_subscriptions(id) ON DELETE SET NULL,
    invoice_number VARCHAR(100) UNIQUE NOT NULL, -- ej: INV-2026-001
    period VARCHAR(100) NOT NULL, -- ej: "Septiembre 2026"
    amount_cop NUMERIC(12, 2) NOT NULL DEFAULT 0.0,
    tax_cop NUMERIC(12, 2) NOT NULL DEFAULT 0.0,
    total_cop NUMERIC(12, 2) NOT NULL DEFAULT 0.0,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    due_at TIMESTAMP WITH TIME ZONE NOT NULL,
    paid_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING', -- PAID, PENDING, OVERDUE, VOIDED
    payment_reference VARCHAR(150),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_saas_invoices_tenant ON saas_invoices(tenant_id);
CREATE INDEX IF NOT EXISTS idx_saas_invoices_status ON saas_invoices(status);
CREATE INDEX IF NOT EXISTS idx_saas_invoices_number ON saas_invoices(invoice_number);
