/** Client-side guidance only. The backend remains the authority for checkout. */
export function validCoordinationPhone(raw: string): boolean {
  const value = raw.trim();
  const digits = value.replace(/\D/g, '');
  return value.length <= 40
    && digits.length >= 5
    && digits.length <= 15
    && /^\+?[0-9() .-]+$/.test(value);
}

/** Whitespace-only delivery addresses must not pass the required input check. */
export function validDeliveryAddress(raw: string): boolean {
  const value = raw.trim();
  return value.length >= 3 && value.length <= 240;
}
