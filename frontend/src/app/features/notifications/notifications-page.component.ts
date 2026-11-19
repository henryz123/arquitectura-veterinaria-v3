import { DatePipe, NgFor, NgIf } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { InternalNotification } from '../../core/api/api.models';
import { NotificationsReportingApiService } from '../../core/api/notifications-reporting-api.service';
import { AuthStateService } from '../../core/auth/auth-state.service';

@Component({ selector: 'app-notifications-page', imports: [DatePipe, NgFor, NgIf, RouterLink], templateUrl: './notifications-page.component.html', styleUrl: './notifications-page.component.scss', changeDetection: ChangeDetectionStrategy.OnPush })
export class NotificationsPageComponent implements OnInit {
  private readonly api = inject(NotificationsReportingApiService);
  private readonly auth = inject(AuthStateService);
  readonly items = signal<InternalNotification[]>([]);
  readonly onlyUnread = signal(false);
  readonly unreadCount = computed(() => this.items().filter((item) => !item.readAt).length);
  readonly visibleItems = computed(() => this.onlyUnread() ? this.items().filter((item) => !item.readAt) : this.items());
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly context = this.auth.context;
  ngOnInit(): void { this.load(); }
  load(): void { this.loading.set(true); this.error.set(null); this.api.listOwnNotifications().pipe(finalize(() => this.loading.set(false))).subscribe({ next: ({ data }) => this.items.set(data), error: () => this.error.set('No fue posible cargar tus notificaciones. Recarga para revalidar la sesión.') }); }
  markRead(item: InternalNotification): void { if (item.readAt) return; this.api.markNotificationRead(item.id).subscribe({ next: ({ data }) => this.items.update((items) => items.map((current) => current.id === data.id ? data : current)), error: () => this.error.set('No fue posible actualizar esa notificación.') }); }
  title(item: InternalNotification): string {
    return ({
      ONLINE_ORDER_CREATED: 'Pedido registrado', ONLINE_ORDER_CONFIRMED: 'Pedido confirmado', ONLINE_ORDER_REVIEW_REQUIRED: 'Pedido en revisión',
      ONLINE_ORDER_READY_FOR_PICKUP: 'Pedido listo para recojo', ONLINE_ORDER_DISPATCHED: 'Pedido en reparto', ONLINE_ORDER_DELIVERED: 'Pedido entregado',
      ONLINE_ORDER_CANCELLATION_RESOLVED: 'Cancelación resuelta', ONLINE_ORDER_RETURN_RESOLVED: 'Devolución resuelta',
      SCHEDULE_REQUESTED: 'Nueva solicitud de cita', SCHEDULE_COUNTERPROPOSED: 'Nueva contrapropuesta de cita',
      SCHEDULE_RESCHEDULE_PROPOSED: 'Nueva propuesta de reprogramación', SCHEDULE_CONFIRMED: 'Cita confirmada',
      SCHEDULE_RESCHEDULED: 'Cita reprogramada', SCHEDULE_REASSIGNED: 'Profesional reasignado',
      SCHEDULE_CANCELLATION_RESOLVED: 'Cancelación de cita resuelta', GROOMING_CONFIRMED: 'Grooming confirmado',
      GROOMING_COMPLETED: 'Grooming completado'
    } as Record<string, string>)[item.notificationCode] ?? 'Actualización autorizada';
  }
  detail(item: InternalNotification): string {
    return ({
      ONLINE_ORDER_CREATED: 'El pedido quedó registrado y está pendiente de revisión.', ONLINE_ORDER_CONFIRMED: 'El pedido fue confirmado por personal autorizado.',
      ONLINE_ORDER_REVIEW_REQUIRED: 'El pedido requiere revisión por disponibilidad de existencias.', ONLINE_ORDER_READY_FOR_PICKUP: 'El pedido está listo para recojo.',
      ONLINE_ORDER_DISPATCHED: 'El pedido se encuentra en reparto.', ONLINE_ORDER_DELIVERED: 'La entrega física fue registrada.',
      ONLINE_ORDER_CANCELLATION_RESOLVED: 'La cancelación del pedido fue resuelta.', ONLINE_ORDER_RETURN_RESOLVED: 'La devolución del pedido fue resuelta.',
      SCHEDULE_REQUESTED: 'Hay una solicitud de cita pendiente de gestión.', SCHEDULE_COUNTERPROPOSED: 'Revisa la nueva alternativa de horario.',
      SCHEDULE_RESCHEDULE_PROPOSED: 'Revisa la nueva propuesta de reprogramación.', SCHEDULE_CONFIRMED: 'La cita fue confirmada.',
      SCHEDULE_RESCHEDULED: 'La cita fue reprogramada.', SCHEDULE_REASSIGNED: 'La asignación profesional de la cita cambió.',
      SCHEDULE_CANCELLATION_RESOLVED: 'La solicitud de cancelación fue resuelta.', GROOMING_CONFIRMED: 'El servicio de grooming fue confirmado.',
      GROOMING_COMPLETED: 'El servicio de grooming fue completado.'
    } as Record<string, string>)[item.notificationCode] ?? 'Existe una actualización disponible en tu cuenta.';
  }
  backLink(): string { return this.context()?.accountType === 'CLIENT' ? '/client-space' : '/workspace'; }
  trackItem(_: number, item: InternalNotification): string { return item.id; }
}
