import { describe, expect, it } from 'vitest';
import { validCoordinationPhone, validDeliveryAddress } from './checkout-validation';

describe('checkout client-side validation', () => {
  it('accepts ordinary local and international coordination numbers', () => {
    expect(validCoordinationPhone('999 050 099')).toBe(true);
    expect(validCoordinationPhone('+51 999 050 099')).toBe(true);
  });

  it('rejects blank, letter-only, malformed and excessive phone values', () => {
    expect(validCoordinationPhone('     ')).toBe(false);
    expect(validCoordinationPhone('abcdefgh')).toBe(false);
    expect(validCoordinationPhone('----123')).toBe(false);
    expect(validCoordinationPhone('999\n050')).toBe(false);
    expect(validCoordinationPhone('1'.repeat(41))).toBe(false);
  });

  it('requires a meaningful, bounded delivery address', () => {
    expect(validDeliveryAddress('Av. Perú 123')).toBe(true);
    expect(validDeliveryAddress('  Av. Perú 123  ')).toBe(true);
    expect(validDeliveryAddress('   ')).toBe(false);
    expect(validDeliveryAddress('A'.repeat(241))).toBe(false);
  });
});
