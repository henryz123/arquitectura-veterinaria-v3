import { CurrencyPipe, NgFor, NgIf } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, effect, inject, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize, forkJoin } from 'rxjs';
import { AdministrationApiService } from '../../core/api/administration-api.service';
import { ClientOwnerLinkStatus, ClientPetProfile, ClientScheduleProposal, ClientSpace, OfferedService, OnlineOrder, OnlineOrderLine, OnlineOrderProposal, OwnerProfile, OwnerRequest, PetRequest, ScheduleRequest } from '../../core/api/api.models';
import { OnlineOrderApiService } from '../../core/api/online-order-api.service';
import { ScheduleApiService } from '../../core/api/schedule-api.service';
import { AuthStateService } from '../../core/auth/auth-state.service';
import { NotificationService } from '../../core/notifications/notification.service';
import { ActionDialogService } from '../../shared/dialog/action-dialog.service';

type ClientSection = 'home' | 'pets' | 'register-pet' | 'appointments' | 'request-appointment' | 'profile' | 'orders';

@Component({
  selector: 'app-client-space-page',
  imports: [CurrencyPipe, FormsModule, NgFor, NgIf, RouterLink],
  templateUrl: './client-space-page.component.html',
  styleUrl: './client-space-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ClientSpacePageComponent implements OnInit {
  private readonly administrationApi = inject(AdministrationApiService);
  private readonly scheduleApi = inject(ScheduleApiService);
  private readonly onlineOrdersApi = inject(OnlineOrderApiService);
  private readonly auth = inject(AuthStateService);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);
  private readonly dialogs = inject(ActionDialogService);
  readonly space = signal<ClientSpace | null>(null);
  readonly linkStatus = signal<ClientOwnerLinkStatus | null>(null);
  readonly pets = signal<ClientPetProfile[]>([]);
  readonly scheduleServices = signal<OfferedService[]>([]);
  readonly scheduleRequests = signal<ScheduleRequest[]>([]);
  readonly scheduleProposals = signal<ClientScheduleProposal[]>([]);
  readonly onlineOrders = signal<OnlineOrder[]>([]);
  readonly returnOrder = signal<OnlineOrder | null>(null);
  readonly ownerProfile = signal<OwnerProfile | null>(null);
  readonly editingPet = signal<ClientPetProfile | null>(null);
  readonly activeSection = signal<ClientSection>('home');
  readonly loading = signal(true);
  readonly actionPending = signal(false);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  private lastNotice: string | null = null;
  private readonly announceNotice = effect(() => {
    const message = this.notice();
    if (message && message !== this.lastNotice) {
      this.notifications.success(message);
    }
    this.lastNotice = message;
  });
  readonly ownerDraft: OwnerRequest = { givenNames: '', familyNames: '', phone: null, email: null, address: null };
  readonly newPetDraft: PetRequest = this.emptyPetDraft();
  readonly petEditDraft: PetRequest = this.emptyPetDraft();
  readonly scheduleDraft = { petId: '', serviceId: '', requestedStartsAt: '', observation: '' };
  readonly returnDraft = { reason: '', quantities: {} as Record<string, number> };

  ngOnInit(): void {
    forkJoin({
      space: this.administrationApi.clientSpace(),
      linkStatus: this.administrationApi.getClientOwnerLinkStatus(),
      pets: this.administrationApi.listClientPets(),
	  scheduleServices: this.scheduleApi.listClientScheduleServices(),
	  scheduleRequests: this.scheduleApi.listClientScheduleRequests(),
	  scheduleProposals: this.scheduleApi.listClientScheduleProposals(),
	  onlineOrders: this.onlineOrdersApi.listOwn(),
    }).subscribe({
      next: (response) => {
        this.space.set(response.space.data);
        this.linkStatus.set(response.linkStatus.data);
        this.pets.set(response.pets.data);
		this.scheduleServices.set(response.scheduleServices.data);
		this.scheduleRequests.set(response.scheduleRequests.data);
		this.scheduleProposals.set(response.scheduleProposals.data);
		this.onlineOrders.set(response.onlineOrders.data);
        if (response.linkStatus.data.status === 'APROBADA') {
          this.loadOwnerProfile();
        }
        this.loading.set(false);
      },
      error: () => {
        this.error.set('No fue posible cargar tu espacio privado autorizado. Inténtalo nuevamente.');
        this.loading.set(false);
      },
    });
  }

  canRequestLink(): boolean {
    const status = this.linkStatus()?.status ?? 'SIN_SOLICITUD';
    return status === 'SIN_SOLICITUD' || status === 'RECHAZADA';
  }

  showSection(section: ClientSection): void {
    this.activeSection.set(section);
  }

  isSection(...sections: ClientSection[]): boolean {
    return sections.includes(this.activeSection());
  }

  requestOwnerLink(): void {
    if (!this.canRequestLink() || this.actionPending()) {
      return;
    }
    this.error.set(null);
    this.notice.set(null);
    this.actionPending.set(true);
    this.administrationApi.requestClientOwnerLink().pipe(finalize(() => this.actionPending.set(false))).subscribe({
      next: (response) => {
        this.linkStatus.set(response.data);
        this.notice.set('Tu solicitud fue enviada. El personal verificará manualmente la identidad y la relación antes de habilitar el acceso.');
      },
      error: () => this.error.set('No fue posible registrar la solicitud. No se reveló ningún registro privado.'),
    });
  }

  saveOwnerProfile(form: NgForm): void {
    if (form.invalid || this.actionPending()) {
      form.control.markAllAsTouched();
      if (!this.actionPending()) {
        this.notifications.warning('Completa los campos indicados antes de guardar tus datos.');
      }
      return;
    }
    const phoneMessage = this.phoneValidationMessage(this.ownerDraft.phone);
    if (phoneMessage) {
      this.error.set(phoneMessage);
      this.notifications.warning(phoneMessage);
      return;
    }
    this.error.set(null);
    this.notice.set(null);
    this.actionPending.set(true);
    this.administrationApi.updateClientOwnerProfile(this.normalizedOwnerDraft()).pipe(finalize(() => this.actionPending.set(false))).subscribe({
      next: (response) => {
        this.applyOwnerProfile(response.data);
        this.notice.set('Tus datos basicos fueron actualizados. El correo de acceso no se modifica desde esta pantalla.');
      },
      error: () => this.error.set('No fue posible actualizar la ficha basica autorizada.'),
    });
  }

  createPet(form: NgForm): void {
    if (form.invalid || this.actionPending()) {
      form.control.markAllAsTouched();
      if (!this.actionPending()) {
        this.notifications.warning('Completa los campos indicados antes de registrar la mascota.');
      }
      return;
    }
    const request = this.validPetRequest(this.newPetDraft);
    if (!request) {
      return;
    }
    this.error.set(null);
    this.notice.set(null);
    this.actionPending.set(true);
    this.administrationApi.createClientPet(request).pipe(finalize(() => this.actionPending.set(false))).subscribe({
      next: (response) => {
        this.pets.update((pets) => [...pets, response.data].sort((left, right) => left.name.localeCompare(right.name)));
        Object.assign(this.newPetDraft, this.emptyPetDraft());
        this.activeSection.set('pets');
        this.notice.set('La ficha basica de tu mascota fue registrada. La historia clinica sigue siendo privada.');
      },
      error: () => this.error.set('No fue posible registrar la mascota. Verifica los datos basicos e intentalo nuevamente.'),
    });
  }

  beginPetEdit(pet: ClientPetProfile): void {
    this.activeSection.set('pets');
    this.editingPet.set(pet);
    Object.assign(this.petEditDraft, {
      name: pet.name,
      species: pet.species,
      breed: pet.breed,
      sex: pet.sex,
      birthDate: pet.birthDate,
      estimatedAgeMonths: pet.estimatedAgeMonths,
      distinguishingMarks: pet.distinguishingMarks,
    });
  }

  cancelPetEdit(): void {
    this.editingPet.set(null);
    Object.assign(this.petEditDraft, this.emptyPetDraft());
  }

  savePet(form: NgForm): void {
    const pet = this.editingPet();
    if (!pet || form.invalid || this.actionPending()) {
      form.control.markAllAsTouched();
      if (pet && !this.actionPending()) {
        this.notifications.warning('Completa los campos indicados antes de guardar la ficha.');
      }
      return;
    }
    const request = this.validPetRequest(this.petEditDraft);
    if (!request) {
      return;
    }
    this.error.set(null);
    this.notice.set(null);
    this.actionPending.set(true);
    this.administrationApi.updateClientPet(pet.id, request).pipe(finalize(() => this.actionPending.set(false))).subscribe({
      next: (response) => {
        this.pets.update((pets) => pets.map((item) => item.id === response.data.id ? response.data : item));
        this.cancelPetEdit();
        this.notice.set('La ficha basica fue actualizada. No se modificaron datos clinicos ni responsabilidades.');
      },
      error: () => this.error.set('No fue posible actualizar esta ficha. Puede que ya no tengas la responsabilidad vigente.'),
    });
  }

	createScheduleRequest(form: NgForm): void {
		if (form.invalid || this.actionPending()) {
			form.control.markAllAsTouched();
			if (!this.actionPending()) {
				this.notifications.warning('Completa mascota, servicio y fecha antes de enviar la solicitud.');
			}
			return;
		}
		if (!this.scheduleDraft.petId || !this.scheduleDraft.serviceId || !this.scheduleDraft.requestedStartsAt) {
			this.error.set('Selecciona una mascota, un servicio y una fecha futura para enviar la solicitud.');
			this.notifications.warning('Selecciona una mascota, un servicio y una fecha futura para enviar la solicitud.');
			return;
		}
		const requestedStartsAt = new Date(this.scheduleDraft.requestedStartsAt);
		const dateMessage = this.scheduleDateValidationMessage();
		if (dateMessage) {
			this.error.set(dateMessage);
			this.notifications.warning(dateMessage);
			return;
		}
		this.error.set(null);
		this.notice.set(null);
		this.actionPending.set(true);
		this.scheduleApi.createClientScheduleRequest({
			petId: this.scheduleDraft.petId,
			serviceId: this.scheduleDraft.serviceId,
			requestedStartsAt: requestedStartsAt.toISOString(),
			observation: this.scheduleDraft.observation.trim() || null,
			idempotencyKey: crypto.randomUUID(),
		}).pipe(finalize(() => this.actionPending.set(false))).subscribe({
			next: (response) => {
				this.scheduleRequests.update((requests) => [...requests, response.data]
					.sort((left, right) => left.requestedStartsAt.localeCompare(right.requestedStartsAt)));
				Object.assign(this.scheduleDraft, { petId: '', serviceId: '', requestedStartsAt: '', observation: '' });
				this.activeSection.set('appointments');
				this.notice.set('Tu solicitud quedó registrada. Aún no reserva un cupo: recepción o administración confirmará la disponibilidad.');
			},
			error: () => this.error.set('No fue posible registrar la solicitud. Verifica que tu vinculación y la mascota sigan vigentes.'),
		});
	}

	onlineOrderDate(value: string): string { return new Intl.DateTimeFormat('es-PE',{dateStyle:'medium',timeStyle:'short',timeZone:'America/Lima'}).format(new Date(value)); }

	onlineOrderStatusLabel(status: OnlineOrder['status']): string {
		return ({ PENDIENTE: 'Pendiente de confirmación', EN_REVISION: 'En revisión', CAMBIO_PROPUESTO: 'Esperando tu decisión', CONFIRMADO: 'Confirmado', EN_PREPARACION: 'En preparación', LISTO_PARA_RECOJO: 'Listo para recojo', EN_REPARTO: 'En reparto', ENTREGADO: 'Entregado', CANCELACION_SOLICITADA: 'Cancelación solicitada', CANCELADO: 'Cancelado', DEVOLUCION_EN_PROCESO: 'Devolución en proceso', CERRADO_CON_DEVOLUCION: 'Cerrado con devolución' })[status];
	}

	onlinePaymentStatusLabel(status: OnlineOrder['paymentStatus']): string {
		return ({ NO_DECLARADO: 'Pago aún no declarado', DECLARADO: 'Pago en validación', VALIDADO: 'Pago validado', RECHAZADO: 'Pago rechazado' })[status];
	}

	onlineProposalStatusLabel(status: OnlineOrderProposal['status']): string {
		return ({ PROPUESTA: 'Esperando tu decisión', ACEPTADA: 'Aceptada', RECHAZADA: 'Rechazada', VENCIDA: 'Vencida' })[status];
	}

	decideOnlineProposal(proposal: OnlineOrderProposal, decision: 'ACEPTADA' | 'RECHAZADA'): void {
		if (this.actionPending() || proposal.status !== 'PROPUESTA') return;
		this.error.set(null); this.notice.set(null); this.actionPending.set(true);
		this.onlineOrdersApi.decideProposal(proposal.id, { operationId: crypto.randomUUID(), decision }).pipe(finalize(() => this.actionPending.set(false))).subscribe({
			next: ({ data }) => {
				this.onlineOrders.update((orders) => orders.map((item) => item.id === data.id ? data : item));
				this.notice.set(decision === 'ACEPTADA'
					? 'Aceptaste la propuesta. La veterinaria volverá a validar existencias antes de confirmar el pedido.'
					: 'Rechazaste la propuesta. El pedido continúa en revisión y el inventario no cambió.');
			},
			error: (response) => this.error.set(response?.error?.message ?? 'No fue posible registrar la decisión. Actualiza el pedido y vuelve a intentarlo.'),
		});
	}

	canCancelOnlineOrder(order: OnlineOrder): boolean {
		return ['PENDIENTE', 'CONFIRMADO', 'EN_PREPARACION', 'LISTO_PARA_RECOJO'].includes(order.status);
	}

	async cancelOnlineOrder(order: OnlineOrder): Promise<void> {
		if (!this.canCancelOnlineOrder(order) || this.actionPending()) return;
		const reason = await this.dialogs.askReason(order.status === 'PENDIENTE' ? 'Cancelar pedido' : 'Solicitar cancelación', 'Escribe el motivo para dejar constancia de tu decisión. Los pedidos confirmados requieren autorización del personal.', order.status === 'PENDIENTE' ? 'Cancelar pedido' : 'Enviar solicitud', order.status === 'PENDIENTE');
		if (!reason || reason.trim().length < 3) {
			this.notifications.warning('La cancelación requiere un motivo de al menos 3 caracteres.');
			return;
		}
		this.error.set(null); this.notice.set(null); this.actionPending.set(true);
		const request = { operationId: crypto.randomUUID(), reason: reason.trim() };
		const call = order.status === 'PENDIENTE'
			? this.onlineOrdersApi.cancelPending(order.id, request)
			: this.onlineOrdersApi.requestCancellation(order.id, request);
		call.pipe(finalize(() => this.actionPending.set(false))).subscribe({
			next: ({ data }) => {
				this.onlineOrders.update((orders) => orders.map((item) => item.id === data.id ? data : item));
				this.notice.set(data.status === 'CANCELADO'
					? 'El pedido pendiente fue cancelado. No se realizaron movimientos de inventario.'
					: 'Tu solicitud de cancelación quedó registrada para revisión de la veterinaria.');
			},
			error: (response) => this.error.set(response?.error?.message ?? 'No fue posible procesar la cancelación con el estado actual del pedido.'),
		});
	}

	beginOnlineReturn(order: OnlineOrder): void {
		if (order.status !== 'ENTREGADO' || this.actionPending()) return;
		this.returnOrder.set(order);
		setTimeout(() => document.getElementById('client-return-form')?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 30);
		this.returnDraft.reason = '';
		this.returnDraft.quantities = Object.fromEntries(this.returnableOrderLines(order).map((line) => [line.productId, 0]));
		this.error.set(null);
	}

	cancelOnlineReturn(): void {
		this.returnOrder.set(null);
		this.returnDraft.reason = '';
		this.returnDraft.quantities = {};
	}

	returnableOrderLines(order: OnlineOrder): OnlineOrderLine[] {
		const accepted = order.proposals.find((proposal) => proposal.status === 'ACEPTADA');
		return accepted?.proposedTerms.lines ?? order.lines;
	}

	submitOnlineReturn(): void {
		const order = this.returnOrder();
		const reason = this.returnDraft.reason.trim();
		if (!order || order.status !== 'ENTREGADO' || this.actionPending()) return;
		const lines = this.returnableOrderLines(order)
			.map((line) => ({ productId: line.productId, quantity: Number(this.returnDraft.quantities[line.productId] ?? 0) }))
			.filter((line) => line.quantity > 0);
		if (reason.length < 3 || !lines.length || lines.some((line) => !Number.isInteger(line.quantity))) {
			this.error.set('Describe el motivo y selecciona una o más cantidades enteras mayores que cero.');
			return;
		}
		this.error.set(null); this.notice.set(null); this.actionPending.set(true);
		this.onlineOrdersApi.requestReturn(order.id, { operationId: crypto.randomUUID(), reason, lines }).pipe(finalize(() => this.actionPending.set(false))).subscribe({
			next: ({ data }) => {
				this.onlineOrders.update((orders) => orders.map((item) => item.id === data.id ? data : item));
				this.cancelOnlineReturn();
				this.notice.set('Tu solicitud de devolución quedó registrada. La veterinaria revisará la recepción e inspección física.');
			},
			error: (response) => this.error.set(response?.error?.message ?? 'No fue posible registrar la solicitud de devolución.'),
		});
	}

	onlineReturnStatusLabel(status: NonNullable<OnlineOrder['returns']>[number]['status']): string {
		return ({ SOLICITADA: 'Solicitada', AUTORIZADA: 'Autorizada para recepción', RECHAZADA: 'Rechazada', RECIBIDA: 'Recibida para inspección', INSPECCIONADA_APTA: 'Inspeccionada: unidades aptas', INSPECCIONADA_NO_APTA: 'Inspeccionada: sin unidades aptas', CERRADA: 'Cerrada' })[status];
	}

	serviceName(serviceId: string): string {
		return this.scheduleServices().find((service) => service.id === serviceId)?.name ?? 'Servicio no disponible';
	}

	scheduleStateLabel(status: ScheduleRequest['status']): string {
		return ({
			PENDIENTE: 'Pendiente de confirmación',
			PROPUESTA: 'Alternativa propuesta',
			CONFIRMADA: 'Confirmada',
			REPROGRAMACION_PROPUESTA: 'Reprogramación propuesta',
			PENDIENTE_REPROGRAMACION: 'Pendiente de confirmar cambio',
			CANCELACION_SOLICITADA: 'Cancelación solicitada',
			CANCELADA: 'Cancelada',
			COMPLETADA: 'Completada',
			INASISTENCIA: 'Inasistencia',
		} as Record<string, string>)[status] ?? 'Estado no disponible';
	}

	formatScheduleDateTime(value: string): string {
		return new Intl.DateTimeFormat('es-PE', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'America/Lima' })
			.format(new Date(value));
	}

	cancelScheduleRequest(request: ScheduleRequest): void {
		if (this.actionPending() || !['PENDIENTE', 'PROPUESTA', 'CONFIRMADA', 'REPROGRAMACION_PROPUESTA', 'PENDIENTE_REPROGRAMACION'].includes(request.status)) {
			return;
		}
		this.error.set(null);
		this.notice.set(null);
		this.actionPending.set(true);
		this.scheduleApi.cancelClientScheduleRequest(request.id, { reason: null }).pipe(finalize(() => this.actionPending.set(false))).subscribe({
			next: (response) => {
				this.scheduleRequests.update((requests) => requests.map((item) => item.id === response.data.id ? response.data : item));
				this.notice.set(response.data.status === 'CANCELADA'
					? 'La solicitud pendiente fue cancelada y no ocupaba un cupo.'
					: 'La cancelacion fue enviada para revision. La cita confirmada sigue vigente hasta la decision de la veterinaria.');
			},
			error: () => this.error.set('No fue posible procesar la cancelacion con el estado actual de la solicitud.'),
		});
	}

	proposalFor(request: ScheduleRequest): ClientScheduleProposal | null {
		return this.scheduleProposals().find((proposal) => proposal.requestId === request.id) ?? null;
	}

	decideScheduleProposal(proposal: ClientScheduleProposal, decision: 'ACEPTADA' | 'RECHAZADA'): void {
		if (this.actionPending()) {
			return;
		}
		this.error.set(null);
		this.notice.set(null);
		this.actionPending.set(true);
		this.scheduleApi.decideClientScheduleProposal(proposal.id, { decision }).pipe(finalize(() => this.actionPending.set(false))).subscribe({
			next: (response) => {
				this.scheduleRequests.update((requests) => requests.map((item) => item.id === response.data.id ? response.data : item));
				this.scheduleProposals.update((proposals) => proposals.filter((item) => item.id !== proposal.id));
				this.notice.set(decision === 'RECHAZADA'
					? 'La alternativa fue rechazada. Tu cita confirmada se conserva o la solicitud vuelve a revision.'
					: response.data.status === 'PENDIENTE_REPROGRAMACION'
						? 'Aceptaste la alternativa. Recepcion debe revalidar la disponibilidad antes de cambiar la cita confirmada.'
						: 'Aceptaste la alternativa. La solicitud volvera a confirmarse con disponibilidad vigente.');
			},
			error: () => this.error.set('No fue posible registrar la decision. La propuesta pudo cambiar, vencer o no pertenecer a tu cuenta.'),
		});
	}

	petName(petId: string): string {
		return this.pets().find((pet) => pet.id === petId)?.name ?? 'Mascota no disponible';
	}

  logout(): void {
    const tenantSlug = this.auth.context()?.tenant?.slug ?? 'veterinaria-aurora';
    this.auth.logout().subscribe({
      next: () => void this.router.navigate(['/client/login', tenantSlug]),
      error: () => void this.router.navigate(['/client/login', tenantSlug]),
    });
  }

  private loadOwnerProfile(): void {
    this.administrationApi.getClientOwnerProfile().subscribe({
      next: (response) => this.applyOwnerProfile(response.data),
      error: () => this.error.set('La vinculacion fue aprobada, pero no fue posible cargar tu ficha basica.'),
    });
  }

  private applyOwnerProfile(owner: OwnerProfile): void {
    this.ownerProfile.set(owner);
    Object.assign(this.ownerDraft, {
      givenNames: owner.givenNames,
      familyNames: owner.familyNames,
      phone: owner.phone,
      email: owner.email,
      address: owner.address,
    });
  }

  private validPetRequest(draft: PetRequest): PetRequest | null {
    const ageMessage = this.petAgeValidationMessage(draft);
    if (ageMessage) {
      this.error.set(ageMessage);
      this.notifications.warning(ageMessage);
      return null;
    }
    return { ...draft, birthDate: draft.birthDate || null };
  }

  phoneValidationMessage(phone: string | null): string | null {
    if (!phone?.trim() || /^[0-9+() -]{5,40}$/.test(phone.trim())) {
      return null;
    }
    return 'El teléfono solo puede contener números, espacios, guiones, paréntesis o el signo +.';
  }

  private normalizedOwnerDraft(): OwnerRequest {
    return {
      givenNames: this.ownerDraft.givenNames.trim(),
      familyNames: this.ownerDraft.familyNames.trim(),
      phone: this.trimToNull(this.ownerDraft.phone),
      email: this.trimToNull(this.ownerDraft.email),
      address: this.trimToNull(this.ownerDraft.address),
    };
  }

  private trimToNull(value: string | null): string | null {
    const normalized = value?.trim() ?? '';
    return normalized || null;
  }

  petAgeValidationMessage(draft: PetRequest): string | null {
    const hasBirthDate = Boolean(draft.birthDate);
    const hasEstimatedAge = draft.estimatedAgeMonths !== null && String(draft.estimatedAgeMonths).trim().length > 0;
    if (hasBirthDate && hasEstimatedAge) {
      return 'Indica una fecha de nacimiento o una edad estimada, pero no ambas.';
    }
    if (!hasBirthDate && !hasEstimatedAge) {
      return 'Indica una fecha de nacimiento o una edad estimada para la mascota.';
    }
    const estimatedAge = Number(draft.estimatedAgeMonths);
    if (hasEstimatedAge && (!Number.isInteger(estimatedAge) || estimatedAge < 0 || estimatedAge > 480)) {
      return 'La edad estimada debe ser un número entero entre 0 y 480 meses.';
    }
    return null;
  }

  fieldInvalid(form: NgForm, controlName: string): boolean {
    const control = form.controls[controlName];
    return Boolean(control?.invalid && (form.submitted || control.touched));
  }

  petAgeInvalid(form: NgForm, draft: PetRequest): boolean {
    const hasInteracted = form.submitted
      || form.controls['newPetBirthDate']?.touched || form.controls['newPetEstimatedAge']?.touched
      || form.controls['editPetBirthDate']?.touched || form.controls['editPetEstimatedAge']?.touched;
    return Boolean(hasInteracted && this.petAgeValidationMessage(draft));
  }

  scheduleDateInvalid(form: NgForm): boolean {
    return Boolean(this.scheduleDateValidationMessage()
      && (form.submitted || form.controls['scheduleDateTime']?.touched));
  }

  scheduleDateValidationMessage(): string | null {
    const requestedStartsAt = new Date(this.scheduleDraft.requestedStartsAt);
    return Number.isNaN(requestedStartsAt.getTime()) || requestedStartsAt.getTime() <= Date.now()
      ? 'La solicitud debe proponer una fecha futura. La veterinaria confirmará manualmente la disponibilidad.'
      : null;
  }

  private emptyPetDraft(): PetRequest {
    return { name: '', species: '', breed: null, sex: 'DESCONOCIDO', birthDate: null, estimatedAgeMonths: null, distinguishingMarks: null };
  }
}
