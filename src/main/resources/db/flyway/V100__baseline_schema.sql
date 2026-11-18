-- =====================================================================================================================
-- Havita · V100 · Baseline estructural (Fase 2 / P0, 2026-11-10) — PREPARADA, NO ACTIVA
--
-- Estructura (sin datos) de la base local de desarrollo a 2026-11-10, obtenida con pg_dump --schema-only --no-owner
-- --no-privileges (lectura). Representa el esquema "de facto": V1–V19 aplicadas por Flyway + lo que añadió Hibernate
-- (ddl-auto: update) + objetos creados fuera de Flyway (índices únicos parciales de V27/V28, CHECK de V24).
--
-- NO se ejecuta en ningún perfil: Flyway sigue desactivado (spring.flyway.enabled=false) y ningún perfil apunta a
-- classpath:db/flyway. Las migraciones históricas de db/migration (V1–V36) no se modifican.
--
-- Uso previsto (requiere aprobación, ver tasks/P0_FLYWAY_BASELINE.md):
--   · Base VACÍA: flyway migrate (aplica V100 y V101).
--   · Base EXISTENTE: comprobar deriva contra el estado V100 y, si coincide, flyway baseline -baselineVersion=100
--     en una tabla de historial nueva (la heredada flyway_schema_history tiene checksums que ya no coinciden).
-- =====================================================================================================================

--
-- PostgreSQL database dump
--

--
-- Name: uuid-ossp; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA public;

--
-- Name: EXTENSION "uuid-ossp"; Type: COMMENT; Schema: -; Owner: -
--

--
-- Name: assemblies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.assemblies (
    assembly_date date NOT NULL,
    quorum_percentage numeric(6,2) NOT NULL,
    registered_attendees integer NOT NULL,
    start_time time(6) without time zone NOT NULL,
    total_properties integer NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    modality character varying(30) NOT NULL,
    status character varying(30) NOT NULL,
    type character varying(30) NOT NULL,
    title character varying(200) NOT NULL,
    description text,
    CONSTRAINT assemblies_status_check CHECK (((status)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'IN_PROGRESS'::character varying, 'ENDED'::character varying])::text[])))
);

--
-- Name: assembly_attendees; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.assembly_attendees (
    coefficient numeric(8,6) NOT NULL,
    is_power_of_attorney boolean NOT NULL,
    checked_in_at timestamp(6) with time zone NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    assembly_id uuid NOT NULL,
    id uuid NOT NULL,
    property_id uuid NOT NULL,
    resident_id uuid,
    tenant_id uuid NOT NULL,
    representative_name character varying(150)
);

--
-- Name: attendance_records; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.attendance_records (
    record_date date NOT NULL,
    scheduled_check_in time(6) without time zone,
    scheduled_check_out time(6) without time zone,
    check_in timestamp(6) with time zone,
    check_out timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    shift_id uuid,
    staff_member_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    status character varying(30) NOT NULL,
    gate character varying(100),
    observations text,
    CONSTRAINT attendance_records_status_check CHECK (((status)::text = ANY ((ARRAY['PRESENT'::character varying, 'LATE'::character varying, 'ABSENT'::character varying, 'INCOMPLETE'::character varying])::text[])))
);

--
-- Name: billing_configs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.billing_configs (
    base_budget_amount numeric(14,2) NOT NULL,
    discount_day_of_month integer NOT NULL,
    discount_early_payment_percentage numeric(5,2) NOT NULL,
    due_day_of_month integer NOT NULL,
    fixed_fee_amount numeric(12,2) NOT NULL,
    late_fee_percentage numeric(5,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    calculation_method character varying(30) NOT NULL
);

--
-- Name: common_zones; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.common_zones (
    capacity integer NOT NULL,
    cost_per_hour numeric(12,2),
    reservation_required boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    close_time character varying(10),
    open_time character varying(10),
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    status character varying(30) NOT NULL,
    name character varying(100) NOT NULL,
    description text,
    disabled_days character varying(255),
    disabled_message character varying(255),
    rules text,
    CONSTRAINT common_zones_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))
);

--
-- Name: communications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.communications (
    created_at timestamp(6) with time zone NOT NULL,
    published_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    author_id uuid,
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    status character varying(30) NOT NULL,
    audience character varying(50) NOT NULL,
    category character varying(50) NOT NULL,
    author_name character varying(150) NOT NULL,
    title character varying(200) NOT NULL,
    content text NOT NULL,
    CONSTRAINT communications_status_check CHECK (((status)::text = ANY ((ARRAY['PUBLISHED'::character varying, 'DRAFT'::character varying, 'ARCHIVED'::character varying])::text[])))
);

--
-- Name: community_documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.community_documents (
    downloads_count integer NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    uploaded_by_user_id uuid,
    file_type character varying(20) NOT NULL,
    category character varying(50) NOT NULL,
    file_size character varying(50) NOT NULL,
    title character varying(200) NOT NULL,
    file_url character varying(500) NOT NULL,
    storage_key character varying(500),
    description text
);

--
-- Name: concierge_chat_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.concierge_chat_messages (
    is_read boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    read_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    property_id uuid NOT NULL,
    sender_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    message_type character varying(30) NOT NULL,
    sender_role character varying(50) NOT NULL,
    sender_name character varying(150) NOT NULL,
    attachment_url character varying(500),
    content text NOT NULL
);

--
-- Name: feature_flags; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.feature_flags (
    is_beta boolean NOT NULL,
    is_global_enabled boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    category character varying(50) NOT NULL,
    flag_key character varying(100) NOT NULL,
    name character varying(150) NOT NULL,
    description text,
    enabled_by_plan character varying(255) NOT NULL
);

--
-- Name: intercom_calls; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.intercom_calls (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    tenant_id uuid NOT NULL,
    accepted_at timestamp(6) with time zone,
    caller_name character varying(150) NOT NULL,
    caller_participant_id character varying(150),
    created_by_user_id uuid,
    duration_seconds integer,
    end_reason character varying(100),
    ended_at timestamp(6) with time zone,
    property_unit character varying(100) NOT NULL,
    realtime_meeting_id character varying(150) NOT NULL,
    recipient_name character varying(150) NOT NULL,
    recipient_participant_id character varying(150),
    ringing_at timestamp(6) with time zone,
    status character varying(30) NOT NULL,
    technical_failure_reason character varying(255),
    caller_user_id uuid NOT NULL,
    property_id uuid NOT NULL,
    recipient_resident_id uuid NOT NULL,
    recipient_user_id uuid,
    connected_at timestamp(6) with time zone,
    caller_connected_at timestamp(6) with time zone,
    recipient_connected_at timestamp(6) with time zone,
    CONSTRAINT intercom_calls_status_check CHECK (((status)::text = ANY ((ARRAY['INITIATING'::character varying, 'RINGING'::character varying, 'ACCEPTED'::character varying, 'REJECTED'::character varying, 'MISSED'::character varying, 'ENDED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[])))
);

--
-- Name: marketplace_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.marketplace_config (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    listing_duration_days integer NOT NULL,
    mode character varying(30) NOT NULL,
    tenant_id uuid NOT NULL,
    CONSTRAINT marketplace_config_mode_check CHECK (((mode)::text = ANY ((ARRAY['AUTO_APPROVE'::character varying, 'REQUIRE_APPROVAL'::character varying])::text[])))
);

--
-- Name: marketplace_listings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.marketplace_listings (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    category character varying(100) NOT NULL,
    contact_phone character varying(50),
    description text NOT NULL,
    expires_at timestamp(6) with time zone,
    image_keys text,
    price numeric(12,2),
    price_type character varying(20) NOT NULL,
    property_unit character varying(200),
    published_at timestamp(6) with time zone,
    rejection_reason text,
    status character varying(30) NOT NULL,
    tenant_id uuid NOT NULL,
    title character varying(150) NOT NULL,
    type character varying(20) NOT NULL,
    created_by_user_id uuid NOT NULL,
    terms_accepted boolean NOT NULL,
    hidden_at timestamp(6) with time zone,
    hidden_reason text,
    CONSTRAINT marketplace_listings_price_type_check CHECK (((price_type)::text = ANY ((ARRAY['FIXED'::character varying, 'NEGOTIABLE'::character varying, 'CONTACT'::character varying])::text[]))),
    CONSTRAINT marketplace_listings_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING_APPROVAL'::character varying, 'PUBLISHED'::character varying, 'REJECTED'::character varying, 'HIDDEN'::character varying, 'EXPIRED'::character varying])::text[]))),
    CONSTRAINT marketplace_listings_type_check CHECK (((type)::text = ANY ((ARRAY['PRODUCT'::character varying, 'SERVICE'::character varying])::text[])))
);

--
-- Name: marketplace_reports; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.marketplace_reports (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    description text,
    reason character varying(30) NOT NULL,
    reported_by_user_id uuid NOT NULL,
    resolved_at timestamp(6) with time zone,
    resolved_by_user_id uuid,
    status character varying(20) NOT NULL,
    tenant_id uuid NOT NULL,
    listing_id uuid NOT NULL,
    CONSTRAINT marketplace_reports_reason_check CHECK (((reason)::text = ANY ((ARRAY['INAPPROPRIATE'::character varying, 'NOT_ALLOWED'::character varying, 'MISLEADING'::character varying, 'SPAM'::character varying, 'OTHER'::character varying])::text[]))),
    CONSTRAINT marketplace_reports_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'RESOLVED'::character varying, 'DISMISSED'::character varying])::text[])))
);

--
-- Name: memberships; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.memberships (
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    tenant_id uuid,
    user_id uuid NOT NULL,
    status character varying(30) NOT NULL,
    role character varying(50) NOT NULL,
    CONSTRAINT memberships_role_check CHECK (((role)::text = ANY ((ARRAY['SUPER_ADMIN'::character varying, 'PLATFORM_ADMIN'::character varying, 'READ_ONLY'::character varying, 'COMMUNITY_ADMIN'::character varying, 'SECURITY'::character varying, 'STAFF'::character varying, 'RESIDENT'::character varying, 'ACCOUNTANT'::character varying])::text[]))),
    CONSTRAINT memberships_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))
);

--
-- Name: organizations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.organizations (
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    status character varying(30) NOT NULL,
    nit character varying(50) NOT NULL,
    phone character varying(50),
    city character varying(100),
    email character varying(150),
    logo_url character varying(500),
    address character varying(255),
    name character varying(255) NOT NULL,
    CONSTRAINT organizations_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))
);

--
-- Name: packages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.packages (
    created_at timestamp(6) with time zone NOT NULL,
    delivered_at timestamp(6) with time zone,
    received_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    property_id uuid NOT NULL,
    security_guard_id uuid,
    tenant_id uuid NOT NULL,
    status character varying(30) NOT NULL,
    company character varying(100) NOT NULL,
    tracking_number character varying(100),
    delivered_to character varying(150),
    recipient_name character varying(150) NOT NULL,
    observations text,
    CONSTRAINT packages_status_check CHECK (((status)::text = ANY ((ARRAY['RECEIVED'::character varying, 'NOTIFIED'::character varying, 'DELIVERED'::character varying])::text[])))
);

--
-- Name: password_reset_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.password_reset_tokens (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    token character varying(255) NOT NULL,
    used boolean NOT NULL,
    user_id uuid NOT NULL
);

--
-- Name: pets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pets (
    is_dangerous_breed boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    property_id uuid NOT NULL,
    resident_id uuid,
    tenant_id uuid NOT NULL,
    color character varying(30),
    type character varying(30) NOT NULL,
    breed character varying(50),
    name character varying(100) NOT NULL,
    photo_url character varying(500),
    vaccination_card_url character varying(500)
);

--
-- Name: platform_audit_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_audit_events (
    created_at timestamp(6) with time zone NOT NULL,
    "timestamp" timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    tenant_id uuid,
    result character varying(30) NOT NULL,
    ip_address character varying(45) NOT NULL,
    actor_role character varying(50) NOT NULL,
    action character varying(100) NOT NULL,
    actor_email character varying(150),
    actor_name character varying(150) NOT NULL,
    resource character varying(150) NOT NULL,
    tenant_name character varying(150),
    details text
);

--
-- Name: poll_options; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.poll_options (
    option_order integer NOT NULL,
    id uuid NOT NULL,
    poll_id uuid NOT NULL,
    text character varying(200) NOT NULL
);

--
-- Name: poll_votes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.poll_votes (
    coefficient numeric(8,6) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    voted_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    poll_id uuid NOT NULL,
    poll_option_id uuid NOT NULL,
    property_id uuid NOT NULL,
    resident_id uuid,
    tenant_id uuid NOT NULL
);

--
-- Name: polls; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.polls (
    duration_minutes integer,
    show_results_to_residents boolean NOT NULL,
    closed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    opened_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    assembly_id uuid NOT NULL,
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    status character varying(30) NOT NULL,
    type character varying(30) NOT NULL,
    question character varying(300) NOT NULL,
    CONSTRAINT polls_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SCHEDULED'::character varying, 'LIVE'::character varying, 'CLOSED'::character varying])::text[])))
);

--
-- Name: pqrs_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pqrs_requests (
    closed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    resolved_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    assigned_to_user_id uuid,
    created_by_user_id uuid,
    id uuid NOT NULL,
    property_id uuid NOT NULL,
    resident_id uuid,
    tenant_id uuid NOT NULL,
    priority character varying(30) NOT NULL,
    status character varying(30) NOT NULL,
    category character varying(50) NOT NULL,
    title character varying(200) NOT NULL,
    description text NOT NULL,
    response_notes text,
    CONSTRAINT pqrs_requests_status_check CHECK (((status)::text = ANY ((ARRAY['NEW'::character varying, 'IN_PROGRESS'::character varying, 'RESOLVED'::character varying, 'CLOSED'::character varying])::text[])))
);

--
-- Name: pqrs_timeline_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pqrs_timeline_events (
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    pqrs_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid,
    status_changed_to character varying(30),
    author_role character varying(50) NOT NULL,
    author_name character varying(150) NOT NULL,
    comment text NOT NULL,
    CONSTRAINT pqrs_timeline_events_status_changed_to_check CHECK (((status_changed_to)::text = ANY ((ARRAY['NEW'::character varying, 'IN_PROGRESS'::character varying, 'RESOLVED'::character varying, 'CLOSED'::character varying])::text[])))
);

--
-- Name: properties; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.properties (
    area numeric(10,2),
    bathrooms integer,
    bedrooms integer,
    coefficient numeric(8,6),
    pets_count integer,
    vehicles_count integer,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    structure_id uuid,
    tenant_id uuid NOT NULL,
    level character varying(20),
    status character varying(30) NOT NULL,
    number character varying(50) NOT NULL,
    type character varying(50) NOT NULL,
    name character varying(100) NOT NULL,
    path character varying(200),
    CONSTRAINT properties_status_check CHECK (((status)::text = ANY ((ARRAY['VACANT'::character varying, 'OCCUPIED'::character varying, 'MAINTENANCE'::character varying, 'INACTIVE'::character varying])::text[])))
);

--
-- Name: property_invoices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.property_invoices (
    balance_due numeric(12,2) NOT NULL,
    base_amount numeric(12,2) NOT NULL,
    discount_amount numeric(12,2) NOT NULL,
    discount_until_date date,
    due_date date NOT NULL,
    issued_date date NOT NULL,
    late_fee_amount numeric(12,2) NOT NULL,
    previous_balance numeric(12,2) NOT NULL,
    total_amount numeric(12,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    property_id uuid NOT NULL,
    resident_id uuid,
    tenant_id uuid NOT NULL,
    status character varying(30) NOT NULL,
    invoice_number character varying(100) NOT NULL,
    period character varying(100) NOT NULL,
    concept character varying(200) NOT NULL,
    CONSTRAINT property_invoices_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PAID'::character varying, 'PARTIALLY_PAID'::character varying, 'OVERDUE'::character varying, 'CANCELLED'::character varying])::text[])))
);

--
-- Name: property_payments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.property_payments (
    amount_paid numeric(12,2) NOT NULL,
    payment_date date NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    invoice_id uuid NOT NULL,
    property_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    status character varying(30) NOT NULL,
    payment_method character varying(50) NOT NULL,
    reference_number character varying(100),
    receipt_url character varying(500),
    notes text,
    CONSTRAINT property_payments_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING_VERIFICATION'::character varying, 'CONFIRMED'::character varying, 'REJECTED'::character varying])::text[])))
);

--
-- Name: push_subscriptions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.push_subscriptions (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    tenant_id uuid NOT NULL,
    active boolean NOT NULL,
    auth character varying(255) NOT NULL,
    device_name character varying(150),
    endpoint text NOT NULL,
    last_used_at timestamp(6) with time zone,
    p256dh text NOT NULL,
    user_id uuid NOT NULL
);

--
-- Name: realtime_notifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.realtime_notifications (
    is_read boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    read_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    property_id uuid,
    tenant_id uuid NOT NULL,
    user_id uuid,
    type character varying(50) NOT NULL,
    title character varying(200) NOT NULL,
    data_payload text,
    message text NOT NULL
);

--
-- Name: refresh_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.refresh_tokens (
    revoked boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    token character varying(255) NOT NULL
);

--
-- Name: reservations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reservations (
    end_time time(6) without time zone NOT NULL,
    guests_count integer,
    reservation_date date NOT NULL,
    start_time time(6) without time zone NOT NULL,
    total_cost numeric(12,2),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    common_zone_id uuid NOT NULL,
    created_by_user_id uuid,
    id uuid NOT NULL,
    property_id uuid NOT NULL,
    resident_id uuid,
    tenant_id uuid NOT NULL,
    status character varying(30) NOT NULL,
    admin_notes text,
    observations text,
    CONSTRAINT reservations_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'CONFIRMED'::character varying, 'CANCELLED'::character varying, 'REJECTED'::character varying, 'COMPLETED'::character varying])::text[])))
);

--
-- Name: resident_invitations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.resident_invitations (
    max_uses integer NOT NULL,
    used_count integer NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    revoked_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    used_at timestamp(6) with time zone,
    id uuid NOT NULL,
    inviter_user_id uuid,
    property_id uuid NOT NULL,
    resident_id uuid,
    tenant_id uuid NOT NULL,
    status character varying(30) NOT NULL,
    invitation_type character varying(50) NOT NULL,
    invitee_phone character varying(50),
    token_hash character varying(64) NOT NULL,
    invitee_email character varying(150),
    invitee_name character varying(150),
    additional_property_ids character varying(2000),
    CONSTRAINT resident_invitations_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'USED'::character varying, 'EXPIRED'::character varying, 'REVOKED'::character varying])::text[])))
);

--
-- Name: residents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.residents (
    is_main_contact boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    ended_at timestamp(6) with time zone,
    registered_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    ended_by_id uuid,
    id uuid NOT NULL,
    parent_resident_id uuid,
    property_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid,
    document_type character varying(20),
    status character varying(30) NOT NULL,
    type character varying(30) NOT NULL,
    document_id character varying(50),
    emergency_contact_phone character varying(50),
    phone character varying(50),
    email character varying(150),
    emergency_contact_name character varying(150),
    name character varying(150) NOT NULL,
    end_reason character varying(255),
    granted_by_user_id uuid,
    CONSTRAINT residents_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))
);

--
-- Name: saas_invoices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.saas_invoices (
    amount_cop numeric(12,2) NOT NULL,
    tax_cop numeric(12,2) NOT NULL,
    total_cop numeric(12,2) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    due_at timestamp(6) with time zone NOT NULL,
    issued_at timestamp(6) with time zone NOT NULL,
    paid_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    subscription_id uuid,
    tenant_id uuid NOT NULL,
    status character varying(30) NOT NULL,
    invoice_number character varying(100) NOT NULL,
    period character varying(100) NOT NULL,
    payment_reference character varying(150),
    CONSTRAINT saas_invoices_status_check CHECK (((status)::text = ANY ((ARRAY['PAID'::character varying, 'PENDING'::character varying, 'OVERDUE'::character varying, 'VOIDED'::character varying])::text[])))
);

--
-- Name: saas_plans; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.saas_plans (
    community_share_per_property numeric(12,2) NOT NULL,
    currency character varying(3) NOT NULL,
    gross_price_per_property numeric(12,2) NOT NULL,
    max_admin_users integer,
    max_documents integer,
    max_porters integer,
    max_properties integer,
    max_reservations integer,
    max_staff_users integer,
    platform_price_per_property numeric(12,2) NOT NULL,
    popular boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    max_storage_bytes bigint,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    billing_period character varying(30) NOT NULL,
    status character varying(30) NOT NULL,
    tier character varying(50) NOT NULL,
    name character varying(100) NOT NULL,
    features text,
    tagline character varying(255),
    CONSTRAINT saas_plans_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))
);

--
-- Name: saas_subscriptions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.saas_subscriptions (
    auto_renew boolean NOT NULL,
    cancelled_at date,
    community_share_per_property numeric(12,2) NOT NULL,
    currency character varying(3) NOT NULL,
    current_period_end date,
    current_period_start date,
    expires_at date NOT NULL,
    gross_price_per_property numeric(12,2) NOT NULL,
    platform_price_per_property numeric(12,2) NOT NULL,
    start_date date NOT NULL,
    trial_ends_at date,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    plan_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    billing_cycle character varying(30) NOT NULL,
    status character varying(30) NOT NULL,
    payment_method character varying(50),
    CONSTRAINT saas_subscriptions_status_check CHECK (((status)::text = ANY ((ARRAY['TRIAL'::character varying, 'ACTIVE'::character varying, 'PAST_DUE'::character varying, 'SUSPENDED'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])))
);

--
-- Name: security_panic_alerts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.security_panic_alerts (
    created_at timestamp(6) with time zone NOT NULL,
    resolved_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    acknowledged_by uuid,
    id uuid NOT NULL,
    property_id uuid NOT NULL,
    resident_id uuid,
    resolved_by uuid,
    tenant_id uuid NOT NULL,
    status character varying(30) NOT NULL,
    alert_type character varying(50) NOT NULL,
    notes text,
    resolution_notes text,
    acknowledged_at timestamp(6) with time zone,
    CONSTRAINT security_panic_alerts_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'ACKNOWLEDGED'::character varying, 'RESOLVED'::character varying, 'FALSE_ALARM'::character varying])::text[])))
);

--
-- Name: shifts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.shifts (
    end_time time(6) without time zone NOT NULL,
    shift_date date NOT NULL,
    start_time time(6) without time zone NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    staff_member_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    shift_type character varying(30) NOT NULL,
    status character varying(30) NOT NULL,
    gate character varying(100),
    shift_name character varying(100) NOT NULL,
    notes text,
    CONSTRAINT shifts_status_check CHECK (((status)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'ABSENT'::character varying, 'CANCELLED'::character varying])::text[])))
);

--
-- Name: staff_members; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.staff_members (
    start_date date NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid,
    document_type character varying(20) NOT NULL,
    status character varying(30) NOT NULL,
    area character varying(50) NOT NULL,
    contract_type character varying(50) NOT NULL,
    document_number character varying(50) NOT NULL,
    phone character varying(50),
    assigned_gate character varying(100),
    first_name character varying(100) NOT NULL,
    last_name character varying(100) NOT NULL,
    "position" character varying(100) NOT NULL,
    company character varying(150),
    email character varying(150),
    avatar_url character varying(500),
    observations text,
    CONSTRAINT staff_members_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))
);

--
-- Name: structures; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.structures (
    total_levels integer NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    type character varying(50) NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(255)
);

--
-- Name: tenant_feature_overrides; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_feature_overrides (
    enabled boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    feature_flag_id uuid NOT NULL,
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    reason character varying(255)
);

--
-- Name: tenants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenants (
    contracted_properties integer,
    storage_limit_mb integer NOT NULL,
    storage_used_mb integer NOT NULL,
    primary_color character varying(7),
    secondary_color character varying(7),
    created_at timestamp(6) with time zone NOT NULL,
    trial_ends_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    organization_id uuid,
    plan_id uuid,
    status character varying(30) NOT NULL,
    nit character varying(50),
    phone character varying(50),
    city character varying(100),
    plan_name character varying(100),
    slug character varying(100) NOT NULL,
    subdomain character varying(100) NOT NULL,
    contact_email character varying(150),
    logo_url character varying(500),
    active_features character varying(1000),
    address character varying(255),
    name character varying(255) NOT NULL,
    suspended_reason character varying(255),
    CONSTRAINT tenants_status_check CHECK (((status)::text = ANY ((ARRAY['TRIAL'::character varying, 'ACTIVE'::character varying, 'PAST_DUE'::character varying, 'SUSPENDED'::character varying, 'CANCELLED'::character varying])::text[])))
);

--
-- Name: user_legal_consents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_legal_consents (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    accepted_at timestamp(6) with time zone NOT NULL,
    document_type character varying(50) NOT NULL,
    document_version character varying(20) NOT NULL,
    ip_address character varying(45),
    tenant_id uuid,
    user_agent character varying(500),
    user_id uuid NOT NULL,
    CONSTRAINT user_legal_consents_document_type_check CHECK (((document_type)::text = ANY ((ARRAY['TERMS_OF_SERVICE'::character varying, 'PRIVACY_POLICY'::character varying])::text[])))
);

--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    has_completed_onboarding boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    status character varying(30) NOT NULL,
    phone character varying(50),
    email character varying(150) NOT NULL,
    full_name character varying(150) NOT NULL,
    avatar_url character varying(255),
    password_hash character varying(255) NOT NULL,
    CONSTRAINT users_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))
);

--
-- Name: vehicles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.vehicles (
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    property_id uuid NOT NULL,
    resident_id uuid,
    tenant_id uuid NOT NULL,
    plate character varying(20) NOT NULL,
    color character varying(30),
    type character varying(30) NOT NULL,
    brand character varying(50),
    model character varying(50),
    parking_spot character varying(50)
);

--
-- Name: visitor_day_schedules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.visitor_day_schedules (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    day_of_week character varying(10) NOT NULL,
    enabled boolean NOT NULL,
    end_time time(6) without time zone,
    start_time time(6) without time zone,
    visitor_settings_id uuid NOT NULL,
    CONSTRAINT visitor_day_schedules_day_of_week_check CHECK (((day_of_week)::text = ANY ((ARRAY['MONDAY'::character varying, 'TUESDAY'::character varying, 'WEDNESDAY'::character varying, 'THURSDAY'::character varying, 'FRIDAY'::character varying, 'SATURDAY'::character varying, 'SUNDAY'::character varying])::text[])))
);

--
-- Name: visitor_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.visitor_settings (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    mode character varying(20) NOT NULL,
    tenant_id uuid NOT NULL,
    CONSTRAINT visitor_settings_mode_check CHECK (((mode)::text = ANY ((ARRAY['HOURS_24'::character varying, 'CUSTOM'::character varying])::text[])))
);

--
-- Name: visitors; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.visitors (
    created_at timestamp(6) with time zone NOT NULL,
    entered_at timestamp(6) with time zone,
    exited_at timestamp(6) with time zone,
    expected_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    entered_by uuid,
    exited_by uuid,
    id uuid NOT NULL,
    property_id uuid NOT NULL,
    resident_id uuid,
    tenant_id uuid NOT NULL,
    vehicle_plate character varying(20),
    status character varying(30) NOT NULL,
    document_id character varying(50) NOT NULL,
    group_id character varying(100),
    group_name character varying(150),
    name character varying(150) NOT NULL,
    visit_purpose character varying(150),
    observations text,
    qr_code character varying(255),
    created_by_user_id uuid,
    CONSTRAINT visitors_status_check CHECK (((status)::text = ANY (ARRAY[('AUTHORIZED'::character varying)::text, ('PENDING'::character varying)::text, ('ENTERED'::character varying)::text, ('EXITED'::character varying)::text, ('EXPIRED'::character varying)::text, ('CANCELLED'::character varying)::text])))
);

--
-- Name: voting_delegations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.voting_delegations (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    tenant_id uuid NOT NULL,
    granted_at timestamp(6) with time zone NOT NULL,
    revoked_at timestamp(6) with time zone,
    status character varying(20) NOT NULL,
    delegate_user_id uuid NOT NULL,
    owner_user_id uuid NOT NULL,
    property_id uuid NOT NULL,
    revoked_by_user_id uuid,
    CONSTRAINT voting_delegations_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REVOKED'::character varying, 'INVALIDATED'::character varying])::text[])))
);

--
-- Name: assemblies assemblies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.assemblies
    ADD CONSTRAINT assemblies_pkey PRIMARY KEY (id);

--
-- Name: assembly_attendees assembly_attendees_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.assembly_attendees
    ADD CONSTRAINT assembly_attendees_pkey PRIMARY KEY (id);

--
-- Name: attendance_records attendance_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.attendance_records
    ADD CONSTRAINT attendance_records_pkey PRIMARY KEY (id);

--
-- Name: billing_configs billing_configs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_configs
    ADD CONSTRAINT billing_configs_pkey PRIMARY KEY (id);

--
-- Name: common_zones common_zones_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.common_zones
    ADD CONSTRAINT common_zones_pkey PRIMARY KEY (id);

--
-- Name: communications communications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.communications
    ADD CONSTRAINT communications_pkey PRIMARY KEY (id);

--
-- Name: community_documents community_documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.community_documents
    ADD CONSTRAINT community_documents_pkey PRIMARY KEY (id);

--
-- Name: concierge_chat_messages concierge_chat_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.concierge_chat_messages
    ADD CONSTRAINT concierge_chat_messages_pkey PRIMARY KEY (id);

--
-- Name: feature_flags feature_flags_flag_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_flags
    ADD CONSTRAINT feature_flags_flag_key_key UNIQUE (flag_key);

--
-- Name: feature_flags feature_flags_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feature_flags
    ADD CONSTRAINT feature_flags_pkey PRIMARY KEY (id);

--
-- Name: intercom_calls intercom_calls_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.intercom_calls
    ADD CONSTRAINT intercom_calls_pkey PRIMARY KEY (id);

--
-- Name: marketplace_config marketplace_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.marketplace_config
    ADD CONSTRAINT marketplace_config_pkey PRIMARY KEY (id);

--
-- Name: marketplace_listings marketplace_listings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.marketplace_listings
    ADD CONSTRAINT marketplace_listings_pkey PRIMARY KEY (id);

--
-- Name: marketplace_reports marketplace_reports_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.marketplace_reports
    ADD CONSTRAINT marketplace_reports_pkey PRIMARY KEY (id);

--
-- Name: memberships memberships_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memberships
    ADD CONSTRAINT memberships_pkey PRIMARY KEY (id);

--
-- Name: organizations organizations_nit_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organizations
    ADD CONSTRAINT organizations_nit_key UNIQUE (nit);

--
-- Name: organizations organizations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organizations
    ADD CONSTRAINT organizations_pkey PRIMARY KEY (id);

--
-- Name: packages packages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.packages
    ADD CONSTRAINT packages_pkey PRIMARY KEY (id);

--
-- Name: password_reset_tokens password_reset_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_pkey PRIMARY KEY (id);

--
-- Name: pets pets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pets
    ADD CONSTRAINT pets_pkey PRIMARY KEY (id);

--
-- Name: platform_audit_events platform_audit_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_audit_events
    ADD CONSTRAINT platform_audit_events_pkey PRIMARY KEY (id);

--
-- Name: poll_options poll_options_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.poll_options
    ADD CONSTRAINT poll_options_pkey PRIMARY KEY (id);

--
-- Name: poll_votes poll_votes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.poll_votes
    ADD CONSTRAINT poll_votes_pkey PRIMARY KEY (id);

--
-- Name: polls polls_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.polls
    ADD CONSTRAINT polls_pkey PRIMARY KEY (id);

--
-- Name: pqrs_requests pqrs_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pqrs_requests
    ADD CONSTRAINT pqrs_requests_pkey PRIMARY KEY (id);

--
-- Name: pqrs_timeline_events pqrs_timeline_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pqrs_timeline_events
    ADD CONSTRAINT pqrs_timeline_events_pkey PRIMARY KEY (id);

--
-- Name: properties properties_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.properties
    ADD CONSTRAINT properties_pkey PRIMARY KEY (id);

--
-- Name: property_invoices property_invoices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.property_invoices
    ADD CONSTRAINT property_invoices_pkey PRIMARY KEY (id);

--
-- Name: property_payments property_payments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.property_payments
    ADD CONSTRAINT property_payments_pkey PRIMARY KEY (id);

--
-- Name: push_subscriptions push_subscriptions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.push_subscriptions
    ADD CONSTRAINT push_subscriptions_pkey PRIMARY KEY (id);

--
-- Name: realtime_notifications realtime_notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.realtime_notifications
    ADD CONSTRAINT realtime_notifications_pkey PRIMARY KEY (id);

--
-- Name: refresh_tokens refresh_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);

--
-- Name: refresh_tokens refresh_tokens_token_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_token_key UNIQUE (token);

--
-- Name: reservations reservations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT reservations_pkey PRIMARY KEY (id);

--
-- Name: resident_invitations resident_invitations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resident_invitations
    ADD CONSTRAINT resident_invitations_pkey PRIMARY KEY (id);

--
-- Name: resident_invitations resident_invitations_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resident_invitations
    ADD CONSTRAINT resident_invitations_token_hash_key UNIQUE (token_hash);

--
-- Name: residents residents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.residents
    ADD CONSTRAINT residents_pkey PRIMARY KEY (id);

--
-- Name: saas_invoices saas_invoices_invoice_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saas_invoices
    ADD CONSTRAINT saas_invoices_invoice_number_key UNIQUE (invoice_number);

--
-- Name: saas_invoices saas_invoices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saas_invoices
    ADD CONSTRAINT saas_invoices_pkey PRIMARY KEY (id);

--
-- Name: saas_plans saas_plans_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saas_plans
    ADD CONSTRAINT saas_plans_pkey PRIMARY KEY (id);

--
-- Name: saas_plans saas_plans_tier_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saas_plans
    ADD CONSTRAINT saas_plans_tier_key UNIQUE (tier);

--
-- Name: saas_subscriptions saas_subscriptions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saas_subscriptions
    ADD CONSTRAINT saas_subscriptions_pkey PRIMARY KEY (id);

--
-- Name: security_panic_alerts security_panic_alerts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.security_panic_alerts
    ADD CONSTRAINT security_panic_alerts_pkey PRIMARY KEY (id);

--
-- Name: shifts shifts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shifts
    ADD CONSTRAINT shifts_pkey PRIMARY KEY (id);

--
-- Name: staff_members staff_members_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.staff_members
    ADD CONSTRAINT staff_members_pkey PRIMARY KEY (id);

--
-- Name: structures structures_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.structures
    ADD CONSTRAINT structures_pkey PRIMARY KEY (id);

--
-- Name: tenant_feature_overrides tenant_feature_overrides_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_feature_overrides
    ADD CONSTRAINT tenant_feature_overrides_pkey PRIMARY KEY (id);

--
-- Name: tenants tenants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT tenants_pkey PRIMARY KEY (id);

--
-- Name: tenants tenants_slug_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT tenants_slug_key UNIQUE (slug);

--
-- Name: tenants tenants_subdomain_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT tenants_subdomain_key UNIQUE (subdomain);

--
-- Name: password_reset_tokens uk71lqwbwtklmljk3qlsugr1mig; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_tokens
    ADD CONSTRAINT uk71lqwbwtklmljk3qlsugr1mig UNIQUE (token);

--
-- Name: push_subscriptions uk_push_subscriptions_endpoint; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.push_subscriptions
    ADD CONSTRAINT uk_push_subscriptions_endpoint UNIQUE (endpoint);

--
-- Name: visitor_settings uke87joy740tsxvon43hrpj65be; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.visitor_settings
    ADD CONSTRAINT uke87joy740tsxvon43hrpj65be UNIQUE (tenant_id);

--
-- Name: visitor_day_schedules ukiqqu98vlioc7oue5a0afve4n1; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.visitor_day_schedules
    ADD CONSTRAINT ukiqqu98vlioc7oue5a0afve4n1 UNIQUE (visitor_settings_id, day_of_week);

--
-- Name: marketplace_config uklflyyvvna6wpthxws35or0m2y; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.marketplace_config
    ADD CONSTRAINT uklflyyvvna6wpthxws35or0m2y UNIQUE (tenant_id);

--
-- Name: assembly_attendees uq_assembly_property; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.assembly_attendees
    ADD CONSTRAINT uq_assembly_property UNIQUE (assembly_id, property_id);

--
-- Name: property_invoices uq_property_invoice_period; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.property_invoices
    ADD CONSTRAINT uq_property_invoice_period UNIQUE (property_id, period);

--
-- Name: poll_votes uq_property_poll_option; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.poll_votes
    ADD CONSTRAINT uq_property_poll_option UNIQUE (poll_id, property_id, poll_option_id);

--
-- Name: billing_configs uq_tenant_billing_config; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.billing_configs
    ADD CONSTRAINT uq_tenant_billing_config UNIQUE (tenant_id);

--
-- Name: tenant_feature_overrides uq_tenant_feature_override; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_feature_overrides
    ADD CONSTRAINT uq_tenant_feature_override UNIQUE (tenant_id, feature_flag_id);

--
-- Name: properties uq_tenant_property_number; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.properties
    ADD CONSTRAINT uq_tenant_property_number UNIQUE (tenant_id, number);

--
-- Name: staff_members uq_tenant_staff_document; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.staff_members
    ADD CONSTRAINT uq_tenant_staff_document UNIQUE (tenant_id, document_number);

--
-- Name: structures uq_tenant_structure_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.structures
    ADD CONSTRAINT uq_tenant_structure_name UNIQUE (tenant_id, name);

--
-- Name: saas_subscriptions uq_tenant_subscription; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saas_subscriptions
    ADD CONSTRAINT uq_tenant_subscription UNIQUE (tenant_id);

--
-- Name: vehicles uq_tenant_vehicle_plate; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vehicles
    ADD CONSTRAINT uq_tenant_vehicle_plate UNIQUE (tenant_id, plate);

--
-- Name: common_zones uq_tenant_zone_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.common_zones
    ADD CONSTRAINT uq_tenant_zone_name UNIQUE (tenant_id, name);

--
-- Name: memberships uq_user_tenant_membership; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memberships
    ADD CONSTRAINT uq_user_tenant_membership UNIQUE (user_id, tenant_id);

--
-- Name: user_legal_consents user_legal_consents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_legal_consents
    ADD CONSTRAINT user_legal_consents_pkey PRIMARY KEY (id);

--
-- Name: users users_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);

--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

--
-- Name: vehicles vehicles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vehicles
    ADD CONSTRAINT vehicles_pkey PRIMARY KEY (id);

--
-- Name: visitor_day_schedules visitor_day_schedules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.visitor_day_schedules
    ADD CONSTRAINT visitor_day_schedules_pkey PRIMARY KEY (id);

--
-- Name: visitor_settings visitor_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.visitor_settings
    ADD CONSTRAINT visitor_settings_pkey PRIMARY KEY (id);

--
-- Name: visitors visitors_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.visitors
    ADD CONSTRAINT visitors_pkey PRIMARY KEY (id);

--
-- Name: voting_delegations voting_delegations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.voting_delegations
    ADD CONSTRAINT voting_delegations_pkey PRIMARY KEY (id);

--
-- Name: idx_push_sub_tenant_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_push_sub_tenant_active ON public.push_subscriptions USING btree (tenant_id, active);

--
-- Name: idx_push_sub_user_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_push_sub_user_tenant ON public.push_subscriptions USING btree (user_id, tenant_id, active);

--
-- Name: uq_active_arrendatario_per_property; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_active_arrendatario_per_property ON public.residents USING btree (property_id) WHERE (((type)::text = 'Arrendatario'::text) AND ((status)::text = 'ACTIVE'::text));

--
-- Name: uq_active_non_owner_residence_per_email; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_active_non_owner_residence_per_email ON public.residents USING btree (lower((email)::text)) WHERE ((email IS NOT NULL) AND ((status)::text = 'ACTIVE'::text) AND ((type)::text = ANY ((ARRAY['Residente'::character varying, 'Arrendatario'::character varying])::text[])));

--
-- Name: uq_active_non_owner_residence_per_user; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_active_non_owner_residence_per_user ON public.residents USING btree (user_id) WHERE ((user_id IS NOT NULL) AND ((status)::text = 'ACTIVE'::text) AND ((type)::text = ANY ((ARRAY['Residente'::character varying, 'Arrendatario'::character varying])::text[])));

--
-- Name: uq_pending_arrendatario_invitation; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_pending_arrendatario_invitation ON public.resident_invitations USING btree (property_id) WHERE (((invitation_type)::text = 'Arrendatario'::text) AND ((status)::text = 'PENDING'::text));

--
-- Name: poll_options fk1baxdjoxricfu0grc0j6821f7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.poll_options
    ADD CONSTRAINT fk1baxdjoxricfu0grc0j6821f7 FOREIGN KEY (poll_id) REFERENCES public.polls(id);

--
-- Name: security_panic_alerts fk1h0ky1fuuu9q3wvyeiloim7l; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.security_panic_alerts
    ADD CONSTRAINT fk1h0ky1fuuu9q3wvyeiloim7l FOREIGN KEY (resolved_by) REFERENCES public.users(id);

--
-- Name: refresh_tokens fk1lih5y2npsf8u5o3vhdb9y0os; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT fk1lih5y2npsf8u5o3vhdb9y0os FOREIGN KEY (user_id) REFERENCES public.users(id);

--
-- Name: pqrs_requests fk1vf7y5ne4nkjcnpjfakdvujmx; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pqrs_requests
    ADD CONSTRAINT fk1vf7y5ne4nkjcnpjfakdvujmx FOREIGN KEY (assigned_to_user_id) REFERENCES public.users(id);

--
-- Name: concierge_chat_messages fk1x7urrh08dux1u6nn7bgtbus5; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.concierge_chat_messages
    ADD CONSTRAINT fk1x7urrh08dux1u6nn7bgtbus5 FOREIGN KEY (sender_id) REFERENCES public.users(id);

--
-- Name: properties fk2o2enmi6i852g93n0vsusrrxl; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.properties
    ADD CONSTRAINT fk2o2enmi6i852g93n0vsusrrxl FOREIGN KEY (structure_id) REFERENCES public.structures(id);

--
-- Name: intercom_calls fk2p9t8robu445efgair4r5075t; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.intercom_calls
    ADD CONSTRAINT fk2p9t8robu445efgair4r5075t FOREIGN KEY (caller_user_id) REFERENCES public.users(id);

--
-- Name: marketplace_reports fk323yyhi93shu0ebm3ib4ueths; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.marketplace_reports
    ADD CONSTRAINT fk323yyhi93shu0ebm3ib4ueths FOREIGN KEY (listing_id) REFERENCES public.marketplace_listings(id);

--
-- Name: voting_delegations fk376m7axolipvw9xurcgw2ndu8; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.voting_delegations
    ADD CONSTRAINT fk376m7axolipvw9xurcgw2ndu8 FOREIGN KEY (owner_user_id) REFERENCES public.users(id);

--
-- Name: property_invoices fk4u4mi0uaa5xcdfnc1lg6dj8av; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.property_invoices
    ADD CONSTRAINT fk4u4mi0uaa5xcdfnc1lg6dj8av FOREIGN KEY (resident_id) REFERENCES public.residents(id);

--
-- Name: marketplace_listings fk4xbfwgsq4ph0fslsuvnfowa4q; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.marketplace_listings
    ADD CONSTRAINT fk4xbfwgsq4ph0fslsuvnfowa4q FOREIGN KEY (created_by_user_id) REFERENCES public.users(id);

--
-- Name: property_payments fk5wfuce3m4u7kpetcadc2q12c4; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.property_payments
    ADD CONSTRAINT fk5wfuce3m4u7kpetcadc2q12c4 FOREIGN KEY (property_id) REFERENCES public.properties(id);

--
-- Name: attendance_records fk65akvepxwb3o4864pk0vucgcn; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.attendance_records
    ADD CONSTRAINT fk65akvepxwb3o4864pk0vucgcn FOREIGN KEY (shift_id) REFERENCES public.shifts(id);

--
-- Name: saas_subscriptions fk6l2jv82fmrrd4mutt5p40hu8m; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saas_subscriptions
    ADD CONSTRAINT fk6l2jv82fmrrd4mutt5p40hu8m FOREIGN KEY (tenant_id) REFERENCES public.tenants(id);

--
-- Name: voting_delegations fk6tfnju18cxjcd9q9gx9tp05o4; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.voting_delegations
    ADD CONSTRAINT fk6tfnju18cxjcd9q9gx9tp05o4 FOREIGN KEY (property_id) REFERENCES public.properties(id);

--
-- Name: resident_invitations fk7furai9bpdjxs559bg8f1u6l5; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resident_invitations
    ADD CONSTRAINT fk7furai9bpdjxs559bg8f1u6l5 FOREIGN KEY (resident_id) REFERENCES public.residents(id);

--
-- Name: realtime_notifications fk7lpxc81j13d1fjkwcgwwlydio; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.realtime_notifications
    ADD CONSTRAINT fk7lpxc81j13d1fjkwcgwwlydio FOREIGN KEY (user_id) REFERENCES public.users(id);

--
-- Name: residents fk7svjs7e3j6f8kmb6um3dl5i9q; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.residents
    ADD CONSTRAINT fk7svjs7e3j6f8kmb6um3dl5i9q FOREIGN KEY (ended_by_id) REFERENCES public.users(id);

--
-- Name: pqrs_requests fk862xdxdksfmtrj609lhtkndf9; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pqrs_requests
    ADD CONSTRAINT fk862xdxdksfmtrj609lhtkndf9 FOREIGN KEY (resident_id) REFERENCES public.residents(id);

--
-- Name: vehicles fk8b0m3ot8j2jepo14umob3j1n7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vehicles
    ADD CONSTRAINT fk8b0m3ot8j2jepo14umob3j1n7 FOREIGN KEY (resident_id) REFERENCES public.residents(id);

--
-- Name: pqrs_timeline_events fk8ipv5qeqct76xkmogph1i4rkn; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pqrs_timeline_events
    ADD CONSTRAINT fk8ipv5qeqct76xkmogph1i4rkn FOREIGN KEY (pqrs_id) REFERENCES public.pqrs_requests(id);

--
-- Name: visitors fk91r018mftg3llau339j3rg7j9; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.visitors
    ADD CONSTRAINT fk91r018mftg3llau339j3rg7j9 FOREIGN KEY (property_id) REFERENCES public.properties(id);

--
-- Name: staff_members fk92mamalp6m26ktawu5i98pdca; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.staff_members
    ADD CONSTRAINT fk92mamalp6m26ktawu5i98pdca FOREIGN KEY (user_id) REFERENCES public.users(id);

--
-- Name: shifts fk97v6wtor63t5lp02hybcd25ya; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shifts
    ADD CONSTRAINT fk97v6wtor63t5lp02hybcd25ya FOREIGN KEY (staff_member_id) REFERENCES public.staff_members(id);

--
-- Name: reservations fk98xw715j75gn2cb2d3cexd4w7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT fk98xw715j75gn2cb2d3cexd4w7 FOREIGN KEY (common_zone_id) REFERENCES public.common_zones(id);

--
-- Name: concierge_chat_messages fk9bso95dl8rd8qr4b3klbyibd1; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.concierge_chat_messages
    ADD CONSTRAINT fk9bso95dl8rd8qr4b3klbyibd1 FOREIGN KEY (property_id) REFERENCES public.properties(id);

--
-- Name: assembly_attendees fkaqdcnu8ej3w2pdafe9nkfd5vx; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.assembly_attendees
    ADD CONSTRAINT fkaqdcnu8ej3w2pdafe9nkfd5vx FOREIGN KEY (resident_id) REFERENCES public.residents(id);

--
-- Name: pqrs_timeline_events fkbj1ql815kvxjb32cepsesqpl8; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pqrs_timeline_events
    ADD CONSTRAINT fkbj1ql815kvxjb32cepsesqpl8 FOREIGN KEY (user_id) REFERENCES public.users(id);

--
-- Name: visitor_day_schedules fkbs21r9e1obh24vy89hc9asy1v; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.visitor_day_schedules
    ADD CONSTRAINT fkbs21r9e1obh24vy89hc9asy1v FOREIGN KEY (visitor_settings_id) REFERENCES public.visitor_settings(id);

--
-- Name: intercom_calls fkcbta6r90x27w37rx7semk2qhv; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.intercom_calls
    ADD CONSTRAINT fkcbta6r90x27w37rx7semk2qhv FOREIGN KEY (recipient_resident_id) REFERENCES public.residents(id);

--
-- Name: resident_invitations fkcr6nud2hvc1r5d9jvqq9n5cm3; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.resident_invitations
    ADD CONSTRAINT fkcr6nud2hvc1r5d9jvqq9n5cm3 FOREIGN KEY (property_id) REFERENCES public.properties(id);

--
-- Name: residents fkd2jgmhdy89uypsd3ximwjjuls; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.residents
    ADD CONSTRAINT fkd2jgmhdy89uypsd3ximwjjuls FOREIGN KEY (property_id) REFERENCES public.properties(id);

--
-- Name: packages fkda2g26ph9cxhhwkxuq2m827ko; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.packages
    ADD CONSTRAINT fkda2g26ph9cxhhwkxuq2m827ko FOREIGN KEY (property_id) REFERENCES public.properties(id);

--
-- Name: memberships fkdjormybfoo7f4i4d4r803qohb; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memberships
    ADD CONSTRAINT fkdjormybfoo7f4i4d4r803qohb FOREIGN KEY (user_id) REFERENCES public.users(id);

--
-- Name: packages fke47k7q2e0sc14j7momtiuepck; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.packages
    ADD CONSTRAINT fke47k7q2e0sc14j7momtiuepck FOREIGN KEY (security_guard_id) REFERENCES public.users(id);

--
-- Name: poll_votes fke95ke8vf97rs9i45igbq4o9x4; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.poll_votes
    ADD CONSTRAINT fke95ke8vf97rs9i45igbq4o9x4 FOREIGN KEY (poll_option_id) REFERENCES public.poll_options(id);

--
-- Name: communications fkejs33powbpun8a57cfb0foqvk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.communications
    ADD CONSTRAINT fkejs33powbpun8a57cfb0foqvk FOREIGN KEY (author_id) REFERENCES public.users(id);

--
-- Name: voting_delegations fkeum4pbdc7f48c0sksl00vdvnb; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.voting_delegations
    ADD CONSTRAINT fkeum4pbdc7f48c0sksl00vdvnb FOREIGN KEY (delegate_user_id) REFERENCES public.users(id);

--
-- Name: property_payments fkf01no94pxiximwygvwbwn5gvi; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.property_payments
    ADD CONSTRAINT fkf01no94pxiximwygvwbwn5gvi FOREIGN KEY (invoice_id) REFERENCES public.property_invoices(id);

--
-- Name: residents fkg38luqcgm3qqs0s9lgjdfl9ov; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.residents
    ADD CONSTRAINT fkg38luqcgm3qqs0s9lgjdfl9ov FOREIGN KEY (user_id) REFERENCES public.users(id);

--
-- Name: vehicles fkg88e2kpftf3p0n00nv8gqiuf0; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vehicles
    ADD CONSTRAINT fkg88e2kpftf3p0n00nv8gqiuf0 FOREIGN KEY (property_id) REFERENCES public.properties(id);

--
-- Name: pets fkg8bf28siv2huye7t1i9m7equ3; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pets
    ADD CONSTRAINT fkg8bf28siv2huye7t1i9m7equ3 FOREIGN KEY (property_id) REFERENCES public.properties(id);

--
-- Name: residents fkgbmflu7ryakh6fqt7sojw7gtw; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.residents
    ADD CONSTRAINT fkgbmflu7ryakh6fqt7sojw7gtw FOREIGN KEY (parent_resident_id) REFERENCES public.residents(id);

--
-- Name: visitors fkgj299rxaeu7t58ss0sbghyx1b; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.visitors
    ADD CONSTRAINT fkgj299rxaeu7t58ss0sbghyx1b FOREIGN KEY (resident_id) REFERENCES public.residents(id);

--
-- Name: attendance_records fkhvr9rt4l6rpjrhw91aesg784f; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.attendance_records
    ADD CONSTRAINT fkhvr9rt4l6rpjrhw91aesg784f FOREIGN KEY (staff_member_id) REFERENCES public.staff_members(id);

--
-- Name: voting_delegations fkiou0dgq1ld3atj2awxoxy96d; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.voting_delegations
    ADD CONSTRAINT fkiou0dgq1ld3atj2awxoxy96d FOREIGN KEY (revoked_by_user_id) REFERENCES public.users(id);

--
-- Name: security_panic_alerts fkiqncad82yovc19wi1murcgfpb; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.security_panic_alerts
    ADD CONSTRAINT fkiqncad82yovc19wi1murcgfpb FOREIGN KEY (acknowledged_by) REFERENCES public.users(id);

--
-- Name: community_documents fkiyeaoj6gdqt5u4o2340l0i157; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.community_documents
    ADD CONSTRAINT fkiyeaoj6gdqt5u4o2340l0i157 FOREIGN KEY (uploaded_by_user_id) REFERENCES public.users(id);

--
-- Name: intercom_calls fkjb05ji5mo1tgy4wa5j5itta81; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.intercom_calls
    ADD CONSTRAINT fkjb05ji5mo1tgy4wa5j5itta81 FOREIGN KEY (recipient_user_id) REFERENCES public.users(id);

--
-- Name: tenant_feature_overrides fkjlmsj4no7olgtofmi5d6fbijn; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_feature_overrides
    ADD CONSTRAINT fkjlmsj4no7olgtofmi5d6fbijn FOREIGN KEY (feature_flag_id) REFERENCES public.feature_flags(id);

--
-- Name: reservations fkjmvhu91lj4r28bb80lsoxen9f; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT fkjmvhu91lj4r28bb80lsoxen9f FOREIGN KEY (resident_id) REFERENCES public.residents(id);

--
-- Name: reservations fkjnhj9kb99h37wt8avmcfr0m3g; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT fkjnhj9kb99h37wt8avmcfr0m3g FOREIGN KEY (created_by_user_id) REFERENCES public.users(id);

--
-- Name: security_panic_alerts fkjo5c2n6n9x86uv0w3pv8imokd; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.security_panic_alerts
    ADD CONSTRAINT fkjo5c2n6n9x86uv0w3pv8imokd FOREIGN KEY (resident_id) REFERENCES public.residents(id);

--
-- Name: saas_invoices fkjw8vr1jp5r1nc7xi3clc3mi8m; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saas_invoices
    ADD CONSTRAINT fkjw8vr1jp5r1nc7xi3clc3mi8m FOREIGN KEY (subscription_id) REFERENCES public.saas_subscriptions(id);

--
-- Name: password_reset_tokens fkk3ndxg5xp6v7wd4gjyusp15gq; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_tokens
    ADD CONSTRAINT fkk3ndxg5xp6v7wd4gjyusp15gq FOREIGN KEY (user_id) REFERENCES public.users(id);

--
-- Name: property_invoices fkkr00yjxs975sug3be25winrmf; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.property_invoices
    ADD CONSTRAINT fkkr00yjxs975sug3be25winrmf FOREIGN KEY (property_id) REFERENCES public.properties(id);

--
-- Name: realtime_notifications fkl98wvukm43jnsehpam6ilcgsc; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.realtime_notifications
    ADD CONSTRAINT fkl98wvukm43jnsehpam6ilcgsc FOREIGN KEY (property_id) REFERENCES public.properties(id);

--
-- Name: poll_votes fkm0g7r6l06c8b3aqphr97ijpd3; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.poll_votes
    ADD CONSTRAINT fkm0g7r6l06c8b3aqphr97ijpd3 FOREIGN KEY (property_id) REFERENCES public.properties(id);

--
-- Name: poll_votes fkmaogo469u92y072mev488em6p; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.poll_votes
    ADD CONSTRAINT fkmaogo469u92y072mev488em6p FOREIGN KEY (poll_id) REFERENCES public.polls(id);

--
-- Name: saas_subscriptions fkmsa3ns0lsxb150ot3qtyog1ht; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saas_subscriptions
    ADD CONSTRAINT fkmsa3ns0lsxb150ot3qtyog1ht FOREIGN KEY (plan_id) REFERENCES public.saas_plans(id);

--
-- Name: reservations fko0x0js5gs43o4bj3aeq6ba62l; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reservations
    ADD CONSTRAINT fko0x0js5gs43o4bj3aeq6ba62l FOREIGN KEY (property_id) REFERENCES public.properties(id);

--
-- Name: pqrs_requests fkop9wy8yjxrg32wo0gvh8wif57; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pqrs_requests
    ADD CONSTRAINT fkop9wy8yjxrg32wo0gvh8wif57 FOREIGN KEY (property_id) REFERENCES public.properties(id);

--
-- Name: intercom_calls fkowinhetkgkmpvxq0rs03fcpan; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.intercom_calls
    ADD CONSTRAINT fkowinhetkgkmpvxq0rs03fcpan FOREIGN KEY (property_id) REFERENCES public.properties(id);

--
-- Name: pets fkpav8rj5dwaaiktqmoroc8th1s; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pets
    ADD CONSTRAINT fkpav8rj5dwaaiktqmoroc8th1s FOREIGN KEY (resident_id) REFERENCES public.residents(id);

--
-- Name: tenants fkpkatup2hidrwkgs4yuuodf6bt; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT fkpkatup2hidrwkgs4yuuodf6bt FOREIGN KEY (organization_id) REFERENCES public.organizations(id);

--
-- Name: assembly_attendees fkqbw96ss1bx1wvhv2tgh2ue0ap; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.assembly_attendees
    ADD CONSTRAINT fkqbw96ss1bx1wvhv2tgh2ue0ap FOREIGN KEY (assembly_id) REFERENCES public.assemblies(id);

--
-- Name: poll_votes fkqta7hk4pt5jj8wshcrmg6dd51; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.poll_votes
    ADD CONSTRAINT fkqta7hk4pt5jj8wshcrmg6dd51 FOREIGN KEY (resident_id) REFERENCES public.residents(id);

--
-- Name: polls fkrxod2gqgo7q0cse0qwnep1y8c; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.polls
    ADD CONSTRAINT fkrxod2gqgo7q0cse0qwnep1y8c FOREIGN KEY (assembly_id) REFERENCES public.assemblies(id);

--
-- Name: assembly_attendees fks5smayfas0y37v00of5ugrpo7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.assembly_attendees
    ADD CONSTRAINT fks5smayfas0y37v00of5ugrpo7 FOREIGN KEY (property_id) REFERENCES public.properties(id);

--
-- Name: security_panic_alerts fkskvrm5nmgm0h3lgk2kkobwv3g; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.security_panic_alerts
    ADD CONSTRAINT fkskvrm5nmgm0h3lgk2kkobwv3g FOREIGN KEY (property_id) REFERENCES public.properties(id);

--
-- Name: pqrs_requests fktpc9kdw9st332in8jbmygjt0w; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pqrs_requests
    ADD CONSTRAINT fktpc9kdw9st332in8jbmygjt0w FOREIGN KEY (created_by_user_id) REFERENCES public.users(id);

--
-- Name: residents residents_granted_by_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.residents
    ADD CONSTRAINT residents_granted_by_user_id_fkey FOREIGN KEY (granted_by_user_id) REFERENCES public.users(id);

--
-- PostgreSQL database dump complete
--
