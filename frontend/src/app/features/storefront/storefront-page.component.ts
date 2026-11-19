import { CurrencyPipe, NgFor, NgIf } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { StorefrontApiService } from '../../core/api/storefront-api.service';
import { StorefrontCatalog, StorefrontProduct } from '../../core/api/api.models';
import { NotificationService } from '../../core/notifications/notification.service';
import { AuthStateService } from '../../core/auth/auth-state.service';
import { resolveAccessTenant } from '../access/access-tenant';
import { StorefrontCartLine, StorefrontCartService } from './storefront-cart.service';

interface CartDisplayLine {
  product: StorefrontProduct;
  quantity: number;
  subtotal: number;
}

@Component({
  selector: 'app-storefront-page',
  imports: [CurrencyPipe, NgFor, NgIf, RouterLink],
  templateUrl: './storefront-page.component.html',
  styleUrl: './storefront-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StorefrontPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly storefrontApi = inject(StorefrontApiService);
  private readonly cartStorage = inject(StorefrontCartService);
  private readonly notifications = inject(NotificationService);
  private readonly auth = inject(AuthStateService);

  readonly tenantSlug = toSignal(
    this.route.paramMap.pipe(map((params) => params.get('tenantSlug') ?? '')),
    { initialValue: '' },
  );
  readonly tenant = computed(() => resolveAccessTenant(this.tenantSlug()));
  readonly returnToClient = computed(() => this.auth.context()?.accountType === 'CLIENT'
    && this.auth.context()?.tenant?.slug === this.tenantSlug());
  readonly returnToStaff = computed(() => this.auth.context()?.accountType === 'STAFF'
    && this.auth.context()?.tenant?.slug === this.tenantSlug());
  readonly catalogSearch = signal('');
  readonly availableOnly = signal(false);
  readonly catalogSort = signal<'RELEVANCIA' | 'PRECIO_ASC' | 'PRECIO_DESC'>('RELEVANCIA');
  readonly visibleProducts = computed(() => {
    const text = this.catalogSearch().trim().toLocaleLowerCase('es-PE');
    const products = (this.catalog()?.products ?? []).filter((product) => {
      if (this.availableOnly() && !product.available) return false;
      return !text || [product.title, product.description, product.categoryName]
        .some((value) => (value ?? '').toLocaleLowerCase('es-PE').includes(text));
    });
    if (this.catalogSort() === 'PRECIO_ASC') return [...products].sort((a, b) => a.salePricePen - b.salePricePen);
    if (this.catalogSort() === 'PRECIO_DESC') return [...products].sort((a, b) => b.salePricePen - a.salePricePen);
    return products;
  });
  setSort(value: string): void {
    this.catalogSort.set(value === 'PRECIO_ASC' || value === 'PRECIO_DESC' ? value : 'RELEVANCIA');
  }
  clearCatalogFilters(): void {
    this.catalogSearch.set('');
    this.availableOnly.set(false);
    this.catalogSort.set('RELEVANCIA');
  }
  readonly catalog = signal<StorefrontCatalog | null>(null);
  readonly cartLines = signal<StorefrontCartLine[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly cartNotice = signal<string | null>(null);
  readonly displayLines = computed<CartDisplayLine[]>(() => {
    const products = new Map((this.catalog()?.products ?? []).map((product) => [product.id, product] as const));
    return this.cartLines().flatMap((line) => {
      const product = products.get(line.productId);
      return product ? [{ product, quantity: line.quantity, subtotal: product.salePricePen * line.quantity }] : [];
    });
  });
  readonly cartTotal = computed(() => this.displayLines().reduce((total, line) => total + line.subtotal, 0));
  readonly cartQuantity = computed(() => this.displayLines().reduce((total, line) => total + line.quantity, 0));
  private readonly reloadOnTenantChange = effect(() => this.loadCatalog(this.tenantSlug()));

  addToCart(product: StorefrontProduct): void {
    if (!product.available) {
      this.cartNotice.set('Este producto no está disponible por el momento.');
      return;
    }
    this.cartLines.set(this.cartStorage.add(this.tenantSlug(), product.id));
    this.cartNotice.set(`${product.title} se añadió al carrito.`);
    this.notifications.success('Producto añadido al carrito.');
  }

  updateQuantity(productId: string, rawQuantity: string): void {
    const quantity = Number(rawQuantity);
    if (!Number.isInteger(quantity) || quantity < 1 || quantity > 99) {
      this.cartNotice.set('La cantidad debe ser un número entero entre 1 y 99.');
      return;
    }
    this.cartLines.set(this.cartStorage.setQuantity(this.tenantSlug(), productId, quantity));
    this.cartNotice.set('Cantidad actualizada. El total es provisional hasta un pedido futuro.');
  }

  removeFromCart(productId: string): void {
    this.cartLines.set(this.cartStorage.remove(this.tenantSlug(), productId));
    this.cartNotice.set('Producto retirado del carrito.');
  }

  private loadCatalog(tenantSlug: string): void {
    this.loading.set(true);
    this.error.set(null);
    this.cartNotice.set(null);
    this.catalog.set(null);
    if (!tenantSlug) {
      this.loading.set(false);
      this.error.set('La veterinaria solicitada no es válida.');
      return;
    }
    this.storefrontApi.loadCatalog(tenantSlug).subscribe({
      next: (response) => {
        this.catalog.set(response.data);
        const availableIds = new Set(response.data.products.filter((product) => product.available).map((product) => product.id));
        this.cartLines.set(this.cartStorage.reconcile(response.data.tenantSlug, availableIds));
        this.loading.set(false);
      },
      error: (response: HttpErrorResponse) => {
        this.loading.set(false);
        this.error.set(response.status === 404
          ? 'Esta tienda no está disponible. Elige una veterinaria activa para continuar.'
          : 'No fue posible cargar el catálogo. Inténtalo nuevamente en unos momentos.');
      },
    });
  }
}
