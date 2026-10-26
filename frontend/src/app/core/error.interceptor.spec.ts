import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { errorInterceptor } from './error.interceptor';
import { SessionService } from './session.service';

describe('errorInterceptor', () => {
  let http: HttpClient;
  let ctrl: HttpTestingController;
  let router: Router;
  let sesion: SessionService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([errorInterceptor])), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    sesion = TestBed.inject(SessionService);
    sesion.estado.set('activa');
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  });

  function fallar(url: string, status: number, body: object | null = null) {
    http.get(url).subscribe({ error: () => undefined });
    ctrl.expectOne(url).flush(body, { status, statusText: '' });
  }

  it('401 → /login', () => {
    fallar('/api/sedes', 401);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
    expect(sesion.estado()).toBe('anonimo');
  });

  it('401 de /auth/login no redirige', () => {
    fallar('/api/auth/login', 401, { message: 'Credenciales inválidas' });
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('401 de la carga inicial de /me no navega', () => {
    sesion.estado.set('cargando');
    fallar('/api/me', 401);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it.each(['ACCESS_DENIED', 'ACCESS_RULE_MISSING', 'ESTABLISHMENT_WITHOUT_ADMIN'])(
    '403 %s → mensaje, sin redirección',
    (code) => {
      fallar('/api/sedes', 403, { code });
      expect(sesion.mensaje()).toBeTruthy();
      expect(router.navigateByUrl).not.toHaveBeenCalled();
    }
  );

  it('403 ESTABLISHMENT_REQUIRED → /establecimiento', () => {
    fallar('/api/sedes', 403, { code: 'ESTABLISHMENT_REQUIRED' });
    expect(router.navigateByUrl).toHaveBeenCalledWith('/establecimiento');
  });

  it('403 PASSWORD_CHANGE_REQUIRED → /cambiar-contrasena', () => {
    fallar('/api/sedes', 403, { code: 'PASSWORD_CHANGE_REQUIRED' });
    expect(router.navigateByUrl).toHaveBeenCalledWith('/cambiar-contrasena');
  });

  it('403 sin code (p. ej. CSRF) → mensaje genérico', () => {
    fallar('/api/sedes', 403, { error: 'Forbidden' });
    expect(sesion.mensaje()).toBeTruthy();
  });
});
