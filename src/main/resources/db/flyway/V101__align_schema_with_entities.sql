-- =====================================================================================================================
-- Havita · V101 · Alineación no destructiva con las entidades JPA (Fase 2 / P0, 2026-11-10) — PREPARADA, NO ACTIVA
--
-- Diferencias funcionales entre la baseline V100 (estructura real a 2026-11-10) y el esquema que generan hoy las
-- entidades (Hibernate). Solo AÑADE: no borra columnas, tablas ni datos.
--
-- 1. marketplace_listings.hidden_by_admin: la entidad la declara NOT NULL (valor por defecto false en el código) y
--    falta en la base real (la migración V36, que la añade igual que aquí, nunca se aplicó; Hibernate update no puede
--    añadir una columna NOT NULL sin valor por defecto a una tabla con filas). DEFAULT false = "no oculto por la
--    administración", el mismo valor por defecto que la entidad y V36.
-- 2. security_panic_alerts.alert_type: CHECK de los valores del enumerado (Hibernate lo crea en bases nuevas; falta en
--    la real). Se crea NOT VALID y se valida después: si hubiera valores fuera del enumerado, la migración falla sin
--    modificar datos. Verificado (lectura, 2026-11-10): la base local solo contiene MEDICAL, SECURITY, FIRE y OTHER.
-- =====================================================================================================================

ALTER TABLE public.marketplace_listings
    ADD COLUMN IF NOT EXISTS hidden_by_admin boolean NOT NULL DEFAULT false;

ALTER TABLE public.security_panic_alerts
    ADD CONSTRAINT security_panic_alerts_alert_type_check
    CHECK (alert_type IN ('MEDICAL', 'SECURITY', 'FIRE', 'OTHER')) NOT VALID;

ALTER TABLE public.security_panic_alerts
    VALIDATE CONSTRAINT security_panic_alerts_alert_type_check;
