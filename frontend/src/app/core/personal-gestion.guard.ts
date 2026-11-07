import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionService } from './session.service';

/** Staff o Administrador del establecimiento activo; va después de sessionGuard (en el padre). */
export const gestionGuard: CanActivateFn = () => {
  const roles = inject(SessionService).roles();
  return roles.includes('staff') || roles.includes('admin') || inject(Router).parseUrl('/mi-perfil');
};
