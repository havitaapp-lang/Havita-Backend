-- =========================================================================
-- HAVITA SAAS — V23 FIX QR CODE COLLISION RISK
-- =========================================================================

-- Agrega restricción UNIQUE a qr_code para evitar colisiones estadísticas
-- PostgreSQL trata los NULLs como distintos, por lo que esto no romperá visitas antiguas sin QR.
ALTER TABLE visitors ADD CONSTRAINT uq_visitor_qr_code UNIQUE (qr_code);
