import { CurrencyPipe, NgFor, NgIf } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { CommercialReport, CommercialReportMovement, CommercialReportProduct } from '../../core/api/api.models';
import { PointOfSaleApiService } from '../../core/api/point-of-sale-api.service';
import { AuthStateService } from '../../core/auth/auth-state.service';

function limaDateValue(offsetDays = 0): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'America/Lima', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date());
  const part = (type: string) => Number(parts.find((item) => item.type === type)?.value);
  const date = new Date(Date.UTC(part('year'), part('month') - 1, part('day') + offsetDays));
  return date.toISOString().slice(0, 10);
}

@Component({
  selector: 'app-commercial-report-page',
  imports: [CurrencyPipe, NgFor, NgIf, ReactiveFormsModule, RouterLink],
  templateUrl: './commercial-report-page.component.html',
  styleUrl: './commercial-report-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CommercialReportPageComponent implements OnInit {
  private readonly api = inject(PointOfSaleApiService);
  private readonly auth = inject(AuthStateService);
  private readonly formBuilder = inject(FormBuilder);

  readonly context = this.auth.context;
  readonly report = signal<CommercialReport | null>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly reportForm = this.formBuilder.nonNullable.group({
    from: [limaDateValue(-29), Validators.required],
    to: [limaDateValue(), Validators.required],
  });
  readonly canView = computed(() => {
    const context = this.context();
    return context?.accountType === 'STAFF' && context.roles.includes('ADMIN_VETERINARIA');
  });

  ngOnInit(): void {
    if (!this.canView()) {
      this.loading.set(false);
      this.error.set('El informe comercial completo requiere ADMIN_VETERINARIA vigente. Caja y administración se mantienen separados.');
      return;
    }
    this.load();
  }

  load(): void {
    if (!this.canView() || this.reportForm.invalid) {
      this.reportForm.markAllAsTouched();
      return;
    }
    const { from, to } = this.reportForm.getRawValue();
    if (from > to) {
      this.error.set('La fecha final no puede ser anterior a la fecha inicial.');
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.api.commercialReport(from, to).pipe(finalize(() => this.loading.set(false))).subscribe({
      next: ({ data }) => this.report.set(data),
      error: () => this.error.set('No fue posible cargar el informe. Actualiza para revalidar la sesión y los permisos.'),
    });
  }

  trackProduct(_: number, product: CommercialReportProduct): string { return product.productId; }
  trackMovement(_: number, movement: CommercialReportMovement): string { return movement.movementType; }
}
