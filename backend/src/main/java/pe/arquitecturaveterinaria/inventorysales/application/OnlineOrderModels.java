package pe.arquitecturaveterinaria.inventorysales.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** B05-B values. Product and monetary fields are server snapshots. */
public final class OnlineOrderModels {
	private OnlineOrderModels() { }

	public enum DeliveryMode { RECOJO, DELIVERY }
	public enum ProposalStatus { PROPUESTA, ACEPTADA, RECHAZADA, VENCIDA }
	public enum ManualPaymentStatus { DECLARADO, VALIDADO, RECHAZADO }
	public enum CancellationOrigin { CLIENTE, STAFF }
	public enum CancellationStatus { SOLICITADA, CANCELADA, RECHAZADA }
	public enum OnlineReturnStatus { SOLICITADA, AUTORIZADA, RECHAZADA, RECIBIDA, INSPECCIONADA_APTA, INSPECCIONADA_NO_APTA, CERRADA }
	public record DeliveryConfiguration(String pickupLocation, boolean deliveryEnabled, BigDecimal fixedDeliveryFeePen,
			String coverageDescription, Instant updatedAt) { }
	public record CheckoutOptions(String pickupLocation, boolean deliveryAvailable, BigDecimal fixedDeliveryFeePen,
			String coverageDescription) { }
	public record Draft(UUID operationId, DeliveryMode deliveryMode, String coordinationPhone, String deliveryAddress,
			BigDecimal expectedDeliveryFeePen, List<LineDraft> lines) { }
	public record LineDraft(UUID productId, int quantity, BigDecimal expectedUnitPricePen) { }
	public record Line(UUID productId, String sku, String title, int quantity, BigDecimal unitPricePen, BigDecimal subtotalPen) { }
	public record StatusEvent(String status, String reason, Instant createdAt) { }
	/** Immutable terms captured for a proposal side; a proposal never changes the original order request. */
	public record Terms(DeliveryMode deliveryMode, String coordinationPhone, String pickupLocation, String deliveryAddress,
			String deliveryCoverage, BigDecimal subtotalPen, BigDecimal deliveryFeePen, BigDecimal totalPen, List<Line> lines) { }
	public record ProposalDraft(UUID operationId, String reason, DeliveryMode deliveryMode, String coordinationPhone,
			String deliveryAddress, List<ProposalLineDraft> lines) { }
	public record ProposalLineDraft(UUID productId, int quantity, BigDecimal unitPricePen) { }
	public record Proposal(UUID id, UUID orderId, int proposalNumber, ProposalStatus status, String reason, Instant createdAt,
			Instant decidedAt, Terms previousTerms, Terms proposedTerms) { }
	public record ProposalDecision(UUID operationId, ProposalStatus decision) { }
	/** A manual payment is only an auditable commercial declaration; it never moves stock. */
	public record ManualPaymentDraft(UUID operationId, String paymentMethod, BigDecimal declaredAmountPen, String reference) { }
	public record ManualPaymentDecision(UUID operationId, ManualPaymentStatus decision, String reason) { }
	public record ManualPayment(UUID id, UUID orderId, int paymentNumber, String paymentMethod, BigDecimal declaredAmountPen,
			ManualPaymentStatus status, String reference, Instant declaredAt, Instant decidedAt, String decisionReason) { }
	/** The one physical handover record is created only when an order reaches ENTREGADO. */
	public record DeliveryCompletionDraft(UUID operationId, String receiver, String proofNote) { }
	public record DeliveryRecord(UUID id, UUID orderId, DeliveryMode deliveryMode, String receiver, String proofNote,
			BigDecimal deliveryFeePen, Instant deliveredAt) { }
	/** A cancellation never changes validated manual-payment records or implies a refund. */
	public record Cancellation(UUID id, UUID orderId, CancellationOrigin origin, String reason, String sourceOrderStatus,
			CancellationStatus status, boolean stockRestocked, boolean financialResolutionPending, Instant requestedAt,
			Instant resolvedAt) { }
	public record CancellationDraft(UUID operationId, String reason) { }
	public record CancellationDecision(UUID operationId, CancellationStatus decision, String reason) { }
	public record OnlineReturnLine(UUID id, UUID productId, String sku, String title, int requestedQuantity,
			int receivedQuantity, int restockedQuantity, String inspectionResult) { }
	/** Return is a sub-process of an delivered order; its records keep the physical inspection auditable. */
	public record OnlineReturn(UUID id, UUID orderId, String reason, OnlineReturnStatus status, Instant requestedAt,
			Instant decidedAt, Instant receivedAt, Instant inspectedAt, Instant closedAt, List<OnlineReturnLine> lines) { }
	public record ReturnDraft(UUID operationId, String reason, List<ReturnLineDraft> lines) { }
	public record ReturnLineDraft(UUID productId, int quantity) { }
	public record ReturnDecision(UUID operationId, OnlineReturnStatus decision, String reason) { }
	public record ReturnReceiptLineDraft(UUID returnLineId, int receivedQuantity) { }
	public record ReturnInspectionLineDraft(UUID returnLineId, int restockedQuantity) { }
	public record Order(UUID id, long orderNumber, String status, DeliveryMode deliveryMode, String coordinationPhone,
			String pickupLocation, String deliveryAddress, String deliveryCoverage, BigDecimal subtotalPen,
			BigDecimal deliveryFeePen, BigDecimal totalPen, Instant createdAt, List<Line> lines, List<StatusEvent> history,
			List<Proposal> proposals, String paymentStatus, List<ManualPayment> payments, DeliveryRecord deliveryRecord,
			Cancellation cancellation, List<OnlineReturn> returns) { }
	/** Commercial projection: it excludes the client principal and only exposes operational coordination data. */
	public record StaffOrder(UUID id, long orderNumber, String status, DeliveryMode deliveryMode, String buyerName,
			String coordinationPhone, String pickupLocation, String deliveryAddress, String deliveryCoverage,
			BigDecimal subtotalPen, BigDecimal deliveryFeePen, BigDecimal totalPen, String reviewReason, Instant createdAt,
			List<Line> lines, List<StatusEvent> history, List<Proposal> proposals, String paymentStatus,
			List<ManualPayment> payments, DeliveryRecord deliveryRecord, Cancellation cancellation,
			List<OnlineReturn> returns) { }
	public record Product(UUID id, String sku, String title, BigDecimal salePricePen, String status, boolean inventoryManaged) { }
}
