import { Injectable } from '@angular/core';

export interface StorefrontCartLine {
  productId: string;
  quantity: number;
}

/**
 * Browser-only provisional cart. It stores product IDs and quantities under a
 * tenant-specific key; it never stores sessions, customer data, prices, or stock.
 */
@Injectable({ providedIn: 'root' })
export class StorefrontCartService {
  private readonly prefix = 'av.storefront.cart.v1.';
  private readonly maximumQuantity = 99;

  lines(tenantSlug: string): StorefrontCartLine[] {
    try {
      const parsed: unknown = JSON.parse(localStorage.getItem(this.key(tenantSlug)) ?? '[]');
      if (!Array.isArray(parsed)) {
        return [];
      }
      return parsed.flatMap((line) => this.validLine(line) ? [line] : []);
    } catch {
      return [];
    }
  }

  add(tenantSlug: string, productId: string, quantity = 1): StorefrontCartLine[] {
    const current = this.lines(tenantSlug);
    const existing = current.find((line) => line.productId === productId);
    if (existing) {
      return this.setQuantity(tenantSlug, productId, Math.min(this.maximumQuantity, existing.quantity + quantity));
    }
    return this.persist(tenantSlug, [...current, { productId, quantity: this.safeQuantity(quantity) }]);
  }

  setQuantity(tenantSlug: string, productId: string, quantity: number): StorefrontCartLine[] {
    const normalized = this.safeQuantity(quantity);
    return this.persist(tenantSlug, this.lines(tenantSlug).map((line) =>
      line.productId === productId ? { ...line, quantity: normalized } : line,
    ));
  }

  remove(tenantSlug: string, productId: string): StorefrontCartLine[] {
    return this.persist(tenantSlug, this.lines(tenantSlug).filter((line) => line.productId !== productId));
  }

  clear(tenantSlug: string): StorefrontCartLine[] {
    localStorage.removeItem(this.key(tenantSlug));
    return [];
  }

  reconcile(tenantSlug: string, availableProductIds: ReadonlySet<string>): StorefrontCartLine[] {
    return this.persist(tenantSlug, this.lines(tenantSlug).filter((line) => availableProductIds.has(line.productId)));
  }

  private persist(tenantSlug: string, lines: StorefrontCartLine[]): StorefrontCartLine[] {
    const key = this.key(tenantSlug);
    if (!lines.length) {
      localStorage.removeItem(key);
      return [];
    }
    localStorage.setItem(key, JSON.stringify(lines));
    return lines;
  }

  private key(tenantSlug: string): string {
    return `${this.prefix}${tenantSlug}`;
  }

  private safeQuantity(quantity: number): number {
    return Number.isInteger(quantity) && quantity > 0 ? Math.min(quantity, this.maximumQuantity) : 1;
  }

  private validLine(value: unknown): value is StorefrontCartLine {
    if (!value || typeof value !== 'object') {
      return false;
    }
    const line = value as Partial<StorefrontCartLine>;
    return typeof line.productId === 'string' && line.productId.length > 0
      && Number.isInteger(line.quantity) && (line.quantity ?? 0) > 0 && (line.quantity ?? 0) <= this.maximumQuantity;
  }
}
