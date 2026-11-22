import { HttpBackend, HttpClient, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, of, switchMap } from 'rxjs';

const SEGURO = ['GET', 'HEAD', 'OPTIONS'];

const COOKIE = 'XSRF-TOKEN=';

function leerCookie(): string | null {
  const c = document.cookie.split('; ').find((c) => c.startsWith(COOKIE));
  return c ? decodeURIComponent(c.slice(COOKIE.length)) : null;
}

/**
 * Si la cookie ya existe, la cabecera X-XSRF-TOKEN la pone Angular. Si no existe, aquí se pide y, como el
 * interceptor de Angular corre antes que este y ya no puede leerla, la cabecera se agrega aquí.
 */
export const csrfInterceptor: HttpInterceptorFn = (req, next) => {
  if (SEGURO.includes(req.method) || !req.url.startsWith('/api/') || leerCookie()) {
    return next(req);
  }
  // Health es público y siembra la cookie. HttpBackend evita los interceptores: sus errores no redirigen ni muestran banner.
  return new HttpClient(inject(HttpBackend))
    .get('/api/actuator/health')
    .pipe(
      catchError(() => of(null)),
      switchMap(() => {
        const token = leerCookie();
        return next(token ? req.clone({ setHeaders: { 'X-XSRF-TOKEN': token } }) : req);
      })
    );
};
