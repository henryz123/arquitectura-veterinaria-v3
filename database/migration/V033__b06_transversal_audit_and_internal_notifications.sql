-- B06-B: protected read-side audit and tenant-local internal notification delivery.
-- Forward-only and additive. Existing audit events remain their authoritative records.
-- No SECURITY DEFINER, dynamic SQL, external delivery provider, PUBLIC access or destructive operation.

-- B01 audit rows become readable only through an RLS-scoped tenant context. Rows
-- with a NULL tenant (global SaaS administration) remain invisible to tenant APIs.
alter table tenant_admin.audit_entry enable row level security;

create policy audit_entry_tenant_isolation on tenant_admin.audit_entry
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));

revoke all on tenant_admin.audit_entry from public;
grant select on tenant_admin.audit_entry to av_app;

create schema if not exists notifications_reporting;

revoke all on schema notifications_reporting from public;
revoke create on schema notifications_reporting from av_app;
grant usage on schema notifications_reporting to av_app;

-- One row is an internal-delivery work item for exactly one tenant member.
-- The code is mapped to safe UI copy; free text, medical narrative, emails,
-- opaque links and tokens are deliberately absent from this cross-module record.
create table notifications_reporting.notification_delivery (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenancy.tenant(id),
    recipient_principal_id uuid not null,
    source_module varchar(16) not null check (source_module in ('B03', 'B05')),
    source_event_id uuid not null,
    source_resource_type varchar(64) not null,
    source_resource_id uuid not null,
    notification_code varchar(64) not null
        check (notification_code ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    status varchar(24) not null default 'PENDING'
        check (status in ('PENDING', 'ACCEPTED_LOCAL', 'FAILED')),
    attempt_count integer not null default 0 check (attempt_count >= 0),
    next_attempt_at timestamptz not null default statement_timestamp(),
    last_attempt_at timestamptz,
    accepted_local_at timestamptz,
    last_failure_code varchar(80),
    read_at timestamptz,
    created_at timestamptz not null default statement_timestamp(),
    constraint uq_notification_delivery_tenant_id unique (tenant_id, id),
    constraint uq_notification_delivery_source_recipient
        unique (tenant_id, recipient_principal_id, source_module, source_event_id, notification_code),
    constraint fk_notification_delivery_recipient_same_tenant
        foreign key (tenant_id, recipient_principal_id)
        references identity_auth.account_profile(tenant_id, principal_id),
    constraint ck_notification_delivery_result check (
        (status = 'PENDING' and accepted_local_at is null and last_failure_code is null)
        or (status = 'ACCEPTED_LOCAL' and accepted_local_at is not null and last_failure_code is null)
        or (status = 'FAILED' and accepted_local_at is null and last_failure_code is not null)
    ),
    constraint ck_notification_delivery_failure_code check (
        last_failure_code is null or last_failure_code ~ '^[A-Z][A-Z0-9_]{2,79}$'
    )
);

create index ix_notification_delivery_recipient_due
    on notifications_reporting.notification_delivery (tenant_id, recipient_principal_id, next_attempt_at, created_at, id)
    where status in ('PENDING', 'FAILED');
create index ix_notification_delivery_recipient_visible
    on notifications_reporting.notification_delivery (tenant_id, recipient_principal_id, created_at desc, id desc)
    where status = 'ACCEPTED_LOCAL';

alter table notifications_reporting.notification_delivery enable row level security;

-- A trusted business transaction may enqueue for an authorized recipient in
-- its tenant. Reads and state updates require both tenant and recipient GUCs.
create policy notification_delivery_enqueue_same_tenant
    on notifications_reporting.notification_delivery for insert
    with check (tenant_id::text = current_setting('app.tenant_id', true));
create policy notification_delivery_recipient_read
    on notifications_reporting.notification_delivery for select
    using (
        tenant_id::text = current_setting('app.tenant_id', true)
        and recipient_principal_id::text = current_setting('app.principal_id', true)
    );
create policy notification_delivery_recipient_update
    on notifications_reporting.notification_delivery for update
    using (
        tenant_id::text = current_setting('app.tenant_id', true)
        and recipient_principal_id::text = current_setting('app.principal_id', true)
    )
    with check (
        tenant_id::text = current_setting('app.tenant_id', true)
        and recipient_principal_id::text = current_setting('app.principal_id', true)
    );

revoke all on notifications_reporting.notification_delivery from public;
grant select, insert, update on notifications_reporting.notification_delivery to av_app;
