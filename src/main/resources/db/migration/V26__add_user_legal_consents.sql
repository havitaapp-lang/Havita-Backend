-- =========================================================================
-- HAVITA SAAS - V26 LEGAL CONSENTS AUDIT
-- =========================================================================

CREATE TABLE IF NOT EXISTS user_legal_consents (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    document_type VARCHAR(50) NOT NULL, -- TERMS_OF_SERVICE, PRIVACY_POLICY
    document_version VARCHAR(20) NOT NULL, -- e.g., '1.0', '1.1'
    accepted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_legal_consents_user ON user_legal_consents(user_id);
CREATE INDEX IF NOT EXISTS idx_user_legal_consents_tenant ON user_legal_consents(tenant_id);
CREATE INDEX IF NOT EXISTS idx_user_legal_consents_doc ON user_legal_consents(document_type, document_version);
