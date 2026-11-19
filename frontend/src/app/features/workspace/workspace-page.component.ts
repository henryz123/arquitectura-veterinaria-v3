import { NgFor, NgIf } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { finalize, forkJoin, of } from 'rxjs';
import { AuthStateService } from '../../core/auth/auth-state.service';
import { AdministrationApiService } from '../../core/api/administration-api.service';
import { GroomingTask, OnlineStaffOrder, ProfessionalAgendaItem, ScheduleRequest, SessionContext, StaffAccount, StaffRole, TenantProfile } from '../../core/api/api.models';
import { OnlineOrderApiService } from '../../core/api/online-order-api.service';
import { ScheduleApiService } from '../../core/api/schedule-api.service';
import { NotificationService } from '../../core/notifications/notification.service';

const STAFF_ROLE_OPTIONS: ReadonlyArray<{ code: StaffRole; label: string }> = [
  { code: 'ADMIN_VETERINARIA', label: 'Administración de veterinaria' },
  { code: 'VETERINARIO', label: 'Atención veterinaria' },
  { code: 'RECEPCION', label: 'Recepción' },
  { code: 'CAJERO', label: 'Caja' },
  { code: 'GROOMING', label: 'Grooming' },
];

type TenantScopedSession = SessionContext & { tenant: TenantProfile };
type OperationalLink = { label: string; detail: string; route: string; fragment?: WorkspaceSection };
type OperationalMetric = { label: string; value: number; detail: string };

@Component({
  selector: 'app-workspace-page',
  imports: [NgFor, NgIf, ReactiveFormsModule, RouterLink, RouterLinkActive],
  templateUrl: './workspace-page.component.html',
  styleUrl: './workspace-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WorkspacePageComponent implements OnInit {
  private readonly auth = inject(AuthStateService);
  private readonly administrationApi = inject(AdministrationApiService);
  private readonly scheduleApi = inject(ScheduleApiService);
  private readonly onlineOrderApi = inject(OnlineOrderApiService);
  private readonly formBuilder = inject(FormBuilder);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly notifications = inject(NotificationService);
  readonly profile = signal<TenantProfile | null>(null);
  readonly staff = signal<StaffAccount[]>([]);
  readonly scheduleRequests = signal<ScheduleRequest[]>([]);
  readonly ownVeterinarianAgenda = signal<ProfessionalAgendaItem[]>([]);
  readonly groomingTasks = signal<GroomingTask[]>([]);
  readonly commercialOrders = signal<OnlineStaffOrder[]>([]);
  readonly loading = signal(true);
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
  readonly actionPending = signal(false);
  readonly inviteSubmitted = signal(false);
  readonly rolesSubmitted = signal(false);
  readonly selectedStaff = signal<StaffAccount | null>(null);
  readonly pendingDeactivation = signal<StaffAccount | null>(null);
  readonly roleOptions = STAFF_ROLE_OPTIONS;
  readonly context = this.auth.context;
  readonly tenantSession = computed<TenantScopedSession | null>(() => {
    const context = this.context();
    return context?.tenant ? { ...context, tenant: context.tenant } : null;
  });
  readonly tenantCommercialName = computed(() => this.tenantSession()?.tenant.commercialName ?? '');
  readonly tenantStatus = computed(() => this.tenantSession()?.tenant.status ?? 'SUSPENDIDO');
  readonly canManageStaff = computed(() => {
    const context = this.context();
    return context?.accountType === 'STAFF' && context.roles.includes('ADMIN_VETERINARIA');
  });
  readonly canManageSchedule = computed(() => {
    const context = this.context();
    return context?.accountType === 'STAFF'
      && (context.roles.includes('ADMIN_VETERINARIA') || context.roles.includes('RECEPCION'));
  });
  readonly canUsePos = computed(() => {
    const context = this.context();
    return context?.accountType === 'STAFF' && context.roles.includes('CAJERO');
  });
  readonly canManageOnlineOrders = computed(() => {
    const context = this.context();
    return context?.accountType === 'STAFF'
      && (context.roles.includes('ADMIN_VETERINARIA') || context.roles.includes('CAJERO'));
  });
  readonly canUseCare = computed(() => {
    const context = this.context();
    return context?.accountType === 'STAFF'
      && (context.roles.includes('ADMIN_VETERINARIA') || context.roles.includes('VETERINARIO') || context.roles.includes('RECEPCION'));
  });
  readonly canUseGrooming = computed(() => this.context()?.accountType === 'STAFF' && this.context()?.roles.includes('GROOMING'));
  readonly canUseVeterinarianAgenda = computed(() => this.context()?.accountType === 'STAFF' && this.context()?.roles.includes('VETERINARIO'));
  readonly activeStaffCount = computed(() => this.staff().filter((member) => member.status === 'ACTIVA').length);
  readonly operationalLinks = computed<OperationalLink[]>(() => {
    const context = this.context();
    if (!context || context.accountType !== 'STAFF') {
      return [];
    }
    const links: OperationalLink[] = [];
    if (this.canUseCare()) {
      links.push({ label: 'Clientes y mascotas', detail: 'Consulta autorizada y atención clínica según tu rol.', route: '/care' });
    }
    if (this.canManageSchedule()) {
      links.push({ label: 'Citas y agenda', detail: 'Revisa solicitudes y disponibilidad de la veterinaria.', route: '/schedule' });
    }
    if (this.canUseVeterinarianAgenda()) {
      links.push({ label: 'Atención clínica', detail: 'Consulta pacientes y completa las atenciones permitidas.', route: '/care' });
    }
    if (this.canUseGrooming()) {
      links.push({ label: 'Peluquería y baño', detail: 'Consulta y actualiza únicamente tus tareas asignadas.', route: '/grooming' });
    }
    if (this.canManageStaff()) {
      links.push({ label: 'Productos e inventario', detail: 'Gestiona el catálogo y las existencias de esta veterinaria.', route: '/inventory' });
    }
    if (this.canUsePos()) {
      links.push({ label: 'Punto de venta', detail: 'Registra ventas presenciales autorizadas.', route: '/pos' });
    }
    if (this.canManageOnlineOrders()) {
      links.push({ label: 'Pedidos online', detail: 'Revisa los pedidos comerciales de esta veterinaria.', route: '/online-orders/management' });
    }
    if (this.canManageStaff()) {
      links.push({ label: 'Informe comercial', detail: 'Consulta el informe interno del tenant actual.', route: '/reports/commercial' });
      links.push({ label: 'Auditoría', detail: 'Consulta trazabilidad resumida de acciones autorizadas.', route: '/audit-trail' });
      links.push({ label: 'Equipo y roles', detail: 'Administra las cuentas laborales de esta veterinaria.', route: '/workspace', fragment: 'team' });
    }
    links.push({ label: 'Notificaciones', detail: 'Revisa únicamente los avisos dirigidos a tu cuenta.', route: '/notifications' });
    links.push({ label: 'Organización', detail: 'Consulta el contexto y estado de la veterinaria activa.', route: '/workspace', fragment: 'organization' });
    return links;
  });
  readonly operationalMetrics = computed<OperationalMetric[]>(() => {
    const metrics: OperationalMetric[] = [];
    if (this.canManageSchedule()) {
      metrics.push({ label: 'Solicitudes por gestionar', value: this.scheduleRequests().filter((item) =>
        ['PENDIENTE', 'PENDIENTE_REPROGRAMACION', 'CANCELACION_SOLICITADA'].includes(item.status)).length,
      detail: 'Agenda de esta veterinaria' });
    }
    if (this.canUseVeterinarianAgenda()) {
      metrics.push({ label: 'Pacientes asignados', value: this.ownVeterinarianAgenda().length,
        detail: 'Citas clínicas vigentes a tu cargo' });
    }
    if (this.canUseGrooming()) {
      metrics.push({ label: 'Tareas de grooming', value: this.groomingTasks().filter((item) => item.reservationStatus !== 'COMPLETADA').length,
        detail: 'Reservas vigentes a tu cargo' });
    }
    if (this.canManageOnlineOrders()) {
      metrics.push({ label: 'Pedidos por revisar', value: this.commercialOrders().filter((item) =>
        ['PENDIENTE', 'EN_REVISION', 'CAMBIO_PROPUESTO'].includes(item.status)).length,
      detail: 'Pedidos comerciales de esta veterinaria' });
    }
    return metrics;
  });
  readonly activeSection = signal<WorkspaceSection>('summary');
  readonly activeAdminTab = signal<'team' | 'invitation' | 'organization'>('team');
  readonly invitationForm = this.formBuilder.nonNullable.group({
    email: ['', { validators: [Validators.required, Validators.email], updateOn: 'blur' }],
    roles: this.formBuilder.nonNullable.control<StaffRole[]>(['RECEPCION'], Validators.minLength(1)),
  });
  readonly rolesForm = this.formBuilder.nonNullable.group({
    roles: this.formBuilder.nonNullable.control<StaffRole[]>(['RECEPCION']),
  });

  ngOnInit(): void {
    const context = this.context();
    if (!context || !context.tenant) {
      void this.router.navigateByUrl('/login/veterinaria-aurora');
      return;
    }
    forkJoin({
      profile: this.auth.loadOwnTenantProfile(context.tenant.slug),
      staff: this.canManageStaff() ? this.administrationApi.listStaff() : of(null),
      scheduleRequests: this.canManageSchedule() ? this.scheduleApi.listScheduleRequests() : of(null),
      ownVeterinarianAgenda: this.canUseVeterinarianAgenda() ? this.scheduleApi.listOwnVeterinarianAgenda() : of(null),
      groomingTasks: this.canUseGrooming() ? this.scheduleApi.listGroomingTasks() : of(null),
      commercialOrders: this.canManageOnlineOrders() ? this.onlineOrderApi.listManagement() : of(null),
    }).subscribe({
      next: ({ profile, staff, scheduleRequests, ownVeterinarianAgenda, groomingTasks, commercialOrders }) => {
        this.profile.set(profile.data);
        this.staff.set(staff?.data ?? []);
        this.scheduleRequests.set(scheduleRequests?.data ?? []);
        this.ownVeterinarianAgenda.set(ownVeterinarianAgenda?.data ?? []);
        this.groomingTasks.set(groomingTasks?.data ?? []);
        this.commercialOrders.set(commercialOrders?.data ?? []);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('No fue posible cargar el espacio autorizado. Actualiza la página e inténtalo nuevamente.');
        this.loading.set(false);
      },
    });

    this.route.fragment.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((fragment) => {
      const section = isWorkspaceSection(fragment) ? fragment : 'summary';
      this.activeSection.set(section);
      if (section !== 'summary') this.activeAdminTab.set(section);
      setTimeout(() => this.scrollToSection(section), 30);
    });
  }

  navigateToSection(section: WorkspaceSection): void {
    this.activeSection.set(section);
    if (section !== 'summary') this.activeAdminTab.set(section);
    setTimeout(() => this.scrollToSection(section), 30);
  }

  showAdminTab(section: 'team' | 'invitation' | 'organization'): void {
    this.activeAdminTab.set(section);
    this.activeSection.set(section);
    setTimeout(() => this.scrollToSection(section), 30);
  }

  hasInviteRole(role: StaffRole): boolean { return this.invitationForm.controls.roles.value.includes(role); }
  hasStaffRole(role: StaffRole): boolean { return this.rolesForm.controls.roles.value.includes(role); }
  toggleInviteRole(role: StaffRole, checked: boolean): void {
    const control = this.invitationForm.controls.roles;
    control.setValue(checked ? [...new Set([...control.value, role])] : control.value.filter((item) => item !== role));
    control.markAsTouched();
  }
  toggleStaffRole(role: StaffRole, checked: boolean): void {
    const control = this.rolesForm.controls.roles;
    control.setValue(checked ? [...new Set([...control.value, role])] : control.value.filter((item) => item !== role));
    control.markAsTouched();
  }

  inviteStaff(): void {
    this.inviteSubmitted.set(true);
    if (this.invitationForm.invalid || this.actionPending()) {
      this.invitationForm.markAllAsTouched();
      if (!this.actionPending()) {
        this.notifications.warning('Completa el correo y al menos un rol antes de registrar la invitación.');
      }
      return;
    }
    this.error.set(null);
    this.notice.set(null);
    this.actionPending.set(true);
    const { email, roles } = this.invitationForm.getRawValue();
    this.administrationApi.inviteStaff(email, roles).pipe(
      finalize(() => this.actionPending.set(false)),
    ).subscribe({
      next: () => {
        this.invitationForm.reset({ email: '', roles: ['RECEPCION'] });
        this.inviteSubmitted.set(false);
        this.notice.set('La invitación fue registrada. La cuenta conservará el estado pendiente hasta aceptar su enlace.');
        this.reloadStaff();
      },
      error: () => this.error.set('No fue posible registrar la invitación. Revisa los datos e inténtalo nuevamente.'),
    });
  }

  editRoles(member: StaffAccount): void {
    this.rolesSubmitted.set(false);
    this.selectedStaff.set(member);
    this.rolesForm.setValue({ roles: [...member.roles] });
    setTimeout(() => document.getElementById('staff-roles-editor')?.scrollIntoView({ behavior: 'smooth', block: 'center' }), 30);
  }

  cancelRoleEdit(): void {
    this.selectedStaff.set(null);
    this.rolesSubmitted.set(false);
  }

  saveRoles(): void {
    const member = this.selectedStaff();
    this.rolesSubmitted.set(true);
    if (!member || this.rolesForm.controls.roles.value.length === 0 || this.actionPending()) {
      this.rolesForm.markAllAsTouched();
      if (member && !this.actionPending()) {
        this.notifications.warning('Selecciona al menos un rol antes de guardar los cambios.');
      }
      return;
    }
    this.error.set(null);
    this.notice.set(null);
    this.actionPending.set(true);
    this.administrationApi.replaceStaffRoles(member.principalId, this.rolesForm.controls.roles.value).pipe(
      finalize(() => this.actionPending.set(false)),
    ).subscribe({
      next: (response) => {
        this.staff.update((members) => members.map((current) => current.principalId === member.principalId ? response.data : current));
        this.selectedStaff.set(null);
        this.rolesSubmitted.set(false);
        this.notice.set('Los roles de la cuenta se actualizaron. Las sesiones afectadas fueron gestionadas por el servidor.');
      },
      error: () => this.error.set('No fue posible actualizar los roles. La protección del último administrador puede haber rechazado la operación.'),
    });
  }

  requestDeactivation(member: StaffAccount): void {
    this.pendingDeactivation.set(member);
    setTimeout(() => document.getElementById('deactivation-title')?.scrollIntoView({ behavior: 'smooth', block: 'center' }), 30);
  }

  cancelDeactivation(): void {
    this.pendingDeactivation.set(null);
  }

  confirmDeactivation(): void {
    const member = this.pendingDeactivation();
    if (!member || this.actionPending()) {
      return;
    }
    this.error.set(null);
    this.notice.set(null);
    this.actionPending.set(true);
    this.administrationApi.deactivateStaff(member.principalId).pipe(
      finalize(() => this.actionPending.set(false)),
    ).subscribe({
      next: () => {
        this.pendingDeactivation.set(null);
        this.notice.set('La cuenta fue desactivada y sus accesos afectados se revocaron en el servidor.');
        this.reloadStaff();
      },
      error: () => this.error.set('No fue posible desactivar la cuenta. El servidor conserva la protección del último administrador.'),
    });
  }

  roleLabel(role: StaffRole): string {
    return STAFF_ROLE_OPTIONS.find((option) => option.code === role)?.label ?? role;
  }

  sessionRoleLabel(roles: string[]): string {
    if (roles.includes('ADMIN_VETERINARIA')) {
      return 'Administrador';
    }
    if (roles.includes('VETERINARIO')) {
      return 'Veterinario';
    }
    if (roles.includes('RECEPCION')) {
      return 'Recepción';
    }
    if (roles.includes('CAJERO')) {
      return 'Caja';
    }
    if (roles.includes('GROOMING')) {
      return 'Grooming';
    }
    return 'Personal autorizado';
  }

  statusLabel(status: StaffAccount['status'] | TenantProfile['status']): string {
    return status === 'ACTIVA' || status === 'ACTIVO' ? 'Activo' : status === 'INVITADA' ? 'Invitada' : 'Desactivado';
  }

  trackStaff(_: number, member: StaffAccount): string {
    return member.principalId;
  }

  trackAgenda(_: number, item: ProfessionalAgendaItem): string {
    return item.reservationId;
  }

  formatAgendaDate(value: string): string {
    return new Intl.DateTimeFormat('es-PE', {
      dateStyle: 'medium', timeStyle: 'short', timeZone: 'America/Lima',
    }).format(new Date(value));
  }

  invitationFieldInvalid(control: { invalid: boolean; touched: boolean }): boolean {
    return control.invalid && (this.inviteSubmitted() || control.touched);
  }

  private reloadStaff(): void {
    this.administrationApi.listStaff().subscribe({
      next: (response) => this.staff.set(response.data),
      error: () => this.error.set('La acción se realizó, pero no fue posible actualizar la lista. Recarga el espacio para confirmar el estado.'),
    });
  }

  logout(): void {
    this.auth.logout().subscribe({
      next: () => void this.router.navigateByUrl('/login/veterinaria-aurora'),
      error: () => void this.router.navigateByUrl('/login/veterinaria-aurora'),
    });
  }

  private scrollToSection(section: WorkspaceSection): void {
    const target = document.getElementById(section);
    if (typeof target?.scrollIntoView === 'function') {
      target.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }
}

type WorkspaceSection = 'summary' | 'team' | 'invitation' | 'organization';

function isWorkspaceSection(value: string | null): value is WorkspaceSection {
  return value === 'summary' || value === 'team' || value === 'invitation' || value === 'organization';
}
