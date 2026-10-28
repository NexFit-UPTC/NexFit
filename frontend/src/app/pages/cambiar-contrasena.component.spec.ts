import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { SessionService } from '../core/session.service';
import { CambiarContrasenaComponent } from './cambiar-contrasena.component';

describe('CambiarContrasenaComponent', () => {
  let fixture: ComponentFixture<CambiarContrasenaComponent>;
  let http: HttpTestingController;
  let router: Router;
  let sesion: SessionService;

  function montar(estado: 'cambiar-contrasena' | 'activa') {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    sesion = TestBed.inject(SessionService);
    sesion.estado.set(estado);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(CambiarContrasenaComponent);
    fixture.detectChanges();
  }
  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const entrada = (id: string) => el().querySelector<HTMLInputElement>('#' + id)!;

  function escribir(actual: string, nueva: string, confirmar: string) {
    for (const [id, valor] of [['actual', actual], ['nueva', nueva], ['confirmar', confirmar]]) {
      entrada(id).value = valor;
      entrada(id).dispatchEvent(new Event('input'));
    }
  }

  async function enviar() {
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('con contraseña temporal: es obligatorio, sin enlace para volver', () => {
    montar('cambiar-contrasena');
    expect(el().querySelector('h1')?.textContent).toContain('temporal');
    expect(el().querySelector('a[href="/"]')).toBeNull();
    expect(el().textContent).toContain('Cerrar sesión');
  });

  it('voluntario: permite volver', () => {
    montar('activa');
    expect(el().querySelector('h1')?.textContent).toContain('Cambiar contraseña');
    expect(el().querySelector('a[href="/"]')).toBeTruthy();
  });

  it('exige mínimo 10 caracteres y no llama a la API', async () => {
    montar('cambiar-contrasena');
    escribir('temporal', 'corta', 'corta');
    await enviar();
    http.expectNone('/api/auth/password');
    expect(el().textContent).toContain('al menos 10 caracteres');
  });

  it('exige que la confirmación coincida', async () => {
    montar('cambiar-contrasena');
    escribir('temporal', 'NuevaClave-123', 'NuevaClave-124');
    await enviar();
    http.expectNone('/api/auth/password');
    expect(el().textContent).toContain('no coinciden');
  });

  it('con datos válidos hace POST /api/auth/password y sigue a /me', async () => {
    montar('cambiar-contrasena');
    escribir('temporal', 'NuevaClave-123', 'NuevaClave-123');
    const hecho = enviar();
    const req = http.expectOne({ method: 'POST', url: '/api/auth/password' });
    expect(req.request.body).toEqual({ currentPassword: 'temporal', newPassword: 'NuevaClave-123' });
    req.flush(null, { status: 204, statusText: '' });
    await Promise.resolve();
    http.expectOne('/api/me').flush({ identity: { firstName: 'A', lastName: 'B', email: 'a@x.co' }, affiliation: null });
    await hecho;
    expect(sesion.estado()).toBe('activa');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/');
  });

  it('muestra el mensaje del servidor cuando la actual es incorrecta (400)', async () => {
    montar('cambiar-contrasena');
    escribir('mala', 'NuevaClave-123', 'NuevaClave-123');
    const hecho = enviar();
    http
      .expectOne('/api/auth/password')
      .flush({ message: 'La contraseña actual no es correcta' }, { status: 400, statusText: '' });
    await hecho;
    expect(el().querySelector('[role=alert]')?.textContent).toContain('La contraseña actual no es correcta');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });
});
