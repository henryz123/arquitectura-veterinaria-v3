package pe.arquitecturaveterinaria.inventorysales.api;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import pe.arquitecturaveterinaria.identityaccess.application.SessionPrincipal;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.*;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderService;
import pe.arquitecturaveterinaria.shared.api.ApiEnvelope;

@RestController @RequestMapping("/api/v1/online-orders") @Validated
public class OnlineOrderController {
	private final OnlineOrderService service;
	public OnlineOrderController(OnlineOrderService service){this.service=service;}
	@GetMapping("/checkout-options") ApiEnvelope<CheckoutOptions> options(@AuthenticationPrincipal SessionPrincipal p){return ApiEnvelope.of(service.checkoutOptions(p));}
	@GetMapping ApiEnvelope<List<Order>> list(@AuthenticationPrincipal SessionPrincipal p){return ApiEnvelope.of(service.listOwn(p));}
	@GetMapping("/management") ApiEnvelope<List<StaffOrder>> listManagement(@AuthenticationPrincipal SessionPrincipal p){return ApiEnvelope.of(service.listManagement(p));}
	@GetMapping("/management/{orderId}") ApiEnvelope<StaffOrder> findManagement(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID orderId){return ApiEnvelope.of(service.findManagement(p,orderId));}
	@PostMapping("/management/{orderId}/proposals") ResponseEntity<ApiEnvelope<StaffOrder>> propose(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID orderId,@Valid @RequestBody ProposalRequest r){
		StaffOrder order=service.propose(p,orderId,new ProposalDraft(r.operationId(),r.reason(),r.deliveryMode(),r.coordinationPhone(),r.deliveryAddress(),r.lines().stream().map(line->new ProposalLineDraft(line.productId(),line.quantity(),line.unitPricePen())).toList()));
		return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/api/v1/online-orders/management/"+orderId)).body(ApiEnvelope.of(order));
	}
	@PostMapping("/management/{orderId}/confirmation") ApiEnvelope<StaffOrder> confirm(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID orderId){return ApiEnvelope.of(service.confirm(p,orderId));}
	@PostMapping("/management/{orderId}/payments") ResponseEntity<ApiEnvelope<StaffOrder>> recordPayment(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID orderId,@Valid @RequestBody ManualPaymentRequest r){
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiEnvelope.of(service.recordManualPayment(p,orderId,new ManualPaymentDraft(r.operationId(),r.paymentMethod(),r.declaredAmountPen(),r.reference()))));
	}
	@PostMapping("/management/{orderId}/payments/{paymentId}/decision") ApiEnvelope<StaffOrder> decidePayment(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID orderId,@PathVariable UUID paymentId,@Valid @RequestBody ManualPaymentDecisionRequest r){
		return ApiEnvelope.of(service.decideManualPayment(p,orderId,paymentId,new ManualPaymentDecision(r.operationId(),r.decision(),r.reason())));
	}
	@PostMapping("/management/{orderId}/preparation") ApiEnvelope<StaffOrder> startPreparation(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID orderId,@Valid @RequestBody OperationRequest r){return ApiEnvelope.of(service.startPreparation(p,orderId,r.operationId()));}
	@PostMapping("/management/{orderId}/ready-for-pickup") ApiEnvelope<StaffOrder> readyForPickup(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID orderId,@Valid @RequestBody OperationRequest r){return ApiEnvelope.of(service.markReadyForPickup(p,orderId,r.operationId()));}
	@PostMapping("/management/{orderId}/dispatch") ApiEnvelope<StaffOrder> dispatch(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID orderId,@Valid @RequestBody OperationRequest r){return ApiEnvelope.of(service.markDispatched(p,orderId,r.operationId()));}
	@PostMapping("/management/{orderId}/delivery") ApiEnvelope<StaffOrder> completeDelivery(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID orderId,@Valid @RequestBody DeliveryCompletionRequest r){return ApiEnvelope.of(service.completeDelivery(p,orderId,new DeliveryCompletionDraft(r.operationId(),r.receiver(),r.proofNote())));}
	@PostMapping("/{orderId}/cancellation") ApiEnvelope<Order> cancelPending(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID orderId,@Valid @RequestBody CancellationRequest r){return ApiEnvelope.of(service.cancelPending(p,orderId,new CancellationDraft(r.operationId(),r.reason())));}
	@PostMapping("/{orderId}/cancellation-request") ApiEnvelope<Order> requestCancellation(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID orderId,@Valid @RequestBody CancellationRequest r){return ApiEnvelope.of(service.requestCancellation(p,orderId,new CancellationDraft(r.operationId(),r.reason())));}
	@PostMapping("/management/{orderId}/cancellation") ApiEnvelope<StaffOrder> cancelPreDispatch(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID orderId,@Valid @RequestBody CancellationRequest r){return ApiEnvelope.of(service.cancelPreDispatch(p,orderId,new CancellationDraft(r.operationId(),r.reason())));}
	@PostMapping("/management/cancellations/{cancellationId}/decision") ApiEnvelope<StaffOrder> decideCancellation(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID cancellationId,@Valid @RequestBody CancellationDecisionRequest r){return ApiEnvelope.of(service.decideCancellation(p,cancellationId,new CancellationDecision(r.operationId(),r.decision(),r.reason())));}
	@PostMapping("/{orderId}/returns") ResponseEntity<ApiEnvelope<Order>> requestReturn(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID orderId,@Valid @RequestBody ReturnRequest r){Order order=service.requestReturn(p,orderId,new ReturnDraft(r.operationId(),r.reason(),r.lines().stream().map(line->new ReturnLineDraft(line.productId(),line.quantity())).toList()));return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/api/v1/online-orders/"+orderId)).body(ApiEnvelope.of(order));}
	@PostMapping("/management/returns/{returnId}/decision") ApiEnvelope<StaffOrder> decideReturn(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID returnId,@Valid @RequestBody ReturnDecisionRequest r){return ApiEnvelope.of(service.decideReturn(p,returnId,new ReturnDecision(r.operationId(),r.decision(),r.reason())));}
	@PostMapping("/management/returns/{returnId}/receipt") ApiEnvelope<StaffOrder> receiveReturn(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID returnId,@Valid @RequestBody ReturnReceiptRequest r){return ApiEnvelope.of(service.receiveReturn(p,returnId,r.lines().stream().map(line->new ReturnReceiptLineDraft(line.returnLineId(),line.receivedQuantity())).toList()));}
	@PostMapping("/management/returns/{returnId}/inspection") ApiEnvelope<StaffOrder> inspectReturn(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID returnId,@Valid @RequestBody ReturnInspectionRequest r){return ApiEnvelope.of(service.inspectReturn(p,returnId,r.lines().stream().map(line->new ReturnInspectionLineDraft(line.returnLineId(),line.restockedQuantity())).toList()));}
	@PostMapping("/management/returns/{returnId}/closure") ApiEnvelope<StaffOrder> closeReturn(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID returnId){return ApiEnvelope.of(service.closeReturn(p,returnId));}
	@PostMapping("/proposals/{proposalId}/decision") ApiEnvelope<Order> decideProposal(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID proposalId,@Valid @RequestBody ProposalDecisionRequest r){return ApiEnvelope.of(service.decideProposal(p,proposalId,new ProposalDecision(r.operationId(),r.decision())));}
	@GetMapping("/{orderId}") ApiEnvelope<Order> find(@AuthenticationPrincipal SessionPrincipal p,@PathVariable UUID orderId){return ApiEnvelope.of(service.findOwn(p,orderId));}
	@PostMapping ResponseEntity<ApiEnvelope<Order>> create(@AuthenticationPrincipal SessionPrincipal p,@Valid @RequestBody CreateOrderRequest r){Order o=service.create(p,new Draft(r.operationId(),r.deliveryMode(),r.coordinationPhone(),r.deliveryAddress(),r.expectedDeliveryFeePen(),r.lines().stream().map(x->new LineDraft(x.productId(),x.quantity(),x.expectedUnitPricePen())).toList()));return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/api/v1/online-orders/"+o.id())).body(ApiEnvelope.of(o));}
	@GetMapping("/delivery-configuration") ApiEnvelope<DeliveryConfiguration> configuration(@AuthenticationPrincipal SessionPrincipal p){return ApiEnvelope.of(service.deliveryConfiguration(p));}
	@PutMapping("/delivery-configuration") ApiEnvelope<DeliveryConfiguration> configure(@AuthenticationPrincipal SessionPrincipal p,@Valid @RequestBody DeliveryConfigurationRequest r){return ApiEnvelope.of(service.updateDeliveryConfiguration(p,r.pickupLocation(),r.deliveryEnabled(),r.fixedDeliveryFeePen(),r.coverageDescription()));}
	public record CreateOrderRequest(@NotNull UUID operationId,@NotNull DeliveryMode deliveryMode,@NotBlank @Size(max=40) String coordinationPhone,@Size(max=240) String deliveryAddress,@DecimalMin("0.00") BigDecimal expectedDeliveryFeePen,@NotEmpty @Size(max=100) List<@Valid OrderLineRequest> lines){}
	public record OrderLineRequest(@NotNull UUID productId,@NotNull @Positive Integer quantity,@NotNull @DecimalMin("0.00") BigDecimal expectedUnitPricePen){}
	public record ProposalRequest(@NotNull UUID operationId,@NotBlank @Size(min=3,max=500) String reason,@NotNull DeliveryMode deliveryMode,@NotBlank @Size(max=40) String coordinationPhone,@Size(max=240) String deliveryAddress,@NotEmpty @Size(max=100) List<@Valid ProposalLineRequest> lines){}
	public record ProposalLineRequest(@NotNull UUID productId,@NotNull @Positive Integer quantity,@NotNull @DecimalMin("0.00") BigDecimal unitPricePen){}
	public record ProposalDecisionRequest(@NotNull UUID operationId,@NotNull ProposalStatus decision){}
	public record ManualPaymentRequest(@NotNull UUID operationId,@NotBlank @Size(max=16) String paymentMethod,@NotNull @DecimalMin("0.00") BigDecimal declaredAmountPen,@Size(max=120) String reference){}
	public record ManualPaymentDecisionRequest(@NotNull UUID operationId,@NotNull ManualPaymentStatus decision,@Size(max=500) String reason){}
	public record OperationRequest(@NotNull UUID operationId){}
	public record DeliveryCompletionRequest(@NotNull UUID operationId,@NotBlank @Size(max=160) String receiver,@Size(max=240) String proofNote){}
	public record CancellationRequest(@NotNull UUID operationId,@NotBlank @Size(min=3,max=500) String reason){}
	public record CancellationDecisionRequest(@NotNull UUID operationId,@NotNull CancellationStatus decision,@NotBlank @Size(min=3,max=500) String reason){}
	public record ReturnRequest(@NotNull UUID operationId,@NotBlank @Size(min=3,max=500) String reason,@NotEmpty @Size(max=100) List<@Valid ReturnLineRequest> lines){}
	public record ReturnLineRequest(@NotNull UUID productId,@NotNull @Positive Integer quantity){}
	public record ReturnDecisionRequest(@NotNull UUID operationId,@NotNull OnlineReturnStatus decision,@Size(max=500) String reason){}
	public record ReturnReceiptRequest(@NotEmpty @Size(max=100) List<@Valid ReturnReceiptLineRequest> lines){}
	public record ReturnReceiptLineRequest(@NotNull UUID returnLineId,@NotNull @PositiveOrZero Integer receivedQuantity){}
	public record ReturnInspectionRequest(@NotEmpty @Size(max=100) List<@Valid ReturnInspectionLineRequest> lines){}
	public record ReturnInspectionLineRequest(@NotNull UUID returnLineId,@NotNull @PositiveOrZero Integer restockedQuantity){}
	public record DeliveryConfigurationRequest(@NotBlank @Size(max=240) String pickupLocation,@NotNull Boolean deliveryEnabled,@NotNull @DecimalMin("0.00") BigDecimal fixedDeliveryFeePen,@Size(max=500) String coverageDescription){}
}
