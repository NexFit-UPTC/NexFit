import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionService } from './session.service';

/** Solo el Master entra; va después de sessionGuard (en el padre), que ya resolvió la sesión. */
export const masterGuard: CanActivateFn = () => {
  const sesion = inject(SessionService);
  return sesion.esMaster() || inject(Router).parseUrl('/mi-perfil');
};
