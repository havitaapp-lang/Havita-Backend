-- =========================================================================
-- HAVITA SAAS — V1 INITIAL FOUNDATION SCHEMA
-- Baseline Flyway migration for Modular Monolith
-- =========================================================================

-- Enable UUID extension if supported
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. BASE AUDIT & PLATFORM HEALTH LOG TABLE
CREATE TABLE IF NOT EXISTS platform_audit_events (
    id UUID PRIMARY KEY,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_name VARCHAR(150) NOT NULL,
    actor_email VARCHAR(150),
    actor_role VARCHAR(50) NOT NULL,
    action VARCHAR(100) NOT NULL,
    resource VARCHAR(150) NOT NULL,
    tenant_id UUID,
    tenant_name VARCHAR(150),
    result VARCHAR(30) NOT NULL DEFAULT 'SUCCESS',
    details TEXT,
    ip_address VARCHAR(45) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON platform_audit_events(timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_audit_tenant ON platform_audit_events(tenant_id);

-- 2. ORGANIZATIONS (Multi-Community Corporate Groups)
CREATE TABLE IF NOT EXISTS organizations (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    nit VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(150),
    phone VARCHAR(50),
    city VARCHAR(100),
    address VARCHAR(255),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 3. TENANTS (Copropiedades / Comunidades Residenciales)
CREATE TABLE IF NOT EXISTS tenants (
    id UUID PRIMARY KEY,
    organization_id UUID REFERENCES organizations(id) ON DELETE SET NULL,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(100) UNIQUE NOT NULL,
    subdomain VARCHAR(100) UNIQUE NOT NULL,
    nit VARCHAR(50),
    city VARCHAR(100),
    address VARCHAR(255),
    phone VARCHAR(50),
    contact_email VARCHAR(150),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    storage_limit_mb INT NOT NULL DEFAULT 5120,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tenants_slug ON tenants(slug);
CREATE INDEX IF NOT EXISTS idx_tenants_subdomain ON tenants(subdomain);
