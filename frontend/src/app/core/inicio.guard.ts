import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { destinoPorRol } from './destino-por-rol';
import { SessionService } from './session.service';

/**
 * Guard de «/»: quien ya tiene sesión va a su pantalla; el resto ve la página pública.
 * No espera a /api/me: si la sesión llega después, la propia página redirige.
 */
export const inicioGuard: CanActivateFn = () => {
  const sesion = inject(SessionService);
  const router = inject(Router);
  switch (sesion.estado()) {
    case 'activa':
      return router.parseUrl('/' + destinoPorRol(sesion));
    case 'sin-establecimiento':
      return router.parseUrl('/establecimiento');
    case 'cambiar-contrasena':
      return router.parseUrl('/cambiar-contrasena');
    default:
      return true;
  }
};
