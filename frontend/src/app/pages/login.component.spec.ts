import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { SessionService } from '../core/session.service';
import { LoginComponent } from './login.component';

describe('LoginComponent', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let http: HttpTestingController;
  let router: Router;
  let sesion: SessionService;

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    sesion = TestBed.inject(SessionService);
    sesion.estado.set('anonimo');
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();
  });
  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const entrada = (id: string) => el().querySelector<HTMLInputElement>('#' + id)!;

  function escribir(email: string, clave: string) {
    entrada('email').value = email;
    entrada('email').dispatchEvent(new Event('input'));
    entrada('password').value = clave;
    entrada('password').dispatchEvent(new Event('input'));
  }

  async function enviar() {
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('muestra el formulario con sus etiquetas', () => {
    expect(el().querySelector('h1')?.textContent).toContain('Iniciar sesión');
    expect(el().querySelector('label[for=email]')).toBeTruthy();
    expect(el().querySelector('label[for=password]')).toBeTruthy();
  });

  it('no llama a la API con el formulario vacío y marca los errores', async () => {
    await enviar();
    http.expectNone('/api/auth/login');
    expect(el().textContent).toContain('Escribe un correo válido.');
    expect(el().textContent).toContain('Escribe tu contraseña.');
  });

  it('envía correo y contraseña a POST /api/auth/login', async () => {
    escribir('ana@x.co', 'secreto');
    const hecho = enviar();
    const req = http.expectOne({ method: 'POST', url: '/api/auth/login' });
    expect(req.request.body).toEqual({ email: 'ana@x.co', password: 'secreto' });
    req.flush({ userId: 1, mustChangePassword: true, establishments: [] });
    await hecho;
    expect(router.navigateByUrl).toHaveBeenCalledWith('/cambiar-contrasena');
  });

  it('401: mensaje genérico, sin revelar si el correo existe, y limpia la contraseña', async () => {
    escribir('nadie@x.co', 'mala');
    const hecho = enviar();
    http.expectOne('/api/auth/login').flush({ message: 'Credenciales inválidas' }, { status: 401, statusText: '' });
    await hecho;
    const alerta = el().querySelector('[role=alert]')!.textContent!;
    expect(alerta).toContain('Correo o contraseña incorrectos');
    expect(alerta.toLowerCase()).not.toMatch(/no existe|no registrado|bloquead/);
    expect(entrada('password').value).toBe('');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('falla de red: mensaje de conexión', async () => {
    escribir('ana@x.co', 'secreto');
    const hecho = enviar();
    http.expectOne('/api/auth/login').error(new ProgressEvent('error'));
    await hecho;
    expect(el().querySelector('[role=alert]')?.textContent).toContain('No se pudo conectar');
  });

  it('el botón Mostrar alterna la visibilidad de la contraseña', () => {
    expect(entrada('password').type).toBe('password');
    el().querySelector<HTMLButtonElement>('button[aria-pressed]')!.click();
    fixture.detectChanges();
    expect(entrada('password').type).toBe('text');
  });

  it('quien ya tiene sesión es llevado fuera del login', () => {
    sesion.estado.set('activa');
    const otro = TestBed.createComponent(LoginComponent);
    otro.detectChanges();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/');
  });
});
