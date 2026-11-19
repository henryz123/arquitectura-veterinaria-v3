package pe.arquitecturaveterinaria.inventorysales.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.DeliveryConfiguration;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.DeliveryMode;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Cancellation;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.CancellationOrigin;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.CancellationStatus;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Line;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.DeliveryRecord;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ManualPayment;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ManualPaymentStatus;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Order;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Product;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.OnlineReturn;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.OnlineReturnLine;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.OnlineReturnStatus;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Proposal;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ProposalStatus;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.StaffOrder;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.StatusEvent;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Terms;

@Repository
public class OnlineOrderJdbcRepository {
	private final JdbcTemplate jdbc;
	public OnlineOrderJdbcRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	public void lockOperation(UUID tenantId, UUID operationId) {
		jdbc.query("select pg_advisory_xact_lock(hashtextextended(?::text || ':' || ?::text, 0))",
				resultSet -> null, tenantId, operationId);
	}
	public Optional<DeliveryConfiguration> findConfiguration() {
		return jdbc.query("select pickup_location, delivery_enabled, fixed_delivery_fee_pen, coverage_description, updated_at from inventory_sales.online_delivery_configuration",
				this::mapConfiguration).stream().findFirst();
	}
	public DeliveryConfiguration upsertConfiguration(UUID tenantId, String pickupLocation, boolean enabled, java.math.BigDecimal fee,
			String coverage, UUID actor) {
		return jdbc.queryForObject("""
			insert into inventory_sales.online_delivery_configuration (tenant_id, pickup_location, delivery_enabled, fixed_delivery_fee_pen, coverage_description, updated_by_principal_id)
			values (?, ?, ?, ?, ?, ?)
			on conflict (tenant_id) do update set pickup_location=excluded.pickup_location, delivery_enabled=excluded.delivery_enabled,
			 fixed_delivery_fee_pen=excluded.fixed_delivery_fee_pen, coverage_description=excluded.coverage_description,
			 updated_by_principal_id=excluded.updated_by_principal_id, updated_at=statement_timestamp()
			returning pickup_location, delivery_enabled, fixed_delivery_fee_pen, coverage_description, updated_at
			""", this::mapConfiguration, tenantId, pickupLocation, enabled, fee, coverage, actor);
	}
	public Optional<Product> lockPublishedPhysicalProduct(UUID id) {
		return jdbc.query("""
			select product.id, product.sku, product.title, product.sale_price_pen, product.status, product.inventory_managed
			from inventory_sales.product product
			where product.id = ? and coalesce((select balance.available_quantity from inventory_sales.stock_balance balance
				where balance.tenant_id = product.tenant_id and balance.product_id = product.id), 0) > 0
			for update
			""", this::mapProduct, id).stream().findFirst();
	}
	/** Proposal lines may be evaluated without reserving stock; stock is locked only at confirmation. */
	public Optional<Product> findProposalProduct(UUID id) {
		return jdbc.query("""
			select product.id, product.sku, product.title, product.sale_price_pen, product.status, product.inventory_managed
			from inventory_sales.product product where product.id = ?
			""", this::mapProduct, id).stream().findFirst();
	}
	public long nextOrderNumber(UUID tenantId) {
		Long number = jdbc.queryForObject("""
			insert into inventory_sales.online_order_counter (tenant_id, next_order_number) values (?, 2)
			on conflict (tenant_id) do update set next_order_number = inventory_sales.online_order_counter.next_order_number + 1
			returning next_order_number - 1
			""", Long.class, tenantId);
		return number;
	}
	public Order insertOrder(UUID tenantId, long number, UUID client, UUID operation, DeliveryMode mode, String phone,
			String pickup, String address, String coverage, java.math.BigDecimal subtotal, java.math.BigDecimal deliveryFee,
			java.math.BigDecimal total) {
		return jdbc.queryForObject("""
			insert into inventory_sales.online_order (tenant_id, order_number, client_principal_id, operation_id, delivery_mode, coordination_phone,
			 pickup_location_snapshot, delivery_address, delivery_coverage_snapshot, subtotal_pen, delivery_fee_pen, total_pen)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			returning id, order_number, status, delivery_mode, coordination_phone, pickup_location_snapshot, delivery_address,
		 delivery_coverage_snapshot, subtotal_pen, delivery_fee_pen, total_pen, 'NO_DECLARADO' as payment_status, created_at
			""", this::mapOrder, tenantId, number, client, operation, mode.name(), phone, pickup, address, coverage, subtotal, deliveryFee, total);
	}
	public void insertLine(UUID tenantId, UUID orderId, Product product, int quantity, java.math.BigDecimal subtotal) {
		jdbc.update("""
			insert into inventory_sales.online_order_line (tenant_id, order_id, product_id, quantity, product_sku_snapshot, product_title_snapshot, unit_price_pen, subtotal_pen)
			values (?, ?, ?, ?, ?, ?, ?, ?)
			""", tenantId, orderId, product.id(), quantity, product.sku(), product.title(), product.salePricePen(), subtotal);
	}
	public UUID insertInitialEvent(UUID tenantId, UUID orderId, UUID actor, UUID operation) {
		return jdbc.queryForObject("""
				insert into inventory_sales.online_order_status_event (tenant_id, order_id, status, actor_principal_id, operation_id)
				values (?, ?, 'PENDIENTE', ?, ?)
				returning id
				""", UUID.class, tenantId, orderId, actor, operation);
	}
	public Optional<Order> findByOperation(UUID operation) { return jdbc.query(orderQuery("where online_order.operation_id = ?"), this::mapOrder, operation).stream().findFirst(); }
	public Optional<Order> findOwn(UUID id, UUID client) { return jdbc.query(orderQuery("where online_order.id = ? and online_order.client_principal_id = ?"), this::mapOrder, id, client).stream().findFirst(); }
	public List<Order> listOwn(UUID client) { return jdbc.query(orderQuery("where online_order.client_principal_id = ? order by online_order.created_at desc, online_order.id desc"), this::mapOrder, client); }
	public List<StaffOrder> listManagement() {
		return jdbc.query(managementQuery("order by online_order.created_at desc, online_order.id desc"), this::mapStaffOrder);
	}
	public Optional<StaffOrder> findManagement(UUID id) {
		return jdbc.query(managementQuery("where online_order.id = ?"), this::mapStaffOrder, id).stream().findFirst();
	}
	public Optional<Order> lockOrder(UUID id) {
		return jdbc.query(orderQuery("where online_order.id = ? for update"), this::mapOrder, id).stream().findFirst();
	}
	public List<Line> listLines(UUID orderId) { return jdbc.query("select product_id, product_sku_snapshot, product_title_snapshot, quantity, unit_price_pen, subtotal_pen from inventory_sales.online_order_line where order_id=? order by product_title_snapshot, id", this::mapLine, orderId); }
	// The parent order is locked first and order lines are immutable after creation.
	// A plain read therefore preserves the atomic protocol without requiring a row-lock
	// privilege on the immutable line ledger.
	public List<Line> lockLines(UUID orderId) { return jdbc.query("select product_id, product_sku_snapshot, product_title_snapshot, quantity, unit_price_pen, subtotal_pen from inventory_sales.online_order_line where order_id=? order by product_id", this::mapLine, orderId); }
	public List<StatusEvent> listEvents(UUID orderId) { return jdbc.query("select status, reason, created_at from inventory_sales.online_order_status_event where order_id=? order by created_at, id", this::mapEvent, orderId); }
	public Optional<String> findStatusByOperation(UUID orderId, UUID operationId) { return jdbc.query("select status from inventory_sales.online_order_status_event where order_id = ? and operation_id = ?", (rs, n) -> rs.getString("status"), orderId, operationId).stream().findFirst(); }
	public Order transitionOrderStatus(UUID orderId, String expectedStatus, String nextStatus) {
		return jdbc.query("update inventory_sales.online_order set status = ?, version = version + 1 where id = ? and status = ? returning id, order_number, status, delivery_mode, coordination_phone, pickup_location_snapshot, delivery_address, delivery_coverage_snapshot, subtotal_pen, delivery_fee_pen, total_pen, coalesce((select payment.status from inventory_sales.online_order_manual_payment payment where payment.tenant_id = inventory_sales.online_order.tenant_id and payment.order_id = inventory_sales.online_order.id order by payment.payment_number desc limit 1), 'NO_DECLARADO') as payment_status, created_at", this::mapOrder, nextStatus, orderId, expectedStatus)
				.stream().findFirst().orElseThrow(() -> new IllegalStateException("El pedido cambió antes de completar una operación bloqueada."));
	}
	public void recordReviewAttempt(UUID orderId) {
		jdbc.update("update inventory_sales.online_order set version = version + 1 where id = ? and status = 'EN_REVISION'", orderId);
	}
	public UUID insertStatusEvent(UUID tenantId, UUID orderId, String status, UUID actor, UUID operationId, String reason) {
		return jdbc.queryForObject("""
				insert into inventory_sales.online_order_status_event (tenant_id, order_id, status, actor_principal_id, operation_id, reason)
				values (?, ?, ?, ?, ?, ?)
				returning id
				""", UUID.class, tenantId, orderId, status, actor, operationId, reason);
	}
	public Optional<UUID> findClientPrincipalId(UUID orderId) {
		return jdbc.query("select client_principal_id from inventory_sales.online_order where id = ?", (resultSet, rowNumber) ->
				resultSet.getObject("client_principal_id", UUID.class), orderId).stream().findFirst();
	}
	public Optional<Proposal> findProposalByCreationOperation(UUID operationId) {
		return jdbc.query(proposalQuery("where proposal.creation_operation_id = ?"), this::mapProposal, operationId).stream().findFirst();
	}
	public Optional<Proposal> findProposalByDecisionOperation(UUID operationId) {
		return jdbc.query(proposalQuery("where proposal.decision_operation_id = ?"), this::mapProposal, operationId).stream().findFirst();
	}
	public Optional<Proposal> findOwnProposal(UUID proposalId, UUID clientPrincipalId) {
		return jdbc.query(proposalQuery("join inventory_sales.online_order online_order on online_order.tenant_id = proposal.tenant_id and online_order.id = proposal.order_id where proposal.id = ? and online_order.client_principal_id = ?"), this::mapProposal, proposalId, clientPrincipalId).stream().findFirst();
	}
	public Optional<Proposal> lockProposal(UUID proposalId) {
		return jdbc.query(proposalQuery("where proposal.id = ? for update"), this::mapProposal, proposalId).stream().findFirst();
	}
	public Optional<Proposal> findLatestAcceptedProposal(UUID orderId) {
		return jdbc.query(proposalQuery("where proposal.order_id = ? and proposal.status = 'ACEPTADA' order by proposal.proposal_number desc limit 1"), this::mapProposal, orderId).stream().findFirst();
	}
	public List<Proposal> listProposals(UUID orderId) {
		return jdbc.query(proposalQuery("where proposal.order_id = ? order by proposal.proposal_number"), this::mapProposal, orderId);
	}
	public Proposal insertProposal(UUID tenantId, UUID orderId, UUID operationId, String reason, Terms previous, Terms proposed, UUID actor) {
		return jdbc.queryForObject("""
			insert into inventory_sales.online_order_proposal (
			  tenant_id, order_id, proposal_number, creation_operation_id, reason,
			  previous_delivery_mode, previous_coordination_phone, previous_pickup_location, previous_delivery_address, previous_delivery_coverage,
			  previous_subtotal_pen, previous_delivery_fee_pen, previous_total_pen,
			  proposed_delivery_mode, proposed_coordination_phone, proposed_pickup_location, proposed_delivery_address, proposed_delivery_coverage,
			  proposed_subtotal_pen, proposed_delivery_fee_pen, proposed_total_pen, created_by_principal_id
			) values (?, ?, (select coalesce(max(proposal_number), 0) + 1 from inventory_sales.online_order_proposal where tenant_id = ? and order_id = ?), ?, ?,
			  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			returning id, order_id, proposal_number, status, reason, created_at, decided_at,
			 previous_delivery_mode, previous_coordination_phone, previous_pickup_location, previous_delivery_address, previous_delivery_coverage,
			 previous_subtotal_pen, previous_delivery_fee_pen, previous_total_pen,
			 proposed_delivery_mode, proposed_coordination_phone, proposed_pickup_location, proposed_delivery_address, proposed_delivery_coverage,
			 proposed_subtotal_pen, proposed_delivery_fee_pen, proposed_total_pen
			""", this::mapProposal, tenantId, orderId, tenantId, orderId, operationId, reason,
			previous.deliveryMode().name(), previous.coordinationPhone(), previous.pickupLocation(), previous.deliveryAddress(), previous.deliveryCoverage(), previous.subtotalPen(), previous.deliveryFeePen(), previous.totalPen(),
			proposed.deliveryMode().name(), proposed.coordinationPhone(), proposed.pickupLocation(), proposed.deliveryAddress(), proposed.deliveryCoverage(), proposed.subtotalPen(), proposed.deliveryFeePen(), proposed.totalPen(), actor);
	}
	public void insertProposalLine(UUID tenantId, UUID proposalId, String version, Line line) {
		jdbc.update("""
			insert into inventory_sales.online_order_proposal_line (tenant_id, proposal_id, condition_version, product_id, quantity, product_sku_snapshot, product_title_snapshot, unit_price_pen, subtotal_pen)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?)
			""", tenantId, proposalId, version, line.productId(), line.quantity(), line.sku(), line.title(), line.unitPricePen(), line.subtotalPen());
	}
	public List<Line> listProposalLines(UUID proposalId, String version) {
		return jdbc.query("select product_id, product_sku_snapshot, product_title_snapshot, quantity, unit_price_pen, subtotal_pen from inventory_sales.online_order_proposal_line where proposal_id = ? and condition_version = ? order by product_title_snapshot, id", this::mapLine, proposalId, version);
	}
	public void decideProposal(UUID proposalId, ProposalStatus decision, UUID actor, UUID operationId) {
		int changed = jdbc.update("""
			update inventory_sales.online_order_proposal
			set status = ?, decided_by_principal_id = ?, decision_operation_id = ?, decided_at = statement_timestamp()
			where id = ? and status = 'PROPUESTA'
			""", decision.name(), actor, operationId, proposalId);
		if (changed != 1) throw new IllegalStateException("La propuesta cambió antes de registrar la decisión.");
	}
	public Optional<ManualPayment> findPaymentByDeclarationOperation(UUID operationId) { return jdbc.query(paymentQuery("where payment.declaration_operation_id = ?"), this::mapPayment, operationId).stream().findFirst(); }
	public Optional<ManualPayment> findPaymentByDecisionOperation(UUID operationId) { return jdbc.query(paymentQuery("where payment.decision_operation_id = ?"), this::mapPayment, operationId).stream().findFirst(); }
	public Optional<ManualPayment> lockPayment(UUID paymentId) { return jdbc.query(paymentQuery("where payment.id = ? for update"), this::mapPayment, paymentId).stream().findFirst(); }
	public List<ManualPayment> listPayments(UUID orderId) { return jdbc.query(paymentQuery("where payment.order_id = ? order by payment.payment_number"), this::mapPayment, orderId); }
	public ManualPayment insertManualPayment(UUID tenantId, UUID orderId, UUID operationId, String method, java.math.BigDecimal amount, String reference, UUID actor) {
		return jdbc.queryForObject("""
			insert into inventory_sales.online_order_manual_payment (
			 tenant_id, order_id, payment_number, declaration_operation_id, payment_method, declared_amount_pen, reference, declared_by_principal_id
			) values (?, ?, (select coalesce(max(payment_number), 0) + 1 from inventory_sales.online_order_manual_payment where tenant_id = ? and order_id = ?), ?, ?, ?, ?, ?)
			returning id, order_id, payment_number, payment_method, declared_amount_pen, status, reference, declared_at, decided_at, decision_reason
			""", this::mapPayment, tenantId, orderId, tenantId, orderId, operationId, method, amount, reference, actor);
	}
	public void decideManualPayment(UUID paymentId, ManualPaymentStatus decision, UUID actor, UUID operationId, String reason) {
		int changed = jdbc.update("""
			update inventory_sales.online_order_manual_payment
			set status = ?, decided_by_principal_id = ?, decision_operation_id = ?, decided_at = statement_timestamp(), decision_reason = ?
			where id = ? and status = 'DECLARADO'
			""", decision.name(), actor, operationId, reason, paymentId);
		if (changed != 1) throw new IllegalStateException("El pago cambió antes de registrar la decisión.");
	}
	public Optional<DeliveryRecord> findDeliveryByOperation(UUID operationId) { return jdbc.query(deliveryRecordQuery("where record.operation_id = ?"), this::mapDeliveryRecord, operationId).stream().findFirst(); }
	public Optional<DeliveryRecord> findDeliveryRecord(UUID orderId) { return jdbc.query(deliveryRecordQuery("where record.order_id = ?"), this::mapDeliveryRecord, orderId).stream().findFirst(); }
	public DeliveryRecord insertDeliveryRecord(UUID tenantId, UUID orderId, UUID operationId, DeliveryMode mode, String receiver, String proofNote, java.math.BigDecimal fee, UUID actor) {
		return jdbc.queryForObject("""
			insert into inventory_sales.online_order_delivery_record (
			 tenant_id, order_id, operation_id, delivery_mode, receiver, proof_note, delivery_fee_pen, recorded_by_principal_id
			) values (?, ?, ?, ?, ?, ?, ?, ?)
			returning id, order_id, delivery_mode, receiver, proof_note, delivery_fee_pen, delivered_at
			""", this::mapDeliveryRecord, tenantId, orderId, operationId, mode.name(), receiver, proofNote, fee, actor);
	}
	public Optional<Order> lockOwnOrder(UUID id, UUID client) {
		return jdbc.query(orderQuery("where online_order.id = ? and online_order.client_principal_id = ? for update"), this::mapOrder, id, client).stream().findFirst();
	}
	public Optional<Cancellation> findCancellation(UUID orderId) { return jdbc.query(cancellationQuery("where cancellation.order_id = ? order by cancellation.requested_at desc, cancellation.id desc limit 1"), this::mapCancellation, orderId).stream().findFirst(); }
	public Optional<Cancellation> findCancellationByRequestOperation(UUID operationId) { return jdbc.query(cancellationQuery("where cancellation.request_operation_id = ?"), this::mapCancellation, operationId).stream().findFirst(); }
	public Optional<Cancellation> findCancellationByResolutionOperation(UUID operationId) { return jdbc.query(cancellationQuery("where cancellation.resolution_operation_id = ?"), this::mapCancellation, operationId).stream().findFirst(); }
	public Optional<Cancellation> lockCancellation(UUID cancellationId) { return jdbc.query(cancellationQuery("where cancellation.id = ? for update"), this::mapCancellation, cancellationId).stream().findFirst(); }
	public Cancellation insertCancellation(UUID tenantId, UUID orderId, UUID requestOperationId, CancellationOrigin origin,
			String reason, String sourceStatus, boolean financialResolutionPending, UUID actor) {
		return jdbc.queryForObject("""
			insert into inventory_sales.online_order_cancellation (
			 tenant_id, order_id, request_operation_id, origin, reason, source_order_status, financial_resolution_pending, requested_by_principal_id
			) values (?, ?, ?, ?, ?, ?, ?, ?)
			returning id, order_id, origin, reason, source_order_status, status, stock_restocked, financial_resolution_pending, requested_at, resolved_at
			""", this::mapCancellation, tenantId, orderId, requestOperationId, origin.name(), reason, sourceStatus, financialResolutionPending, actor);
	}
	public void resolveCancellation(UUID cancellationId, CancellationStatus decision, UUID actor, UUID operationId, boolean stockRestocked) {
		int changed = jdbc.update("""
			update inventory_sales.online_order_cancellation
			set status = ?, resolution_operation_id = ?, resolved_by_principal_id = ?, resolved_at = statement_timestamp(), stock_restocked = ?
			where id = ? and status = 'SOLICITADA'
			""", decision.name(), operationId, actor, stockRestocked, cancellationId);
		if (changed != 1) throw new IllegalStateException("La cancelación cambió antes de registrar su resolución.");
	}
	public Optional<OnlineReturn> findReturnByRequestOperation(UUID operationId) { return jdbc.query(onlineReturnQuery("where online_return.request_operation_id = ?"), this::mapOnlineReturn, operationId).stream().findFirst(); }
	public Optional<OnlineReturn> findReturnByDecisionOperation(UUID operationId) { return jdbc.query(onlineReturnQuery("where online_return.decision_operation_id = ?"), this::mapOnlineReturn, operationId).stream().findFirst(); }
	public Optional<OnlineReturn> lockReturn(UUID returnId) { return jdbc.query(onlineReturnQuery("where online_return.id = ? for update"), this::mapOnlineReturn, returnId).stream().findFirst(); }
	public List<OnlineReturn> listReturns(UUID orderId) { return jdbc.query(onlineReturnQuery("where online_return.order_id = ? order by online_return.requested_at desc, online_return.id desc"), this::mapOnlineReturn, orderId); }
	public OnlineReturn insertReturn(UUID tenantId, UUID orderId, UUID operationId, String reason, UUID actor) {
		return jdbc.queryForObject("""
			insert into inventory_sales.online_order_return (tenant_id, order_id, request_operation_id, reason, requested_by_principal_id)
			values (?, ?, ?, ?, ?)
			returning id, order_id, reason, status, requested_at, decided_at, received_at, inspected_at, closed_at
			""", this::mapOnlineReturn, tenantId, orderId, operationId, reason, actor);
	}
	public void insertReturnLine(UUID tenantId, UUID returnId, Line line, int requestedQuantity) {
		jdbc.update("""
			insert into inventory_sales.online_order_return_line (
			 tenant_id, return_id, product_id, product_sku_snapshot, product_title_snapshot, requested_quantity
			) values (?, ?, ?, ?, ?, ?)
			""", tenantId, returnId, line.productId(), line.sku(), line.title(), requestedQuantity);
	}
	public List<OnlineReturnLine> listReturnLines(UUID returnId) { return jdbc.query(returnLineQuery("where return_line.return_id = ? order by return_line.product_title_snapshot, return_line.id"), this::mapOnlineReturnLine, returnId); }
	public List<OnlineReturnLine> lockReturnLines(UUID returnId) { return jdbc.query(returnLineQuery("where return_line.return_id = ? order by return_line.product_id, return_line.id for update of return_line"), this::mapOnlineReturnLine, returnId); }
	public int allocatedReturnQuantity(UUID orderId, UUID productId) {
		Integer value = jdbc.queryForObject("""
			select coalesce(sum(return_line.requested_quantity), 0)
			from inventory_sales.online_order_return_line return_line
			join inventory_sales.online_order_return online_return
			  on online_return.tenant_id = return_line.tenant_id and online_return.id = return_line.return_id
			where online_return.order_id = ? and return_line.product_id = ? and online_return.status <> 'RECHAZADA'
			""", Integer.class, orderId, productId);
		return value == null ? 0 : value;
	}
	public void decideReturn(UUID returnId, OnlineReturnStatus decision, UUID actor, UUID operationId, String reason) {
		int changed = jdbc.update("""
			update inventory_sales.online_order_return
			set status = ?, decision_operation_id = ?, decided_by_principal_id = ?, decided_at = statement_timestamp(), decision_reason = ?, updated_at = statement_timestamp()
			where id = ? and status = 'SOLICITADA'
			""", decision.name(), operationId, actor, reason, returnId);
		if (changed != 1) throw new IllegalStateException("La devolución cambió antes de registrar su decisión.");
	}
	public void updateReturnLineReceipt(UUID returnLineId, int receivedQuantity) { jdbc.update("update inventory_sales.online_order_return_line set received_quantity = ? where id = ?", receivedQuantity, returnLineId); }
	public void markReturnReceived(UUID returnId, UUID actor) {
		jdbc.update("""
			update inventory_sales.online_order_return set status='RECIBIDA', received_by_principal_id=?, received_at=statement_timestamp(), updated_at=statement_timestamp()
			where id=? and status='AUTORIZADA'
			""", actor, returnId);
	}
	public void updateReturnLineInspection(UUID returnLineId, int restockedQuantity, String result) { jdbc.update("update inventory_sales.online_order_return_line set restocked_quantity=?, inspection_result=? where id=?", restockedQuantity, result, returnLineId); }
	public void markReturnInspected(UUID returnId, OnlineReturnStatus status, UUID actor) {
		jdbc.update("""
			update inventory_sales.online_order_return set status=?, inspected_by_principal_id=?, inspected_at=statement_timestamp(), updated_at=statement_timestamp()
			where id=? and status='RECIBIDA'
			""", status.name(), actor, returnId);
	}
	public void closeReturn(UUID returnId, UUID actor) {
		jdbc.update("""
			update inventory_sales.online_order_return set status='CERRADA', closed_by_principal_id=?, closed_at=statement_timestamp(), updated_at=statement_timestamp()
			where id=? and status in ('INSPECCIONADA_APTA','INSPECCIONADA_NO_APTA')
			""", actor, returnId);
	}
	private String orderQuery(String where) { return """
		select online_order.id, online_order.order_number, online_order.status, online_order.delivery_mode,
		       online_order.coordination_phone, online_order.pickup_location_snapshot, online_order.delivery_address,
		       online_order.delivery_coverage_snapshot, online_order.subtotal_pen, online_order.delivery_fee_pen,
		       online_order.total_pen, coalesce((select payment.status from inventory_sales.online_order_manual_payment payment
		          where payment.tenant_id = online_order.tenant_id and payment.order_id = online_order.id
		          order by payment.payment_number desc limit 1), 'NO_DECLARADO') as payment_status, online_order.created_at
		from inventory_sales.online_order online_order
		""" + where; }
	private String paymentQuery(String where) { return """
		select payment.id, payment.order_id, payment.payment_number, payment.payment_method, payment.declared_amount_pen,
		       payment.status, payment.reference, payment.declared_at, payment.decided_at, payment.decision_reason
		from inventory_sales.online_order_manual_payment payment
		""" + where; }
	private String deliveryRecordQuery(String where) { return """
		select record.id, record.order_id, record.delivery_mode, record.receiver, record.proof_note, record.delivery_fee_pen, record.delivered_at
		from inventory_sales.online_order_delivery_record record
		""" + where; }
	private String cancellationQuery(String where) { return """
		select cancellation.id, cancellation.order_id, cancellation.origin, cancellation.reason, cancellation.source_order_status,
		       cancellation.status, cancellation.stock_restocked, cancellation.financial_resolution_pending,
		       cancellation.requested_at, cancellation.resolved_at
		from inventory_sales.online_order_cancellation cancellation
		""" + where; }
	private String onlineReturnQuery(String where) { return """
		select online_return.id, online_return.order_id, online_return.reason, online_return.status, online_return.requested_at,
		       online_return.decided_at, online_return.received_at, online_return.inspected_at, online_return.closed_at
		from inventory_sales.online_order_return online_return
		""" + where; }
	private String returnLineQuery(String where) { return """
		select return_line.id, return_line.product_id, return_line.product_sku_snapshot, return_line.product_title_snapshot,
		       return_line.requested_quantity, return_line.received_quantity, return_line.restocked_quantity, return_line.inspection_result
		from inventory_sales.online_order_return_line return_line
		""" + where; }
	private String proposalQuery(String where) { return """
		select proposal.id, proposal.order_id, proposal.proposal_number, proposal.status, proposal.reason, proposal.created_at, proposal.decided_at,
		       proposal.previous_delivery_mode, proposal.previous_coordination_phone, proposal.previous_pickup_location, proposal.previous_delivery_address, proposal.previous_delivery_coverage,
		       proposal.previous_subtotal_pen, proposal.previous_delivery_fee_pen, proposal.previous_total_pen,
		       proposal.proposed_delivery_mode, proposal.proposed_coordination_phone, proposal.proposed_pickup_location, proposal.proposed_delivery_address, proposal.proposed_delivery_coverage,
		       proposal.proposed_subtotal_pen, proposal.proposed_delivery_fee_pen, proposal.proposed_total_pen
		from inventory_sales.online_order_proposal proposal
		""" + where; }
	private String managementQuery(String where) { return """
		select online_order.id, online_order.order_number, online_order.status, online_order.delivery_mode,
		       coalesce(nullif(btrim(concat_ws(' ', owner.given_names, owner.family_names)), ''), 'Cliente registrado') as buyer_name,
		       online_order.coordination_phone, online_order.pickup_location_snapshot, online_order.delivery_address,
		       online_order.delivery_coverage_snapshot, online_order.subtotal_pen, online_order.delivery_fee_pen,
		       online_order.total_pen,
		       coalesce((select payment.status from inventory_sales.online_order_manual_payment payment
		         where payment.tenant_id = online_order.tenant_id and payment.order_id = online_order.id
		         order by payment.payment_number desc limit 1), 'NO_DECLARADO') as payment_status,
		       (select event.reason from inventory_sales.online_order_status_event event
		         where event.order_id = online_order.id and event.status = 'EN_REVISION'
		         order by event.created_at desc, event.id desc limit 1) as review_reason,
		       online_order.created_at
		from inventory_sales.online_order online_order
		left join owner_pet_care.client_owner_link client_link
		  on client_link.tenant_id = online_order.tenant_id and client_link.client_principal_id = online_order.client_principal_id
		left join owner_pet_care.owner_profile owner
		  on owner.tenant_id = client_link.tenant_id and owner.id = client_link.owner_id
		""" + where; }
	private DeliveryConfiguration mapConfiguration(ResultSet rs, int n) throws SQLException { return new DeliveryConfiguration(rs.getString("pickup_location"), rs.getBoolean("delivery_enabled"), rs.getBigDecimal("fixed_delivery_fee_pen"), rs.getString("coverage_description"), instant(rs, "updated_at")); }
	private Product mapProduct(ResultSet rs, int n) throws SQLException { return new Product(rs.getObject("id", UUID.class), rs.getString("sku"), rs.getString("title"), rs.getBigDecimal("sale_price_pen"), rs.getString("status"), rs.getBoolean("inventory_managed")); }
	private Order mapOrder(ResultSet rs, int n) throws SQLException { return new Order(rs.getObject("id", UUID.class), rs.getLong("order_number"), rs.getString("status"), DeliveryMode.valueOf(rs.getString("delivery_mode")), rs.getString("coordination_phone"), rs.getString("pickup_location_snapshot"), rs.getString("delivery_address"), rs.getString("delivery_coverage_snapshot"), rs.getBigDecimal("subtotal_pen"), rs.getBigDecimal("delivery_fee_pen"), rs.getBigDecimal("total_pen"), instant(rs, "created_at"), List.of(), List.of(), List.of(), rs.getString("payment_status"), List.of(), null, null, List.of()); }
	private StaffOrder mapStaffOrder(ResultSet rs, int n) throws SQLException { return new StaffOrder(rs.getObject("id", UUID.class), rs.getLong("order_number"), rs.getString("status"), DeliveryMode.valueOf(rs.getString("delivery_mode")), rs.getString("buyer_name"), rs.getString("coordination_phone"), rs.getString("pickup_location_snapshot"), rs.getString("delivery_address"), rs.getString("delivery_coverage_snapshot"), rs.getBigDecimal("subtotal_pen"), rs.getBigDecimal("delivery_fee_pen"), rs.getBigDecimal("total_pen"), rs.getString("review_reason"), instant(rs, "created_at"), List.of(), List.of(), List.of(), rs.getString("payment_status"), List.of(), null, null, List.of()); }
	private Proposal mapProposal(ResultSet rs, int n) throws SQLException {
		Terms previous = new Terms(DeliveryMode.valueOf(rs.getString("previous_delivery_mode")), rs.getString("previous_coordination_phone"), rs.getString("previous_pickup_location"), rs.getString("previous_delivery_address"), rs.getString("previous_delivery_coverage"), rs.getBigDecimal("previous_subtotal_pen"), rs.getBigDecimal("previous_delivery_fee_pen"), rs.getBigDecimal("previous_total_pen"), List.of());
		Terms proposed = new Terms(DeliveryMode.valueOf(rs.getString("proposed_delivery_mode")), rs.getString("proposed_coordination_phone"), rs.getString("proposed_pickup_location"), rs.getString("proposed_delivery_address"), rs.getString("proposed_delivery_coverage"), rs.getBigDecimal("proposed_subtotal_pen"), rs.getBigDecimal("proposed_delivery_fee_pen"), rs.getBigDecimal("proposed_total_pen"), List.of());
		return new Proposal(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getInt("proposal_number"), ProposalStatus.valueOf(rs.getString("status")), rs.getString("reason"), instant(rs, "created_at"), instant(rs, "decided_at"), previous, proposed);
	}
	private Line mapLine(ResultSet rs, int n) throws SQLException { return new Line(rs.getObject("product_id", UUID.class), rs.getString("product_sku_snapshot"), rs.getString("product_title_snapshot"), rs.getInt("quantity"), rs.getBigDecimal("unit_price_pen"), rs.getBigDecimal("subtotal_pen")); }
	private ManualPayment mapPayment(ResultSet rs, int n) throws SQLException { return new ManualPayment(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getInt("payment_number"), rs.getString("payment_method"), rs.getBigDecimal("declared_amount_pen"), ManualPaymentStatus.valueOf(rs.getString("status")), rs.getString("reference"), instant(rs, "declared_at"), instant(rs, "decided_at"), rs.getString("decision_reason")); }
	private DeliveryRecord mapDeliveryRecord(ResultSet rs, int n) throws SQLException { return new DeliveryRecord(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), DeliveryMode.valueOf(rs.getString("delivery_mode")), rs.getString("receiver"), rs.getString("proof_note"), rs.getBigDecimal("delivery_fee_pen"), instant(rs, "delivered_at")); }
	private Cancellation mapCancellation(ResultSet rs, int n) throws SQLException { return new Cancellation(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), CancellationOrigin.valueOf(rs.getString("origin")), rs.getString("reason"), rs.getString("source_order_status"), CancellationStatus.valueOf(rs.getString("status")), rs.getBoolean("stock_restocked"), rs.getBoolean("financial_resolution_pending"), instant(rs, "requested_at"), instant(rs, "resolved_at")); }
	private OnlineReturn mapOnlineReturn(ResultSet rs, int n) throws SQLException { return new OnlineReturn(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getString("reason"), OnlineReturnStatus.valueOf(rs.getString("status")), instant(rs, "requested_at"), instant(rs, "decided_at"), instant(rs, "received_at"), instant(rs, "inspected_at"), instant(rs, "closed_at"), List.of()); }
	private OnlineReturnLine mapOnlineReturnLine(ResultSet rs, int n) throws SQLException { return new OnlineReturnLine(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("product_sku_snapshot"), rs.getString("product_title_snapshot"), rs.getInt("requested_quantity"), rs.getInt("received_quantity"), rs.getInt("restocked_quantity"), rs.getString("inspection_result")); }
	private StatusEvent mapEvent(ResultSet rs, int n) throws SQLException { return new StatusEvent(rs.getString("status"), rs.getString("reason"), instant(rs, "created_at")); }
	private Instant instant(ResultSet rs, String column) throws SQLException { Timestamp value=rs.getTimestamp(column); return value == null ? null : value.toInstant(); }
}
