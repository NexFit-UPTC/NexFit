import { registerLocaleData } from '@angular/common';
import localeEsCo from '@angular/common/locales/es-CO';
import { ApplicationConfig, inject, LOCALE_ID, provideAppInitializer } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { csrfInterceptor } from './core/csrf.interceptor';
import { errorInterceptor } from './core/error.interceptor';
import { SessionService } from './core/session.service';

// Fechas y moneda en español de Colombia (COP sin decimales: $ 90.000).
registerLocaleData(localeEsCo);

export const appConfig: ApplicationConfig = {
  providers: [
    { provide: LOCALE_ID, useValue: 'es-CO' },
    provideRouter(routes),
    provideHttpClient(withInterceptors([csrfInterceptor, errorInterceptor])),
    // Sin await: la app arranca sin bloquearse; los guards esperan a que el estado deje de ser 'cargando'.
    provideAppInitializer(() => {
      inject(SessionService).cargar();
    })
  ]
};
