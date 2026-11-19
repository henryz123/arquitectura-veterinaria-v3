import { describe, expect, it } from 'vitest';
import { FormControl } from '@angular/forms';
import { optionalHttpsImage, readableName, twoDecimalPen } from './business-input.validators';

describe('validaciones de catálogo V1', () => {
  it('rechaza nombres numéricos y admite textos españoles legibles', () => {
    expect(readableName(new FormControl('123456'))).toEqual({ readableName: true });
    expect(readableName(new FormControl(' Higiene y cuidado '))).toBeNull();
    expect(readableName(new FormControl('Baño 2'))).toBeNull();
  });
  it('admite URL vacía o HTTPS y rechaza http, URL malformada y credenciales', () => {
    expect(optionalHttpsImage(new FormControl(''))).toBeNull();
    expect(optionalHttpsImage(new FormControl('https://ejemplo.com/imagen.jpg'))).toBeNull();
    expect(optionalHttpsImage(new FormControl('http://ejemplo.com/imagen.jpg'))).toEqual({ httpsImage: true });
    expect(optionalHttpsImage(new FormControl('https://usuario:clave@ejemplo.com/imagen.jpg'))).toEqual({ httpsImage: true });
    expect(optionalHttpsImage(new FormControl('asdf'))).toEqual({ httpsImage: true });
  });
  it('acepta PEN con dos decimales y rechaza importes negativos o más precisión', () => {
    expect(twoDecimalPen(new FormControl(12.50))).toBeNull();
    expect(twoDecimalPen(new FormControl(0))).toBeNull();
    expect(twoDecimalPen(new FormControl(1.234))).toEqual({ penAmount: true });
    expect(twoDecimalPen(new FormControl(-1))).toEqual({ penAmount: true });
  });
});
