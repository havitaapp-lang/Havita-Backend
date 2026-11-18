CREATE TABLE voting_delegations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    property_id UUID NOT NULL REFERENCES properties(id),
    owner_user_id UUID NOT NULL REFERENCES users(id),
    delegate_user_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(20) NOT NULL,
    granted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP WITH TIME ZONE,
    revoked_by_user_id UUID REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_active_delegation_per_property 
ON voting_delegations(property_id) 
WHERE status = 'ACTIVE';

CREATE INDEX idx_voting_delegations_tenant ON voting_delegations(tenant_id);
CREATE INDEX idx_voting_delegations_property ON voting_delegations(property_id);
