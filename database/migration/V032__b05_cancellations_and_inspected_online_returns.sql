-- B05-C4 cancellations and inspected online returns.
-- Forward-only, tenant-local and additive. The B04 stock ledger remains authoritative.
-- No functions, dynamic SQL, extensions, PUBLIC access or destructive data operation.

alter table inventory_sales.online_order
    drop constraint ck_online_order_status;

alter table inventory_sales.online_order
    add constraint ck_online_order_status
    check (status in (
        'PENDIENTE', 'EN_REVISION', 'CAMBIO_PROPUESTO', 'CONFIRMADO',
        'EN_PREPARACION', 'LISTO_PARA_RECOJO', 'EN_REPARTO', 'ENTREGADO',
        'CANCELACION_SOLICITADA', 'CANCELADO', 'DEVOLUCION_EN_PROCESO',
        'CERRADO_CON_DEVOLUCION'
    ));

alter table inventory_sales.online_order_status_event
    drop constraint ck_online_order_status_event_status;

alter table inventory_sales.online_order_status_event
    add constraint ck_online_order_status_event_status
    check (status in (
        'PENDIENTE', 'EN_REVISION', 'CAMBIO_PROPUESTO', 'CONFIRMADO',
        'EN_PREPARACION', 'LISTO_PARA_RECOJO', 'EN_REPARTO', 'ENTREGADO',
        'CANCELACION_SOLICITADA', 'CANCELADO', 'DEVOLUCION_EN_PROCESO',
        'CERRADO_CON_DEVOLUCION'
    ));

create table inventory_sales.online_order_cancellation (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenancy.tenant(id),
    order_id uuid not null,
    request_operation_id uuid not null,
    resolution_operation_id uuid,
    origin varchar(16) not null check (origin in ('CLIENTE', 'STAFF')),
    reason varchar(500) not null,
    source_order_status varchar(32) not null check (source_order_status in ('PENDIENTE', 'CONFIRMADO', 'EN_PREPARACION', 'LISTO_PARA_RECOJO')),
    status varchar(16) not null default 'SOLICITADA' check (status in ('SOLICITADA', 'CANCELADA', 'RECHAZADA')),
    requested_by_principal_id uuid not null,
    requested_at timestamptz not null default statement_timestamp(),
    resolved_by_principal_id uuid,
    resolved_at timestamptz,
    stock_restocked boolean not null default false,
    financial_resolution_pending boolean not null default false,
    constraint uq_online_order_cancellation_tenant_id unique (tenant_id, id),
    constraint uq_online_order_cancellation_request_operation unique (tenant_id, request_operation_id),
    constraint uq_online_order_cancellation_resolution_operation unique (tenant_id, resolution_operation_id),
    constraint fk_online_order_cancellation_order_same_tenant
        foreign key (tenant_id, order_id) references inventory_sales.online_order(tenant_id, id),
    constraint fk_online_order_cancellation_requester_same_tenant
        foreign key (tenant_id, requested_by_principal_id) references identity_auth.account_profile(tenant_id, principal_id),
    constraint fk_online_order_cancellation_resolver_same_tenant
        foreign key (tenant_id, resolved_by_principal_id) references identity_auth.account_profile(tenant_id, principal_id),
    constraint ck_online_order_cancellation_reason
        check (char_length(btrim(reason)) between 3 and 500),
    constraint ck_online_order_cancellation_audit check (
        (status = 'SOLICITADA' and resolution_operation_id is null and resolved_by_principal_id is null and resolved_at is null and not stock_restocked)
        or
        (status in ('CANCELADA', 'RECHAZADA') and resolution_operation_id is not null
            and resolved_by_principal_id is not null and resolved_at is not null)
    ),
    constraint ck_online_order_cancellation_stock check (
        not stock_restocked or (status = 'CANCELADA' and source_order_status in ('CONFIRMADO', 'EN_PREPARACION', 'LISTO_PARA_RECOJO'))
    )
);

create unique index ux_online_order_cancellation_open_or_final
    on inventory_sales.online_order_cancellation (tenant_id, order_id)
    where status in ('SOLICITADA', 'CANCELADA');
create index ix_online_order_cancellation_tenant_order_created
    on inventory_sales.online_order_cancellation (tenant_id, order_id, requested_at desc, id desc);

create table inventory_sales.online_order_return (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenancy.tenant(id),
    order_id uuid not null,
    request_operation_id uuid not null,
    decision_operation_id uuid,
    status varchar(32) not null default 'SOLICITADA'
        check (status in ('SOLICITADA', 'AUTORIZADA', 'RECHAZADA', 'RECIBIDA', 'INSPECCIONADA_APTA', 'INSPECCIONADA_NO_APTA', 'CERRADA')),
    reason varchar(500) not null,
    decision_reason varchar(500),
    requested_by_principal_id uuid not null,
    requested_at timestamptz not null default statement_timestamp(),
    decided_by_principal_id uuid,
    decided_at timestamptz,
    received_by_principal_id uuid,
    received_at timestamptz,
    inspected_by_principal_id uuid,
    inspected_at timestamptz,
    closed_by_principal_id uuid,
    closed_at timestamptz,
    updated_at timestamptz not null default statement_timestamp(),
    constraint uq_online_order_return_tenant_id unique (tenant_id, id),
    constraint uq_online_order_return_request_operation unique (tenant_id, request_operation_id),
    constraint uq_online_order_return_decision_operation unique (tenant_id, decision_operation_id),
    constraint fk_online_order_return_order_same_tenant
        foreign key (tenant_id, order_id) references inventory_sales.online_order(tenant_id, id),
    constraint fk_online_order_return_requester_same_tenant
        foreign key (tenant_id, requested_by_principal_id) references identity_auth.account_profile(tenant_id, principal_id),
    constraint fk_online_order_return_decider_same_tenant
        foreign key (tenant_id, decided_by_principal_id) references identity_auth.account_profile(tenant_id, principal_id),
    constraint fk_online_order_return_receiver_same_tenant
        foreign key (tenant_id, received_by_principal_id) references identity_auth.account_profile(tenant_id, principal_id),
    constraint fk_online_order_return_inspector_same_tenant
        foreign key (tenant_id, inspected_by_principal_id) references identity_auth.account_profile(tenant_id, principal_id),
    constraint fk_online_order_return_closer_same_tenant
        foreign key (tenant_id, closed_by_principal_id) references identity_auth.account_profile(tenant_id, principal_id),
    constraint ck_online_order_return_reason check (char_length(btrim(reason)) between 3 and 500),
    constraint ck_online_order_return_decision_reason check (decision_reason is null or char_length(btrim(decision_reason)) between 3 and 500),
    constraint ck_online_order_return_timeline check (
        (status = 'SOLICITADA' and decided_by_principal_id is null and decided_at is null and decision_operation_id is null
            and received_by_principal_id is null and received_at is null and inspected_by_principal_id is null and inspected_at is null
            and closed_by_principal_id is null and closed_at is null)
        or (status = 'AUTORIZADA' and decided_by_principal_id is not null and decided_at is not null and decision_operation_id is not null
            and received_by_principal_id is null and received_at is null and inspected_by_principal_id is null and inspected_at is null
            and closed_by_principal_id is null and closed_at is null)
        or (status = 'RECHAZADA' and decided_by_principal_id is not null and decided_at is not null and decision_operation_id is not null
            and received_by_principal_id is null and received_at is null and inspected_by_principal_id is null and inspected_at is null
            and closed_by_principal_id is null and closed_at is null)
        or (status = 'RECIBIDA' and decided_by_principal_id is not null and decided_at is not null and decision_operation_id is not null
            and received_by_principal_id is not null and received_at is not null and inspected_by_principal_id is null and inspected_at is null
            and closed_by_principal_id is null and closed_at is null)
        or (status in ('INSPECCIONADA_APTA', 'INSPECCIONADA_NO_APTA') and decided_by_principal_id is not null and decided_at is not null
            and decision_operation_id is not null and received_by_principal_id is not null and received_at is not null
            and inspected_by_principal_id is not null and inspected_at is not null and closed_by_principal_id is null and closed_at is null)
        or (status = 'CERRADA' and decided_by_principal_id is not null and decided_at is not null and decision_operation_id is not null
            and received_by_principal_id is not null and received_at is not null and inspected_by_principal_id is not null and inspected_at is not null
            and closed_by_principal_id is not null and closed_at is not null)
    )
);

create table inventory_sales.online_order_return_line (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null,
    return_id uuid not null,
    product_id uuid not null,
    product_sku_snapshot varchar(80) not null,
    product_title_snapshot varchar(240) not null,
    requested_quantity integer not null check (requested_quantity > 0 and requested_quantity <= 1000000),
    received_quantity integer not null default 0 check (received_quantity >= 0 and received_quantity <= requested_quantity),
    restocked_quantity integer not null default 0 check (restocked_quantity >= 0 and restocked_quantity <= received_quantity),
    inspection_result varchar(16) not null default 'PENDIENTE'
        check (inspection_result in ('PENDIENTE', 'APTA', 'PARCIAL', 'NO_APTA')),
    constraint uq_online_order_return_line_tenant_id unique (tenant_id, id),
    constraint uq_online_order_return_line_product unique (tenant_id, return_id, product_id),
    constraint fk_online_order_return_line_return_same_tenant
        foreign key (tenant_id, return_id) references inventory_sales.online_order_return(tenant_id, id),
    constraint fk_online_order_return_line_product_same_tenant
        foreign key (tenant_id, product_id) references inventory_sales.product(tenant_id, id),
    constraint ck_online_order_return_line_inspection check (
        (inspection_result = 'PENDIENTE' and restocked_quantity = 0)
        or (inspection_result = 'APTA' and received_quantity > 0 and restocked_quantity = received_quantity)
        or (inspection_result = 'PARCIAL' and restocked_quantity > 0 and restocked_quantity < received_quantity)
        or (inspection_result = 'NO_APTA' and restocked_quantity = 0)
    )
);

alter table inventory_sales.stock_movement
    add column online_order_return_id uuid;
alter table inventory_sales.stock_movement
    add constraint fk_stock_movement_online_return_same_tenant
        foreign key (tenant_id, online_order_return_id)
        references inventory_sales.online_order_return(tenant_id, id);

create index ix_online_order_return_tenant_order_status
    on inventory_sales.online_order_return (tenant_id, order_id, status, requested_at desc, id desc);
create index ix_online_order_return_line_tenant_return
    on inventory_sales.online_order_return_line (tenant_id, return_id, product_id);
create index ix_stock_movement_tenant_online_return
    on inventory_sales.stock_movement (tenant_id, online_order_return_id, id)
    where online_order_return_id is not null;
create unique index ux_stock_movement_online_return_once
    on inventory_sales.stock_movement (tenant_id, online_order_return_id, product_id)
    where online_order_return_id is not null and movement_type = 'DEVOLUCION_APTA';

alter table inventory_sales.online_order_cancellation enable row level security;
alter table inventory_sales.online_order_return enable row level security;
alter table inventory_sales.online_order_return_line enable row level security;

create policy online_order_cancellation_tenant_isolation on inventory_sales.online_order_cancellation
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));
create policy online_order_return_tenant_isolation on inventory_sales.online_order_return
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));
create policy online_order_return_line_tenant_isolation on inventory_sales.online_order_return_line
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));

revoke all on inventory_sales.online_order_cancellation, inventory_sales.online_order_return, inventory_sales.online_order_return_line from public;
grant select, insert, update on inventory_sales.online_order_cancellation to av_app;
grant select, insert, update on inventory_sales.online_order_return to av_app;
grant select, insert, update on inventory_sales.online_order_return_line to av_app;
