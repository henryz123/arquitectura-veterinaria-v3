import { CurrencyPipe, DatePipe, NgFor, NgIf } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize, forkJoin } from 'rxjs';
import { CatalogProduct, OnlineDeliveryMode, OnlineManualPayment, OnlineOrderLine, OnlineOrderProposal, OnlineOrderProposalRequest, OnlineOrderReturn, OnlinePaymentMethod, OnlineStaffOrder } from '../../core/api/api.models';
import { CatalogApiService } from '../../core/api/catalog-api.service';
import { OnlineOrderApiService } from '../../core/api/online-order-api.service';
import { NotificationService } from '../../core/notifications/notification.service';
import { ActionDialogService } from '../../shared/dialog/action-dialog.service';

type ProposalLineForm = FormGroup<{
  productId: FormControl<string>;
  quantity: FormControl<number>;
  unitPricePen: FormControl<number>;
}>;

@Component({
  selector: 'app-online-order-management-page',
  imports: [CurrencyPipe, DatePipe, NgFor, NgIf, ReactiveFormsModule, RouterLink],
  templateUrl: './online-order-management-page.component.html',
  styleUrl: './online-order-management-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OnlineOrderManagementPageComponent implements OnInit {
  private readonly api = inject(OnlineOrderApiService);
  private readonly catalogApi = inject(CatalogApiService);
  private readonly formBuilder = inject(FormBuilder);
  private readonly notifications = inject(NotificationService);
  private readonly dialogs = inject(ActionDialogService);
  readonly orders = signal<OnlineStaffOrder[]>([]);
  readonly orderSearch = signal('');
  readonly orderStatusFilter = signal('TODOS');
  readonly visibleOrders = computed(() => {
    const text = this.orderSearch().trim().toLocaleLowerCase('es-PE');
    const status = this.orderStatusFilter();
    return this.orders().filter((order) => {
      if (status !== 'TODOS' && order.status !== status) return false;
      if (!text) return true;
      return [order.orderNumber, order.buyerName, order.coordinationPhone, order.status]
        .some((value) => String(value ?? '').toLocaleLowerCase('es-PE').includes(text));
    });
  });
  readonly products = signal<CatalogProduct[]>([]);
  readonly selected = signal<OnlineStaffOrder | null>(null);
  readonly proposalOrder = signal<OnlineStaffOrder | null>(null);
  readonly loading = signal(true);
  readonly confirmingOrderId = signal<string | null>(null);
  readonly logisticsActionOrderId = signal<string | null>(null);
  readonly proposing = signal(false);
  readonly paymentSaving = signal(false);
  readonly error = signal<string | null>(null);
  readonly proposalSubmitted = signal(false);
  readonly paymentSubmitted = signal(false);
  readonly paymentOrder = signal<OnlineStaffOrder | null>(null);
  readonly deliveryOrder = signal<OnlineStaffOrder | null>(null);
  readonly cancellationActionOrderId = signal<string | null>(null);
  readonly returnActionId = signal<string | null>(null);
  readonly proposalLines = this.formBuilder.nonNullable.array<ProposalLineForm>([]);
  readonly proposalForm = this.formBuilder.nonNullable.group({
    reason: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(500)]],
    deliveryMode: ['RECOJO' as OnlineDeliveryMode, Validators.required],
    coordinationPhone: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(40)]],
    deliveryAddress: ['', Validators.maxLength(240)],
    lines: this.proposalLines,
  });
  readonly paymentForm = this.formBuilder.nonNullable.group({
    paymentMethod: ['EFECTIVO' as OnlinePaymentMethod, Validators.required],
    reference: ['', Validators.maxLength(120)],
  });
  readonly deliveryForm = this.formBuilder.nonNullable.group({
    receiver: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(160)]],
    proofNote: ['', Validators.maxLength(240)],
  });
  ngOnInit(): void { this.reload(); }

  reload(): void {
    this.loading.set(true); this.error.set(null);
    forkJoin({ orders: this.api.listManagement(), products: this.catalogApi.listProducts() }).subscribe({
      next: ({ orders, products }) => { this.orders.set(orders.data); this.products.set(products.data.filter((product) => product.inventoryManaged && product.status !== 'INACTIVO')); this.loading.set(false); },
      error: () => { this.error.set('No fue posible cargar los pedidos autorizados. Actualiza para revalidar la sesión y tus permisos.'); this.loading.set(false); },
    });
  }

  open(order: OnlineStaffOrder): void {
    this.error.set(null);
    this.api.managementOrder(order.id).subscribe({ next: ({ data }) => this.replaceOrder(data, true), error: () => this.error.set('No fue posible consultar el detalle del pedido. Puede que ya no esté disponible para esta sesión.') });
  }

  confirm(order: OnlineStaffOrder): void {
    if (!this.canConfirm(order) || this.confirmingOrderId()) return;
    this.error.set(null); this.confirmingOrderId.set(order.id);
    this.api.confirm(order.id).pipe(finalize(() => this.confirmingOrderId.set(null))).subscribe({
      next: ({ data }) => {
        this.replaceOrder(data, true);
        if (data.status === 'CONFIRMADO') this.notifications.success('Pedido confirmado. Las existencias se descontaron una sola vez.');
        else this.notifications.warning('El pedido requiere revisión: no se descontó ninguna existencia.');
      },
      error: (response) => this.error.set(response?.error?.message ?? 'No fue posible confirmar el pedido. El servidor mantuvo el pedido y el inventario sin cambios parciales.'),
    });
  }

  beginProposal(order: OnlineStaffOrder): void {
    if (!this.canPropose(order) || this.proposing()) return;
    this.proposalOrder.set(order); this.scrollToDetail('staff-order-proposal'); this.proposalSubmitted.set(false); this.error.set(null);
    const source = this.currentAcceptedProposal(order)?.proposedTerms ?? this.originalTerms(order);
    this.proposalForm.reset({ reason: '', deliveryMode: source.deliveryMode, coordinationPhone: source.coordinationPhone, deliveryAddress: source.deliveryAddress ?? '' });
    this.proposalLines.clear(); source.lines.forEach((line) => this.proposalLines.push(this.lineControl(line)));
  }

  cancelProposal(): void { this.proposalOrder.set(null); this.proposalSubmitted.set(false); }
  addProposalLine(): void { this.proposalLines.push(this.lineControl()); }
  removeProposalLine(index: number): void { if (this.proposalLines.length > 1) this.proposalLines.removeAt(index); }

  updateLinePrice(index: number): void {
    const productId = this.proposalLines.at(index).controls.productId.value;
    const product = this.products().find((candidate) => candidate.id === productId);
    if (product) this.proposalLines.at(index).controls.unitPricePen.setValue(product.salePricePen);
  }

  submitProposal(): void {
    const order = this.proposalOrder();
    this.proposalSubmitted.set(true);
    if (!order || this.proposalForm.invalid || this.proposing()) { this.proposalForm.markAllAsTouched(); return; }
    const value = this.proposalForm.getRawValue();
    if (value.deliveryMode === 'DELIVERY' && !value.deliveryAddress.trim()) { this.proposalForm.controls.deliveryAddress.setErrors({ required: true }); return; }
    const request: OnlineOrderProposalRequest = {
      operationId: crypto.randomUUID(), reason: value.reason.trim(), deliveryMode: value.deliveryMode,
      coordinationPhone: value.coordinationPhone.trim(), deliveryAddress: value.deliveryMode === 'DELIVERY' ? value.deliveryAddress.trim() : null,
      lines: value.lines.map((line) => ({ productId: line.productId, quantity: Number(line.quantity), unitPricePen: Number(line.unitPricePen) })),
    };
    this.proposing.set(true); this.error.set(null);
    this.api.propose(order.id, request).pipe(finalize(() => this.proposing.set(false))).subscribe({
      next: ({ data }) => { this.replaceOrder(data, true); this.proposalOrder.set(null); this.notifications.success('Propuesta enviada. El cliente debe decidir expresamente antes de confirmar.'); },
      error: (response) => this.error.set(response?.error?.message ?? 'No fue posible registrar la propuesta. Revisa las condiciones y vuelve a intentarlo.'),
    });
  }

  beginPayment(order: OnlineStaffOrder): void {
    if (!this.canRecordPayment(order) || this.paymentSaving()) return;
    this.paymentOrder.set(order); this.scrollToDetail('staff-order-payment'); this.paymentSubmitted.set(false); this.paymentForm.reset({ paymentMethod: 'EFECTIVO', reference: '' }); this.error.set(null);
  }

  cancelPayment(): void { this.paymentOrder.set(null); this.paymentSubmitted.set(false); }

  submitPayment(): void {
    const order = this.paymentOrder();
    this.paymentSubmitted.set(true);
    if (!order || this.paymentForm.invalid || this.paymentSaving()) { this.paymentForm.markAllAsTouched(); return; }
    const value = this.paymentForm.getRawValue();
    this.paymentSaving.set(true); this.error.set(null);
    this.api.recordPayment(order.id, { operationId: crypto.randomUUID(), paymentMethod: value.paymentMethod, declaredAmountPen: order.totalPen, reference: value.reference.trim() || null }).pipe(finalize(() => this.paymentSaving.set(false))).subscribe({
      next: ({ data }) => { this.replaceOrder(data, true); this.paymentOrder.set(null); this.notifications.success('Pago manual declarado. Valídalo o recházalo después de comprobarlo.'); },
      error: (response) => this.error.set(response?.error?.message ?? 'No fue posible registrar el pago manual. El pedido y el inventario permanecen sin cambios.'),
    });
  }

  async decidePayment(order: OnlineStaffOrder, payment: OnlineManualPayment, decision: 'VALIDADO' | 'RECHAZADO'): Promise<void> {
    if (!this.canDecidePayment(payment) || this.paymentSaving()) return;
    const reason = decision === 'RECHAZADO' ? await this.dialogs.askReason('Rechazar pago manual', 'Explica por qué se rechaza el pago. El pedido y su inventario no cambiarán.', 'Rechazar pago', true) : null;
    if (decision === 'RECHAZADO' && (!reason || reason.trim().length < 3)) { this.notifications.warning('El rechazo requiere un motivo de al menos 3 caracteres.'); return; }
    this.paymentSaving.set(true); this.error.set(null);
    this.api.decidePayment(order.id, payment.id, { operationId: crypto.randomUUID(), decision, reason: reason?.trim() || null }).pipe(finalize(() => this.paymentSaving.set(false))).subscribe({
      next: ({ data }) => { this.replaceOrder(data, true); this.notifications.success(decision === 'VALIDADO' ? 'Pago manual validado.' : 'Pago manual rechazado.'); },
      error: (response) => this.error.set(response?.error?.message ?? 'No fue posible registrar la decisión del pago.'),
    });
  }

  advanceFulfillment(order: OnlineStaffOrder, action: 'PREPARACION' | 'LISTO_RECOJO' | 'DESPACHO'): void {
    if (this.logisticsActionOrderId()) return;
    const call = action === 'PREPARACION' ? this.api.startPreparation(order.id, { operationId: crypto.randomUUID() })
      : action === 'LISTO_RECOJO' ? this.api.markReadyForPickup(order.id, { operationId: crypto.randomUUID() })
      : this.api.markDispatched(order.id, { operationId: crypto.randomUUID() });
    this.logisticsActionOrderId.set(order.id); this.error.set(null);
    call.pipe(finalize(() => this.logisticsActionOrderId.set(null))).subscribe({
      next: ({ data }) => { this.replaceOrder(data, true); this.notifications.success(this.statusLabel(data.status)); },
      error: (response) => this.error.set(response?.error?.message ?? 'No fue posible actualizar el estado logístico del pedido.'),
    });
  }

  beginDelivery(order: OnlineStaffOrder): void {
    if (!this.canCompleteDelivery(order) || this.logisticsActionOrderId()) return;
    this.deliveryOrder.set(order); this.scrollToDetail('staff-order-delivery'); this.deliveryForm.reset({ receiver: '', proofNote: '' }); this.error.set(null);
  }

  cancelDelivery(): void { this.deliveryOrder.set(null); }

  submitDelivery(): void {
    const order = this.deliveryOrder();
    if (!order || this.deliveryForm.invalid || this.logisticsActionOrderId()) { this.deliveryForm.markAllAsTouched(); return; }
    const value = this.deliveryForm.getRawValue(); this.logisticsActionOrderId.set(order.id); this.error.set(null);
    this.api.completeDelivery(order.id, { operationId: crypto.randomUUID(), receiver: value.receiver.trim(), proofNote: value.proofNote.trim() || null }).pipe(finalize(() => this.logisticsActionOrderId.set(null))).subscribe({
      next: ({ data }) => { this.replaceOrder(data, true); this.deliveryOrder.set(null); this.notifications.success('Entrega registrada. El inventario no se modifica en este paso.'); },
      error: (response) => this.error.set(response?.error?.message ?? 'No fue posible registrar la entrega del pedido.'),
    });
  }

  async cancelPreDispatch(order: OnlineStaffOrder): Promise<void> {
    if (!this.canCancelPreDispatch(order) || this.cancellationActionOrderId()) return;
    const reason = await this.dialogs.askReason('Cancelar pedido', 'La cancelación se registrará en el historial. Se restituirán únicamente existencias elegibles.', 'Confirmar cancelación', true);
    if (!reason || reason.trim().length < 3) { this.notifications.warning('La cancelación requiere un motivo de al menos 3 caracteres.'); return; }
    this.cancellationActionOrderId.set(order.id); this.error.set(null);
    this.api.cancelPreDispatch(order.id, { operationId: crypto.randomUUID(), reason: reason.trim() }).pipe(finalize(() => this.cancellationActionOrderId.set(null))).subscribe({
      next: ({ data }) => { this.replaceOrder(data, true); this.notifications.success(data.cancellation?.stockRestocked ? 'Pedido cancelado y existencias restituidas una sola vez.' : 'Pedido cancelado sin movimientos de existencias.'); },
      error: (response) => this.error.set(response?.error?.message ?? 'No fue posible cancelar el pedido. El estado y el inventario permanecen consistentes.'),
    });
  }

  async decideCancellation(order: OnlineStaffOrder, approved: boolean): Promise<void> {
    const cancellation = order.cancellation;
    if (!cancellation || cancellation.status !== 'SOLICITADA' || this.cancellationActionOrderId()) return;
    const reason = await this.dialogs.askReason(approved ? 'Autorizar cancelación' : 'Rechazar cancelación', 'Esta decisión quedará registrada con tu motivo y usuario responsable.', approved ? 'Autorizar' : 'Rechazar solicitud', !approved);
    if (!reason || reason.trim().length < 3) { this.notifications.warning('La resolución requiere un motivo de al menos 3 caracteres.'); return; }
    this.cancellationActionOrderId.set(order.id); this.error.set(null);
    this.api.decideCancellation(cancellation.id, { operationId: crypto.randomUUID(), decision: approved ? 'CANCELADA' : 'RECHAZADA', reason: reason.trim() }).pipe(finalize(() => this.cancellationActionOrderId.set(null))).subscribe({
      next: ({ data }) => { this.replaceOrder(data, true); this.notifications.success(approved ? 'Cancelación resuelta y existencias restituidas cuando correspondía.' : 'La solicitud de cancelación fue rechazada.'); },
      error: (response) => this.error.set(response?.error?.message ?? 'No fue posible resolver la cancelación.'),
    });
  }

  async decideReturn(order: OnlineStaffOrder, onlineReturn: OnlineOrderReturn, approved: boolean): Promise<void> {
    if (onlineReturn.status !== 'SOLICITADA' || this.returnActionId()) return;
    const reason = approved ? null : await this.dialogs.askReason('Rechazar devolución', 'Registra el motivo que podrá consultar el cliente.', 'Rechazar devolución', true);
    if (!approved && (!reason || reason.trim().length < 3)) { this.notifications.warning('El rechazo requiere un motivo de al menos 3 caracteres.'); return; }
    this.returnActionId.set(onlineReturn.id); this.error.set(null);
    this.api.decideReturn(onlineReturn.id, { operationId: crypto.randomUUID(), decision: approved ? 'AUTORIZADA' : 'RECHAZADA', reason: reason?.trim() ?? null }).pipe(finalize(() => this.returnActionId.set(null))).subscribe({
      next: ({ data }) => { this.replaceOrder(data, true); this.notifications.success(approved ? 'Devolución autorizada para recepción física.' : 'Devolución rechazada con el motivo indicado.'); },
      error: (response) => this.error.set(response?.error?.message ?? 'No fue posible resolver la devolución.'),
    });
  }

  async receiveReturn(order: OnlineStaffOrder, onlineReturn: OnlineOrderReturn): Promise<void> {
    if (onlineReturn.status !== 'AUTORIZADA' || this.returnActionId()) return;
    const quantities = await this.dialogs.askQuantities('Registrar recepción física', 'Indica las unidades realmente recibidas. La recepción todavía no repone existencias.', onlineReturn.lines.map((line) => ({ id: line.id, label: line.title, max: line.requestedQuantity })));
    if (!quantities) return;
    const lines = onlineReturn.lines.map((line) => ({ returnLineId: line.id, receivedQuantity: Number(quantities[line.id]) }));
    if (lines.some((line, index) => !Number.isInteger(line.receivedQuantity) || line.receivedQuantity < 0 || line.receivedQuantity > onlineReturn.lines[index].requestedQuantity)) { this.notifications.warning('Comprueba las cantidades recibidas.'); return; }
    this.returnActionId.set(onlineReturn.id); this.error.set(null);
    this.api.receiveReturn(onlineReturn.id, { lines }).pipe(finalize(() => this.returnActionId.set(null))).subscribe({ next: ({ data }) => { this.replaceOrder(data, true); this.notifications.success('Recepción física registrada. Falta la inspección de aptitud.'); }, error: (response) => this.error.set(response?.error?.message ?? 'No fue posible registrar la recepción.') });
  }

  async inspectReturn(order: OnlineStaffOrder, onlineReturn: OnlineOrderReturn): Promise<void> {
    if (onlineReturn.status !== 'RECIBIDA' || this.returnActionId()) return;
    const quantities = await this.dialogs.askQuantities('Inspeccionar devolución', 'Selecciona únicamente las unidades aptas para volver al stock disponible. Esta operación se audita.', onlineReturn.lines.map((line) => ({ id: line.id, label: line.title, max: line.receivedQuantity })));
    if (!quantities) return;
    const lines = onlineReturn.lines.map((line) => ({ returnLineId: line.id, restockedQuantity: Number(quantities[line.id]) }));
    if (lines.some((line, index) => !Number.isInteger(line.restockedQuantity) || line.restockedQuantity < 0 || line.restockedQuantity > onlineReturn.lines[index].receivedQuantity)) { this.notifications.warning('Comprueba las cantidades inspeccionadas.'); return; }
    this.returnActionId.set(onlineReturn.id); this.error.set(null);
    this.api.inspectReturn(onlineReturn.id, { lines }).pipe(finalize(() => this.returnActionId.set(null))).subscribe({ next: ({ data }) => { this.replaceOrder(data, true); this.notifications.success('Inspección registrada. Solo las unidades aptas volvieron al inventario.'); }, error: (response) => this.error.set(response?.error?.message ?? 'No fue posible registrar la inspección.') });
  }

  closeReturn(order: OnlineStaffOrder, onlineReturn: OnlineOrderReturn): void {
    if (!['INSPECCIONADA_APTA', 'INSPECCIONADA_NO_APTA'].includes(onlineReturn.status) || this.returnActionId()) return;
    this.returnActionId.set(onlineReturn.id); this.error.set(null);
    this.api.closeReturn(onlineReturn.id).pipe(finalize(() => this.returnActionId.set(null))).subscribe({ next: ({ data }) => { this.replaceOrder(data, true); this.notifications.success('Devolución cerrada con su resultado físico registrado.'); }, error: (response) => this.error.set(response?.error?.message ?? 'No fue posible cerrar la devolución.') });
  }

  canConfirm(order: OnlineStaffOrder): boolean { return order.status === 'PENDIENTE' || (order.status === 'EN_REVISION' && !!this.currentAcceptedProposal(order)); }
  canPropose(order: OnlineStaffOrder): boolean { return order.status === 'EN_REVISION'; }
  canRecordPayment(order: OnlineStaffOrder): boolean { return ['CONFIRMADO', 'EN_PREPARACION', 'LISTO_PARA_RECOJO', 'EN_REPARTO', 'ENTREGADO'].includes(order.status) && ['NO_DECLARADO', 'RECHAZADO'].includes(order.paymentStatus); }
  canDecidePayment(payment: OnlineManualPayment): boolean { return payment.status === 'DECLARADO'; }
  canStartPreparation(order: OnlineStaffOrder): boolean { return order.status === 'CONFIRMADO'; }
  canMarkReadyForPickup(order: OnlineStaffOrder): boolean { return order.status === 'EN_PREPARACION' && this.effectiveMode(order) === 'RECOJO'; }
  canMarkDispatched(order: OnlineStaffOrder): boolean { return order.status === 'EN_PREPARACION' && this.effectiveMode(order) === 'DELIVERY'; }
  canCompleteDelivery(order: OnlineStaffOrder): boolean { return (order.status === 'LISTO_PARA_RECOJO' || order.status === 'EN_REPARTO') && !order.deliveryRecord; }
  canCancelPreDispatch(order: OnlineStaffOrder): boolean { return ['PENDIENTE', 'CONFIRMADO', 'EN_PREPARACION', 'LISTO_PARA_RECOJO'].includes(order.status); }
  currentAcceptedProposal(order: OnlineStaffOrder): OnlineOrderProposal | null { return order.proposals.filter((proposal) => proposal.status === 'ACEPTADA').sort((a, b) => b.proposalNumber - a.proposalNumber)[0] ?? null; }
  proposalStatusLabel(status: OnlineOrderProposal['status']): string { return ({ PROPUESTA: 'Esperando respuesta del cliente', ACEPTADA: 'Aceptada por cliente', RECHAZADA: 'Rechazada por cliente', VENCIDA: 'Vencida' })[status]; }
  statusLabel(status: OnlineStaffOrder['status']): string { return ({ PENDIENTE: 'Pendiente de confirmación', EN_REVISION: 'En revisión', CAMBIO_PROPUESTO: 'Esperando decisión del cliente', CONFIRMADO: 'Confirmado', EN_PREPARACION: 'En preparación', LISTO_PARA_RECOJO: 'Listo para recojo', EN_REPARTO: 'En reparto', ENTREGADO: 'Entregado', CANCELACION_SOLICITADA: 'Cancelación solicitada', CANCELADO: 'Cancelado', DEVOLUCION_EN_PROCESO: 'Devolución en proceso', CERRADO_CON_DEVOLUCION: 'Cerrado con devolución' })[status]; }
  returnStatusLabel(status: OnlineOrderReturn['status']): string { return ({ SOLICITADA: 'Solicitada', AUTORIZADA: 'Autorizada para recepción', RECHAZADA: 'Rechazada', RECIBIDA: 'Recibida, pendiente de inspección', INSPECCIONADA_APTA: 'Inspeccionada con unidades aptas', INSPECCIONADA_NO_APTA: 'Inspeccionada sin unidades aptas', CERRADA: 'Cerrada' })[status]; }
  paymentStatusLabel(status: OnlineStaffOrder['paymentStatus']): string { return ({ NO_DECLARADO: 'Sin pago declarado', DECLARADO: 'Pago por validar', VALIDADO: 'Pago validado', RECHAZADO: 'Pago rechazado' })[status]; }
  paymentMethodLabel(method: OnlinePaymentMethod): string { return ({ EFECTIVO: 'Efectivo', TRANSFERENCIA: 'Transferencia', OTRO: 'Otro medio' })[method]; }
  modeLabel(mode: OnlineDeliveryMode): string { return mode === 'RECOJO' ? 'Recojo' : 'Delivery'; }
  trackOrder(_: number, order: OnlineStaffOrder): string { return order.id; }
  trackLine(_: number, line: OnlineOrderLine): string { return line.productId; }
  trackProposal(_: number, proposal: OnlineOrderProposal): string { return proposal.id; }

  private originalTerms(order: OnlineStaffOrder) { return { deliveryMode: order.deliveryMode, coordinationPhone: order.coordinationPhone, pickupLocation: order.pickupLocation, deliveryAddress: order.deliveryAddress, deliveryCoverage: order.deliveryCoverage, subtotalPen: order.subtotalPen, deliveryFeePen: order.deliveryFeePen, totalPen: order.totalPen, lines: order.lines }; }
  effectiveMode(order: OnlineStaffOrder): OnlineDeliveryMode { return this.currentAcceptedProposal(order)?.proposedTerms.deliveryMode ?? order.deliveryMode; }
  private lineControl(line?: OnlineOrderLine): ProposalLineForm { return this.formBuilder.nonNullable.group({ productId: [line?.productId ?? '', Validators.required], quantity: [line?.quantity ?? 1, [Validators.required, Validators.pattern(/^[1-9]\d*$/), Validators.max(1000000)]], unitPricePen: [line?.unitPricePen ?? 0, [Validators.required, Validators.min(0)]] }); }
  private replaceOrder(order: OnlineStaffOrder, select: boolean): void {
    this.orders.update((items) => items.map((item) => item.id === order.id ? order : item));
    if (select) { this.selected.set(order); this.scrollToDetail('staff-order-detail'); }
  }

  private scrollToDetail(id: string): void {
    setTimeout(() => document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 30);
  }
}
