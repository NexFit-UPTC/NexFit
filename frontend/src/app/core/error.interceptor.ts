import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { tap } from 'rxjs';
import { SessionService } from './session.service';

const MENSAJES: Record<string, string> = {
  ACCESS_DENIED: 'No tienes permiso para esta operación.',
  ACCESS_RULE_MISSING: 'Esta operación no tiene una regla de acceso definida. Avisa al equipo.',
  ESTABLISHMENT_WITHOUT_ADMIN: 'El establecimiento no tiene un Administrador asignado.',
  ESTABLISHMENT_REQUIRED: 'Elige el establecimiento con el que vas a operar.',
  PASSWORD_CHANGE_REQUIRED: 'Debes cambiar tu contraseña temporal antes de continuar.'
};

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const sesion = inject(SessionService);
  // /me lo consulta SessionService al arrancar: allí el guard decide, no el interceptor.
  const propia = req.url === '/api/me' && sesion.estado() === 'cargando';
  return next(req).pipe(
    tap({
      error: (e: HttpErrorResponse) => {
        if (propia) return;
        if (e.status === 401 && req.url !== '/api/auth/login') {
          sesion.limpiar();
          sesion.estado.set('anonimo');
          router.navigateByUrl('/login');
        } else if (e.status === 403) {
          const code: string | undefined = e.error?.code;
          sesion.mensaje.set((code && MENSAJES[code]) || 'No tienes permiso para realizar esta acción.');
          if (code === 'ESTABLISHMENT_REQUIRED') {
            sesion.estado.set('sin-establecimiento');
            router.navigateByUrl('/establecimiento');
          } else if (code === 'PASSWORD_CHANGE_REQUIRED') {
            sesion.estado.set('cambiar-contrasena');
            router.navigateByUrl('/cambiar-contrasena');
          }
        }
      }
    })
  );
};
