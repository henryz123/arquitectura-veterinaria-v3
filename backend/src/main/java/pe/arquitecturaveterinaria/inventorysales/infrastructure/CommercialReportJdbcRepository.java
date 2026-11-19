package pe.arquitecturaveterinaria.inventorysales.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import pe.arquitecturaveterinaria.inventorysales.application.CommercialReport;
import pe.arquitecturaveterinaria.inventorysales.application.CommercialReportMovement;
import pe.arquitecturaveterinaria.inventorysales.application.CommercialReportProduct;

/** Read-only B04 aggregation adapter. RLS has already been scoped by the enclosing service transaction. */
@Repository
public class CommercialReportJdbcRepository {

	private static final String FINAL_POS_STATES = "('COMPLETADA', 'DEVOLUCION_PARCIAL', 'DEVUELTA')";
	private static final String OPERATIONAL_ONLINE_ORDER_STATES = "('CONFIRMADO', 'EN_PREPARACION', 'LISTO_PARA_RECOJO', 'EN_REPARTO', 'ENTREGADO', 'DEVOLUCION_EN_PROCESO', 'CERRADO_CON_DEVOLUCION')";

	private final JdbcTemplate jdbcTemplate;

	public CommercialReportJdbcRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public CommercialReport find(LocalDate from, LocalDate to) {
		SalesSummary sales = jdbcTemplate.queryForObject("""
				select count(*) as pos_sales_count,
				       coalesce(sum(total_amount_pen), 0::numeric) as pos_sales_gross_amount_pen
				from inventory_sales.pos_sale
				where status in %s
				  and completed_at >= (cast(? as date)::timestamp at time zone 'America/Lima')
				  and completed_at < ((cast(? as date) + 1)::timestamp at time zone 'America/Lima')
				""".formatted(FINAL_POS_STATES), this::mapSalesSummary, from, to);
		OnlineSummary online = jdbcTemplate.queryForObject("""
				select count(*) as confirmed_orders_count,
				       coalesce(sum(online_order.total_pen), 0::numeric) as confirmed_orders_gross_amount_pen
				from inventory_sales.online_order online_order
				where online_order.status in %s
				  and exists (
				      select 1
				      from inventory_sales.online_order_status_event status_event
				      where status_event.tenant_id = online_order.tenant_id
				        and status_event.order_id = online_order.id
				        and status_event.status = 'CONFIRMADO'
				        and status_event.created_at >= (cast(? as date)::timestamp at time zone 'America/Lima')
				        and status_event.created_at < ((cast(? as date) + 1)::timestamp at time zone 'America/Lima')
				  )
				""".formatted(OPERATIONAL_ONLINE_ORDER_STATES), this::mapOnlineSummary, from, to);
		ValidatedPaymentSummary onlinePayments = jdbcTemplate.queryForObject("""
				select count(*) as validated_payments_count,
				       coalesce(sum(payment.declared_amount_pen), 0::numeric) as validated_payments_amount_pen
				from inventory_sales.online_order_manual_payment payment
				where payment.status = 'VALIDADO'
				  and payment.decided_at >= (cast(? as date)::timestamp at time zone 'America/Lima')
				  and payment.decided_at < ((cast(? as date) + 1)::timestamp at time zone 'America/Lima')
				""", this::mapValidatedPaymentSummary, from, to);
		long cancellations = count("""
				select count(*) from inventory_sales.pos_sale_cancellation
				where created_at >= (cast(? as date)::timestamp at time zone 'America/Lima')
				  and created_at < ((cast(? as date) + 1)::timestamp at time zone 'America/Lima')
				""", from, to);
		ReturnsSummary returns = jdbcTemplate.queryForObject("""
				select count(*) as return_requests_count,
				       count(*) filter (where status in ('SOLICITADA', 'RECIBIDA')) as returns_pending_inspection_count,
				       count(*) filter (where status = 'CERRADA') as returns_closed_count
				from inventory_sales.pos_return
				where created_at >= (cast(? as date)::timestamp at time zone 'America/Lima')
				  and created_at < ((cast(? as date) + 1)::timestamp at time zone 'America/Lima')
				""", this::mapReturnsSummary, from, to);
		StockSummary stock = jdbcTemplate.queryForObject("""
				select count(product.id) as inventory_products_count,
				       coalesce(sum(coalesce(balance.available_quantity, 0)), 0) as available_stock_units,
				       count(*) filter (where coalesce(balance.available_quantity, 0) = 0) as out_of_stock_products_count
				from inventory_sales.product product
				left join inventory_sales.stock_balance balance
				  on balance.tenant_id = product.tenant_id and balance.product_id = product.id
				where product.inventory_managed
				""", this::mapStockSummary);
		long reconciliationMismatches = count("""
				select count(*) from (
				    select product.id
				    from inventory_sales.product product
				    left join inventory_sales.stock_balance balance
				      on balance.tenant_id = product.tenant_id and balance.product_id = product.id
				    left join inventory_sales.stock_movement movement
				      on movement.tenant_id = product.tenant_id and movement.product_id = product.id
				    where product.inventory_managed
				    group by product.id, balance.available_quantity
				    having coalesce(balance.available_quantity, 0) <> coalesce(sum(movement.quantity_delta), 0)
				) mismatches
				""");
		return new CommercialReport(from, to, sales.posSalesCount(), sales.posSalesGrossAmountPen(),
				online.confirmedOrdersCount(), online.confirmedOrdersGrossAmountPen(),
				onlinePayments.validatedPaymentsCount(), onlinePayments.validatedPaymentsAmountPen(), cancellations,
				returns.returnRequestsCount(), returns.returnsPendingInspectionCount(), returns.returnsClosedCount(),
				stock.inventoryProductsCount(), stock.availableStockUnits(), stock.outOfStockProductsCount(), reconciliationMismatches,
				listTopProducts(from, to), listMovements(from, to));
	}

	private List<CommercialReportProduct> listTopProducts(LocalDate from, LocalDate to) {
		return jdbcTemplate.query("""
				select line.product_id, line.product_sku_snapshot, line.product_title_snapshot,
				       sum(line.quantity) as quantity_sold, coalesce(sum(line.subtotal_pen), 0::numeric) as gross_amount_pen
				from inventory_sales.pos_sale sale
				join inventory_sales.pos_sale_line line
				  on line.tenant_id = sale.tenant_id and line.sale_id = sale.id
				where sale.status in %s
				  and sale.completed_at >= (cast(? as date)::timestamp at time zone 'America/Lima')
				  and sale.completed_at < ((cast(? as date) + 1)::timestamp at time zone 'America/Lima')
				group by line.product_id, line.product_sku_snapshot, line.product_title_snapshot
				order by gross_amount_pen desc, quantity_sold desc, line.product_title_snapshot, line.product_id
				limit 10
				""".formatted(FINAL_POS_STATES), this::mapProduct, from, to);
	}

	private List<CommercialReportMovement> listMovements(LocalDate from, LocalDate to) {
		return jdbcTemplate.query("""
				select movement_type, count(*) as entries, coalesce(sum(quantity_delta), 0) as quantity_delta
				from inventory_sales.stock_movement
				where created_at >= (cast(? as date)::timestamp at time zone 'America/Lima')
				  and created_at < ((cast(? as date) + 1)::timestamp at time zone 'America/Lima')
				group by movement_type
				order by movement_type
				""", this::mapMovement, from, to);
	}

	private long count(String sql, Object... values) {
		Long value = jdbcTemplate.queryForObject(sql, Long.class, values);
		return value == null ? 0L : value;
	}

	private SalesSummary mapSalesSummary(ResultSet resultSet, int rowNumber) throws SQLException {
		return new SalesSummary(resultSet.getLong("pos_sales_count"), resultSet.getBigDecimal("pos_sales_gross_amount_pen"));
	}

	private ReturnsSummary mapReturnsSummary(ResultSet resultSet, int rowNumber) throws SQLException {
		return new ReturnsSummary(resultSet.getLong("return_requests_count"), resultSet.getLong("returns_pending_inspection_count"),
				resultSet.getLong("returns_closed_count"));
	}

	private OnlineSummary mapOnlineSummary(ResultSet resultSet, int rowNumber) throws SQLException {
		return new OnlineSummary(resultSet.getLong("confirmed_orders_count"), resultSet.getBigDecimal("confirmed_orders_gross_amount_pen"));
	}

	private ValidatedPaymentSummary mapValidatedPaymentSummary(ResultSet resultSet, int rowNumber) throws SQLException {
		return new ValidatedPaymentSummary(resultSet.getLong("validated_payments_count"), resultSet.getBigDecimal("validated_payments_amount_pen"));
	}

	private StockSummary mapStockSummary(ResultSet resultSet, int rowNumber) throws SQLException {
		return new StockSummary(resultSet.getLong("inventory_products_count"), resultSet.getLong("available_stock_units"),
				resultSet.getLong("out_of_stock_products_count"));
	}

	private CommercialReportProduct mapProduct(ResultSet resultSet, int rowNumber) throws SQLException {
		return new CommercialReportProduct(resultSet.getObject("product_id", UUID.class), resultSet.getString("product_sku_snapshot"),
				resultSet.getString("product_title_snapshot"), resultSet.getLong("quantity_sold"), resultSet.getBigDecimal("gross_amount_pen"));
	}

	private CommercialReportMovement mapMovement(ResultSet resultSet, int rowNumber) throws SQLException {
		return new CommercialReportMovement(resultSet.getString("movement_type"), resultSet.getLong("entries"),
				resultSet.getLong("quantity_delta"));
	}

	private record SalesSummary(long posSalesCount, BigDecimal posSalesGrossAmountPen) {
	}

	private record OnlineSummary(long confirmedOrdersCount, BigDecimal confirmedOrdersGrossAmountPen) {
	}

	private record ValidatedPaymentSummary(long validatedPaymentsCount, BigDecimal validatedPaymentsAmountPen) {
	}

	private record ReturnsSummary(long returnRequestsCount, long returnsPendingInspectionCount, long returnsClosedCount) {
	}

	private record StockSummary(long inventoryProductsCount, long availableStockUnits, long outOfStockProductsCount) {
	}
}
