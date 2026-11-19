-- B05-C1 confirmation. This additive, transactional migration expands only the
-- pending-order workflow required to confirm or review an order against the
-- shared B04 stock authority. It adds no functions, dynamic SQL, public grant
-- or stock mutation by itself.

alter table inventory_sales.online_order
    drop constraint online_order_status_check;

alter table inventory_sales.online_order
    add constraint ck_online_order_status
    check (status in ('PENDIENTE', 'EN_REVISION', 'CONFIRMADO'));

alter table inventory_sales.online_order_status_event
    drop constraint online_order_status_event_status_check;

alter table inventory_sales.online_order_status_event
    add column reason varchar(500),
    add constraint ck_online_order_status_event_status
        check (status in ('PENDIENTE', 'EN_REVISION', 'CONFIRMADO')),
    add constraint ck_online_order_status_event_reason
        check (reason is null or char_length(btrim(reason)) between 3 and 500);

alter table inventory_sales.stock_movement
    add column online_order_id uuid,
    add constraint fk_stock_movement_online_order_same_tenant
        foreign key (tenant_id, online_order_id)
        references inventory_sales.online_order (tenant_id, id);

create index ix_stock_movement_tenant_online_order
    on inventory_sales.stock_movement (tenant_id, online_order_id, id)
    where online_order_id is not null;

create unique index ux_stock_movement_online_confirmation_once
    on inventory_sales.stock_movement (tenant_id, online_order_id, product_id)
    where online_order_id is not null
      and movement_type = 'CONFIRMACION_ONLINE';

-- V028 allowed av_app to create and read a pending order. C1 needs only the
-- state/version update; the immutable event ledger remains insert-only.
grant select, insert, update on inventory_sales.online_order to av_app;
