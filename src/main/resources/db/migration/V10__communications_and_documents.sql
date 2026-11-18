-- =========================================================================
-- HAVITA SAAS — V10 COMMUNICATIONS AND DOCUMENTS SCHEMA
-- =========================================================================

-- 1. COMMUNICATIONS TABLE (Comunicados, Circulares y Avisos)
CREATE TABLE IF NOT EXISTS communications (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    author_id UUID REFERENCES users(id) ON DELETE SET NULL,
    author_name VARCHAR(150) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    audience VARCHAR(50) NOT NULL DEFAULT 'Todos', -- Todos, Propietarios, Arrendatarios, Personal
    category VARCHAR(50) NOT NULL DEFAULT 'General', -- General, Mantenimiento, Asamblea, Seguridad
    status VARCHAR(30) NOT NULL DEFAULT 'PUBLISHED', -- PUBLISHED, DRAFT, ARCHIVED
    published_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_communications_tenant ON communications(tenant_id);
CREATE INDEX IF NOT EXISTS idx_communications_audience ON communications(tenant_id, audience);
CREATE INDEX IF NOT EXISTS idx_communications_status ON communications(tenant_id, status);

-- 2. COMMUNITY DOCUMENTS TABLE (Repositorio Documental de la Copropiedad)
CREATE TABLE IF NOT EXISTS community_documents (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    uploaded_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    title VARCHAR(200) NOT NULL,
    category VARCHAR(50) NOT NULL DEFAULT 'General', -- Reglamento, Acta, Estados Financieros, Manual, Circular
    file_type VARCHAR(20) NOT NULL DEFAULT 'PDF', -- PDF, DOCX, XLSX
    file_size VARCHAR(50) NOT NULL, -- ej: "2.4 MB"
    file_url VARCHAR(500) NOT NULL,
    description TEXT,
    downloads_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_documents_tenant ON community_documents(tenant_id);
CREATE INDEX IF NOT EXISTS idx_documents_category ON community_documents(tenant_id, category);
