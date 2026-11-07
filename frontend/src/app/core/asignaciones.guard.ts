import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionService } from './session.service';

/** Asignar alumnos es del Administrador y el Staff; va después de sessionGuard (en el padre). */
export const asignacionesGuard: CanActivateFn = () => {
  const roles = inject(SessionService).roles();
  return roles.includes('admin') || roles.includes('staff') || inject(Router).parseUrl('/mi-perfil');
};

/** «Mis alumnos» es solo del Entrenador. */
export const entrenadorGuard: CanActivateFn = () =>
  inject(SessionService).roles().includes('trainer') || inject(Router).parseUrl('/mi-perfil');
