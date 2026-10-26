import { HttpBackend, HttpClient, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, of, switchMap } from 'rxjs';

const SEGURO = ['GET', 'HEAD', 'OPTIONS'];

function hayCookie(): boolean {
  return document.cookie.split('; ').some((c) => c.startsWith('XSRF-TOKEN='));
}

/** La cabecera X-XSRF-TOKEN la pone Angular; aquí solo se pide la cookie si aún no existe. */
export const csrfInterceptor: HttpInterceptorFn = (req, next) => {
  if (SEGURO.includes(req.method) || !req.url.startsWith('/api/') || hayCookie()) {
    return next(req);
  }
  // Health es público y siembra la cookie. HttpBackend evita los interceptores: sus errores no redirigen ni muestran banner.
  return new HttpClient(inject(HttpBackend))
    .get('/api/actuator/health')
    .pipe(
      catchError(() => of(null)),
      switchMap(() => next(req))
    );
};
