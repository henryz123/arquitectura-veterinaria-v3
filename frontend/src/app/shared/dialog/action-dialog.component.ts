import { NgFor, NgIf } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, HostListener, ViewChild, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActionDialogService } from './action-dialog.service';

@Component({
  selector: 'app-action-dialog',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div *ngIf="dialogs.current() as request" class="av-dialog-backdrop" (mousedown)="cancelOnBackdrop($event)" role="presentation">
      <section class="av-dialog-card" role="dialog" aria-modal="true" aria-labelledby="av-dialog-title" aria-describedby="av-dialog-description" tabindex="-1" #dialogCard>
        <div class="av-dialog-heading">
          <div class="av-dialog-icon" [class.is-danger]="request.destructive" aria-hidden="true">{{ request.destructive ? '!' : '✓' }}</div>
          <div><p class="av-dialog-kicker">Confirmación de operación</p><h2 id="av-dialog-title">{{ request.title }}</h2></div>
        </div>
        <p id="av-dialog-description" class="av-dialog-description">{{ request.description }}</p>
        <form (ngSubmit)="submit()" novalidate>
          <ng-container *ngIf="request.kind === 'reason'">
            <label for="av-dialog-reason">Motivo de la operación <span class="required-mark">*</span></label>
            <textarea #reasonField id="av-dialog-reason" name="reason" [(ngModel)]="reason" minlength="3" maxlength="500" rows="4" placeholder="Escribe un motivo claro (mínimo 3 caracteres)" required></textarea>
            <p class="av-dialog-helper">{{ reason.trim().length }} / 500 caracteres · mínimo 3</p>
            <p class="av-dialog-error" role="alert" *ngIf="attempted() && !valid()">Escribe un motivo de al menos 3 caracteres.</p>
          </ng-container>
          <div class="av-dialog-quantities" *ngIf="request.kind === 'quantities'">
            <label *ngFor="let line of request.lines" [for]="'av-quantity-' + line.id">
              <span>{{ line.label }} <small>Máximo {{ line.max }}</small></span>
              <input [id]="'av-quantity-' + line.id" type="number" step="1" min="0" [max]="line.max" [name]="'quantity-' + line.id" [(ngModel)]="quantities[line.id]" required inputmode="numeric" />
            </label>
            <p class="av-dialog-error" role="alert" *ngIf="attempted() && !valid()">Revisa las cantidades: deben ser enteros entre 0 y el máximo indicado.</p>
          </div>
          <div class="av-dialog-actions">
            <button type="button" class="av-dialog-secondary" (click)="dismiss()">Volver sin cambios</button>
            <button type="submit" class="av-dialog-primary" [class.is-danger]="request.destructive" [disabled]="!valid()">{{ request.actionLabel }}</button>
          </div>
        </form>
      </section>
    </div>
  `,
  styleUrls: ['./action-dialog.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ActionDialogComponent {
  readonly dialogs = inject(ActionDialogService);
  readonly attempted = signal(false);
  reason = '';
  quantities: Record<string, number> = {};
  @ViewChild('dialogCard') dialogCard?: ElementRef<HTMLElement>;

  private readonly resetOnOpen = effect(() => {
    const request = this.dialogs.current();
    if (!request) return;
    this.reason = '';
    this.attempted.set(false);
    this.quantities = Object.fromEntries((request.lines ?? []).map((line) => [line.id, line.initial ?? line.max]));
    setTimeout(() => this.dialogCard?.nativeElement.focus(), 0);
  });

  valid(): boolean {
    const request = this.dialogs.current();
    if (!request) return false;
    if (request.kind === 'reason') return this.reason.trim().length >= 3 && this.reason.trim().length <= 500;
    return (request.lines ?? []).length > 0 && (request.lines ?? []).every((line) =>
      Number.isInteger(Number(this.quantities[line.id])) && this.quantities[line.id] !== null && this.quantities[line.id] !== undefined
      && Number(this.quantities[line.id]) >= 0 && Number(this.quantities[line.id]) <= line.max);
  }

  submit(): void {
    this.attempted.set(true);
    if (!this.valid()) return;
    const request = this.dialogs.current();
    this.dialogs.finish(request?.kind === 'reason' ? this.reason.trim() : { ...this.quantities });
  }

  dismiss(): void { this.dialogs.finish(null); }
  cancelOnBackdrop(event: MouseEvent): void { if (event.target === event.currentTarget) this.dismiss(); }

  @HostListener('document:keydown.escape', ['$event'])
  escape(event: Event): void {
    if (this.dialogs.current()) { event.preventDefault(); this.dismiss(); }
  }
}
