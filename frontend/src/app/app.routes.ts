import { Routes } from '@angular/router';
import { clientSessionGuard, clientTenantSessionGuard, saasSessionGuard, sessionGuard, tenantMemberSessionGuard } from './core/auth/session.guard';

export const routes: Routes = [
  { path: 'tienda/:tenantSlug/checkout', loadComponent: () => import('./features/storefront/storefront-checkout-page.component').then((m) => m.StorefrontCheckoutPageComponent), canActivate: [clientTenantSessionGuard], title: 'Checkout | Arquitectura Veterinaria' },
	{
		path: 'tienda/:tenantSlug',
		loadComponent: () => import('./features/storefront/storefront-page.component').then((module) => module.StorefrontPageComponent),
		title: 'Tienda | Arquitectura Veterinaria',
	},
  {
    path: 'seleccionar-veterinaria',
    loadComponent: () => import('./features/access/tenant-selector-page.component').then((module) => module.TenantSelectorPageComponent),
    title: 'Selecciona tu veterinaria | Arquitectura Veterinaria',
  },
  {
    path: 'login/:tenantSlug',
    loadComponent: () => import('./features/auth/login-page.component').then((module) => module.LoginPageComponent),
    data: { accountType: 'STAFF' },
    title: 'Acceso | Arquitectura Veterinaria',
  },
  {
    path: 'client/login/:tenantSlug',
    loadComponent: () => import('./features/auth/login-page.component').then((module) => module.LoginPageComponent),
    data: { accountType: 'CLIENT' },
    title: 'Acceso cliente | Arquitectura Veterinaria',
  },
  {
    path: 'client/register/:tenantSlug',
    loadComponent: () => import('./features/client/client-access-page.component').then((module) => module.ClientAccessPageComponent),
    data: { clientAccessMode: 'REGISTER' },
    title: 'Registro cliente | Arquitectura Veterinaria',
  },
  {
    path: 'client/verify-email',
    loadComponent: () => import('./features/client/client-access-page.component').then((module) => module.ClientAccessPageComponent),
    data: { clientAccessMode: 'VERIFY' },
    title: 'Verificar correo | Arquitectura Veterinaria',
  },
  {
    path: 'client/recover/:tenantSlug',
    loadComponent: () => import('./features/client/client-access-page.component').then((module) => module.ClientAccessPageComponent),
    data: { clientAccessMode: 'RECOVER' },
    title: 'Recuperar acceso | Arquitectura Veterinaria',
  },
  {
    path: 'client/reset-password',
    loadComponent: () => import('./features/client/client-access-page.component').then((module) => module.ClientAccessPageComponent),
    data: { clientAccessMode: 'RESET' },
    title: 'Nueva contraseña | Arquitectura Veterinaria',
  },
  {
    path: 'workspace',
    loadComponent: () => import('./features/workspace/workspace-page.component').then((module) => module.WorkspacePageComponent),
    canActivate: [sessionGuard],
    title: 'Espacio de trabajo | Arquitectura Veterinaria',
  },
  {
    path: 'care',
    loadComponent: () => import('./features/care/owner-pet-care-page.component').then((module) => module.OwnerPetCarePageComponent),
    canActivate: [sessionGuard],
    title: 'Propietarios y clínica | Arquitectura Veterinaria',
  },
  {
    path: 'schedule',
    loadComponent: () => import('./features/schedule/schedule-page.component').then((module) => module.SchedulePageComponent),
    canActivate: [sessionGuard],
    title: 'Agenda | Arquitectura Veterinaria',
  },
  {
    path: 'grooming',
    loadComponent: () => import('./features/grooming/grooming-page.component').then((module) => module.GroomingPageComponent),
    canActivate: [sessionGuard],
    title: 'Grooming | Arquitectura Veterinaria',
  },
  {
    path: 'inventory',
    loadComponent: () => import('./features/inventory/inventory-catalog-page.component').then((module) => module.InventoryCatalogPageComponent),
    canActivate: [sessionGuard],
    title: 'Inventario y catálogo | Arquitectura Veterinaria',
  },
  { path: 'online-orders/settings', loadComponent: () => import('./features/storefront/online-delivery-settings-page.component').then((m) => m.OnlineDeliverySettingsPageComponent), canActivate: [sessionGuard], title: 'Configuración de delivery | Arquitectura Veterinaria' },
  { path: 'online-orders/management', loadComponent: () => import('./features/storefront/online-order-management-page.component').then((m) => m.OnlineOrderManagementPageComponent), canActivate: [sessionGuard], title: 'Pedidos web | Arquitectura Veterinaria' },
  {
    path: 'pos',
    loadComponent: () => import('./features/pos/pos-page.component').then((module) => module.PosPageComponent),
    canActivate: [sessionGuard],
    title: 'Punto de venta | Arquitectura Veterinaria',
  },
  {
    path: 'reports/commercial',
    loadComponent: () => import('./features/reports/commercial-report-page.component').then((module) => module.CommercialReportPageComponent),
    canActivate: [sessionGuard],
    title: 'Informe comercial | Arquitectura Veterinaria',
  },
  {
    path: 'audit-trail',
    loadComponent: () => import('./features/audit/audit-trail-page.component').then((module) => module.AuditTrailPageComponent),
    canActivate: [sessionGuard],
    title: 'Auditoría | Arquitectura Veterinaria',
  },
  {
    path: 'notifications',
    loadComponent: () => import('./features/notifications/notifications-page.component').then((module) => module.NotificationsPageComponent),
    canActivate: [tenantMemberSessionGuard],
    title: 'Notificaciones | Arquitectura Veterinaria',
  },
  {
    path: 'saas/login',
    loadComponent: () => import('./features/saas/saas-login-page.component').then((module) => module.SaasLoginPageComponent),
    title: 'Administración SaaS | Arquitectura Veterinaria',
  },
  {
    path: 'saas/console',
    loadComponent: () => import('./features/saas/saas-console-page.component').then((module) => module.SaasConsolePageComponent),
    canActivate: [saasSessionGuard],
    title: 'Consola SaaS | Arquitectura Veterinaria',
  },
  {
    path: 'client-space',
    loadComponent: () => import('./features/client/client-space-page.component').then((module) => module.ClientSpacePageComponent),
    canActivate: [clientSessionGuard],
    title: 'Espacio cliente | Arquitectura Veterinaria',
  },
  { path: '', pathMatch: 'full', redirectTo: 'seleccionar-veterinaria' },
  { path: '**', redirectTo: '' },
];
