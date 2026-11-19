import { NgIf } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { OnlineOrderApiService } from '../../core/api/online-order-api.service';
import { NotificationService } from '../../core/notifications/notification.service';
@Component({selector:'app-online-delivery-settings-page',imports:[FormsModule,NgIf,RouterLink],templateUrl:'./online-delivery-settings-page.component.html',styleUrl:'./online-delivery-settings-page.component.scss',changeDetection:ChangeDetectionStrategy.OnPush})
export class OnlineDeliverySettingsPageComponent implements OnInit {
 private readonly api=inject(OnlineOrderApiService);private readonly notifications=inject(NotificationService);readonly loading=signal(true);readonly saving=signal(false);readonly error=signal<string|null>(null);readonly draft={pickupLocation:'',deliveryEnabled:false,fixedDeliveryFeePen:0,coverageDescription:''};
 ngOnInit():void{this.api.configuration().subscribe({next:r=>{if(r.data)Object.assign(this.draft,{...r.data,coverageDescription:r.data.coverageDescription??''});this.loading.set(false);},error:()=>{this.error.set('No fue posible cargar la configuración autorizada.');this.loading.set(false);}});}
 save(form: NgForm): void {
   if (this.saving()) return;
   if (form.invalid) {
     form.control.markAllAsTouched();
     this.error.set('Revisa los campos marcados antes de guardar la configuración.');
     return;
   }
   const pickupLocation = this.draft.pickupLocation.trim();
   const coverageDescription = this.draft.coverageDescription.trim();
   const fee = Number(this.draft.fixedDeliveryFeePen);
   if (pickupLocation.length < 3) {
     this.error.set('El punto de recojo debe contener al menos tres caracteres útiles.');
     return;
   }
   if (this.draft.deliveryEnabled && coverageDescription.length < 3) {
     this.error.set('Describe la cobertura del reparto; no puede contener solo espacios.');
     return;
   }
   if (this.draft.deliveryEnabled && (!Number.isFinite(fee) || fee < 0 || Math.abs(fee * 100 - Math.round(fee * 100)) > 0.000001)) {
     this.error.set('La tarifa debe ser un importe válido con un máximo de dos decimales.');
     return;
   }
   this.error.set(null);
   this.saving.set(true);
   this.api.saveConfiguration({
     ...this.draft,
     pickupLocation,
     coverageDescription: this.draft.deliveryEnabled ? coverageDescription : null,
   }).pipe(finalize(() => this.saving.set(false))).subscribe({
     next: () => this.notifications.success('Configuración de delivery actualizada.'),
     error: e => this.error.set(e?.error?.error?.message ?? 'No fue posible guardar la configuración.'),
   });
 }
}
