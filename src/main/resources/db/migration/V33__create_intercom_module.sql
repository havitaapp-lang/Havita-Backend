-- V33: Create digital audio intercom module (citofonía con Cloudflare RealtimeKit)
CREATE TABLE IF NOT EXISTS intercom_calls (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    caller_user_id UUID NOT NULL REFERENCES users(id),
    caller_name VARCHAR(150) NOT NULL,
    recipient_resident_id UUID NOT NULL REFERENCES residents(id) ON DELETE CASCADE,
    recipient_user_id UUID REFERENCES users(id),
    recipient_name VARCHAR(150) NOT NULL,
    property_unit VARCHAR(100) NOT NULL,
    realtime_meeting_id VARCHAR(150) NOT NULL,
    caller_participant_id VARCHAR(150),
    recipient_participant_id VARCHAR(150),
    status VARCHAR(30) NOT NULL,
    ringing_at TIMESTAMP WITH TIME ZONE,
    accepted_at TIMESTAMP WITH TIME ZONE,
    ended_at TIMESTAMP WITH TIME ZONE,
    duration_seconds INTEGER DEFAULT 0,
    end_reason VARCHAR(100),
    technical_failure_reason VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by_user_id UUID
);

CREATE INDEX IF NOT EXISTS idx_intercom_calls_tenant_created ON intercom_calls(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_intercom_calls_property ON intercom_calls(property_id);
CREATE INDEX IF NOT EXISTS idx_intercom_calls_recipient_user ON intercom_calls(tenant_id, recipient_user_id);
CREATE INDEX IF NOT EXISTS idx_intercom_calls_caller_user ON intercom_calls(tenant_id, caller_user_id);
CREATE INDEX IF NOT EXISTS idx_intercom_calls_status ON intercom_calls(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_intercom_calls_meeting ON intercom_calls(realtime_meeting_id);
