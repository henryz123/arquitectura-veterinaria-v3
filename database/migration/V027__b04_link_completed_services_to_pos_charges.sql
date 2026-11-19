-- B04 service-charge traceability. This migration is additive and tenant-local.
-- A charge is created only on POS confirmation. It preserves the original
-- commercial reference when a documented sale cancellation annuls the charge.
-- No functions, dynamic SQL, extensions, public permissions or destructive DDL.

create table inventory_sales.pos_service_charge (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenancy.tenant(id),
    sale_line_id uuid not null,
    clinical_episode_id uuid,
    grooming_attention_id uuid,
    status varchar(16) not null default 'COBRADO'
        check (status in ('COBRADO', 'ANULADO')),
    charged_by_principal_id uuid not null,
    charged_at timestamptz not null default statement_timestamp(),
    cancelled_by_cancellation_id uuid,
    cancelled_at timestamptz,
    constraint ck_pos_service_charge_source check (
        num_nonnulls(clinical_episode_id, grooming_attention_id) = 1
    ),
    constraint ck_pos_service_charge_cancellation check (
        (status = 'COBRADO' and cancelled_by_cancellation_id is null and cancelled_at is null)
        or (status = 'ANULADO' and cancelled_by_cancellation_id is not null and cancelled_at is not null)
    ),
    constraint uq_pos_service_charge_tenant_id unique (tenant_id, id),
    constraint fk_pos_service_charge_sale_line_same_tenant
        foreign key (tenant_id, sale_line_id)
        references inventory_sales.pos_sale_line (tenant_id, id),
    constraint fk_pos_service_charge_clinical_episode_same_tenant
        foreign key (tenant_id, clinical_episode_id)
        references owner_pet_care.clinical_episode (tenant_id, id),
    constraint fk_pos_service_charge_grooming_attention_same_tenant
        foreign key (tenant_id, grooming_attention_id)
        references appointment_management.grooming_attention (tenant_id, id),
    constraint fk_pos_service_charge_actor_same_tenant
        foreign key (tenant_id, charged_by_principal_id)
        references identity_auth.account_profile (tenant_id, principal_id),
    constraint fk_pos_service_charge_cancellation_same_tenant
        foreign key (tenant_id, cancelled_by_cancellation_id)
        references inventory_sales.pos_sale_cancellation (tenant_id, id)
);

-- Only one active charge may exist for an actual prestation. Historical annulled
-- links remain available for audit and a documented commercial correction.
create unique index uq_pos_service_charge_active_clinical_episode
    on inventory_sales.pos_service_charge (tenant_id, clinical_episode_id)
    where status = 'COBRADO' and clinical_episode_id is not null;
create unique index uq_pos_service_charge_active_grooming_attention
    on inventory_sales.pos_service_charge (tenant_id, grooming_attention_id)
    where status = 'COBRADO' and grooming_attention_id is not null;
create index ix_pos_service_charge_tenant_sale_line
    on inventory_sales.pos_service_charge (tenant_id, sale_line_id, charged_at desc, id desc);

alter table inventory_sales.pos_service_charge enable row level security;

create policy pos_service_charge_tenant_isolation on inventory_sales.pos_service_charge
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));

revoke all on inventory_sales.pos_service_charge from public;
grant select, insert, update on inventory_sales.pos_service_charge to av_app;
