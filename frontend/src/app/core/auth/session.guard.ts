import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AuthStateService } from './auth-state.service';

export const sessionGuard: CanActivateFn = () => {
  const auth = inject(AuthStateService);
  const router = inject(Router);
  const isAuthorized = () => {
    const context = auth.context();
    return context?.accountType === 'STAFF' && context.tenant !== null;
  };
  const staffLogin = () => router.createUrlTree(['/login', auth.context()?.tenant?.slug ?? 'veterinaria-aurora']);
  const expiredStaffLogin = () => router.createUrlTree(
    ['/login', auth.context()?.tenant?.slug ?? 'veterinaria-aurora'],
    { queryParams: { reason: 'session-expired' } },
  );
  if (auth.context()) {
    return isAuthorized() ? true : staffLogin();
  }
  return auth.restoreContext().pipe(
    map(() => isAuthorized() ? true : staffLogin()),
    catchError(() => {
      auth.clear();
      return of(expiredStaffLogin());
    }),
  );
};

export const saasSessionGuard: CanActivateFn = () => {
  const auth = inject(AuthStateService);
  const router = inject(Router);
  const isAuthorized = () => {
    const context = auth.context();
    return context?.accountType === 'SAAS' && context.roles.includes('ADMIN_SAAS');
  };
  if (auth.context()) {
    return isAuthorized() ? true : router.createUrlTree(['/saas/login']);
  }
  return auth.restoreContext().pipe(
    map(() => isAuthorized() ? true : router.createUrlTree(['/saas/login'])),
    catchError(() => {
      auth.clear();
      return of(router.createUrlTree(['/saas/login'], { queryParams: { reason: 'session-expired' } }));
    }),
  );
};

export const clientSessionGuard: CanActivateFn = () => {
  const auth = inject(AuthStateService);
  const router = inject(Router);
  const isAuthorized = () => auth.context()?.accountType === 'CLIENT';
  if (auth.context()) {
    return isAuthorized() ? true : router.createUrlTree(['/client/login/veterinaria-aurora']);
  }
  return auth.restoreContext().pipe(
    map(() => isAuthorized() ? true : router.createUrlTree(['/client/login/veterinaria-aurora'])),
    catchError(() => {
      auth.clear();
      return of(router.createUrlTree(['/client/login/veterinaria-aurora'], { queryParams: { reason: 'session-expired' } }));
    }),
  );
};

/** Allows only an authenticated tenant member; the API still authorizes each B06 operation. */
export const tenantMemberSessionGuard: CanActivateFn = () => {
  const auth = inject(AuthStateService);
  const router = inject(Router);
  const permitted = () => {
    const context = auth.context();
    return context?.tenant !== null && (context?.accountType === 'STAFF' || context?.accountType === 'CLIENT');
  };
  const login = () => auth.context()?.accountType === 'CLIENT'
    ? router.createUrlTree(['/client/login', auth.context()?.tenant?.slug ?? 'veterinaria-aurora'])
    : router.createUrlTree(['/login', auth.context()?.tenant?.slug ?? 'veterinaria-aurora']);
  if (auth.context()) return permitted() ? true : login();
  return auth.restoreContext().pipe(
    map(() => permitted() ? true : login()),
    catchError(() => { auth.clear(); return of(login()); }),
  );
};

/** Stops a CLIENT from resuming a cart for a different veterinary tenant. */
export const clientTenantSessionGuard: CanActivateFn = (route: ActivatedRouteSnapshot) => {
  const auth = inject(AuthStateService); const router = inject(Router);
  const slug = route.paramMap.get('tenantSlug') ?? 'veterinaria-aurora';
  const login = () => router.createUrlTree(['/client/login', slug], { queryParams: { returnTo: `/tienda/${slug}/checkout` } });
  const permitted = () => auth.context()?.accountType === 'CLIENT' && auth.context()?.tenant?.slug === slug;
  if (auth.context()) return permitted() ? true : login();
  return auth.restoreContext().pipe(map(() => permitted() ? true : login()), catchError(() => { auth.clear(); return of(login()); }));
};
