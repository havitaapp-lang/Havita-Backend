-- =========================================================================
-- HAVITA SAAS — V27 ENFORCE SINGLE TENANT & ACCESS HIERARCHY
-- =========================================================================

-- 1. Añadir columna de auditoría explícita a residents si no existe
ALTER TABLE residents ADD COLUMN IF NOT EXISTS granted_by_user_id UUID REFERENCES users(id);

-- 2. Saneamiento / Backfill de relaciones existentes basándonos en resident_invitations
-- Para los residentes que fueron invitados por el Propietario o Arrendatario:
UPDATE residents r
SET 
    granted_by_user_id = i.inviter_user_id,
    parent_resident_id = parent_r.id
FROM resident_invitations i
JOIN residents parent_r ON parent_r.user_id = i.inviter_user_id AND parent_r.property_id = i.property_id
WHERE i.status = 'USED'
  AND i.property_id = r.property_id
  AND (LOWER(r.email) = LOWER(i.invitee_email) OR (r.type = i.invitation_type AND r.created_at >= i.created_at - interval '2 minutes' AND r.created_at <= i.used_at + interval '2 minutes'))
  AND r.parent_resident_id IS NULL;

-- 3. Índice único parcial: MÁXIMO UN ARRENDATARIO ACTIVO POR UNIDAD
CREATE UNIQUE INDEX IF NOT EXISTS uq_active_arrendatario_per_property 
ON residents (property_id) 
WHERE type = 'Arrendatario' AND status = 'ACTIVE';

-- 4. Índice único parcial: MÁXIMO UNA INVITACIÓN PENDIENTE DE ARRENDATARIO POR UNIDAD
CREATE UNIQUE INDEX IF NOT EXISTS uq_pending_arrendatario_invitation 
ON resident_invitations (property_id) 
WHERE invitation_type = 'Arrendatario' AND status = 'PENDING';