import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/** Prevent misleading labels such as "1234" or whitespace-only product names. */
export const readableName: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = String(control.value ?? '').trim();
  return value && !/[A-Za-zÁÉÍÓÚÜÑáéíóúüñ]/.test(value) ? { readableName: true } : null;
};

/** Optional remote images are accepted only with secure HTTPS URLs and no embedded credentials. */
export const optionalHttpsImage: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = String(control.value ?? '').trim();
  if (!value) return null;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && !!url.hostname && !url.username && !url.password ? null : { httpsImage: true };
  } catch { return { httpsImage: true }; }
};

/** Precision is checked numerically and cannot be bypassed with a numeric input's step attribute. */
export const twoDecimalPen: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = Number(control.value);
  if (control.value === '' || control.value === null || !Number.isFinite(value) || value < 0) return { penAmount: true };
  return Math.abs(value * 100 - Math.round(value * 100)) < 0.000001 ? null : { penAmount: true };
};
