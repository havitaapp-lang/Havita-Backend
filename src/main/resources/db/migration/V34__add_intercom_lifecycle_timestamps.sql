-- V34: Add lifecycle connection timestamps to intercom_calls
ALTER TABLE intercom_calls ADD COLUMN IF NOT EXISTS caller_connected_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE intercom_calls ADD COLUMN IF NOT EXISTS recipient_connected_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE intercom_calls ADD COLUMN IF NOT EXISTS connected_at TIMESTAMP WITH TIME ZONE;
