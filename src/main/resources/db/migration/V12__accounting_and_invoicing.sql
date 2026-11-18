-- =========================================================================
-- HAVITA SAAS — V12 ACCOUNTING AND INVOICING SCHEMA
-- =========================================================================

-- 1. BILLING CONFIG TABLE (Configuración de Cuota de Administración por Tenant)
CREATE TABLE IF NOT EXISTS billing_configs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    calculation_method VARCHAR(30) NOT NULL DEFAULT 'COEFFICIENT', -- COEFFICIENT, FIXED_FEE, MIXED
    base_budget_amount NUMERIC(14, 2) NOT NULL DEFAULT 0.0, -- Presupuesto total base a repartir por coeficientes
    fixed_fee_amount NUMERIC(12, 2) NOT NULL DEFAULT 0.0, -- Tarifa plana si aplica
    late_fee_percentage NUMERIC(5, 2) NOT NULL DEFAULT 1.5, -- % de interés por mora mensual
    discount_early_payment_percentage NUMERIC(5, 2) NOT NULL DEFAULT 5.0, -- % descuento pronto pago
    due_day_of_month INT NOT NULL DEFAULT 15, -- Día límite de pago
    discount_day_of_month INT NOT NULL DEFAULT 5, -- Día límite con descuento
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tenant_billing_config UNIQUE (tenant_id)
);

CREATE INDEX IF NOT EXISTS idx_billing_config_tenant ON billing_configs(tenant_id);

-- 2. INVOICES / BILLING STATEMENTS TABLE (Cuentas de Cobro de Administración por Inmueble)
CREATE TABLE IF NOT EXISTS property_invoices (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    resident_id UUID REFERENCES residents(id) ON DELETE SET NULL,
    invoice_number VARCHAR(100) NOT NULL, -- ej: CUO-2026-09-101
    period VARCHAR(100) NOT NULL, -- ej: "Septiembre 2026"
    concept VARCHAR(200) NOT NULL DEFAULT 'Cuota Ordinaria de Administración',
    base_amount NUMERIC(12, 2) NOT NULL DEFAULT 0.0,
    late_fee_amount NUMERIC(12, 2) NOT NULL DEFAULT 0.0,
    discount_amount NUMERIC(12, 2) NOT NULL DEFAULT 0.0,
    previous_balance NUMERIC(12, 2) NOT NULL DEFAULT 0.0,
    total_amount NUMERIC(12, 2) NOT NULL DEFAULT 0.0,
    balance_due NUMERIC(12, 2) NOT NULL DEFAULT 0.0,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING', -- PENDING, PAID, PARTIALLY_PAID, OVERDUE, CANCELLED
    issued_date DATE NOT NULL DEFAULT CURRENT_DATE,
    due_date DATE NOT NULL,
    discount_until_date DATE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_property_invoice_period UNIQUE (property_id, period)
);

CREATE INDEX IF NOT EXISTS idx_prop_invoices_tenant ON property_invoices(tenant_id);
CREATE INDEX IF NOT EXISTS idx_prop_invoices_property ON property_invoices(property_id);
CREATE INDEX IF NOT EXISTS idx_prop_invoices_status ON property_invoices(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_prop_invoices_number ON property_invoices(tenant_id, invoice_number);

-- 3. PAYMENTS TABLE (Recibos de Caja / Pagos Registrados)
CREATE TABLE IF NOT EXISTS property_payments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    invoice_id UUID NOT NULL REFERENCES property_invoices(id) ON DELETE CASCADE,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    payment_method VARCHAR(50) NOT NULL DEFAULT 'TRANSFERENCIA', -- PSE, TRANSFERENCIA, EFECTIVO, CONSIGNACION, TARJETA
    reference_number VARCHAR(100),
    amount_paid NUMERIC(12, 2) NOT NULL DEFAULT 0.0,
    payment_date DATE NOT NULL DEFAULT CURRENT_DATE,
    status VARCHAR(30) NOT NULL DEFAULT 'CONFIRMED', -- PENDING_VERIFICATION, CONFIRMED, REJECTED
    receipt_url VARCHAR(500),
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_prop_payments_tenant ON property_payments(tenant_id);
CREATE INDEX IF NOT EXISTS idx_prop_payments_invoice ON property_payments(invoice_id);
CREATE INDEX IF NOT EXISTS idx_prop_payments_property ON property_payments(property_id);
