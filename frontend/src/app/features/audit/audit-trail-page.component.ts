import { DatePipe, NgFor, NgIf } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuditSourceModule, AuditTrailEvent } from '../../core/api/api.models';
import { NotificationsReportingApiService } from '../../core/api/notifications-reporting-api.service';
import { AuthStateService } from '../../core/auth/auth-state.service';

function limaDateValue(offsetDays = 0): string {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: 'America/Lima', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date());
  const part = (type: string) => Number(parts.find((item) => item.type === type)?.value);
  return new Date(Date.UTC(part('year'), part('month') - 1, part('day') + offsetDays)).toISOString().slice(0, 10);
}

@Component({
  selector: 'app-audit-trail-page',
  imports: [DatePipe, NgFor, NgIf, ReactiveFormsModule, RouterLink],
  templateUrl: './audit-trail-page.component.html',
  styleUrl: './audit-trail-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuditTrailPageComponent implements OnInit {
  private readonly api = inject(NotificationsReportingApiService);
  private readonly auth = inject(AuthStateService);
  private readonly formBuilder = inject(FormBuilder);
  readonly events = signal<AuditTrailEvent[]>([]);
  readonly hasMore = signal(false);
  readonly page = signal(0);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly modules: AuditSourceModule[] = ['ALL', 'B01', 'B02', 'B03', 'B04', 'B05'];
  readonly canView = computed(() => {
    const context = this.auth.context();
    return context?.accountType === 'STAFF' && context.roles.includes('ADMIN_VETERINARIA');
  });
  readonly filterForm = this.formBuilder.nonNullable.group({
    from: [limaDateValue(-29), Validators.required],
    to: [limaDateValue(), Validators.required],
    module: ['ALL' as AuditSourceModule, Validators.required],
    action: [''],
  });

  ngOnInit(): void {
    if (!this.canView()) { this.loading.set(false); this.error.set('La auditoría transversal requiere ADMIN_VETERINARIA vigente.'); return; }
    this.load(0);
  }

  load(page: number): void {
    if (!this.canView() || this.filterForm.invalid) { this.filterForm.markAllAsTouched(); return; }
    const value = this.filterForm.getRawValue();
    if (value.from > value.to) { this.error.set('La fecha final no puede ser anterior a la fecha inicial.'); return; }
    this.loading.set(true); this.error.set(null);
    this.api.auditTrail(value.from, value.to, value.module, value.action, page).pipe(finalize(() => this.loading.set(false))).subscribe({
      next: ({ data }) => { this.events.set(data.events); this.hasMore.set(data.hasMore); this.page.set(page); },
      error: () => this.error.set('No fue posible consultar los eventos autorizados. Recarga para revalidar tu sesión y permisos.'),
    });
  }

  eventLabel(event: AuditTrailEvent): string { return event.actionCode.replaceAll('_', ' ').toLowerCase().replace(/^./, (character) => character.toUpperCase()); }
  actorLabel(event: AuditTrailEvent): string { return event.actorPrincipalId ? `Cuenta protegida · ${event.actorPrincipalId.slice(-8).toUpperCase()}` : 'Actor no disponible'; }
  trackEvent(_: number, event: AuditTrailEvent): string { return event.sourceEventId; }
}
