import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionService } from './session.service';

/** Solo el Staff del establecimiento activo entra; va después de sessionGuard (en el padre). */
export const staffGuard: CanActivateFn = () => {
  const sesion = inject(SessionService);
  return sesion.roles().includes('staff') || inject(Router).parseUrl('/mi-perfil');
};
