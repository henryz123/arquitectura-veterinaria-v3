import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { StorefrontApiService } from './storefront-api.service';

describe('StorefrontApiService', () => {
  it('uses only the anonymous read-only B05-A endpoint scoped by the route slug', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const service = TestBed.inject(StorefrontApiService);
    const http = TestBed.inject(HttpTestingController);

    service.loadCatalog('veterinaria-aurora').subscribe();
    const request = http.expectOne('/api/v1/tienda/veterinaria-aurora/productos');
    expect(request.request.method).toBe('GET');
    expect(request.request.body).toBeNull();
    request.flush({ data: { tenantSlug: 'veterinaria-aurora', products: [] } });
    http.verify();
  });
});
