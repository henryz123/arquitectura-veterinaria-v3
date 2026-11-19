import { beforeEach, describe, expect, it } from 'vitest';
import { StorefrontCartService } from './storefront-cart.service';

describe('StorefrontCartService', () => {
  let cart: StorefrontCartService;

  beforeEach(() => {
    localStorage.clear();
    cart = new StorefrontCartService();
  });

  it('persists provisional cart lines per veterinarian without storing prices or session data', () => {
    cart.add('veterinaria-aurora', 'product-a', 2);
    cart.add('veterinaria-bosque', 'product-b', 1);

    expect(cart.lines('veterinaria-aurora')).toEqual([{ productId: 'product-a', quantity: 2 }]);
    expect(cart.lines('veterinaria-bosque')).toEqual([{ productId: 'product-b', quantity: 1 }]);
    expect(localStorage.getItem('av.storefront.cart.v1.veterinaria-aurora')).not.toContain('price');
  });

  it('updates, removes, and drops product IDs no longer public without accepting invalid quantities', () => {
    cart.add('veterinaria-aurora', 'product-a', 1);
    cart.setQuantity('veterinaria-aurora', 'product-a', -8);
    expect(cart.lines('veterinaria-aurora')[0].quantity).toBe(1);

    cart.setQuantity('veterinaria-aurora', 'product-a', 4);
    expect(cart.reconcile('veterinaria-aurora', new Set(['product-a']))).toEqual([{ productId: 'product-a', quantity: 4 }]);
    expect(cart.reconcile('veterinaria-aurora', new Set())).toEqual([]);
    expect(cart.remove('veterinaria-aurora', 'product-a')).toEqual([]);
  });
});
