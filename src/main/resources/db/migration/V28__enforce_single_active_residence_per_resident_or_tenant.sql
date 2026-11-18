-- =========================================================================
-- HAVITA SAAS — V28 ENFORCE SINGLE ACTIVE RESIDENCE PER RESIDENT/TENANT
-- =========================================================================

-- 1. Partial Unique Index: A user can only be an ACTIVE Residente or Arrendatario in ONE property at a time
CREATE UNIQUE INDEX IF NOT EXISTS uq_active_non_owner_residence_per_user
ON residents (user_id)
WHERE user_id IS NOT NULL 
  AND status = 'ACTIVE' 
  AND type IN ('Residente', 'Arrendatario');

-- 2. Partial Unique Index: An email can only be an ACTIVE Residente or Arrendatario in ONE property at a time
CREATE UNIQUE INDEX IF NOT EXISTS uq_active_non_owner_residence_per_email
ON residents (LOWER(email))
WHERE email IS NOT NULL 
  AND status = 'ACTIVE' 
  AND type IN ('Residente', 'Arrendatario');
