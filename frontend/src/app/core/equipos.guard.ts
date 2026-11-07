import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionService } from './session.service';

/** El equipo lo consultan el Administrador y el Staff; va después de sessionGuard (en el padre). */
export const equiposGuard: CanActivateFn = () => {
  const roles = inject(SessionService).roles();
  return roles.includes('admin') || roles.includes('staff') || inject(Router).parseUrl('/mi-perfil');
};
