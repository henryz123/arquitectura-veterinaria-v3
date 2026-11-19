import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiEnvelope, StorefrontCatalog } from './api.models';

/** Anonymous, read-only B05-A endpoint. Product administration remains under CatalogApiService. */
@Injectable({ providedIn: 'root' })
export class StorefrontApiService {
  private readonly http = inject(HttpClient);

  loadCatalog(tenantSlug: string): Observable<ApiEnvelope<StorefrontCatalog>> {
    return this.http.get<ApiEnvelope<StorefrontCatalog>>(
      `/api/v1/tienda/${encodeURIComponent(tenantSlug)}/productos`,
    );
  }
}
