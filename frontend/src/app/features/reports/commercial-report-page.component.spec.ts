import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { PointOfSaleApiService } from '../../core/api/point-of-sale-api.service';
import { AuthStateService } from '../../core/auth/auth-state.service';
import { CommercialReportPageComponent } from './commercial-report-page.component';

describe('CommercialReportPageComponent', () => {
  let fixture: ComponentFixture<CommercialReportPageComponent>;
  const api = { commercialReport: vi.fn(() => of({ data: {
    from: '2026-10-01', to: '2026-10-28', posSalesCount: 2, posSalesGrossAmountPen: 20,
    onlineConfirmedOrdersCount: 3, onlineConfirmedOrdersGrossAmountPen: 37.5,
    onlineValidatedPaymentsCount: 2, onlineValidatedPaymentsAmountPen: 25,
    cancellationsCount: 1, returnRequestsCount: 1, returnsPendingInspectionCount: 1, returnsClosedCount: 0,
    inventoryProductsCount: 3, availableStockUnits: 7, outOfStockProductsCount: 1, reconciliationMismatchesCount: 0,
    topProducts: [], movements: [],
  } })) };
  const auth = { context: signal({ accountType: 'STAFF' as const, roles: ['ADMIN_VETERINARIA'] }) };

  beforeEach(() => {
    api.commercialReport.mockClear();
    TestBed.configureTestingModule({
      imports: [CommercialReportPageComponent],
      providers: [provideRouter([]), { provide: AuthStateService, useValue: auth }, { provide: PointOfSaleApiService, useValue: api }],
    });
    fixture = TestBed.createComponent(CommercialReportPageComponent);
    fixture.detectChanges();
  });

  it('loads the server report for an explicit period instead of rendering fixed metrics', () => {
    const component = fixture.componentInstance;
    component.reportForm.setValue({ from: '2026-10-01', to: '2026-10-28' });
    component.load();

    expect(api.commercialReport).toHaveBeenLastCalledWith('2026-10-01', '2026-10-28');
    expect(component.report()?.posSalesCount).toBe(2);
    expect(component.report()?.onlineConfirmedOrdersGrossAmountPen).toBe(37.5);
    expect(component.report()?.onlineValidatedPaymentsCount).toBe(2);
    expect(component.report()?.availableStockUnits).toBe(7);
  });
});
