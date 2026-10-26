import { toObservable } from '@angular/core/rxjs-interop';
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { filter, map, take } from 'rxjs';
import { SessionService } from './session.service';

/** Rutas con data { public: true } no piden sesión; con data { sinEstablecimiento: true } no piden establecimiento. */
export const sessionGuard: CanActivateFn = (route) => {
  const sesion = inject(SessionService);
  const router = inject(Router);
  const publica = route.data['public'] === true;
  const sinEstablecimiento = route.data['sinEstablecimiento'] === true;

  return toObservable(sesion.estado).pipe(
    filter((estado) => estado !== 'cargando'),
    take(1),
    map((estado) => {
      if (publica) return true;
      switch (estado) {
        case 'anonimo':
          return router.parseUrl('/login');
        case 'cambiar-contrasena':
          return route.routeConfig?.path === 'cambiar-contrasena' || router.parseUrl('/cambiar-contrasena');
        case 'sin-establecimiento':
          return sinEstablecimiento || router.parseUrl('/establecimiento');
        default:
          return true;
      }
    })
  );
};
