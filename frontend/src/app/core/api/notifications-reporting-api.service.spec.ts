import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NotificationsReportingApiService } from './notifications-reporting-api.service';

describe('NotificationsReportingApiService', () => {
  let service: NotificationsReportingApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(NotificationsReportingApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('requests an explicitly bounded tenant audit page without a tenant parameter', () => {
    service.auditTrail('2026-11-01', '2026-11-16', 'B05', 'CONFIRMADO', 0).subscribe();
    const request = http.expectOne('/api/v1/audit-trail?from=2026-11-01&to=2026-11-16&module=B05&page=0&size=25&action=CONFIRMADO');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.has('tenantId')).toBe(false);
    request.flush({ data: { events: [], hasMore: false } });
  });

  it('keeps notifications recipient-scoped and marks a single notification read', () => {
    service.listOwnNotifications().subscribe();
    const list = http.expectOne('/api/v1/notifications');
    expect(list.request.method).toBe('GET');
    list.flush({ data: [] });

    service.markNotificationRead('notification-1').subscribe();
    const read = http.expectOne('/api/v1/notifications/notification-1/read');
    expect(read.request.method).toBe('PATCH');
    expect(read.request.body).toEqual({});
    read.flush({ data: { id: 'notification-1' } });
  });
});
