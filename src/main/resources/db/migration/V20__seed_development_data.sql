-- =========================================================================
-- HAVITA SAAS — V20 SEED DEVELOPMENT DATA
-- Datos reales para entorno local y desarrollo multi-tenant
-- =========================================================================

-- 1. CREACIÓN DE ORGANIZACIÓN DEMO
INSERT INTO organizations (id, name, nit, email, phone, city, address, status, created_at, updated_at)
VALUES (
    '11111111-1111-1111-1111-111111111111',
    'Administraciones del Valle S.A.S.',
    '900123456-1',
    'contacto@adminvalle.com',
    '+57 300 123 4567',
    'Cali',
    'Avenida Colombia # 12-34',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (nit) DO NOTHING;

-- 2. CREACIÓN DE TENANTS (COPROPIEDADES)
INSERT INTO tenants (id, organization_id, name, slug, subdomain, nit, city, address, phone, contact_email, status, storage_limit_mb, storage_used_mb, primary_color, secondary_color, plan_name, created_at, updated_at)
VALUES (
    '16e9543b-3984-4861-ad81-79ba51755a64',
    '11111111-1111-1111-1111-111111111111',
    'Conjunto Residencial El Limonar',
    'limonar',
    'limonar',
    '901234567-8',
    'Cali',
    'Calle 13 # 66-40, Barrio El Limonar',
    '+57 315 789 0123',
    'admin@limonar.demo',
    'ACTIVE',
    10240,
    0,
    '#059669',
    '#047857',
    'Enterprise',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (subdomain) DO UPDATE SET
    name = EXCLUDED.name,
    primary_color = EXCLUDED.primary_color,
    secondary_color = EXCLUDED.secondary_color;

INSERT INTO tenants (id, organization_id, name, slug, subdomain, nit, city, address, phone, contact_email, status, storage_limit_mb, storage_used_mb, primary_color, secondary_color, plan_name, created_at, updated_at)
VALUES (
    '22222222-2222-2222-2222-222222222222',
    '11111111-1111-1111-1111-111111111111',
    'Altos del Río Condominio',
    'altosdelrio',
    'altosdelrio',
    '901345678-9',
    'Medellín',
    'Carrera 43A # 1-50, El Poblado',
    '+57 320 654 3210',
    'admin@altosdelrio.demo',
    'ACTIVE',
    5120,
    0,
    '#3B82F6',
    '#1D4ED8',
    'Pro',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (subdomain) DO NOTHING;

INSERT INTO tenants (id, organization_id, name, slug, subdomain, nit, city, address, phone, contact_email, status, storage_limit_mb, storage_used_mb, primary_color, secondary_color, plan_name, created_at, updated_at)
VALUES (
    '33333333-3333-3333-3333-333333333333',
    '11111111-1111-1111-1111-111111111111',
    'Torres del Valle Club Residencial',
    'torresdelvalle',
    'torresdelvalle',
    '901456789-0',
    'Bogotá',
    'Calle 140 # 15-20, Cedritos',
    '+57 311 234 5678',
    'admin@torresdelvalle.demo',
    'ACTIVE',
    5120,
    0,
    '#8B5CF6',
    '#6D28D9',
    'Starter',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (subdomain) DO NOTHING;

-- 3. USUARIOS DEL SISTEMA (Password123!)
INSERT INTO users (id, email, password_hash, full_name, phone, status, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000001',
    'superadmin@havita.com',
    '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi',
    'Super Admin Havita',
    '+57 300 000 0001',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (email) DO UPDATE SET password_hash = EXCLUDED.password_hash;

INSERT INTO users (id, email, password_hash, full_name, phone, status, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000002',
    'admin@limonar.demo',
    '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi',
    'Carlos Alberto Gómez (Administrador)',
    '+57 315 111 2233',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (email) DO UPDATE SET password_hash = EXCLUDED.password_hash;

INSERT INTO users (id, email, password_hash, full_name, phone, status, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000003',
    'porteria@limonar.demo',
    '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi',
    'Vigilancia Portería Principal',
    '+57 315 222 3344',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (email) DO UPDATE SET password_hash = EXCLUDED.password_hash;

INSERT INTO users (id, email, password_hash, full_name, phone, property_unit, status, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000004',
    'residente@limonar.demo',
    '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi',
    'María Fernanda Restrepo',
    '+57 315 333 4455',
    'Torre 1 - 504',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (email) DO UPDATE SET password_hash = EXCLUDED.password_hash;

-- 4. MEMBRESÍAS Y ROLES POR TENANT
INSERT INTO memberships (id, user_id, tenant_id, role, status, created_at, updated_at)
VALUES (
    'b0000000-0000-0000-0000-000000000001',
    'a0000000-0000-0000-0000-000000000001',
    '16e9543b-3984-4861-ad81-79ba51755a64',
    'SUPER_ADMIN',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (user_id, tenant_id) DO UPDATE SET role = EXCLUDED.role;

INSERT INTO memberships (id, user_id, tenant_id, role, status, created_at, updated_at)
VALUES (
    'b0000000-0000-0000-0000-000000000002',
    'a0000000-0000-0000-0000-000000000002',
    '16e9543b-3984-4861-ad81-79ba51755a64',
    'COMMUNITY_ADMIN',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (user_id, tenant_id) DO UPDATE SET role = EXCLUDED.role;

INSERT INTO memberships (id, user_id, tenant_id, role, status, created_at, updated_at)
VALUES (
    'b0000000-0000-0000-0000-000000000003',
    'a0000000-0000-0000-0000-000000000003',
    '16e9543b-3984-4861-ad81-79ba51755a64',
    'SECURITY',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (user_id, tenant_id) DO UPDATE SET role = EXCLUDED.role;

INSERT INTO memberships (id, user_id, tenant_id, role, status, created_at, updated_at)
VALUES (
    'b0000000-0000-0000-0000-000000000004',
    'a0000000-0000-0000-0000-000000000004',
    '16e9543b-3984-4861-ad81-79ba51755a64',
    'RESIDENT',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (user_id, tenant_id) DO UPDATE SET role = EXCLUDED.role;

-- 5. ESTRUCTURAS E INMUEBLES INICIALES EN LIMONAR
INSERT INTO structures (id, tenant_id, name, type, total_levels, description, created_at, updated_at)
VALUES 
(
    'c1111111-1111-1111-1111-111111111111',
    '16e9543b-3984-4861-ad81-79ba51755a64',
    'Torre 1',
    'Torre',
    10,
    'Torre residencial principal con vista al parque',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'c2222222-2222-2222-2222-222222222222',
    '16e9543b-3984-4861-ad81-79ba51755a64',
    'Torre 2',
    'Torre',
    10,
    'Torre residencial secundaria junto al área social',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, name) DO NOTHING;

INSERT INTO properties (id, tenant_id, structure_id, number, name, path, type, status, level, area, coefficient, bedrooms, bathrooms, vehicles_count, pets_count, created_at, updated_at)
VALUES
(
    'd1111111-1111-1111-1111-111111111101',
    '16e9543b-3984-4861-ad81-79ba51755a64',
    'c1111111-1111-1111-1111-111111111111',
    '101',
    'Apartamento 101',
    'Torre 1 / Piso 1 / Apto 101',
    'Apartamento',
    'OCCUPIED',
    '1',
    78.50,
    0.012500,
    3,
    2,
    1,
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'd1111111-1111-1111-1111-111111111102',
    '16e9543b-3984-4861-ad81-79ba51755a64',
    'c1111111-1111-1111-1111-111111111111',
    '102',
    'Apartamento 102',
    'Torre 1 / Piso 1 / Apto 102',
    'Apartamento',
    'VACANT',
    '1',
    65.00,
    0.010500,
    2,
    2,
    1,
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'd1111111-1111-1111-1111-111111111504',
    '16e9543b-3984-4861-ad81-79ba51755a64',
    'c1111111-1111-1111-1111-111111111111',
    '504',
    'Apartamento 504',
    'Torre 1 / Piso 5 / Apto 504',
    'Apartamento',
    'OCCUPIED',
    '5',
    92.00,
    0.014800,
    3,
    3,
    2,
    1,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, number) DO NOTHING;

-- 6. CENSO DE RESIDENTES REAL EN POSTGRESQL
INSERT INTO residents (id, tenant_id, property_id, user_id, name, document_type, document_id, type, phone, email, is_main_contact, emergency_contact_name, emergency_contact_phone, status, registered_at, created_at, updated_at)
VALUES
(
    'e1111111-1111-1111-1111-111111111504',
    '16e9543b-3984-4861-ad81-79ba51755a64',
    'd1111111-1111-1111-1111-111111111504',
    'a0000000-0000-0000-0000-000000000004',
    'María Fernanda Restrepo',
    'CC',
    '1144001122',
    'Propietario',
    '+57 315 333 4455',
    'residente@limonar.demo',
    TRUE,
    'Guillermo Restrepo',
    '+57 310 987 6543',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT DO NOTHING;

-- 7. ZONAS COMUNES
INSERT INTO common_zones (id, tenant_id, name, description, capacity, reservation_required, cost_per_hour, rules, status, created_at, updated_at)
VALUES
(
    'f1111111-1111-1111-1111-111111111001',
    '16e9543b-3984-4861-ad81-79ba51755a64',
    'Salón Social Principal',
    'Espacio climatizado para eventos familiares y reuniones con cocina y sonido básico.',
    50,
    TRUE,
    25000.00,
    'Uso hasta las 11:00 PM. No música estridente ni pólvora.',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'f1111111-1111-1111-1111-111111111002',
    '16e9543b-3984-4861-ad81-79ba51755a64',
    'Zona BBQ Terraza',
    'Parrilla a gas y carbón con mesas exteriores con vista a la cordillera.',
    20,
    TRUE,
    15000.00,
    'Entregar limpio el asador al culminar el turno.',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, name) DO NOTHING;
