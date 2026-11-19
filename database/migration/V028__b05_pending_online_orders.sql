-- B05-B pending online orders. Additive tenant-local storage only: no stock
-- reservation, stock movement, payment, delivery workflow or privileged function.

create table inventory_sales.online_delivery_configuration (
    tenant_id uuid primary key references tenancy.tenant(id),
    pickup_location varchar(240) not null,
    delivery_enabled boolean not null default false,
    fixed_delivery_fee_pen numeric(12,2) not null default 0 check (fixed_delivery_fee_pen >= 0),
    coverage_description varchar(500),
    updated_by_principal_id uuid not null,
    updated_at timestamptz not null default statement_timestamp(),
    constraint ck_online_delivery_configuration_enabled check (
        (delivery_enabled and fixed_delivery_fee_pen >= 0 and char_length(btrim(coverage_description)) between 3 and 500)
        or (not delivery_enabled and coverage_description is null and fixed_delivery_fee_pen = 0)
    ),
    constraint fk_online_delivery_configuration_actor_same_tenant
        foreign key (tenant_id, updated_by_principal_id)
        references identity_auth.account_profile (tenant_id, principal_id)
);

-- Tenant-local counters avoid deriving a visible order number from count(*),
-- which would race under concurrent registrations.
create table inventory_sales.online_order_counter (
    tenant_id uuid primary key references tenancy.tenant(id),
    next_order_number bigint not null default 1 check (next_order_number > 0)
);

create table inventory_sales.online_order (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenancy.tenant(id),
    order_number bigint not null,
    client_principal_id uuid not null,
    operation_id uuid not null,
    status varchar(24) not null default 'PENDIENTE' check (status = 'PENDIENTE'),
    delivery_mode varchar(16) not null check (delivery_mode in ('RECOJO', 'DELIVERY')),
    coordination_phone varchar(40) not null,
    pickup_location_snapshot varchar(240),
    delivery_address varchar(240),
    delivery_coverage_snapshot varchar(500),
    subtotal_pen numeric(12,2) not null check (subtotal_pen >= 0),
    delivery_fee_pen numeric(12,2) not null check (delivery_fee_pen >= 0),
    total_pen numeric(12,2) not null check (total_pen = subtotal_pen + delivery_fee_pen),
    currency varchar(3) not null default 'PEN' check (currency = 'PEN'),
    created_at timestamptz not null default statement_timestamp(),
    version integer not null default 0 check (version >= 0),
    constraint uq_online_order_tenant_id unique (tenant_id, id),
    constraint uq_online_order_tenant_number unique (tenant_id, order_number),
    constraint uq_online_order_tenant_operation unique (tenant_id, operation_id),
    constraint ck_online_order_delivery_data check (
        (delivery_mode = 'RECOJO' and delivery_fee_pen = 0 and pickup_location_snapshot is not null
         and delivery_address is null and delivery_coverage_snapshot is null)
        or
        (delivery_mode = 'DELIVERY' and delivery_fee_pen >= 0 and pickup_location_snapshot is null
         and delivery_address is not null and delivery_coverage_snapshot is not null)
    ),
    constraint fk_online_order_client_same_tenant
        foreign key (tenant_id, client_principal_id)
        references identity_auth.account_profile (tenant_id, principal_id)
);

create table inventory_sales.online_order_line (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null,
    order_id uuid not null,
    product_id uuid not null,
    quantity integer not null check (quantity > 0),
    product_sku_snapshot varchar(80) not null,
    product_title_snapshot varchar(160) not null,
    unit_price_pen numeric(12,2) not null check (unit_price_pen >= 0),
    subtotal_pen numeric(12,2) not null check (subtotal_pen = unit_price_pen * quantity),
    constraint uq_online_order_line_tenant_id unique (tenant_id, id),
    constraint uq_online_order_line_product unique (tenant_id, order_id, product_id),
    constraint fk_online_order_line_order_same_tenant
        foreign key (tenant_id, order_id)
        references inventory_sales.online_order (tenant_id, id),
    constraint fk_online_order_line_product_same_tenant
        foreign key (tenant_id, product_id)
        references inventory_sales.product (tenant_id, id)
);

create table inventory_sales.online_order_status_event (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null,
    order_id uuid not null,
    status varchar(24) not null check (status = 'PENDIENTE'),
    actor_principal_id uuid not null,
    operation_id uuid not null,
    created_at timestamptz not null default statement_timestamp(),
    constraint uq_online_order_status_event_tenant_id unique (tenant_id, id),
    constraint uq_online_order_status_event_operation unique (tenant_id, order_id, operation_id),
    constraint fk_online_order_status_event_order_same_tenant
        foreign key (tenant_id, order_id)
        references inventory_sales.online_order (tenant_id, id),
    constraint fk_online_order_status_event_actor_same_tenant
        foreign key (tenant_id, actor_principal_id)
        references identity_auth.account_profile (tenant_id, principal_id)
);

create index ix_online_order_tenant_client_created
    on inventory_sales.online_order (tenant_id, client_principal_id, created_at desc, id desc);
create index ix_online_order_status_event_tenant_order_created
    on inventory_sales.online_order_status_event (tenant_id, order_id, created_at desc, id desc);

alter table inventory_sales.online_delivery_configuration enable row level security;
alter table inventory_sales.online_order_counter enable row level security;
alter table inventory_sales.online_order enable row level security;
alter table inventory_sales.online_order_line enable row level security;
alter table inventory_sales.online_order_status_event enable row level security;

create policy online_delivery_configuration_tenant_isolation on inventory_sales.online_delivery_configuration
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));
create policy online_order_counter_tenant_isolation on inventory_sales.online_order_counter
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));
create policy online_order_tenant_isolation on inventory_sales.online_order
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));
create policy online_order_line_tenant_isolation on inventory_sales.online_order_line
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));
create policy online_order_status_event_tenant_isolation on inventory_sales.online_order_status_event
    using (tenant_id::text = current_setting('app.tenant_id', true))
    with check (tenant_id::text = current_setting('app.tenant_id', true));

revoke all on inventory_sales.online_delivery_configuration, inventory_sales.online_order_counter, inventory_sales.online_order,
    inventory_sales.online_order_line, inventory_sales.online_order_status_event from public;
grant select, insert, update on inventory_sales.online_delivery_configuration to av_app;
grant select, insert, update on inventory_sales.online_order_counter to av_app;
grant select, insert on inventory_sales.online_order, inventory_sales.online_order_line,
    inventory_sales.online_order_status_event to av_app;
