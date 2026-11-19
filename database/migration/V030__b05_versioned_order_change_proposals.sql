-- B05-C2 versioned order-change proposals. This migration is additive: the
-- original B05-B request and its immutable lines remain untouched. It adds no
-- privileged functions, dynamic SQL, stock mutation, or public permission.

alter table inventory_sales.online_order
    drop constraint ck_online_order_status;

alter table inventory_sales.online_order
    add constraint ck_online_order_status
    check (status in ('PENDIENTE', 'EN_REVISION', 'CAMBIO_PROPUESTO', 'CONFIRMADO'));

alter table inventory_sales.online_order_status_event
    drop constraint ck_online_order_status_event_status;

alter table inventory_sales.online_order_status_event
    add constraint ck_online_order_status_event_status
    check (status in ('PENDIENTE', 'EN_REVISION', 'CAMBIO_PROPUESTO', 'CONFIRMADO'));

create table inventory_sales.online_order_proposal (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null,
    order_id uuid not null,
    proposal_number integer not null check (proposal_number > 0),
    creation_operation_id uuid not null,
    status varchar(24) not null default 'PROPUESTA'
        check (status in ('PROPUESTA', 'ACEPTADA', 'RECHAZADA', 'VENCIDA')),
    reason varchar(500) not null check (char_length(btrim(reason)) between 3 and 500),

    previous_delivery_mode varchar(16) not null check (previous_delivery_mode in ('RECOJO', 'DELIVERY')),
    previous_coordination_phone varchar(40) not null check (char_length(btrim(previous_coordination_phone)) between 3 and 40),
    previous_pickup_location varchar(240),
    previous_delivery_address varchar(240),
    previous_delivery_coverage varchar(500),
    previous_subtotal_pen numeric(12,2) not null check (previous_subtotal_pen >= 0),
    previous_delivery_fee_pen numeric(12,2) not null check (previous_delivery_fee_pen >= 0),
    previous_total_pen numeric(12,2) not null check (previous_total_pen = previous_subtotal_pen + previous_delivery_fee_pen),

    proposed_delivery_mode varchar(16) not null check (proposed_delivery_mode in ('RECOJO', 'DELIVERY')),
    proposed_coordination_phone varchar(40) not null check (char_length(btrim(proposed_coordination_phone)) between 3 and 40),
    proposed_pickup_location varchar(240),
    proposed_delivery_address varchar(240),
    proposed_delivery_coverage varchar(500),
    proposed_subtotal_pen numeric(12,2) not null check (proposed_subtotal_pen >= 0),
    proposed_delivery_fee_pen numeric(12,2) not null check (proposed_delivery_fee_pen >= 0),
    proposed_total_pen numeric(12,2) not null check (proposed_total_pen = proposed_subtotal_pen + proposed_delivery_fee_pen),

    created_by_principal_id uuid not null,
    created_at timestamptz not null default statement_timestamp(),
    decided_by_principal_id uuid,
    decision_operation_id uuid,
    decided_at timestamptz,

    constraint uq_online_order_proposal_tenant_id unique (tenant_id, id),
    constraint uq_online_order_proposal_number unique (tenant_id, order_id, proposal_number),
    constraint uq_online_order_proposal_creation_operation unique (tenant_id, creation_operation_id),
    constraint uq_online_order_proposal_decision_operation unique (tenant_id, decision_operation_id),
    constraint fk_online_order_proposal_order_same_tenant
        foreign key (tenant_id, order_id)
        references inventory_sales.online_order (tenant_id, id),
    constraint fk_online_order_proposal_creator_same_tenant
        foreign key (tenant_id, created_by_principal_id)
        references identity_auth.account_profile (tenant_id, principal_id),
    constraint fk_online_order_proposal_decider_same_tenant
        foreign key (tenant_id, decided_by_principal_id)
        references identity_auth.account_profile (tenant_id, principal_id),
    constraint ck_online_order_proposal_previous_delivery_data check (
        (previous_delivery_mode = 'RECOJO' and previous_delivery_fee_pen = 0 and previous_pickup_location is not null
         and previous_delivery_address is null and previous_delivery_coverage is null)
        or
        (previous_delivery_mode = 'DELIVERY' and previous_delivery_fee_pen >= 0 and previous_pickup_location is null
         and previous_delivery_address is not null and previous_delivery_coverage is not null)
    ),
    constraint ck_online_order_proposal_proposed_delivery_data check (
        (proposed_delivery_mode = 'RECOJO' and proposed_delivery_fee_pen = 0 and proposed_pickup_location is not null
         and proposed_delivery_address is null and proposed_delivery_coverage is null)
        or
        (proposed_delivery_mode = 'DELIVERY' and proposed_delivery_fee_pen >= 0 and proposed_pickup_location is null
         and proposed_delivery_address is not null and proposed_delivery_coverage is not null)
    ),
    constraint ck_online_order_proposal_decision_audit check (
        (status = 'PROPUESTA' and decided_by_principal_id is null and decision_operation_id is null and decided_at is null)
        or
        (status in ('ACEPTADA', 'RECHAZADA', 'VENCIDA') and decided_by_principal_id is not null
         and decision_operation_id is not null and decided_at is not null)
    )
);

create table inventory_sales.online_order_proposal_line (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null,
    proposal_id uuid not null,
    condition_version varchar(16) not null check (condition_version in ('ANTERIOR', 'PROPUESTA')),
    product_id uuid not null,
    quantity integer not null check (quantity > 0),
    product_sku_snapshot varchar(80) not null,
    product_title_snapshot varchar(160) not null,
    unit_price_pen numeric(12,2) not null check (unit_price_pen >= 0),
    subtotal_pen numeric(12,2) not null check (subtotal_pen = unit_price_pen * quantity),
    constraint uq_online_order_proposal_line_tenant_id unique (tenant_id, id),
    constraint uq_online_order_proposal_line_version_product unique (tenant_id, proposal_id, condition_version, product_id),
    constraint fk_online_order_proposal_line_proposal_same_tenant
        foreign key (tenant_id, proposal_id)
        references inventory_sales.online_order_proposal (tenant_id, id),
    constraint fk_online_order_proposal_line_product_same_tenant
        foreign key (tenant_id, product_id)
        references inventory_sales.product (tenant_id, id)
);

create unique index ux_online_order_proposal_one_open_per_order
    on inventory_sales.online_order_proposal (tenant_id, order_id)
    where status = 'PROPUESTA';

create index ix_online_order_proposal_tenant_order_version
    on inventory_sales.online_order_proposal (tenant_id, order_id, proposal_number desc);

create index ix_online_order_proposal_line_tenant_proposal_version
    on inventory_sales.online_order_proposal_line (tenant_id, proposal_id, condition_version, id);

alter table inventory_sales.online_order_proposal enable row level security;
alter table inventory_sales.online_order_proposal_line enable row level security;

create policy online_order_proposal_tenant_isolation on inventory_sales.online_order_proposal
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));
create policy online_order_proposal_line_tenant_isolation on inventory_sales.online_order_proposal_line
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));

revoke all on inventory_sales.online_order_proposal, inventory_sales.online_order_proposal_line from public;
grant select, insert on inventory_sales.online_order_proposal to av_app;
grant update (status, decided_by_principal_id, decision_operation_id, decided_at) on inventory_sales.online_order_proposal to av_app;
grant select, insert on inventory_sales.online_order_proposal_line to av_app;
