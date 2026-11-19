import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiEnvelope, AuditSourceModule, AuditTrailPage, InternalNotification } from './api.models';

@Injectable({ providedIn: 'root' })
export class NotificationsReportingApiService {
  private readonly http = inject(HttpClient);

  auditTrail(from: string, to: string, module: AuditSourceModule, action: string, page: number, size = 25): Observable<ApiEnvelope<AuditTrailPage>> {
    let params = new HttpParams().set('from', from).set('to', to).set('module', module).set('page', page).set('size', size);
    if (action.trim()) params = params.set('action', action.trim());
    return this.http.get<ApiEnvelope<AuditTrailPage>>('/api/v1/audit-trail', { params });
  }

  listOwnNotifications(): Observable<ApiEnvelope<InternalNotification[]>> {
    return this.http.get<ApiEnvelope<InternalNotification[]>>('/api/v1/notifications');
  }

  markNotificationRead(notificationId: string): Observable<ApiEnvelope<InternalNotification>> {
    return this.http.patch<ApiEnvelope<InternalNotification>>(`/api/v1/notifications/${encodeURIComponent(notificationId)}/read`, {});
  }
}
