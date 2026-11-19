import { CurrencyPipe, DatePipe, NgFor, NgIf } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize, forkJoin, switchMap } from 'rxjs';
import { PointOfSaleProduct, PosReturn, PosSale, PosServiceChargeRequest, ServiceChargeCandidate } from '../../core/api/api.models';
import { PointOfSaleApiService } from '../../core/api/point-of-sale-api.service';
import { AuthStateService } from '../../core/auth/auth-state.service';

interface CartLine {
  product: PointOfSaleProduct;
  quantity: number;
}

@Component({
  selector: 'app-pos-page',
  imports: [CurrencyPipe, DatePipe, NgFor, NgIf, ReactiveFormsModule, RouterLink],
  templateUrl: './pos-page.component.html',
  styleUrl: './pos-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PosPageComponent implements OnInit {
  private readonly api = inject(PointOfSaleApiService);
  private readonly auth = inject(AuthStateService);
  private readonly formBuilder = inject(FormBuilder);

  readonly context = this.auth.context;
  readonly products = signal<PointOfSaleProduct[]>([]);
  readonly serviceChargeCandidates = signal<ServiceChargeCandidate[]>([]);
  readonly serviceChargeSelections = signal<Record<string, string[]>>({});
  readonly sales = signal<PosSale[]>([]);
  readonly returns = signal<PosReturn[]>([]);
  readonly cart = signal<CartLine[]>([]);
  readonly receipt = signal<PosSale | null>(null);
  readonly selectedSale = signal<PosSale | null>(null);
  readonly selectedReturn = signal<PosReturn | null>(null);
  readonly returnQuantities = signal<Record<string, number>>({});
  readonly receivedQuantities = signal<Record<string, number>>({});
  readonly restockedQuantities = signal<Record<string, number>>({});
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly searchForm = this.formBuilder.nonNullable.group({ query: ['', Validators.maxLength(160)] });
  readonly paymentForm = this.formBuilder.nonNullable.group({
    paymentMethod: ['EFECTIVO' as 'EFECTIVO' | 'TRANSFERENCIA' | 'OTRO', Validators.required],
  });
  readonly correctionForm = this.formBuilder.nonNullable.group({
    reason: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(500)]],
    itemsLeftPremises: [false],
  });
  readonly canUsePos = computed(() => {
    const context = this.context();
    return context?.accountType === 'STAFF' && context.roles.includes('CAJERO');
  });
  readonly canManageCorrections = computed(() => {
    const context = this.context();
    return context?.accountType === 'STAFF'
      && (context.roles.includes('CAJERO') || context.roles.includes('ADMIN_VETERINARIA'));
  });
  readonly total = computed(() => this.cart().reduce((sum, line) => sum + line.product.salePricePen * line.quantity, 0));

  ngOnInit(): void {
    if (!this.canManageCorrections()) {
      this.loading.set(false);
      this.error.set('La caja y las correcciones comerciales requieren CAJERO o ADMIN_VETERINARIA vigente.');
      return;
    }
    this.reload();
  }

  reload(): void {
    if (!this.canManageCorrections()) return;
    this.loading.set(true);
    this.error.set(null);
    const sales = this.api.listSales();
    const returns = this.api.listReturns();
    if (!this.canUsePos()) {
      forkJoin({ sales, returns }).subscribe({
        next: (result) => {
          this.sales.set(result.sales.data);
          this.returns.set(result.returns.data);
          this.products.set([]);
          this.serviceChargeCandidates.set([]);
          this.loading.set(false);
        },
        error: () => this.loadError(),
      });
      return;
    }
    const query = this.searchForm.controls.query.value.trim();
    forkJoin({ products: this.api.listProducts(query || undefined), sales, returns, charges: this.api.listEligibleServiceCharges() }).subscribe({
      next: (result) => {
        this.products.set(result.products.data);
        this.sales.set(result.sales.data);
        this.returns.set(result.returns.data);
        this.serviceChargeCandidates.set(result.charges.data);
        this.pruneServiceChargeSelections();
        this.loading.set(false);
      },
      error: () => this.loadError(),
    });
  }

  addProduct(product: PointOfSaleProduct): void {
    if (this.saving() || !this.canUsePos() || !this.canAdd(product)) return;
    this.cart.update((lines) => {
      const existing = lines.find((line) => line.product.id === product.id);
      if (!existing) return [...lines, { product, quantity: 1 }];
      return lines.map((line) => line.product.id === product.id ? { ...line, quantity: line.quantity + 1 } : line);
    });
  }

  setQuantity(productId: string, rawValue: string): void {
    const quantity = this.quantity(rawValue, 1);
    if (quantity === null) {
      this.error.set('Cada cantidad debe ser un entero positivo dentro del rango permitido.');
      return;
    }
    const line = this.cart().find((item) => item.product.id === productId);
    if (!line || (line.product.inventoryManaged && (line.product.availableQuantity ?? 0) < quantity)) {
      this.error.set('La cantidad solicitada excede el saldo disponible mostrado. El servidor volverá a validarlo al confirmar.');
      return;
    }
    this.cart.update((lines) => lines.map((item) => item.product.id === productId ? { ...item, quantity } : item));
    this.serviceChargeSelections.update((current) => ({
      ...current,
      [productId]: (current[productId] ?? []).slice(0, quantity),
    }));
  }

  removeProduct(productId: string): void {
    this.cart.update((lines) => lines.filter((line) => line.product.id !== productId));
    this.serviceChargeSelections.update((current) => {
      const next = { ...current };
      delete next[productId];
      return next;
    });
  }

  toggleServiceCharge(productId: string, candidate: ServiceChargeCandidate, checked: boolean): void {
    const line = this.cart().find((item) => item.product.id === productId);
    if (!line || line.product.inventoryManaged || this.saving()) return;
    const key = this.serviceChargeKey(candidate);
    const current = this.serviceChargeSelections()[productId] ?? [];
    if (checked && current.includes(key)) return;
    if (!checked && !current.includes(key)) return;
    if (checked && current.length >= line.quantity) {
      this.error.set('Las prestaciones vinculadas no pueden superar la cantidad del servicio seleccionado.');
      return;
    }
    if (checked && this.isServiceChargeSelectedElsewhere(productId, key)) {
      this.error.set('Una prestación solo puede asociarse una vez a la venta actual.');
      return;
    }
    this.error.set(null);
    this.serviceChargeSelections.update((selections) => ({
      ...selections,
      [productId]: checked ? [...current, key] : current.filter((value) => value !== key),
    }));
  }

  selectedServiceChargeCount(productId: string): number {
    return (this.serviceChargeSelections()[productId] ?? []).length;
  }

  isServiceChargeSelected(productId: string, candidate: ServiceChargeCandidate): boolean {
    return (this.serviceChargeSelections()[productId] ?? []).includes(this.serviceChargeKey(candidate));
  }

  availableServiceCharges(productId: string): ServiceChargeCandidate[] {
    return this.serviceChargeCandidates().filter((candidate) => !this.isServiceChargeSelectedElsewhere(productId, this.serviceChargeKey(candidate)));
  }

  prestationLabel(candidate: ServiceChargeCandidate): string {
    const type = candidate.prestationType === 'CLINICA' ? 'Atención clínica finalizada' : 'Servicio de grooming completado';
    return `${type} · ${candidate.petName}`;
  }

  confirmSale(): void {
    if (!this.canUsePos() || this.cart().length === 0 || this.saving() || this.paymentForm.invalid) {
      this.paymentForm.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.notice.set(null);
    const serviceCharges = this.selectedServiceChargeRequests();
    if (serviceCharges === null) {
      this.saving.set(false);
      return;
    }
    const paymentMethod = this.paymentForm.controls.paymentMethod.value;
    const request = { operationId: crypto.randomUUID(), lines: this.cart().map((line) => ({ productId: line.product.id, quantity: line.quantity })) };
    this.api.createSale(request).pipe(
      switchMap(({ data }) => this.api.confirmSale(data.id, {
        paymentMethod, declaredAmountPen: Number(this.total().toFixed(2)), serviceCharges,
      })),
      finalize(() => this.saving.set(false)),
    ).subscribe({
      next: ({ data }) => {
        this.receipt.set(data);
        this.cart.set([]);
        this.serviceChargeSelections.set({});
        this.notice.set(data.serviceCharges.length
          ? 'La venta y la prestación realizada quedaron vinculadas en el comprobante interno.'
          : 'La venta fue confirmada una sola vez y el comprobante interno ya está disponible.');
        this.reload();
      },
      error: () => this.error.set('No fue posible confirmar la venta. El servidor conserva el saldo y rechaza confirmaciones incompletas.'),
    });
  }

  openReceipt(saleId: string): void {
    this.error.set(null);
    this.api.findSale(saleId).subscribe({
      next: ({ data }) => this.receipt.set(data),
      error: () => this.error.set('No fue posible recuperar el comprobante solicitado.'),
    });
  }

  openCorrection(sale: PosSale): void {
    this.selectedSale.set(sale);
    this.selectedReturn.set(null);
    this.correctionForm.reset({ reason: '', itemsLeftPremises: false });
    this.returnQuantities.set(Object.fromEntries(sale.lines.filter((line) => line.inventoryManaged).map((line) => [line.id, 0])));
  }

  setReturnQuantity(lineId: string, rawValue: string): void {
    const quantity = this.quantity(rawValue, 0);
    if (quantity === null) return;
    this.returnQuantities.update((current) => ({ ...current, [lineId]: quantity }));
  }

  cancelSelectedSale(): void {
    const sale = this.selectedSale();
    if (!sale || this.saving() || this.correctionForm.invalid) {
      this.correctionForm.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.api.cancelSale(sale.id, {
      operationId: crypto.randomUUID(),
      reason: this.correctionForm.controls.reason.value.trim(),
      itemsLeftPremises: this.correctionForm.controls.itemsLeftPremises.value,
    }).pipe(finalize(() => this.saving.set(false))).subscribe({
      next: ({ data }) => {
        this.notice.set(data.stockRestocked
          ? 'La anulación quedó auditada y el stock se recompuso porque no hubo salida física.'
          : 'La anulación quedó auditada sin reponer stock porque hubo salida física.');
        this.selectedSale.set(null);
        this.reload();
      },
      error: () => this.error.set('No fue posible anular la venta. El historial y el stock no se alteraron parcialmente.'),
    });
  }

  requestReturn(): void {
    const sale = this.selectedSale();
    const quantities = this.returnQuantities();
    const lines = sale?.lines.filter((line) => line.inventoryManaged && (quantities[line.id] ?? 0) > 0)
      .map((line) => ({ saleLineId: line.id, quantity: quantities[line.id] })) ?? [];
    if (!sale || this.saving() || this.correctionForm.invalid || lines.length === 0) {
      this.correctionForm.markAllAsTouched();
      this.error.set('Indica un motivo y al menos una cantidad física para solicitar la devolución.');
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.api.requestReturn(sale.id, { operationId: crypto.randomUUID(), reason: this.correctionForm.controls.reason.value.trim(), lines })
      .pipe(finalize(() => this.saving.set(false))).subscribe({
        next: ({ data }) => {
          this.openReturn(data);
          this.notice.set('La devolución quedó solicitada; el stock no cambia hasta la recepción e inspección apta.');
          this.reload();
        },
        error: () => this.error.set('No fue posible solicitar la devolución. Se validan líneas y cantidades contra la venta real.'),
      });
  }

  openReturn(posReturn: PosReturn): void {
    this.selectedReturn.set(posReturn);
    this.selectedSale.set(null);
    this.receivedQuantities.set(Object.fromEntries(posReturn.lines.map((line) => [line.id, line.receivedQuantity])));
    this.restockedQuantities.set(Object.fromEntries(posReturn.lines.map((line) => [line.id, line.restockedQuantity])));
  }

  setReceivedQuantity(lineId: string, rawValue: string): void {
    const quantity = this.quantity(rawValue, 0);
    if (quantity === null) return;
    this.receivedQuantities.update((current) => ({ ...current, [lineId]: quantity }));
  }

  setRestockedQuantity(lineId: string, rawValue: string): void {
    const quantity = this.quantity(rawValue, 0);
    if (quantity === null) return;
    this.restockedQuantities.update((current) => ({ ...current, [lineId]: quantity }));
  }

  receiveSelectedReturn(): void {
    const posReturn = this.selectedReturn();
    if (!posReturn || posReturn.status !== 'SOLICITADA' || this.saving()) return;
    const quantities = this.receivedQuantities();
    this.saving.set(true);
    this.api.receiveReturn(posReturn.id, posReturn.lines.map((line) => ({ returnLineId: line.id, receivedQuantity: quantities[line.id] ?? 0 })))
      .pipe(finalize(() => this.saving.set(false))).subscribe({
        next: ({ data }) => {
          this.openReturn(data);
          this.notice.set('La recepción física quedó registrada; el stock continúa sin cambios hasta la inspección.');
          this.reload();
        },
        error: () => this.error.set('No fue posible registrar la recepción. Revisa que las cantidades no excedan lo solicitado.'),
      });
  }

  inspectSelectedReturn(): void {
    const posReturn = this.selectedReturn();
    if (!posReturn || posReturn.status !== 'RECIBIDA' || this.saving()) return;
    const quantities = this.restockedQuantities();
    this.saving.set(true);
    this.api.inspectReturn(posReturn.id, posReturn.lines.map((line) => ({ returnLineId: line.id, restockedQuantity: quantities[line.id] ?? 0 })))
      .pipe(finalize(() => this.saving.set(false))).subscribe({
        next: ({ data }) => {
          this.openReturn(data);
          this.notice.set('La inspección quedó auditada; solo las unidades aptas se restituyeron una vez al stock.');
          this.reload();
        },
        error: () => this.error.set('No fue posible cerrar la inspección. Las cantidades aptas no pueden superar lo físicamente recibido.'),
      });
  }

  closeSelectedReturn(): void {
    const posReturn = this.selectedReturn();
    if (!posReturn || !['INSPECCIONADA_APTA', 'INSPECCIONADA_NO_APTA'].includes(posReturn.status) || this.saving()) return;
    this.saving.set(true);
    this.api.closeReturn(posReturn.id).pipe(finalize(() => this.saving.set(false))).subscribe({
      next: ({ data }) => {
        this.openReturn(data);
        this.notice.set('La devolución quedó cerrada y el historial de venta conserva su estado final.');
        this.reload();
      },
      error: () => this.error.set('No fue posible cerrar la devolución inspeccionada.'),
    });
  }

  lineSubtotal(line: CartLine): number {
    return line.product.salePricePen * line.quantity;
  }

  canAdd(product: PointOfSaleProduct): boolean {
    return !product.inventoryManaged || (product.availableQuantity ?? 0) > (this.cart().find((line) => line.product.id === product.id)?.quantity ?? 0);
  }

  availability(product: PointOfSaleProduct): string {
    return product.inventoryManaged ? `${product.availableQuantity ?? 0} disponibles` : 'Servicio sin stock';
  }

  returnStatus(status: PosReturn['status']): string {
    return status.replaceAll('_', ' ').toLowerCase().replace(/(^|\s)\S/g, (letter) => letter.toUpperCase());
  }

  trackProduct(_: number, product: PointOfSaleProduct): string { return product.id; }
  trackSale(_: number, sale: PosSale): string { return sale.id; }
  trackReturn(_: number, posReturn: PosReturn): string { return posReturn.id; }
  trackLine(_: number, line: CartLine): string { return line.product.id; }
  trackReturnLine(_: number, line: PosReturn['lines'][number]): string { return line.id; }
	trackServiceCharge(_: number, candidate: ServiceChargeCandidate): string { return `${candidate.prestationType}:${candidate.prestationId}`; }

  private loadError(): void {
    this.loading.set(false);
    this.error.set('No fue posible cargar las operaciones del tenant. Actualiza para revalidar la sesión y los permisos.');
  }

  private selectedServiceChargeRequests(): PosServiceChargeRequest[] | null {
    const candidates = new Map(this.serviceChargeCandidates().map((candidate) => [this.serviceChargeKey(candidate), candidate]));
    const requests: PosServiceChargeRequest[] = [];
    for (const line of this.cart()) {
      if (line.product.inventoryManaged) continue;
      for (const key of this.serviceChargeSelections()[line.product.id] ?? []) {
        const candidate = candidates.get(key);
        if (!candidate) {
          this.error.set('Una prestación seleccionada ya no está disponible. Actualiza la caja antes de confirmar.');
          return null;
        }
        requests.push({ productId: line.product.id, prestationType: candidate.prestationType, prestationId: candidate.prestationId });
      }
    }
    return requests;
  }

  private pruneServiceChargeSelections(): void {
    const candidateKeys = new Set(this.serviceChargeCandidates().map((candidate) => this.serviceChargeKey(candidate)));
    const cartQuantities = new Map(this.cart().map((line) => [line.product.id, line.quantity]));
    this.serviceChargeSelections.update((current) => Object.fromEntries(Object.entries(current)
      .filter(([productId]) => cartQuantities.has(productId))
      .map(([productId, values]) => [productId, values.filter((value) => candidateKeys.has(value)).slice(0, cartQuantities.get(productId))])));
  }

  private isServiceChargeSelectedElsewhere(productId: string, key: string): boolean {
    return Object.entries(this.serviceChargeSelections()).some(([selectedProductId, values]) => selectedProductId !== productId && values.includes(key));
  }

  private serviceChargeKey(candidate: ServiceChargeCandidate): string {
    return `${candidate.prestationType}:${candidate.prestationId}`;
  }

  private quantity(rawValue: string, minimum: number): number | null {
    const value = Number(rawValue);
    return Number.isInteger(value) && value >= minimum && value <= 1_000_000 ? value : null;
  }
}
