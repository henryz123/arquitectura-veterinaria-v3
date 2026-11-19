-- B05-C3 manual payment and fulfillment. Additive tenant-local storage only.
-- It never changes stock, prior request snapshots, proposals, credentials or PUBLIC access.

alter table inventory_sales.online_order
    drop constraint ck_online_order_status;

alter table inventory_sales.online_order
    add constraint ck_online_order_status
    check (status in (
        'PENDIENTE', 'EN_REVISION', 'CAMBIO_PROPUESTO', 'CONFIRMADO',
        'EN_PREPARACION', 'LISTO_PARA_RECOJO', 'EN_REPARTO', 'ENTREGADO'
    ));

alter table inventory_sales.online_order_status_event
    drop constraint ck_online_order_status_event_status;

alter table inventory_sales.online_order_status_event
    add constraint ck_online_order_status_event_status
    check (status in (
        'PENDIENTE', 'EN_REVISION', 'CAMBIO_PROPUESTO', 'CONFIRMADO',
        'EN_PREPARACION', 'LISTO_PARA_RECOJO', 'EN_REPARTO', 'ENTREGADO'
    ));

create table inventory_sales.online_order_manual_payment (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null,
    order_id uuid not null,
    payment_number integer not null check (payment_number > 0),
    declaration_operation_id uuid not null,
    payment_method varchar(16) not null
        check (payment_method in ('EFECTIVO', 'TRANSFERENCIA', 'OTRO')),
    declared_amount_pen numeric(12,2) not null check (declared_amount_pen >= 0),
    reference varchar(120),
    status varchar(16) not null default 'DECLARADO'
        check (status in ('DECLARADO', 'VALIDADO', 'RECHAZADO')),
    declared_by_principal_id uuid not null,
    declared_at timestamptz not null default statement_timestamp(),
    decided_by_principal_id uuid,
    decision_operation_id uuid,
    decided_at timestamptz,
    decision_reason varchar(500),
    constraint uq_online_order_manual_payment_tenant_id unique (tenant_id, id),
    constraint uq_online_order_manual_payment_number unique (tenant_id, order_id, payment_number),
    constraint uq_online_order_manual_payment_declaration_operation unique (tenant_id, declaration_operation_id),
    constraint uq_online_order_manual_payment_decision_operation unique (tenant_id, decision_operation_id),
    constraint fk_online_order_manual_payment_order_same_tenant
        foreign key (tenant_id, order_id)
        references inventory_sales.online_order (tenant_id, id),
    constraint fk_online_order_manual_payment_declarer_same_tenant
        foreign key (tenant_id, declared_by_principal_id)
        references identity_auth.account_profile (tenant_id, principal_id),
    constraint fk_online_order_manual_payment_decider_same_tenant
        foreign key (tenant_id, decided_by_principal_id)
        references identity_auth.account_profile (tenant_id, principal_id),
    constraint ck_online_order_manual_payment_reference
        check (reference is null or char_length(btrim(reference)) between 3 and 120),
    constraint ck_online_order_manual_payment_decision_audit check (
        (status = 'DECLARADO' and decided_by_principal_id is null and decision_operation_id is null and decided_at is null and decision_reason is null)
        or
        (status = 'VALIDADO' and decided_by_principal_id is not null and decision_operation_id is not null and decided_at is not null
            and (decision_reason is null or char_length(btrim(decision_reason)) between 3 and 500))
        or
        (status = 'RECHAZADO' and decided_by_principal_id is not null and decision_operation_id is not null and decided_at is not null
            and char_length(btrim(decision_reason)) between 3 and 500)
    )
);

create table inventory_sales.online_order_delivery_record (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null,
    order_id uuid not null,
    operation_id uuid not null,
    delivery_mode varchar(16) not null check (delivery_mode in ('RECOJO', 'DELIVERY')),
    receiver varchar(160) not null check (char_length(btrim(receiver)) between 3 and 160),
    proof_note varchar(240),
    delivery_fee_pen numeric(12,2) not null check (delivery_fee_pen >= 0),
    recorded_by_principal_id uuid not null,
    delivered_at timestamptz not null default statement_timestamp(),
    constraint uq_online_order_delivery_record_tenant_id unique (tenant_id, id),
    constraint uq_online_order_delivery_record_order unique (tenant_id, order_id),
    constraint uq_online_order_delivery_record_operation unique (tenant_id, operation_id),
    constraint fk_online_order_delivery_record_order_same_tenant
        foreign key (tenant_id, order_id)
        references inventory_sales.online_order (tenant_id, id),
    constraint fk_online_order_delivery_record_actor_same_tenant
        foreign key (tenant_id, recorded_by_principal_id)
        references identity_auth.account_profile (tenant_id, principal_id),
    constraint ck_online_order_delivery_record_proof
        check (proof_note is null or char_length(btrim(proof_note)) between 3 and 240)
);

create unique index ux_online_order_manual_payment_one_pending
    on inventory_sales.online_order_manual_payment (tenant_id, order_id)
    where status = 'DECLARADO';
create unique index ux_online_order_manual_payment_one_validated
    on inventory_sales.online_order_manual_payment (tenant_id, order_id)
    where status = 'VALIDADO';
create index ix_online_order_manual_payment_tenant_order_created
    on inventory_sales.online_order_manual_payment (tenant_id, order_id, declared_at desc, id desc);
create index ix_online_order_delivery_record_tenant_delivered
    on inventory_sales.online_order_delivery_record (tenant_id, delivered_at desc, id desc);

alter table inventory_sales.online_order_manual_payment enable row level security;
alter table inventory_sales.online_order_delivery_record enable row level security;

create policy online_order_manual_payment_tenant_isolation on inventory_sales.online_order_manual_payment
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));
create policy online_order_delivery_record_tenant_isolation on inventory_sales.online_order_delivery_record
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));

revoke all on inventory_sales.online_order_manual_payment, inventory_sales.online_order_delivery_record from public;
grant select, insert on inventory_sales.online_order_manual_payment to av_app;
grant update (status, decided_by_principal_id, decision_operation_id, decided_at, decision_reason)
    on inventory_sales.online_order_manual_payment to av_app;
grant select, insert on inventory_sales.online_order_delivery_record to av_app;
