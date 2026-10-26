import { ApplicationConfig, inject, provideAppInitializer } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { csrfInterceptor } from './core/csrf.interceptor';
import { errorInterceptor } from './core/error.interceptor';
import { SessionService } from './core/session.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes),
    provideHttpClient(withInterceptors([csrfInterceptor, errorInterceptor])),
    // Sin await: la app arranca sin bloquearse; los guards esperan a que el estado deje de ser 'cargando'.
    provideAppInitializer(() => {
      inject(SessionService).cargar();
    })
  ]
};
