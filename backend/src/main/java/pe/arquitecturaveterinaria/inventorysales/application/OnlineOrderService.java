package pe.arquitecturaveterinaria.inventorysales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.arquitecturaveterinaria.identityaccess.application.SessionPrincipal;
import pe.arquitecturaveterinaria.identityaccess.infrastructure.TenantContextJdbc;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.CheckoutOptions;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Cancellation;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.CancellationDecision;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.CancellationDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.CancellationOrigin;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.CancellationStatus;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.DeliveryConfiguration;

import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.DeliveryCompletionDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.DeliveryRecord;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.DeliveryMode;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Draft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Line;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.LineDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ManualPayment;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ManualPaymentDecision;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ManualPaymentDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ManualPaymentStatus;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Order;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Product;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.OnlineReturn;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.OnlineReturnLine;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.OnlineReturnStatus;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Proposal;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ProposalDecision;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ProposalDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ProposalLineDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ProposalStatus;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.StaffOrder;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.Terms;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ReturnDecision;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ReturnDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ReturnInspectionLineDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ReturnLineDraft;
import pe.arquitecturaveterinaria.inventorysales.application.OnlineOrderModels.ReturnReceiptLineDraft;
import pe.arquitecturaveterinaria.inventorysales.infrastructure.OnlineOrderJdbcRepository;
import pe.arquitecturaveterinaria.inventorysales.infrastructure.StockJdbcRepository;
import pe.arquitecturaveterinaria.notificationsreporting.application.InternalNotificationService;
import pe.arquitecturaveterinaria.notificationsreporting.application.NotificationDeliveryRequest;
import pe.arquitecturaveterinaria.shared.api.ApiException;

@Service
public class OnlineOrderService {
	private final OnlineOrderJdbcRepository repository;
	private final StockJdbcRepository stockRepository;
	private final TenantContextJdbc tenantContext;
	private final InternalNotificationService internalNotifications;
	private final OnlineOrderAccessPolicy access = new OnlineOrderAccessPolicy();
	public OnlineOrderService(OnlineOrderJdbcRepository repository, StockJdbcRepository stockRepository, TenantContextJdbc tenantContext,
			InternalNotificationService internalNotifications) { this.repository=repository; this.stockRepository=stockRepository; this.tenantContext=tenantContext; this.internalNotifications=internalNotifications; }

	@Transactional(transactionManager="jdbcTransactionManager", readOnly=true)
	public CheckoutOptions checkoutOptions(SessionPrincipal actor) {
		access.requireClient(actor); setContext(actor);
		DeliveryConfiguration c=repository.findConfiguration().orElse(null);
		return c == null ? new CheckoutOptions(null,false,null,null) : new CheckoutOptions(c.pickupLocation(),c.deliveryEnabled(),c.fixedDeliveryFeePen(),c.coverageDescription());
	}
	@Transactional(transactionManager="jdbcTransactionManager")
	public Order create(SessionPrincipal actor, Draft command) {
		access.requireClient(actor); setContext(actor); Draft draft=draft(command); UUID tenant=actor.session().tenantId();
		repository.lockOperation(tenant,draft.operationId());
		Order replay=repository.findByOperation(draft.operationId()).orElse(null);
		if(replay!=null) { Order detail=detail(replay); if(!same(draft,detail)) throw conflict("B05_ORDER_REPLAY_CONFLICT","La clave de operación ya fue utilizada con un pedido distinto."); return detail; }
		DeliveryConfiguration config=repository.findConfiguration().orElseThrow(() -> conflict("B05_CHECKOUT_NOT_CONFIGURED","La veterinaria aún no configuró el punto de recojo."));
		String pickup=null,address=null,coverage=null; BigDecimal deliveryFee=BigDecimal.ZERO.setScale(2);
		if(draft.deliveryMode()==DeliveryMode.RECOJO) pickup=config.pickupLocation();
		else { if(!config.deliveryEnabled()) throw conflict("B05_DELIVERY_NOT_AVAILABLE","Delivery no está disponible para esta veterinaria."); address=required(draft.deliveryAddress(),"deliveryAddress","Ingresa la dirección de entrega."); coverage=config.coverageDescription(); deliveryFee=config.fixedDeliveryFeePen(); if(draft.expectedDeliveryFeePen()!=null && draft.expectedDeliveryFeePen().compareTo(deliveryFee)!=0) throw conflict("B05_DELIVERY_FEE_CHANGED","La tarifa de delivery cambió. Revisa tu pedido."); }
		List<PreparedLine> prepared=new ArrayList<>(); BigDecimal subtotal=BigDecimal.ZERO.setScale(2);
		for(LineDraft line:draft.lines()) { Product product=repository.lockPublishedPhysicalProduct(line.productId()).orElseThrow(() -> conflict("B05_PRODUCT_UNAVAILABLE","Un producto ya no está disponible. Revisa tu carrito."));
			if(!"ACTIVO_PUBLICADO".equals(product.status()) || !product.inventoryManaged()) throw conflict("B05_PRODUCT_UNAVAILABLE","Un producto ya no está disponible. Revisa tu carrito.");
			if(line.expectedUnitPricePen()==null || line.expectedUnitPricePen().compareTo(product.salePricePen())!=0) throw conflict("B05_PRICE_CHANGED","El precio de un producto cambió. Revisa tu carrito.");
			BigDecimal lineSubtotal=product.salePricePen().multiply(BigDecimal.valueOf(line.quantity())).setScale(2,RoundingMode.UNNECESSARY); prepared.add(new PreparedLine(product,line.quantity(),lineSubtotal)); subtotal=subtotal.add(lineSubtotal).setScale(2,RoundingMode.UNNECESSARY); }
		BigDecimal total=subtotal.add(deliveryFee).setScale(2,RoundingMode.UNNECESSARY);
		Order order=repository.insertOrder(tenant,repository.nextOrderNumber(tenant),actor.session().accountId(),draft.operationId(),draft.deliveryMode(),draft.coordinationPhone(),pickup,address,coverage,subtotal,deliveryFee,total);
		for(PreparedLine line:prepared) repository.insertLine(tenant,order.id(),line.product(),line.quantity(),line.subtotal());
		UUID eventId=repository.insertInitialEvent(tenant,order.id(),actor.session().accountId(),draft.operationId());
		notifyPurchaser(tenant,order.id(),eventId,"ONLINE_ORDER_CREATED");
		notifyCommercialStaff(tenant,order.id(),eventId,"ONLINE_ORDER_CREATED");
		return detail(order);
	}
	@Transactional(transactionManager="jdbcTransactionManager",readOnly=true)
	public List<Order> listOwn(SessionPrincipal actor) { access.requireClient(actor); setContext(actor); return repository.listOwn(actor.session().accountId()).stream().map(this::detail).toList(); }
	@Transactional(transactionManager="jdbcTransactionManager",readOnly=true)
	public Order findOwn(SessionPrincipal actor, UUID id) { access.requireClient(actor); setContext(actor); return detail(repository.findOwn(requiredId(id),actor.session().accountId()).orElseThrow(() -> notFound("No se encontró el pedido solicitado."))); }
	@Transactional(transactionManager="jdbcTransactionManager",readOnly=true)
	public List<StaffOrder> listManagement(SessionPrincipal actor) { access.requireCommercialOperator(actor); setContext(actor); return repository.listManagement().stream().map(this::staffDetail).toList(); }
	@Transactional(transactionManager="jdbcTransactionManager",readOnly=true)
	public StaffOrder findManagement(SessionPrincipal actor, UUID id) { access.requireCommercialOperator(actor); setContext(actor); return staffDetail(repository.findManagement(requiredId(id)).orElseThrow(() -> notFound("No se encontró el pedido solicitado."))); }
	/** STAFF proposes replacement terms only while an insufficient order is being reviewed. */
	@Transactional(transactionManager="jdbcTransactionManager")
	public StaffOrder propose(SessionPrincipal actor, UUID id, ProposalDraft command) {
		access.requireCommercialOperator(actor); setContext(actor); UUID orderId=requiredId(id); ProposalDraft draft=proposalDraft(command);
		repository.lockOperation(actor.session().tenantId(),draft.operationId());
		Proposal replay=repository.findProposalByCreationOperation(draft.operationId()).orElse(null);
		if(replay!=null) {
			if(!replay.orderId().equals(orderId)||!sameProposalCommand(replay,draft)) throw conflict("B05_PROPOSAL_REPLAY_CONFLICT","La clave de operación ya fue usada con una propuesta distinta.");
			return currentManagement(orderId);
		}
		Order order=repository.lockOrder(orderId).orElseThrow(() -> notFound("No se encontró el pedido solicitado."));
		if(!"EN_REVISION".equals(order.status())) throw conflict("B05_PROPOSAL_STATE","Solo un pedido en revisión puede recibir una propuesta.");
		Terms previous=effectiveTerms(order);
		Terms proposed=prepareProposedTerms(actor,draft);
		Proposal proposal=repository.insertProposal(actor.session().tenantId(),orderId,draft.operationId(),draft.reason(),previous,proposed,actor.session().accountId());
		for(Line line:previous.lines()) repository.insertProposalLine(actor.session().tenantId(),proposal.id(),"ANTERIOR",line);
		for(Line line:proposed.lines()) repository.insertProposalLine(actor.session().tenantId(),proposal.id(),"PROPUESTA",line);
		repository.transitionOrderStatus(orderId,"EN_REVISION","CAMBIO_PROPUESTO");
		repository.insertStatusEvent(actor.session().tenantId(),orderId,"CAMBIO_PROPUESTO",actor.session().accountId(),draft.operationId(),draft.reason());
		return currentManagement(orderId);
	}
	/** Only the purchaser may decide one pending proposal. A decision never reserves stock. */
	@Transactional(transactionManager="jdbcTransactionManager")
	public Order decideProposal(SessionPrincipal actor, UUID proposalId, ProposalDecision command) {
		access.requireClient(actor); setContext(actor); ProposalDecision decision=proposalDecision(command); UUID id=requiredId(proposalId);
		repository.lockOperation(actor.session().tenantId(),decision.operationId());
		Proposal replay=repository.findProposalByDecisionOperation(decision.operationId()).orElse(null);
		if(replay!=null) {
			if(!replay.id().equals(id) || replay.status()!=decision.decision()) throw conflict("B05_PROPOSAL_REPLAY_CONFLICT","La clave de decisión ya fue utilizada con otra respuesta.");
			return detail(repository.findOwn(replay.orderId(),actor.session().accountId()).orElseThrow(() -> notFound("No se encontró el pedido solicitado.")));
		}
		Proposal reference=repository.findOwnProposal(id,actor.session().accountId()).orElseThrow(() -> notFound("No se encontró la propuesta solicitada."));
		Order order=repository.lockOrder(reference.orderId()).orElseThrow(() -> notFound("No se encontró el pedido solicitado."));
		Proposal proposal=repository.lockProposal(id).orElseThrow(() -> notFound("No se encontró la propuesta solicitada."));
		if(proposal.status()!=ProposalStatus.PROPUESTA || !"CAMBIO_PROPUESTO".equals(order.status())) throw conflict("B05_PROPOSAL_STATE","La propuesta ya fue resuelta o reemplazada.");
		repository.decideProposal(proposal.id(),decision.decision(),actor.session().accountId(),decision.operationId());
		repository.transitionOrderStatus(order.id(),"CAMBIO_PROPUESTO","EN_REVISION");
		String reason=decision.decision()==ProposalStatus.ACEPTADA ? "El cliente aceptó la propuesta; queda pendiente de revalidación de stock." : "El cliente rechazó la propuesta; no se modificaron las condiciones ni el inventario.";
		repository.insertStatusEvent(actor.session().tenantId(),order.id(),"EN_REVISION",actor.session().accountId(),decision.operationId(),reason);
		return detail(repository.findOwn(order.id(),actor.session().accountId()).orElseThrow(() -> notFound("No se encontró el pedido solicitado.")));
	}
	/** Locks the order and every involved B04 balance before checking any availability or mutating stock. */
	@Transactional(transactionManager="jdbcTransactionManager")
	public StaffOrder confirm(SessionPrincipal actor, UUID id) {
		access.requireCommercialOperator(actor); setContext(actor); UUID orderId=requiredId(id);
		Order order=repository.lockOrder(orderId).orElseThrow(() -> notFound("No se encontró el pedido solicitado."));
		if("CONFIRMADO".equals(order.status())) return currentManagement(orderId);
		if("CAMBIO_PROPUESTO".equals(order.status())) throw conflict("B05_CONFIRMATION_WAITING_CLIENT","La propuesta todavía requiere una decisión explícita del cliente.");
		if(!"PENDIENTE".equals(order.status())&&!"EN_REVISION".equals(order.status())) return currentManagement(orderId);
		Proposal accepted="EN_REVISION".equals(order.status()) ? repository.findLatestAcceptedProposal(orderId).orElseThrow(() -> conflict("B05_CONFIRMATION_REQUIRES_ACCEPTED_PROPOSAL","El pedido en revisión requiere una propuesta aceptada antes de confirmar.")) : null;
		List<Line> lines=(accepted==null?repository.lockLines(orderId):repository.listProposalLines(accepted.id(),"PROPUESTA")).stream().sorted(Comparator.comparing(line -> line.productId().toString())).toList();
		if(lines.isEmpty()) throw conflict("B05_ORDER_EMPTY","El pedido no contiene líneas confirmables.");
		List<PreparedStockLine> prepared=new ArrayList<>(); String reviewReason=null;
		for(Line line:lines) {
			var product=stockRepository.lockInventoryManagedProduct(line.productId()).orElse(null);
			if(product==null || !product.inventoryManaged()) { reviewReason="Una línea ya no corresponde a un producto físico gestionado por inventario."; break; }
		}
		// POS locks all products before balances. Preserve that order so a multi-line
		// online confirmation cannot deadlock with the existing shared-stock flow.
		if(reviewReason==null) for(Line line:lines) {
			StockBalance balance=stockRepository.lockBalance(line.productId()).orElse(null);
			if(balance==null || balance.availableQuantity()<line.quantity()) { reviewReason="Las existencias no alcanzan para confirmar todas las líneas del pedido."; break; }
			prepared.add(new PreparedStockLine(line,balance));
		}
		if(reviewReason!=null) {
			if("PENDIENTE".equals(order.status())) repository.transitionOrderStatus(orderId,"PENDIENTE","EN_REVISION"); else repository.recordReviewAttempt(orderId);
			UUID eventId=repository.insertStatusEvent(actor.session().tenantId(),orderId,"EN_REVISION",actor.session().accountId(),statusEventOperation(orderId,"EN_REVISION",accepted==null?"ORIGINAL":accepted.id().toString()),reviewReason);
			notifyPurchaser(actor.session().tenantId(),orderId,eventId,"ONLINE_ORDER_REVIEW_REQUIRED");
			notifyCommercialStaff(actor.session().tenantId(),orderId,eventId,"ONLINE_ORDER_REVIEW_REQUIRED");
			return currentManagement(orderId);
		}
		for(PreparedStockLine line:prepared) {
			int after=line.balance().availableQuantity()-line.line().quantity();
			stockRepository.updateBalance(line.balance(),after);
			stockRepository.insertOnlineConfirmationMovement(actor.session().tenantId(),line.line().productId(),orderId,line.balance().availableQuantity(),after,
					confirmationMovementOperation(orderId,line.line().productId()),"Confirmación de pedido online #"+order.orderNumber(),actor.session().accountId());
		}
		repository.transitionOrderStatus(orderId,order.status(),"CONFIRMADO");
		UUID eventId=repository.insertStatusEvent(actor.session().tenantId(),orderId,"CONFIRMADO",actor.session().accountId(),statusEventOperation(orderId,"CONFIRMADO",accepted==null?"ORIGINAL":accepted.id().toString()),null);
		notifyPurchaser(actor.session().tenantId(),orderId,eventId,"ONLINE_ORDER_CONFIRMED");
		notifyCommercialStaff(actor.session().tenantId(),orderId,eventId,"ONLINE_ORDER_CONFIRMED");
		return currentManagement(orderId);
	}
	/** Records an auditable staff declaration; validation is intentionally a separate action. */
	@Transactional(transactionManager="jdbcTransactionManager")
	public StaffOrder recordManualPayment(SessionPrincipal actor, UUID id, ManualPaymentDraft command) {
		access.requireCommercialOperator(actor); setContext(actor); UUID orderId=requiredId(id); ManualPaymentDraft draft=manualPaymentDraft(command);
		repository.lockOperation(actor.session().tenantId(),draft.operationId());
		ManualPayment replay=repository.findPaymentByDeclarationOperation(draft.operationId()).orElse(null);
		if(replay!=null) {
			if(!replay.orderId().equals(orderId)||!samePaymentDraft(replay,draft)) throw conflict("B05_PAYMENT_REPLAY_CONFLICT","La clave de operación ya fue utilizada con otro pago manual.");
			return currentManagement(orderId);
		}
		Order order=repository.lockOrder(orderId).orElseThrow(() -> notFound("No se encontró el pedido solicitado."));
		if(!paymentCompatible(order.status())) throw conflict("B05_PAYMENT_STATE","El pedido debe estar confirmado o en gestión logística para registrar un pago manual.");
		ManualPayment previous=repository.listPayments(orderId).stream().reduce((first,second)->second).orElse(null);
		if(previous!=null&&previous.status()==ManualPaymentStatus.DECLARADO) throw conflict("B05_PAYMENT_PENDING","Existe un pago manual pendiente de validación.");
		if(previous!=null&&previous.status()==ManualPaymentStatus.VALIDADO) throw conflict("B05_PAYMENT_ALREADY_VALIDATED","El pedido ya tiene un pago manual validado.");
		Terms terms=effectiveTerms(order);
		if(terms.totalPen().compareTo(draft.declaredAmountPen())!=0) throw validation("declaredAmountPen","El importe manual debe coincidir exactamente con el total operativo aceptado.");
		repository.insertManualPayment(actor.session().tenantId(),orderId,draft.operationId(),draft.paymentMethod(),draft.declaredAmountPen(),draft.reference(),actor.session().accountId());
		return currentManagement(orderId);
	}
	@Transactional(transactionManager="jdbcTransactionManager")
	public StaffOrder decideManualPayment(SessionPrincipal actor, UUID orderId, UUID paymentId, ManualPaymentDecision command) {
		access.requireCommercialOperator(actor); setContext(actor); UUID id=requiredId(orderId); ManualPaymentDecision decision=manualPaymentDecision(command);
		repository.lockOperation(actor.session().tenantId(),decision.operationId());
		ManualPayment replay=repository.findPaymentByDecisionOperation(decision.operationId()).orElse(null);
		if(replay!=null) {
			if(!replay.orderId().equals(id)||!replay.id().equals(paymentId)||replay.status()!=decision.decision()) throw conflict("B05_PAYMENT_REPLAY_CONFLICT","La clave de decisión ya fue utilizada con otra operación.");
			return currentManagement(id);
		}
		Order order=repository.lockOrder(id).orElseThrow(() -> notFound("No se encontró el pedido solicitado."));
		ManualPayment payment=repository.lockPayment(requiredId(paymentId)).orElseThrow(() -> notFound("No se encontró el pago manual solicitado."));
		if(!payment.orderId().equals(id)) throw notFound("No se encontró el pago manual solicitado.");
		if(payment.status()!=ManualPaymentStatus.DECLARADO) {
			if(payment.status()==decision.decision()) return currentManagement(id);
			throw conflict("B05_PAYMENT_STATE","El pago manual ya tiene una decisión definitiva.");
		}
		if(decision.decision()==ManualPaymentStatus.VALIDADO&&payment.declaredAmountPen().compareTo(effectiveTerms(order).totalPen())!=0) throw conflict("B05_PAYMENT_AMOUNT_CHANGED","El importe declarado ya no coincide con las condiciones operativas aceptadas.");
		repository.decideManualPayment(payment.id(),decision.decision(),actor.session().accountId(),decision.operationId(),decision.reason());
		return currentManagement(id);
	}
	@Transactional(transactionManager="jdbcTransactionManager")
	public StaffOrder startPreparation(SessionPrincipal actor, UUID orderId, UUID operationId) {
		return advanceFulfillment(actor,orderId,operationId,"CONFIRMADO","EN_PREPARACION",null);
	}
	@Transactional(transactionManager="jdbcTransactionManager")
	public StaffOrder markReadyForPickup(SessionPrincipal actor, UUID orderId, UUID operationId) {
		return advanceFulfillment(actor,orderId,operationId,"EN_PREPARACION","LISTO_PARA_RECOJO",DeliveryMode.RECOJO);
	}
	@Transactional(transactionManager="jdbcTransactionManager")
	public StaffOrder markDispatched(SessionPrincipal actor, UUID orderId, UUID operationId) {
		return advanceFulfillment(actor,orderId,operationId,"EN_PREPARACION","EN_REPARTO",DeliveryMode.DELIVERY);
	}
	@Transactional(transactionManager="jdbcTransactionManager")
	public StaffOrder completeDelivery(SessionPrincipal actor, UUID orderId, DeliveryCompletionDraft command) {
		access.requireCommercialOperator(actor); setContext(actor); UUID id=requiredId(orderId); DeliveryCompletionDraft draft=deliveryCompletionDraft(command);
		repository.lockOperation(actor.session().tenantId(),draft.operationId());
		DeliveryRecord replay=repository.findDeliveryByOperation(draft.operationId()).orElse(null);
		if(replay!=null) { if(!replay.orderId().equals(id)) throw conflict("B05_FULFILLMENT_REPLAY_CONFLICT","La clave de operación ya fue utilizada para otra entrega."); return currentManagement(id); }
		Order order=repository.lockOrder(id).orElseThrow(() -> notFound("No se encontró el pedido solicitado."));
		if("ENTREGADO".equals(order.status())) return currentManagement(id);
		DeliveryMode mode=effectiveTerms(order).deliveryMode();
		String expected=mode==DeliveryMode.RECOJO?"LISTO_PARA_RECOJO":"EN_REPARTO";
		if(!expected.equals(order.status())) throw conflict("B05_FULFILLMENT_STATE","El pedido no está listo para registrar su entrega física.");
		repository.insertDeliveryRecord(actor.session().tenantId(),id,draft.operationId(),mode,draft.receiver(),draft.proofNote(),effectiveTerms(order).deliveryFeePen(),actor.session().accountId());
		repository.transitionOrderStatus(id,expected,"ENTREGADO");
		UUID eventId=repository.insertStatusEvent(actor.session().tenantId(),id,"ENTREGADO",actor.session().accountId(),draft.operationId(),"Entrega física registrada manualmente.");
		notifyPurchaser(actor.session().tenantId(),id,eventId,"ONLINE_ORDER_DELIVERED");
		return currentManagement(id);
	}
	/** CLIENT may cancel a pending request directly because it has never affected stock. */
	@Transactional(transactionManager="jdbcTransactionManager")
	public Order cancelPending(SessionPrincipal actor, UUID orderId, CancellationDraft command) {
		access.requireClient(actor); setContext(actor); UUID id=requiredId(orderId); CancellationDraft draft=cancellationDraft(command);
		repository.lockOperation(actor.session().tenantId(),draft.operationId());
		Cancellation replay=repository.findCancellationByRequestOperation(draft.operationId()).orElse(null);
		if(replay!=null) { if(!replay.orderId().equals(id)||replay.origin()!=CancellationOrigin.CLIENTE) throw conflict("B05_CANCELLATION_REPLAY_CONFLICT","La clave de operación ya fue utilizada para otra cancelación."); return ownDetail(actor,id); }
		Order order=repository.lockOwnOrder(id,actor.session().accountId()).orElseThrow(() -> notFound("No se encontró el pedido solicitado."));
		if(!"PENDIENTE".equals(order.status())) throw conflict("B05_CANCELLATION_STATE","Solo un pedido pendiente puede cancelarse directamente.");
		Cancellation cancellation=repository.insertCancellation(actor.session().tenantId(),id,draft.operationId(),CancellationOrigin.CLIENTE,draft.reason(),order.status(),false,actor.session().accountId());
		repository.resolveCancellation(cancellation.id(),CancellationStatus.CANCELADA,actor.session().accountId(),draft.operationId(),false);
		repository.transitionOrderStatus(id,"PENDIENTE","CANCELADO");
		UUID eventId=repository.insertStatusEvent(actor.session().tenantId(),id,"CANCELADO",actor.session().accountId(),cancellationEventOperation(id,"DIRECTA_CLIENTE:"+draft.operationId()),"Cancelación directa de pedido pendiente: "+draft.reason());
		notifyPurchaser(actor.session().tenantId(),id,eventId,"ONLINE_ORDER_CANCELLATION_RESOLVED");
		return ownDetail(actor,id);
	}
	/** A client request never restocks by itself; STAFF must resolve it while stock is still physically in the clinic. */
	@Transactional(transactionManager="jdbcTransactionManager")
	public Order requestCancellation(SessionPrincipal actor, UUID orderId, CancellationDraft command) {
		access.requireClient(actor); setContext(actor); UUID id=requiredId(orderId); CancellationDraft draft=cancellationDraft(command);
		repository.lockOperation(actor.session().tenantId(),draft.operationId());
		Cancellation replay=repository.findCancellationByRequestOperation(draft.operationId()).orElse(null);
		if(replay!=null) { if(!replay.orderId().equals(id)||replay.origin()!=CancellationOrigin.CLIENTE) throw conflict("B05_CANCELLATION_REPLAY_CONFLICT","La clave de operación ya fue utilizada para otra cancelación."); return ownDetail(actor,id); }
		Order order=repository.lockOwnOrder(id,actor.session().accountId()).orElseThrow(() -> notFound("No se encontró el pedido solicitado."));
		if(!Set.of("CONFIRMADO","EN_PREPARACION","LISTO_PARA_RECOJO").contains(order.status())) throw conflict("B05_CANCELLATION_STATE","El pedido no admite una solicitud de cancelación en su estado actual.");
		boolean financialPending="VALIDADO".equals(order.paymentStatus());
		repository.insertCancellation(actor.session().tenantId(),id,draft.operationId(),CancellationOrigin.CLIENTE,draft.reason(),order.status(),financialPending,actor.session().accountId());
		repository.transitionOrderStatus(id,order.status(),"CANCELACION_SOLICITADA");
		repository.insertStatusEvent(actor.session().tenantId(),id,"CANCELACION_SOLICITADA",actor.session().accountId(),cancellationEventOperation(id,"SOLICITUD_CLIENTE:"+draft.operationId()),"El cliente solicitó cancelación: "+draft.reason());
		return ownDetail(actor,id);
	}
	/** STAFF can cancel pre-dispatch orders directly; a confirmed unit returns once through the shared stock ledger. */
	@Transactional(transactionManager="jdbcTransactionManager")
	public StaffOrder cancelPreDispatch(SessionPrincipal actor, UUID orderId, CancellationDraft command) {
		access.requireCommercialOperator(actor); setContext(actor); UUID id=requiredId(orderId); CancellationDraft draft=cancellationDraft(command);
		repository.lockOperation(actor.session().tenantId(),draft.operationId());
		Cancellation replay=repository.findCancellationByRequestOperation(draft.operationId()).orElse(null);
		if(replay!=null) { if(!replay.orderId().equals(id)||replay.origin()!=CancellationOrigin.STAFF) throw conflict("B05_CANCELLATION_REPLAY_CONFLICT","La clave de operación ya fue utilizada para otra cancelación."); return currentManagement(id); }
		Order order=repository.lockOrder(id).orElseThrow(() -> notFound("No se encontró el pedido solicitado."));
		if(!Set.of("PENDIENTE","CONFIRMADO","EN_PREPARACION","LISTO_PARA_RECOJO").contains(order.status())) throw conflict("B05_CANCELLATION_STATE","Solo se puede cancelar directamente antes del despacho físico.");
		boolean restock=!"PENDIENTE".equals(order.status());
		Cancellation cancellation=repository.insertCancellation(actor.session().tenantId(),id,draft.operationId(),CancellationOrigin.STAFF,draft.reason(),order.status(),"VALIDADO".equals(order.paymentStatus()),actor.session().accountId());
		if(restock) restockCancellation(actor,order,draft.reason());
		repository.resolveCancellation(cancellation.id(),CancellationStatus.CANCELADA,actor.session().accountId(),draft.operationId(),restock);
		repository.transitionOrderStatus(id,order.status(),"CANCELADO");
		UUID eventId=repository.insertStatusEvent(actor.session().tenantId(),id,"CANCELADO",actor.session().accountId(),cancellationEventOperation(id,"DIRECTA_STAFF:"+draft.operationId()),"Cancelación resuelta por personal: "+draft.reason());
		notifyPurchaser(actor.session().tenantId(),id,eventId,"ONLINE_ORDER_CANCELLATION_RESOLVED");
		return currentManagement(id);
	}
	@Transactional(transactionManager="jdbcTransactionManager")
	public StaffOrder decideCancellation(SessionPrincipal actor, UUID cancellationId, CancellationDecision command) {
		access.requireCommercialOperator(actor); setContext(actor); UUID id=requiredId(cancellationId); CancellationDecision decision=cancellationDecision(command);
		repository.lockOperation(actor.session().tenantId(),decision.operationId());
		Cancellation replay=repository.findCancellationByResolutionOperation(decision.operationId()).orElse(null);
		if(replay!=null) { if(!replay.id().equals(id)||replay.status()!=decision.decision()) throw conflict("B05_CANCELLATION_REPLAY_CONFLICT","La clave de resolución ya fue utilizada para otra cancelación."); return currentManagement(replay.orderId()); }
		Cancellation cancellation=repository.lockCancellation(id).orElseThrow(() -> notFound("No se encontró la cancelación solicitada."));
		Order order=repository.lockOrder(cancellation.orderId()).orElseThrow(() -> notFound("No se encontró el pedido solicitado."));
		if(cancellation.status()!=CancellationStatus.SOLICITADA||!"CANCELACION_SOLICITADA".equals(order.status())) throw conflict("B05_CANCELLATION_STATE","La solicitud de cancelación ya fue resuelta.");
		boolean approve=decision.decision()==CancellationStatus.CANCELADA;
		if(approve) restockCancellation(actor,order,decision.reason());
		repository.resolveCancellation(cancellation.id(),decision.decision(),actor.session().accountId(),decision.operationId(),approve);
		String next=approve?"CANCELADO":cancellation.sourceOrderStatus();
		repository.transitionOrderStatus(order.id(),"CANCELACION_SOLICITADA",next);
		String message=approve?"Cancelación autorizada: "+decision.reason():"Cancelación rechazada: "+decision.reason();
		UUID eventId=repository.insertStatusEvent(actor.session().tenantId(),order.id(),next,actor.session().accountId(),cancellationEventOperation(order.id(),(approve?"APROBADA:":"RECHAZADA:")+cancellation.id()),message);
		notifyPurchaser(actor.session().tenantId(),order.id(),eventId,"ONLINE_ORDER_CANCELLATION_RESOLVED");
		return currentManagement(order.id());
	}
	/** CLIENT selects only quantities actually confirmed and physically delivered. */
	@Transactional(transactionManager="jdbcTransactionManager")
	public Order requestReturn(SessionPrincipal actor, UUID orderId, ReturnDraft command) {
		access.requireClient(actor); setContext(actor); UUID id=requiredId(orderId); ReturnDraft draft=returnDraft(command);
		repository.lockOperation(actor.session().tenantId(),draft.operationId());
		OnlineReturn replay=repository.findReturnByRequestOperation(draft.operationId()).orElse(null);
		if(replay!=null) { if(!replay.orderId().equals(id)||!sameReturn(returnDetail(replay),draft)) throw conflict("B05_RETURN_REPLAY_CONFLICT","La clave de operación ya fue utilizada para otra devolución."); return ownDetail(actor,id); }
		Order order=repository.lockOwnOrder(id,actor.session().accountId()).orElseThrow(() -> notFound("No se encontró el pedido solicitado."));
		if(!"ENTREGADO".equals(order.status())||repository.findDeliveryRecord(id).isEmpty()) throw conflict("B05_RETURN_STATE","Solo un pedido entregado puede iniciar una devolución física.");
		Map<UUID,Line> effective=effectiveTerms(order).lines().stream().collect(java.util.stream.Collectors.toMap(Line::productId,line->line));
		for(ReturnLineDraft line:draft.lines()) { Line confirmed=effective.get(line.productId()); if(confirmed==null||repository.allocatedReturnQuantity(id,line.productId())+line.quantity()>confirmed.quantity()) throw conflict("B05_RETURN_QUANTITY_EXCEEDED","La devolución supera la cantidad confirmada pendiente de retorno."); }
		OnlineReturn created=repository.insertReturn(actor.session().tenantId(),id,draft.operationId(),draft.reason(),actor.session().accountId());
		for(ReturnLineDraft line:draft.lines()) repository.insertReturnLine(actor.session().tenantId(),created.id(),effective.get(line.productId()),line.quantity());
		repository.insertStatusEvent(actor.session().tenantId(),id,"ENTREGADO",actor.session().accountId(),returnEventOperation(id,created.id(),"SOLICITADA"),"Devolución solicitada: "+draft.reason());
		return ownDetail(actor,id);
	}
	@Transactional(transactionManager="jdbcTransactionManager")
	public StaffOrder decideReturn(SessionPrincipal actor, UUID returnId, ReturnDecision command) {
		access.requireCommercialOperator(actor); setContext(actor); UUID id=requiredId(returnId); ReturnDecision decision=returnDecision(command);
		repository.lockOperation(actor.session().tenantId(),decision.operationId());
		OnlineReturn replay=repository.findReturnByDecisionOperation(decision.operationId()).orElse(null);
		if(replay!=null) { if(!replay.id().equals(id)||replay.status()!=decision.decision()) throw conflict("B05_RETURN_REPLAY_CONFLICT","La clave de decisión ya fue utilizada para otra devolución."); return currentManagement(replay.orderId()); }
		OnlineReturn onlineReturn=repository.lockReturn(id).orElseThrow(() -> notFound("No se encontró la devolución solicitada."));
		Order order=repository.lockOrder(onlineReturn.orderId()).orElseThrow(() -> notFound("No se encontró el pedido solicitado."));
		if(!"ENTREGADO".equals(order.status())||onlineReturn.status()!=OnlineReturnStatus.SOLICITADA) throw conflict("B05_RETURN_STATE","La devolución ya fue resuelta o el pedido no está entregado.");
		repository.decideReturn(id,decision.decision(),actor.session().accountId(),decision.operationId(),decision.reason());
		UUID eventId=repository.insertStatusEvent(actor.session().tenantId(),order.id(),"ENTREGADO",actor.session().accountId(),returnEventOperation(order.id(),id,decision.decision().name()),decision.decision()==OnlineReturnStatus.AUTORIZADA?"Devolución autorizada para recepción física.":"Devolución rechazada: "+decision.reason());
		notifyPurchaser(actor.session().tenantId(),order.id(),eventId,"ONLINE_ORDER_RETURN_RESOLVED");
		return currentManagement(order.id());
	}
	@Transactional(transactionManager="jdbcTransactionManager")
	public StaffOrder receiveReturn(SessionPrincipal actor, UUID returnId, List<ReturnReceiptLineDraft> command) {
		access.requireCommercialOperator(actor); setContext(actor); OnlineReturn onlineReturn=repository.lockReturn(requiredId(returnId)).orElseThrow(() -> notFound("No se encontró la devolución solicitada."));
		Order order=repository.lockOrder(onlineReturn.orderId()).orElseThrow(() -> notFound("No se encontró el pedido solicitado."));
		if(!"ENTREGADO".equals(order.status())) throw conflict("B05_RETURN_STATE","La devolución solo puede continuar sobre un pedido entregado.");
		if(onlineReturn.status()!=OnlineReturnStatus.AUTORIZADA) return currentManagement(order.id());
		List<OnlineReturnLine> lines=repository.lockReturnLines(onlineReturn.id()); Map<UUID,Integer> received=receiptDraft(command,lines);
		for(OnlineReturnLine line:lines) repository.updateReturnLineReceipt(line.id(),received.get(line.id()));
		repository.markReturnReceived(onlineReturn.id(),actor.session().accountId());
		repository.insertStatusEvent(actor.session().tenantId(),order.id(),"ENTREGADO",actor.session().accountId(),returnEventOperation(order.id(),onlineReturn.id(),"RECIBIDA"),"Devolución recibida físicamente; pendiente de inspección.");
		return currentManagement(order.id());
	}
	@Transactional(transactionManager="jdbcTransactionManager")
	public StaffOrder inspectReturn(SessionPrincipal actor, UUID returnId, List<ReturnInspectionLineDraft> command) {
		access.requireCommercialOperator(actor); setContext(actor); OnlineReturn onlineReturn=repository.lockReturn(requiredId(returnId)).orElseThrow(() -> notFound("No se encontró la devolución solicitada."));
		Order order=repository.lockOrder(onlineReturn.orderId()).orElseThrow(() -> notFound("No se encontró el pedido solicitado."));
		if(!"ENTREGADO".equals(order.status())) throw conflict("B05_RETURN_STATE","La devolución solo puede continuar sobre un pedido entregado.");
		if(onlineReturn.status()!=OnlineReturnStatus.RECIBIDA) return currentManagement(order.id());
		List<OnlineReturnLine> lines=repository.lockReturnLines(onlineReturn.id()); Map<UUID,Integer> restocked=inspectionDraft(command,lines);
		List<OnlineReturnLine> approved=lines.stream().filter(line->restocked.get(line.id())>0).sorted(Comparator.comparing(line->line.productId().toString())).toList();
		for(OnlineReturnLine line:approved) if(stockRepository.lockInventoryManagedProduct(line.productId()).isEmpty()) throw conflict("B05_STOCK_PRODUCT_MISSING","No se encontró el producto físico que se devuelve.");
		Map<UUID,StockBalance> balances=new TreeMap<>();
		for(OnlineReturnLine line:approved) balances.put(line.productId(),stockRepository.lockBalance(line.productId()).orElseThrow(()->conflict("B05_STOCK_BALANCE_MISSING","Falta el saldo del producto devuelto.")));
		boolean someRestocked=false;
		for(OnlineReturnLine line:lines) { int quantity=restocked.get(line.id()); String result=inspectionResult(line.receivedQuantity(),quantity);
			if(quantity>0) { StockBalance balance=balances.get(line.productId()); int after=addStock(balance.availableQuantity(),quantity); stockRepository.updateBalance(balance,after); stockRepository.insertOnlineReturnMovement(actor.session().tenantId(),line.productId(),order.id(),onlineReturn.id(),balance.availableQuantity(),after,returnMovementOperation(onlineReturn.id(),line.id()),"Devolución online apta "+onlineReturn.id(),actor.session().accountId()); someRestocked=true; }
			repository.updateReturnLineInspection(line.id(),quantity,result);
		}
		OnlineReturnStatus outcome=someRestocked?OnlineReturnStatus.INSPECCIONADA_APTA:OnlineReturnStatus.INSPECCIONADA_NO_APTA;
		repository.markReturnInspected(onlineReturn.id(),outcome,actor.session().accountId());
		repository.insertStatusEvent(actor.session().tenantId(),order.id(),"ENTREGADO",actor.session().accountId(),returnEventOperation(order.id(),onlineReturn.id(),outcome.name()),someRestocked?"Devolución inspeccionada: unidades aptas restituidas una sola vez.":"Devolución inspeccionada: no se restituyeron unidades vendibles.");
		return currentManagement(order.id());
	}
	@Transactional(transactionManager="jdbcTransactionManager")
	public StaffOrder closeReturn(SessionPrincipal actor, UUID returnId) {
		access.requireCommercialOperator(actor); setContext(actor); OnlineReturn onlineReturn=repository.lockReturn(requiredId(returnId)).orElseThrow(() -> notFound("No se encontró la devolución solicitada."));
		Order order=repository.lockOrder(onlineReturn.orderId()).orElseThrow(() -> notFound("No se encontró el pedido solicitado."));
		if(onlineReturn.status()==OnlineReturnStatus.CERRADA) return currentManagement(order.id());
		if(!Set.of(OnlineReturnStatus.INSPECCIONADA_APTA,OnlineReturnStatus.INSPECCIONADA_NO_APTA).contains(onlineReturn.status())) throw conflict("B05_RETURN_STATE","La devolución debe inspeccionarse antes de cerrarse.");
		repository.closeReturn(onlineReturn.id(),actor.session().accountId());
		repository.insertStatusEvent(actor.session().tenantId(),order.id(),"ENTREGADO",actor.session().accountId(),returnEventOperation(order.id(),onlineReturn.id(),"CERRADA"),"Devolución cerrada con su resultado físico registrado.");
		return currentManagement(order.id());
	}
	private StaffOrder advanceFulfillment(SessionPrincipal actor, UUID orderId, UUID operationId, String expectedStatus, String nextStatus, DeliveryMode requiredMode) {
		access.requireCommercialOperator(actor); setContext(actor); UUID id=requiredId(orderId); UUID operation=requiredOperation(operationId);
		repository.lockOperation(actor.session().tenantId(),operation);
		String replay=repository.findStatusByOperation(id,operation).orElse(null);
		if(replay!=null) { if(!nextStatus.equals(replay)) throw conflict("B05_FULFILLMENT_REPLAY_CONFLICT","La clave de operación ya fue utilizada para otra transición."); return currentManagement(id); }
		Order order=repository.lockOrder(id).orElseThrow(() -> notFound("No se encontró el pedido solicitado."));
		if(nextStatus.equals(order.status())) return currentManagement(id);
		if(!expectedStatus.equals(order.status())) throw conflict("B05_FULFILLMENT_STATE","El pedido no admite esta transición logística en su estado actual.");
		if(requiredMode!=null&&effectiveTerms(order).deliveryMode()!=requiredMode) throw conflict("B05_FULFILLMENT_MODE","La modalidad del pedido no admite esta transición logística.");
		repository.transitionOrderStatus(id,expectedStatus,nextStatus);
		UUID eventId=repository.insertStatusEvent(actor.session().tenantId(),id,nextStatus,actor.session().accountId(),operation,null);
		if("LISTO_PARA_RECOJO".equals(nextStatus)) notifyPurchaser(actor.session().tenantId(),id,eventId,"ONLINE_ORDER_READY_FOR_PICKUP");
		if("EN_REPARTO".equals(nextStatus)) notifyPurchaser(actor.session().tenantId(),id,eventId,"ONLINE_ORDER_DISPATCHED");
		return currentManagement(id);
	}
	@Transactional(transactionManager="jdbcTransactionManager",readOnly=true)
	public DeliveryConfiguration deliveryConfiguration(SessionPrincipal actor) { access.requireDeliveryAdministrator(actor); setContext(actor); return repository.findConfiguration().orElse(null); }
	@Transactional(transactionManager="jdbcTransactionManager")
	public DeliveryConfiguration updateDeliveryConfiguration(SessionPrincipal actor,String pickup,boolean enabled,BigDecimal fee,String coverage) { access.requireDeliveryAdministrator(actor); setContext(actor); pickup=required(pickup,"pickupLocation","Indica el punto de recojo."); fee=money(fee,"fixedDeliveryFeePen"); if(!enabled){fee=BigDecimal.ZERO.setScale(2);coverage=null;}else coverage=required(coverage,"coverageDescription","Describe la cobertura de delivery."); return repository.upsertConfiguration(actor.session().tenantId(),pickup,enabled,fee,coverage,actor.session().accountId()); }
	private Order ownDetail(SessionPrincipal actor,UUID orderId){ return detail(repository.findOwn(orderId,actor.session().accountId()).orElseThrow(()->notFound("No se encontró el pedido solicitado."))); }
	private Order detail(Order order){ return new Order(order.id(),order.orderNumber(),order.status(),order.deliveryMode(),order.coordinationPhone(),order.pickupLocation(),order.deliveryAddress(),order.deliveryCoverage(),order.subtotalPen(),order.deliveryFeePen(),order.totalPen(),order.createdAt(),repository.listLines(order.id()),repository.listEvents(order.id()),proposalDetails(order.id()),order.paymentStatus(),repository.listPayments(order.id()),repository.findDeliveryRecord(order.id()).orElse(null),repository.findCancellation(order.id()).orElse(null),returnDetails(order.id())); }
	private StaffOrder staffDetail(StaffOrder order){ return new StaffOrder(order.id(),order.orderNumber(),order.status(),order.deliveryMode(),order.buyerName(),order.coordinationPhone(),order.pickupLocation(),order.deliveryAddress(),order.deliveryCoverage(),order.subtotalPen(),order.deliveryFeePen(),order.totalPen(),order.reviewReason(),order.createdAt(),repository.listLines(order.id()),repository.listEvents(order.id()),proposalDetails(order.id()),order.paymentStatus(),repository.listPayments(order.id()),repository.findDeliveryRecord(order.id()).orElse(null),repository.findCancellation(order.id()).orElse(null),returnDetails(order.id())); }
	private StaffOrder currentManagement(UUID orderId){ return staffDetail(repository.findManagement(orderId).orElseThrow(() -> notFound("No se encontró el pedido solicitado."))); }
	private List<Proposal> proposalDetails(UUID orderId){ return repository.listProposals(orderId).stream().map(this::proposalDetail).toList(); }
	private List<OnlineReturn> returnDetails(UUID orderId){ return repository.listReturns(orderId).stream().map(this::returnDetail).toList(); }
	private OnlineReturn returnDetail(OnlineReturn onlineReturn){ return new OnlineReturn(onlineReturn.id(),onlineReturn.orderId(),onlineReturn.reason(),onlineReturn.status(),onlineReturn.requestedAt(),onlineReturn.decidedAt(),onlineReturn.receivedAt(),onlineReturn.inspectedAt(),onlineReturn.closedAt(),repository.listReturnLines(onlineReturn.id())); }
	private Proposal proposalDetail(Proposal proposal){
		Terms previous=withLines(proposal.previousTerms(),repository.listProposalLines(proposal.id(),"ANTERIOR"));
		Terms proposed=withLines(proposal.proposedTerms(),repository.listProposalLines(proposal.id(),"PROPUESTA"));
		return new Proposal(proposal.id(),proposal.orderId(),proposal.proposalNumber(),proposal.status(),proposal.reason(),proposal.createdAt(),proposal.decidedAt(),previous,proposed);
	}
	private Terms withLines(Terms terms,List<Line> lines){ return new Terms(terms.deliveryMode(),terms.coordinationPhone(),terms.pickupLocation(),terms.deliveryAddress(),terms.deliveryCoverage(),terms.subtotalPen(),terms.deliveryFeePen(),terms.totalPen(),lines); }
	private Terms effectiveTerms(Order order){
		Proposal accepted=repository.findLatestAcceptedProposal(order.id()).orElse(null);
		if(accepted!=null) return withLines(accepted.proposedTerms(),repository.listProposalLines(accepted.id(),"PROPUESTA"));
		return new Terms(order.deliveryMode(),order.coordinationPhone(),order.pickupLocation(),order.deliveryAddress(),order.deliveryCoverage(),order.subtotalPen(),order.deliveryFeePen(),order.totalPen(),repository.lockLines(order.id()));
	}
	private Terms prepareProposedTerms(SessionPrincipal actor,ProposalDraft draft){
		DeliveryConfiguration config=repository.findConfiguration().orElseThrow(() -> conflict("B05_CHECKOUT_NOT_CONFIGURED","La veterinaria aún no configuró el punto de recojo."));
		String pickup=null,address=null,coverage=null; BigDecimal deliveryFee=BigDecimal.ZERO.setScale(2);
		if(draft.deliveryMode()==DeliveryMode.RECOJO) pickup=config.pickupLocation();
		else { if(!config.deliveryEnabled()) throw conflict("B05_DELIVERY_NOT_AVAILABLE","Delivery no está disponible para esta veterinaria."); address=required(draft.deliveryAddress(),"deliveryAddress","Ingresa la dirección de entrega."); coverage=config.coverageDescription(); deliveryFee=config.fixedDeliveryFeePen(); }
		List<Line> lines=new ArrayList<>(); BigDecimal subtotal=BigDecimal.ZERO.setScale(2);
		for(ProposalLineDraft line:draft.lines()){
			Product product=repository.findProposalProduct(line.productId()).orElseThrow(() -> conflict("B05_PROPOSAL_PRODUCT_UNAVAILABLE","Un producto propuesto no pertenece a esta veterinaria."));
			if(!product.inventoryManaged() || "INACTIVO".equals(product.status())) throw conflict("B05_PROPOSAL_PRODUCT_UNAVAILABLE","Solo se pueden proponer productos físicos activos.");
			BigDecimal unitPrice=money(line.unitPricePen(),"lines");
			if(unitPrice.compareTo(product.salePricePen())!=0) access.requirePriceProposalAdministrator(actor);
			BigDecimal lineSubtotal=unitPrice.multiply(BigDecimal.valueOf(line.quantity())).setScale(2,RoundingMode.UNNECESSARY);
			lines.add(new Line(product.id(),product.sku(),product.title(),line.quantity(),unitPrice,lineSubtotal)); subtotal=subtotal.add(lineSubtotal).setScale(2,RoundingMode.UNNECESSARY);
		}
		return new Terms(draft.deliveryMode(),draft.coordinationPhone(),pickup,address,coverage,subtotal,deliveryFee,subtotal.add(deliveryFee).setScale(2,RoundingMode.UNNECESSARY),List.copyOf(lines));
	}
	private ProposalDraft proposalDraft(ProposalDraft raw){
		if(raw==null||raw.operationId()==null||raw.deliveryMode()==null) throw validation("proposal","Completa la información de la propuesta.");
		String reason=required(raw.reason(),"reason","Indica el motivo de la propuesta.");
		String phone=required(raw.coordinationPhone(),"coordinationPhone","Ingresa un teléfono de coordinación."); if(phone.length()>40) throw validation("coordinationPhone","El teléfono es demasiado largo.");
		if(raw.lines()==null||raw.lines().isEmpty()) throw validation("lines","La propuesta debe conservar al menos un producto.");
		Map<UUID,ProposalLineDraft> lines=new TreeMap<>();
		for(ProposalLineDraft line:raw.lines()) { if(line==null||line.productId()==null||line.quantity()<1||line.quantity()>1_000_000||line.unitPricePen()==null||line.unitPricePen().signum()<0||line.unitPricePen().scale()>2) throw validation("lines","Cada línea propuesta requiere producto, cantidad y precio válidos."); if(lines.put(line.productId(),line)!=null) throw validation("lines","La propuesta no puede repetir productos."); }
		if(lines.size()>100) throw validation("lines","La propuesta admite hasta 100 productos.");
		return new ProposalDraft(raw.operationId(),reason,raw.deliveryMode(),phone,trim(raw.deliveryAddress()),List.copyOf(lines.values()));
	}
	private ProposalDecision proposalDecision(ProposalDecision command){
		if(command==null||command.operationId()==null||command.decision()==null||!(command.decision()==ProposalStatus.ACEPTADA||command.decision()==ProposalStatus.RECHAZADA)) throw validation("proposal","La propuesta debe aceptarse o rechazarse explícitamente.");
		return command;
	}
	private ManualPaymentDraft manualPaymentDraft(ManualPaymentDraft raw){
		if(raw==null||raw.operationId()==null) throw validation("payment","Completa la información del pago manual.");
		String method=trim(raw.paymentMethod()); if(method==null||!Set.of("EFECTIVO","TRANSFERENCIA","OTRO").contains(method)) throw validation("paymentMethod","El medio de pago manual no es válido.");
		BigDecimal amount=money(raw.declaredAmountPen(),"declaredAmountPen"); String reference=optional(raw.reference(),"reference",120);
		return new ManualPaymentDraft(raw.operationId(),method,amount,reference);
	}
	private ManualPaymentDecision manualPaymentDecision(ManualPaymentDecision raw){
		if(raw==null||raw.operationId()==null||raw.decision()==null||!(raw.decision()==ManualPaymentStatus.VALIDADO||raw.decision()==ManualPaymentStatus.RECHAZADO)) throw validation("payment","El pago manual debe validarse o rechazarse explícitamente.");
		String reason=raw.decision()==ManualPaymentStatus.RECHAZADO?required(raw.reason(),"reason","Indica el motivo del rechazo."):optional(raw.reason(),"reason",500);
		return new ManualPaymentDecision(raw.operationId(),raw.decision(),reason);
	}
	private DeliveryCompletionDraft deliveryCompletionDraft(DeliveryCompletionDraft raw){
		if(raw==null||raw.operationId()==null) throw validation("delivery","Completa la constancia de entrega.");
		return new DeliveryCompletionDraft(raw.operationId(),required(raw.receiver(),"receiver","Indica quién recibió el pedido."),optional(raw.proofNote(),"proofNote",240));
	}
	private CancellationDraft cancellationDraft(CancellationDraft raw){ if(raw==null||raw.operationId()==null) throw validation("cancellation","Completa la información de cancelación."); return new CancellationDraft(raw.operationId(),required(raw.reason(),"reason","Indica el motivo de la cancelación.")); }
	private CancellationDecision cancellationDecision(CancellationDecision raw){ if(raw==null||raw.operationId()==null||raw.decision()==null||!(raw.decision()==CancellationStatus.CANCELADA||raw.decision()==CancellationStatus.RECHAZADA)) throw validation("cancellation","La solicitud debe aprobarse o rechazarse explícitamente."); return new CancellationDecision(raw.operationId(),raw.decision(),required(raw.reason(),"reason","Indica el motivo de la resolución.")); }
	private ReturnDraft returnDraft(ReturnDraft raw){
		if(raw==null||raw.operationId()==null||raw.lines()==null||raw.lines().isEmpty()||raw.lines().size()>100) throw validation("return","La devolución requiere entre una y 100 líneas.");
		Map<UUID,ReturnLineDraft> lines=new TreeMap<>(); for(ReturnLineDraft line:raw.lines()) { if(line==null||line.productId()==null||line.quantity()<1||line.quantity()>1_000_000) throw validation("lines","Cada línea de devolución requiere producto y cantidad válida."); if(lines.put(line.productId(),line)!=null) throw validation("lines","La devolución no puede repetir productos."); }
		return new ReturnDraft(raw.operationId(),required(raw.reason(),"reason","Indica el motivo de la devolución."),List.copyOf(lines.values()));
	}
	private ReturnDecision returnDecision(ReturnDecision raw){ if(raw==null||raw.operationId()==null||raw.decision()==null||!(raw.decision()==OnlineReturnStatus.AUTORIZADA||raw.decision()==OnlineReturnStatus.RECHAZADA)) throw validation("return","La devolución debe autorizarse o rechazarse explícitamente."); String reason=raw.decision()==OnlineReturnStatus.RECHAZADA?required(raw.reason(),"reason","Indica el motivo del rechazo."):optional(raw.reason(),"reason",500); return new ReturnDecision(raw.operationId(),raw.decision(),reason); }
	private Map<UUID,Integer> receiptDraft(List<ReturnReceiptLineDraft> raw,List<OnlineReturnLine> expected){
		if(raw==null||raw.size()!=expected.size()) throw validation("lines","La recepción debe indicar exactamente las líneas solicitadas."); Map<UUID,Integer> values=new TreeMap<>();
		for(ReturnReceiptLineDraft line:raw){ if(line==null||line.returnLineId()==null||line.receivedQuantity()<0||values.put(line.returnLineId(),line.receivedQuantity())!=null) throw validation("lines","La recepción contiene líneas inválidas o repetidas."); }
		for(OnlineReturnLine line:expected){ Integer value=values.get(line.id()); if(value==null||value>line.requestedQuantity()) throw validation("lines","La recepción supera la cantidad solicitada."); } return values;
	}
	private Map<UUID,Integer> inspectionDraft(List<ReturnInspectionLineDraft> raw,List<OnlineReturnLine> expected){
		if(raw==null||raw.size()!=expected.size()) throw validation("lines","La inspección debe indicar exactamente las líneas recibidas."); Map<UUID,Integer> values=new TreeMap<>();
		for(ReturnInspectionLineDraft line:raw){ if(line==null||line.returnLineId()==null||line.restockedQuantity()<0||values.put(line.returnLineId(),line.restockedQuantity())!=null) throw validation("lines","La inspección contiene líneas inválidas o repetidas."); }
		for(OnlineReturnLine line:expected){ Integer value=values.get(line.id()); if(value==null||value>line.receivedQuantity()) throw validation("lines","La restitución supera la cantidad recibida."); } return values;
	}
	private boolean sameReturn(OnlineReturn source,ReturnDraft draft){ if(!source.reason().equals(draft.reason())||source.lines().size()!=draft.lines().size()) return false; Map<UUID,Integer> actual=new TreeMap<>(); for(OnlineReturnLine line:source.lines()) actual.put(line.productId(),line.requestedQuantity()); Map<UUID,Integer> requested=new TreeMap<>(); for(ReturnLineDraft line:draft.lines()) requested.put(line.productId(),line.quantity()); return actual.equals(requested); }
	private void restockCancellation(SessionPrincipal actor,Order order,String reason){
		List<Line> lines=effectiveTerms(order).lines().stream().sorted(Comparator.comparing(line->line.productId().toString())).toList(); if(lines.isEmpty()) throw conflict("B05_ORDER_EMPTY","El pedido no contiene líneas para restituir.");
		for(Line line:lines) if(stockRepository.lockInventoryManagedProduct(line.productId()).isEmpty()) throw conflict("B05_STOCK_PRODUCT_MISSING","No se encontró el producto físico que se cancela.");
		Map<UUID,StockBalance> balances=new TreeMap<>(); for(Line line:lines) balances.put(line.productId(),stockRepository.lockBalance(line.productId()).orElseThrow(()->conflict("B05_STOCK_BALANCE_MISSING","Falta el saldo del producto que se cancela.")));
		for(Line line:lines){ StockBalance balance=balances.get(line.productId()); int after=addStock(balance.availableQuantity(),line.quantity()); stockRepository.updateBalance(balance,after); stockRepository.insertOnlineCancellationMovement(actor.session().tenantId(),line.productId(),order.id(),balance.availableQuantity(),after,cancellationMovementOperation(order.id(),line.productId()),"Cancelación online #"+order.orderNumber()+": "+reason,actor.session().accountId()); }
	}
	private int addStock(int before,int quantity){ try{return Math.addExact(before,quantity);}catch(ArithmeticException ex){throw conflict("B05_STOCK_OVERFLOW","El saldo no admite la restitución solicitada.");} }
	private String inspectionResult(int received,int restocked){ if(restocked==0)return "NO_APTA"; return restocked==received?"APTA":"PARCIAL"; }
	private boolean samePaymentDraft(ManualPayment payment,ManualPaymentDraft draft){ return payment.paymentMethod().equals(draft.paymentMethod())&&payment.declaredAmountPen().compareTo(draft.declaredAmountPen())==0&&java.util.Objects.equals(payment.reference(),draft.reference()); }
	private boolean paymentCompatible(String status){ return Set.of("CONFIRMADO","EN_PREPARACION","LISTO_PARA_RECOJO","EN_REPARTO","ENTREGADO").contains(status); }
	private boolean sameProposalCommand(Proposal proposal,ProposalDraft draft){
		Proposal persisted=proposalDetail(proposal); Terms terms=persisted.proposedTerms(); if(!persisted.reason().equals(draft.reason())||terms.deliveryMode()!=draft.deliveryMode()||!terms.coordinationPhone().equals(draft.coordinationPhone())||!java.util.Objects.equals(terms.deliveryAddress(),trim(draft.deliveryAddress()))||terms.lines().size()!=draft.lines().size()) return false;
		Map<UUID,Line> actual=terms.lines().stream().collect(java.util.stream.Collectors.toMap(Line::productId,line->line));
		return draft.lines().stream().allMatch(line->{Line storedLine=actual.get(line.productId());return storedLine!=null&&storedLine.quantity()==line.quantity()&&storedLine.unitPricePen().compareTo(line.unitPricePen())==0;});
	}
	private Draft draft(Draft raw){ if(raw==null||raw.operationId()==null||raw.deliveryMode()==null) throw validation("operationId","Completa la información del pedido."); String phone=required(raw.coordinationPhone(),"coordinationPhone","Ingresa un teléfono de coordinación."); if(phone.length()>40) throw validation("coordinationPhone","El teléfono es demasiado largo."); if(raw.lines()==null||raw.lines().isEmpty()) throw validation("lines","El carrito no puede estar vacío."); Map<UUID,LineDraft> lines=new TreeMap<>(); for(LineDraft line:raw.lines()){ if(line==null||line.productId()==null||line.quantity()<1||line.quantity()>1_000_000) throw validation("lines","Cada línea requiere un producto y cantidad válida."); if(line.expectedUnitPricePen()==null||line.expectedUnitPricePen().signum()<0) throw validation("lines","El precio a revisar es obligatorio."); if(lines.containsKey(line.productId())) throw validation("lines","El carrito no puede repetir productos."); lines.put(line.productId(),line); } if(lines.size()>100) throw validation("lines","El pedido admite hasta 100 productos."); return new Draft(raw.operationId(),raw.deliveryMode(),phone,trim(raw.deliveryAddress()),raw.expectedDeliveryFeePen(),List.copyOf(lines.values())); }
	private boolean same(Draft d,Order o){ if(d.deliveryMode()!=o.deliveryMode()||!d.coordinationPhone().equals(o.coordinationPhone())||!java.util.Objects.equals(trim(d.deliveryAddress()),o.deliveryAddress())||d.lines().size()!=o.lines().size())return false; Map<UUID,Line> actual=o.lines().stream().collect(java.util.stream.Collectors.toMap(Line::productId,line->line)); return d.lines().stream().allMatch(l->{Line line=actual.get(l.productId());return line!=null&&line.quantity()==l.quantity()&&line.unitPricePen().compareTo(l.expectedUnitPricePen())==0;}); }
	private String required(String value,String field,String message){String trimmed=trim(value);if(trimmed==null||trimmed.length()<3)throw validation(field,message);if(trimmed.length()> ("coverageDescription".equals(field)?500:240))throw validation(field,"El texto excede el límite permitido.");return trimmed;}
	private String optional(String value,String field,int maxLength){String trimmed=trim(value);if(trimmed!=null&&trimmed.length()>maxLength)throw validation(field,"El texto excede el límite permitido.");return trimmed;}
	private String trim(String v){return v==null?null:v.trim();}
	private BigDecimal money(BigDecimal v,String field){if(v==null||v.signum()<0||v.scale()>2)throw validation(field,"Ingresa un importe PEN válido.");return v.setScale(2,RoundingMode.UNNECESSARY);}
	private UUID requiredId(UUID id){if(id==null)throw validation("orderId","El pedido es obligatorio.");return id;}
	private UUID requiredOperation(UUID id){if(id==null)throw validation("operationId","La clave de operación es obligatoria.");return id;}
	private UUID statusEventOperation(UUID orderId,String status,String cause){return UUID.nameUUIDFromBytes(("ONLINE-STATUS:"+orderId+":"+status+":"+cause).getBytes(StandardCharsets.UTF_8));}
	private UUID confirmationMovementOperation(UUID orderId,UUID productId){return UUID.nameUUIDFromBytes(("ONLINE-CONFIRM:"+orderId+":"+productId).getBytes(StandardCharsets.UTF_8));}
	private UUID cancellationMovementOperation(UUID orderId,UUID productId){return UUID.nameUUIDFromBytes(("ONLINE-CANCEL:"+orderId+":"+productId).getBytes(StandardCharsets.UTF_8));}
	private UUID returnMovementOperation(UUID returnId,UUID lineId){return UUID.nameUUIDFromBytes(("ONLINE-RETURN:"+returnId+":"+lineId).getBytes(StandardCharsets.UTF_8));}
	private UUID cancellationEventOperation(UUID orderId,String action){return UUID.nameUUIDFromBytes(("ONLINE-CANCELLATION:"+orderId+":"+action).getBytes(StandardCharsets.UTF_8));}
	private UUID returnEventOperation(UUID orderId,UUID returnId,String action){return UUID.nameUUIDFromBytes(("ONLINE-RETURN-EVENT:"+orderId+":"+returnId+":"+action).getBytes(StandardCharsets.UTF_8));}
	private void notifyPurchaser(UUID tenantId, UUID orderId, UUID eventId, String notificationCode) {
		UUID purchaser = repository.findClientPrincipalId(orderId)
				.orElseThrow(() -> new IllegalStateException("El pedido no tiene una cuenta CLIENT asociada."));
		internalNotifications.enqueue(new NotificationDeliveryRequest(tenantId, purchaser, "B05", eventId,
				"ONLINE_ORDER", orderId, notificationCode));
	}
	private void notifyCommercialStaff(UUID tenantId, UUID orderId, UUID eventId, String notificationCode) {
		internalNotifications.enqueueForRecipients(tenantId, internalNotifications.activeCommercialRecipients(tenantId), "B05", eventId,
				"ONLINE_ORDER", orderId, notificationCode);
	}
	private void setContext(SessionPrincipal actor){tenantContext.setTenantId(actor.session().tenantId());}
	private ApiException validation(String f,String m){return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"B05_ORDER_VALIDATION",m);}
	private ApiException conflict(String c,String m){return new ApiException(HttpStatus.CONFLICT,c,m);}
	private ApiException notFound(String m){return new ApiException(HttpStatus.NOT_FOUND,"B05_ORDER_NOT_FOUND",m);}
	private record PreparedLine(Product product,int quantity,BigDecimal subtotal){}
	private record PreparedStockLine(Line line,StockBalance balance){}
}
