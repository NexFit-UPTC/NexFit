import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionService } from './session.service';

/** Solo el Administrador del establecimiento activo entra; va después de sessionGuard (en el padre). */
export const adminGuard: CanActivateFn = () => {
  const sesion = inject(SessionService);
  return sesion.roles().includes('admin') || inject(Router).parseUrl('/mi-perfil');
};
