import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionService } from './session.service';

/** El panel de indicadores es del Administrador y del Master; va después de sessionGuard (en el padre). */
export const panelGuard: CanActivateFn = () => {
  const sesion = inject(SessionService);
  return sesion.esMaster() || sesion.roles().includes('admin') || inject(Router).parseUrl('/mi-perfil');
};
